"""Las fotos se consultan fuera del hilo de interfaz y se conservan por artista."""
import os
import threading
import time
import unittest
from unittest.mock import Mock
from types import SimpleNamespace

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication
from musicbox_desktop import MusicBoxBridge

APP = QApplication.instance() or QApplication([])


class ArtistPhotosTests(unittest.TestCase):
    def make(self):
        b = MusicBoxBridge.__new__(MusicBoxBridge)
        QObject.__init__(b)
        b._library_artists = ["Ana", "Otro"]
        b._library_artist = "Ana"
        b._artist_directory = {}
        b._artist_avatar_pending = set()
        b._artist_avatar_retry = {}
        b._artist_avatar_slots = threading.Semaphore(2)
        b._settings = SimpleNamespace(setValue=Mock())
        b._artistAvatarReady.connect(b._apply_artist_avatar)
        return b

    def wait(self, condition):
        deadline = time.monotonic() + 3
        while not condition() and time.monotonic() < deadline:
            APP.processEvents()
            time.sleep(.01)
        self.assertTrue(condition())

    def test_photo_is_cached_and_duplicate_requests_share_a_lookup(self):
        b = self.make()
        release = threading.Event()
        b._resolve_artist = Mock(side_effect=lambda name: release.wait(2) and {"name": name, "photo": "https://example.invalid/a.jpg"})
        try:
            b.requestArtistPhoto("Ana")
            b.requestArtistPhoto("ana")
            release.set()
            self.wait(lambda: not b._artist_avatar_pending)
            self.assertEqual(b.artistPhotos["Ana"], "https://example.invalid/a.jpg")
            b.requestArtistPhoto("Ana")
            self.assertEqual(b._resolve_artist.call_count, 1)
            b._settings.setValue.assert_called_once()
        finally:
            release.set()

    def test_a_late_result_does_not_change_selected_artist_or_its_photo(self):
        b = self.make()
        release = threading.Event()
        def resolve(name):
            if name == "Ana": release.wait(2)
            return {"name": name, "photo": "https://example.invalid/" + name}
        b._resolve_artist = resolve
        try:
            b.requestArtistPhoto("Ana")
            b._library_artist = "Otro"
            b.requestArtistPhoto("Otro")
            self.wait(lambda: bool(b.artistPhotos["Otro"]))
            release.set()
            self.wait(lambda: not b._artist_avatar_pending)
            self.assertEqual(b.libraryArtist, "Otro")
            self.assertEqual(b.artistPhotos[b.libraryArtist], "https://example.invalid/Otro")
        finally:
            release.set()

    def test_a_failed_request_keeps_fallback_without_repeated_lookups(self):
        b = self.make()
        b._resolve_artist = Mock(side_effect=OSError("offline"))
        b.requestArtistPhoto("Ana")
        self.wait(lambda: not b._artist_avatar_pending)
        b.requestArtistPhoto("Ana")
        self.assertEqual(b._resolve_artist.call_count, 1)
        self.assertEqual(b.artistPhotos["Ana"], "")


if __name__ == "__main__":
    unittest.main()
