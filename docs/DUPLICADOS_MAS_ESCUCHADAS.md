# Copias de una canción en «Más escuchadas»

28 de octubre de 2026. Corrección incluida en 2.3.4.

La sección ordenaba cada archivo de la biblioteca por reproducciones, sin agrupar
copias. La inspección de solo lectura del teléfono confirmó tres archivos con el
mismo ID de YouTube: uno privado de la app y dos en carpetas públicas distintas.
Una copia carecía de metadatos y mostraba el ID como parte del título.

`SearchHistoryManager.getTopPlayed` agrupa únicamente para esta presentación:

- Usa la identidad online o el ID vinculado; también reconoce el ID de once
  caracteres entre corchetes al final del nombre de archivo descargado.
- Sin ID, requiere título, artista conocido y duración coincidentes. Conserva
  las indicaciones de versión del título y no agrupa archivos de artista desconocido.
- Selecciona una copia con mejores metadatos y aplica el límite después de agrupar.
- No borra archivos ni modifica biblioteca, playlists, vínculos ni contadores.
  No suma las reproducciones compartidas de cada copia.

Las regresiones cubren tres copias, metadatos incompletos, IDs diferentes, otros
artistas, versiones distintas, archivos desconocidos, copias vinculadas y límites.
La prueba física `topLibraryShowsOneRowForThreeCopiesWithoutMutatingTheLibrary`
usa una biblioteca sintética en verification y comprueba una sola fila y que se
conserven sus tres entradas y los contadores originales.

Resultado: suite Android de 176 pruebas unitarias correcta. Trece escenarios
físicos de interfaz, estabilidad y carga correctos juntos (41,845 s), sin casos
omitidos ni reintentos. Registro local: `build/duplicates-device-20261028.txt`.
Las compilaciones y controles de distribución posteriores terminaron correctamente;
2.3.4 ya está publicada. Detalles en `PUBLICACION_2.3.4.md`.
