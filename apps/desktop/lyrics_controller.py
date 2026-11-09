"""Controlador reactivo de letras para QML: sincronización por milisegundo, búsqueda en segundo plano y salto de tiempo."""

from __future__ import annotations

import threading

from PySide6.QtCore import Property, QObject, Signal, Slot

import i18n
import lyrics


class LyricsController(QObject):
    changed = Signal()
    activeIndexChanged = Signal()
    _loaded = Signal(object, str, int)

    def __init__(self, bridge=None, finder=lyrics.find, parent=None):
        super().__init__(parent)
        self._bridge = bridge
        self._finder = finder
        self._open = False
        self._state = "idle"          # idle, loading, ready, instrumental, empty, error
        self._track = {}
        self._identity = ""
        self._loaded_identity = ""
        self._lines = []
        self._synced = False
        self._source = ""
        self._instrumental = False
        self._error = ""
        self._active_index = -1
        self._generation = 0
        self._loaded.connect(self._apply)
        if bridge is not None:
            if hasattr(bridge, "playerChanged"):
                bridge.playerChanged.connect(self._on_player_changed)
            player = getattr(bridge, "_player", None)
            if player is not None and hasattr(player, "positionChanged"):
                player.positionChanged.connect(self._update_position)

    @staticmethod
    def _identity_of(track: dict) -> str:
        if not track:
            return ""
        vid = str(track.get("id") or "").strip()
        path = str(track.get("path") or "").strip()
        url = str(track.get("url") or "").strip()
        if vid or path or url:
            return f"{vid}|{path}|{url}"
        title = str(track.get("title") or "").strip()
        artist = str(track.get("artist") or track.get("subtitle") or "").strip()
        return f"{title}|{artist}" if title else ""

    @Property(bool, notify=changed)
    def open(self) -> bool:
        return self._open

    @Property(str, notify=changed)
    def state(self) -> str:
        return self._state

    @Property("QVariantMap", notify=changed)
    def track(self) -> dict:
        return self._track

    @Property("QVariantList", notify=changed)
    def lines(self) -> list:
        return self._lines

    @Property(bool, notify=changed)
    def synced(self) -> bool:
        return self._synced

    @Property(str, notify=changed)
    def source(self) -> str:
        return self._source

    @Property(bool, notify=changed)
    def instrumental(self) -> bool:
        return self._instrumental

    @Property(str, notify=changed)
    def error(self) -> str:
        return self._error

    @Property(int, notify=activeIndexChanged)
    def activeIndex(self) -> int:
        return self._active_index

    @Property(str, notify=activeIndexChanged)
    def activeText(self) -> str:
        if 0 <= self._active_index < len(self._lines):
            return str(self._lines[self._active_index].get("text") or "")
        return ""

    @Slot(bool)
    def setOpen(self, value: bool) -> None:
        value = bool(value)
        if value == self._open:
            return
        self._open = value
        self.changed.emit()
        if self._open:
            current = self._current_bridge_track()
            if current:
                ident = self._identity_of(current)
                if ident != self._loaded_identity or self._state in ("idle", "error"):
                    self.load(current, force=(self._state == "error"))

    @Slot()
    def toggle(self) -> None:
        self.setOpen(not self._open)

    def _current_bridge_track(self) -> dict:
        if self._bridge is None:
            return dict(self._track)
        current = getattr(self._bridge, "currentTrack", None)
        if callable(current):
            current = current()
        return dict(current or {})

    def _current_bridge_position(self) -> int:
        if self._bridge is None:
            return 0
        pos = getattr(self._bridge, "playerPosition", 0)
        if callable(pos):
            pos = pos()
        return int(pos or 0)

    def _enrich_track(self, track: dict) -> dict:
        item = dict(track or {})
        if self._bridge is not None and hasattr(self._bridge, "trackVideoId") and not item.get("id"):
            vid = self._bridge.trackVideoId(item)
            if vid:
                item["id"] = vid
        return item

    @Slot("QVariantMap")
    @Slot("QVariantMap", bool)
    def load(self, track: dict, force: bool = False) -> None:
        """Busca la letra de ``track`` en segundo plano."""
        item = self._enrich_track(dict(track or {}))
        ident = self._identity_of(item)
        if not ident:
            self.reset()
            return
        self._track = item
        self._identity = ident
        self._lines = []
        self._synced = False
        self._source = ""
        self._instrumental = False
        self._error = ""
        self._set_active_index(-1)
        self._generation += 1
        generation = self._generation
        self._state = "loading"
        self.changed.emit()

        def worker():
            try:
                try:
                    result = self._finder(item, use_cache=not force)
                except TypeError:
                    result = self._finder(item)
                self._loaded.emit(result, "", generation)
            except Exception as exc:
                message = str(exc).splitlines()[-1] if str(exc) else i18n.tr("No se pudo cargar la letra. Revisa tu conexión.")
                self._loaded.emit({}, message, generation)

        threading.Thread(target=worker, daemon=True, name="musicbox-lyrics").start()

    @Slot()
    def retry(self) -> None:
        target = self._current_bridge_track() or dict(self._track)
        if target:
            self.load(target, force=True)

    @Slot(object, str, int)
    def _apply(self, result: object, error: str, generation: int) -> None:
        if generation != self._generation:
            return
        data = dict(result or {}) if isinstance(result, dict) else {}
        self._loaded_identity = self._identity
        if error:
            self._lines = []
            self._synced = False
            self._source = ""
            self._instrumental = False
            self._error = i18n.tr("No se pudo cargar la letra. Revisa tu conexión.")
            self._state = "error"
            self._set_active_index(-1)
            self.changed.emit()
            return

        self._lines = [dict(line) for line in (data.get("lines") or [])]
        self._synced = bool(data.get("synced") and self._lines)
        self._source = str(data.get("source") or "")
        self._instrumental = bool(data.get("instrumental"))
        self._error = ""
        if self._lines:
            self._state = "ready"
        elif self._instrumental:
            self._state = "instrumental"
        else:
            self._state = "empty"
        self._update_position(self._current_bridge_position())
        self.changed.emit()

    @Slot(int)
    def seekTo(self, milliseconds: int) -> None:
        target = max(0, int(milliseconds))
        if self._bridge is not None and hasattr(self._bridge, "seek"):
            self._bridge.seek(target)
        self._update_position(target)

    @Slot(int)
    def seekToLine(self, index: int) -> None:
        if not (0 <= index < len(self._lines)):
            return
        stamp = int(self._lines[index].get("timeMs", -1))
        if stamp >= 0:
            self.seekTo(stamp)

    @Slot()
    def reset(self) -> None:
        self._generation += 1
        self._state = "idle"
        self._track = {}
        self._identity = ""
        self._loaded_identity = ""
        self._lines = []
        self._synced = False
        self._source = ""
        self._instrumental = False
        self._error = ""
        self._set_active_index(-1)
        self.changed.emit()

    def _set_active_index(self, index: int) -> None:
        if index != self._active_index:
            self._active_index = index
            self.activeIndexChanged.emit()

    @Slot(int)
    def _update_position(self, position_ms: int) -> None:
        if not self._synced or not self._lines:
            self._set_active_index(-1)
            return
        self._set_active_index(lyrics.active_line_index(self._lines, int(position_ms)))

    @Slot()
    def _on_player_changed(self) -> None:
        current = self._current_bridge_track()
        ident = self._identity_of(current)
        if not ident:
            if self._state != "idle" or self._identity:
                self.reset()
            return
        if ident != self._identity:
            self._track = current
            self._identity = ident
            if self._open:
                self.load(current)
            else:
                self._generation += 1
                self._loaded_identity = ""
                self._lines = []
                self._synced = False
                self._source = ""
                self._instrumental = False
                self._error = ""
                self._state = "idle"
                self._set_active_index(-1)
                self.changed.emit()
        else:
            self._track = current
            self._update_position(self._current_bridge_position())
