"""Idiomas de la interfaz de escritorio: español (texto base) e inglés."""

from __future__ import annotations

from PySide6.QtCore import QLocale, QTranslator

LANGUAGES = ("auto", "es", "en")

EN = {
    # --- Interfaz QML ---
    "  ·  tienes la ": "  ·  you have ",
    " carpetas": " folders",
    " canciones": " songs",
    " canciones)": " songs)",
    " playlists": " playlists",
    " · Este dispositivo": " · This device",
    "+ Crear nueva playlist": "+ Create new playlist",
    "+ Opciones avanzadas": "+ Advanced options",
    "− Opciones avanzadas": "− Advanced options",
    "Abrir carpeta de destino": "Open destination folder",
    "Activar auto-descarga para esta sección": "Turn on auto-download for this section",
    "Activar sonido": "Unmute",
    "Actividad": "Activity",
    "Actualizar ahora": "Update now",
    "Actualizar biblioteca": "Refresh library",
    "Agrega canciones a esta playlist desde el menú de 3 puntos": "Add songs to this playlist from the three-dot menu",
    "Agregar a favoritas": "Add to favorites",
    "Agregar a playlist": "Add to playlist",
    "Aleatorio": "Shuffle",
    "Aparecerán aquí al terminar": "They will appear here when finished",
    "Aquí agregaremos nuevas opciones próximamente": "New options will be added here soon",
    "Aquí verás los archivos terminados": "Finished files will show up here",
    "Artista desconocido": "Unknown artist",
    "Auto-descarga": "Auto-download",
    "Auto-descarga activada: las nuevas canciones se descargarán al agregarse":
        "Auto-download is on: new songs are downloaded as soon as they are added",
    "Aún no hay archivos": "No files yet",
    "Busca algo para escuchar": "Search for something to listen to",
    "Buscar": "Search",
    "Buscar en tu música": "Search your music",
    "Búsquedas recientes": "Recent searches",
    "CARPETAS": "FOLDERS",
    "Calidad": "Quality",
    "Cambiar": "Change",
    "Cancelar": "Cancel",
    "Carpetas": "Folders",
    "No hay carpetas todavía. Agrega una carpeta de música en Configuración.": "No folders yet. Add a music folder in Settings.",
    "Canciones": "Songs",
    "Canción, artista o enlace": "Song, artist or link",
    "Cerrar": "Close",
    "Cerrar sesión": "Sign out",
    "Cola": "Queue",
    "Completadas": "Completed",
    "Conectando…": "Connecting…",
    "Configuración": "Settings",
    "Configurar playlist": "Playlist settings",
    "Continuar con Google": "Continue with Google",
    "Contraer panel": "Collapse panel",
    "Crea una con el botón + o agrega canciones desde cualquier vista":
        "Create one with the + button or add songs from any view",
    "Crear": "Create",
    "Cuenta de Google": "Google account",
    "DISPOSITIVOS CONECTADOS": "CONNECTED DEVICES",
    "Desactivar": "Turn off",
    "Descargando…": "Downloading…",
    "Descargar": "Download",
    "Descargar canciones nuevas": "Download new songs",
    "Descargar canción": "Download song",
    "Descargas": "Downloads",
    "Destino": "Destination",
    "Dispositivo": "Device",
    "ENLACES": "LINKS",
    "Elegir": "Choose",
    "Eliminar": "Delete",
    "Eliminar playlist": "Delete playlist",
    "Escritorio": "Desktop",
    "Escuchadas recientemente": "Recently played",
    "Expandir panel": "Expand panel",
    "Formato": "Format",
    "Guardar": "Save",
    "Historial": "History",
    "Inicia sesión para sincronizar tu biblioteca, favoritos, playlists y preferencias.":
        "Sign in to sync your library, favorites, playlists and preferences.",
    "Instalando actualización…": "Installing update…",
    "Ir a la canción actual": "Go to current song",
    "La cola está vacía": "The queue is empty",
    "Las descargas permanecen únicamente en este dispositivo.": "Downloads stay only on this device.",
    "Limpiar": "Clear",
    "Mejor disponible": "Best available",
    "Mejoras de rendimiento y correcciones.": "Performance improvements and fixes.",
    "Mi música": "My music",
    "Mostrar en carpeta": "Show in folder",
    "MusicBox se reiniciará solo": "MusicBox will restart on its own",
    "MÁS CONFIGURACIONES": "MORE SETTINGS",
    "Más escuchadas en tu biblioteca": "Most played in your library",
    "Más tarde": "Later",
    "NOVEDADES": "WHAT'S NEW",
    "No hay canciones aquí": "No songs here",
    "No hay playlists todavía": "No playlists yet",
    "Nombre": "Name",
    "Nombre de la playlist": "Playlist name",
    "Nueva descarga": "New download",
    "Nueva playlist": "New playlist",
    "Nueva versión disponible": "New version available",
    "Omitir": "Skip",
    "Pausar": "Pause",
    "Pega uno o más enlaces, uno por línea": "Paste one or more links, one per line",
    "Pegar": "Paste",
    "Playlist vacía": "Empty playlist",
    "Prueba otra búsqueda o carpeta": "Try another search or folder",
    "Quitar de favoritas": "Remove from favorites",
    "REINTENTAR": "RETRY",
    "Rango: 1-20,25": "Range: 1-20,25",
    "Reintentar": "Retry",
    "Repetición desactivada": "Repeat off",
    "Repetir canción": "Repeat song",
    "Repetir cola": "Repeat queue",
    "Reproduce una canción o usa Aleatorio": "Play a song or use Shuffle",
    "Reproduciendo desde Mi música · ": "Playing from My music · ",
    "Reproducir": "Play",
    "Reproducir visible en aleatorio": "Shuffle visible songs",
    "Si desactivas la auto-descarga, las canciones nuevas no se descargarán al agregarse.\nLas ya descargadas se mantendrán.":
        "If you turn off auto-download, new songs won't be downloaded when added.\nAlready downloaded songs will be kept.",
    "Silenciar": "Mute",
    "Sin descargas todavía": "No downloads yet",
    "Sin enlaces todavía": "No links yet",
    "Tema claro": "Light theme",
    "Tema oscuro": "Dark theme",
    "Todavía no hay dispositivos registrados": "No devices registered yet",
    "Tu actividad aparecerá aquí": "Your activity will appear here",
    "Tu música, en todos lados": "Your music, everywhere",
    "Tus carpetas más escuchadas": "Your most played folders",
    "Un enlace por línea": "One link per line",
    "Ver todas": "See all",
    "Volver": "Back",
    "\"?\nLas canciones descargadas se mantendrán.": "\"?\nDownloaded songs will be kept.",
    "¿Desactivar auto-descarga?": "Turn off auto-download?",
    "¿Seguro que deseas cerrar sesión?\nTus descargas se mantendrán en este dispositivo.":
        "Are you sure you want to sign out?\nYour downloads will stay on this device.",
    "¿Seguro que deseas eliminar la playlist \"": "Are you sure you want to delete the playlist \"",
    "Idioma": "Language",
    "APARIENCIA": "APPEARANCE",
    "CARPETAS DE MÚSICA": "MUSIC FOLDERS",
    "Restablecer": "Reset",
    "Agregar carpeta": "Add folder",
    "Esta carpeta ya no existe": "This folder no longer exists",
    "Cada dispositivo tiene sus propias carpetas; no se sincronizan.": "Each device has its own folders; they are not synced.",
    "Desconectar": "Disconnect",
    "¿Desconectar este dispositivo?": "Disconnect this device?",
    "Se cerrará la sesión guardada en ": "The session saved on ",
    ". Tendrá que volver a iniciar sesión.": " will be signed out. It will need to sign in again.",
    "Este dispositivo": "This device",
    "Tu sesión se cerró desde otro dispositivo.": "Your session was signed out from another device.",
    "No se pudo desconectar el dispositivo. Revisa tu conexión.": "Couldn't disconnect the device. Check your connection.",
    "Automático": "Automatic",
    # --- Mensajes generados por Python ---
    "Todo listo": "All set",
    "Todo listo para descargar": "Ready to download",
    "Pega uno o más enlaces para comenzar.": "Paste one or more links to begin.",
    "Canción de YouTube": "YouTube song",
    "Sin actividad reciente": "No recent activity",
    "Este PC": "This PC",
    "No se pudo descargar la actualización.": "The update could not be downloaded.",
    "No se pudo reproducir esta canción. ": "This song could not be played. ",
    "Inténtalo de nuevo en unos segundos.": "Try again in a few seconds.",
    "Canción online": "Online song",
    "Buscando en YouTube…": "Searching YouTube…",
    "Sin título": "Untitled",
    "No se pudo completar la búsqueda": "The search could not be completed",
    "No se pudo completar la búsqueda. ": "The search could not be completed. ",
    "No se pudo abrir la carpeta": "The folder could not be opened",
    "No se encontró FFmpeg. Coloca ffmpeg.exe junto al programa o en PATH.":
        "FFmpeg was not found. Place ffmpeg.exe next to the program or on PATH.",
    "El archivo de cookies no existe.": "The cookies file does not exist.",
    "Revisa los datos": "Check your input",
    "Analizando enlaces…": "Analyzing links…",
    "Buscando los formatos disponibles.": "Looking for available formats.",
    "Consultando la información del enlace.": "Fetching the link information.",
    "Procesando archivo…": "Processing file…",
    "Listo: ": "Done: ",
    "Listo": "Done",
    "Descarga cancelada": "Download cancelled",
    "Descargas terminadas": "Downloads finished",
    "No se pudo completar la descarga. Revisa tu conexión e inténtalo otra vez.":
        "The download could not be completed. Check your connection and try again.",
    "Cancelando…": "Cancelling…",
    "Preparando reproducción…": "Preparing playback…",
    "No se pudo reproducir este resultado": "This result could not be played",
    "No se pudo reproducir este resultado.": "This result could not be played.",
    "Reproduciendo": "Playing",
    "Analizando enlace {i} de {n}…": "Analyzing link {i} of {n}…",
    "{n} resultados": "{n} results",
    "{n} completadas · {e} errores": "{n} completed · {e} errors",
    "{s} s restantes": "{s} s left",
    "{n} canción": "{n} song",
    "{n} canciones": "{n} songs",
    "Carpeta · {n} reproducciones": "Folder · {n} plays",
    "Destino: {t}\nFormato: {f} · {q}\n": "Destination: {t}\nFormat: {f} · {q}\n",
}

from i18n_downloads import EXTRA as _EXTRA
EN.update(_EXTRA)

_language = "es"


def resolve(setting: str) -> str:
    """Convierte 'auto' en el idioma del sistema; cualquier otro valor inválido cae a español."""
    if setting == "auto":
        return "es" if QLocale.system().language() == QLocale.Language.Spanish else "en"
    return setting if setting in ("es", "en") else "es"


def set_language(setting: str) -> str:
    global _language
    _language = resolve(setting)
    return _language


def language() -> str:
    return _language


def tr(text: str, **values) -> str:
    """Traduce un mensaje generado por Python y rellena sus marcadores."""
    result = EN.get(text, text) if _language == "en" else text
    return result.format(**values) if values else result


class Translator(QTranslator):
    """Traductor de Qt basado en el diccionario; el texto fuente de QML está en español."""

    def translate(self, context, source_text, disambiguation=None, n=-1):
        if _language == "en":
            return EN.get(source_text)
        return None

    def isEmpty(self):
        return False
