"""Tabla editable de canciones con selección explícita y estado por fila."""

from collections import defaultdict, deque
import tkinter as tk
from tkinter import ttk

from interfaz import GREEN, MUTED, RED
from lectura_listas import song_query
from song_model import SongEntry
from filtro_canciones import SongFilter


class SongTable(ttk.Frame):
    def __init__(self, master, on_change, on_error):
        super().__init__(master, style="Card.TFrame")
        self.on_change, self.on_error = on_change, on_error
        self.items = {}
        self.order = []
        self.enabled = True
        self.editor = None
        self.sort_reverse = {}
        self.count_var = tk.StringVar(master=self)
        tools = ttk.Frame(self, style="Card.TFrame")
        tools.pack(fill="x", pady=(0, 10))
        ttk.Label(tools, textvariable=self.count_var, foreground=MUTED).pack(side="left")
        self.controls = []
        for text, command in (("Eliminar filas", self.remove_selected), ("Añadir fila", self.add_empty),
                              ("Desmarcar", lambda: self.check_all(False)), ("Marcar todas", lambda: self.check_all(True))):
            button = ttk.Button(tools, text=text, command=command)
            button.pack(side="right", padx=(6, 0))
            self.controls.append(button)
        ttk.Label(self, text="Marca las canciones que quieres buscar. Doble clic en artista o título para corregirlos.",
                  style="Muted.TLabel", font=("Segoe UI", 9), wraplength=820).pack(anchor="w", pady=(0, 10))
        ttk.Label(self, text="En videos se muestra el canal si el intérprete no está indicado. El canal no se usa como artista al buscar.",
                  style="Muted.TLabel", font=("Segoe UI", 9), wraplength=820).pack(anchor="w", pady=(0, 8))
        self.filter = SongFilter(self, self.filter_rows)
        self.filter.pack(fill="x", pady=(0, 10))
        frame = ttk.Frame(self, style="Card.TFrame")
        frame.pack(fill="both", expand=True)
        frame.rowconfigure(0, weight=1)
        frame.columnconfigure(0, weight=1)
        self.tree = ttk.Treeview(frame, columns=("number", "artist", "title", "duration", "source", "state"),
                                 show="tree headings", selectmode="extended")
        self.tree.heading("#0", text="✓", command=self.toggle_all)
        self.tree.column("#0", width=38, minwidth=38, stretch=False, anchor="center")
        for name, title, width, minimum in (("number", "#", 38, 32), ("artist", "ARTISTA / CANAL", 190, 130),
                                            ("title", "CANCIÓN / TÍTULO", 330, 200),
                                            ("duration", "DURACIÓN", 80, 70),
                                            ("source", "ORIGEN", 140, 90), ("state", "ESTADO", 135, 110)):
            self.tree.heading(name, text=title, command=lambda key=name: self.sort_by(key))
            self.tree.column(name, width=width, minwidth=minimum, stretch=name in ("artist", "title", "source"))
        self.tree.grid(row=0, column=0, sticky="nsew")
        vertical = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        vertical.grid(row=0, column=1, sticky="ns")
        horizontal = ttk.Scrollbar(frame, orient="horizontal", command=self.tree.xview)
        horizontal.grid(row=1, column=0, sticky="ew")
        self.tree.configure(yscrollcommand=vertical.set, xscrollcommand=horizontal.set)
        self.tree.tag_configure("review", foreground="#9d6a22")
        self.tree.tag_configure("chosen", foreground=GREEN)
        self.tree.tag_configure("error", foreground=RED)
        self.tree.bind("<Button-1>", self.on_click)
        self.tree.bind("<Double-1>", self.on_double_click)
        self.tree.bind("<space>", self.toggle_selected)
        self.tree.bind("<Delete>", lambda _: self.remove_selected())
        self.tree.bind("<Return>", lambda _: self.edit_selected())
        self.tree.bind("<Configure>", lambda _: self.cancel_editor())
        self.tree.bind("<MouseWheel>", self.on_mousewheel, add="+")
        self.refresh_count()

    def entries(self):
        # El modelo incluye siempre las filas ocultas por el filtro.
        return [(item, self.items[item]) for item in self.order]

    def filter_rows(self):
        self.commit_editor()
        for item, entry in self.entries():
            if self.filter.matches(entry.artist, entry.title, entry.album, entry.uploader):
                self.tree.move(item, "", "end")
            else:
                self.tree.selection_remove(item)
                self.tree.detach(item)
        self.refresh_count()

    def set_queries(self, queries, sources=None, uploaders=None):
        previous = defaultdict(deque)
        for _, entry in self.entries():
            previous[entry.query.casefold()].append(entry)
        sources = sources or {}
        uploaders = uploaders or {}
        entries = []
        for query in queries:
            matches = previous[query.casefold()]
            entries.append(matches.popleft() if matches else SongEntry.from_query(query, sources.get(query.casefold(), "Texto")))
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

    def set_entries(self, entries):
        self.cancel_editor()
        self.tree.delete(*self.items)
        self.items.clear()
        self.order.clear()
        for entry in entries:
            entry.reset_state()
            item = self.tree.insert("", "end")
            self.items[item] = entry
            self.order.append(item)
        self.refresh()

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

    def checked_ids(self):
        return [item for item, entry in self.entries() if entry.checked and entry.title]

    def refresh(self):
        for number, (item, entry) in enumerate(self.entries(), 1):
            tag = "chosen" if entry.state == "Elegida" else "error" if entry.state in ("Error", "Sin resultados") else "review" if entry.state.startswith(("Revisar", "Falta")) else ""
            self.tree.item(item, text="☑" if entry.checked else "☐",
                           values=(number, entry.display_artist, entry.title, entry.duration, entry.source, entry.state), tags=(tag,) if tag else ())
        self.filter_rows()

    def refresh_count(self):
        count = len(self.items)
        checked = sum(entry.checked for entry in self.items.values())
        self.count_var.set(f"{count} {'canción' if count == 1 else 'canciones'} · {checked} marcada{'s' if checked != 1 else ''}")
        if self.filter.value.get().strip():
            self.count_var.set(self.count_var.get() + f" · {len(self.tree.get_children())} visibles")

    def changed(self):
        self.refresh()
        self.on_change()

    def on_click(self, event):
        item = self.tree.identify_row(event.y)
        if self.enabled and item and self.tree.identify_column(event.x) == "#0":
            self.items[item].checked = not self.items[item].checked
            self.changed()
            return "break"

    def toggle_selected(self, _=None):
        if self.enabled:
            selected = self.tree.selection()
            check = not all(self.items[item].checked for item in selected)
            for item in selected:
                self.items[item].checked = check
            self.changed()
        return "break"

    def check_all(self, checked):
        if self.enabled:
            for entry in self.items.values():
                entry.checked = checked
            self.changed()

    def toggle_all(self):
        self.check_all(not all(entry.checked for entry in self.items.values()))

    def remove_selected(self):
        if not self.enabled:
            return
        self.cancel_editor()
        for item in self.tree.selection():
            self.tree.delete(item)
            self.items.pop(item)
            self.order.remove(item)
        self.changed()

    def add_empty(self):
        if not self.enabled:
            return
        self.filter.clear()
        entry = SongEntry("", "", "Manual")
        entry.reset_state()
        item = self.tree.insert("", "end")
        self.items[item] = entry
        self.order.append(item)
        self.tree.selection_set(item)
        self.tree.see(item)
        self.changed()
        self.tree.update_idletasks()
        self.begin_edit(item, "title")

    def sort_by(self, column):
        if not self.enabled:
            return
        self.commit_editor()
        if column == "number":
            return
        reverse = self.sort_reverse.get(column, False)
        def sort_key(pair):
            value = pair[1].display_artist if column == "artist" else getattr(pair[1], column)
            if column == "duration":
                return sum(int(part) * 60 ** index for index, part in enumerate(reversed(value.split(":")))) if value else 0
            return value.casefold()

        self.order = [item for item, _ in sorted(self.entries(), key=sort_key, reverse=reverse)]
        self.sort_reverse[column] = not reverse
        self.changed()

    def edit_value(self, item, field, value):
        if not self.enabled or item not in self.items or field not in ("artist", "title"):
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
        self.changed()

    def on_double_click(self, event):
        item, column = self.tree.identify_row(event.y), self.tree.identify_column(event.x)
        if item and column in ("#2", "#3"):
            self.begin_edit(item, "artist" if column == "#2" else "title")
            return "break"

    def edit_selected(self):
        selected = self.tree.selection()
        if selected:
            self.begin_edit(selected[0], "title")
        return "break"

    def begin_edit(self, item, field):
        if not self.enabled:
            return
        self.commit_editor()
        box = self.tree.bbox(item, field)
        if not box:
            return
        editor = ttk.Entry(self.tree)
        editor.insert(0, getattr(self.items[item], field))
        editor.place(x=box[0], y=box[1], width=box[2], height=box[3])
        editor.select_range(0, "end")
        self.editor = (editor, item, field)
        editor.focus_set()
        editor.bind("<Return>", self.commit_editor)
        editor.bind("<FocusOut>", self.commit_editor)
        editor.bind("<Escape>", self.cancel_editor)

    def commit_editor(self, _=None):
        if self.editor is not None:
            editor, item, field = self.editor
            value = editor.get()
            self.cancel_editor()
            try:
                self.edit_value(item, field, value)
            except ValueError as exc:
                self.on_error(str(exc))
        return "break"

    def cancel_editor(self, _=None):
        if self.editor is not None:
            editor, _, _ = self.editor
            self.editor = None
            editor.destroy()
        return "break"

    def set_enabled(self, enabled):
        self.cancel_editor()
        self.enabled = enabled
        for button in self.controls:
            button.configure(state="normal" if enabled else "disabled")

    def on_mousewheel(self, _=None):
        self.commit_editor()

    def set_state(self, item, state):
        if item in self.items:
            self.items[item].state = state
            tag = "chosen" if state == "Elegida" else "error" if state in ("Error", "Sin resultados") else "review" if state.startswith(("Revisar", "Falta")) else ""
            self.tree.set(item, "state", state)
            self.tree.item(item, tags=(tag,) if tag else ())

    def reset_states(self):
        for entry in self.items.values():
            entry.reset_state()
        self.refresh()

    def destroy(self):
        self.cancel_editor()
        super().destroy()
        del self.count_var
