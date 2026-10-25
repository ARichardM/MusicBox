"""Escucha rápida de resultados: un reproductor aparte, sin tocar la cola ni la canción actual."""

from __future__ import annotations

import threading
from pathlib import Path

from PySide6.QtCore import QObject, Property, QUrl, Signal, Slot
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer

import i18n
import youtube_streams
from app_paths import DATA_ROOT


def _extract(options, url):
    import yt_dlp
    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)


def _download(options, url):
    import yt_dlp
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        return ydl.prepare_filename(info)


class AudioPreview(QObject):
    changed = Signal()
    _resolved = Signal(int, str, str)

    def __init__(self, before_play=None, parent=None):
        super().__init__(parent)
        self._before_play = before_play
        self._audio = QAudioOutput(self)
        self._audio.setVolume(0.9)
        self._player = QMediaPlayer(self)
        self._player.setAudioOutput(self._audio)
        self._key = ""
        self._url = ""
        self._title = ""
        self._state = "idle"
        self._text = ""
        self._generation = 0
        self._cache_tried = False
        self._resolved.connect(self._on_resolved)
        self._player.positionChanged.connect(lambda _: self.changed.emit())
        self._player.durationChanged.connect(lambda _: self.changed.emit())
        self._player.playbackStateChanged.connect(self._on_state)
        self._player.mediaStatusChanged.connect(self._on_media_status)
        self._player.errorOccurred.connect(self._on_error)

    # ---- propiedades ----
    @Property(str, notify=changed)
    def activeKey(self): return self._key

    @Property(str, notify=changed)
    def state(self): return self._state

    @Property(int, notify=changed)
    def position(self): return self._player.position() if self._state in ("playing", "paused") else 0

    @Property(int, notify=changed)
    def duration(self): return self._player.duration() if self._state in ("playing", "paused") else 0

    @Property(str, notify=changed)
    def statusText(self): return self._text

    @Property(bool, notify=changed)
    def active(self): return self._state != "idle"

    def _set(self, state, text=""):
        self._state, self._text = state, text
        self.changed.emit()

    # ---- control ----
    @Slot(str, str, str)
    def play(self, key, url, title):
        """Resuelve y reproduce `url`; cualquier reproducción anterior se detiene."""
        if not url:
            return
        if self._before_play:
            self._before_play()
        self._player.stop()
        self._generation += 1
        generation = self._generation
        self._key, self._url, self._title, self._cache_tried = key, url, title, False
        self._set("loading", i18n.tr("Abriendo: ") + title)

        def work():
            try:
                info = youtube_streams.resolve_stream(url, _extract)
                self._resolved.emit(generation, info.get("url", ""), "")
            except Exception as exc:
                self._resolved.emit(generation, "", str(exc).splitlines()[-1] if str(exc) else "")

        threading.Thread(target=work, daemon=True).start()

    @Slot(int, str, str)
    def _on_resolved(self, generation, source, error):
        if generation != self._generation:
            return
        if not source:
            self._set("error", error or i18n.tr("No se pudo reproducir este resultado"))
            return
        self._player.setSource(self._to_url(source))
        self._player.play()

    @staticmethod
    def _to_url(source):
        return QUrl(source) if source.startswith(("http://", "https://")) else QUrl.fromLocalFile(source)

    @Slot(str)
    def toggle(self, key):
        if key != self._key or self._state not in ("playing", "paused"):
            return
        if self._state == "playing":
            self._player.pause()
        else:
            self._player.play()

    @Slot(int)
    def seek(self, milliseconds):
        if self._state in ("playing", "paused"):
            self._player.setPosition(max(0, int(milliseconds)))

    @Slot()
    def stop(self):
        self._generation += 1
        self._player.stop()
        self._key = ""
        self._set("idle", "")

    # ---- eventos del reproductor ----
    def _on_state(self, state):
        if self._state in ("loading", "error") and state != QMediaPlayer.PlayingState:
            return
        if state == QMediaPlayer.PlayingState:
            self._set("playing", i18n.tr("Reproduciendo: ") + self._title)
        elif state == QMediaPlayer.PausedState:
            self._set("paused", i18n.tr("En pausa."))
        elif self._state in ("playing", "paused"):
            self._set("idle", "")

    def _on_media_status(self, status):
        if status == QMediaPlayer.EndOfMedia:
            self._key = ""
            self._set("idle", i18n.tr("Reproducción terminada."))

    def _on_error(self, error, message=""):
        if error == QMediaPlayer.NoError or self._state == "idle":
            return
        if not self._cache_tried and self._url and self._player.source().scheme() in ("http", "https"):
            self._cache_tried = True
            generation, url = self._generation, self._url

            def work():
                try:
                    path = youtube_streams.download_to_cache(url, DATA_ROOT / "datos" / "cache_streams", _download)
                    self._resolved.emit(generation, path, "")
                except Exception as exc:
                    self._resolved.emit(generation, "", str(exc).splitlines()[-1] if str(exc) else "")

            threading.Thread(target=work, daemon=True).start()
            return
        self._set("error", message or i18n.tr("No se pudo reproducir este resultado"))
