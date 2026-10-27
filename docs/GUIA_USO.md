# Guía de MusicBox y Descargador

[Volver al README](../README.md)

Primera publicación: **1.0.0**, etiqueta Git **v1.0**. Consulta [CHANGELOG.md](../CHANGELOG.md)
para conocer las funciones incluidas y los límites conocidos.

## Preparación en Windows

1. Instala Python (se recomienda **3.11 o superior**; la versión local también
   funciona con 3.10, aunque yt-dlp avisa de su próxima retirada de soporte).
2. Descarga o clona este repositorio.
3. Instala [FFmpeg](https://ffmpeg.org/download.html) y pon `ffmpeg.exe` junto a
   `descargador_gui.py`, o añade FFmpeg al PATH. El binario no se incluye en Git.
4. Para escuchar dentro del programa, Windows necesita
   [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/).
5. Abre `iniciar_descargador.bat`; preparará el entorno y las dependencias Python.

No se publican cookies, credenciales, bibliotecas musicales ni archivos descargados.
Si necesitas cookies, selecciónalas localmente en Opciones avanzadas y no las compartas.
Descarga únicamente contenido que tengas derecho a guardar y respeta las condiciones
del servicio y las restricciones del contenido.

En Windows, abre **iniciar_descargador.bat** con doble clic. La primera vez prepara
un entorno `.venv` e instala las dependencias. Requiere Python 3.10 o superior.

Puedes abrir la aplicación desde el acceso directo **Descargador** del escritorio,
con su nuevo icono de música y descarga. Para crearlo con doble clic, abre
**crear_acceso_directo.bat**. Prepara las dependencias sin abrir la aplicación y
crea los accesos del escritorio y de la carpeta. Si mueves el proyecto, ejecútalo
otra vez. También puedes hacerlo manualmente desde esta carpeta:

```powershell
.\.venv\Scripts\python.exe crear_acceso_directo.py
```

También se crea **ABRIR DESCARGADOR** dentro de la carpeta del proyecto, junto al
archivo `.bat`. Los accesos directos muestran el logo y abren directamente la
interfaz sin una consola; el `.bat` conserva el icono genérico de Windows. Los recursos del
logo están en `apps/desktop/assets/` (SVG, PNG e ICO); `apps/desktop/generar_logo.py` permite regenerarlos.

La interfaz organiza los enlaces y el progreso a la izquierda, y las opciones a la
derecha. Los botones de descarga y cancelación permanecen visibles si reduces la
ventana; el panel de opciones permite desplazarse con la rueda del ratón.
**Opciones avanzadas** muestra el campo de cookies. La pestaña **Archivos** permite
abrir los resultados con doble clic; **Registro** contiene los detalles técnicos.
Los errores de entrada se muestran en la propia ventana.

La pestaña **Historial** conserva las nuevas descargas terminadas entre sesiones.
Muestra fecha, título, artista cuando está disponible, formato y si el archivo se
guardó o ya existía. Permite buscar por nombre, artista o ruta, abrir el archivo o
su carpeta y volver a añadir su enlace a la cola. Si mueves o borras un archivo,
su registro se conserva y se indica que ya no está en esa ubicación al abrirlo.
Pulsa **Ampliar** para consultar el historial en una ventana más grande.
Los errores de descarga siguen en **Registro**.

El historial se guarda localmente en `datos/historial.sqlite3`, excluido de Git.
Cada archivo se registra al terminar, incluso si cancelas después el resto de la
cola. La lectura se hace en segundo plano y por páginas de 100 registros. Las
descargas funcionan igualmente si no se puede escribir el historial; el registro
muestra el aviso.

Atajos: **Ctrl + Enter** para descargar y **Ctrl + L** para ir a los enlaces.
Al pegar un enlace directo a una playlist de YouTube, el modo de lista se selecciona
automáticamente. Una selección manual del modo tiene prioridad; los enlaces de
videos que incluyen una lista mantienen el modo individual por defecto.

## Buscar con portadas y escuchar dentro del programa

En la barra **Buscar videos**, escribe un título o artista y pulsa **Buscar con
portadas** (también funciona Enter). Se abre una búsqueda de hasta 10 resultados,
con miniatura, título, canal y duración. No consulta YouTube en cada pulsación:
solo al pulsar Buscar. La búsqueda y las portadas se cargan en segundo plano y
la consulta se puede cancelar. Una portada que falle muestra un marcador sin
impedir elegir o reproducir el resultado.

**Escuchar aquí** abre una ventana del propio programa con el reproductor oficial
de YouTube. Permite pausar, ajustar el volumen y adelantar sin crear un MP3; usa
Internet y el búfer temporal normal de una reproducción en streaming. Si no
comienza automáticamente, pulsa ▶ en el reproductor. Al elegir otro video se
cierra la reproducción anterior; al cerrar el buscador también se cierra su
reproductor. No se importan las cookies del descargador ni las de tu navegador.

En Windows utiliza **pywebview** y **Microsoft Edge WebView2 Runtime**. El iniciador
instala la dependencia Python; si falta WebView2 o YouTube rechaza la reproducción
integrada, se muestra el motivo. Los videos privados, restringidos o cuyo propietario
no permite reproducción incrustada pueden no funcionar. **Abrir YouTube ↗** es una
alternativa opcional y solo abre el navegador cuando tú la pulsas. La reproducción
integrada no evita bloqueos, restricciones de YouTube ni verificaciones de sesión.

Marca **Elegir para descargar** en los resultados que quieras y pulsa **Añadir
elegidos a la cola**. Esto no inicia ninguna descarga: en la ventana principal
eliges MP3/MP4, calidad y destino y pulsas Descargar. Los fallos de búsqueda o
bloqueos 429 se muestran con su motivo; no se anuncia éxito si no hubo resultados.

Referencias del reproductor: [API de pywebview](https://pywebview.flowrl.com/api/)
y [reproductor oficial de YouTube](https://developers.google.com/youtube/iframe_api_reference).

## Importar canciones desde fotos o texto

Pulsa **Imágenes y nombres…** en la parte superior de la ventana.

1. **Adjuntar imágenes…** admite varias fotos o capturas PNG, JPG, WebP, BMP y TIFF.
   **Pegar imagen** admite capturas del portapapeles y archivos de imagen copiados.
   También puedes usar Ctrl + V cuando el foco no está en el editor de nombres.
2. Selecciona una imagen en la lista. Arrastra sobre la vista previa y pulsa
   **Recortar selección**. Puedes volver a recortar o usar **Restaurar** para
   recuperar la imagen completa. **Quitar imagen** y **Quitar todas** solo retiran
   las copias del programa; no borran archivos de tu equipo.
3. Pulsa **Leer imágenes → texto**. El OCR distingue títulos y subtítulos por su
   posición, omite duraciones y agrupa el artista con el título en listas de canciones.
   Si detecta tiempos a la derecha, repite la lectura sobre la columna de nombres
   para reducir el texto capturado de las portadas. El OCR se ejecuta localmente con RapidOCR,
   en un proceso separado y cancelable. Los recortes originales permanecen en
   memoria; las copias temporales de lectura se eliminan al terminar la operación.
   Las imágenes no se envían a un servicio de reconocimiento.
4. Revisa el texto: una canción por línea, preferiblemente `Artista - Canción`.
   También puedes pegar directamente una lista copiada con bloques de **título,
   artista o canal, álbum opcional y duración**: se agrupa automáticamente en una
   fila por entrada. Las duraciones van en su propia columna y los álbumes no se
   cuentan como canciones. Los títulos bilingües permanecen juntos. Se conservan
   los registros repetidos; una búsqueda idéntica se consulta una sola vez.
   Las colaboraciones separadas por comas o `&` se agrupan incluso si los nombres
   ocupan varias líneas. Los bloques que no se puedan interpretar se conservan
   completos en una sola fila **Revisar bloque**, sin marcar. Su texto original y
   el motivo aparecen en **Informe y pendientes**; corrige la fila antes de buscarla.
   Quita encabezados y otras líneas que no sean canciones. **Limpiar texto de lista**
   organiza texto ya pegado y elimina duraciones; **Ctrl + Z** deshace esa limpieza.
   Las líneas ambiguas y los títulos cortados necesitan revisión. Puedes escribir o pegar
   nombres directamente, sin adjuntar una imagen. **OCR original** conserva aparte
   la lectura sin organizar, para compararla con el texto y la tabla. Repetir el OCR añade texto sin
   duplicados y conserva tus correcciones; retirar una foto no elimina el texto.
5. La pestaña **Tabla de canciones** se arma automáticamente. Muestra número,
   artista o canal, título, duración si estaba en el texto, origen y estado. Marca o desmarca las canciones con
   las casillas, corrige artista o título con doble clic, añade o elimina filas y
   ordena pulsando los encabezados. En videos, **Artista / canal** muestra el canal
   con la indicación `(canal)` cuando no se conoce el intérprete; ese nombre no se
   añade como artista a la búsqueda. Los créditos explícitos del título, como
   `Hitamuki by SUPER BEAVER` o `Bokurano by Eve`, completan el artista y conservan
   el título original de la versión. El canal también se conserva al leer fotos.
   Si faltan ambos datos o el título está cortado,
   la fila se destaca para revisión; puedes buscar solo por título si lo prefieres.
   Las correcciones se reflejan también en el texto organizado. Editar la tabla
   invalida las coincidencias anteriores para que no se añada una versión desactualizada.
   **Buscar en la lista** filtra al escribir por artista o título, sin distinguir
   mayúsculas ni acentos latinos. **Limpiar** o Escape vuelve a mostrar todo.
   El filtro no borra filas ni cambia las marcas: la búsqueda en YouTube incluye
   todas las canciones marcadas, aunque estén ocultas. **Marcar todas** y
   **Desmarcar** también se aplican a la lista completa.
6. **Buscar canciones marcadas →** consulta YouTube solo por las filas marcadas y muestra hasta
   tres resultados por canción, con título, canal y duración. Esta búsqueda sí
   necesita Internet. Si la primera consulta queda vacía, prueba hasta dos variantes
   del mismo nombre: por ejemplo, `SPYAIR Sakuramitsutsuki` y `SPYAIR サクラミツツキ`
   para un título bilingüe. Conserva el nombre original, el orden y las elecciones
   previas. Si los primeros resultados fueron descartados por restricciones o por
   ser emisiones en directo, amplía el número de resultados una sola vez.
   **Informe y pendientes** muestra las consultas alternativas y sus resultados.
   Una búsqueda automática vacía no confirma que la canción no exista en YouTube;
   la web puede resolver el mismo texto de otra manera. Los fallos de conexión o de
   la herramienta se registran como errores.
   Solo se marca automáticamente una coincidencia suficientemente parecida y
   compatible con el artista, la versión y la duración disponibles. Si no se
   puede verificar, queda **Revisar · sin marcar**, con el motivo en el informe.
   La columna **Elección** indica **Auto · exacta** o **Auto · similar** cuando se elige;
   es una propuesta, no una garantía de identificar la grabación. Las demás opciones
   siguen visibles. Haz doble clic para cambiar la elección; otro doble clic en el
   mismo resultado la quita. A la derecha de cada resultado, **Escucha rápida · Audio**
   permite escuchar sin abrir video: pulsa **▶** para iniciar, **Ⅱ** para pausar y
   arrastra la barra para adelantar o retroceder. Muestra el tiempo transcurrido y total.
   Solo suena una coincidencia a la vez; puedes seguir buscando y filtrando mientras
   escuchas. Usa FFmpeg y sounddevice, con un búfer pequeño en memoria, sin guardar
   archivos ni añadir entradas al historial de descargas. La conexión y la disponibilidad
   de audio en YouTube pueden afectar al inicio de la escucha.
   El reproductor de video se conserva: selecciona una fila de resultado y pulsa
   **▶ Reproducir** para escucharla en una ventana del programa, incluso mientras
   continúa la búsqueda. El reproductor incluye pausa, tiempo transcurrido y total,
   una barra para adelantar o retroceder y botones **−10 s / +10 s**. La barra aplica
   el salto al soltarla, sin pedir nuevos fragmentos en cada movimiento.
   **Detener** cierra la escucha; iniciar otro resultado o pasar entre audio y video
   detiene primero la reproducción anterior.
   Escuchar no cambia las canciones elegidas ni las añade a descargas.
   **Ver en YouTube** abre el navegador como alternativa si el video rechaza la
   reproducción integrada. Al cerrar el importador también se detiene su reproductor.
   Aquí también puedes filtrar por canción o canal; se mantienen juntas todas las
   alternativas de cada canción y se conservan las elecciones ocultas por el filtro.
7. **Añadir elegidas a descargas** incorpora solo los enlaces marcados tras tu revisión.
   Ya en la ventana principal, elige MP3 o MP4 y pulsa **Descargar**.
   Si varias elecciones apuntan al mismo video, se muestran los títulos y el enlace
   compartido y se pide confirmación antes de añadir los videos únicos. Puedes
   rechazar para revisar las coincidencias, sin perder las elecciones. El mismo
   video no se descarga dos veces. Los enlaces que ya estaban en cola tampoco se
   vuelven a añadir: se informa cuántos son nuevos y cuántos ya estaban presentes.
   La pestaña **Informe de canciones** de la ventana principal conserva los motivos,
   los títulos que compartían enlace y el recuento de transferencia, incluso después
   de cerrar el importador. Esto distingue elecciones de canciones, videos únicos
   y archivos realmente descargados; añadir a la cola todavía no inicia la descarga.

### Pendientes, errores y entradas no disponibles

La pestaña **Informe y pendientes** conserva el nombre y motivo de cada consulta
sin resultados, error o consulta no completada. Al terminar con pendientes, se abre
automáticamente y el resumen indica cuántas consultas se hicieron, cuántas quedaron
elegidas y cuántas faltan. Una búsqueda sin resultados no garantiza que la canción
no exista; puedes corregir el nombre o usar **Reintentar pendientes**. El reintento
consulta solo las que no devolvieron alternativas, conserva las elecciones anteriores
y también funciona tras cancelar. Si quitaste una elección manualmente, vuelve a
elegirla en **Coincidencias**; no se repite su consulta innecesariamente.

En **Tabla de canciones → Playlist original (opcional)** pega el enlace de YouTube
o YouTube Music y pulsa **Comprobar**, o déjalo escrito al iniciar la búsqueda.
Esta consulta no descarga nada ni cambia las elecciones. El informe muestra el
total de entradas y las no disponibles, con posición, enlace y motivo conocido.
Si YouTube no publica un título o motivo, se indica expresamente; no se inventa.
También se advierte si la lista recibida está incompleta. Los recuentos de entradas
no disponibles de la playlist y de búsquedas pendientes se muestran por separado.
Sin el enlace original, el texto copiado no permite detectar entradas ocultas.

**Copiar informe** y **Guardar informe…** permiten conservar el detalle antes de
cerrar el programa. El guardado es un archivo de texto UTF-8 en la ubicación elegida;
la sesión no se recupera automáticamente al reiniciar.

El texto de una foto no garantiza identificar una grabación exacta: revisa las
coincidencias para distinguir versiones oficiales, en vivo y covers. Si editas los
nombres después de una búsqueda, debes volver a buscar. El programa permite hasta
20 imágenes, 40 megapíxeles en total y 500 consultas por búsqueda. Las consultas se
procesan secuencialmente y puedes cancelarlas; una lista larga puede tardar varios
minutos. Las imágenes individuales
pueden tener hasta 25 megapíxeles. La carga, el OCR y las búsquedas se pueden cancelar.

Referencias de implementación: [RapidOCR](https://rapidai.github.io/RapidOCRDocs/main/en/quickstart/)
y [portapapeles de Pillow](https://pillow.readthedocs.io/en/stable/reference/ImageGrab.html).

1. Pega uno o varios enlaces, uno por línea.
2. Elige **Video individual** para descargar solo el video de cada enlace, incluso
   si el enlace de YouTube incluye una lista. Para una lista completa selecciona
   **Playlist completa / rango**; puedes dejar el rango vacío o indicar `1-10`,
   `1,3,5-8`, etc.
3. Elige MP3 (128, 192, 256 o 320 kbps) o MP4 (hasta 360p, 480p, 720p, 1080p o la
   mejor resolución disponible). La calidad final depende del material original;
   una tasa MP3 mayor no mejora una fuente de baja calidad. MP4 selecciona pistas
   MP4/M4A compatibles disponibles; si el sitio no las ofrece, el registro lo indica.
4. Elige una carpeta y pulsa **Descargar**. Por defecto usa `Descargas` junto al script.

La ventana muestra el progreso de cada pista descargada, velocidad, tiempo restante,
archivos listos y registro de errores. El contador aumenta después de terminar la
conversión/unión. Una descarga MP4 puede tener una fase de video y otra de audio.
Las playlists se guardan en subcarpetas con su nombre y llevan número de posición.
Los nombres incluyen el ID para distinguir videos con títulos iguales.

Las canciones de un texto mantienen el orden de sus filas; las fotos se leen en
el orden de la lista de imágenes y de arriba abajo dentro de cada imagen. Revisa
ese orden en la tabla antes de buscar. Las elecciones se añaden a la cola en ese
orden, aunque elijas o cambies los resultados en otro orden. Si ya había enlaces
en la cola, los nuevos se añaden al final; los enlaces repetidos se guardan una
sola vez.

Al descargar varios enlaces individuales, los nombres llevan prefijos `001 -`,
`002 -`, etc., según su posición en la cola. Así se mantiene el orden al ordenar
los archivos por nombre, tanto en MP3 como en MP4. Si falla una canción, se conserva
su hueco en la numeración. Las playlists mantienen su propia numeración.

Los **MP3 incluyen automáticamente los metadatos disponibles**: título de la
canción, artista, álbum, artista del álbum, número de pista y disco, género,
compositor, año, descripción y enlace de origen. Se incrusta la imagen disponible
como portada dentro del MP3, sin dejar una imagen aparte. Si la fuente solo ofrece
la miniatura del video, esa será la portada; si no ofrece imágenes, se guarda el
audio igualmente. Las imágenes WebP se convierten automáticamente para incrustarlas.

La información depende de lo que publique la fuente: puede faltar el álbum o el
artista, y yt-dlp puede usar el nombre del canal cuando no hay un artista musical
identificado. El año puede corresponder a la publicación del video. No se buscan
datos adicionales en servicios externos. Esto se aplica a las nuevas descargas MP3;
los archivos existentes conservan su contenido.

**Cancelar** detiene también FFmpeg. Conserva los archivos terminados y los parciales
para permitir reintentos cuando sea posible; no garantiza reanudar conversiones
interrumpidas. Los archivos finales existentes no se sobrescriben. Una lista puede
continuar aunque algún video esté privado, eliminado o falle.

El programa utiliza el `ffmpeg.exe` de esta carpeta, o FFmpeg del PATH. `ffprobe.exe`
junto a FFmpeg es recomendable para operaciones adicionales de análisis, aunque la
extracción básica de audio funciona con FFmpeg. Para YouTube se instala el componente
`yt-dlp-ejs` y se habilitan automáticamente Node.js o Deno si están en el PATH.

Si un sitio necesita iniciar sesión, puedes seleccionar opcionalmente un archivo
de cookies en formato Netscape. El archivo existente `cookies.txt` no se carga
automáticamente. Para quitarlo de la selección, borra el campo.

Si YouTube cambia y las descargas dejan de funcionar, cierra el programa y ejecuta
**actualizar_descargador.bat**. La implementación usa la
[API oficial de yt-dlp](https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp).

Ejecución manual:

```powershell
.\.venv\Scripts\python.exe apps\desktop\descargador_gui.py
```

Los scripts originales se conservan para poder seguir usándolos por separado.

Pruebas locales de interfaz, descarga, conversión, playlists y cancelación:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -v
```


## Descargador en Android

Se abre desde tu **perfil → Descargador** y tiene tres pestañas.

**Descargar.** Pega los enlaces, uno por línea (**Pegar** toma el portapapeles). Elige **MP3** o **MP4** y su calidad. Con un enlace de playlist de YouTube el modo cambia solo a *Playlist completa o rango*; escribe un rango como `1-10` o `1,3,5-8` para bajar solo esas, o déjalo vacío para toda la lista. Cada playlist se guarda en su carpeta y los archivos llevan su número; en un lote de enlaces sueltos el nombre empieza con `001 -`, `002 -`… para conservar el orden. Pulsa **Descargar**: la cola sigue aunque cierres la pantalla, con una notificación y el botón **Cancelar**. Un archivo que ya existe se conserva y se marca como *Existente*; si algo falla se explica el motivo.

**Historial.** Lo descargado, más reciente primero, con búsqueda. Cada fila permite **Abrir** (se reproduce), **Compartir** y **A la cola**.

**Registro.** Lo que hizo la cola, con colores por nivel.

**Cookies.** Si un video pide iniciar sesión, carga un `cookies.txt` (formato Netscape) en *Cookies de YouTube*; se valida antes de usarse y se puede quitar.

**Buscar con portadas.** Busca videos con su miniatura, marca varios y pulsa **Añadir elegidos a la cola**: solo se agregan los enlaces, la descarga la inicias tú.

**Importar lista.** Pega una lista (una canción por línea, o la copiada de Spotify o YouTube Music), revisa la tabla, busca las coincidencias y elige las alternativas dudosas. La elección automática solo ocurre cuando coinciden el título, el artista, la versión y la duración. Al final, **Añadir a la cola** y un informe que puedes copiar o compartir.

En **Coincidencias** e **Informe**, usa **Todas**, **Elegidas**, **Por revisar** y
**Pendientes** para ver cada grupo sin recorrer toda la lista. Los contadores se
actualizan cuando eliges o desmarcas una alternativa. **Revisar pendientes** abre
directamente las coincidencias que necesitan atención. Los filtros solo cambian
lo que ves: copiar, compartir y finalizar siguen incluyendo la selección completa.
Al enlazar a una playlist, el selector muestra su portada y cantidad de canciones.

**Importar carpeta.** Elige una carpeta de tu teléfono: cada archivo se reconoce en YouTube y se enlaza a la playlist que elijas, sin descargar nada.

## Inicio, Para ti y artistas

En **Inicio**, sin escribir en la búsqueda, ves **Para ti**. Se calcula con lo que escuchas últimamente: se toman tus canciones favoritas del momento, se piden a YouTube Music las parecidas y se mezclan; una canción que aparece en varias listas sube. Son siempre **canciones de audio**, nunca videoclips ni videos subidos por otras personas. El botón de actualizar vuelve a calcularla.

La lista se calcula al abrir la app y al pulsar **Actualizar**. Reproducir canciones, sincronizar o volver desde otra pantalla no la cambia. Actualizar solicita recomendaciones nuevas incluso si hay resultados recientes en caché; si no hay conexión, se conserva la última lista disponible.

**Artistas para ti** muestra a cada artista con su foto. Al tocarlo se abre su página con sus **canciones más escuchadas**, el botón **Ver todas sus canciones**, sus **álbumes** y sus **sencillos y EPs**. Tocar un álbum abre sus pistas, y tocar una canción reproduce la lista como cola.

En Android, el minirreproductor permanece al pie de las páginas de artista y álbum.
Desde él puedes pausar, avanzar, abrir la cola o volver al reproductor completo.
Las búsquedas recientes se despliegan debajo del buscador y se ajustan al teclado.
Las tarjetas de colecciones más escuchadas mantienen la misma altura; al tocarlas
se abre la playlist o carpeta correspondiente con sus canciones.

## Cola y reproducción automática

Abre la **Cola** desde el reproductor. En Windows muestra la lista local u online que está sonando; en Android admite ambas fuentes en la misma cola. Pulsa una canción para saltar a ella, arrastra el asa para cambiar el orden o usa **×** para quitar una entrada. **Limpiar cola** conserva la canción actual. Aleatorio mezcla la lista sin cortar la reproducción y, al desactivarlo, recupera su orden; repetición permite repetir la cola o la canción actual.

El interruptor **Reproducción automática** busca canciones parecidas al llegar al final de una cola online. Excluye las que ya están en la cola y las mezclas de más de diez minutos. Necesita conexión; sin resultados, la reproducción termina. Si no quieres que la cola crezca, desactiva el interruptor. **Repetir canción** conserva la canción actual.

## Seleccionar, ordenar y eliminar en Android

En **Mi música → Canciones**, o dentro de una carpeta o playlist, mantén pulsada una canción. Toca otras filas para agregarlas a la selección. **Todas** selecciona las canciones visibles según el filtro; volver a pulsarlo las desmarca. La barra permite reproducirlas, ponerlas después de la actual, agregarlas al final de la cola o a una playlist y eliminarlas del dispositivo. Pulsa **×** o Atrás para salir de la selección. Durante la selección, la barra ocupa el espacio de la navegación y del minirreproductor.

El botón de orden permite elegir **Orden original**, **Título**, **Artista**, **Más recientes**, **Más escuchadas** o **Más cortas**. Toca otra vez el criterio activo para invertirlo. La app recuerda el criterio y el sentido al abrirla de nuevo.

**Eliminar del dispositivo** muestra una confirmación con la cantidad de canciones y el espacio que ocupan. Para los archivos que no pertenecen a MusicBox, Android puede mostrar un segundo diálogo de permiso. Cancelarlo conserva los archivos. Solo lo que se eliminó correctamente se retira de las playlists, el historial de escucha y la cola. Las canciones online no ocupan espacio local y no se borran con esta acción.

Al retirar MusicBox de **Recientes**, la música se detiene y desaparece la notificación del reproductor. La app recuerda la posición para retomarla. Las descargas tienen su propio servicio y continúan con su notificación; usa **Cancelar** si quieres detenerlas. Forzar la detención desde Ajustes, reiniciar o apagar el teléfono interrumpe el trabajo. Al volver a abrir la pantalla principal o el descargador, MusicBox recupera automáticamente los enlaces pendientes, con su formato, calidad, carpeta, rango y numeración originales. Se aprovechan los archivos parciales cuando el servidor permite reanudarlos y se conservan los archivos ya terminados. Las descargas canceladas o que terminaron con error no se reintentan automáticamente.

## Capturas y enlaces compartidos en Android

En el importador, pulsa **Leer imagen** y elige una captura o foto con tu lista. MusicBox reconoce el texto en el dispositivo con el modelo incluido, sin enviar la imagen para reconocimiento ni necesitar conexión. Revisa títulos y artistas, corrige lo necesario y pulsa **Añadir a la lista**. El texto anterior se conserva; **Cancelar** descarta el resultado. La búsqueda posterior de coincidencias en YouTube sí requiere conexión.

Desde YouTube u otra aplicación, usa **Compartir → MusicBox**. Se abre el descargador con el texto recibido, donde puedes revisar enlaces, formato y calidad antes de iniciar. Las descargas rápidas de búsqueda, biblioteca y reproductor usan la misma cola que el descargador y aparecen en su historial y registro.

## Estadísticas de escucha

En el perfil, abre **Tu música en cifras**. Muestra las reproducciones, el tiempo escuchado y las canciones y playlists más escuchadas. El tiempo solo aumenta mientras la reproducción avanza: las pausas, la espera de carga y los saltos de posición no se cuentan. Las reproducciones antiguas se conservan, pero su tiempo anterior a esta función no puede reconstruirse. La actividad de una playlist corresponde a escuchar desde ella, no simplemente a tener allí una canción.

## Sincronización y respaldos de playlists

Las playlists se guardan como datos de MusicBox; no se reconstruyen automáticamente
desde las carpetas del equipo. Desde 2.3.3, los cambios locales se combinan con los
de la cuenta usando la última versión conocida por cada dispositivo. Una copia
antigua no borra las canciones añadidas desde otro equipo. Las eliminaciones que
hiciste expresamente sí se sincronizan. Si dos dispositivos editan al mismo tiempo,
la escritura vuelve a leer el estado actual antes de guardar.

Se conservan diez instantáneas locales anteriores y un registro reciente de cambios
de playlists con el dispositivo y los contadores de canciones afectadas. Estos
respaldos no se restauran automáticamente ni pueden recuperar un historial que no
existía antes de esta versión. Conviene actualizar todos los dispositivos para
que todos usen las nuevas protecciones.
