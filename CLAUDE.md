@AGENTS.md


<!-- graphify:start -->
## Graphify & Knowledge Graph (graphify-out/)

Este proyecto cuenta con un grafo de conocimiento indexado en graphify-out/ que mapea todas las funciones, clases, componentes y relaciones del código.

### Reglas obligatorias para asistentes de IA:
1. **Consultas sobre el código:** Antes de hacer lecturas masivas de archivos o búsquedas ciegas (grep), ejecuta graphify query "<pregunta>" para obtener el contexto exacto. Usa graphify path "<A>" "<B>" para rastrear dependencias entre módulos y graphify explain "<concepto>" para analizar componentes específicos.
2. **Sincronización tras modificar código:** Tras crear, modificar o eliminar archivos de código, ejecuta siempre:
   `ash
   graphify update .
   `
   (o invoca /graphify --update). Este comando actualiza el grafo de forma incremental en 2 a 5 segundos (análisis determinista mediante AST local, sin consumo de tokens de LLM ni llamadas a APIs).
<!-- graphify:end -->
