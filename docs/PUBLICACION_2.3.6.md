# Publicación 2.3.6-rc.2

Fecha: 29 de octubre de 2026. Versión preliminar para Android y Windows.

- Código publicado: `f5e7730024ae0ac6d7ce98399c5bf3ab37b74d92`.
- Etiqueta: `v2.3.6-rc.2`.
- Descargas: https://github.com/ARichardM/MusicBox/releases/tag/v2.3.6-rc.2
- [Compilación y pruebas de Windows](https://github.com/ARichardM/MusicBox/actions/runs/40979579262): aprobadas, incluida la ejecución del binario empaquetado.
- [Compilación, pruebas JVM y lint de Android](https://github.com/ARichardM/MusicBox/actions/runs/40979579330): aprobados; APK de publicación firmado.
- [Desktop CI](https://github.com/ARichardM/MusicBox/actions/runs/40979576540), [Android CI](https://github.com/ARichardM/MusicBox/actions/runs/40979576562) y [análisis de seguridad](https://github.com/ARichardM/MusicBox/actions/runs/40979576505): aprobados para el mismo commit.
- [Publicación automática](https://github.com/ARichardM/MusicBox/actions/runs/40980441848): aprobada como prerelease después de comprobar ambos paquetes.

El APK identifica `cl.arichard.descargador`, versión `2.3.6`, código `14`,
arquitecturas `arm64-v8a` y `armeabi-v7a`. Los tres paquetes y sus archivos de
suma están disponibles en GitHub.

| Archivo | SHA-256 |
| --- | --- |
| MusicBox-Android.apk | `844cb94f2e31f552145f1647365139e1e2a9123cf67ea67636c2d7c13124b65c` |
| MusicBox-Windows-Setup.exe | `63751826879e9e36a04ad498df08a0c226d437c91bc9c93810f560924baeb780` |
| MusicBox-Windows-Portable.zip | `0c6915efa87c87c1f161ca783053a0471525966da54e7186413d17e27644d31d` |

Esta publicación es preliminar y no sustituye a `v2.3.5` como última estable.
La suite completa de dispositivo Android conserva resultados pendientes
(avatar Zeta y cierres del emulador), y falta comprobar la desconexión de
audífonos Bluetooth físicos. El detalle y los casos aprobados se conservan en
[VERIFICACION_AUDIO_Y_COLA.md](VERIFICACION_AUDIO_Y_COLA.md).
No se declara aprobada esa batería ni se promociona esta versión a estable.
