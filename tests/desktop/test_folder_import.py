import tempfile
import unittest
from pathlib import Path

import folder_import as fi


def candidate(title, video, channel="", duration=None):
    return dict(title=title, channel=channel, duration=duration, url=f"https://www.youtube.com/watch?v={video}")


class FolderImportTests(unittest.TestCase):
    def test_title_from_filename_removes_track_numbers_and_underscores(self):
        self.assertEqual(fi.title_from_filename("03 - Luis_Jara - No sabía"), "Luis Jara - No sabía")
        self.assertEqual(fi.title_from_filename("(07) Tell Me Why"), "Tell Me Why")
        self.assertEqual(fi.title_from_filename("1999"), "1999")

    def test_build_query_does_not_repeat_the_artist(self):
        self.assertEqual(fi.build_query("No sabía", "Luis Jara"), "Luis Jara - No sabía")
        self.assertEqual(fi.build_query("Luis Jara - No sabía", "Luis Jara"), "Luis Jara - No sabía")
        self.assertEqual(fi.build_query("Tell Me Why", ""), "Tell Me Why")

    def test_scan_folder_lists_only_audio_sorted_and_recursive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sub").mkdir()
            (root / "b - Segunda.mp3").write_bytes(b"x")
            (root / "a - Primera.flac").write_bytes(b"x")
            (root / "sub" / "c - Tercera.m4a").write_bytes(b"x")
            (root / "notas.txt").write_text("no")
            songs, truncated = fi.scan_folder(root)
            self.assertFalse(truncated)
            self.assertEqual([Path(s.path).name for s in songs], ["a - Primera.flac", "b - Segunda.mp3", "c - Tercera.m4a"])
            flat, _ = fi.scan_folder(root, recursive=False)
            self.assertEqual(len(flat), 2)

    def test_scan_folder_respects_limit_and_missing_folder(self):
        with tempfile.TemporaryDirectory() as directory:
            for number in range(5):
                (Path(directory) / f"{number} canción.mp3").write_bytes(b"x")
            songs, truncated = fi.scan_folder(directory, limit=3)
            self.assertEqual(len(songs), 3)
            self.assertTrue(truncated)
        with self.assertRaises(ValueError):
            fi.scan_folder(Path(directory) / "no-existe")

    def test_scan_folder_can_be_cancelled(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "uno.mp3").write_bytes(b"x")
            songs, _ = fi.scan_folder(directory, cancelled=lambda: True)
            self.assertEqual(songs, [])

    def test_video_id_from_urls(self):
        self.assertEqual(fi.video_id("https://www.youtube.com/watch?v=abcDEF12345"), "abcDEF12345")
        self.assertEqual(fi.video_id("https://youtu.be/abcDEF12345"), "abcDEF12345")
        self.assertEqual(fi.video_id("https://example.com/"), "")

    def test_decide_auto_for_exact_match(self):
        song = fi.LocalSong("a.mp3", "No sabía", "Luis Jara", 0, "Luis Jara - No sabía")
        result = fi.decide(song, [candidate("Luis Jara - No sabía", "id000000001", "LUISJARA OFICIAL")])
        self.assertEqual(result["status"], "auto")
        self.assertEqual(result["id"], "id000000001")

    def test_decide_prefers_the_exact_title_among_candidates(self):
        song = fi.LocalSong("a.mp3", "Tell Me Why", "Supermode", 0, "Supermode - Tell Me Why")
        result = fi.decide(song, [candidate("Tell Me Why (Live)", "live0000001"),
                                  candidate("Supermode - Tell Me Why", "orig0000001", "Ultra Records")])
        self.assertEqual(result["id"], "orig0000001")
        self.assertEqual(result["index"], 1)

    def test_decide_asks_for_review_when_the_title_differs(self):
        song = fi.LocalSong("a.mp3", "Canción rara", "", 0, "Canción rara")
        result = fi.decide(song, [candidate("Otra cosa distinta", "other000001")])
        self.assertEqual(result["status"], "review")
        self.assertTrue(result["reason"])

    def test_decide_none_without_usable_candidates(self):
        song = fi.LocalSong("a.mp3", "x", "", 0, "x")
        self.assertEqual(fi.decide(song, [])["status"], "none")
        self.assertEqual(fi.decide(song, [dict(title="sin url", url="")])["status"], "none")

    def test_queries_for_merges_duplicates_in_order(self):
        songs = [fi.LocalSong("1", "A", "", 0, "Tema A"), fi.LocalSong("2", "B", "", 0, "Tema B"),
                 fi.LocalSong("3", "A", "", 0, "tema a"), fi.LocalSong("4", "", "", 0, "")]
        order, groups = fi.queries_for(songs)
        self.assertEqual(order, ["Tema A", "Tema B"])
        self.assertEqual(groups, [[0, 2], [1]])



class SpanishTitlesTests(unittest.TestCase):
    def test_filename_with_artist_and_title_is_split(self):
        self.assertEqual(fi.split_name("Luis Jara - No sabía"), ("No sabía", "Luis Jara"))
        self.assertEqual(fi.split_name("Sin separador"), ("Sin separador", ""))
        self.assertEqual(fi.split_name("A-B sin espacios"), ("A-B sin espacios", ""))

    def test_untagged_file_named_artist_title_matches_a_spanish_video_oficial(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "03 - Luis Jara - No sabía.mp3"
            file.write_bytes(b"x")
            song = fi.describe_file(file)
        self.assertEqual((song.title, song.artist, song.query), ("No sabía", "Luis Jara", "Luis Jara - No sabía"))
        result = fi.decide(song, [candidate("Luis Jara - No sabía (Video Oficial) HD", "n0vo-mLGe6I", "LUISJARA OFICIAL")])
        self.assertEqual(result["status"], "auto")


if __name__ == "__main__":
    unittest.main()
