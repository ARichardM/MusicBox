# Carga de biblioteca y avisos Android

Cambios sin publicar, 27 de octubre de 2026.

## Diagnóstico

- El shimmer de Mi música era un segundo hijo con peso del contenedor vertical.
  Ocultar el RecyclerView no ocultaba su FrameLayout: ambos contenedores seguían
  repartiéndose la altura. Por eso las filas de carga comenzaban a media pantalla.
- El escaneo local compartía el ejecutor de un solo hilo con la inicialización y
  actualización por red del extractor. Una actualización lenta retrasaba contenido
  local que no necesita internet.
- Cada escaneo reabría los archivos privados con MediaMetadataRetriever aunque no
  hubieran cambiado. Varios avisos de nube/descargas podían acumular escaneos.
- El aviso de Home de la captura procede de una descarga terminada con error:
  DownloadManager concatenaba el motivo comprensible y toda la salida de yt-dlp.
  La advertencia de formatos omitidos es parte del diagnóstico, no prueba por sí
  sola de la causa del timeout.

## Correcciones

- Shimmer superpuesto en el mismo FrameLayout que la lista: comienza inmediatamente
  después de los controles y no reserva una segunda mitad de pantalla.
- Contador «Cargando…» durante la primera carga, no un cero definitivo engañoso.
- Ejecutor independiente para escanear música local. Las peticiones concurrentes
  se agrupan en una actualización posterior sin acumular tareas redundantes.
- Caché privada de metadatos validada por ruta, tamaño, fecha y lenguaje.
  Se invalida al modificar/eliminar archivos; entradas corruptas se ignoran.
  Un fallo de extracción no se memoriza. Los retrievers se liberan incluso con error.
  MediaStore se sigue consultando para descubrir cambios; no se omiten archivos.
- Avisos nuevos breves; los trabajos antiguos también se muestran sin su sufijo
  técnico. El registro de descargas conserva el diagnóstico completo.

No se han desactivado errores, cambiado reintentos ni prometido eliminar fallos
de red o rechazos externos. Se mantienen los límites/reintentos existentes.

## Verificación

Las pruebas físicas se ejecutan exclusivamente en `cl.arichard.descargador.verification`,
sin Firebase ni sesión real. La prueba de caché borra únicamente metadatos derivados
de esa variante; no borra música. La prueba de arranque bloquea deliberadamente la
cola del extractor y exige que la biblioteca local termine mientras sigue bloqueada.

Pruebas de regresión: `MusicMetadataCacheTest`, `DownloadNoticeTest` y
`LibraryLoadingDeviceTest`.

- Compilación `assembleVerification` y APK de instrumentación correctos.
- 168 pruebas unitarias correctas, sin fallos ni omisiones.
- `lintVerification` sin errores; quedan las 203 advertencias del proyecto.
- 14 pruebas físicas correctas juntas, sin reintentos ni omisiones (74,531 s):
  cuatro de carga/avisos, dos de estabilidad en vivo, seis de pulido de interfaz,
  descarga de audio sintético por loopback tras quitar la tarea y autoplay online
  con avance a la siguiente canción. Informe local ignorado por Git:
  `build/library-loading-device-final.txt`.
- En el Samsung SM-G985F: escaneo de 686 canciones, 271 ms sin caché de metadatos
  y 202 ms con caché, conservando todos los campos. Son tiempos de escaneo de esta
  muestra, no una promesa sobre el arranque completo ni todas las bibliotecas.
- En esa comprobación no se reemplazó `MusicBox pruebas`. Posteriormente llegó
  la clave original del ASUS y se actualizó sin desinstalar; estado vigente en
  `FIRMA_PRUEBAS_DOS_EQUIPOS.md`.
