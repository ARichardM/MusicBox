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


if __name__ == "__main__":
    unittest.main()
