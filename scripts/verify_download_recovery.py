"""Comprueba recuperación tras detener el proceso, solo en la variante Android verification."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import re
import subprocess
import threading
import time
import wave


PACKAGE = "cl.arichard.descargador.verification"
TEST_CLASS = "cl.arichard.descargador.download.DownloadRecoveryDeviceTest"


def run(adb, serial=None):
    command = [adb] + (["-s", serial] if serial else [])

    def device(*arguments, timeout=240):
        result = subprocess.run(command + list(arguments), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    devices = device("devices")
    if not serial and len(re.findall(r"^\S+\s+device$", devices, re.MULTILINE)) != 1:
        raise RuntimeError("Conecta un solo dispositivo o indica --serial.")
    audio = io.BytesIO()
    with wave.open(audio, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(bytes(16000 * 2 * 20))
    payload = audio.getvalue()
    ranges = []

    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            if self.path not in ("/recovery-first.wav", "/recovery-second.wav", "/recovery-cancelled.wav"):
                self.send_error(404)
                return
            start = 0
            requested = self.headers.get("Range", "")
            if requested:
                match = re.fullmatch(r"bytes=(\d+)-", requested)
                if not match or int(match.group(1)) >= len(payload):
                    self.send_error(416)
                    return
                start = int(match.group(1))
                if body and start > 0:
                    ranges.append((self.path, start))
            self.send_response(206 if requested else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(payload) - start))
            self.send_header("Accept-Ranges", "bytes")
            if requested:
                self.send_header("Content-Range", f"bytes {start}-{len(payload) - 1}/{len(payload)}")
            self.end_headers()
            if body:
                try:
                    for offset in range(start, len(payload), 8192):
                        self.wfile.write(payload[offset:offset + 8192])
                        self.wfile.flush()
                        time.sleep(.1)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

        def log_message(self, *_arguments):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    port = server.server_port
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    reverse = f"tcp:{port}"

    def instrument(method, activity):
        result = device("shell", "am", "instrument", "--user", "0", "-w", "-r",
                        "-e", "class", TEST_CLASS + "#" + method,
                        "-e", "recoverySource", f"http://127.0.0.1:{port}",
                        "-e", "recoveryActivity", activity,
                        PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        print(result, flush=True)
        if "OK (1 test)" not in result or "FAILURES" in result:
            raise RuntimeError("Falló la fase " + method)

    try:
        device("reverse", reverse, reverse)
        for activity in ("main", "downloader"):
            print("Verificando recuperación desde " + activity, flush=True)
            instrument("prepareInterruptedDownloads", activity)
            device("shell", "am", "force-stop", "--user", "0", PACKAGE)
            ranges.clear()
            instrument("reopeningResumesOnlyPendingDownloads", activity)
            if not any(path == "/recovery-first.wav" for path, _offset in ranges):
                raise RuntimeError("No se observó reanudación HTTP del archivo parcial desde " + activity)
            device("shell", "am", "force-stop", "--user", "0", PACKAGE)
            instrument("anotherLaunchDoesNotRepeatCompletedOrCancelledJobs", activity)
        print("Recuperación verificada: dos pendientes completados, parcial reanudado y cancelación conservada.", flush=True)
    finally:
        device("shell", "am", "force-stop", "--user", "0", PACKAGE)
        device("reverse", "--remove", reverse)
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    options = parser.parse_args()
    run(options.adb, options.serial)
