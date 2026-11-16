"""Manual refresh exposes progress, deduplicates taps and keeps data on failure."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from PySide6.QtCore import QSettings
from PySide6.QtTest import QSignalSpy
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module

APP = QApplication.instance() or QApplication([])


class LibraryRefreshTests(unittest.TestCase):
    def test_failed_manual_scan_preserves_rows_and_finishes_loading(self):
        with tempfile.TemporaryDirectory() as directory:
            settings=QSettings(str(Path(directory)/'preferences.ini'),QSettings.IniFormat)
            with patch.object(module,'FirebaseDesktopClient',return_value=SimpleNamespace(refresh_token='',configured=False)):
                bridge=module.MusicBoxBridge(settings=settings,load_startup=False)
            original=[{'path':'example.mp3','title':'Keep this song'}]
            bridge._library_all=list(original)
            events=QSignalSpy(bridge.libraryRefreshChanged)
            try:
                with patch.object(module.threading,'Thread') as thread, patch.object(bridge,'_scan_library_files',side_effect=PermissionError):
                    bridge.refreshLibrary()
                    self.assertTrue(bridge.libraryRefreshing)
                    bridge.refreshLibrary()
                    self.assertEqual(thread.call_count,1)
                    thread.call_args.kwargs['target']()
                self.assertFalse(bridge.libraryRefreshing)
                self.assertEqual(events.count(),2)
                self.assertEqual(bridge._library_all,original)
                self.assertTrue(bridge.notice['text'])
            finally:
                bridge._listening_timer.stop();bridge._device_timer.stop();bridge._playback_session_timer.stop()
