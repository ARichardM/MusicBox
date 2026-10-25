"""Identidad común de las canciones entre Windows y Android.

Una canción se identifica por su ID de YouTube cuando se conoce: las playlists guardan ``youtube:<id>`` y
cada dispositivo decide si la reproduce desde su archivo descargado o por internet. Los archivos locales
se asocian a su ID por título una vez; las rutas solo se conservan para canciones sin ID.
"""
import re
import unicodedata

from click_stats import normalize_title

_SEPARATORS = re.compile(r"[\W_]+", re.UNICODE)


def loose_title(title):
    """Título normalizado conservando lo que va entre paréntesis (los nombres de archivo suelen incluirlo)."""
    text = unicodedata.normalize("NFKD", str(title or ""))
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn").lower()
    return _SEPARATORS.sub(" ", text).strip()

PREFIX = "youtube:"


def bare_id(entry):
    entry = str(entry or "")
    return entry[len(PREFIX):] if entry.startswith(PREFIX) else ""


def entry_for(video_id):
    return PREFIX + video_id if video_id else ""


def normalize_online_tracks(raw):
    """Datos de la nube (clave sin prefijo, como en Android) → {"youtube:<id>": meta completa}."""
    result = {}
    if not isinstance(raw, dict):
        return result
    for key, meta in raw.items():
        if not isinstance(meta, dict):
            continue
        video_id = bare_id(key) or str(key)
        channel = meta.get("channel") or meta.get("subtitle") or "YouTube"
        result[entry_for(video_id)] = {
            "id": video_id,
            "title": meta.get("title", ""),
            "channel": channel,
            "subtitle": meta.get("subtitle") or channel,
            "duration": meta.get("duration", 0),
            "thumbnail": meta.get("thumbnail") or f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
            "url": meta.get("url") or f"https://www.youtube.com/watch?v={video_id}",
        }
    return result


def export_online_tracks(tracks):
    """{"youtube:<id>": meta} → formato compartido (clave sin prefijo, campos que lee Android)."""
    result = {}
    for key, meta in (tracks or {}).items():
        video_id = meta.get("id") or bare_id(key) or str(key)
        result[video_id] = {
            "title": meta.get("title", ""),
            "channel": meta.get("channel") or meta.get("subtitle") or "YouTube",
            "duration": int(meta.get("duration") or 0),
            "thumbnail": meta.get("thumbnail", ""),
            "url": meta.get("url", ""),
        }
    return result


def match_local_ids(local_rows, online_tracks, known=None):
    """{ruta: id} para archivos locales cuyo título coincide con una canción de YouTube conocida."""
    by_title = {}
    for key, meta in (online_tracks or {}).items():
        for title in (normalize_title(meta.get("title", "")), loose_title(meta.get("title", ""))):
            if title:
                by_title.setdefault(title, meta.get("id") or bare_id(key))
    result = dict(known or {})
    for row in local_rows:
        path = row.get("path", "")
        if not path or path in result:
            continue
        video_id = by_title.get(normalize_title(row.get("title", ""))) or by_title.get(loose_title(row.get("title", "")))
        if video_id:
            result[path] = video_id
    return result


def canonicalize(playlists, path_ids, online_tracks):
    """Cambia rutas con ID conocido por ``youtube:<id>`` y quita repetidos. Devuelve (playlists, cambió)."""
    changed = False
    result = {}
    for name, entries in playlists.items():
        seen, cleaned = set(), []
        for entry in entries:
            video_id = bare_id(entry) or path_ids.get(entry, "")
            key = entry_for(video_id) if video_id and entry_for(video_id) in online_tracks else entry
            if key in seen:
                changed = True
                continue
            seen.add(key)
            if key != entry:
                changed = True
            cleaned.append(key)
        result[name] = cleaned
    return result, changed
