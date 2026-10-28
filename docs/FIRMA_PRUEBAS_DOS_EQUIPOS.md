# Firma de pruebas compartida: Asus y PC

## Estado al 28 de octubre de 2026

Actualización: se recibió la clave del ASUS mediante un medio local y se comprobó
que coincide con la huella esperada. El script la configuró en el PC en
`%USERPROFILE%\.gradle\musicbox\preview.keystore` y `assemblePreview` terminó
correctamente con la validación de firma. Después se actualizó MusicBox pruebas
con `adb install -r --user 0`, sin desinstalar ni borrar datos, y se abrió para
uso manual del usuario. No se ejecutaron pruebas sobre su cuenta.
El usuario confirmó copiar y renombrar la misma clave en la ruta de Gradle del
ASUS. Falta que reciba este código y comprobar allí su compilación.

### Antecedentes del bloqueo, ya resuelto en el PC

Inicialmente quedó pendiente por decisión del usuario copiar la clave privada
debug del ASUS. No se desinstaló ni actualizó MusicBox pruebas en ese intento.
La instalación principal tampoco se reemplazó.

El APK preview se compiló correctamente antes de configurar la firma compartida,
pero su firma local no coincide con la instalada. Android rechaza actualizar un
mismo paquete con otra clave, aunque la versión y el código sean iguales.
La atribución al Asus se basa en el flujo de trabajo indicado por el usuario;
el script comprobará el archivo real antes de aceptarlo.

La configuración del proyecto ya exige una clave compartida para preview y
comprueba la huella SHA-256 de la instalación actual:

```text
f6016b5ea4f16f199746f8ac3d5740b5170d76fcb7ff7988229445698b819456
```

Se comprobó que rechaza una clave ausente y la clave distinta del PC.
`assembleVerification` sigue compilando correctamente. La ruta correcta se probó
posteriormente con la clave original: compilación e instalación preview aceptadas.

## Cómo retomarlo

1. En el Asus, localizar `%USERPROFILE%\.android\debug.keystore`. No generar
   otra clave ni sobrescribir ese archivo.
2. Copiar ese archivo de forma privada al PC, por ejemplo mediante USB.
   No incluirlo en Git ni enviarlo como texto por el chat.
3. Con estos cambios de código disponibles en ambos equipos, ejecutar desde la
   raíz del proyecto, usando la ruta real de la misma clave en cada equipo:

   ```powershell
   .\scripts\windows\configurar_firma_pruebas.ps1 -ClaveDelNotebook 'RUTA\debug.keystore'
   ```

4. El script verifica la huella y copia la clave a
   `%USERPROFILE%\.gradle\musicbox\preview.keystore`. Si se usa
   `GRADLE_USER_HOME`, la guarda en su subcarpeta `musicbox`.
   No modifica la clave debug general de otras aplicaciones.
5. Conectar el teléfono y ejecutar:

   ```powershell
   .\scripts\windows\install_android_preview.bat
   ```

6. Verificar que se actualice `cl.arichard.descargador.pruebas` conservando los
   datos. El instalador abre LoginActivity, que gestiona el acceso de la app.

Si el archivo del Asus no coincide, detenerse y localizar la clave correcta;
no generar otra ni desinstalar la app como sustituto de esa comprobación.
Hacer pull sincroniza el código, pero no copia la clave privada entre equipos.

## Respaldo local de este intento

En el PC, fuera de Git:

- `build/installed-preview-before-update.apk`: APK anterior.
- `build/preview-before-update-20261027/private.tar`: copia privada de `files`,
  `shared_prefs`, `databases` y `no_backup`.
- `build/preview-before-update-20261027/external`: 209 archivos externos,
  1.457.237.484 bytes transferidos correctamente.

Estos respaldos pueden contener una sesión y datos personales. No se publican
ni se restauran automáticamente: la app instalada conserva sus propios datos.
El respaldo tampoco sustituye a la clave privada original ni garantiza recuperar
secretos ligados al almacén de claves de Android después de una desinstalación.

La firma compartida afecta a preview. Verification, debug y release conservan
sus configuraciones anteriores; las pruebas automáticas siguen usando únicamente
verification, sin cuenta real.
