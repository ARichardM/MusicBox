"""Real Qt media overlap with silent synthetic audio and isolated preferences."""
import os
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
from shiboken6 import delete

APP = QApplication.instance() or QApplication([])


class CrossfadeTests(unittest.TestCase):
    def test_overlap_preserves_queue_and_pause_releases_outgoing_audio(self):
        # Windows multimedia may keep decoder handles until this test process exits.
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
            settings = QSettings(str(Path(directory) / 'settings.ini'), QSettings.IniFormat)
            settings.setValue('muted', True)
            with patch.object(module, 'FirebaseDesktopClient'), patch.object(module.MusicBoxBridge, '_maybe_autoplay'):
                bridge = module.MusicBoxBridge(settings=settings, load_startup=False)
                tracks = []
                for name in ('first', 'next'):
                    path = Path(directory) / (name + '.wav')
                    with wave.open(str(path), 'wb') as audio:
                        audio.setparams((1, 2, 8000, 0, 'NONE', 'not compressed'))
                        audio.writeframes(bytes(8000 * 2 * 4))
                    tracks.append({'path': str(path), 'title': name})
                bridge._library = list(tracks)
                bridge.setCrossfadeSeconds(1)
                try:
                    bridge.playTrack(tracks[0])
                    for _ in range(260):
                        if bridge._crossfade.outgoing is not None:
                            break
                        QTest.qWait(20)
                    self.assertIsNotNone(bridge._crossfade.outgoing, 'The prepared next track should overlap before completion')
                    self.assertEqual(bridge._current['path'], tracks[1]['path'])
                    # En plena mezcla el título sigue a la canción que sale hasta la mitad; luego cambia.
                    self.assertEqual(bridge.currentTrack['path'], tracks[0]['path'])
                    for _ in range(120):
                        if bridge.currentTrack['path'] == tracks[1]['path']:
                            break
                        QTest.qWait(20)
                    self.assertEqual(bridge.currentTrack['path'], tracks[1]['path'])
                    self.assertEqual(len(bridge.queue), 2)
                    self.assertAlmostEqual(bridge.volume, .8, places=2)
                    bridge.pausePlayer()
                    self.assertIsNone(bridge._crossfade.outgoing)
                    self.assertAlmostEqual(bridge._audio.volume(), .8, places=2)
                    bridge.setCrossfadeSeconds(7)
                    bridge.setCrossfadeEnabled(False)
                    self.assertEqual(bridge.crossfadeSeconds, 0)
                    bridge.setCrossfadeEnabled(True)
                    self.assertEqual(bridge.crossfadeSeconds, 7)
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
