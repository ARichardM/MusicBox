# Inventario visual y de controles: Web frente a Windows y Android

Etapa 1 del plan de paridad. Regla: cada control de la web debe existir en la app de referencia (Windows en PC, Android en móvil) y aparecer en el mismo contexto.

## Cómo se obtuvo y qué no cubre

- **Windows:** controles leídos del código QML (`apps/desktop/ui/Main.qml` y componentes). Las capturas se generaron cargando el QML real sin pantalla; en ese modo **los iconos SVG y las listas con datos no se dibujan**, por lo que las capturas de Windows sirven para la estructura, no para comparar iconos ni espaciado.
- **Web:** controles leídos de `index.html` y de los scripts, más capturas con `tests/web/parity_shots.js` a 1180 × 720 y las capturas móviles ya existentes.
- **Android:** controles leídos de `res/layout`, `res/menu`, `strings.xml` y etiquetas del código Java. **No se vio ninguna pantalla** (no hay dispositivo conectado).
- **Pendiente de comparar a fondo:** reproductor expandido, ajustes, cola, letras, diálogos de importación y la pantalla Descargas. Aquí solo hay hallazgos puntuales.
- Todo está sin commitear en `main`; parte de lo que se ve en la web es trabajo en curso de Codex.

## Navegación

| Elemento | Windows | Web PC | Android | Web móvil | Resultado |
| --- | --- | --- | --- | --- | --- |
| Entradas | Inicio, Mi música, Descargas, Historial | Igual | Barra inferior de 2 pestañas: Buscar y Mi música | Barra flotante de 2 pestañas: Inicio y Música | Coincide |
| Ajustes | Botón con avatar y «Configuración», solo con sesión iniciada | Igual | Botón de cuenta | Botón de cuenta arriba a la derecha | Coincide |
| Rótulo de marca | «MusicBox · Escritorio» | «MusicBox · Web» | n/d | n/d | Distinto a propósito |
| Panel lateral | Botón contraer/expandir | Igual | n/d | n/d | Coincide |

## Menú de una canción

Windows reúne en un menú: Descargar canción (solo canciones web no descargadas), Reproducir a continuación, Agregar a la cola, Agregar/Quitar de favoritas, Agregar a playlist, Mover a otra playlist, Ver alternativas, Información de la canción, Mostrar en carpeta, Quitar de esta playlist y Eliminar del dispositivo. El menú «Más opciones» del reproductor añade Crossfade, Pausar/Reproducir y Ver/Ocultar letra.

Estado tras el ajuste del 17/11/2026:

| Acción | Windows | Web | Estado |
| --- | --- | --- | --- |
| Reproducir / Pausar | Solo en el menú del reproductor | Solo en el menú del reproductor (antes salía también en las filas) | **Corregido** |
| Crossfade | Solo en el menú del reproductor | Solo en el menú del reproductor (ya era así) | Coincide |
| Mover a otra playlist | Sí, dentro de una playlist | Sí, dentro de una playlist; el selector no ofrece la playlist de origen y avisa si no hay otra | **Añadido** |
| Eliminar del dispositivo | Sí, con confirmación | Sí, con confirmación, solo si hay copia guardada en el navegador. Una canción en línea conserva sus playlists; un archivo importado se quita también de ellas | **Añadido** |
| Guardar archivo | No existe | `ctx-save-file` | Excepción web pendiente de decidir: es la única forma de sacar un archivo del navegador |
| Mostrar en carpeta | Sí | No aplica en el navegador | Se mantiene ausente |
| Favorita, Agregar a playlist, Cola, A continuación, Alternativas, Información, Quitar de esta playlist, Descargar | Sí | Sí | Coincide |
| Ver letra / Pausar en el menú del reproductor | Sí | **No verificado** | Revisar |

Android no se pudo comparar pantalla a pantalla. Por las etiquetas del código ofrece, además, Compartir música, Descargar Video MP4 y Descargar en carpeta; que aparezcan en Windows o la web no está confirmado.

## Reproductor compacto

| Control | Windows | Web | Qué hacer |
| --- | --- | --- | --- |
| Aleatorio, Anterior, Reproducir/Pausar, Siguiente, Repetición | Sí | Sí | Coincide |
| Video (MP3/MP4), Letra, Cola, Más opciones, Ampliar | Sí | Sí | Coincide |
| Favorito en la barra | **Sí está**, a la izquierda del título (confirmado con una captura real de Windows 2.4.6). Mi lectura del código se equivocó | Corazón a la izquierda | Coincide; no quitarlo |

En la vista ampliada de Windows el orden es: favorito y cola a la izquierda, transporte al centro, y video, más opciones y volumen a la derecha. La web mueve esos controles al ampliar, pero esa disposición no se comparó en detalle.
| Icono de silencio junto al volumen | No visible en la captura (iconos sin dibujar) | Sí | **No verificado** |
| `mini-more` (Más opciones duplicado) | n/d | Oculto en PC; visible en pantallas ≤ 680 px | Revisar contra el minirreproductor de Android |

## Pantallas

- **Inicio (Windows y web):** misma estructura (título, barra de búsqueda con «Canción, artista o enlace» y botón Buscar, «Para ti»). La web muestra además un botón de recargar junto a «Para ti» y tarjetas con corazón y tres puntos; en Windows la captura sin iconos no permite confirmarlo.
- **Playlist abierta:** Windows muestra «PLAYLIST», título y cantidad, búsqueda, pestañas Canciones/Playlists/Carpetas, Orden y Auto-descarga. La web tiene todo eso y añade, arriba a la derecha, anterior/siguiente playlist, localizar, actualizar, más opciones y aleatorio, un bloque «Para esta playlist» con sugerencias y el botón de volver. Esos controles existen en Windows como `prevPlaybackPlaylistButton`, `nextPlaybackPlaylistButton`, «Ir a la canción actual», «Actualizar biblioteca», «Reproducir visible en aleatorio» y «Opciones de playlist», pero su ubicación exacta no pudo compararse.
- **Ajustes:** la web incluye «Ver página de presentación (Landing)», «Subir cookies.txt» y «Borrar descargas». Landing no tiene equivalente en Windows. En Windows hay un icono de cookies en la página Descargas, así que cookies probablemente sea válido, pero hay que confirmar dónde va.

## Lista de cambios propuestos (por prioridad)

1. ~~Quitar Reproducir de las filas~~ Hecho: solo aparece en el menú del reproductor.
2. ~~Crossfade solo en el reproductor~~ Ya era así; el inventario inicial se equivocó.
3. ~~Añadir «Mover a otra playlist» y «Eliminar del dispositivo»~~ Hecho.
4. ~~Quitar el corazón de la barra compacta de PC~~ Descartado: Windows sí lo tiene.
5. Decidir si «Guardar archivo» y «Landing» se mantienen como excepciones web.
6. Comparar reproductor expandido, ajustes, cola, letras y Descargas con capturas reales de Windows y Android.
