# Plan de mejoras de MusicBox

## Objetivo y alcance

Entregar una experiencia consistente, rápida, fluida, moderna y reactiva. La web en PC debe reproducir la apariencia, comportamiento y funciones del escritorio. La web móvil debe reproducir la app Android, incluidos navegación, gestos, herramientas, animaciones y ajustes. No basta con reducir el tamaño de la interfaz de PC.

La paridad es un requisito transversal: cada cambio se verifica también en su equivalente web. Se reutilizarán los componentes y patrones existentes. Las restricciones reales del navegador se documentarán con su alternativa; no se darán por resueltas funciones ausentes ni diferencias evitables.

La app Android conserva los comportamientos y estilos que ya funcionan. Las capturas del usuario y el comportamiento aprobado son referencias de aceptación.

## Trabajo ya realizado

- Integración local de portadas animadas de Windows y Android en `main`, commit `39fc198`, a partir de `94a4740`.
- Implementación de los requisitos en Windows, Android y web, con pruebas y compilaciones locales. Android conserva su presentación existente.
- Las portadas animadas se verificaron en el Samsung mediante la variante aislada `verification`; se corrigió un fallo del extractor de video del sistema mediante Media3.
- El [informe de resultados](RESULTADO_MEJORAS_MULTIPLATAFORMA.md) registra lo implementado, cifras de pruebas, compilaciones y límites. La [matriz de paridad](PARIDAD_WEB.md) distingue implementación de certificación visual completa.
- No se ha publicado una versión ni actualizado la instalación principal o el sitio en producción.

## Requisitos completos

1. **Reproductor compacto de PC.** Barra como la referencia web, conservando todos los controles: canción y portada, favorito, aleatorio, anterior, reproducción, siguiente, repetición, video cuando exista, letras, cola, más opciones, volumen y acceso a la vista grande. Distribución adaptable sin solapamientos ni controles inaccesibles.
2. **Reproductor grande.** La flecha abre una vista de reproducción semejante a Android, adaptada a PC, con portada grande, información, controles y letras. Al volver se conserva la sección, playlist y desplazamiento; el audio continúa. Replicar en web de PC.
3. **Espacio de biblioteca.** Reducir rellenos y marcos negros innecesarios, especialmente abajo, para mostrar más canciones. No reservar altura para letras o contenido inexistente. Replicar en web de PC.
4. **Conteo de reproducciones.** En las cuatro experiencias, registrar una reproducción después de 30 segundos acumulados de escucha real durante esa reproducción. Pausas, buffering y saltos de posición no aportan tiempo. Reanudar no duplica el conteo; una nueva reproducción puede contar nuevamente si supera el umbral. Verificar repetición, recuperación del stream y sincronización. Conservar estadísticas históricas; no inventar una corrección retroactiva sin datos suficientes. Comprobar explícitamente cómo tratar pistas de menos de 30 segundos, manteniendo el umbral acordado mientras no se defina una excepción.
5. **Ir a canción actual.** Recordar el origen de reproducción. Abrir su playlist, desplazar hasta la canción y resaltarla aunque el usuario haya navegado a otra sección. Si procede de una carpeta, usar esa carpeta; si no existe un origen accesible, usar la vista disponible apropiada. No escoger una playlist arbitraria por pertenencia. Verificar escritorio, Android y sus equivalentes web.
6. **Refrescar.** Revisar todos los botones de actualización, no solo el de playlist. En PC el fallo está reportado; verificar web y Android. Cada botón debe actualizar su contenido correspondiente, mostrar spinner mientras trabaja y terminar con un estado comprensible de éxito, ausencia de cambios o error. Evitar tareas simultáneas duplicadas; preservar selección y desplazamiento cuando corresponda. Separar refrescar la biblioteca local de actualizar una playlist importada o recomendaciones; no alterar playlists reales durante las pruebas.
7. **Iconos de menús.** Añadir el de Playlist settings y completar las opciones sin icono en los desplegables. Usar el sistema de iconos existente, alineación y acento del tema. Aplicar a ambos pares de plataformas.
8. **Tarjetas de carpetas.** En escritorio y web de PC, igualar dimensiones, composición y espaciado con las tarjetas de playlists. La web móvil sigue el diseño Android.
9. **Marco señalado.** Identificar el marco exacto de las capturas durante la revisión visual y retirarlo en escritorio y web de PC. No eliminar indiscriminadamente bordes de foco o separadores útiles.
10. **Tema y acento.** Corregir colores azules fijos que no respetan el acento naranjo. Revisar controles, estados, iconos, indicadores y diálogos en todas las plataformas. Conservar colores semánticos necesarios y no recolorear las imágenes. Verificar otros temas y preservar Android donde ya esté correcto.
11. **Portadas completas.** Mostrar toda la imagen en tarjetas de playlists y carpetas, sin recortar ni deformar. Resolver imágenes cuadradas, horizontales y verticales con espacio restante integrado al tema. Mantener la paridad de cada pareja de interfaces.
12. **Indicador de descarga.** Quitar el icono superpuesto sobre las portadas señalado por el usuario. Mantener disponibles las acciones de descarga y su estado en los controles apropiados. Reflejar el cambio en web de PC; en móvil mantener la paridad con Android.
13. **Autoplay de playlists.** Al terminar una playlist, continuar con canciones relacionadas si autoplay está activo. Usar el conjunto de canciones y artistas como contexto, no solo la última pista. Reutilizar y mejorar el recomendador existente; combinar afinidad, variedad, exclusión de duplicados y reproducción reciente, y recuperación ante candidatos no reproducibles. Precargar candidatos antes del final sin modificar la playlist original. Respetar cola manual y modos de repetición. YouTube es una referencia de experiencia, no una garantía de equivalencia algorítmica. Aplicar en todas las plataformas.
14. **Crossfade.** Transición configurable con interruptor y duración en segundos: atenuar la canción saliente mientras entra la siguiente, preparando el audio antes. Aplicar a reproducción normal, playlists y autoplay. Mantener volumen equilibrado; gestionar pausa, seek, salto manual, cambio de salida, fin de cola y pistas cortas. Evaluar convivencia con video y restricciones del navegador; mantener reproducción estable cuando no pueda prepararse la siguiente pista. Implementar en todas las plataformas.
15. **Diálogos.** En escritorio y web de PC, tomar como referencia la ventana de Configuración mostrada: fondo desenfocado y oscurecido, sin velo blanco ni franjas claras arriba y abajo. Unificar cabecera, cuerpo, pie, márgenes y esquinas; respetar el tema y mantener buen rendimiento del desenfoque. La web móvil reproduce los diálogos Android.
16. **Gestos y memoria de navegación.** Android y web móvil: al salir de una playlist hacia Mi música deslizando a la izquierda, deslizar a la derecha debe volver a esa misma playlist y conservar la posición. Mantener los gestos entre tabs y playlists sin conflictos con scroll vertical, carruseles ni controles de audio.
17. **Portadas animadas.** Validar la integración existente en PC y Android y trasladar el comportamiento a sus equivalentes web. Conservar portada estática si no hay animación, falla la red o el formato no es compatible. Sin audio adicional; liberar recursos al cambiar de canción, ocultar la vista o cerrar. Verificar consumo de recursos y evitar solicitudes redundantes.

## Orden de implementación

1. **Inventario y base de comparación.** Revisar grafos y cobertura antes de explorar código, registrar el estado Git sin alterar cambios ajenos y levantar una matriz de pantallas, funciones y estados de los cuatro entornos. Revisar la integración de portadas ya hecha. Comparar las interfaces a igual tamaño y escala.
2. **Correcciones de datos y navegación.** Conteo de 30 segundos, origen de reproducción, localizar canción, refrescar y memoria de gestos. Cubrir los fallos con pruebas de comportamiento y datos aislados.
3. **Diseño de escritorio y web de PC.** Reproductor compacto y grande, biblioteca, tarjetas, portadas, indicadores, iconos, tema y diálogos. Construir estados completos de carga, vacío y error, además del estado normal.
4. **Paridad Android y web móvil.** Replicar pantallas, funciones, navegación y gestos de Android. Comprobar que los cambios compartidos no dañen comportamientos existentes. Revisar tamaños móviles y tabletas, orientación y selección del diseño adaptable.
5. **Autoplay.** Afinidad con la playlist completa, variedad, precarga y continuidad; comprobar interacción con cola y repetición.
6. **Crossfade.** Implementar sobre una reproducción y cola ya verificadas. Probar combinaciones de pistas locales y streaming, disponibilidad de audio y continuidad en cada plataforma.
7. **Portadas animadas y rendimiento.** Completar paridad web, revisar ciclo de vida y fallback, y medir la experiencia con los componentes finales. Optimizar los cuellos de botella observados.
8. **Verificación integral y compilación.** Pruebas automatizadas pertinentes, comparación visual y de interacción por parejas, compilación de escritorio/web y variante Android verification. Entregar resultados y limitaciones pendientes con claridad.

## Criterios de aceptación y pruebas

- Comparar PC nativo con web de PC, y Android con web móvil, a tamaños equivalentes: distribución, tipografía, espaciado, iconos, colores, menús, diálogos, animaciones y estados interactivos. Mantener una matriz explícita de paridad; no declarar igualdad por una sola captura.
- Probar ventanas pequeñas y grandes, móvil, orientación, zoom y escalado de pantalla; sin controles tapados, desbordamientos ni cambios inesperados de interfaz.
- Comprobar reproducción local y streaming, cambio rápido de pista, pausa/reanudación, adelanto, repetición, cola manual, autoplay, fin de playlist, fallos de red y cierre durante tareas pendientes.
- Medir tiempos de respuesta y bloqueos antes y después en los dispositivos disponibles. Evitar E/S pesada en el hilo de interfaz, trabajo redundante, saltos de layout y crecimiento de memoria al navegar o reproducir muchas canciones.
- El spinner debe aparecer mientras exista una operación real; las acciones deben dar respuesta inmediata y terminar en un estado visible. No simular actualizaciones ni ocultar errores.
- Mantener listas virtualizadas, carga asíncrona y cachés existentes donde sean adecuadas. Ajustar animaciones y desenfoque con mediciones, sin añadir efectos que vuelvan lenta la interfaz.
- Verificar accesibilidad: foco visible, teclado en PC, etiquetas de controles, contraste y objetivos táctiles; conservar las preferencias de movimiento reducido donde existan.
- Usar únicamente datos sintéticos o temporales. En dispositivos Android, solo verification (`cl.arichard.descargador.verification`), sin cuenta ni Firebase real. No modificar playlists, historial ni estadísticas reales como prueba.
- Tras modificar código, ejecutar `graphify update .`. Seguir `commit.md`, mantener commits en español y excluir credenciales, multimedia personal, artefactos de compilación y cambios ajenos.
- No publicar, etiquetar como estable ni cambiar versiones como parte de esta implementación sin una solicitud de publicación. Si luego se publica, partir de la última versión publicada e incrementar solo el último número, manteniendo todas las versiones alineadas.

## Ejecución y comunicación

Resolver decisiones rutinarias con los patrones del proyecto y las referencias acordadas, sin pedir confirmaciones por cada cambio. Mantener actualizaciones breves sobre resultados y bloqueos reales. No dar una función por terminada sin verificar su equivalente web y sus estados de error. Si una limitación técnica requiere cambiar una función acordada, exponer el caso concreto antes de asumir una solución incompatible.
