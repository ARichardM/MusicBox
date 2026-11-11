"""Selector MP3 / MP4 del escritorio: un flujo con imagen solo cuando el usuario lo pide, con vuelta segura al audio."""

import os
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, QSettings, QUrl
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

import musicbox_desktop as module
import youtube_streams
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP = QApplication.instance() or QApplication([])

ONLINE = {"id": "abcdefghijk", "url": "https://www.youtube.com/watch?v=abcdefghijk", "title": "Tema", "artist": "Banda"}


def make_bridge(directory):
    settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
    with patch.object(module, "QSettings", return_value=settings), \
            patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
            patch.object(module.MusicBoxBridge, "refresh"), patch.object(module.MusicBoxBridge, "checkForUpdate"), \
            patch.object(module.MusicBoxBridge, "refreshForYou"):
        return module.MusicBoxBridge()


def wait_for(predicate, seconds=5):
    deadline = time.time() + seconds
    while not predicate() and time.time() < deadline:
        APP.processEvents()
        time.sleep(0.01)
    return predicate()


class StreamFormatTests(unittest.TestCase):
    def test_video_asks_for_a_single_mp4_with_sound_and_audio_stays_audio_only(self):
        seen = []

        def extractor(options, url):
            seen.append(options["format"])
            return {"url": "https://stream.example/x", "http_headers": {}}

        with patch.object(youtube_streams, "stream_is_reachable", return_value=True):
            for video in (False, True):
                youtube_streams.resolve_stream("https://www.youtube.com/watch?v=abcdefghijk", extractor, video=video)
        self.assertEqual(seen, [youtube_streams.AUDIO_FORMAT, youtube_streams.VIDEO_FORMAT])
        self.assertTrue(youtube_streams.AUDIO_FORMAT.startswith("bestaudio"))
        self.assertTrue(youtube_streams.VIDEO_FORMAT.startswith("best[ext=mp4]"))
        self.assertNotIn("bestaudio", youtube_streams.VIDEO_FORMAT)


class BridgeVideoTests(unittest.TestCase):
    def test_video_mode_requires_a_verified_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = make_bridge(directory)
            self.assertFalse(bridge.videoAvailable)
            bridge._current = dict(ONLINE)
            self.assertFalse(bridge.videoAvailable)
            bridge._apply_video_check(ONLINE["url"], {"url": "https://stream.example/video.mp4"})
            self.assertTrue(bridge.videoAvailable)
            bridge._current = {"path": "C:/musica/a.mp3", "title": "Local"}
            self.assertFalse(bridge.videoAvailable)
            bridge.setVideoMode(True)
            self.assertFalse(bridge.videoMode)  # sin video disponible no se activa

    def test_switching_mode_restarts_the_stream_at_the_same_second_and_play_state(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = make_bridge(directory)
            calls = []
            bridge._current = dict(ONLINE)
            bridge._apply_video_check(ONLINE["url"], {"url": "https://stream.example/video.mp4"})
            bridge._pending_seek = 42000
            bridge._play_when_ready = False
            bridge._play_online = lambda item, **kwargs: calls.append((dict(item), kwargs))
            bridge.setVideoMode(True)
            self.assertTrue(bridge.videoMode)
            self.assertEqual(len(calls), 1)
            self.assertTrue(calls[0][1]["recovering"])      # conserva cola y posición
            self.assertFalse(calls[0][1]["autoplay"])       # no agrega canciones nuevas
            self.assertEqual(bridge._pending_seek, 42000)
            self.assertFalse(bridge._play_when_ready)       # seguía en pausa
            bridge.setVideoMode(True)
            self.assertEqual(len(calls), 1)                 # repetir el mismo modo no reinicia nada
            bridge.setVideoMode(False)
            self.assertFalse(bridge.videoMode)
            self.assertEqual(len(calls), 2)

    def test_stream_cache_is_kept_apart_per_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = make_bridge(directory)
            url = ONLINE["url"]
            bridge._stream_cache[url] = (time.monotonic(), "audio", {})
            bridge._stream_cache[bridge._stream_key(url, True)] = (time.monotonic(), "video", {})
            self.assertNotEqual(bridge._stream_key(url, True), bridge._stream_key(url, False))
            bridge._drop_stream_cache(url)
            self.assertEqual(bridge._stream_cache, {})

    def test_a_video_without_mp4_falls_back_to_audio_and_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = make_bridge(directory)
            bridge._video_mode = True
            asked = []

            def resolve(url, extractor, opener=None, video=False):
                asked.append(video)
                if video:
                    raise RuntimeError("Requested format is not available")
                return {"url": "https://stream.example/audio.m4a", "title": "Tema"}

            results = []
            bridge._streamReady.connect(lambda item, source: results.append(source))
            changes = []
            bridge.videoModeChanged.connect(lambda: changes.append(1))
            with patch.object(module.ytmusic, "playable_video_source", return_value=ONLINE["id"]), \
                    patch.object(module.youtube_streams, "resolve_stream", resolve), patch.object(bridge, "_maybe_autoplay"):
                bridge._play_online(dict(ONLINE))
                self.assertTrue(wait_for(lambda: results))
            self.assertEqual(asked, [True, False])
            self.assertEqual(results, ["https://stream.example/audio.m4a"])
            self.assertFalse(bridge.videoMode)
            self.assertIn(ONLINE["id"], bridge._no_video)
            self.assertFalse(bridge.videoAvailable)
            self.assertTrue(changes)


class UiTests(unittest.TestCase):
    def test_toggle_and_panel_follow_the_current_song(self):
        QQuickStyle.setStyle("Material")
        with tempfile.TemporaryDirectory() as directory:
            bridge = make_bridge(directory)
            bridge._user = {"name": "Demostración", "email": "demo@example.invalid"}
            bridge._current = dict(ONLINE)
            bridge._apply_video_check(ONLINE["url"], {"url": "https://stream.example/video.mp4"})
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
            bridge.playerChanged.emit()
            toggle, panel = root.findChild(QObject, "videoToggle"), root.findChild(QObject, "videoPanel")
            self.assertIsNotNone(toggle)
            self.assertIsNotNone(panel)
            # Se espera por la condición, no por un tiempo fijo: las animaciones tardan más con muchas ventanas en el proceso.
            self.assertTrue(wait_for(lambda: toggle.property("visible")))
            self.assertFalse(panel.property("visible"))
            with patch.object(bridge, "_play_online"):
                bridge.setVideoMode(True)
            self.assertTrue(wait_for(lambda: panel.property("visible"), 10))
            with patch.object(bridge, "_play_online"):
                bridge.setVideoMode(False)
            self.assertTrue(wait_for(lambda: not panel.property("visible"), 10))
            bridge._current = {"path": "C:/musica/a.mp3", "title": "Local"}
            bridge.playerChanged.emit()
            self.assertTrue(wait_for(lambda: not toggle.property("visible")))
            self.assertFalse([e for e in errors if "ideo" in e], errors)


if __name__ == "__main__":
    unittest.main()
