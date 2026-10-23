# Archi MCP Axioma

**Automatización ArchiMate por MCP, sin abrir el workbench de Archi.**

Servidor open source basado en el metamodelo y el formato de archivo oficiales
de [Archi](https://www.archimatetool.com/). Cada modelo se identifica explícitamente;
las mutaciones se validan y guardan antes de devolver `persisted: true`.
No utiliza una selección activa del editor ni una aprobación gráfica inicial.

[Repositorio](https://github.com/h0w4r/archi-mcp-axioma) ·
[Incidencias](https://github.com/h0w4r/archi-mcp-axioma/issues) ·
[Licencia MIT](LICENSE)

## Estado

El servidor independiente está en **versión inicial de desarrollo 0.1.0**.
Su aceptación se ejecuta contra procesos Equinox reales por MCP/stdio, no contra
un sustituto del modelo. El entorno acreditado es Windows, Archi 5.10.0 y Java 21.
Todavía no se acredita compatibilidad operativa con otras versiones o plataformas.

El complemento gráfico original se conserva por separado. Su documentación está
en [README-LEGADO.md](README-LEGADO.md); sus herramientas y configuración HTTP
no son el contrato del servidor headless nuevo.

## Capacidades verificadas

- Crear, abrir, consultar y cerrar varios modelos sin un modelo activo global.
- Guardado automático por transacción y guardado explícito, mediante el gestor
  nativo de Archi, con reemplazo atómico en el mismo sistema de archivos.
- Consultar y utilizar los **61 tipos concretos de elementos y 11 tipos de
  relaciones** expuestos por el metamodelo de Archi 5.10.
- Consultar relaciones permitidas por Archi para un par de conceptos. Un extremo
  puede ser otra relación cuando las reglas de Archi lo permiten.
- Crear vistas, instancias de elementos y relaciones, grupos, notas, líneas,
  referencias a otras vistas e imágenes.
- Editar propiedades, documentación, atributos escalares y features nativas,
  organizar carpetas y asignar especializaciones.
- Ejecutar lotes atómicos con alias `$alias.id`, previsualización `dryRun` y
  controles `expectedRevision` y `expectedFingerprint`.
- Detectar conflictos de archivo entre procesos y mantener el estado previo
  cuando una operación o un lote falla antes del guardado.
- Conservar versiones anteriores y recuperarlas mediante `model.history` y
  `model.restore`, sin sobrescribir silenciosamente una modificación externa.
- Distribuir y enrutar vistas mediante el motor ELK de la línea base.
- Exportar SVG y PNG con fondo transparente y conservar imágenes al guardar,
  recuperar versiones, cerrar y volver a abrir archivos nativos.
- Actualizar modelos y vistas ya abiertos mediante el [adaptador Live opcional](live/README.md),
  con protección de ediciones humanas y sin convertir la GUI en una dependencia del MCP.

No se necesita jArchi para estas operaciones: se utilizan directamente las APIs
oficiales de Archi. No se modifica la instalación del operador.

## Requisitos

| Componente | Uso |
| --- | --- |
| Distribución de Archi 5.10.0 | Motor, metamodelo, formato nativo y exportador SVG |
| JDK 21 y `JAVA_HOME` | Compilación desde fuentes |
| PowerShell 7 | Preparación y lanzamiento en Windows |
| Cliente MCP con transporte stdio | Uso del servidor |
| Python 3 y psutil | Sólo aceptación automatizada |

Las bibliotecas necesarias se reutilizan de la línea base y de la distribución
de Archi. No hay una suscripción ni una API de pago necesaria para ejecutar este
servidor. El modelo de IA y las políticas independientes del cliente MCP quedan
fuera del servidor.

## Compilar y preparar

Desde la raíz del repositorio, con un JDK 21 configurado:

```powershell
./tools/build-headless.ps1 -ArchiHome 'C:/Program Files/Archi'
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi'
```

La compilación crea el bundle bajo `build/headless/` y una referencia local
`build/headless-latest.json`. La preparación copia la distribución a
`build/runtime/Archi`, incorpora sólo el nuevo bundle y no instala nada en
`C:/Program Files/Archi`.

Para utilizar un paquete ya compilado, extraerlo y preparar con el directorio
del bundle como argumento:

```powershell
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi' -BundlePath './bundle'
```

## Configurar un cliente MCP

Ejemplo de configuración genérica. Sustituir las rutas por las del equipo:

```json
{
  "mcpServers": {
    "archi-mcp-axioma": {
      "command": "pwsh",
      "args": [
        "-NoProfile", "-File",
        "C:/ruta/archi-mcp-axioma/tools/start-headless.ps1",
        "-Workspace", "C:/modelos-arquitectura"
      ]
    }
  }
}
```

El cliente inicia el proceso. `stdout` contiene exclusivamente MCP; el
diagnóstico se escribe en `stderr`. Al cerrar stdin, el servidor termina.
Cada proceso utiliza un perfil Equinox aislado. Dos procesos pueden abrir
el mismo archivo, pero un estado desactualizado no puede guardarse sin recargarlo.

El `Workspace` delimita las rutas accesibles. Los modelos, imágenes de entrada
y exportaciones deben permanecer dentro de esa raíz, incluidos los destinos
resueltos a través de enlaces. El historial y los perfiles están en `.axioma/`.

## Herramientas del servidor

Las nueve herramientas agrupan acciones. Sus esquemas anuncian los parámetros
y requisitos de cada acción; los parámetros desconocidos se rechazan.

| Herramienta | Acciones principales |
| --- | --- |
| `axioma_runtime` | `info` |
| `axioma_model` | `create`, `open`, `list`, `info`, `update`, `save`, `reload`, `close`, `validate`, `history`, `restore` |
| `axioma_element` | `types`, `schema`, `create`, `get`, `list`, `update`, `delete` |
| `axioma_relationship` | Las anteriores, `validTypes` y `reconnect` |
| `axioma_view` | Vistas, objetos visuales, líneas, notas, grupos, imágenes, referencias, posiciones, bendpoints, `layout`, `export` |
| `axioma_folder` | `create`, `get`, `list`, `update`, `move`, `delete` |
| `axioma_profile` | `create`, `get`, `list`, `update`, `assign`, `delete` |
| `axioma_image` | `add`, `list`, `assign` |
| `axioma_batch` | `apply` |

Las respuestas satisfactorias contienen `correlationId` y `data`. Las mutaciones
añaden `persisted`, `revision`, `fingerprint` y `result`. Un error de herramienta
contiene `error.code`, `error.message` y `error.correlationId`, con `isError=true`.
Las operaciones de lectura no alteran la revisión del modelo.
En un lote, `operations` conserva las instantáneas de cada paso y lo indica con
`operationResultsAreIntermediate=true`. `effectiveObjects` y los alias de
`references` reflejan el estado después de ejecutar todo el lote, incluidas
actualizaciones posteriores o eliminaciones.

### Flujo recomendado

1. Crear o abrir un archivo con `axioma_model` y conservar su `modelId`.
2. Consultar tipos y relaciones válidas, sin inventar nombres de tipos.
3. Preparar un lote de elementos, relaciones y representaciones visuales.
4. Usar `dryRun=true` si se quiere comprobarlo sin escribir.
5. Confirmar el lote con la revisión o huella consultada.
6. Consultar la vista o exportarla; verificar `persisted` y la revisión devuelta.

Ejemplo de argumentos para un lote, usando el `modelId` real devuelto al crear:

```json
{
  "action": "apply",
  "modelId": "ID_REAL_DEL_MODELO",
  "operations": [
    {"domain": "element", "action": "create", "type": "BusinessActor", "name": "Operador", "as": "actor"},
    {"domain": "view", "action": "create", "name": "Vista de negocio", "as": "vista"},
    {"domain": "view", "action": "addElement", "viewId": "$vista.id", "elementId": "$actor.id", "x": 30, "y": 30}
  ]
}
```

## Persistencia y límites actuales

- `revision` pertenece a la sesión y vuelve a cero al reabrir el archivo en otro
  proceso. `fingerprint` es la huella SHA-256 del contenido y sirve entre sesiones.
- El bloqueo lateral coordina escritores Axioma. Con escritores ajenos, como
  Archi GUI, la detección es optimista: se comprueba la huella antes y después
  de preparar el guardado. No constituye un bloqueo distribuido de otras aplicaciones.
- El historial es local y no se elimina automáticamente. No sustituye una copia
  externa del equipo. `restore` también conserva el estado que reemplaza.
- Ante una pérdida de conexión durante un guardado, consultar el archivo y su
  huella antes de repetir la operación. No hay repetición idempotente automática.
- Las imágenes deben quedar referenciadas dentro de la misma transacción:
  `image.add` acepta `id` de un perfil u objeto que admita imagen; también puede
  usarse un lote con importación y `view.addImage`. Archi no conserva una
  biblioteca de imágenes huérfanas y Axioma no acredita una importación que se perdería.
- Para cambiar los extremos de una relación representada, retirar sus conexiones
  visuales, ejecutar `reconnect` y añadir las nuevas conexiones dentro de un lote.
- ELK enruta conexiones entre objetos. Las conexiones cuyo extremo es otra
  conexión se conservan y se informan en `notRouted`; pueden configurarse con
  `setBendpoints`. No se afirma que ELK las haya enrutado.
- El render usa SWT y fuentes, pero no un workbench ni ventanas visibles.
  En Linux requiere un servidor gráfico, por ejemplo Xvfb; ese entorno aún
  no está acreditado. El núcleo no inicia `Display` para editar o guardar.
- No están implementadas en el servidor nuevo las 70 herramientas HTTP ni los
  recursos del complemento anterior con sus mismos nombres.
- La actualización de vistas abiertas requiere instalar y habilitar por separado
  el [adaptador Live](live/README.md). Se pausa ante cambios locales sin guardar;
  no realiza fusiones automáticas de ediciones humanas simultáneas.

## Ejecutar la aceptación

```powershell
python -m pip install -r ./tools/requirements-headless-test.txt
python ./tools/test_headless.py
python ./tools/test_headless_extended.py
python ./tools/test_headless_concurrency.py
```

Las tres pruebas ejecutan el transporte stdio real. La primera comprueba múltiples
modelos, persistencia tras reinicio, layout, transparencia y errores. La segunda
recorre el catálogo nativo completo, representaciones, perfiles, imágenes,
historial, borrados transitivos y conflictos entre dos procesos.
La tercera comprueba 24 peticiones concurrentes, rechazo de revisiones obsoletas,
bloqueo real del archivo y recuperación después de liberarlo.

Los resultados y transcripciones se conservan localmente en `.axioma-local/`.
Las pruebas observan salida, CPU y E/S del árbol administrado; no cancelan una
operación viva por su duración total.

Para acreditar también la visualización en vivo, compilar y preparar el adaptador
en el runtime aislado y ejecutar el circuito con el workbench real:

```powershell
./tools/build-live.ps1
./tools/prepare-live.ps1
python ./tools/test_live.py
```

Esta prueba utiliza los menús y editores nativos, dos modelos abiertos, cambios
estructurales y rápidos consecutivos, conflictos humanos, archivos inválidos,
reintento y continuidad del servidor después de cerrar la GUI. El paquete opcional
se genera con `./tools/package-live.ps1`.

Para generar una distribución compilada:

```powershell
./tools/package-headless.ps1
```

## Organización y atribución

- `headless/src/.../core`: casos de uso, registro multimodelo y contrato de entrada.
- `headless/src/.../persistence`: formato nativo, rutas, huellas e historial.
- `headless/src/.../archi`: adaptación del metamodelo, imágenes y render.
- `headless/src/.../protocol`: aplicación Equinox, catálogo y transporte MCP.
- `headless/src/net/.../AxiomaLayoutBridge.java`: puente al motor ELK reutilizado.
- `net.vheerden.archi.mcp`: línea base original y dependencias versionadas.
- `live`: adaptador gráfico opcional, comparación/fusión y aceptación con Archi real.

Partimos de [fanievh/archi-mcp-server](https://github.com/fanievh/archi-mcp-server),
de JS van Heerden. Conservamos licencia, atribución e historial y reutilizamos
su motor geométrico. [Dependencias y licencias](headless/DEPENDENCIAS.md).
