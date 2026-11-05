"""Controlador de «Importar playlist desde un enlace»: lee la lista, deja elegir el nombre y la importa con progreso."""

from __future__ import annotations

import threading

from PySide6.QtCore import Property, QObject, Signal, Slot

import i18n
import playlist_import


class PlaylistImportController(QObject):
    stateChanged = Signal()
    progressChanged = Signal()
    _fetched = Signal(object, str, int)
    _advanced = Signal(int, int, int)
    _resolved = Signal(object, int, bool, int)

    def __init__(self, bridge, fetcher=playlist_import.fetch, matcher=playlist_import.match, parent=None):
        super().__init__(parent)
        self._bridge = bridge
        self._fetcher = fetcher
        self._matcher = matcher
        self._state = "idle"          # idle, reading, ready, importing, done, error
        self._label = ""
        self._name = ""
        self._message = ""
        self._total = self._done = self._added = self._skipped = 0
        self._playlist = None
        self._generation = 0
        self._cancelled = threading.Event()
        self._fetched.connect(self._apply_fetched)
        self._advanced.connect(self._apply_progress)
        self._resolved.connect(self._apply_resolved)

    # --- Propiedades para la interfaz ---
    @Property(str, notify=stateChanged)
    def state(self):
        return self._state

    @Property(str, notify=stateChanged)
    def platform(self):
        return self._label

    @Property(str, notify=stateChanged)
    def playlistName(self):
        return self._name

    @Property(str, notify=stateChanged)
    def message(self):
        return self._message

    @Property(int, notify=stateChanged)
    def total(self):
        return self._total

    @Property(int, notify=stateChanged)
    def added(self):
        return self._added

    @Property(int, notify=stateChanged)
    def skipped(self):
        return self._skipped

    @Property(int, notify=progressChanged)
    def done(self):
        return self._done

    def _set_state(self, state, message=""):
        self._state = state
        self._message = message
        self.stateChanged.emit()

    # --- Acciones ---
    @Slot(str, result=bool)
    def detect(self, text):
        """¿El texto contiene un enlace de playlist soportado?"""
        return playlist_import.find(text) is not None

    @Slot(str, result=bool)
    def start(self, text):
        """Empieza a leer la playlist del enlace. Devuelve False si el texto no tiene un enlace soportado."""
        link = playlist_import.find(text)
        if link is None:
            return False
        if self._state in ("reading", "importing"):
            return True
        self._generation += 1
        generation = self._generation
        self._cancelled.clear()
        self._label, self._name, self._playlist = link.label, "", None
        self._total = self._done = self._added = self._skipped = 0
        self._set_state("reading", i18n.tr("Leyendo la playlist de {platform}…", platform=link.label))

        def worker():
            try:
                self._fetched.emit(self._fetcher(link), "", generation)
            except Exception as error:
                self._fetched.emit(None, str(error) or error.__class__.__name__, generation)

        threading.Thread(target=worker, daemon=True).start()
        return True

    @Slot(object, str, int)
    def _apply_fetched(self, playlist, error, generation):
        if generation != self._generation or self._state != "reading":
            return
        if playlist is None:
            self._set_state("error", i18n.tr("No se pudo importar la playlist. ") + error)
            return
        self._playlist = playlist
        self._name = playlist.name
        self._total = len(playlist.tracks)
        self._set_state("ready")

    @Slot(str)
    def confirm(self, name):
        """Importa la playlist leída con el nombre elegido."""
        if self._state != "ready" or self._playlist is None:
            return
        target = (name or "").strip() or self._playlist.name
        self._name = target
        self._done = 0
        self.progressChanged.emit()
        self._set_state("importing")
        playlist, generation = self._playlist, self._generation
        self._cancelled.clear()

        def worker():
            videos, skipped, stopped = playlist_import.resolve(
                playlist, progress=lambda done, total: self._advanced.emit(done, total, generation),
                cancelled=self._cancelled.is_set, matcher=self._matcher)
            self._resolved.emit(videos, skipped, stopped, generation)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(int, int, int)
    def _apply_progress(self, done, total, generation):
        if generation == self._generation and self._state == "importing":
            self._done = done
            self.progressChanged.emit()

    @Slot(object, int, bool, int)
    def _apply_resolved(self, videos, skipped, stopped, generation):
        if generation != self._generation or self._state != "importing":
            return
        self._bridge.createPlaylist(self._name)
        self._added = int(self._bridge.addTracksToPlaylist(self._name, videos) or 0)
        self._skipped = skipped
        if stopped:
            message = i18n.tr("Importación detenida: {n} canciones agregadas a «{name}»", n=self._added, name=self._name)
        else:
            message = i18n.tr("Playlist «{name}» creada con {n} canciones", name=self._name, n=self._added)
        if skipped:
            message += i18n.tr(" · {n} sin coincidencia segura", n=skipped)
        self._set_state("done", message)

    @Slot()
    def cancel(self):
        """Detiene la importación (se conserva lo ya encontrado) o descarta una lectura pendiente."""
        if self._state == "importing":
            self._cancelled.set()
            return
        if self._state in ("reading", "ready"):
            self._generation += 1
            self._set_state("idle")

    @Slot()
    def reset(self):
        if self._state not in ("reading", "importing"):
            self._set_state("idle")
