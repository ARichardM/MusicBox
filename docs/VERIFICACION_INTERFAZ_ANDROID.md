# Interfaz Android: artistas, recientes, playlists e importación

27 de octubre de 2026. Cambios sin publicar sobre 2.3.3.

## Cambios

- Minirreproductor fijo en artista y álbum, separado del contenido desplazable.
  Mantiene pausa, siguiente, cola, progreso, portada y apertura del reproductor.
  Sus observadores se retiran al abandonar la pantalla; las portadas solo se
  vuelven a cargar al cambiar de canción.
- Recientes anclados al borde inferior del buscador, con el mismo ancho y una
  altura máxima de 216 dp, limitada además por el espacio sobre el teclado.
  Los textos largos se acortan y la lista conserva su desplazamiento propio.
- Selector compartido con portadas, cantidad de canciones y una opción de crear
  visualmente diferenciada cuando corresponde. Resuelve las portadas fuera del
  hilo de interfaz, leyendo las playlists sin volver a escanear toda la música.
  Los collages usan también canciones sin reproducciones previas.
- Filtros Todas, Elegidas, Por revisar y Pendientes en Coincidencias e Informe,
  con contadores que se actualizan al elegir o desmarcar. Filtrar no modifica las
  filas ni las elegidas que se exportan o se añaden al terminar.
- Accesos de colecciones corregidos: playlist usa el modo 3 y carpeta el modo 2.
  Cada apertura establece su propio modo para evitar depender del llamador.
  Todas las tarjetas del carrusel miden 84 dp y acortan los títulos largos.

## Verificación

- 149 pruebas unitarias Android correctas: incluyen nueve regresiones nuevas de
  navegación y clasificación de estados, filtrado del informe y conservación de
  la selección final.
- 21 escenarios comprobados en el Samsung SM-G985F con Android 13: cinco nuevos
  y dieciséis regresiones anteriores. En la ejecución conjunta pasaron veinte;
  la reproducción online agotó su espera y pasó al repetirla de forma aislada
  (21,9 segundos), sin cambios en el reproductor. No se presenta como una pasada
  conjunta sin incidencias.
- Compilación de APK verification y de instrumentación correcta.
- Lint verification sin errores. Continúan 259 advertencias, incluidas las
  recomendaciones de orientación bloqueada y otros avisos de diseño y análisis;
  no se presentan como corregidas.
- Versión coherente en 2.3.3 y `git diff --check` sin incidencias.

Los cinco escenarios nuevos del teléfono comprueban apertura de playlists y
carpetas con canciones y altura uniforme; posición de recientes con teclado
visible; controles del minirreproductor en artista y retorno desde cola y
reproductor completo; portadas y selección del diálogo; y reclasificación de una
canción al elegir una alternativa y regreso del informe a los pendientes.

Se usan exclusivamente `cl.arichard.descargador.verification` y muestras de audio
de esa variante. Las playlists creadas para verificar pertenecen a esa
instalación sin cuenta; no se modifican playlists reales. La comprobación de
eliminación solo usa las tres muestras expresamente permitidas por `AGENTS.md`,
respaldando cualquier copia previa antes de prepararlas.
