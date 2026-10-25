"""Los clics se comparten entre dispositivos sin duplicarse ni perderse."""

import unittest

from click_stats import ClickStats, normalize_title, song_key


class ClickStatsTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
