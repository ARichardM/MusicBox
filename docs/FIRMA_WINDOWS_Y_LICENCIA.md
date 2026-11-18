# Licencia de MusicBox y firma de Windows

Revisión del 12 de noviembre de 2026 sobre MusicBox v2.4.3.

## Estado de MusicBox

- El repositorio es público y el README de v2.4.3 indica que MusicBox todavía no
  declara una licencia. La visibilidad del código permite consultarlo. Para
  publicarlo como código abierto hay que conceder los permisos mediante una
  licencia explícita.
- Se comprobó en la cabecera del `MusicBox-Windows-Setup.exe` publicado que no
  tiene firma Authenticode incorporada. La publicación incluye archivos SHA-256
  para verificar la integridad de la descarga. Authenticode permite además que
  Windows compruebe quién firmó el ejecutable.
- El [flujo de publicación de Windows](../.github/workflows/release-windows.yml)
  ya compila `MusicBox.exe` con PyInstaller, genera el instalador con Inno Setup
  y publica el instalador y el ZIP portable en GitHub Releases. El
  [instalador](../packaging/windows/MusicBox.iss) empaqueta los mismos archivos
  de la aplicación que se distribuyen en el ZIP portable.

## Referencia de RiMusic

El RiMusic original de `fast4x` surgió como un fork de ViMusic, publicó su código
bajo GNU GPL v3 y quedó archivado el 30 de julio de 2025. Su repositorio contiene
un script que usa un almacén de claves para firmar paquetes Android y otro que
genera un MSI con Gradle. La revisión no confirmó que el proyecto publicara un MSI
firmado con Authenticode ni que usara SignPath. El certificado de Android sirve
para verificar los APK y sus actualizaciones. El instalador de Windows requiere
su propia firma.

[Repositorio de RiMusic](https://github.com/fast4x/RiMusic) ·
[Licencia GPL v3](https://github.com/fast4x/RiMusic/blob/master/LICENSE) ·
[Script Android](https://github.com/fast4x/RiMusic/blob/master/createapks.cmd) ·
[Script MSI](https://github.com/fast4x/RiMusic/blob/master/createDesktopPackageMsi.cmd)

## Qué revisar antes de solicitar SignPath

1. **Licencia del proyecto.** Si se decide abrir MusicBox, comprobar los derechos
   sobre las contribuciones y los recursos incluidos, elegir una licencia
   aprobada por la OSI y declararla en `LICENSE` y en el README. GPL v3 es la
   licencia que eligió RiMusic. La elección para MusicBox sigue pendiente.
2. **Dependencias del instalador.** El ZIP publicado incluye FFmpeg de Gyan,
   PySide6 y DLL de WebView2 y del runtime de Visual C++. Identificar las
   versiones exactas y revisar sus licencias y avisos de redistribución,
   incluido el acceso al código fuente correspondiente de FFmpeg. Consultar a
   SignPath si las DLL de Microsoft entran en su excepción para bibliotecas
   del sistema. La aceptación de esas DLL sigue sin confirmar.
3. **Política de firma y privacidad.** Publicar la política de firma que pide
   SignPath. El README ya describe qué sincroniza Firebase. Revisar cómo se
   informa al usuario durante la instalación y cómo puede usar la aplicación
   sin habilitar la sincronización.
4. **Compilación y publicación.** Si SignPath acepta el proyecto, integrar la
   firma en GitHub Actions con verificación del origen. Firmar `MusicBox.exe`
   antes de empaquetarlo y firmar el instalador final antes de calcular y
   publicar los SHA-256. Comprobar las firmas y probar el instalador en un
   equipo con la configuración habitual de seguridad de Windows.

La firma gratuita de SignPath depende de que la fundación apruebe el proyecto.
Si se decide distribuir MusicBox sin una licencia de código abierto, Microsoft
Store firma gratis los paquetes MSIX que aprueba para esa tienda.
La firma de un instalador EXE o MSI enviado a la tienda corre por cuenta del
publicador. SmartScreen también tiene en cuenta la reputación del archivo y
del firmante.

[MusicBox v2.4.3](https://github.com/ARichardM/MusicBox/releases/tag/v2.4.3) ·
[Requisitos de SignPath](https://signpath.org/terms.html) ·
[Integración con GitHub Actions](https://docs.signpath.io/trusted-build-systems/github) ·
[Licencia de FFmpeg](https://www.ffmpeg.org/legal.html) ·
[Opciones de firma de Microsoft](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options) ·
[Reputación de SmartScreen](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation)
