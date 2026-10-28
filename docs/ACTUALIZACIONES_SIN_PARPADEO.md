# Actualizaciones en vivo sin reconstruir la interfaz

27 de octubre de 2026. Cambios sin publicar sobre 2.3.3.

## Causa comprobada

`ClickStats.recordListening` guarda y notifica el tiempo escuchado aproximadamente
cada cinco segundos mientras avanza la reproducción. La sincronización puede
actualizar también la actividad del dispositivo. Mantener estos datos actualizados
es correcto; las reconstrucciones de interfaz no lo eran:

- `SettingsActivity.renderDevices` eliminaba todas las tarjetas y volvía a ejecutar
  sus animaciones de entrada, incluso si solo cambiaba `lastSeen`.
- `StatisticsActivity.render` creaba otro ScrollView y llamaba a `setContentView`
  en cada aviso. El ranking también eliminaba todas las filas.
- Los cargadores de imágenes podían volver a colocar un shimmer/fundido al
  enlazar una portada que ya estaba en esa misma vista.
- El callback de CloudSyncManager solicitaba un escaneo de biblioteca en cada
  snapshot, aun cuando solo cambiaban milisegundos de escucha o actividad de equipos.

## Cambios

Dispositivos conserva una vista por identificador y un orden estable. Solo cambia
los textos necesarios; añade o elimina la tarjeta correspondiente cuando cambia
la lista. No se repiten animaciones de entrada en los latidos de actividad.

Estadísticas construye su pantalla una vez. Conserva ScrollView, controles,
pestaña y filas identificadas por canción/playlist. Actualiza cifras, textos y
barras existentes; mueve filas únicamente cuando cambia realmente su posición.
Las portadas se resuelven nuevamente solo si cambia su ruta.

Los cargadores compartidos conservan la imagen o petición en curso si reciben
la misma ruta en la misma vista. Un cambio de ruta invalida la respuesta antigua;
una imagen remota fallida muestra un fondo y permite intentar cargarla después.

CloudSyncManager compara el estado visible de biblioteca: playlists, historial,
contadores de reproducciones y títulos. La escucha en milisegundos y los latidos
de dispositivos siguen sincronizándose, pero no solicitan ese escaneo. Los cambios
locales de reproducciones se comparan con el último estado notificado, por lo
que continúan llegando a Inicio al sincronizarse.

## Otras pantallas revisadas

El descargador ya identifica sus filas por IDs de trabajos: actualiza progreso
sin reconstruirlas y rehace el contenedor solo si cambia la lista. Sus archivos
terminados también tienen una firma de contenido. Los carruseles de Inicio y la
biblioteca podían reconstruirse indirectamente por el callback de nube; esa ruta
queda corregida. Las pantallas de artista se reconstruyen al cambiar de página
o completar su carga, no por un temporizador periódico.

## Pruebas y límites

- 164 pruebas unitarias Android correctas. Seis nuevas comprueban identidad de
  vistas y portadas, desplazamiento, pestaña, cambios de dispositivos, y que el
  tiempo escuchado no invalide biblioteca pero sí lo hagan reproducciones/playlists.
- APK verification e instrumentación compilados correctamente.
- Dos escenarios en el Samsung SM-G985F con Android 13 pasaron juntos en
  14,213 segundos, con doce actualizaciones repetidas por pantalla: se mantienen
  las tarjetas, su opacidad, la raíz de estadísticas y su pestaña seleccionada.
- Con el APK final, esos dos escenarios y la regresión de portadas del selector
  de playlists pasaron juntos en 18,604 segundos, sin omisiones ni reintentos.
- Las pruebas de dispositivos usan datos sintéticos en `verification` y restauran
  su preferencia de dispositivos. No desconectan equipos reales ni operan con
  una cuenta Firebase. El filtro de eventos de nube se verifica con datos locales.

MusicBox pruebas conserva su instalación anterior; estos cambios requieren la
clave del Asus para actualizarla sin desinstalarla. No se consideran instalados
en esa variante hasta completar `FIRMA_PRUEBAS_DOS_EQUIPOS.md`.
