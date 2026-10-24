# Historial de versiones

## Sin publicar

## 2.2.1 — 24 de octubre de 2026

- Reorganización profesional del monorepo en `apps/`, `tests/`, `scripts/` y `legacy/`.
- Android actualizado a target SDK 36 sin acceso total al almacenamiento.
- Backups internos deshabilitados, servicio multimedia protegido y archivos compartidos mediante `FileProvider`.
- WebView limitado a HTTPS y dominios oficiales de YouTube, sin acceso a archivos locales ni contenido mixto.
- Pillow actualizado a 13 y vigilancia automática mediante Dependabot y CodeQL.
- Instalador profesional para Windows 10/11 con ejecutable autocontenido, FFmpeg, metadatos de versión y desinstalador.
- Publicación automática del instalador y su suma SHA-256 en GitHub Releases.
- Datos del escritorio separados de los recursos instalados y almacenados en el perfil local del usuario.

### MusicBox Android 2.2.0

- Bucle desactivado, de lista o de canción, con estado visible y preferencia guardada.
- Acciones del reproductor para añadir a playlists, copiar o descargar a carpetas
  y eliminar una canción con confirmación y comprobación del resultado.
- Mi música conserva la colección abierta, los filtros, la búsqueda y el desplazamiento.
- La canción actual se destaca con fondo, borde, color y un indicador de reproducción.
- Botón compacto para localizar la canción activa en la lista.
- Gestos horizontales en el minirreproductor para avanzar o retroceder, conservando
  el gesto hacia arriba para abrir el reproductor completo.
- Cola editable en un panel inferior: añadir varias canciones, reproducir después,
  arrastrar para reordenar, quitar entradas y limpiar conservando la canción actual.
- Cola compartida entre canciones locales y online, guardada junto a la canción y
  la posición de reproducción; al volver a abrir se recupera en pausa.
- Carpetas y playlists muestran collages de hasta cuatro portadas, ordenadas por
  reproducciones, incluidas las canciones que avanzan automáticamente.
- Indicador de descarga con flecha y tic.
- Gestos delimitados por sección: navegación entre pestañas desde el contenido,
  prioridad de carruseles y filtros, cierre del reproductor solo desde su portada y reordenamiento de cola
  sin arrastrar el panel. Cancelar un gesto nunca cambia de canción ni cierra el reproductor.
- Navegación encima del minirreproductor, animada al mostrarlo u ocultarlo, y
  transición de portada y título que acompaña el swipe entre canciones.
- Navbar con botones y selector arrastrable; páginas y reproductor expandido
  acompañan el movimiento del dedo antes de completar o cancelar el gesto.
- Menú de tres puntos con iconos para playlist, carpeta, eliminación y cola.
- El cambio de tema conserva biblioteca, resultados de búsqueda, posición y tareas
  de descarga; el selector MP4 se oculta para fuentes sin video.
- Filtros animados, cabeceras compactas, botones de texto enmarcados y centrados,
  y protección de las zonas de acciones frente a pulsaciones durante el scroll.

### MusicBox Android 2.1.1

- Se incorpora el proyecto Android completo, con Gradle Wrapper y APK instalable.
- Reproducción nativa asíncrona de audio y video mediante MediaPlayer y yt-dlp,
  con controles compartidos entre el reproductor completo y el compacto.
- Cambio entre portada/audio y video conservando la posición y proporción del video.
- Play compacto visible sobre las portadas, favoritos y agregar a playlists en búsqueda.
- Playlists con canciones online, switch de descarga automática y cola de descargas.
- Indicador de canciones descargadas, resultados planos y caché de búsquedas repetidas.
- Biblioteca con filtros, carpetas, historial y controles de reproducción en segundo plano.
- Configuración de descarga MP4 y mensajes de carga/error en la reproducción.

### Escritorio

- Las listas copiadas agrupan colaboraciones con varios artistas, incluso con
  separadores y saltos de línea, sin buscar nombres o álbumes como canciones.
- Los bloques ambiguos se conservan en una sola fila sin marcar, con motivo y texto original.
- Las coincidencias débiles o con diferencias de artista, versión o duración quedan
  pendientes de revisión manual; las alternativas siguen visibles en el informe.
- Escucha rápida de solo audio a la derecha de cada coincidencia, con play/pausa,
  barra para buscar un instante y tiempos; se conserva el reproductor de video.
- Audio en un trabajador independiente con búfer acotado en memoria y controles
  limitados a las filas visibles para mantener fluida la lista.
- Las consultas vacías prueban variantes de nombres bilingües y títulos con símbolos,
  con intentos limitados y un informe de las búsquedas realizadas.
- Se amplía una vez la búsqueda si todos los primeros resultados fueron descartados,
  y los errores del buscador se distinguen de las consultas sin alternativas.
- Reproducción de coincidencias durante la búsqueda, con pausa, barra de tiempo y
  saltos de diez segundos; cambiar de resultado o cerrar el importador detiene la escucha.
- La tabla muestra el canal de los videos y reconoce artistas acreditados en el título,
  conservando el título completo, el orden y los datos del texto y del OCR.
- Logo minimalista de música y descarga en la cabecera, la ventana y el acceso directo.
- Script para crear el acceso directo de Windows con apertura sin consola.
- Historial persistente de archivos terminados, con búsqueda, paginación y acciones
  para abrir archivos, carpetas o volver a añadir el enlace a la cola.
- Escritura del historial desde el trabajador y consultas en segundo plano.
- Los nuevos MP3 incrustan la portada y los metadatos disponibles en la fuente.
- El progreso distingue el guardado de metadatos y la incorporación de la portada.
- Las descargas de varios enlaces llevan números según su orden en la cola,
  conservando la secuencia importada desde texto o imágenes.
- Los archivos terminados se conservan sin volver a escribir sus etiquetas.
- Se fija una versión compatible de Shapely en Windows para la lectura de fotos.

## v1.0 — 1.0.0 (2026-10-15)

Primera publicación del proyecto Descargador.

- Descargas MP3/MP4 individuales o por playlist, con calidad configurable.
- Interfaz Tkinter con progreso, cancelación y registro de resultados.
- Importación de imágenes, recortes y reconocimiento de canciones mediante OCR local.
- Lectura de listas en texto, tabla editable y filtros de búsqueda.
- Comparación de coincidencias y propuesta automática por título, artista y duración.
- Informe de pendientes, comprobación de playlists y reintento de consultas fallidas.
- Detección y confirmación de elecciones que apuntan al mismo video.
- Buscador con portadas y selección de resultados para la cola.
- Reproductor integrado basado en YouTube y WebView2, separado de la descarga.
- Pruebas automatizadas de interfaz, procesamiento, errores y reproducción integrada.

### Límites conocidos

- YouTube puede rechazar búsquedas, reproducción integrada o descargas por
  restricciones del video, verificación de sesión o límites de solicitudes.
- La reproducción integrada no importa la sesión del navegador ni las cookies
  configuradas para descargas.
- FFmpeg, WebView2, cookies y archivos multimedia no se distribuyen en el repositorio.
