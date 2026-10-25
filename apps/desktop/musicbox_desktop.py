"""Nueva interfaz de escritorio MusicBox (PySide6/QML).

La lógica pesada continúa en los módulos Python existentes. Esta entrada puede
convivir con la interfaz Tkinter mientras se migran sus herramientas una a una.
"""

from __future__ import annotations

import os
import json
from pathlib import Path
import subprocess
import sys
import threading
from urllib.parse import parse_qs, urlsplit
import hashlib
import random
import platform
import time
import uuid

from PySide6.QtCore import QObject, Property, QSettings, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QIcon
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuickControls2 import QQuickStyle
from PySide6.QtWidgets import QApplication, QFileDialog
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer

import descargador_gui as download_backend
import i18n
import updater
import youtube_streams
from app_paths import CODE_DIR, DATA_ROOT, DEFAULT_DOWNLOAD_DIR, worker_command
from historial import DownloadHistory
from musicbox_cloud import FirebaseDesktopClient
from click_stats import ClickStats, song_key
import track_identity as identity
from version import __version__
from downloads_controller import DownloadsController
from importer_controller import ImporterImageProvider


ROOT = DATA_ROOT
MEDIA_EXTENSIONS = {".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".mp4", ".mkv", ".webm"}
COVER_DIR = ROOT / "datos" / "portadas"


def _first(value, default=""):
    if isinstance(value, (list, tuple)):
        value = value[0] if value else default
    return str(value or default)


def media_info(path):
    """Return normalized tags and an extracted embedded cover for QML."""
    result = {"title": path.stem, "artist": i18n.tr("Artista desconocido"), "album": "", "duration": 0,
              "thumbnail": "", "subtitle": path.suffix[1:].upper(), "path": str(path),
              "downloaded": True}
    try:
        from mutagen import File
        audio = File(path, easy=False)
        if audio is None:
            return result
        result["duration"] = round(getattr(getattr(audio, "info", None), "length", 0) or 0)
        tags = audio.tags or {}
        def tag(*names):
            for name in names:
                value = tags.get(name)
                if value is not None:
                    return _first(getattr(value, "text", value))
            return ""
        result["title"] = tag("TIT2", "title", "\xa9nam") or result["title"]
        result["artist"] = tag("TPE1", "artist", "\xa9ART") or result["artist"]
        result["album"] = tag("TALB", "album", "\xa9alb")
        picture = next((frame for key, frame in tags.items() if str(key).startswith("APIC")), None)
        data = getattr(picture, "data", None)
        mime = getattr(picture, "mime", "image/jpeg")
        if data is None and getattr(audio, "pictures", None):
            data = audio.pictures[0].data; mime = audio.pictures[0].mime
        if data is None:
            covers = tags.get("covr")
            if covers: data = bytes(covers[0]); mime = "image/png" if data.startswith(b"\x89PNG") else "image/jpeg"
        if data:
            COVER_DIR.mkdir(parents=True, exist_ok=True)
            ext = ".png" if "png" in mime else ".jpg"
            cover = COVER_DIR / (hashlib.sha1(str(path).encode("utf-8")).hexdigest() + ext)
            if not cover.exists(): cover.write_bytes(data)
            result["thumbnail"] = QUrl.fromLocalFile(str(cover)).toString()
        result["subtitle"] = " · ".join(filter(None, (result["artist"], result["album"], path.suffix[1:].upper())))
    except Exception:
        pass
    return result


class MusicBoxBridge(QObject):
    searchResultsChanged = Signal()
    libraryChanged = Signal()
    foldersChanged = Signal()
    historyChanged = Signal()
    busyChanged = Signal()
    statusChanged = Signal()
    darkModeChanged = Signal()
    downloadStateChanged = Signal()
    downloadFilesChanged = Signal()
    downloadLogChanged = Signal()
    playerChanged = Signal()
    sidebarChanged = Signal()
    authChanged = Signal()
    discoveryChanged = Signal()
    downloadingIdsChanged = Signal()
    autoDownloadChanged = Signal()
    playlistsChanged = Signal()
    devicesChanged = Signal()
    noticeChanged = Signal()
    languageChanged = Signal()
    libraryFoldersChanged = Signal()
    updateChanged = Signal()
    _updateEvent = Signal(object)
    _searchFinished = Signal(object, str)
    _downloadEvent = Signal(object)
    _streamReady = Signal(object, str)
    _authResult = Signal(object, str)
    _remotePlaylists = Signal(object)
    _devicesPolled = Signal(object, bool)
    _deviceRevoked = Signal(str, bool)

    def __init__(self):
        super().__init__()
        self._search_results = []
        self._library = []
        self._library_all = []
        self._folders = []
        self._library_query = ""
        self._library_filter = "songs"
        self._library_folder = ""
        self._history = []
        self._busy = False
        self._settings = QSettings("MusicBox", "Desktop")
        self._language_setting = self._settings.value("language", "auto", str)
        if self._language_setting not in i18n.LANGUAGES: self._language_setting = "auto"
        i18n.set_language(self._language_setting)
        self._engine = None
        self._status = i18n.tr("Todo listo")
        self._recent_paths = list(self._settings.value("recentPlayed", [], list) or [])
        self._recent_queries = list(self._settings.value("recentQueries", [], list) or [])
        self._device_id = self._settings.value("deviceId", "", str) or uuid.uuid4().hex
        self._settings.setValue("deviceId", self._device_id)
        try:
            self._legacy_counts = json.loads(self._settings.value("playCounts", "{}", str) or "{}")
            self._clicks_stats = ClickStats(self._device_id, json.loads(self._settings.value("clickStats", "{}", str) or "{}"))
        except (TypeError, ValueError):
            self._legacy_counts = {}
            self._clicks_stats = ClickStats(self._device_id)
        self._recorded_source = ""
        self._cloud = FirebaseDesktopClient(self._settings.value("firebaseRefreshToken", "", str))
        self._user = {}
        self._auth_busy = bool(self._cloud.refresh_token)
        self._auth_error = ""
        self._cloud_snapshot = {}
        self._dark_mode = self._settings.value("darkMode", True, bool)
        self._sidebar_expanded = self._settings.value("sidebarExpanded", True, bool)
        self._search_generation = 0
        self._download_running = False
        self._download_progress = 0.0
        self._download_indeterminate = False
        self._download_status = i18n.tr("Todo listo para empezar")
        self._download_detail = i18n.tr("El progreso de cada archivo aparecerá aquí.")
        self._download_files = []
        self._download_log_lines = []
        self._download_badge = "idle"
        self._download_percent_text = "—"
        self._download_completed = 0
        self._download_error_count = 0
        self._download_had_summary = False
        self._download_from_page = False
        self._download_failed = False
        self._download_process = None
        self._download_cancelled = False
        self._downloading_ids = set()
        self._audio = QAudioOutput(self)
        self._audio.setVolume(float(self._settings.value("volume", 0.8)))
        self._audio.setMuted(self._settings.value("muted", False, bool))
        self._player = QMediaPlayer(self)
        self._player.setAudioOutput(self._audio)
        self._queue = []
        self._queue_index = -1
        self._repeat_mode = int(self._settings.value("repeatMode", 0))
        self._current = {}
        self._favorites = set(self._settings.value("favorites", [], list) or [])
        try:
            self._online_favorites_meta = json.loads(self._settings.value("onlineFavoritesMeta", "{}", str) or "{}")
        except (ValueError, TypeError):
            self._online_favorites_meta = {}
        self._library_playlist = ""
        try:
            self._playlists = json.loads(self._settings.value("playlistsData", "{}", str) or "{}")
        except (ValueError, TypeError):
            self._playlists = {}
        if "Favoritos" not in self._playlists:
            self._playlists["Favoritos"] = []
        try:
            self._online_tracks = json.loads(self._settings.value("onlineTracksData", "{}", str) or "{}")
        except (ValueError, TypeError):
            self._online_tracks = {}
        self._online_tracks = identity.normalize_online_tracks(self._online_tracks)
        self._path_ids = {}
        self._auto_attempted = set()
        self._library_scanned = False
        self._auto_download_playlists = set(self._settings.value("autoDownloadPlaylists", [], list) or [])
        try:
            self._playlist_descriptions = json.loads(self._settings.value("playlistDescriptions", "{}", str) or "{}")
        except (ValueError, TypeError):
            self._playlist_descriptions = {}
        self._notice = {}
        self._cache_fallback_tried = set()
        self._notice_retry = None
        self._download_queue = []
        self._update = {}
        self._update_state = "idle"
        self._update_progress = 0.0
        self._update_error = ""
        self._update_cancelled = False
        self._merge_favorites_sources()
        self._save_playlists()
        self._settings.setValue("favorites", list(self._favorites))
        self._player.positionChanged.connect(lambda _: self.playerChanged.emit())
        self._player.durationChanged.connect(lambda _: self.playerChanged.emit())
        self._player.playbackStateChanged.connect(self._playback_state_changed)
        self._player.mediaStatusChanged.connect(self._media_status)
        self._searchFinished.connect(self._apply_search)
        self._downloadEvent.connect(self._handle_download_event)
        self._streamReady.connect(self._play_stream)
        self._updateEvent.connect(self._handle_update_event)
        self._player.errorOccurred.connect(self._player_error)
        self._authResult.connect(self._apply_auth_result)
        self._remotePlaylists.connect(self._apply_remote_playlists)
        self._sync_base = {}
        self._devicesPolled.connect(self._apply_polled_devices)
        self._deviceRevoked.connect(self._device_revoked)
        self._device_timer = QTimer(self)
        self._device_timer.setInterval(45000)
        self._device_timer.timeout.connect(self._poll_devices)
        self._device_timer.start()
        self.refresh()
        if self._cloud.refresh_token:
            threading.Thread(target=self._restore_session, daemon=True).start()
        QTimer.singleShot(4000, self.checkForUpdate)

    @Property("QVariantList", notify=searchResultsChanged)
    def searchResults(self):
        return self._search_results

    def _save_clicks(self):
        self._settings.setValue("clickStats", json.dumps(self._clicks_stats.export(), ensure_ascii=False, separators=(",", ":")))

    def _migrate_legacy_clicks(self, rows):
        """Pasa los contadores antiguos (por ruta de archivo) a la clave común entre plataformas."""
        changed = False
        for row in rows:
            legacy = int(self._legacy_counts.get(row.get("path", ""), 0) or 0)
            if legacy:
                changed |= self._clicks_stats.import_legacy(song_key(row.get("title", "")), legacy, row.get("title", ""))
        if changed:
            self._save_clicks()

    def _playlist_entry_key(self, entry, by_path):
        row = by_path.get(entry)
        if row is not None:
            return song_key(row.get("title", ""), self._path_ids.get(entry, ""))
        if entry.startswith("youtube:"):
            return song_key("", entry.split("youtube:", 1)[1])
        meta = self._online_tracks.get(entry)
        return song_key(meta.get("title", ""), meta.get("id", "")) if meta else ""

    def _row_keys(self, row):
        """Claves de clics de una canción: por ID de YouTube y, para lo anterior, por título."""
        title_key = song_key(row.get("title", ""))
        vid = self._id_of(row)
        return {song_key(row.get("title", ""), vid), title_key} if vid else {title_key}

    def _clicks(self, keys):
        return sum(self._clicks_stats.count(key) for key in set(keys) if key)

    @Property("QVariantList", notify=libraryChanged)
    def library(self):
        return [dict(row, clicks=self._clicks(self._row_keys(row))) for row in self._library]

    @Property(int, notify=discoveryChanged)
    def totalClicks(self):
        return self._clicks_stats.total()

    @Property(int, notify=discoveryChanged)
    def playedSongs(self):
        return self._clicks_stats.played_songs()

    @Property("QVariantList", notify=foldersChanged)
    def folders(self):
        roots = self._library_roots()
        clicks = {}
        for row in self._library_all:
            name = self._folder_of(row.get("path", ""), roots)
            if name is not None:
                clicks.setdefault(name, set()).update(self._row_keys(row))
        return [dict(folder, clicks=self._clicks(clicks.get(folder.get("name"), ()))) for folder in self._folders]

    @Property("QVariantList", notify=playlistsChanged)
    def playlists(self):
        result = []
        by_path_clicks = {row.get("path"): row for row in self._library_all}
        for name, keys in self._playlists.items():
            thumb = ""
            for k in keys:
                if k in self._online_tracks:
                    thumb = self._online_tracks[k].get("thumbnail", "")
                    if thumb: break
                for row in self._library_all:
                    if row.get("path") == k and row.get("thumbnail"):
                        thumb = row.get("thumbnail")
                        break
                if thumb: break
            result.append({
                "name": name,
                "count": len(keys),
                "subtitle": i18n.tr("{n} canciones" if len(keys) != 1 else "{n} canción", n=len(keys)),
                "description": self._playlist_descriptions.get(name, ""),
                "clicks": self._clicks(self._playlist_entry_key(k, by_path_clicks) for k in keys),
                "thumbnail": thumb,
                "isFavorites": name == "Favoritos",
                "autoDownload": name in self._auto_download_playlists or self.isAutoDownload(f"playlist:{name}")
            })
        return sorted(result, key=lambda x: x["name"].casefold())

    @Property("QVariantList", notify=historyChanged)
    def history(self):
        return self._history

    @Property("QVariantList", notify=discoveryChanged)
    def recentQueries(self): return self._recent_queries

    @Property("QVariantList", notify=discoveryChanged)
    def recentPlayed(self):
        by_path = {row.get("path"): row for row in self._library_all}
        return [by_path[path] for path in self._recent_paths if path in by_path][:10]

    @Property("QVariantList", notify=discoveryChanged)
    def topPlayed(self):
        def clicks(row): return self._clicks(self._row_keys(row))
        rows = [row for row in self._library_all if clicks(row) > 0]
        return sorted(rows, key=lambda row: (-clicks(row), row.get("title", "").casefold()))[:5]

    @Property("QVariantList", notify=discoveryChanged)
    def topClicks(self):
        return [{"title": title, "clicks": clicks} for _key, title, clicks in self._clicks_stats.top(3)]

    @Property("QVariantList", notify=discoveryChanged)
    def topCollections(self):
        result = []
        grouped = {}
        roots = self._library_roots()
        for row in self._library_all:
            name = self._folder_of(row.get("path", ""), roots)
            if name is None:
                continue
            grouped.setdefault(name, []).append(row)
        for name, rows in grouped.items():
            plays = self._clicks(key for row in rows for key in self._row_keys(row))
            if plays:
                result.append({"name": name, "plays": plays, "subtitle": i18n.tr("Carpeta · {n} reproducciones", n=plays),
                               "thumbnail": next((row.get("thumbnail", "") for row in rows if row.get("thumbnail")), "")})
        return sorted(result, key=lambda row: (-row["plays"], row["name"].casefold()))[:6]

    @Property(bool, notify=busyChanged)
    def busy(self):
        return self._busy

    @Property(str, notify=statusChanged)
    def status(self):
        return self._status

    @Property(bool, notify=darkModeChanged)
    def darkMode(self):
        return self._dark_mode

    @Property(bool, notify=sidebarChanged)
    def sidebarExpanded(self): return self._sidebar_expanded

    @Property(bool, notify=downloadStateChanged)
    def downloadRunning(self): return self._download_running

    @Property(float, notify=downloadStateChanged)
    def downloadProgress(self): return self._download_progress

    @Property(bool, notify=downloadStateChanged)
    def downloadIndeterminate(self): return self._download_indeterminate

    @Property(str, notify=downloadStateChanged)
    def downloadStatus(self): return self._download_status

    @Property(str, notify=downloadStateChanged)
    def downloadDetail(self): return self._download_detail

    @Property("QVariantList", notify=downloadFilesChanged)
    def downloadFiles(self): return self._download_files

    @Property(str, notify=downloadLogChanged)
    def downloadLog(self): return "\n".join(line["text"] for line in self._download_log_lines)

    @Property("QVariantList", notify=downloadLogChanged)
    def downloadLogLines(self): return self._download_log_lines

    @Property(str, notify=downloadStateChanged)
    def downloadBadge(self): return self._download_badge

    @Property(str, notify=downloadStateChanged)
    def downloadPercentText(self): return self._download_percent_text

    @Property(int, notify=downloadStateChanged)
    def downloadCompleted(self): return self._download_completed

    @Property(int, notify=downloadStateChanged)
    def downloadErrorCount(self): return self._download_error_count

    @Property(bool, notify=downloadStateChanged)
    def downloadFailed(self): return self._download_failed

    @Property("QVariantMap", notify=playerChanged)
    def currentTrack(self): return self._current

    @Property("QVariantList", notify=playerChanged)
    def queue(self): return self._queue

    @Property(int, notify=playerChanged)
    def queueIndex(self): return self._queue_index

    @Property(int, notify=playerChanged)
    def repeatMode(self): return self._repeat_mode

    @Property(bool, notify=playerChanged)
    def playing(self): return self._player.playbackState() == QMediaPlayer.PlayingState

    @Property(int, notify=playerChanged)
    def playerPosition(self): return self._player.position()

    @Property(int, notify=playerChanged)
    def playerDuration(self): return self._player.duration()

    @Property(float, notify=playerChanged)
    def volume(self): return self._audio.volume()

    @Property(bool, notify=playerChanged)
    def muted(self): return self._audio.isMuted()

    @Property(bool, notify=playerChanged)
    def currentFavorite(self):
        if not self._current: return False
        return self._is_fav(self._current)

    # ---- Carpetas de música de este equipo (no se sincronizan: cada dispositivo tiene las suyas) ----
    def _library_roots(self):
        raw = self._settings.value("libraryFoldersJson", "", str)
        if raw:
            try:
                return [Path(item) for item in json.loads(raw) if isinstance(item, str) and item]
            except (ValueError, TypeError):
                pass
        return [ROOT / "Descargas"]

    def _save_library_roots(self, roots):
        unique = []
        for root in roots:
            if str(root) not in unique: unique.append(str(root))
        self._settings.setValue("libraryFoldersJson", json.dumps(unique))
        self.libraryFoldersChanged.emit()
        self.refresh()

    def _folder_of(self, file_path, roots=None):
        """Nombre de la colección de un archivo: la subcarpeta de primer nivel, o la carpeta raíz."""
        parent = Path(file_path).parent
        for root in (roots if roots is not None else self._library_roots()):
            try:
                relative = parent.relative_to(root)
            except ValueError:
                continue
            return root.name or str(root) if str(relative) == "." else relative.parts[0]
        return None

    def _download_dir(self):
        for root in self._library_roots():
            if root.is_dir(): return root
        roots = self._library_roots()
        return roots[0] if roots else ROOT / "Descargas"

    @Property("QVariantList", notify=libraryFoldersChanged)
    def libraryFolders(self):
        return [{"path": str(root), "name": root.name or str(root), "exists": root.is_dir()}
                for root in self._library_roots()]

    @Slot()
    def addLibraryFolder(self):
        start = str(self._download_dir())
        selected = QFileDialog.getExistingDirectory(None, i18n.tr("Agregar carpeta"), start)
        if selected:
            self._save_library_roots(self._library_roots() + [Path(selected)])

    @Slot(str)
    def removeLibraryFolder(self, path):
        self._save_library_roots([root for root in self._library_roots() if str(root) != path])

    @Slot()
    def resetLibraryFolders(self):
        self._settings.remove("libraryFoldersJson")
        self.libraryFoldersChanged.emit()
        self.refresh()

    @Property(str, notify=libraryFoldersChanged)
    def defaultDownloadFolder(self): return str(self._download_dir())

    @Property("QVariantList", notify=downloadingIdsChanged)
    def downloadingIds(self): return list(self._downloading_ids)

    @Slot(str, result=bool)
    def isAutoDownload(self, section):
        if section == "favorites":
            return "Favoritos" in self._auto_download_playlists
        if section.startswith("playlist:"):
            name = section.split("playlist:", 1)[1]
            return name in self._auto_download_playlists
        return False

    @Slot(str, bool)
    def setAutoDownload(self, section, enabled):
        name = "Favoritos" if section == "favorites" else (section.split("playlist:", 1)[1] if section.startswith("playlist:") else "")
        if name:
            if enabled:
                self._auto_download_playlists.add(name)
                self._auto_attempted.clear()
            else:
                self._auto_download_playlists.discard(name)
            self._save_playlists()
            self.playlistsChanged.emit()
        self.autoDownloadChanged.emit()
        self._schedule_cloud_save()
        if enabled:
            self._reconcile_auto_downloads()

    def _save_playlists(self):
        ordered = [key for key in self._playlists.get("Favoritos", []) if key in self._favorites]
        self._playlists["Favoritos"] = ordered + [key for key in sorted(self._favorites) if key not in ordered]
        self._settings.setValue("playlistsData", json.dumps(self._playlists))
        self._settings.setValue("onlineTracksData", json.dumps(self._online_tracks))
        self._settings.setValue("autoDownloadPlaylists", list(self._auto_download_playlists))
        self._settings.setValue("playlistDescriptions", json.dumps(self._playlist_descriptions))

    def _id_of(self, item):
        return item.get("id") or self._path_ids.get(item.get("path", ""), "")

    def _track_keys(self, item):
        vid = self._id_of(item)
        return {key for key in (item.get("path", ""), identity.entry_for(vid), item.get("url", "")) if key}

    def _is_fav(self, item):
        return bool(self._track_keys(item) & self._favorites)

    def _canonical_key(self, item):
        vid = self._id_of(item)
        return identity.entry_for(vid) or item.get("path", "") or item.get("url", "")

    def _remember_online(self, item):
        """Guarda los datos de una canción de YouTube para que cualquier dispositivo pueda mostrarla."""
        vid = self._id_of(item)
        if not vid or identity.entry_for(vid) in self._online_tracks:
            return
        self._online_tracks.update(identity.normalize_online_tracks({vid: {
            "title": item.get("title", ""), "channel": item.get("subtitle") or item.get("channel", "YouTube"),
            "duration": item.get("duration", 0), "thumbnail": item.get("thumbnail", ""), "url": item.get("url", "")}}))

    def _unify_playlists(self):
        """Une cada canción con su ID de YouTube: sin repetidos aunque exista el archivo y la entrada online."""
        rows = self._library_all
        self._path_ids = identity.match_local_ids(rows, self._online_tracks)
        unified, changed = identity.canonicalize(self._playlists, self._path_ids, self._online_tracks)
        if changed:
            self._playlists = unified
            self._favorites = set(unified.get("Favoritos", []))
            self._save_playlists()
            self._settings.setValue("favorites", list(self._favorites))
            self._schedule_cloud_save()
        self._library_all = [dict(row, favorite=self._is_fav(row)) for row in rows]

    def _is_video_downloaded(self, video_id, title=""):
        if video_id and video_id in self._path_ids.values():
            return True
        norm_title = "".join(c for c in title.lower() if c.isalnum())
        for row in self._library_all:
            path = row.get("path", "")
            if video_id and video_id in path: return True
            row_title = "".join(c for c in row.get("title", "").lower() if c.isalnum())
            if norm_title and len(norm_title) > 3 and (norm_title in row_title or row_title in norm_title): return True
        return False

    def _missing_tracks(self, playlist_names):
        """Canciones de YouTube de las playlists dadas que aún no están descargadas ni en descarga."""
        missing, seen = [], set()
        for name in playlist_names:
            for entry in self._playlists.get(name, []):
                vid = identity.bare_id(entry)
                meta = self._online_tracks.get(entry)
                if not vid or not meta or vid in seen:
                    continue
                seen.add(vid)
                if vid in self._downloading_ids or self._is_video_downloaded(vid, meta.get("title", "")):
                    continue
                missing.append(meta)
        return missing

    def _reconcile_auto_downloads(self):
        """La descarga automática vigila las playlists: lo que falte se descarga aunque se haya agregado desde otro dispositivo."""
        if not self._library_scanned or not self._auto_download_playlists:
            return
        for meta in self._missing_tracks(sorted(self._auto_download_playlists)):
            if meta["id"] in self._auto_attempted:
                continue
            self._auto_attempted.add(meta["id"])
            self.downloadOnlineTrack(meta)

    def _merge_favorites_sources(self):
        """Migra datos antiguos y mantiene Favoritos como una única colección."""
        merged = list(self._playlists.get("Favoritos", []))
        merged.extend(key for key in self._favorites if key not in merged)
        self._playlists["Favoritos"] = merged
        self._favorites = set(merged)

    @Slot(str)
    def createPlaylist(self, name):
        name = name.strip()
        if not name or name == "Favoritos": return
        if name not in self._playlists:
            self._playlists[name] = []
            self._save_playlists()
            self.playlistsChanged.emit()
            self._schedule_cloud_save()

    @Slot(str)
    def deletePlaylist(self, name):
        name = name.strip()
        if not name or name == "Favoritos": return
        if name in self._playlists:
            del self._playlists[name]
            self._auto_download_playlists.discard(name)
            self._playlist_descriptions.pop(name, None)
            self._settings.remove(f"autoDownload/playlist:{name}")
            self._save_playlists()
            self.playlistsChanged.emit()
            if self._library_playlist == name:
                self._library_playlist = ""
                self._apply_library_view()
            self._schedule_cloud_save()

    @Slot(str, str, str, result=bool)
    def updatePlaylistDetails(self, old_name, new_name, description):
        old_name, new_name = old_name.strip(), new_name.strip()
        if old_name not in self._playlists: return False
        if old_name == "Favoritos": new_name = old_name
        if not new_name or (new_name == "Favoritos" and old_name != "Favoritos"): return False
        if new_name != old_name and new_name in self._playlists: return False
        if new_name != old_name:
            self._playlists[new_name] = self._playlists.pop(old_name)
            if old_name in self._auto_download_playlists:
                self._auto_download_playlists.remove(old_name); self._auto_download_playlists.add(new_name)
            previous_description = self._playlist_descriptions.pop(old_name, "")
            if previous_description:
                self._playlist_descriptions[new_name] = previous_description
            if self._library_playlist == old_name: self._library_playlist = new_name
        description = description.strip()
        if description: self._playlist_descriptions[new_name] = description
        else: self._playlist_descriptions.pop(new_name, None)
        self._save_playlists(); self.playlistsChanged.emit(); self._apply_library_view(); self._schedule_cloud_save()
        return True

    @Slot(str, "QVariantMap")
    def addTrackToPlaylist(self, playlist_name, track):
        playlist_name = playlist_name.strip()
        if not playlist_name: return
        item = dict(track or {})
        vid = self._id_of(item)
        path = item.get("path", "")
        key = self._canonical_key(item)
        if not key: return
        self._playlists.setdefault(playlist_name, [])
        added = key not in self._playlists[playlist_name]
        if added:
            self._playlists[playlist_name].append(key)
            if playlist_name == "Favoritos":
                self._favorites.add(key)
        if vid:
            self._remember_online(item)
        self._save_playlists()
        if playlist_name == "Favoritos":
            self._settings.setValue("favorites", list(self._favorites))
            self._settings.setValue("onlineFavoritesMeta", json.dumps(self._online_favorites_meta))
        self.playlistsChanged.emit()
        if added and playlist_name in self._auto_download_playlists:
            self._reconcile_auto_downloads()
        if self._library_playlist == playlist_name:
            self._apply_library_view()
        self._schedule_cloud_save()

    @Slot(str, str)
    def removeTrackFromPlaylist(self, playlist_name, track_key):
        playlist_name = playlist_name.strip()
        if playlist_name in self._playlists and track_key in self._playlists[playlist_name]:
            self._playlists[playlist_name].remove(track_key)
            if playlist_name == "Favoritos":
                self._favorites.discard(track_key)
                self._online_favorites_meta.pop(track_key, None)
            self._save_playlists()
            if playlist_name == "Favoritos":
                self._settings.setValue("favorites", list(self._favorites))
                self._settings.setValue("onlineFavoritesMeta", json.dumps(self._online_favorites_meta))
            self.playlistsChanged.emit()
            if self._library_playlist == playlist_name:
                self._apply_library_view()
            self._schedule_cloud_save()

    @Slot(str)
    def setLibraryPlaylist(self, value):
        self._library_playlist = value.strip()
        if self._library_playlist:
            self._library_folder = ""
        self._apply_library_view()

    @Slot(str)
    def downloadAllInPlaylist(self, name):
        for meta in self._missing_tracks([name.strip()]):
            self.downloadOnlineTrack(meta)

    @Slot()
    def downloadAllInFavorites(self):
        for meta in self._missing_tracks(["Favoritos"]):
            self.downloadOnlineTrack(meta)

    @Slot("QVariantMap")
    def downloadOnlineTrack(self, track):
        item = dict(track or {})
        video_id = item.get("id", "")
        url = item.get("url") or (f"https://www.youtube.com/watch?v={video_id}" if video_id else "")
        if not url: return
        if video_id:
            self._downloading_ids.add(video_id)
            self.downloadingIdsChanged.emit()
        target = Path(self.defaultDownloadFolder).expanduser().resolve()
        target.mkdir(parents=True, exist_ok=True)
        self.startDownload(url, False, "", "MP3", "192 kbps", str(target), "")

    @Property(bool, notify=authChanged)
    def authenticated(self): return bool(self._user)

    @Property(bool, notify=authChanged)
    def authBusy(self): return self._auth_busy

    @Property(str, notify=authChanged)
    def authError(self): return self._auth_error

    @Property("QVariantMap", notify=authChanged)
    def user(self): return self._user

    @Property("QVariantList", notify=devicesChanged)
    def devices(self):
        devices = self._cloud_snapshot.get("devices", {})
        if not isinstance(devices, dict): return []
        rows = []
        for device_id, value in devices.items():
            item = dict(value or {})
            item["id"] = device_id
            item["current"] = device_id == self._device_id
            seen = int(item.get("lastSeen", 0) or 0)
            item["lastSeenText"] = time.strftime("%d/%m/%Y %H:%M", time.localtime(seen / 1000)) if seen else i18n.tr("Sin actividad reciente")
            rows.append(item)
        return sorted(rows, key=lambda row: (not row["current"], -int(row.get("lastSeen", 0) or 0)))

    @Slot()
    def signInGoogle(self):
        if self._auth_busy:
            return
        self._auth_busy = True
        self._auth_error = ""
        self.authChanged.emit()
        def worker():
            try:
                user = self._cloud.sign_in()
                state = self._cloud.load_state()
                self._authResult.emit({"user": user, "state": state, "fresh": True,
                                       "refresh": self._cloud.refresh_token}, "")
            except Exception as exc:
                self._authResult.emit({}, str(exc))
        threading.Thread(target=worker, daemon=True).start()

    def _signed_in_at(self):
        value = int(self._settings.value("signedInAt", 0) or 0)
        if not value:
            value = int(time.time() * 1000)
            self._settings.setValue("signedInAt", value)
        return value

    def _is_revoked(self):
        """True si otro dispositivo cerró la sesión guardada en este."""
        try:
            marks = self._cloud.load_revocations()
        except Exception:
            return False
        return marks.get(self._cloud.revocation_field(self._device_id), 0) > self._signed_in_at()

    def _restore_session(self):
        try:
            user = self._cloud.restore()
            if not user:
                self._authResult.emit({}, "")
                return
            revoked = self._is_revoked()
            state = {} if revoked else self._cloud.load_state()
            self._authResult.emit({"user": user, "state": state, "revoked": revoked,
                                   "refresh": self._cloud.refresh_token}, "")
        except Exception as exc:
            self._authResult.emit({}, str(exc))

    @Slot(object, str)
    def _apply_auth_result(self, result, error):
        self._auth_busy = False
        self._auth_error = error
        if result and result.get("revoked"):
            self._sign_out_locally(i18n.tr("Tu sesión se cerró desde otro dispositivo."))
            return
        if result and result.get("fresh"):
            self._settings.setValue("signedInAt", int(time.time() * 1000))
        if result:
            self._user = dict(result.get("user") or {})
            uid = self._user.get("uid", "")
            self._settings.setValue("firebaseRefreshToken", result.get("refresh", ""))
            state = result.get("state") or {}
            self._cloud_snapshot = dict(state)
            self._sync_base = {"remote": self._section_hash(state.get("playlists")), "local": None}
            devices = self._cloud_snapshot.get("devices")
            if not isinstance(devices, dict): devices = {}
            devices[self._device_id] = {"name": platform.node() or "Este PC", "platform": "Windows", "lastSeen": int(time.time() * 1000)}
            self._cloud_snapshot["devices"] = devices
            self.devicesChanged.emit()
            if "darkMode" in state:
                self._dark_mode = bool(state["darkMode"])
                self.darkModeChanged.emit()
            if "recentPlayed" in state:
                self._recent_paths = list(state.get("recentPlayed") or [])
                self._settings.setValue("recentPlayed", self._recent_paths)
            if "recentQueries" in state:
                self._recent_queries = list(state.get("recentQueries") or [])
                self._settings.setValue("recentQueries", self._recent_queries)
            if self._clicks_stats.merge(state.get("clicks")):
                self._save_clicks()
            local_key = "favorites/" + uid
            local_account_favorites = self._settings.value(local_key, [], list) or []
            if "favorites" in state:
                self._favorites = set(state.get("favorites") or [])
            elif local_account_favorites:
                self._favorites = set(local_account_favorites)
            elif self._settings.value("lastFirebaseUid", "", str) in ("", uid):
                self._favorites = set(self._favorites)
            else:
                self._favorites = set()
            if "playlists" in state and isinstance(state["playlists"], dict):
                p_data = state["playlists"]
                if "playlists" in p_data and isinstance(p_data["playlists"], dict):
                    self._playlists = dict(p_data["playlists"])
                    if "Favoritos" not in self._playlists:
                        self._playlists["Favoritos"] = []
                if "onlineTracks" in p_data and isinstance(p_data["onlineTracks"], dict):
                    self._online_tracks = identity.normalize_online_tracks(p_data["onlineTracks"])
                if "autoDownload" in p_data and isinstance(p_data["autoDownload"], list):
                    self._auto_download_playlists = set(p_data["autoDownload"])
                if "descriptions" in p_data and isinstance(p_data["descriptions"], dict):
                    self._playlist_descriptions = dict(p_data["descriptions"])
                if isinstance(p_data.get("playlists"), dict) and "Favoritos" in p_data["playlists"]:
                    # Quitar un favorito en otro dispositivo no debe resucitar por una copia antigua.
                    self._favorites = set(self._playlists["Favoritos"])
                else:
                    self._merge_favorites_sources()
                self._save_playlists()
                self.playlistsChanged.emit()
            self._settings.setValue("lastFirebaseUid", uid)
            self._settings.setValue(local_key, list(self._favorites))
            self._settings.setValue("favorites", list(self._favorites))
            self._apply_library_view()
            self.discoveryChanged.emit()
            self.playerChanged.emit()
            self._sync_base["local"] = self._section_hash(self._cloud_state().get("playlists"))
            self._schedule_cloud_save()
        else:
            self._user = {}
            if not error:
                self._settings.remove("firebaseRefreshToken")
        self.authChanged.emit()

    @Slot()
    def signOut(self):
        self._sign_out_locally("")

    def _sign_out_locally(self, message):
        self._settings.remove("firebaseRefreshToken")
        self._settings.remove("signedInAt")
        self._cloud.refresh_token = ""
        self._cloud.id_token = ""
        self._cloud.user = {}
        self._user = {}
        self._cloud_snapshot = {}
        self._auth_error = message
        self.authChanged.emit()
        self.devicesChanged.emit()

    def _poll_devices(self):
        """Actualiza la lista de dispositivos y comprueba si cerraron esta sesión."""
        if not self._user or self._auth_busy:
            return
        def worker():
            try:
                revoked = self._is_revoked()
                devices = {} if revoked else (self._cloud.load_state().get("devices") or {})
            except Exception:
                return
            self._devicesPolled.emit(devices, revoked)
        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, bool)
    def _apply_polled_devices(self, devices, revoked):
        if not self._user:
            return
        if revoked:
            self._sign_out_locally(i18n.tr("Tu sesión se cerró desde otro dispositivo."))
            return
        if isinstance(devices, dict) and devices:
            current = (self._cloud_snapshot.get("devices") or {}).get(self._device_id)
            devices = dict(devices)
            if current: devices[self._device_id] = current
            self._cloud_snapshot["devices"] = devices
            self.devicesChanged.emit()

    @Slot(str)
    def disconnectDevice(self, device_id):
        """Cierra la sesión guardada en otro dispositivo."""
        if not self._user or not device_id or device_id == self._device_id:
            return
        devices = dict(self._cloud_snapshot.get("devices") or {})
        devices.pop(device_id, None)
        self._cloud_snapshot["devices"] = devices
        self.devicesChanged.emit()
        def worker():
            try:
                self._cloud.revoke_device(device_id, time.time() * 1000)
                self._deviceRevoked.emit(device_id, True)
            except Exception:
                self._deviceRevoked.emit(device_id, False)
        threading.Thread(target=worker, daemon=True).start()

    @Slot(str, bool)
    def _device_revoked(self, device_id, success):
        if success:
            return
        self._show_notice(i18n.tr("No se pudo desconectar el dispositivo. Revisa tu conexión."))
        self._poll_devices()

    def _cloud_state(self):
        library = [{key: item.get(key, "") for key in
                    ("title", "artist", "album", "duration", "thumbnail")}
                   for item in self._library_all]
        playlists_export = {
            "playlists": self._playlists,
            "onlineTracks": identity.export_online_tracks(self._online_tracks),
            "autoDownload": list(self._auto_download_playlists),
            "descriptions": self._playlist_descriptions,
        }
        state = dict(self._cloud_snapshot)
        devices = state.get("devices") if isinstance(state.get("devices"), dict) else {}
        devices[self._device_id] = {"name": platform.node() or "Este PC", "platform": "Windows", "lastSeen": int(time.time() * 1000)}
        state.update({"schema": 1, "darkMode": self._dark_mode,
                      "favorites": sorted(self._favorites), "library": library,
                      "playlists": playlists_export, "devices": devices,
                      "recentPlayed": self._recent_paths, "recentQueries": self._recent_queries,
                      "clicks": self._clicks_stats.export()})
        return state

    @staticmethod
    def _section_hash(section):
        return hashlib.sha1(json.dumps(section, sort_keys=True, ensure_ascii=False, default=list).encode("utf-8")).hexdigest()

    def _schedule_cloud_save(self):
        if not self._user:
            return
        state = self._cloud_state()
        local_hash = self._section_hash(state.get("playlists"))
        def worker():
            try:
                remote = self._cloud.load_state() or {}
                # Los clics de los demás dispositivos se mezclan antes de guardar para no pisarlos.
                if self._clicks_stats.merge(remote.get("clicks")):
                    self._save_clicks()
                state["clicks"] = self._clicks_stats.export()
                base = self._sync_base
                remote_hash = self._section_hash(remote.get("playlists"))
                if (base.get("local") is not None and remote.get("playlists") is not None
                        and remote_hash != base.get("remote") and local_hash == base.get("local")):
                    # Otro dispositivo cambió las playlists y aquí no hubo cambios: se adoptan en vez de pisarlas.
                    state["playlists"] = remote["playlists"]
                    self._remotePlaylists.emit(remote["playlists"])
                    base["remote"], base["local"] = remote_hash, None
                else:
                    self._cloud.save_state(state)
                    base["remote"], base["local"] = self._section_hash(state.get("playlists")), local_hash
            except Exception:
                pass
        threading.Thread(target=worker, daemon=True).start()

    @Slot(object)
    def _apply_remote_playlists(self, data):
        if not isinstance(data, dict):
            return
        if isinstance(data.get("playlists"), dict):
            self._playlists = dict(data["playlists"])
            self._playlists.setdefault("Favoritos", [])
            self._favorites = set(self._playlists["Favoritos"])
        if isinstance(data.get("onlineTracks"), dict):
            self._online_tracks = identity.normalize_online_tracks(data["onlineTracks"])
        if isinstance(data.get("autoDownload"), list):
            self._auto_download_playlists = set(data["autoDownload"])
        if isinstance(data.get("descriptions"), dict):
            self._playlist_descriptions = dict(data["descriptions"])
        self._save_playlists()
        self._settings.setValue("favorites", list(self._favorites))
        self._sync_base["local"] = self._section_hash(self._cloud_state().get("playlists"))
        self._unify_playlists()
        self.playlistsChanged.emit()
        self._apply_library_view()
        self.discoveryChanged.emit()
        self.playerChanged.emit()
        self._reconcile_auto_downloads()

    @Slot()
    def toggleTheme(self):
        self._dark_mode = not self._dark_mode
        self._settings.setValue("darkMode", self._dark_mode)
        self.darkModeChanged.emit()
        self._schedule_cloud_save()

    @Slot()
    def toggleSidebar(self):
        self._sidebar_expanded = not self._sidebar_expanded
        self._settings.setValue("sidebarExpanded", self._sidebar_expanded)
        self.sidebarChanged.emit()

    @Property(str, notify=languageChanged)
    def language(self): return self._language_setting

    @Slot(str)
    def setLanguage(self, value):
        if value not in i18n.LANGUAGES or value == self._language_setting: return
        self._language_setting = value
        self._settings.setValue("language", value)
        i18n.set_language(value)
        self._set_status(i18n.tr("Todo listo"))
        if self._engine is not None: self._engine.retranslate()
        self.languageChanged.emit()
        self.refresh()

    def attach_engine(self, engine):
        self._engine = engine

    @Property("QVariantMap", notify=noticeChanged)
    def notice(self): return self._notice

    def _show_notice(self, text, retry=None, kind="error"):
        self._notice_retry = retry
        self._notice = {"text": text, "kind": kind, "retry": retry is not None, "id": time.monotonic_ns()}
        self.noticeChanged.emit()

    @Slot()
    def dismissNotice(self):
        if self._notice:
            self._notice = {}
            self._notice_retry = None
            self.noticeChanged.emit()

    @Slot()
    def retryNotice(self):
        retry = self._notice_retry
        self.dismissNotice()
        if retry: retry()

    @Property(str, constant=True)
    def appVersion(self): return __version__

    @Property("QVariantMap", notify=updateChanged)
    def update(self): return self._update

    @Property(str, notify=updateChanged)
    def updateState(self): return self._update_state

    @Property(float, notify=updateChanged)
    def updateProgress(self): return self._update_progress

    @Property(str, notify=updateChanged)
    def updateError(self): return self._update_error

    @Slot()
    def checkForUpdate(self):
        if self._update_state in ("downloading", "installing"): return
        def worker():
            try:
                info = updater.check_for_update(__version__)
            except Exception:
                return
            if info and info["version"] != self._settings.value("skippedUpdate", "", str):
                self._updateEvent.emit({"event": "available", "info": info})
        threading.Thread(target=worker, daemon=True).start()

    @Slot()
    def skipUpdate(self):
        if self._update.get("version"):
            self._settings.setValue("skippedUpdate", self._update["version"])
        self.dismissUpdate()

    @Slot()
    def dismissUpdate(self):
        if self._update_state == "downloading":
            self._update_cancelled = True
        self._update = {}
        self._update_state = "idle"
        self._update_error = ""
        self.updateChanged.emit()

    @Slot()
    def installUpdate(self):
        if not self._update or self._update_state in ("downloading", "installing"): return
        if not getattr(sys, "frozen", False):
            QDesktopServices.openUrl(QUrl(self._update.get("pageUrl") or updater.LATEST_URL))
            return
        info = dict(self._update)
        self._update_cancelled = False
        self._update_state = "downloading"
        self._update_progress = 0.0
        self._update_error = ""
        self.updateChanged.emit()
        def worker():
            try:
                folder = Path(os.environ.get("TEMP", str(ROOT))) / "MusicBoxUpdate"
                path = updater.download_installer(
                    info, folder,
                    lambda done, total: self._updateEvent.emit({"event": "progress", "value": done / total if total else 0.0}),
                    lambda: self._update_cancelled)
                self._updateEvent.emit({"event": "ready", "path": str(path)})
            except InterruptedError:
                pass
            except Exception as exc:
                self._updateEvent.emit({"event": "error", "text": str(exc) or i18n.tr("No se pudo descargar la actualización.")})
        threading.Thread(target=worker, daemon=True).start()

    @Slot(object)
    def _handle_update_event(self, event):
        kind = event.get("event")
        if kind == "available":
            if self._update_state in ("idle", "error"):
                self._update = event["info"]
                self._update_state = "available"
        elif kind == "progress":
            if self._update_state == "downloading": self._update_progress = float(event.get("value", 0))
        elif kind == "error":
            if self._update_state == "downloading":
                self._update_state = "error"
                self._update_error = event.get("text", "")
        elif kind == "ready":
            if self._update_state != "downloading": return
            self._update_state = "installing"
            self._update_progress = 1.0
            self.updateChanged.emit()
            subprocess.Popen([event["path"], "/SILENT", "/SUPPRESSMSGBOXES", "/CLOSEAPPLICATIONS", "/RESTARTAPPLICATIONS"],
                             creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
            QTimer.singleShot(600, QApplication.quit)
        self.updateChanged.emit()

    def _player_error(self, error, message=""):
        if error == QMediaPlayer.NoError: return
        track = dict(self._current)
        url = track.get("url", "")
        if url and not track.get("path") and url not in self._cache_fallback_tried \
                and self._player.source().scheme() in ("http", "https"):
            self._cache_fallback_tried.add(url)
            self._play_from_cache(track)
            return
        retry = (lambda: self.playOnline(track)) if track.get("url") and not track.get("path") else None
        self._show_notice(i18n.tr("No se pudo reproducir esta canción. ") + (message or i18n.tr("Inténtalo de nuevo en unos segundos.")), retry)

    def _set_busy(self, value):
        if self._busy != value:
            self._busy = value
            self.busyChanged.emit()

    def _set_status(self, value):
        if self._status != value:
            self._status = value
            self.statusChanged.emit()

    @Slot()
    def refresh(self):
        found = {}
        for root in self._library_roots():
            if not root.is_dir(): continue
            for path in root.rglob("*"):
                if path.is_file() and path.suffix.lower() in MEDIA_EXTENSIONS:
                    found.setdefault(str(path), path)
        files = []
        for path in sorted(found.values(), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True):
            info = media_info(path)
            info["favorite"] = str(path) in self._favorites
            files.append(info)
        self._library_all = files
        # Archivos que MusicBox descargó muestran la marca de descarga; el resto viene de una carpeta de música.
        download_roots = []
        for root in (DEFAULT_DOWNLOAD_DIR, self._download_dir()):
            try:
                download_roots.append(Path(root).expanduser().resolve())
            except OSError:
                pass
        for item in files:
            try:
                location = Path(item["path"]).resolve()
                item["fromFolder"] = not any(location == root or root in location.parents for root in download_roots)
            except OSError:
                item["fromFolder"] = False
        self._library_scanned = True
        self._unify_playlists()
        files = self._library_all
        self._migrate_legacy_clicks(files)
        grouped = {}
        roots = self._library_roots()
        for item in files:
            folder = self._folder_of(item["path"], roots)
            if folder is not None:
                grouped.setdefault(folder, []).append(item)
        self._folders = [{"name": name, "count": len(items),
                          "subtitle": i18n.tr("{n} canciones" if len(items) != 1 else "{n} canción", n=len(items)),
                          "thumbnail": next((row["thumbnail"] for row in items if row.get("thumbnail")), "")}
                         for name, items in sorted(grouped.items(), key=lambda pair: pair[0].casefold())]
        self.foldersChanged.emit()
        self._apply_library_view()
        if self._search_results:
            for item in self._search_results:
                vid = item.get("id", "")
                item["downloaded"] = self._is_video_downloaded(vid, item.get("title", ""))
                item["favorite"] = bool(f"youtube:{vid}" in self._favorites or item.get("url", "") in self._favorites)
            self.searchResultsChanged.emit()
        self.discoveryChanged.emit()
        self._reconcile_auto_downloads()
        rows, _ = DownloadHistory().page(limit=40)
        self._history = [{
            "title": row.get("title", ""),
            "subtitle": " · ".join(filter(None, (row.get("artist", ""), row.get("format", "")))),
            "path": row.get("filename", ""),
        } for row in rows]
        self.historyChanged.emit()

    def _apply_library_view(self):
        rows = list(self._library_all)
        if self._library_playlist:
            keys = self._playlists.get(self._library_playlist, [])
            resolved = []
            by_path = {row.get("path"): row for row in self._library_all}
            id_rows = {vid: by_path[path] for path, vid in self._path_ids.items() if path in by_path}
            seen = set()
            for k in keys:
                vid = identity.bare_id(k) or self._path_ids.get(k, "")
                if (vid or k) in seen:
                    continue
                seen.add(vid or k)
                row = by_path.get(k) or id_rows.get(vid)
                if row is not None:
                    item = dict(row)
                    item["playlistKey"] = k
                    resolved.append(item)
                    continue
                meta = self._online_tracks.get(identity.entry_for(vid)) or self._online_tracks.get(k)
                if meta:
                    meta = dict(meta)
                    meta["downloaded"] = self._is_video_downloaded(vid, meta.get("title", ""))
                    meta["favorite"] = self._is_fav(meta)
                    meta["web"] = True
                    meta["playlistKey"] = k
                    resolved.append(meta)
                elif vid:
                    resolved.append({
                        "id": vid,
                        "title": i18n.tr("Canción online"),
                        "subtitle": "YouTube",
                        "url": f"https://www.youtube.com/watch?v={vid}",
                        "thumbnail": f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg",
                        "web": True,
                        "downloaded": False,
                        "playlistKey": k
                    })
            rows = resolved
        elif self._library_folder:
            roots = self._library_roots()
            rows = [row for row in rows if self._folder_of(row["path"], roots) == self._library_folder]
        # El escaneo ya entrega el orden más reciente primero.
        query = self._library_query.casefold().strip()
        if query:
            rows = [row for row in rows if query in " ".join((row.get("title", ""), row.get("artist", ""),
                                                               row.get("album", ""))).casefold()]
        self._library = rows
        self.libraryChanged.emit()

    @Slot(str)
    def setLibraryQuery(self, value):
        self._library_query = " ".join(value.split())
        self._apply_library_view()

    @Slot(str)
    def setLibraryFilter(self, value):
        self._library_filter = value if value in {"songs", "playlists"} else "songs"
        if value == "playlists":
            self._library_folder = ""
        if value != "playlists":
            self._library_playlist = ""
        self._apply_library_view()

    @Slot(str)
    def setLibraryFolder(self, value):
        self._library_folder = value.strip()
        self._apply_library_view()

    @Slot()
    def clearRecentQueries(self):
        self._recent_queries = []
        self._settings.setValue("recentQueries", [])
        self.discoveryChanged.emit()
        self._schedule_cloud_save()

    @Slot(str)
    def search(self, query):
        query = " ".join(query.split())
        if not query:
            self.clearSearch()
            return
        self._recent_queries = [entry for entry in self._recent_queries if entry.casefold() != query.casefold()]
        self._recent_queries.insert(0, query)
        self._recent_queries = self._recent_queries[:8]
        self._settings.setValue("recentQueries", self._recent_queries)
        self.discoveryChanged.emit()
        self._schedule_cloud_save()
        self._search_generation += 1
        generation = self._search_generation
        self._set_busy(True)
        self._set_status(i18n.tr("Buscando en YouTube…"))

        def worker():
            results, error = [], ""
            try:
                for item in youtube_streams.search(query, self._extract_info, 12):
                    video_id = item.get("id") or ""
                    results.append({
                        "id": video_id,
                        "title": item.get("title") or i18n.tr("Sin título"),
                        "subtitle": item.get("channel") or item.get("uploader") or "YouTube",
                        "thumbnail": f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
                        "url": item.get("webpage_url") or f"https://www.youtube.com/watch?v={video_id}",
                    })
            except Exception as exc:
                error = str(exc).splitlines()[-1] if str(exc) else i18n.tr("No se pudo completar la búsqueda")
            if generation == self._search_generation:
                self._searchFinished.emit(results, error)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, str)
    def _apply_search(self, results, error):
        for item in results:
            vid = item.get("id", "")
            item["downloaded"] = self._is_video_downloaded(vid, item.get("title", ""))
            item["favorite"] = bool(f"youtube:{vid}" in self._favorites or item.get("url", "") in self._favorites)
        self._search_results = results
        self.searchResultsChanged.emit()
        self._set_busy(False)
        self._set_status(error or i18n.tr("{n} resultados", n=len(results)))
        if error:
            query = self._recent_queries[0] if self._recent_queries else ""
            self._show_notice(i18n.tr("No se pudo completar la búsqueda. ") + error, (lambda: self.search(query)) if query else None)

    @Slot()
    def clearSearch(self):
        self._search_generation += 1
        self._search_results = []
        self.searchResultsChanged.emit()
        self._set_busy(False)
        self._set_status(i18n.tr("Todo listo"))

    @Slot(result=str)
    def chooseDownloadFolder(self):
        selected = QFileDialog.getExistingDirectory(None, "Carpeta de descargas", str(self._download_dir()))
        return selected or ""

    @Slot(result=str)
    def chooseCookieFile(self):
        selected, _ = QFileDialog.getOpenFileName(None, "Cookies en formato Netscape", str(ROOT),
                                                   "Archivo de cookies (*.txt);;Todos los archivos (*)")
        return selected or ""

    @Slot(result=str)
    def clipboardText(self):
        return QApplication.clipboard().text().strip()

    @Slot(str, result=bool)
    def isPlaylistInput(self, value):
        lines = [line.strip() for line in value.splitlines() if line.strip()]
        if not lines:
            return False
        try:
            return all((part.hostname or "").lower() in
                       ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
                       and part.path.rstrip("/") == "/playlist" and "list" in parse_qs(part.query)
                       for part in map(urlsplit, lines))
        except ValueError:
            return False

    @Slot(str)
    def openFolder(self, value):
        try:
            folder = Path(value.strip() or self._download_dir()).expanduser().resolve()
            folder.mkdir(parents=True, exist_ok=True)
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(folder)))
        except OSError as exc:
            self._download_status = i18n.tr("No se pudo abrir la carpeta")
            self._download_detail = str(exc)
            self.downloadStateChanged.emit()

    def _prepare_download(self, raw_urls, playlist, items, media_format, quality, destination, cookies):
        """Valida los datos y devuelve (configuración, mensaje de error)."""
        if quality == "Best available": quality = "Mejor disponible"
        try:
            urls = download_backend.parse_urls(raw_urls, playlist)
            selected_items = download_backend.validate_items(items) if playlist else ""
            if not destination.strip():
                raise ValueError(i18n.tr("Elige una carpeta de destino."))
            target = Path(destination.strip()).expanduser().resolve()
            target.mkdir(parents=True, exist_ok=True)
            ffmpeg = download_backend.find_ffmpeg()
            if not ffmpeg:
                raise ValueError(i18n.tr("No se encontró FFmpeg. Coloca ffmpeg.exe junto al programa o en PATH."))
            cookie_path = cookies.strip()
            if cookie_path and not Path(cookie_path).is_file():
                raise ValueError(i18n.tr("El archivo de cookies no existe."))
            return dict(urls=urls, playlist=playlist, items=selected_items, destination=str(target),
                        ffmpeg=ffmpeg, format=media_format, quality=quality, cookies=cookie_path,
                        history_path=str(DownloadHistory().path)), ""
        except (ValueError, OSError) as exc:
            return None, str(exc)

    @Slot(str, bool, str, str, str, str, str)
    def startDownload(self, raw_urls, playlist, items, media_format, quality, destination, cookies):
        """Descarga rápida (botón + y descargas automáticas): se pone en cola si ya hay una en curso."""
        if self._download_running:
            entry = (raw_urls, media_format, quality, destination, cookies)
            if not playlist and raw_urls.strip() and entry not in self._download_queue:
                self._download_queue.append(entry)
            return
        config, error = self._prepare_download(raw_urls, playlist, items, media_format, quality,
                                               destination or str(DEFAULT_DOWNLOAD_DIR), cookies)
        if error:
            self._download_status = i18n.tr("Revisa los datos")
            self._download_detail = error
            self.downloadStateChanged.emit()
            self._downloading_ids.clear()
            self.downloadingIdsChanged.emit()
            self._show_notice(error)
            return
        self._begin_download(config, from_page=False)

    @Slot(str, bool, str, str, str, str, str, result=str)
    def startDownloadFromPage(self, raw_urls, playlist, items, media_format, quality, destination, cookies):
        """Descarga iniciada desde la página de Descargas. Devuelve el error de validación, o vacío."""
        if self._download_running:
            return ""
        config, error = self._prepare_download(raw_urls, playlist, items, media_format, quality, destination, cookies)
        if error:
            return error
        self._begin_download(config, from_page=True)
        return ""

    def _begin_download(self, config, from_page):
        self._download_running = True
        self._download_from_page = from_page
        self._download_cancelled = False
        self._download_failed = False
        self._download_progress = 0
        self._download_indeterminate = True
        self._download_percent_text = "…"
        self._download_badge = "preparing"
        self._download_status = i18n.tr("Analizando enlaces…")
        self._download_detail = i18n.tr("Buscando los formatos disponibles.")
        self._download_files = []
        self._download_completed = 0
        self._download_error_count = 0
        self._download_had_summary = False
        self._download_log_lines = []
        self._append_download_log(i18n.tr("Destino: {t}\nFormato: {f} · {q}", t=config["destination"],
                                          f=config["format"], q=config["quality"]))
        self.downloadStateChanged.emit(); self.downloadFilesChanged.emit()
        threading.Thread(target=self._download_worker, args=(config,), daemon=True).start()

    def _download_worker(self, config):
        process = None
        try:
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(worker_command("--worker"),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                encoding="utf-8", errors="replace", env={**os.environ, "PYTHONIOENCODING": "utf-8"}, **flags)
            self._download_process = process
            process.stdin.write(json.dumps(config) + "\n"); process.stdin.close()
            if self._download_cancelled:
                download_backend.stop_process(process)
            for line in process.stdout:
                try:
                    event = json.loads(line)
                    if not isinstance(event, dict) or "event" not in event: raise ValueError
                except (ValueError, TypeError):
                    event = {"event": "log", "text": line.strip(), "level": "info"}
                self._downloadEvent.emit(event)
            process.wait()
        except Exception as exc:
            if not self._download_cancelled: self._downloadEvent.emit({"event": "log", "level": "error", "text": str(exc)})
        finally:
            self._download_process = None
            self._downloadEvent.emit({"event": "done", "cancelled": self._download_cancelled})

    @Slot(object)
    def _handle_download_event(self, event):
        kind = event.get("event")
        cancelling = self._download_cancelled
        if kind == "log":
            level = event.get("level", "info")
            if level == "error": self._download_error_count += 1
            self._append_download_log(event.get("text", ""), level)
        elif kind == "link" and not cancelling:
            self._download_status = i18n.tr("Analizando enlace {i} de {n}…", i=event.get("index"), n=event.get("count"))
            self._download_detail = i18n.tr("Consultando la información del enlace.")
            self._download_badge = "preparing"; self._download_percent_text = "…"
            self._download_indeterminate = True
        elif kind == "progress" and not cancelling:
            total, downloaded = event.get("total"), event.get("downloaded", 0)
            if event.get("status") == "finished":
                self._download_status = i18n.tr("Preparando el archivo final…")
                self._download_detail = i18n.tr("Terminando la conversión o unión de pistas.")
                self._download_badge = "processing"; self._download_percent_text = "…"
                self._download_indeterminate = True
            else:
                self._download_status = event.get("title") or i18n.tr("Descargando…")
                self._download_badge = "downloading"
                if total:
                    self._download_progress = min(1.0, downloaded / total)
                    self._download_indeterminate = False
                    self._download_percent_text = f"{self._download_progress * 100:.0f}%"
                else:
                    self._download_indeterminate = True; self._download_percent_text = "…"
                parts = [f"{download_backend.human_bytes(downloaded)} / "
                         f"{download_backend.human_bytes(total) if total else '?'}"]
                if event.get("speed"): parts.append(download_backend.human_bytes(event["speed"]) + "/s")
                if event.get("eta") is not None:
                    eta = max(0, int(event["eta"]))
                    parts.append(i18n.tr("{m}:{s} restantes", m=eta // 60, s=f"{eta % 60:02d}"))
                if event.get("index"):
                    parts.append(i18n.tr("Archivo {i} de {n}", i=event["index"], n=event.get("count") or "?"))
                self._download_detail = " · ".join(parts)
        elif kind == "processing" and not cancelling:
            self._download_status = event.get("text", i18n.tr("Procesando archivo…"))
            self._download_badge = "processing"; self._download_percent_text = "…"
            self._download_indeterminate = True
        elif kind == "ready":
            filename = event.get("filename", "")
            self._download_completed = int(event.get("completed", self._download_completed + 1))
            path = Path(filename)
            self._download_files.append({"title": path.name, "subtitle": i18n.tr("Listo"), "path": filename,
                                         "format": path.suffix.lstrip(".").upper()})
            self.downloadFilesChanged.emit()
            self._append_download_log(i18n.tr("Listo: ") + filename, "ready")
            for vid in list(self._downloading_ids):
                if vid in filename: self._downloading_ids.discard(vid)
            self.downloadingIdsChanged.emit()
            self.refresh()
        elif kind == "summary":
            self._download_completed = int(event.get("completed", 0))
            self._download_error_count = int(event.get("errors", 0))
            self._download_had_summary = True
        elif kind == "done":
            self._download_running = False; self._download_indeterminate = False
            cancelled = bool(event.get("cancelled"))
            if cancelled: self._download_queue.clear()
            queued = self._download_queue.pop(0) if self._download_queue else None
            if queued is None:
                self._downloading_ids.clear()
                self.downloadingIdsChanged.emit()
            self._download_failed = False
            if cancelled:
                self._download_status = i18n.tr("Descarga cancelada")
                self._download_detail = i18n.tr("Los archivos completos están guardados. Puedes volver a intentarlo.")
                self._download_percent_text = "—"; self._download_progress = 0
                self._download_badge = "cancelled"
            elif self._download_error_count or not self._download_had_summary:
                self._download_status = i18n.tr("El proceso terminó con errores · revisa el registro")
                self._download_detail = i18n.tr("Los archivos completados están guardados en la carpeta de destino.")
                self._download_percent_text = "—"; self._download_progress = 0
                self._download_badge = "errors"; self._download_failed = True
                if not self._download_from_page:
                    self._show_notice(i18n.tr("No se pudo completar la descarga. Revisa tu conexión e inténtalo otra vez."))
            else:
                self._download_status = i18n.tr("¡Descargas terminadas!")
                self._download_detail = i18n.tr("Abre un archivo con doble clic o visita la carpeta de destino.")
                self._download_percent_text = "100%"; self._download_progress = 1.0
                self._download_badge = "completed"
            self._append_download_log(self._download_status)
            self.refresh()
            if queued:
                self.downloadStateChanged.emit()
                self.startDownload(queued[0], False, "", queued[1], queued[2], queued[3], queued[4])
                return
        self.downloadStateChanged.emit()

    def _append_download_log(self, text, level="info"):
        if not text: return
        for line in str(text).splitlines() or [""]:
            self._download_log_lines.append({"text": line, "level": level})
        if len(self._download_log_lines) > 1200:
            self._download_log_lines = self._download_log_lines[-1000:]
        self.downloadLogChanged.emit()

    @Slot()
    def cancelDownload(self):
        if not self._download_running or self._download_cancelled: return
        self._download_cancelled = True
        self._download_status = i18n.tr("Cancelando la descarga…")
        self._download_badge = "cancelling"
        self.downloadStateChanged.emit()
        process = self._download_process
        if process is not None: threading.Thread(target=download_backend.stop_process, args=(process,), daemon=True).start()

    @Slot(str, str)
    def setDownloadMessage(self, status, detail):
        """Mensaje del panel de progreso cuando no hay una descarga en curso."""
        self._download_status, self._download_detail = status, detail
        self.downloadStateChanged.emit()

    @Slot(str, str)
    def appendDownloadLog(self, text, level="info"):
        self._append_download_log(text, level)

    @Slot()
    def pausePlayer(self):
        if self._player.playbackState() == QMediaPlayer.PlayingState:
            self._player.pause()

    @Slot()
    def clearDownloadLog(self):
        self._download_log_lines = []; self.downloadLogChanged.emit()

    @Slot()
    def resetDownloadView(self):
        """Vuelve el panel de progreso a su estado de espera (no toca archivos ni historial)."""
        if self._download_running: return
        self._download_badge = "idle"
        self._download_percent_text = "—"
        self._download_progress = 0
        self._download_failed = False
        self._download_status = i18n.tr("Todo listo para empezar")
        self._download_detail = i18n.tr("El progreso de cada archivo aparecerá aquí.")
        self.downloadStateChanged.emit()

    @Slot("QVariantMap")
    def playTrack(self, track):
        item = dict(track or {})
        path = item.get("path", "")
        if not path or not Path(path).is_file(): return
        self._queue = list(self._library)
        self._queue_index = next((i for i, row in enumerate(self._queue) if row.get("path") == path), -1)
        self._start_local_track(item)

    def _start_local_track(self, item):
        path = item.get("path", "")
        self._current = item
        self._recorded_source = ""
        self._player.setSource(QUrl.fromLocalFile(path))
        self._player.play(); self.playerChanged.emit()

    def _playback_state_changed(self, state):
        if state == QMediaPlayer.PlayingState:
            path = self._current.get("path", "")
            if path and Path(path).is_file() and path != self._recorded_source:
                self._recorded_source = path
                self._recent_paths = [entry for entry in self._recent_paths if entry != path]
                self._recent_paths.insert(0, path)
                self._recent_paths = self._recent_paths[:50]
                title = self._current.get("title", "")
                self._clicks_stats.record(song_key(title, self._path_ids.get(path, "")), title)
                self._settings.setValue("recentPlayed", self._recent_paths)
                self._save_clicks()
                self.discoveryChanged.emit()
                self.libraryChanged.emit()
                self.foldersChanged.emit()
                self.playlistsChanged.emit()
                self._schedule_cloud_save()
        self.playerChanged.emit()

    @Slot("QVariantMap")
    def playOnline(self, track):
        item = dict(track or {})
        url = item.get("url", "")
        if not url: return
        self._current = item
        self._current.setdefault("artist", item.get("subtitle", "YouTube"))
        self._current.setdefault("album", "Streaming")
        self._cache_fallback_tried.discard(url)
        self._set_status(i18n.tr("Preparando reproducción…"))
        self.playerChanged.emit()
        def resolve():
            stream, error = "", ""
            try:
                info = youtube_streams.resolve_stream(url, self._extract_info)
                stream = info.get("url", "")
                self._current.update(title=info.get("track") or info.get("title") or self._current.get("title"),
                                     artist=info.get("artist") or info.get("uploader") or self._current.get("artist"),
                                     album=info.get("album") or "Streaming")
            except Exception as exc:
                error = str(exc).splitlines()[-1]
            self._streamReady.emit(dict(self._current), stream or error)
        threading.Thread(target=resolve, daemon=True).start()

    @staticmethod
    def _extract_info(options, url):
        import yt_dlp
        with yt_dlp.YoutubeDL(options) as ydl:
            return ydl.extract_info(url, download=False)

    @staticmethod
    def _download_audio(options, url):
        import yt_dlp
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
            return ydl.prepare_filename(info)

    def _play_from_cache(self, track):
        """Si el reproductor rechaza el enlace remoto, descarga el audio y lo reproduce localmente."""
        url = track.get("url", "")
        self._set_status(i18n.tr("Preparando reproducción…"))
        def work():
            source = ""
            try:
                source = youtube_streams.download_to_cache(url, ROOT / "datos" / "cache_streams", self._download_audio)
            except Exception as exc:
                source = str(exc).splitlines()[-1] if str(exc) else ""
            self._streamReady.emit(track, source)
        threading.Thread(target=work, daemon=True).start()

    @Slot(object, str)
    def _play_stream(self, track, source):
        if Path(source).is_file():
            self._current = dict(track)
            self._player.setSource(QUrl.fromLocalFile(source))
            self._player.play()
            self._set_status(i18n.tr("Reproduciendo"))
            self.playerChanged.emit()
            return
        if not source.startswith(("http://", "https://")):
            self._set_status(source or i18n.tr("No se pudo reproducir este resultado"))
            failed = dict(track)
            self._show_notice(source or i18n.tr("No se pudo reproducir este resultado."), lambda: self.playOnline(failed))
            return
        self._current = dict(track)
        self._player.setSource(QUrl(source))
        self._player.play()
        self._set_status(i18n.tr("Reproduciendo"))
        self.playerChanged.emit()

    @Slot()
    def togglePlayback(self):
        if not self._current: return
        if self.playing: self._player.pause()
        else: self._player.play()

    @Slot(int)
    def seek(self, milliseconds): self._player.setPosition(max(0, milliseconds))

    @Slot(float)
    def setVolume(self, value):
        self._audio.setVolume(max(0.0, min(1.0, value)))
        if value > 0 and self._audio.isMuted(): self._audio.setMuted(False)
        self._settings.setValue("volume", self._audio.volume())
        self._settings.setValue("muted", self._audio.isMuted()); self.playerChanged.emit()

    @Slot()
    def toggleMute(self):
        self._audio.setMuted(not self._audio.isMuted())
        self._settings.setValue("muted", self._audio.isMuted())
        self.playerChanged.emit()

    @Slot(int)
    def skip(self, direction):
        if not self._queue: return
        self._queue_index = (self._queue_index + (1 if direction >= 0 else -1)) % len(self._queue)
        self._start_local_track(dict(self._queue[self._queue_index]))

    @Slot()
    def shuffleVisible(self):
        if not self._library: return
        self._queue = list(self._library)
        random.shuffle(self._queue)
        self._queue_index = 0
        self._start_local_track(dict(self._queue[0]))

    @Slot()
    def cycleRepeat(self):
        self._repeat_mode = (self._repeat_mode + 1) % 3
        self._settings.setValue("repeatMode", self._repeat_mode)
        self.playerChanged.emit()

    def _queue_current_index(self):
        path = self._current.get("path", "")
        return next((i for i, row in enumerate(self._queue) if row.get("path") == path), self._queue_index)

    @Slot(int, int)
    def moveQueueItem(self, source, target):
        count = len(self._queue)
        if not (0 <= source < count and 0 <= target < count) or source == target:
            return
        self._queue.insert(target, self._queue.pop(source))
        self._queue_index = self._queue_current_index()
        self.playerChanged.emit()

    @Slot(int)
    def removeQueueItem(self, index):
        if not (0 <= index < len(self._queue)) or index == self._queue_index:
            return
        del self._queue[index]
        self._queue_index = self._queue_current_index()
        self.playerChanged.emit()

    @Slot()
    def clearQueue(self):
        if 0 <= self._queue_index < len(self._queue):
            self._queue = [self._queue[self._queue_index]]
            self._queue_index = 0
        else:
            self._queue = []
            self._queue_index = -1
        self.playerChanged.emit()

    def _enqueue(self, track, after_current):
        item = dict(track or {})
        path = item.get("path", "")
        if not path or not Path(path).is_file():
            return
        if not self._current:
            self._queue = [item]
            self._queue_index = 0
            self._start_local_track(item)
            return
        self._queue = [row for row in self._queue if row.get("path") != path or row.get("path") == self._current.get("path")]
        current = self._queue_current_index()
        position = current + 1 if after_current else len(self._queue)
        self._queue.insert(max(0, min(position, len(self._queue))), item)
        self._queue_index = self._queue_current_index()
        self.playerChanged.emit()

    @Slot("QVariantMap")
    def playNextTrack(self, track):
        self._enqueue(track, True)

    @Slot("QVariantMap")
    def addToQueue(self, track):
        self._enqueue(track, False)

    @Slot(str)
    def saveQueueAsPlaylist(self, name):
        name = name.strip()
        if not name or name == "Favoritos" or not self._queue:
            return
        entries = self._playlists.setdefault(name, [])
        for row in self._queue:
            key = self._canonical_key(row)
            if key and key not in entries:
                entries.append(key)
        self._save_playlists()
        self.playlistsChanged.emit()
        self._show_notice(i18n.tr("Playlist guardada"), kind="info")
        self._schedule_cloud_save()

    @Slot(int)
    def playQueueIndex(self, index):
        if 0 <= index < len(self._queue):
            self._queue_index = index
            self._start_local_track(dict(self._queue[index]))

    @Slot("QVariantMap")
    def toggleFavoriteFor(self, track):
        self._toggle_favorite_item(dict(track or {}))


    def _toggle_favorite_item(self, item):
        key = self._canonical_key(item)
        if not key: return
        keys = self._track_keys(item)
        if keys & self._favorites:
            self._favorites -= keys
            for stale in keys:
                self._online_favorites_meta.pop(stale, None)
        else:
            self._favorites.add(key)
            self._remember_online(item)
            if "Favoritos" in self._auto_download_playlists and (item.get("url") or self._id_of(item)):
                self._reconcile_auto_downloads()
        self._playlists["Favoritos"] = [k for k in self._playlists.get("Favoritos", []) if k in self._favorites]
        self._save_playlists()
        self.playlistsChanged.emit()
        self._settings.setValue("favorites", list(self._favorites))
        if self._user:
            self._settings.setValue("favorites/" + self._user.get("uid", ""), list(self._favorites))
        self._library_all = [dict(it, favorite=self._is_fav(it)) for it in self._library_all]
        self._search_results = [dict(it, favorite=self._is_fav(it)) for it in self._search_results]
        self._apply_library_view()
        self.searchResultsChanged.emit()
        self.playerChanged.emit()
        self.discoveryChanged.emit()
        self._schedule_cloud_save()

    @Slot(str)
    def showInFolder(self, path):
        target = Path(path)
        if not target.exists(): return
        if os.name == "nt": subprocess.Popen(["explorer", "/select,", str(target)])
        else: QDesktopServices.openUrl(QUrl.fromLocalFile(str(target.parent)))

    @Slot()
    def toggleFavorite(self):
        if not self._current: return
        self._toggle_favorite_item(self._current)


    @Slot()
    def _media_status(self):
        if self._player.mediaStatus() != QMediaPlayer.EndOfMedia: return
        if self._repeat_mode == 2 and self._current:
            self._player.setPosition(0); self._player.play()
        elif self._repeat_mode == 1 or self._queue_index + 1 < len(self._queue):
            self.skip(1)

    @Slot(str)
    def openPath(self, value):
        if value:
            target = Path(value)
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(target)))

    @Slot(str)
    def openUrl(self, value):
        if value:
            QDesktopServices.openUrl(QUrl(value))

    @Slot()
    def openClassicTools(self):
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        subprocess.Popen(worker_command("--classic"), cwd=ROOT, creationflags=flags)


def main():
    if "--version" in sys.argv:
        print(f"MusicBox {__version__}")
        return 0
    if any(flag in sys.argv for flag in ("--worker", "--audio-worker", "--player-worker", "--songs-worker", "--classic")):
        return download_backend.run_cli()
    if os.name == "nt":
        # Evita que Windows agrupe la ventana bajo python.exe y use su icono.
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            "MusicBox.Desktop")
    QQuickStyle.setStyle("Material")
    app = QApplication(sys.argv)
    app.setApplicationName("MusicBox")
    app.setOrganizationName("MusicBox")
    icon = CODE_DIR / "assets" / "logo_app.ico"
    if icon.is_file():
        app.setWindowIcon(QIcon(str(icon)))
    engine = QQmlApplicationEngine()
    bridge = MusicBoxBridge()
    translator = i18n.Translator(app)
    app.installTranslator(translator)
    bridge.attach_engine(engine)
    engine.rootContext().setContextProperty("musicbox", bridge)
    downloads = DownloadsController(bridge)
    bridge.downloads = downloads
    engine.rootContext().setContextProperty("downloads", downloads)
    engine.addImageProvider("importer", ImporterImageProvider(downloads.importer))
    engine.load(QUrl.fromLocalFile(str(CODE_DIR / "ui" / "Main.qml")))
    if not engine.rootObjects():
        return 1
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
