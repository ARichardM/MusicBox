"""Recomendaciones "Para ti" a partir de lo que el usuario escucha.

Se eligen como semillas las canciones de YouTube con más puntaje reciente (ver
``ClickStats.seeds``), se pide a YouTube las canciones relacionadas con cada una y se mezclan las
listas: una canción que aparece cerca del inicio de varias listas sube. La lógica es pura y las
funciones de red se inyectan, así que se prueba sin conexión.
"""
from __future__ import annotations

import re
import time

from click_stats import normalize_title

SEED_COUNT = 4
PER_SEED = 15
MAX_SONGS = 20
MAX_ARTISTS = 8
MAX_SECONDS = 600
CACHE_SECONDS = 6 * 3600
CACHE_ENTRIES = 3
CACHE_VERSION = "v2:"  # súbela cuando cambie el formato del resultado guardado
GENERIC_QUERY = "éxitos música"
_TOPIC = re.compile(r"\s*-\s*topic$", re.IGNORECASE)


def artist_name(entry: dict) -> str:
    name = entry.get("artist") or entry.get("channel") or entry.get("uploader") or entry.get("subtitle") or ""
    return _TOPIC.sub("", str(name)).strip()


_ARTIST_SPLIT = re.compile(r"\s*(?:,|&|\band\b|\by\b|\bfeat\.?\s|\bft\.?\s)\s*", re.IGNORECASE)


def split_artists(text: str) -> list[str]:
    """"Bad Bunny & Jhay Cortez" -> ["Bad Bunny", "Jhay Cortez"]; cada uno cuenta por separado."""
    return [part.strip() for part in _ARTIST_SPLIT.split(str(text or "")) if part.strip()]


AUTOPLAY_BATCH = 15


def pick_autoplay(related, queued_ids=()) -> list[dict]:
    """Canciones parecidas que se agregan al final de la cola: sin repetir, sin directos ni mixes largos."""
    taken, picked = set(queued_ids), []
    for track in related or []:
        video_id = track.get("id")
        if not video_id or not track.get("url") or video_id in taken:
            continue
        if (track.get("duration") or 0) > MAX_SECONDS:
            continue
        taken.add(video_id)
        picked.append(dict(track))
        if len(picked) >= AUTOPLAY_BATCH:
            break
    return picked


def to_item(entry: dict) -> dict | None:
    """Convierte una entrada plana de yt-dlp en el diccionario que usa la interfaz."""
    video_id = str(entry.get("id") or "").strip()
    title = str(entry.get("title") or "").strip()
    if not video_id or not title:
        return None
    duration = entry.get("duration")
    return {"id": video_id, "title": title, "subtitle": " · ".join(p for p in (artist_name(entry), str(entry.get("album") or "")) if p),
            "artist": artist_name(entry), "album": str(entry.get("album") or ""),
            "thumbnail": entry.get("thumbnail") or f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
            "url": f"https://www.youtube.com/watch?v={video_id}",
            "duration": int(duration) if isinstance(duration, (int, float)) else 0}


def combine(results, exclude_ids=(), exclude_titles=(), limit=MAX_SONGS):
    """Mezcla listas de relacionadas. ``results`` es [(peso_de_la_semilla, [entradas])].

    Devuelve ``{"songs": [...], "artists": [...]}``. Se descartan las ya conocidas, los
    duplicados (por id o por título normalizado) y los videos demasiado largos.
    """
    excluded_ids = {str(value) for value in exclude_ids}
    excluded_titles = {normalize_title(value) for value in exclude_titles}
    scores: dict[str, float] = {}
    items: dict[str, dict] = {}
    for weight, entries in results:
        for rank, entry in enumerate(entries):
            item = to_item(entry)
            if not item or item["id"] in excluded_ids:
                continue
            if item["duration"] and item["duration"] > MAX_SECONDS:
                continue
            scores[item["id"]] = scores.get(item["id"], 0.0) + weight / (1 + rank)
            items.setdefault(item["id"], item)
    songs, seen_titles = [], set(excluded_titles)
    for video_id in sorted(scores, key=lambda value: (-scores[value], value)):
        title_key = normalize_title(items[video_id]["title"])
        if title_key in seen_titles:
            continue
        seen_titles.add(title_key)
        songs.append(items[video_id])
        if len(songs) >= limit:
            break
    weights: dict[str, float] = {}
    for song in songs:
        for name in split_artists(song.get("artist") or song["subtitle"]):
            weights[name] = weights.get(name, 0.0) + scores[song["id"]]
    artists = sorted(weights, key=lambda name: (-weights[name], name.casefold()))[:MAX_ARTISTS]
    return {"songs": songs, "artists": artists}


class Recommender:
    """Calcula "Para ti" con caché de 6 horas por conjunto de semillas.

    ``fetch_related(video_id)`` y ``fetch_search(query)`` devuelven listas de entradas de yt-dlp.
    """

    def __init__(self, fetch_related, fetch_search, clock=time.time, ttl=CACHE_SECONDS):
        self._related, self._search = fetch_related, fetch_search
        self._clock, self._ttl = clock, ttl
        self._cache: dict[str, tuple[float, dict]] = {}

    def snapshot(self) -> dict:
        return {signature: {"at": at, "result": result} for signature, (at, result) in self._cache.items()}

    def restore(self, data) -> None:
        if not isinstance(data, dict):
            return
        for signature, entry in data.items():
            try:
                self._cache[str(signature)] = (float(entry["at"]), dict(entry["result"]))
            except (KeyError, TypeError, ValueError):
                continue

    def compute(self, stats, known_ids=(), known_titles=(), recent_queries=(), now=None, fresh=False) -> dict:
        """Devuelve ``{"songs", "artists", "mode"}``; ``mode`` es seeds, queries, trending o offline.

        Con ``fresh`` no se usa la caché vigente (el usuario pidió actualizar o abrió la app), pero si no hay
        conexión se sigue mostrando lo último guardado.
        """
        seeds = stats.seeds(SEED_COUNT, now)
        if seeds:
            mode, signature = "seeds", CACHE_VERSION + "seeds:" + ",".join(key for key, _title, _score in seeds)
        elif recent_queries:
            mode, signature = "queries", CACHE_VERSION + "queries:" + "|".join(str(q) for q in list(recent_queries)[:2])
        else:
            mode, signature = "trending", CACHE_VERSION + "trending"
        cached = self._cache.get(signature)
        if cached and not fresh and self._clock() - cached[0] < self._ttl:
            return dict(cached[1])
        results, exclude, exclude_titles = [], set(known_ids), list(known_titles)
        if mode == "seeds":
            top = seeds[0][2] or 1.0
            for key, _title, score in seeds:
                video_id = key[3:]
                exclude.add(video_id)
                exclude_titles.append(_title)
                entries = self._safe(self._related, video_id)
                if entries:
                    results.append((score / top, entries))
        else:
            queries = list(recent_queries)[:2] if mode == "queries" else [GENERIC_QUERY]
            for query in queries:
                entries = self._safe(self._search, query)
                if entries:
                    results.append((1.0, entries))
        if not results:
            if cached:
                return dict(cached[1])
            return {"songs": [], "artists": [], "mode": "offline"}
        outcome = combine(results, exclude, exclude_titles)
        outcome["mode"] = mode
        self._cache[signature] = (self._clock(), outcome)
        for stale in sorted(self._cache, key=lambda name: self._cache[name][0])[:-CACHE_ENTRIES]:
            del self._cache[stale]
        return dict(outcome)

    @staticmethod
    def _safe(fetch, argument):
        try:
            return [entry for entry in (fetch(argument) or []) if isinstance(entry, dict)]
        except Exception:
            return []
