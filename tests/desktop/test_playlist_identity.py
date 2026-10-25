"""Las playlists de Windows entienden los datos de Android, no repiten canciones y la descarga automática reconcilia."""

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication

import musicbox_desktop as app_module
import track_identity as identity

APP = QApplication.instance() or QApplication([])

ANDROID_TRACKS = {
    "n0vo-mLGe6I": {"title": "Luis Jara - No sabía (Video Oficial) HD", "channel": "LUISJARA OFICIAL", "duration": 0, "thumbnail": "t", "url": "u"},
    "zTDeEJyCmNA": {"title": "Supermode - Tell Me Why (OFFICIAL VIDEO)", "channel": "Ultra Records", "duration": 177, "thumbnail": "t", "url": "u"},
}


class FakeSettings:
    def __init__(self): self.values = {}
    def setValue(self, key, value): self.values[key] = value
    def value(self, key, default=None, type=None): return self.values.get(key, default)
    def remove(self, key): self.values.pop(key, None)


def make_bridge():
    bridge = app_module.MusicBoxBridge.__new__(app_module.MusicBoxBridge)
    QObject.__init__(bridge)
    bridge._settings = FakeSettings()
    bridge._user = {}
    bridge._online_tracks = identity.normalize_online_tracks(ANDROID_TRACKS)
    bridge._online_favorites_meta = {}
    bridge._favorites = {"youtube:n0vo-mLGe6I", "C:/m/Luis_Jara_-_No_sabia_Video_Oficial_HD.mp3", "youtube:zTDeEJyCmNA"}
    bridge._playlists = {"Favoritos": ["youtube:n0vo-mLGe6I", "C:/m/Luis_Jara_-_No_sabia_Video_Oficial_HD.mp3", "youtube:zTDeEJyCmNA"]}
    bridge._auto_download_playlists = {"Favoritos"}
    bridge._playlist_descriptions = {}
    bridge._path_ids = {}
    bridge._auto_attempted = set()
    bridge._library_scanned = True
    bridge._downloading_ids = set()
    bridge._library_playlist = "Favoritos"
    bridge._library_folder = ""
    bridge._library_query = ""
    bridge._library_all = [{"path": "C:/m/Luis_Jara_-_No_sabia_Video_Oficial_HD.mp3",
                            "title": "Luis_Jara_-_No_sabia_Video_Oficial_HD", "artist": "x"}]
    bridge._library = []
    bridge._search_results = []
    bridge._schedule_cloud_save = lambda: None
    bridge.downloaded = []
    bridge.downloadOnlineTrack = lambda meta: bridge.downloaded.append(meta["id"])
    return bridge


class PlaylistIdentityTest(unittest.TestCase):
    def test_file_and_online_entry_show_once_with_android_titles(self):
        bridge = make_bridge()
        bridge._unify_playlists()
        self.assertEqual(bridge._playlists["Favoritos"], ["youtube:n0vo-mLGe6I", "youtube:zTDeEJyCmNA"])
        bridge._apply_library_view()
        titles = [row["title"] for row in bridge._library]
        self.assertEqual(len(titles), 2)
        self.assertNotIn("Canción online", titles)
        self.assertIn("Supermode - Tell Me Why (OFFICIAL VIDEO)", titles)
        local = next(row for row in bridge._library if row.get("path"))
        self.assertFalse(local.get("web"))

    def test_auto_download_only_fetches_what_is_missing_once(self):
        bridge = make_bridge()
        bridge._unify_playlists()
        bridge._reconcile_auto_downloads()
        bridge._reconcile_auto_downloads()
        self.assertEqual(bridge.downloaded, ["zTDeEJyCmNA"])

    def test_unfavoriting_removes_every_copy(self):
        bridge = make_bridge()
        bridge._unify_playlists()
        local_row = bridge._library_all[0]
        bridge._apply_library_view = lambda: None
        bridge.searchResultsChanged = type("S", (), {"emit": staticmethod(lambda: None)})()
        bridge._toggle_favorite_item(dict(local_row))
        self.assertNotIn("youtube:n0vo-mLGe6I", bridge._favorites)
        self.assertNotIn(local_row["path"], bridge._favorites)

    def test_cloud_export_uses_the_shared_format(self):
        bridge = make_bridge()
        exported = identity.export_online_tracks(bridge._online_tracks)
        self.assertEqual(set(exported), {"n0vo-mLGe6I", "zTDeEJyCmNA"})
        self.assertEqual(exported["zTDeEJyCmNA"]["channel"], "Ultra Records")


if __name__ == "__main__":
    unittest.main()


class QueueActionsTest(unittest.TestCase):
    def make(self):
        bridge = make_bridge()
        rows = [{"path": f"C:/m/{i}.mp3", "title": f"T{i}"} for i in range(5)]
        bridge._queue = list(rows)
        bridge._queue_index = 1
        bridge._current = rows[1]
        bridge._start_local_track = lambda item: None
        bridge.playerChanged = type("S", (), {"emit": staticmethod(lambda: None)})()
        bridge.playlistsChanged = type("S", (), {"emit": staticmethod(lambda: None)})()
        bridge._show_notice = lambda *args, **kwargs: None
        return bridge

    def test_moving_keeps_the_current_song_selected(self):
        bridge = self.make()
        bridge.moveQueueItem(3, 0)
        self.assertEqual([row["title"] for row in bridge._queue], ["T3", "T0", "T1", "T2", "T4"])
        self.assertEqual(bridge._queue_index, 2)
        bridge.moveQueueItem(2, 4)
        self.assertEqual(bridge._queue[bridge._queue_index]["title"], "T1")

    def test_removing_skips_the_playing_song_and_clearing_keeps_it(self):
        bridge = self.make()
        bridge.removeQueueItem(1)
        self.assertEqual(len(bridge._queue), 5)
        bridge.removeQueueItem(0)
        self.assertEqual(bridge._queue_index, 0)
        bridge.clearQueue()
        self.assertEqual([row["title"] for row in bridge._queue], ["T1"])

    def test_queue_can_be_saved_as_a_playlist(self):
        bridge = self.make()
        bridge._save_playlists = lambda: None
        bridge.saveQueueAsPlaylist("Para correr")
        self.assertEqual(len(bridge._playlists["Para correr"]), 5)
