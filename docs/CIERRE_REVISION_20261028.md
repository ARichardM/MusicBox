# Cierre de revisión y sincronización entre ASUS y PC

28 de octubre de 2026. Cambios de trabajo sobre 2.3.3, sin publicar otra versión.

## Autoplay: diagnóstico y solución adicional

La prueba anterior con Luis Jara no cubría una radio compuesta únicamente por
videoclips. Para `fKopy74weus` («Thunder») se observaron 50 entradas de radio, todas
OMV. Reintentar la misma consulta no podía producir audio y terminaba en el aviso.
El logcat de la app personal no mostraba una excepción de autoplay: la API había
respondido correctamente, pero el filtrado dejaba cero canciones.

Android y Windows conservan el filtro de audio. Si no quedan pistas, buscan la
misma canción y artista en el catálogo de canciones y usan esa semilla de audio
para una segunda radio. Se limita a una búsqueda y una radio alternativa, sin
búsquedas por cada resultado. Se excluyen ambas semillas, duplicados y videos.
Si no hay coincidencia fiable, se conserva el error/reintento, sin insertar música
de otro artista. La consulta real corregida produjo 25 canciones de audio.

Las regresiones cubren radio solo-video, búsqueda equivocada/covers, ausencia de
metadatos de semilla, límite cero, duplicados y exclusión de la versión de audio.
En hardware se añade un escenario específico de «Thunder» con avance de pista.

## Sincronizar el notebook

En PowerShell, dentro del repositorio del ASUS:

```powershell
git status
git pull --ff-only
.\scripts\windows\configurar_firma_pruebas.ps1 -ClaveDelNotebook "$env:USERPROFILE\.android\debug.keystore"
.\scripts\windows\install_android_preview.bat
```

Si `git status` muestra trabajo local o el pull rechaza una divergencia, detenerse
y revisar los cambios; no usar reset, limpiar archivos ni sobrescribir trabajo.
Los comandos de configuración/instalación necesitan primero el código de este cierre.

El archivo compartido está en `%USERPROFILE%\.gradle\musicbox\preview.keystore`
(o `GRADLE_USER_HOME\musicbox` si se configura esa variable). El script valida la
identidad antes de copiar y la compilación vuelve a validarla. El usuario confirmó
que copió la misma clave a esa ubicación del ASUS. No se ejecutaron comandos
remotos en el notebook: su compilación queda por comprobar allí.

Git sincroniza código y documentación, nunca claves privadas, sesiones, APK ni
respaldos personales. Conservar el original del ASUS y un respaldo privado seguro.
Mantener las configuraciones Firebase privadas existentes de cada máquina; no
copiarlas a Git. Verification sigue aislada, sin configuración Firebase real.

## Límites y deuda técnica

La compilación final y las regresiones Android tienen 172 pruebas unitarias sin
fallos ni omisiones. Windows pasa 392 pruebas tanto en Python 3.12 como en el
entorno `.venv` Python 3.10. También pasan `compileall`, comprobación de versión
2.3.3 y revisión de espacios del diff. Un intento inicial con Python 3.10 global
no tenía las dependencias del proyecto; la validación válida usa su `.venv`.

En el Samsung pasaron juntos 28 escenarios físicos en 152,670 segundos, sin
reintentos ni casos omitidos: carga/avisos, dispositivos, estadísticas, portadas,
gestos, tema, OCR, selección/orden, enlaces, cierre, descargas y dos semillas de
autoplay con avance de pista («Thunder» y Luis Jara). La eliminación de archivos
públicos no se incluye en esta ejecución; su ensayo previo con muestras nombradas
queda registrado en `VERIFICACION_INTERFAZ_ANDROID.md`. El informe local de esta
ejecución es `build/final-device-suite-20261028.txt` y permanece fuera de Git.

Se repitieron además las seis fases de recuperación tras detener el proceso,
desde Inicio y desde Descargador: pendientes completados, HTTP Range del parcial,
cancelación persistente y ausencia de duplicados al reabrir. Resultado correcto
en `build/final-recovery-20261028.txt`. Esta repetición no reinició físicamente
el teléfono; el reinicio físico autorizado y probado anteriormente queda en su
informe específico. Se actualizó MusicBox pruebas con la firma compartida,
sin desinstalación ni borrado de datos, para uso manual del usuario.

- No se publica Release ni etiqueta, ni se incrementa la versión en un commit de trabajo.
- No se automatizan pruebas sobre cuentas o playlists personales.
- Lint mantiene visibles sus 203 advertencias, clasificadas en `REVISION_POST_PULL.md`.
  No hay errores. Eliminarlas todas implica trabajo de compatibilidad, traducción,
  accesibilidad y migración de dependencias que no se declara terminado aquí.
- Persiste el aviso de cierre de PySide6 reproducible sin MusicBox; no se suprime.
- Se mantiene pendiente la validación física en otros modelos/versiones Android
  y la compilación en el ASUS después del pull. El hardware disponible es Samsung
  SM-G985F con Android 13.
