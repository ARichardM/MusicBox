"""Imágenes, OCR local y búsqueda de canciones. Sin dependencias de Tkinter."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import unicodedata

from PIL import Image, ImageGrab, ImageOps

import descargador_gui as backend
from lectura_listas import organize_playlist_text, playlist_crop_bounds, playlist_lines_from_ocr
from informe_canciones import playlist_url, inspect_playlist, error_reason


MAX_IMAGES = 20
MAX_TOTAL_PIXELS = 40_000_000
MAX_QUERIES = 500


@dataclass
class Attachment:
    name: str
    original: Image.Image
    image: Image.Image

    @classmethod
    def from_image(cls, image, name):
        if image.width * image.height > 25_000_000:
            raise ValueError("La imagen es demasiado grande. Usa una imagen de hasta 25 megapíxeles.")
        oriented = ImageOps.exif_transpose(image)
        rgba = oriented.convert("RGBA")
        background = Image.new("RGBA", rgba.size, "white")
        background.alpha_composite(rgba)
        original = background.convert("RGB")
        return cls(name, original, original.copy())

    @classmethod
    def from_path(cls, path):
        with Image.open(path) as image:
            return cls.from_image(image, Path(path).name)

    def crop(self, box):
        left, top, right, bottom = box
        if not (0 <= left < right <= self.image.width and 0 <= top < bottom <= self.image.height):
            raise ValueError("El recorte debe estar dentro de la imagen.")
        cropped = self.image.crop(box)
        self.image.close()
        self.image = cropped

    def restore(self):
        self.image.close()
        self.image = self.original.copy()

    def close(self):
        self.image.close()
        self.original.close()


def selection_to_box(start, end, displayed, image_size):
    """Transforma un arrastre sobre la vista escalada en píxeles de la imagen."""
    x, y, width, height = displayed
    if width <= 0 or height <= 0:
        raise ValueError("Selecciona una imagen primero.")
    image_width, image_height = image_size
    x1, x2 = sorted((max(0, min(width, start[0] - x)), max(0, min(width, end[0] - x))))
    y1, y2 = sorted((max(0, min(height, start[1] - y)), max(0, min(height, end[1] - y))))
    box = (round(x1 * image_width / width), round(y1 * image_height / height),
           round(x2 * image_width / width), round(y2 * image_height / height))
    if box[2] - box[0] < 8 or box[3] - box[1] < 8:
        raise ValueError("Arrastra sobre la imagen para seleccionar un área más grande.")
    return box


def clean_queries(text, *, enforce_limit=True):
    queries, seen = [], set()
    for line in text.splitlines():
        line = re.sub(r"^\s*(?:[•●▪]\s*|\d{1,3}[.)]\s+)", "", line)
        duration = re.search(r"\s+\d{1,2}:\d{2}(?::\d{2})?\s*$", line)
        if duration and not line[:duration.start()].rstrip().endswith(("-", "–", "—", ":")):
            line = line[:duration.start()]
        line = " ".join(line.split()).strip(" \t|•")
        if not line or not any(char.isalnum() for char in line):
            continue
        if line.casefold() not in seen:
            queries.append(line)
            seen.add(line.casefold())
    if enforce_limit and len(queries) > MAX_QUERIES:
        raise ValueError(f"Busca hasta {MAX_QUERIES} canciones por tanda.")
    return queries


def text_from_ocr(result, image_size=None, *, uploaders=None):
    return playlist_lines_from_ocr(result, image_size, uploaders=uploaders)


def make_ocr_engine():
    from rapidocr import RapidOCR

    return RapidOCR(params={
        "Global.log_level": "error",
        "EngineConfig.onnxruntime.intra_op_num_threads": 2,
        "EngineConfig.onnxruntime.inter_op_num_threads": 1,
    })


def search_query_variants(query_text):
    """Hasta tres consultas del mismo nombre; conservar siempre el original primero."""
    original = " ".join(query_text.split())
    normalized = unicodedata.normalize("NFKC", original)

    def words(value):
        return " ".join(re.sub(r"[^\w\s]", " ", value).replace("_", " ").split())

    parts = [part.strip() for part in re.split(r"\s+[-–—]\s*", normalized) if part.strip()]
    alternatives = []
    if len(parts) >= 3:
        # «SPYAIR - サクラミツツキ - Sakuramitsutsuki»: probar ambos nombres con el artista.
        alternatives.extend((words(parts[0] + " " + parts[-1]),
                             words(parts[0] + " " + " ".join(parts[1:-1]))))
    else:
        segments = re.split(r"[|｜ㅣ]", normalized)
        if len(segments) > 1:
            main_title = words(" ".join(segments[1:]))
            if len(main_title) >= 6:
                alternatives.append(main_title)
        alternatives.append(words(normalized))
    variants, seen = [original], {original.casefold()}
    for alternative in alternatives:
        if alternative and alternative.casefold() not in seen:
            variants.append(alternative)
            seen.add(alternative.casefold())
    return variants[:3]


def usable_search_results(entries, *, include_thumbnail=False):
    candidates = []
    skipped = 0
    for item in entries:
        if not isinstance(item, dict) or item.get("is_live") or item.get("live_status") in ("is_live", "is_upcoming"):
            skipped += 1
            continue
        video_id = item.get("id")
        if (not video_id or not item.get("title") or item.get("availability") in ("private", "needs_auth", "premium_only", "subscriber_only")
                or item.get("title", "").casefold() in ("[deleted video]", "[private video]")):
            skipped += 1
            continue
        candidates.append(dict(title=item.get("title") or video_id,
                               channel=item.get("channel") or item.get("uploader") or "",
                               duration=item.get("duration"),
                               url=f"https://www.youtube.com/watch?v={video_id}"))
        if include_thumbnail:
            candidates[-1]["thumbnail"] = (f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg"
                                           if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", video_id) else "")
    return candidates, skipped


def search_candidates(query_text, downloader, diagnostics=None, *, limit=3, include_thumbnail=False):
    if type(limit) is not int or not 1 <= limit <= 20:
        raise ValueError("La búsqueda admite entre 1 y 20 resultados.")
    attempts = []
    widened = False
    for query in search_query_variants(query_text):
        result_limit = limit
        while True:
            info = downloader.extract_info(f"ytsearch{result_limit}:{query}", download=False)
            if not isinstance(info, dict):
                raise ValueError("YouTube no devolvió datos de búsqueda; la consulta no se pudo verificar.")
            entries = list(info.get("entries") or [])
            candidates, skipped = usable_search_results(entries, include_thumbnail=include_thumbnail)
            attempts.append(dict(query=query, limit=result_limit, received=len(entries), skipped=skipped))
            if diagnostics is not None:
                diagnostics.update(search_attempts=list(attempts), matched_query=query if candidates else "")
            if candidates:
                if diagnostics is not None:
                    diagnostics["reason"] = ""
                return candidates[:limit]
            # Si los primeros resultados eran directos o entradas inaccesibles, mirar más
            # resultados una sola vez antes de declarar la consulta vacía.
            if skipped and not widened and result_limit < 20:
                result_limit = min(20, max(10, limit * 2))
                widened = True
                continue
            break
    if diagnostics is not None:
        skipped = max((attempt["skipped"] for attempt in attempts), default=0)
        diagnostics["reason"] = (
            f"La búsqueda automática no devolvió resultados utilizables tras {len(attempts)} consultas. "
            "Esto no confirma que la canción no exista en YouTube."
            + (f" Se descartaron hasta {skipped} resultados por consulta por falta de datos, acceso restringido o emisiones en directo/próximas."
               if skipped else ""))
    return []


def run_import_worker(config):
    def emit(kind, **values):
        print(json.dumps(dict(event=kind, **values), ensure_ascii=True), flush=True)

    errors = 0
    try:
        if config["task"] == "ocr":
            emit("import_status", text="Preparando el reconocimiento de texto…")
            engine = make_ocr_engine()
            sources = config["sources"]
            for index, source in enumerate(sources, 1):
                emit("import_status", text=f"Leyendo imagen {index} de {len(sources)}…")
                try:
                    with Image.open(source["path"]) as image:
                        working = image.convert("RGB")
                        try:
                            result = engine(working)
                            raw_text = "\n".join(getattr(result, "txts", None) or [])
                            bounds = playlist_crop_bounds(result, working.size)
                            if bounds is not None:
                                cropped = working.crop(bounds)
                                working.close()
                                working = cropped
                                result = engine(working)
                            uploaders = {}
                            lines = text_from_ocr(result, working.size, uploaders=uploaders)
                        finally:
                            working.close()
                    emit("ocr_result", lines=lines, uploaders=uploaders, raw_text=raw_text,
                         name=source["name"], index=index, total=len(sources))
                except Exception as exc:
                    errors += 1
                    emit("import_error", text=f"{source['name']}: {exc}")
        elif config["task"] in ("search", "playlist", "browse"):
            import yt_dlp

            class Logger:
                def __init__(self): self.messages, self.errors = [], []
                def debug(self, _): pass
                def warning(self, message): self.messages.append(str(message))
                def error(self, message):
                    self.messages.append(str(message))
                    self.errors.append(str(message))

            logger = Logger()
            options = dict(extract_flat=True, skip_download=True, quiet=True, logger=logger,
                           socket_timeout=15, retries=1, extractor_retries=1,
                           js_runtimes={name: {} for name in ("deno", "node") if shutil.which(name)})
            if config.get("cookies"):
                options["cookiefile"] = config["cookies"]
            browsing = config["task"] == "browse"
            if browsing:
                query = " ".join(config.get("query", "").split())
                if not query or len(query) > 300:
                    raise ValueError("Escribe entre 1 y 300 caracteres para buscar videos.")
                queries = [query]
            else:
                queries = clean_queries("\n".join(config.get("queries", [])))
            with yt_dlp.YoutubeDL(options) as downloader:
                if config.get("playlist_url"):
                    emit("import_status", text="Comprobando las entradas de la playlist original…")
                    try:
                        source_url = playlist_url(config["playlist_url"])
                        report = inspect_playlist(downloader.extract_info(source_url, download=False))
                        emit("playlist_report", report=report, url=source_url)
                    except Exception as exc:
                        errors += 1
                        emit("playlist_error", text=error_reason(exc))
                for position, query_text in enumerate(queries):
                    index = config.get("query_indices", list(range(len(queries))))[position]
                    emit("import_status", text=f"Buscando {position + 1} de {len(queries)}: {query_text}", query_index=index)
                    logger.messages.clear()
                    logger.errors.clear()
                    try:
                        diagnostics = {}
                        candidates = (search_candidates(query_text, downloader, diagnostics, limit=10, include_thumbnail=True)
                                      if browsing else search_candidates(query_text, downloader, diagnostics))
                        if not candidates and logger.errors:
                            raise ValueError(" | ".join(logger.errors))
                        if not candidates and logger.messages:
                            diagnostics["reason"] += " Avisos de YouTube: " + " | ".join(logger.messages)
                        emit("search_result", index=index, query=query_text, candidates=candidates, total=len(queries),
                             progress_index=position + 1, reason=diagnostics.get("reason", ""), warnings=logger.messages,
                             search_attempts=diagnostics.get("search_attempts", []), matched_query=diagnostics.get("matched_query", ""))
                    except Exception as exc:
                        errors += 1
                        reason = error_reason(exc)
                        emit("import_error", text=f"{query_text}: {reason}", query_index=index)
                        emit("search_result", index=index, query=query_text, candidates=[], total=len(queries), failed=True,
                             progress_index=position + 1, reason=reason, warnings=logger.messages,
                             search_attempts=diagnostics.get("search_attempts", []))
        else:
            raise ValueError("Operación desconocida.")
    except Exception as exc:
        errors += 1
        emit("import_error", text=str(exc))
    emit("import_summary", errors=errors)
    return int(bool(errors))


class ImportJob:
    """Puente cancelable hacia un proceso de OCR/búsqueda; no guarda objetos Tk."""

    def __init__(self):
        self.events = queue.Queue()
        self.cancelled = threading.Event()
        self.process = None

    def cancel(self):
        self.cancelled.set()
        process = self.process
        if process is not None:
            backend.stop_process(process)

    def run(self, config, images=()):
        try:
            with tempfile.TemporaryDirectory(prefix="musica_ocr_") as directory:
                config = dict(config)
                if images:
                    config["sources"] = []
                    for index, (name, image) in enumerate(images):
                        if self.cancelled.is_set():
                            return
                        path = Path(directory) / f"imagen_{index}.png"
                        image.save(path)
                        config["sources"].append(dict(name=name, path=str(path)))
                if self.cancelled.is_set():
                    return
                flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
                self.process = subprocess.Popen(backend.worker_command("--songs-worker"),
                                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                                encoding="utf-8", errors="replace",
                                                env={**os.environ, "PYTHONIOENCODING": "utf-8"}, **flags)
                self.process.stdin.write(json.dumps(config) + "\n")
                self.process.stdin.close()
                if self.cancelled.is_set():
                    backend.stop_process(self.process)
                summary = False
                diagnostic = ""
                for line in self.process.stdout:
                    try:
                        event = json.loads(line)
                        if not isinstance(event, dict) or "event" not in event:
                            raise ValueError("Evento inválido")
                    except ValueError:
                        diagnostic = line.strip()
                        continue
                    summary |= event["event"] == "import_summary"
                    self.events.put(event)
                self.process.wait()
                if not summary and not self.cancelled.is_set():
                    self.events.put(dict(event="import_error", text="El proceso se cerró antes de terminar. " + diagnostic[:500]))
        except Exception as exc:
            if not self.cancelled.is_set():
                self.events.put(dict(event="import_error", text=str(exc)))
        finally:
            if self.process is not None:
                if self.process.poll() is None:
                    backend.stop_process(self.process)
                    self.process.wait()
                self.process.stdout.close()
            self.process = None
            for _, image in images:
                image.close()
            self.events.put(dict(event="job_done", cancelled=self.cancelled.is_set()))


class LoadImagesJob:
    """Decodifica archivos y el portapapeles sin bloquear la ventana."""

    def __init__(self):
        self.events = queue.Queue()
        self.cancelled = threading.Event()

    def cancel(self):
        self.cancelled.set()

    def run(self, paths=(), *, clipboard=False, slots=MAX_IMAGES, used_pixels=0):
        clipboard_image = None
        try:
            if slots <= 0:
                raise ValueError(f"Puedes adjuntar hasta {MAX_IMAGES} imágenes por tanda.")
            if clipboard:
                content = ImageGrab.grabclipboard()
                if isinstance(content, Image.Image):
                    clipboard_image = content
                    sources = [("Portapapeles", content)]
                elif isinstance(content, list):
                    sources = [(Path(path).name, path) for path in content]
                else:
                    raise ValueError("Copia una imagen primero. Para nombres escritos, pega en el cuadro de texto.")
            else:
                sources = [(Path(path).name, path) for path in paths]
            loaded = 0
            for index, (name, source) in enumerate(sources, 1):
                if self.cancelled.is_set():
                    break
                if loaded >= slots:
                    raise ValueError(f"Puedes adjuntar hasta {MAX_IMAGES} imágenes por tanda.")
                try:
                    def convert(image):
                        if used_pixels + image.width * image.height > MAX_TOTAL_PIXELS:
                            raise ValueError("Las imágenes superan 40 megapíxeles en total. Quita alguna y vuelve a adjuntar.")
                        return Attachment.from_image(image, name)

                    if isinstance(source, Image.Image):
                        attachment = convert(source)
                    else:
                        with Image.open(source) as image:
                            attachment = convert(image)
                    if self.cancelled.is_set():
                        attachment.close()
                        break
                    used_pixels += attachment.original.width * attachment.original.height
                    loaded += 1
                    self.events.put(dict(event="attachment_result", attachment=attachment,
                                         index=index, total=len(sources)))
                except (OSError, ValueError, Image.DecompressionBombError) as exc:
                    self.events.put(dict(event="import_error", text=f"No se pudo abrir {name}: {exc}"))
        except Exception as exc:
            self.events.put(dict(event="import_error", text=str(exc)))
        finally:
            if clipboard_image is not None:
                clipboard_image.close()
            self.events.put(dict(event="job_done", cancelled=self.cancelled.is_set()))
