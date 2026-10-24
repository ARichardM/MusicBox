# Formato de commits de MusicBox

Usa siempre este formato exacto. No menciones asistentes de IA ni coautores.

```text
feat: MusicBox vX.X.X — descripción corta

════════════════════════════════════════
NombreArchivo.kt
════════════════════════════════════════
- Cambio 1
- Cambio 2

════════════════════════════════════════
OtroArchivo.kt
════════════════════════════════════════
- Cambio 1

════════════════════════════════════════
SEGURIDAD  (solo si aplica)
════════════════════════════════════════
- ...

════════════════════════════════════════
ARCHIVOS NUEVOS  (solo si aplica)
════════════════════════════════════════
- Archivo.kt — descripción

════════════════════════════════════════
ARCHIVOS MODIFICADOS
════════════════════════════════════════
- Archivo.kt
- OtroArchivo.kt

════════════════════════════════════════
CAMBIOS FUERA DEL CODIGO (solo si aplica)
════════════════════════════════════════
- Describir despliegues, migraciones o cambios de infraestructura.
```

## Reglas

- El título se escribe en español como `feat: MusicBox vX.X.X — descripción corta`.
- Se crea una sección por archivo modificado con el separador indicado.
- Las descripciones se redactan en español, en primera persona del plural o en forma impersonal.
- No se incluye ninguna línea `Co-Authored-By`.
- Se ejecuta siempre `git add .` antes de confirmar para no perder archivos.
- Nunca se borra ni se trunca texto de commit redactado por el usuario.
