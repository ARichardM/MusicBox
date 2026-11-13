"""MusicBox Web API - Backend FastAPI para MusicBox Web."""

from __future__ import annotations

import asyncio
from collections import OrderedDict
import logging
import os
from pathlib import Path
import shutil
import time
from typing import AsyncGenerator

from fastapi import FastAPI, HTTPException, Request, Response, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
import httpx
import yt_dlp

import lyrics
import youtube_streams
import ytmusic

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("musicbox.web")

app = FastAPI(title="MusicBox Web API", version="2.4.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Ruta del archivo de cookies opcional
COOKIE_PATH = Path(__file__).resolve().parent / "cookies.txt"

# Caché en memoria para stream URLs (15 minutos de TTL)
STREAM_CACHE: dict[str, tuple[float, str, dict]] = {}
STREAM_CACHE_TTL = 900  # 15 min

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
        "version": "2.4.0",
        "has_cookies": COOKIE_PATH.is_file() and COOKIE_PATH.stat().st_size > 0,
    }


@app.get("/api/cookies/status")
async def cookies_status():
    has_cookies = COOKIE_PATH.is_file() and COOKIE_PATH.stat().st_size > 0
    return {"active": has_cookies, "path": str(COOKIE_PATH) if has_cookies else ""}


@app.post("/api/cookies/upload")
async def upload_cookies(file: UploadFile = File(...)):
    """Permite subir un cookies.txt para saltar restricciones de YouTube si fuera necesario."""
    try:
        content = await file.read()
        COOKIE_PATH.write_bytes(content)
        return {"status": "ok", "message": "Cookies guardadas correctamente"}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Error guardando cookies: {exc}")


@app.get("/api/search")
async def search(q: str, type: str = "songs"):
    if not q or not q.strip():
        return []
    query = q.strip()
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
        for name in curated_artists:
            try:
                art = await loop.run_in_executor(None, ytmusic.find_artist, name)
                if art:
                    artists.append(art)
            except Exception:
                pass

        # 2. Canciones para el grid "Para ti"
        queries = ["Top éxitos 2026", "Top canciones español", "Indie pop hits", "Pop latino éxitos"]
        all_tracks = []
        seen_ids = set()

        for q in queries:
            songs = await loop.run_in_executor(None, ytmusic.search_songs, q)
            for s in songs:
                if s["id"] not in seen_ids:
                    seen_ids.add(s["id"])
                    all_tracks.append(s)

        res = {"artists": artists, "songs": all_tracks[:40]}
        SEARCH_CACHE[cache_key] = (now, res)
        return res
    except Exception as exc:
        logger.error("Error en recomendaciones: %s", exc)
        raise HTTPException(status_code=500, detail=str(exc))


@app.get("/api/related/{video_id}")
async def related(video_id: str, count: int = 20):
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


@app.get("/api/lyrics/{video_id}")
async def get_lyrics(video_id: str, title: str = "", artist: str = "", duration: int = 0):
    loop = asyncio.get_running_loop()
    try:
        track = {"id": video_id, "title": title, "artist": artist, "duration": duration}
        result = await loop.run_in_executor(None, lyrics.find, track)
        return result or {"lines": [], "synced": False, "source": "", "instrumental": False}
    except Exception as exc:
        logger.error("Error en lyrics '%s': %s", video_id, exc)
        return {"lines": [], "synced": False, "source": "", "instrumental": False}


@app.api_route("/api/stream/{video_id}", methods=["GET", "HEAD"])
async def stream_audio(video_id: str, request: Request):
    """Proxy de streaming de audio con soporte para Range requests."""
    cached = get_cached_stream(video_id)
    if cached:
        stream_url, yt_headers = cached
    else:
        loop = asyncio.get_running_loop()
        try:
            url = f"https://www.youtube.com/watch?v={video_id}"
            info = await loop.run_in_executor(None, youtube_streams.resolve_stream, url, _extract_info)
            stream_url = info.get("url")
            if not stream_url:
                raise HTTPException(status_code=404, detail="No se encontró stream de audio")
            yt_headers = info.get("http_headers") or {}
            store_cached_stream(video_id, stream_url, yt_headers)
        except Exception as exc:
            logger.error("Error resolviendo stream para '%s': %s", video_id, exc)
            raise HTTPException(status_code=500, detail=f"Error resolviendo stream: {exc}")

    req_headers = {"User-Agent": yt_headers.get("User-Agent", "Mozilla/5.0")}
    range_header = request.headers.get("range")
    if range_header:
        req_headers["Range"] = range_header

    client = httpx.AsyncClient(timeout=20.0, follow_redirects=True)
    try:
        upstream_req = client.build_request(request.method, stream_url, headers=req_headers)
        upstream_resp = await client.send(upstream_req, stream=True)

        async def stream_generator() -> AsyncGenerator[bytes, None]:
            try:
                async for chunk in upstream_resp.aiter_bytes(chunk_size=65536):
                    yield chunk
            finally:
                await upstream_resp.aclose()
                await client.aclose()

        response_headers = {
            "Accept-Ranges": "bytes",
            "Content-Type": upstream_resp.headers.get("content-type", "audio/webm"),
            "Cache-Control": "public, max-age=1800",
        }
        if "content-length" in upstream_resp.headers:
            response_headers["Content-Length"] = upstream_resp.headers["content-length"]
        if "content-range" in upstream_resp.headers:
            response_headers["Content-Range"] = upstream_resp.headers["content-range"]

        if request.method == "HEAD":
            await upstream_resp.aclose()
            await client.aclose()
            return Response(status_code=upstream_resp.status_code, headers=response_headers)

        return StreamingResponse(
            stream_generator(),
            status_code=upstream_resp.status_code,
            headers=response_headers,
        )
    except Exception as exc:
        await client.aclose()
        logger.error("Error en streaming para '%s': %s", video_id, exc)
        raise HTTPException(status_code=502, detail=str(exc))


# Servir frontend estático
FRONTEND_DIR = Path(__file__).resolve().parent / "frontend"
if not FRONTEND_DIR.is_dir():
    FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"

if FRONTEND_DIR.is_dir():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="frontend")
