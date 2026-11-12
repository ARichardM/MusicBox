# Historial de versiones

## 2.4.5 — 12 de noviembre de 2026

### Windows y Android

- Configuración permite elegir azul o naranjo como color primario, conservándolo al cambiar entre tema claro y oscuro y sincronizando la preferencia con la cuenta.
- Las playlists se pueden exportar como CSV con título, artista y álbum para compartirlas e importarlas.

### Android

- Un swipe corto hacia arriba sobre el minirreproductor abre el reproductor al soltar, incluso sobre el botón de pausa, sin pulsarlo ni cambiar de canción.
- Se mantienen las correcciones de las tarjetas de Inicio de la versión 2.4.4.

## 2.4.4 — 12 de noviembre de 2026

### Android

- Corregimos las secciones vacías de Inicio cuando un refresco interrumpe la aparición de sus tarjetas.
- Verificación en Samsung de playlists, búsqueda, letras, sugerencias, reproducción automática y descargas en segundo plano.
- Ajustamos la sincronización de las pruebas de gestos con las animaciones y la carga de biblioteca.

## 2.4.3 — 11 de noviembre de 2026

### Windows y Android

- Favoritos aparece en la biblioteca al guardar la primera canción. Mantén pulsada una playlist y arrástrala para ordenarla. Desde su menú, «Fijar en Inicio» la agrega a una fila compacta encima de las recomendaciones.
- Las canciones de «Para esta playlist» responden con un rebote suave al tocarlas. El carrusel mantiene el desplazamiento horizontal por arrastre.
- Las letras siguen la posición del reproductor durante la reproducción, la pausa y los saltos de tiempo. Si la letra disponible corresponde a otra duración o versión, se muestra como texto cuando hay una coincidencia de título y artista.
- Los controles de Letra y MP4 aparecen cuando el contenido correspondiente está disponible. Android oculta el selector completo cuando solo queda el modo de audio.

### Android

- Se corrigió el cierre al hacer scroll y cargar más resultados de búsqueda. Los resultados y el indicador de carga se muestran sin superponerse con Inicio.
- Las novedades del aviso de actualización se desplazan dentro del panel. Al arrastrar hacia abajo, el panel empieza a bajar cuando el texto llega al inicio.

## 2.4.2 — 8 de noviembre de 2026

- La cuenta guarda el idioma, el tema, el formato y las calidades de descarga. Las carpetas se recuerdan por plataforma.
- Letras sincronizadas en el reproductor de Windows y Android: lee archivos `.lrc`, consulta LRCLIB y usa las letras oficiales de YouTube Music como respaldo, sigue el verso actual, permite saltar a cualquier línea al tocarla y limpia las marcas de tiempo por palabra del LRC extendido.
- Las descargas automáticas de playlists respetan el formato MP3 o MP4 elegido. En Android, las filas muestran formato y origen, y las listas largas tienen desplazamiento propio.
- Android reconoce los MP4 de MusicBox en la biblioteca y puede recuperar el video asociado a una canción local al cambiar al modo video.
- Windows conserva la sesión ante errores de red, protege cambios simultáneos al desconectar dispositivos y limpia las actualizaciones canceladas.
- Android: al importar una playlist por enlace se abre una hoja de revisión con portada, título, artista y duración de cada canción, con opciones para escucharla, ver alternativas, descartarla o restaurarla. Las canciones de Spotify, Deezer, Apple Music y SoundCloud se buscan primero en YouTube Music y ninguna se pierde: las dudosas quedan marcadas «Por revisar». Las playlists de YouTube ya incluyen videos y clips.
- Android: la playlist importada aparece de inmediato en la biblioteca, con animación de rebote.
- Android: el reproductor muestra la portada cuadrada de YouTube Music con bordes redondeados; en MP4 reproduce el videoclip real de la canción, a todo el ancho, y al tocarlo pasa a pantalla completa con fundido, como YouTube Music. Los resultados de búsqueda usan el mismo marco cuadrado.
- Android: se arreglan los cortes de streaming y las descargas que quedaban vacías en algunos videos (más reintentos con enlace nuevo, clientes por defecto de yt-dlp, tamaño exacto del archivo y descarte de archivos falsos que entrega YouTube), y los anillos de descarga giran desde que la descarga espera turno.
- Android: volver desde el borde sigue a la derecha al cerrar las pantallas secundarias, sin interferir con el deslizar entre canciones del reproductor ni con el cambio de playlist, y los gestos entre pestañas y playlists responden más rápido.

## 2.4.1 — 5 de noviembre de 2026

### Windows y Android

- «Importar playlists» visible en Mi música, con iconos de Spotify, YouTube, Deezer, Apple Music y SoundCloud e instrucciones para importar una playlist pública por enlace.
- «Mover a otra playlist» permite elegir el destino sin duplicar la canción, borrar archivos ni afectar las demás playlists.

### Windows

- Los campos de búsqueda ocultan su contorno en reposo y resaltan una sola tarjeta redondeada al escribir, sin marcos rectangulares interiores.
- Al cambiar entre Canciones, Playlists y Carpetas se limpian los filtros de la vista anterior, evitando que la biblioteca parezca vacía.
- Las tarjetas de playlists cuentan las canciones que pueden mostrar, sin duplicados ni referencias a archivos ausentes. Favoritos vacío explica cómo guardar canciones con el corazón.
- Los indicadores de carpeta, descarga y progreso aparecen junto al artista. El indicador de carga de reproducción conserva su ubicación.
- Las recomendaciones separan los botones del texto y ofrecen un menú de tres puntos con la opción de descarga.
- «Información de la canción» en los menús de canciones, recomendaciones y reproductor.
- Bordes interiores redondeados y unión continua entre la barra lateral y el reproductor. Controles de reproducción, progreso y volumen blancos en el tema oscuro.

### Android

- Compartir una canción desde Shazam abre el buscador con el título y el artista, en lugar de pegar el enlace en Descargas.
- Deslizar desde el borde izquierdo permite volver en las pantallas secundarias y salir de una playlist o carpeta abierta, respetando los controles y la navegación de la app.

## 2.4.0 — 5 de noviembre de 2026

### Windows y Android

- Importar una playlist desde un enlace: pega o abre el enlace de una playlist de Spotify, YouTube o YouTube Music, Deezer, Apple Music o SoundCloud y MusicBox crea la playlist completa. Las de YouTube llegan con sus videos; las de las demás plataformas se buscan en YouTube Music y solo se agregan las coincidencias seguras, así no entran versiones equivocadas. Muestra el avance real, deja elegir el nombre, se puede detener sin perder lo ya encontrado e indica cuántas canciones no tuvieron una coincidencia segura. Las listas de Spotify son las que muestra su página pública, por lo que una lista muy larga puede salir incompleta.
- «Ver alternativas» en el menú de tres puntos de cada canción: otras versiones de la misma canción (la original, en vivo, otra subida). Cada una se escucha sin tocar la cola, con la reproducción principal en pausa mientras tanto, y puede reemplazar a la actual en todas tus playlists y en la cola, en la misma posición y sin borrar ningún archivo. Si la canción no está en ninguna playlist, el botón la guarda en la que elijas.
- El Inicio ya no aparece vacío la primera vez: muestra esqueletos con una ola de brillo continua que reproducen las secciones reales (Para ti, artistas, colecciones, recientes y más escuchadas) y el contenido entra con un fundido suave. Solo aparece cuando todavía no hay nada que mostrar.
- Las sugerencias de una playlist cargan plegadas y se despliegan con una animación suave cuando terminan de llegar, respetando si las habías plegado tú.
- La cola muestra la siguiente canción con su miniatura, el artista y la duración.
- El selector MP3 / MP4 funciona de verdad: antes el reproductor pedía siempre solo audio y la opción de video casi nunca aparecía. Ahora, al elegir MP4, una canción de YouTube pasa a un flujo con imagen y sonido, sigue desde el mismo segundo y en el mismo estado (sonando o en pausa); si ese video no ofrece un MP4 con sonido, vuelve al audio y lo avisa. En Windows hay un botón de video en el reproductor y un panel flotante con la imagen.

### Android

- Las portadas de las colecciones más escuchadas vuelven a verse: el mosaico se coloca cuando ya se conoce su tamaño, sin depender del orden de la pantalla.
- En la cola del reproductor, la pista de arrastre y la reproducción automática solo aparecen al abrir la hoja, con un fundido corto y sin quedar apagadas a medio camino; la vista compacta se ajustó a su contenido.
- Nuevo ícono con fondo negro y el cubo centrado, como ícono adaptable.
- Se pueden compartir o abrir con MusicBox los enlaces de playlists de Spotify, YouTube Music, Deezer, Apple Music y SoundCloud.

## 2.3.9 — 4 de noviembre de 2026

### Windows y Android

- Al abrir una playlist aparece arriba una tarjeta compacta, «Para esta playlist», con un carrusel de canciones sugeridas a partir de las de esa misma playlist, sin repetir las que ya tiene. Pasa sola cada pocos segundos, se desliza con el dedo o el mouse, tiene un indicador de puntos, se puede plegar y actualizar, y el botón + agrega la canción a la playlist con una animación. Se oculta con búsqueda o filtro de artista activos.
- Las sugerencias se guardan por playlist durante 6 horas y, sin conexión, se muestran las últimas guardadas.

### Windows

- Las portadas de las playlists vuelven a aparecer: ahora se buscan en el archivo local, en los datos guardados o en la miniatura de YouTube, igual que la lista de canciones.
- Los cuadros de diálogo ya no oscurecen el fondo con un velo gris claro: usan el mismo velo oscuro del resto de la aplicación.
- La biblioteca tiene ordenamiento, como en Android: orden original, título, artista, más recientes, más escuchadas y duración. Un segundo clic invierte el sentido y la elección se recuerda.

### Android

- La pestaña Artistas de la búsqueda ya no queda en negro después de limpiar o reiniciar la búsqueda: la lista de artistas dejaba de mostrarse aunque el contador indicara resultados.

## 2.3.8 — 1 de noviembre de 2026

### Windows y Android

- La reproducción automática ya no se activa dentro de una playlist: una canción sin descargar sigue con la siguiente de la playlist y no con sugeridas. La cola de «Para ti» se completa aunque la canción de partida sea un video.
- La música en streaming empieza antes: la app prepara la canción actual y la siguiente en segundo plano, resuelve los primeros resultados de una búsqueda mientras se miran y ya no comprueba el enlace antes de reproducir. La actualización diaria de yt-dlp ya no retrasa la primera canción.
- El anillo de descarga muestra el avance real y es compacto: ocupa el lugar de la marca de descargada y se cruza con ella al terminar, sin mover el texto.
- La búsqueda usa el catálogo de YouTube Music con las pestañas Todos, Canciones, Videos y Artistas, y carga más resultados con un spinner al final y una entrada fluida. Ya no aparecen gameplays al buscar un artista.
- «Información de la canción» en el menú de tres puntos: datos de la app (reproducciones, favorita, playlists, estado) y del archivo u origen (formato, tamaño, calidad, enlace de YouTube).
- El botón «Continuar con Google» usa el logo de Google.
- Las canciones recién agregadas a una playlist se destacan con una animación al abrirla.

### Windows

- Los avisos se cierran solos: 4,5 s los informativos y 7 s los errores; pasar el puntero los pausa, con un tope de 20 s.
- El icono anclado de la barra de tareas abre la ventana en el mismo botón, sin un botón aparte sin icono.

### Android

- Compatibilidad con Android 15 y posteriores: todas las pantallas dejan espacio para las barras del sistema, de modo que botones como el corazón y el reproductor responden en dispositivos Xiaomi y similares.
- La rotación de pantalla vuelve en tablets; los teléfonos se quedan en vertical.
- El splash tiene la misma onda animada y el mismo brillo que el de escritorio.
- La barra de progreso del reproductor mini queda dentro de la tarjeta; el icono de reproducir ya no desaparece al terminar de cargar.
- Una reproducción se cuenta tras 30 segundos de escucha real; el menú de playlists usa el icono de reproducción; Ajustes muestra la foto de la cuenta y permite elegir la calidad de descarga (MP3 128/192/320 kbps, MP4 480p/720p/1080p).

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
