"""Importar una carpeta de audio: leer etiquetas, preparar la búsqueda y decidir qué coincidencias son seguras.

Sin Qt: solo lógica pura que el controlador usa y las pruebas verifican.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re

from canciones import MAX_QUERIES, clean_queries
from coincidencias import best_candidate

AUDIO_EXTENSIONS = {".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma"}
MAX_FILES = 500
_VIDEO_ID = re.compile(r"(?:[?&]v=|youtu\.be/)([A-Za-z0-9_-]{6,20})")
_TRACK_PREFIX = re.compile(r"^\s*(?:\d{1,3}\s*[-–.)_]\s*|\(\d{1,3}\)\s*)")


@dataclass
class LocalSong:
    path: str
    title: str
    artist: str
    duration: int
    query: str


def _first(value):
    if isinstance(value, (list, tuple)):
        value = value[0] if value else ""
    return str(value or "").strip()


def read_tags(path):
    """(título, artista, duración en segundos) de las etiquetas; vacíos si no se pueden leer."""
    try:
        from mutagen import File
        audio = File(path, easy=False)
        if audio is None:
            return "", "", 0
        duration = round(getattr(getattr(audio, "info", None), "length", 0) or 0)
        tags = audio.tags or {}

        def tag(*names):
            for name in names:
                value = tags.get(name)
                if value is not None:
                    return _first(getattr(value, "text", value))
            return ""
        return tag("TIT2", "title", "\xa9nam"), tag("TPE1", "artist", "\xa9ART"), duration
    except Exception:
        return "", "", 0


def title_from_filename(stem):
    """«03 - Artista - Canción_en_vivo» → «Artista - Canción en vivo»."""
    text = _TRACK_PREFIX.sub("", str(stem).replace("_", " "))
    return " ".join(text.split())


def split_name(name):
    """«Artista - Canción» → (canción, artista); sin separador, todo es el título."""
    parts = re.split(r"\s+[-–—]\s+", name, maxsplit=1)
    return (parts[1], parts[0]) if len(parts) == 2 and all(p.strip() for p in parts) else (name, "")


def build_query(title, artist):
    """Consulta de búsqueda «artista - título», sin repetir el artista si ya está en el título."""
    title, artist = " ".join(title.split()), " ".join(artist.split())
    if artist and artist.casefold() not in title.casefold():
        return f"{artist} - {title}"
    return title


def describe_file(path):
    path = Path(path)
    title, artist, duration = read_tags(path)
    if not title:
        title, artist = split_name(title_from_filename(path.stem))
    return LocalSong(str(path), title, artist, duration, build_query(title, artist))


def scan_folder(folder, *, recursive=True, limit=MAX_FILES, cancelled=None):
    """Canciones de audio de una carpeta (orden alfabético). Devuelve (canciones, hubo_más_del_límite)."""
    root = Path(folder)
    if not root.is_dir():
        raise ValueError("La carpeta no existe o no se puede abrir.")
    pattern = root.rglob("*") if recursive else root.glob("*")
    files = []
    for candidate in pattern:
        if cancelled is not None and cancelled():
            break
        try:
            if candidate.is_file() and candidate.suffix.lower() in AUDIO_EXTENSIONS:
                files.append(candidate)
        except OSError:
            continue
    files.sort(key=lambda item: str(item).casefold())
    truncated = len(files) > limit
    songs = []
    for file in files[:limit]:
        if cancelled is not None and cancelled():
            break
        songs.append(describe_file(file))
    return songs, truncated


def video_id(url):
    match = _VIDEO_ID.search(str(url or ""))
    return match.group(1) if match else ""


def decide(song, candidates):
    """Evalúa los candidatos de una canción.

    Devuelve un dict con ``status`` («auto», «review» o «none»), el índice elegido, el motivo y
    el ID de YouTube. «auto» solo si el puntaje lo considera seguro; si no, queda para revisar.
    """
    usable = [c for c in candidates if video_id(c.get("url"))]
    if not usable:
        return dict(status="none", index=-1, reason="Sin resultados utilizables.", id="")
    found = best_candidate(song.query, usable, title=song.title, artist=song.artist,
                           duration=song.duration or "")
    if found is None:
        return dict(status="none", index=-1, reason="Sin resultados utilizables.", id="")
    position, match = found
    chosen = usable[position]
    return dict(status="auto" if match.auto_select else "review",
                index=candidates.index(chosen), reason=match.review_reason,
                id=video_id(chosen.get("url")), exact=match.exact, score=round(match.score, 3))


def queries_for(songs):
    """Consultas únicas (ya limpias, igual que las deja el buscador) y qué canciones comparte cada una."""
    order, groups = [], {}
    for index, song in enumerate(songs):
        cleaned = clean_queries(song.query, enforce_limit=False)
        if not cleaned:
            continue
        key = cleaned[0].casefold()
        if key not in groups:
            groups[key] = []
            order.append(cleaned[0])
        groups[key].append(index)
    if len(order) > MAX_QUERIES:
        raise ValueError(f"Importa hasta {MAX_QUERIES} canciones por carpeta.")
    return order, [groups[query.casefold()] for query in order]
