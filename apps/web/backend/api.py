"""MusicBox Web API - Backend FastAPI para MusicBox Web."""

from __future__ import annotations

import asyncio
from collections import OrderedDict
import logging
import os
import re
import secrets
import tempfile
from pathlib import Path
import shutil
import time
from typing import AsyncGenerator
from typing import Annotated, Literal
from urllib.parse import urlparse, parse_qs

from fastapi import FastAPI, HTTPException, Request, Response, UploadFile, File, Query, Path as ApiPath
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask
from fastapi.staticfiles import StaticFiles
import httpx
import yt_dlp

import lyrics
import youtube_streams
import ytmusic
import playlist_import
import artwork
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("musicbox.web")

app = FastAPI(title="MusicBox Web API", version="2.4.5")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Ruta del archivo de cookies opcional
COOKIE_PATH = Path(os.environ.get('MUSICBOX_COOKIE_PATH', str(Path(__file__).resolve().parent / 'cookies.txt')))
VideoId = Annotated[str, ApiPath(pattern=r'^[A-Za-z0-9_-]{11}$')]

# Caché en memoria para stream URLs (15 minutos de TTL)
STREAM_CACHE: dict[str, tuple[float, str, dict]] = {}
STREAM_CACHE_TTL = 900  # 15 min
DOWNLOAD_SLOTS = asyncio.Semaphore(2)


def remove_download_directory(directory: Path):
    resolved = directory.resolve()
    if resolved.parent != Path(tempfile.gettempdir()).resolve() or not resolved.name.startswith('musicbox-download-'):
        raise ValueError('Unexpected temporary download directory')
    shutil.rmtree(resolved, ignore_errors=True)

# Caché para búsquedas y recomendaciones
SEARCH_CACHE: OrderedDict[str, tuple[float, list]] = OrderedDict()
SEARCH_CACHE_MAX = 64
SEARCH_CACHE_TTL = 300  # 5 min


def _extract_info(options: dict, url: str) -> dict:
    opts = dict(options)
    if COOKIE_PATH.is_file() and COOKIE_PATH.stat().st_size > 0:
        opts["cookiefile"] = str(COOKIE_PATH)
    js_node = shutil.which("node") or shutil.which("nodejs")
    if js_node:
        opts["js_runtimes"] = {"node": {"path": js_node}}
    with yt_dlp.YoutubeDL(opts) as ydl:
        return ydl.extract_info(url, download=False)


def prepare_download(video_id: str, fmt: str, quality: int) -> tuple[Path, Path]:
    directory = Path(tempfile.mkdtemp(prefix='musicbox-download-'))
    options = {'outtmpl':str(directory / '%(id)s.%(ext)s'), 'quiet':True, 'noplaylist':True,
               'socket_timeout':20, 'retries':1, 'max_filesize':150 * 1024 * 1024,
               'postprocessor_args':{'ffmpeg':['-threads','1']}}
    if fmt == 'mp3':
        options.update(format='bestaudio/best', postprocessors=[{'key':'FFmpegExtractAudio','preferredcodec':'mp3','preferredquality':str(quality)}])
    else:
        options.update(format=f'bestvideo[ext=mp4][height<={quality}]+bestaudio[ext=m4a]/best[ext=mp4][height<={quality}]', merge_output_format='mp4')
    if COOKIE_PATH.is_file(): options['cookiefile'] = str(COOKIE_PATH)
    node = shutil.which('node') or shutil.which('nodejs')
    if node: options['js_runtimes'] = {'node':{'path':node}}
    try:
        with yt_dlp.YoutubeDL(options) as downloader:
            downloader.download([f'https://www.youtube.com/watch?v={video_id}'])
        output = directory / f'{video_id}.{fmt}'
        if not output.is_file() or not output.stat().st_size:
            raise RuntimeError('No complete media file')
        return directory, output
    except Exception:
        remove_download_directory(directory)
        raise


def get_cached_stream(video_id: str) -> tuple[str, dict] | None:
    now = time.monotonic()
    if video_id in STREAM_CACHE:
        timestamp, url, headers = STREAM_CACHE[video_id]
        if now - timestamp < STREAM_CACHE_TTL:
            return url, headers
        del STREAM_CACHE[video_id]
    return None


def store_cached_stream(video_id: str, url: str, headers: dict) -> None:
    now = time.monotonic()
    if len(STREAM_CACHE) > 200:
        expired = [k for k, (t, _, _) in STREAM_CACHE.items() if now - t > STREAM_CACHE_TTL]
        for k in expired:
            STREAM_CACHE.pop(k, None)
    STREAM_CACHE[video_id] = (now, url, headers)


@app.get("/api/health")
async def health():
    return {
        "status": "ok",
        "app": "MusicBox Web",
        "version": "2.4.8",
        "build": "release-v248",
        "has_cookies": COOKIE_PATH.is_file() and COOKIE_PATH.stat().st_size > 0,
    }


@app.get("/api/cookies/status")
async def cookies_status():
    has_cookies = COOKIE_PATH.is_file() and COOKIE_PATH.stat().st_size > 0
    return {"active": has_cookies, "upload_enabled": bool(os.environ.get('MUSICBOX_ADMIN_TOKEN'))}


@app.post("/api/cookies/upload")
async def upload_cookies(request: Request, file: UploadFile = File(...)):
    """Permite subir un cookies.txt para saltar restricciones de YouTube si fuera necesario."""
    try:
        token = os.environ.get('MUSICBOX_ADMIN_TOKEN', '')
        if not token or not secrets.compare_digest(request.headers.get('authorization', ''), 'Bearer ' + token):
            raise HTTPException(status_code=403, detail='Las cookies del servidor solo las puede configurar su administrador.')
        content = await file.read(1024 * 1024 + 1)
        if len(content) > 1024 * 1024 or b'Netscape HTTP Cookie File' not in content[:200]:
            raise HTTPException(status_code=400, detail='Archivo de cookies Netscape inválido (máximo 1 MB).')
        COOKIE_PATH.parent.mkdir(parents=True, exist_ok=True)
        COOKIE_PATH.write_bytes(content)
        return {"status": "ok", "message": "Cookies guardadas correctamente"}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Error guardando cookies: {exc}")


@app.get("/api/search")
async def search(q: Annotated[str, Query(max_length=500)], type: Literal['songs', 'artists', 'videos', 'all'] = "songs"):
    if not q or not q.strip():
        return []
    query = q.strip()
    if query.startswith(('https://', 'http://')):
        parsed = urlparse(query)
        host = (parsed.hostname or '').lower()
        if host in ('youtube.com', 'www.youtube.com', 'music.youtube.com', 'm.youtube.com', 'youtu.be'):
            video_id = parsed.path.strip('/') if host == 'youtu.be' else parse_qs(parsed.query).get('v', [''])[0]
            if parsed.path.startswith(('/shorts/', '/embed/')): video_id = parsed.path.split('/')[2]
            if re.fullmatch(r'[A-Za-z0-9_-]{11}', video_id):
                try:
                    info = await asyncio.to_thread(ytmusic.post, 'player', {'videoId': video_id})
                except Exception:
                    info = {}
                details = info.get('videoDetails') or {}
                if not details.get('title'):
                    try:
                        async with httpx.AsyncClient(timeout=10) as client:
                            response = await client.get('https://www.youtube.com/oembed', params={'url':f'https://www.youtube.com/watch?v={video_id}', 'format':'json'})
                            if response.is_success:
                                meta = response.json()
                                details = {'title': meta.get('title'), 'author': meta.get('author_name')}
                    except Exception:
                        pass
                return [ytmusic.make_track(video_id, details.get('title') or f'YouTube · {video_id}', details.get('author', ''), '',
                    int(details.get('lengthSeconds') or 0), ytmusic.best_thumbnail(details), 'song')]
    cache_key = f"{type}:{query.lower()}"

    now = time.monotonic()
    if cache_key in SEARCH_CACHE:
        ts, data = SEARCH_CACHE[cache_key]
        if now - ts < SEARCH_CACHE_TTL:
            return data

    loop = asyncio.get_running_loop()
    try:
        if type == "artists":
            results = await loop.run_in_executor(None, ytmusic.search_artists, query)
        elif type == "videos":
            results = await loop.run_in_executor(None, ytmusic.search_videos, query)
        elif type == "all":
            results = await loop.run_in_executor(None, ytmusic.search_all, query)
        else:
            results = await loop.run_in_executor(None, ytmusic.search_songs, query)

        if len(SEARCH_CACHE) >= SEARCH_CACHE_MAX:
            SEARCH_CACHE.popitem(last=False)
        SEARCH_CACHE[cache_key] = (now, results)
        return results
    except Exception as exc:
        logger.error("Error en búsqueda '%s': %s", query, exc)
        raise HTTPException(status_code=500, detail=str(exc))


@app.get("/api/search/page")
async def search_page(q: Annotated[str, Query(max_length=500)],
                      type: Literal['songs', 'artists', 'videos', 'all'] = "songs",
                      continuation: Annotated[str, Query(max_length=4000)] = ""):
    """Una página de resultados y el token de la siguiente (scroll infinito). Sin token es la primera página."""
    query = q.strip()
    if not query and not continuation:
        return {"items": [], "continuation": ""}
    try:
        return await asyncio.to_thread(ytmusic.search_page, query, type, continuation)
    except Exception as exc:
        logger.error("Error en la página de búsqueda '%s': %s", query, exc)
        raise HTTPException(status_code=502, detail="No se pudieron cargar más resultados.")


@app.get("/api/recommendations")
async def recommendations():
    """Artistas y canciones recomendadas para la pantalla de inicio con paridad Desktop."""
    cache_key = "home_recommendations_v2"
    now = time.monotonic()
    if cache_key in SEARCH_CACHE:
        ts, data = SEARCH_CACHE[cache_key]
        if now - ts < 1800:
            return data

    loop = asyncio.get_running_loop()
    try:
        # 1. Artistas recomendados para la fila circular "Artistas para ti"
        curated_artists = [
            "Bad Bunny", "Rosalía", "Quevedo", "Dua Lipa", "The Weeknd",
            "Feid", "Coldplay", "Taylor Swift", "Bizarrap", "Billie Eilish"
        ]
        artists = []
        async def find_artist(name):
            try: return await asyncio.to_thread(ytmusic.find_artist, name)
            except Exception: return None
        artists = [art for art in await asyncio.gather(*(find_artist(name) for name in curated_artists)) if art]

        # 2. Canciones para el grid "Para ti"
        queries = ["éxitos música", "Pop latino éxitos", "Indie pop hits"]
        all_tracks = []
        seen_ids = set()

        results = await asyncio.gather(*(asyncio.to_thread(ytmusic.search_songs, q) for q in queries), return_exceptions=True)
        for songs in results:
            if isinstance(songs, Exception): continue
            for s in songs:
                if s['kind'] == 'song' and (s.get('duration') or 0) <= 600 and s["id"] not in seen_ids:
                    seen_ids.add(s["id"])
                    all_tracks.append(s)

        res = {"artists": artists, "songs": all_tracks[:20]}
        SEARCH_CACHE[cache_key] = (now, res)
        return res
    except Exception as exc:
        logger.error("Error en recomendaciones: %s", exc)
        raise HTTPException(status_code=500, detail=str(exc))


@app.get("/api/related/{video_id}")
async def related(video_id: VideoId, count: Annotated[int, Query(ge=1, le=40)] = 20):
    loop = asyncio.get_running_loop()
    try:
        tracks = await loop.run_in_executor(None, ytmusic.related, video_id, count)
        return tracks
    except Exception as exc:
        logger.error("Error en related '%s': %s", video_id, exc)
        return []


@app.get("/api/artist/{browse_id}")
async def artist_page(browse_id: str):
    loop = asyncio.get_running_loop()
    try:
        page = await loop.run_in_executor(None, ytmusic.artist_page, browse_id)
        return page
    except Exception as exc:
        logger.error("Error en artist_page '%s': %s", browse_id, exc)
        raise HTTPException(status_code=500, detail=str(exc))


@app.get("/api/album/{browse_id}")
async def album_page(browse_id: str):
    loop = asyncio.get_running_loop()
    try:
        page = await loop.run_in_executor(None, ytmusic.album_page, browse_id)
        return page
    except Exception as exc:
        logger.error("Error en album_page '%s': %s", browse_id, exc)
        raise HTTPException(status_code=500, detail=str(exc))


@app.get('/api/playlist/{browse_id}')
async def playlist_tracks(browse_id: Annotated[str, ApiPath(pattern=r'^[A-Za-z0-9_-]{8,100}$')]):
    return await asyncio.to_thread(ytmusic.playlist_tracks, browse_id)


@app.get('/api/video-source/{video_id}')
async def video_source(video_id: VideoId):
    try:
        source = await asyncio.to_thread(ytmusic.playable_video_source, video_id)
        return {'id': source if source and source != video_id else ''}
    except Exception:
        return {'id': ''}


@app.get('/api/import/preview')
async def import_preview(url: Annotated[str, Query(max_length=1000)]):
    link = playlist_import.find(url)
    if not link:
        raise HTTPException(status_code=400, detail='Pega un enlace de playlist pública de YouTube, Spotify, Deezer, Apple Music o SoundCloud.')
    try:
        result = await asyncio.to_thread(playlist_import.fetch, link)
        return {'name': result.name, 'platform': result.platform, 'tracks': [
            {'title': t.title, 'artist': t.artist, 'seconds': t.seconds, 'direct': t.direct} for t in result.tracks[:500]]}
    except Exception:
        raise HTTPException(status_code=502, detail='No se pudo leer la playlist pública. Revisa el enlace y su visibilidad.')


class ImportTrack(BaseModel):
    title: str = Field(min_length=1, max_length=500)
    artist: str = Field(default='', max_length=500)
    seconds: int = Field(default=0, ge=0, le=86400)


@app.post('/api/import/match')
async def import_match(track: ImportTrack):
    result = await asyncio.to_thread(playlist_import.match, playlist_import.Track(track.title, track.artist, track.seconds))
    return result


@app.get("/api/lyrics/{video_id}")
async def get_lyrics(video_id: VideoId, title: str = "", artist: str = "", duration: int = 0):
    loop = asyncio.get_running_loop()
    try:
        track = {"id": video_id, "title": title, "artist": artist, "duration": duration}
        result = await loop.run_in_executor(None, lyrics.find, track)
        return result or {"lines": [], "synced": False, "source": "", "instrumental": False}
    except Exception as exc:
        logger.error("Error en lyrics '%s': %s", video_id, exc)
        return {"lines": [], "synced": False, "source": "", "instrumental": False}


FALLBACK_FOLDER = Path(tempfile.gettempdir()) / "musicbox-stream-fallback"
FALLBACK_FILES: "OrderedDict[str, str]" = OrderedDict()
FALLBACK_LIMIT = 16


def _download_audio(options: dict, url: str) -> str:
    opts = dict(options)
    if COOKIE_PATH.is_file() and COOKIE_PATH.stat().st_size > 0:
        opts["cookiefile"] = str(COOKIE_PATH)
    js_node = shutil.which("node") or shutil.which("nodejs")
    if js_node:
        opts["js_runtimes"] = {"node": {"path": js_node}}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        return ydl.prepare_filename(info)


def _audio_type(path: str) -> str:
    return {".m4a": "audio/mp4", ".mp4": "audio/mp4", ".webm": "audio/webm", ".opus": "audio/ogg", ".mp3": "audio/mpeg"}.get(
        Path(path).suffix.lower(), "application/octet-stream")


def _remember_fallback(video_id: str, path: str) -> None:
    FALLBACK_FILES[video_id] = path
    FALLBACK_FILES.move_to_end(video_id)
    while len(FALLBACK_FILES) > FALLBACK_LIMIT:
        _, old = FALLBACK_FILES.popitem(last=False)
        try:
            Path(old).unlink(missing_ok=True)
        except OSError:
            pass


STREAM_CHUNK = 2 * 1024 * 1024
_OPEN_RANGE = re.compile(r"^bytes=(\d+)-$")
_CONTENT_RANGE = re.compile(r"^bytes \d+-\d+/(\d+)$")


def _upstream_range(header: str | None) -> tuple[str, bool]:
    """Algunos enlaces firmados de YouTube rechazan rangos abiertos (403): se pide siempre un rango acotado.

    Devuelve el rango para el proveedor y si el cliente quería el archivo completo (sin Range)."""
    if not header:
        return f"bytes=0-{STREAM_CHUNK - 1}", True
    match = _OPEN_RANGE.match(header.strip())
    if match:
        start = int(match[1])
        return f"bytes={start}-{start + STREAM_CHUNK - 1}", False
    return header, False


@app.api_route("/api/stream/{video_id}", methods=["GET", "HEAD"])
async def stream_audio(video_id: VideoId, request: Request, video: bool = False):
    """Sirve audio y clips MP4 con soporte para solicitudes Range."""
    cache_key = f"{video_id}:video" if video else video_id
    cached = get_cached_stream(cache_key)
    if cached:
        stream_url, yt_headers = cached
    else:
        loop = asyncio.get_running_loop()
        local = FALLBACK_FILES.get(video_id)
        if not video and local and Path(local).is_file():
            return FileResponse(local, media_type=_audio_type(local), headers={"Accept-Ranges": "bytes"})
        try:
            url = f"https://www.youtube.com/watch?v={video_id}"
            if video:
                def resolve_video():
                    source = ytmusic.playable_video_source(video_id)
                    if not source:
                        raise HTTPException(status_code=404, detail="Esta canción no tiene un clip disponible.")
                    return youtube_streams.resolve_stream(f"https://www.youtube.com/watch?v={source}", _extract_info, video=True)

                kind, result = "url", await asyncio.to_thread(resolve_video)
            else:
                kind, result = await loop.run_in_executor(None, youtube_streams.resolve_or_cache, url, _extract_info,
                                                          _download_audio, FALLBACK_FOLDER)
            if kind == "file":
                # Ninguna herramienta dio un enlace directo: se sirvió el audio descargado, sin cortar la música.
                _remember_fallback(video_id, result)
                return FileResponse(result, media_type=_audio_type(result), headers={"Accept-Ranges": "bytes"})
            info = result
            stream_url = info.get("url")
            if not stream_url:
                raise HTTPException(status_code=404, detail="No se encontró stream de audio")
            yt_headers = info.get("http_headers") or {}
            store_cached_stream(cache_key, stream_url, yt_headers)
        except HTTPException:
            raise
        except Exception as exc:
            logger.error("Error resolviendo stream para '%s': %s", video_id, exc)
            if video:
                raise HTTPException(status_code=503, detail="El vídeo no está disponible desde el servidor.")
            raise HTTPException(status_code=503, detail="YouTube no permitió obtener el audio desde el servidor. Puedes reproducir en línea; la descarga requiere que el administrador revise el acceso del servidor a YouTube.")

    # Use the same headers with which the resolver validated the signed URL.
    req_headers = dict(yt_headers)
    req_headers.setdefault("User-Agent", "Mozilla/5.0")
    range_header, whole_file = _upstream_range(request.headers.get("range"))
    req_headers["Range"] = range_header

    client = httpx.AsyncClient(timeout=20.0, follow_redirects=True)
    try:
        upstream_req = client.build_request(request.method, stream_url, headers=req_headers)
        upstream_resp = await client.send(upstream_req, stream=True)
        if upstream_resp.status_code not in (200, 206):
            status = upstream_resp.status_code
            await upstream_resp.aclose()
            await client.aclose()
            STREAM_CACHE.pop(cache_key, None)
            raise HTTPException(status_code=502, detail=f'El proveedor rechazó el {"vídeo" if video else "audio"} ({status}).')

        # Sin Range, el cliente espera el archivo entero: se encadenan fragmentos acotados y se responde 200.
        total = None
        if whole_file and upstream_resp.status_code == 206:
            match = _CONTENT_RANGE.match(upstream_resp.headers.get("content-range", ""))
            total = int(match[1]) if match else None
        status_code = 200 if total is not None else upstream_resp.status_code

        async def stream_generator() -> AsyncGenerator[bytes, None]:
            sent = 0
            try:
                async for chunk in upstream_resp.aiter_bytes(chunk_size=65536):
                    sent += len(chunk)
                    yield chunk
                while total is not None and sent < total:
                    next_req = client.build_request("GET", stream_url, headers={
                        **req_headers, "Range": f"bytes={sent}-{min(sent + STREAM_CHUNK, total) - 1}"})
                    next_resp = await client.send(next_req, stream=True)
                    try:
                        if next_resp.status_code != 206:
                            break
                        received = 0
                        async for chunk in next_resp.aiter_bytes(chunk_size=65536):
                            received += len(chunk)
                            sent += len(chunk)
                            yield chunk
                        if not received:
                            break
                    finally:
                        await next_resp.aclose()
            finally:
                await upstream_resp.aclose()
                await client.aclose()

        response_headers = {
            "Accept-Ranges": "bytes",
            "Content-Type": upstream_resp.headers.get("content-type", "video/mp4" if video else "audio/webm"),
            "Cache-Control": "public, max-age=1800",
        }
        if total is not None:
            response_headers["Content-Length"] = str(total)
        else:
            if "content-length" in upstream_resp.headers:
                response_headers["Content-Length"] = upstream_resp.headers["content-length"]
            if "content-range" in upstream_resp.headers:
                response_headers["Content-Range"] = upstream_resp.headers["content-range"]

        if request.method == "HEAD":
            await upstream_resp.aclose()
            await client.aclose()
            return Response(status_code=status_code, headers=response_headers)

        return StreamingResponse(
            stream_generator(),
            status_code=status_code,
            headers=response_headers,
        )
    except HTTPException:
        raise
    except Exception as exc:
        await client.aclose()
        logger.error("Error en streaming para '%s': %s", video_id, exc)
        raise HTTPException(status_code=502, detail=str(exc))


@app.get('/api/download/{video_id}')
async def download_file(video_id: VideoId, format: Literal['mp3','mp4'] = 'mp3', quality: int = 192):
    if quality not in ((128,192,256,320) if format == 'mp3' else (360,480,720,1080)):
        raise HTTPException(status_code=422, detail='Calidad no válida para el formato seleccionado.')
    await DOWNLOAD_SLOTS.acquire()
    task = asyncio.create_task(asyncio.to_thread(prepare_download, video_id, format, quality))
    cancelled = False
    try:
        directory, output = await asyncio.shield(task)
    except asyncio.CancelledError:
        cancelled = True
        def cleanup_finished_job(finished):
            try:
                temporary, _ = finished.result()
                remove_download_directory(temporary)
            except Exception:
                pass
            finally:
                DOWNLOAD_SLOTS.release()
        task.add_done_callback(cleanup_finished_job)
        raise
    except Exception as error:
        logger.warning('Download unavailable for %s: %s', video_id, type(error).__name__)
        raise HTTPException(status_code=503, detail='YouTube no permitió descargar desde el servidor. Reintenta más tarde; el administrador debe revisar el acceso a YouTube.')
    finally:
        if not cancelled: DOWNLOAD_SLOTS.release()
    return FileResponse(output,media_type='audio/mpeg' if format == 'mp3' else 'video/mp4',filename=output.name,
                        background=BackgroundTask(remove_download_directory,directory))


@app.get('/api/artwork')
async def animated_artwork(title: str = Query(max_length=300), artist: str = Query(max_length=200),
                           album: str = Query(default='', max_length=300), duration: int = Query(default=0, ge=0, le=86400),
                           quality: str = Query(default='', pattern=r'^(low|medium|high)?$')):
    try:
        args = (title, artist, album, duration, quality) if quality else (title, artist, album, duration)
        return {'url': await asyncio.to_thread(artwork.resolve, *args)}
    except Exception:
        return {'url': ''}


# Servir frontend estático
FRONTEND_DIR = Path(__file__).resolve().parent / "frontend"
if not FRONTEND_DIR.is_dir():
    FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"

if FRONTEND_DIR.is_dir():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")
