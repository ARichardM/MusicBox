"""Clics (reproducciones) compartidos entre Windows y Android.

Cada dispositivo solo escribe su propio contador por canción y el total es la suma de todos los
dispositivos. Al mezclar se conserva el máximo por dispositivo y canción, así que sincronizar en
cualquier orden o varias veces nunca duplica ni pierde clics. La canción se identifica con una
clave común a ambas plataformas: ``yt:<id>`` para YouTube o ``t:<título normalizado>``.
"""
import re
import threading
import unicodedata

SCHEMA = 1
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
        if data:
            self.merge(data)

    def record(self, key, title=""):
        with self._lock:
            bucket = self._devices.setdefault(self.device_id, {})
            bucket[key] = int(bucket.get(key, 0)) + 1
            if title:
                self._titles[key] = str(title)

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
        return changed

    def export(self):
        with self._lock:
            return {"v": SCHEMA,
                    "devices": {device: dict(bucket) for device, bucket in self._devices.items()},
                    "titles": dict(self._titles)}
