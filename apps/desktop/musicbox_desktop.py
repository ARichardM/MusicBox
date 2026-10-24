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

from PySide6.QtCore import QObject, Property, QSettings, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QIcon
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtWidgets import QApplication, QFileDialog
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer

import descargador_gui as download_backend
from app_paths import CODE_DIR, DATA_ROOT, DEFAULT_DOWNLOAD_DIR, worker_command
from historial import DownloadHistory
from musicbox_cloud import FirebaseDesktopClient


ROOT = DATA_ROOT
MEDIA_EXTENSIONS = {".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".mp4", ".mkv", ".webm"}
COVER_DIR = ROOT / "datos" / "portadas"


def _first(value, default=""):
    if isinstance(value, (list, tuple)):
        value = value[0] if value else default
    return str(value or default)


def media_info(path):
    """Return normalized tags and an extracted embedded cover for QML."""
    result = {"title": path.stem, "artist": "Artista desconocido", "album": "", "duration": 0,
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
    _searchFinished = Signal(object, str)
    _downloadEvent = Signal(object)
    _streamReady = Signal(object, str)
    _authResult = Signal(object, str)

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
        self._status = "Todo listo"
        self._settings = QSettings("MusicBox", "Desktop")
        self._recent_paths = list(self._settings.value("recentPlayed", [], list) or [])
        self._recent_queries = list(self._settings.value("recentQueries", [], list) or [])
        try:
            self._play_counts = json.loads(self._settings.value("playCounts", "{}", str) or "{}")
        except (TypeError, ValueError):
            self._play_counts = {}
        self._recorded_source = ""
        self._cloud = FirebaseDesktopClient(self._settings.value("firebaseRefreshToken", "", str))
        self._device_id = self._settings.value("deviceId", "", str) or uuid.uuid4().hex
        self._settings.setValue("deviceId", self._device_id)
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
        self._download_status = "Todo listo para descargar"
        self._download_detail = "Pega uno o más enlaces para comenzar."
        self._download_files = []
        self._download_log = ""
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
        self._auto_download_playlists = set(self._settings.value("autoDownloadPlaylists", [], list) or [])
        try:
            self._playlist_descriptions = json.loads(self._settings.value("playlistDescriptions", "{}", str) or "{}")
        except (ValueError, TypeError):
            self._playlist_descriptions = {}
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
        self._authResult.connect(self._apply_auth_result)
        self.refresh()
        if self._cloud.refresh_token:
            threading.Thread(target=self._restore_session, daemon=True).start()

    @Property("QVariantList", notify=searchResultsChanged)
    def searchResults(self):
        return self._search_results

    @Property("QVariantList", notify=libraryChanged)
    def library(self):
        return self._library

    @Property("QVariantList", notify=foldersChanged)
    def folders(self):
        return self._folders

    @Property("QVariantList", notify=playlistsChanged)
    def playlists(self):
        result = []
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
                "subtitle": f"{len(keys)} canción{'es' if len(keys) != 1 else ''}",
                "description": self._playlist_descriptions.get(name, ""),
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
        rows = [row for row in self._library_all if self._play_counts.get(row.get("path", ""), 0) > 0]
        return sorted(rows, key=lambda row: (-self._play_counts.get(row.get("path", ""), 0),
                                             row.get("title", "").casefold()))[:5]

    @Property("QVariantList", notify=discoveryChanged)
    def topCollections(self):
        result = []
        root = ROOT / "Descargas"
        grouped = {}
        for row in self._library_all:
            try:
                relative = Path(row["path"]).parent.relative_to(root)
                name = "Descargas" if str(relative) == "." else relative.parts[0]
            except (ValueError, KeyError):
                continue
            grouped.setdefault(name, []).append(row)
        for name, rows in grouped.items():
            plays = sum(int(self._play_counts.get(row.get("path", ""), 0)) for row in rows)
            if plays:
                result.append({"name": name, "plays": plays, "subtitle": f"Carpeta · {plays} reproducciones",
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
    def downloadLog(self): return self._download_log

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
        vid = self._current.get("id", "")
        path = self._current.get("path", "")
        key = path if path else (f"youtube:{vid}" if vid else self._current.get("url", ""))
        return bool(key in self._favorites or (vid and f"youtube:{vid}" in self._favorites))

    @Property(str, constant=True)
    def defaultDownloadFolder(self): return str(ROOT / "Descargas")

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
            else:
                self._auto_download_playlists.discard(name)
            self._save_playlists()
            self.playlistsChanged.emit()
        self.autoDownloadChanged.emit()
        self._schedule_cloud_save()

    def _save_playlists(self):
        self._playlists["Favoritos"] = list(self._favorites)
        self._settings.setValue("playlistsData", json.dumps(self._playlists))
        self._settings.setValue("onlineTracksData", json.dumps(self._online_tracks))
        self._settings.setValue("autoDownloadPlaylists", list(self._auto_download_playlists))
        self._settings.setValue("playlistDescriptions", json.dumps(self._playlist_descriptions))

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
        vid = item.get("id", "")
        path = item.get("path", "")
        key = path if path else (f"youtube:{vid}" if vid else item.get("url", ""))
        if not key: return
        self._playlists.setdefault(playlist_name, [])
        added = key not in self._playlists[playlist_name]
        if added:
            self._playlists[playlist_name].append(key)
            if playlist_name == "Favoritos":
                self._favorites.add(key)
        if not path and key:
            self._online_tracks[key] = {
                "id": vid,
                "title": item.get("title", ""),
                "channel": item.get("subtitle", item.get("channel", "YouTube")),
                "subtitle": item.get("subtitle", item.get("channel", "YouTube")),
                "duration": item.get("duration", 0),
                "thumbnail": item.get("thumbnail", ""),
                "url": item.get("url") or (f"https://www.youtube.com/watch?v={vid}" if vid else "")
            }
            if playlist_name == "Favoritos":
                self._online_favorites_meta[key] = dict(self._online_tracks[key])
        self._save_playlists()
        if playlist_name == "Favoritos":
            self._settings.setValue("favorites", list(self._favorites))
            self._settings.setValue("onlineFavoritesMeta", json.dumps(self._online_favorites_meta))
        if added and playlist_name in self._auto_download_playlists and not path and not item.get("downloaded"):
            self.downloadOnlineTrack(item)
        self.playlistsChanged.emit()
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
        name = name.strip()
        keys = self._playlists.get(name, [])
        for k in keys:
            meta = self._online_tracks.get(k)
            if not meta and k.startswith("youtube:"):
                vid = k.split("youtube:", 1)[1]
                meta = {"id": vid, "url": f"https://www.youtube.com/watch?v={vid}", "title": "Canción de YouTube"}
            if meta:
                vid = meta.get("id", "")
                if not self._is_video_downloaded(vid, meta.get("title", "")):
                    self.downloadOnlineTrack(meta)

    @Slot()
    def downloadAllInFavorites(self):
        for key in list(self._favorites):
            meta = self._online_favorites_meta.get(key)
            if not meta and key.startswith("youtube:"):
                vid = key.split("youtube:", 1)[1]
                meta = {"id": vid, "url": f"https://www.youtube.com/watch?v={vid}", "title": "Favorita"}
            if meta:
                vid = meta.get("id", "")
                if not self._is_video_downloaded(vid, meta.get("title", "")):
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

    def _is_video_downloaded(self, video_id, title=""):
        norm_title = "".join(c for c in title.lower() if c.isalnum())
        for row in self._library_all:
            path = row.get("path", "")
            if video_id and video_id in path: return True
            row_title = "".join(c for c in row.get("title", "").lower() if c.isalnum())
            if norm_title and len(norm_title) > 3 and (norm_title in row_title or row_title in norm_title): return True
        return False

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
            item["lastSeenText"] = time.strftime("%d/%m/%Y %H:%M", time.localtime(seen / 1000)) if seen else "Sin actividad reciente"
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
                self._authResult.emit({"user": user, "state": state,
                                       "refresh": self._cloud.refresh_token}, "")
            except Exception as exc:
                self._authResult.emit({}, str(exc))
        threading.Thread(target=worker, daemon=True).start()

    def _restore_session(self):
        try:
            user = self._cloud.restore()
            if not user:
                self._authResult.emit({}, "")
                return
            self._authResult.emit({"user": user, "state": self._cloud.load_state(),
                                   "refresh": self._cloud.refresh_token}, "")
        except Exception as exc:
            self._authResult.emit({}, str(exc))

    @Slot(object, str)
    def _apply_auth_result(self, result, error):
        self._auth_busy = False
        self._auth_error = error
        if result:
            self._user = dict(result.get("user") or {})
            uid = self._user.get("uid", "")
            self._settings.setValue("firebaseRefreshToken", result.get("refresh", ""))
            state = result.get("state") or {}
            self._cloud_snapshot = dict(state)
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
            if "playCounts" in state and isinstance(state.get("playCounts"), dict):
                self._play_counts = dict(state["playCounts"])
                self._settings.setValue("playCounts", json.dumps(self._play_counts))
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
                    self._online_tracks = dict(p_data["onlineTracks"])
                if "autoDownload" in p_data and isinstance(p_data["autoDownload"], list):
                    self._auto_download_playlists = set(p_data["autoDownload"])
                if "descriptions" in p_data and isinstance(p_data["descriptions"], dict):
                    self._playlist_descriptions = dict(p_data["descriptions"])
                self._merge_favorites_sources()
                self._save_playlists()
                self.playlistsChanged.emit()
            self._settings.setValue("lastFirebaseUid", uid)
            self._settings.setValue(local_key, list(self._favorites))
            self._settings.setValue("favorites", list(self._favorites))
            self._apply_library_view()
            self.discoveryChanged.emit()
            self.playerChanged.emit()
            self._schedule_cloud_save()
        else:
            self._user = {}
            if not error:
                self._settings.remove("firebaseRefreshToken")
        self.authChanged.emit()

    @Slot()
    def signOut(self):
        self._settings.remove("firebaseRefreshToken")
        self._cloud.refresh_token = ""
        self._cloud.id_token = ""
        self._cloud.user = {}
        self._user = {}
        self._auth_error = ""
        self.authChanged.emit()

    def _cloud_state(self):
        library = [{key: item.get(key, "") for key in
                    ("title", "artist", "album", "duration", "thumbnail")}
                   for item in self._library_all]
        playlists_export = {
            "playlists": self._playlists,
            "onlineTracks": self._online_tracks,
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
                      "playCounts": self._play_counts})
        return state

    def _schedule_cloud_save(self):
        if not self._user:
            return
        state = self._cloud_state()
        def worker():
            try:
                self._cloud.save_state(state)
            except Exception:
                pass
        threading.Thread(target=worker, daemon=True).start()

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
        download_dir = ROOT / "Descargas"
        files = []
        if download_dir.is_dir():
            for path in sorted(download_dir.rglob("*"), key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True):
                if path.is_file() and path.suffix.lower() in MEDIA_EXTENSIONS:
                    info = media_info(path)
                    info["favorite"] = str(path) in self._favorites
                    files.append(info)
        self._library_all = files
        grouped = {}
        for item in files:
            relative = Path(item["path"]).parent.relative_to(download_dir)
            folder = "Descargas" if str(relative) == "." else relative.parts[0]
            grouped.setdefault(folder, []).append(item)
        self._folders = [{"name": name, "count": len(items),
                          "subtitle": f"{len(items)} canción{'es' if len(items) != 1 else ''}",
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
            for k in keys:
                if k in by_path:
                    item = dict(by_path[k])
                    item["playlistKey"] = k
                    resolved.append(item)
                elif k in self._online_tracks:
                    meta = dict(self._online_tracks[k])
                    vid = meta.get("id", "")
                    meta["downloaded"] = self._is_video_downloaded(vid, meta.get("title", ""))
                    meta["favorite"] = bool(f"youtube:{vid}" in self._favorites or meta.get("url", "") in self._favorites)
                    meta["web"] = True
                    meta["playlistKey"] = k
                    resolved.append(meta)
                elif k.startswith("youtube:"):
                    vid = k.split("youtube:", 1)[1]
                    dl_row = next((r for r in self._library_all if vid in r.get("path", "")), None)
                    if dl_row:
                        item = dict(dl_row)
                        item["playlistKey"] = k
                        resolved.append(item)
                    else:
                        resolved.append({
                            "id": vid,
                            "title": "Canción online",
                            "subtitle": "YouTube",
                            "url": f"https://www.youtube.com/watch?v={vid}",
                            "thumbnail": f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg",
                            "web": True,
                            "downloaded": False,
                            "playlistKey": k
                        })
            rows = resolved
        elif self._library_folder:
            root = ROOT / "Descargas"
            rows = [row for row in rows if
                    ("Descargas" if str(Path(row["path"]).parent.relative_to(root)) == "."
                     else Path(row["path"]).parent.relative_to(root).parts[0]) == self._library_folder]
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
        self._set_status("Buscando en YouTube…")

        def worker():
            results, error = [], ""
            try:
                import yt_dlp
                options = {"quiet": True, "no_warnings": True, "extract_flat": True, "skip_download": True,
                           "socket_timeout": 8, "playlistend": 12}
                with yt_dlp.YoutubeDL(options) as downloader:
                    data = downloader.extract_info("ytsearch12:" + query, download=False)
                for item in (data or {}).get("entries") or []:
                    video_id = item.get("id") or ""
                    results.append({
                        "id": video_id,
                        "title": item.get("title") or "Sin título",
                        "subtitle": item.get("channel") or item.get("uploader") or "YouTube",
                        "thumbnail": f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
                        "url": item.get("webpage_url") or f"https://www.youtube.com/watch?v={video_id}",
                    })
            except Exception as exc:
                error = str(exc).splitlines()[-1] if str(exc) else "No se pudo completar la búsqueda"
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
        self._set_status(error or f"{len(results)} resultados")

    @Slot()
    def clearSearch(self):
        self._search_generation += 1
        self._search_results = []
        self.searchResultsChanged.emit()
        self._set_busy(False)
        self._set_status("Todo listo")

    @Slot(result=str)
    def chooseDownloadFolder(self):
        selected = QFileDialog.getExistingDirectory(None, "Carpeta de descargas", str(ROOT / "Descargas"))
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
            folder = Path(value.strip() or ROOT / "Descargas").expanduser().resolve()
            folder.mkdir(parents=True, exist_ok=True)
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(folder)))
        except OSError as exc:
            self._download_status = "No se pudo abrir la carpeta"
            self._download_detail = str(exc)
            self.downloadStateChanged.emit()

    @Slot(str, bool, str, str, str, str, str)
    def startDownload(self, raw_urls, playlist, items, media_format, quality, destination, cookies):
        if self._download_running:
            return
        try:
            urls = download_backend.parse_urls(raw_urls, playlist)
            selected_items = download_backend.validate_items(items) if playlist else ""
            target = Path(destination.strip() or DEFAULT_DOWNLOAD_DIR).expanduser().resolve()
            target.mkdir(parents=True, exist_ok=True)
            ffmpeg = download_backend.find_ffmpeg()
            if not ffmpeg:
                raise ValueError("No se encontró FFmpeg. Coloca ffmpeg.exe junto al programa o en PATH.")
            cookie_path = cookies.strip()
            if cookie_path and not Path(cookie_path).is_file():
                raise ValueError("El archivo de cookies no existe.")
            config = dict(urls=urls, playlist=playlist, items=selected_items, destination=str(target),
                          ffmpeg=ffmpeg, format=media_format, quality=quality, cookies=cookie_path,
                          history_path=str(DownloadHistory().path))
        except (ValueError, OSError) as exc:
            self._download_status = "Revisa los datos"
            self._download_detail = str(exc)
            self.downloadStateChanged.emit()
            return
        self._download_running = True
        self._download_cancelled = False
        self._download_progress = 0
        self._download_indeterminate = True
        self._download_status = "Analizando enlaces…"
        self._download_detail = "Buscando los formatos disponibles."
        self._download_files = []
        self._download_log = f"Destino: {target}\nFormato: {media_format} · {quality}\n"
        self.downloadStateChanged.emit(); self.downloadFilesChanged.emit(); self.downloadLogChanged.emit()
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
        if kind == "link":
            self._download_status = f"Analizando enlace {event.get('index')} de {event.get('count')}…"
            self._download_detail = "Consultando la información del enlace."
            self._download_indeterminate = True
        elif kind == "progress":
            total, downloaded = event.get("total"), event.get("downloaded", 0)
            self._download_status = event.get("title") or "Descargando…"
            if total:
                self._download_progress = min(1.0, downloaded / total); self._download_indeterminate = False
                parts = [f"{download_backend.human_bytes(downloaded)} / {download_backend.human_bytes(total)}"]
                if event.get("speed"): parts.append(download_backend.human_bytes(event["speed"]) + "/s")
                if event.get("eta") is not None: parts.append(f"{int(event['eta'])} s restantes")
                self._download_detail = " · ".join(parts)
            else: self._download_indeterminate = True
        elif kind == "processing":
            self._download_status = event.get("text", "Procesando archivo…"); self._download_indeterminate = True
        elif kind == "ready":
            filename = event.get("filename", "")
            self._download_files.append({"title": Path(filename).name, "subtitle": "Listo", "path": filename})
            self.downloadFilesChanged.emit()
            self._append_download_log("Listo: " + filename)
            for vid in list(self._downloading_ids):
                if vid in filename: self._downloading_ids.discard(vid)
            self.downloadingIdsChanged.emit()
            self.refresh()
        elif kind == "log": self._append_download_log(event.get("text", ""))
        elif kind == "summary":
            self._download_detail = f"{event.get('completed', 0)} completadas · {event.get('errors', 0)} errores"
        elif kind == "done":
            self._download_running = False; self._download_indeterminate = False
            self._downloading_ids.clear()
            self.downloadingIdsChanged.emit()
            if event.get("cancelled"):
                self._download_status = "Descarga cancelada"
            else:
                self._download_progress = 1.0
                self._download_status = "Descargas terminadas"
            self.refresh()
        self.downloadStateChanged.emit()

    def _append_download_log(self, text):
        if not text: return
        lines = (self._download_log + text + "\n").splitlines()[-600:]
        self._download_log = "\n".join(lines)
        self.downloadLogChanged.emit()

    @Slot()
    def cancelDownload(self):
        if not self._download_running: return
        self._download_cancelled = True
        self._download_status = "Cancelando…"; self.downloadStateChanged.emit()
        process = self._download_process
        if process is not None: threading.Thread(target=download_backend.stop_process, args=(process,), daemon=True).start()

    @Slot()
    def clearDownloadLog(self):
        self._download_log = ""; self.downloadLogChanged.emit()

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
                self._play_counts[path] = int(self._play_counts.get(path, 0)) + 1
                self._settings.setValue("recentPlayed", self._recent_paths)
                self._settings.setValue("playCounts", json.dumps(self._play_counts))
                self.discoveryChanged.emit()
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
        self._set_status("Preparando reproducción…")
        self.playerChanged.emit()
        def resolve():
            stream, error = "", ""
            try:
                import yt_dlp
                with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "format": "bestaudio/best", "noplaylist": True, "socket_timeout": 10}) as ydl:
                    info = ydl.extract_info(url, download=False)
                stream = info.get("url", "")
                self._current.update(title=info.get("track") or info.get("title") or self._current.get("title"),
                                     artist=info.get("artist") or info.get("uploader") or self._current.get("artist"),
                                     album=info.get("album") or "Streaming")
            except Exception as exc:
                error = str(exc).splitlines()[-1]
            self._streamReady.emit(dict(self._current), stream or error)
        threading.Thread(target=resolve, daemon=True).start()

    @Slot(object, str)
    def _play_stream(self, track, source):
        if not source.startswith(("http://", "https://")):
            self._set_status(source or "No se pudo reproducir este resultado")
            return
        self._current = dict(track)
        self._player.setSource(QUrl(source))
        self._player.play()
        self._set_status("Reproduciendo")
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

    @Slot(int)
    def playQueueIndex(self, index):
        if 0 <= index < len(self._queue):
            self._queue_index = index
            self._start_local_track(dict(self._queue[index]))

    @Slot("QVariantMap")
    def toggleFavoriteFor(self, track):
        item = dict(track or {})
        vid = item.get("id", "")
        path = item.get("path", "")
        key = path if path else (f"youtube:{vid}" if vid else item.get("url", ""))
        if not key: return
        if key in self._favorites:
            self._favorites.remove(key)
            self._online_favorites_meta.pop(key, None)
            if vid: self._online_favorites_meta.pop(f"youtube:{vid}", None)
        else:
            self._favorites.add(key)
            if not path:
                meta = dict(item)
                meta["favorite"] = True
                meta["web"] = True
                self._online_favorites_meta[key] = meta
            if self.isAutoDownload("favorites") and item.get("url") and not item.get("downloaded"):
                self.downloadOnlineTrack(item)
        self._playlists["Favoritos"] = list(self._favorites)
        self._save_playlists()
        self.playlistsChanged.emit()
        self._settings.setValue("favorites", list(self._favorites))
        self._settings.setValue("onlineFavoritesMeta", json.dumps(self._online_favorites_meta))
        if self._user:
            self._settings.setValue("favorites/" + self._user.get("uid", ""), list(self._favorites))
        self._library_all = [dict(it, favorite=it.get("path") in self._favorites) for it in self._library_all]
        self._search_results = [
            dict(it, favorite=bool(f"youtube:{it.get('id', '')}" in self._favorites or it.get("url", "") in self._favorites or it.get("path", "") in self._favorites))
            for it in self._search_results
        ]
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
        vid = self._current.get("id", "")
        path = self._current.get("path", "")
        key = path if path else (f"youtube:{vid}" if vid else self._current.get("url", ""))
        if not key: return
        if key in self._favorites:
            self._favorites.remove(key)
            self._online_favorites_meta.pop(key, None)
            if vid: self._online_favorites_meta.pop(f"youtube:{vid}", None)
        else:
            self._favorites.add(key)
            if not path:
                meta = dict(self._current)
                meta["favorite"] = True
                meta["web"] = True
                self._online_favorites_meta[key] = meta
            if "Favoritos" in self._auto_download_playlists and self._current.get("url") and not self._current.get("downloaded"):
                self.downloadOnlineTrack(self._current)
        self._playlists["Favoritos"] = list(self._favorites)
        self._save_playlists()
        self.playlistsChanged.emit()
        self._settings.setValue("favorites", list(self._favorites))
        self._settings.setValue("onlineFavoritesMeta", json.dumps(self._online_favorites_meta))
        if self._user:
            self._settings.setValue("favorites/" + self._user.get("uid", ""), list(self._favorites))
        self._library_all = [dict(it, favorite=it.get("path") in self._favorites) for it in self._library_all]
        self._search_results = [
            dict(it, favorite=bool(f"youtube:{it.get('id', '')}" in self._favorites or it.get("url", "") in self._favorites or it.get("path", "") in self._favorites))
            for it in self._search_results
        ]
        self._apply_library_view()
        self.searchResultsChanged.emit()
        self.playerChanged.emit()
        self.discoveryChanged.emit()
        self._schedule_cloud_save()

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
        print("MusicBox 2.2.1")
        return 0
    if any(flag in sys.argv for flag in ("--worker", "--audio-worker", "--player-worker", "--songs-worker", "--classic")):
        return download_backend.run_cli()
    if os.name == "nt":
        # Evita que Windows agrupe la ventana bajo python.exe y use su icono.
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            "MusicBox.Desktop")
    app = QApplication(sys.argv)
    app.setApplicationName("MusicBox")
    app.setOrganizationName("MusicBox")
    icon = CODE_DIR / "assets" / "logo_app.ico"
    if icon.is_file():
        app.setWindowIcon(QIcon(str(icon)))
    engine = QQmlApplicationEngine()
    bridge = MusicBoxBridge()
    engine.rootContext().setContextProperty("musicbox", bridge)
    engine.load(QUrl.fromLocalFile(str(CODE_DIR / "ui" / "Main.qml")))
    if not engine.rootObjects():
        return 1
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
