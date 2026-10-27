"""El puente abre la página de un artista, resuelve su foto una sola vez y avanza por una lista online."""

import json
import os
import time
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QCoreApplication, QObject
from PySide6.QtWidgets import QApplication

import musicbox_desktop as app_module
import ytmusic

APP = QApplication.instance() or QApplication([])
FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name):
    return json.loads((FIXTURES / f"ytmusic_{name}.json").read_text(encoding="utf-8"))


def make_bridge():
    bridge = app_module.MusicBoxBridge.__new__(app_module.MusicBoxBridge)
    QObject.__init__(bridge)
    bridge._artist_open = bridge._album_open = False
    bridge._artist, bridge._album, bridge._artist_all = {}, {}, []
    bridge._artist_loading = bridge._album_loading = bridge._artist_all_loading = bridge._artist_all_loaded = False
    bridge._artist_error = ""
    bridge._artist_gen = bridge._album_gen = 0
    bridge._online_queue, bridge._online_index = [], -1
    bridge._artist_directory = {}
    bridge._current = {}
    bridge._decorate_tracks = lambda tracks: tracks
    bridge.notices = []
    bridge._show_notice = lambda text, retry=None, kind="error": bridge.notices.append(text)
    bridge._artistReady.connect(bridge._apply_artist)
    bridge._artistSongsReady.connect(bridge._apply_artist_songs)
    bridge._albumReady.connect(bridge._apply_album)
    bridge.played = []
    bridge._play_online = lambda track: (bridge.played.append(track["id"]), bridge._current.update(id=track["id"]))
    return bridge


def wait_for(condition, seconds=5):
    end = time.time() + seconds
    while time.time() < end:
        QCoreApplication.processEvents()
        if condition():
            return True
        time.sleep(0.01)
    return False


def tracks(*ids):
    return [{"id": vid, "title": vid, "url": f"https://www.youtube.com/watch?v={vid}"} for vid in ids]


class OnlineQueueTests(unittest.TestCase):
    def test_a_list_plays_from_the_chosen_song_and_moves_on(self):
        bridge = make_bridge()
        bridge.playOnlineList(tracks("a", "b", "c"), 1)
        self.assertEqual(bridge.played, ["b"])
        self.assertTrue(bridge._advance_online(1))
        self.assertTrue(bridge._advance_online(1))
        self.assertEqual(bridge.played, ["b", "c"])

    def test_the_end_of_the_list_does_not_wrap_or_play_again(self):
        bridge = make_bridge()
        bridge.playOnlineList(tracks("a", "b"), 1)
        self.assertTrue(bridge._advance_online(1))
        self.assertEqual(bridge.played, ["b"])

    def test_previous_goes_back(self):
        bridge = make_bridge()
        bridge.playOnlineList(tracks("a", "b", "c"), 2)
        self.assertTrue(bridge._advance_online(-1))
        self.assertEqual(bridge.played, ["c", "b"])

    def test_a_different_current_track_is_not_part_of_the_queue(self):
        bridge = make_bridge()
        bridge.playOnlineList(tracks("a", "b"), 0)
        bridge._current = {"id": "something-else"}
        self.assertFalse(bridge._advance_online(1))

    def test_invalid_lists_are_ignored(self):
        bridge = make_bridge()
        bridge.playOnlineList([], 0)
        bridge.playOnlineList(tracks("a"), 5)
        bridge.playOnlineList([{"id": "x"}], 0)
        self.assertEqual(bridge.played, [])


class ArtistDirectoryTests(unittest.TestCase):
    def test_a_found_artist_is_remembered_and_not_searched_twice(self):
        bridge = make_bridge()
        found = {"name": "Los Prisioneros", "browse_id": "UC1", "photo": "https://x/p.jpg"}
        with patch.object(ytmusic, "find_artist", return_value=found) as find:
            first = bridge._resolve_artist("Los Prisioneros")
            second = bridge._resolve_artist("los prisioneros")
        self.assertEqual(find.call_count, 1)
        self.assertEqual(first, {"name": "Los Prisioneros", "browseId": "UC1", "photo": "https://x/p.jpg"})
        self.assertEqual(second, first)

    def test_an_unknown_artist_keeps_its_name_without_a_photo(self):
        bridge = make_bridge()
        with patch.object(ytmusic, "find_artist", return_value=None):
            entry = bridge._resolve_artist("Nadie")
        self.assertEqual(entry, {"name": "Nadie", "browseId": "", "photo": ""})
        self.assertEqual(bridge._artist_directory, {})

    def test_a_network_failure_does_not_break_the_list(self):
        bridge = make_bridge()
        with patch.object(ytmusic, "find_artist", side_effect=OSError("sin red")):
            self.assertEqual(bridge._resolve_artist("X")["photo"], "")


class ArtistPageTests(unittest.TestCase):
    def test_opening_an_artist_loads_its_page(self):
        bridge = make_bridge()
        page = ytmusic.artist_page("UCTDZPojhkqe4pfN9X-9ZnBw", api=lambda endpoint, body: fixture("artist"))
        with patch.object(ytmusic, "artist_page", return_value=page):
            bridge.openArtist("UCTDZPojhkqe4pfN9X-9ZnBw", "Los Prisioneros")
            self.assertTrue(bridge.artistOpen and bridge.artistLoading)
            self.assertTrue(wait_for(lambda: not bridge.artistLoading))
        self.assertEqual(bridge.artistName, "Los Prisioneros")
        self.assertTrue(bridge.artistPhoto.startswith("https://"))
        self.assertTrue(bridge.artistTopSongs and bridge.artistAlbums and bridge.artistSingles)
        self.assertTrue(bridge.artistCanLoadAll)

    def test_a_failure_shows_an_error_instead_of_an_empty_page(self):
        bridge = make_bridge()
        with patch.object(ytmusic, "artist_page", side_effect=OSError("sin red")):
            bridge.openArtist("UC1", "X")
            self.assertTrue(wait_for(lambda: not bridge.artistLoading))
        self.assertTrue(bridge.artistError)
        self.assertEqual(bridge.artistTopSongs, [])

    def test_a_slow_answer_for_a_closed_artist_is_ignored(self):
        bridge = make_bridge()
        with patch.object(ytmusic, "artist_page", return_value={"name": "Tarde", "top_songs": []}):
            bridge.openArtist("UC1", "X")
            bridge.closeArtist()
            wait_for(lambda: False, 0.3)
        self.assertFalse(bridge.artistOpen)
        self.assertEqual(bridge.artistName, "")

    def test_all_songs_load_once_and_hide_the_button(self):
        bridge = make_bridge()
        bridge._artist = {"name": "A", "songs_playlist": "VLX", "top_songs": []}
        bridge._artist_open = True
        with patch.object(ytmusic, "playlist_tracks", return_value=tracks("a", "b")) as playlist:
            bridge.loadAllArtistSongs()
            bridge.loadAllArtistSongs()
            self.assertTrue(wait_for(lambda: not bridge.artistAllLoading))
        self.assertEqual(playlist.call_count, 1)
        self.assertEqual(len(bridge.artistAllSongs), 2)
        self.assertFalse(bridge.artistCanLoadAll)

    def test_an_album_opens_with_its_tracks_and_closes(self):
        bridge = make_bridge()
        album = ytmusic.album_page("MPREb_x", api=lambda endpoint, body: fixture("album"))
        with patch.object(ytmusic, "album_page", return_value=album):
            bridge.openAlbum("MPREb_x")
            self.assertTrue(bridge.albumOpen and bridge.albumLoading)
            self.assertTrue(wait_for(lambda: not bridge.albumLoading))
        self.assertTrue(bridge.albumTitle.startswith("La Voz de los"))
        self.assertTrue(bridge.albumTracks)
        bridge.closeAlbum()
        self.assertFalse(bridge.albumOpen)

    def test_an_empty_album_closes_with_a_notice(self):
        bridge = make_bridge()
        with patch.object(ytmusic, "album_page", return_value={"tracks": []}):
            bridge.openAlbum("MPREb_x")
            self.assertTrue(wait_for(lambda: not bridge.albumLoading))
        self.assertFalse(bridge.albumOpen)
        self.assertTrue(bridge.notices)


if __name__ == "__main__":
    unittest.main()
