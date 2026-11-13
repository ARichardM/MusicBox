# MusicBox Web — Despliegue, Arquitectura y Paridad con Desktop

Este documento detalla la arquitectura, configuración en producción y auditoría de paridad 1:1 de la plataforma web de MusicBox desplegada en producción.

---

## 1. Infraestructura y Despliegue en Producción

- **URL de Producción:** [https://openmusicbox.duckdns.org/](https://openmusicbox.duckdns.org/)
- **Landing Page de Presentación:** [https://openmusicbox.duckdns.org/landing.html](https://openmusicbox.duckdns.org/landing.html)
- **Servidor:** Servidor VPS OVH (`144.217.240.183`).
- **Aislamiento y Recursos:**
  - El contenedor Docker `musicbox_web` corre en `127.0.0.1:8005` con límites estrictos de memoria (**512 MB RAM**, consumiendo ~151 MB, <30%).
  - Las aplicaciones preexistentes de producción en el VPS (`ftracker` y `mc_server`) se mantuvieron completamente operativas e intactas.
- **Proxy Inverso y SSL:**
  - Nginx 1.24 gestiona el proxy inverso con HTTP/2, compresión Gzip y headers de seguridad.
  - Certificado SSL Let's Encrypt administrado mediante Certbot con renovación automática.

---

## 2. Autenticación y Sincronización en la Nube

- **Google OAuth Oficial con Firebase Auth:**
  - Se autorizó el dominio `openmusicbox.duckdns.org` en el proyecto Firebase oficial de MusicBox (`musicbox-813e3`) a través de la API Identity Toolkit de Google Cloud.
  - Se implementó el flujo de autenticación emergente (`signInWithPopup`) con el proveedor de Google, obteniendo credenciales reales y datos de perfil (nombre, correo y avatar).
- **Sincronización Bidireccional en Firestore:**
  - Documento de estado: `users/{uid}/state/musicbox`.
  - Al iniciar sesión, se leen automáticamente playlists, favoritos y preferencias de apariencia (modo oscuro y color de acento).
  - Al agregar o modificar favoritos y playlists, los cambios se replican inmediatamente en Firestore para sincronizarse con Desktop y Android.
- **Modo Invitado:**
  - Permite navegar, escuchar y probar la aplicación sin iniciar sesión, persistiendo los datos de manera privada en el almacenamiento local del navegador (IndexedDB).

---

## 3. Motor de Reproducción Híbrido

- **Reproducción Online:** Utiliza el reproductor incrustado de YouTube (IFrame Player API) alojado en un mini dock flotante (`#video-dock`). Esto garantiza la reproducción fluida conectando directamente desde el navegador del usuario a YouTube, evitando los bloqueos por bots asociados a las IPs de datacenters.
- **Reproducción Offline:** Implementada mediante Web Audio API / HTML5 Audio Blob URLs guardadas en IndexedDB (`MusicBoxDB`), permitiendo reproducción sin conexión a internet.

---

## 4. Auditoría de Paridad 1:1 con Desktop (11 Módulos)

1. **Módulo 1: Login & Autenticación:** Logo BrandMark con ondas sinusoidales matemáticas idénticas a `Main.qml:130`, botón Google oficial 260x50, botón invitado y aviso de descargas locales.
2. **Módulo 2: Barra Lateral (Sidebar):** 230px expandido / 76px colapsado, botón flotante de alternancia (`top: 74px; right: -17px`), accesos directos principales y eliminación del indicador espurio "En línea".
3. **Módulo 3: Vista de Inicio (Home):** Buscador de 62px con botón de limpieza y búsqueda, filtros de categorías (`Todos`, `Canciones`, `Videos`, `Artistas`), secciones "Artistas para ti", "Para ti" (tarjetas 172x252px) y páginas dedicadas de Artista y Álbum.
4. **Módulo 4: Mi Música:** Buscador en cabecera *"Buscar en tu música"* (`Main.qml:403`), botón `+ Nueva playlist`, filtros rápidos y modal de asignación de canciones.
5. **Módulo 5: Descargas:** Resumen de almacenamiento en MB, almacenamiento en IndexedDB y borrado de descargas sin conexión.
6. **Módulo 6: Historial:** Listado de reproducciones recientes, contador individual de clics/reproducciones y botón para limpiar historial.
7. **Módulo 7: Reproductor Inferior:** Carátula 56px, marquesina de título, corazón de favoritos, controles completos (aleatorio, anterior, reproducir/pausar, siguiente, repetir), barra interactiva de progreso con tiempos y control deslizante de volumen.
8. **Módulo 8: Paneles Deslizables (Drawers):** Drawer de letras sincronizadas con karaoke interactivo y drawer de cola de reproducción.
9. **Módulo 9: Video Dock Flotante:** Mini reproductor dockeado en esquina inferior derecha para visualización y streaming directo.
10. **Módulo 10: Configuración:** Modal con tarjeta de perfil, conmutador de tema claro/oscuro, botones para acento `Azul` (#0084FF) / `Naranjo` (#FF7A00), gestor de `cookies.txt` para yt-dlp y cierre de sesión.
11. **Módulo 11: Diálogo de Estadísticas:** *"Tu música en cifras"*, tarjetas de tiempo acumulado y reproducciones, y ranking de canciones con barras de porcentaje proporcionales.
