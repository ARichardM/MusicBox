# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path
from PyInstaller.utils.hooks import collect_all, collect_submodules

repo = Path(SPECPATH).resolve().parents[1]
desktop = repo / "apps" / "desktop"

datas = [
    (str(desktop / "ui"), "apps/desktop/ui"),
    (str(desktop / "assets"), "apps/desktop/assets"),
    (str(repo / "desktop_oauth_client.json"), "."),
    (str(repo / "apps" / "android" / "app" / "google-services.json"), "apps/android/app"),
    (str(repo / "ffmpeg.exe"), "."),
]
binaries = []
hiddenimports = collect_submodules("yt_dlp")
for package in ("rapidocr", "onnxruntime", "mutagen", "google_auth_oauthlib"):
    package_datas, package_binaries, package_hidden = collect_all(package)
    datas += package_datas
    binaries += package_binaries
    hiddenimports += package_hidden

a = Analysis(
    [str(desktop / "musicbox_desktop.py")],
    pathex=[str(desktop)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="MusicBox",
    icon=str(desktop / "assets" / "logo_app.ico"),
    console=False,
    version=str(repo / "packaging" / "windows" / "version_info.txt"),
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="MusicBox",
)
