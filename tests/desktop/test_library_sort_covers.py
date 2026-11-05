"""Orden de la biblioteca y portadas de playlists en escritorio."""
import os
import unittest
from unittest.mock import Mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication
from musicbox_desktop import MusicBoxBridge

APP = QApplication.instance() or QApplication([])


class LibrarySortTests(unittest.TestCase):
    def setUp(self):
        self.bridge = b = MusicBoxBridge.__new__(MusicBoxBridge)
        QObject.__init__(b)
        b._settings = Mock()
        b._library_all = [
            {"path": "/m/c.mp3", "title": "Charlie", "artist": "Zeta", "duration": 300},
            {"path": "/m/a.mp3", "title": "alfa", "artist": "Beta", "duration": 100},
            {"path": "/m/b.mp3", "title": "Bravo", "artist": "Alfa", "duration": 200},
        ]
        plays = {"/m/c.mp3": 1, "/m/a.mp3": 5, "/m/b.mp3": 3}
        b._online_tracks, b._playlists, b._path_ids, b._folders = {}, {}, {}, []
        b._library_playlist, b._library_folder, b._library_query = "", "", ""
        b._library_artist = ""
        b._library_sort, b._library_sort_ascending = "default", True
        b._row_keys = lambda row: [row["path"]]
        b._clicks = lambda keys: sum(plays[k] for k in keys)
        b._apply_library_view()

    def titles(self):
        return [row["title"] for row in self.bridge.library]

    def test_tabs_clear_previous_collection_and_search_filters(self):
        b = self.bridge
        for tab in ("songs", "playlists", "folders"):
            with self.subTest(tab=tab):
                b._library_folder = "Carpeta anterior"
                b._library_playlist = "Playlist anterior"
                b._library_artist = "Otro artista"
                b.setLibraryQuery("sin coincidencias")
                self.assertEqual(self.titles(), [])
                b.setLibraryFilter(tab)
                self.assertEqual(self.titles(), ["Charlie", "alfa", "Bravo"])
                self.assertEqual((b._library_folder, b._library_playlist,
                                  b._library_artist, b._library_query), ("", "", "", ""))

    def test_clearing_search_restores_tracks_without_changing_playlist(self):
        b = self.bridge
        b._playlists = {"chill": ["/m/a.mp3"]}
        b._library_playlist = "chill"
        b.setLibraryQuery("harukamirai")
        self.assertEqual(self.titles(), [])
        b.setLibraryQuery("")
        self.assertEqual(self.titles(), ["alfa"])
        self.assertEqual(b._playlists, {"chill": ["/m/a.mp3"]})

    def test_each_mode_has_natural_direction_and_second_tap_reverses_it(self):
        b = self.bridge
        expected = {"title": ["alfa", "Bravo", "Charlie"], "artist": ["Bravo", "alfa", "Charlie"],
                    "duration": ["alfa", "Bravo", "Charlie"], "plays": ["alfa", "Bravo", "Charlie"],
                    "recent": ["Charlie", "alfa", "Bravo"], "default": ["Charlie", "alfa", "Bravo"]}
        for mode, order in expected.items():
            b.setLibrarySort(mode, True)
            self.assertEqual(self.titles(), order, mode)
            b.setLibrarySort(mode, False)
            self.assertEqual(self.titles(), order[::-1], mode)

    def test_unknown_mode_falls_back_and_choice_is_saved(self):
        b = self.bridge
        b.setLibrarySort("nada", True)
        self.assertEqual(b.librarySort, "default")
        b.setLibrarySort("title", False)
        b._settings.setValue.assert_called_with("librarySort", "title:0")

    def test_saved_choice_is_read_back(self):
        b = self.bridge
        b._settings.value.return_value = "plays:0"
        self.assertEqual(b._read_library_sort(), ("plays", False))
        b._settings.value.return_value = "basura"
        self.assertEqual(b._read_library_sort(), ("default", True))


class PlaylistCoverTests(unittest.TestCase):
    def setUp(self):
        self.bridge = b = MusicBoxBridge.__new__(MusicBoxBridge)
        QObject.__init__(b)
        b._library_all = [{"path": "/m/a.mp3", "title": "A", "thumbnail": "file:///cover.jpg"},
                          {"path": "/m/b.mp3", "title": "B", "thumbnail": ""}]
        b._online_tracks = {"youtube:saved": {"id": "saved", "thumbnail": "https://img/saved.jpg"}}
        b._path_ids = {"/m/a.mp3": "idA"}
        b._playlist_descriptions, b._auto_download_playlists = {}, set()
        b._clicks = lambda keys: 0
        b._playlist_entry_key = lambda entry, rows: entry
        b.isAutoDownload = lambda key: False

    def covers(self, playlists):
        self.bridge._playlists = playlists
        return {row["name"]: row["thumbnail"] for row in self.bridge.playlists}

    def test_cover_resolves_local_file_by_id_online_data_or_youtube_thumbnail(self):
        covers = self.covers({
            "por id": ["youtube:idA"],
            "por ruta": ["/m/a.mp3"],
            "guardada": ["youtube:saved"],
            "sin datos": ["youtube:unknown"],
            "primera sin portada": ["/m/b.mp3", "youtube:saved"],
            "vacía": [],
        })
        self.assertEqual(covers["por id"], "file:///cover.jpg")
        self.assertEqual(covers["por ruta"], "file:///cover.jpg")
        self.assertEqual(covers["guardada"], "https://img/saved.jpg")
        self.assertEqual(covers["sin datos"], "https://i.ytimg.com/vi/unknown/mqdefault.jpg")
        self.assertEqual(covers["primera sin portada"], "https://img/saved.jpg")
        self.assertEqual(covers["vacía"], "")

    def test_counts_ignore_unresolved_files_and_deduplicate_linked_tracks(self):
        b = self.bridge
        b._playlists = {"Favoritos": ["/missing/song.mp3"],
                        "Mixta": ["/m/a.mp3", "youtube:idA", "youtube:unknown", "/missing/song.mp3"]}
        rows = {row["name"]: row for row in b.playlists}
        self.assertEqual(rows["Favoritos"]["count"], 0)
        self.assertEqual(rows["Mixta"]["count"], 2)
        self.assertEqual(b._playlists["Favoritos"], ["/missing/song.mp3"])


if __name__ == "__main__":
    unittest.main()
