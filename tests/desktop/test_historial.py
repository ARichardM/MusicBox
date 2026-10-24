"""Persistencia, paginación y acciones del historial con archivos locales."""

from concurrent.futures import ThreadPoolExecutor
import gc
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock

import descargador_gui as app
from historial import DownloadHistory, PAGE_SIZE


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = DownloadHistory(self.root / "datos" / "history.sqlite3")

    def tearDown(self):
        self.temp.cleanup()

    def entry(self, title="Canción 日本語", **extra):
        return dict(filename=str(self.root / f"{title}.mp3"), title=title,
                    artist="Artista", source_url="https://www.youtube.com/watch?v=prueba",
                    quality="192 kbps", **extra)

    def test_empty_read_does_not_create_data_and_records_survive_reopening(self):
        self.assertEqual(self.store.page(), ([], 0))
        self.assertFalse(self.store.path.exists())
        self.store.record(self.entry())
        rows, total = DownloadHistory(self.store.path).page("日本語")
        self.assertEqual(total, 1)
        self.assertEqual(rows[0]["artist"], "Artista")
        self.assertEqual(rows[0]["format"], "MP3")
        self.assertEqual(rows[0]["state"], "downloaded")
        self.assertIn("+00:00", rows[0]["created_at"])

    def test_pagination_search_and_latest_attempt_first(self):
        for index in range(PAGE_SIZE + 3):
            self.store.record(self.entry(f"Canción {index:03d}"))
        rows, total = self.store.page()
        self.assertEqual((len(rows), total), (PAGE_SIZE, PAGE_SIZE + 3))
        self.assertEqual(rows[0]["title"], f"Canción {PAGE_SIZE + 2:03d}")
        older, total = self.store.page(offset=PAGE_SIZE)
        self.assertEqual(len(older), 3)
        self.assertEqual(older[-1]["title"], "Canción 000")
        self.assertEqual(self.store.page("ARTISTA")[1], PAGE_SIZE + 3)
        self.assertEqual(self.store.page("' OR 1=1 --"), ([], 0))

    def test_concurrent_writes_and_existing_attempt_do_not_lose_records(self):
        with ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(lambda index: self.store.record(self.entry(f"Pista {index}")), range(12)))
        self.store.record(self.entry("Pista 0", state="existing"))
        rows, total = self.store.page()
        self.assertEqual(total, 13)
        self.assertEqual(rows[0]["state"], "existing")


class HistoryGuiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.path = self.directory / "history.sqlite3"
        self.file = self.directory / "Canción.mp3"
        self.file.write_bytes(b"archivo de prueba")
        self.store = DownloadHistory(self.path)
        self.store.record(dict(filename=str(self.file), title="Canción especial", artist="Artista",
                               source_url="https://www.youtube.com/watch?v=prueba"))
        self.window = app.launch_gui(history_path=self.path)
        self.window.withdraw()
        self.panel = self.window.history_panel
        self.wait_for(lambda: len(self.panel.records) == 1)

    def tearDown(self):
        self.window.destroy()
        self.window = self.panel = None
        gc.collect()
        self.temp.cleanup()

    def wait_for(self, condition):
        deadline = time.monotonic() + 5
        while not condition():
            self.assertLess(time.monotonic(), deadline, "El historial no terminó de cargar")
            self.window.update()
            time.sleep(0.01)

    def select_first(self):
        self.panel.tree.selection_set(self.panel.tree.get_children()[0])
        self.window.update()

    def test_logo_history_open_actions_and_queue(self):
        self.assertEqual((self.window.logo_image.width(), self.window.logo_image.height()), (48, 48))
        self.assertIn(str(self.panel), self.window.notebook.tabs())
        self.select_first()
        self.panel.open_path = Mock()
        self.panel.open_selected()
        opened_file = self.panel.open_path.call_args.args[0]
        self.assertTrue(opened_file.samefile(self.file))
        self.panel.open_selected(folder=True)
        opened_folder = self.panel.open_path.call_args.args[0]
        self.assertTrue(opened_folder.samefile(self.directory))
        self.panel.add_selected()
        self.assertIn("watch?v=prueba", self.window.urls.get("1.0", "end"))
        self.window.set_running(True)
        self.assertEqual(str(self.panel.queue_button["state"]), "disabled")
        self.window.set_running(False)

    def test_filter_missing_file_and_refresh_after_ready(self):
        self.panel.search_var.set("no existe")
        self.panel.search()
        self.wait_for(lambda: not self.panel.records)
        self.assertEqual(self.panel.status_var.get(), "Sin resultados.")
        self.panel.show_all()
        self.wait_for(lambda: len(self.panel.records) == 1)
        self.select_first()
        self.file.unlink()
        self.panel.open_path = Mock()
        self.panel.open_selected()
        self.panel.open_path.assert_not_called()
        self.assertIn("ya no existe", self.panel.status_var.get())
        self.store.record(dict(filename=str(self.directory / "Otra.mp3"), title="Otra"))
        self.window.handle_event(dict(event="ready", filename=str(self.directory / "Otra.mp3"), completed=1))
        self.wait_for(lambda: len(self.panel.records) == 2)

    def test_stale_results_do_not_replace_current_search(self):
        self.panel.events.put((self.panel.generation - 1, [], 0, ""))
        self.panel.after_cancel(self.panel.poll_timer)
        self.panel.poll_timer = None
        self.panel.poll()
        self.assertEqual(len(self.panel.records), 1)

    def test_expanded_history_is_reused_and_tracks_download_state(self):
        self.window.open_download_history()
        history_window = self.window.history_window
        self.window.open_download_history()
        self.assertIs(self.window.history_window, history_window)
        expanded = self.window.history_window_panel
        self.wait_for(lambda: len(expanded.records) == 1)
        expanded.tree.selection_set(expanded.tree.get_children()[0])
        self.window.update()
        self.assertEqual(str(expanded.queue_button["state"]), "normal")
        self.window.set_running(True)
        self.assertEqual(str(expanded.queue_button["state"]), "disabled")
        self.window.set_running(False)
        history_window.destroy()
        self.window.open_download_history()
        self.assertIsNot(self.window.history_window, history_window)


if __name__ == "__main__":
    unittest.main(verbosity=2)
