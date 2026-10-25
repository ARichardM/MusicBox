"""Búsqueda visual de YouTube: resultados con portada, escucha rápida y selección para la cola."""

from __future__ import annotations

import queue
import threading
import webbrowser

from PySide6.QtCore import QObject, Property, QTimer, Signal, Slot

import i18n
from canciones import ImportJob


def duration_label(value):
    try:
        value = int(value)
        if value < 0:
            return i18n.tr("Duración desconocida")
        return (f"{value // 3600}:{value // 60 % 60:02d}:{value % 60:02d}" if value >= 3600
                else f"{value // 60}:{value % 60:02d}")
    except (ValueError, TypeError, OverflowError):
        return i18n.tr("Duración desconocida")


class VideoSearchController(QObject):
    changed = Signal()
    resultsChanged = Signal()
    closeRequested = Signal()

    def __init__(self, add_urls, parent=None):
        super().__init__(parent)
        self._add_urls = add_urls
        self._job = None
        self._busy = False
        self._status = i18n.tr("Escribe un título o artista y pulsa Buscar. No se consulta mientras escribes.")
        self._status_error = False
        self._results = []
        self._errors = []
        self._warnings = []
        self._received = False
        self._cancelling = False
        self._timer = QTimer(self)
        self._timer.setInterval(60)
        self._timer.timeout.connect(self._poll)

    # ---- propiedades ----
    @Property(bool, notify=changed)
    def busy(self): return self._busy

    @Property(str, notify=changed)
    def status(self): return self._status

    @Property(bool, notify=changed)
    def statusError(self): return self._status_error

    @Property("QVariantList", notify=resultsChanged)
    def results(self): return self._results

    @Property(int, notify=resultsChanged)
    def selectedCount(self): return sum(1 for row in self._results if row["selected"])

    @Property(bool, notify=changed)
    def cancelling(self): return self._busy and self._cancelling

    def _say(self, text, error=False):
        self._status, self._status_error = text, error
        self.changed.emit()

    # ---- acciones ----
    @Slot(str, str)
    def search(self, query, cookies):
        if self._busy:
            return
        query = " ".join(query.split())
        if not query or len(query) > 300:
            self._say(i18n.tr("Escribe entre 1 y 300 caracteres para buscar."), True)
            return
        self._results = []
        self.resultsChanged.emit()
        self._errors, self._warnings, self._received = [], [], False
        self._job = ImportJob()
        self._busy = True
        self._cancelling = False
        self._say(i18n.tr("Buscando hasta 10 videos en YouTube…"))
        config = dict(task="browse", query=query, cookies=cookies)
        threading.Thread(target=self._job.run, args=(config,), daemon=True).start()
        self._timer.start()

    @Slot()
    def cancel(self):
        if self._busy and self._job and not self._cancelling:
            self._cancelling = True
            self._say(i18n.tr("Cancelando la búsqueda…"))
            threading.Thread(target=self._job.cancel, daemon=True).start()

    @Slot(int, bool)
    def setSelected(self, index, selected):
        if 0 <= index < len(self._results) and self._results[index]["selected"] != selected:
            self._results[index] = dict(self._results[index], selected=selected)
            self.resultsChanged.emit()

    @Slot(bool)
    def checkAll(self, selected):
        self._results = [dict(row, selected=selected) for row in self._results]
        self.resultsChanged.emit()

    @Slot(int)
    def openYoutube(self, index):
        try:
            if not webbrowser.open(self._results[index]["url"]):
                raise OSError(i18n.tr("No se pudo abrir el navegador predeterminado."))
            self._say(i18n.tr("Abierto en YouTube para escuchar. No se ha añadido ni descargado ningún archivo."))
        except Exception as exc:
            self._say(i18n.tr("No se pudo abrir el reproductor: ") + str(exc), True)

    @Slot(result=bool)
    def addSelected(self):
        if self._busy:
            return False
        urls = [row["url"] for row in self._results if row["selected"]]
        if not urls:
            self._say(i18n.tr("Marca al menos un video para añadirlo."), True)
            return False
        try:
            if self._add_urls(urls) is False:
                self._say(i18n.tr("No se pudo añadir a la cola; comprueba si hay una descarga activa. Se conservan tus elecciones."), True)
                return False
        except Exception as exc:
            self._say(i18n.tr("No se pudo añadir a la cola: ") + str(exc), True)
            return False
        return True

    @Slot()
    def reset(self):
        """Cierra el diálogo: cancela la búsqueda en curso y limpia los resultados."""
        if self._busy:
            self.cancel()
        self._results = []
        self.resultsChanged.emit()
        self._say(i18n.tr("Escribe un título o artista y pulsa Buscar. No se consulta mientras escribes."))

    # ---- eventos del trabajador ----
    def _poll(self):
        job = self._job
        if job is None:
            self._timer.stop()
            return
        for _ in range(15):
            try:
                event = job.events.get_nowait()
            except queue.Empty:
                break
            try:
                self._handle(event, job)
            except Exception as exc:
                self._errors.append(i18n.tr("No se pudo mostrar una respuesta: ") + str(exc))

    def _handle(self, event, job):
        kind = event["event"]
        cancelled = job.cancelled.is_set()
        if kind == "import_error":
            self._errors.append(event["text"])
        elif kind == "search_result" and not cancelled:
            self._received = True
            self._warnings = event.get("warnings", [])
            if event.get("failed"):
                self._errors.append(event.get("reason") or i18n.tr("No se pudo completar la búsqueda."))
            elif not event["candidates"]:
                self._errors.append(event.get("reason") or i18n.tr("YouTube no devolvió resultados. Prueba otro nombre."))
            seen, rows = set(), []
            for candidate in event["candidates"]:
                if candidate["url"] in seen:
                    continue
                seen.add(candidate["url"])
                rows.append(dict(title=candidate["title"], url=candidate["url"],
                                 channel=candidate.get("channel") or i18n.tr("Canal desconocido"),
                                 durationText=duration_label(candidate.get("duration")),
                                 thumbnail=candidate.get("thumbnail", ""), selected=False))
            self._results = rows
            self.resultsChanged.emit()
        elif kind == "job_done":
            self._busy = False
            self._cancelling = False
            self._timer.stop()
            if event["cancelled"]:
                self._say(i18n.tr("Búsqueda cancelada. Puedes volver a buscar."))
            elif self._errors:
                self._say(" | ".join(dict.fromkeys(self._errors)), True)
            elif not self._received:
                self._say(i18n.tr("No se recibió una respuesta completa. Vuelve a intentarlo más tarde."), True)
            else:
                text = i18n.tr("{n} resultados. «Escuchar aquí» usa el reproductor integrado; añadir a la cola no inicia la descarga.",
                               n=len(self._results))
                if self._warnings:
                    text += i18n.tr(" Aviso: ") + " | ".join(self._warnings[:2])
                self._say(text)
