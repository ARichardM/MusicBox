"""Filtrar la playlist no modifica sus canciones ni permite salir de la cola del artista."""
import os
import unittest
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication
from musicbox_desktop import MusicBoxBridge

APP = QApplication.instance() or QApplication([])


class PlaylistArtistTests(unittest.TestCase):
    def setUp(self):
        self.bridge = b = MusicBoxBridge.__new__(MusicBoxBridge)
        QObject.__init__(b)
        b._library_all = [
            {"path": "/test/a.mp3", "title": "Primera", "artist": "Ana"},
            {"path": "/test/b.mp3", "title": "Ana en el título", "artist": "Otro"},
        ]
        b._online_tracks = {"youtube:online": {
            "id": "online", "title": "Segunda", "channel": "ana · Otro álbum",
            "url": "https://example.invalid/online"}}
        b._playlists = {"Prueba": ["/test/a.mp3", "/test/b.mp3", "youtube:online"],
                        "Otra": ["/test/b.mp3"]}
        b._path_ids = {}
        b._library_playlist, b._library_folder, b._library_query = "Prueba", "", ""
        b._library_artist, b._queue_artist = "", ""
        b._mark_local = lambda *args: None
        b._is_fav = lambda item: False
        b._clicks = lambda keys: 0
        b._row_keys = lambda row: []
        b._queue, b._queue_index = [], -1
        b._online_queue, b._online_index = [], -1
        b._online_generation = 0
        b._shuffle, b._repeat_mode = False, 0
        b._current = {}
        b._start_local_track = Mock(side_effect=lambda row: setattr(b, "_current", row))
        b._play_online = Mock(side_effect=lambda row: setattr(b, "_current", row))
        b._apply_library_view()

    def test_exact_artist_filter_combines_with_search_and_keeps_all_picker_choices(self):
        b = self.bridge
        b.setLibraryArtist("ANA")
        self.assertEqual([r["title"] for r in b.library], ["Primera", "Segunda"])
        self.assertEqual(b.libraryArtists, ["Ana", "Otro"])
        b.setLibraryQuery("Segunda")
        self.assertEqual([r["title"] for r in b.library], ["Segunda"])
        self.assertEqual(b.libraryArtists, ["Ana", "Otro"])
        self.assertEqual(len(b._playlists["Prueba"]), 3)

    def test_changing_playlist_clears_artist_and_all_artists_restores_list(self):
        b = self.bridge
        b.setLibraryArtist("Ana")
        b.setLibraryArtist("")
        self.assertEqual(len(b.library), 3)
        b.setLibraryArtist("Ana")
        b.setLibraryPlaylist("Otra")
        self.assertEqual(b.libraryArtist, "")
        self.assertEqual([r["artist"] for r in b.library], ["Otro"])

    def test_opening_folder_clears_playlist_and_artist_context(self):
        b = self.bridge
        b._library_artist = "Ana"
        b._library_folder = ""
        b._library_roots = lambda: []
        b.setLibraryFolder("Colección local")
        self.assertEqual(b._library_folder, "Colección local")
        self.assertEqual(b._library_playlist, "")
        self.assertEqual(b.libraryArtist, "")

    def test_mixed_queue_advances_only_through_selected_artist_and_survives_browsing(self):
        b = self.bridge
        b.setLibraryArtist("Ana")
        b.playLibraryTrack(b.library[0])
        b.setLibraryArtist("")
        self.assertEqual(len(b._queue), 2)
        b.skip(1)
        self.assertEqual(b._current["id"], "online")
        self.assertEqual(b._queue_current_index(), 1)
        b.skip(-1)
        self.assertEqual(b._current["path"], "/test/a.mp3")
        b._related_songs = Mock()
        b._maybe_autoplay({"id": "online"})
        b._related_songs.assert_not_called()

    def test_shuffle_and_queue_clicks_keep_both_local_and_online_tracks(self):
        b = self.bridge
        b.setLibraryArtist("Ana")
        with patch("musicbox_desktop.random.shuffle", side_effect=lambda rows: rows.reverse()):
            b.shuffleVisible()
        self.assertEqual(b._current["id"], "online")
        b.playQueueIndex(1)
        self.assertEqual(b._current["path"], "/test/a.mp3")
        self.assertEqual(len(b._queue), 2)

    def test_no_matches_does_not_start_playback(self):
        b = self.bridge
        b.setLibraryArtist("Ausente")
        b.shuffleVisible()
        self.assertEqual(b.library, [])
        b._start_local_track.assert_not_called()
        b._play_online.assert_not_called()

    def test_shuffle_starts_with_clicked_song_even_if_previous_song_is_in_same_list(self):
        b = self.bridge
        b.setLibraryArtist("Ana")
        b._current = dict(b.library[0])
        b._shuffle = True
        b.playLibraryTrack(b.library[1])
        self.assertEqual(b._current["id"], "online")
        self.assertEqual(b._queue[0]["id"], "online")
        self.assertEqual(b._queue_current_index(), 0)


if __name__ == "__main__":
    unittest.main()
