"""Account changes received while Windows is open, without Firebase or personal settings."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch, Mock
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QSettings
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module

APP = QApplication.instance() or QApplication([])

class AccountSyncTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.settings = QSettings(str(Path(self.directory.name) / "account.ini"), QSettings.IniFormat)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            self.bridge = module.MusicBoxBridge(settings=self.settings, load_startup=False)
        self.bridge._schedule_cloud_save = Mock()
        self.bridge._user = {"uid": "isolated-test"}

    def tearDown(self):
        self.bridge._listening_timer.stop(); self.bridge._device_timer.stop()
        self.settings.sync(); self.directory.cleanup()

    def test_poll_receives_membership_dates_and_portable_history(self):
        bridge = self.bridge
        section = {"playlists": {"Mix": ["youtube:aaaaaaa0001", "youtube:bbbbbbb0002"], "Favoritos": []},
                   "onlineTracks": {"aaaaaaa0001": {"title": "A"}, "bbbbbbb0002": {"title": "B"}},
                   "entryAddedAt": {"Mix": {"youtube:aaaaaaa0001": 100, "youtube:bbbbbbb0002": 50}}}
        bridge._apply_polled_devices({"uid": "isolated-test", "generation": bridge._cloud_sync_generation,
                                      "state": {"playlists": section, "activity": {"queries": {"Banda": 200}}}}, False)
        self.assertEqual(bridge._playlists, section["playlists"])
        self.assertEqual(bridge._entry_added_at["Mix"], section["entryAddedAt"]["Mix"])
        self.assertEqual(bridge.recentQueries, ["Banda"])
        bridge._library_playlist = "Mix"; bridge.setLibrarySort("recent", True)
        self.assertEqual([row["id"] for row in bridge.library], ["aaaaaaa0001", "bbbbbbb0002"])

    def test_remote_preferences_apply_without_uploading_them_as_local_edits(self):
        bridge = self.bridge
        bridge._apply_preferences({"preferences": {"autoplay": False, "crossfadeSeconds": 7, "crossfadeBar": False,
                                  "lyricsShown": False, "zoomMp3": True, "librarySort": "recent:1"}})
        self.assertFalse(bridge.autoplay); self.assertEqual(bridge.crossfadeSeconds, 7)
        self.assertFalse(bridge.crossfadeBar); self.assertFalse(bridge.lyricsShown); self.assertTrue(bridge.zoomMp3)
        self.assertEqual(bridge.librarySort, "recent")
        self.assertEqual(bridge._pending_prefs(), {})
        bridge._schedule_cloud_save.assert_not_called()

    def test_identical_remote_sort_does_not_rebuild_the_visible_library(self):
        bridge=self.bridge
        bridge._library_sort,bridge._library_sort_ascending="recent",True
        with patch.object(bridge,"_apply_library_view") as render:
            bridge._apply_preferences({"preferences":{"librarySort":"recent:1"}})
        render.assert_not_called()

    def test_device_paths_are_not_exported_as_account_preferences(self):
        prefs = self.bridge._all_preferences()
        self.assertNotIn("windowsFolders", prefs)
        self.assertNotIn("androidFolders", prefs)

    def test_renaming_a_playlist_keeps_dates(self):
        bridge = self.bridge
        bridge._playlists = {"Mix": ["youtube:aaaaaaa0001"], "Favoritos": []}
        bridge._entry_added_at = {"Mix": {"youtube:aaaaaaa0001": 123}}
        bridge._save_playlists()
        self.assertTrue(bridge.updatePlaylistDetails("Mix", "Renamed", ""))
        self.assertEqual(bridge._entry_added_at["Renamed"], {"youtube:aaaaaaa0001": 123})

    def test_mp4_duration_update_does_not_clear_visible_lyrics(self):
        controller = self.bridge.lyrics
        track = {"id": "aaaaaaa0001", "title": "A", "artist": "B", "duration": 180}
        identity = controller._identity_of(track)
        controller._identity = identity
        controller._track = track
        controller._request_signature = (identity, "A", "B", None, 180)
        controller._lines = [{"timeMs": 0, "text": "Letra disponible"}]
        controller._open = True
        generation = controller._generation
        controller.load(dict(track, duration=181))
        self.assertTrue(controller.available); self.assertTrue(controller.open)
        self.assertEqual(controller._generation, generation)

if __name__ == "__main__": unittest.main()
