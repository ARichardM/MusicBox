# Verificación de MusicBox 2.3.3

Fecha: 27 de octubre de 2026.

## Comprobaciones realizadas

| Comprobación | Resultado |
| --- | --- |
| Pruebas de escritorio | 381 correctas |
| Pruebas unitarias Android | 127 correctas |
| Pruebas en teléfono Android | 16 correctas en una ejecución conjunta |
| Lint de Android release | Sin errores; permanecen avisos de lint anteriores |
| Compilación Android release | Correcta; la firma de distribución se aplica en GitHub Actions |
| Compilación Windows con PyInstaller | Correcta |
| Arranque del ejecutable Windows empaquetado | Interfaz QML cargada con `--smoke-test`, salida 0 y metadatos 2.3.3 |
| Audio online y reproducción automática en PC | Audio avanzando y cambio efectivo a otra canción, con red real |
| Coherencia de versión | Android, Windows, descargador y changelog coinciden en 2.3.3 |

La interfaz empaquetada se prueba con preferencias temporales, sin acceder a la
cuenta ni iniciar sincronizaciones. El ensayo online usa audio silenciado y una
caché temporal, que se libera al terminar.

La prueba del historial resuelve la carpeta temporal antes de buscar por ruta:
Windows puede devolver un alias 8.3 o una unión, mientras SQLite conserva la ruta
canónica. Se reprodujo el fallo con una unión local y se verificó la corrección;
el guardado de descargas no requería cambios.

## Cobertura del teléfono

`PendingFeaturesDeviceTest` comprueba selección múltiple, seleccionar todo,
acciones en bloque, orden persistente, enlaces compartidos sin descarga automática,
OCR con revisión previa, permiso del sistema para eliminar muestras, cierre de
reproducción desde recientes, continuidad de descargas con su notificación y
reproducción automática online hasta la siguiente canción. También comprueba que
tocar fuera de la cola interna la cierre con fotogramas intermedios de animación,
sin activar el botón que queda debajo ni cerrar el reproductor.

`DeviceGestureTest` comprueba desplazamientos entre pestañas, carruseles, arrastre
de navegación, minirreproductor, expansión interactiva y cancelación, reordenamiento
de cola, protección de acciones y conservación de estado al cambiar el tema.

Las pruebas se ejecutan exclusivamente en `cl.arichard.descargador.verification`,
sin cuenta ni configuración Firebase. Solo la comprobación de eliminación puede
borrar las tres muestras denominadas `zz_prueba_borrar_[123].mp3`, con respaldo
local previo. No se reconstruyen playlists reales para probar la app.

## Sincronización

Las regresiones de Python y Java cubren copias vacías antiguas, adiciones
simultáneas, eliminaciones expresas, cambios de orden, conservación de metadatos,
respaldos y registro de cambios. Las pruebas del cliente REST comprueban que cada
escritura exige la versión leída y que un conflicto vuelve a leer y recalcular.
Android usa transacciones de Firestore para esa misma protección.

Esto verifica las reglas sin modificar playlists de una cuenta real. Los respaldos
y el registro comienzan con esta versión; no reconstruyen cambios anteriores.

## Publicación

La versión anterior publicada es 2.3.2; corresponde 2.3.3. `scripts/check_version.py`
lo verifica y rechaza saltos sin una indicación explícita. Los workflows vuelven a
probar, compilan APK firmado, instalador y portable de Windows, generan SHA-256 y
mantienen la Release en borrador hasta que ambas plataformas terminen correctamente.
