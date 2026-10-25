"""Traducciones: todo texto traducible tiene equivalente en inglés y los marcadores coinciden."""

import ast
import re
import unittest
from pathlib import Path

import i18n

DESKTOP = Path(__file__).resolve().parents[2] / "apps" / "desktop"
UI = DESKTOP / "ui"
QSTR = re.compile(r"qsTr\(\s*(?:\"((?:[^\"\\]|\\.)*)\"|'((?:[^'\\]|\\.)*)')\s*\)")


def qml_literals():
    found = []
    for path in UI.rglob("*.qml"):
        for match in QSTR.finditer(path.read_text(encoding="utf-8")):
            raw = match.group(1) if match.group(1) is not None else match.group(2)
            found.append((path.name, raw.replace("\\n", "\n").replace('\\"', '"').replace("\\'", "'")))
    return found


class I18nTests(unittest.TestCase):
    def test_placeholders_match_between_languages(self):
        for source, english in i18n.EN.items():
            self.assertEqual(sorted(re.findall(r"\{\w+\}", source)), sorted(re.findall(r"\{\w+\}", english)), source)

    def test_python_messages_follow_language(self):
        i18n.set_language("en")
        self.assertEqual(i18n.tr("{n} resultados", n=3), "3 results")
        i18n.set_language("es")
        self.assertEqual(i18n.tr("{n} resultados", n=3), "3 resultados")

    def test_resolve_falls_back_to_spanish(self):
        self.assertEqual(i18n.resolve("fr"), "es")
        self.assertEqual(i18n.resolve("en"), "en")

    def test_every_qsTr_literal_has_an_english_entry(self):
        literals = qml_literals()
        self.assertGreater(len(literals), 300)
        for name, text in literals:
            if re.search(r"[A-Za-zÁÉÍÓÚáéíóúñ]{2,}", text):
                self.assertIn(text, i18n.EN, f"{name}: {text}")

    def test_python_messages_have_english_entries(self):
        for path in DESKTOP.glob("*.py"):
            if path.name.startswith("i18n"):
                continue
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if not (isinstance(node, ast.Call) and node.args):
                    continue
                func = node.func
                name = func.id if isinstance(func, ast.Name) else func.attr if isinstance(func, ast.Attribute) else ""
                if name != "tr":
                    continue
                for sub in ast.walk(node.args[0]):
                    if (isinstance(sub, ast.Constant) and isinstance(sub.value, str)
                            and re.search(r"[A-Za-zÁÉÍÓÚáéíóúñ]{2,}", sub.value)):
                        self.assertIn(sub.value, i18n.EN, f"{path.name}: {sub.value}")


if __name__ == "__main__":
    unittest.main()
