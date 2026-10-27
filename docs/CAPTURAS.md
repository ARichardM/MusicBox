# Capturas de la interfaz

La galería del README muestra ventanas y pantallas reales de MusicBox. No son
maquetas ni imágenes generadas de una interfaz inexistente. Los nombres de canciones
y artistas son una selección pública de muestra; las portadas geométricas son
ilustraciones originales para la documentación, no carátulas oficiales.

## Privacidad

- Escritorio utiliza un archivo INI de demostración, sin tokens, Firebase ni escaneo
  de carpetas. El historial se dirige a una base de datos de demostración.
- Android solo permite exportar desde `cl.arichard.descargador.verification`, sin
  cuenta Firebase. Antes de capturar, se invalidan los escaneos pendientes y se
  sustituye la biblioteca en memoria por las nueve canciones de la fixture.
- No se reproducen ni descargan canciones: la portada, el tiempo y la cola son
  datos de demostración. No se reconstruyen ni modifican playlists de una cuenta.
- La captura Android se obtiene del contenido de la actividad, sin notificaciones,
  barra de estado, fondo del teléfono ni aplicaciones ajenas. El reloj del marco
  es un elemento ilustrativo fijo.
- Los archivos públicos son SVG autocontenidos: incluyen la captura PNG original
  y un marco vectorial redondeado. No llevan scripts, recursos remotos ni metadatos PNG.
- Las capturas originales y los archivos temporales quedan en `build/`, fuera de Git.

## Reproducir las capturas

Desde la raíz, con las dependencias de escritorio instaladas:

```powershell
.\.venv\Scripts\python.exe scripts/documentation_screenshots.py
```

En Windows se utiliza el renderizador nativo para conservar los efectos QML,
las portadas y los iconos. Se abre brevemente una ventana con datos de demostración.
Nunca se captura el escritorio completo.

Para Android, conecta un dispositivo de pruebas y compila exclusivamente la variante
independiente. Los comandos siguientes no instalan ni abren la aplicación de producción:

```powershell
Push-Location apps/android
.\gradlew.bat assembleVerification assembleVerificationAndroidTest
Pop-Location
adb install -r apps/android/app/build/outputs/apk/verification/app-verification.apk
adb install -r apps/android/app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
.\.venv\Scripts\python.exe scripts/documentation_screenshots.py --prepare-android
adb shell mkdir -p /sdcard/Android/data/cl.arichard.descargador.verification/files/documentation
adb push build/documentation-screenshots/. /sdcard/Android/data/cl.arichard.descargador.verification/files/documentation/
adb shell am instrument -w -e documentationScreenshots true -e class cl.arichard.descargador.DocumentationScreenshotsTest cl.arichard.descargador.verification.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/cl.arichard.descargador.verification/files/documentation/android-library.png build/documentation-screenshots/android-library.png
adb pull /sdcard/Android/data/cl.arichard.descargador.verification/files/documentation/android-player.png build/documentation-screenshots/android-player.png
.\.venv\Scripts\python.exe scripts/documentation_screenshots.py --frame-android build/documentation-screenshots
```

La prueba es optativa: sin el argumento `documentationScreenshots=true` se omite.
Usa español para las capturas y restaura la selección de idioma al terminar.

Antes de publicar:

```powershell
.\.venv\Scripts\python.exe scripts/documentation_screenshots.py --verify
git diff --check
```

Además de las comprobaciones automáticas, revisar visualmente las cuatro vistas
previas de `build/documentation-screenshots/*-framed-preview.png` para confirmar
la legibilidad, los marcos y la ausencia de datos personales.
