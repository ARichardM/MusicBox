"""Resolución resistente de audio de YouTube: clientes alternativos y validación del enlace."""

from __future__ import annotations

import urllib.request
from pathlib import Path

# Mismo orden que usa la app móvil.
CLIENT_SETS = ("android_vr,android", "web,android", "ios,web")
AUDIO_FORMAT = ("bestaudio[ext=m4a][protocol^=http]/bestaudio[protocol^=http]/"
                "best[ext=mp4][protocol^=http]/best[protocol^=http]")


def _options(client: str | None, **extra) -> dict:
    options = {"quiet": True, "no_warnings": True, "noplaylist": True, "socket_timeout": 12,
               "retries": 2, "extractor_retries": 2, **extra}
    if client:
        options["extractor_args"] = {"youtube": {"player_client": client.split(",")}}
    return options


def stream_is_reachable(url: str, headers: dict | None, opener=urllib.request.urlopen) -> bool:
    """Pide el primer byte con Range, como lo haría el reproductor."""
    request = urllib.request.Request(url, headers={**(headers or {}), "Range": "bytes=0-1"})
    try:
        with opener(request, timeout=7) as response:
            return getattr(response, "status", 200) in (200, 206)
    except Exception:
        return False


def resolve_stream(url: str, extractor, opener=urllib.request.urlopen) -> dict:
    """Devuelve la información de yt-dlp con un enlace validado.

    ``extractor(options, url)`` debe devolver el diccionario de información. Se prueba cada
    cliente hasta encontrar uno cuyo enlace responda; si ninguno responde se relanza el último
    error o se indica que no hubo enlace reproducible.
    """
    last_error: Exception | None = None
    for client in CLIENT_SETS:
        try:
            info = extractor(_options(client, format=AUDIO_FORMAT), url)
            stream = (info or {}).get("url", "")
            if stream and stream_is_reachable(stream, info.get("http_headers"), opener):
                return info
        except Exception as exc:  # cada cliente puede fallar de forma distinta
            last_error = exc
    if last_error:
        raise last_error
    raise RuntimeError("YouTube no devolvió un enlace reproducible")


def search(query: str, extractor, count: int = 12) -> list:
    """Busca videos reintentando con otros clientes si el primero falla o no devuelve nada."""
    last_error: Exception | None = None
    for client in (None, "web,android", "ios,web"):
        try:
            data = extractor(_options(client, extract_flat=True, skip_download=True, socket_timeout=8,
                                      playlistend=count), f"ytsearch{count}:{query}")
            entries = [entry for entry in (data or {}).get("entries") or [] if entry]
            if entries:
                return entries
        except Exception as exc:
            last_error = exc
    if last_error:
        raise last_error
    return []


def related(video_id: str, extractor, count: int = 15) -> list:
    """Canciones relacionadas: la lista Mix de YouTube (``RD<id>``) que arranca en ese video."""
    last_error: Exception | None = None
    url = f"https://www.youtube.com/watch?v={video_id}&list=RD{video_id}"
    for client in (None, "web,android", "ios,web"):
        try:
            data = extractor(_options(client, noplaylist=False, extract_flat=True, skip_download=True,
                                      socket_timeout=8, playlistend=count + 1), url)
            entries = [entry for entry in (data or {}).get("entries") or []
                       if entry and entry.get("id") != video_id]
            if entries:
                return entries[:count]
        except Exception as exc:
            last_error = exc
    if last_error:
        raise last_error
    return []


def download_to_cache(url: str, folder: Path, extractor_download) -> str:
    """Último recurso: descarga el audio completo y devuelve la ruta del archivo local."""
    folder.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for client in CLIENT_SETS:
        try:
            options = _options(client, format="bestaudio[ext=m4a]/bestaudio/best",
                               outtmpl=str(folder / "%(id)s.%(ext)s"), overwrites=False)
            return extractor_download(options, url)
        except Exception as exc:
            last_error = exc
    raise last_error or RuntimeError("No se pudo descargar el audio")
