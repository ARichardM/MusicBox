# Paridad de MusicBox Web

## Criterio solicitado

- La web en computador debe seguir la interfaz, navegación y comportamiento de MusicBox para Windows.
- La web en celular debe seguir la aplicación Android, incluyendo Inicio, Buscar, Biblioteca y el reproductor móvil.
- Conservar la identidad, colores, temas claro/oscuro, idiomas y funciones del software.
- Probar con datos aislados; no modificar ni reconstruir playlists reales durante las pruebas.
- Guardar la implementación web en `apps/web`; el sitio existente se aloja en el VPS, en `/home/ubuntu/projects/musicbox-web`.

## Verificación

La URL `/?verification=1` usa una base IndexedDB separada y desactiva Firebase y el registro del service worker. No iniciar sesión con una cuenta real para probar.

Las funciones que dependen de servicios externos requieren pruebas reales antes de declararse verificadas.

## Implementación del 14 de noviembre de 2026

| Área | Cambios |
| --- | --- |
| Escritorio | Paleta y dimensiones de QML, barra lateral 230/76 px, tarjetas cuadradas, formularios y botones completos, temas claro/oscuro y azul/naranjo. Los archivos de marca coinciden por SHA-256 con los de Windows. |
| Celular | Referencia Android: encabezado con cuenta, recomendaciones horizontales, barra flotante Inicio/Música, minirreproductor, pantalla completa, MP3/MP4/letra y cola. Descargas e historial se abren desde Cuenta. |
| Reproducción | Audio y MP4 locales, pausa, salto, volumen, aleatorio, repetición, cola ordenable y eliminación de entradas. Las peticiones tardías de audio, búsqueda y letras se descartan. Un rechazo de YouTube no cambia a una canción distinta. |
| Biblioteca | Búsqueda, orden, importación de archivos y carpetas, favoritos, playlists editables, descripción, fijación en Inicio, reordenamiento, eliminación y exportación CSV. |
| Importación | Revisión previa y rango de canciones. Se reutilizó la lógica de coincidencias de Windows para YouTube, Spotify, Deezer, Apple Music y SoundCloud. Los flujos se probaron con datos sintéticos; no se certificó la disponibilidad actual de las cinco plataformas externas. |
| Descargas | MP3 a 128/192/256/320 kbps y MP4 a 360/480/720/1080p; progreso, cancelación, descarga automática de playlists, historial y guardado del archivo. La descarga real de YouTube sigue bloqueada en el VPS. |
| Cuenta | Identificadores `youtube:…`, mezcla de tres vías, conservación de datos de otros dispositivos y cambios locales sin enviar. Preferencias, contadores por dispositivo, registro de navegador y desconexión remota. Falta validar con una cuenta real. |
| Estadísticas | Se registra el tiempo efectivamente reproducido. Los contadores compartidos usan el máximo por dispositivo para evitar duplicar reproducciones al sincronizar. |
| Sin conexión | Shell y controles en caché; las pistas importadas o descargadas se reproducen después de recargar sin red. Las fotos externas pueden no estar disponibles sin conexión. |

## Evidencia

- `node --test tests/web/core.test.js`: 8 pruebas de identidades, preservación de metadatos, mezcla concurrente, contadores y escape de HTML.
- `.venv/Scripts/python.exe tests/web/test_api.py`: 14 pruebas de API, enlaces exactos, formatos, errores del proveedor, limpieza de temporales y protección de cookies.
- Navegador Chromium mediante la skill Playwright: 39 comprobaciones en 390×844 y 360×740; 33 en 1280×800 y 768×1024. Cubren creación/importación/edición de playlists, filtros, favoritos, cola, letras, audio, preferencias, estadísticas, descarga simulada, cancelación, calidad y descarga automática.
- Firestore simulado: 8 comprobaciones, incluyendo adiciones simultáneas desde otro equipo, preservación de rutas locales y recuperación tras un fallo de red. No se utilizaron cuentas reales.
- Pruebas adicionales: recarga de la PWA sin conexión, importación/reproducción de un MP4 sintético y exportación con conservación de todos sus bytes.
- Reproducción real en producción: `6DeDzsCGbsQ` (BBC Music) reprodujo correctamente y avanzó el tiempo. También se observaron rechazos de incrustación de YouTube, código 150, en otras grabaciones; se informa el error y se conserva la selección.
- Descarga real desde producción: `/api/download/9qnqYL0eNNI?format=mp3&quality=192` devolvió 503. YouTube exige autenticación al servidor. Los tests con respuestas simuladas no resuelven ni certifican esta dependencia.

Los resultados JSON y PNG están en `apps/web/.test-artifacts/` y no se incluyen en Git. Las capturas usan una biblioteca sintética. Se revisaron capturas de Inicio y reproductor en móvil, tablet y escritorio; no se certifica igualdad de píxeles de todas las pantallas ni funcionamiento en un teléfono físico.

## Despliegue y respaldo

Sitio: `https://openmusicbox.duckdns.org/`. Build: `parity-20261114`. Contenedor: `musicbox_web`, publicado únicamente en `127.0.0.1:8005` detrás de Nginx HTTPS.

Antes de desplegar se creó este respaldo completo en el VPS:

```text
/home/ubuntu/backups/musicbox-web-before-parity-20261114T182446Z
Imagen anterior: musicbox-web:backup-20261114T182446Z
```

Se conservaron los datos y configuraciones previos. Para revertir, restaurar frontend, backend y compose desde ese respaldo y arrancar la imagen anterior. No se publicó una nueva versión de Windows/Android ni se creó una etiqueta estable.

## Pendiente para certificar paridad completa

1. Resolver el acceso del VPS a YouTube y completar descargas MP3 y MP4 reales con sus calidades. No dar por verificadas estas descargas a partir de fixtures.
2. Completar inicio/cierre de sesión Google, sincronización y desconexión entre dispositivos con una cuenta de prueba autorizada y sin playlists personales.
3. Revisar en teléfono físico los gestos, teclado, audio en segundo plano y límites del navegador. Una web no recibe acceso automático a las carpetas que conoce la aplicación nativa.
4. Revisar las pantallas restantes con el usuario frente a las aplicaciones. Las comprobaciones realizadas cubren los flujos descritos, no una certificación exhaustiva de todas las funciones nativas.


## Correcci?n visual posterior: colores, reproductor y botones

- Unificar azul/naranjo con los tokens de `Theme.qml`, incluidos fondos adaptados, iconos, brillo, letras y texto oscuro sobre el acento naranjo. Conservar los colores sem?nticos de borrar/cerrar sesi?n y estados de ?xito.
- Quitar la separaci?n horizontal del reproductor en escritorio: lateral y barra inferior comparten color y se tocan sin borde.
- Componer `logo_complete.png` con los dos recursos nativos (`logo_cube.png` y `logo_wave.png`): la car?tula predeterminada muestra el logo entero con `object-fit: contain`. No modificar los originales nativos.
- Impedir la compresi?n de los hijos del scroll de configuraci?n. Cerrar sesi?n mantiene 48 px y radio 24 px; las acciones secundarias usan el fondo de PillButton y las de borrado su borde rojo. Opciones de color con el control de selecci?n circular de Choice.qml. Scroll con el tema de la aplicaci?n.
- Verificaci?n: `tests/web/visual_checks.js` comprueba las ocho combinaciones de escritorio/celular, claro/oscuro y azul/naranjo, sin errores JavaScript. Capturas revisadas visualmente. Suite funcional de navegador: 39/33/39/33 controles por tama?o, sincronizaci?n simulada y reproducci?n/archivo local correctos.
- Respaldo de frontend anterior: `/home/ubuntu/backups/musicbox-web-ui2-20261114/frontend`. Recursos publicados con identificador de cach? `parity-20261114-ui2`.
- Esta correcci?n no elimina los pendientes externos de YouTube, autenticaci?n real y prueba f?sica indicados arriba.

Validaci?n posterior a publicar: las ocho combinaciones visuales y la suite funcional completa tambi?n pasaron sobre HTTPS en producci?n, en modo aislado `verification`. Sin errores JavaScript no controlados. Los hashes de `index.html` y `style.css` publicados coinciden con los locales; el navegador confirma el logo completo y el build `parity-20261114-ui2`.
