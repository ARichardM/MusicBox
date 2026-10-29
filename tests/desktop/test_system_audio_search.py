"""Regresiones de salida del sistema, búsqueda al escribir y carga del reproductor."""
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QSettings
from PySide6.QtMultimedia import QMediaPlayer
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication
import musicbox_desktop as module
from audio_preview import AudioPreview

APP = QApplication.instance() or QApplication([])


class SystemAudioSearchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        settings = QSettings(str(Path(self.directory.name) / "settings.ini"), QSettings.IniFormat)
        with patch.object(module, "FirebaseDesktopClient", return_value=SimpleNamespace(refresh_token="", configured=False)):
            self.bridge = module.MusicBoxBridge(settings=settings, load_startup=False)
        self.bridge._schedule_cloud_save = Mock()
        self.addCleanup(self.bridge.deleteLater)
        self.addCleanup(self.bridge._search_timer.stop)

    def test_output_changes_follow_default_without_restarting_player(self):
        preview = AudioPreview()
        self.addCleanup(preview.deleteLater)
        for target in (self.bridge, preview):
            with self.subTest(player=type(target).__name__):
                old, new = Mock(), Mock()
                old.id.return_value, new.id.return_value = b"old", b"new"
                new.isNull.return_value = False
                target._last_output_id = b"old"
                target._audio = Mock()
                target._audio.device.side_effect = [old, new]
                player = target._player = Mock()
                # Exercise Qt's connected notification, including an unrelated list
                # update and the temporary absence of any default output.
                with patch.object(module.QMediaDevices, "defaultAudioOutput", side_effect=[new, new]), \
                        patch.object(module.QMediaDevices, "audioOutputs", return_value=[old, new]):
                    for _ in range(2):
                        target._media_devices.audioOutputsChanged.emit()
                target._audio.setDevice.assert_called_once_with(new)
                target._audio.setVolume.assert_not_called()
                target._audio.setMuted.assert_not_called()
                player.setSource.assert_not_called()
                player.play.assert_not_called()
                player.pause.assert_not_called()

    def route_fixture(self, target):
        buds, speaker = Mock(), Mock()
        buds.id.return_value, speaker.id.return_value = b"buds", b"speaker"
        buds.isNull.return_value = speaker.isNull.return_value = False
        target._last_output_id = b"buds"
        target._play_when_ready = True
        target._audio = Mock()
        target._audio.device.return_value = buds
        player = target._player = Mock()
        player.playbackState.return_value = QMediaPlayer.PlayingState
        player.source.return_value = module.QUrl("https://media.example/audio")
        events = []
        player.pause.side_effect = lambda: events.append("pause")
        def route(device):
            events.append("route")
            target._audio.device.return_value = device
        target._audio.setDevice.side_effect = route
        return buds, speaker, player, events

    def test_disconnect_pauses_before_switching_to_speakers_and_reconnect_does_not_resume(self):
        preview = AudioPreview()
        self.addCleanup(preview.deleteLater)
        for target in (self.bridge, preview):
            with self.subTest(player=type(target).__name__):
                buds, speaker, player, events = self.route_fixture(target)
                if target is preview:
                    preview._state, preview._key = "playing", "sample"
                original_queue = list(self.bridge.queue)
                with patch.object(module.QMediaDevices, "defaultAudioOutput", return_value=speaker), \
                        patch.object(module.QMediaDevices, "audioOutputs", return_value=[speaker]):
                    target._media_devices.audioOutputsChanged.emit()
                self.assertEqual(events[:2], ["pause", "route"])
                self.assertFalse(target._play_when_ready)
                self.assertEqual(self.bridge.queue, original_queue)
                # Reconnecting may restore the system default but must leave playback paused.
                with patch.object(module.QMediaDevices, "defaultAudioOutput", return_value=buds), \
                        patch.object(module.QMediaDevices, "audioOutputs", return_value=[speaker, buds]):
                    target._media_devices.audioOutputsChanged.emit()
                self.assertFalse(target._play_when_ready)
                player.play.assert_not_called()
                player.setSource.assert_not_called()

    def test_disconnection_cancels_playback_after_pending_resolution_on_both_players(self):
        preview = AudioPreview()
        self.addCleanup(preview.deleteLater)
        for target in (self.bridge, preview):
            with self.subTest(player=type(target).__name__):
                buds, speaker, player, _ = self.route_fixture(target)
                player.playbackState.return_value = QMediaPlayer.StoppedState
                if target is preview:
                    preview._state, preview._key = "loading", "sample"
                else:
                    self.bridge._stream_resolving = True
                with patch.object(module.QMediaDevices, "defaultAudioOutput", return_value=speaker), \
                        patch.object(module.QMediaDevices, "audioOutputs", return_value=[speaker]):
                    target._media_devices.audioOutputsChanged.emit()
                if target is preview:
                    preview._on_resolved(preview._generation, "https://media.example/fresh", "")
                    self.assertEqual(preview.state, "paused")
                    preview._on_state(QMediaPlayer.PlayingState)  # A stale backend notification.
                else:
                    self.bridge._play_stream({"_stream_generation": self.bridge._stream_generation}, "https://media.example/fresh")
                    self.bridge._playback_state_changed(QMediaPlayer.PlayingState)
                player.play.assert_not_called()
                self.assertFalse(target._play_when_ready)
                player.pause.assert_called()

    def test_disappearing_all_outputs_pauses_and_recovery_of_devices_stays_paused(self):
        buds, speaker, player, _ = self.route_fixture(self.bridge)
        missing = Mock()
        missing.isNull.return_value = True
        with patch.object(module.QMediaDevices, "defaultAudioOutput", return_value=missing), \
                patch.object(module.QMediaDevices, "audioOutputs", return_value=[]):
            self.bridge._media_devices.audioOutputsChanged.emit()
        player.pause.assert_called_once()
        self.assertIsNone(self.bridge._last_output_id)
        with patch.object(module.QMediaDevices, "defaultAudioOutput", return_value=speaker), \
                patch.object(module.QMediaDevices, "audioOutputs", return_value=[speaker]):
            self.bridge._media_devices.audioOutputsChanged.emit()
        self.assertFalse(self.bridge._play_when_ready)
        player.play.assert_not_called()

    def test_typing_debounces_and_enter_does_not_repeat_the_search(self):
        with patch.object(module.youtube_streams, "search", return_value=[]) as search:
            self.bridge.scheduleSearch("lu")
            QTest.qWait(150)
            self.bridge.scheduleSearch("luis jara")
            QTest.qWait(300)
            search.assert_not_called()
            QTest.qWait(220)
            self.assertEqual(search.call_count, 1)
            self.assertEqual(search.call_args.args[0], "luis jara")
            self.bridge.scheduleSearch("otra canción")
            self.bridge.search("otra canción")
            QTest.qWait(550)
            self.assertEqual(search.call_count, 2)
            self.assertEqual(self.bridge._recent_queries[:2], ["otra canción", "luis jara"])

    def test_clearing_or_pasting_a_link_cancels_pending_live_search(self):
        with patch.object(module.youtube_streams, "search", return_value=[]) as search:
            for query in ("", "x", "https://www.youtube.com/watch?v=abcdefghijk", "youtu.be/abcdefghijk"):
                self.bridge.scheduleSearch("luis jara")
                self.bridge.scheduleSearch(query)
                QTest.qWait(510)
                self.assertFalse(self.bridge.busy)
            search.assert_not_called()

    def test_queued_results_cannot_replace_a_newer_query_or_cleared_search(self):
        self.bridge.scheduleSearch("antigua")
        generation = self.bridge._search_generation
        self.bridge.scheduleSearch("nueva")
        self.bridge._apply_search([{"id": "old"}], "error antiguo", generation)
        self.assertEqual(self.bridge.searchResults, [])
        self.assertTrue(self.bridge.busy)
        generation = self.bridge._search_generation
        self.bridge.clearSearch()
        self.bridge._apply_search([{"id": "old"}], "", generation)
        self.assertEqual(self.bridge.searchResults, [])
        self.assertFalse(self.bridge.busy)

    def test_loading_covers_resolution_buffering_success_and_failure(self):
        self.bridge._player = Mock()
        self.bridge._player.position.return_value = 0
        self.bridge._player.playbackState.return_value = QMediaPlayer.PlayingState
        self.bridge._player.mediaStatus.return_value = QMediaPlayer.NoMedia
        self.assertFalse(self.bridge.playerLoading)
        self.bridge._stream_resolving = True
        self.assertTrue(self.bridge.playerLoading)
        # A late response must not turn off the spinner for the current song.
        self.bridge._stream_generation = 2
        self.bridge._play_stream({"_stream_generation": 1}, "https://example.invalid/old")
        self.assertTrue(self.bridge.playerLoading)
        self.bridge._play_stream({"_stream_generation": 2, "_retry_attempt": 2}, "No disponible")
        self.assertFalse(self.bridge.playerLoading)
        for status in (QMediaPlayer.LoadingMedia, QMediaPlayer.StalledMedia, QMediaPlayer.BufferingMedia):
            self.bridge._player.mediaStatus.return_value = status
            self.assertTrue(self.bridge.playerLoading)
        self.bridge._player.mediaStatus.return_value = QMediaPlayer.BufferedMedia
        self.assertFalse(self.bridge.playerLoading)
        self.bridge._player.mediaStatus.return_value = QMediaPlayer.BufferingMedia
        self.bridge._player.playbackState.return_value = QMediaPlayer.PausedState
        self.assertFalse(self.bridge.playerLoading)  # The user can resume a paused source.


if __name__ == "__main__":
    unittest.main()
