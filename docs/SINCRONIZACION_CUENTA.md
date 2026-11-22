# Datos de la cuenta y datos del dispositivo

Android, Windows y Web utilizan el mismo documento de cuenta. Los cambios de
playlists se mezclan en una transacción y las copias antiguas no pueden borrar
adiciones que todavía no conocían.

| Información | Alcance |
| --- | --- |
| Playlists, canciones guardadas, favoritas, descripción, orden y fijadas | Cuenta |
| Descarga automática de una playlist | Cuenta; cada dispositivo descarga su propia copia |
| Fecha de incorporación de una canción a cada playlist | Cuenta, `playlists.entryAddedAt` |
| Reproducciones y tiempo escuchado | Cuenta, contadores por dispositivo sin sumar dos veces una misma copia |
| Búsquedas y reproducciones recientes con identificadores portables | Cuenta, `activity` |
| Tema, color, idioma, formato y calidad de descarga | Cuenta, `preferences` |
| Autoplay, duración del crossfade, zona en la barra, letra visible y zoom MP3 | Cuenta, `preferences` |
| Criterio y sentido de orden de la biblioteca | Cuenta, `preferences.librarySort` |
| Ajustes de portadas ofrecidos solo en Web | Se conservan en la cuenta; los otros clientes respetan los campos que no implementan |
| Carpetas, rutas, archivos descargados, cookies y permisos | Dispositivo |
| Cola activa, posición de reproducción, volumen, salida de audio, pantalla y desplazamiento | Dispositivo |

## Orden y compatibilidad

Con sesión iniciada, **Canciones** muestra las canciones de YouTube guardadas en
las playlists o en Favoritos de la cuenta. Las copias descargadas se usan para
reproducir el mismo tema cuando están disponibles. Los archivos propios del
dispositivo se encuentran en **Carpetas** o **Descargas**.

«Más recientes» dentro de una playlist utiliza la fecha compartida de incorporación
y un identificador estable para resolver empates. Reordenar manualmente una playlist
no convierte las canciones antiguas en incorporaciones nuevas. Al renombrarla se
conservan sus fechas.

Las listas anteriores no guardaban fechas de incorporación. Su migración conserva
el orden disponible mediante posiciones estables; no inventa fechas históricas.
Las incorporaciones nuevas sí registran una fecha común. En un conflicto simultáneo
de orden, ambas plataformas eligen el mismo resultado, independientemente de cuál
se considere la copia local.

Al cambiar el criterio de orden o su sentido, la app guarda automáticamente
ambos en la cuenta. Una cola nueva parte del orden visible de la playlist.

Una entrada de un archivo que solo existe en otro dispositivo puede permanecer
no reproducible allí. La Web conserva su posición al exportar los cambios, en lugar
de desplazarla al final. La sincronización no transfiere los archivos de música.

## Actividad y preferencias

`activity.queries` contiene como máximo ocho búsquedas y `activity.played`, cincuenta
canciones recientes identificadas por su ID, con sus metadatos y fecha. Las marcas
`queriesClearedAt` y `playedClearedAt` impiden que una copia antigua resucite un
historial vaciado. La actividad simultánea de dos dispositivos se conserva.

Los cambios locales pendientes de preferencias tienen prioridad hasta que se
confirma su escritura. Aplicar una preferencia recibida no se registra como una
nueva edición local. Los controles de Configuración reflejan las actualizaciones
recibidas aunque la pantalla ya esté abierta.

Android y Web escuchan el documento de cuenta; Windows comprueba cambios cada diez
segundos, sin solapar consultas. Una actualización sin cambios no debe reconstruir
la biblioteca ni reiniciar la letra o el video.

Todos los clientes deben incorporar este formato para conservar las nuevas fechas
y preferencias durante escrituras hechas desde distintas plataformas.
