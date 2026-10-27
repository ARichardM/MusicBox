"""Valida la coherencia de versión y que el parche avance exactamente una unidad."""
import argparse
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check(previous=None, allow_version_jump=False):
    patterns = {
        "apps/desktop/version.py": r'__version__\s*=\s*"([\d.]+)"',
        "apps/desktop/descargador_gui.py": r'APP_VERSION\s*=\s*"([\d.]+)"',
        "apps/android/app/build.gradle.kts": r'versionName\s*=\s*"([\d.]+)"',
        "packaging/windows/MusicBox.iss": r'#define AppVersion\s*"([\d.]+)"',
        "packaging/windows/version_info.txt": r"StringStruct\('(?:FileVersion|ProductVersion)', '([\d.]+)'\)",
        "CHANGELOG.md": r'^## ([\d.]+) ',
    }
    versions = {}
    for filename, pattern in patterns.items():
        values = re.findall(pattern, (ROOT / filename).read_text(encoding="utf-8"), re.MULTILINE)
        if not values:
            raise ValueError("No se encontró la versión en " + filename)
        versions[filename] = values[:1] if filename == "CHANGELOG.md" else values
    current = versions["apps/desktop/version.py"][0]
    if any(value != current for values in versions.values() for value in values):
        raise ValueError("Versiones distintas: " + repr(versions))
    parts = tuple(map(int, current.split(".")))
    if len(parts) != 3:
        raise ValueError("Usa una versión de tres números")
    metadata = (ROOT / "packaging/windows/version_info.txt").read_text(encoding="utf-8")
    for values in re.findall(r'(?:filevers|prodvers)=\((\d+,\s*\d+,\s*\d+,\s*\d+)\)', metadata):
        if tuple(map(int, values.split(","))) != (*parts, 0):
            raise ValueError("Los metadatos numéricos de Windows no coinciden")
    if previous is None:
        tags = subprocess.check_output(["git", "tag", "--list", "v*"], cwd=ROOT, text=True).splitlines()
        releases = [tuple(map(int, tag[1:].split("."))) for tag in tags if re.fullmatch(r'v\d+\.\d+\.\d+', tag)
                    and tag != "v" + current]
        previous = ".".join(map(str, max(releases))) if releases else None
    if previous and not allow_version_jump:
        major, minor, patch = map(int, previous.lstrip("v").split("."))
        if parts != (major, minor, patch + 1):
            raise ValueError(f"Después de {previous} corresponde {major}.{minor}.{patch + 1}; cualquier otro salto requiere una orden explícita")
    return current


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous")
    parser.add_argument("--allow-version-jump", action="store_true", help="Solo con una indicación explícita del usuario")
    args = parser.parse_args()
    print("Versión verificada:", check(args.previous, args.allow_version_jump))
