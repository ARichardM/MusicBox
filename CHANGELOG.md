# Historial de versiones

## 2.3.7 — 29 de octubre de 2026

### Windows

- «Ir a la canción actual» limpia los filtros y encuentra la fila con una sola actualización, evitando que la interfaz se bloquee con bibliotecas grandes.
- Al eliminar la canción que está sonando, el reproductor libera primero el archivo y espera en segundo plano a que Windows cierre el recurso antes de moverlo a la Papelera de reciclaje.

## 2.3.6 — 29 de octubre de 2026

### Publicación estable

- Publicación `v2.3.6` para Windows y Android, promovida desde `v2.3.6-rc.2` después de aprobar las compilaciones firmadas, las suites automatizadas, el análisis de seguridad y la comprobación de la variante aislada en un Samsung físico.

### Windows y Android

- Al volver a abrir MusicBox se conserva la última canción, su cola y el punto donde quedó. La sesión se restaura pausada para que el usuario decida cuándo continuar.
- «Ir a la canción actual» abre la biblioteca, limpia los filtros que ocultaban la pista y la centra desde cualquier sección o colección.
- La recuperación de errores de reproducción renueva los enlaces fallidos y conserva la cola. Si una canción sigue fallando, se continúa con otra disponible, sin bucles cuando todas fallan.
- Reintentar desde el aviso vuelve a la canción fallida sin reemplazar la cola por una sola entrada.
- Desconectar los audífonos pausa la reproducción y cancela arranques, avances o reanudaciones pendientes. Reconectarlos no inicia la música automáticamente.

### Windows

- El reproductor incluye el menú de tres puntos para la canción actual, y los títulos largos se desplazan suavemente en el espacio disponible.
- El selector de artistas permanece completo y utilizable al expandir o reducir la ventana.
- El reproductor y la escucha previa siguen la salida predeterminada de Windows y pausan antes de cambiar a otra salida cuando desaparece la que se estaba usando.
- Inicio busca automáticamente tras una pausa de 450 ms al escribir; las respuestas antiguas no reemplazan una búsqueda más reciente.
- Se muestran anillos de carga al buscar, abrir una canción y descargar, con una marca de descarga discreta al estilo de Android.
- La recuperación conserva la posición cuando es posible y publica en la caché únicamente descargas completas, sin reutilizar archivos truncados.

### Android

- El splash muestra el cubo y su onda completos con un aura animada; las solicitudes de permisos esperan hasta que termine la entrada.
- Se refuerza la pausa por desconexión con avisos de dispositivos y cambios de ruta, incluidos Bluetooth y USB, sin reanudar por una recuperación de foco o radio pendiente.
- Los controles de volumen de las tres pantallas del reproductor actúan sobre el audio multimedia.
- Los archivos locales dañados se omiten también sin internet; una caída de red conserva la cola online.
- La variante aislada `verification` incluye x86_64 para ejecutar Python y FFmpeg nativos en el emulador, sin cambiar las arquitecturas de publicación para teléfonos.

## 2.3.5 — 29 de octubre de 2026

### Windows y Android

- Playlists y carpetas se recorren con un gesto horizontal fluido y conservan su posición, filtros y contexto. El encabezado identifica claramente si se está viendo una playlist o una carpeta y muestra su nombre.
- El selector de artistas incorpora búsqueda inmediata, fotos o iniciales, selección reactiva y transiciones pulidas sin modificar la playlist.
- Los contadores de reproducciones usan audífonos en canciones, playlists y carpetas en lugar del icono de reproducción.
- Las portadas reutilizan solicitudes y resultados, evitan fundidos repetidos y reducen parpadeos al actualizar listas y collages.

### Android

- El splash de MusicBox tiene una entrada más visible, movimiento continuo y una salida integrada con Inicio. En Android 12 o posterior se oculta el icono nativo intermedio para evitar el doble splash.
- La biblioteca, sincronización y secciones de Inicio reparten su trabajo entre procesos de fondo y fotogramas consecutivos. Cambiar de pestaña ya no fuerza un escaneo completo y las tarjetas aparecen sin bloquear la animación inicial.
- La variante `verification` abre sin una cuenta ni Firebase reales y permanece aislada para pruebas automáticas; `preview` conserva la sesión real del usuario.
- Las imágenes remotas se deduplican, se decodifican al tamaño necesario y dejan de provocar cargas de bitmap lentas durante el desplazamiento.

### Windows

- La biblioteca restaura al instante los metadatos guardados y valida los archivos en segundo plano, agrupando escaneos repetidos.
- Las direcciones de reproducción online se reutilizan durante diez minutos para reducir la espera al volver a escuchar música en la nube.
- El diseño de playlists, carpetas y filtro de artistas se adapta al ancho disponible y mantiene animaciones consistentes con Android.

## 2.3.4 — 28 de octubre de 2026

### Windows

- El arranque ya no se cierra si falta la configuración de Firebase o contiene JSON inválido. El inicio de sesión informa del problema sin hacer solicitudes ni descartar el token guardado.
- La prueba de interfaz usa una portada local de muestra y deja de depender de una imagen inexistente en YouTube.

### Android

- «Más escuchadas en tu biblioteca» muestra una sola entrada por canción aunque existan varias copias del mismo video en carpetas distintas. Se elige la copia con mejores metadatos, sin borrar archivos, modificar playlists ni multiplicar contadores.
- Autoplay recupera radios de videoclips como «Thunder»: busca la misma canción y artista en el catálogo de audio antes de generar otra radio, sin aceptar covers ajenos ni repetir ambas versiones de la semilla. La misma corrección se aplica a Windows.
- Mi música corrige el espacio vacío antes del shimmer y carga su biblioteca sin esperar actualizaciones de red del extractor. Reutiliza metadatos de archivos sin cambios y agrupa escaneos repetidos. Los avisos de descargas muestran un motivo breve, con el detalle técnico conservado en el registro.
- Dispositivos conectados conserva las tarjetas al actualizar su actividad. Estadísticas actualiza cifras y filas sin reconstruir la pantalla ni perder desplazamiento o pestaña. Las portadas ya cargadas no repiten brillo o fundido, y los avisos de tiempo escuchado no fuerzan escaneos de biblioteca.
- Autoplay reintenta consultas fallidas hasta tres veces y permite reintentar desde la cola con un estado visible de búsqueda o error. Limpiar la cola o reabrir el reproductor ya no bloquea la búsqueda para la canción actual. Las portadas de la cola quedan separadas del borde y recortadas con esquinas redondeadas.
- La variante de pruebas preview admite una clave compartida fuera de Git para compilar desde dos equipos con la misma firma. Comprueba la identidad antes de empaquetar y el instalador no abre una versión antigua si falla la compilación o instalación.
- Las búsquedas y los tiempos de reproducción se mantienen consistentes al cambiar el idioma del teléfono.
- Los controles de reproducción y favoritos tienen etiquetas accesibles; las imágenes decorativas no repiten el texto de las canciones.
- Las animaciones de inicio y carga reutilizan sus objetos gráficos; detener el inicio cancela también su cierre programado.
- Se actualiza el analizador lint para leer los metadatos de Firebase y se añaden diagnósticos de estado a las esperas de reproducción online. CI compila y comprueba la variante aislada verification y conserva sus informes.
- El minirreproductor permanece visible al abrir artistas y álbumes, con pausa, siguiente, cola, progreso y acceso al reproductor completo.
- Las búsquedas recientes aparecen debajo del buscador, con altura limitada al espacio disponible sobre el teclado.
- Los selectores de playlists muestran portadas y cantidad de canciones. También se muestran portadas de canciones que todavía no se han reproducido.
- Coincidencias e Informe del importador incorporan filtros con contadores: Todas, Elegidas, Por revisar y Pendientes. El informe permite volver directamente a revisar; copiar, compartir y finalizar conservan todas las elegidas.
- Las colecciones más escuchadas abren correctamente sus canciones: se corrige el intercambio entre los modos de playlist y carpeta. Sus tarjetas mantienen una altura uniforme y acortan los títulos largos.
- Todas las pantallas de MusicBox quedan fijas en orientación vertical, incluido el reproductor de video.
- La cola de descargas se guarda de forma atómica y se recupera al abrir la pantalla principal o el descargador después de reiniciar el teléfono o terminarse el proceso. Conserva enlaces, opciones, carpeta, rangos y numeración; reanuda parciales cuando el servidor lo permite, sin reiniciar descargas canceladas ni terminadas.

## 2.3.3 — 27 de octubre de 2026

### Windows y Android

- **Sincronización de playlists protegida.** Los cambios se combinan con una base por dispositivo y se guardan con control de concurrencia. Una copia antigua no borra canciones nuevas de otro equipo. Se conservan diez respaldos locales y un registro de cambios con el dispositivo que los escribió.
- **Inicio.** La pestaña Buscar pasa a llamarse **Inicio**: arriba la búsqueda de siempre y, mientras no escribes nada, tus recomendaciones.
- **Para ti.** Canciones que se adaptan a lo que escuchas. Lo que escuchas últimamente pesa más (una reproducción de hace dos semanas vale la mitad), se toman tus canciones favoritas del momento, se piden a YouTube Music las parecidas a cada una y se mezclan: la que aparece en varias listas sube. Solo salen **canciones de audio** (no videoclips ni videos subidos por otras personas), con su artista y su álbum, y se descartan las que ya tienes. Se calcula al abrir la app o al pulsar **Actualizar**; escuchar, sincronizar o volver de otra pantalla conserva la lista que estás viendo. La actualización manual consulta de nuevo el origen y, sin conexión, se conserva lo último disponible.
- **Artistas para ti.** Cada artista aparece con su foto en un marco redondo y su nombre. Al abrirlo ves su página: **canciones más escuchadas**, **todas sus canciones** (con duración y portada), **álbumes** y **sencillos y EPs**. Al tocar un álbum se abren sus pistas, y al tocar una canción se reproduce la lista como cola.
- **Canciones, no videos.** Las recomendaciones y las páginas de artista usan YouTube Music, que distingue la pista de audio oficial de los videoclips y de los videos subidos por cualquiera, así que ya no se mezclan.
- **Reproducciones por día.** Se sincronizan entre tus dispositivos y alimentan el algoritmo; en Windows ahora también cuentan las canciones que escuchas desde YouTube.
- **Reproducción automática.** Al llegar al final de una cola online se agregan canciones similares, sin repetir las que ya están en la cola ni añadir mezclas de más de diez minutos. Se activa o desactiva desde la cola. Las respuestas de una cola anterior se descartan al cambiar de lista o limpiarla.

### Android

- **Descargador completo, desde tu perfil.** Pega enlaces (uno por línea) y elige MP3 (128 a 320 kbps) o MP4 (360p a la mejor calidad). Reconoce solo las playlists y permite bajar un rango (`1-10`, `1,3,5-8`), cada una en su carpeta y con los archivos numerados. La cola trabaja en segundo plano con una notificación y botón **Cancelar**; el avance muestra velocidad, tiempo restante y «archivo 3 de 20». Lo que ya existe se conserva, y lo que falla explica el motivo.
- **Historial y registro.** Historial paginado con búsqueda (abrir, compartir o volver a la cola) y un registro con colores.
- **Cookies de YouTube.** Carga un `cookies.txt` para los videos que piden iniciar sesión; se valida antes de usarlo.
- **Buscar con portadas.** Resultados con miniatura para elegir varios y añadirlos a la cola sin descargar; cada uno se puede escuchar o abrir en YouTube.
- **Importar lista.** Pega una lista (también las copiadas de Spotify o YouTube Music), revisa la tabla, busca las coincidencias en YouTube con elección automática solo cuando es segura, y añade las elegidas a la cola con un informe que se puede copiar o compartir.
- **Leer una imagen.** El importador reconoce el texto de capturas y fotos con un modelo incluido en la app, sin necesitar conexión. Puedes corregir el resultado antes de añadirlo a tu lista.
- **Compartir hacia el descargador.** Desde YouTube u otra app, comparte un enlace con MusicBox para revisarlo en el descargador; la descarga comienza cuando tú la inicias.
- **Una sola cola de descargas.** Búsqueda, biblioteca, reproductor y descargador utilizan la misma cola, con progreso, historial, cookies y cancelación. Las descargas continúan al cerrar la pantalla o retirar la app de recientes; la notificación permite cancelarlas. Forzar la detención o apagar el teléfono sí las interrumpe.
- **Selección múltiple.** Mantén pulsada una canción para seleccionar varias, marca todas las visibles y reproduce, añade después de la actual, agrega a la cola o a una playlist y elimina del dispositivo. La barra de acciones ocupa el lugar de la navegación y el minirreproductor durante la selección.
- **Orden de biblioteca.** Orden original, título, artista, fecha, reproducciones o duración. Tocar otra vez el criterio activo invierte su sentido; se recuerda la elección.
- **Eliminar con permiso de Android.** Muestra cuántos archivos se eliminarán y el espacio que ocupan; cuando la música pertenece a otra app, Android pide su propia confirmación. Solo las canciones eliminadas se retiran de playlists, historial y cola.
- **Cierre desde recientes.** Detiene la música y retira la notificación del reproductor, conservando la posición para continuar al volver a abrir.
- **Importar carpeta.** Reconoce los archivos de una carpeta en YouTube y los enlaza a una playlist sin descargar nada.
- **Cola del reproductor.** Un toque fuera de la hoja la cierra con animación.
- **Tu música en cifras.** Estadísticas con el tiempo escuchado y tus canciones más escuchadas.

### Windows

- **Página del artista y de cada álbum**, con las mismas secciones que en Android.
- **Listas de canciones online.** Al reproducir desde un artista o un álbum, la cola sigue sola con la siguiente canción y los botones de siguiente y anterior funcionan.
- **Cola online visible y editable.** El panel muestra la lista que está sonando, incluida la reproducción automática. Permite saltar, arrastrar, quitar entradas y limpiar conservando la actual. Aleatorio y repetición funcionan también con la lista online, y una respuesta de audio tardía no sustituye una canción elegida después.
- **Estadísticas de escucha.** Tiempo real escuchado y canciones y playlists más reproducidas. Las pausas, la carga y los saltos de la barra no suman tiempo; el último tramo de escucha se guarda al cerrar.

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
