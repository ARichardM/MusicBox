# Revisión posterior al pull

27 de octubre de 2026. Base revisada: `38eefc9`; cambios sin publicar en 2.3.3.

## Correcciones

- Los filtros de canciones, carpetas y playlists, el reconocimiento de archivos y
  las comparaciones normalizadas usan `Locale.ROOT`. Cambiar a turco ya no rompe
  una búsqueda de `INDIE`. Las duraciones conservan sus dígitos ASCII también en
  árabe. Se añaden dos pruebas de regresión.
- Los botones con iconos tienen etiquetas localizadas. Los favoritos y la
  reproducción anuncian su acción actual. Portadas e iconos decorativos quedan
  fuera de la navegación accesible para no repetir los textos asociados.
- Los campos del importador tienen pistas en XML y la búsqueda de biblioteca
  declara el tipo de entrada.
- El skeleton reutiliza el gradiente y su matriz; el inicio reutiliza el aura,
  el rectángulo y los puntos de la onda. Se eliminan las diez advertencias de
  asignaciones en dibujo. Detener o separar el inicio cancela su salida
  programada; una nueva prueba comprueba que no se retire después de detenerlo.
- Las esperas de reproducción online usan reloj monotónico y muestran la fase,
  video, reproducción, carga, tamaño de cola, posición, duración y errores al
  agotar el tiempo. No se añaden reintentos que oculten una primera falla.
- CI usa `verification`, sin generar configuración Firebase, compila también
  el APK de instrumentación y conserva informes aunque falle una tarea.

## Herramientas

Se comprobó `lintVerification` con lint 9.1.0 y luego se fijó esa versión en
`gradle.properties`. Desaparecieron los diagnósticos de metadatos Kotlin 2.4 de
Firebase Auth. AGP 8.11.1, Gradle 8.14 y las dependencias de ejecución se conservan.
La [documentación de Android](https://developer.android.com/develop/ui/compose/tooling/lint)
describe la actualización independiente mediante `android.experimental.lint.version`.
Gradle advierte que esta propiedad es experimental.

Persiste la deprecación `null-attribute-lookup` al generar el modelo lint. La
traza de tareas la sitúa en la integración de herramientas, no en un script de
la app. No se ha suprimido ni se ha migrado el proyecto a Gradle 10.

## ResourceWarning de Python

Se reprodujo el aviso con una sola propiedad, sin importar MusicBox ni iniciar
reproductores, descargas, hilos o interfaces:

```python
from PySide6.QtCore import QObject, Property
X = type("X", (QObject,), {"value": Property(str, lambda self: "x")})
```

Ejecutado con `-W default`, informa un objeto no recolectable al salir en Python
3.10 y 3.12. Con `gc.DEBUG_UNCOLLECTABLE`, los objetos del cierre de la prueba QML
incluyen `PySide6.QtCore.Property`. Las suites completas pasan en ambos Python,
pero notifican 153 objetos al cerrar. Es reproducible fuera de la aplicación;
no se considera corregido ni se filtra el aviso. La
[documentación de Python](https://docs.python.org/3/library/gc.html#gc.garbage)
explica este diagnóstico de recolección al cerrar el intérprete.

## Límites

Al comenzar la revisión no había teléfono conectado; posteriormente el usuario
conectó el Samsung SM-G985F con Android 13 por ADB inalámbrico. Los 21 escenarios
físicos pasaron juntos, sin reintentos ni omisiones, en 142,077 segundos.
Las seis fases de recuperación también pasaron, incluyendo un reinicio físico
autorizado. No se probó inicio de sesión con una cuenta ni otros modelos/Android.

Android rechazó actualizar verification por una firma distinta. Se respaldaron
sus datos privados y sus nueve muestras externas en
`build/verification-wireless-backup-20261027`, fuera de Git. Solo se reinstalaron
verification y su APK de instrumentación; las nueve muestras se restauraron.
Las instalaciones principal y preview se conservaron, sin tocar sus cuentas ni
playlists. Se crearon las tres muestras públicas de eliminación después de
comprobar que no existían y el ensayo del sistema las eliminó correctamente.

El ensayo de reinicio identificó el mismo teléfono por su serie física y un
identificador de arranque nuevo. Tras el desbloqueo se restableció el puerto
loopback; los dos pendientes se completaron, el parcial usó HTTP Range y la
cancelación persistió. Una nueva apertura no modificó archivos ni duplicó el
historial. Desde el descargador se repitió con interrupción de proceso.

Al terminar se retiró `adb reverse` y se abrió verification con reproducción
en pausa; la captura local se revisó visualmente. No se publica una versión.

## Resultado final

- 388 pruebas de escritorio correctas con Python 3.10; repetidas y correctas con
  Python 3.12. Incluyen la ejecución de los controles JavaScript mediante Node.
- 152 pruebas unitarias Android correctas, sin fallos ni errores.
- `assembleVerification` y `assembleVerificationAndroidTest` correctos.
- 21 escenarios físicos juntos y seis fases de recuperación correctos,
  incluyendo reproducción automática online y reinicio físico.
- `lintVerification`: cero errores, 203 advertencias. Ya no aparecen
  `ContentDescription`, `DefaultLocale`, `DrawAllocation`, `LabelFor` ni
  `TextFields`. La cifra anterior documentada era 259; cambió también el
  analizador, por lo que la diferencia de totales no mide por sí sola las
  correcciones de código.
- `scripts/check_version.py`: 2.3.3 coherente; `git diff --check` correcto.

Revisión por categorías de los avisos que continúan:

| Grupo | Evaluación y trabajo que continúa |
| --- | --- |
| Recursos y textos | `UnusedResources`, `SetTextI18n`, `HardcodedText`, tipografía y tamaños: requieren revisar usos dinámicos y traducciones antes de eliminar o mover recursos. |
| Listas y rendimiento | `NotifyDataSetChanged`, `UseCompoundDrawables`, `Overdraw`, padres redundantes, profundidad, cantidad de vistas y alineación: requieren cambios de diseño/adaptadores con regresiones de interfaz. |
| Accesibilidad de gestos | Ocho avisos `ClickableViewAccessibility` en vistas y listeners: debe conservarse la separación entre toque, arrastre y cancelación; no se sustituyen por llamadas indiscriminadas a `performClick`. |
| Formularios | `Autofill`: quedan recomendaciones sobre autocompletado; los campos de importación/búsqueda no son formularios de credenciales. |
| Compatibilidad | Orientación vertical solicitada, acceso a APIs, compatibilidad de drawables, atributos nuevos e iconos/ABI de ChromeOS: se mantienen visibles para revisar cada plataforma objetivo. |
| Persistencia y estado | `ApplySharedPref`, `HardwareIds`, `StaticFieldLeak`, reglas de extracción: requieren preservar escrituras durables, identidad de dispositivo y uso de contexto de aplicación. No se cambia `commit` por `apply` solo para limpiar lint. |
| Dependencias y herramientas | Actualizaciones de bibliotecas, AGP y Gradle: se actualizó únicamente lint, comprobando la compilación; las migraciones de runtime siguen separadas de esa corrección. |

Esta clasificación no declara corregidos los avisos restantes. Los detalles por
archivo están en `apps/android/app/build/reports/lint-results-verification.html`
y se conservarán como artefactos de CI.
