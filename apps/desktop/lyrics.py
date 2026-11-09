"""Letras de canciones: archivos .lrc y etiquetas locales, LRCLIB sincronizado (LRC) y respaldo en YouTube Music."""

from __future__ import annotations

from bisect import bisect_right
from collections import OrderedDict
import json
from pathlib import Path
import re
from urllib.parse import urlencode, parse_qs, urlsplit
import urllib.request

import coincidencias
import ytmusic

LRCLIB_SEARCH_URL = "https://lrclib.net/api/search"
USER_AGENT = "MusicBox/2.4 (https://github.com/ARichardM/MusicBox)"
CACHE_LIMIT = 128

TIME_TAG = re.compile(r"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]")
OFFSET_TAG = re.compile(r"^\s*\[offset:\s*([+-]?\d+)\s*\]\s*$", re.IGNORECASE)
META_TAG = re.compile(r"^\s*\[(?:ar|ti|al|au|by|length|re|ve|id|tool|la):[^\]]*\]\s*$", re.IGNORECASE)
INLINE_TIME_TAG = re.compile(r"<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>")

NOISY_BRACKETS = re.compile(
    r"[\(\[\{][^\)\]\}]*\b(?:"
    r"official(?:\s+music|\s+lyric|\s+hd|\s+hq)?(?:\s+video|\s+audio|\s+visualizer)?|"
    r"video\s+oficial|audio\s+oficial|lyric\s+video|letra(?:\s+oficial)?|lyrics?|"
    r"visualizer|hd|hq|4k|1080p|720p|remaster(?:ed|izado)?(?:\s+\d{4})?|"
    r"en\s+vivo|live(?:\s+at\b|\s+from\b|\s+version|\s+performance)?|"
    r"feat\.?|ft\.?|prod\.?|con\s+letra|sub\s+espa[ñn]ol|videoclip"
    r")\b[^\)\]\}]*[\)\]\}]",
    re.IGNORECASE,
)
TRAILING_YT_ID = re.compile(r"\s*[\[\(][A-Za-z0-9_-]{11}[\]\)]\s*$")
FEAT_SUFFIX = re.compile(r"\s+(?:feat\.?|ft\.?)\s+[^-\(\[\]]+$", re.IGNORECASE)
ARTIST_SUFFIX = re.compile(r"\s*(?:-\s*topic|vevo|official(?:\s+channel)?)\s*$", re.IGNORECASE)
PLACEHOLDER_ARTISTS = {
    "", "<unknown>", "unknown", "unknown artist", "artista desconocido",
    "youtube", "streaming", "mp3", "mp4", "m4a", "flac", "wav", "ogg", "aac", "webm", "mkv",
}

_CACHE: OrderedDict[str, dict] = OrderedDict()


def _fraction_to_ms(raw: str | None) -> int:
    if not raw:
        return 0
    if len(raw) == 1:
        return int(raw) * 100
    if len(raw) == 2:
        return int(raw) * 10
    return int(raw[:3])


def parse_lrc(text: str) -> list[dict]:
    """Convierte texto LRC (``[mm:ss.xx] verso``) en líneas ordenadas con ``timeMs`` y ``text``."""
    if not text:
        return []
    offset_ms = 0
    entries: list[tuple[int, int, str]] = []
    for order, raw_line in enumerate(str(text).splitlines()):
        offset_match = OFFSET_TAG.match(raw_line)
        if offset_match:
            try:
                offset_ms = int(offset_match.group(1))
            except ValueError:
                pass
            continue
        if META_TAG.match(raw_line):
            continue
        matches = list(TIME_TAG.finditer(raw_line))
        if not matches:
            continue
        lyric = TIME_TAG.sub("", raw_line)
        lyric = INLINE_TIME_TAG.sub("", lyric)
        lyric = " ".join(lyric.split()).strip()
        if not lyric:
            continue
        for match in matches:
            minutes = int(match.group(1))
            seconds = int(match.group(2))
            millis = _fraction_to_ms(match.group(3))
            stamp = max(0, minutes * 60_000 + seconds * 1_000 + millis - offset_ms)
            entries.append((stamp, order, lyric))
    entries.sort(key=lambda item: (item[0], item[1]))
    return [{"timeMs": stamp, "text": lyric} for stamp, _order, lyric in entries]


def plain_lines(text: str) -> list[dict]:
    """Divide una letra sin marcas de tiempo en líneas limpias (``timeMs = -1``)."""
    if not text:
        return []
    result = []
    for raw_line in str(text).splitlines():
        if META_TAG.match(raw_line) or OFFSET_TAG.match(raw_line):
            continue
        cleaned = " ".join(TIME_TAG.sub("", INLINE_TIME_TAG.sub("", raw_line)).split()).strip()
        if cleaned:
            result.append({"timeMs": -1, "text": cleaned})
    return result


def active_line_index(lines: list[dict], position_ms: int) -> int:
    """Índice de la línea sincronizada activa en ``position_ms``, o ``-1`` si aún no empieza o no es sincronizada."""
    if not lines or position_ms < 0:
        return -1
    first_time = lines[0].get("timeMs", -1)
    if first_time is None or first_time < 0:
        return -1
    stamps = [int(line.get("timeMs", 0)) for line in lines]
    index = bisect_right(stamps, int(position_ms)) - 1
    return index if index >= 0 else -1


def clean_artist(raw_artist: str) -> str:
    """Limpia sufijos de canal («- Topic», « · Álbum · MP3») y descarta marcadores genéricos."""
    artist = str(raw_artist or "").split(" · ", 1)[0].strip()
    artist = ARTIST_SUFFIX.sub("", artist).strip(" -–—\t")
    if artist.casefold() in PLACEHOLDER_ARTISTS:
        return ""
    return artist


def clean_title(raw_title: str) -> str:
    """Quita etiquetas de video/audio («(Official Video)», «[Letra]», ID de archivo) conservando el nombre de la canción."""
    title = str(raw_title or "").strip()
    title = TRAILING_YT_ID.sub("", title)
    title = NOISY_BRACKETS.sub(" ", title)
    title = FEAT_SUFFIX.sub("", title)
    title = " ".join(title.split()).strip(" -–—\"'«»“”")
    return title or str(raw_title or "").strip()


def clean_query(track: dict) -> tuple[str, str, int]:
    """Extrae ``(título, artista, duración_s)`` limpios a partir de una pista local u online."""
    item = dict(track or {})
    raw_title = str(item.get("title") or "").strip()
    raw_artist = str(item.get("artist") or item.get("channel") or item.get("subtitle") or "")
    artist = clean_artist(raw_artist)
    title = clean_title(raw_title)

    for sep in (" - ", " – ", " — "):
        if sep in title:
            left, right = title.split(sep, 1)
            left_clean, right_clean = left.strip(" \"'«»“”"), right.strip(" \"'«»“”")
            if not left_clean or not right_clean:
                continue
            norm_left = coincidencias.normalize(left_clean)
            norm_right = coincidencias.normalize(right_clean)
            norm_artist = coincidencias.normalize(artist)
            if not artist:
                artist = clean_artist(left_clean)
                title = clean_title(right_clean)
            elif norm_artist and (norm_left == norm_artist or norm_artist in norm_left or norm_left in norm_artist):
                title = clean_title(right_clean)
            elif norm_artist and (norm_right == norm_artist or norm_artist in norm_right or norm_right in norm_artist):
                title = clean_title(left_clean)
            break

    raw_duration = item.get("duration") or 0
    try:
        duration = int(round(float(raw_duration)))
    except (TypeError, ValueError):
        duration = 0
    if duration > 36_000:  # milisegundos accidentales
        duration //= 1000
    return title, artist, max(0, duration)


def _decode_bytes(data: bytes) -> str:
    for encoding in ("utf-8-sig", "utf-8", "cp1252", "latin-1"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def _result_from_raw(raw_text: str, source: str) -> dict | None:
    synced = parse_lrc(raw_text)
    if synced:
        return {"lines": synced, "synced": True, "source": source, "instrumental": False}
    plain = plain_lines(raw_text)
    if plain:
        return {"lines": plain, "synced": False, "source": source, "instrumental": False}
    return None


def read_local(path: str | Path) -> dict | None:
    """Lee un archivo ``.lrc`` junto al audio o la letra incrustada en las etiquetas del archivo."""
    if not path:
        return None
    media_path = Path(path)
    for suffix in (".lrc", ".LRC"):
        lrc_path = media_path.with_suffix(suffix)
        try:
            if lrc_path.is_file():
                parsed = _result_from_raw(_decode_bytes(lrc_path.read_bytes()), "LRC local")
                if parsed:
                    return parsed
        except OSError:
            pass
    if not media_path.is_file():
        return None
    try:
        from mutagen import File
        audio = File(media_path, easy=False)
        tags = getattr(audio, "tags", None) or {}
        for key, value in tags.items():
            key_str = str(key)
            if key_str.startswith("SYLT") and hasattr(value, "text"):
                lines = []
                for item in getattr(value, "text", []) or []:
                    if isinstance(item, tuple) and len(item) == 2:
                        lyric_text, stamp = str(item[0]).strip(), int(item[1] or 0)
                        if lyric_text:
                            lines.append({"timeMs": max(0, stamp), "text": lyric_text})
                if lines:
                    lines.sort(key=lambda entry: entry["timeMs"])
                    return {"lines": lines, "synced": True, "source": "Archivo local", "instrumental": False}
            if key_str.startswith("USLT") or key_str.lower() in ("\xa9lyr", "lyrics", "unsyncedlyrics", "©lyr"):
                raw = getattr(value, "text", value)
                if isinstance(raw, (list, tuple)):
                    raw = "\n".join(str(part) for part in raw if part)
                parsed = _result_from_raw(str(raw or ""), "Archivo local")
                if parsed:
                    return parsed
    except Exception:
        pass
    return None


def _score_lrclib_candidate(candidate: dict, title: str, artist: str, duration: int) -> float:
    wanted_title = coincidencias.normalize(clean_title(title))
    found_title = coincidencias.normalize(clean_title(candidate.get("trackName") or candidate.get("name") or ""))
    if not wanted_title or not found_title:
        return -1.0
    title_sim = 1.0 if (wanted_title == found_title or wanted_title in found_title or found_title in wanted_title) \
        else coincidencias.similarity(wanted_title, found_title)
    if title_sim < 0.55:
        return -1.0

    score = title_sim * 0.65
    if wanted_title == found_title:
        score += 0.15

    wanted_artist = coincidencias.normalize(artist)
    found_artist = coincidencias.normalize(candidate.get("artistName") or "")
    if wanted_artist and found_artist:
        artist_sim = 1.0 if (wanted_artist in found_artist or found_artist in wanted_artist) \
            else coincidencias.similarity(wanted_artist, found_artist)
        if artist_sim < 0.35:
            return -1.0
        score += artist_sim * 0.25

    if candidate.get("syncedLyrics") and parse_lrc(candidate.get("syncedLyrics") or ""):
        score += 0.35
    elif candidate.get("plainLyrics") and plain_lines(candidate.get("plainLyrics") or ""):
        score += 0.10
    elif candidate.get("instrumental"):
        score += 0.05
    else:
        return -1.0

    cand_duration = candidate.get("duration")
    if duration > 0 and isinstance(cand_duration, (int, float)) and cand_duration > 0:
        diff = abs(duration - float(cand_duration))
        if diff <= 3:
            score += 0.20
        elif diff <= 15:
            score += 0.12
        elif diff > 45:
            score -= 0.25

    return score


def _lrclib_request(params: dict, opener=urllib.request.urlopen) -> list[dict]:
    query_string = urlencode({k: v for k, v in params.items() if v})
    request = urllib.request.Request(
        f"{LRCLIB_SEARCH_URL}?{query_string}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    with opener(request, timeout=8) as response:
        data = json.loads(response.read().decode("utf-8"))
    return data if isinstance(data, list) else []


def fetch_lrclib(title: str, artist: str = "", duration: int = 0, opener=urllib.request.urlopen) -> dict | None:
    """Busca la letra en LRCLIB priorizando letras sincronizadas que coincidan en título, artista y duración."""
    title = title.strip()
    artist = artist.strip()
    if not title:
        return None

    queries = []
    if artist:
        queries.append({"track_name": title, "artist_name": artist})
    queries.append({"q": f"{artist} {title}".strip() if artist else title})
    if artist:
        queries.append({"q": title})

    candidates: list[dict] = []
    seen_ids = set()
    had_network_success = False
    last_error: Exception | None = None

    for params in queries:
        try:
            items = _lrclib_request(params, opener=opener)
            had_network_success = True
        except Exception as exc:
            last_error = exc
            continue
        for item in items:
            if not isinstance(item, dict):
                continue
            item_id = item.get("id")
            if item_id in seen_ids:
                continue
            if item_id is not None:
                seen_ids.add(item_id)
            candidates.append(item)
        # Si ya hay una coincidencia sincronizada sólida en la primera consulta, no hace falta seguir buscando.
        if any(_score_lrclib_candidate(c, title, artist, duration) >= 1.15 for c in candidates):
            break

    if not had_network_success and last_error is not None:
        raise last_error

    best_score = -1.0
    best_item = None
    for item in candidates:
        score = _score_lrclib_candidate(item, title, artist, duration)
        if score > best_score:
            best_score = score
            best_item = item
        elif not artist and score < 0:
            # Si el título original incluía al artista sin guion, probamos coincidencias solo por texto.
            alt_score = _score_lrclib_candidate(item, item.get("trackName") or "", "", duration)
            norm_query = coincidencias.normalize(title)
            norm_track = coincidencias.normalize(item.get("trackName") or "")
            norm_artist = coincidencias.normalize(item.get("artistName") or "")
            if norm_track and norm_artist and norm_track in norm_query and norm_artist in norm_query and alt_score > best_score:
                best_score = alt_score
                best_item = item

    if not best_item or best_score < 0:
        return None

    synced = parse_lrc(best_item.get("syncedLyrics") or "")
    if synced:
        return {"lines": synced, "synced": True, "source": "LRCLIB", "instrumental": False}
    if best_item.get("instrumental"):
        return {"lines": [], "synced": False, "source": "LRCLIB", "instrumental": True}
    plain = plain_lines(best_item.get("plainLyrics") or "")
    if plain:
        return {"lines": plain, "synced": False, "source": "LRCLIB", "instrumental": False}
    return None


def _video_id_from_track(track: dict) -> str:
    vid = str(track.get("id") or "").strip()
    if vid:
        return vid
    url = str(track.get("url") or "").strip()
    if url:
        try:
            parsed = urlsplit(url)
            if "v" in parse_qs(parsed.query):
                return parse_qs(parsed.query)["v"][0]
            if parsed.hostname and "youtu.be" in parsed.hostname:
                return parsed.path.strip("/")
        except ValueError:
            pass
    return ""


def _cache_key(track: dict, title: str, artist: str) -> str:
    vid = _video_id_from_track(track)
    norm_title = coincidencias.normalize(title)
    norm_artist = coincidencias.normalize(artist)
    if norm_title:
        return f"{vid}|{norm_artist}|{norm_title}"
    return vid or str(track.get("path") or "")


def clear_cache() -> None:
    _CACHE.clear()


def find(
    track: dict,
    *,
    lrclib_opener=urllib.request.urlopen,
    ytmusic_api=ytmusic.post,
    cache: OrderedDict | None = None,
    use_cache: bool = True,
) -> dict:
    """Obtiene la mejor letra disponible para ``track`` (local → LRCLIB sincronizada → YouTube Music)."""
    item = dict(track or {})
    local_result = read_local(item.get("path") or "")
    if local_result and local_result.get("synced"):
        return local_result

    title, artist, duration = clean_query(item)
    video_id = _video_id_from_track(item)
    store = _CACHE if cache is None else cache
    key = _cache_key(item, title, artist)

    if use_cache and key and key in store:
        store.move_to_end(key)
        return dict(store[key])

    if not title and not video_id:
        return local_result or {"lines": [], "synced": False, "source": "", "instrumental": False}

    lrclib_result = None
    errors = 0
    attempts = 0

    if title:
        attempts += 1
        try:
            lrclib_result = fetch_lrclib(title, artist, duration, opener=lrclib_opener)
        except Exception:
            errors += 1

    if lrclib_result and (lrclib_result.get("synced") or lrclib_result.get("instrumental")):
        if key:
            store[key] = dict(lrclib_result)
            while len(store) > CACHE_LIMIT:
                store.popitem(last=False)
        return lrclib_result

    if local_result and local_result.get("lines"):
        return local_result

    if lrclib_result and lrclib_result.get("lines"):
        if key:
            store[key] = dict(lrclib_result)
            while len(store) > CACHE_LIMIT:
                store.popitem(last=False)
        return lrclib_result

    # Respaldo: letra oficial en YouTube Music (directa por videoId o buscando la canción equivalente).
    yt_result = None
    attempts += 1
    try:
        target_id = video_id
        if not target_id and title:
            query = f"{artist} {title}".strip() if artist else title
            songs = ytmusic.search_songs(query, api=ytmusic_api)
            if songs:
                target_id = songs[0].get("id") or ""
        if target_id:
            yt_data = ytmusic.lyrics(target_id, api=ytmusic_api)
            if yt_data.get("text"):
                yt_result = _result_from_raw(yt_data["text"], yt_data.get("source") or "YouTube Music")
    except Exception:
        errors += 1

    if yt_result and yt_result.get("lines"):
        if key:
            store[key] = dict(yt_result)
            while len(store) > CACHE_LIMIT:
                store.popitem(last=False)
        return yt_result

    if attempts > 0 and errors == attempts:
        raise RuntimeError("No se pudo cargar la letra. Revisa tu conexión.")

    return {"lines": [], "synced": False, "source": "", "instrumental": False}
