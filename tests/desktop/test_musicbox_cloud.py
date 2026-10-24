"""Configuración de autenticación Firebase para la app de escritorio."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import musicbox_cloud


class FirebaseDesktopConfigTests(unittest.TestCase):
    def test_reads_dedicated_desktop_config(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "desktop_firebase_config.json"
            config.write_text(json.dumps({
                "project_id": "musicbox-test",
                "api_key": "desktop-test-key",
            }), encoding="utf-8")
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config):
                self.assertEqual(
                    musicbox_cloud._api_config(),
                    ("musicbox-test", "desktop-test-key"),
                )

    def test_rejects_incomplete_desktop_config(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "desktop_firebase_config.json"
            config.write_text('{"project_id": "musicbox-test"}', encoding="utf-8")
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config):
                with self.assertRaisesRegex(RuntimeError, "incompleta"):
                    musicbox_cloud._api_config()


if __name__ == "__main__":
    unittest.main()
