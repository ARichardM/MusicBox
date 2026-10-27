"""Las recomendaciones salen del historial reciente y se degradan sin red."""

import unittest
from datetime import datetime, timedelta, timezone

import youtube_streams
from click_stats import ClickStats
from recommendations import MAX_SONGS, Recommender, combine

NOW = datetime.now(timezone.utc)


def entry(video_id, title=None, channel="Canal", duration=200):
    return {"id": video_id, "title": title or f"Canción {video_id}", "channel": channel, "duration": duration}


def stats_with(*plays):
    stats = ClickStats("pc")
    for video_id, count in plays:
        for _ in range(count):
            stats.record(f"yt:{video_id}", f"Semilla {video_id}", now=NOW)
    return stats


class CombineTest(unittest.TestCase):
    def test_song_in_several_lists_rises(self):
        outcome = combine([(1.0, [entry("a"), entry("shared")]), (1.0, [entry("b"), entry("shared")]),
                           (1.0, [entry("c"), entry("shared")])])
        ids = [song["id"] for song in outcome["songs"]]
        self.assertEqual(ids.count("shared"), 1)
        self.assertLess(ids.index("shared"), ids.index("b"))

    def test_filters_known_duplicates_and_long_videos(self):
        outcome = combine(
            [(1.0, [entry("seed"), entry("own"), entry("x", "Mismo Título"), entry("y", "mismo título (Official)"),
                    entry("mix", duration=3600), entry("ok")])],
            exclude_ids={"seed"}, exclude_titles={"Canción own"})
        self.assertEqual([song["id"] for song in outcome["songs"]], ["x", "ok"])

    def test_limit_and_artists(self):
        entries = [entry(str(i), channel="Ana - Topic" if i % 2 else "Beto") for i in range(40)]
        outcome = combine([(1.0, entries)])
        self.assertEqual(len(outcome["songs"]), MAX_SONGS)
        self.assertIn("Ana", outcome["artists"])
        self.assertNotIn("Ana - Topic", outcome["artists"])

    def test_entries_without_id_or_title_are_ignored(self):
        self.assertEqual(combine([(1.0, [{"id": "", "title": "x"}, {"id": "a", "title": ""}])])["songs"], [])


class RecommenderTest(unittest.TestCase):
    def setUp(self):
        self.related_calls, self.search_calls = [], []

    def related(self, video_id):
        self.related_calls.append(video_id)
        return [entry(f"{video_id}-1"), entry(f"{video_id}-2")]

    def search(self, query):
        self.search_calls.append(query)
        return [entry("s1"), entry("s2")]

    def test_uses_the_top_seeds_and_weights_the_most_listened_higher(self):
        recommender = Recommender(self.related, self.search)
        outcome = recommender.compute(stats_with(("fav", 5), ("meh", 1)), now=NOW)
        self.assertEqual(outcome["mode"], "seeds")
        self.assertEqual(set(self.related_calls), {"fav", "meh"})
        self.assertEqual(outcome["songs"][0]["id"], "fav-1")
        self.assertNotIn("fav", [song["id"] for song in outcome["songs"]])

    def test_result_is_cached_until_the_seeds_change(self):
        recommender = Recommender(self.related, self.search)
        stats = stats_with(("fav", 3))
        recommender.compute(stats, now=NOW)
        recommender.compute(stats, now=NOW)
        self.assertEqual(self.related_calls, ["fav"])
        stats.record("yt:new", "Nueva", now=NOW)
        stats.record("yt:new", "Nueva", now=NOW)
        stats.record("yt:new", "Nueva", now=NOW)
        stats.record("yt:new", "Nueva", now=NOW)
        recommender.compute(stats, now=NOW)
        self.assertIn("new", self.related_calls)

    def test_fresh_ignores_a_valid_cache_but_a_failure_keeps_the_last_result(self):
        recommender = Recommender(self.related, self.search)
        stats = stats_with(("fav", 2))
        first = recommender.compute(stats, now=NOW)
        recommender.compute(stats, now=NOW)
        self.assertEqual(self.related_calls, ["fav"])
        recommender.compute(stats, now=NOW, fresh=True)
        self.assertEqual(self.related_calls, ["fav", "fav"])
        recommender._related = lambda _video: (_ for _ in ()).throw(OSError("sin red"))
        self.assertEqual(recommender.compute(stats, now=NOW, fresh=True)["songs"], first["songs"])

    def test_cache_expires(self):
        clock = [1000.0]
        recommender = Recommender(self.related, self.search, clock=lambda: clock[0], ttl=60)
        stats = stats_with(("fav", 2))
        recommender.compute(stats, now=NOW)
        clock[0] += 61
        recommender.compute(stats, now=NOW)
        self.assertEqual(self.related_calls, ["fav", "fav"])

    def test_new_user_falls_back_to_recent_queries_then_generic(self):
        recommender = Recommender(self.related, self.search)
        self.assertEqual(recommender.compute(ClickStats("pc"), recent_queries=["rock", "jazz", "x"])["mode"], "queries")
        self.assertEqual(self.search_calls, ["rock", "jazz"])
        self.assertEqual(recommender.compute(ClickStats("pc"))["mode"], "trending")

    def test_offline_returns_the_last_result_or_an_empty_one(self):
        def broken(_value):
            raise OSError("sin red")

        stats = stats_with(("fav", 2))
        self.assertEqual(Recommender(broken, broken).compute(stats, now=NOW),
                         {"songs": [], "artists": [], "mode": "offline"})
        clock = [0.0]
        recommender = Recommender(self.related, broken, clock=lambda: clock[0], ttl=10)
        good = recommender.compute(stats, now=NOW)
        recommender._related = broken
        clock[0] += 100
        self.assertEqual(recommender.compute(stats, now=NOW)["songs"], good["songs"])

    def test_snapshot_round_trips(self):
        first = Recommender(self.related, self.search)
        stats = stats_with(("fav", 2))
        first.compute(stats, now=NOW)
        second = Recommender(self.related, self.search)
        second.restore(first.snapshot())
        before = len(self.related_calls)
        second.compute(stats, now=NOW)
        self.assertEqual(len(self.related_calls), before)


class RelatedFetchTest(unittest.TestCase):
    def test_reads_the_mix_playlist_and_drops_the_seed(self):
        calls = []

        def extractor(options, url):
            calls.append((options, url))
            return {"entries": [{"id": "abc"}, {"id": "n1"}, None, {"id": "n2"}]}

        entries = youtube_streams.related("abc", extractor, 5)
        self.assertEqual([item["id"] for item in entries], ["n1", "n2"])
        options, url = calls[0]
        self.assertIn("list=RDabc", url)
        self.assertFalse(options["noplaylist"])

    def test_retries_other_clients_and_raises_the_last_error(self):
        def failing(_options, _url):
            raise RuntimeError("bloqueado")

        with self.assertRaises(RuntimeError):
            youtube_streams.related("abc", failing)


if __name__ == "__main__":
    unittest.main()
