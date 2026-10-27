"""Carga real de QML y paneles de cola/estadísticas con datos y preferencias aislados."""
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, QSettings, QUrl, QMetaObject, Qt
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP = QApplication.instance() or QApplication([])


class QmlUiTests(unittest.TestCase):
    def test_main_window_opens_queue_and_statistics_without_qml_errors(self):
        QQuickStyle.setStyle("Material")
        with tempfile.TemporaryDirectory() as directory:
            settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
            with patch.object(module, "QSettings", return_value=settings), \
                    patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                    patch.object(module.MusicBoxBridge, "refresh"), \
                    patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                    patch.object(module.MusicBoxBridge, "refreshForYou"):
                bridge = module.MusicBoxBridge()
            bridge._online_queue = [{"id": "sample", "url": "https://example.invalid/sample", "title": "Canción de prueba"}]
            bridge._online_index = 0
            bridge._current = dict(bridge._online_queue[0])
            bridge._clicks_stats.record("yt:sample", "Canción de prueba")
            bridge._clicks_stats.record_listening("yt:sample", "Canción de prueba", millis=12500, start=True, playlist="Favoritos")
            engine = QQmlApplicationEngine()
            errors = []
            engine.warnings.connect(lambda warnings: errors.extend(warning.toString() for warning in warnings))
            bridge.attach_engine(engine)
            engine.rootContext().setContextProperty("musicbox", bridge)
            downloads = DownloadsController(bridge)
            bridge.downloads = downloads
            engine.rootContext().setContextProperty("downloads", downloads)
            engine.addImageProvider("importer", ImporterImageProvider(downloads.importer))
            engine.load(QUrl.fromLocalFile(str(module.CODE_DIR / "ui" / "Main.qml")))
            self.assertTrue(engine.rootObjects(), "\n".join(errors))
            root = engine.rootObjects()[0]
            statistics = root.findChild(QObject, "statisticsDialog")
            self.assertIsNotNone(statistics)
            QMetaObject.invokeMethod(statistics, "open", Qt.DirectConnection)
            APP.processEvents()
            self.assertTrue(statistics.property("visible"))
            self.assertEqual(bridge.statistics["ms"], 12500)
            self.assertEqual(bridge.statistics["plays"], 1)
            QMetaObject.invokeMethod(statistics, "close", Qt.DirectConnection)
            queue = root.findChild(QObject, "queueDrawer")
            self.assertIsNotNone(queue)
            QMetaObject.invokeMethod(queue, "open", Qt.DirectConnection)
            APP.processEvents()
            self.assertTrue(queue.property("visible"))
            self.assertEqual(bridge.queue[0]["title"], "Canción de prueba")
            root.setProperty("visible", False)
            bridge._listening_timer.stop()
            bridge._device_timer.stop()
            # Destruir QML antes que sus contextos evita avisos de propiedades nulas al salir.
            engine.deleteLater()
            from PySide6.QtCore import QCoreApplication, QEvent
            QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
            self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
