"""Importador de canciones: imágenes, OCR, tabla editable, coincidencias e informe.

Reúne la lógica del antiguo diálogo de Tkinter (importar_canciones.py) sin depender de ninguna
interfaz: el motor sigue siendo `canciones.py`, que habla con un proceso trabajador cancelable.
"""

from __future__ import annotations

import threading
import webbrowser
from pathlib import Path

from PySide6.QtCore import QAbstractListModel, QModelIndex, QObject, Property, Qt, QTimer, Signal, Slot
from PySide6.QtGui import QGuiApplication, QImage
from PySide6.QtQuick import QQuickImageProvider
from PySide6.QtWidgets import QFileDialog

import i18n
from canciones import ImportJob, LoadImagesJob, MAX_IMAGES, MAX_QUERIES, clean_queries
from coincidencias import best_candidate, normalize
from importar_texto import parse_exported_playlist
from informe_canciones import playlist_url, selection_transfer, transfer_text
from lectura_listas import organize_playlist_text
from song_model import SongTableModel
from video_search_controller import duration_label

tr = i18n.tr


class DictListModel(QAbstractListModel):
    """Lista de diccionarios para QML que se actualiza por diferencias, sin reiniciar el desplazamiento."""

    def __init__(self, keys, id_key, parent=None):
        super().__init__(parent)
        self._keys = list(keys)
        self._id_key = id_key
        self._rows = []
        self._roles = {Qt.ItemDataRole.UserRole + 1 + number: key.encode() for number, key in enumerate(self._keys)}

    def roleNames(self):
        return dict(self._roles)

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self._rows)

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or not 0 <= index.row() < len(self._rows):
            return None
        key = self._roles.get(role)
        return self._rows[index.row()].get(key.decode()) if key else None

    @Property(int, constant=True)
    def length(self):
        return len(self._rows)

    def sync(self, rows):
        old = self._rows
        old_ids = [row[self._id_key] for row in old]
        new_ids = [row[self._id_key] for row in rows]
        if old_ids == new_ids:
            for position, (before, after) in enumerate(zip(old, rows)):
                if before != after:
                    self._rows[position] = after
                    self.dataChanged.emit(self.index(position), self.index(position))
        elif new_ids[:len(old_ids)] == old_ids and len(rows) > len(old):
            for position in range(len(old)):
                if old[position] != rows[position]:
                    self._rows[position] = rows[position]
                    self.dataChanged.emit(self.index(position), self.index(position))
            self.beginInsertRows(QModelIndex(), len(old), len(rows) - 1)
            self._rows = old + rows[len(old):]
            self.endInsertRows()
        else:
            self.beginResetModel()
            self._rows = list(rows)
            self.endResetModel()


SONG_KEYS = ("id", "number", "checked", "artist", "rawArtist", "title", "duration", "source", "state", "tag")
GROUP_KEYS = ("index", "query", "failed", "reason", "message", "candidates")


def qimage_from_pil(image):
    rgb = image.convert("RGB")
    data = rgb.tobytes("raw", "RGB")
    return QImage(data, rgb.width, rgb.height, rgb.width * 3, QImage.Format.Format_RGB888).copy()


class ImporterImageProvider(QQuickImageProvider):
    """Entrega a QML la imagen de trabajo activa (con el recorte aplicado)."""

    def __init__(self, controller):
        super().__init__(QQuickImageProvider.ImageType.Image)
        self._controller = controller

    def requestImage(self, image_id, size, requested_size):
        image = self._controller.snapshot_image()
        return image if image is not None else QImage()


class ImporterController(QObject):
    stateChanged = Signal()
    imagesChanged = Signal()
    namesChanged = Signal()
    rawChanged = Signal()
    tableChanged = Signal()
    resultsChanged = Signal()
    reportChanged = Signal()
    goToStep = Signal(int)
    confirmDuplicates = Signal(str)
    transferDone = Signal()

    def __init__(self, add_urls, on_transfer, preview, cookies_provider, parent=None):
        super().__init__(parent)
        self._add_urls = add_urls
        self._on_transfer = on_transfer
        self._preview = preview
        self._cookies = cookies_provider
        self._lock = threading.Lock()
        self._timer = QTimer(self)
        self._timer.setInterval(60)
        self._timer.timeout.connect(self._poll)
        self._names_timer = QTimer(self)
        self._names_timer.setSingleShot(True)
        self._names_timer.setInterval(220)
        self._names_timer.timeout.connect(self._sync_table_from_text)
        self._init_state()

    def _init_state(self):
        if not hasattr(self, "_song_model"):
            self._song_model = DictListModel(SONG_KEYS, "id", self)
            self._group_model = DictListModel(GROUP_KEYS, "index", self)
        self._attachments = []
        self._active = None
        self._image_version = 0
        self._names = ""
        self._synced_text = ""
        self._raw_text = ""
        self._last_pasted_export = ""
        self._query_sources = {}
        self._query_uploaders = {}
        self._table = SongTableModel()
        self._playlist_input = ""
        self._playlist_report = None
        self._playlist_error = ""
        self._searched_queries = []
        self._row_groups = []
        self._row_ids = []
        self._groups = {}
        self._labels = {}
        self._candidate_map = {}
        self._selected = {}
        self._outcomes = {}
        self._query_errors = {}
        self._result_counts = {}
        self._result_filter = ""
        self._errors = []
        self._transfer_report = None
        self._pending_transfer = None
        self._import_summary = ""
        self._job = None
        self._task = None
        self._busy = False
        self._cancelling = False
        self._progress = 0.0
        self._status = tr("Adjunta una imagen o escribe nombres de canciones para empezar.")
        self._status_error = False
        self._report_text = ""
        self._update_report()

    # ================= propiedades =================
    @Property(bool, notify=stateChanged)
    def busy(self): return self._busy

    @Property(bool, notify=stateChanged)
    def cancelling(self): return self._cancelling

    @Property(str, notify=stateChanged)
    def task(self): return self._task or ""

    @Property(float, notify=stateChanged)
    def progress(self): return self._progress

    @Property(str, notify=stateChanged)
    def status(self): return self._status

    @Property(bool, notify=stateChanged)
    def statusError(self): return self._status_error

    @Property(int, notify=stateChanged)
    def choiceCount(self): return len(self._selected)

    @Property(str, notify=stateChanged)
    def choiceText(self):
        count = len(self._selected)
        return tr("{n} canción elegida" if count == 1 else "{n} canciones elegidas", n=count)

    @Property("QVariantList", notify=imagesChanged)
    def attachments(self): return [{"name": item.name} for item in self._attachments]

    @Property(int, notify=imagesChanged)
    def activeIndex(self): return -1 if self._active is None else self._active

    @Property(int, notify=imagesChanged)
    def imageVersion(self): return self._image_version

    @Property(str, notify=imagesChanged)
    def imageInfo(self):
        if self._active is None:
            return tr("Sin imágenes")
        item = self._attachments[self._active]
        return f"{self._active + 1}/{len(self._attachments)} · {item.image.width} × {item.image.height} px"

    @Property(int, constant=True)
    def maxImages(self): return MAX_IMAGES

    @Property(str, notify=namesChanged)
    def names(self): return self._names

    @Property(str, notify=rawChanged)
    def rawText(self): return self._raw_text

    @Property(QObject, constant=True)
    def songModel(self): return self._song_model

    @Property(bool, notify=tableChanged)
    def allChecked(self):
        total, checked, _ = self._table.counts()
        return total > 0 and checked == total

    @Property(QObject, constant=True)
    def groupModel(self): return self._group_model

    @Property(str, notify=tableChanged)
    def songCountText(self):
        total, checked, visible = self._table.counts()
        text = tr("{n} canción" if total == 1 else "{n} canciones", n=total) + " · " + \
            tr("{n} marcada" if checked == 1 else "{n} marcadas", n=checked)
        if self._table.filter_text.strip():
            text += " · " + tr("{n} visibles", n=visible)
        return text

    @Property(str, notify=tableChanged)
    def songFilter(self): return self._table.filter_text

    @Property(int, constant=True)
    def maxQueries(self): return MAX_QUERIES

    @Property(str, notify=stateChanged)
    def playlistInput(self): return self._playlist_input

    def _group_rows(self):
        groups = []
        for index in sorted(self._groups):
            group = self._groups[index]
            if not self._group_visible(group):
                continue
            chosen = self._selected.get(index, "")
            groups.append({
                "index": index, "query": group["query"], "failed": bool(group.get("failed")),
                "reason": group.get("reason", ""), "message": self._group_message(group),
                "candidates": [dict(candidate, chosen=candidate["key"] == chosen,
                                    label=self._labels.get(candidate["key"], ""))
                               for candidate in group["candidates"]],
            })
        return groups

    def _push_table(self):
        self._song_model.sync(self._table.rows())
        self.tableChanged.emit()

    def _push_results(self):
        self._group_model.sync(self._group_rows())
        self.resultsChanged.emit()

    @Property(str, notify=resultsChanged)
    def resultFilter(self): return self._result_filter

    @Property(str, notify=reportChanged)
    def reportText(self): return self._report_text

    @Property(bool, notify=reportChanged)
    def canRetry(self): return bool(self._pending_indices(retryable=True)) and not self._busy

    # ================= utilidades =================
    def _say(self, text, error=False):
        self._status, self._status_error = text, error
        self.stateChanged.emit()

    def _set_busy(self, busy):
        self._busy = busy
        if not busy:
            self._cancelling = False
        self.stateChanged.emit()
        self._push_table()
        self.reportChanged.emit()

    def _set_progress(self, index, total):
        self._progress = index / max(1, total)
        self.stateChanged.emit()

    def snapshot_image(self):
        with self._lock:
            if self._active is None or self._active >= len(self._attachments):
                return None
            return qimage_from_pil(self._attachments[self._active].image)

    def _bump_image(self):
        self._image_version += 1
        self.imagesChanged.emit()

    # ================= imágenes =================
    @Slot()
    def attachFiles(self):
        if self._busy:
            return
        paths, _ = QFileDialog.getOpenFileNames(
            None, tr("Adjuntar imágenes con nombres de canciones"), "",
            tr("Imágenes") + " (*.png *.jpg *.jpeg *.webp *.bmp *.tif *.tiff);;" + tr("Todos") + " (*.*)")
        if paths:
            self._start_loading(paths=paths)

    @Slot(list)
    def attachPaths(self, paths):
        if not self._busy and paths:
            self._start_loading(paths=[str(path).replace("file:///", "") for path in paths])

    @Slot()
    def pasteImage(self):
        if not self._busy:
            self._start_loading(clipboard=True)

    def _start_loading(self, paths=(), clipboard=False):
        self._errors.clear()
        self._task = "load"
        job = LoadImagesJob()
        self._job = job
        self._set_busy(True)
        self._progress = -1
        self._say(tr("Cargando imágenes…"))
        used = sum(item.original.width * item.original.height for item in self._attachments)
        threading.Thread(target=job.run, kwargs=dict(paths=paths, clipboard=clipboard,
                         slots=MAX_IMAGES - len(self._attachments), used_pixels=used), daemon=True).start()
        self._timer.start()

    @Slot(int)
    def selectImage(self, index):
        if not self._busy and 0 <= index < len(self._attachments):
            self._active = index
            self._bump_image()

    @Slot()
    def removeImage(self):
        if self._active is None or self._busy:
            return
        with self._lock:
            self._attachments.pop(self._active).close()
        self._active = min(self._active, len(self._attachments) - 1) if self._attachments else None
        self._bump_image()
        self._say(tr("Imagen retirada del programa. El archivo original se conserva."))

    @Slot()
    def clearImages(self):
        if self._busy:
            return
        with self._lock:
            for item in self._attachments:
                item.close()
            self._attachments.clear()
        self._active = None
        self._bump_image()
        self._say(tr("Adjuntos retirados. El texto que ya extrajiste se conserva para que puedas editarlo."))

    @Slot(float, float, float, float)
    def crop(self, x1, y1, x2, y2):
        """Recorta la imagen activa; las coordenadas son fracciones (0 a 1) del ancho y alto."""
        if self._busy or self._active is None:
            return
        try:
            item = self._attachments[self._active]
            width, height = item.image.size
            left, right = sorted((max(0, min(1, x1)), max(0, min(1, x2))))
            top, bottom = sorted((max(0, min(1, y1)), max(0, min(1, y2))))
            box = (round(left * width), round(top * height), round(right * width), round(bottom * height))
            if box[2] - box[0] < 8 or box[3] - box[1] < 8:
                raise ValueError(tr("Arrastra sobre la imagen para seleccionar un área más grande."))
            with self._lock:
                item.crop(box)
            self._bump_image()
            self._say(tr("Recorte aplicado a la copia de trabajo. «Restaurar» recupera la imagen completa."))
        except ValueError as exc:
            self._say(str(exc), True)

    @Slot()
    def restoreImage(self):
        if self._active is not None and not self._busy:
            with self._lock:
                self._attachments[self._active].restore()
            self._bump_image()
            self._say(tr("Imagen completa restaurada."))

    # ================= texto =================
    @Slot(str)
    def setNames(self, text):
        """El usuario escribió en el cuadro de texto."""
        if text == self._names:
            return
        self._names = text
        if not self._busy and text.strip() != self._synced_text:
            if self._searched_queries:
                self._clear_results()
                self._say(tr("Cambiaste los nombres. Vuelve a buscar para elegir las nuevas coincidencias."))
            self._names_timer.start()

    def _replace_names(self, text):
        self._names = text
        self.namesChanged.emit()

    def _append_raw(self, text):
        self._raw_text = (self._raw_text + "\n\n" if self._raw_text.strip() else "") + text
        self.rawChanged.emit()

    def _sync_table_from_text(self, invalidate=True):
        self._names_timer.stop()
        text = self._names.strip()
        if text == self._synced_text:
            return
        if invalidate and self._searched_queries:
            self._clear_results()
        parsed = parse_exported_playlist(text)
        if parsed is not None:
            self._table.set_imported(parsed.songs)
            self._import_summary = tr("Lista por bloques: {n} canciones · {d} duraciones.",
                                      n=len(parsed.songs), d=parsed.timed_blocks)
            if parsed.warnings:
                self._import_summary += " " + tr("Hay bloques para revisar.")
            if text != self._last_pasted_export:
                self._append_raw(tr("Texto pegado original") + "\n" + "─" * 20 + "\n" + text)
                self._last_pasted_export = text
            self._say(self._import_summary)
        else:
            self._table.set_queries(clean_queries(text, enforce_limit=False), self._query_sources, self._query_uploaders)
            self._import_summary = ""
        self._synced_text = text
        self._push_table()
        self._update_report()

    @Slot()
    def cleanText(self):
        if self._busy:
            return
        text = self._names.strip()
        if not text:
            self._say(tr("Pega primero el texto de la lista o lee una imagen."), True)
            return
        if parse_exported_playlist(text) is not None:
            self.reviewTable()
            return
        lines = organize_playlist_text(text, uploaders=self._query_uploaders)
        self._clear_results()
        self._replace_names("\n".join(lines))
        self._sync_table_from_text()
        self._say(tr("Lista organizada: {n} entradas. Revisa títulos incompletos antes de buscar. Ctrl + Z deshace la limpieza.",
                     n=len(lines)))

    @Slot()
    def startOcr(self):
        if not self._attachments or self._busy:
            return
        self._clear_results()
        self._say(tr("Preparando OCR local. La primera lectura puede tardar unos segundos."))
        images = [(item.name, item.image.copy()) for item in self._attachments]
        self._start_job(dict(task="ocr"), images)

    @Slot()
    def reviewTable(self):
        if self._busy:
            return
        self._sync_table_from_text()
        self.goToStep.emit(1)
        self._say((self._import_summary + " " if self._import_summary else "") +
                  tr("Revisa la tabla y marca las canciones que quieras buscar."))

    # ================= tabla =================
    def _table_changed(self):
        """Cualquier cambio manual de la tabla invalida las búsquedas y reescribe el texto."""
        self._clear_results()
        self._names_timer.stop()
        text = "\n".join(self._table.queries())
        self._replace_names(text)
        self._synced_text = text
        self._import_summary = ""
        self._push_table()
        self._say(tr("Tabla actualizada. Busca las canciones marcadas cuando termines de revisarlas."))
        self._update_report()

    @Slot(int)
    def toggleRow(self, item):
        if not self._busy:
            self._table.toggle(item)
            self._table_changed()

    @Slot(bool)
    def checkAll(self, checked):
        if not self._busy:
            self._table.check_all(checked)
            self._table_changed()

    @Slot()
    def toggleAll(self):
        if not self._busy:
            self._table.toggle_all()
            self._table_changed()

    @Slot(list)
    def removeRows(self, ids):
        if not self._busy:
            self._table.remove([int(item) for item in ids])
            self._table_changed()

    @Slot(result=int)
    def addRow(self):
        if self._busy:
            return -1
        item = self._table.add_empty()
        self._table_changed()
        return item

    @Slot(str)
    def sortBy(self, column):
        if not self._busy:
            self._table.sort_by(column)
            self._table_changed()

    @Slot(int, str, str, result=str)
    def editField(self, item, field, value):
        """Corrige artista o título de una fila. Devuelve el motivo del rechazo, o vacío."""
        if self._busy:
            return ""
        try:
            self._table.edit_value(item, field, value)
        except ValueError as exc:
            message = tr(str(exc))
            self._say(message, True)
            return message
        self._table_changed()
        return ""

    @Slot(str)
    def setSongFilter(self, text):
        self._table.filter_text = text
        self._push_table()

    @Slot(str)
    def setPlaylistInput(self, text):
        self._playlist_input = text
        self.stateChanged.emit()

    # ================= trabajos =================
    def _start_job(self, config, images=()):
        if self._busy:
            return
        self._errors.clear()
        self._task = config["task"]
        job = ImportJob()
        self._job = job
        self._set_busy(True)
        self._progress = -1
        self.stateChanged.emit()
        threading.Thread(target=job.run, args=(config, images), daemon=True).start()
        self._timer.start()

    @Slot()
    def cancelJob(self):
        job = self._job
        if job is None or not self._busy or self._cancelling:
            return
        self._cancelling = True
        self._say(tr("Cancelando la operación…"))
        threading.Thread(target=job.cancel, daemon=True).start()

    @Slot()
    def checkPlaylist(self):
        if self._busy:
            return
        try:
            url = playlist_url(self._playlist_input)
            if not url:
                raise ValueError(tr("Pega el enlace de la playlist original para comprobar sus entradas."))
        except ValueError as exc:
            self._say(tr(str(exc)), True)
            return
        self._playlist_report, self._playlist_error = None, ""
        self._start_job(dict(task="playlist", playlist_url=url, cookies=self._cookies()))

    @Slot()
    def startSearch(self):
        if self._busy:
            return
        try:
            self._sync_table_from_text()
            queries = clean_queries("\n".join(self._table.queries(checked_only=True)))
            source_url = playlist_url(self._playlist_input)
            if not queries:
                raise ValueError(tr("Marca al menos una canción en la tabla para buscarla."))
        except ValueError as exc:
            self._say(tr(str(exc)) if str(exc) in i18n.EN else str(exc), True)
            return
        self._clear_results()
        self._searched_queries = queries
        groups = {query.casefold(): [] for query in queries}
        for item, entry in self._table.entries():
            normalized = clean_queries(entry.query, enforce_limit=False)
            if entry.checked and normalized and normalized[0].casefold() in groups:
                groups[normalized[0].casefold()].append(item)
        self._row_groups = [groups[query.casefold()] for query in queries]
        self._row_ids = [group[0] for group in self._row_groups]
        for index in range(len(self._row_ids)):
            self._set_search_state(index, "En cola")
        self.goToStep.emit(2)
        self._say(tr("Buscando coincidencias por nombre en YouTube…"))
        self._playlist_report, self._playlist_error = None, ""
        self._start_job(dict(task="search", queries=queries, cookies=self._cookies(), playlist_url=source_url))

    def _set_search_state(self, index, state):
        rows = self._row_groups[index] if index < len(self._row_groups) else [self._row_ids[index]]
        for item in rows:
            self._table.set_state(item, state)
        self._push_table()

    def _pending_indices(self, retryable=False):
        return [index for index in range(len(self._searched_queries)) if index not in self._selected
                and (not retryable or not self._outcomes.get(index, {}).get("candidates"))]

    @Slot()
    def retryPending(self):
        if self._busy:
            return
        indices = self._pending_indices(retryable=True)
        if not indices:
            return
        for index in indices:
            self._remove_query_results(index)
            self._outcomes.pop(index, None)
            self._query_errors.pop(index, None)
            if index < len(self._row_ids):
                self._set_search_state(index, "En cola")
        self.goToStep.emit(2)
        self._say(tr("Reintentando {n} pendientes. Se conservan las elecciones anteriores.", n=len(indices)))
        self._start_job(dict(task="search", queries=[self._searched_queries[i] for i in indices],
                             query_indices=indices, cookies=self._cookies()))

    def _remove_query_results(self, index):
        group = self._groups.pop(index, None)
        if group:
            for candidate in group["candidates"]:
                self._candidate_map.pop(candidate["key"], None)
                self._labels.pop(candidate["key"], None)
        self._push_results()

    def _clear_results(self):
        self._preview.stop()
        self._groups.clear()
        self._labels.clear()
        self._candidate_map.clear()
        self._outcomes.clear()
        self._query_errors.clear()
        self._transfer_report = None
        self._selected.clear()
        self._searched_queries = []
        self._row_ids = []
        self._row_groups = []
        self._result_counts.clear()
        self._table.reset_states()
        self._push_results()
        self._push_table()
        self.stateChanged.emit()

    # ================= resultados =================
    def _group_message(self, group):
        if group["candidates"]:
            return ""
        return (tr("Error: ") if group.get("failed") else tr("Sin resultados: ")) + \
            (group.get("reason") or self._query_errors.get(group["index"]) or
             tr("Revisa el nombre o vuelve a buscar."))

    def _group_visible(self, group):
        words = normalize(self._result_filter).split()
        if not words:
            return True

        def match(*texts):
            haystack = normalize(" ".join(str(text or "") for text in texts))
            return all(word in haystack for word in words)

        return match(group["query"]) or any(match(group["query"], c["title"], c["channel"]) for c in group["candidates"])

    @Slot(str)
    def setResultFilter(self, text):
        self._result_filter = text
        self._push_results()

    def _add_candidates(self, event):
        index = event["index"]
        self._outcomes[index] = event
        candidates = []
        for number, candidate in enumerate(event["candidates"]):
            key = f"{index}:{number}"
            row = dict(key=key, title=candidate["title"], channel=candidate.get("channel", ""),
                       duration=candidate.get("duration"), durationText=duration_label(candidate.get("duration")),
                       url=candidate["url"])
            candidates.append(row)
            self._candidate_map[key] = (index, row)
        self._groups[index] = dict(index=index, query=event["query"], candidates=candidates,
                                   failed=bool(event.get("failed")), reason=event.get("reason", ""))
        entry = None
        if index < len(self._row_ids):
            row_id = self._row_ids[index]
            count = len(candidates)
            self._result_counts[row_id] = count
            self._set_search_state(index, "Error" if event.get("failed") else
                                   tr("{n} resultado" if count == 1 else "{n} resultados", n=count) if count
                                   else "Sin resultados")
            entry = self._table.items.get(row_id)
        metadata = dict(title=entry.title, artist=entry.artist, duration=entry.duration) if entry else {}
        match = best_candidate(event["query"], event["candidates"], **metadata)
        if match is not None and index not in self._selected:
            position, comparison = match
            review_reason = (entry.review_reason if entry else "") or comparison.review_reason
            key = candidates[position]["key"]
            if comparison.auto_select and not review_reason:
                self._select(key, label="✓ Auto · " + (tr("exacta") if comparison.exact else tr("similar")))
            else:
                event["auto_review_reason"] = review_reason + " " + tr("Revisa las alternativas y elige una manualmente.")
                self._labels[key] = tr("Revisar · sin marcar")
                if index < len(self._row_ids):
                    self._set_search_state(index, "Revisar coincidencia")
                self._update_report()
        self._push_results()

    def _select(self, key, *, label=None, toggle=False):
        """Única ruta de selección, para propuestas automáticas y cambios manuales."""
        index, _ = self._candidate_map[key]
        self._transfer_report = None
        previous = self._selected.pop(index, None)
        if previous:
            self._labels[previous] = ""
        if not toggle or previous != key:
            self._selected[index] = key
            self._labels[key] = label or tr("✓ Elegida")
        if index < len(self._row_ids):
            count = self._result_counts.get(self._row_ids[index], 0)
            self._set_search_state(index, "Elegida" if index in self._selected else
                                   tr("{n} resultado" if count == 1 else "{n} resultados", n=count))
        self._push_results()
        self.stateChanged.emit()
        if not self._busy:
            self._update_report()

    @Slot(str)
    def choose(self, key):
        if not self._busy and key in self._candidate_map:
            self._select(key, toggle=True)

    @Slot(str)
    def viewResult(self, key):
        if key in self._candidate_map:
            webbrowser.open(self._candidate_map[key][1]["url"])

    # ================= informe =================
    def _update_report(self):
        lines = [tr("INFORME DE CANCIONES"), ""]
        source = self._playlist_report
        if source:
            lines.extend([tr("Playlist: {t}", t=source["title"]),
                          tr("{a} entradas en total · {b} con título · {c} no disponibles en la consulta.",
                             a=source["total"], b=source["readable"], c=len(source["unavailable"]))])
            for item in source["unavailable"]:
                lines.extend([tr("  Posición {p}: {t}", p=item["position"], t=item["title"]),
                              tr("  Motivo: {r}", r=item["reason"]),
                              tr("  Enlace: {u}", u=item["url"] or tr("YouTube no proporcionó un enlace.")), ""])
            if source.get("unaccounted"):
                lines.append(tr("ATENCIÓN: faltan por comprobar {n} entradas; YouTube devolvió una lista incompleta.",
                                n=source["unaccounted"]))
        elif self._playlist_error:
            lines.extend([tr("NO SE PUDO COMPROBAR LA PLAYLIST"), self._playlist_error])
        else:
            lines.append(tr("Playlist original sin comprobar. El texto copiado no permite detectar entradas ocultas/no disponibles. Pega el enlace en «Tabla de canciones → Playlist original» y pulsa «Comprobar»."))
        lines.append("")
        ambiguous = [(number, entry) for number, (_, entry) in enumerate(self._table.entries(), 1) if entry.review_reason]
        if ambiguous:
            lines.extend([tr("IMPORTACIÓN: {n} bloques ambiguos; se importaron sin dividir y sin marcar.", n=len(ambiguous)), ""])
            for number, entry in ambiguous:
                lines.extend([tr("Fila {n}: {t}", n=number, t=entry.title), entry.review_reason,
                              tr("Texto original:"), entry.raw_text, ""])
        if self._searched_queries:
            pending = self._pending_indices()
            lines.extend([tr("BÚSQUEDA: {a} consultas únicas · {b} elegidas · {c} pendientes.",
                             a=len(self._searched_queries), b=len(self._selected), c=len(pending)),
                          tr("Las entradas no disponibles de la playlist y los pendientes de búsqueda son recuentos separados."), ""])
            source_rows = sum(len(group) for group in self._row_groups)
            if source_rows > len(self._searched_queries):
                lines.extend([tr("TABLA: {a} filas marcadas → {b} consultas únicas; {c} repeticiones agrupadas. No son errores ni canciones perdidas.",
                                 a=source_rows, b=len(self._searched_queries), c=source_rows - len(self._searched_queries)), ""])
            for index in pending:
                outcome = self._outcomes.get(index)
                if outcome is None:
                    reason = self._query_errors.get(index) or tr("Consulta no completada (cancelación o cierre inesperado del proceso). Reintenta.")
                elif outcome.get("candidates"):
                    reason = outcome.get("auto_review_reason") or tr("Hay alternativas, pero ninguna está elegida. Revisa «Coincidencias» y selecciona una.")
                else:
                    reason = outcome.get("reason") or self._query_errors.get(index) or tr("YouTube no devolvió alternativas utilizables; revisa el nombre o reintenta.")
                label = tr("ERROR") if outcome and outcome.get("failed") else tr("PENDIENTE")
                lines.extend([f"{index + 1}. {self._searched_queries[index]}", f"   {label}: {reason}", ""])
            for index, outcome in sorted(self._outcomes.items()):
                attempts = outcome.get("search_attempts", [])
                if len(attempts) > 1:
                    lines.extend([tr("Consultas de {q}:", q=self._searched_queries[index]),
                                  *[tr("  • {q} → {r} resultados; {s} descartados.", q=attempt["query"],
                                       r=attempt["received"], s=attempt["skipped"]) for attempt in attempts], ""])
                if outcome.get("warnings"):
                    lines.extend([tr("Advertencias de {q}:", q=self._searched_queries[index]), *outcome["warnings"], ""])
        else:
            lines.append(tr("Todavía no se ha buscado ninguna canción por nombre."))
        if self._errors:
            lines.extend([tr("REGISTRO DE LA ÚLTIMA OPERACIÓN"), *self._errors])
        if self._transfer_report:
            lines.extend(["", transfer_text(self._transfer_report)])
        self._report_text = "\n".join(lines)
        self.reportChanged.emit()

    @Slot()
    def copyReport(self):
        QGuiApplication.clipboard().setText(self._report_text)
        self._say(tr("Informe copiado al portapapeles."))

    @Slot()
    def saveReport(self):
        path, _ = QFileDialog.getSaveFileName(None, tr("Guardar informe de canciones"), "informe_canciones.txt",
                                              tr("Texto") + " (*.txt)")
        if path:
            try:
                Path(path).write_text(self._report_text, encoding="utf-8")
                self._say(tr("Informe guardado."))
            except OSError as exc:
                self._say(tr("No se pudo guardar el informe: ") + str(exc), True)

    # ================= paso a descargas =================
    @Slot(result=str)
    def addSelected(self):
        """Devuelve 'ok', 'confirm' (hay enlaces repetidos: pedir confirmación) o 'fail'."""
        if self._busy or not self._selected:
            return "fail"
        try:
            self._sync_table_from_text(invalidate=False)
            if clean_queries("\n".join(self._table.queries(checked_only=True))) != self._searched_queries:
                raise ValueError(tr("Los nombres cambiaron. Busca de nuevo antes de añadir las canciones."))
            records = [dict(query=self._searched_queries[index], title=self._candidate_map[key][1]["title"],
                            url=self._candidate_map[key][1]["url"]) for index, key in sorted(self._selected.items())]
            self._transfer_report = selection_transfer(records)
            self._update_report()
        except ValueError as exc:
            self._say(str(exc), True)
            return "fail"
        report = self._transfer_report
        if report["repeated"]:
            self.goToStep.emit(3)
            self._say(tr("Atención: {a} elecciones corresponden a {b} videos únicos. Revisa los títulos que comparten enlace en el informe.",
                         a=report["selected"], b=report["unique"]), True)
            preview = transfer_text(report)
            if len(preview) > 1500:
                preview = preview[:1500] + "\n" + tr("…El detalle completo está en «Informe y pendientes».")
            self.confirmDuplicates.emit(preview + "\n\n" + tr("¿Añadir {n} videos únicos?", n=report["unique"]))
            return "confirm"
        return "ok" if self._finish_transfer() else "fail"

    @Slot(result=bool)
    def confirmAdd(self):
        return self._finish_transfer()

    def _finish_transfer(self):
        report = self._transfer_report
        try:
            if self._add_urls(report["urls"]) is False:
                self._say(tr("No se añadieron los enlaces a la cola. Comprueba si hay una descarga activa; tus elecciones se conservan."), True)
                return False
        except Exception as exc:
            self._errors.append(tr("No se pudieron añadir las elecciones: ") + str(exc))
            self._update_report()
            self.goToStep.emit(3)
            self._say(tr("No se pudo completar el paso a descargas. Tus elecciones se conservan; consulta el informe."), True)
            return False
        if self._on_transfer:
            self._on_transfer(dict(report, source_report=self._report_text))
        self.transferDone.emit()
        return True

    # ================= cierre =================
    @Slot()
    def reset(self):
        """Cierra el importador: cancela lo que esté en marcha y libera las imágenes."""
        self._preview.stop()
        job = self._job
        if job is not None and self._busy:
            threading.Thread(target=job.cancel, daemon=True).start()
        self._timer.stop()
        self._names_timer.stop()
        with self._lock:
            for item in self._attachments:
                item.close()
        self._init_state()
        self._push_table()
        self._push_results()
        for signal in (self.stateChanged, self.imagesChanged, self.namesChanged, self.rawChanged, self.reportChanged):
            signal.emit()

    # ================= eventos del trabajador =================
    def _poll(self):
        job = self._job
        if job is None:
            self._timer.stop()
            return
        for _ in range(30):
            try:
                event = job.events.get_nowait()
            except Exception:
                break
            try:
                self._handle(event, job)
            except Exception as exc:
                reason = f"No se pudo procesar una respuesta del trabajador: {type(exc).__name__}: {exc}"
                self._errors.append(reason)
                index = event.get("index") if isinstance(event, dict) else None
                if isinstance(index, int) and 0 <= index < len(self._searched_queries):
                    self._query_errors[index] = reason
                    self._remove_query_results(index)
                    self._add_candidates(dict(index=index, query=self._searched_queries[index], candidates=[],
                                              failed=True, reason=reason))

    def _handle(self, event, job):
        kind = event["event"]
        cancelled = job.cancelled.is_set()
        if kind == "import_status" and not cancelled:
            self._say(event["text"])
            index = event.get("query_index")
            if index is not None and index < len(self._row_ids):
                self._set_search_state(index, "Buscando…")
        elif kind == "import_error":
            self._errors.append(event["text"])
            if "query_index" in event:
                self._query_errors[event["query_index"]] = event["text"]
        elif kind == "playlist_report" and not cancelled:
            self._playlist_report, self._playlist_error = event["report"], ""
        elif kind == "playlist_error" and not cancelled:
            self._playlist_error = event["text"]
            self._errors.append("Playlist: " + event["text"])
        elif kind == "attachment_result":
            if cancelled:
                event["attachment"].close()
            else:
                with self._lock:
                    self._attachments.append(event["attachment"])
                self._active = len(self._attachments) - 1
                self._bump_image()
                self._set_progress(event["index"], event["total"])
        elif kind == "ocr_result" and not cancelled:
            self._query_uploaders.update(event.get("uploaders", {}))
            combined = clean_queries(self._names.strip() + "\n" + "\n".join(event["lines"]), enforce_limit=False)
            self._replace_names("\n".join(combined))
            for query in event["lines"]:
                self._query_sources.setdefault(query.casefold(), event["name"])
            self._append_raw(f"{event['name']}\n{'─' * 20}\n" + event.get("raw_text", "\n".join(event["lines"])))
            self._sync_table_from_text()
            self._set_progress(event["index"], event["total"])
        elif kind == "search_result" and not cancelled:
            self._add_candidates(event)
            self._set_progress(event.get("progress_index", event["index"] + 1), event["total"])
        elif kind == "job_done":
            self._timer.stop()
            self._finish_job(event)

    def _finish_job(self, event):
        cancelled = event["cancelled"]
        self._progress = 0 if cancelled else 1
        self._set_busy(False)
        if self._task == "search":
            for row in (item for group in self._row_groups for item in group):
                entry = self._table.items.get(row)
                if entry and entry.state in ("En cola", "Buscando…"):
                    self._table.set_state(row, "Cancelada" if cancelled else "Error")
            self._push_table()
        self._update_report()
        if self._task == "search" and self._searched_queries:
            pending = self._pending_indices()
            failures = sum(bool(o.get("failed")) for o in self._outcomes.values())
            empty = sum(not o.get("candidates") and not o.get("failed") for o in self._outcomes.values())
            missing = sum(index not in self._outcomes for index in range(len(self._searched_queries)))
            manual = sum(bool(self._outcomes.get(index, {}).get("candidates")) for index in pending)
            unavailable = len(self._playlist_report["unavailable"]) if self._playlist_report else 0
            problem = bool(pending or self._errors or unavailable or (self._playlist_report or {}).get("unaccounted"))
            summary = tr("{head}: {a} consultas · {b} elegidas · {c} pendientes ({d} sin resultados, {e} errores, {f} sin completar, {g} sin elegir).",
                         head=tr("Búsqueda cancelada") if cancelled else tr("Búsqueda terminada"),
                         a=len(self._searched_queries), b=len(self._selected), c=len(pending),
                         d=empty, e=failures, f=missing, g=manual)
            if self._playlist_report:
                summary += " " + tr("Playlist original: {a} entradas; {b} no disponibles.",
                                    a=self._playlist_report["total"], b=unavailable)
            if problem:
                self.goToStep.emit(3)
                self._say(summary + " " + tr("Revisa «Informe y pendientes» para ver nombres y motivos."), True)
            else:
                self._say(summary + " " + tr("Puedes revisar o cambiar las elecciones con doble clic."))
            return
        if self._task == "playlist":
            self.goToStep.emit(3)
            report = self._playlist_report
            if report:
                self._say(tr("Playlist comprobada: {a} entradas · {b} con título · {c} no disponibles. Consulta el informe.",
                             a=report["total"], b=report["readable"], c=len(report["unavailable"])),
                          bool(report["unavailable"] or report.get("unaccounted")))
            else:
                self._say(tr("Comprobación cancelada.") if cancelled else
                          tr("No se pudo comprobar la playlist. Consulta el informe."), True)
            return
        if cancelled:
            self._say(tr("Operación cancelada. Puedes continuar con los resultados que ya llegaron."))
        elif self._errors:
            self._say(tr("Algunos elementos fallaron. ") + " | ".join(self._errors[:2]), True)
        elif self._task == "load":
            self._say(tr("Imágenes cargadas. Arrastra para recortar o pulsa «Leer imágenes → texto»."))
        elif self._task == "ocr":
            if self._names.strip():
                self.goToStep.emit(1)
                self._say(tr("Tabla creada. Revisa artista y título, marca las canciones y busca coincidencias."))
            else:
                self._say(tr("No se encontró texto. Prueba con un recorte más claro o escribe los nombres."), True)
        elif not self._candidate_map:
            self._say(tr("No se encontraron coincidencias. Edita los nombres y vuelve a buscar."))
        else:
            self._say(tr("Resultados listos: {c} automáticamente. Revisa las opciones; puedes cambiarlas o quitarlas con doble clic.",
                         c=self.choiceText))
