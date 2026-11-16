"""Relieve de volumen de una canción y dónde empieza y termina el sonido de verdad.

Equivale a WaveformStore de Android y a MixEnv de la web: el relieve alimenta la barra de progreso del crossfade y los
límites audibles permiten mezclar sobre sonido, no sobre el silencio del inicio o del final de la pista.
"""
from __future__ import annotations

import array
import hashlib
import json
import math
import os
import subprocess
import wave
from pathlib import Path

RATE = 8000
COARSE = 120
FINE = 128
FINE_MS = 125
MAX_SECONDS = 20 * 60


def _levels_from_samples(samples, rate: int) -> list[float]:
    """Volumen (RMS, 0..1) por tramos de 125 ms."""
    size = max(1, rate * FINE_MS // 1000)
    out = []
    for start in range(0, len(samples), size):
        chunk = samples[start:start + size:2]
        if not chunk:
            break
        out.append(math.sqrt(sum(x * x for x in chunk) / len(chunk)) / 32768.0)
    return out


def _read_pcm(path: str, ffmpeg: str | None):
    """Muestras mono de 16 bits y su frecuencia; WAV se lee directo, el resto con FFmpeg."""
    if path.lower().endswith(".wav"):
        try:
            with wave.open(path, "rb") as source:
                channels, rate, width = source.getnchannels(), source.getframerate(), source.getsampwidth()
                if width == 2 and source.getnframes() / max(1, rate) <= MAX_SECONDS:
                    raw = array.array("h")
                    raw.frombytes(source.readframes(source.getnframes()))
                    if channels > 1:
                        raw = array.array("h", raw[::channels])
                    return raw, rate
        except Exception:
            pass
    if not ffmpeg:
        return None, RATE
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    result = subprocess.run([ffmpeg, "-v", "error", "-i", path, "-vn", "-ac", "1", "-ar", str(RATE), "-t", str(MAX_SECONDS),
                             "-f", "s16le", "-"], capture_output=True, timeout=180, creationflags=flags)
    if result.returncode != 0 or not result.stdout:
        return None, RATE
    raw = array.array("h")
    raw.frombytes(result.stdout[: len(result.stdout) // 2 * 2])
    return raw, RATE


def _normalise(values, peak):
    return [max(.08, min(1.0, (v / peak) ** .62)) for v in values]


def analyse_levels(levels: list[float], duration_ms: float) -> dict | None:
    count = len(levels)
    if count < 8:
        return None
    coarse = []
    for i in range(COARSE):
        start = i * count // COARSE
        stop = max(start + 1, (i + 1) * count // COARSE)
        window = levels[start:stop]
        coarse.append(math.sqrt(sum(v * v for v in window) / len(window)))
    peak = max(max(levels), 0.0001)
    silent = peak < .003
    threshold = max(.004, peak * .03)
    first = 0
    while first < count - 1 and levels[first] < threshold:
        first += 1
    last = count - 1
    while last > 0 and levels[last] < threshold:
        last -= 1
    audible_start = 0 if first * FINE_MS < 300 else first * FINE_MS
    audible_end = duration_ms if last >= count - 2 else min(duration_ms, (last + 1) * FINE_MS)
    if silent or audible_end < duration_ms / 4:
        audible_start, audible_end, first, last = 0, duration_ms, 0, count - 1
    head, tail = [], []
    for k in range(FINE):
        h, t = first + k, last - (FINE - 1 - k)
        head.append(levels[h] if h < count else 0.0)
        tail.append(levels[t] if 0 <= t < count else 0.0)
    return {"coarse": _normalise(coarse, peak), "head": _normalise(head, peak), "tail": _normalise(tail, peak),
            "durationMs": duration_ms, "audibleStartMs": audible_start, "audibleEndMs": audible_end}


def analyse(path: str, ffmpeg: str | None) -> dict | None:
    samples, rate = _read_pcm(path, ffmpeg)
    if not samples:
        return None
    duration_ms = len(samples) * 1000.0 / rate
    return analyse_levels(_levels_from_samples(samples, rate), duration_ms)


def cache_key(path: str) -> str:
    try:
        stat = os.stat(path)
        text = f"{path}|{stat.st_size}|{int(stat.st_mtime)}"
    except OSError:
        text = path
    return hashlib.sha1(text.encode("utf-8", "ignore")).hexdigest()


def load_or_analyse(path: str, folder: Path, ffmpeg: str | None) -> dict | None:
    folder.mkdir(parents=True, exist_ok=True)
    stored = folder / (cache_key(path) + ".json")
    try:
        return json.loads(stored.read_text(encoding="utf-8"))
    except Exception:
        pass
    envelope = analyse(path, ffmpeg)
    if envelope:
        try:
            stored.write_text(json.dumps(envelope), encoding="utf-8")
        except OSError:
            pass
    return envelope
