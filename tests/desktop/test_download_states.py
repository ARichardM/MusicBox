"""El panel de progreso sigue los mismos estados que la ventana clásica: insignia, porcentaje, contadores y registro."""

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication

import i18n
import musicbox_desktop as app_module

APP = QApplication.instance() or QApplication([])


def make_bridge(from_page=True):
    """Puente mínimo: solo el estado de descargas, sin biblioteca, nube ni reproductor."""
    bridge = app_module.MusicBoxBridge.__new__(app_module.MusicBoxBridge)
    QObject.__init__(bridge)
    bridge._download_running = True
    bridge._download_from_page = from_page
    bridge._download_cancelled = False
    bridge._download_failed = False
    bridge._download_progress = 0.0
    bridge._download_indeterminate = True
    bridge._download_status = ""
    bridge._download_detail = ""
    bridge._download_files = []
    bridge._download_log_lines = []
    bridge._download_badge = "preparing"
    bridge._download_percent_text = "…"
    bridge._download_completed = 0
    bridge._download_error_count = 0
    bridge._download_had_summary = False
    bridge._download_queue = []
    bridge._download_process = None
    bridge._downloading_ids = set()
    bridge.refresh = lambda: None
    bridge.notices = []
    bridge._show_notice = lambda text, retry=None, kind="error": bridge.notices.append(text)
    return bridge


class DownloadStateTests(unittest.TestCase):
    def setUp(self):
        i18n.set_language("es")

    def test_progress_updates_percent_detail_and_badge(self):
        bridge = make_bridge()
        bridge._handle_download_event({"event": "link", "index": 1, "count": 2})
        self.assertEqual((bridge.downloadBadge, bridge.downloadStatus), ("preparing", "Analizando enlace 1 de 2…"))
        bridge._handle_download_event({"event": "progress", "status": "downloading", "title": "Crimen",
                                       "downloaded": 1_000_000, "total": 4_000_000, "speed": 500_000, "eta": 75,
                                       "index": 1, "count": 2})
        self.assertEqual(bridge.downloadBadge, "downloading")
        self.assertEqual(bridge.downloadPercentText, "25%")
        self.assertAlmostEqual(bridge.downloadProgress, 0.25)
        self.assertFalse(bridge.downloadIndeterminate)
        self.assertIn("1:15 restantes", bridge.downloadDetail)
        self.assertIn("Archivo 1 de 2", bridge.downloadDetail)

    def test_unknown_size_keeps_the_ring_indeterminate(self):
        bridge = make_bridge()
        bridge._handle_download_event({"event": "progress", "status": "downloading", "title": "x", "downloaded": 10, "total": None})
        self.assertTrue(bridge.downloadIndeterminate)
        self.assertEqual(bridge.downloadPercentText, "…")
        self.assertIn("/ ?", bridge.downloadDetail)

    def test_finished_download_moves_to_processing(self):
        bridge = make_bridge()
        bridge._handle_download_event({"event": "progress", "status": "finished", "title": "x", "downloaded": 1, "total": 1})
        self.assertEqual(bridge.downloadBadge, "processing")
        self.assertEqual(bridge.downloadStatus, "Preparando el archivo final…")

    def test_ready_file_is_listed_with_its_format_and_logged_in_green(self):
        bridge = make_bridge()
        bridge._handle_download_event({"event": "ready", "filename": "C:/m/Cancion.mp3", "completed": 1})
        self.assertEqual(bridge.downloadFiles[0]["format"], "MP3")
        self.assertEqual(bridge.downloadCompleted, 1)
        self.assertEqual(bridge.downloadLogLines[-1]["level"], "ready")

    def test_successful_run_ends_completed(self):
        bridge = make_bridge()
        bridge._handle_download_event({"event": "summary", "completed": 3, "errors": 0})
        bridge._handle_download_event({"event": "done", "cancelled": False})
        self.assertFalse(bridge.downloadRunning)
        self.assertEqual(bridge.downloadBadge, "completed")
        self.assertEqual(bridge.downloadPercentText, "100%")
        self.assertEqual(bridge.downloadStatus, "¡Descargas terminadas!")
        self.assertEqual(bridge.notices, [])

    def test_errors_end_with_the_error_badge_and_only_toast_for_quick_downloads(self):
        bridge = make_bridge(from_page=True)
        bridge._handle_download_event({"event": "log", "level": "error", "text": "fallo"})
        bridge._handle_download_event({"event": "summary", "completed": 0, "errors": 1})
        bridge._handle_download_event({"event": "done", "cancelled": False})
        self.assertEqual(bridge.downloadBadge, "errors")
        self.assertTrue(bridge.downloadFailed)
        self.assertEqual(bridge.downloadPercentText, "—")
        self.assertEqual(bridge.notices, [])           # la página ya muestra el estado en pantalla
        quick = make_bridge(from_page=False)
        quick._handle_download_event({"event": "log", "level": "error", "text": "fallo"})
        quick._handle_download_event({"event": "done", "cancelled": False})
        self.assertEqual(len(quick.notices), 1)

    def test_missing_summary_counts_as_a_failed_run(self):
        bridge = make_bridge()
        bridge._handle_download_event({"event": "done", "cancelled": False})
        self.assertEqual(bridge.downloadBadge, "errors")

    def test_cancelling_clears_the_queue_and_resets_progress(self):
        bridge = make_bridge()
        bridge._download_queue = [("https://youtu.be/a", "MP3", "192 kbps", "C:/m", "")]
        bridge._download_cancelled = True
        bridge._handle_download_event({"event": "progress", "status": "downloading", "title": "x", "downloaded": 5, "total": 10})
        self.assertEqual(bridge.downloadBadge, "preparing")     # los eventos tardíos no pisan el estado de cancelación
        bridge._handle_download_event({"event": "done", "cancelled": True})
        self.assertEqual(bridge.downloadBadge, "cancelled")
        self.assertEqual(bridge._download_queue, [])
        self.assertEqual(bridge.downloadProgress, 0)

    def test_log_is_trimmed_and_keeps_levels(self):
        bridge = make_bridge()
        for number in range(1300):
            bridge._append_download_log(f"línea {number}", "warning" if number % 2 else "info")
        self.assertLessEqual(len(bridge.downloadLogLines), 1200)
        self.assertEqual(bridge.downloadLogLines[-1]["text"], "línea 1299")
        self.assertEqual(bridge.downloadLogLines[-1]["level"], "warning")

    def test_validation_errors_are_returned_not_shown(self):
        bridge = make_bridge()
        bridge._download_running = False
        message = bridge.startDownloadFromPage("https://youtu.be/a", False, "", "MP3", "192 kbps", "", "")
        self.assertEqual(message, "Elige una carpeta de destino.")
        message = bridge.startDownloadFromPage("", False, "", "MP3", "192 kbps", "C:/m", "")
        self.assertNotEqual(message, "")
        self.assertFalse(bridge.downloadRunning)


if __name__ == "__main__":
    unittest.main()
