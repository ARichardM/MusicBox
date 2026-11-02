"""La ventana de MusicBox se agrupa en el mismo botón de la barra de tareas que su acceso directo."""
import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import musicbox_desktop as module


class TaskbarIdentityTests(unittest.TestCase):
    def test_installed_exe_uses_the_identity_of_its_shortcut(self):
        self.assertEqual(module.explicit_app_user_model_id(frozen=True, platform_name="nt"), "")

    def test_running_from_python_does_not_group_under_python_exe(self):
        self.assertEqual(module.explicit_app_user_model_id(frozen=False, platform_name="nt"), "MusicBox.Desktop")

    def test_other_systems_do_nothing(self):
        self.assertEqual(module.explicit_app_user_model_id(frozen=False, platform_name="posix"), "")


if __name__ == "__main__":
    unittest.main()
