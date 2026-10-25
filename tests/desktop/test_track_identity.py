"""La identidad por ID de YouTube evita duplicados y mantiene el formato compartido con Android."""

import unittest

from track_identity import canonicalize, export_online_tracks, match_local_ids, normalize_online_tracks

ANDROID = {"n0vo-mLGe6I": {"title": "Luis Jara - No sabía (Video Oficial) HD", "channel": "LUISJARA OFICIAL",
                          "duration": 0, "thumbnail": "t", "url": "u"}}


class TrackIdentityTest(unittest.TestCase):
    def test_android_tracks_get_ids_and_full_fields(self):
        tracks = normalize_online_tracks(ANDROID)
        meta = tracks["youtube:n0vo-mLGe6I"]
        self.assertEqual(meta["id"], "n0vo-mLGe6I")
        self.assertEqual(meta["subtitle"], "LUISJARA OFICIAL")

    def test_export_round_trip_uses_bare_keys(self):
        exported = export_online_tracks(normalize_online_tracks(ANDROID))
        self.assertEqual(list(exported), ["n0vo-mLGe6I"])
        self.assertEqual(exported["n0vo-mLGe6I"]["channel"], "LUISJARA OFICIAL")

    def test_local_file_is_linked_by_title(self):
        tracks = normalize_online_tracks(ANDROID)
        rows = [{"path": "C:/m/Luis_Jara.mp3", "title": "Luis Jara - No sabia Video Oficial HD"},
                {"path": "C:/m/otra.mp3", "title": "Otra"}]
        ids = match_local_ids(rows, tracks)
        self.assertEqual(ids, {"C:/m/Luis_Jara.mp3": "n0vo-mLGe6I"})

    def test_playlist_entries_are_unified_without_duplicates(self):
        tracks = normalize_online_tracks(ANDROID)
        playlists = {"Favoritos": ["youtube:n0vo-mLGe6I", "C:/m/Luis_Jara.mp3", "C:/m/solo.mp3"]}
        result, changed = canonicalize(playlists, {"C:/m/Luis_Jara.mp3": "n0vo-mLGe6I"}, tracks)
        self.assertTrue(changed)
        self.assertEqual(result["Favoritos"], ["youtube:n0vo-mLGe6I", "C:/m/solo.mp3"])
        again, changed_again = canonicalize(result, {"C:/m/Luis_Jara.mp3": "n0vo-mLGe6I"}, tracks)
        self.assertFalse(changed_again)
        self.assertEqual(again, result)

    def test_unknown_metadata_keeps_the_path(self):
        result, changed = canonicalize({"A": ["C:/x.mp3"]}, {"C:/x.mp3": "abc"}, {})
        self.assertFalse(changed)
        self.assertEqual(result["A"], ["C:/x.mp3"])


if __name__ == "__main__":
    unittest.main()
