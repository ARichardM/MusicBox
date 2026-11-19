"""Portadas redondeadas visibles y una interfaz que no se satura con el vídeo y la barra de ondas (2.4.9)."""
import os
import re
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QSettings, QUrl
from PySide6.QtQuick import QQuickView
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication

import musicbox_desktop as module

APP = QApplication.instance() or QApplication([])
UI = Path(module.CODE_DIR) / "ui"

# Los efectos de capa (MultiEffect) no se dibujan con la plataforma «offscreen» de las demás pruebas:
# la portada se renderiza en un proceso aparte con la plataforma gráfica de Windows.
RENDER_SCRIPT = r'''
import os, sys
os.environ["QT_QPA_PLATFORM"] = "windows"
from PySide6.QtWidgets import QApplication
from PySide6.QtQuick import QQuickView
from PySide6.QtCore import QUrl
from PySide6.QtTest import QTest
app = QApplication([])
view = QQuickView()
view.setSource(QUrl.fromLocalFile(sys.argv[1]))
view.show()
QTest.qWait(800)
image = view.grabWindow()
ratio = image.devicePixelRatio()  # la captura viene con el escalado de pantalla
print(" ".join(image.pixelColor(int(x * ratio), int(y * ratio)).name() for x, y in ((5, 115), (60, 60), (11, 11))))
'''

COVER_SCENE = r'''import QtQuick
import QtQuick.Effects
Rectangle{width:120;height:120;color:"black"
%s
 Rectangle{x:0;y:110;width:10;height:10;color:"blue"}
 RoundedArtwork{x:10;y:10;width:100;height:100;radius:10;color:"red";Rectangle{anchors.fill:parent;color:"#00ff00"}}
}
'''


class RoundedArtworkTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "win32", "Necesita la plataforma gráfica de Windows")
    def test_rounded_cover_shows_its_image_and_clips_the_corners(self):
        """La máscara en línea de la 2.4.8 nunca se dibujaba y dejaba todas las portadas transparentes."""
        qml = (UI / "Main.qml").read_text(encoding="utf-8")
        match = re.search(r"^ component RoundedArtwork:Rectangle \{.*?^ \}", qml, re.S | re.M)
        self.assertIsNotNone(match, "RoundedArtwork no encontrado en Main.qml")
        with tempfile.TemporaryDirectory() as directory:
            scene = Path(directory) / "cover.qml"
            scene.write_text(COVER_SCENE % match.group(0), encoding="utf-8")
            script = Path(directory) / "render.py"
            script.write_text(RENDER_SCRIPT, encoding="utf-8")
            result = subprocess.run([sys.executable, str(script), str(scene)], capture_output=True, text=True, timeout=120)
        colours = (result.stdout.strip().splitlines() or [""])[-1].split()
        if len(colours) != 3 or colours[0] != "#0000ff":
            self.skipTest("Esta máquina no puede dibujar una ventana: " + (result.stderr[-300:] or result.stdout))
        self.assertEqual(colours[1], "#00ff00", "La portada debe verse")
        self.assertEqual(colours[2], "#000000", "Las esquinas quedan recortadas")


def show(source, directory):
    path = Path(directory) / "view.qml"
    path.write_text(source, encoding="utf-8")
    view = QQuickView()
    view.setSource(QUrl.fromLocalFile(str(path)))
    if view.status() != QQuickView.Ready:
        raise AssertionError("\n".join(error.toString() for error in view.errors()))
    view.show()
    return view


class WaveBarRepaintTests(unittest.TestCase):
    def test_playing_bar_repaints_only_when_its_progress_moves_a_column(self):
        """Repintar 120 barras por fotograma ocupaba el hilo de la interfaz y, con el vídeo, la dejaba «No responde»."""
        coarse = ",".join(str(0.2 + (i % 7) / 10) for i in range(120))
        source = """import QtQuick
import "%s"
Item{width:700;height:40
 property int paints:0
 property real pos:30000
 WaveBar{id:bar;anchors.fill:parent;duration:240000;position:pos;playing:true;active:true;seed:"a"
   wave:({coarse:[%s],tail:[.5,.4,.3],audibleEndMs:200000})}
 Timer{interval:250;repeat:true;running:true;onTriggered:pos+=250}
 Component.onCompleted:{for(var i=0;i<bar.children.length;i++)if(bar.children[i].requestPaint)
   bar.children[i].painted.connect(function(){paints++})}
}
""" % ((UI / "components").as_uri(), coarse)
        with tempfile.TemporaryDirectory() as directory:
            view = show(source, directory)
            QTest.qWait(2500)  # entrada y morph inicial
            root = view.rootObject()
            before = root.property("paints")
            QTest.qWait(3000)
            during = root.property("paints") - before
            view.close()
        # 3 s de una canción de 4 min avanzan ~1,5 columnas; antes eran ~180 repintados (uno por fotograma).
        self.assertLess(during, 12, f"La barra se repintó {during} veces en 3 s")


class FileCheckCacheTests(unittest.TestCase):
    def test_wave_properties_do_not_hit_the_disk_on_every_position_update(self):
        settings_dir = tempfile.TemporaryDirectory()
        self.addCleanup(settings_dir.cleanup)
        settings = QSettings(str(Path(settings_dir.name) / "settings.ini"), QSettings.IniFormat)
        with patch.object(module, "QSettings", return_value=settings), \
                patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                patch.object(module.MusicBoxBridge, "refresh"), patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                patch.object(module.MusicBoxBridge, "refreshForYou"):
            bridge = module.MusicBoxBridge()
        song = Path(settings_dir.name) / "song.mp3"
        song.write_bytes(b"0")
        item = {"path": str(song)}
        real = Path.is_file
        calls = []

        def counting(path):
            calls.append(str(path))
            return real(path)
        with patch.object(Path, "is_file", counting):
            for _ in range(200):
                self.assertEqual(bridge._wave_path(item), str(song))
            self.assertEqual(len(calls), 1, "Una sola consulta al disco mientras dura la memoria corta")
            with patch.object(module.time, "monotonic", return_value=time.monotonic() + bridge.FILE_CHECK_SECONDS + 1):
                bridge._wave_path(item)
        self.assertEqual(len(calls), 2, "Pasado el plazo se vuelve a comprobar")


class HiddenBrandMarkTests(unittest.TestCase):
    def test_brand_mark_only_animates_while_visible(self):
        """El logo de la pantalla de inicio de sesión, oculta tras entrar, se repintaba ~145 veces por segundo."""
        qml = (UI / "Main.qml").read_text(encoding="utf-8")
        match = re.search(r"readonly property bool live:([^;]+);", qml)
        self.assertIsNotNone(match)
        self.assertTrue(match.group(1).startswith("visible&&"), match.group(1))


if __name__ == "__main__":
    unittest.main()
