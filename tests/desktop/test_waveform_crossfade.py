"""El relieve mide dónde suena de verdad cada pista y la mezcla cae sobre sonido, no sobre silencio."""
import math
import os
import struct
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtCore import QSettings, QCoreApplication, QEvent
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
import waveform
from shiboken6 import delete

APP = QApplication.instance() or QApplication([])


def write_track(path, lead, sound, tail, hz=440):
    rate = 8000
    with wave.open(str(path), 'wb') as audio:
        audio.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
        frames = bytearray()
        for i in range(rate * (lead + sound + tail)):
            audible = rate * lead <= i < rate * (lead + sound)
            frames += struct.pack('<h', int(math.sin(2 * math.pi * hz * i / rate) * 9000) if audible else 0)
        audio.writeframes(bytes(frames))


class WaveformTests(unittest.TestCase):
    def test_audible_bounds_ignore_leading_and_trailing_silence(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
            path = Path(directory) / 'a.wav'
            write_track(path, 2, 3, 4)
            envelope = waveform.analyse(str(path), None)
            self.assertAlmostEqual(envelope['audibleStartMs'], 2000, delta=300)
            self.assertAlmostEqual(envelope['audibleEndMs'], 5000, delta=300)
            self.assertEqual(waveform.COARSE, len(envelope['coarse']))
            self.assertTrue(all(0 < v <= 1 for v in envelope['coarse']))

    def test_a_silent_file_is_not_trimmed(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
            path = Path(directory) / 'mute.wav'
            write_track(path, 0, 0, 6)
            envelope = waveform.analyse(str(path), None)
            self.assertEqual(0, envelope['audibleStartMs'])
            self.assertAlmostEqual(envelope['audibleEndMs'], 6000, delta=200)


class SilenceAwareCrossfadeTests(unittest.TestCase):
    def test_the_mix_starts_over_sound_and_skips_the_next_songs_silence(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
            settings = QSettings(str(Path(directory) / 'settings.ini'), QSettings.IniFormat)
            settings.setValue('muted', True)
            first, second = Path(directory) / 'first.wav', Path(directory) / 'second.wav'
            write_track(first, 0, 4, 6)      # suena 4 s y luego 6 s de silencio
            write_track(second, 3, 5, 0, 660)  # 3 s de silencio inicial
            with patch.object(module, 'FirebaseDesktopClient'), patch.object(module.MusicBoxBridge, '_maybe_autoplay'):
                bridge = module.MusicBoxBridge(settings=settings, load_startup=False)
                tracks = [{'path': str(first), 'title': 'first'}, {'path': str(second), 'title': 'second'}]
                bridge._library = list(tracks)
                for track in tracks:
                    bridge._waves[track['path']] = waveform.analyse(track['path'], None)
                bridge.setCrossfadeSeconds(1)
                try:
                    bridge.playTrack(tracks[0])
                    outgoing_at = None
                    for _ in range(400):
                        if bridge._crossfade.outgoing is not None:
                            outgoing_at = bridge._crossfade.outgoing.position()
                            break
                        QTest.qWait(20)
                    self.assertIsNotNone(outgoing_at, 'The crossfade should start')
                    # El sonido termina a los 4 s: la mezcla de 1 s empieza hacia los 3 s, no a los 9 s del final del archivo.
                    self.assertTrue(2500 <= outgoing_at <= 3800, f'La mezcla empezó en {outgoing_at} ms')
                    # La barra sigue a la canción que sale (no vuelve a 0 con la nueva).
                    self.assertGreaterEqual(bridge.playerPosition, 2500)
                    QTest.qWait(900)
                    self.assertGreaterEqual(bridge._player.position(), 2500, 'La siguiente entra pasado su silencio inicial')
                finally:
                    bridge._crossfade.cancel()
                    bridge._player.stop()
                    bridge._player.setSource(module.QUrl())
                    bridge._listening_timer.stop()
                    bridge._device_timer.stop()
                    bridge._playback_session_timer.stop()
                    bridge._player.deleteLater()
                    QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
                    delete(bridge)
                    QTest.qWait(100)


if __name__ == '__main__':
    unittest.main()
