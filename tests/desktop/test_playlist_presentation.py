"""Orden y playlists fijadas con preferencias temporales y sin cuenta real."""
import os
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QSettings
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module

APP = QApplication.instance() or QApplication([])


class PlaylistPresentationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.settings = QSettings(str(Path(self.directory.name) / "settings.ini"), QSettings.IniFormat)
        self.bridges = []
        self.bridge = self.make_bridge()
        self.bridge.createPlaylist("Driving")
        self.bridge.createPlaylist("Chill")
        self.bridge.createPlaylist("Gym")

    def make_bridge(self):
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            bridge = module.MusicBoxBridge(settings=self.settings, load_startup=False)
        self.bridges.append(bridge)
        return bridge

    def tearDown(self):
        for bridge in self.bridges:
            bridge._listening_timer.stop()
            bridge._device_timer.stop()
        self.settings.sync()
        self.directory.cleanup()

    def test_empty_favorites_hide_and_reappear_without_losing_their_pin(self):
        b = self.bridge
        self.assertIn("Favoritos", [row["name"] for row in b.playlists])
        self.assertNotIn("Favoritos", [row["name"] for row in b.visiblePlaylists])
        b.setPlaylistPinned("Favoritos", True)
        self.assertEqual(b.pinnedPlaylists, [])
        b._favorites.add("youtube:abcdefghijk")
        b._save_playlists()
        self.assertEqual([row["name"] for row in b.pinnedPlaylists], ["Favoritos"])
        b._favorites.clear()
        b._save_playlists()
        self.assertEqual(b.pinnedPlaylists, [])

    def test_order_pins_rename_delete_and_reload_preserve_membership(self):
        b = self.bridge
        b._playlists["Driving"] = ["youtube:abcdefghijk"]
        before = deepcopy(b._playlists)
        b.setPlaylistPinned("Driving", True)
        b.setPlaylistPinned("Chill", True)
        b.movePlaylist("Gym", "Driving")
        self.assertEqual([row["name"] for row in b.visiblePlaylists], ["Gym", "Driving", "Chill"])
        self.assertEqual(b._playlists, before)
        restored = self.make_bridge()
        self.assertEqual([row["name"] for row in restored.visiblePlaylists], ["Gym", "Driving", "Chill"])
        self.assertEqual([row["name"] for row in restored.pinnedPlaylists], ["Driving", "Chill"])
        self.assertTrue(restored.updatePlaylistDetails("Driving", "Viajes", "Descripción"))
        self.assertEqual([row["name"] for row in restored.pinnedPlaylists], ["Viajes", "Chill"])
        restored.deletePlaylist("Chill")
        self.assertEqual([row["name"] for row in restored.pinnedPlaylists], ["Viajes"])
        self.assertEqual(restored._playlists["Viajes"], ["youtube:abcdefghijk"])

    def test_android_metadata_is_applied_and_exported_in_the_same_format(self):
        b = self.bridge
        baseline = deepcopy(b._cloud_state()["playlists"])
        incoming = deepcopy(baseline)
        incoming["playlistOrder"] = ["Favoritos", "Gym", "Chill", "Driving"]
        incoming["pinnedPlaylists"] = ["Gym", "Driving"]
        b._apply_remote_playlists({"syncedPlaylists": incoming, "localSnapshot": baseline})
        self.assertEqual([row["name"] for row in b.visiblePlaylists], ["Gym", "Chill", "Driving"])
        self.assertEqual([row["name"] for row in b.pinnedPlaylists], ["Gym", "Driving"])
        exported = b._cloud_state()["playlists"]
        self.assertEqual(exported["playlistOrder"], incoming["playlistOrder"])
        self.assertEqual(set(exported["pinnedPlaylists"]), {"Gym", "Driving"})
        self.assertEqual(exported["playlists"], baseline["playlists"])

    def test_hold_and_drag_reorders_cards_and_home_uses_one_compact_row(self):
        from PySide6.QtCore import QObject, QPointF, Qt, QUrl, QCoreApplication, QEvent
        from PySide6.QtQml import QQmlApplicationEngine
        from PySide6.QtQuickControls2 import QQuickStyle
        from PySide6.QtTest import QTest
        from downloads_controller import DownloadsController
        from importer_controller import ImporterImageProvider

        def wait_for(predicate, timeout_ms=3000):
            for _ in range(timeout_ms // 20):
                if predicate():
                    return True
                QTest.qWait(20)
            return predicate()

        QQuickStyle.setStyle("Material")
        if os.environ.get("MUSICBOX_UI_PREVIEW_DIR"):
            from PySide6.QtGui import QFont, QFontDatabase
            for name in ("segoeui.ttf", "segoeuib.ttf"):
                font = Path("C:/Windows/Fonts") / name
                if font.is_file(): QFontDatabase.addApplicationFont(str(font))
            APP.setFont(QFont("Segoe UI", 10))
        b = self.bridge
        b._user = {"name": "Demostración", "email": "demo@example.invalid"}
        b._run_in_thread = lambda work: None
        b.setPlaylistPinned("Driving", True)
        b.setPlaylistPinned("Chill", True)
        b._for_you_loading = True
        engine = QQmlApplicationEngine()
        errors = []
        engine.warnings.connect(lambda warnings: errors.extend(w.toString() for w in warnings))
        b.attach_engine(engine)
        engine.rootContext().setContextProperty("musicbox", b)
        downloads = DownloadsController(b)
        b.downloads = downloads
        engine.rootContext().setContextProperty("downloads", downloads)
        engine.addImageProvider("importer", ImporterImageProvider(downloads.importer))
        engine.load(QUrl.fromLocalFile(str(module.CODE_DIR / "ui" / "Main.qml")))
        self.assertTrue(engine.rootObjects(), errors)
        root = engine.rootObjects()[0]
        try:
            root.setProperty("splashVisible", False)
            root.setProperty("page", 1)
            root.setProperty("libraryFilter", "playlists")
            QTest.qWait(650)
            grid = root.findChild(QObject, "playlistsGrid")
            self.assertEqual(grid.property("count"), 3)
            areas, items = {}, [grid.property("contentItem")]
            while items:
                item = items.pop()
                if item.objectName() == "playlistDragArea": areas[item.property("playlistName")] = item
                items.extend(item.childItems())
            first, second = areas["Driving"], areas["Chill"]
            start = first.mapToScene(QPointF(first.width() / 2, first.height() / 2)).toPoint()
            end = second.mapToScene(QPointF(second.width() / 2, second.height() / 2)).toPoint()
            QTest.mouseMove(root, start)
            self.assertTrue(wait_for(lambda: first.property("containsMouse")), "La tarjeta debe recibir el puntero")
            QTest.mousePress(root, Qt.LeftButton, Qt.NoModifier, start)
            destination = start
            try:
                self.assertTrue(first.property("pressed"))
                self.assertTrue(wait_for(lambda: first.property("held"),
                                         APP.styleHints().mousePressAndHoldInterval() + 2000))
                QTest.mouseMove(root, end, 100)
                destination = end
            finally:
                QTest.mouseRelease(root, Qt.LeftButton, Qt.NoModifier, destination)
            self.assertTrue(wait_for(lambda: [row["name"] for row in b.visiblePlaylists] == ["Chill", "Driving", "Gym"]))
            self.assertEqual(root.property("page"), 1, "Arrastrar no abre la playlist")
            root.setProperty("page", 0)
            QTest.qWait(150)
            pinned = root.findChild(QObject, "pinnedPlaylistsRow")
            self.assertEqual(pinned.property("count"), 2)
            self.assertEqual(pinned.property("height"), 56)
            self.assertTrue(pinned.property("visible"))
            cards = [item for item in pinned.property("contentItem").childItems()
                     if item.objectName() == "pinnedPlaylistButton"]
            self.assertEqual(len(cards), 2)
            positions = sorted(card.mapToItem(pinned, QPointF(0, 0)).x() for card in cards)
            self.assertAlmostEqual(positions[0], pinned.width() - positions[-1] - cards[-1].width(), delta=1)
            preview_dir = os.environ.get("MUSICBOX_UI_PREVIEW_DIR")
            for dark in (False, True):
                if b.darkMode != dark: b.toggleTheme()
                QTest.qWait(150)
                if preview_dir:
                    Path(preview_dir).mkdir(parents=True, exist_ok=True)
                    self.assertTrue(root.grabWindow().save(str(Path(preview_dir) / f"pinned-home-{'dark' if dark else 'light'}.png")))
            b.setPlaylistPinned("Chill", False); b.setPlaylistPinned("Driving", False)
            QTest.qWait(50)
            self.assertFalse(pinned.property("visible"))
        finally:
            root.setProperty("visible", False)
            engine.deleteLater()
            QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
        self.assertEqual(errors, [])
