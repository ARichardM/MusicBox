"""Cliente mínimo de YouTube Music (sin cuenta): canciones, artistas con foto, discografías.

YouTube Music separa lo que YouTube mezcla. Cada elemento trae un ``musicVideoType``:
``ATV`` es la pista de audio oficial (una *canción*), ``OMV`` el videoclip oficial y ``UGC`` un video
subido por cualquiera. La lista de "siguientes" (``RDAMVM<id>``) también puede contener videoclips:
se filtran y, si no queda audio, se busca la misma grabación en el catálogo de canciones.
"""
from __future__ import annotations

import json
import re
import urllib.request

BASE = "https://music.youtube.com/youtubei/v1/"
CLIENT_VERSION = "1.20250101.01.00"
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
FILTER_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
DURATION = re.compile(r"^\d{1,2}(?::\d{2}){1,2}$")
YEAR = re.compile(r"^(?:19|20)\d{2}$")
SEPARATOR = "•"


def post(endpoint: str, body: dict, opener=urllib.request.urlopen, timeout: float = 15) -> dict:
    payload = dict(body, context={"client": {"clientName": "WEB_REMIX", "clientVersion": CLIENT_VERSION, "hl": "es", "gl": "CL"}})
    request = urllib.request.Request(
        BASE + endpoint + "?prettyPrint=false", data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Origin": "https://music.youtube.com", "Referer": "https://music.youtube.com/",
                 "User-Agent": USER_AGENT, "X-YouTube-Client-Name": "67", "X-YouTube-Client-Version": CLIENT_VERSION})
    with opener(request, timeout=timeout) as response:
        return json.load(response)


def walk(node, key):
    """Todos los valores de ``key`` en cualquier nivel (la respuesta cambia de anidación según la pantalla)."""
    if isinstance(node, dict):
        for name, value in node.items():
            if name == key:
                yield value
            yield from walk(value, key)
    elif isinstance(node, list):
        for item in node:
            yield from walk(item, key)


def first(node, key, default=None):
    return next(walk(node, key), default)


def runs(text_node) -> list[dict]:
    return list((text_node or {}).get("runs") or [])


def text_of(text_node) -> str:
    return "".join(run.get("text", "") for run in runs(text_node))


def segments(text_node) -> list[list[dict]]:
    """Las partes de una línea "Artista • Álbum • 3:45", cada una con sus tramos (que pueden traer un enlace)."""
    parts, current = [], []
    for run in runs(text_node):
        if run.get("text", "").strip() == SEPARATOR:
            parts.append(current)
            current = []
        else:
            current.append(run)
    if current:
        parts.append(current)
    return parts


def seconds(text) -> int:
    if not isinstance(text, str) or not DURATION.match(text.strip()):
        return 0
    total = 0
    for part in text.strip().split(":"):
        total = total * 60 + int(part)
    return total


_SIZE = re.compile(r"=w\d+-h\d+")


def resize_photo(url: str, size: int) -> str:
    """Pide a YouTube la foto cuadrada del tamaño justo (las de artista vienen como banner de 1200x500)."""
    return _SIZE.sub(f"=w{size}-h{size}", url, count=1) if url else url


def best_thumbnail(node) -> str:
    thumbs = first(node, "thumbnails", []) or []
    return thumbs[-1].get("url", "") if thumbs else ""


def kind_of(node) -> str:
    """song (pista de audio oficial), video (clip oficial o subido por alguien) o vacío si no se sabe."""
    video_type = first(node, "musicVideoType", "") or ""
    if video_type == "MUSIC_VIDEO_TYPE_ATV":
        return "song"
    return "video" if video_type in ("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC") else ""


def make_track(video_id, title, artist, album, duration, thumbnail, kind) -> dict | None:
    if not video_id or not title:
        return None
    return {"id": video_id, "title": title, "subtitle": " · ".join(part for part in (artist, album) if part), "artist": artist or "",
            "album": album or "", "duration": duration or 0,
            "thumbnail": thumbnail or f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
            "url": f"https://www.youtube.com/watch?v={video_id}", "kind": kind}


def _joined(parts: list[list[dict]]) -> str:
    return " ".join(text for text in ("".join(run.get("text", "") for run in part).strip() for part in parts) if text)


def track_from_row(row: dict, album="", artist="", thumbnail="") -> dict | None:
    """Una fila de lista (búsqueda, página del artista, álbum o playlist)."""
    flex = [c.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text") for c in row.get("flexColumns", [])]
    video_id = (row.get("playlistItemData") or {}).get("videoId") or first(row, "videoId")
    title = text_of(flex[0]) if flex else ""
    found_artist, found_album, duration = "", "", 0
    for index, column in enumerate(flex[1:], start=1):
        for part in segments(column):
            text = "".join(run.get("text", "") for run in part).strip()
            browse = first(part, "browseId", "") or ""
            if not text:
                continue
            if DURATION.match(text):
                duration = duration or seconds(text)
            elif browse.startswith("MPRE"):
                found_album = found_album or text
            elif browse.startswith("UC"):
                found_artist = (found_artist + ", " if found_artist else "") + text
            elif index == 1 and not found_artist and not text.endswith(("reproducciones", "views", "plays")):
                found_artist = text
    for column in row.get("fixedColumns", []):
        duration = duration or seconds(text_of(column.get("musicResponsiveListItemFixedColumnRenderer", {}).get("text")))
    return make_track(video_id, title, found_artist or artist, found_album or album, duration,
                      best_thumbnail(row.get("thumbnail")) or thumbnail, kind_of(row))


def track_from_panel(item: dict) -> dict | None:
    """Un elemento de la lista de "siguientes" (``playlistPanelVideoRenderer``)."""
    parts = segments(item.get("longBylineText"))
    artist = _joined(parts[:1])
    album = ""
    for part in parts[1:]:
        text = "".join(run.get("text", "") for run in part).strip()
        if text and not YEAR.match(text):
            album = album or text
    return make_track(item.get("videoId"), text_of(item.get("title")), artist, album, seconds(text_of(item.get("lengthText"))),
                      best_thumbnail(item.get("thumbnail")), kind_of(item))


def related(video_id: str, count: int = 25, api=post) -> list[dict]:
    """Canciones parecidas a una (la radio de YouTube Music); solo pistas de audio, sin la propia semilla."""
    if count <= 0:
        return []
    data = api("next", {"videoId": video_id, "playlistId": "RDAMVM" + video_id})
    tracks = _radio_tracks(data, {video_id}, count)
    if tracks:
        return tracks
    # A videoclip seed may return nothing but videoclips. Resolve its audio
    # counterpart once instead of retrying the identical, unusable radio.
    for item in walk(data, "playlistPanelVideoRenderer"):
        seed = track_from_panel(item)
        if not seed or seed["id"] != video_id or not seed["artist"]:
            continue
        for candidate in search_songs(seed["artist"] + " " + seed["title"], api=api):
            if candidate["id"] == video_id or not _same_recording(seed, candidate):
                continue
            audio_id = candidate["id"]
            radio = api("next", {"videoId": audio_id, "playlistId": "RDAMVM" + audio_id})
            return _radio_tracks(radio, {video_id, audio_id}, count)
        break
    return []


def _same_recording(seed: dict, candidate: dict) -> bool:
    def normalize(value):
        return re.sub(r"[^\w]+", " ", value.casefold()).strip()
    for field in ("title", "artist"):
        wanted, actual = normalize(seed[field]), normalize(candidate[field])
        if not actual or f" {actual} " not in f" {wanted} ":
            return False
    return True


def _radio_tracks(data: dict, excluded: set[str], count: int) -> list[dict]:
    tracks, seen = [], set(excluded)
    for item in walk(data, "playlistPanelVideoRenderer"):
        track = track_from_panel(item)
        if track and track["id"] not in seen and track["kind"] in ("song", ""):
            seen.add(track["id"])
            tracks.append(track)
    return tracks[:count]


def search_songs(query: str, api=post) -> list[dict]:
    data = api("search", {"query": query, "params": FILTER_SONGS})
    return [t for t in (track_from_row(r) for r in walk(data, "musicResponsiveListItemRenderer")) if t and t["kind"] in ("song", "")]


def _artist_from_row(row: dict) -> dict | None:
    flex = [c.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text") for c in row.get("flexColumns", [])]
    browse = first(row.get("navigationEndpoint", {}), "browseId", "") or ""
    if not flex or not browse.startswith("UC"):
        return None
    return {"name": text_of(flex[0]), "browse_id": browse, "photo": resize_photo(best_thumbnail(row.get("thumbnail")), 226)}


def search_artists(query: str, api=post) -> list[dict]:
    data = api("search", {"query": query, "params": FILTER_ARTISTS})
    return [a for a in (_artist_from_row(r) for r in walk(data, "musicResponsiveListItemRenderer")) if a]


def normalize(text: str) -> str:
    return re.sub(r"[^\w]+", " ", str(text or "").casefold()).strip()


def best_artist(name: str, candidates: list[dict]) -> dict | None:
    """El artista con ese nombre exacto (ignorando mayúsculas y signos); si no, el primer resultado."""
    wanted = normalize(name)
    for candidate in candidates:
        if normalize(candidate["name"]) == wanted:
            return candidate
    return candidates[0] if candidates else None


def find_artist(name: str, api=post) -> dict | None:
    return best_artist(name, search_artists(name, api))


def _album_item(item: dict, kind: str) -> dict | None:
    browse = first(item.get("navigationEndpoint", {}), "browseId", "") or ""
    if not browse.startswith("MPRE"):
        return None
    subtitle = [run.get("text", "") for run in runs(item.get("subtitle")) if run.get("text", "").strip() != SEPARATOR]
    year = next((text for text in subtitle if YEAR.match(text.strip())), "")
    return {"browse_id": browse, "title": text_of(item.get("title")), "year": year, "kind": kind,
            "thumbnail": best_thumbnail(item.get("thumbnailRenderer") or item.get("thumbnail"))}


def artist_page(browse_id: str, api=post) -> dict:
    """Foto, canciones más escuchadas y discografía de un artista."""
    data = api("browse", {"browseId": browse_id})
    header = first(data, "musicImmersiveHeaderRenderer") or first(data, "musicVisualHeaderRenderer") or {}
    page = {"browse_id": browse_id, "name": text_of(header.get("title")), "photo": resize_photo(best_thumbnail(header.get("thumbnail")), 544),
            "subscribers": text_of(first(header, "subscriberCountText")), "top_songs": [], "songs_playlist": "", "albums": [], "singles": []}
    for section in walk(data, "sectionListRenderer"):
        for block in section.get("contents", []):
            shelf = block.get("musicShelfRenderer")
            if shelf and not page["top_songs"]:
                page["songs_playlist"] = first(shelf.get("bottomEndpoint", {}), "browseId", "") or ""
                for item in shelf.get("contents", []):
                    track = track_from_row(item.get("musicResponsiveListItemRenderer", {}), artist=page["name"])
                    if track:
                        page["top_songs"].append(track)
            carousel = block.get("musicCarouselShelfRenderer")
            if carousel:
                title = text_of((carousel.get("header") or {}).get("musicCarouselShelfBasicHeaderRenderer", {}).get("title")).casefold()
                kind = "single" if ("single" in title or "sencillo" in title or re.search(r"\bep\b", title)) else \
                    "album" if ("álbum" in title or "album" in title) else ""
                for item in carousel.get("contents", []):
                    album = _album_item(item.get("musicTwoRowItemRenderer", {}), kind) if kind else None
                    if album:
                        page["singles" if kind == "single" else "albums"].append(album)
    return page


def _continuation(items: list) -> str:
    for item in items:
        token = first(item.get("continuationItemRenderer", {}), "token") if isinstance(item, dict) else None
        if token:
            return token
    return ""


def playlist_tracks(browse_id: str, limit: int = 300, api=post, artist="") -> list[dict]:
    """Todas las canciones de una lista (p. ej. "todas las canciones" de un artista), con paginación."""
    data = api("browse", {"browseId": browse_id})
    tracks, seen = [], set()
    items = []
    for shelf in walk(data, "musicPlaylistShelfRenderer"):
        items = shelf.get("contents", [])
        break
    while True:
        for item in items:
            track = track_from_row(item.get("musicResponsiveListItemRenderer", {}), artist=artist) if isinstance(item, dict) else None
            if track and track["id"] not in seen:
                seen.add(track["id"])
                tracks.append(track)
        token = _continuation(items)
        if not token or len(tracks) >= limit:
            break
        more = api("browse", {"continuation": token})
        items = first(more, "continuationItems", []) or []
        if not items:
            break
    return tracks[:limit]


def album_page(browse_id: str, api=post) -> dict:
    """Título, año, portada y canciones de un álbum."""
    data = api("browse", {"browseId": browse_id})
    header = first(data, "musicResponsiveHeaderRenderer") or first(data, "musicDetailHeaderRenderer") or {}
    subtitle = [run.get("text", "") for run in runs(header.get("subtitle")) if run.get("text", "").strip() != SEPARATOR]
    year = next((text for text in subtitle if YEAR.match(text.strip())), "")
    artist = text_of(first(header, "straplineTextOne")) or (subtitle[1] if len(subtitle) > 1 and not YEAR.match(subtitle[1]) else "")
    title, cover = text_of(header.get("title")), best_thumbnail(header.get("thumbnail"))
    tracks = []
    for shelf in walk(data, "musicShelfRenderer"):
        for item in shelf.get("contents", []):
            track = track_from_row(item.get("musicResponsiveListItemRenderer", {}), album=title, artist=artist, thumbnail=cover)
            if track:
                tracks.append(track)
    return {"browse_id": browse_id, "title": title, "year": year, "artist": artist, "thumbnail": cover, "tracks": tracks}
