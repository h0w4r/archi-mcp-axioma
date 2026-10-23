# Actualización en vivo en Archi

Este complemento es **opcional**. El servidor MCP headless no depende de él,
no inicia el workbench y sigue editando y exportando después de cerrar Archi.
El complemento sólo se utiliza cuando se desea visualizar en Archi los cambios
que el servidor ya ha guardado en los archivos nativos.

## Funcionamiento

1. Abrir en Archi el mismo archivo que se utiliza mediante el MCP.
2. Activar **Axioma > Actualizar modelos abiertos en vivo**.
3. Modificar y guardar el modelo mediante el MCP. Las transacciones satisfactorias
   ya se guardan automáticamente; no se necesita una aprobación gráfica.
4. El complemento detecta el archivo cambiado, carga y valida una copia aislada
   y calcula sus diferencias mediante EMF Compare.
5. Si no hay cambios locales pendientes, aplica las diferencias al modelo que ya
   está abierto y reconstruye sus figuras en el mismo editor. No cierra y reabre
   la pestaña para actualizar nombres, contenido, posiciones, grupos o conexiones.

La detección utiliza una huella SHA-256 y un intervalo de observación de 750 ms.
Las escrituras rápidas se agrupan hasta observar dos huellas consecutivas iguales;
si un escritor cambia el archivo durante su lectura, se vuelve a intentar sin
aplicar una versión incompleta ni detener definitivamente la observación.
La lectura y la comparación se ejecutan fuera del hilo gráfico. No se copia
el modelo completo en cada observación: sólo cuando el archivo cambia.

Los modelos abiertos se observan independientemente. Un conflicto en un modelo
no impide actualizar otro modelo limpio. El complemento no escribe el archivo
del modelo ni guarda por su cuenta los cambios del operador.

## Protección de las ediciones humanas

Si el modelo o alguno de sus editores tiene cambios sin guardar y el archivo
externo cambia, el estado pasa a `CONFLICT_UNSAVED`. No se aplica la actualización
externa ni se descartan las modificaciones locales.

Resolver primero qué cambios se quieren conservar, por ejemplo mediante una
copia separada o deshaciendo la edición local. Después usar **Axioma > Reintentar
tras resolver conflictos**. El complemento vuelve a comprobar el estado antes
de aplicar cualquier diferencia; el reintento no autoriza descartar cambios.

No hay una fusión automática de ediciones simultáneas ni un bloqueo del botón
Guardar de Archi. Si se guarda desde la GUI, el servidor detectará que su archivo
ha cambiado externamente y exigirá recargarlo antes de guardar una sesión antigua.

## Conservación del contexto

- Mantiene las instancias del modelo, la vista, el editor y su control gráfico.
- Conserva el zoom y recupera la posición del viewport y la selección existente
  cuando los nuevos límites del canvas lo permiten y los objetos seleccionados siguen presentes.
- Recrea las figuras GEF con las referencias completas, sin exponer los estados
  intermedios de una fusión a los adaptadores gráficos.
- Si el cambio elimina expresamente una vista abierta, cierra esa pestaña antes
  de eliminar sus referencias. Las demás vistas y modelos permanecen abiertos.
- Cuando aplica una versión externa, invalida el historial de deshacer del estado
  anterior, que ya no corresponde al archivo actual. No lo hace mientras haya
  ediciones humanas sin guardar.

## Errores

Los archivos ilegibles, los modelos inválidos o un cambio de identidad del modelo
producen `ERROR_PAUSED`. La versión que se estaba visualizando se conserva.
La barra de estado y el registro de errores de Eclipse contienen el motivo.
Tras corregir el archivo, el operador puede pedir el reintento desde el menú.

Los cambios de datos se registran con `ChangeRecorder` para poder revertir una
fusión que no reproduzca exactamente el modelo entrante. La comparación y el
guardado del servidor son procesos independientes: un fallo gráfico no invalida
ni simula una escritura MCP.

## Compilación y aceptación

Se utilizan las bibliotecas que ya incluye Archi 5.10, incluidas EMF Compare y
EMF Change. No requiere instalar jArchi, descargar un servicio ni contratar una API.
Ejecutar las instrucciones de desarrollo desde la raíz del repositorio fuente:

```powershell
./tools/build-live.ps1
./tools/prepare-live.ps1
python ./tools/test_live.py
```

`prepare-live.ps1` sólo modifica un runtime aislado preparado por Axioma, nunca
la instalación de Archi del operador. La aceptación utiliza un workbench real,
editores nativos y el menú SWT/JFace real, con un perfil temporal independiente.

La prueba cubre actualización de figuras, adiciones, movimientos, grupos,
relaciones, eliminaciones, protección de ediciones humanas, dos modelos abiertos,
archivos inválidos, reintento, cierre de una vista eliminada y continuidad del
servidor headless después de cerrar la GUI.

```powershell
./tools/package-live.ps1
```

El archivo `.archiplugin` resultante se instala mediante **Help > Manage Plug-ins
> Install New...** y después se reinicia Archi. La actualización está desactivada
en una instalación nueva y se habilita desde el menú Axioma.

La compatibilidad acreditada se limita a Windows y Archi 5.10. No se afirma que
la sincronización esté probada con versiones o extensiones de terceros no ensayadas.
