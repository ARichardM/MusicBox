# Verificación 2.4.5 — 12 de noviembre de 2026

Se integra origin/feat/color-primario (4718bd9) sobre main, conservando la corrección
de tarjetas de Inicio de 2.4.4. El único conflicto textual fue el comentario de
DeviceGestureTest: se conservó la indicación de usar audios sintéticos de verification.

## Funciones integradas

- Acento azul o naranjo en PC y Android, con temas claro y oscuro, persistencia y preferencia de cuenta.
- Exportación de playlists como CSV de título, artista y álbum.
- Android: swipe corto ascendente desde el minirreproductor, incluido el botón de reproducción; abre al soltar y respeta la cancelación.

## Resultados

| Comprobación | Resultado |
| --- | --- |
| Suite completa de escritorio | 539 pruebas aprobadas |
| Suite completa Android JVM verification | 288 pruebas aprobadas, sin omisiones |
| lintVerification | Sin errores; 242 advertencias |
| APK verification y pruebas | Compilación e instalación correctas |
| Samsung SM-G985F, Android 13: 26 regresiones de 2.4.4 con el nuevo gesto | Aprobadas en ejecución conjunta |
| Samsung: cuatro combinaciones de tema/acento | Aprobada tras ajustar la expectativa del azul predeterminado |
| CSV de escritorio con datos ficticios | UTF-8, comas, comillas, saltos de línea, metadatos desconocidos, cancelación y lista vacía: aprobado |
| Coherencia de versiones | 2.4.5; Android versionCode 23 |

La ejecución conjunta de dispositivo tuvo 27 casos: 26 aprobados y un fallo de la
prueba de colores, que esperaba una preferencia escrita incluso cuando azul ya era
el valor por defecto. Se corrigió esa expectativa y se repitió su clase: aprobada.
La prueba recorre azul/naranjo y claro/oscuro desde Configuración, comprueba los
colores resultantes, conserva la preferencia elegida y detecta píxeles azules
saturados residuales en la interfaz naranja de esa pantalla. Guarda cuatro capturas.

La primera suite de escritorio tuvo un fallo intermitente en la comprobación del
rebote de sugerencias. Tras esperar la exposición de ventana y la animación real,
y garantizar la liberación del ratón, se repitió la suite completa: 539 aprobadas.

Las pruebas usan exclusivamente verification sin Firebase real, preferencias
temporales en escritorio, playlists ficticias y audios sintéticos. No se envió
ningún CSV a terceros. La comprobación de CSV en PC ejercita su exportación real a
un archivo temporal; no certifica la importación en servicios externos. No se repitió
una prueba física de cambio Bluetooth ni se certificó visualmente un videoclip MP4.

La publicación ejecuta además las compilaciones y comprobaciones finales de Windows
y del APK firmado, y solo publica cuando ambos paquetes y sus sumas están disponibles.
