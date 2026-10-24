"""Rutas estables para desarrollo y para la instalación empaquetada."""

from __future__ import annotations

import os
from pathlib import Path
import sys


FROZEN = bool(getattr(sys, "frozen", False))
if FROZEN:
    RESOURCE_ROOT = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    CODE_DIR = RESOURCE_ROOT / "apps" / "desktop"
    DATA_ROOT = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "MusicBox"
else:
    CODE_DIR = Path(__file__).resolve().parent
    RESOURCE_ROOT = CODE_DIR.parent.parent
    DATA_ROOT = RESOURCE_ROOT

DATA_ROOT.mkdir(parents=True, exist_ok=True)
DEFAULT_DOWNLOAD_DIR = (Path.home() / "Music" / "MusicBox") if FROZEN else DATA_ROOT / "Descargas"


def worker_command(argument: str) -> list[str]:
    """Comando del trabajador compatible con Python y el ejecutable congelado."""
    if FROZEN:
        return [sys.executable, argument]
    interpreter = Path(sys.executable)
    if interpreter.name.lower() == "pythonw.exe":
        interpreter = interpreter.with_name("python.exe")
    return [str(interpreter), "-u", str(CODE_DIR / "descargador_gui.py"), argument]
