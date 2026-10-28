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


def run(adb, serial=None, reboot=False):
    command = [adb] + (["-s", serial] if serial else [])

    def device(*arguments, timeout=240):
        result = subprocess.run(command + list(arguments), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    devices = device("devices")
    if not serial and len(re.findall(r"^\S+\s+device$", devices, re.MULTILINE)) != 1:
        raise RuntimeError("Conecta un solo dispositivo o indica --serial.")
    hardware_serial = device("shell", "getprop", "ro.serialno").strip() if reboot else None
    if reboot and not hardware_serial:
        raise RuntimeError("No se pudo identificar el teléfono para reconectarlo tras reiniciar.")
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

    def reboot_and_reconnect():
        previous_boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
        if not previous_boot:
            raise RuntimeError("No se pudo identificar el arranque anterior del teléfono.")
        print("Reiniciando el teléfono. Desbloquéalo cuando termine de arrancar.", flush=True)
        device("reboot")
        # La dirección/puerto de ADB inalámbrico puede cambiar al reiniciar.
        # Solo se acepta el mismo número de serie físico, nunca otro dispositivo.
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            connected = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15)
            for candidate in re.findall(r"^(\S+)\s+device$", connected.stdout, re.MULTILINE):
                try:
                    probe = subprocess.run([adb, "-s", candidate, "shell", "getprop", "ro.serialno"],
                                           capture_output=True, text=True, timeout=10)
                    if probe.returncode or probe.stdout.strip() != hardware_serial:
                        continue
                    command[:] = [adb, "-s", candidate]
                    current_boot = device("shell", "cat", "/proc/sys/kernel/random/boot_id", timeout=10).strip()
                    if not current_boot or current_boot == previous_boot:
                        continue
                    if device("shell", "getprop", "sys.boot_completed", timeout=10).strip() != "1":
                        continue
                    if device("shell", "am", "get-started-user-state", "0", timeout=10).strip() != "RUNNING_UNLOCKED":
                        continue
                    device("reverse", reverse, reverse)
                    print("Mismo teléfono reconectado y desbloqueado; continúa la recuperación.", flush=True)
                    return
                except (RuntimeError, subprocess.TimeoutExpired):
                    continue
            time.sleep(2)
        raise RuntimeError("El teléfono no volvió desbloqueado a ADB en diez minutos.")

    try:
        device("reverse", reverse, reverse)
        for activity in ("main", "downloader"):
            print("Verificando recuperación desde " + activity, flush=True)
            instrument("prepareInterruptedDownloads", activity)
            device("shell", "am", "force-stop", "--user", "0", PACKAGE)
            if reboot and activity == "main":
                reboot_and_reconnect()
            ranges.clear()
            instrument("reopeningResumesOnlyPendingDownloads", activity)
            if not any(path == "/recovery-first.wav" for path, _offset in ranges):
                raise RuntimeError("No se observó reanudación HTTP del archivo parcial desde " + activity)
            device("shell", "am", "force-stop", "--user", "0", PACKAGE)
            instrument("anotherLaunchDoesNotRepeatCompletedOrCancelledJobs", activity)
        print("Recuperación verificada: dos pendientes completados, parcial reanudado y cancelación conservada.", flush=True)
    finally:
        try:
            device("shell", "am", "force-stop", "--user", "0", PACKAGE, timeout=15)
        finally:
            try:
                device("reverse", "--remove", reverse, timeout=15)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--reboot", action="store_true",
                        help="Reinicia físicamente el teléfono una vez; requiere autorización y desbloqueo manual")
    options = parser.parse_args()
    run(options.adb, options.serial, options.reboot)
