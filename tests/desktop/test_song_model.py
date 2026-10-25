"""La tabla de canciones conserva marcas al filtrar, ordenar y volver a analizar el texto."""

import unittest

from song_model import SongEntry, SongTableModel


def model(*queries):
    table = SongTableModel()
    table.set_queries(list(queries))
    return table


class SongTableModelTests(unittest.TestCase):
    def test_queries_parse_artist_and_title(self):
        table = model("Soda Stereo - De musica ligera", "Solo titulo")
        rows = table.rows()
        self.assertEqual(rows[0]["rawArtist"], "Soda Stereo")
        self.assertEqual(rows[0]["title"], "De musica ligera")
        self.assertEqual(rows[1]["state"], "Revisar artista")
        self.assertEqual(table.counts(), (2, 2, 2))

    def test_filter_hides_rows_without_unchecking_them(self):
        table = model("Soda Stereo - Zoom", "Gustavo Cerati - Crimen")
        table.filter_text = "cerati"
        self.assertEqual([row["title"] for row in table.rows()], ["Crimen"])
        self.assertEqual(table.counts(), (2, 2, 1))
        self.assertEqual(len(table.queries(checked_only=True)), 2)

    def test_reanalysing_the_text_keeps_the_user_marks(self):
        table = model("A - Uno", "B - Dos")
        table.toggle(table.order[0])
        table.set_queries(["A - Uno", "B - Dos", "C - Tres"])
        checked = [entry.checked for _, entry in table.entries()]
        self.assertEqual(checked, [False, True, True])

    def test_editing_rejects_empty_titles_and_duplicates(self):
        table = model("A - Uno", "B - Dos")
        first, second = table.order
        with self.assertRaises(ValueError):
            table.edit_value(first, "title", "  ")
        with self.assertRaises(ValueError):
            table.edit_value(second, "artist", "A")
            table.edit_value(second, "title", "Uno")
        table.edit_value(first, "title", "Nuevo")
        self.assertEqual(table.items[first].title, "Nuevo")

    def test_checked_row_without_title_blocks_the_search(self):
        table = model("A - Uno")
        table.add_empty()
        with self.assertRaises(ValueError):
            table.queries(checked_only=True)

    def test_sort_toggles_direction_and_keeps_numbers_stable_per_position(self):
        table = model("B - Beta", "A - Alfa", "C - Gamma")
        table.sort_by("title")
        self.assertEqual([row["title"] for row in table.rows()], ["Alfa", "Beta", "Gamma"])
        table.sort_by("title")
        self.assertEqual([row["title"] for row in table.rows()], ["Gamma", "Beta", "Alfa"])

    def test_remove_and_state_tags(self):
        table = model("A - Uno", "B - Dos")
        first = table.order[0]
        table.set_state(first, "Elegida")
        self.assertEqual(table.rows()[0]["tag"], "chosen")
        table.remove([first])
        self.assertEqual(table.counts(), (1, 1, 1))

    def test_entry_state_rules(self):
        self.assertEqual(SongEntry("", "").state, "Pendiente")  # el estado se calcula al insertar
        entry = SongEntry("Artista", "Titulo…")
        entry.reset_state()
        self.assertEqual(entry.state, "Revisar título")


if __name__ == "__main__":
    unittest.main()
