## Formato de commits

Usa siempre este formato exacto. Sin mencionar a Claude ni a ningún asistente de IA, y sin co-autores.

```
feat: MusicBox vX.X.X — descripción corta

════════════════════════════════════════
NombreArchivo.kt
════════════════════════════════════════
- Cambio 1
- Cambio 2

════════════════════════════════════════
OtroArchivo.py
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
- OtroArchivo.py

════════════════════════════════════════
CAMBIOS FUERA DEL CODIGO (ej. cambios en la nube, en la publicación o en infraestructura)
════════════════════════════════════════
- firestore.rules desplegadas con las nuevas reglas de sincronización
- Etiqueta vX.X.X subida: los workflows de publicación generan el instalador de Windows, el ZIP portable y el APK
```

### Reglas

- Título en español, formato `feat: MusicBox vX.X.X — ...` (también `fix:`, `chore:` o `style:` según el cambio).
- Una sección por archivo modificado, con el separador `════...════`.
- Descripciones en español, en primera persona del plural o impersonal.
- Sin línea `Co-Authored-By` ni mención a asistentes de IA.
- Usar siempre `git add .` antes de commitear para no perder archivos.
- No borrar ni truncar texto del commit que el usuario haya redactado.
