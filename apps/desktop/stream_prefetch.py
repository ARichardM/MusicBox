"""Precarga de canciones online: qué conviene descargar antes y limpieza de la caché."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

PREFETCH_LIMIT = 4
MAX_AGE_SECONDS = 24 * 3600


def is_stream(item: dict | None) -> bool:
    return bool(item and item.get("url") and not item.get("path"))


def upcoming_streams(current: dict | None, queue: list, index: int, repeat_mode: int) -> list[str]:
    """Enlaces a precargar: la canción online actual y la siguiente de la cola en curso.

    ``repeat_mode`` sigue la app: 0 sin repetir, 1 repetir todo y 2 repetir una.
    """
    urls = [current["url"]] if is_stream(current) else []
    if repeat_mode != 2 and queue and 0 <= index < len(queue):
        following = index + 1
        if following >= len(queue) and repeat_mode == 1:
            following = 0
        if following < len(queue) and following != index and is_stream(queue[following]):
            url = queue[following]["url"]
            if url not in urls:
                urls.append(url)
    return urls


def prune_cache(folder: Path, max_age: float = MAX_AGE_SECONDS, now: float | None = None) -> int:
    """Borra audios temporales antiguos; ignora los que Windows mantiene abiertos."""
    if not folder.is_dir():
        return 0
    limit = (time.time() if now is None else now) - max_age
    removed = 0
    for path in folder.iterdir():
        try:
            if path.stat().st_mtime >= limit:
                continue
            if path.is_file():
                path.unlink()
                removed += 1
            elif path.is_dir() and path.name.startswith("stream-"):  # Descargas interrumpidas.
                shutil.rmtree(path, ignore_errors=True)
                removed += 1
        except OSError:
            pass
    return removed
