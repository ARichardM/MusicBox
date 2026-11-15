"""Resolve a small Apple Music artwork stream without storing media."""

import json
import re
import time
from functools import lru_cache
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import urlopen


def _apple_playlist(url):
    parts = urlsplit(url)
    return parts.scheme == "https" and parts.netloc == "mvod.itunes.apple.com" and parts.path.endswith(".m3u8")


@lru_cache(maxsize=256)
def _resolve(title, artist, album, duration, hour):
    params = {"s": title, "a": artist}
    if album:
        params["al"] = album
    if duration > 0:
        params["d"] = duration
    query = urlencode(params)
    with urlopen("https://artwork.boidu.dev/?" + query, timeout=4) as response:
        data = json.loads(response.read(65536))
    master = data.get("animated")
    if not isinstance(master, str) or not _apple_playlist(master):
        return ""
    with urlopen(master, timeout=4) as response:
        lines = response.read(65536).decode("utf-8").splitlines()
    variants = []
    for line, uri in zip(lines, lines[1:]):
        if not line.startswith("#EXT-X-STREAM-INF:") or 'CODECS="avc1.' not in line:
            continue
        bandwidth = re.search(r"[:,]BANDWIDTH=(\d+)", line)
        url = urljoin(master, uri)
        if bandwidth and not uri.startswith("#") and _apple_playlist(url):
            variants.append((int(bandwidth[1]), url))
    return min(variants, default=(0, ""))[1]


def resolve(title, artist, album, duration=0):
    return _resolve(title, artist, album, duration, int(time.monotonic() // 3600))
