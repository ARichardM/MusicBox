"""Carga real de QML y paneles de cola/estadísticas con datos y preferencias aislados."""
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, QSettings, QUrl, QMetaObject, Qt, Q_ARG
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtTest import QTest
from PySide6.QtGui import QFont, QFontDatabase, QImage, QColor
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP = QApplication.instance() or QApplication([])


class QmlUiTests(unittest.TestCase):
    def test_main_window_opens_queue_and_statistics_without_qml_errors(self):
        QQuickStyle.setStyle("Material")
        if os.environ.get("MUSICBOX_UI_PREVIEW_DIR"):
            for font_name in ("segoeui.ttf", "segoeuib.ttf"):
                font_path = Path("C:/Windows/Fonts") / font_name
                if font_path.is_file():
                    QFontDatabase.addApplicationFont(str(font_path))
            APP.setFont(QFont("Segoe UI", 10))
        with tempfile.TemporaryDirectory() as directory:
            settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
            with patch.object(module, "QSettings", return_value=settings), \
                    patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                    patch.object(module.MusicBoxBridge, "refresh"), \
                    patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                    patch.object(module.MusicBoxBridge, "refreshForYou"):
                bridge = module.MusicBoxBridge()
            bridge._user = {"name": "Demostración", "email": "demo@example.invalid"}
            bridge._resolve_artist = lambda name: {"name": name, "photo": ""}
            photo = QImage(32, 32, QImage.Format_ARGB32)
            photo.fill(QColor("#9D69C5"))
            photo_path = str(Path(directory) / "demo-avatar.png")
            self.assertTrue(photo.save(photo_path))
            photo_url = QUrl.fromLocalFile(photo_path).toString()
            bridge._artist_directory["ana"] = {"name": "Ana", "photo": photo_url}
            thumbnail = QUrl.fromLocalFile(str(module.CODE_DIR / "ui" / "icons" / "music.svg")).toString()
            bridge._online_queue = [{"id": "sample", "url": "https://example.invalid/sample", "title": "Canción de prueba", "thumbnail": thumbnail}]
            bridge._online_index = 0
            bridge._current = dict(bridge._online_queue[0])
            bridge._clicks_stats.record("yt:sample", "Canción de prueba")
            bridge._clicks_stats.record_listening("yt:sample", "Canción de prueba", thumbnail=thumbnail,
                                                  millis=12500, start=True, playlist="Favoritos")
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
            bridge._library_all = [
                {"path": "/test/a.mp3", "title": "Primera", "artist": "Ana"},
                {"path": "/test/b.mp3", "title": "Segunda", "artist": "Otro"}]
            bridge._playlists["Demostración"] = ["/test/a.mp3", "/test/b.mp3"]
            bridge.setLibraryPlaylist("Demostración")
            root.setProperty("libraryPlaylist", "Demostración")
            root.setProperty("libraryFilter", "playlists")
            root.setProperty("page", 1)
            APP.processEvents()
            artist_filter = root.findChild(QObject, "playlistArtistFilter")
            self.assertIsNotNone(artist_filter)
            self.assertEqual(artist_filter.property("count"), 3)
            artist_filter.setProperty("currentIndex", 1)
            QMetaObject.invokeMethod(artist_filter, "activated", Qt.DirectConnection, Q_ARG(int, 1))
            APP.processEvents()
            self.assertEqual(bridge.libraryArtist, "Ana")
            avatar = artist_filter.findChild(QObject, "selectedArtistAvatar")
            self.assertIsNotNone(avatar)
            QTest.qWait(150)
            self.assertEqual(avatar.property("photo").toString(), photo_url)
            self.assertTrue(avatar.property("ready"))
            self.assertEqual([row["title"] for row in bridge.library], ["Primera"])
            tools = root.findChild(QObject, "playlistTools")
            clear_artist = root.findChild(QObject, "clearPlaylistArtistFilter")
            auto_download = root.findChild(QObject, "playlistAutoDownload")
            self.assertTrue(clear_artist.property("visible"))
            QMetaObject.invokeMethod(clear_artist, "clicked", Qt.DirectConnection)
            APP.processEvents()
            self.assertEqual(bridge.libraryArtist, "")
            self.assertEqual(avatar.property("artistName"), "")
            self.assertFalse(clear_artist.property("visible"))
            # El estado visual sigue al puente y tolera nombres largos y ventanas estrechas.
            long_artist = "Un artista con un nombre muy largo que debe recortarse"
            bridge._artist_directory[module.ytmusic.normalize(long_artist)] = {"name": long_artist, "photo": photo_url}
            bridge._library_all[0]["artist"] = long_artist
            bridge.setLibraryArtist(long_artist)
            root.setProperty("splashVisible", False)
            for dark in (False, True):
                if bridge.darkMode != dark:
                    bridge.toggleTheme()
                for width in (900, 1180):
                    root.setProperty("width", width)
                    root.setProperty("height", 620)
                    QTest.qWait(200)
                    self.assertEqual(artist_filter.property("selectedArtist"), long_artist)
                    self.assertTrue(artist_filter.property("filtering"))
                    self.assertLessEqual(artist_filter.property("width"), 260)
                    self.assertLessEqual(tools.property("height"), 36)
                    self.assertLessEqual(artist_filter.property("x") + artist_filter.property("width"),
                                         clear_artist.property("x"))
                    self.assertLessEqual(clear_artist.property("x") + clear_artist.property("width"),
                                         auto_download.property("x"))
                    preview_dir = os.environ.get("MUSICBOX_UI_PREVIEW_DIR")
                    if preview_dir:
                        Path(preview_dir).mkdir(parents=True, exist_ok=True)
                        self.assertTrue(root.grabWindow().save(str(Path(preview_dir) / f"artist-{'dark' if dark else 'light'}-{width}.png")))
            # El desplegable sigue acotado aunque existan muchos artistas.
            bridge._library_all.extend({"path": f"/test/{i}.mp3", "title": f"Tema {i}", "artist": f"Artista {i}"}
                                       for i in range(40))
            bridge._playlists["Demostración"] = [row["path"] for row in bridge._library_all]
            bridge._apply_library_view()
            popup = root.findChild(QObject, "artistFilterPopup")
            self.assertIsNotNone(popup)
            QMetaObject.invokeMethod(artist_filter, "forceActiveFocus", Qt.DirectConnection)
            QTest.keyClick(root, Qt.Key_Space)
            QTest.qWait(150)
            self.assertTrue(popup.property("visible"))
            self.assertLessEqual(popup.property("height"), 280)
            QTest.keyClick(APP.focusWindow() or root, Qt.Key_Escape)
            QTest.qWait(400)
            self.assertFalse(popup.property("visible"),
                             f"focus={popup.property('activeFocus')} opened={popup.property('opened')}")
            bridge.setLibraryArtist("")
            bridge.setLibraryPlaylist("")
            root.setProperty("libraryPlaylist", "")
            root.setProperty("page", 0)
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
