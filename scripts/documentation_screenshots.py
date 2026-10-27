"""Capturas reales para el README con biblioteca pública de muestra y sin cuenta.

No carga preferencias del usuario, escanea música ni consulta servicios de red.
Los marcos son SVG nativos con la captura PNG incrustada (sin recursos externos).
"""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET
from types import SimpleNamespace
from unittest.mock import patch
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build" / "documentation-screenshots"
OUTPUT = ROOT / "docs" / "images" / "screenshots"
FIXTURE = ROOT / "scripts" / "fixtures" / "documentation-library.json"
os.environ.setdefault("QT_QPA_PLATFORM", "windows" if os.name == "nt" else "offscreen")
sys.path.insert(0, str(ROOT / "apps" / "desktop"))

from PySide6.QtCore import QByteArray, QCoreApplication, QEvent, Property, QSettings, Qt, QUrl
from PySide6.QtGui import QFont, QFontDatabase, QImage, QPainter
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtWidgets import QApplication


def album_art(song):
    """Arte vectorial propio para la demostración, no una portada oficial."""
    dark, accent, light = song["colors"]
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="640" height="640" viewBox="0 0 640 640">
      <defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="{dark}"/><stop offset="1" stop-color="{accent}"/></linearGradient>
      <linearGradient id="orb" x2="0.8" y2="1"><stop stop-color="{light}"/><stop offset="1" stop-color="{accent}"/></linearGradient></defs>
      <rect width="640" height="640" fill="url(#bg)"/>
      <circle cx="416" cy="239" r="185" fill="url(#orb)"/>
      <circle cx="416" cy="239" r="124" fill="none" stroke="{dark}" stroke-width="2" opacity=".2"/>
      <path d="M-70 470 Q160 190 710 420 L710 700H-70Z" fill="{dark}" opacity=".75"/>
      <path d="M-70 520 Q210 240 710 490" fill="none" stroke="{light}" stroke-width="3" opacity=".75"/>
      <path d="M-70 542 Q210 262 710 512" fill="none" stroke="{light}" stroke-width="1" opacity=".4"/>
      <text x="42" y="60" font-family="Arial,sans-serif" font-size="14" letter-spacing="6" fill="white" opacity=".8">MUSICBOX SESSIONS</text>
      <text x="42" y="566" font-family="Arial,sans-serif" font-size="35" font-weight="700" letter-spacing="2" fill="white">{escape(song['word'])}</text>
      <text x="44" y="600" font-family="Arial,sans-serif" font-size="17" letter-spacing="3" fill="white" opacity=".7">{escape(song['artist'].upper())}</text>
    </svg>'''


def prepare():
    WORK.mkdir(parents=True, exist_ok=True)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    for i, song in enumerate(data["songs"]):
        image = QImage(640, 640, QImage.Format_ARGB32_Premultiplied)
        image.fill(Qt.transparent)
        painter = QPainter(image)
        QSvgRenderer(QByteArray(album_art(song).encode())).render(painter)
        painter.end()
        if not image.save(str(WORK / f"cover-{i}.png")):
            raise RuntimeError("No se pudo generar la portada de muestra")
    (WORK / "library.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def framed(source, name, title, phone=False):
    """Presentación SVG: imagen real intacta, recorte redondeado y marco vectorial."""
    png = QImage(str(source))
    if png.isNull():
        raise ValueError(f"Captura inválida: {source}")
    payload = base64.b64encode(source.read_bytes()).decode("ascii")
    if phone:
        width, screen_x, screen_y, screen_width = 440, 24, 51, 392
        screen_height = round(screen_width * png.height() / png.width(), 2)
        height = screen_y + screen_height + 36
        shell = f'''<rect x="8" y="3" width="424" height="{height-6}" rx="52" fill="#090B10" stroke="#667187" stroke-width="2"/>
          <rect x="17" y="12" width="406" height="{height-24}" rx="44" fill="#000" stroke="#242A35"/>
          <rect x="180" y="23" width="80" height="6" rx="3" fill="#313744"/>
          <circle cx="278" cy="26" r="4" fill="#25334B"/>
          <text x="43" y="40" fill="#F4F7FD" font-family="Arial,sans-serif" font-size="12" font-weight="700">9:41</text>
          <path d="M356 30v6m5-9v9m5-12v12" stroke="#F4F7FD" stroke-width="2" stroke-linecap="round"/>
          <rect x="376" y="25" width="20" height="11" rx="3" fill="none" stroke="#F4F7FD"/>
          <rect x="379" y="28" width="14" height="5" rx="1" fill="#F4F7FD"/>
          <rect x="165" y="{height-19}" width="110" height="4" rx="2" fill="#CFD7E5"/>
          <rect x="432" y="160" width="4" height="64" rx="2" fill="#576174"/>
          <rect x="4" y="126" width="4" height="43" rx="2" fill="#576174"/>'''
        radius = 12
    else:
        width, screen_x, screen_y, screen_width = 1456, 16, 54, 1424
        screen_height = round(screen_width * png.height() / png.width(), 2)
        height = screen_y + screen_height + 20
        shell = f'''<rect x="2" y="2" width="1452" height="{height-4}" rx="26" fill="#1B202B" stroke="#505D73" stroke-width="2"/>
          <rect x="11" y="11" width="1434" height="{height-22}" rx="20" fill="#10131B"/>
          <rect x="25" y="21" width="21" height="21" rx="6" fill="#0A84FF"/>
          <path d="M30 35V27l5 5 5-5v8" fill="none" stroke="white" stroke-width="1.5" stroke-linejoin="round"/>
          <path d="M1320 31h13m35-6h11v11h-11Zm51 0 11 11m0-11-11 11" fill="none" stroke="#B5C1D4" stroke-width="1.2"/>
          <text x="728" y="36" text-anchor="middle" fill="#CCD5E4" font-family="Arial,sans-serif" font-size="14" letter-spacing="1">MusicBox · Windows</text>'''
        radius = 12
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{escape(title)}">
      <title>{escape(title)}</title><desc>Interfaz real de MusicBox. Biblioteca de demostración, sin datos personales. Marco vectorial redondeado.</desc>
      <defs><clipPath id="screen"><rect x="{screen_x}" y="{screen_y}" width="{screen_width}" height="{screen_height}" rx="{radius}"/></clipPath></defs>
      {shell}
      <image x="{screen_x}" y="{screen_y}" width="{screen_width}" height="{screen_height}" clip-path="url(#screen)" xlink:href="data:image/png;base64,{payload}"/>
    </svg>'''
    destination = OUTPUT / f"{name}.svg"
    destination.write_text(svg, encoding="utf-8")
    print(f"Generada: {destination.relative_to(ROOT)} ({destination.stat().st_size:,} bytes)")


def desktop(data):
    import musicbox_desktop as module
    from downloads_controller import DownloadsController
    from importer_controller import ImporterImageProvider
    from historial import DownloadHistory

    class DocumentationBridge(module.MusicBoxBridge):
        @Property(int, notify=module.MusicBoxBridge.playerChanged)
        def playerPosition(self): return 87000

        @Property(int, notify=module.MusicBoxBridge.playerChanged)
        def playerDuration(self): return 244000

    settings = QSettings(str(WORK / "desktop-demo.ini"), QSettings.IniFormat)
    settings.clear()
    settings.setValue("language", "es")
    settings.setValue("darkMode", True)
    with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
        bridge = DocumentationBridge(settings=settings, load_startup=False)
    bridge._device_timer.stop()
    bridge._listening_timer.stop()
    bridge._user = {"uid":"documentation-offline", "displayName":"MusicBox Demo"}
    rows = [dict(song, path=f"demo://{song['id']}", url="", thumbnail=QUrl.fromLocalFile(str(WORK / f"cover-{i}.png")).toString(),
                 subtitle=f"{song['artist']} · {song['album']}", downloaded=True, favorite=i in (0,1,3,6))
            for i, song in enumerate(data["songs"])]
    bridge._library = list(rows)
    bridge._library_all = list(rows)
    bridge._recent_paths = [row["path"] for row in rows[2:8]]
    bridge._online_tracks = {row["id"]: dict(row) for row in rows}
    bridge._playlists = {name:[rows[i]["path"] for i in indices] for name,indices in data["playlists"].items()}
    bridge._favorites = set(bridge._playlists["Favoritos"])
    bridge._current = dict(rows[0])
    bridge._queue = list(rows)
    bridge._queue_index = 0
    bridge._queue_original = list(rows)
    bridge._for_you = {"songs":[dict(row, downloaded=False) for row in rows[:6]], "artists":[], "mode":"personalized"}
    engine = QQmlApplicationEngine()
    errors = []
    engine.warnings.connect(lambda warnings: errors.extend(warning.toString() for warning in warnings))
    bridge.attach_engine(engine)
    engine.rootContext().setContextProperty("musicbox", bridge)
    downloads = DownloadsController(bridge)
    downloads._history_store = DownloadHistory(WORK / "history.sqlite3")
    bridge.downloads = downloads
    engine.rootContext().setContextProperty("downloads", downloads)
    engine.addImageProvider("importer", ImporterImageProvider(downloads.importer))
    engine.load(QUrl.fromLocalFile(str(module.CODE_DIR / "ui" / "Main.qml")))
    if not engine.rootObjects():
        raise RuntimeError("\n".join(errors))
    window = engine.rootObjects()[0]
    window.setWidth(1424)
    window.setHeight(900)
    window.setProperty("expanded", True)

    def capture(name):
        end = time.monotonic() + 3
        while time.monotonic() < end:
            QApplication.processEvents()
            time.sleep(.02)
        raw = WORK / f"{name}.png"
        if not window.grabWindow().save(str(raw)):
            raise RuntimeError("No se pudo capturar QML")
        framed(raw, name, "MusicBox para Windows · " + ("Inicio" if name.endswith("home") else "Playlists"))

    capture("desktop-home")
    window.setProperty("page", 1)
    window.setProperty("libraryFilter", "playlists")
    bridge._library_filter = "playlists"
    bridge.playlistsChanged.emit()
    capture("desktop-playlists")
    window.setVisible(False)
    engine.deleteLater()
    QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
    if errors:
        raise RuntimeError("\n".join(errors))


def verify():
    """Comprueba archivos autocontenidos y genera vistas previas para revisión visual."""
    for name in ("android-library", "android-player", "desktop-home", "desktop-playlists"):
        source = OUTPUT / f"{name}.svg"
        root = ET.parse(source).getroot()
        images = root.findall("{http://www.w3.org/2000/svg}image")
        assert len(images) == 1, name
        href = images[0].get("{http://www.w3.org/1999/xlink}href", "")
        assert href.startswith("data:image/png;base64,"), name
        png = QImage.fromData(base64.b64decode(href.split(",", 1)[1], validate=True))
        assert not png.isNull() and png.width() >= 1000, name
        assert not png.textKeys(), f"Metadatos PNG inesperados en {name}"
        for element in root.iter():
            assert not element.tag.endswith(("script", "foreignObject")), name
            assert not any(key.lower().startswith("on") for key in element.attrib), name
        renderer = QSvgRenderer(str(source))
        assert renderer.isValid(), name
        image = QImage(renderer.defaultSize(), QImage.Format_ARGB32_Premultiplied)
        image.fill(Qt.transparent)
        painter = QPainter(image)
        renderer.render(painter)
        painter.end()
        assert image.save(str(WORK / f"{name}-framed-preview.png")), name
        print(f"Verificada: {name}, captura {png.width()}×{png.height()}, SVG sin dependencias externas")
    (WORK / "gallery-preview.html").write_text('''<!doctype html><meta charset="utf-8">
      <title>MusicBox · Galería de demostración</title>
      <style>body{margin:0;padding:36px;background:#0d1117;color:#e6edf3;font:16px 'Segoe UI',sans-serif}
      main{max-width:960px;margin:auto}h1{font-size:26px}p{color:#9caabc}section{display:flex;justify-content:center;gap:36px}
      section img{width:310px;height:auto}main>img{width:100%;margin:20px 0}</style>
      <main><h1>Así se ve MusicBox</h1><p>Interfaz real · Biblioteca de demostración · Sin datos personales</p>
      <section><img src="../../docs/images/screenshots/android-library.svg"><img src="../../docs/images/screenshots/android-player.svg"></section>
      <h2>En tu escritorio</h2><img src="../../docs/images/screenshots/desktop-home.svg"><img src="../../docs/images/screenshots/desktop-playlists.svg"></main>''', encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-android", action="store_true")
    parser.add_argument("--frame-android", type=Path, help="Carpeta con android-library.png y android-player.png")
    parser.add_argument("--verify", action="store_true", help="Verificar las cuatro capturas y generar vistas previas")
    args = parser.parse_args()
    app = QApplication.instance() or QApplication([])
    system_font = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / "segoeui.ttf"
    if system_font.is_file():
        QFontDatabase.addApplicationFont(str(system_font))
        app.setFont(QFont("Segoe UI", 10))
    QQuickStyle.setStyle("Material")
    if args.verify:
        WORK.mkdir(parents=True, exist_ok=True)
        verify()
        return
    data = prepare()
    if args.frame_android:
        for name in ("android-library", "android-player"):
            framed(args.frame_android / f"{name}.png", name, "MusicBox para Android · " + ("Biblioteca" if name.endswith("library") else "Reproductor"), phone=True)
    elif not args.prepare_android:
        desktop(data)


if __name__ == "__main__":
    main()
