"""Resolve the same lightweight, silent animated artwork used by the native apps."""
from html.parser import HTMLParser
import json
import re
import time
from functools import lru_cache
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import Request, urlopen


def apple(url, extension):
    parts = urlsplit(url)
    return parts.scheme == 'https' and parts.netloc == 'mvod.itunes.apple.com' and parts.path.endswith(extension)


def read(url, limit=65536):
    with urlopen(Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=4) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Artwork response too large')
    return data.decode('utf-8')


class AmbientArtwork(HTMLParser):
    url = ''

    def handle_starttag(self, tag, attrs):
        if self.url or tag != 'amp-ambient-video':
            return
        attributes = dict(attrs)
        url = attributes.get('src', '')
        if 'editorial-video--cover' in attributes.get('class', '') and apple(url, '.m3u8'):
            self.url = url


def catalog_master(title, artist, album, duration):
    query = urlencode({'term': f'{artist} {album or title}', 'country': 'us', 'entity': 'song', 'limit': 30})
    tracks = json.loads(read('https://itunes.apple.com/search?' + query, 512 * 1024)).get('results', [])
    def normalized(value):
        return ' '.join(re.findall(r'[^\W_]+', (value or '').casefold()))
    expected_artist, expected_title, expected_album = map(normalized, (artist, title, album))
    candidates = {}
    for track in tracks:
        collection = track.get('collectionId')
        if not isinstance(collection, int) or normalized(track.get('artistName')) != expected_artist:
            continue
        exact_title = normalized(track.get('trackName')) == expected_title
        exact_album = bool(expected_album) and normalized(track.get('collectionName')) == expected_album
        if not (exact_title or exact_album):
            continue
        delta = abs((track.get('trackTimeMillis') or 0) / 1000 - duration) if duration and exact_title else 0
        score = (not exact_album, not exact_title, delta > 12, delta)
        candidates[collection] = min(score, candidates.get(collection, score))
    for collection in sorted(candidates, key=candidates.get)[:3]:
        parser = AmbientArtwork()
        try:
            parser.feed(read(f'https://music.apple.com/us/album/{collection}', 2 * 1024 * 1024))
        except (OSError, ValueError):
            continue
        if parser.url:
            return parser.url
    return ''


@lru_cache(maxsize=256)
def cached(title, artist, album, duration, quality, hour):
    try:
        master = catalog_master(title, artist, album, duration)
    except (OSError, ValueError):
        master = ''
    if not master:
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
            dimensions = re.search(r'RESOLUTION=(\d+)x(\d+)', line)
            pixels = min(map(int, dimensions.groups())) if dimensions else 0
            variants.append((pixels, int(bandwidth[1]), url))
    target = {'low': 480, 'medium': 720, 'high': 1080}.get(quality, 720)
    variants.sort(key=lambda variant: (variant[0] > target, abs(target - variant[0]), variant[1]))
    for _, _, playlist in variants:
        segments = {line.strip() for line in read(playlist).splitlines() if line.strip() and not line.startswith('#')}
        if len(segments) == 1:
            video = urljoin(playlist, segments.pop())
            if apple(video, '.mp4'):
                return video
    return ''


def resolve(title, artist, album='', duration=0, quality='medium'):
    if not title.strip() or not artist.strip():
        return ''
    return cached(title, artist, album, duration, quality, int(time.monotonic() // 3600))
