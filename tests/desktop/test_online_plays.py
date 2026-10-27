"""Las reproducciones online también cuentan para el gusto del usuario (alimentan "Para ti")."""

import os
import unittest
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtMultimedia import QMediaPlayer
from PySide6.QtWidgets import QApplication

import musicbox_desktop as app_module
from click_stats import ClickStats

APP = QApplication.instance() or QApplication([])


def make_bridge():
    bridge = app_module.MusicBoxBridge.__new__(app_module.MusicBoxBridge)
    QObject.__init__(bridge)
    bridge._clicks_stats = ClickStats("pc")
    bridge._current = {}
    bridge._recorded_source = ""
    bridge._recent_paths = []
    bridge._path_ids = {}
    bridge.saved = 0
    bridge.cloud_saves = 0
    bridge._save_clicks = lambda: setattr(bridge, "saved", bridge.saved + 1)
    bridge._schedule_cloud_save = lambda: setattr(bridge, "cloud_saves", bridge.cloud_saves + 1)
    return bridge


class OnlinePlayTests(unittest.TestCase):
    def test_listening_clock_counts_progress_but_not_pauses_buffering_or_seeks(self):
        for position, was_playing, expected in [(2000, True, 1000), (2000, False, 0), (1000, True, 0), (61000, True, 0)]:
            with self.subTest(position=position, was_playing=was_playing):
                bridge = make_bridge()
                bridge._current = {"id": "clock", "title": "Clock"}
                bridge._path_ids = {}
                bridge._library_playlist = ""
                bridge._listening_started = True
                bridge._listening_sample = ("yt:clock", 10.0, 1000, was_playing, bridge._current, "")
                bridge._last_listening_save = 11.0
                bridge._player = SimpleNamespace(playbackState=lambda: QMediaPlayer.PlayingState, position=lambda: position)
                with patch("time.monotonic", return_value=11.0):
                    bridge._sample_listening()
                rows = bridge._clicks_stats.listening_rows()
                self.assertEqual(rows[0]["ms"] if rows else 0, expected)

    def test_an_online_play_is_counted_once_per_start(self):
        bridge = make_bridge()
        bridge._current = {"id": "abc123", "url": "https://www.youtube.com/watch?v=abc123", "title": "Canción"}
        bridge._playback_state_changed(QMediaPlayer.PlayingState)
        bridge._playback_state_changed(QMediaPlayer.PlayingState)
        self.assertEqual(bridge._clicks_stats.count("yt:abc123"), 1)
        self.assertEqual((bridge.saved, bridge.cloud_saves), (1, 1))
        self.assertEqual([key for key, _title, _score in bridge._clicks_stats.seeds(4)], ["yt:abc123"])

    def test_playing_again_after_a_new_start_counts_again(self):
        bridge = make_bridge()
        bridge._current = {"id": "abc123", "url": "u", "title": "Canción"}
        bridge._playback_state_changed(QMediaPlayer.PlayingState)
        bridge._recorded_source = ""  # lo que hace playOnline al iniciar de nuevo
        bridge._playback_state_changed(QMediaPlayer.PlayingState)
        self.assertEqual(bridge._clicks_stats.count("yt:abc123"), 2)

    def test_paused_or_without_id_is_not_counted(self):
        bridge = make_bridge()
        bridge._current = {"id": "abc123", "url": "u", "title": "Canción"}
        bridge._playback_state_changed(QMediaPlayer.PausedState)
        bridge._current = {"url": "u", "title": "Sin id"}
        bridge._playback_state_changed(QMediaPlayer.PlayingState)
        self.assertEqual(bridge._clicks_stats.total(), 0)


if __name__ == "__main__":
    unittest.main()
