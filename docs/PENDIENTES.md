# Pendientes y estado del trabajo

Actualizado el 28 de octubre de 2026. Versión 2.3.4 publicada para Android y Windows.

Informe de compilaciones, pruebas, firmas y descargas verificadas:
[`PUBLICACION_2.3.4.md`](PUBLICACION_2.3.4.md).

## Cierre de la revisión y trabajo entre equipos

- Se corrige además la repetición de copias de la misma canción en «Más escuchadas»
  sin borrar archivos, alterar playlists ni sumar los contadores de cada copia.
  Suite ampliada: 176 unitarias Android y 13 escenarios físicos de regresión
  correctos; ver `DUPLICADOS_MAS_ESCUCHADAS.md`.
- Autoplay de «Thunder»: se confirmó que la radio del videoclip devuelve solo
  OMV y el filtro descartaba todas las entradas. Android y Windows resuelven ahora
  la versión de audio de la misma canción/artista y consultan su radio una vez.
  No se relaja el filtro ni se modifica ninguna playlist guardada.
- 172 unitarias Android correctas; APK verification, instrumentación y preview
  compilados con firma validada. Lint: cero errores y 203 advertencias, sin ocultarlas.
- 28 escenarios físicos correctos juntos, sin omisiones ni reintentos (152,670 s),
  incluidos «Thunder» y Luis Jara con reproducción y avance automático.
- Seis fases de recuperación tras cierre de proceso repetidas y correctas desde
  Inicio y Descargador; MusicBox pruebas actualizada sin borrar datos.
- 392 pruebas de escritorio correctas en Python 3.12 y en `.venv` Python 3.10. El aviso de PySide6 al cerrar
  continúa como deuda externa conocida, no como una corrección terminada.
- La clave ASUS ya coincide, está configurada fuera de Git en el PC y la app de
  pruebas se actualizó sin desinstalarla. El usuario confirmó copiarla y renombrarla
  también en ASUS; allí falta recibir este código mediante `git pull --ff-only`.
- Guía entre equipos y límites de validación: `CIERRE_REVISION_20261028.md`.

## Implementado y comprobado

- Carga de biblioteca sin el hueco anterior al shimmer ni dependencia de la cola
  de actualización de YouTube; caché validada de metadatos y avisos de descarga
  breves. Detalles y alcance en `CARGA_BIBLIOTECA_Y_AVISOS.md`.
  La suite posterior llega a 168 pruebas unitarias; 14 escenarios físicos
  relacionados pasaron juntos en 74,531 segundos sobre el APK actualizado.
- Recuperación de descargas Android, incluidas cancelaciones persistentes,
  conservación de numeración y reanudación HTTP de archivos parciales.
- Orientación vertical en las pantallas propias de Android.
- Minirreproductor en artistas y álbumes, recientes debajo del buscador,
  selectores con portadas, filtros del importador y accesos de colecciones
  corregidos, con tarjetas de altura uniforme.
- Arranque de Windows sin configuración Firebase válida y prueba QML con portada
  local para eliminar su dependencia de una imagen ficticia de YouTube.
- 388 pruebas de escritorio correctas en la ejecución final correspondiente.
- 158 pruebas unitarias Android correctas, incluidas seis regresiones de autoplay.
  La corrección posterior de parpadeos amplía la suite a 164; véase
  `ACTUALIZACIONES_SIN_PARPADEO.md` para dispositivos, estadísticas, portadas y nube.
  Los 21 escenarios de interfaz del
  teléfono pasaron juntos, sin reintentos ni casos omitidos, en la ejecución
  inalámbrica posterior al pull (142,077 segundos), incluido el ensayo online.
- Seis fases de recuperación correctas: desde Inicio después de reiniciar
  físicamente el teléfono y desde el descargador después de terminar el proceso.
  Se comprobaron reanudación HTTP, cancelación y ausencia de duplicados.

Los detalles están en `VERIFICACION_RECUPERACION.md` y
`VERIFICACION_INTERFAZ_ANDROID.md`. Las cifras de la primera revisión preceden
a las ampliaciones de interfaz y no sustituyen sus resultados posteriores.

## Pendientes técnicos

- [x] Revisar las advertencias de lint por categorías y corregir etiquetas,
  configuración regional y asignaciones durante dibujo. Los avisos restantes
  siguen registrados: revisar no equivale a eliminarlos todos.
- [x] Corregir la lectura de metadatos Kotlin de Firebase con lint 9.1.0,
  manteniendo AGP y Gradle. La comprobación final no muestra la incompatibilidad.
- [x] Investigar el `ResourceWarning`: se reproduce con una sola
  `PySide6.QtCore.Property`, sin código de MusicBox, en Python 3.10 y 3.12.
- [x] Añadir fase y estado del reproductor al diagnóstico de esperas online;
  usar reloj monotónico. El escenario pasó en hardware dentro de la suite conjunta.
- [ ] Resolver las advertencias de lint restantes y la deprecación de Gradle
  `null-attribute-lookup`. No se añaden supresiones ni una línea base para ocultarlas.
- [ ] Verificar una solución de PySide6 al aviso de cierre; la causa está
  reproducida, pero el aviso persiste y no se filtra.

Las correcciones y sus límites se detallan en `REVISION_POST_PULL.md`.

Validación posterior al pull: 388 pruebas de escritorio en Python 3.10 y 3.12,
152 unitarias Android y ambos APK verification correctos en esa revisión;
la corrección posterior de autoplay amplía las unitarias a 158. Lint termina con cero
errores y 203 advertencias. Tras conectar el teléfono por ADB inalámbrico, los
21 escenarios físicos también pasaron en una ejecución conjunta.

La corrección posterior de autoplay y los márgenes de la portada, junto con sus
comprobaciones específicas, se documentan en `CORRECCION_AUTOPLAY_COLA.md`.

## Verificaciones que faltan

- [ ] Confirmación manual del usuario de inicio de sesión y funcionamiento con
  su cuenta después de corregir la autorización de la clave API. Se comprobó
  que la solicitud deja de bloquearse, sin automatizar operaciones sobre una
  cuenta ni modificar playlists reales como parte de pruebas.
- [x] Recuperación después de reiniciar físicamente el teléfono, con autorización
  del usuario. Se verificó un identificador de arranque diferente, reconexión
  inalámbrica del mismo teléfono y recuperación tras desbloquearlo.
- [ ] Validación en otros dispositivos y versiones de Android. Las comprobaciones
  físicas de esta sesión corresponden al Samsung SM-G985F con Android 13.

## Firma, configuración y publicación

La clave ya fue transferida y validada en el PC. Procedimiento entre equipos:
[Firma de pruebas entre dos equipos](FIRMA_PRUEBAS_DOS_EQUIPOS.md).

- [x] Copiar al PC la clave original de ASUS, validarla y configurar preview.
  El usuario confirmó colocar la misma clave en la ruta de Gradle de ASUS.
  Tras recibir este código, `verifyPreviewSigningIdentity` comprueba la firma
  también al compilar allí. No se reemplaza la clave debug general.
- [ ] Conservar y respaldar fuera de Git la clave de firma de pruebas que se
  quiera reutilizar entre equipos. Una firma nueva exige reinstalar esa variante
  o disponer de la clave anterior para actualizarla conservando su instalación.
- [x] Publicar 2.3.4 tras confirmar la base pública 2.3.3, alinear versiones y
  comprobar compilaciones y pruebas. Etiqueta `v2.3.4`, APK firmado, instalador
  y portable públicos con sumas SHA-256 verificadas. Se comprobó la continuidad
  del certificado Android. No se publica la clave privada.
- [ ] Preparar futuras publicaciones solo cuando se soliciten; la siguiente
  después de 2.3.4 es 2.3.5 salvo indicación explícita distinta.

## Cambios realizados fuera del código

- Se compiló e instaló la variante aislada **MusicBox verificación** para las
  pruebas automatizadas, sin cuenta ni configuración Firebase real.
- Para uso manual con cuenta se compiló **MusicBox pruebas** con la configuración
  Firebase suministrada por el usuario. La diferencia de firma obligó a
  reinstalarla, con autorización explícita y respaldo previo. Se restauraron y
  verificaron sus cinco archivos externos. La app principal no se reemplazó.
- La nueva huella SHA-1 se registró para el cliente Android de pruebas. Después
  se añadió ese mismo paquete y certificado a las aplicaciones permitidas de la
  clave API de Android mediante `gcloud services api-keys update --append`.
  Se verificó que se conservaran los clientes y las restricciones de APIs
  anteriores, y que Firebase dejara de responder con bloqueo de aplicación.
- La configuración descargada, claves de firma, APK, respaldos, credenciales,
  muestras de audio y registros locales permanecen fuera del repositorio.
