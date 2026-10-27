# Pendientes y estado del trabajo

Actualizado el 27 de octubre de 2026. Cambios sin publicar sobre la base 2.3.3.

## Implementado y comprobado

- Recuperación de descargas Android, incluidas cancelaciones persistentes,
  conservación de numeración y reanudación HTTP de archivos parciales.
- Orientación vertical en las pantallas propias de Android.
- Minirreproductor en artistas y álbumes, recientes debajo del buscador,
  selectores con portadas, filtros del importador y accesos de colecciones
  corregidos, con tarjetas de altura uniforme.
- Arranque de Windows sin configuración Firebase válida y prueba QML con portada
  local para eliminar su dependencia de una imagen ficticia de YouTube.
- 388 pruebas de escritorio correctas en la ejecución final correspondiente.
- 149 pruebas unitarias Android correctas. Los 21 escenarios de interfaz del
  teléfono se comprobaron: veinte pasaron juntos y el ensayo online pasó al
  repetirlo aisladamente tras agotar su espera. La recuperación se comprobó
  adicionalmente en seis fases con interrupciones reales del proceso.

Los detalles están en `VERIFICACION_RECUPERACION.md` y
`VERIFICACION_INTERFAZ_ANDROID.md`. Las cifras de la primera revisión preceden
a las ampliaciones de interfaz y no sustituyen sus resultados posteriores.

## Pendientes técnicos

- [ ] Revisar las 259 advertencias de la última ejecución de lint Android.
  Incluyen accesibilidad, recursos, rendimiento, dependencias y recomendaciones
  sobre orientación bloqueada. Lint terminó sin errores; los avisos no se han
  considerado corregidos ni se han ocultado para presentar una validación limpia.
- [ ] Revisar la compatibilidad de los metadatos Kotlin de Firebase con el
  analizador de lint y las advertencias de deprecación de Gradle antes de cambiar
  herramientas. La compilación actual termina correctamente.
- [ ] Investigar el `ResourceWarning` de objetos no recolectados al cerrar la
  suite Python. Las pruebas pasan; falta determinar su causa y alcance.
- [ ] Mejorar el diagnóstico de las esperas agotadas en los ensayos con YouTube.
  Hubo incidencias transitorias; no se garantiza una pasada conjunta sin
  reintentos de los escenarios dependientes de la red.

## Verificaciones que faltan

- [ ] Confirmación manual del usuario de inicio de sesión y funcionamiento con
  su cuenta después de corregir la autorización de la clave API. Se comprobó
  que la solicitud deja de bloquearse, sin automatizar operaciones sobre una
  cuenta ni modificar playlists reales como parte de pruebas.
- [ ] Recuperación después de reiniciar físicamente el teléfono. Se verificó
  terminar el proceso y abrirlo de nuevo; no se reinició el dispositivo.
- [ ] Validación en otros dispositivos y versiones de Android. Las comprobaciones
  físicas de esta sesión corresponden al Samsung SM-G985F con Android 13.

## Firma, configuración y publicación

- [ ] Conservar y respaldar fuera de Git la clave de firma de pruebas que se
  quiera reutilizar entre equipos. Una firma nueva exige reinstalar esa variante
  o disponer de la clave anterior para actualizarla conservando su instalación.
- [ ] Preparar la siguiente publicación solo cuando se solicite: consultar la
  última versión publicada, incrementar el parche una unidad, alinear Android,
  Windows, instalador, metadatos y changelog, y repetir las comprobaciones y
  compilaciones de distribución necesarias. No se creó una etiqueta ni una
  Release para estos cambios; el changelog permanece en **Sin publicar**.

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
