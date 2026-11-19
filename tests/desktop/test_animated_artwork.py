"""Artwork requests can finish after their Qt receiver has been destroyed."""
import unittest
from unittest.mock import MagicMock, patch

from PySide6.QtCore import QObject
from shiboken6 import delete

import musicbox_desktop as module


class AnimatedArtworkTests(unittest.TestCase):
    def test_pending_lookup_can_finish_after_bridge_is_destroyed(self):
        bridge = module.MusicBoxBridge.__new__(module.MusicBoxBridge)
        QObject.__init__(bridge)
        bridge._settings = MagicMock()
        bridge._settings.value.return_value = True
        with patch.object(module.threading, "Thread") as thread:
            bridge.requestAnimatedArtwork("key", "Song", "Artist", "Album", 180)
        worker = thread.call_args.kwargs["target"]
        delete(bridge)
        with patch.object(module.animated_artwork, "resolve", return_value=""):
            worker()
