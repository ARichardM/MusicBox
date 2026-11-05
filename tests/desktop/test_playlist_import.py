"""Importar una playlist desde un enlace de Spotify, YouTube, Deezer, Apple Music o SoundCloud."""

import json
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

import i18n
import musicbox_desktop as module
import playlist_import as pi
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider
from playlist_import_controller import PlaylistImportController

APP = QApplication.instance() or QApplication([])


def spotify_page(name="Mi lista"):
    data = {"props": {"pageProps": {"state": {"data": {"entity": {"name": name, "trackList": [
        {"title": "Loser", "subtitle": "Tame Impala", "duration": 223069, "entityType": "track"},
        {"title": "", "subtitle": "Sin título", "duration": 1000},
        {"title": "Episodio", "subtitle": "Podcast", "duration": 60000, "entityType": "episode"}]}}}}}}
    return '<html><script id="__NEXT_DATA__" type="application/json">' + json.dumps(data) + "</script></html>"


class FindTests(unittest.TestCase):
    def test_recognises_every_platform_inside_shared_text(self):
        cases = {
            "Mira https://open.spotify.com/intl-es/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc ya": ("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            "https://music.youtube.com/playlist?list=PLabcdefghij123&si=x": ("youtube", "PLabcdefghij123"),
            "https://www.youtube.com/playlist?feature=share&list=PLXYZ_987654": ("youtube", "PLXYZ_987654"),
            "https://www.deezer.com/en/playlist/1116190041": ("deezer", "1116190041"),
        }
        for text, (platform, identifier) in cases.items():
            link = pi.find(text)
            self.assertEqual((link.platform, link.id), (platform, identifier), text)
        apple = pi.find("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb?l=es")
        self.assertEqual(apple.platform, "apple")
        cloud = pi.find("Mira https://soundcloud.com/forss/sets/soulhack?si=x")
        self.assertEqual((cloud.platform, cloud.url), ("soundcloud", "https://soundcloud.com/forss/sets/soulhack"))

    def test_ignores_single_tracks_videos_and_plain_text(self):
        for text in ("https://open.spotify.com/track/7bxaFZ1O3cHkgLKMsdC3xR",
                     "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghij123",
                     "https://soundcloud.com/forss/soulhack",
                     "https://music.apple.com/us/album/solar-eclipse/6818209777",
                     "solo texto", "", None):
            self.assertIsNone(pi.find(text), text)


class ParserTests(unittest.TestCase):
    def test_spotify_embed_keeps_only_real_tracks(self):
        playlist = pi.parse_spotify(spotify_page())
        self.assertEqual(playlist.name, "Mi lista")
        self.assertEqual([(t.title, t.artist, t.seconds) for t in playlist.tracks], [("Loser", "Tame Impala", 223)])

    def test_apple_music_reads_tracks_and_trims_the_page_title(self):
        data = {"data": [{"items": [
            {"title": "Solar Eclipse", "trackNumber": None, "artistName": "Drake & Don Toliver", "duration": 218389},
            {"title": "Otra", "trackNumber": 2, "artistName": "Alguien", "duration": 1000}]}]}
        page = ('<meta property="og:title" content="‎Today&#39;s Hits on Apple Music">'
                '<script type="application/json" id="serialized-server-data">' + json.dumps(data) + "</script>")
        playlist = pi.parse_apple_music(page)
        self.assertEqual(playlist.name, "Today's Hits")
        self.assertEqual([(t.title, t.artist, t.seconds) for t in playlist.tracks],
                         [("Solar Eclipse", "Drake & Don Toliver", 218), ("Otra", "Alguien", 1)])

    def test_soundcloud_reads_set_name_tracks_and_missing_artists(self):
        data = {"props": {"pageProps": {"playlist": {"urn": "soundcloud:playlists:18", "title": "Soulhack"}, "tracks": [
            {"urn": "soundcloud:tracks:290", "title": "City Ports", "duration": 142233, "publisher_metadata": {"artist": "Forss"}},
            {"urn": "soundcloud:tracks:291", "title": "Sin metadatos", "duration": 274459}]}}}
        page = '<script id="__NEXT_DATA__" type="application/json">' + json.dumps(data) + "</script>"
        playlist = pi.parse_soundcloud(page)
        self.assertEqual(playlist.name, "Soulhack")
        self.assertEqual([(t.title, t.artist) for t in playlist.tracks], [("City Ports", "Forss"), ("Sin metadatos", "")])

    def test_unreadable_pages_raise_clear_errors(self):
        for parser in (pi.parse_spotify, pi.parse_apple_music, pi.parse_soundcloud):
            with self.assertRaises(ValueError):
                parser("<html>nada</html>")

    def test_empty_playlist_name_falls_back_to_the_platform(self):
        self.assertEqual(pi.Playlist("deezer", "  ", []).name, "Deezer · playlist")


class FetchTests(unittest.TestCase):
    def test_deezer_follows_every_page(self):
        pages = {
            "https://api.deezer.com/playlist/9": json.dumps({"title": "Mix", "tracks": {
                "data": [{"title": "Uno", "duration": 100, "artist": {"name": "A"}}], "next": "https://api.deezer.com/page2"}}),
            "https://api.deezer.com/page2": json.dumps({"data": [{"title": "Dos", "duration": 200}]})}
        playlist = pi.fetch(pi.Link("deezer", "9", "u"), get=pages.__getitem__)
        self.assertEqual(playlist.name, "Mix")
        self.assertEqual([(t.title, t.artist) for t in playlist.tracks], [("Uno", "A"), ("Dos", "")])

    def test_deezer_error_is_reported(self):
        with self.assertRaises(ValueError):
            pi.fetch(pi.Link("deezer", "9", "u"), get=lambda url: json.dumps({"error": {"code": 800}}))

    def test_youtube_playlists_arrive_with_their_videos_and_real_title(self):
        calls = []

        class Fake:
            @staticmethod
            def playlist_tracks(browse_id, limit=300):
                calls.append(browse_id)
                return [{"id": "v1", "title": "Tema", "artist": "Banda", "duration": 200}]

        playlist = pi.fetch(pi.Link("youtube", "PLabcdefghij123", "u"), ytm=Fake,
                            get=lambda url: json.dumps({"title": "gym"}))
        self.assertEqual(calls, ["VLPLabcdefghij123"])
        self.assertEqual(playlist.name, "gym")
        self.assertEqual(playlist.tracks[0].direct["id"], "v1")

    def test_youtube_title_failure_does_not_block_the_import(self):
        class Fake:
            @staticmethod
            def playlist_tracks(browse_id, limit=300):
                return [{"id": "v1", "title": "Tema"}]

        def broken(url):
            raise OSError("sin red")

        playlist = pi.fetch(pi.Link("youtube", "PLabcdefghij123", "u"), ytm=Fake, get=broken)
        self.assertEqual(playlist.name, "YouTube · playlist")


class MatchTests(unittest.TestCase):
    def song(self, title, artist="Banda", duration=200, video_id="x"):
        return {"id": video_id, "title": title, "artist": artist, "album": "", "duration": duration}

    def test_an_exact_confident_match_is_chosen(self):
        track = pi.Track("Mi canción", "Banda", 200)
        chosen = pi.match(track, search_songs=lambda q: [self.song("Otra cosa", video_id="a"), self.song("Mi canción", video_id="b")],
                          search_videos=lambda q: [])
        self.assertEqual(chosen["id"], "b")

    def test_a_different_song_is_never_added_automatically(self):
        track = pi.Track("Mi canción", "Banda", 200)
        self.assertIsNone(pi.match(track, search_songs=lambda q: [self.song("Algo totalmente distinto", "Otro")],
                                   search_videos=lambda q: []))

    def test_falls_back_to_videos_and_handles_no_results(self):
        track = pi.Track("Mi canción", "Banda", 200)
        self.assertEqual(pi.match(track, search_songs=lambda q: [], search_videos=lambda q: [self.song("Mi canción", video_id="v")])["id"], "v")
        self.assertIsNone(pi.match(track, search_songs=lambda q: [], search_videos=lambda q: []))

    def test_query_skips_the_artist_when_the_title_already_has_it(self):
        self.assertEqual(pi.search_query(pi.Track("Banda - Tema", "Banda")), "Banda - Tema")
        self.assertEqual(pi.search_query(pi.Track("Tema", "Banda")), "Banda - Tema")


class ResolveTests(unittest.TestCase):
    def playlist(self):
        return pi.Playlist("spotify", "Lista", [pi.Track("a"), pi.Track("b"), pi.Track("c", direct={"id": "direct"})])

    def test_counts_found_skipped_and_reports_progress(self):
        seen = []
        found, skipped, stopped = pi.resolve(self.playlist(), progress=lambda d, t: seen.append((d, t)),
                                             matcher=lambda t: {"id": t.title} if t.title == "a" else None)
        self.assertEqual([item["id"] for item in found], ["a", "direct"])
        self.assertEqual((skipped, stopped), (1, False))
        self.assertEqual(seen, [(1, 3), (2, 3), (3, 3)])

    def test_a_failing_search_only_skips_that_song(self):
        def matcher(track):
            if track.title == "a":
                raise OSError("red")
            return {"id": track.title}

        found, skipped, _ = pi.resolve(self.playlist(), matcher=matcher)
        self.assertEqual([item["id"] for item in found], ["b", "direct"])
        self.assertEqual(skipped, 1)

    def test_cancelling_keeps_what_was_already_found(self):
        stop = {"now": False}

        def matcher(track):
            stop["now"] = True
            return {"id": track.title}

        found, _, stopped = pi.resolve(self.playlist(), cancelled=lambda: stop["now"], matcher=matcher)
        self.assertEqual([item["id"] for item in found], ["a"])
        self.assertTrue(stopped)


class FakeBridge(QObject):
    def __init__(self):
        super().__init__()
        self.created, self.added = [], []

    def createPlaylist(self, name):
        self.created.append(name)

    def addTracksToPlaylist(self, name, tracks):
        self.added.append((name, [t["id"] for t in tracks]))
        return len(tracks)


def wait_for(predicate, seconds=5):
    deadline = time.time() + seconds
    while not predicate() and time.time() < deadline:
        APP.processEvents()
        time.sleep(0.01)
    return predicate()


class ControllerTests(unittest.TestCase):
    def setUp(self):
        i18n.set_language("es")

    def make(self, tracks=None, error=None):
        bridge = FakeBridge()
        playlist = pi.Playlist("spotify", "Lista", tracks or [pi.Track("a"), pi.Track("b"), pi.Track("c")])

        def fetcher(link):
            if error:
                raise ValueError(error)
            return playlist

        return bridge, PlaylistImportController(bridge, fetcher=fetcher, matcher=lambda t: {"id": t.title} if t.title != "b" else None)

    def test_full_flow_reads_asks_for_a_name_and_imports(self):
        bridge, controller = self.make()
        self.assertFalse(controller.start("sin enlace"))
        self.assertTrue(controller.start("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        self.assertEqual(controller.state, "reading")
        self.assertTrue(wait_for(lambda: controller.state == "ready"))
        self.assertEqual((controller.total, controller.playlistName, controller.platform), (3, "Lista", "Spotify"))
        controller.confirm("Mi nombre")
        self.assertTrue(wait_for(lambda: controller.state == "done"))
        self.assertEqual(bridge.created, ["Mi nombre"])
        self.assertEqual(bridge.added, [("Mi nombre", ["a", "c"])])
        self.assertEqual((controller.added, controller.skipped), (2, 1))
        self.assertIn("1 sin coincidencia segura", controller.message)

    def test_read_errors_are_shown_and_can_be_retried(self):
        _bridge, controller = self.make(error="Spotify no responde")
        controller.start("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        self.assertTrue(wait_for(lambda: controller.state == "error"))
        self.assertIn("Spotify no responde", controller.message)
        controller.reset()
        self.assertEqual(controller.state, "idle")

    def test_cancelling_while_reading_discards_the_result(self):
        _bridge, controller = self.make()
        controller.start("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        controller.cancel()
        self.assertEqual(controller.state, "idle")
        QTest.qWait(150)
        self.assertEqual(controller.state, "idle")

    def test_stopping_an_import_keeps_the_songs_already_found(self):
        i18n.set_language("es")
        bridge = FakeBridge()
        playlist = pi.Playlist("spotify", "Lista", [pi.Track(str(i)) for i in range(6)])
        holder = {}

        def matcher(track):
            if track.title == "2":
                holder["controller"].cancel()
            return {"id": track.title}

        controller = PlaylistImportController(bridge, fetcher=lambda link: playlist, matcher=matcher)
        holder["controller"] = controller
        controller.start("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        self.assertTrue(wait_for(lambda: controller.state == "ready"))
        controller.confirm("")
        self.assertTrue(wait_for(lambda: controller.state == "done"))
        self.assertEqual(bridge.created, ["Lista"])
        self.assertEqual(bridge.added[0][1], ["0", "1", "2"])
        self.assertIn("detenida", controller.message)


class BridgeAndUiTests(unittest.TestCase):
    def make_bridge(self, directory):
        settings = QSettings(str(Path(directory) / "settings.ini"), QSettings.IniFormat)
        with patch.object(module, "QSettings", return_value=settings), \
                patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                patch.object(module.MusicBoxBridge, "refresh"), patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                patch.object(module.MusicBoxBridge, "refreshForYou"):
            return module.MusicBoxBridge()

    def test_bulk_add_creates_entries_once_and_skips_repeats(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make_bridge(directory)
            bridge.createPlaylist("Importada")
            tracks = [{"id": "aaaaaaaaaaa", "title": "Uno", "artist": "A", "duration": 100, "url": "u"},
                      {"id": "bbbbbbbbbbb", "title": "Dos", "artist": "B", "duration": 120, "url": "u"}]
            self.assertEqual(bridge.addTracksToPlaylist("Importada", tracks), 2)
            self.assertEqual(bridge.addTracksToPlaylist("Importada", tracks + [{"id": "ccccccccccc", "title": "Tres"}]), 1)
            self.assertEqual(len(bridge._playlists["Importada"]), 3)
            self.assertEqual(bridge.addTracksToPlaylist("", tracks), 0)

    def test_searching_a_playlist_link_opens_the_importer_instead_of_searching(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make_bridge(directory)
            seen = []
            bridge.playlistLinkDetected.connect(seen.append)
            bridge.search("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1")
            self.assertEqual(seen, ["https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"])
            self.assertEqual(bridge._search_query, "")

    def test_dialog_loads_and_walks_through_its_phases(self):
        QQuickStyle.setStyle("Material")
        with tempfile.TemporaryDirectory() as directory:
            bridge = self.make_bridge(directory)
            bridge._user = {"name": "Demostración", "email": "demo@example.invalid"}
            playlist = pi.Playlist("spotify", "Lista de prueba", [pi.Track("a"), pi.Track("b")])
            importer = bridge.playlistImporter
            importer._fetcher = lambda link: playlist
            importer._matcher = lambda track: {"id": track.title * 11, "title": track.title, "artist": "X", "duration": 100, "url": "u"}
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
            dialog = root.findChild(QObject, "importLinkDialog")
            self.assertIsNotNone(dialog)
            self.assertIsNotNone(root.findChild(QObject, "importLinkButton"))
            from PySide6.QtCore import QMetaObject, Q_ARG, Qt
            QMetaObject.invokeMethod(dialog, "openFor", Qt.DirectConnection,
                                     Q_ARG("QVariant", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
            self.assertTrue(wait_for(lambda: importer.state == "ready"))
            QTest.qWait(400)
            self.assertEqual(dialog.property("phase"), "ready")
            self.assertEqual(root.findChild(QObject, "importLinkName").property("text"), "Lista de prueba")
            self.assertIn("2", root.findChild(QObject, "importLinkCount").property("text"))
            importer.confirm("Lista de prueba")
            self.assertTrue(wait_for(lambda: importer.state == "done"))
            QTest.qWait(400)
            self.assertIn("2", root.findChild(QObject, "importLinkResult").property("text"))
            self.assertEqual(len(bridge._playlists["Lista de prueba"]), 2)
            self.assertFalse([e for e in errors if "importLink" in e or "ImportLinkDialog" in e], errors)


if __name__ == "__main__":
    unittest.main()
