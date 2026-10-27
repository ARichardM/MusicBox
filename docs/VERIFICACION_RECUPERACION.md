# Recuperación de descargas y verificación de pendientes

Fecha: 27 de octubre de 2026. Cambios sin publicar sobre 2.3.3.

## Cambios funcionales

- Android guarda los enlaces pendientes en un archivo privado con escritura atómica antes de comenzar a descargarlos. Si no puede guardarlos, no acepta el lote y registra el error.
- Al abrir Inicio o el descargador después de terminarse el proceso, recupera los pendientes en el mismo orden y conserva formato, calidad, carpeta, rango, cookies y numeración del lote.
- Los trabajos interrumpidos vuelven a esperar; no se restaura un porcentaje ni una velocidad antiguos. yt-dlp aprovecha los parciales cuando el servidor admite reanudación y conserva los archivos terminados.
- Las cancelaciones se guardan antes de detener el proceso de descarga. Los trabajos cancelados, terminados o con error no se reintentan automáticamente.
- En Windows, una configuración Firebase ausente, incompleta o con JSON inválido ya no impide abrir la interfaz. El inicio de sesión informa del problema y conserva el token guardado, sin usar credenciales de otra plataforma.
- La prueba QML usa una portada local de muestra. Antes solicitaba la imagen de un ID ficticio de YouTube y podía fallar con un HTTP 404 según el tiempo de respuesta de la red.

## Comprobaciones realizadas

| Comprobación | Resultado |
| --- | --- |
| Suite completa de escritorio con los cambios finales | 388 correctas en una ejecución conjunta |
| Regresiones de configuración Firebase de Windows | 9 correctas |
| Regresiones del reproductor de video y sus controles JavaScript | 7 correctas |
| Arranque Windows con `--smoke-test`, sin configuración Firebase | Correcto, salida 0 |
| Audio online y reproducción automática de Windows | Avance real del audio y reproducción de la siguiente canción |
| Pruebas unitarias Android, incluida persistencia y cancelación | 140 correctas |
| Regresiones del teléfono: descargas, OCR, selección, eliminación y gestos | 16 correctas en una ejecución conjunta |
| Recuperación con interrupción real del proceso | 6 fases correctas, desde Inicio y desde el descargador |
| Compilación de APK verification y APK de instrumentación | Correcta |
| Lint verification | Sin errores; 238 advertencias existentes |
| Dependencias Python y compilación de código | `pip check` y `compileall` correctos |
| Coherencia de versiones | 2.3.3; sin publicación ni cambio de versión |

La suite ampliada de escritorio tuvo una interrupción transitoria de conexión
local (`WinError 10053`) en una comprobación HTTP del reproductor. La misma prueba
y las otras seis de su módulo pasaron al repetirlas sin cambios en ese servidor.
Tras corregir la portada ficticia de la prueba QML, la ejecución final de las
388 pruebas terminó correctamente. Python mantiene al cerrar un aviso de
recolección de objetos de la suite (`ResourceWarning`), sin errores de pruebas.

Las advertencias de lint siguen abiertas: incluyen accesibilidad, recursos sin
uso, actualizaciones de dependencias y recomendaciones de diseño y rendimiento.
No se han silenciado ni se consideran corregidas con este cambio. El analizador
también informa de metadatos Kotlin de Firebase más recientes que su versión;
la compilación, lint y las pruebas finalizaron correctamente.

## Prueba de recuperación en el teléfono

Con los dos APK de verification instalados y el dispositivo conectado por ADB:

```powershell
python scripts/verify_download_recovery.py --adb RUTA/AL/adb.exe
```

Si hay varios dispositivos, añadir `--serial IDENTIFICADOR`.

El script sirve audio de silencio generado en memoria desde loopback y usa un
puerto temporal con `adb reverse`. En cada pantalla comprueba estas fases:

1. Encola tres enlaces, cancela el tercero y espera a que exista un archivo parcial.
2. Detiene exclusivamente `cl.arichard.descargador.verification` y abre un proceso nuevo.
3. Comprueba que los dos pendientes se conviertan en MP3, mantengan su numeración
   y aparezcan en el historial. El servidor confirma una petición HTTP Range que
   continúa el parcial desde un desplazamiento mayor que cero.
4. Vuelve a detener y abrir el proceso, comprobando que la cola siga vacía, que
   los archivos no se modifiquen y que no se dupliquen las entradas del historial.

Esto comprueba la recuperación después de perder el proceso. No se reinició
físicamente el teléfono. No se intenta iniciar un servicio durante el arranque
del sistema: la recuperación comienza al abrir la app.

## Aislamiento

También se fijaron en vertical las nueve pantallas de producción y la pantalla
principal de verificación. Se recompiló e instaló el APK de verification y se
comprobó el manifiesto combinado: las diez actividades declaran `portrait`.
En el teléfono, Inicio y el descargador mantuvieron
`SCREEN_ORIENTATION_PORTRAIT` y rotación 0 al comprobar la rotación mediante ADB.
La preferencia global de rotación del teléfono se restauró al finalizar.

Las pruebas físicas se hicieron en el Samsung SM-G985F con Android 13, usando
exclusivamente `cl.arichard.descargador.verification`, sin cuenta Firebase.
La instalación anterior de esta variante tenía otra firma: se respaldaron sus
archivos privados antes de reemplazarla. La app original y la variante preview
no se desinstalaron ni se actualizaron.

Los gestos usan muestras de silencio en la carpeta de música privada de la
variante de verificación. La prueba de eliminación solo borra las tres muestras
`zz_prueba_borrar_[123].mp3`; antes de prepararlas se comprueba si existen y se
respalda cualquier copia previa. No se modifican playlists de una cuenta real.

Las pruebas de Windows usan preferencias y carpetas temporales. El ensayo de
audio online se ejecuta silenciado y elimina su caché temporal al terminar.
