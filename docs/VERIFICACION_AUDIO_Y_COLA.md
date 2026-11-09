# Verificación de audio y cola — 29 de octubre de 2026

## Corrección

En Windows, el error de lectura agotaba la recuperación sin avanzar la cola y el
botón Reintentar llamaba a `playOnline`, reemplazando la lista por una canción.
Ahora se conserva la cola, se renueva el enlace fallido, se recupera la posición y
se intenta una copia temporal completa antes de omitir una canción no reproducible.
Los resultados tardíos y los errores duplicados no deben sustituir la canción actual.

Android también detenía la cola ante errores del reproductor. Ahora invalida el
enlace, reintenta una vez y continúa con otra canción si el error persiste. Sin
conexión se conserva la cola online; los archivos locales pueden continuar. Un
conjunto de canciones dañadas no debe provocar un bucle, incluso con repetición.
Reintentar desde el aviso vuelve a la canción fallida sin reconstruir la cola.

Se mantienen los cambios de seguimiento de salida del sistema, volumen multimedia,
búsqueda automática de Windows, indicadores de carga y marca de descarga.

Al perder la salida activa en Windows, ambos reproductores pausan antes de elegir
otra salida. Android espera 1000 ms a que se estabilice el enrutamiento: si el sistema
cambia a otra salida personal, la reproducción continúa; si cae al altavoz o no hay
reemplazo, pausa para evitar que el audio salga inesperadamente. La desconexión
cancela también el arranque pendiente, la reanudación por foco y el avance automático.
Reconectar los audífonos no reanuda la música por sí solo.

## Resultado comprobado

| Comprobación | Resultado |
| --- | --- |
| Suite completa de escritorio, antes del añadido de pausa por desconexión | 415 pruebas aprobadas |
| Regresiones actuales de salida, desconexión, reproducción, búsqueda, QML, radio, idiomas y contadores | 75 pruebas aprobadas |
| YouTube real en Windows, silenciado y con datos temporales | Recuperó un `Demuxing failed` controlado, conservó posición y cola y reprodujo la siguiente canción |
| Suite JVM de Android, incluida desconexión de audífonos | 202 pruebas aprobadas, sin errores ni fallos |
| APK y tests `verification`, lint Android | Compilación correcta; lint sin errores |
| Decodificador Android en emulador API 35 | Caso aprobado: archivo dañado seguido de dos WAV válidos; la cola continúa y conserva sus tres canciones |
| Volumen multimedia en Android | Comprobado en MainActivity, PlayerActivity y NowPlayingActivity dentro del caso de dispositivo |

Las pruebas usan exclusivamente `cl.arichard.descargador.verification`, sin cuenta
ni configuración Firebase real. Los audios son sintéticos. La variante incluye
x86_64 para que Python y FFmpeg puedan ejecutarse en el emulador; las arquitecturas
de publicación para teléfonos no cambian.

## Validación pendiente

La batería completa de dispositivo **no está aprobada**. La comprobación del
avatar Zeta en el filtro de artistas del tema claro no alcanzó el estado esperado.
El emulador se cerró repetidamente durante ejecuciones más amplias y de red,
interrumpiendo también la comprobación de descargas. La última ejecución focalizada
registró el caso de recuperación como aprobado, pero terminó con dos fallos y sin
completar todas las pruebas previstas.

Falta cerrar esos resultados y la reproducción online de Android en un emulador
estable o un dispositivo físico de verificación. Tampoco se ha comprobado un cambio
de salida con audífonos Bluetooth físicos. No se ha publicado ni etiquetado una
versión estable con estos cambios.

## Actualización — 7 de noviembre de 2026

| Comprobación | Resultado |
| --- | --- |
| Cambio entre salidas Bluetooth y desconexión sin reemplazo | Pruebas JVM aprobadas en API 24 y API 28 |
| Suite Android `verification`, lint y APK de app/tests | 256 pruebas aprobadas; compilación correcta; lint sin errores |
| Enlaces compartidos de artista, canción y playlist | Prueba instrumentada aprobada en emulador API 35 |
| Carga de QML de escritorio | 2 pruebas aprobadas |
| Cambio de salida con audífonos Bluetooth físicos | Pendiente: el emulador validó los intents, pero no había audífonos/dispositivo Bluetooth físico |

La validación de laboratorio no sustituye la comprobación física de la ruta Bluetooth.

## Actualización — 8 de noviembre de 2026

| Comprobación | Resultado |
| --- | --- |
| Suite JVM `verification`, lint y APK de app/tests tras el ajuste Bluetooth | 256 pruebas aprobadas; compilación correcta; lint sin errores |
| Enlaces compartidos | `SharedMusicDeviceTest` aprobado en Samsung SM-G985F, Android 13 |
| Reproductor Android en dispositivo físico | `PlaybackRecoveryDeviceTest` aprobado con audio sintético privado |
| Ruta Bluetooth durante la instrumentación | No concluyente: Android instrumentado reportó `remote-submix` para el reproductor |
| Ruta fuera de instrumentación | El probe ADB detectó A2DP en `Agustin's Buds2` |
| Conexión y retiro de audífonos durante reproducción | El usuario confirmó en el Samsung que el audio continúa por los audífonos al conectarlos y se pausa al retirarlos |
| Cambio directo de `Agustin's Buds2` a `ES-BT10` | Confirmado por el usuario |

El reproductor deja a Android elegir la salida (`USAGE_MEDIA` y ninguna salida
preferida fijada). En API 28 o posterior confirma la ruta efectiva con
`MediaPlayer.getRoutedDevice()`; en versiones anteriores usa el inventario de
salidas conectadas. Los callbacks de dispositivo y `ACTION_AUDIO_BECOMING_NOISY`
inician una comprobación diferida, en vez de pausar inmediatamente al desconectar
la salida anterior.

Como la instrumentación desvía el audio a `remote-submix`, la variante `verification`
incluye un observador ADB que ejecuta la reproducción fuera del runner de pruebas.
Las pruebas automatizadas siguen usando exclusivamente
`cl.arichard.descargador.verification`.
