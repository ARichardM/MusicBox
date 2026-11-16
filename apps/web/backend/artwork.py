"""Resolve the same lightweight, silent animated artwork used by the native apps."""
import json
import re
import time
from functools import lru_cache
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import urlopen


def apple(url, extension):
    parts = urlsplit(url)
    return parts.scheme == 'https' and parts.netloc == 'mvod.itunes.apple.com' and parts.path.endswith(extension)


def read(url):
    with urlopen(url, timeout=4) as response:
        data = response.read(65537)
    if len(data) > 65536:
        raise ValueError('Artwork response too large')
    return data.decode('utf-8')


@lru_cache(maxsize=256)
def cached(title, artist, album, duration, hour):
    query = urlencode({'s': title, 'a': artist, 'al': album, 'd': duration})
    master = json.loads(read('https://artwork.boidu.dev/?' + query)).get('animated')
    if not isinstance(master, str) or not apple(master, '.m3u8'):
        return ''
    lines = read(master).splitlines()
    variants = []
    for line, uri in zip(lines, lines[1:]):
        if not line.startswith('#EXT-X-STREAM-INF:') or 'CODECS="avc1.' not in line:
            continue
        bandwidth = re.search(r'[:,]BANDWIDTH=(\d+)', line)
        url = urljoin(master, uri.strip())
        if bandwidth and apple(url, '.m3u8'):
            variants.append((int(bandwidth[1]), url))
    if not variants:
        return ''
    playlist = min(variants)[1]
    segments = {line.strip() for line in read(playlist).splitlines() if line.strip() and not line.startswith('#')}
    if len(segments) != 1:
        return ''
    video = urljoin(playlist, segments.pop())
    return video if apple(video, '.mp4') else ''


def resolve(title, artist, album='', duration=0):
    if not title.strip() or not artist.strip():
        return ''
    return cached(title, artist, album, duration, int(time.monotonic() // 3600))
