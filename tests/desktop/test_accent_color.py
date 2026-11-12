"""Accent persistence, cloud conflict handling and real rendering of all four themes."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, QSettings, QUrl, QMetaObject, Qt
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
from PySide6.QtGui import QFont, QFontDatabase
import musicbox_desktop as module
import downloads_controller as downloads_module
from historial import DownloadHistory
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP = QApplication.instance() or QApplication([])
if os.name == "nt":
    for font in ("segoeui.ttf", "segoeuib.ttf"):
        QFontDatabase.addApplicationFont(str(Path("C:/Windows/Fonts") / font))
    font = QFont("Segoe UI", 10)
    font.setStyleStrategy(QFont.PreferAntialias | QFont.NoSubpixelAntialias)
    APP.setFont(font)


def visual_child(item, name):
    if item.objectName() == name:
        return item
    for child in item.childItems():
        found = visual_child(child, name)
        if found is not None:
            return found
    return None


class AccentColorTests(unittest.TestCase):
    def make_bridge(self, settings):
        with patch.object(module, "QSettings", return_value=settings), \
                patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                patch.object(module.MusicBoxBridge, "refresh"), \
                patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                patch.object(module.MusicBoxBridge, "refreshForYou"):
            return module.MusicBoxBridge()

    def test_saved_accent_and_remote_changes_respect_pending_local_choice(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
            bridge = self.make_bridge(settings)
            self.assertEqual(bridge.accentColor, "blue")
            bridge._apply_preferences({"preferences": {"accentColor": "orange"}})
            self.assertEqual(bridge.accentColor, "orange")
            bridge.setAccentColor("invalid")
            bridge._apply_preferences({"preferences": {"accentColor": None}})
            self.assertEqual(bridge.accentColor, "orange")
            bridge.toggleTheme()
            self.assertEqual(bridge.accentColor, "orange")
            self.assertEqual(self.make_bridge(settings).accentColor, "orange")
            bridge._user = {"uid": "accent-test"}
            bridge._schedule_cloud_save = lambda: None
            bridge.setAccentColor("blue")
            bridge._apply_preferences({"preferences": {"accentColor": "orange"}})
            self.assertEqual(bridge.accentColor, "blue")
            self.assertEqual(bridge._all_preferences()["accentColor"], "blue")
            settings.setValue("accentColor", "unknown")
            self.assertEqual(self.make_bridge(settings).accentColor, "blue")

    def test_four_palettes_render_and_settings_change_every_page(self):
        QQuickStyle.setStyle("Material")
        with tempfile.TemporaryDirectory() as directory:
            settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
            bridge = self.make_bridge(settings)
            bridge._user = {"name": "Demostración", "email": "demo@example.invalid"}
            bridge._schedule_cloud_save = lambda: None
            bridge._library_all = [
                {"path": str(Path(directory) / f"song-{index}.mp3"), "title": title,
                 "artist": "Artista de muestra", "album": "Demostración", "duration": 180,
                 "thumbnail": "", "favorite": False}
                for index, title in enumerate(("Primera canción", "Segunda canción"))]
            bridge._apply_library_view()
            bridge._queue = list(bridge._library_all)
            bridge._queue_index = 0
            bridge._current = dict(bridge._queue[0])
            bridge._shuffle = True
            bridge._repeat_mode = 1
            engine = QQmlApplicationEngine()
            errors = []
            engine.warnings.connect(lambda warnings: errors.extend(w.toString() for w in warnings))
            bridge.attach_engine(engine)
            engine.rootContext().setContextProperty("musicbox", bridge)
            with patch.object(downloads_module, "DownloadHistory", return_value=DownloadHistory(Path(directory) / "history.sqlite3")):
                downloads = DownloadsController(bridge)
            bridge.downloads = downloads
            engine.rootContext().setContextProperty("downloads", downloads)
            engine.addImageProvider("importer", ImporterImageProvider(downloads.importer))
            engine.load(QUrl.fromLocalFile(str(module.CODE_DIR / "ui" / "Main.qml")))
            self.assertTrue(engine.rootObjects(), "\n".join(errors))
            window = engine.rootObjects()[0]
            window.setProperty("splashVisible", False)
            dialog = window.findChild(QObject, "settingsDialog")
            expected = {("blue", True): "#0a84ff", ("blue", False): "#006fe6",
                        ("orange", True): "#ff9f43", ("orange", False): "#b94700"}
            preview = os.environ.get("MUSICBOX_ACCENT_PREVIEW_DIR")
            try:
                for dark in (False, True):
                    if bridge.darkMode != dark:
                        bridge.toggleTheme()
                    for accent in ("blue", "orange"):
                        QMetaObject.invokeMethod(dialog, "open", Qt.DirectConnection)
                        QTest.qWait(320)
                        choice = visual_child(dialog.property("contentItem"), "accentChoice_" + accent)
                        self.assertIsNotNone(choice)
                        QMetaObject.invokeMethod(choice, "clicked", Qt.DirectConnection)
                        QTest.qWait(220)
                        window.grabWindow()
                        QTest.qWait(220)
                        self.assertTrue(choice.property("selected"))
                        self.assertEqual(bridge.accentColor, accent)
                        self.assertEqual(window.property("accent").name(), expected[(accent, dark)])
                        def colors(item):
                            if item.property("text") == "Buscar con portadas" and item.property("color") is not None:
                                expected_text = "#211000" if accent == "orange" and dark else "#ffffff"
                                self.assertEqual(item.property("color").name(), expected_text)
                            for child in item.childItems(): colors(child)
                        colors(window.contentItem())
                        image = window.grabWindow()
                        self.assertFalse(image.isNull())
                        if preview:
                            Path(preview).mkdir(parents=True, exist_ok=True)
                            self.assertTrue(image.save(str(Path(preview) / f"pc-{accent}-{'dark' if dark else 'light'}-settings.png")))
                        QMetaObject.invokeMethod(dialog, "close", Qt.DirectConnection)
                        for page in range(4):
                            window.setProperty("page", page)
                            QTest.qWait(170)
                            self.assertEqual(window.property("accent").name(), expected[(accent, dark)])
                            if preview:
                                window.grabWindow().save(str(Path(preview) / f"pc-{accent}-{'dark' if dark else 'light'}-page{page}.png"))
                self.assertFalse(errors, "\n".join(errors))
            finally:
                window.close()
                engine.deleteLater()
                APP.processEvents()


if __name__ == "__main__":
    unittest.main()
