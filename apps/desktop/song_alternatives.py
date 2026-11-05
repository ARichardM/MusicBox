"""Alternativas de una canción: otras versiones en YouTube Music (original, en vivo, otra subida…) de la misma canción."""

from __future__ import annotations

import coincidencias
import ytmusic

SIMILARITY_THRESHOLD = 0.6
MAX_RESULTS = 24


def query_for(track: dict) -> str:
    """La mejor búsqueda: artista y título, sin los datos que no aportan."""
    title = str(track.get("title") or "").strip()
    artist = str(track.get("artist") or track.get("channel") or track.get("subtitle") or "").split(" · ")[0].strip()
    if not artist or artist.casefold() in ("<unknown>", "unknown", "youtube", "streaming") or artist.casefold() in title.casefold():
        return title
    return f"{artist} - {title}"


def rank_similar(items: list, title: str) -> list:
    """Deja las versiones de la misma canción (mismo título o muy parecido), las más parecidas primero.

    Si ninguna se parece devuelve la lista tal cual, para no mostrar una hoja vacía.
    """
    wanted = coincidencias.normalize(title)
    if not wanted or not items:
        return list(items)
    kept = []
    for item in items:
        found = coincidencias.normalize(item.get("title"))
        score = 1.0 if found and (wanted in found or found in wanted) else coincidencias.similarity(wanted, found)
        if score >= SIMILARITY_THRESHOLD:
            kept.append((score, item))
    kept.sort(key=lambda pair: -pair[0])  # estable: a igual parecido se conserva el orden de la búsqueda
    return [item for _score, item in kept] or list(items)


def find(track: dict, search_songs=ytmusic.search_songs, search_videos=ytmusic.search_videos, own_id: str = "") -> list:
    """Alternativas de ``track``: canciones y videos musicales, sin repetidos ni la versión que ya suena."""
    query = query_for(track)
    if not query:
        return []
    found, failed = [], 0
    for search in (search_songs, search_videos):
        try:
            found.extend(search(query))
        except Exception:
            failed += 1
    if failed == 2 and not found:
        raise RuntimeError("No se pudieron cargar las alternativas.")
    seen, usable = {own_id} if own_id else set(), []
    for item in found:
        identifier = item.get("id", "")
        if identifier and identifier not in seen and (item.get("duration") or 0) > 0:
            seen.add(identifier)
            usable.append(item)
    return rank_similar(usable, str(track.get("title") or ""))[:MAX_RESULTS]
