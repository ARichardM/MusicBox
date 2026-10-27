import io
import json
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from musicbox_cloud import FirebaseDesktopClient, StateConflict


class CloudConcurrencyTests(unittest.TestCase):
    def client(self):
        client = FirebaseDesktopClient.__new__(FirebaseDesktopClient)
        client.project_id = "test-only"
        client.id_token = "fake-token"
        client.user = {"uid": "test-user"}
        return client

    def response(self, version, songs):
        return io.BytesIO(json.dumps({"updateTime": version, "fields": {"payload": {
            "stringValue": json.dumps({"songs": songs})}}}).encode())

    def test_write_requires_the_exact_version_that_was_read(self):
        with patch("musicbox_cloud.urlopen", return_value=self.response("version-1", ["a"])), \
                patch("musicbox_cloud._post_json") as write:
            result = self.client().update_state(lambda remote: {"songs": [*remote["songs"], "b"]})
        self.assertEqual(result["songs"], ["a", "b"])
        query = parse_qs(urlsplit(write.call_args.args[0]).query)
        self.assertEqual(query["currentDocument.updateTime"], ["version-1"])

    def test_conflict_rereads_and_recomputes_instead_of_overwriting(self):
        with patch("musicbox_cloud.urlopen", side_effect=[self.response("v1", ["a"]), self.response("v2", ["a", "c"])]), \
                patch("musicbox_cloud._post_json", side_effect=[StateConflict(), {}]) as write:
            result = self.client().update_state(lambda remote: {"songs": [*remote["songs"], "b"]})
        self.assertEqual(result["songs"], ["a", "c", "b"])
        self.assertEqual(write.call_count, 2)
        self.assertEqual(parse_qs(urlsplit(write.call_args.args[0]).query)["currentDocument.updateTime"], ["v2"])

    def test_exhausted_conflicts_raise_and_do_not_claim_success(self):
        with patch("musicbox_cloud.urlopen", side_effect=lambda *args, **kwargs: self.response("v1", [])), \
                patch("musicbox_cloud._post_json", side_effect=StateConflict()):
            with self.assertRaises(StateConflict):
                self.client().update_state(lambda remote: remote, retries=2)


if __name__ == "__main__":
    unittest.main()
