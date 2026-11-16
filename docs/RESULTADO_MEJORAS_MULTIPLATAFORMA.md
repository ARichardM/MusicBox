# Resultado de las mejoras multiplataforma

Implementación local, 15–16 de noviembre de 2026. Versiones conservadas en 2.4.5; la web se alineó con las aplicaciones. No hay nueva etiqueta, publicación ni despliegue. Las portadas animadas nativas proceden de la integración `39fc198`; esta ejecución corrige su funcionamiento en Samsung y añade su equivalente web.

## Cambios por requisito

| Plan | Resultado | Plataformas |
| --- | --- | --- |
| 1–3 | Reproductor compacto de 96 px, vista grande y menos espacio desperdiciado en biblioteca. | Windows y web de PC |
| 4 | Una reproducción al superar 30 segundos acumulados de progreso real; protección ante pausa, buffering, seek y recuperación. Se conservan los datos históricos. Pistas menores a 30 segundos no alcanzan el umbral. | Android, Windows y web |
| 5 | Localizar recuerda la playlist/carpeta de origen y desplaza hasta la canción; no escoge una colección arbitraria. | Android, Windows y web |
| 6 | Refresco manual de biblioteca, playlist y recomendaciones con actividad, bloqueo de doble pulsación y resultado/error. Se mantiene el refresco Android existente. | Windows y web; revisión Android |
| 7 | Icono de configuración y acciones de menú completas; las 29 acciones del menú principal QML declaran icono. | Windows y web |
| 8–12 | Carpetas y playlists del mismo tamaño en PC; portada sin recorte; sin borde de tarjeta ni insignia superpuesta; indicador de canción usa el acento elegido. | Windows y web, conservando presentación móvil |
| 13 | Radio de playlist con varias semillas, ponderación, variedad y exclusión de duplicados; añade a cola sin alterar la colección. | Android, Windows y web |
| 14 | Transiciones configurables de 0 a 12 segundos; mezcla complementaria y cancelación en controles manuales. | Android, Windows y web |
| 15 | Diálogos con tema uniforme, sin velo blanco ni franjas claras; fondo oscuro y desenfoque con aceleración gráfica. | Windows y web |
| 16 | Volver desde Mi música a la última playlist con gesto inverso y memoria de posición, incluso si solo hay una playlist. | Android y web móvil |
| 17 | Portadas animadas silenciosas, caché, respaldo estático y liberación de recursos. Media3 evita el fallo del extractor Samsung observado durante las pruebas. | Android, Windows y web |
| Transversal | Base visual y funcional web alineada con cada aplicación; diseño móvil en orientación horizontal y listas virtualizadas. | Web de PC y móvil |

## Verificación ejecutada

- Windows: **543 pruebas satisfactorias**. Incluyen recuperación de audio, umbral de escucha, origen de playlist, autoplay, refresco, QML y reproducción con dos motores durante crossfade. Después se amplió la prueba QML con apertura/cierre del reproductor grande y también pasó.
- Android: **290 pruebas unitarias satisfactorias**, `lintVerification` y `assembleVerification` correctos. Los nuevos casos cubren crossfade y conteo sin duplicados al recuperar el audio.
- Samsung SM-G985F, Android 13, por wireless: prueba instrumental de portada animada local y online, retorno a portada estática y liberación al cerrar, correcta. Solo paquete `cl.arichard.descargador.verification`.
- Web: **9 pruebas de núcleo y 15 de API** satisfactorias. Suite funcional Chromium: **148 comprobaciones** en cuatro tamaños, **8 comprobaciones de Firestore simulado** y recarga/reproducción sin conexión.
- Web, suite de experiencia: cinco tamaños, incluido 844 × 390 táctil; origen de playlist, refresco, tarjetas, gestos, vista grande, autoplay y lista de 500 canciones. Incluye crossfade real sobre WAV sintético, ganancias complementarias, cancelación y continuidad si falla el motor siguiente.
- Resolución real de portada web con metadatos públicos: devuelve MP4 del host permitido de Apple. Esto verifica resolución; la prueba física de renderizado corresponde a Android.
- Ejecutable de Windows empaquetado con PyInstaller y prueba `--smoke-test`: salida **0**.
- `git diff --check` de los cambios de esta implementación: correcto. Se conserva aparte el cambio previo del usuario en `docs/MUSICBOX_WEB_DESPLIEGUE.md`.

## Compilaciones locales

- Windows: `dist/verification-desktop/MusicBox/MusicBox.exe` (carpeta portable completa; no mover el EXE separado de `_internal`).
- Android aislado: `apps/android/app/build/outputs/apk/verification/app-verification.apk`.
- Resultados/capturas web: `apps/web/.test-artifacts/`, ignorado por Git.
- Informes Android: `apps/android/app/build/reports/`, ignorado por Git.

## Comandos reproducibles

```powershell
rtk proxy .venv/Scripts/python.exe -c "import sys,unittest;sys.path.insert(0,'apps/desktop');r=unittest.TextTestRunner().run(unittest.defaultTestLoader.discover('tests/desktop'));sys.exit(not r.wasSuccessful())"
rtk proxy node --test tests/web/core.test.js
rtk proxy .venv/Scripts/python.exe -m unittest discover -s tests/web -p test_api.py
rtk proxy node tests/web/playwright_checks.js
rtk proxy node tests/web/experience.test.js
```

Las suites de navegador aceptan `MUSICBOX_PLAYWRIGHT_MODULE` para una instalación externa de Playwright. En `apps/android`:

```powershell
rtk proxy .\gradlew.bat testVerificationUnitTest lintVerification assembleVerification --console=plain
rtk proxy .\gradlew.bat connectedVerificationAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cl.arichard.descargador.AnimatedArtworkDeviceTest' --console=plain
```

## Límites de esta entrega

### Correcciones adicionales del 16 de noviembre

- El zoom MP3 ahora reutiliza el mismo encuadre inmersivo de MP4: borde superior, ancho completo, fundidos y retorno con Atrás. Se sustituyó la ampliación simple; escritorio y web incluyen el encuadre equivalente.
- Chips web persistentes, con transición de ancho/opacidad y disponibilidad estable durante actualizaciones de metadatos. Las pruebas con streaming real a 390 y 1280 px registraron cero reemplazos de los chips.
- La cola Android ajusta su altura al espacio disponible desde su posición expandida; la última fila puede verse completa. La web añade espacio inferior seguro.
- “Crossfade” está en el menú de tres puntos de Android, escritorio y web; al desactivar y reactivar conserva la duración elegida.
- El selector “Agregar a playlist” muestra las portadas reales en escritorio y web, con respaldo para imágenes ausentes o fallidas.
- Windows: la suite completa volvió a pasar, **543 pruebas**. Android: **290 pruebas unitarias**, compilación y lint correctos. En Samsung pasaron **6 pruebas instrumentales**: zoom/menú/cola, portada animada y cuatro casos de crossfade.
- Crossfade con música real: escritorio pasó con WAV locales y streaming HTTP a través de la API local (**77 y 76 muestras de solapamiento**). La API obtuvo audio real de U2 y Dua Lipa. La resolución nativa directa de esos enlaces YouTube se intentó aparte: inicialmente no arrancó dentro del límite con yt-dlp 2026.8.19; tras actualizar a **2026.10.21**, la comprobación directa también terminó con código 0 y cumplió las aserciones de precarga, solapamiento y ganancias. Se elevó la dependencia mínima compatible con Python 3.10; la web usa una versión posterior cuando dispone de Python 3.11 o superior.
- Web: música real desde IndexedDB y streaming; **99 muestras de solapamiento** en las comprobaciones reproducibles a 390 y 1280 px. Se comprobó precarga anterior al fundido, cruce de ganancias y liberación del motor saliente. Los temporizadores de audio funcionan también con la pestaña oculta; ya no dependen de `requestAnimationFrame`.
- Precarga web de la siguiente canción desde el comienzo de la pista actual; una prueba con respuesta de red retenida comprueba que no se inicia una mezcla silenciosa ni se interrumpe la canción actual.
- La prueba real también detectó rechazos 403 de YouTube para algunas grabaciones. Se conservan las cabeceras validadas por el resolver en el proxy; eso no elimina las restricciones del proveedor para todas las canciones.

Pruebas opcionales reproducibles con proveedores reales: `tests/web/streaming_checks.js` y `tests/desktop/crossfade_music_check.py`. Usan el modo verification y archivos temporales/ignorados; no se utilizaron cuentas ni playlists reales.

El crossfade requiere una siguiente pista preparada y se omite en video; si falta audio preparado continúa la reproducción normal. No se promete reproducir el algoritmo propietario de YouTube: se aprovecha el recomendador existente y varias semillas de la colección.

La paridad visual y funcional se revisó en los flujos descritos; **no está certificada la igualdad de píxeles de todas las pantallas** ni la integración real de una cuenta Google. La API se probó localmente con proveedores simulados; no se ha verificado ni cambiado el servidor de producción. Docker no está instalado aquí y no se construyó su imagen. Las diferencias inevitables de acceso a archivos y reproducción del navegador se detallan en [la matriz de paridad](PARIDAD_WEB.md).

Las capturas QML automáticas usan renderizado por software: los efectos GPU de iconos/desenfoque no representan fielmente el aspecto acelerado. La prueba QML sí verifica estructura, controles, tema del diálogo y errores de carga; no se usa esa captura para certificar los efectos del equipo del usuario.
