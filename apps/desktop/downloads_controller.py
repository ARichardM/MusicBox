"""Página de Descargas: enlaces, cola, historial paginado e informe de canciones."""

from __future__ import annotations

import os
import subprocess
import sys
import threading
from datetime import datetime
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from PySide6.QtCore import QObject, Property, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QGuiApplication

import i18n
from historial import DownloadHistory, PAGE_SIZE
from audio_preview import AudioPreview
from importer_controller import ImporterController
from video_search_controller import VideoSearchController

tr = i18n.tr
PLAYLIST_HOSTS = ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")


def detect_playlist_mode(lines):
    """True si todos los enlaces son listas de reproducción explícitas de YouTube."""
    try:
        parts = [urlsplit(line) for line in lines]
    except ValueError:
        return False
    return bool(parts) and all((part.hostname or "").lower() in PLAYLIST_HOSTS
                               and part.path.rstrip("/") == "/playlist" and "list" in parse_qs(part.query)
                               for part in parts)


def unique_lines(text):
    return list(dict.fromkeys(line.strip() for line in text.splitlines() if line.strip()))


class DownloadsController(QObject):
    linksChanged = Signal()
    modeChanged = Signal()
    errorChanged = Signal()
    reportChanged = Signal()
    historyChanged = Signal()
    showTab = Signal(str)
    _historyLoaded = Signal(int, object, int, str)

    def __init__(self, bridge, parent=None):
        super().__init__(parent)
        self._bridge = bridge
        self._links = ""
        self._mode = "single"
        self._manual_mode = False
        self._error = ""
        self._report = ""
        self._last_queue = {}
        self._cookies = ""
        # historial
        self._history_store = DownloadHistory()
        self._history_rows = []
        self._history_total = 0
        self._history_offset = 0
        self._history_search = ""
        self._history_status = tr("Todavía no hay descargas guardadas.")
        self._history_loading = False
        self._history_generation = 0
        self._historyLoaded.connect(self._apply_history)
        self._refresh_timer = QTimer(self)
        self._refresh_timer.setSingleShot(True)
        self._refresh_timer.setInterval(200)
        self._refresh_timer.timeout.connect(self.historyRefresh)
        # vista previa y diálogos
        self._preview = AudioPreview(before_play=self._pause_main_player, parent=self)
        self._video_search = VideoSearchController(self.addUrls, parent=self)
        self._importer = ImporterController(self.addUrls, self.recordSongTransfer, self._preview,
                                            lambda: self._cookies, parent=self)

    # ================= objetos hijos =================
    @Property(QObject, constant=True)
    def preview(self): return self._preview

    @Property(QObject, constant=True)
    def videoSearch(self): return self._video_search

    @Property(QObject, constant=True)
    def importer(self): return self._importer

    def _pause_main_player(self):
        try:
            self._bridge.pausePlayer()
        except Exception:
            pass

    cookiesChanged = Signal()

    @Property(str, notify=cookiesChanged)
    def cookiePath(self): return self._cookies

    @Slot(str)
    def setCookies(self, path):
        """La ruta de cookies del panel de configuración también la usan las búsquedas."""
        self._cookies = path.strip()
        self.cookiesChanged.emit()

    # ================= enlaces =================
    @Property(str, notify=linksChanged)
    def links(self): return self._links

    @Property(str, notify=linksChanged)
    def linkSummary(self):
        count = len(unique_lines(self._links))
        if not count:
            return tr("Sin enlaces todavía")
        return tr("{n} enlace en cola" if count == 1 else "{n} enlaces en cola", n=count)

    @Property(int, notify=linksChanged)
    def linkCount(self): return len(unique_lines(self._links))

    @Property(str, notify=modeChanged)
    def mode(self): return self._mode

    @Property(str, notify=errorChanged)
    def inputError(self): return self._error

    def _set_error(self, text):
        if text != self._error:
            self._error = text
            self.errorChanged.emit()

    @Slot(str)
    def setLinks(self, text):
        if text == self._links:
            return
        self._links = text
        self._set_error("")
        lines = unique_lines(text)
        if lines and not self._manual_mode:
            self._set_mode("playlist" if detect_playlist_mode(lines) else "single")
        self.linksChanged.emit()

    def _set_mode(self, mode):
        if mode != self._mode:
            self._mode = mode
            self.modeChanged.emit()

    @Slot(str)
    def setMode(self, mode):
        """Elegir el modo a mano desactiva la detección automática."""
        self._manual_mode = True
        self._set_mode(mode)

    @Slot()
    def clearLinks(self):
        self._links = ""
        self._manual_mode = False
        self._set_error("")
        self.linksChanged.emit()

    @Slot()
    def pasteLinks(self):
        text = (QGuiApplication.clipboard().text() or "").strip()
        if not text:
            self._set_error(tr("Copia un enlace y vuelve a pulsar Pegar."))
            return
        self.setLinks((self._links.rstrip("\n") + "\n" if self._links.strip() else "") + text)

    @Slot(list, result=bool)
    def addUrls(self, urls):
        """Añade enlaces a la cola sin repetir los que ya estaban (como el sistema clásico)."""
        if self._bridge.downloadRunning:
            return False
        incoming = list(urls)
        unique = list(dict.fromkeys(url.strip() for url in incoming if url.strip()))
        existing = set(unique_lines(self._links))
        additions = [url for url in unique if url not in existing]
        self._last_queue = dict(incoming=len(incoming), unique=len(unique), added=len(additions),
                                existing=len(unique) - len(additions), repeated=len(incoming) - len(unique))
        if not unique:
            self._set_error(tr("No hay enlaces válidos para añadir. Revisa las elecciones."))
            return False
        if additions:
            text = self._links.rstrip("\n")
            self._links = (text + "\n" if text.strip() else "") + "\n".join(additions)
        self._manual_mode = True
        self._set_mode("single")
        self._set_error("")
        self.linksChanged.emit()
        self._bridge.setDownloadMessage(
            tr("{a} enlaces nuevos · {b} ya estaban en cola", a=len(additions), b=len(unique) - len(additions)),
            tr("{a} enlaces recibidos · {b} únicos. Se descargan en el orden de la cola. Elige MP3 o MP4 y pulsa Descargar.",
               a=len(incoming), b=len(unique)))
        if len(incoming) != len(additions):
            self._bridge.appendDownloadLog(
                tr("Cola: {a} enlaces recibidos → {b} únicos; {c} nuevos y {d} ya presentes.",
                   a=len(incoming), b=len(unique), c=len(additions), d=len(unique) - len(additions)), "warning")
            self.showTab.emit("log")
        return True

    @Slot(str, bool, str, str, str, str, str, result=bool)
    def start(self, raw_urls, playlist, items, media_format, quality, destination, cookies):
        self._cookies = cookies.strip()
        error = self._bridge.startDownloadFromPage(raw_urls, playlist, items, media_format, quality,
                                                   destination, cookies)
        self._set_error(error)
        if not error:
            self.showTab.emit("files")
        return not error

    # ================= informe de canciones =================
    @Property(str, notify=reportChanged)
    def importReport(self): return self._report

    def recordSongTransfer(self, report):
        """Conserva nombres y motivos aunque se cierre el importador."""
        stats = self._last_queue or dict(added=0, existing=0)
        summary = tr("{a} elecciones → {b} videos únicos · {c} nuevos · {d} ya en cola",
                     a=report["selected"], b=report["unique"], c=stats["added"], d=stats["existing"])
        detail = report["source_report"] + "\n\n" + tr("COLA: ") + summary
        if self._report.strip():
            self._report += "\n\n" + "═" * 40 + "\n\n"
        self._report += detail
        self.reportChanged.emit()
        self._bridge.setDownloadMessage(
            summary, tr("La descarga aún no ha empezado. El detalle de pendientes y duplicados está en «Informe de canciones»."))
        self._bridge.appendDownloadLog(summary, "warning" if report["repeated"] else "info")
        self.showTab.emit("report")

    @Slot()
    def clearReport(self):
        self._report = ""
        self.reportChanged.emit()

    # ================= historial =================
    @Property("QVariantList", notify=historyChanged)
    def historyRows(self): return self._history_rows

    @Property(str, notify=historyChanged)
    def historyStatus(self): return self._history_status

    @Property(bool, notify=historyChanged)
    def historyLoading(self): return self._history_loading

    @Property(bool, notify=historyChanged)
    def historyHasPrevious(self): return self._history_offset > 0

    @Property(bool, notify=historyChanged)
    def historyHasNext(self): return self._history_offset + PAGE_SIZE < self._history_total

    @Property(str, notify=historyChanged)
    def historySearch(self): return self._history_search

    @Slot(str)
    def historySearchFor(self, text):
        self._history_search, self._history_offset = text, 0
        self.historyRefresh()

    @Slot()
    def historyShowAll(self):
        self.historySearchFor("")

    @Slot(int)
    def historyMove(self, direction):
        self._history_offset = max(0, self._history_offset + direction * PAGE_SIZE)
        self.historyRefresh()

    @Slot()
    def historyScheduleRefresh(self):
        if not self._refresh_timer.isActive():
            self._refresh_timer.start()

    @Slot()
    def historyRefresh(self):
        self._refresh_timer.stop()
        self._history_generation += 1
        generation, search, offset = self._history_generation, self._history_search, self._history_offset
        self._history_loading = True
        self.historyChanged.emit()

        def read():
            try:
                rows, total = self._history_store.page(search, offset)
                self._historyLoaded.emit(generation, rows, total, "")
            except Exception as exc:
                self._historyLoaded.emit(generation, [], 0, str(exc))

        threading.Thread(target=read, daemon=True).start()

    @Slot(int, object, int, str)
    def _apply_history(self, generation, rows, total, error):
        if generation != self._history_generation:
            return
        self._history_loading = False
        if error:
            self._history_status = tr("No se pudo leer el historial. ") + error
            self.historyChanged.emit()
            return
        shaped = []
        for row in rows:
            try:
                date = datetime.fromisoformat(row["created_at"]).astimezone().strftime("%d/%m/%y %H:%M")
            except (ValueError, KeyError):
                date = ""
            shaped.append({"id": row["id"], "date": date,
                           "title": row["title"] + (f" · {row['artist']}" if row["artist"] else ""),
                           "format": row["format"], "existing": row["state"] == "existing",
                           "stateText": tr("Existente") if row["state"] == "existing" else tr("Guardado"),
                           "filename": row["filename"], "sourceUrl": row["source_url"]})
        self._history_rows = shaped
        self._history_total = total
        if shaped:
            self._history_status = tr("{a}–{b} de {c} registros", a=self._history_offset + 1,
                                      b=self._history_offset + len(shaped), c=total)
        elif self._history_search.strip():
            self._history_status = tr("Sin resultados.")
        else:
            self._history_status = tr("Todavía no hay descargas guardadas.")
        self.historyChanged.emit()

    def _record(self, record_id):
        return next((row for row in self._history_rows if row["id"] == record_id), None)

    @Slot(int, bool)
    def historyOpen(self, record_id, folder):
        record = self._record(record_id)
        if not record:
            return
        path = Path(record["filename"])
        path = path.parent if folder else path
        if not path.exists():
            self._history_status = tr("El archivo o la carpeta ya no existe en esa ubicación.")
            self.historyChanged.emit()
            return
        self.openPath(str(path))

    @Slot(int)
    def historyQueue(self, record_id):
        record = self._record(record_id)
        if record and record["sourceUrl"] and not self._bridge.downloadRunning:
            self.addUrls([record["sourceUrl"]])

    @Slot(str)
    def openPath(self, path):
        try:
            if os.name == "nt":
                os.startfile(str(path))
            else:
                subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", str(path)])
        except OSError as exc:
            self._set_error(tr("No se pudo abrir: ") + str(exc))
