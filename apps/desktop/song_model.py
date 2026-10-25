"""Modelo de la tabla de canciones: filas editables con selección explícita y estado. Sin Tkinter ni Qt."""

from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass

from coincidencias import normalize
from lectura_listas import VIDEO_TITLE, video_artist, song_query


@dataclass
class SongEntry:
    artist: str
    title: str
    source: str = "Texto"
    checked: bool = True
    state: str = "Pendiente"
    duration: str = ""
    album: str = ""
    uploader: str = ""
    review_reason: str = ""
    raw_text: str = ""

    @classmethod
    def from_query(cls, query, source="Texto"):
        credited_artist = video_artist(query)
        if credited_artist:
            entry = cls(credited_artist, query, source)
            entry.reset_state()
            return entry
        artist, separator, title = query.partition(" - ")
        if not separator or VIDEO_TITLE.search(artist):
            artist, title = "", query
        entry = cls(artist.strip(), title.strip(), source)
        entry.reset_state()
        return entry

    @property
    def query(self):
        if not self.title:
            return ""
        return song_query(self.title, self.artist)

    @property
    def display_artist(self):
        return self.artist or (f"{self.uploader} (canal)" if self.uploader else "")

    def reset_state(self):
        self.state = "Falta título" if not self.title else "Revisar artista" if not self.artist else "Pendiente"
        if self.title and not self.artist and self.uploader:
            self.state = "Canal disponible"
        if self.title.endswith(("…", "...")):
            self.state = "Revisar título"
        if self.review_reason:
            self.state = "Revisar bloque"


def state_tag(state):
    """Categoría visual de un estado: chosen, error, review o vacío."""
    if state == "Elegida":
        return "chosen"
    if state in ("Error", "Sin resultados"):
        return "error"
    if state.startswith(("Revisar", "Falta")):
        return "review"
    return ""


def duration_seconds(value):
    try:
        return sum(int(part) * 60 ** index for index, part in enumerate(reversed(value.split(":")))) if value else 0
    except ValueError:
        return 0


class SongTableModel:
    """Filas con identificador estable. Filtrar u ordenar no cambia qué canciones están marcadas."""

    def __init__(self):
        self.items: dict[int, SongEntry] = {}
        self.order: list[int] = []
        self.filter_text = ""
        self.sort_reverse: dict[str, bool] = {}
        self._next_id = 1

    # ---- consulta ----
    def entries(self):
        return [(item, self.items[item]) for item in self.order]

    def matches(self, entry):
        words = normalize(self.filter_text).split()
        if not words:
            return True
        haystack = normalize(" ".join((entry.artist, entry.title, entry.album, entry.uploader)))
        return all(word in haystack for word in words)

    def visible_ids(self):
        return [item for item, entry in self.entries() if self.matches(entry)]

    def counts(self):
        total = len(self.items)
        checked = sum(entry.checked for entry in self.items.values())
        visible = len(self.visible_ids()) if self.filter_text.strip() else total
        return total, checked, visible

    def rows(self):
        """Filas visibles listas para la interfaz."""
        numbers = {item: number for number, item in enumerate(self.order, 1)}
        rows = []
        for item in self.visible_ids():
            entry = self.items[item]
            rows.append({"id": item, "number": numbers[item], "checked": entry.checked,
                         "artist": entry.display_artist, "rawArtist": entry.artist, "title": entry.title,
                         "duration": entry.duration, "source": entry.source, "state": entry.state,
                         "tag": state_tag(entry.state)})
        return rows

    def queries(self, checked_only=False):
        queries = []
        for number, (_, entry) in enumerate(self.entries(), 1):
            if checked_only and not entry.checked:
                continue
            if checked_only and not entry.title:
                raise ValueError(f"Completa el título de la fila {number} o desmárcala.")
            if entry.query:
                queries.append(entry.query)
        return queries

    # ---- carga ----
    def _insert(self, entry):
        entry.reset_state()
        item = self._next_id
        self._next_id += 1
        self.items[item] = entry
        self.order.append(item)
        return item

    def set_entries(self, entries):
        self.items.clear()
        self.order.clear()
        for entry in entries:
            self._insert(entry)

    def set_queries(self, queries, sources=None, uploaders=None):
        previous = defaultdict(deque)
        for _, entry in self.entries():
            previous[entry.query.casefold()].append(entry)
        sources, uploaders = sources or {}, uploaders or {}
        entries = []
        for query in queries:
            matches = previous[query.casefold()]
            entries.append(matches.popleft() if matches
                           else SongEntry.from_query(query, sources.get(query.casefold(), "Texto")))
            if not entries[-1].uploader:
                entries[-1].uploader = uploaders.get(query.casefold(), "")
        self.set_entries(entries)

    def set_imported(self, records):
        previous = defaultdict(deque)
        for _, entry in self.entries():
            previous[entry.query.casefold()].append(entry)
        entries = []
        for record in records:
            entry = (SongEntry(record.artist, record.title, "Texto pegado", duration=record.duration,
                               album=record.album, uploader=record.uploader,
                               checked=not bool(record.review_reason), review_reason=record.review_reason,
                               raw_text=record.raw_text)
                     if record.structured else SongEntry.from_query(record.title, "Texto pegado"))
            matches = previous[entry.query.casefold()]
            if matches:
                previous_entry = matches.popleft()
                if not entry.review_reason:
                    entry.checked = previous_entry.checked
            entries.append(entry)
        self.set_entries(entries)

    # ---- edición ----
    def toggle(self, item):
        if item in self.items:
            self.items[item].checked = not self.items[item].checked

    def check_all(self, checked):
        for entry in self.items.values():
            entry.checked = checked

    def toggle_all(self):
        self.check_all(not all(entry.checked for entry in self.items.values()))

    def remove(self, ids):
        for item in ids:
            if item in self.items:
                self.items.pop(item)
                self.order.remove(item)

    def add_empty(self):
        self.filter_text = ""
        return self._insert(SongEntry("", "", "Manual"))

    def sort_by(self, column):
        if column == "number":
            return

        def key(pair):
            entry = pair[1]
            if column == "duration":
                return duration_seconds(entry.duration)
            value = entry.display_artist if column == "artist" else getattr(entry, column)
            return value.casefold()

        reverse = self.sort_reverse.get(column, False)
        self.order = [item for item, _ in sorted(self.entries(), key=key, reverse=reverse)]
        self.sort_reverse[column] = not reverse

    def edit_value(self, item, field, value):
        if item not in self.items or field not in ("artist", "title"):
            return
        value = " ".join(value.split())
        entry = self.items[item]
        artist, title = (value, entry.title) if field == "artist" else (entry.artist, value)
        if field == "title" and not title:
            raise ValueError("El título no puede quedar vacío. Puedes quitar o desmarcar esa fila.")
        query = song_query(title, artist) if title else ""
        if query and any(other != item and row.query.casefold() == query.casefold() for other, row in self.entries()):
            raise ValueError("Esa canción ya está en otra fila.")
        entry.artist, entry.title = artist, title
        entry.review_reason = ""
        entry.reset_state()

    def set_state(self, item, state):
        if item in self.items:
            self.items[item].state = state

    def reset_states(self):
        for entry in self.items.values():
            entry.reset_state()
