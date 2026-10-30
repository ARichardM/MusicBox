# Publicación 2.3.7

Fecha: 29 de octubre de 2026. Versión estable para Android y Windows.

- Código publicado: `0f08b59941c0a6306358cd6af3a7f27f18459e8f`.
- Etiqueta: `v2.3.7`.
- Descargas: https://github.com/ARichardM/MusicBox/releases/tag/v2.3.7
- [Compilación y pruebas de Windows](https://github.com/ARichardM/MusicBox/actions/runs/40983111896): aprobadas, incluida la ejecución del binario empaquetado.
- [Compilación, pruebas JVM y lint de Android](https://github.com/ARichardM/MusicBox/actions/runs/40983111948): aprobados; APK de publicación firmado.
- GitHub publicó la versión sin marca preliminar y la estableció como `Latest` después de comprobar ambos paquetes.

Antes de etiquetar se ejecutaron 421 pruebas de escritorio, 202 pruebas JVM de
Android, lint, el APK `verification` en un Samsung físico y la compilación local
del ejecutable Windows. También se comprobó con `QMediaPlayer` real que una
canción activa se libera y puede moverse a la Papelera, y que localizar una
pista en la biblioteca real de 315 canciones no bloquea la interfaz.

| Archivo | SHA-256 |
| --- | --- |
| MusicBox-Android.apk | `63a5df24be80bf4fe145e39081ae4217febcc2ce08b3f77e8a5d92542e19d017` |
| MusicBox-Windows-Setup.exe | `c8cfa3dd7e00dc6d41c11bece737ac155f727e01b9b54073f6fc7abbc0fe94bc` |
| MusicBox-Windows-Portable.zip | `007583b36e61e486a77b12a6ec1d2cf8447a445ec91602718c97ed06a19b4c72` |

Esta publicación sustituye a `v2.3.6` como última versión estable.
