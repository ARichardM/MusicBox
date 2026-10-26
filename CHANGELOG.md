# Historial de versiones

## 2.3.2 — 26 de octubre de 2026

### Windows

- **Versión y actualizaciones en tu perfil.** En Configuración aparece la versión instalada y un botón «Buscar actualizaciones» que muestra si estás al día o si hay una versión nueva, con su insignia y su animación.
- **Aviso de actualización más claro.** Muestra solo lo esencial, con «Ver detalles» para ampliar, y únicamente las novedades de Windows.

### Android

- **Actualizar sin salir de la app.** La hoja de actualización descarga el APK con una barra de progreso real, lo verifica (misma app y misma firma) y abre el instalador. Siempre se ven sus botones, ya no se corta y sus esquinas redondeadas se ven limpias.
- **Versión y actualizaciones en tu perfil.** Nueva tarjeta «Acerca de» con la versión instalada y un botón «Buscar actualizaciones» que avisa al abrir el perfil si hay algo nuevo.
- **Descargas con progreso real.** El anillo de cada canción avanza desde el primer momento según las etapas de la descarga (conexión, descarga, conversión y portada) y se completa antes de mostrar el icono de descargada; las canciones en cola esperan con el anillo vacío en vez de girar sin fin.
- **Sin avisos repetidos.** Al descargar varias canciones ya no sale un aviso del sistema por cada una, ni fuera de la app; al terminar el lote aparece un solo resumen dentro de la app.
- **Transición de arranque fluida.** El splash del sistema y el de la app comparten fondo, tamaño y posición del cubo, y la app aparece con un acercamiento suave.

## 2.3.1 — 25 de octubre de 2026

### Windows

- **Importar una carpeta.** En Descargas, «Importar carpeta…» lee tus archivos de audio, reconoce cada canción en YouTube, une tu archivo con ella y la agrega a la playlist que elijas. Las dudosas quedan para revisar y sus alternativas se pueden escuchar con barra de avance.
- **Versión portable.** Además del instalador, ahora se publica un ZIP que se usa sin instalar; tus datos siguen en tu usuario.
- **Mi música.** Tres pestañas, Canciones, Playlists y Carpetas. Carpetas muestra solo las carpetas de tu PC y ya no la de descargas de MusicBox. El botón ＋ Playlist está siempre a la vista y el icono de descarga es igual al de Android.
- **Origen de cada canción.** Las canciones que vienen de una carpeta muestran el icono de carpeta, también en Buscar y en las playlists; las descargadas por MusicBox, la flecha de descarga.
- **Menú de cada canción.** Se suman Agregar a playlist, Quitar de esta playlist y Eliminar del dispositivo (a la Papelera de reciclaje). El menú se abre junto al botón y combina con el resto de la app.
- **Reproductor.** Aleatorio y bucle siempre visibles; el aleatorio es un interruptor que mezcla la cola sin cortar la canción y al apagarlo vuelve al orden original.
- Inicio de sesión centrado, avisos al agregar canciones a una playlist y contadores en singular.
- Se corrige el diálogo de agregar a playlist, que no se cerraba, y las pestañas, que podían quedar marcadas de más.

### Android

- El corazón de las listas ahora reconoce los favoritos de canciones unidas a YouTube.
- **Quitar de esta playlist** y **Eliminar del dispositivo** funcionan también con archivos unidos a su canción de YouTube, y la eliminación saca la canción de todas tus playlists.
- Menú de canción más simple: un solo Eliminar (borra el archivo del dispositivo), Descargar para canciones online y Reproducir a continuación. Se quitan Agregar a carpeta y Guardar cola en playlist.
- El splash del sistema muestra el cubo completo con su onda.

## 2.3.0 — 25 de octubre de 2026

### Novedades en Windows y Android

- **Canciones con identidad propia.** Cada canción se identifica por su ID de YouTube: el archivo descargado y la entrada online se unen, ya no se duplican en las playlists y cada dispositivo reproduce desde su archivo cuando lo tiene.
- **Clics.** Contador discreto de reproducciones en canciones, carpetas y playlists, compartido entre dispositivos, y un apartado de Estadísticas en Configuración.
- **Español e inglés.** Siguen el idioma del sistema y se pueden cambiar desde Configuración, sin reiniciar en escritorio.
- **Configuración renovada.** Tarjetas redondeadas, tema e idioma, carpetas de música por dispositivo y desconexión remota de otros dispositivos.
- **Actualizaciones automáticas.** Una hoja "Nueva versión disponible" muestra las novedades; en Windows la descarga se verifica con SHA-256, se instala en silencio y MusicBox se reabre sola.
- **Marca de origen.** Las canciones de una carpeta muestran una carpeta; las descargadas por MusicBox, la marca de descarga.
- **YouTube más robusto.** Clientes alternativos, validación del enlace de audio y extractor que se actualiza solo.

### Windows

- Panel **Descargas** completo: enlaces, formato y calidad, cola, progreso real, archivos, historial, registro, informe, búsqueda con portadas e importador por imágenes, texto o tabla.
- La **cola** se reordena arrastrando, permite quitar canciones, limpiar y guardar como playlist; el menú de cada canción suma "Reproducir a continuación" y "Agregar a la cola".
- Las playlists de Android se ven completas (título, portada y canal) y los favoritos quitados en otro dispositivo ya no reaparecen.
- La descarga automática revisa las playlists al abrir, al sincronizar y al activarla.
- Avisos de error integrados con el diseño de MusicBox, con reintento.

### Android

- El reproductor indica **desde dónde suena** la canción y la cola se despliega deslizando hacia arriba, como en YouTube Music.
- La notificación suma aleatorio, repetir y favorito.
- Descarga automática que revisa lo pendiente, spinner de progreso real y sin la tarjeta inferior que tapaba la lista.
- Reproducción sin cortes con un buffer por adelantado y spinner de carga compacto.
- Lista más fluida, barra de desplazamiento de tamaño cómodo, corazón y menú compactos con animación; en Favoritos el corazón desmarcado deja la fila hasta salir de la lista.
- Botón de inicio de sesión adaptable a tablets y se quitó el botón de descarga del reproductor.

## 2.2.2 — 24 de octubre de 2026

- Inicio de sesión de Windows corregido con una configuración Firebase dedicada a escritorio.
- Compilación protegida contra credenciales OAuth o Firebase incompletas y contra el uso accidental de la clave Android.

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
