"""Descargador MP3/MP4 con Tkinter. Ejecutar con iniciar_descargador.bat."""

from __future__ import annotations

import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit


from app_paths import CODE_DIR, DATA_ROOT, RESOURCE_ROOT, worker_command

APP_DIR = DATA_ROOT
APP_VERSION = "2.3.7"
AUDIO_QUALITY = ("192 kbps", "128 kbps", "256 kbps", "320 kbps")
VIDEO_QUALITY = ("1080p", "720p", "480p", "360p", "Mejor disponible")


def parse_urls(text: str, playlist: bool) -> list[str]:
    urls = list(dict.fromkeys(line.strip() for line in text.splitlines() if line.strip()))
    if not urls:
        raise ValueError("Pega al menos un enlace, uno por línea.")
    for url in urls:
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError(f"El enlace debe comenzar por https:// o http://:\n{url}")
        host = parts.hostname.lower().removeprefix("www.").removeprefix("m.")
        if not playlist and host in ("youtube.com", "music.youtube.com"):
            if parts.path.rstrip("/") == "/playlist":
                raise ValueError("Este enlace es una playlist. Selecciona «Playlist completa / rango».")
    return urls


def validate_items(items: str) -> str:
    items = items.replace(" ", "")
    if items and not re.fullmatch(r"[1-9]\d*(?:-[1-9]\d*)?(?:,[1-9]\d*(?:-[1-9]\d*)?)*", items):
        raise ValueError("El rango debe tener este formato: 1-10 o 1,3,5-8.")
    for item in items.split(",") if items else []:
        if "-" in item:
            start, end = map(int, item.split("-"))
            if start > end:
                raise ValueError("El inicio del rango no puede ser mayor que el final.")
    return items


def find_ffmpeg() -> str | None:
    filename = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    candidates = [Path(sys.executable).resolve().parent / filename, RESOURCE_ROOT / filename, APP_DIR / filename]
    return next((str(path) for path in candidates if path.is_file()), None) or shutil.which("ffmpeg")


def build_output_template(config: dict, queue_position: int = 1) -> str:
    destination = Path(config["destination"])
    if config["playlist"]:
        return str(destination / "%(playlist_title|Sin playlist).100B"
                   / "%(playlist_index|0)03d - %(title).140B [%(id)s].%(ext)s")
    prefix = (f"{queue_position:03d} - "
              if not config["playlist"] and len(config.get("urls", [])) > 1 else "")
    return str(destination / (prefix + "%(title).150B [%(id)s].%(ext)s"))


def build_options(config: dict) -> dict:
    """Opciones compartidas por el proceso de descarga y las pruebas."""
    opts = {
        "outtmpl": build_output_template(config),
        "ffmpeg_location": config["ffmpeg"],
        "noplaylist": not config["playlist"],
        "ignoreerrors": True,
        "continuedl": True,
        "overwrites": False,
        "final_ext": config["format"].lower(),
        "windowsfilenames": True,
        "quiet": True,
        "noprogress": True,
        "nocolor": True,
        "socket_timeout": 20,
        "retries": 3,
        "fragment_retries": 3,
        "js_runtimes": {name: {} for name in ("deno", "node") if shutil.which(name)},
    }
    if config["playlist"]:
        if config.get("items"):
            opts["playlist_items"] = validate_items(config["items"])
    if config.get("cookies"):
        opts["cookiefile"] = config["cookies"]
    if config["format"] == "MP3":
        opts["format"] = "bestaudio/best"
        # La imagen se usa como portada incrustada y se elimina al terminar.
        # No descargar portadas adicionales de la playlist.
        opts["writethumbnail"] = True
        opts["outtmpl"] = {"default": opts["outtmpl"], "pl_thumbnail": ""}
        opts["postprocessors"] = [{
            "key": "FFmpegExtractAudio", "preferredcodec": "mp3",
            "preferredquality": config["quality"].split()[0],
        }, {
            "key": "FFmpegMetadata", "add_metadata": True,
        }, {
            "key": "EmbedThumbnail", "already_have_thumbnail": False,
        }]
    else:
        height = config["quality"].removesuffix("p")
        cap = "" if height == "Mejor disponible" else f"[height<=?{int(height)}]"
        opts["format"] = f"bestvideo[ext=mp4]{cap}+bestaudio[ext=m4a]/best[ext=mp4]{cap}"
        opts["merge_output_format"] = "mp4"
        opts["postprocessors"] = [{"key": "FFmpegVideoRemuxer", "preferedformat": "mp4"}]
    return opts


def create_downloader(options: dict, on_existing=None):
    """Conserva los archivos finales antes de descargar imágenes o retocar etiquetas."""
    import yt_dlp

    downloader = yt_dlp.YoutubeDL(options)

    def preserve_existing(info, *, incomplete=False):
        if incomplete or options.get("overwrites") is not False:
            return None
        filename = downloader.prepare_filename({**info, "ext": options["final_ext"]})
        if filename and Path(filename).is_file():
            if on_existing:
                on_existing(info)
            downloader.report_file_already_downloaded(filename)
            for hook in options.get("post_hooks", []):
                hook(filename)
            return "Se conserva el archivo terminado, incluida su portada y sus etiquetas."
        return None

    downloader.params["match_filter"] = preserve_existing
    return downloader


def run_worker(config: dict) -> int:
    """Solo este proceso importa yt-dlp. La GUI recibe eventos JSON por stdout."""
    def emit(event: str, **values):
        print(json.dumps({"event": event, **values}, ensure_ascii=True), flush=True)

    try:
        import yt_dlp
    except ImportError:
        emit("log", level="error", text="Falta yt-dlp. Ejecuta iniciar_descargador.bat para instalarlo.")
        return 1

    completed = 0
    errors = 0
    last_progress = 0.0
    current_info = {}
    current_url = ""
    existing_file = False

    class Logger:
        def __init__(self):
            self.warnings = set()

        def debug(self, message):
            if not message.startswith("[debug]"):
                emit("log", level="info", text=message)

        def warning(self, message):
            if message not in self.warnings:
                self.warnings.add(message)
                emit("log", level="warning", text=message)

        def error(self, message):
            nonlocal errors
            # yt-dlp también envía avisos de obsolescencia a logger.error.
            if message.startswith("Deprecated Feature:"):
                self.warning(message)
                return
            errors += 1
            emit("log", level="error", text=message)

    def progress(data):
        nonlocal last_progress
        now = time.monotonic()
        if data["status"] == "downloading" and now - last_progress < 0.15:
            return
        last_progress = now
        info = data.get("info_dict") or {}
        emit("progress", status=data["status"], title=info.get("title", "Descargando…"),
             downloaded=data.get("downloaded_bytes", 0),
             total=data.get("total_bytes") or data.get("total_bytes_estimate"),
             speed=data.get("speed"), eta=data.get("eta"),
             index=info.get("playlist_index"), count=info.get("n_entries") or info.get("playlist_count"))

    def postprocess(data):
        nonlocal current_info, existing_file
        current_info = data.get("info_dict") or {}
        existing_file = False
        if data["status"] == "started":
            processor = data.get("postprocessor", "")
            message = {
                "Metadata": "Guardando título, artista y metadatos disponibles…",
                "EmbedThumbnail": "Incrustando la portada en la canción…",
            }.get(processor, "Convirtiendo / uniendo audio y video…")
            emit("processing", text=message)

    def mark_existing(info):
        nonlocal current_info, existing_file
        current_info, existing_file = info, True

    def ready(filename):
        nonlocal completed
        artist = current_info.get("artist") or current_info.get("artists") or ""
        if isinstance(artist, (list, tuple)):
            artist = ", ".join(map(str, artist))
        entry = dict(filename=filename,
                     title=current_info.get("track") or current_info.get("title") or Path(filename).stem,
                     artist=artist, source_url=current_info.get("webpage_url") or current_url,
                     format=Path(filename).suffix.lstrip(".").upper(), quality=config["quality"],
                     state="existing" if existing_file else "downloaded")
        if config.get("history_path"):
            try:
                from historial import DownloadHistory

                DownloadHistory(config["history_path"]).record(entry)
            except Exception as exc:
                emit("log", level="warning", text=f"El archivo está listo, pero no se pudo guardar el historial: {exc}")
        completed += 1
        emit("ready", filename=filename, completed=completed, history=entry)

    try:
        options = build_options(config)
        options.update(logger=Logger(), progress_hooks=[progress],
                       postprocessor_hooks=[postprocess], post_hooks=[ready])
        # Reutilizar la sesión, las cookies y los extractores durante toda la cola.
        with create_downloader(options, on_existing=mark_existing) as downloader:
            for index, url in enumerate(config["urls"], 1):
                current_url, current_info, existing_file = url, {}, False
                downloader.params["outtmpl"]["default"] = build_output_template(config, index)
                emit("link", index=index, count=len(config["urls"]))
                downloader.download([url])
    except Exception as exc:
        errors += 1
        emit("log", level="error", text=str(exc))
    emit("summary", completed=completed, errors=errors)
    return 1 if errors else 0


def stop_process(process: subprocess.Popen):
    """Detiene únicamente el trabajador y sus conversores hijos."""
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=subprocess.CREATE_NO_WINDOW, check=False)
    else:
        import signal
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass


def human_bytes(value) -> str:
    value = float(value or 0)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}"
        value /= 1024


def launch_gui(*, history_path=None):
    from interfaz import DownloaderApp

    if os.name == "nt":
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Descargador.Desktop")
    return DownloaderApp(history_path=history_path)

def run_cli() -> int:
    if "--audio-worker" in sys.argv:
        from reproductor_audio import run_audio

        return run_audio(json.loads(sys.stdin.readline()))
    elif "--player-worker" in sys.argv:
        from reproductor_video import run_player

        return run_player(json.loads(sys.stdin.readline()))
    elif "--songs-worker" in sys.argv:
        from canciones import run_import_worker

        return run_import_worker(json.loads(sys.stdin.readline()))
    elif "--worker" in sys.argv:
        try:
            worker_config = json.loads(sys.stdin.readline())
            return run_worker(worker_config)
        except (ValueError, KeyError) as exc:
            print(json.dumps(dict(event="log", level="error", text=f"Configuración inválida: {exc}")), flush=True)
            return 1
    else:
        launch_gui().mainloop()
        return 0


if __name__ == "__main__":
    raise SystemExit(run_cli())
