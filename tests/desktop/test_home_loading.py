"""El Inicio muestra un esqueleto mientras no hay nada y lo retira cuando ya resolvieron las recomendaciones y la biblioteca."""

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject
from PySide6.QtWidgets import QApplication

import musicbox_desktop as app_module

APP = QApplication.instance() or QApplication([])


def make_bridge(loading):
    bridge = app_module.MusicBoxBridge.__new__(app_module.MusicBoxBridge)
    QObject.__init__(bridge)
    bridge._home_loading = loading
    bridge._home_ready = set()
    bridge.changes = 0
    bridge.homeLoadingChanged.connect(lambda: setattr(bridge, "changes", bridge.changes + 1))
    return bridge


class HomeLoadingTests(unittest.TestCase):
    def test_stays_until_both_recommendations_and_library_resolved(self):
        bridge = make_bridge(True)
        bridge._mark_home_ready("forYou")
        self.assertTrue(bridge.homeLoading)
        bridge._mark_home_ready("library")
        self.assertFalse(bridge.homeLoading)
        self.assertEqual(bridge.changes, 1)

    def test_order_does_not_matter_and_repeats_are_harmless(self):
        bridge = make_bridge(True)
        bridge._mark_home_ready("library")
        bridge._mark_home_ready("library")
        self.assertTrue(bridge.homeLoading)
        bridge._mark_home_ready("forYou")
        bridge._mark_home_ready("forYou")
        self.assertFalse(bridge.homeLoading)
        self.assertEqual(bridge.changes, 1)

    def test_the_time_limit_always_removes_it(self):
        bridge = make_bridge(True)
        bridge._finish_home_loading()
        bridge._finish_home_loading()
        self.assertFalse(bridge.homeLoading)
        self.assertEqual(bridge.changes, 1)

    def test_nothing_happens_when_the_home_already_has_content(self):
        bridge = make_bridge(False)
        bridge._mark_home_ready("forYou")
        bridge._mark_home_ready("library")
        self.assertFalse(bridge.homeLoading)
        self.assertEqual(bridge.changes, 0)

    def test_home_qml_uses_the_skeleton_and_fades_the_real_sections(self):
        source = (app_module.CODE_DIR / "ui" / "Main.qml").read_text(encoding="utf-8")
        self.assertIn("Mb.HomeSkeleton", source)
        self.assertEqual(source.count("&&!musicbox.homeLoading;opacity:musicbox.homeLoading?0:1"), 5)


if __name__ == "__main__":
    unittest.main()
