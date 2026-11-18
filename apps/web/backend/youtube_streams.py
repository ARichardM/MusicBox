"""Resolución resistente de audio de YouTube: clientes alternativos y validación del enlace."""

from __future__ import annotations

import urllib.error
import urllib.request
import tempfile
import uuid
from pathlib import Path

# «android» solo va primero: medido, tarda ~2 s y su enlace funciona, mientras que «android_vr,android» elige el formato
# de android_vr, que falla la validación y añade ~2,3 s en cada canción. Los demás quedan como respaldo.
CLIENT_SETS = ("android", "web,android", "android_vr,android", "ios,web")
AUDIO_FORMAT = ("bestaudio[ext=m4a][protocol^=http]/bestaudio[protocol^=http]/"
                "best[ext=mp4][protocol^=http]/best[protocol^=http]")
# Video: un solo archivo con imagen y sonido, porque el reproductor no puede unir pistas separadas.
VIDEO_FORMAT = ("best[ext=mp4][height<=720][protocol^=http]/best[ext=mp4][protocol^=http]/"
                "best[protocol^=http]")


def _options(client: str | None, **extra) -> dict:
    options = {"quiet": True, "no_warnings": True, "noplaylist": True, "socket_timeout": 12,
               "retries": 2, "extractor_retries": 2, **extra}
    if client:
        options["extractor_args"] = {"youtube": {"player_client": client.split(",")}}
    return options


# Algunos enlaces (p. ej. android_vr) responden al inicio del archivo pero dan 403 a cualquier rango más allá de ~1 MB.
FAR_PROBE_OFFSET = 1_500_000


def _range_responds(url: str, headers: dict | None, first: int, opener) -> bool:
    request = urllib.request.Request(url, headers={**(headers or {}), "Range": f"bytes={first}-{first + 1}"})
    try:
        with opener(request, timeout=7) as response:
            return getattr(response, "status", 200) in (200, 206)
    except urllib.error.HTTPError as exc:
        return exc.code == 416 and first > 0  # el archivo es más corto que la prueba: no hay nada que rechazar
    except Exception:
        return False


def stream_is_reachable(url: str, headers: dict | None, opener=urllib.request.urlopen, size: int | None = None) -> bool:
    """Pide el primer byte con Range, como lo haría el reproductor, y un tramo lejano para descartar enlaces truncados."""
    if not _range_responds(url, headers, 0, opener):
        return False
    far = size - 2 if size and size > 2 else FAR_PROBE_OFFSET  # con tamaño conocido se prueba el final del archivo
    return far <= 0 or _range_responds(url, headers, far, opener)


# El cliente que funcionó la última vez se prueba primero: así no se repite en cada canción el que suele fallar
# (p. ej. android_vr), que añadía unos 2 segundos antes de empezar a sonar.
_preferred_client: str | None = None


def _client_order() -> tuple:
    if _preferred_client in CLIENT_SETS:
        return (_preferred_client, *(client for client in CLIENT_SETS if client != _preferred_client))
    return CLIENT_SETS



def resolve_stream(url: str, extractor, opener=urllib.request.urlopen, video: bool = False) -> dict:
    """Devuelve la información de yt-dlp con un enlace validado.

    ``extractor(options, url)`` debe devolver el diccionario de información. Se prueba cada
    cliente hasta encontrar uno cuyo enlace responda; si ninguno responde se relanza el último
    error o se indica que no hubo enlace reproducible.
    """
    last_error: Exception | None = None
    for client in _client_order():
        try:
            info = extractor(_options(client, format=VIDEO_FORMAT if video else AUDIO_FORMAT), url)
            stream = (info or {}).get("url", "")
            if video and stream and (info.get("vcodec") == "none" or info.get("acodec") == "none"):
                continue
            if stream and stream_is_reachable(stream, info.get("http_headers"), opener,
                                              info.get("filesize") or info.get("filesize_approx")):
                global _preferred_client
                _preferred_client = client
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
            # Never reuse a truncated fallback or race another playback of this video.
            # Publish a complete, private cache file only after the download succeeds.
            with tempfile.TemporaryDirectory(prefix="stream-", dir=folder) as temporary:
                options = _options(client, format="bestaudio[ext=m4a]/bestaudio/best",
                                   outtmpl=str(Path(temporary) / "%(id)s.%(ext)s"),
                                   overwrites=True, continuedl=False)
                downloaded = Path(extractor_download(options, url))
                if not downloaded.is_file() or downloaded.stat().st_size == 0:
                    raise RuntimeError("No se pudo descargar el audio")
                target = folder / (uuid.uuid4().hex + downloaded.suffix)
                downloaded.replace(target)
                return str(target)
        except Exception as exc:
            last_error = exc
    raise last_error or RuntimeError("No se pudo descargar el audio")


def resolve_or_cache(url: str, extractor, extractor_download, folder: Path, opener=urllib.request.urlopen):
    """Primero el enlace directo (rápido); solo si ninguna herramienta lo logra, descarga el audio.

    Devuelve ``("url", info)`` o ``("file", ruta)``. Así el oyente nunca se queda sin música aunque yt-dlp no consiga un
    enlace reproducible, y mientras todo funciona nada de esto se usa.
    """
    try:
        return "url", resolve_stream(url, extractor, opener)
    except Exception:
        return "file", download_to_cache(url, folder, extractor_download)
