"""La reproducción automática agrega canciones parecidas al final de la cola online, sin repetir ni mezclar videos largos."""

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication

import musicbox_desktop as app_module
from recommendations import AUTOPLAY_BATCH, pick_autoplay

APP = QApplication.instance() or QApplication([])


def track(video_id, duration=200):
    return {"id": video_id, "title": f"T{video_id}", "duration": duration, "url": f"https://www.youtube.com/watch?v={video_id}"}


class FakeSettings:
    def __init__(self, enabled=True):
        self.values = {"autoplay": enabled}

    def value(self, key, default=None, _type=None):
        return self.values.get(key, default)

    def setValue(self, key, value):
        self.values[key] = value


class PickTests(unittest.TestCase):
    def test_skips_queued_repeated_and_long_ones(self):
        related = [track("a"), track("b"), track("a"), track("mix", 3600), track("c")]
        self.assertEqual([item["id"] for item in pick_autoplay(related, ["b"])], ["a", "c"])

    def test_never_adds_more_than_one_batch(self):
        self.assertEqual(len(pick_autoplay([track(f"v{i}") for i in range(40)])), AUTOPLAY_BATCH)

    def test_nothing_related_adds_nothing(self):
        self.assertEqual(pick_autoplay(None), [])


class BridgeTests(unittest.TestCase):
    def make(self, enabled=True, queue=None, index=-1):
        bridge = app_module.MusicBoxBridge.__new__(app_module.MusicBoxBridge)
        QObject.__init__(bridge)
        bridge._settings = FakeSettings(enabled)
        bridge._online_queue, bridge._online_index = list(queue or []), index
        bridge._online_generation = bridge._stream_generation = 0
        bridge._online_waiting = False
        bridge._online_original = []
        bridge._queue, bridge._queue_index = [], -1
        bridge._repeat_mode = 0
        bridge._shuffle = False
        bridge._autoplay_tried, bridge._autoplay_loading = "", False
        bridge._current = {}
        bridge.requested = []
        bridge._related_songs = lambda video_id: bridge.requested.append(video_id) or [track("x"), track("y")]
        bridge._autoplayReady.connect(bridge._apply_autoplay)
        return bridge

    def wait(self, bridge):
        import time
        deadline = time.time() + 3
        while bridge._autoplay_loading and time.time() < deadline:
            APP.processEvents()
            time.sleep(0.01)

    def test_a_single_song_gets_related_songs_queued_after_it(self):
        bridge = self.make()
        bridge._current = track("seed")
        bridge._maybe_autoplay(bridge._current)
        self.wait(bridge)
        self.assertEqual([item["id"] for item in bridge._online_queue], ["seed", "x", "y"])
        self.assertEqual(bridge._online_index, 0)

    def test_nothing_is_fetched_while_more_songs_are_still_queued(self):
        bridge = self.make(queue=[track("a"), track("b")], index=0)
        bridge._current = track("a")
        bridge._maybe_autoplay(bridge._current)
        self.assertEqual(bridge.requested, [])

    def test_turned_off_does_nothing(self):
        bridge = self.make(enabled=False)
        bridge._current = track("seed")
        bridge._maybe_autoplay(bridge._current)
        self.assertEqual(bridge.requested, [])
        self.assertEqual(bridge._online_queue, [])

    def test_the_same_song_is_not_asked_twice(self):
        bridge = self.make()
        bridge._current = track("seed")
        bridge._maybe_autoplay(bridge._current)
        self.wait(bridge)
        bridge._maybe_autoplay(bridge._current)
        self.assertEqual(bridge.requested, ["seed"])

    def test_online_queue_is_exposed_and_editing_keeps_the_current_song(self):
        bridge = self.make(queue=[track("a"), track("b"), track("c")], index=1)
        bridge._current = track("b")
        self.assertEqual([row["id"] for row in bridge.queue], ["a", "b", "c"])
        bridge.moveQueueItem(1, 0)
        self.assertEqual(bridge.queueIndex, 0)
        bridge.removeQueueItem(0)  # La canción actual no se elimina desde ×.
        self.assertEqual(len(bridge.queue), 3)
        bridge.removeQueueItem(1)
        self.assertEqual([row["id"] for row in bridge.queue], ["b", "c"])
        bridge.clearQueue()
        self.assertEqual([row["id"] for row in bridge.queue], ["b"])
        self.assertEqual(bridge.queueIndex, 0)

    def test_clearing_the_queue_discards_a_late_autoplay_response(self):
        bridge = self.make(queue=[track("seed")], index=0)
        bridge._current = track("seed")
        generation = bridge._online_generation
        bridge.clearQueue()
        bridge._apply_autoplay("seed", [track("late")], generation)
        self.assertEqual([row["id"] for row in bridge.queue], ["seed"])

    def test_end_of_media_waits_for_related_songs_and_then_advances(self):
        bridge = self.make(queue=[track("seed")], index=0)
        bridge._current = track("seed")
        bridge._autoplay_loading = True
        played = []
        bridge._play_online = lambda row: played.append(row["id"])
        self.assertTrue(bridge._advance_online(1))
        self.assertTrue(bridge._online_waiting)
        bridge._apply_autoplay("seed", [track("next")], 0)
        self.assertEqual(played, ["next"])

    def test_shuffle_can_be_restored_after_autoplay_adds_new_songs(self):
        bridge = self.make(queue=[track("a"), track("b"), track("c")], index=1)
        bridge._current = track("b")
        bridge.toggleShuffle()
        self.assertEqual(bridge.queueIndex, 0)
        bridge._online_queue.append(track("d"))
        bridge.toggleShuffle()
        self.assertEqual([row["id"] for row in bridge.queue], ["a", "b", "c", "d"])
        self.assertEqual(bridge.queueIndex, 1)

    def test_repeat_all_wraps_online_and_repeat_one_does_not_advance(self):
        from types import SimpleNamespace
        from PySide6.QtMultimedia import QMediaPlayer
        bridge = self.make(queue=[track("a"), track("b")], index=1)
        bridge._current = track("b")
        bridge._repeat_mode = 1
        played = []
        bridge._play_online = lambda row: played.append(row["id"])
        bridge._advance_online(1)
        self.assertEqual(played, ["a"])
        bridge._repeat_mode = 2
        calls = []
        bridge._player = SimpleNamespace(mediaStatus=lambda: QMediaPlayer.EndOfMedia,
                                        setPosition=lambda position: calls.append(position), play=lambda: calls.append("play"))
        bridge._media_status()
        self.assertEqual(calls, [0, "play"])
        self.assertEqual(played, ["a"])

    def test_a_late_stream_does_not_replace_the_current_song(self):
        bridge = self.make()
        bridge._stream_generation = 2
        bridge._current = track("current")
        bridge._play_stream(dict(track("old"), _stream_generation=1), "https://audio/old")
        self.assertEqual(bridge._current["id"], "current")

    def test_failed_radio_keeps_pending_advance_and_retry_clears_the_error(self):
        bridge = self.make(queue=[track("seed")], index=0)
        bridge._current = track("seed")
        bridge._autoplay_attempts = 1
        bridge._online_waiting = True
        bridge._apply_autoplay("seed", [], 0)
        self.assertTrue(bridge._online_waiting)
        self.assertNotEqual(bridge.autoplayError, "")
        played = []
        bridge._play_online = lambda row: played.append(row["id"])
        bridge._apply_autoplay("seed", [track("next")], 0)
        self.assertEqual(played, ["next"])
        self.assertEqual(bridge.autoplayError, "")

    def test_exhausted_radio_can_be_retried_without_replacing_the_queue(self):
        bridge = self.make(queue=[track("seed")], index=0)
        bridge._current = track("seed")
        bridge._autoplay_tried = "seed"
        bridge._autoplay_attempts = 3
        bridge._apply_autoplay("seed", [], 0)
        self.assertNotEqual(bridge.autoplayError, "")
        bridge.retryAutoplay()
        self.wait(bridge)
        self.assertEqual([row["id"] for row in bridge.queue], ["seed", "x", "y"])
        self.assertEqual(bridge.autoplayError, "")


if __name__ == "__main__":
    unittest.main()
