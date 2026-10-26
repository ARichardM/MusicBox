import queue
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from PySide6.QtCore import QCoreApplication

import folder_import_controller as fic

app = QCoreApplication.instance() or QCoreApplication([])


def watch(vid, title, channel="Canal"):
    return dict(title=title, channel=channel, duration=200, url=f"https://www.youtube.com/watch?v={vid}")


class FakeBridge:
    def __init__(self, in_library=True):
        self.in_library = in_library
        self.calls = []

    def isInLibrary(self, folder):
        return self.in_library

    def linkLocalSongs(self, playlist, items, add_folder, folder):
        self.calls.append((playlist, items, add_folder, folder))
        return len(items)


class FakeJob:
    """Responde a cada consulta con candidatos fijos, como lo haría el proceso de búsqueda."""
    answers = {}

    def __init__(self):
        self.events = queue.Queue()
        self.cancelled = mock.Mock(is_set=lambda: False)

    def run(self, config, images=()):
        queries = config["queries"]
        for index, query in enumerate(queries):
            self.events.put(dict(event="import_status", text=f"Buscando {index + 1}", query_index=index))
            self.events.put(dict(event="search_result", index=index, query=query, total=len(queries),
                                 progress_index=index + 1, candidates=self.answers.get(query, [])))
        self.events.put(dict(event="import_summary", errors=0))
        self.events.put(dict(event="job_done", cancelled=False))

    def cancel(self):
        pass


def wait_for(condition, seconds=5):
    end = time.time() + seconds
    while time.time() < end:
        app.processEvents()
        if condition():
            return True
        time.sleep(0.01)
    return False


class FolderImportControllerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        for name in ("Luis Jara - No sabía.mp3", "Supermode - Tell Me Why.mp3", "tema raro.mp3"):
            (root / name).write_bytes(b"x")
        self.bridge = FakeBridge()
        self.controller = fic.FolderImportController(self.bridge, lambda: "")
        FakeJob.answers = {
            "Luis Jara - No sabía": [watch("jara0000001", "Luis Jara - No sabía")],
            "Supermode - Tell Me Why": [watch("live0000001", "Tell Me Why (Live)"),
                                        watch("orig0000001", "Supermode - Tell Me Why")],
            "tema raro": [watch("otro0000001", "Algo completamente distinto")],
        }

    def tearDown(self):
        self.controller.reset()
        self.directory.cleanup()

    def scan(self):
        self.controller.scan(self.directory.name)
        self.assertTrue(wait_for(lambda: self.controller.phase == "ready"), self.controller.status)

    def search(self):
        with mock.patch.object(fic, "ImportJob", FakeJob):
            self.controller.startSearch()
            self.assertTrue(wait_for(lambda: self.controller.phase == "review"), self.controller.status)

    def test_scan_finds_the_audio_files(self):
        self.scan()
        self.assertEqual(self.controller.total, 3)

    def test_scan_of_empty_folder_returns_to_pick_with_an_error(self):
        with tempfile.TemporaryDirectory() as empty:
            self.controller.scan(empty)
            self.assertTrue(wait_for(lambda: self.controller.phase == "pick"))
            self.assertTrue(self.controller.statusError)

    def test_search_classifies_each_song(self):
        self.scan()
        self.search()
        self.assertEqual((self.controller.autoCount, self.controller.reviewCount, self.controller.noneCount), (2, 1, 0))
        self.assertEqual(self.controller.includedCount, 2)

    def test_apply_links_only_included_songs_with_the_chosen_video(self):
        self.scan()
        self.search()
        self.assertEqual(self.controller.apply("Mi carpeta", False), 2)
        playlist, items, add_folder, folder = self.bridge.calls[0]
        self.assertEqual(playlist, "Mi carpeta")
        self.assertEqual({item["id"] for item in items}, {"jara0000001", "orig0000001"})
        self.assertTrue(all(Path(item["path"]).exists() for item in items))
        self.assertFalse(add_folder)

    def test_choosing_an_alternative_includes_the_song(self):
        self.scan()
        self.search()
        review = next(r for r in self.controller._rows if r["state"] == "review")
        self.controller.choose(review["id"], 0)
        self.assertEqual(self.controller.includedCount, 3)
        self.controller.apply("Lista", False)
        ids = {item["id"] for item in self.bridge.calls[0][1]}
        self.assertIn("otro0000001", ids)

    def test_apply_asks_to_add_the_folder_only_when_it_is_not_in_the_library(self):
        self.bridge.in_library = False
        self.scan()
        self.search()
        self.controller.apply("Lista", True)
        self.assertTrue(self.bridge.calls[0][2])

    def test_apply_without_marks_is_rejected(self):
        self.scan()
        self.search()
        self.controller.includeAll(False)
        self.assertEqual(self.controller.apply("Lista", False), -1)
        self.assertEqual(self.bridge.calls, [])

    def test_filter_limits_the_visible_rows(self):
        self.scan()
        self.search()
        self.controller.setFilter("review")
        self.assertEqual(self.controller.rows.length, 1)
        self.controller.setFilter("all")
        self.assertEqual(self.controller.rows.length, 3)

    def test_reset_returns_to_the_first_step(self):
        self.scan()
        self.controller.reset()
        self.assertEqual((self.controller.phase, self.controller.total), ("pick", 0))


if __name__ == "__main__":
    unittest.main()
