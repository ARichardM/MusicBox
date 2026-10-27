"""Pruebas locales: no descargan contenido de YouTube ni usan cookies."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import gc
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest

import descargador_gui as app


class ConfigurationTests(unittest.TestCase):
    def tearDown(self):
        # Los objetos Tcl de cada ventana deben recogerse en el hilo principal.
        gc.collect()

    def test_urls_and_deduplication(self):
        url = "https://www.youtube.com/watch?v=example&list=example"
        self.assertEqual(app.parse_urls(f"{url}\n\n{url}", False), [url])
        for invalid in ("", "sin enlace", "file:///tmp/video.mp4"):
            with self.assertRaises(ValueError):
                app.parse_urls(invalid, False)
        with self.assertRaises(ValueError):
            app.parse_urls("https://www.youtube.com/playlist?list=example", False)

    def test_ranges(self):
        self.assertEqual(app.validate_items("1, 3, 5-8"), "1,3,5-8")
        self.assertEqual(app.validate_items(""), "")
        for invalid in ("0", "9-2", "1,,3", "abc", "-1"):
            with self.assertRaises(ValueError):
                app.validate_items(invalid)

    def test_playlist_and_resolution_options(self):
        config = dict(destination="output", ffmpeg=app.find_ffmpeg(), playlist=True,
                      items="2-4", format="MP4", quality="720p", cookies="")
        options = app.build_options(config)
        self.assertFalse(options["noplaylist"])
        self.assertEqual(options["playlist_items"], "2-4")
        self.assertIn("[height<=?720]", options["format"])
        self.assertIn("playlist_index", options["outtmpl"])
        self.assertFalse(options["overwrites"])
        config.update(playlist=False, format="MP3", quality="320 kbps")
        options = app.build_options(config)
        self.assertTrue(options["noplaylist"])
        self.assertNotIn("playlist_items", options)
        self.assertEqual(options["postprocessors"][0]["preferredquality"], "320")

    def test_gui_states(self):
        window = app.launch_gui()
        window.withdraw()
        try:
            window.update()
            window.format_var.set("MP4")
            window.mode_var.set("playlist")
            window.update_options()
            self.assertEqual(window.quality_var.get(), "1080p")
            self.assertEqual(str(window.items_entry["state"]), "normal")
            window.set_running(True)
            self.assertEqual(str(window.start_button["state"]), "disabled")
            window.handle_event(dict(event="summary", completed=2, errors=0))
            window.handle_event(dict(event="done", cancelled=False))
            self.assertFalse(window.running)
            self.assertEqual(window.status_var.get(), "¡Descargas terminadas!")
            window.set_running(True)
            window.handle_event(dict(event="done", cancelled=True))
            self.assertEqual(window.status_var.get(), "Descarga cancelada")
        finally:
            window.destroy()

    def test_compact_window_keeps_actions_visible(self):
        window = app.launch_gui()
        try:
            window.geometry("860x640")
            window.mode_var.set("playlist")
            window.update_options()
            window.toggle_advanced()
            window.set_running(True)
            window.update()
            self.assertTrue(window.start_button.winfo_ismapped())
            self.assertTrue(window.cancel_button.winfo_ismapped())
            self.assertTrue(window.settings_scroll.scrollbar.winfo_ismapped())
            bottom = window.cancel_button.winfo_rooty() + window.cancel_button.winfo_height()
            self.assertLess(bottom, window.winfo_rooty() + window.winfo_height())
            window.set_running(False)
        finally:
            window.destroy()

    def test_playlist_detection_respects_manual_mode(self):
        window = app.launch_gui()
        window.withdraw()
        try:
            window.urls.insert("1.0", "https://www.youtube.com/playlist?list=example")
            window.refresh_links()
            self.assertEqual(window.mode_var.get(), "playlist")
            window.mode_var.set("single")
            window.select_mode()
            window.refresh_links()
            self.assertEqual(window.mode_var.get(), "single")
            window.clear_links()
            window.urls.insert("1.0", "https://www.youtube.com/watch?v=example&list=example")
            window.refresh_links()
            self.assertEqual(window.mode_var.get(), "single")
        finally:
            window.destroy()

    def test_invalid_input_stays_in_window(self):
        window = app.launch_gui()
        window.withdraw()
        try:
            window.start_download()
            self.assertFalse(window.running)
            self.assertIn("al menos un enlace", window.error_var.get())
        finally:
            window.destroy()

    def test_cancellation_is_not_overwritten_by_late_progress(self):
        window = app.launch_gui()
        window.withdraw()
        try:
            window.set_running(True)
            window.cancel_download()
            window.handle_event(dict(event="progress", status="downloading", title="Tardío", total=100, downloaded=50))
            self.assertEqual(window.badge_var.get(), "CANCELANDO")
            window.handle_event(dict(event="done", cancelled=True))
        finally:
            window.destroy()


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path != "/slow.mp4":
            return super().do_GET()
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(1024 * 1024))
        self.end_headers()
        try:
            for _ in range(1024):
                self.wfile.write(b"0" * 1024)
                self.wfile.flush()
                time.sleep(0.05)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


@unittest.skipUnless(app.find_ffmpeg(), "Requiere FFmpeg")
class DownloadTests(unittest.TestCase):
    def tearDown(self):
        gc.collect()

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        # Windows CI puede entregar TEMP mediante un alias 8.3 o una unión.
        # El historial guarda rutas resueltas: buscar con el alias no coincide.
        cls.root = Path(cls.temp.name).resolve()
        cls.media = cls.root / "video.mp4"
        subprocess.run([
            app.find_ffmpeg(), "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            str(cls.media),
        ], check=True, capture_output=True)
        shutil.copyfile(cls.media, cls.root / "video2.mp4")
        (cls.root / "playlist.html").write_text(
            '<html><head><title>Lista de prueba</title></head><body>'
            '<video src="video.mp4" controls></video>'
            '<video src="video2.mp4" controls></video></body></html>', encoding="utf-8")
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(cls.root)))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/video.mp4"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        cls.temp.cleanup()

    def worker(self, fmt, urls=None, folder=None, playlist=False, items="", history_path=None):
        destination = self.root / (folder or fmt)
        destination.mkdir(exist_ok=True)
        config = dict(urls=urls or [self.url], destination=str(destination),
                      ffmpeg=app.find_ffmpeg(), playlist=playlist, items=items, cookies="",
                      format=fmt, quality="192 kbps" if fmt == "MP3" else "720p",
                      history_path=str(history_path or self.root / "history.sqlite3"))
        result = subprocess.run([sys.executable, str(app.CODE_DIR / "descargador_gui.py"), "--worker"],
                                input=json.dumps(config) + "\n", capture_output=True,
                                text=True, encoding="utf-8", timeout=45)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        return result, events, destination

    def test_real_mp3_and_mp4(self):
        for fmt in ("MP3", "MP4"):
            with self.subTest(format=fmt):
                result, events, destination = self.worker(fmt)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                summary = next(event for event in events if event["event"] == "summary")
                self.assertEqual(summary["completed"], 1)
                self.assertEqual(summary["errors"], 0)
                final = list(destination.glob(f"*.{fmt.lower()}"))
                self.assertEqual(len(final), 1)
                self.assertGreater(final[0].stat().st_size, 1000)
                decoded = subprocess.run([app.find_ffmpeg(), "-v", "error", "-i", str(final[0]),
                                          "-f", "null", "-"], capture_output=True, timeout=15)
                self.assertEqual(decoded.returncode, 0, decoded.stderr)

    def test_failed_link_does_not_block_next_link(self):
        invalid = self.url.replace("video.mp4", "no-existe.mp4")
        result, events, destination = self.worker("MP3", [invalid, self.url], folder="mixed")
        self.assertEqual(result.returncode, 1)
        summary = next(event for event in events if event["event"] == "summary")
        self.assertEqual(summary["completed"], 1)
        self.assertGreaterEqual(summary["errors"], 1)
        self.assertTrue(next(destination.glob("*.mp3")).name.startswith("002 - "))

    def test_multiple_links_keep_queue_order_and_numbered_files_on_retry(self):
        urls = [self.url.replace("video.mp4", "video2.mp4"), self.url]
        for fmt in ("MP3", "MP4"):
            with self.subTest(format=fmt):
                folder = f"ordered-{fmt}"
                result, events, destination = self.worker(fmt, urls, folder=folder)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                ready = [Path(event["filename"]).name for event in events if event["event"] == "ready"]
                self.assertEqual(len(ready), 2)
                self.assertTrue(ready[0].startswith("001 - video2"), ready)
                self.assertTrue(ready[1].startswith("002 - video "), ready)
                before = {file.name: (file.stat().st_mtime_ns, file.read_bytes())
                          for file in destination.iterdir()}
                result, events, _ = self.worker(fmt, urls, folder=folder)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual([Path(event["filename"]).name for event in events
                                  if event["event"] == "ready"], ready)
                self.assertEqual({file.name: (file.stat().st_mtime_ns, file.read_bytes())
                                  for file in destination.iterdir()}, before)

    def test_playlist_range(self):
        playlist_url = self.url.replace("video.mp4", "playlist.html")
        result, events, destination = self.worker("MP3", [playlist_url], folder="playlist", playlist=True, items="2")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        summary = next(event for event in events if event["event"] == "summary")
        self.assertEqual(summary["completed"], 1)
        files = list(destination.rglob("*.mp3"))
        self.assertEqual(len(files), 1)
        self.assertTrue(files[0].name.startswith("002 - "))

    def test_worker_history_records_final_files_and_preserved_retries(self):
        from historial import DownloadHistory

        for expected_state in ("downloaded", "existing"):
            result, events, destination = self.worker("MP3", folder="history-check")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            rows, total = DownloadHistory(self.root / "history.sqlite3").page(str(destination))
            self.assertTrue(rows, events)
            self.assertEqual(rows[0]["state"], expected_state)
            self.assertEqual(rows[0]["source_url"], self.url)
            self.assertEqual(rows[0]["title"], "video")
            self.assertTrue(Path(rows[0]["filename"]).is_file())
            self.assertEqual(total, 1 if expected_state == "downloaded" else 2)

    def test_history_failure_does_not_fail_a_completed_download(self):
        invalid_database = self.root / "invalid.sqlite3"
        invalid_database.write_bytes(b"No es una base SQLite")
        result, events, destination = self.worker("MP3", folder="history-error", history_path=invalid_database)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(list(destination.glob("*.mp3"))), 1)
        self.assertTrue(any(event.get("level") == "warning" and "historial" in event.get("text", "")
                            for event in events))
        self.assertEqual(next(event for event in events if event["event"] == "summary")["completed"], 1)

    def test_existing_file_is_preserved(self):
        result, _, destination = self.worker("MP3", folder="preserve")
        self.assertEqual(result.returncode, 0)
        final = next(destination.glob("*.mp3"))
        before = (final.stat().st_mtime_ns, final.read_bytes())
        result, _, _ = self.worker("MP3", folder="preserve")
        self.assertEqual(result.returncode, 0)
        self.assertEqual((final.stat().st_mtime_ns, final.read_bytes()), before)

    def download_music_info(self, folder, **metadata):
        destination = self.root / folder
        config = dict(destination=str(destination), ffmpeg=app.find_ffmpeg(),
                      playlist=False, format="MP3", quality="192 kbps")
        info = dict(id="music", title="Título del video", url=self.url,
                    ext="mp4", webpage_url=self.url, extractor="local",
                    extractor_key="Local", **metadata)
        with app.create_downloader(app.build_options(config)) as downloader:
            downloader.process_ie_result(info, download=True)
        return destination, next(destination.glob("*.mp3"))

    def test_mp3_embeds_music_tags_and_cover_without_sidecar(self):
        from mutagen.id3 import ID3
        from PIL import Image

        # WebP es habitual en YouTube; comprobar también JPEG y PNG.
        for extension in ("jpg", "png", "webp"):
            with self.subTest(cover=extension):
                cover = self.root / f"cover.{extension}"
                with Image.new("RGB", (64, 64), "red") as image:
                    image.save(cover)
                destination, final = self.download_music_info(
                    f"tags-{extension}", track="Canción de prueba 日本語",
                    artists=["Artista uno", "Artista dos"], album="Álbum de prueba",
                    album_artist="Artista del álbum", track_number=3, disc_number=2,
                    genre="Rock", composer="Compositor", upload_date="20251001",
                    description="Descripción de la canción",
                    thumbnails=[dict(url=self.url.replace("video.mp4", cover.name))])
                tags = ID3(final)
                for key, expected in {
                    "TIT2": "Canción de prueba 日本語", "TPE1": "Artista uno, Artista dos",
                    "TALB": "Álbum de prueba", "TPE2": "Artista del álbum",
                    "TRCK": "3", "TPOS": "2", "TCON": "Rock", "TCOM": "Compositor",
                    "TDRC": "2025",
                }.items():
                    self.assertEqual(str(tags[key]), expected)
                artwork = tags.getall("APIC")
                self.assertEqual(len(artwork), 1)
                self.assertEqual(artwork[0].type, 3)  # Portada frontal ID3.
                with Image.open(io.BytesIO(artwork[0].data)) as image:
                    self.assertEqual(image.size, (64, 64))
                    self.assertIn(image.format, ("JPEG", "PNG"))
                self.assertEqual(list(destination.iterdir()), [final])

                # Reintentar la misma descarga no debe modificar el MP3 existente.
                before = (final.stat().st_mtime_ns, final.read_bytes())
                self.download_music_info(f"tags-{extension}", track="Otro título")
                self.assertEqual((final.stat().st_mtime_ns, final.read_bytes()), before)

    def test_mp3_with_missing_music_info_or_unavailable_cover(self):
        from mutagen.id3 import ID3

        for thumbnails in ([], [dict(url=self.url.replace("video.mp4", "missing.jpg"))]):
            with self.subTest(thumbnails=thumbnails):
                destination, final = self.download_music_info(
                    f"missing-cover-{len(thumbnails)}", uploader="Canal de prueba",
                    thumbnails=thumbnails)
                tags = ID3(final)
                self.assertEqual(str(tags["TIT2"]), "Título del video")
                self.assertEqual(str(tags["TPE1"]), "Canal de prueba")
                self.assertNotIn("TALB", tags)
                self.assertEqual(tags.getall("APIC"), [])
                self.assertEqual(list(destination.iterdir()), [final])

    def wait_until(self, window, condition, timeout=20):
        deadline = time.monotonic() + timeout
        while not condition():
            self.assertLess(time.monotonic(), deadline, "La interfaz no terminó a tiempo")
            window.update()
            time.sleep(0.01)

    def test_gui_download_and_cancel(self):
        window = app.launch_gui(history_path=self.root / "gui-history.sqlite3")
        window.withdraw()
        try:
            window.folder_var.set(str(self.root / "gui"))
            window.urls.insert("1.0", self.url)
            window.start_download()
            self.wait_until(window, lambda: not window.running)
            self.assertEqual(window.status_var.get(), "¡Descargas terminadas!")
            self.assertEqual(window.completed, 1)
            window.urls.delete("1.0", "end")
            window.urls.insert("1.0", self.url.replace("video.mp4", "slow.mp4"))
            window.start_download()
            self.wait_until(window, lambda: "KB" in window.detail_var.get())
            process = window.process
            self.assertIsNotNone(process)
            window.cancel_download()
            self.wait_until(window, lambda: not window.running, timeout=10)
            self.assertEqual(window.status_var.get(), "Descarga cancelada")
            self.assertIsNotNone(process.poll())
        finally:
            if window.process:
                app.stop_process(window.process)
            window.destroy()


if __name__ == "__main__":
    unittest.main(verbosity=2)
