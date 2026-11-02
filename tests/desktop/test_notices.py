"""Los avisos de la app se cierran solos; pasar el puntero solo los pausa."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QSettings
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module

APP = QApplication.instance() or QApplication([])


class NoticeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            self.bridge = module.MusicBoxBridge(
                settings=QSettings(str(Path(self.temporary.name) / "settings.ini"), QSettings.IniFormat), load_startup=False)
        self.addCleanup(self.bridge.deleteLater)
        self.bridge._schedule_cloud_save = Mock()
        self.bridge._listening_timer.stop()
        self.bridge._device_timer.stop()
        self.bridge.NOTICE_INFO_MS = self.bridge.NOTICE_ERROR_MS = self.bridge.NOTICE_RETRY_MS = 80
        self.bridge.NOTICE_RESUME_MS = 60
        self.bridge.NOTICE_MAX_MS = 400

    def wait_until(self, condition, timeout=1500):
        for _ in range(timeout // 20):
            APP.processEvents()
            if condition(): return
            QTest.qWait(20)
        self.fail("El aviso no se cerró: " + str(self.bridge.notice))

    def test_info_and_error_notices_close_by_themselves(self):
        self.bridge._show_notice("Canción eliminada", kind="info")
        self.assertEqual(self.bridge.notice["text"], "Canción eliminada")
        self.wait_until(lambda: not self.bridge.notice)
        self.bridge._show_notice("No se pudo reproducir", retry=lambda: None)
        self.wait_until(lambda: not self.bridge.notice)

    def test_hover_pauses_and_leaving_closes_it_shortly_after(self):
        self.bridge._show_notice("Canción eliminada", kind="info")
        self.bridge.holdNotice(True)
        QTest.qWait(200)
        self.assertTrue(self.bridge.notice)
        self.bridge.holdNotice(False)
        self.wait_until(lambda: not self.bridge.notice, timeout=400)

    def test_a_stuck_hover_cannot_keep_the_notice_forever(self):
        self.bridge._show_notice("Canción eliminada", kind="info")
        self.bridge.holdNotice(True)
        self.wait_until(lambda: not self.bridge.notice)

    def test_a_new_notice_restarts_the_countdown(self):
        self.bridge._show_notice("Primero", kind="info")
        QTest.qWait(50)
        self.bridge._show_notice("Segundo", kind="info")
        QTest.qWait(50)
        self.assertEqual(self.bridge.notice["text"], "Segundo")
        self.wait_until(lambda: not self.bridge.notice)


if __name__ == "__main__":
    unittest.main()
