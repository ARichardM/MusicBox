"""Diálogo «Importar una carpeta»: reconoce los archivos en YouTube y los une a una playlist."""

from __future__ import annotations

import queue
import threading
from pathlib import Path

from PySide6.QtCore import QObject, Property, QTimer, Signal, Slot
from PySide6.QtWidgets import QFileDialog

from canciones import ImportJob
from folder_import import decide, queries_for, scan_folder
from i18n import tr
from importer_controller import DictListModel
from video_search_controller import duration_label

ROW_KEYS = ("id", "title", "artist", "fileName", "state", "label", "reason", "include", "selected",
            "candidates", "chosenTitle", "chosenChannel", "thumbnail")
STATE_LABELS = {
    "queued": "En cola",
    "searching": "Buscando…",
    "auto": "Reconocida",
    "review": "Revisar",
    "none": "Sin coincidencia",
}
FILTERS = ("all", "auto", "review", "none")


def state_label(state):
    return tr(STATE_LABELS[state])


class FolderImportController(QObject):
    stateChanged = Signal()
    rowsChanged = Signal()
    finished = Signal(int, str)

    def __init__(self, bridge, cookies_provider, parent=None):
        super().__init__(parent)
        self._bridge = bridge
        self._cookies = cookies_provider
        self._model = DictListModel(ROW_KEYS, "id", self)
        self._events = queue.Queue()
        self._timer = QTimer(self)
        self._timer.setInterval(60)
        self._timer.timeout.connect(self._poll)
        self._cancel = threading.Event()
        self._job = None
        self._init_state()

    def _init_state(self):
        self._phase = "pick"          # pick → scanning → ready → searching → review
        self._folder = ""
        self._recursive = True
        self._status = tr("Elige una carpeta con tu música.")
        self._status_error = False
        self._progress = 0.0
        self._songs = []
        self._groups = []
        self._queries = []
        self._rows = []
        self._filter = "all"
        self._truncated = False
        self._model.sync([])

    # ---- propiedades ----
    @Property(str, notify=stateChanged)
    def phase(self): return self._phase

    @Property(bool, notify=stateChanged)
    def busy(self): return self._phase in ("scanning", "searching")

    @Property(str, notify=stateChanged)
    def status(self): return self._status

    @Property(bool, notify=stateChanged)
    def statusError(self): return self._status_error

    @Property(float, notify=stateChanged)
    def progress(self): return self._progress

    @Property(str, notify=stateChanged)
    def folder(self): return self._folder

    @Property(str, notify=stateChanged)
    def folderName(self):
        return Path(self._folder).name or self._folder if self._folder else ""

    @Property(bool, notify=stateChanged)
    def recursive(self): return self._recursive

    @Property(bool, notify=stateChanged)
    def truncated(self): return self._truncated

    @Property(bool, notify=stateChanged)
    def inLibrary(self):
        return bool(self._folder) and self._bridge.isInLibrary(self._folder)

    @Property(QObject, constant=True)
    def rows(self): return self._model

    @Property(str, notify=stateChanged)
    def filter(self): return self._filter

    @Property(int, notify=stateChanged)
    def total(self): return len(self._rows)

    @Property(int, notify=stateChanged)
    def autoCount(self): return self._count("auto")

    @Property(int, notify=stateChanged)
    def reviewCount(self): return self._count("review")

    @Property(int, notify=stateChanged)
    def noneCount(self): return self._count("none")

    @Property(int, notify=stateChanged)
    def includedCount(self): return sum(bool(r["include"]) and r["selected"] >= 0 for r in self._rows)

    def _count(self, state):
        return sum(r["state"] == state for r in self._rows)

    # ---- pasos ----
    def _say(self, text, error=False):
        self._status, self._status_error = text, error
        self.stateChanged.emit()

    def _set_phase(self, phase):
        self._phase = phase
        self.stateChanged.emit()

    @Slot(result=str)
    def chooseFolder(self):
        start = self._folder or str(Path.home() / "Music")
        selected = QFileDialog.getExistingDirectory(None, tr("Carpeta de música"), start)
        return selected or ""

    @Slot(bool)
    def setRecursive(self, value):
        if self._phase in ("pick", "ready") and self._recursive != bool(value):
            self._recursive = bool(value)
            self.stateChanged.emit()
            if self._folder:
                self.scan(self._folder)

    @Slot(str)
    def scan(self, folder):
        if self.busy or not folder:
            return
        self._folder = folder
        self._cancel.clear()
        self._songs, self._rows, self._groups = [], [], []
        self._model.sync([])
        self._progress = -1
        self._status_error = False
        self._status = tr("Leyendo los archivos de la carpeta…")
        self._set_phase("scanning")
        recursive = self._recursive
        threading.Thread(target=self._scan_worker, args=(folder, recursive), daemon=True).start()
        self._timer.start()

    def _scan_worker(self, folder, recursive):
        try:
            songs, truncated = scan_folder(folder, recursive=recursive, cancelled=self._cancel.is_set)
            self._events.put(("scan", songs, truncated))
        except Exception as exc:
            self._events.put(("scan_error", str(exc)))

    @Slot()
    def startSearch(self):
        if self.busy or self._phase != "ready" or not self._songs:
            return
        try:
            queries, groups = queries_for(self._songs)
        except ValueError as exc:
            self._say(str(exc), True)
            return
        if not queries:
            self._say(tr("Ninguna canción tiene un nombre que se pueda buscar."), True)
            return
        self._groups = groups
        self._queries = queries
        for row in self._rows:
            row.update(state="queued", label=state_label("queued"), reason="", include=False,
                       selected=-1, candidates=[])
        self._push()
        self._progress = -1
        self._status_error = False
        self._say(tr("Buscando tus canciones en YouTube…"))
        self._set_phase("searching")
        job = ImportJob()
        self._job = job
        config = dict(task="search", queries=queries, cookies=self._cookies())
        threading.Thread(target=job.run, args=(config,), daemon=True).start()
        self._timer.start()

    @Slot()
    def cancel(self):
        self._cancel.set()
        job = self._job
        if job is not None:
            self._say(tr("Cancelando…"))
            threading.Thread(target=job.cancel, daemon=True).start()

    # ---- eventos ----
    def _poll(self):
        for _ in range(40):
            try:
                event = self._events.get_nowait()
            except queue.Empty:
                break
            self._handle_scan(event)
        job = self._job
        if job is None:
            if self._phase != "scanning" and self._events.empty():
                self._timer.stop()
            return
        for _ in range(40):
            try:
                event = job.events.get_nowait()
            except queue.Empty:
                break
            try:
                self._handle_job(event, job)
            except Exception as exc:
                self._say(f"{type(exc).__name__}: {exc}", True)

    def _handle_scan(self, event):
        if event[0] == "scan_error":
            self._progress = 0
            self._status_error = True
            self._status = event[1]
            self._set_phase("pick")
            return
        _, songs, truncated = event
        if self._cancel.is_set():
            self._init_state()
            self.stateChanged.emit()
            return
        self._songs, self._truncated = songs, truncated
        self._rows = [dict(id=str(index), title=song.title, artist=song.artist,
                           fileName=Path(song.path).name, state="queued", label="", reason="",
                           include=False, selected=-1, candidates=[], chosenTitle="", chosenChannel="",
                           thumbnail="") for index, song in enumerate(songs)]
        self._progress = 0
        if not songs:
            self._status_error = True
            self._status = tr("No se encontraron archivos de audio en esa carpeta.")
            self._set_phase("pick")
            return
        self._status_error = False
        self._status = tr("{n} canciones encontradas. Pulsa Buscar para reconocerlas.", n=len(songs))
        self._push()
        self._set_phase("ready")

    def _handle_job(self, event, job):
        kind = event["event"]
        cancelled = job.cancelled.is_set()
        if kind == "import_status" and not cancelled:
            index = event.get("query_index")
            if index is not None and index < len(self._groups):
                self._say(tr("Buscando {n} de {total}: {query}", n=index + 1, total=len(self._groups),
                             query=self._queries[index]))
                for song in self._groups[index]:
                    self._rows[song].update(state="searching", label=state_label("searching"))
                self._push()
            else:
                message = event["text"]
                self._say(tr(message))
        elif kind == "search_result" and not cancelled:
            self._apply_result(event)
            self._progress = event.get("progress_index", event["index"] + 1) / max(1, event["total"])
            self.stateChanged.emit()
        elif kind == "import_error" and not cancelled and "query_index" not in event:
            self._say(event["text"], True)
        elif kind == "job_done":
            self._job = None
            self._finish_search(cancelled)

    def _apply_result(self, event):
        index = event["index"]
        if not 0 <= index < len(self._groups):
            return
        candidates = [dict(c, id=self._video_id(c), durationText=duration_label(c.get("duration")),
                           thumbnail=self._thumb(c)) for c in event["candidates"]]
        for song_index in self._groups[index]:
            song, row = self._songs[song_index], self._rows[song_index]
            result = decide(song, event["candidates"]) if candidates and not event.get("failed") else dict(
                status="none", index=-1, reason=event.get("reason", ""))
            state, reason = result["status"], result["reason"]
            chosen = candidates[result["index"]] if result["index"] >= 0 else None
            row.update(state=state, label=state_label(state), candidates=candidates,
                       selected=result["index"], include=state == "auto",
                       reason=tr(reason) if reason else "",
                       chosenTitle=chosen["title"] if chosen else "",
                       chosenChannel=chosen.get("channel", "") if chosen else "",
                       thumbnail=chosen["thumbnail"] if chosen else "")
        self._push()

    @staticmethod
    def _video_id(candidate):
        from folder_import import video_id
        return video_id(candidate.get("url"))

    @classmethod
    def _thumb(cls, candidate):
        vid = cls._video_id(candidate)
        return f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg" if vid else ""

    def _finish_search(self, cancelled):
        for row in self._rows:
            if row["state"] in ("queued", "searching"):
                row.update(state="none", label=state_label("none"), reason=tr("No se llegó a buscar."))
        self._push()
        self._progress = 1
        if cancelled:
            self._status_error = False
            self._status = tr("Búsqueda cancelada. Puedes revisar lo que se reconoció.")
        else:
            self._status_error = False
            self._status = tr("Listo: {a} reconocidas, {b} para revisar, {c} sin coincidencia.",
                              a=self._count("auto"), b=self._count("review"), c=self._count("none"))
        self._set_phase("review")

    # ---- revisión ----
    @Slot(str)
    def setFilter(self, value):
        if value in FILTERS and value != self._filter:
            self._filter = value
            self._push()
            self.stateChanged.emit()

    @Slot(str)
    def toggle(self, row_id):
        row = self._row(row_id)
        if row and row["selected"] >= 0 and self._phase == "review":
            row["include"] = not row["include"]
            self._push()
            self.stateChanged.emit()

    @Slot(str, int)
    def choose(self, row_id, candidate):
        row = self._row(row_id)
        if not row or self._phase != "review" or not 0 <= candidate < len(row["candidates"]):
            return
        picked = row["candidates"][candidate]
        row.update(selected=candidate, include=True, chosenTitle=picked["title"],
                   chosenChannel=picked.get("channel", ""), thumbnail=picked["thumbnail"],
                   reason="", label=tr("Elegida por ti"))
        if row["state"] in ("review", "none"):
            row["state"] = "auto"
        self._push()
        self.stateChanged.emit()

    @Slot(bool)
    def includeAll(self, value):
        if self._phase != "review":
            return
        for row in self._visible():
            if row["selected"] >= 0:
                row["include"] = bool(value)
        self._push()
        self.stateChanged.emit()

    def _row(self, row_id):
        return next((r for r in self._rows if r["id"] == row_id), None)

    def _visible(self):
        return [r for r in self._rows if self._filter == "all" or r["state"] == self._filter]

    def _push(self):
        self._model.sync([dict(row) for row in self._visible()])
        self.rowsChanged.emit()

    # ---- confirmar ----
    @Slot(str, bool, result=int)
    def apply(self, playlist, add_folder):
        if self._phase != "review" or self.busy:
            return -1
        items = []
        for row in self._rows:
            if not (row["include"] and row["selected"] >= 0):
                continue
            picked = row["candidates"][row["selected"]]
            items.append(dict(path=self._songs[int(row["id"])].path, id=picked["id"], title=picked["title"],
                              channel=picked.get("channel", ""), duration=picked.get("duration") or 0,
                              thumbnail=picked["thumbnail"], url=picked.get("url", "")))
        if not items:
            self._say(tr("Marca al menos una canción para agregarla."), True)
            return -1
        added = self._bridge.linkLocalSongs(playlist, items, bool(add_folder) and not self.inLibrary, self._folder)
        self.finished.emit(len(items), playlist.strip())
        return len(items)

    @Slot()
    def reset(self):
        self._cancel.set()
        job = self._job
        if job is not None:
            threading.Thread(target=job.cancel, daemon=True).start()
        self._job = None
        self._timer.stop()
        while not self._events.empty():
            try:
                self._events.get_nowait()
            except queue.Empty:
                break
        self._init_state()
        self._progress = 0
        self.stateChanged.emit()
