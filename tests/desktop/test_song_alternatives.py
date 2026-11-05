"""Ver alternativas: otras versiones de la misma canción, escuchar sin tocar la cola y reemplazar donde estaba."""

import os
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, QSettings, QUrl, QMetaObject, Q_ARG, Qt
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

import i18n
import musicbox_desktop as module
import song_alternatives as alt
from alternatives_controller import AlternativesController
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP = QApplication.instance() or QApplication([])


def video(video_id, title, artist="Banda", duration=200):
    return {"id": video_id, "title": title, "artist": artist, "duration": duration,
            "url": f"https://www.youtube.com/watch?v={video_id}", "thumbnail": ""}


def wait_for(predicate, seconds=5):
    deadline = time.time() + seconds
    while not predicate() and time.time() < deadline:
        APP.processEvents()
        time.sleep(0.01)
    return predicate()


class SearchTests(unittest.TestCase):
    def test_query_uses_artist_and_title_once(self):
        self.assertEqual(alt.query_for({"title": "Tema", "artist": "Banda"}), "Banda - Tema")
        self.assertEqual(alt.query_for({"title": "Banda - Tema", "artist": "Banda"}), "Banda - Tema")
        self.assertEqual(alt.query_for({"title": "Tema", "artist": "<unknown>"}), "Tema")
        self.assertEqual(alt.query_for({"title": "Tema", "subtitle": "YouTube"}), "Tema")

    def test_only_versions_of_the_same_song_survive_best_match_first(self):
        ranked = alt.rank_similar([
            video("a", "El Baile De Los Que Sobran"), video("b", "Estrechez De Corazón (En vivo)"),
            video("c", "Amiga Mía"), video("d", "Estrechez De Corazón")], "Estrechez De Corazón")
        self.assertEqual([item["id"] for item in ranked], ["b", "d"])

    def test_nothing_similar_returns_everything_instead_of_an_empty_list(self):
        items = [video("a", "Otra cosa"), video("b", "Algo distinto")]
        self.assertEqual(alt.rank_similar(items, "Estrechez De Corazón"), items)

    def test_find_merges_songs_and_videos_without_repeats_or_the_current_version(self):
        songs = lambda q: [video("own", "Tema"), video("a", "Tema"), video("zero", "Tema", duration=0)]
        videos = lambda q: [video("a", "Tema"), video("b", "Tema (Live)")]
        found = alt.find({"title": "Tema", "artist": "Banda"}, songs, videos, own_id="own")
        self.assertEqual([item["id"] for item in found], ["a", "b"])

    def test_one_failing_search_is_tolerated_but_both_failing_is_an_error(self):
        def broken(query):
            raise OSError("sin red")

        found = alt.find({"title": "Tema"}, lambda q: [video("a", "Tema")], broken)
        self.assertEqual([item["id"] for item in found], ["a"])
        with self.assertRaises(RuntimeError):
            alt.find({"title": "Tema"}, broken, broken)


class BridgeTests(unittest.TestCase):
    def make_bridge(self, directory):
        settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
        with patch.object(module, "QSettings", return_value=settings), \
                patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                patch.object(module.MusicBoxBridge, "refresh"), patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                patch.object(module.MusicBoxBridge, "refreshForYou"):
            return module.MusicBoxBridge()

    def test_replaces_in_place_across_playlists_and_favorites(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make_bridge(directory)
            bridge._playlists["A"] = ["youtube:primera11111", "youtube:original1111", "youtube:ultima111111"]
            bridge._playlists["B"] = ["youtube:original1111", "youtube:nueva2222222"]
            bridge._playlists["Favoritos"] = ["youtube:original1111"]
            old = {"id": "original1111", "title": "Tema", "url": "https://www.youtube.com/watch?v=original1111"}
            new = video("nueva2222222", "Tema (En vivo)")
            self.assertTrue(bridge.isTrackReplaceable(old))
            self.assertGreaterEqual(bridge.replaceTrack(old, new), 3)
            self.assertEqual(bridge._playlists["A"], ["youtube:primera11111", "youtube:nueva2222222", "youtube:ultima111111"])
            self.assertEqual(bridge._playlists["B"], ["youtube:nueva2222222"])  # ya la tenía: solo se quitó la anterior
            self.assertEqual(bridge._playlists["Favoritos"], ["youtube:nueva2222222"])
            self.assertEqual(bridge._favorites, {"youtube:nueva2222222"})
            self.assertFalse(bridge.isTrackReplaceable(old))

    def test_replaces_the_queue_entry_and_restarts_what_was_playing(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make_bridge(directory)
            started = []
            bridge._play_online = lambda item, **kwargs: started.append(item["id"])
            bridge._online_queue = [video("uno", "Uno"), video("original1111", "Tema"), video("tres", "Tres")]
            bridge._online_index = 1
            bridge._current = dict(bridge._online_queue[1])
            old = dict(bridge._online_queue[1])
            self.assertTrue(bridge.isTrackReplaceable(old))
            self.assertEqual(bridge.replaceTrack(old, video("nueva2222222", "Tema (En vivo)")), 1)
            self.assertEqual([item["id"] for item in bridge._online_queue], ["uno", "nueva2222222", "tres"])
            self.assertEqual(started, ["nueva2222222"])

    def test_nothing_to_replace_changes_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make_bridge(directory)
            old = {"id": "suelta111111", "title": "Suelta"}
            self.assertFalse(bridge.isTrackReplaceable(old))
            self.assertEqual(bridge.replaceTrack(old, video("nueva2222222", "Otra")), 0)
            self.assertEqual(bridge.replaceTrack({}, video("nueva2222222", "Otra")), 0)


class FakeBridge(QObject):
    def __init__(self, replaceable=True):
        super().__init__()
        self.replaced, self.replaceable = [], replaceable

    def trackVideoId(self, track):
        return track.get("id", "")

    def isTrackReplaceable(self, track):
        return self.replaceable

    def replaceTrack(self, old, new):
        self.replaced.append((old["id"], new["id"]))
        return 2


class ControllerTests(unittest.TestCase):
    def setUp(self):
        i18n.set_language("es")

    def test_loads_results_and_replaces(self):
        bridge = FakeBridge()
        controller = AlternativesController(bridge, finder=lambda track, own_id="": [video("a", "Tema"), video("b", "Tema 2")])
        controller.load({"id": "own", "title": "Tema"})
        self.assertEqual(controller.state, "loading")
        self.assertTrue(wait_for(lambda: controller.state == "ready"))
        self.assertEqual([item["id"] for item in controller.results], ["a", "b"])
        self.assertTrue(controller.replaceable)
        self.assertEqual(controller.replaceWith(controller.results[0]), 2)
        self.assertEqual(bridge.replaced, [("own", "a")])
        self.assertTrue(controller.isReplaced("a"))
        self.assertFalse(controller.isReplaced("b"))
        self.assertEqual(controller.detail(video("a", "Tema")), "Banda · 3:20")

    def test_empty_and_error_states(self):
        bridge = FakeBridge()
        empty = AlternativesController(bridge, finder=lambda track, own_id="": [])
        empty.load({"id": "own", "title": "Tema"})
        self.assertTrue(wait_for(lambda: empty.state == "empty"))

        def broken(track, own_id=""):
            raise RuntimeError("sin red")

        failing = AlternativesController(bridge, finder=broken)
        failing.load({"id": "own", "title": "Tema"})
        self.assertTrue(wait_for(lambda: failing.state == "error"))
        self.assertIn("sin red", failing.error)

    def test_a_late_answer_for_an_old_song_is_ignored(self):
        bridge = FakeBridge()
        controller = AlternativesController(bridge, finder=lambda track, own_id="": [video(track["id"] + "x", "T")])
        controller.load({"id": "viejo", "title": "T"})
        controller.reset()
        QTest.qWait(150)
        self.assertEqual((controller.state, controller.results), ("idle", []))


class UiTests(unittest.TestCase):
    def test_dialog_lists_versions_and_confirms_the_replacement(self):
        QQuickStyle.setStyle("Material")
        i18n.set_language("es")
        with tempfile.TemporaryDirectory() as directory:
            settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
            with patch.object(module, "QSettings", return_value=settings), \
                    patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                    patch.object(module.MusicBoxBridge, "refresh"), patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                    patch.object(module.MusicBoxBridge, "refreshForYou"):
                bridge = module.MusicBoxBridge()
            bridge._user = {"name": "Demostración", "email": "demo@example.invalid"}
            bridge._playlists["Mi lista"] = ["youtube:originalxxxx"]
            bridge._alternatives._finder = lambda track, own_id="": [
                video("nuevaxxxxxx1", "Tema (En vivo)"), video("nuevaxxxxxx2", "Tema (Remaster)")]
            engine = QQmlApplicationEngine()
            errors = []
            engine.warnings.connect(lambda warnings: errors.extend(w.toString() for w in warnings))
            bridge.attach_engine(engine)
            engine.rootContext().setContextProperty("musicbox", bridge)
            downloads = DownloadsController(bridge)
            bridge.downloads = downloads
            engine.rootContext().setContextProperty("downloads", downloads)
            engine.addImageProvider("importer", ImporterImageProvider(downloads.importer))
            engine.load(QUrl.fromLocalFile(str(module.CODE_DIR / "ui" / "Main.qml")))
            self.assertTrue(engine.rootObjects(), "\n".join(errors))
            root = engine.rootObjects()[0]
            root.setProperty("splashVisible", False)
            dialog = root.findChild(QObject, "alternativesDialog")
            self.assertIsNotNone(dialog)
            track = {"id": "originalxxxx", "title": "Tema", "artist": "Banda", "thumbnail": ""}
            QMetaObject.invokeMethod(dialog, "openFor", Qt.DirectConnection, Q_ARG("QVariant", track))
            self.assertTrue(wait_for(lambda: bridge._alternatives.state == "ready"))
            QTest.qWait(500)
            listing = root.findChild(QObject, "alternativesList")
            self.assertIsNotNone(listing)
            self.assertEqual(listing.property("count"), 2)
            self.assertTrue(bridge._alternatives.replaceable)
            bridge._alternatives.replaceWith(bridge._alternatives.results[0])
            QTest.qWait(300)
            self.assertEqual(bridge._playlists["Mi lista"], ["youtube:nuevaxxxxxx1"])
            self.assertEqual(list(bridge._alternatives.replacedIds), ["nuevaxxxxxx1"])
            self.assertFalse([e for e in errors if "lternatives" in e], errors)


if __name__ == "__main__":
    unittest.main()
