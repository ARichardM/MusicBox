"""El enlace directo manda; la descarga al servidor solo entra cuando ninguna herramienta consigue un enlace."""
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "apps" / "web" / "backend"))
import youtube_streams  # noqa: E402


class FakeResponse:
    status = 206
    def __enter__(self): return self
    def __exit__(self, *args): return False


class StreamFallbackTests(unittest.TestCase):
    def test_direct_link_is_used_and_the_download_is_never_touched(self):
        calls = []
        info = {"url": "https://example.test/audio.m4a", "http_headers": {}}
        kind, result = youtube_streams.resolve_or_cache(
            "https://www.youtube.com/watch?v=abc", lambda options, url: info,
            lambda options, url: calls.append("download"), Path(tempfile.gettempdir()), opener=lambda *a, **k: FakeResponse())
        self.assertEqual(("url", info), (kind, result))
        self.assertEqual([], calls)

    def setUp(self):
        youtube_streams._preferred_client = None

    def test_the_client_that_worked_is_tried_first_next_time(self):
        calls = []

        def extractor(options, url):
            client = options["extractor_args"]["youtube"]["player_client"][0]
            calls.append(client)
            return {"url": "https://good" if client == "web" else "https://bad", "http_headers": {}}

        def opener(request, timeout=0):
            if request.full_url != "https://good":
                raise OSError("403")
            return FakeResponse()

        youtube_streams.resolve_stream("u", extractor, opener)
        self.assertEqual(["android", "web"], calls, "La primera vez se recorre el orden normal")
        calls.clear()
        youtube_streams.resolve_stream("u", extractor, opener)
        self.assertEqual(["web"], calls, "La segunda vez empieza por el cliente que funcionó, sin repetir el que falla")

    @staticmethod
    def _opener(truncated):
        """Los enlaces 'truncated' atienden el inicio del archivo pero rechazan cualquier tramo lejano."""
        def opener(request, timeout=0):
            if request.full_url in truncated and not request.get_header("Range").startswith("bytes=0-"):
                raise urllib.error.HTTPError(request.full_url, 403, "Forbidden", {}, None)
            return FakeResponse()
        return opener

    def test_link_that_only_serves_the_start_of_the_file_is_skipped(self):
        urls = iter(["https://example.test/truncated", "https://example.test/complete"])
        info = lambda options, url: {"url": next(urls), "http_headers": {}}
        result = youtube_streams.resolve_stream("https://www.youtube.com/watch?v=abc", info,
                                                self._opener({"https://example.test/truncated"}))
        self.assertEqual("https://example.test/complete", result["url"])

    def test_known_size_probes_the_end_of_the_file(self):
        def opener(request, timeout=0):  # solo atiende bytes por debajo de ~1 MB, como algunos enlaces de android_vr
            if int(request.get_header("Range")[6:].split("-")[0]) >= 1_000_000:
                raise urllib.error.HTTPError(request.full_url, 403, "Forbidden", {}, None)
            return FakeResponse()
        url = "https://example.test/audio"
        self.assertTrue(youtube_streams.stream_is_reachable(url, {}, opener, size=900_000))
        self.assertFalse(youtube_streams.stream_is_reachable(url, {}, opener, size=1_149_530))
        self.assertFalse(youtube_streams.stream_is_reachable(url, {}, opener), "Sin tamaño se prueba un tramo lejano")

    def test_range_not_satisfiable_far_away_means_a_short_file(self):
        def opener(request, timeout=0):
            if request.get_header("Range").startswith("bytes=0-"):
                return FakeResponse()
            raise urllib.error.HTTPError(request.full_url, 416, "Range Not Satisfiable", {}, None)
        self.assertTrue(youtube_streams.stream_is_reachable("https://example.test/short", {}, opener))

    def test_download_is_the_last_resort_when_every_client_fails(self):
        attempts = []
        def broken(options, url):
            attempts.append(options["extractor_args"]["youtube"]["player_client"])
            raise RuntimeError("sin enlace")
        with tempfile.TemporaryDirectory() as folder:
            def download(options, url):
                target = Path(options["outtmpl"].replace("%(id)s", "abc").replace("%(ext)s", "m4a"))
                target.write_bytes(b"audio")
                return str(target)
            kind, result = youtube_streams.resolve_or_cache("https://www.youtube.com/watch?v=abc", broken, download, Path(folder))
            self.assertEqual("file", kind)
            self.assertTrue(Path(result).is_file())
        self.assertEqual(len(youtube_streams.CLIENT_SETS), len(attempts), "Antes de descargar se probaron todos los clientes")


if __name__ == "__main__":
    unittest.main()
