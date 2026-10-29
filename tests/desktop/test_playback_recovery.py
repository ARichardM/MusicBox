"""Errores de decodificación no borran ni bloquean la cola de reproducción."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QSettings, QUrl
from PySide6.QtMultimedia import QMediaPlayer
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
import youtube_streams

APP = QApplication.instance() or QApplication([])

def track(identifier):
    return {"id": identifier, "title": identifier, "url": "https://www.youtube.com/watch?v=" + identifier}

class PlaybackRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            self.bridge = module.MusicBoxBridge(
                settings=QSettings(str(Path(self.temporary.name) / "settings.ini"), QSettings.IniFormat), load_startup=False)
        self.addCleanup(self.bridge.deleteLater)
        self.bridge._schedule_cloud_save = Mock()
        self.bridge._sample_listening = Mock()
        self.bridge._maybe_autoplay = Mock()
        self.bridge._listening_timer.stop()
        self.bridge._device_timer.stop()
        self.bridge._online_queue = [track("seed"), track("next"), track("last")]
        self.bridge._online_index = 0
        self.bridge._current = dict(self.bridge._online_queue[0], _stream_generation=0)
        self.bridge._play_when_ready = True
        self.source = QUrl("https://media.example/expired")
        self.player = self.bridge._player = Mock()
        self.player.position.return_value = 4250
        self.player.source.side_effect = lambda: self.source
        self.player.setSource.side_effect = lambda source: setattr(self, "source", source)
        self.player.isSeekable.return_value = False
        self.player.mediaStatus.return_value = QMediaPlayer.NoMedia
        self.player.playbackState.return_value = QMediaPlayer.StoppedState
        self.resolver = patch.object(youtube_streams, "resolve_stream", return_value={"url": "https://media.example/fresh"})
        self.resolve = self.resolver.start()
        self.addCleanup(self.resolver.stop)

    def wait(self, condition, timeout=2500):
        for _ in range(timeout // 20):
            APP.processEvents()
            if condition(): return
            QTest.qWait(20)
        self.fail("La recuperación no completó: " + str(self.bridge._current))

    def test_demux_error_refreshes_once_preserves_position_and_ignores_duplicate_errors(self):
        self.bridge._stream_cache[self.bridge._current["url"]] = (0, "expired", {})
        self.bridge._player_error(QMediaPlayer.FormatError, "Demuxing failed")
        self.bridge._player_error(QMediaPlayer.FormatError, "Demuxing failed")
        self.assertTrue(self.bridge.playerLoading)
        self.assertTrue(self.source.isEmpty())
        self.wait(lambda: self.player.play.called)
        self.assertEqual(self.resolve.call_count, 1)
        self.assertEqual(self.source.toString(), "https://media.example/fresh")
        self.assertEqual(self.bridge.queueIndex, 0)
        self.assertEqual(len(self.bridge.queue), 3)
        self.player.isSeekable.return_value = True
        self.player.mediaStatus.return_value = QMediaPlayer.LoadedMedia
        self.bridge._resume_recovered_track()
        self.player.setPosition.assert_called_once_with(4250)

    def test_exhausted_recovery_skips_to_next_and_retry_keeps_entire_queue(self):
        self.bridge._current["_retry_attempt"] = 1
        with patch.object(youtube_streams, "download_to_cache", side_effect=OSError("rechazado")) as cache:
            self.bridge._player_error(QMediaPlayer.FormatError, "Demuxing failed")
            self.wait(lambda: self.bridge.currentTrack.get("id") == "next" and not self.bridge._stream_resolving)
        cache.assert_called_once()
        self.assertEqual(self.bridge.queueIndex, 1)
        self.assertEqual([row["id"] for row in self.bridge.queue], ["seed", "next", "last"])
        retry = self.bridge._notice_retry
        self.assertIsNotNone(retry)
        retry()
        self.wait(lambda: self.bridge.currentTrack.get("id") == "seed" and not self.bridge._stream_resolving)
        self.assertEqual(self.bridge.queueIndex, 0)
        self.assertEqual(len(self.bridge.queue), 3)

    def test_repeat_all_stops_when_every_track_failed_instead_of_looping(self):
        self.bridge._repeat_mode = 1
        self.bridge._online_queue = self.bridge._online_queue[:2]
        self.resolve.side_effect = RuntimeError("sin enlace")
        with patch.object(youtube_streams, "download_to_cache", side_effect=OSError("sin audio")):
            self.bridge._play_online(self.bridge._online_queue[0])
            self.wait(lambda: not self.bridge._play_when_ready, timeout=4000)
            count = self.resolve.call_count
            QTest.qWait(350)
            self.assertEqual(self.resolve.call_count, count)
        self.assertEqual(len(self.bridge._playback_failures), 2)
        self.assertFalse(self.bridge.playerLoading)
        self.assertTrue(self.source.isEmpty())

    def test_selecting_another_track_cancels_a_scheduled_retry(self):
        self.bridge._player_error(QMediaPlayer.FormatError, "Demuxing failed")
        self.bridge.playOnlineList([track("chosen")], 0)
        self.wait(lambda: self.bridge.currentTrack.get("id") == "chosen" and not self.bridge._stream_resolving)
        QTest.qWait(350)
        self.assertEqual(self.bridge.currentTrack["id"], "chosen")
        self.assertEqual(self.resolve.call_count, 1)

    def test_cache_is_published_only_after_success_and_never_reuses_corrupt_file(self):
        folder = Path(self.temporary.name) / "cache"
        folder.mkdir()
        (folder / "seed.m4a").write_bytes(b"corrupt")
        def download(options, url):
            self.assertTrue(options["overwrites"])
            self.assertFalse(options["continuedl"])
            result = Path(options["outtmpl"].replace("%(id)s", "seed").replace("%(ext)s", "m4a"))
            result.write_bytes(b"complete audio")
            return str(result)
        result = Path(youtube_streams.download_to_cache("url", folder, download))
        self.assertEqual(result.read_bytes(), b"complete audio")
        self.assertNotEqual(result.name, "seed.m4a")
        self.assertFalse(any(path.is_dir() for path in folder.iterdir()))

if __name__ == "__main__":
    unittest.main()
