"""Importar una playlist completa desde un enlace público de Spotify, YouTube / YouTube Music, Deezer, Apple Music o SoundCloud.

Las canciones de YouTube ya traen su video. Las de las demás plataformas se buscan en YouTube Music y solo se agregan
cuando la coincidencia es segura (título, artista y duración), para no meter versiones equivocadas.
"""

from __future__ import annotations

import html as html_lib
import json
import re
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

import coincidencias
import ytmusic

USER_AGENT = ("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/120.0.0.0 Mobile Safari/537.36")
MAX_DEEZER_PAGES = 40
MAX_YOUTUBE_TRACKS = 500

LABELS = {"spotify": "Spotify", "youtube": "YouTube", "deezer": "Deezer",
          "apple": "Apple Music", "soundcloud": "SoundCloud"}

_PATTERNS = (
    ("spotify", re.compile(r"https?://open\.spotify\.com/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]{10,40})")),
    ("youtube", re.compile(r"https?://(?:www\.|m\.|music\.)?youtube\.com/playlist\?(?:[^\s]*&)?list=([A-Za-z0-9_-]{10,80})")),
    ("deezer", re.compile(r"https?://(?:www\.)?deezer\.com/(?:[a-z]{2}/)?playlist/(\d{1,15})")),
    ("apple", re.compile(r"https?://music\.apple\.com/(?:[a-z]{2}/)?playlist/[^\s/?#]+/pl\.[A-Za-z0-9]+")),
    ("soundcloud", re.compile(r"https?://(?:www\.|m\.)?soundcloud\.com/[A-Za-z0-9_.-]+/sets/[A-Za-z0-9_.-]+(?:/s-[A-Za-z0-9]+)?")),
)
_NEXT_DATA = re.compile(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', re.DOTALL)
_APPLE_DATA = re.compile(r'<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>', re.DOTALL)
_OG_TITLE = re.compile(r'<meta property="og:title" content="([^"]*)"')


@dataclass(frozen=True)
class Link:
    platform: str
    id: str
    url: str

    @property
    def label(self) -> str:
        return LABELS[self.platform]


@dataclass
class Track:
    """Una canción de la playlist de origen. ``direct`` trae el video de YouTube cuando ya se conoce."""
    title: str
    artist: str = ""
    seconds: int = 0
    direct: dict | None = None


@dataclass
class Playlist:
    platform: str
    name: str
    tracks: list = field(default_factory=list)

    def __post_init__(self):
        self.name = (self.name or "").strip() or f"{LABELS[self.platform]} · playlist"

    @property
    def label(self) -> str:
        return LABELS[self.platform]


@dataclass
class Outcome:
    playlist: str
    added: int
    skipped: int
    stopped: bool = False


def find(text) -> Link | None:
    """Primer enlace de playlist soportado dentro de un texto (un mensaje compartido, el portapapeles…)."""
    value = str(text or "")
    for platform, pattern in _PATTERNS:
        found = pattern.search(value)
        if found:
            identifier = found.group(1) if pattern.groups else found.group(0)
            return Link(platform, identifier, found.group(0))
    return None


def http_get(url: str, opener=urllib.request.urlopen, timeout: float = 20) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                                   "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"})
    with opener(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


# --- Lectura de cada plataforma (funciones puras sobre el texto de la página) ---

def parse_spotify(page: str) -> Playlist:
    found = _NEXT_DATA.search(page or "")
    if not found:
        raise ValueError("No se pudo leer la playlist de Spotify.")
    entity = json.loads(found.group(1))["props"]["pageProps"]["state"]["data"]["entity"]
    tracks = []
    for item in entity.get("trackList") or []:
        if item.get("entityType", "track") != "track" or not item.get("title"):
            continue
        tracks.append(Track(item["title"], item.get("subtitle", ""), int((item.get("duration") or 0) / 1000)))
    if not tracks:
        raise ValueError("La playlist de Spotify no tiene canciones visibles.")
    return Playlist("spotify", entity.get("name", ""), tracks)


def parse_deezer_tracks(data) -> list:
    tracks = []
    for item in data or []:
        if not item.get("title"):
            continue
        tracks.append(Track(item["title"], (item.get("artist") or {}).get("name", ""), int(item.get("duration") or 0)))
    return tracks


def parse_apple_music(page: str) -> Playlist:
    found = _APPLE_DATA.search(page or "")
    if not found:
        raise ValueError("No se pudo leer la playlist de Apple Music.")
    tracks = []

    def walk(node):
        if isinstance(node, dict):
            if "trackNumber" in node and "artistName" in node and node.get("title"):
                tracks.append(Track(node["title"], node.get("artistName", ""), int((node.get("duration") or 0) / 1000)))
                return
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(json.loads(found.group(1)))
    if not tracks:
        raise ValueError("La playlist de Apple Music no tiene canciones visibles.")
    title = _OG_TITLE.search(page)
    name = html_lib.unescape(title.group(1)).replace("‎", "") if title else ""
    return Playlist("apple", re.sub(r"\s+on Apple Music$", "", name), tracks)


def parse_soundcloud(page: str) -> Playlist:
    found = _NEXT_DATA.search(page or "")
    if not found:
        raise ValueError("No se pudo leer la playlist de SoundCloud.")
    tracks, name = [], [""]

    def walk(node):
        if isinstance(node, dict):
            urn = str(node.get("urn", ""))
            if urn.startswith("soundcloud:playlists:") and not name[0]:
                name[0] = node.get("title", "")
            if urn.startswith("soundcloud:tracks:") and "title" in node and "duration" in node:
                meta = node.get("publisher_metadata") if isinstance(node.get("publisher_metadata"), dict) else {}
                tracks.append(Track(node["title"], meta.get("artist", ""), int((node.get("duration") or 0) / 1000)))
                return
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(json.loads(found.group(1)))
    if not tracks:
        raise ValueError("La playlist de SoundCloud no tiene canciones visibles.")
    return Playlist("soundcloud", name[0], tracks)


def _youtube_title(identifier: str, get) -> str:
    """Título de la playlist por oEmbed, el servicio público de YouTube; vacío si no responde."""
    try:
        target = urllib.parse.quote(f"https://www.youtube.com/playlist?list={identifier}", safe="")
        return str(json.loads(get(f"https://www.youtube.com/oembed?format=json&url={target}")).get("title", ""))
    except Exception:
        return ""


def fetch(link: Link, get=http_get, ytm=ytmusic) -> Playlist:
    """Lee la playlist completa. Es bloqueante: llamar siempre fuera del hilo de interfaz."""
    if link.platform == "spotify":
        return parse_spotify(get(f"https://open.spotify.com/embed/playlist/{link.id}"))
    if link.platform == "apple":
        return parse_apple_music(get(link.url))
    if link.platform == "soundcloud":
        return parse_soundcloud(get(link.url))
    if link.platform == "deezer":
        playlist = json.loads(get(f"https://api.deezer.com/playlist/{link.id}"))
        if "error" in playlist:
            raise ValueError("Deezer no encontró esta playlist.")
        tracks, page = [], playlist.get("tracks") or {}
        for _ in range(MAX_DEEZER_PAGES):
            tracks.extend(parse_deezer_tracks(page.get("data")))
            following = page.get("next")
            if not following:
                break
            page = json.loads(get(following))
        if not tracks:
            raise ValueError("La playlist de Deezer no tiene canciones.")
        return Playlist("deezer", playlist.get("title", ""), tracks)
    songs = ytm.playlist_tracks("VL" + link.id, limit=MAX_YOUTUBE_TRACKS)
    if not songs:
        raise ValueError("La playlist de YouTube está vacía, es privada o no está disponible.")
    return Playlist("youtube", _youtube_title(link.id, get), [
        Track(song["title"], song.get("artist", ""), int(song.get("duration") or 0), dict(song)) for song in songs])


# --- Emparejamiento con YouTube Music ---

def search_query(track: Track) -> str:
    artist = track.artist.strip()
    if artist and artist.casefold() not in track.title.casefold():
        return f"{artist} - {track.title}"
    return track.title


def match(track: Track, search_songs=ytmusic.search_songs, search_videos=ytmusic.search_videos) -> dict | None:
    """Mejor resultado de YouTube Music si es una coincidencia segura; None si no lo es."""
    query = search_query(track)
    candidates = search_songs(query) or search_videos(query)
    if not candidates:
        return None
    scored = [{"title": item.get("title", ""), "channel": " ".join(filter(None, (item.get("artist"), item.get("album")))),
               "duration": item.get("duration", 0)} for item in candidates]
    duration = f"{track.seconds // 60}:{track.seconds % 60:02d}" if track.seconds else ""
    best = coincidencias.best_candidate(query, scored, title=track.title, artist=track.artist, duration=duration)
    if best is None:
        return None
    index, result = best
    return candidates[index] if result.auto_select else None


def resolve(playlist: Playlist, progress=None, cancelled=None, matcher=match):
    """Recorre las canciones y devuelve (video por canción encontrada, cantidad sin coincidencia segura, detenida)."""
    found, skipped, total = [], 0, len(playlist.tracks)
    for position, track in enumerate(playlist.tracks, start=1):
        if cancelled is not None and cancelled():
            return found, skipped, True
        video = track.direct
        if video is None:
            try:
                video = matcher(track)
            except Exception:
                video = None  # un fallo de red en una canción no detiene al resto
        if video is None:
            skipped += 1
        else:
            found.append(video)
        if progress is not None:
            progress(position, total)
    return found, skipped, False
