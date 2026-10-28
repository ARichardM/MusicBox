# Publicación MusicBox 2.3.4

28 de octubre de 2026. Release pública y última versión estable:
https://github.com/ARichardM/MusicBox/releases/tag/v2.3.4

Base publicada comprobada: 2.3.3. Se incrementó únicamente el parche y se alinearon
Android (`versionCode=12`), Windows, instalador, metadatos PE y changelog.
La etiqueta anotada `v2.3.4` apunta al commit `e06c86d832aec617de60f6c409176dfcd37b3cdb`.

## Controles correctos

- 176 pruebas unitarias Android, sin fallos ni omisiones; lintRelease sin errores.
- 392 pruebas de escritorio en Python 3.12 sobre la versión 2.3.4.
- Trece escenarios físicos posteriores a la corrección de copias, sin reintentos
  ni omisiones (41,845 s). Regresión de duplicados repetida sobre el APK 2.3.4 de
  verification (4,242 s), correcta. No se prueban cuentas reales.
- Los 28 escenarios físicos y las seis fases de recuperación del cierre anterior
  están detallados en `CIERRE_REVISION_20261028.md`.
- CI de Android, escritorio (incluida auditoría de dependencias) y seguridad:
  correctos para el commit de distribución.
- Workflow Android correcto: https://github.com/ARichardM/MusicBox/actions/runs/40633779336
- Workflow Windows correcto: https://github.com/ARichardM/MusicBox/actions/runs/40633779043
- Windows ejecutó sus pruebas antes de empaquetar y probó `--version` y
  `--smoke-test` del ejecutable empaquetado, con interfaz aislada sin cuenta.
- Se descargaron los tres archivos de distribución y sus sumas; las tres coinciden.
- El instalador indica versión 2.3.4.0; el ejecutable dentro del ZIP indica 2.3.4.
- El APK supera apksigner, declara `cl.arichard.descargador`, versión 2.3.4 y código 12.
  Su certificado SHA-256 coincide con el APK público 2.3.3:
  `1df77cf2b585137d1643af21e0aceab642fa1d59ba454bb49750aa9a952f2987`.
  No se requiere cambiar de identidad ni desinstalar para actualizar.

## Descargas verificadas

| Archivo | SHA-256 |
| --- | --- |
| MusicBox-Android.apk | `4e7bf5ddc46974d928990fd93f3c50ffb6e079ec9973b69aeb9242bcf1c5e0ad` |
| MusicBox-Windows-Setup.exe | `4d5e65c30cd1212991d95964f9029e1d2426498af4f03fb055c945d6874734e4` |
| MusicBox-Windows-Portable.zip | `140446211010711dcbbb113151c9e58df764946bd2f87baaa713cf7fd6948a4c` |

Cada archivo incluye su descarga `.sha256`. Los binarios y registros locales se
conservan en `build/release234-validation`, fuera de Git.

## Cuenta y dos equipos

MusicBox pruebas quedó actualizada a `2.3.4-pruebas` con la clave compartida,
sin desinstalarla ni borrar sus datos. La corrección de «Más escuchadas» agrupa
solo su presentación: no elimina las copias ni cambia playlists o contadores.
La app principal no se reinstaló como prueba ni se automatizó una cuenta personal.

En ASUS, dentro del repositorio limpio: `git pull --ff-only`. La clave privada
ya copiada permanece fuera de Git; la compilación valida la identidad. No usar
reset si hay cambios locales. Guía: `FIRMA_PRUEBAS_DOS_EQUIPOS.md`.

La deuda técnica no bloqueante continúa explícita en `PENDIENTES.md`: advertencias
de lint, aviso externo de cierre de PySide6, compilación en ASUS y validación en
otros modelos Android. Publicado no significa que esas tareas estén resueltas.
