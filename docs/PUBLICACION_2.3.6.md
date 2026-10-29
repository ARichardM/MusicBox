# Publicación 2.3.6-rc.1

Fecha: 29 de octubre de 2026. Versión preliminar para Android y Windows.

- Código publicado: `c3b653964db5a2945b2a8d158c25d4d3b17be68f`.
- Etiqueta: `v2.3.6-rc.1`.
- Descargas: https://github.com/ARichardM/MusicBox/releases/tag/v2.3.6-rc.1
- [Compilación y pruebas de Windows](https://github.com/ARichardM/MusicBox/actions/runs/40954701096): aprobadas, incluida la ejecución del binario empaquetado.
- [Compilación, pruebas JVM y lint de Android](https://github.com/ARichardM/MusicBox/actions/runs/40954701207): aprobados; APK de publicación firmado.
- Desktop CI, Android CI y análisis de seguridad del mismo commit: aprobados.

El APK identifica `cl.arichard.descargador`, versión `2.3.6`, código `14`,
arquitecturas `arm64-v8a` y `armeabi-v7a`. La firma se verificó con `apksigner`;
SHA-256 del certificado:
`1df77cf2b585137d1643af21e0aceab642fa1d59ba454bb49750aa9a952f2987`.

Los SHA-256 de los tres paquetes registrados por GitHub coinciden con los
archivos de suma generados por las compilaciones. El APK también se descargó
y verificó localmente. La descarga local de Windows sufrió interrupciones;
la ejecución del binario empaquetado fue verificada por el workflow de Windows.

| Archivo | SHA-256 |
| --- | --- |
| MusicBox-Android.apk | `7a882df3e09adde7c67c0f90c24640fe37e0e241c66f3315afa2b4284a408d2c` |
| MusicBox-Windows-Setup.exe | `94ab6e6ebc56576c059fa9158b151f6dba028f8e6f9719e941a079d4f6d8db2d` |
| MusicBox-Windows-Portable.zip | `bf77cf4e2524e1fb126aef73b5ec29eee36d420cec34771dacb4339d7665e41e` |

Esta publicación es preliminar y no sustituye a `v2.3.5` como última estable.
La suite completa de dispositivo Android conserva resultados pendientes
(avatar Zeta y cierres del emulador), y falta comprobar la desconexión de
audífonos Bluetooth físicos. El detalle y los casos aprobados se conservan en
[VERIFICACION_AUDIO_Y_COLA.md](VERIFICACION_AUDIO_Y_COLA.md).
No se declara aprobada esa batería ni se promociona esta versión a estable.
