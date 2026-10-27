"""Regresiones de sincronización: copias antiguas, borrados explícitos y escrituras simultáneas."""
import unittest
from playlist_sync import merge_playlists, append_audit


def section(**lists):
    return {"playlists": lists}


class PlaylistMergeTests(unittest.TestCase):
    def test_an_unchanged_empty_copy_does_not_clear_remote_songs(self):
        self.assertEqual(merge_playlists(section(driving=[]), section(driving=[]), section(driving=["yt:a"])),
                         section(driving=["yt:a"]))

    def test_simultaneous_additions_survive_in_both_lists(self):
        result = merge_playlists(section(driving=["a"]), section(driving=["a", "b"]), section(driving=["a", "c"]))
        self.assertEqual(result, section(driving=["a", "b", "c"]))

    def test_explicit_removal_is_kept_but_unseen_remote_additions_survive(self):
        self.assertEqual(merge_playlists(section(driving=["a", "b"]), section(driving=[]), section(driving=["a", "b", "c"])),
                         section(driving=["c"]))

    def test_remote_deletion_is_not_resurrected_by_a_playback_stats_upload(self):
        base = section(driving=["a"])
        self.assertEqual(merge_playlists(base, base, section(driving=[])), section(driving=[]))

    def test_deleted_playlist_stays_deleted_when_other_copy_is_unchanged(self):
        base = section(driving=["a"], chill=["x"])
        self.assertEqual(merge_playlists(base, base, section(chill=["x"])), section(chill=["x"]))

    def test_conflicting_playlist_delete_preserves_concurrent_edits(self):
        self.assertEqual(merge_playlists(section(driving=["a"]), section(), section(driving=["a", "b"])),
                         section(driving=["a", "b"]))

    def test_order_changes_and_metadata_of_other_devices_survive(self):
        before = {**section(driving=["a", "b"]), "onlineTracks": {"a": {"title": "A"}}}
        local = {**section(driving=["b", "a"]), "onlineTracks": {"a": {"title": "A"}}}
        remote = {**section(driving=["a", "b"]), "onlineTracks": {"a": {"title": "New A"}, "c": {"title": "C"}}}
        result = merge_playlists(before, local, remote)
        self.assertEqual(result["playlists"]["driving"], ["b", "a"])
        self.assertEqual(result["onlineTracks"], remote["onlineTracks"])

    def test_input_snapshots_are_never_modified(self):
        base = section(driving=["a"])
        result = merge_playlists(base, base, base)
        result["playlists"]["driving"].append("b")
        self.assertEqual(base, section(driving=["a"]))

    def test_audit_records_writer_and_counts_only_when_membership_changes(self):
        root = {}
        append_audit(root, section(driving=["a"]), section(driving=[]), "test-device", "Windows", 123)
        change = root["playlistAudit"][0]
        self.assertEqual(change["device"], "test-device")
        self.assertEqual(change["changes"], [{"name": "driving", "before": 1, "after": 0, "added": 0, "removed": 1}])
        append_audit(root, section(driving=[]), section(driving=[]), "other", "Android", 124)
        self.assertEqual(len(root["playlistAudit"]), 1)


if __name__ == "__main__":
    unittest.main()
