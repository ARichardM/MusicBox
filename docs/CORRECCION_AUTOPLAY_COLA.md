# Autoplay y portada de la cola

27 de octubre de 2026. Cambios sin publicar sobre 2.3.3.

## Problemas encontrados

- Una respuesta fallida o sin canciones válidas dejaba el ID marcado como
  intentado, impidiendo repetir la consulta para esa canción.
- Limpiar los siguientes marcaba también el ID actual como intentado, aunque
  la opción de autoplay continuaba activada.
- Detener el reproductor invalidaba la consulta, pero conservaba esa marca.
- La portada de la fila activa empezaba en el borde del fondo redondeado y no
  tenía recorte propio.

## Correcciones

Se realizan como máximo tres consultas por canción tras resultados fallidos.
La cola muestra la búsqueda en curso o un aviso que se puede tocar para
reintentar. Desactivar autoplay invalida consultas anteriores. Cambiar o limpiar
la cola descarta respuestas antiguas; limpiar permite buscar de nuevo al llegar
al final. Detener el reproductor elimina la marca de consulta anterior.

Las portadas tienen margen interior y recorte redondeado de 6 dp, conservando
los controles de quitar, reordenar y seleccionar canciones.

Se mantienen el filtrado de duplicados, el límite de diez minutos y los criterios
de pistas de YouTube Music. Los reintentos no garantizan resultados si el
proveedor no devuelve canciones válidas.

## Comprobaciones

- 158 pruebas unitarias Android correctas, incluidas seis nuevas de recuperación,
  invalidación, errores y limpieza de cola.
- APK verification e instrumentación compilados correctamente.
- La portada y la reproducción automática online pasaron juntas en el Samsung
  SM-G985F con Android 13 (31,464 segundos); la prueba de gestos, búsqueda de
  posición y reordenamiento pasó al repetirla aisladamente (10,306 segundos).
- El primer intento conjunto falló al preparar la biblioteca del ensayo online
  y al localizar la confirmación de cancelar eliminación del ensayo de gestos.
  El ensayo online dejó de depender de la lista restaurada y su preparación
  espera el estado adecuado. No se ocultan los intentos fallidos.
- La ejecución final de los tres escenarios juntos pasó en 37,664 segundos,
  sin omisiones: portada, autoplay online y gestos/reordenamiento de cola.

Las comprobaciones usan exclusivamente verification sin cuenta. MusicBox pruebas
no se actualizó: sigue pendiente copiar la clave del Asus descrita en
`FIRMA_PRUEBAS_DOS_EQUIPOS.md`. Estos cambios todavía no están en esa instalación.
