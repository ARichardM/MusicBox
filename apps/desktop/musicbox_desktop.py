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
from copy import deepcopy

from PySide6.QtCore import QFile, QObject, Property, QSettings, QTimer, QUrl, Signal, Slot
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
import ytmusic
from recommendations import PER_SEED, Recommender, pick_autoplay
import track_identity as identity
import playlist_sync
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
    forYouChanged = Signal()
    artistChanged = Signal()
    artistPhotosChanged = Signal()
    _artistAvatarReady = Signal(str, object)
    _artistReady = Signal(object, int)
    _artistSongsReady = Signal(object, int)
    _albumReady = Signal(object, int)
    _forYouReady = Signal(object, int)
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
    _autoplayReady = Signal(str, list, int)
    autoplayChanged = Signal()
    _authResult = Signal(object, str)
    _remotePlaylists = Signal(object)
    _devicesPolled = Signal(object, bool)
    _deviceRevoked = Signal(str, bool)

    def __init__(self, settings=None, load_startup=True):
        super().__init__()
        self._search_results = []
        self._library = []
        self._library_all = []
        self._folders = []
        self._library_query = ""
        self._library_artist = ""
        self._library_artists = []
        self._queue_artist = ""
        self._library_filter = "songs"
        self._library_folder = ""
        self._history = []
        self._busy = False
        self._settings = settings if settings is not None else QSettings("MusicBox", "Desktop")
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
        self._shuffle = str(self._settings.value("shuffleOn", "false")).lower() == "true"
        self._queue_original = []
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
        try:
            linked = json.loads(self._settings.value("linkedLocalIds", "{}", str) or "{}")
        except (ValueError, TypeError):
            linked = {}
        self._linked_ids = {str(k): str(v) for k, v in linked.items()} if isinstance(linked, dict) else {}
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
        self._update_check = "idle"   # idle, checking, uptodate, failed; «available» se deduce del estado
        self._update_progress = 0.0
        self._update_error = ""
        self._update_cancelled = False
        self._merge_favorites_sources()
        self._save_playlists()
        self._settings.setValue("favorites", list(self._favorites))
        self._player.positionChanged.connect(lambda _: self.playerChanged.emit())
        self._player.durationChanged.connect(lambda _: self.playerChanged.emit())
        self._player.playbackStateChanged.connect(self._playback_state_changed)
        self._listening_sample = None
        self._listening_started = False
        self._last_listening_save = 0.0
        self._listening_timer = QTimer(self)
        self._listening_timer.setInterval(1000)
        self._listening_timer.timeout.connect(self._sample_listening)
        self._listening_timer.start()
        self._player.mediaStatusChanged.connect(self._media_status)
        self._searchFinished.connect(self._apply_search)
        self._artist_open = self._album_open = False
        self._artist, self._album, self._artist_all = {}, {}, []
        self._artist_loading = self._album_loading = self._artist_all_loading = self._artist_all_loaded = False
        self._artist_error = ""
        self._artist_gen = self._album_gen = 0
        self._online_queue, self._online_index = [], -1
        self._online_original = []
        self._online_generation = 0
        self._stream_generation = 0
        self._online_waiting = False
        try:
            self._artist_directory = dict(json.loads(self._settings.value("artistDirectory", "{}", str) or "{}"))
        except (TypeError, ValueError):
            self._artist_directory = {}
        self._artist_avatar_pending = set()
        self._artist_avatar_retry = {}
        self._artist_avatar_slots = threading.Semaphore(2)
        self._artistAvatarReady.connect(self._apply_artist_avatar)
        self._artistReady.connect(self._apply_artist)
        self._artistSongsReady.connect(self._apply_artist_songs)
        self._albumReady.connect(self._apply_album)
        self._for_you = {"songs": [], "artists": [], "mode": ""}
        self._for_you_loading = False
        self._for_you_generation = 0
        self._recommender = Recommender(
            self._related_songs,
            lambda query: youtube_streams.search(query, self._extract_info, 12))
        try:
            self._recommender.restore(json.loads(self._settings.value("forYouCache", "{}", str) or "{}"))
        except (TypeError, ValueError):
            pass
        self._forYouReady.connect(self._apply_for_you)
        self._downloadEvent.connect(self._handle_download_event)
        self._streamReady.connect(self._play_stream)
        self._autoplayReady.connect(self._apply_autoplay)
        self._autoplay_tried, self._autoplay_loading = "", False
        self._updateEvent.connect(self._handle_update_event)
        self._player.errorOccurred.connect(self._player_error)
        self._authResult.connect(self._apply_auth_result)
        self._remotePlaylists.connect(self._apply_remote_playlists)
        self._sync_base = {}
        self._sync_base_section = {}
        self._cloud_sync_generation = 0
        self._cloud_sync_lock = threading.Lock()
        self._devicesPolled.connect(self._apply_polled_devices)
        self._deviceRevoked.connect(self._device_revoked)
        self._device_timer = QTimer(self)
        self._device_timer.setInterval(45000)
        self._device_timer.timeout.connect(self._poll_devices)
        self._device_timer.start()
        if load_startup:
            self.refresh()
            if self._cloud.refresh_token:
                threading.Thread(target=self._restore_session, daemon=True).start()
            QTimer.singleShot(4000, self.checkForUpdate)
            QTimer.singleShot(2500, self.refreshForYou)

    @Property("QVariantList", notify=searchResultsChanged)
    def searchResults(self):
        return self._search_results

    def _save_clicks(self):
        self._settings.setValue("clickStats", json.dumps(self._clicks_stats.export(), ensure_ascii=False, separators=(",", ":")))

    @Slot()
    def flushListening(self):
        self._sample_listening()
        self._save_clicks()
        self._settings.sync()

    # --- Artistas: foto, canciones más escuchadas, todas sus canciones y discografía ---

    @Property(bool, notify=artistChanged)
    def artistOpen(self): return self._artist_open

    @Property(str, notify=artistChanged)
    def artistName(self): return self._artist.get("name", "")

    @Property(str, notify=artistChanged)
    def artistPhoto(self): return self._artist.get("photo", "")

    @Property(str, notify=artistChanged)
    def artistSubscribers(self): return self._artist.get("subscribers", "")

    @Property(str, notify=artistChanged)
    def artistError(self): return self._artist_error

    @Property(bool, notify=artistChanged)
    def artistLoading(self): return self._artist_loading

    @Property("QVariantList", notify=artistChanged)
    def artistTopSongs(self): return self._artist.get("top_songs", [])

    @Property("QVariantList", notify=artistChanged)
    def artistAlbums(self): return self._artist.get("albums", [])

    @Property("QVariantList", notify=artistChanged)
    def artistSingles(self): return self._artist.get("singles", [])

    @Property("QVariantList", notify=artistChanged)
    def artistAllSongs(self): return self._artist_all

    @Property(bool, notify=artistChanged)
    def artistCanLoadAll(self): return bool(self._artist.get("songs_playlist")) and not self._artist_all_loaded

    @Property(bool, notify=artistChanged)
    def artistAllLoading(self): return self._artist_all_loading

    @Property(bool, notify=artistChanged)
    def albumOpen(self): return self._album_open

    @Property(bool, notify=artistChanged)
    def albumLoading(self): return self._album_loading

    @Property(str, notify=artistChanged)
    def albumTitle(self): return self._album.get("title", "")

    @Property(str, notify=artistChanged)
    def albumYear(self): return self._album.get("year", "")

    @Property(str, notify=artistChanged)
    def albumArtist(self): return self._album.get("artist", "")

    @Property(str, notify=artistChanged)
    def albumThumbnail(self): return self._album.get("thumbnail", "")

    @Property("QVariantList", notify=artistChanged)
    def albumTracks(self): return self._album.get("tracks", [])

    def _decorate_tracks(self, tracks):
        """Marca descargadas y favoritas, igual que los resultados de búsqueda."""
        for item in tracks:
            video_id = item.get("id", "")
            self._mark_local(item, video_id, item.get("title", ""))
            item["favorite"] = bool(f"youtube:{video_id}" in self._favorites or item.get("url", "") in self._favorites)
        return tracks

    @Slot(str, str)
    def openArtist(self, browse_id, name):
        self._artist_gen += 1
        generation = self._artist_gen
        self._artist_open = True
        self._artist = {"name": name or "", "browse_id": browse_id or ""}
        self._artist_all, self._artist_all_loaded, self._artist_all_loading = [], False, False
        self._artist_loading, self._artist_error = True, ""
        self._album_open, self._album = False, {}
        self.artistChanged.emit()

        def worker():
            try:
                target = browse_id
                if not target:
                    found = ytmusic.find_artist(name)
                    target = found["browse_id"] if found else ""
                page = ytmusic.artist_page(target) if target else {}
                self._artistReady.emit(page, generation)
            except Exception as exc:
                self._artistReady.emit({"error": str(exc).splitlines()[-1] if str(exc) else "error"}, generation)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, int)
    def _apply_artist(self, page, generation):
        if generation != self._artist_gen:
            return
        self._artist_loading = False
        if page.get("error") or not page.get("name"):
            self._artist_error = i18n.tr("No se pudo cargar este artista. Revisa tu conexión.")
        else:
            self._decorate_tracks(page.get("top_songs", []))
            self._artist = page
        self.artistChanged.emit()

    @Slot()
    def closeArtist(self):
        self._artist_gen += 1
        self._artist_open = self._album_open = False
        self._artist, self._album, self._artist_all = {}, {}, []
        self._artist_all_loaded = self._artist_all_loading = self._artist_loading = False
        self.artistChanged.emit()

    @Slot()
    def loadAllArtistSongs(self):
        playlist = self._artist.get("songs_playlist")
        if not playlist or self._artist_all_loading or self._artist_all_loaded:
            return
        generation = self._artist_gen
        self._artist_all_loading = True
        self.artistChanged.emit()
        name = self._artist.get("name", "")

        def worker():
            try:
                tracks = ytmusic.playlist_tracks(playlist, limit=300, artist=name)
            except Exception:
                tracks = []
            self._artistSongsReady.emit(tracks, generation)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, int)
    def _apply_artist_songs(self, tracks, generation):
        if generation != self._artist_gen:
            return
        self._artist_all = self._decorate_tracks(tracks)
        self._artist_all_loading = False
        self._artist_all_loaded = bool(tracks)
        self.artistChanged.emit()

    @Slot(str)
    def openAlbum(self, browse_id):
        if not browse_id:
            return
        self._album_gen += 1
        generation = self._album_gen
        self._album_open, self._album_loading, self._album = True, True, {}
        self.artistChanged.emit()

        def worker():
            try:
                page = ytmusic.album_page(browse_id)
            except Exception:
                page = {}
            self._albumReady.emit(page, generation)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, int)
    def _apply_album(self, page, generation):
        if generation != self._album_gen:
            return
        self._album_loading = False
        if page.get("tracks"):
            self._decorate_tracks(page["tracks"])
            self._album = page
        else:
            self._album_open = False
            self._show_notice(i18n.tr("No se pudo cargar este álbum."), None)
        self.artistChanged.emit()

    @Slot()
    def closeAlbum(self):
        self._album_gen += 1
        self._album_open, self._album_loading, self._album = False, False, {}
        self.artistChanged.emit()

    @Property(bool, notify=autoplayChanged)
    def autoplay(self):
        return self._settings.value("autoplay", True, bool)

    @Slot(bool)
    def setAutoplay(self, enabled):
        self._settings.setValue("autoplay", bool(enabled))
        self.autoplayChanged.emit()
        if enabled and self._current.get("url") and not self._current.get("path"):
            self._maybe_autoplay(self._current)

    def _maybe_autoplay(self, item):
        """Al sonar lo último de la cola online, busca canciones parecidas y las deja a continuación."""
        if getattr(self, "_queue_artist", ""):
            return
        video_id = item.get("id")
        if not self.autoplay or not video_id or self._autoplay_loading or video_id == self._autoplay_tried:
            return
        queue = self._online_queue
        if queue and self._online_index < len(queue) - 1:
            return
        if not queue or not 0 <= self._online_index < len(queue) or queue[self._online_index].get("id") != video_id:
            self._online_queue, self._online_index = [dict(item)], 0
        self._autoplay_tried, self._autoplay_loading = video_id, True
        generation = self._online_generation

        def work():
            try:
                tracks = self._related_songs(video_id)
            except Exception:
                tracks = []
            self._autoplayReady.emit(video_id, tracks or [], generation)
        threading.Thread(target=work, daemon=True).start()

    @Slot(str, list, int)
    def _apply_autoplay(self, video_id, tracks, generation):
        if generation != self._online_generation:
            return
        self._autoplay_loading = False
        queue = self._online_queue
        if not self.autoplay or not queue or self._current.get("id") != video_id and queue[-1].get("id") != video_id:
            return
        queue.extend(pick_autoplay(tracks, [track.get("id") for track in queue]))
        self.playerChanged.emit()
        if self._online_waiting:
            self._online_waiting = False
            self._advance_online(1)

    def _reset_online_queue(self):
        self._online_generation += 1
        self._online_queue, self._online_index, self._online_original = [], -1, []
        self._autoplay_tried, self._autoplay_loading, self._online_waiting = "", False, False

    def _online_queue_active(self):
        return bool(self._online_queue and self._current.get("url") and not self._current.get("path"))

    def _advance_online(self, step):
        """Pasa a la siguiente (o anterior) canción de la lista online en curso. True si había una."""
        queue = self._online_queue
        if not queue or not 0 <= self._online_index < len(queue) or self._current.get("id") != queue[self._online_index].get("id"):
            return False
        target = self._online_index + step
        if self._repeat_mode == 1:
            target %= len(queue)
        if 0 <= target < len(queue):
            self._online_index = target
            self._play_online(queue[target])
        elif step > 0 and self._autoplay_loading:
            self._online_waiting = True
        return True

    @Slot("QVariantList", int)
    def playOnlineList(self, tracks, index):
        """Reproduce una lista de canciones de YouTube Music desde ``index`` y sigue con las demás."""
        queue = [dict(track) for track in (tracks or []) if track.get("url")]
        if not queue or not 0 <= index < len(queue):
            return
        self._queue_artist = ""
        self._reset_online_queue()
        self._online_queue, self._online_index = queue, index
        self._play_online(queue[index])

    @Property("QVariantList", notify=forYouChanged)
    def forYou(self):
        return self._for_you["songs"]

    @Property("QVariantList", notify=forYouChanged)
    def forYouArtists(self):
        return self._for_you["artists"]

    @Property(str, notify=forYouChanged)
    def forYouMode(self):
        return self._for_you.get("mode", "")

    @Property(bool, notify=forYouChanged)
    def forYouLoading(self):
        return self._for_you_loading

    def _related_songs(self, video_id):
        """Canciones parecidas de YouTube Music (solo audio oficial); si falla, la lista Mix de YouTube."""
        try:
            tracks = ytmusic.related(video_id, PER_SEED)
            if tracks:
                return tracks
        except Exception:
            pass
        return youtube_streams.related(video_id, self._extract_info, PER_SEED)

    def _resolve_artist(self, name):
        """Foto e id de YouTube Music de un artista; los aciertos se recuerdan para no repetir la consulta."""
        key = ytmusic.normalize(name)
        cached = self._artist_directory.get(key)
        if cached and cached.get("photo"):
            return dict(cached)
        try:
            found = ytmusic.find_artist(name)
        except Exception:
            found = None
        entry = {"name": found["name"] if found else name, "browseId": found["browse_id"] if found else "",
                 "photo": found["photo"] if found else ""}
        if found:
            self._artist_directory[key] = entry
        return entry

    @Property("QVariantMap", notify=artistPhotosChanged)
    def artistPhotos(self):
        directory = getattr(self, "_artist_directory", {})
        return {name: directory.get(ytmusic.normalize(name), {}).get("photo", "")
                for name in getattr(self, "_library_artists", [])}

    @Slot(str)
    def requestArtistPhoto(self, name):
        key = ytmusic.normalize(name)
        if not key:
            return
        if self._artist_directory.get(key, {}).get("photo"):
            self.artistPhotosChanged.emit()
            return
        if key in self._artist_avatar_pending or self._artist_avatar_retry.get(key, 0) > time.monotonic():
            return
        self._artist_avatar_pending.add(key)
        def work():
            with self._artist_avatar_slots:
                try:
                    entry = self._resolve_artist(name)
                except Exception:
                    entry = {"name": name, "photo": ""}
            try:
                self._artistAvatarReady.emit(name, entry)
            except RuntimeError:
                pass  # La ventana pudo cerrarse mientras terminaba la consulta.
        threading.Thread(target=work, daemon=True, name="artist-avatar").start()

    @Slot(str, object)
    def _apply_artist_avatar(self, name, entry):
        key = ytmusic.normalize(name)
        self._artist_avatar_pending.discard(key)
        if entry.get("photo"):
            self._artist_directory[key] = dict(entry)
            self._artist_avatar_retry.pop(key, None)
            self._settings.setValue("artistDirectory", json.dumps(self._artist_directory, ensure_ascii=False))
        else:
            self._artist_avatar_retry[key] = time.monotonic() + 60
        self.artistPhotosChanged.emit()

    @Slot()
    def refreshForYou(self):
        """Recalcula "Para ti". Solo ocurre al abrir la app y al pulsar el botón de actualizar."""
        self._for_you_generation += 1
        generation = self._for_you_generation
        self._for_you_loading = True
        self.forYouChanged.emit()
        known_ids = set(self._path_ids.values())
        known_titles = [row.get("title", "") for row in self._library_all]
        queries = list(self._recent_queries)

        def worker():
            try:
                outcome = dict(self._recommender.compute(self._clicks_stats, known_ids, known_titles, queries, fresh=True))
                outcome["artists"] = [self._resolve_artist(name) for name in outcome.get("artists", [])]
            except Exception:
                outcome = {"songs": [], "artists": [], "mode": "offline"}
            self._forYouReady.emit(outcome, generation)

        threading.Thread(target=worker, daemon=True).start()

    @Slot(object, int)
    def _apply_for_you(self, outcome, generation):
        if generation != self._for_you_generation:
            return
        self._for_you_loading = False
        if outcome.get("mode") == "offline" and self._for_you["songs"]:
            self.forYouChanged.emit()
            return
        songs = []
        for item in outcome.get("songs", []):
            item = dict(item)
            video_id = item.get("id", "")
            self._mark_local(item, video_id, item.get("title", ""))
            item["favorite"] = bool(f"youtube:{video_id}" in self._favorites or item.get("url", "") in self._favorites)
            songs.append(item)
        self._for_you = {"songs": songs, "artists": list(outcome.get("artists", [])), "mode": outcome.get("mode", "")}
        self._settings.setValue("artistDirectory", json.dumps(self._artist_directory, ensure_ascii=False, separators=(",", ":")))
        self.forYouChanged.emit()
        self._settings.setValue("forYouCache", json.dumps(self._recommender.snapshot(), ensure_ascii=False,
                                                         separators=(",", ":")))

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

    @Property("QStringList", notify=libraryChanged)
    def libraryArtists(self): return self._library_artists

    @Property(str, notify=libraryChanged)
    def libraryArtist(self): return self._library_artist

    @staticmethod
    def _track_artist(row):
        # Las playlists antiguas de escritorio guardaban «artista · álbum» como canal.
        return (row.get("artist") or (row.get("channel") or row.get("subtitle") or "").split(" · ", 1)[0]).strip()

    @Slot(str)
    def setLibraryArtist(self, value):
        self._library_artist = value.strip() if self._library_playlist else ""
        self._apply_library_view()

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
        rows = [dict(row, clicks=clicks(row)) for row in self._library_all if clicks(row) > 0]
        return sorted(rows, key=lambda row: (-clicks(row), row.get("title", "").casefold()))[:5]

    @Property("QVariantList", notify=discoveryChanged)
    def topClicks(self):
        return [{"title": title, "clicks": clicks} for _key, title, clicks in self._clicks_stats.top(3)]

    @Property("QVariantMap", notify=discoveryChanged)
    def statistics(self):
        songs = self._clicks_stats.listening_rows()
        metadata = {}
        for row in list(self._online_tracks.values()) + self._library_all:
            for key in self._row_keys(row):
                metadata[key] = row
        for row in songs:
            meta = metadata.get(row["key"], {})
            for field in ("title", "artist", "thumbnail"):
                if meta.get(field): row[field] = meta[field]
            if not row.get("thumbnail") and row["key"].startswith("yt:"):
                row["thumbnail"] = "https://i.ytimg.com/vi/" + row["key"][3:] + "/mqdefault.jpg"
        playlists = self._clicks_stats.listening_rows(playlists=True)
        covers = {row["name"]: row["thumbnail"] for row in self.playlists}
        for row in playlists:
            if covers.get(row["title"]): row["thumbnail"] = covers[row["title"]]
        return {"songs": songs, "playlists": playlists, "ms": sum(row.get("ms", 0) for row in songs), "plays": self._clicks_stats.total()}

    def _sample_listening(self):
        import time
        now = time.monotonic()
        current = dict(self._current)
        playing = self._player.playbackState() == QMediaPlayer.PlayingState
        key = song_key(current.get("title", ""), current.get("id") or self._path_ids.get(current.get("path", ""), ""))
        position = self._player.position()
        previous = self._listening_sample
        playlist = self._library_playlist
        if playlist:
            by_path = {row.get("path"): row for row in self._library_all}
            members = {self._playlist_entry_key(entry, by_path) for entry in self._playlists.get(playlist, [])}
            if not self._row_keys(current).intersection(members): playlist = ""
        if previous and previous[0] == key:
            playlist = previous[5]
        if playing and current and not self._listening_started:
            self._clicks_stats.record_listening(key, current.get("title", ""), current.get("artist", ""), current.get("thumbnail", ""), start=True, playlist=playlist)
            self._listening_started = True
        if previous and previous[0] == key and previous[3]:
            wall = (now - previous[1]) * 1000
            delta = position - previous[2]
            if delta > 0 and wall > 0 and abs(delta - wall) < 1200:
                self._clicks_stats.record_listening(key, current.get("title", ""), current.get("artist", ""), current.get("thumbnail", ""), millis=min(delta, wall), playlist=playlist)
                if now - self._last_listening_save >= 5 or not playing:
                    self._settings.setValue("clickStats", json.dumps(self._clicks_stats.export(), ensure_ascii=False, separators=(",", ":")))
                    self._last_listening_save = now
                    self.discoveryChanged.emit()
                    self._schedule_cloud_save()
        self._listening_sample = (key, now, position, playing, current, playlist) if current else None

    @Property("QVariantList", notify=discoveryChanged)
    def topCollections(self):
        result = []
        grouped = {}
        roots = self._library_roots()
        for row in self._library_all:
            name = self._folder_of(row.get("path", ""), roots) if row.get("fromFolder") else None
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
    def queue(self): return self._online_queue if self._online_queue_active() else self._queue

    @Property(int, notify=playerChanged)
    def queueIndex(self): return self._online_index if self._online_queue_active() else self._queue_index

    @Property(int, notify=playerChanged)
    def repeatMode(self): return self._repeat_mode

    @Property(bool, notify=playerChanged)
    def shuffleOn(self): return self._shuffle

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
        previous = self._settings.value("playlistsData", "{}", str)
        try:
            before = json.loads(previous or "{}")
            if before and before != self._playlists:
                backups = json.loads(self._settings.value("playlistBackups", "[]", str) or "[]")
                backups.append({"at": int(time.time() * 1000), "playlists": before,
                                "onlineTracks": json.loads(self._settings.value("onlineTracksData", "{}", str) or "{}")})
                self._settings.setValue("playlistBackups", json.dumps(backups[-10:]))
        except (TypeError, ValueError, AttributeError):
            pass
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
            "title": item.get("title", ""), "channel": item.get("artist") or item.get("channel") or item.get("subtitle") or "YouTube",
            "duration": item.get("duration", 0), "thumbnail": item.get("thumbnail", ""), "url": item.get("url", "")}}))

    def _unify_playlists(self):
        """Une cada canción con su ID de YouTube: sin repetidos aunque exista el archivo y la entrada online."""
        rows = self._library_all
        self._path_ids = identity.match_local_ids(rows, self._online_tracks, known=self._linked_ids)
        unified, changed = identity.canonicalize(self._playlists, self._path_ids, self._online_tracks)
        if changed:
            self._playlists = unified
            self._favorites = set(unified.get("Favoritos", []))
            self._save_playlists()
            self._settings.setValue("favorites", list(self._favorites))
            self._schedule_cloud_save()
        self._library_all = [dict(row, favorite=self._is_fav(row)) for row in rows]

    def _local_row_for(self, video_id, title=""):
        """Archivo local de la biblioteca que corresponde a una canción de YouTube, o None."""
        if video_id:
            for path, linked in self._path_ids.items():
                if linked == video_id:
                    row = next((r for r in self._library_all if r.get("path") == path), None)
                    return row or {"path": path}
        norm_title = "".join(c for c in title.lower() if c.isalnum())
        for row in self._library_all:
            path = row.get("path", "")
            if video_id and video_id in path: return row
            row_title = "".join(c for c in row.get("title", "").lower() if c.isalnum())
            if norm_title and len(norm_title) > 3 and (norm_title in row_title or row_title in norm_title): return row
        return None

    def _is_video_downloaded(self, video_id, title=""):
        return self._local_row_for(video_id, title) is not None

    def _mark_local(self, item, video_id, title=""):
        """Marca de origen de una canción online: descargada por MusicBox o enlazada desde una carpeta."""
        row = self._local_row_for(video_id, title)
        item["downloaded"] = row is not None
        item["fromFolder"] = bool(row and row.get("fromFolder"))
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
        self._show_notice(i18n.tr("Agregada a «{name}»" if added else "Ya estaba en «{name}»", name=playlist_name), kind="info")

    @Slot(str, "QVariantList", bool, str, result=int)
    def linkLocalSongs(self, playlist_name, items, add_folder, folder):
        """Une archivos locales con su canción de YouTube y los agrega a una playlist. Devuelve cuántos agregó."""
        name = playlist_name.strip()
        if not name or not items:
            return 0
        playlist = self._playlists.setdefault(name, [])
        added = 0
        for item in items:
            video_id = identity.bare_id(item.get("id")) or str(item.get("id", ""))
            path = str(item.get("path", ""))
            if not video_id or not path:
                continue
            self._linked_ids[path] = video_id
            self._remember_online(dict(item, id=video_id))
            key = identity.entry_for(video_id)
            if key not in playlist:
                playlist.append(key)
                added += 1
            if name == "Favoritos":
                self._favorites.add(key)
        self._settings.setValue("linkedLocalIds", json.dumps(self._linked_ids))
        if name == "Favoritos":
            self._settings.setValue("favorites", list(self._favorites))
        self._save_playlists()
        self.playlistsChanged.emit()
        self._schedule_cloud_save()
        self._show_notice(i18n.tr("Se agregaron {n} canciones a «{name}»", n=added, name=name), kind="info")
        if add_folder and folder:
            self._save_library_roots(self._library_roots() + [Path(folder)])  # vuelve a leer la biblioteca
        else:
            self.refresh()
        return added

    @Slot(str, result=bool)
    def isInLibrary(self, folder):
        """¿La carpeta ya está dentro de las que MusicBox lee?"""
        try:
            target = Path(folder).resolve()
            return any(target == root.resolve() or root.resolve() in target.parents for root in self._library_roots())
        except OSError:
            return False

    @Slot("QVariantMap", result=bool)
    def deleteTrack(self, track):
        """Elimina el archivo (a la Papelera de reciclaje) y la canción de todas las playlists."""
        path = str((track or {}).get("path", ""))
        if not path or not Path(path).is_file():
            return False
        if not QFile.moveToTrash(path):
            self._show_notice(i18n.tr("No se pudo eliminar el archivo. Comprueba que no esté en uso."))
            return False
        self._forget_track(path)
        self._show_notice(i18n.tr("Canción eliminada"), kind="info")
        return True

    def _forget_track(self, path):
        """Quita de la app todo rastro de un archivo que ya no existe."""
        video_id = self._path_ids.get(path, "")
        keys = {path} | ({identity.entry_for(video_id)} if video_id else set())
        if self._current.get("path") == path:
            self._player.stop()
            self._current = {}
        self._queue = [row for row in self._queue if row.get("path") != path]
        self._queue_index = min(max(self._queue_index, 0), len(self._queue) - 1) if self._queue else -1
        self._recent_paths = [entry for entry in self._recent_paths if entry != path]
        self._settings.setValue("recentPlayed", self._recent_paths)
        self._linked_ids.pop(path, None)
        self._settings.setValue("linkedLocalIds", json.dumps(self._linked_ids))
        for name in list(self._playlists):
            self._playlists[name] = [key for key in self._playlists[name] if key not in keys]
        self._favorites -= keys
        for key in keys:
            self._online_favorites_meta.pop(key, None)
        self._settings.setValue("favorites", list(self._favorites))
        self._settings.setValue("onlineFavoritesMeta", json.dumps(self._online_favorites_meta))
        self._save_playlists()
        self.playlistsChanged.emit()
        self._schedule_cloud_save()
        self.refresh()

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
        if self._library_playlist != value.strip():
            self._library_artist = ""
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
            self._sync_base_section = deepcopy(state.get("playlists") or {})
            if self._settings.value("lastFirebaseUid", "", str) == uid:
                try:
                    saved_base = json.loads(self._settings.value("playlistSyncBase/" + uid, "null", str) or "null")
                    if isinstance(saved_base, dict) and isinstance(state.get("playlists"), dict):
                        state = dict(state, playlists=playlist_sync.merge_playlists(
                            saved_base, self._cloud_state().get("playlists"), state["playlists"]))
                except (TypeError, ValueError):
                    pass
            self._settings.setValue("playlistSyncBase/" + uid, json.dumps(self._sync_base_section))
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
        self._cloud_sync_generation += 1
        generation = self._cloud_sync_generation
        uid = self._user.get("uid")
        state = deepcopy(self._cloud_state())
        baseline = deepcopy(self._sync_base_section)

        def worker():
            with self._cloud_sync_lock:
                if generation != self._cloud_sync_generation or self._user.get("uid") != uid:
                    return
                try:
                    def update(remote):
                        if self._user.get("uid") != uid:
                            raise RuntimeError("La sesión cambió durante la sincronización")
                        merged = dict(remote)
                        for key in ("schema", "darkMode", "library", "recentPlayed", "recentQueries"):
                            merged[key] = state[key]
                        merged["playlists"] = playlist_sync.merge_playlists(
                            baseline, state.get("playlists"), remote.get("playlists"))
                        merged["favorites"] = merged["playlists"].get("playlists", {}).get("Favoritos", [])
                        self._clicks_stats.merge(remote.get("clicks"))
                        merged["clicks"] = self._clicks_stats.export()
                        devices = dict(remote.get("devices") or {})
                        devices[self._device_id] = state["devices"][self._device_id]
                        merged["devices"] = devices
                        playlist_sync.append_audit(merged, remote.get("playlists"), merged["playlists"],
                                                   self._device_id, "Windows", int(time.time() * 1000))
                        return merged

                    synced = self._cloud.update_state(update)
                    self._remotePlaylists.emit({"syncedPlaylists": synced["playlists"],
                                               "localSnapshot": state["playlists"], "uid": uid})
                except Exception:
                    # Se conserva la copia local. La siguiente interacción vuelve a intentar sincronizar.
                    pass
        threading.Thread(target=worker, daemon=True).start()

    @Slot(object)
    def _apply_remote_playlists(self, data):
        if not isinstance(data, dict):
            return
        incoming = data.get("syncedPlaylists", data)
        if "uid" in data and data["uid"] != self._user.get("uid"):
            return
        current = self._cloud_state().get("playlists", {})
        baseline = data.get("localSnapshot", self._sync_base_section)
        self._sync_base_section = deepcopy(incoming)
        if self._user.get("uid"):
            self._settings.setValue("playlistSyncBase/" + self._user["uid"], json.dumps(incoming))
        data = playlist_sync.merge_playlists(baseline, current, incoming)
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
        self._save_clicks()
        self._unify_playlists()
        self.playlistsChanged.emit()
        self._apply_library_view()
        self.discoveryChanged.emit()
        self.playerChanged.emit()
        self._reconcile_auto_downloads()
        if data != incoming:
            self._schedule_cloud_save()

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

    @Property(str, notify=updateChanged)
    def updateCheck(self):
        """Estado de la comprobación manual del perfil: idle, checking, uptodate, available o failed."""
        if self._update_state in ("available", "downloading", "installing", "error"):
            return "available"
        return self._update_check

    @Slot()
    def checkUpdatesNow(self):
        """Comprobación manual desde el perfil; muestra siempre el resultado y no respeta «omitir»."""
        if self._update_state in ("downloading", "installing") or self._update_check == "checking":
            return
        self._update_check = "checking"
        self.updateChanged.emit()

        def worker():
            started = time.monotonic()
            try:
                info = updater.check_for_update(__version__)
                event = {"event": "checked", "result": "available", "info": info} if info else {"event": "checked", "result": "uptodate"}
            except Exception:
                event = {"event": "checked", "result": "failed"}
            # Un mínimo de tiempo para que el estado «buscando» se alcance a ver.
            time.sleep(max(0.0, 0.7 - (time.monotonic() - started)))
            self._updateEvent.emit(event)
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
        self._update_check = "idle"
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
        if kind == "checked":
            result = event.get("result")
            if result == "available" and self._update_state in ("idle", "error", "available"):
                self._update = event["info"]
                self._update_state = "available"
                self._update_check = "available"
            else:
                self._update_check = result if result in ("uptodate", "failed") else "idle"
        elif kind == "available":
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
            if item["path"] in self._linked_ids:
                item["fromFolder"] = True  # importada desde una carpeta del usuario
        self._library_scanned = True
        self._unify_playlists()
        files = self._library_all
        self._migrate_legacy_clicks(files)
        grouped = {}
        roots = self._library_roots()
        for item in files:
            if not item.get("fromFolder"):
                continue  # descargadas por MusicBox: no son una carpeta del usuario
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
                self._mark_local(item, vid, item.get("title", ""))
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
                    self._mark_local(meta, vid, meta.get("title", ""))
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
        artists = {}
        for row in rows if self._library_playlist else []:
            artist = self._track_artist(row)
            if artist:
                artists.setdefault(artist.casefold(), artist)
        self._library_artists = sorted(artists.values(), key=str.casefold)
        artist = getattr(self, "_library_artist", "").casefold()
        if not self._library_playlist:
            self._library_artist = ""
        elif artist:
            rows = [row for row in rows if self._track_artist(row).casefold() == artist]
        # El escaneo ya entrega el orden más reciente primero.
        query = self._library_query.casefold().strip()
        if query:
            rows = [row for row in rows if query in " ".join((row.get("title", ""), row.get("artist", ""),
                                                               row.get("album", ""))).casefold()]
        self._library = rows
        self.libraryChanged.emit()
        self.artistPhotosChanged.emit()

    @Slot(str)
    def setLibraryQuery(self, value):
        self._library_query = " ".join(value.split())
        self._apply_library_view()

    @Slot(str)
    def setLibraryFilter(self, value):
        self._library_filter = value if value in {"songs", "playlists", "folders"} else "songs"
        if value in {"playlists", "folders"}:
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
            self._mark_local(item, vid, item.get("title", ""))
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
        self._queue_artist = ""
        self._queue = list(self._library)
        self._queue_index = next((i for i, row in enumerate(self._queue) if row.get("path") == path), -1)
        if self._shuffle:
            self._shuffle_queue()
        self._start_local_track(item)

    @Slot("QVariantMap")
    def playLibraryTrack(self, track):
        """La cola filtrada es una copia y admite canciones locales y online."""
        if not self._library_artist:
            self.playOnline(track) if track.get("web") else self.playTrack(track)
            return
        item = dict(track or {})
        key = item.get("path") or item.get("url")
        index = next((i for i, row in enumerate(self._library)
                      if (row.get("path") or row.get("url")) == key), -1)
        if index < 0:
            return
        self._reset_online_queue()
        self._queue_artist = self._library_artist
        self._queue, self._queue_index = list(self._library), index
        if self._shuffle:
            self._shuffle_queue(index)
        self._start_queue_track(item)

    def _start_queue_track(self, item):
        if item.get("path"):
            self._start_local_track(dict(item))
        else:
            self._play_online(dict(item))

    def _start_local_track(self, item):
        self._sample_listening()
        self._listening_sample = None
        self._listening_started = False
        self._reset_online_queue()
        self._stream_generation += 1
        path = item.get("path", "")
        self._current = item
        self._recorded_source = ""
        self._player.setSource(QUrl.fromLocalFile(path))
        self._player.play(); self.playerChanged.emit()

    def _playback_state_changed(self, state):
        if hasattr(self, "_listening_timer"):
            self._sample_listening()
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
            elif not path and self._current.get("id") and self._current.get("url") != self._recorded_source:
                self._recorded_source = self._current.get("url", "")
                title = self._current.get("title", "")
                self._clicks_stats.record(song_key(title, self._current["id"]), title)
                self._save_clicks()
                self.discoveryChanged.emit()
                self._schedule_cloud_save()
        self.playerChanged.emit()

    @Slot("QVariantMap")
    def playOnline(self, track):
        self.playOnlineList([track], 0)

    def _play_online(self, track):
        self._sample_listening()
        self._listening_sample = None
        self._listening_started = False
        item = dict(track or {})
        url = item.get("url", "")
        if not url: return
        self._stream_generation += 1
        item["_stream_generation"] = self._stream_generation
        self._online_waiting = False
        self._player.stop()
        self._current = item
        self._recorded_source = ""
        self._current.setdefault("artist", item.get("subtitle", "YouTube"))
        self._current.setdefault("album", "Streaming")
        self._cache_fallback_tried.discard(url)
        self._maybe_autoplay(item)
        self._set_status(i18n.tr("Preparando reproducción…"))
        self.playerChanged.emit()
        def resolve():
            stream, error = "", ""
            try:
                info = youtube_streams.resolve_stream(url, self._extract_info)
                stream = info.get("url", "")
                item.update(title=info.get("track") or info.get("title") or item.get("title"),
                                     artist=info.get("artist") or info.get("uploader") or item.get("artist"),
                                     album=info.get("album") or "Streaming")
            except Exception as exc:
                error = str(exc).splitlines()[-1]
            self._streamReady.emit(item, stream or error)
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
        if track.get("_stream_generation") != self._stream_generation:
            return
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
    def seek(self, milliseconds):
        self._sample_listening()
        self._listening_sample = None
        self._player.setPosition(max(0, milliseconds))

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
        if self._advance_online(1 if direction >= 0 else -1): return
        if not self._queue: return
        self._queue_index = (self._queue_index + (1 if direction >= 0 else -1)) % len(self._queue)
        self._start_queue_track(self._queue[self._queue_index])

    @Slot()
    def shuffleVisible(self):
        if not self._library: return
        self._reset_online_queue()
        self._queue_artist = self._library_artist
        self._queue = list(self._library)
        self._queue_original = list(self._queue)
        random.shuffle(self._queue)
        self._queue_index = 0
        self._start_queue_track(self._queue[0])

    def _shuffle_queue(self, current_index=None):
        """Mezcla la cola dejando la canción actual primera; recuerda el orden para poder volver."""
        self._queue_original = list(self._queue)
        current = self._queue_current_index() if current_index is None else current_index
        first = [self._queue[current]] if 0 <= current < len(self._queue) else []
        rest = [row for index, row in enumerate(self._queue) if index != current]
        random.shuffle(rest)
        self._queue = first + rest
        self._queue_index = 0 if first else -1

    def _restore_queue_order(self):
        paths = {row.get("path") or row.get("url") for row in self._queue}
        ordered = [row for row in self._queue_original if (row.get("path") or row.get("url")) in paths]
        known = {row.get("path") or row.get("url") for row in ordered}
        self._queue = ordered + [row for row in self._queue if (row.get("path") or row.get("url")) not in known]
        self._queue_original = []
        self._queue_index = self._queue_current_index() if self._queue else -1

    @Slot()
    def toggleShuffle(self):
        self._shuffle = not self._shuffle
        self._settings.setValue("shuffleOn", "true" if self._shuffle else "false")
        if self._online_queue_active():
            current_id = self._current.get("id")
            if self._shuffle:
                self._online_original = list(self._online_queue)
                current = self._online_queue[self._online_index]
                rest = self._online_queue[:self._online_index] + self._online_queue[self._online_index + 1:]
                random.shuffle(rest)
                self._online_queue = [current] + rest
            else:
                by_id = {row.get("id"): row for row in self._online_queue}
                ordered = [by_id.pop(row.get("id")) for row in self._online_original if row.get("id") in by_id]
                self._online_queue = ordered + list(by_id.values())
                self._online_original = []
            self._online_index = next(i for i, row in enumerate(self._online_queue) if row.get("id") == current_id)
        elif self._queue:
            self._shuffle_queue() if self._shuffle else self._restore_queue_order()
        self.playerChanged.emit()

    @Slot()
    def cycleRepeat(self):
        self._repeat_mode = (self._repeat_mode + 1) % 3
        self._settings.setValue("repeatMode", self._repeat_mode)
        self.playerChanged.emit()

    def _queue_current_index(self):
        key = self._current.get("path") or self._current.get("url")
        return next((i for i, row in enumerate(self._queue)
                     if (row.get("path") or row.get("url")) == key), self._queue_index)

    @Slot(int, int)
    def moveQueueItem(self, source, target):
        if self._online_queue_active():
            if 0 <= source < len(self._online_queue) and 0 <= target < len(self._online_queue):
                current = self._online_queue[self._online_index]
                self._online_queue.insert(target, self._online_queue.pop(source))
                self._online_index = self._online_queue.index(current)
                self.playerChanged.emit()
            return
        count = len(self._queue)
        if not (0 <= source < count and 0 <= target < count) or source == target:
            return
        self._queue.insert(target, self._queue.pop(source))
        self._queue_index = self._queue_current_index()
        self.playerChanged.emit()

    @Slot(int)
    def removeQueueItem(self, index):
        if self._online_queue_active():
            if 0 <= index < len(self._online_queue) and index != self._online_index:
                current = self._online_queue[self._online_index]
                del self._online_queue[index]
                self._online_index = self._online_queue.index(current)
                self.playerChanged.emit()
            return
        if not (0 <= index < len(self._queue)) or index == self._queue_index:
            return
        del self._queue[index]
        self._queue_index = self._queue_current_index()
        self.playerChanged.emit()

    @Slot()
    def clearQueue(self):
        if self._online_queue_active():
            current = self._online_queue[self._online_index]
            self._reset_online_queue()
            self._online_queue, self._online_index = [current], 0
            self._autoplay_tried = current.get("id", "")
            self.playerChanged.emit()
            return
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

    @Slot(int)
    def playQueueIndex(self, index):
        if self._online_queue_active():
            if 0 <= index < len(self._online_queue):
                self._online_index = index
                self._play_online(self._online_queue[index])
            return
        if 0 <= index < len(self._queue):
            self._queue_index = index
            self._start_queue_track(self._queue[index])

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
        elif self._advance_online(1):
            return
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
    smoke_directory = None
    if "--smoke-test" in sys.argv:
        import tempfile
        smoke_directory = tempfile.TemporaryDirectory(prefix="musicbox-smoke-")
        smoke_settings = QSettings(str(Path(smoke_directory.name) / "settings.ini"), QSettings.IniFormat)
        bridge = MusicBoxBridge(settings=smoke_settings, load_startup=False)
    else:
        bridge = MusicBoxBridge()
    app.aboutToQuit.connect(bridge.flushListening)
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
    if smoke_directory is not None:
        QTimer.singleShot(1200, app.quit)
    result = app.exec()
    if smoke_directory is not None:
        bridge._listening_timer.stop()
        bridge._device_timer.stop()
        engine.deleteLater()
        from PySide6.QtCore import QCoreApplication, QEvent
        QCoreApplication.sendPostedEvents(None, QEvent.DeferredDelete)
        bridge._settings.sync()
        smoke_directory.cleanup()
    return result


if __name__ == "__main__":
    raise SystemExit(main())
