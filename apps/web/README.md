# MusicBox Web

Interfaz web basada en MusicBox Windows para escritorio y MusicBox Android para celular. Frontend sin compilador, backend FastAPI y despliegue Docker.

## Desarrollo

Desde la raíz del repositorio:

```powershell
.venv\Scripts\python.exe -m pip install -r apps/web/backend/requirements.txt
Copy-Item apps/web/frontend/runtime-config.example.js apps/web/frontend/runtime-config.js
.venv\Scripts\python.exe -m uvicorn api:app --app-dir apps/web/backend --host 127.0.0.1 --port 8015
```

Abrir `http://127.0.0.1:8015/?verification=1` para probar. Esta modalidad usa `MusicBoxVerification`, desactiva Firebase y no registra un service worker. Los archivos importados, favoritos y playlists de prueba quedan separados de la biblioteca habitual.

`runtime-config.js` contiene la configuración pública del proyecto Firebase de despliegue. No guardar credenciales, cookies ni cuentas personales en Git. Sin configuración se puede continuar como invitado.

## Pruebas

```powershell
node --test tests/web/core.test.js
.venv\Scripts\python.exe tests/web/test_api.py
node tests/web/playwright_checks.js
node tests/web/experience.test.js
node tests/web/youtube_fallback.test.js   # necesita conexión a YouTube
```

Las pruebas de navegador requieren Playwright y Chromium. `MUSICBOX_PLAYWRIGHT_MODULE` permite indicar una instalación externa sin añadir dependencias de desarrollo a la aplicación. Ambas pruebas crean y cierran su servidor local automáticamente. `MUSICBOX_TEST_URL` permite dirigir la primera a una instancia preparada en modo aislado. Los resultados y capturas van a `apps/web/.test-artifacts/`, excluido de Git.

## Despliegue

Desde `apps/web`, `docker compose up -d --build`. El contenedor publica únicamente `127.0.0.1:8005`; Nginx ofrece HTTPS. Antes de sustituir el proyecto del servidor, guardar una copia completa y validar la nueva imagen en otro puerto.

El reproductor del navegador usa /api/stream para audio en línea y los archivos guardados en IndexedDB para escuchar sin conexión. El modo MP4 usa el mismo marco en la interfaz y solicita vídeo con ?video=1. Si el servidor no consigue el audio o el clip (por ejemplo, porque YouTube bloquea su IP), la web reintenta una vez y sigue con el reproductor oficial de YouTube (`ytmedia.js`) desde el mismo segundo; lo recuerda 10 minutos para no esperar al servidor en cada canción. En ese modo no hay crossfade. Las descargas y la auto-descarga sí dependen del acceso del servidor al proveedor: tras tres rechazos seguidos la auto-descarga se pausa, avisa y se reintenta a los 10 minutos. Verifica la reproducción y las descargas con canciones reales desde el servidor de destino antes de publicar (`docker logs musicbox_web` muestra el motivo de cada 503).

Las cookies del servidor se administran fuera de la interfaz pública. El endpoint de carga exige `MUSICBOX_ADMIN_TOKEN` por cabecera Bearer; por defecto está deshabilitado. `MUSICBOX_COOKIE_PATH` permite usar un archivo privado persistente. No compartir cookies personales a través de una web pública.

Las descargas ofrecen MP3 y MP4 con calidad elegida y preparan archivos temporales que se eliminan después de entregarlos o terminar un trabajo cancelado. Las pruebas de contrato no certifican la disponibilidad actual del proveedor en producción.

El módulo `backend/playlist_import.py` se copió de la implementación Windows de este repositorio; al cambiar el emparejamiento nativo hay que mantener ambas copias alineadas.

## Alcance y diferencias

La biblioteca local del navegador requiere importar archivos o carpetas mediante el selector del sistema. El navegador no obtiene acceso automático a las carpetas que utiliza la aplicación Windows. La sincronización mantiene los identificadores `youtube:…`, metadatos y entradas locales de otros equipos; esas entradas se conservan aunque no sean reproducibles aquí.

Consultar [el seguimiento de paridad](../../docs/PARIDAD_WEB.md) para requisitos, evidencia y pendientes de comprobación.
