"""Controlador de «Ver alternativas»: busca otras versiones de una canción y permite reemplazarla por una de ellas."""

from __future__ import annotations

import threading

from PySide6.QtCore import Property, QObject, Signal, Slot

import i18n
import song_alternatives


class AlternativesController(QObject):
    changed = Signal()
    _found = Signal(object, str, int)

    def __init__(self, bridge, finder=song_alternatives.find, parent=None):
        super().__init__(parent)
        self._bridge = bridge
        self._finder = finder
        self._state = "idle"          # idle, loading, ready, empty, error
        self._track = {}
        self._results = []
        self._replaced = set()
        self._error = ""
        self._generation = 0
        self._found.connect(self._apply)

    @Property(str, notify=changed)
    def state(self):
        return self._state

    @Property("QVariantMap", notify=changed)
    def track(self):
        return self._track

    @Property("QVariantList", notify=changed)
    def results(self):
        return self._results

    @Property("QVariantList", notify=changed)
    def replacedIds(self):
        return sorted(self._replaced)

    @Property(str, notify=changed)
    def error(self):
        return self._error

    @Property(bool, notify=changed)
    def replaceable(self):
        """¿Hay algo que reemplazar? Solo si la canción está en alguna playlist o en la cola."""
        return bool(self._track) and self._bridge.isTrackReplaceable(self._track)

    @Slot("QVariantMap")
    def load(self, track):
        """Empieza a buscar las alternativas de ``track``."""
        self._track = dict(track or {})
        self._results, self._replaced, self._error = [], set(), ""
        self._generation += 1
        generation = self._generation
        self._state = "loading"
        self.changed.emit()
        own_id = self._bridge.trackVideoId(self._track)
        query_track = dict(self._track)

        def worker():
            try:
                self._found.emit(self._finder(query_track, own_id=own_id), "", generation)
            except Exception as error:
                self._found.emit([], str(error) or error.__class__.__name__, generation)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, str, int)
    def _apply(self, results, error, generation):
        if generation != self._generation:
            return
        self._results = [dict(item) for item in results]
        self._error = error
        self._state = "error" if error else ("ready" if self._results else "empty")
        self.changed.emit()

    @Slot("QVariantMap", result=int)
    def replaceWith(self, alternative):
        """Cambia la canción por esta versión en todas las playlists y en la cola. Devuelve cuántos lugares cambió."""
        changed = int(self._bridge.replaceTrack(self._track, dict(alternative or {})) or 0)
        if changed:
            self._replaced.add(str(dict(alternative).get("id", "")))
            self.changed.emit()
        return changed

    @Slot()
    def reset(self):
        self._generation += 1
        self._state, self._results, self._track, self._replaced, self._error = "idle", [], {}, set(), ""
        self.changed.emit()

    @Slot(str, result=bool)
    def isReplaced(self, identifier):
        return identifier in self._replaced

    @Slot("QVariantMap", result=str)
    def detail(self, item):
        """«Artista · 3:45» para una fila de resultados."""
        artist = str(item.get("artist") or item.get("channel") or item.get("subtitle") or "").split(" · ")[0]
        seconds = int(item.get("duration") or 0)
        clock = f"{seconds // 60}:{seconds % 60:02d}" if seconds > 0 else ""
        return " · ".join(part for part in (artist, clock) if part)

    @Slot(result=str)
    def emptyText(self):
        return i18n.tr("No encontramos otras versiones de esta canción.")
