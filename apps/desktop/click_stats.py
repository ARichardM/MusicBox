"""Clics (reproducciones) compartidos entre Windows y Android.

Cada dispositivo solo escribe su propio contador por canción y el total es la suma de todos los
dispositivos. Al mezclar se conserva el máximo por dispositivo y canción, así que sincronizar en
cualquier orden o varias veces nunca duplica ni pierde clics. La canción se identifica con una
clave común a ambas plataformas: ``yt:<id>`` para YouTube o ``t:<título normalizado>``.

Además se guarda cuántas veces se escuchó cada canción por día (UTC) durante los últimos
``DAILY_DAYS`` días. Es otro contador que solo crece, así que se mezcla igual (máximo por
dispositivo, canción y día) y alimenta las recomendaciones, donde lo reciente pesa más.
"""
import math
import re
import threading
import unicodedata
from datetime import datetime, timedelta, timezone

SCHEMA = 1
DAILY_DAYS = 90
HALF_LIFE_DAYS = 14.0
ALL_TIME_WEIGHT = 0.1
_KNOWN = {"v", "devices", "titles", "daily", "listening"}
_BRACKETS = re.compile(r"[(\[][^)\]]*[)\]]")
_SEPARATORS = re.compile(r"[\W_]+", re.UNICODE)


def normalize_title(title):
    text = unicodedata.normalize("NFKD", str(title or ""))
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn").lower()
    without_brackets = _BRACKETS.sub(" ", text)
    cleaned = _SEPARATORS.sub(" ", without_brackets).strip()
    return cleaned or _SEPARATORS.sub(" ", text).strip()


def song_key(title, video_id=""):
    if video_id:
        return "yt:" + str(video_id)
    return "t:" + normalize_title(title)


class ClickStats:
    def __init__(self, device_id, data=None):
        self.device_id = device_id
        self._lock = threading.RLock()
        self._devices = {}
        self._titles = {}
        self._daily = {}
        self._extra = {}
        self._listening = {}
        if data:
            self.merge(data)

    @staticmethod
    def _day(now=None):
        return (now or datetime.now(timezone.utc)).astimezone(timezone.utc).strftime("%Y-%m-%d")

    @staticmethod
    def _cutoff(now=None):
        moment = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        return (moment - timedelta(days=DAILY_DAYS)).strftime("%Y-%m-%d")

    def record(self, key, title="", now=None):
        with self._lock:
            bucket = self._devices.setdefault(self.device_id, {})
            bucket[key] = int(bucket.get(key, 0)) + 1
            days = self._daily.setdefault(self.device_id, {}).setdefault(key, {})
            today = self._day(now)
            days[today] = int(days.get(today, 0)) + 1
            self._prune(now)
            if title:
                self._titles[key] = str(title)

    def _prune(self, now=None):
        cutoff = self._cutoff(now)
        for device in list(self._daily):
            keys = self._daily[device]
            for key in list(keys):
                keys[key] = {day: n for day, n in keys[key].items() if day >= cutoff}
                if not keys[key]:
                    del keys[key]
            if not keys:
                del self._daily[device]

    def score(self, key, now=None):
        """Puntaje de gusto: lo reciente pesa más (vida media de 14 días) más un poco del total."""
        with self._lock:
            today = datetime.strptime(self._day(now), "%Y-%m-%d")
            weighted = 0.0
            for keys in self._daily.values():
                for day, count in keys.get(key, {}).items():
                    age = max(0, (today - datetime.strptime(day, "%Y-%m-%d")).days)
                    weighted += int(count) * 0.5 ** (age / HALF_LIFE_DAYS)
            return weighted + ALL_TIME_WEIGHT * math.log1p(self.count(key))

    def seeds(self, limit=4, now=None):
        """[(clave, título, puntaje)] de las canciones de YouTube que más te gustan ahora."""
        with self._lock:
            keys = {key for bucket in self._devices.values() for key, n in bucket.items()
                    if key.startswith("yt:") and int(n) > 0}
            ranked = sorted(((key, self.score(key, now)) for key in keys), key=lambda pair: (-pair[1], pair[0]))
            return [(key, self._titles.get(key, key), score) for key, score in ranked[:limit]]

    def count(self, key):
        with self._lock:
            return sum(int(bucket.get(key, 0)) for bucket in self._devices.values())

    def total(self):
        with self._lock:
            return sum(int(value) for bucket in self._devices.values() for value in bucket.values())

    def played_songs(self):
        with self._lock:
            return len({key for bucket in self._devices.values() for key, value in bucket.items() if int(value) > 0})

    def top(self, limit=3):
        """[(key, title, clicks)] ordenado de mayor a menor."""
        with self._lock:
            totals = {}
            for bucket in self._devices.values():
                for key, value in bucket.items():
                    totals[key] = totals.get(key, 0) + int(value)
            ranked = sorted(totals.items(), key=lambda pair: (-pair[1], self._titles.get(pair[0], pair[0]).casefold()))
            return [(key, self._titles.get(key, key), clicks) for key, clicks in ranked[:limit] if clicks > 0]

    def import_legacy(self, key, count, title=""):
        """Migra un contador antiguo (por ruta) a este dispositivo sin duplicar si se repite."""
        if count <= 0:
            return False
        with self._lock:
            bucket = self._devices.setdefault(self.device_id, {})
            if int(bucket.get(key, 0)) >= count:
                return False
            bucket[key] = int(count)
            if title:
                self._titles.setdefault(key, str(title))
            return True

    def record_listening(self, key, title="", artist="", thumbnail="", millis=0, start=False, playlist=""):
        """Measured playback only. Playlist activity belongs to the source, not playlist membership."""
        with self._lock:
            bucket = self._listening.setdefault(self.device_id, {})
            for entry, name in [(key, title)] + ([("playlist:" + playlist, playlist)] if playlist else []):
                row = bucket.setdefault(entry, {"ms": 0, "plays": 0})
                row["ms"] += max(0, int(millis))
                row["plays"] += int(bool(start))
                row.update(title=name, artist=artist if entry == key else "", thumbnail=thumbnail)

    def listening_rows(self, playlists=False):
        with self._lock:
            result = {}
            for bucket in self._listening.values():
                for key, source in bucket.items():
                    if key.startswith("playlist:") != playlists:
                        continue
                    row = result.setdefault(key, dict(source, key=key, ms=0, plays=0))
                    row["ms"] += int(source.get("ms", 0))
                    row["plays"] += int(source.get("plays", 0))
            if not playlists:
                for key, title, plays in self.top(limit=1000000):
                    result.setdefault(key, {"key": key, "title": title, "ms": 0})["plays"] = plays
            return sorted(result.values(), key=lambda row: (-row.get("plays", 0), -row.get("ms", 0), row.get("title", "")))

    def merge(self, data):
        """Mezcla datos remotos (máximo por dispositivo y canción). Devuelve True si algo cambió."""
        if not isinstance(data, dict):
            return False
        changed = False
        with self._lock:
            devices = data.get("devices")
            for device, remote in (devices.items() if isinstance(devices, dict) else ()):
                if not isinstance(remote, dict):
                    continue
                bucket = self._devices.setdefault(str(device), {})
                for key, value in remote.items():
                    try:
                        value = int(value)
                    except (TypeError, ValueError):
                        continue
                    if value > int(bucket.get(key, 0)):
                        bucket[key] = value
                        changed = True
            titles = data.get("titles")
            for key, title in (titles.items() if isinstance(titles, dict) else ()):
                if key not in self._titles and title:
                    self._titles[key] = str(title)
                    changed = True
            changed = self._merge_daily(data.get("daily")) or changed
            listening = data.get("listening", {})
            if isinstance(listening, dict):
                for device, incoming in listening.items():
                    if not isinstance(incoming, dict):
                        continue
                    mine = self._listening.setdefault(str(device), {})
                    for key, source in incoming.items():
                        if not isinstance(source, dict):
                            continue
                        if key not in mine:
                            mine[key] = dict(source)
                            changed = True
                        else:
                            for field in ("ms", "plays"):
                                try:
                                    value = max(0, int(source.get(field, 0)))
                                except (ValueError, TypeError):
                                    continue
                                if value > int(mine[key].get(field, 0)):
                                    mine[key][field] = value
                                    changed = True
            for name, value in data.items():
                if name not in _KNOWN and name not in self._extra:
                    self._extra[name] = value
        return changed

    def _merge_daily(self, remote):
        if not isinstance(remote, dict):
            return False
        cutoff = self._cutoff()
        changed = False
        for device, keys in remote.items():
            if not isinstance(keys, dict):
                continue
            mine = self._daily.setdefault(str(device), {})
            for key, days in keys.items():
                if not isinstance(days, dict):
                    continue
                bucket = mine.setdefault(key, {})
                for day, value in days.items():
                    try:
                        value = int(value)
                        datetime.strptime(str(day), "%Y-%m-%d")
                    except (TypeError, ValueError):
                        continue
                    if day >= cutoff and value > int(bucket.get(day, 0)):
                        bucket[day] = value
                        changed = True
                if not bucket:
                    del mine[key]
            if not mine:
                del self._daily[str(device)]
        return changed

    def export(self):
        with self._lock:
            self._prune()
            data = dict(self._extra)
            data.update({"v": SCHEMA,
                         "devices": {device: dict(bucket) for device, bucket in self._devices.items()},
                         "titles": dict(self._titles),
                         "listening": {device: {key: dict(row) for key, row in bucket.items()} for device, bucket in self._listening.items()},
                         "daily": {device: {key: dict(days) for key, days in keys.items()}
                                   for device, keys in self._daily.items()}})
            return data
