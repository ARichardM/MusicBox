"""Los clics se comparten entre dispositivos sin duplicarse ni perderse."""

import unittest
from datetime import datetime, timedelta, timezone

from click_stats import ClickStats, normalize_title, song_key


class ClickStatsTest(unittest.TestCase):
    def test_listening_time_merges_without_duplicating_and_keeps_playlist_source(self):
        phone, pc = ClickStats("phone"), ClickStats("pc")
        phone.record("yt:a", "A")
        phone.record_listening("yt:a", "A", millis=65000, start=True, playlist="Favoritas")
        phone.record_listening("yt:a", "A", millis=5000)
        self.assertTrue(pc.merge(phone.export()))
        self.assertFalse(pc.merge(phone.export()))
        self.assertEqual(pc.listening_rows()[0]["ms"], 70000)
        self.assertEqual(pc.listening_rows(playlists=True)[0]["ms"], 65000)
        pc.record_listening("yt:a", "A", millis=30000, start=True, playlist="Favoritas")
        self.assertTrue(phone.merge(pc.export()))
        self.assertEqual(phone.listening_rows()[0]["ms"], 100000)
        self.assertEqual(phone.listening_rows(playlists=True)[0]["plays"], 2)
        self.assertFalse(phone.merge(pc.export()))

    def test_historical_plays_have_no_invented_listening_time(self):
        stats = ClickStats("pc")
        stats.record("yt:old", "Old song")
        self.assertEqual(stats.listening_rows()[0]["ms"], 0)
        self.assertEqual(stats.listening_rows(playlists=True), [])

    def test_same_song_gets_same_key_on_every_platform(self):
        self.assertEqual(song_key("Post Malone - Circles"), song_key("post malone – CIRCLES (Official Video)"))
        self.assertEqual(normalize_title("Canción  Ñandú [HD]"), "cancion nandu")
        self.assertEqual(song_key("lo que sea", "abc123"), "yt:abc123")

    def test_devices_add_up(self):
        phone, pc = ClickStats("phone"), ClickStats("pc")
        phone.record("t:a", "A"); phone.record("t:a", "A")
        pc.record("t:a", "A")
        pc.merge(phone.export()); phone.merge(pc.export())
        self.assertEqual(pc.count("t:a"), 3)
        self.assertEqual(phone.count("t:a"), 3)
        self.assertEqual(phone.total(), 3)

    def test_merging_twice_or_out_of_order_is_idempotent(self):
        phone, pc = ClickStats("phone"), ClickStats("pc")
        phone.record("t:a", "A")
        remote = phone.export()
        self.assertTrue(pc.merge(remote))
        self.assertFalse(pc.merge(remote))
        phone.record("t:a", "A")
        pc.merge(phone.export()); pc.merge(remote)
        self.assertEqual(pc.count("t:a"), 2)

    def test_a_stale_upload_never_lowers_a_counter(self):
        pc = ClickStats("pc")
        for _ in range(5):
            pc.record("t:a")
        pc.merge({"devices": {"pc": {"t:a": 2}}})
        self.assertEqual(pc.count("t:a"), 5)

    def test_legacy_migration_is_not_duplicated(self):
        pc = ClickStats("pc")
        self.assertTrue(pc.import_legacy("t:a", 4, "A"))
        self.assertFalse(pc.import_legacy("t:a", 4, "A"))
        pc.record("t:a")
        self.assertFalse(pc.import_legacy("t:a", 4, "A"))
        self.assertEqual(pc.count("t:a"), 5)

    def test_top_and_summary(self):
        stats = ClickStats("pc")
        for title, plays in (("B", 1), ("A", 3), ("C", 2)):
            for _ in range(plays):
                stats.record(song_key(title), title)
        self.assertEqual([title for _key, title, _n in stats.top(2)], ["A", "C"])
        self.assertEqual(stats.played_songs(), 3)

    def test_ignores_malformed_remote_data(self):
        stats = ClickStats("pc")
        self.assertFalse(stats.merge("nope"))
        self.assertFalse(stats.merge({"devices": {"x": "bad", "y": {"t:a": "oops"}}}))
        self.assertEqual(stats.total(), 0)


NOW = datetime.now(timezone.utc)


def days_ago(n):
    return NOW - timedelta(days=n)


class DailyClicksTest(unittest.TestCase):
    def test_record_counts_the_day_in_utc(self):
        stats = ClickStats("pc")
        stats.record("yt:a", "A", now=NOW)
        stats.record("yt:a", "A", now=NOW)
        self.assertEqual(stats.export()["daily"], {"pc": {"yt:a": {ClickStats._day(NOW): 2}}})
        self.assertEqual(stats.count("yt:a"), 2)

    def test_daily_merges_with_max_and_is_idempotent(self):
        phone, pc = ClickStats("phone"), ClickStats("pc")
        phone.record("yt:a", "A")
        remote = phone.export()
        self.assertTrue(pc.merge(remote))
        self.assertFalse(pc.merge(remote))
        stale = {"daily": {"phone": {"yt:a": {ClickStats._day(): 0}}}}
        self.assertFalse(pc.merge(stale))
        self.assertEqual(pc.export()["daily"]["phone"]["yt:a"][ClickStats._day()], 1)

    def test_days_older_than_the_window_are_dropped_and_not_reintroduced(self):
        stats = ClickStats("pc")
        stats.record("yt:a", "A", now=days_ago(120))
        stats.record("yt:a", "A", now=NOW)
        self.assertEqual(list(stats.export()["daily"]["pc"]["yt:a"]), [ClickStats._day(NOW)])
        self.assertEqual(stats.count("yt:a"), 2)
        old = {"daily": {"pc": {"yt:a": {ClickStats._day(days_ago(200)): 9}}}}
        self.assertFalse(stats.merge(old))

    def test_old_clients_without_daily_still_merge(self):
        stats = ClickStats("pc")
        self.assertTrue(stats.merge({"v": 1, "devices": {"phone": {"yt:a": 3}}, "titles": {"yt:a": "A"}}))
        self.assertEqual(stats.count("yt:a"), 3)

    def test_unknown_fields_survive_a_round_trip(self):
        stats = ClickStats("pc")
        stats.merge({"devices": {}, "future": {"x": 1}})
        self.assertEqual(stats.export()["future"], {"x": 1})

    def test_recent_plays_outweigh_old_ones(self):
        stats = ClickStats("pc")
        for _ in range(10):
            stats.record("yt:old", "Old", now=days_ago(60))
        for _ in range(3):
            stats.record("yt:new", "New", now=days_ago(1))
        self.assertGreater(stats.score("yt:new", NOW), stats.score("yt:old", NOW))

    def test_seeds_are_youtube_songs_ranked_by_score(self):
        stats = ClickStats("pc")
        stats.record("t:local only", "Local", now=NOW)
        for _ in range(3):
            stats.record("yt:b", "B", now=NOW)
        stats.record("yt:a", "A", now=NOW)
        self.assertEqual([key for key, _title, _score in stats.seeds(4, NOW)], ["yt:b", "yt:a"])
        self.assertEqual(len(stats.seeds(1, NOW)), 1)


if __name__ == "__main__":
    unittest.main()
