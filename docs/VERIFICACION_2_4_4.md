# Verificación 2.4.4 — 12 de noviembre de 2026

## Corrección

Un refresco de Inicio incrementaba la generación que valida las tarjetas diferidas.
Si llegaban otra vez los mismos datos antes de crearlas, la caché evitaba repetir
el renderizado y los callbacks antiguos se descartaban. La sección podía quedar
vacía. Ahora el resultado idéntico se reutiliza solo si todas sus tarjetas están
presentes. Se aplica a recientes, biblioteca y colecciones superiores.

La regresión JVM fuerza dos resultados idénticos antes de ejecutar los callbacks
y comprueba que recientes y biblioteca terminan con sus dos tarjetas, sin duplicados.

## Resultados finales

| Comprobación | Resultado |
| --- | --- |
| Escritorio: suite completa unittest | 537 aprobadas |
| Android: suite completa testVerificationUnitTest | 285 aprobadas, sin omisiones |
| lintVerification | Sin errores; 244 advertencias |
| APK verification y APK de instrumentación | Compilación e instalación correctas |
| Coherencia de versión respecto de 2.4.3 publicada | 2.4.4, versionCode 22 |
| Samsung SM-G985F, Android 13 | Los 26 casos seleccionados aprobaron tras las correcciones y repeticiones descritas abajo |

En el Samsung se ejecutaron DeviceGestureTest (7), HomePolishDeviceTest (7),
PlaylistOrganizationDeviceTest (2), PlaylistSearchRenderingDeviceTest (3),
InteractionPolishDeviceTest (3), PlaybackRecoveryDeviceTest (1), y tres casos
de PendingFeaturesDeviceTest: autoplay de Luis Jara, autoplay de Thunder y
descarga que continúa al retirar la tarea de recientes.

La última ejecución conjunta de 26 casos aprobó 25; la comprobación del historial
con teclado aún falló. Después de corregir su preparación se repitió la clase
HomePolishDeviceTest completa y aprobaron sus 7 casos. No se presenta esto como
una ejecución conjunta única de 26 casos sin fallos.

Las pruebas esperan la carga inicial y la creación asíncrona de tarjetas,
preparan explícitamente el historial (la reproducción lo registra tras 30 segundos),
y esperan la expansión del panel de novedades. La prueba del desplegable consulta
la geometría de la ventana subyacente y tolera filas de accesibilidad transitorias
mientras se ajusta el teclado, manteniendo las comprobaciones de posición.

## Aislamiento y límites

- Paquete exclusivo cl.arichard.descargador.verification, sin configuración Firebase real.
- Audios WAV sintéticos de 180 segundos en su directorio privado externo Music/Verification.
- Playlists ficticias del paquete aislado. No se probaron ni modificaron playlists personales.
- Paginación de búsqueda con respuestas controladas; autoplay con red real.
- Descarga desde loopback con audio sintético, conversión a MP3 y comprobación del archivo final.
- Esta selección no equivale a toda la instrumentación del repositorio.
- No se repitió el cambio físico de salidas Bluetooth ni se certificó visualmente un videoclip MP4 real.
- Los workflows de publicación vuelven a probar, compilar y verificar los paquetes finales antes de publicar ambos juntos.
