"""Configuración de autenticación Firebase para la app de escritorio."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import musicbox_cloud


class FirebaseDesktopConfigTests(unittest.TestCase):
    def test_missing_configuration_does_not_prevent_startup_or_discard_session(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "missing.json"
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config):
                client = musicbox_cloud.FirebaseDesktopClient("saved-refresh-token")
                self.assertFalse(client.configured)
                self.assertEqual(client.refresh_token, "saved-refresh-token")
                self.assertEqual(client.id_token, "")
                self.assertEqual(client.user, {})

    def test_missing_configuration_reports_sign_in_error_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "missing.json"
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config), \
                    patch.object(musicbox_cloud, "urlopen") as network:
                client = musicbox_cloud.FirebaseDesktopClient()
                self.assertIsNone(client.restore())
                with self.assertRaisesRegex(RuntimeError, "no está configurado"):
                    client.sign_in()
                network.assert_not_called()

    def test_restore_with_missing_configuration_preserves_saved_token(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", Path(directory) / "missing.json"), \
                    patch.object(musicbox_cloud, "urlopen") as network:
                client = musicbox_cloud.FirebaseDesktopClient("saved-token")
                with self.assertRaisesRegex(RuntimeError, "no está configurado"):
                    client.restore()
                self.assertEqual(client.refresh_token, "saved-token")
                network.assert_not_called()

    def test_malformed_configuration_does_not_crash_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "desktop_firebase_config.json"
            config.write_text("{broken", encoding="utf-8")
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config):
                client = musicbox_cloud.FirebaseDesktopClient()
                self.assertFalse(client.configured)
                with self.assertRaisesRegex(RuntimeError, "no está configurado"):
                    client.sign_in()

    def test_configuration_can_be_restored_without_recreating_client(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "desktop_firebase_config.json"
            oauth = Path(directory) / "desktop_oauth_client.json"
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config), \
                    patch.object(musicbox_cloud, "OAUTH_FILE", oauth):
                client = musicbox_cloud.FirebaseDesktopClient()
                config.write_text('{"project_id":"musicbox-test","api_key":"test-key"}', encoding="utf-8")
                oauth.write_text("{}", encoding="utf-8")
                client._ensure_api_config()
                self.assertTrue(client.configured)
                self.assertEqual((client.project_id, client.api_key), ("musicbox-test", "test-key"))

    def test_invalid_configuration_structure_is_reported_without_crashing(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "desktop_firebase_config.json"
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config):
                for content in ("[]", "null", "{}"):
                    with self.subTest(content=content):
                        config.write_text(content, encoding="utf-8")
                        client = musicbox_cloud.FirebaseDesktopClient()
                        self.assertFalse(client.configured)
                        with self.assertRaisesRegex(RuntimeError, "incompleta"):
                            client.sign_in()

    def test_missing_oauth_configuration_does_not_open_browser(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "desktop_firebase_config.json"
            config.write_text('{"project_id":"musicbox-test","api_key":"test-key"}', encoding="utf-8")
            with patch.object(musicbox_cloud, "FIREBASE_CONFIG_FILE", config), \
                    patch.object(musicbox_cloud, "OAUTH_FILE", Path(directory) / "missing-oauth.json"), \
                    patch.object(musicbox_cloud, "urlopen") as network:
                client = musicbox_cloud.FirebaseDesktopClient()
                self.assertFalse(client.configured)
                with self.assertRaisesRegex(RuntimeError, "no está configurado"):
                    client.sign_in()
                network.assert_not_called()

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
