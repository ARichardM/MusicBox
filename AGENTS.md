# Instrucciones del repositorio

<!-- codebase-memory-mcp:start -->
For structural codebase exploration, use the installed `codebase-memory` skill.
<!-- codebase-memory-mcp:end -->

## Versiones y publicación

- La base es la última versión publicada, no una versión anotada en un commit de trabajo.
- La siguiente versión después de `2.3.2` es `2.3.3`.
- Por defecto solo se incrementa el último número en una unidad: `2.3.3` → `2.3.4`.
- No saltar números ni cambiar la versión menor o mayor sin una indicación explícita del usuario.
- Mantener iguales la versión Android, `apps/desktop/version.py`, el instalador, los metadatos de Windows y la sección nueva del changelog.
- Probar y compilar antes de publicar. No etiquetar como estable una versión con fallos conocidos pendientes de verificar.

## Commits

- Seguir el formato y las secciones definidos en `commit.md`.
- Títulos y descripciones en español, sin coautores automáticos.
- Incluir los cambios del trabajo solicitado; no añadir credenciales, multimedia personal ni artefactos de compilación.

## Pruebas con datos del usuario

- Las pruebas de dispositivo deben usar la variante `verification` (`cl.arichard.descargador.verification`), sin cuenta ni configuración Firebase real.
- No asumir que la variante `preview` está aislada: puede conservar una sesión real de instalaciones anteriores.
- No modificar ni reconstruir playlists reales como parte de una prueba.
