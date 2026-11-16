"""El enlace directo manda; la descarga al servidor solo entra cuando ninguna herramienta consigue un enlace."""
import sys
import tempfile
import unittest
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
