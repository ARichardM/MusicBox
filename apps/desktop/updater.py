"""Búsqueda, descarga verificada e instalación de actualizaciones desde GitHub Releases."""

from __future__ import annotations

import hashlib
import json
import re
import urllib.request
from pathlib import Path

REPOSITORY = "ARichardM/MusicBox"
LATEST_URL = f"https://api.github.com/repos/{REPOSITORY}/releases/latest"
INSTALLER_NAME = "MusicBox-Windows-Setup.exe"
TIMEOUT = 12
_HEADERS = {"User-Agent": "MusicBox-Updater", "Accept": "application/vnd.github+json"}


def parse_version(value: str) -> tuple[int, ...]:
    """Convierte 'v2.3.0' o '2.3.0-pruebas' en (2, 3, 0)."""
    parts = re.findall(r"\d+", str(value).split("-")[0])
    return tuple(int(part) for part in parts[:4]) or (0,)


def is_newer(candidate: str, current: str) -> bool:
    return parse_version(candidate) > parse_version(current)


def clean_notes(body: str) -> list[str]:
    """Viñetas legibles del texto de la publicación; se omiten las secciones que son solo de Android."""
    notes = []
    include = True
    for line in str(body or "").splitlines():
        line = line.strip()
        if line.startswith("#"):
            heading = line.lstrip("#").strip().lower()
            include = not ("android" in heading and "windows" not in heading)
            continue
        if not include or not line or line.startswith(("**Full Changelog", "<")):
            continue
        line = re.sub(r"^[-*+]\s+", "", line)
        line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)
        line = re.sub(r"https?://\S+", "", line).replace("**", "").replace("`", "").strip(" -")
        if line:
            notes.append(line)
    return notes[:12]


def check_for_update(current: str, opener=urllib.request.urlopen) -> dict | None:
    """Devuelve los datos de la versión más reciente o None si ya está al día."""
    request = urllib.request.Request(LATEST_URL, headers=_HEADERS)
    with opener(request, timeout=TIMEOUT) as response:
        release = json.loads(response.read().decode("utf-8"))
    version = str(release.get("tag_name", "")).lstrip("vV")
    if not version or not is_newer(version, current):
        return None
    assets = {asset.get("name"): asset.get("browser_download_url") for asset in release.get("assets", [])}
    url = assets.get(INSTALLER_NAME)
    if not url:
        return None
    return {
        "version": version,
        "name": release.get("name") or f"MusicBox {version}",
        "date": str(release.get("published_at", ""))[:10],
        "notes": clean_notes(release.get("body", "")),
        "url": url,
        "checksumUrl": assets.get(INSTALLER_NAME + ".sha256", ""),
        "pageUrl": release.get("html_url", ""),
    }


def _fetch_text(url: str) -> str:
    with urllib.request.urlopen(urllib.request.Request(url, headers=_HEADERS), timeout=TIMEOUT) as response:
        return response.read().decode("utf-8", "replace")


def download_installer(info: dict, folder: Path, progress, cancelled) -> Path:
    """Descarga el instalador y comprueba su SHA-256 antes de entregarlo."""
    folder.mkdir(parents=True, exist_ok=True)
    expected = ""
    if info.get("checksumUrl"):
        match = re.search(r"\b[0-9a-fA-F]{64}\b", _fetch_text(info["checksumUrl"]))
        expected = match.group(0).lower() if match else ""
    if not expected:
        raise RuntimeError("La publicación no incluye una suma de verificación válida.")
    target = folder / f"MusicBox-{info['version']}-Setup.exe"
    partial = target.with_suffix(".part")
    digest = hashlib.sha256()
    request = urllib.request.Request(info["url"], headers=_HEADERS)
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response, open(partial, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        while chunk := response.read(256 * 1024):
            if cancelled():
                partial.unlink(missing_ok=True)
                raise InterruptedError("Descarga cancelada")
            out.write(chunk)
            digest.update(chunk)
            done += len(chunk)
            progress(done, total)
    if digest.hexdigest() != expected:
        partial.unlink(missing_ok=True)
        raise RuntimeError("El instalador descargado no coincide con su suma de verificación.")
    partial.replace(target)
    return target
