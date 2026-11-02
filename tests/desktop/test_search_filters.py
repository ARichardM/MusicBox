"""La búsqueda usa el catálogo de YouTube Music con filtros Canciones, Videos y Artistas."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QSettings
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
import youtube_streams
import ytmusic

APP = QApplication.instance() or QApplication([])


def song(video_id):
    return ytmusic.make_track(video_id, "Tema " + video_id, "Artista", "Álbum", 200, "", "song")


class SearchFilterTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            self.bridge = module.MusicBoxBridge(
                settings=QSettings(str(Path(self.temporary.name) / "settings.ini"), QSettings.IniFormat), load_startup=False)
        self.addCleanup(self.bridge.deleteLater)
        self.bridge._schedule_cloud_save = Mock()
        self.bridge._listening_timer.stop()
        self.bridge._device_timer.stop()
        self.patches = {
            "songs": patch.object(ytmusic, "search_songs", return_value=[song("s1"), song("s2")]),
            "videos": patch.object(ytmusic, "search_videos", return_value=[dict(song("v1"), kind="video")]),
            "artists": patch.object(ytmusic, "search_artists",
                                    return_value=[{"name": "Vegetta777", "browse_id": "UCabc", "photo": "https://foto"}]),
            "youtube": patch.object(youtube_streams, "search", return_value=[{"id": "gameplay", "title": "Minecraft"}]),
        }
        self.mocks = {key: value.start() for key, value in self.patches.items()}
        for value in self.patches.values():
            self.addCleanup(value.stop)

    def wait(self):
        for _ in range(150):
            APP.processEvents()
            if not self.bridge.busy: return
            QTest.qWait(20)
        self.fail("La búsqueda no terminó")

    def test_songs_are_the_default_and_come_from_the_music_catalog(self):
        self.assertEqual(self.bridge.searchFilter, "songs")
        self.bridge.search("vegetta777")
        self.wait()
        self.assertEqual([row["id"] for row in self.bridge.searchResults], ["s1", "s2"])
        self.mocks["youtube"].assert_not_called()

    def test_changing_the_filter_repeats_the_search_without_duplicating_history(self):
        self.bridge.search("vegetta777")
        self.wait()
        self.bridge.setSearchFilter("artists")
        self.wait()
        self.assertEqual(self.bridge.searchResults, [])
        self.assertEqual(self.bridge.searchArtists, [{"kind": "artist", "name": "Vegetta777", "browseId": "UCabc",
                                                      "photo": "https://foto"}])
        self.bridge.setSearchFilter("videos")
        self.wait()
        self.assertEqual([row["id"] for row in self.bridge.searchResults], ["v1"])
        self.assertEqual(self.bridge.searchArtists, [])
        self.assertEqual(self.bridge.recentQueries.count("vegetta777"), 1)
        self.assertEqual(self.bridge._settings.value("searchFilter"), "videos")

    def test_plain_youtube_is_only_a_fallback_when_the_catalog_fails(self):
        self.mocks["songs"].side_effect = OSError("sin respuesta")
        self.bridge.search("vegetta777")
        self.wait()
        self.assertEqual([row["id"] for row in self.bridge.searchResults], ["gameplay"])

    def test_unknown_filters_are_ignored(self):
        self.bridge.setSearchFilter("podcasts")
        self.assertEqual(self.bridge.searchFilter, "songs")


if __name__ == "__main__":
    unittest.main()
