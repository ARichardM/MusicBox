# Descargador móvil

Aplicación Android nativa derivada de la versión de escritorio. Permite buscar en
YouTube, pegar o compartir un enlace, reproducirlo dentro de la aplicación y guardar
una copia MP3 o MP4 en la carpeta privada de música de la app, sin solicitar acceso
total al almacenamiento. El descargador muestra la ruta de destino.

## Novedades 2.3.3

Inicio con recomendaciones estables hasta actualizar, páginas de artistas y álbumes,
reproducción automática y selección múltiple con orden de biblioteca. Las descargas
rápidas y el descargador completo comparten una sola cola en un servicio con
notificación: continúan al retirar la app de recientes, mientras la música se detiene.
El importador admite capturas mediante OCR local incluido; los enlaces compartidos
se abren en el descargador para revisarlos antes de descargar.

## Reproductor y biblioteca (2.2.0)

- El botón de bucle alterna entre repetir lista, repetir canción y desactivado;
  el texto indica el modo actual.
- El menú **⋮** reúne **Agregar a playlist**, **Agregar a carpeta**, **Eliminar**
  y **Cola de reproducción**, con sus iconos, para la canción actual.
  Agregar a una carpeta copia los archivos locales o descarga los temas online.
  Se puede crear una playlist o carpeta durante la selección. La eliminación pide
  confirmación; quitar de una playlist se ofrece por separado en el menú de la lista.
- En **Mi música**, el botón con una diana lleva a la canción activa, resaltada en
  azul. La app recuerda colección, filtros, búsqueda y posición de la lista.
- Desliza el minirreproductor a la izquierda para avanzar, a la derecha para ir a
  la canción anterior y hacia arriba para expandirlo. La expansión sigue el dedo
  y se completa o se cancela al soltar. Los botones conservan sus toques.
- El swipe horizontal entre Buscar y Mi música funciona sobre el contenido y la
  navegación inferior. Los carruseles y filtros tienen prioridad sobre el cambio
  de pestaña, incluso en sus extremos, y el movimiento vertical desplaza las listas.
  Los campos de texto conservan sus gestos. El reproductor completo se cierra deslizando hacia abajo sobre la
  portada o el video, sin interferir con la barra de tiempo ni los botones.
  Los movimientos diagonales y los gestos cancelados no ejecutan estas acciones.
- El navbar conserva sus dos botones y permite mantener y arrastrar el selector;
  tanto el indicador como las páginas acompañan el movimiento.
- La navegación queda encima del minirreproductor y se desplaza suavemente cuando
  este aparece o desaparece. Al cambiar de canción con un swipe, la portada y el
  título siguen el gesto y se deslizan para mostrar la siguiente canción.
- Abre **Cola de reproducción** en el reproductor o usa el botón de cola del
  minirreproductor. Puedes agregar varias canciones con búsqueda, pulsar una para
  reproducirla, arrastrar las filas para cambiar su orden y quitar entradas con **×**.
  **Limpiar cola** conserva la canción actual. El menú de cada canción y el botón
  **+** de los resultados también permiten agregar al final o reproducir después.
- La cola admite canciones locales y online. Al reabrir la app se recuperan la cola,
  la canción y su posición en pausa; pulsa reproducir para continuar.
- Los collages de carpetas y playlists priorizan sus cuatro canciones más escuchadas.
  Sin portadas se muestra un fondo de color; las colecciones vacías lo indican con texto.
- Cambiar entre tema claro y oscuro conserva biblioteca, resultados, búsquedas,
  filtros, desplazamiento, reproducción y descargas activas. MP4 aparece únicamente
  cuando la fuente en reproducción tiene video.
- Cabeceras compactas, filtros animados y botones de texto enmarcados y centrados.
  Las zonas de acciones cancelan pulsaciones cuando el dedo empieza a desplazarse,
  evitando favoritos, menús o reproducción accidentales durante un gesto.

## Compilar

Abre `apps/android` en Android Studio y ejecuta **Build > Build APK(s)**, o usa:

```powershell
.\gradlew.bat assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Android 7 (API 24) o
posterior es necesario. La primera compilación descarga las dependencias y puede
tardar; el APK incluye Python, yt-dlp y FFmpeg, por lo que ocupa bastante espacio.

Usa JDK 17 o 21 para Gradle. Las pruebas Android se ejecutan en JVM mediante
Robolectric, sin teléfono conectado:

```powershell
.\gradlew.bat testPreviewUnitTest lintPreview
```

Para instalar una edición de pruebas junto a la app actual, sin necesitar su clave
de firma ni reemplazar sus datos:

```powershell
.\gradlew.bat assemblePreview
```

Se genera `app/build/outputs/apk/preview/app-preview.apk`, con el nombre
**MusicBox pruebas** y el identificador `cl.arichard.descargador.pruebas`. Su cola,
favoritos y playlists se guardan por separado. Una actualización de la aplicación
original necesita la misma clave de firma que su APK anterior.

### Una firma de pruebas para el Asus y el PC

El estado pendiente y los pasos para retomarlo están en
[Firma de pruebas entre dos equipos](../../docs/FIRMA_PRUEBAS_DOS_EQUIPOS.md).

`preview` usa una clave compartida, no la clave debug que cada máquina genera
por separado. Se guarda fuera del repositorio en
`%USERPROFILE%\.gradle\musicbox\preview.keystore` (o dentro de `GRADLE_USER_HOME`).
Si falta, compilar/instalar preview falla antes de generar un APK con otra firma.
Debug, verification y release mantienen sus configuraciones anteriores.

Para conservar la instalación actual de MusicBox pruebas, copia de forma privada
el archivo `%USERPROFILE%\.android\debug.keystore` del Asus y ejecuta, desde la
raíz del proyecto, en **ambos equipos**:

```powershell
.\scripts\windows\configurar_firma_pruebas.ps1 -ClaveDelNotebook 'RUTA\debug.keystore'
.\scripts\windows\install_android_preview.bat
```

El script verifica que la huella coincida con la app instalada y no sobrescribe
otra clave existente. No cambia el `debug.keystore` general de otras aplicaciones.
Guarda una copia privada de esa clave: el APK contiene el certificado público,
pero no permite recuperar la clave privada. No basta con hacer pull del código.

Para una ubicación personalizada se puede usar `MUSICBOX_PREVIEW_KEYSTORE_PATH`.
Una clave con credenciales distintas admite `MUSICBOX_PREVIEW_STORE_PASSWORD`,
`MUSICBOX_PREVIEW_KEY_ALIAS` y `MUSICBOX_PREVIEW_KEY_PASSWORD`.

Las pruebas de gestos del dispositivo usan exclusivamente **verification** e incluyen
carruseles, filtros, arrastre del navbar, minirreproductor, expansión interactiva,
reordenamiento de cola, protección de botones y conservación del estado al cambiar
el tema. Compila `assembleVerification assembleVerificationAndroidTest` y ejecuta la clase
`cl.arichard.descargador.DeviceGestureTest` con AndroidJUnitRunner en un teléfono
con música local y permisos de biblioteca concedidos.

`PendingFeaturesDeviceTest` verifica selección, orden persistente, enlaces compartidos,
OCR, reproducción automática online, continuidad de descargas y cierre desde recientes.
Ambas clases abren `PreviewMainActivity`, disponible exclusivamente en **verification**
(`cl.arichard.descargador.verification`), sin configuración Firebase ni sincronización.
La variante **preview** puede tener una sesión real guardada; no se utiliza para estas
pruebas. La prueba
de eliminación solo acepta `/storage/emulated/0/Music/zz_prueba_borrar_[123].mp3`:
crea esas muestras desechables y guarda una copia antes de ejecutarla. Si no están,
esa comprobación se omite; nunca se eligen canciones del usuario para borrar.

```powershell
adb install -r -g app/build/outputs/apk/verification/app-verification.apk
adb install -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb shell am instrument -w -e class cl.arichard.descargador.PendingFeaturesDeviceTest cl.arichard.descargador.verification.test/androidx.test.runner.AndroidJUnitRunner
```

Desde 2.1.1 la reproducción usa MediaPlayer de Android y una fuente audiovisual
obtenida por yt-dlp. MP3 muestra la portada y mantiene el audio; MP4 conecta una
superficie de video al mismo reproductor y conserva la posición. El video mantiene
su proporción original. La preparación es asíncrona y las peticiones antiguas se
descartan al cambiar de canción. Los errores se muestran y quedan en Logcat bajo
`MusicBoxPlayer`.

La disponibilidad de reproducción y descarga depende del origen y de la versión
incorporada de yt-dlp. Para streaming se selecciona un formato con audio y video
juntos (`best[ext=mp4]/best`), cuya calidad puede ser inferior a la descarga MP4.
