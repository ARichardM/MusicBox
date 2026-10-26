"""Las playlists de Windows entienden los datos de Android, no repiten canciones y la descarga automática reconcilia."""

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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
    bridge._linked_ids = {}
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


class LinkLocalSongsTest(unittest.TestCase):
    def make(self):
        bridge = make_bridge()
        bridge._library_playlist = ""
        bridge._playlists = {"Favoritos": []}
        bridge._favorites = set()
        bridge._online_tracks = {}
        bridge._library_all = [{"path": "C:/m/archivo_sin_parecido.mp3", "title": "archivo sin parecido", "artist": "x",
                                "fromFolder": True}]
        bridge._save_library_roots = lambda roots: setattr(bridge, "saved_roots", roots)
        bridge._library_roots = lambda: []
        bridge.refresh = lambda: setattr(bridge, "refreshed", True)
        bridge._show_notice = lambda *a, **k: setattr(bridge, "notice_args", (a, k))
        return bridge

    ITEM = {"path": "C:/m/archivo_sin_parecido.mp3", "id": "abcDEF12345", "title": "Un título muy distinto",
            "channel": "Canal", "duration": 200, "thumbnail": "t", "url": "https://www.youtube.com/watch?v=abcDEF12345"}

    def test_explicit_link_unites_file_and_online_song_even_when_titles_differ(self):
        bridge = self.make()
        self.assertEqual(bridge.linkLocalSongs("Mi carpeta", [dict(self.ITEM)], False, ""), 1)
        self.assertEqual(bridge._playlists["Mi carpeta"], ["youtube:abcDEF12345"])
        self.assertEqual(bridge._linked_ids, {self.ITEM["path"]: "abcDEF12345"})
        bridge._unify_playlists()
        self.assertEqual(bridge._path_ids[self.ITEM["path"]], "abcDEF12345")
        bridge._library_playlist = "Mi carpeta"
        bridge._apply_library_view()
        self.assertEqual(len(bridge._library), 1)
        self.assertEqual(bridge._library[0]["path"], self.ITEM["path"])
        self.assertFalse(bridge._library[0].get("web"))

    def test_link_is_idempotent_and_never_repeats_a_song(self):
        bridge = self.make()
        bridge.linkLocalSongs("Lista", [dict(self.ITEM)], False, "")
        self.assertEqual(bridge.linkLocalSongs("Lista", [dict(self.ITEM)], False, ""), 0)
        self.assertEqual(bridge._playlists["Lista"], ["youtube:abcDEF12345"])

    def test_link_ignores_items_without_id_or_path(self):
        bridge = self.make()
        self.assertEqual(bridge.linkLocalSongs("Lista", [dict(self.ITEM, id=""), dict(self.ITEM, path="")], False, ""), 0)
        self.assertEqual(bridge.linkLocalSongs("  ", [dict(self.ITEM)], False, ""), 0)

    def test_link_can_add_the_folder_to_the_library(self):
        bridge = self.make()
        bridge.linkLocalSongs("Lista", [dict(self.ITEM)], True, "C:/m")
        self.assertEqual([str(root) for root in bridge.saved_roots], [str(__import__("pathlib").Path("C:/m"))])

    def test_search_results_show_the_folder_mark_when_the_file_comes_from_a_folder(self):
        bridge = self.make()
        bridge.linkLocalSongs("Lista", [dict(self.ITEM)], False, "")
        bridge._unify_playlists()
        result = {"id": "abcDEF12345", "title": "Un título muy distinto"}
        bridge._mark_local(result, result["id"], result["title"])
        self.assertTrue(result["downloaded"])
        self.assertTrue(result["fromFolder"])
        other = {"id": "zzzzzzzzzzz", "title": "Nada que ver"}
        bridge._mark_local(other, other["id"], other["title"])
        self.assertFalse(other["downloaded"])
        self.assertFalse(other["fromFolder"])


class ShuffleToggleTest(unittest.TestCase):
    def make(self):
        bridge = make_bridge()
        bridge._shuffle = False
        bridge._queue_original = []
        bridge._queue = [{"path": f"C:/m/{n}.mp3", "title": str(n)} for n in range(30)]
        bridge._current = dict(bridge._queue[5])
        bridge._queue_index = 5
        return bridge

    def paths(self, bridge):
        return [row["path"] for row in bridge._queue]

    def test_shuffle_keeps_the_current_song_first_and_every_song_once(self):
        bridge = self.make()
        original = self.paths(bridge)
        bridge.toggleShuffle()
        self.assertTrue(bridge._shuffle)
        self.assertEqual(bridge._queue[0]["path"], "C:/m/5.mp3")
        self.assertEqual(bridge._queue_index, 0)
        self.assertEqual(sorted(self.paths(bridge)), sorted(original))
        self.assertNotEqual(self.paths(bridge), original)

    def test_turning_shuffle_off_restores_the_original_order(self):
        bridge = self.make()
        original = self.paths(bridge)
        bridge.toggleShuffle()
        bridge.toggleShuffle()
        self.assertFalse(bridge._shuffle)
        self.assertEqual(self.paths(bridge), original)
        self.assertEqual(bridge._queue_index, 5)

    def test_songs_added_while_shuffled_survive_turning_it_off(self):
        bridge = self.make()
        bridge.toggleShuffle()
        bridge._queue.append({"path": "C:/m/nueva.mp3", "title": "nueva"})
        bridge.toggleShuffle()
        self.assertEqual(self.paths(bridge)[-1], "C:/m/nueva.mp3")
        self.assertEqual(len(bridge._queue), 31)

    def test_choice_is_saved(self):
        bridge = self.make()
        bridge.toggleShuffle()
        self.assertEqual(bridge._settings.values["shuffleOn"], "true")


class DeleteTrackTest(unittest.TestCase):
    def make(self, directory):
        bridge = make_bridge()
        self.path = str(Path(directory) / "No sabia.mp3")
        Path(self.path).write_bytes(b"x")
        bridge._online_tracks = identity.normalize_online_tracks(ANDROID_TRACKS)
        bridge._path_ids = {self.path: "n0vo-mLGe6I"}
        bridge._linked_ids = {self.path: "n0vo-mLGe6I"}
        bridge._playlists = {"Favoritos": ["youtube:n0vo-mLGe6I", "youtube:zTDeEJyCmNA"],
                             "Rock": ["youtube:n0vo-mLGe6I", self.path, "youtube:zTDeEJyCmNA"]}
        bridge._favorites = {"youtube:n0vo-mLGe6I", "youtube:zTDeEJyCmNA"}
        bridge._queue = [{"path": self.path}, {"path": "C:/m/otra.mp3"}]
        bridge._queue_index = 0
        bridge._current = {"path": self.path}
        bridge._recent_paths = [self.path, "C:/m/otra.mp3"]
        bridge._player = type("Player", (), {"stop": lambda self_: setattr(bridge, "stopped", True)})()
        bridge.refresh = lambda: setattr(bridge, "refreshed", True)
        bridge._show_notice = lambda *a, **k: setattr(bridge, "last_notice", (a, k))
        bridge._save_playlists = lambda: None
        return bridge

    def trash(self, name):
        Path(name).unlink()
        return True

    def test_delete_removes_the_file_and_every_trace_of_the_song(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make(directory)
            with mock.patch.object(app_module.QFile, "moveToTrash", self.trash):
                self.assertTrue(bridge.deleteTrack({"path": self.path}))
            self.assertFalse(Path(self.path).exists())
            self.assertEqual(bridge._playlists["Rock"], ["youtube:zTDeEJyCmNA"])
            self.assertEqual(bridge._playlists["Favoritos"], ["youtube:zTDeEJyCmNA"])
            self.assertEqual(bridge._favorites, {"youtube:zTDeEJyCmNA"})
            self.assertEqual(bridge._linked_ids, {})
            self.assertEqual(bridge._queue, [{"path": "C:/m/otra.mp3"}])
            self.assertEqual(bridge._recent_paths, ["C:/m/otra.mp3"])
            self.assertEqual(bridge._current, {})
            self.assertTrue(bridge.stopped)
            self.assertTrue(bridge.refreshed)
            self.assertEqual(bridge.last_notice[1], {"kind": "info"})

    def test_delete_that_fails_keeps_everything(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make(directory)
            with mock.patch.object(app_module.QFile, "moveToTrash", lambda name: False):
                self.assertFalse(bridge.deleteTrack({"path": self.path}))
            self.assertTrue(Path(self.path).exists())
            self.assertIn("youtube:n0vo-mLGe6I", bridge._playlists["Rock"])
            self.assertEqual(bridge._current, {"path": self.path})

    def test_delete_ignores_online_songs_and_missing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make(directory)
            self.assertFalse(bridge.deleteTrack({"id": "n0vo-mLGe6I"}))
            self.assertFalse(bridge.deleteTrack({"path": str(Path(directory) / "no-existe.mp3")}))
            self.assertIn("youtube:n0vo-mLGe6I", bridge._playlists["Rock"])

    def test_remove_from_playlist_only_touches_that_playlist(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make(directory)
            bridge.removeTrackFromPlaylist("Rock", "youtube:n0vo-mLGe6I")
            self.assertEqual(bridge._playlists["Rock"], [self.path, "youtube:zTDeEJyCmNA"])
            self.assertIn("youtube:n0vo-mLGe6I", bridge._playlists["Favoritos"])
            self.assertTrue(Path(self.path).exists())


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
