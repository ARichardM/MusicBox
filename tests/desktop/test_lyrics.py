"""Letras de canciones: análisis LRC, limpieza de metadatos, prioridad de fuentes y panel QML reactivo."""

import io
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QMetaObject, QObject, QSettings, Qt, QUrl
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

import i18n
import lyrics
from lyrics_controller import LyricsController
import musicbox_desktop as module
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider

APP = QApplication.instance() or QApplication([])


def wait_for(predicate, seconds=5):
    deadline = time.time() + seconds
    while not predicate() and time.time() < deadline:
        APP.processEvents()
        time.sleep(0.01)
    return predicate()


def fake_opener(payload):
    def opener(request, timeout=8):
        if isinstance(payload, Exception):
            raise payload
        data = payload(request.full_url) if callable(payload) else payload
        raw = json.dumps(data).encode("utf-8")
        response = io.BytesIO(raw)
        response.__enter__ = lambda: response
        response.__exit__ = lambda *args: None
        return response
    return opener


class LrcAndCleaningTests(unittest.TestCase):
    def test_parses_lrc_timestamps_multi_tags_and_offsets(self):
        raw = (
            "[ar:Los Prisioneros]\n"
            "[ti:Tren Al Sur]\n"
            "[offset:200]\n"
            "[00:10.00] <00:10.10> Siete y media en la mañana\n"
            "[00:15.50][00:45.250] Mi asiento toca la ventana\n"
            "[00:22.5]   \n"
            "[00:30:00] Estación Central, segundo carro\n"
        )
        parsed = lyrics.parse_lrc(raw)
        self.assertEqual(parsed, [
            {"timeMs": 9800, "text": "Siete y media en la mañana"},
            {"timeMs": 15300, "text": "Mi asiento toca la ventana"},
            {"timeMs": 29800, "text": "Estación Central, segundo carro"},
            {"timeMs": 45050, "text": "Mi asiento toca la ventana"},
        ])

    def test_active_line_index_tracks_playback_position(self):
        lines = [
            {"timeMs": 5000, "text": "Primera línea"},
            {"timeMs": 12000, "text": "Segunda línea"},
            {"timeMs": 20000, "text": "Tercera línea"},
        ]
        self.assertEqual(lyrics.active_line_index(lines, 0), -1)
        self.assertEqual(lyrics.active_line_index(lines, 4999), -1)
        self.assertEqual(lyrics.active_line_index(lines, 5000), 0)
        self.assertEqual(lyrics.active_line_index(lines, 11999), 0)
        self.assertEqual(lyrics.active_line_index(lines, 12000), 1)
        self.assertEqual(lyrics.active_line_index(lines, 35000), 2)
        self.assertEqual(lyrics.active_line_index([{"timeMs": -1, "text": "Texto plano"}], 10000), -1)

    def test_cleans_noisy_titles_and_artists(self):
        title, artist, duration = lyrics.clean_query({
            "title": "Los Prisioneros - Tren Al Sur (Video Oficial) [HD] [NgEJ9gMxP90]",
            "artist": "Los Prisioneros - Topic · Corazones · MP3",
            "duration": 337,
        })
        self.assertEqual((title, artist, duration), ("Tren Al Sur", "Los Prisioneros", 337))

        title2, artist2, _ = lyrics.clean_query({
            "title": "Rosa Walton - I Really Want to Stay At Your House (Official Lyric Video)",
            "artist": "Artista desconocido",
        })
        self.assertEqual((title2, artist2), ("I Really Want to Stay At Your House", "Rosa Walton"))


class ResolutionTests(unittest.TestCase):
    def setUp(self):
        lyrics.clear_cache()

    def test_reads_local_sidecar_lrc_file_first(self):
        with tempfile.TemporaryDirectory() as directory:
            audio_path = Path(directory) / "cancion.mp3"
            lrc_path = Path(directory) / "cancion.lrc"
            audio_path.write_bytes(b"ID3")
            lrc_path.write_text("[00:04.00] Verso local uno\n[00:08.50] Verso local dos\n", encoding="utf-8")
            result = lyrics.find({"path": str(audio_path), "title": "Canción", "artist": "Artista"}, use_cache=False)
            self.assertTrue(result["synced"])
            self.assertEqual(result["source"], "LRC local")
            self.assertEqual(len(result["lines"]), 2)
            self.assertEqual(result["lines"][0], {"timeMs": 4000, "text": "Verso local uno"})

    def test_lrclib_prefers_synced_lyrics_matching_duration(self):
        items = [
            {
                "id": 1,
                "trackName": "Tren Al Sur",
                "artistName": "Los Prisioneros",
                "duration": 337,
                "plainLyrics": "Solo texto plano",
                "syncedLyrics": None,
            },
            {
                "id": 2,
                "trackName": "Tren Al Sur",
                "artistName": "Los Prisioneros",
                "duration": 336,
                "plainLyrics": "Siete y media\nMi asiento",
                "syncedLyrics": "[00:10.00] Siete y media\n[00:15.00] Mi asiento",
            },
        ]
        result = lyrics.fetch_lrclib("Tren Al Sur", "Los Prisioneros", 337, opener=fake_opener(items))
        self.assertIsNotNone(result)
        self.assertTrue(result["synced"])
        self.assertEqual(result["source"], "LRCLIB")
        self.assertEqual(result["lines"][0]["text"], "Siete y media")

    def test_falls_back_to_ytmusic_when_lrclib_has_no_match(self):
        def yt_api(endpoint, body):
            if endpoint == "next":
                return {"tabs": [{"tabRenderer": {"endpoint": {"browseEndpoint": {"browseId": "MPLYt_test"}}}}]}
            return {"musicDescriptionShelfRenderer": {
                "description": {"runs": [{"text": "Primera línea oficial\nSegunda línea oficial"}]},
                "footer": {"runs": [{"text": "Fuente: LyricFind"}]}}}

        result = lyrics.find(
            {"id": "NgEJ9gMxP90", "title": "Tren Al Sur", "artist": "Los Prisioneros"},
            lrclib_opener=fake_opener([]),
            ytmusic_api=yt_api,
            use_cache=False,
        )
        self.assertFalse(result["synced"])
        self.assertEqual(result["source"], "Fuente: LyricFind")
        self.assertEqual([line["text"] for line in result["lines"]], ["Primera línea oficial", "Segunda línea oficial"])

    def test_raises_only_when_all_online_sources_fail(self):
        def broken_yt(endpoint, body):
            raise OSError("sin red")

        with self.assertRaises(RuntimeError):
            lyrics.find(
                {"id": "NgEJ9gMxP90", "title": "Tren Al Sur", "artist": "Los Prisioneros"},
                lrclib_opener=fake_opener(OSError("sin red")),
                ytmusic_api=broken_yt,
                use_cache=False,
            )


class QmlLyricsUiTests(unittest.TestCase):
    def setUp(self):
        i18n.set_language("es")
        lyrics.clear_cache()

    def test_lyrics_panel_syncs_with_playback_and_seeks_on_tap(self):
        QQuickStyle.setStyle("Material")
        with tempfile.TemporaryDirectory() as directory:
            settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
            with patch.object(module, "QSettings", return_value=settings), \
                    patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                    patch.object(module.MusicBoxBridge, "refresh"), \
                    patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                    patch.object(module.MusicBoxBridge, "refreshForYou"):
                bridge = module.MusicBoxBridge()

            bridge._user = {"name": "Demostración", "email": "demo@example.invalid"}
            bridge._current = {
                "id": "NgEJ9gMxP90",
                "url": "https://www.youtube.com/watch?v=NgEJ9gMxP90",
                "title": "Tren Al Sur",
                "artist": "Los Prisioneros",
                "duration": 337,
            }
            synced_lines = [
                {"timeMs": 5000, "text": "Siete y media en la mañana"},
                {"timeMs": 12000, "text": "Mi asiento toca la ventana"},
                {"timeMs": 20000, "text": "Estación Central, segundo carro"},
            ]
            bridge.lyrics._finder = lambda track, **kwargs: {
                "lines": synced_lines,
                "synced": True,
                "source": "LRCLIB",
                "instrumental": False,
            }
            seeks = []
            bridge.seek = lambda ms: seeks.append(ms)

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

            lyrics_toggle = root.findChild(QObject, "lyricsToggle")
            lyrics_drawer = root.findChild(QObject, "lyricsDrawer")
            lyrics_panel = root.findChild(QObject, "lyricsPanel")
            lyrics_list = root.findChild(QObject, "lyricsListView")
            sync_badge_text = root.findChild(QObject, "lyricsSyncBadgeText")
            self.assertIsNotNone(lyrics_toggle)
            self.assertIsNotNone(lyrics_drawer)
            self.assertIsNotNone(lyrics_panel)
            self.assertIsNotNone(lyrics_list)

            # Al pulsar el botón de letra en el reproductor se abre el panel y se carga la letra sincronizada.
            QMetaObject.invokeMethod(lyrics_toggle, "clicked", Qt.DirectConnection)
            self.assertTrue(wait_for(lambda: bridge.lyrics.state == "ready" and lyrics_drawer.property("visible")))
            self.assertTrue(bridge.lyrics.open)
            self.assertTrue(bridge.lyrics.synced)
            self.assertEqual(sync_badge_text.property("text"), "Sincronizada")
            self.assertEqual(lyrics_list.property("count"), 3)

            # La posición del reproductor actualiza la línea activa en tiempo real y el pie expandido.
            root.setProperty("expanded", True)
            bridge.lyrics._update_position(13500)
            APP.processEvents()
            self.assertEqual(bridge.lyrics.activeIndex, 1)
            self.assertEqual(bridge.lyrics.activeText, "Mi asiento toca la ventana")
            caption = root.findChild(QObject, "playerExpandedCaption")
            self.assertIsNotNone(caption)
            self.assertIn("Mi asiento toca la ventana", caption.property("text"))

            # Tocar o activar una línea salta al milisegundo exacto de ese verso.
            bridge.lyrics.seekToLine(2)
            APP.processEvents()
            self.assertEqual(seeks[-1], 20000)
            self.assertEqual(bridge.lyrics.activeIndex, 2)
            self.assertEqual(bridge.lyrics.activeText, "Estación Central, segundo carro")

            # Verifica que se adapte sin errores tanto en tema oscuro como claro y distintos anchos.
            for dark in (False, True):
                if bridge.darkMode != dark:
                    bridge.toggleTheme()
                for width in (900, 1180):
                    root.setProperty("width", width)
                    QTest.qWait(80)
                    self.assertTrue(lyrics_drawer.property("visible"))

            # Al abrir la cola se cierra el cajón de letras automáticamente.
            queue_drawer = root.findChild(QObject, "queueDrawer")
            QMetaObject.invokeMethod(queue_drawer, "open", Qt.DirectConnection)
            self.assertTrue(wait_for(lambda: not bridge.lyrics.open))

            root.setProperty("visible", False)
            bridge._listening_timer.stop()
            bridge._device_timer.stop()
            self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
