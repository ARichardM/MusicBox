"""Comparación de versiones, lectura de publicaciones y verificación del instalador."""

import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import updater


def release(tag="v2.3.0", assets=True):
    return {"tag_name": tag, "name": "MusicBox " + tag, "published_at": "2026-10-30T10:00:00Z",
            "html_url": "https://github.com/x/y/releases/tag/" + tag,
            "body": "## Novedades\n- Nuevo **reproductor**\n* Corrección de [errores](http://a.b)\n\n**Full Changelog**: http://x",
            "assets": [{"name": updater.INSTALLER_NAME, "browser_download_url": "https://x/setup.exe"},
                       {"name": updater.INSTALLER_NAME + ".sha256", "browser_download_url": "https://x/setup.sha"}] if assets else []}


def fake_opener(payload):
    def opener(request, timeout=0):
        response = MagicMock()
        response.read.return_value = json.dumps(payload).encode()
        response.__enter__.return_value = response
        return response
    return opener


class UpdaterTests(unittest.TestCase):
    def test_version_comparison(self):
        self.assertTrue(updater.is_newer("2.10.0", "2.9.9"))
        self.assertTrue(updater.is_newer("v2.2.3", "2.2.2"))
        self.assertFalse(updater.is_newer("2.2.2", "2.2.2"))
        self.assertFalse(updater.is_newer("2.2.2-pruebas", "2.2.2"))

    def test_notes_are_cleaned(self):
        notes = updater.clean_notes(release()["body"])
        self.assertEqual(notes, ["Nuevo reproductor", "Corrección de errores"])

    def test_check_returns_info_only_for_newer_release(self):
        info = updater.check_for_update("2.2.2", fake_opener(release()))
        self.assertEqual(info["version"], "2.3.0")
        self.assertEqual(info["url"], "https://x/setup.exe")
        self.assertIsNone(updater.check_for_update("2.3.0", fake_opener(release())))
        self.assertIsNone(updater.check_for_update("2.2.2", fake_opener(release(assets=False))))

    def test_download_verifies_checksum(self):
        data = b"installer-bytes"
        info = {"version": "2.3.0", "url": "https://x/setup.exe", "checksumUrl": "https://x/setup.sha"}

        def make_open(digest):
            def fake_urlopen(request, timeout=0):
                url = getattr(request, "full_url", request)
                body = (digest + "  MusicBox-Windows-Setup.exe").encode() if url.endswith(".sha") else data
                response = io.BytesIO(body)
                response.headers = {"Content-Length": str(len(body))}
                return response
            return fake_urlopen

        with tempfile.TemporaryDirectory() as folder:
            good = hashlib.sha256(data).hexdigest()
            with patch("urllib.request.urlopen", make_open(good)):
                path = updater.download_installer(info, Path(folder), lambda *_: None, lambda: False)
            self.assertEqual(path.read_bytes(), data)
            with patch("urllib.request.urlopen", make_open("0" * 64)):
                with self.assertRaises(RuntimeError):
                    updater.download_installer(info, Path(folder) / "b", lambda *_: None, lambda: False)
            self.assertEqual(list((Path(folder) / "b").iterdir()), [])


if __name__ == "__main__":
    unittest.main()
