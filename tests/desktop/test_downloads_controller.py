"""Página de Descargas: enlaces, cola, detección de playlists, historial paginado e informe."""

import os
import tempfile
import time
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

import i18n
from downloads_controller import DownloadsController, detect_playlist_mode, unique_lines
from historial import DownloadHistory

APP = QApplication.instance() or QApplication([])


class FakeBridge:
    def __init__(self):
        self.downloadRunning = False
        self.messages = []
        self.logs = []
        self.started = []
        self.start_error = ""

    def setDownloadMessage(self, status, detail): self.messages.append((status, detail))
    def appendDownloadLog(self, text, level): self.logs.append((text, level))
    def pausePlayer(self): pass

    def startDownloadFromPage(self, *args):
        self.started.append(args)
        return self.start_error


def spin(condition, seconds=5):
    deadline = time.time() + seconds
    while time.time() < deadline and not condition():
        APP.processEvents()
        time.sleep(0.01)
    return condition()


class LinkHelpersTests(unittest.TestCase):
    def test_playlist_detection_requires_every_link_to_be_a_playlist(self):
        playlist = "https://www.youtube.com/playlist?list=PLabc"
        self.assertTrue(detect_playlist_mode([playlist]))
        self.assertFalse(detect_playlist_mode([playlist, "https://youtu.be/xyz"]))
        self.assertFalse(detect_playlist_mode(["https://www.youtube.com/watch?v=1&list=PLabc"]))
        self.assertFalse(detect_playlist_mode([]))

    def test_unique_lines_keeps_order_and_ignores_blanks(self):
        self.assertEqual(unique_lines(" a \n\nb\na\n"), ["a", "b"])


class DownloadsControllerTests(unittest.TestCase):
    def setUp(self):
        i18n.set_language("es")
        self.bridge = FakeBridge()
        self.controller = DownloadsController(self.bridge)

    def test_pasting_a_playlist_switches_mode_until_the_user_chooses(self):
        self.controller.setLinks("https://www.youtube.com/playlist?list=PLabc")
        self.assertEqual(self.controller.mode, "playlist")
        self.controller.setMode("single")
        self.controller.setLinks("https://www.youtube.com/playlist?list=PLother")
        self.assertEqual(self.controller.mode, "single")      # la elección manual no se pisa
        self.controller.clearLinks()
        self.controller.setLinks("https://www.youtube.com/playlist?list=PLabc")
        self.assertEqual(self.controller.mode, "playlist")    # limpiar devuelve la detección automática

    def test_link_summary_counts_unique_lines(self):
        self.assertEqual(self.controller.linkSummary, "Sin enlaces todavía")
        self.controller.setLinks("a\na\nb")
        self.assertEqual(self.controller.linkSummary, "2 enlaces en cola")

    def test_adding_urls_skips_repeats_and_reports_what_happened(self):
        self.controller.setLinks("https://youtu.be/a")
        self.assertTrue(self.controller.addUrls(["https://youtu.be/a", "https://youtu.be/b", "https://youtu.be/b"]))
        self.assertEqual(unique_lines(self.controller.links), ["https://youtu.be/a", "https://youtu.be/b"])
        status, detail = self.bridge.messages[-1]
        self.assertIn("1 enlaces nuevos", status)
        self.assertIn("1 ya estaban", status)
        self.assertEqual(self.bridge.logs[-1][1], "warning")

    def test_adding_urls_is_refused_while_a_download_runs(self):
        self.bridge.downloadRunning = True
        self.assertFalse(self.controller.addUrls(["https://youtu.be/a"]))
        self.assertEqual(self.controller.links, "")

    def test_empty_selection_sets_an_inline_error(self):
        self.assertFalse(self.controller.addUrls(["  "]))
        self.assertIn("No hay enlaces válidos", self.controller.inputError)

    def test_start_reports_validation_errors_inline_and_switches_tab_on_success(self):
        tabs = []
        self.controller.showTab.connect(tabs.append)
        self.bridge.start_error = "Elige una carpeta de destino."
        self.assertFalse(self.controller.start("x", False, "", "MP3", "192 kbps", "", ""))
        self.assertEqual(self.controller.inputError, "Elige una carpeta de destino.")
        self.bridge.start_error = ""
        self.assertTrue(self.controller.start("x", False, "", "MP3", "192 kbps", "C:/m", "cookies.txt"))
        self.assertEqual(self.controller.inputError, "")
        self.assertEqual(tabs, ["files"])
        self.assertEqual(self.controller.cookiePath, "cookies.txt")

    def test_song_transfer_accumulates_in_the_report(self):
        self.controller.addUrls(["https://youtu.be/a"])
        report = dict(selected=2, unique=1, repeated=1, urls=["https://youtu.be/a"], duplicates=[], source_report="INFORME")
        self.controller.recordSongTransfer(report)
        self.controller.recordSongTransfer(report)
        self.assertEqual(self.controller.importReport.count("INFORME"), 2)
        self.assertIn("═" * 40, self.controller.importReport)
        self.assertIn("2 elecciones → 1 videos únicos", self.controller.importReport)
        self.controller.clearReport()
        self.assertEqual(self.controller.importReport, "")

    def test_history_pages_search_and_open_rules(self):
        with tempfile.TemporaryDirectory() as folder:
            store = DownloadHistory(Path(folder) / "h.sqlite3")
            real = Path(folder) / "cancion.mp3"
            real.write_bytes(b"x")
            store.record(dict(filename=str(real), title="Crimen", artist="Cerati", format="MP3", source_url="https://youtu.be/a"))
            store.record(dict(filename=str(Path(folder) / "no_existe.mp3"), title="Otra", state="existing"))
            self.controller._history_store = store
            opened = []
            self.controller.openPath = opened.append      # nunca abrir archivos de verdad en una prueba
            self.controller.historyRefresh()
            self.assertTrue(spin(lambda: not self.controller.historyLoading and self.controller.historyRows))
            self.assertEqual(len(self.controller.historyRows), 2)
            self.assertEqual(self.controller.historyRows[0]["title"], "Otra")
            self.assertEqual(self.controller.historyRows[0]["stateText"], "Existente")
            self.assertIn("1–2 de 2 registros", self.controller.historyStatus)

            self.controller.historySearchFor("cerati")
            self.assertTrue(spin(lambda: [r["title"] for r in self.controller.historyRows] == ["Crimen · Cerati"]))

            # un archivo que ya no existe no se abre y se informa
            self.controller.historySearchFor("otra")
            self.assertTrue(spin(lambda: [r["title"] for r in self.controller.historyRows] == ["Otra"]))
            self.controller.historyOpen(self.controller.historyRows[0]["id"], False)
            self.assertIn("ya no existe", self.controller.historyStatus)
            self.assertEqual(opened, [])

            # a la cola solo si hay enlace y no hay descarga en curso
            self.controller.historySearchFor("cerati")
            self.assertTrue(spin(lambda: [r["title"] for r in self.controller.historyRows] == ["Crimen · Cerati"]))
            self.controller.historyOpen(self.controller.historyRows[0]["id"], True)
            self.assertEqual([Path(item).resolve() for item in opened], [Path(folder).resolve()])
            self.controller.historyQueue(self.controller.historyRows[0]["id"])
            self.assertIn("https://youtu.be/a", self.controller.links)


if __name__ == "__main__":
    unittest.main()
