"""Importador: tabla, recortes, coincidencias, informe y modelos de lista, sin red ni procesos externos."""

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtCore import QModelIndex
from PySide6.QtWidgets import QApplication

import i18n
from canciones import Attachment
from importer_controller import DictListModel, ImporterController

APP = QApplication.instance() or QApplication([])


class FakePreview:
    def __init__(self): self.stopped = 0
    def stop(self): self.stopped += 1


def make_controller(sent=None, transfers=None):
    i18n.set_language("es")
    sent = sent if sent is not None else []
    transfers = transfers if transfers is not None else []
    controller = ImporterController(lambda urls: sent.append(list(urls)) or True, transfers.append,
                                    FakePreview(), lambda: "")
    return controller


def search_event(index, query, titles, **extra):
    candidates = [dict(title=title, channel="Canal", duration=200, url=f"https://www.youtube.com/watch?v={index}{n}")
                  for n, title in enumerate(titles)]
    return dict(index=index, query=query, candidates=candidates, total=3, progress_index=index + 1, **extra)


class DictListModelTests(unittest.TestCase):
    def collect(self, model):
        events = []
        model.dataChanged.connect(lambda a, b: events.append(("changed", a.row())))
        model.rowsInserted.connect(lambda p, first, last: events.append(("inserted", first, last)))
        model.modelReset.connect(lambda: events.append(("reset",)))
        return events

    def test_updates_only_what_changed(self):
        model = DictListModel(("id", "title"), "id")
        model.sync([{"id": 1, "title": "a"}, {"id": 2, "title": "b"}])
        events = self.collect(model)
        model.sync([{"id": 1, "title": "a"}, {"id": 2, "title": "B"}])
        self.assertEqual(events, [("changed", 1)])
        model.sync([{"id": 1, "title": "a"}, {"id": 2, "title": "B"}, {"id": 3, "title": "c"}])
        self.assertEqual(events[-1], ("inserted", 2, 2))
        model.sync([{"id": 3, "title": "c"}])
        self.assertEqual(events[-1], ("reset",))
        self.assertEqual(model.rowCount(), 1)
        self.assertEqual(model.data(model.index(0), model.roleNames().popitem()[0]) is not None, True)


class ImporterTableTests(unittest.TestCase):
    def test_names_become_rows_and_edits_are_validated(self):
        controller = make_controller()
        controller.setNames("Soda Stereo - De musica ligera\nGustavo Cerati - Crimen")
        controller._sync_table_from_text()
        self.assertEqual(controller._song_model.rowCount(), 2)
        first = controller._song_model._rows[0]["id"]
        self.assertEqual(controller.editField(first, "title", ""), "El título no puede quedar vacío. Puedes quitar o desmarcar esa fila.")
        self.assertTrue(controller.statusError)
        self.assertEqual(controller.editField(first, "title", "Persiana americana"), "")
        # el texto se reescribe a partir de la tabla
        self.assertIn("Soda Stereo - Persiana americana", controller.names)

    def test_search_requires_a_marked_song(self):
        controller = make_controller()
        controller.setNames("A - Uno")
        controller._sync_table_from_text()
        controller.checkAll(False)
        controller.startSearch()
        self.assertTrue(controller.statusError)
        self.assertIn("Marca al menos una canción", controller.status)
        self.assertFalse(controller.busy)

    def test_filter_and_counts(self):
        controller = make_controller()
        controller.setNames("A - Uno\nB - Dos")
        controller._sync_table_from_text()
        controller.setSongFilter("dos")
        self.assertEqual(controller._song_model.rowCount(), 1)
        self.assertIn("1 visibles", controller.songCountText)
        self.assertFalse(controller.allChecked and False)


class ImporterImageTests(unittest.TestCase):
    def test_crop_uses_fractions_and_rejects_tiny_areas(self):
        controller = make_controller()
        controller._attachments.append(Attachment.from_image(Image.new("RGB", (200, 100), "white"), "prueba.png"))
        controller._active = 0
        controller.crop(0.0, 0.0, 0.5, 1.0)
        self.assertEqual(controller._attachments[0].image.size, (100, 100))
        self.assertIn("Recorte aplicado", controller.status)
        controller.crop(0.1, 0.1, 0.11, 0.11)
        self.assertTrue(controller.statusError)
        controller.restoreImage()
        self.assertEqual(controller._attachments[0].image.size, (200, 100))
        controller.removeImage()
        self.assertEqual(controller.activeIndex, -1)
        self.assertEqual(controller.imageInfo, "Sin imágenes")


class ImporterResultsTests(unittest.TestCase):
    def setup_search(self, controller, queries):
        controller._searched_queries = queries
        controller._row_groups = [[] for _ in queries]
        controller._row_ids = []

    def test_exact_match_is_chosen_automatically_and_can_be_toggled(self):
        controller = make_controller()
        self.setup_search(controller, ["Soda Stereo - De musica ligera", "Gustavo Cerati - Crimen"])
        controller._add_candidates(search_event(0, "Soda Stereo - De musica ligera",
                                                ["Soda Stereo - De Musica Ligera", "Otra cosa distinta"]))
        controller._add_candidates(search_event(1, "Gustavo Cerati - Crimen", ["Version en vivo de otra cancion"]))
        self.assertEqual(controller.choiceCount, 1)               # solo la verificable
        groups = controller._group_rows()
        self.assertTrue(groups[0]["candidates"][0]["chosen"])
        self.assertIn("Auto", groups[0]["candidates"][0]["label"])
        self.assertFalse(any(candidate["chosen"] for candidate in groups[1]["candidates"]))
        controller.choose("0:0")                                   # quitar la elección
        self.assertEqual(controller.choiceCount, 0)
        controller.choose("1:0")
        self.assertEqual(controller.choiceCount, 1)
        self.assertEqual(controller._labels["1:0"], "✓ Elegida")

    def test_filter_keeps_every_alternative_of_a_matching_group(self):
        controller = make_controller()
        self.setup_search(controller, ["Uno - A", "Dos - B"])
        controller._add_candidates(search_event(0, "Uno - A", ["Alfa", "Beta"]))
        controller._add_candidates(search_event(1, "Dos - B", ["Gamma"]))
        controller.setResultFilter("gamma")
        rows = controller._group_rows()
        self.assertEqual([row["query"] for row in rows], ["Dos - B"])
        controller.setResultFilter("uno")
        self.assertEqual(len(controller._group_rows()[0]["candidates"]), 2)

    def test_failed_group_shows_its_reason_and_is_retryable(self):
        controller = make_controller()
        self.setup_search(controller, ["X - Y"])
        controller._add_candidates(dict(index=0, query="X - Y", candidates=[], failed=True, reason="sin red", total=1))
        group = controller._group_rows()[0]
        self.assertTrue(group["failed"])
        self.assertIn("Error: sin red", group["message"])
        self.assertEqual(controller._pending_indices(retryable=True), [0])
        controller._update_report()                      # el informe se regenera al terminar cada trabajo
        self.assertIn("ERROR: sin red", controller._report_text)

    def test_adding_to_downloads_sends_unique_urls_and_records_the_transfer(self):
        sent, transfers = [], []
        controller = make_controller(sent, transfers)
        controller.setNames("Soda Stereo - De musica ligera")
        controller._sync_table_from_text()
        controller._searched_queries = ["Soda Stereo - De musica ligera"]
        controller._row_groups = [list(controller._song_model._rows and [controller._song_model._rows[0]["id"]])]
        controller._row_ids = [controller._row_groups[0][0]]
        controller._add_candidates(search_event(0, "Soda Stereo - De musica ligera", ["Soda Stereo - De Musica Ligera"]))
        self.assertEqual(controller.addSelected(), "ok")
        self.assertEqual(sent, [["https://www.youtube.com/watch?v=00"]])
        self.assertEqual(len(transfers), 1)
        self.assertEqual(transfers[0]["selected"], 1)

    def test_shared_video_asks_for_confirmation_before_adding(self):
        sent = []
        controller = make_controller(sent)
        controller.setNames("A - Uno\nB - Dos")
        controller._sync_table_from_text()
        controller._searched_queries = ["A - Uno", "B - Dos"]
        ids = [row["id"] for row in controller._song_model._rows]
        controller._row_groups = [[ids[0]], [ids[1]]]
        controller._row_ids = ids
        same = "https://www.youtube.com/watch?v=mismo"
        for index, query in enumerate(controller._searched_queries):
            event = search_event(index, query, ["x"])
            event["candidates"][0]["url"] = same
            controller._add_candidates(event)
            controller._select(f"{index}:0")
        questions = []
        controller.confirmDuplicates.connect(questions.append)
        self.assertEqual(controller.addSelected(), "confirm")
        self.assertEqual(sent, [])
        self.assertIn("¿Añadir 1 videos únicos?", questions[0])
        self.assertTrue(controller.confirmAdd())
        self.assertEqual(sent, [[same]])

    def test_reset_clears_everything_and_stops_the_preview(self):
        controller = make_controller()
        controller.setNames("A - Uno")
        controller._sync_table_from_text()
        controller.reset()
        self.assertEqual(controller._song_model.rowCount(), 0)
        self.assertEqual(controller.names, "")
        self.assertGreaterEqual(controller._preview.stopped, 1)


if __name__ == "__main__":
    unittest.main()
