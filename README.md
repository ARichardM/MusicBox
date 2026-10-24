<p align="center">
  <img src="docs/images/musicbox-banner.svg" width="860" alt="MusicBox — tu música, en todos lados">
</p>

<p align="center">
  <a href="https://github.com/ARichardM/MusicBox/releases/latest"><img alt="Última versión" src="https://img.shields.io/github/v/release/ARichardM/MusicBox?style=for-the-badge&color=0A84FF"></a>
  <a href="https://github.com/ARichardM/MusicBox/actions/workflows/android-ci.yml"><img alt="Android CI" src="https://img.shields.io/github/actions/workflow/status/ARichardM/MusicBox/android-ci.yml?style=for-the-badge&label=Android"></a>
  <img alt="Android 7+" src="https://img.shields.io/badge/Android-7%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Windows" src="https://img.shields.io/badge/Windows-10%2F11-0078D4?style=for-the-badge&logo=windows11&logoColor=white">
</p>

<p align="center">
  <strong>Busca, reproduce, organiza y descarga tu música desde Android o Windows.</strong><br>
  Biblioteca sincronizada, playlists, favoritos, cola y una experiencia diseñada para tema claro y oscuro.
</p>

<p align="center">
  <a href="https://github.com/ARichardM/MusicBox/releases/latest/download/MusicBox-Android.apk"><strong>Descargar APK</strong></a>
  · <a href="https://github.com/ARichardM/MusicBox/releases/latest/download/MusicBox-Windows-Setup.exe"><strong>Descargar para Windows</strong></a>
  · <a href="https://github.com/ARichardM/MusicBox/releases">Ver versiones</a>
  · <a href="docs/GUIA_USO.md">Guía de uso</a>
</p>

> [!NOTE]
> El APK se distribuye mediante **GitHub Releases** porque supera el límite de tamaño de los archivos normales del repositorio. Android puede pedir permiso para instalar aplicaciones desde el navegador o gestor de archivos.

## Qué ofrece MusicBox

| Experiencia | Funciones |
| --- | --- |
| **Reproductor** | Audio y video, cola editable, repetición, reproducción aleatoria y cambio de canción mediante gestos. |
| **Biblioteca** | Canciones locales, carpetas y playlists con portadas, búsqueda y estado de reproducción. |
| **Playlists** | Nombre, descripción, menú de configuración y descarga automática de nuevas canciones. |
| **Favoritos** | Una playlist especial y sincronizada; el corazón agrega o elimina canciones de ella. |
| **Descargas** | MP3 o MP4, progreso, historial y archivos que permanecen en cada dispositivo. |
| **Cuenta** | Inicio con Google, preferencias sincronizadas y listado de dispositivos asociados. |
| **Diseño** | Tema claro/oscuro, dialogs adaptados, skeleton shimmer y diseño responsive. |

## Aplicaciones

### Android

- Android 7.0 o posterior (`minSdk 24`).
- Arquitecturas ARM y ARM64.
- APK autocontenido con yt-dlp y FFmpeg.
- Reproducción en segundo plano con MediaSession.
- Descargas guardadas localmente en el dispositivo.

Descarga la versión preparada desde [Releases](https://github.com/ARichardM/MusicBox/releases/latest). Para actualizar sin perder datos, instala una versión firmada con la misma clave que la anterior.

### Windows

La interfaz actual de escritorio está construida con **Python + PySide6/QML**. También se conservan herramientas históricas de importación, OCR y revisión de listas desarrolladas con Tkinter.

Para Windows 10/11 de 64 bits descarga `MusicBox-Windows-Setup.exe` desde [Releases](https://github.com/ARichardM/MusicBox/releases/latest). El instalador incluye MusicBox y FFmpeg; no exige tener Python instalado. Junto al instalador se publica su suma SHA-256 para comprobar su integridad.

```powershell
git clone https://github.com/ARichardM/MusicBox.git
cd MusicBox
python -m venv .venv
.\.venv\Scripts\pip.exe install -r apps\desktop\requirements.txt
.\.venv\Scripts\python.exe apps\desktop\musicbox_desktop.py
```

Para usar inicio de sesión en una compilación propia debes proporcionar tus archivos locales de Firebase/OAuth. No se incluyen credenciales privadas en Git.

## Compilar Android

Necesitas JDK 17 y un archivo válido `apps/android/app/google-services.json` de tu proyecto Firebase.

```powershell
cd apps/android
.\gradlew.bat testPreviewUnitTest assembleDebug
```

El APK queda en `apps/android/app/build/outputs/apk/debug/app-debug.apk`. La variante `preview` se instala como **MusicBox pruebas** y mantiene sus datos separados:

```powershell
.\gradlew.bat assemblePreview
```

Consulta la [documentación específica de Android](apps/android/README.md) para las pruebas de gestos y la arquitectura del reproductor.

## Publicar una versión

Los workflows de publicación compilan y adjuntan automáticamente `MusicBox-Android.apk` y `MusicBox-Windows-Setup.exe` al publicar una etiqueta `v*`.

Configura estos secretos en **Settings → Secrets and variables → Actions**:

| Secreto | Contenido |
| --- | --- |
| `GOOGLE_SERVICES_JSON_BASE64` | `google-services.json` codificado en Base64. |
| `ANDROID_KEYSTORE_BASE64` | Keystore de publicación codificado en Base64. |
| `KEYSTORE_PASSWORD` | Contraseña del keystore. |
| `KEY_ALIAS` | Alias de la clave. |
| `KEY_PASSWORD` | Contraseña de la clave. |
| `DESKTOP_OAUTH_CLIENT_JSON_BASE64` | Cliente OAuth de escritorio codificado en Base64. |

Después crea una etiqueta como `v2.2.1`. GitHub generará una Release con ambos instaladores descargables desde los botones superiores.

## Arquitectura

```text
apps/android/            Aplicación Android nativa (Java)
apps/desktop/            Aplicación Windows, interfaz QML y recursos
tests/desktop/           Pruebas automatizadas de escritorio
scripts/windows/         Herramientas de instalación y mantenimiento
legacy/                  Prototipos históricos conservados como referencia
docs/                    Guías y documentación técnica
```

Firebase sincroniza metadatos ligeros —playlists, favoritos, preferencias y dispositivos—. Los archivos multimedia permanecen en el equipo donde fueron descargados.

## Privacidad y uso responsable

- Cookies, tokens, credenciales, historiales y archivos multimedia están excluidos de Git.
- Las descargas permanecen localmente; no se suben a Firebase.
- MusicBox no es una aplicación oficial de YouTube ni de YouTube Music.
- Utiliza la aplicación únicamente con contenido que tengas derecho a descargar.

## Estado y contribuciones

MusicBox está en desarrollo activo. Revisa [CHANGELOG.md](CHANGELOG.md), propón mejoras mediante [Issues](https://github.com/ARichardM/MusicBox/issues) y consulta [CONTRIBUTING.md](CONTRIBUTING.md) antes de abrir un Pull Request. Los problemas sensibles se reportan siguiendo [SECURITY.md](SECURITY.md).

> El repositorio aún no declara una licencia. El código conserva los derechos de su autor hasta que se elija una licencia explícita.
