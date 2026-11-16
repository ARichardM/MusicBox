# Paridad de MusicBox Web

## Referencias y alcance

Windows es la referencia de la web en PC; Android es la referencia de la web móvil. El objetivo incluye apariencia, funciones, estados y navegación. La implementación local incorpora la base web de `origin/feat/color-primario` y las correcciones del [plan multiplataforma](PLAN_MEJORAS_MULTIPLATAFORMA.md).

## Matriz implementada

| Área | PC y web de PC | Android y web móvil |
| --- | --- | --- |
| Reproductor | Barra compacta de 96 px, controles, volumen, menú y vista grande; la biblioteca permanece montada al ampliar. | Minirreproductor y pantalla grande, cola, letras, controles y opciones. Se conserva el diseño Android existente. |
| Biblioteca | Tarjetas de playlists y carpetas de 196 × 196 px; portada completa; sin borde ni descarga superpuesta. | Colecciones en filas; búsqueda, orden, artistas y acciones disponibles. |
| Navegación | Localizar recupera el origen de la cola y ubica la canción. | Memoria de última colección: izquierda al salir de la última y derecha para regresar, con desplazamiento. |
| Datos | Umbral de 30 segundos reales; sin contar pausas, buffering ni seek. Refresco con actividad y errores. | Mismo umbral; Android mantiene la sesión al recuperar el stream. La web usa IndexedDB y sincronización por dispositivo. |
| Autoplay | Varias semillas de la playlist, afinidad, diversidad y exclusión de duplicados. Solo amplía la cola. | Android reutiliza RecommendationEngine; web combina recomendaciones de varias semillas. |
| Crossfade | Preferencia de 0 a 12 segundos; mezcla complementaria cuando la siguiente pista está preparada. | Ajuste equivalente; pausa, seek y selección manual cancelan la mezcla anterior. |
| Tema y ventanas | Acento del tema, iconos de acciones, fondo modal oscuro/desenfocado y cabeceras/pies uniformes. | Web respeta el tema y sigue las ventanas móviles. Los componentes Android existentes se conservan. |
| Portadas animadas | Resolución asíncrona, caché y portada estática de respaldo. | Android usa Media3 para evitar el fallo del extractor Samsung; web usa video MP4 silencioso y libera recursos al ocultarse. |
| Rendimiento | Listas virtualizadas y cargas asíncronas. | Web mantiene el diseño móvil al girar el teléfono; gestos separados de scroll, controles y carruseles. |

## Evidencia actual

Se verificaron Chromium a 1180 × 760, 900 × 620, 390 × 844, 360 × 740 y 844 × 390 táctil; además, la suite funcional cubrió 1280 × 800 y 768 × 1024. Se comprobaron audio sintético, navegación, actualización, portadas completas, menú con teclado, cola, playlists, descargas simuladas, letras, temas y PWA sin conexión.

La prueba de lista de 500 canciones mantiene menos de 50 filas renderizadas y permite ubicar la última canción. El crossfade se probó con audio real del motor HTML, ganancias complementarias, cancelación por seek y rechazo del reproductor siguiente sin interrumpir el actual.

Ver [resultados y compilaciones](RESULTADO_MEJORAS_MULTIPLATAFORMA.md) para cifras, comandos y límites. No se certifica identidad de píxeles de todas las pantallas a partir de estas pruebas; tampoco se atribuyen a esta ejecución pruebas históricas del VPS.

## Restricciones verificables

- El navegador requiere importar archivos/carpetas; no puede escanear automáticamente las carpetas privadas de Windows o Android. La sincronización conserva las entradas de otros dispositivos aunque sus archivos no sean accesibles aquí.
- Las descargas y el stream del servidor dependen del acceso permitido por YouTube. La reproducción incrustada no verifica esa disponibilidad. No se usaron cuentas reales ni se desplegó esta implementación en el VPS.
- El iframe de YouTube no permite mezclar su audio con precisión. La web usa el motor HTML y el endpoint de stream cuando crossfade está activado y no se está viendo video. Si la siguiente pista no está lista, se conserva la reproducción normal.
- Android y Windows también mantienen reproducción normal si no se preparó la siguiente pista. No se fuerza crossfade sobre video ni repetición de una sola canción.
- Las portadas animadas dependen de la disponibilidad del catálogo externo; siempre existe respaldo estático. La web respeta movimiento reducido y visibilidad de la página.
- Docker no está instalado en este equipo: se probaron la API y los archivos web, pero no se construyó la imagen Docker aquí.

## Desarrollo y pruebas

Consultar [apps/web/README.md](../apps/web/README.md). `/?verification=1` usa `MusicBoxVerification` y desactiva Firebase. Los tests de navegador habilitan el service worker explícitamente solo para probar el modo sin conexión en ese entorno aislado.
