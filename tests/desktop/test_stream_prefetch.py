"""La canción online actual y la siguiente se precargan para sonar sin esperas ni cortes."""
import os
import tempfile
import time
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
import stream_prefetch
import youtube_streams

APP = QApplication.instance() or QApplication([])


def track(identifier, **extra):
    return {"id": identifier, "title": identifier, "url": "https://www.youtube.com/watch?v=" + identifier, **extra}


class UpcomingTests(unittest.TestCase):
    def test_current_stream_and_the_next_stream(self):
        queue = [track("a"), track("b"), track("c")]
        self.assertEqual(stream_prefetch.upcoming_streams(queue[0], queue, 0, 0), [queue[0]["url"], queue[1]["url"]])

    def test_local_songs_are_never_downloaded_again(self):
        local = {"path": "C:/musica/a.mp3", "title": "a"}
        queue = [local, {"path": "C:/musica/b.mp3"}, track("c")]
        self.assertEqual(stream_prefetch.upcoming_streams(local, queue, 0, 0), [])
        self.assertEqual(stream_prefetch.upcoming_streams(queue[1], queue, 1, 0), [queue[2]["url"]])

    def test_repeat_modes(self):
        queue = [track("a"), track("b")]
        self.assertEqual(stream_prefetch.upcoming_streams(queue[1], queue, 1, 0), [queue[1]["url"]])
        self.assertEqual(stream_prefetch.upcoming_streams(queue[1], queue, 1, 1), [queue[1]["url"], queue[0]["url"]])
        self.assertEqual(stream_prefetch.upcoming_streams(queue[0], queue, 0, 2), [queue[0]["url"]])

    def test_old_files_and_interrupted_downloads_are_pruned(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            old, fresh, partial = folder / "old.m4a", folder / "fresh.m4a", folder / "stream-x"
            old.write_bytes(b"1"); fresh.write_bytes(b"1"); partial.mkdir()
            past = time.time() - 2 * stream_prefetch.MAX_AGE_SECONDS
            os.utime(old, (past, past)); os.utime(partial, (past, past))
            self.assertEqual(stream_prefetch.prune_cache(folder), 2)
            self.assertEqual([path.name for path in folder.iterdir()], ["fresh.m4a"])


class BridgePrefetchTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name) / "cache"
        patcher = patch.object(module, "STREAM_CACHE_DIR", self.cache)
        patcher.start(); self.addCleanup(patcher.stop)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            self.bridge = module.MusicBoxBridge(
                settings=QSettings(str(Path(self.temporary.name) / "settings.ini"), QSettings.IniFormat), load_startup=False)
        self.addCleanup(self.bridge.deleteLater)
        self.bridge._schedule_cloud_save = Mock()
        self.bridge._sample_listening = Mock()
        self.bridge._maybe_autoplay = Mock()
        self.bridge._listening_timer.stop()
        self.bridge._device_timer.stop()
        self.bridge._prefetch_timer.setInterval(0)
        self.source = QUrl()
        self.player = self.bridge._player = Mock()
        self.player.position.return_value = 61000
        self.player.source.side_effect = lambda: self.source
        self.player.setSource.side_effect = lambda source: setattr(self, "source", source)
        self.player.isSeekable.return_value = True
        self.player.mediaStatus.return_value = QMediaPlayer.BufferedMedia
        self.player.playbackState.return_value = QMediaPlayer.PlayingState
        resolver = patch.object(youtube_streams, "resolve_stream",
                                side_effect=lambda url, _extract: {"url": "https://media.example/" + url[-2:]})
        self.resolve = resolver.start(); self.addCleanup(resolver.stop)
        self.downloaded = []

        def download(url, folder, _download):
            folder.mkdir(parents=True, exist_ok=True)
            path = folder / (url[-2:] + ".m4a")
            path.write_bytes(b"audio")
            self.downloaded.append(url[-2:])
            return str(path)
        downloader = patch.object(youtube_streams, "download_to_cache", side_effect=download)
        downloader.start(); self.addCleanup(downloader.stop)

    def wait(self, condition, timeout=2500):
        for _ in range(timeout // 20):
            APP.processEvents()
            if condition(): return
            QTest.qWait(20)
        self.fail("No se cumplió la condición: " + str(self.bridge._current) + " " + str(self.downloaded))

    def test_next_song_plays_from_the_local_copy_without_resolving_again(self):
        playlist = [track("p1", web=True), track("p2", web=True)]
        self.bridge._library = list(playlist)
        self.bridge.playLibraryTrack(playlist[0])
        self.wait(lambda: len(self.bridge._prefetched) == 2)
        self.assertEqual(sorted(self.downloaded), ["p1", "p2"])
        resolved = self.resolve.call_count
        self.player.mediaStatus.return_value = QMediaPlayer.EndOfMedia
        self.bridge._media_status()
        self.wait(lambda: self.bridge.currentTrack.get("id") == "p2")
        self.assertTrue(self.source.isLocalFile())
        self.assertTrue(self.source.toLocalFile().endswith("p2.m4a"))
        self.assertEqual(self.resolve.call_count, resolved)

    def test_a_stalled_stream_continues_from_the_local_copy_at_the_same_second(self):
        self.bridge.playOnlineList([track("s1"), track("s2")], 0)
        self.wait(lambda: self.source.toString().startswith("https://media.example/"))
        self.player.mediaStatus.return_value = QMediaPlayer.StalledMedia
        self.bridge._media_status()  # Qt avisa el atasco; si la copia ya estaba lista se usa en ese momento.
        self.wait(lambda: self.source.isLocalFile())  # Si no, en cuanto termina la descarga.
        self.assertTrue(self.source.toLocalFile().endswith("s1.m4a"))
        self.assertEqual(self.bridge._pending_seek, 61000)
        self.player.mediaStatus.return_value = QMediaPlayer.LoadedMedia
        self.bridge._media_status()
        self.player.setPosition.assert_called_with(61000)

    def test_only_a_few_copies_are_kept(self):
        for index in range(stream_prefetch.PREFETCH_LIMIT + 2):
            path = self.cache / f"{index}.m4a"
            self.cache.mkdir(exist_ok=True); path.write_bytes(b"a")
            self.bridge._store_prefetch(f"https://www.youtube.com/watch?v={index}", str(path))
        self.assertEqual(len(self.bridge._prefetched), stream_prefetch.PREFETCH_LIMIT)
        self.assertEqual(len(list(self.cache.iterdir())), stream_prefetch.PREFETCH_LIMIT)


if __name__ == "__main__":
    unittest.main()
