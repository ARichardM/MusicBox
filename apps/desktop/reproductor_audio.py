"""Escucha de audio en streaming, aislada de Tk y de la cola de descargas."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import threading
import time

import descargador_gui as backend
from reproductor_video import video_id

RATE = 44100
FRAMES = 2048
FRAME_BYTES = 4  # Estéreo, PCM de 16 bits.


def resolve_audio(config):
    import yt_dlp

    identifier = video_id(config["url"])
    options = dict(format="bestaudio/best", noplaylist=True, quiet=True, no_warnings=True,
                   skip_download=True, socket_timeout=15, retries=1, extractor_retries=1,
                   js_runtimes={name: {} for name in ("deno", "node") if shutil.which(name)})
    if config.get("cookies"):
        options["cookiefile"] = config["cookies"]
    with yt_dlp.YoutubeDL(options) as downloader:
        info = downloader.extract_info("https://www.youtube.com/watch?v=" + identifier, download=False)
    if not info or not info.get("url") or info.get("is_live"):
        raise ValueError("No hay una pista de audio disponible para esta vista previa.")
    return dict(url=info["url"], duration=float(info.get("duration") or config.get("duration") or 0),
                headers=info.get("http_headers") or {})


def decoder_command(ffmpeg, source, position):
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"]
    if source.get("headers"):
        headers = "".join(f"{key}: {value}\r\n" for key, value in source["headers"].items()
                          if "\n" not in str(key) + str(value) and "\r" not in str(key) + str(value))
        if headers:
            command += ["-headers", headers]
    command += ["-rw_timeout", "15000000", "-ss", f"{position:.3f}", "-i", source["url"],
                "-vn", "-sn", "-dn", "-ac", "2", "-ar", str(RATE), "-f", "s16le", "pipe:1"]
    return command


class Decoder:
    """Búfer acotado a unos 1,5 segundos; nunca guarda la canción en disco."""

    def __init__(self, ffmpeg, source, position):
        self.chunks = queue.Queue(maxsize=32)
        self.closed = threading.Event()
        flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
        self.process = subprocess.Popen(decoder_command(ffmpeg, source, position),
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **flags)
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def read(self):
        try:
            while not self.closed.is_set():
                block = self.process.stdout.read(FRAMES * FRAME_BYTES)
                if not self.put(block):
                    return
                if not block:
                    return
        finally:
            self.process.stdout.close()

    def put(self, block):
        while not self.closed.is_set():
            try:
                self.chunks.put(block, timeout=0.1)
                return True
            except queue.Full:
                pass
        return False

    def close(self):
        self.closed.set()
        if self.process.poll() is None:
            backend.stop_process(self.process)
        self.process.wait()
        self.thread.join(timeout=2)


def play_audio(source, commands, emit, *, ffmpeg, stream_factory=None, decoder_factory=Decoder):
    """Los comandos y las muestras se procesan juntos, sin trabajo de red en Tk."""
    if stream_factory is None:
        import sounddevice
        stream_factory = sounddevice.RawOutputStream
    duration = max(0, float(source.get("duration") or 0))
    position, paused = 0.0, False
    decoder = None
    last_report = 0
    state = "loading"
    try:
        with stream_factory(samplerate=RATE, blocksize=FRAMES, channels=2, dtype="int16") as output:
            # El contexto inicia el dispositivo. Abortar evita silencio en cola antes del primer bloque.
            output.abort()
            decoder = decoder_factory(ffmpeg, source, position)
            while True:
                try:
                    command = commands.get(timeout=0.02)
                except queue.Empty:
                    command = {}
                action = command.get("action")
                if action == "stop":
                    return
                if action in ("pause", "resume"):
                    paused = action == "pause"
                    if paused:
                        output.stop()
                        state = "paused"
                    else:
                        state = "loading"
                    emit(dict(status=state, position=position, duration=duration))
                elif action == "seek":
                    try:
                        target = float(command["position"])
                        if not math.isfinite(target):
                            continue
                    except (KeyError, ValueError, TypeError):
                        continue
                    position = min(max(0, target), max(0, duration - 0.05)) if duration else max(0, target)
                    output.abort()
                    decoder.close()
                    decoder = decoder_factory(ffmpeg, source, position)
                    state = "paused" if paused else "loading"
                    emit(dict(status=state, position=position, duration=duration))
                if paused or output.write_available < FRAMES:
                    continue
                try:
                    block = decoder.chunks.get_nowait()
                except queue.Empty:
                    if state != "loading":
                        state = "loading"
                        emit(dict(status=state, position=position, duration=duration))
                    continue
                if not block:
                    code = decoder.process.wait()
                    output.stop()
                    if code:
                        raise RuntimeError("No se pudo leer el audio. Comprueba la conexión o prueba otra coincidencia.")
                    emit(dict(status="ended", position=position, duration=duration))
                    return
                if not output.active:
                    output.start()
                output.write(block)
                position += len(block) / FRAME_BYTES / RATE
                if duration:
                    position = min(position, duration)
                now = time.monotonic()
                if state != "playing" or now - last_report >= 0.2:
                    state, last_report = "playing", now
                    emit(dict(status=state, position=position, duration=duration))
    finally:
        if decoder:
            decoder.close()


def run_audio(config):
    def emit(event):
        print(json.dumps(dict(event="audio", **event), ensure_ascii=True), flush=True)

    commands = queue.Queue()

    def read_commands():
        try:
            for line in sys.stdin:
                try:
                    data = json.loads(line)
                    if isinstance(data, dict):
                        commands.put(data)
                except ValueError:
                    pass
        finally:
            commands.put(dict(action="stop"))

    # El padre cierra stdin al recibir ended/error para terminar también este lector.
    # Así Python no finaliza con un hilo daemon bloqueado sobre su entrada de texto.
    threading.Thread(target=read_commands).start()
    try:
        ffmpeg = backend.find_ffmpeg()
        if not ffmpeg:
            raise RuntimeError("Falta FFmpeg. Coloca ffmpeg.exe junto al programa para escuchar el audio.")
        emit(dict(status="loading", position=0, duration=config.get("duration") or 0))
        source = resolve_audio(config)
        play_audio(source, commands, emit, ffmpeg=ffmpeg)
        return 0
    except Exception as exc:
        emit(dict(status="error", text=f"No se pudo reproducir el audio: {exc}"))
        return 1


class AudioJob:
    def __init__(self):
        self.events = queue.Queue()
        self.cancelled = threading.Event()
        self.process = None
        self.commands = queue.Queue()

    def command(self, action, **values):
        if not self.cancelled.is_set():
            self.commands.put(dict(action=action, **values))

    def cancel(self):
        self.cancelled.set()
        process = self.process
        if process:
            backend.stop_process(process)

    def run(self, config):
        process = None
        writer = None
        input_closed = False
        finished = threading.Event()

        def close_input():
            nonlocal input_closed
            finished.set()
            if writer:
                writer.join(timeout=1)
            if process and not input_closed:
                input_closed = True
                try:
                    process.stdin.close()
                except OSError:
                    pass
        try:
            if self.cancelled.is_set():
                return
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(backend.worker_command("--audio-worker"),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                encoding="utf-8", errors="replace", env={**os.environ, "PYTHONIOENCODING": "utf-8"}, **flags)
            self.process = process
            process.stdin.write(json.dumps(config) + "\n")
            process.stdin.flush()
            if self.cancelled.is_set():
                backend.stop_process(process)

            def write_commands():
                while not finished.is_set() and not self.cancelled.is_set():
                    try:
                        command = self.commands.get(timeout=0.1)
                        process.stdin.write(json.dumps(command) + "\n")
                        process.stdin.flush()
                    except queue.Empty:
                        continue
                    except (BrokenPipeError, OSError, ValueError):
                        return

            writer = threading.Thread(target=write_commands, daemon=True)
            writer.start()
            terminal = False
            for line in process.stdout:
                try:
                    event = json.loads(line)
                    if isinstance(event, dict) and event.get("event") == "audio":
                        terminal = terminal or event.get("status") in ("error", "ended")
                        self.events.put(event)
                        if terminal:
                            close_input()
                except ValueError:
                    pass
            process.wait()
            if not terminal and not self.cancelled.is_set():
                self.events.put(dict(event="audio", status="error", text="El reproductor de audio se cerró inesperadamente."))
        except Exception as exc:
            if not self.cancelled.is_set():
                self.events.put(dict(event="audio", status="error", text=f"No se pudo abrir el audio: {exc}"))
        finally:
            close_input()
            if process:
                if process.poll() is None:
                    backend.stop_process(process)
                    process.wait()
                process.stdout.close()
            self.process = None
            self.events.put(dict(event="audio_done"))
