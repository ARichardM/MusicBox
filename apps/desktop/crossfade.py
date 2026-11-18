"""Overlap prepared local/cache audio while retaining the bridge's queue and recovery logic."""
import math
import time
from pathlib import Path

from PySide6.QtCore import QObject, QTimer, QUrl
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer


class Crossfade(QObject):
    def __init__(self, bridge):
        super().__init__(bridge)
        self.bridge = bridge
        self.outgoing = self.output = None
        self.held = None
        self.outgoing_track = None
        self.end_ms = 0
        self.zone_ms = 0
        self.switching = False
        self.started = None
        self.timer = QTimer(self)
        self.timer.setInterval(25)
        self.timer.timeout.connect(self.tick)
        bridge._player.positionChanged.connect(self.consider)

    def cancel(self):
        if self.switching:
            return
        self.timer.stop()
        if self.outgoing is not None:
            self.outgoing.stop()
            self.outgoing.setSource(QUrl())
            self.outgoing.setAudioOutput(None)
            self.outgoing.deleteLater()
            self.output.deleteLater()
            self.bridge._audio.setVolume(self.volume)
        self.outgoing = self.output = None
        self.started = None
        if self.outgoing_track is not None:
            self.held = self.outgoing_track = None
            self.bridge.playerChanged.emit()

    def consider(self, position):
        b = self.bridge
        seconds = b.crossfadeSeconds
        duration = b._player.duration()
        end = b.crossfade_end_ms()  # donde deja de sonar de verdad (hay pistas con silencio al final)
        if (not seconds or self.outgoing is not None or b.videoMode or not b.playing
                or b._repeat_mode == 2 or end < seconds * 2000
                or end - position > seconds * 1000 or position >= end):
            return
        online = b._online_queue_active()
        queue, index = (b._online_queue, b._online_index) if online else (b._queue, b._queue_index)
        target = index + 1
        if target >= len(queue) and b._repeat_mode == 1:
            target = 0
        if target < 0 or target >= len(queue):
            return
        item = queue[target]
        path = item.get('path') or b._prefetched.get(item.get('url', ''), '')
        if not path or not Path(path).is_file():
            return  # Let the existing player continue normally when the next audio is not cached.
        b._sample_listening()
        self.outgoing, self.output = b._player, b._audio
        self.volume = b._audio.volume()
        self.seconds = min(seconds, (end - position) / 1000)
        self.held = self.outgoing_track = b._current
        self.end_ms = end
        self.zone_ms = seconds * 1000
        entering = b._wave_of(item)
        self.entering_ms = int(entering.get('audibleStartMs', 0)) if entering else 0
        self.deadline = time.monotonic() + self.seconds + 2
        for signal in (self.outgoing.positionChanged, self.outgoing.durationChanged,
                       self.outgoing.playbackStateChanged, self.outgoing.mediaStatusChanged,
                       self.outgoing.errorOccurred, self.outgoing.seekableChanged):
            signal.disconnect()
        b._audio = QAudioOutput(b)
        b._audio.setDevice(self.output.device())
        b._audio.setMuted(self.output.isMuted())
        b._audio.setVolume(0)
        b._player = QMediaPlayer(b)
        b._player.setAudioOutput(b._audio)
        b._player.positionChanged.connect(lambda _: b.playerChanged.emit())
        b._player.positionChanged.connect(self.consider)
        b._player.durationChanged.connect(lambda _: b.playerChanged.emit())
        b._player.hasVideoChanged.connect(b.videoModeChanged)
        b._player.positionChanged.connect(b.lyrics._update_position)
        b._player.playbackStateChanged.connect(b._playback_state_changed)
        b._player.mediaStatusChanged.connect(b._media_status)
        b._player.errorOccurred.connect(b._player_error)
        b._player.seekableChanged.connect(lambda _: b._resume_recovered_track())
        self.switching = True
        try:
            b.skip(1)
        finally:
            self.switching = False
        if self.entering_ms > 300:
            self._enter_after_load(b._player, self.entering_ms)
        self.timer.start()
        b.playerChanged.emit()

    def _enter_after_load(self, player, start_ms):
        """La siguiente canción entra donde empieza a sonar, no en su silencio inicial."""
        done = []

        def seek(*_):
            if done or player is not self.bridge._player:
                return
            if player.mediaStatus() in (QMediaPlayer.LoadedMedia, QMediaPlayer.BufferedMedia):
                done.append(True)
                player.setPosition(start_ms)
        player.mediaStatusChanged.connect(seek)
        seek()

    def tick(self):
        b = self.bridge
        now = time.monotonic()
        if self.started is None:
            if b.playing and b._player.position() > 0:
                self.started = now
            elif now >= self.deadline:
                self.cancel()
            return
        if not b.playing:
            self.cancel()
            return
        fraction = min(1, max(0, (now - self.started) / self.seconds))
        # Potencia constante: la que sale sigue el coseno y la que entra el seno, sin bajón de volumen a mitad.
        self.output.setVolume(self.volume * math.cos(fraction * math.pi / 2))
        b._audio.setVolume(self.volume * math.sin(fraction * math.pi / 2))
        if fraction >= .5 and self.held:
            self.held = None
            b.playerChanged.emit()
        if fraction >= 1:
            self.cancel()
