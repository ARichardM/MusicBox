"""Prueba optativa con red real: audio y reproducción automática sin tocar cuenta ni biblioteca.

Ejecutar con PYTHONPATH=apps/desktop. Usa preferencias y caché temporales y audio silenciado.
"""
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace
from PySide6.QtMultimedia import QMediaPlayer
from PySide6.QtCore import QSettings, QUrl, QCoreApplication, QEvent
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module


def run():
    app = QApplication.instance() or QApplication([])
    with tempfile.TemporaryDirectory(prefix="musicbox-online-") as folder:
        settings = QSettings(str(Path(folder) / "settings.ini"), QSettings.IniFormat)
        settings.setValue("muted", True)
        settings.setValue("repeatMode", 0)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)), \
                patch.object(module, "ROOT", Path(folder)), \
                patch.object(module.MusicBoxBridge, "refresh"), \
                patch.object(module.MusicBoxBridge, "checkForUpdate"), \
                patch.object(module.MusicBoxBridge, "refreshForYou"):
            bridge = module.MusicBoxBridge(settings=settings, load_startup=False)
            seed = "n0vo-mLGe6I"
            bridge.playOnline({"id": seed, "title": "No sabía", "url": "https://www.youtube.com/watch?v=" + seed})

            def wait(condition, timeout=90):
                end = time.monotonic() + timeout
                while time.monotonic() < end:
                    app.processEvents()
                    if condition(): return
                    time.sleep(.03)
                raise RuntimeError("Tiempo agotado. Estado: " + bridge._status + "; aviso: " + str(bridge._notice))

            try:
                wait(lambda: bridge.playing and bridge.playerPosition > 500 and len(bridge.queue) > 1)
                print("Audio online avanza y la cola recibió canciones similares:", len(bridge.queue), flush=True)
                queued = [row.get("id") for row in bridge.queue]
                position = bridge.playerPosition
                generation = bridge._stream_generation
                bridge._player_error(QMediaPlayer.FormatError, "Demuxing failed (prueba controlada)")
                wait(lambda: bridge._stream_generation > generation and not bridge.playerLoading
                     and bridge.playing and bridge.playerPosition >= position)
                assert bridge.currentTrack.get("id") == seed
                assert [row.get("id") for row in bridge.queue] == queued
                print("Error de demux recuperado: misma canción, posición y cola conservadas.", flush=True)
                wait(lambda: bridge.playerDuration > 2000)
                bridge.seek(bridge.playerDuration - 1000)
                wait(lambda: bridge.currentTrack.get("id") != seed and bridge.playing and bridge.playerPosition > 300)
                print("Reproducción automática avanzó y reproduce la siguiente canción.", flush=True)
            finally:
                bridge._listening_timer.stop()
                bridge._device_timer.stop()
                bridge._player.stop()
                bridge._reset_online_queue()
                bridge._player.setSource(QUrl())
                bridge._player.deleteLater()
                QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
                app.processEvents()


if __name__ == "__main__":
    run()
