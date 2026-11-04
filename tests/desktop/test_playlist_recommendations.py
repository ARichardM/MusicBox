"""Sugerencias para una playlist: semillas de la propia playlist y nada que ya tenga."""

import unittest

from recommendations import PLAYLIST_SONGS, Recommender, playlist_seeds


def entry(video_id, title=None, channel="Canal", duration=200):
    return {"id": video_id, "title": title or f"Canción {video_id}", "channel": channel, "duration": duration}


class PlaylistSeedsTest(unittest.TestCase):
    def test_most_played_first_then_latest_added(self):
        ids = ["a", "b", "c", "d", "e", "f"]
        plays = {"b": 5, "a": 2}
        seeds = playlist_seeds(ids, plays.get, limit=4)
        self.assertEqual([vid for vid, _weight in seeds], ["b", "a", "f", "e"])

    def test_weights_are_normalised_and_unplayed_songs_still_count(self):
        seeds = dict(playlist_seeds(["a", "b"], {"a": 4}.get, limit=4))
        self.assertEqual(seeds["a"], 1.0)
        self.assertGreater(seeds["b"], 0)
        self.assertLess(seeds["b"], 1.0)

    def test_empty_playlist_has_no_seeds(self):
        self.assertEqual(playlist_seeds([], lambda _id: 0), [])

    def test_duplicates_are_ignored(self):
        self.assertEqual([vid for vid, _ in playlist_seeds(["a", "a", "b"], lambda _id: 0, limit=4)], ["b", "a"])


class ForPlaylistTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.now = [1000.0]

        def related(video_id):
            self.calls.append(video_id)
            return {"a": [entry("own"), entry("x"), entry("y")], "b": [entry("x"), entry("z")]}.get(video_id, [])

        self.recommender = Recommender(related, lambda query: [], clock=lambda: self.now[0])

    def test_excludes_what_the_playlist_already_has_and_the_seeds(self):
        outcome = self.recommender.for_playlist([("a", 1.0), ("b", 0.5)], {"own"}, ["Canción own"])
        ids = [song["id"] for song in outcome["songs"]]
        self.assertEqual(ids[0], "x")  # aparece en dos listas
        self.assertNotIn("own", ids)
        self.assertNotIn("a", ids)
        self.assertEqual(outcome["mode"], "playlist")

    def test_limit(self):
        many = Recommender(lambda _id: [entry(str(i)) for i in range(40)], lambda q: [], clock=lambda: 0)
        self.assertEqual(len(many.for_playlist([("a", 1.0)], set(), [])["songs"]), PLAYLIST_SONGS)

    def test_cached_per_seed_set_until_fresh(self):
        self.recommender.for_playlist([("a", 1.0)], set(), [])
        self.recommender.for_playlist([("a", 1.0)], set(), [])
        self.assertEqual(self.calls, ["a"])
        self.recommender.for_playlist([("a", 1.0)], set(), [], fresh=True)
        self.assertEqual(self.calls, ["a", "a"])
        self.recommender.for_playlist([("b", 1.0)], set(), [])
        self.assertEqual(self.calls, ["a", "a", "b"])

    def test_cache_does_not_evict_for_you_and_expires(self):
        for letter in "abcdefghij":
            self.recommender.for_playlist([(letter, 1.0)], set(), [])
        self.assertEqual(len(self.calls), 10)
        self.now[0] += 7 * 3600
        self.recommender.for_playlist([("j", 1.0)], set(), [])
        self.assertEqual(len(self.calls), 11)

    def test_cached_result_is_filtered_again_with_the_current_playlist(self):
        self.recommender.for_playlist([("a", 1.0)], set(), [])
        again = self.recommender.for_playlist([("a", 1.0)], {"x"}, [])
        self.assertNotIn("x", [song["id"] for song in again["songs"]])

    def test_offline_returns_last_result_or_empty(self):
        self.recommender.for_playlist([("a", 1.0)], set(), [])

        def broken(_id):
            raise OSError("sin red")
        offline = Recommender(broken, lambda q: [], clock=lambda: 0)
        offline.restore_playlists(self.recommender.snapshot_playlists())
        self.assertTrue(offline.for_playlist([("a", 1.0)], set(), [], fresh=True)["songs"])
        self.assertEqual(offline.for_playlist([("zz", 1.0)], set(), [], fresh=True), {"songs": [], "mode": "offline"})

    def test_no_seeds_gives_empty_result_without_network(self):
        self.assertEqual(self.recommender.for_playlist([], set(), []), {"songs": [], "mode": "offline"})
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main()


class BridgeSuggestionsTest(unittest.TestCase):
    def setUp(self):
        import os
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        from unittest.mock import Mock
        from PySide6.QtCore import QObject
        from PySide6.QtWidgets import QApplication
        from musicbox_desktop import MusicBoxBridge
        self.app = QApplication.instance() or QApplication([])
        self.bridge = b = MusicBoxBridge.__new__(MusicBoxBridge)
        QObject.__init__(b)
        b._playlists = {"Mix": ["youtube:a", "/m/b.mp3", "youtube:c"], "Vacía": []}
        b._path_ids = {"/m/b.mp3": "b"}
        b._library_playlist, b._library_query, b._library_artist = "", "", ""
        b._library_all, b._online_tracks = [{"path": "/m/b.mp3", "title": "Bravo"}], {}
        b._playlist_sugg = {"playlist": "", "songs": [], "loading": False}
        b._playlist_sugg_generation = 0
        b._playlist_sugg_collapsed = False
        b._settings = Mock()
        b._clicks_stats = Mock(count=lambda key: {"yt:b": 9}.get(key, 0))
        b._mark_local = lambda item, vid, title="": item.update(downloaded=False)
        b._is_fav = lambda item: False
        b.addTrackToPlaylist = Mock()
        self.calls = []

        def related(video_id):
            self.calls.append(video_id)
            return [entry("own"), entry("x"), entry("y")]
        b._recommender = Recommender(related, lambda q: [])
        # El hilo de trabajo se ejecuta en el acto para poder comprobar el resultado.
        b._run_in_thread = lambda work: work()
        b._playlistSuggestionsReady.connect(b._apply_playlist_suggestions)

    def test_seeds_use_only_the_open_playlist_and_exclude_its_songs(self):
        b = self.bridge
        b._playlists["Mix"].append("youtube:own")
        b.loadPlaylistSuggestions("Mix", False)
        self.assertEqual(self.calls[0], "b")  # la más escuchada primero
        self.assertEqual(set(self.calls), {"a", "b", "c", "own"})
        self.assertEqual([s["id"] for s in b.playlistSuggestions], ["x", "y"])
        self.assertEqual(b.playlistSuggestionsPlaylist, "Mix")
        self.assertFalse(b.playlistSuggestionsLoading)

    def test_empty_playlist_clears_and_does_not_call_the_network(self):
        b = self.bridge
        b.loadPlaylistSuggestions("Vacía", False)
        self.assertEqual(b.playlistSuggestions, [])
        self.assertEqual(self.calls, [])

    def test_stale_answers_for_another_playlist_are_ignored(self):
        b = self.bridge
        b._run_in_thread = lambda work: None  # la respuesta llega después
        b.loadPlaylistSuggestions("Mix", False)
        old = b._playlist_sugg_generation
        b.loadPlaylistSuggestions("Vacía", False)
        b._apply_playlist_suggestions({"songs": [entry("zzz")], "mode": "playlist"}, old, "Mix")
        self.assertEqual(b.playlistSuggestions, [])

    def test_adding_a_suggestion_removes_it_and_adds_it_to_the_open_playlist(self):
        b = self.bridge
        b.loadPlaylistSuggestions("Mix", False)
        b._library_playlist = "Mix"
        b.addSuggestionToPlaylist({"id": "x", "title": "Canción x"})
        b.addTrackToPlaylist.assert_called_once()
        self.assertEqual(b.addTrackToPlaylist.call_args.args[0], "Mix")
        self.assertEqual([s["id"] for s in b.playlistSuggestions], ["own", "y"])

    def test_collapsed_choice_is_saved(self):
        b = self.bridge
        b.setPlaylistSuggestionsCollapsed(True)
        b._settings.setValue.assert_called_with("playlistSuggestionsCollapsed", True)
        self.assertTrue(b.playlistSuggestionsCollapsed)
