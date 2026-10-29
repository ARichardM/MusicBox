"""Pruebas de adjuntos, recorte, OCR real y elección de coincidencias."""

import gc
import queue
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from PIL import Image, ImageDraw, ImageFont

import canciones
import descargador_gui as backend
from importar_canciones import ImportSongsDialog


class ImageTests(unittest.TestCase):
    def test_crop_restore_preserves_source(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "foto.png"
            original = Image.new("RGB", (400, 300), "blue")
            original.save(path)
            before = path.read_bytes()
            attachment = canciones.Attachment.from_path(path)
            attachment.crop((50, 40, 250, 200))
            self.assertEqual(attachment.image.size, (200, 160))
            attachment.restore()
            self.assertEqual(attachment.image.size, (400, 300))
            attachment.close()
            self.assertEqual(path.read_bytes(), before)
            original.close()

    def test_reversed_drag_and_image_bounds(self):
        box = canciones.selection_to_box((220, 180), (60, 50), (20, 30, 200, 150), (800, 600))
        self.assertEqual(box, (160, 80, 800, 600))
        box = canciones.selection_to_box((-100, -100), (1000, 1000), (20, 30, 200, 150), (800, 600))
        self.assertEqual(box, (0, 0, 800, 600))
        with self.assertRaises(ValueError):
            canciones.selection_to_box((20, 30), (21, 31), (20, 30, 200, 150), (800, 600))

    def test_transparency_is_composited_on_white(self):
        image = Image.new("RGBA", (30, 30), (0, 0, 0, 0))
        attachment = canciones.Attachment.from_image(image, "Transparente")
        self.assertEqual(attachment.image.getpixel((0, 0)), (255, 255, 255))
        attachment.close()
        image.close()

    def test_query_cleanup_and_columns(self):
        self.assertEqual(canciones.clean_queries("1. Adele - Hello 4:55\nADELE - Hello\n1979\n"), ["Adele - Hello", "1979"])
        result = SimpleNamespace(txts=("Adele", "Hello", "Shakira - Antologia"), boxes=(
            ((0, 0), (100, 0), (100, 20), (0, 20)),
            ((200, 2), (300, 2), (300, 22), (200, 22)),
            ((0, 45), (300, 45), (300, 65), (0, 65))))
        self.assertEqual(canciones.text_from_ocr(result), ["Adele - Hello", "Shakira - Antologia"])

    def test_search_uses_names_without_downloading(self):
        downloader = Mock()
        downloader.extract_info.return_value = {"entries": [
            {"id": "example", "title": "Adele - Hello", "channel": "Adele", "duration": 295},
            {"id": "live", "title": "Live", "is_live": True}, None]}
        candidates = canciones.search_candidates("Adele Hello", downloader)
        downloader.extract_info.assert_called_once_with("ytsearch3:Adele Hello", download=False)
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0]["url"], "https://www.youtube.com/watch?v=example")

    def test_query_limit_is_500_not_60(self):
        self.assertEqual(canciones.MAX_QUERIES, 500)
        text = "\n".join(f"Artista - Canción {index}" for index in range(500))
        self.assertEqual(len(canciones.clean_queries(text)), 500)
        with self.assertRaisesRegex(ValueError, "500"):
            canciones.clean_queries(text + "\nUna canción adicional")

    def test_real_ocr_in_separate_process(self):
        image = Image.new("RGB", (1000, 240), "white")
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 42)
        draw.text((35, 30), "Adele - Hello", fill="black", font=font)
        draw.text((35, 120), "Shakira - Antologia", fill="black", font=font)
        job = canciones.ImportJob()
        job.run(dict(task="ocr"), [("Lista de prueba", image)])
        events = []
        while not job.events.empty():
            events.append(job.events.get_nowait())
        failures = [event for event in events if event["event"] == "import_error"]
        self.assertFalse(failures, failures)
        result = next(event for event in events if event["event"] == "ocr_result")
        text = "\n".join(result["lines"]).casefold()
        self.assertIn("adele", text)
        self.assertIn("hello", text)
        self.assertIn("shakira", text)
        self.assertEqual(events[-1]["event"], "job_done")

    def test_dark_playlist_ocr_groups_artists_and_ignores_artwork_and_times(self):
        image = Image.new("RGB", (832, 310), "#080811")
        draw = ImageDraw.Draw(image)
        title_font = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 17)
        subtitle_font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 15)
        cover_font = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 9)
        entries = [("spiral", "LONGMAN · spiral", "3:52"),
                   ("Gunjo Sanka", "Eve · Kaizin", "4:37"),
                   ("Over", "Little Glee Monster · Juice", "3:58"),
                   ("Lonely Go!", "Brian the Sun · Another Edition", "3:59")]
        for index, (title, subtitle, duration) in enumerate(entries):
            y = 15 + index * 73
            draw.rectangle((10, y, 53, y + 43), fill="#c77727")
            draw.text((11, y + 14), "NFORN", font=cover_font, fill="black")
            draw.text((68, y), title, font=title_font, fill="white")
            draw.text((68, y + 25), subtitle, font=subtitle_font, fill="#b6b6b6")
            draw.text((780, y + 10), duration, font=subtitle_font, fill="#b6b6b6")
        job = canciones.ImportJob()
        job.run(dict(task="ocr"), [("Captura oscura", image)])
        events = []
        while not job.events.empty():
            events.append(job.events.get_nowait())
        failures = [event for event in events if event["event"] == "import_error"]
        self.assertFalse(failures, failures)
        result = next(event for event in events if event["event"] == "ocr_result")
        self.assertEqual(result["lines"], ["LONGMAN - spiral", "Eve - Gunjo Sanka",
                                          "Little Glee Monster - Over", "Brian the Sun - Lonely Go!"])


class DialogTests(unittest.TestCase):
    def setUp(self):
        self.root = backend.launch_gui()
        self.root.withdraw()
        self.received = []
        self.dialog = ImportSongsDialog(self.root, lambda urls: self.received.extend(urls))
        self.dialog.withdraw()

    def tearDown(self):
        if self.dialog.winfo_exists():
            self.dialog.destroy()
        self.root.destroy()
        self.dialog = self.root = None
        gc.collect()

    def test_attach_clipboard_crop_restore_remove(self):
        image = Image.new("RGB", (600, 400), "white")
        with patch("canciones.ImageGrab.grabclipboard", return_value=image):
            self.dialog.paste_image()
            self.wait_job()
        self.assertEqual(len(self.dialog.attachments), 1)
        self.assertEqual(self.dialog.image_list.size(), 1)
        self.dialog.displayed = (10, 20, 300, 200)
        self.dialog.selection_start = (10, 20)
        self.dialog.selection_end = (160, 120)
        self.dialog.crop_selected()
        self.assertEqual(self.dialog.attachments[0].image.size, (300, 200))
        self.dialog.restore_image()
        self.assertEqual(self.dialog.attachments[0].image.size, (600, 400))
        self.dialog.remove_image()
        self.assertEqual(self.dialog.attachments, [])
        self.assertIsNone(self.dialog.active)

    def test_choose_one_version_and_send_selected_urls(self):
        dialog = self.dialog
        dialog.names.insert("1.0", "Adele - Hello")
        dialog.names.edit_modified(False)
        dialog.searched_queries = ["Adele - Hello"]
        dialog.add_candidates(dict(index=0, query="Adele - Hello", candidates=[
            dict(title="Adele - Hello (Official)", channel="Adele", duration=295, url="https://www.youtube.com/watch?v=official"),
            dict(title="Adele - Hello Live", channel="Adele", duration=300, url="https://www.youtube.com/watch?v=live")]))
        first, second = list(dialog.candidates)
        self.assertEqual(dialog.selected_candidates[0], first)
        self.assertIn("Auto", dialog.results.set(first, "chosen"))
        dialog.results.selection_set(second)
        dialog.choose_result()
        self.assertEqual(len(dialog.selected_candidates), 1)
        self.assertEqual(dialog.results.set(first, "chosen"), "")
        dialog.add_selected()
        self.assertEqual(self.received, ["https://www.youtube.com/watch?v=live"])

    def test_editor_changes_invalidate_results(self):
        dialog = self.dialog
        dialog.searched_queries = ["Adele - Hello"]
        dialog.names.insert("1.0", "Shakira - Antologia")
        dialog.names_changed()
        self.assertEqual(dialog.searched_queries, [])
        self.assertEqual(str(dialog.add_button["state"]), "disabled")

    def test_cleanup_button_can_be_undone(self):
        original = "spiral\n3:52\nLONGMAN·spiral\nGunjo Sanka\n4:37\nEve·Kaizin"
        self.dialog.names.insert("1.0", original)
        self.dialog.clean_playlist_text()
        self.assertEqual(self.dialog.names.get("1.0", "end").strip(), "LONGMAN - spiral\nEve - Gunjo Sanka")
        self.dialog.names.edit_undo()
        self.assertEqual(self.dialog.names.get("1.0", "end").strip(), original)

    def test_corrupt_image_reports_error_without_crash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "falsa.png"
            path.write_bytes(b"not an image")
            self.dialog.add_paths([str(path)])
            self.wait_job()
        self.assertIn("No se pudo abrir", self.dialog.status_var.get())
        self.assertFalse(self.dialog.attachments)

    def test_search_errors_and_cancellation_restore_controls(self):
        dialog = self.dialog
        dialog.job = canciones.ImportJob()
        dialog.task = "search"
        dialog.set_busy(True)
        dialog.handle_event(dict(event="import_error", text="Sin conexión"))
        dialog.handle_event(dict(event="job_done", cancelled=False))
        self.assertFalse(dialog.busy)
        self.assertIn("Sin conexión", dialog.status_var.get())
        dialog.set_busy(True)
        dialog.handle_event(dict(event="job_done", cancelled=True))
        self.assertFalse(dialog.busy)
        self.assertIn("cancelada", dialog.status_var.get())

    def test_selected_song_links_reach_main_downloader(self):
        self.dialog.on_add = self.root.add_song_urls
        self.dialog.names.insert("1.0", "Adele - Hello")
        self.dialog.names.edit_modified(False)
        self.dialog.searched_queries = ["Adele - Hello"]
        self.dialog.add_candidates(dict(index=0, query="Adele - Hello", candidates=[
            dict(title="Adele - Hello (Official)", channel="Adele", duration=295,
                 url="https://www.youtube.com/watch?v=official")]))
        self.assertEqual(len(self.dialog.selected_candidates), 1)
        self.dialog.add_selected()
        self.assertIn("https://www.youtube.com/watch?v=official", self.root.urls.get("1.0", "end"))
        self.assertEqual(self.root.mode_var.get(), "single")
        self.assertFalse(self.root.running)

    def test_ocr_to_editable_names_through_window(self):
        image = Image.new("RGB", (800, 160), "white")
        ImageDraw.Draw(image).text((30, 35), "Adele - Hello", font=ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 44), fill="black")
        with patch("canciones.ImageGrab.grabclipboard", return_value=image):
            self.dialog.paste_image()
            self.wait_job()
        self.dialog.start_ocr()
        self.wait_job(timeout=90)  # El primer arranque carga los modelos OCR en otro proceso.
        self.assertIn("hello", self.dialog.names.get("1.0", "end").casefold())
        self.assertEqual(str(self.dialog.names["state"]), "normal")

    def test_close_waits_for_cancellation(self):
        self.dialog.job = canciones.ImportJob()
        self.dialog.set_busy(True)
        self.dialog.close()
        self.assertTrue(self.dialog.closing)
        self.assertTrue(self.dialog.winfo_exists())
        self.dialog.job.events.put(dict(event="job_done", cancelled=True))
        self.dialog.poll_events()
        self.assertFalse(self.dialog.winfo_exists())

    def test_compact_importer_keeps_bottom_actions_visible(self):
        self.root.deiconify()
        self.dialog.deiconify()
        self.dialog.geometry("900x650")
        self.root.update()
        self.assertTrue(self.dialog.add_button.winfo_ismapped())
        self.assertTrue(self.dialog.search_button.winfo_ismapped())
        bottom = self.dialog.add_button.winfo_rooty() + self.dialog.add_button.winfo_height()
        self.assertLess(bottom, self.dialog.winfo_rooty() + self.dialog.winfo_height())

    def wait_job(self, timeout=30):
        deadline = time.monotonic() + timeout
        while self.dialog.busy:
            self.assertLess(time.monotonic(), deadline)
            self.root.update()
            time.sleep(0.01)


if __name__ == "__main__":
    unittest.main(verbosity=2)
