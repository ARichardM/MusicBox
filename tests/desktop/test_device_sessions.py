"""Cierre de sesión remoto: las marcas se escriben aparte y no dependen del estado principal."""

import io
import json
import unittest
from unittest.mock import patch

import musicbox_cloud


def client():
    with patch.object(musicbox_cloud, "_api_config", return_value=("proyecto", "clave")):
        instance = musicbox_cloud.FirebaseDesktopClient("refresh")
    instance.id_token = "token"
    instance.user = {"uid": "usuario"}
    return instance


class DeviceSessionTests(unittest.TestCase):
    def test_revocation_marks_are_parsed(self):
        document = {"fields": {"d_abc": {"integerValue": "1700000000000"}, "d_x": {"stringValue": "ignorado"}}}
        with patch.object(musicbox_cloud, "urlopen", return_value=io.BytesIO(json.dumps(document).encode())):
            self.assertEqual(client().load_revocations(), {"d_abc": 1700000000000})

    def test_revoking_writes_only_its_own_field_and_removes_the_device(self):
        calls = []
        instance = client()
        state = {"devices": {"abc": {"name": "Teléfono"}, "yo": {"name": "PC"}}, "favorites": ["a"]}
        saved = {}

        def fake_post(url, payload, headers=None, method="POST"):
            calls.append((url, payload, method))
            return {}

        with patch.object(musicbox_cloud, "_post_json", fake_post), \
                patch.object(instance, "load_state", return_value=state), \
                patch.object(instance, "save_state", side_effect=saved.update):
            instance.revoke_device("abc", 1700000000000)

        url, payload, method = calls[0]
        self.assertTrue(url.endswith("/state/revocations?updateMask.fieldPaths=d_abc"))
        self.assertEqual(payload["fields"], {"d_abc": {"integerValue": "1700000000000"}})
        self.assertEqual(method, "PATCH")
        self.assertEqual(list(saved["devices"]), ["yo"])
        self.assertEqual(saved["favorites"], ["a"])


if __name__ == "__main__":
    unittest.main()
