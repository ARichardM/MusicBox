"""Selección de cliente de YouTube y validación del enlace de audio."""

import unittest
from unittest.mock import MagicMock

import youtube_streams


def opener_for(allowed):
    def opener(request, timeout=0):
        if request.full_url not in allowed:
            raise OSError("403")
        response = MagicMock(status=206)
        response.__enter__.return_value = response
        return response
    return opener


class YoutubeStreamTests(unittest.TestCase):
    def test_falls_back_to_next_client_when_link_is_forbidden(self):
        calls = []

        def extractor(options, url):
            client = options["extractor_args"]["youtube"]["player_client"][0]
            calls.append(client)
            return {"url": "https://good" if client == "web" else "https://bad", "http_headers": {}}

        info = youtube_streams.resolve_stream("u", extractor, opener_for({"https://good"}))
        self.assertEqual(info["url"], "https://good")
        self.assertEqual(calls, ["android_vr", "web"])

    def test_raises_when_no_client_yields_a_reachable_link(self):
        with self.assertRaises(RuntimeError):
            youtube_streams.resolve_stream("u", lambda o, u: {"url": "https://bad"}, opener_for(set()))

    def test_search_retries_when_first_attempt_is_empty(self):
        answers = iter([{"entries": []}, {"entries": [{"id": "a"}, None]}])
        self.assertEqual(youtube_streams.search("q", lambda o, u: next(answers)), [{"id": "a"}])


if __name__ == "__main__":
    unittest.main()
