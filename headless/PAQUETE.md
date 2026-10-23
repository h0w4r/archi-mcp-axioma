# Archi MCP Axioma: paquete headless

Este paquete contiene el servidor compilado 0.1.0, no una instalación de Archi.
Está acreditado en Windows con Archi 5.10.0 y Java 21. Necesita PowerShell 7.
No requiere instalar jArchi ni mantener abierto el workbench de Archi.

## Preparación

Extraer el ZIP a un directorio permanente. Desde ese directorio:

```powershell
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi' -BundlePath './bundle'
```

Se crea una copia aislada en `build/runtime/Archi`. No se modifica la instalación
indicada por `ArchiHome`. No mover `bundle` después de preparar el runtime sin
repetir la preparación, porque Equinox registra su ruta absoluta.

## Configuración MCP

Sustituir las rutas del ejemplo:

```json
{
  "mcpServers": {
    "archi-mcp-axioma": {
      "command": "pwsh",
      "args": [
        "-NoProfile", "-File", "C:/ruta/axioma/tools/start-headless.ps1",
        "-Workspace", "C:/modelos-arquitectura"
      ]
    }
  }
}
```

El transporte es stdio. El proceso termina al cerrar stdin. Los errores y el
progreso van a stderr, no a stdout. Consultar `axioma_runtime` con `action=info`
para comprobar el modo y `axioma_model` con `action=create` o `action=open`
para obtener un `modelId` explícito.

## Comportamiento

- Las mutaciones satisfactorias se guardan automáticamente y devuelven
  `persisted=true`, revisión y huella SHA-256.
- `dryRun=true` previsualiza transacciones sin escribir. No se admite en
  creación, apertura, cierre, recarga ni exportación.
- `expectedRevision` y `expectedFingerprint` detectan estados desactualizados.
  La revisión es de sesión; para comparar entre reinicios, usar la huella.
- El historial está en `.axioma/history` dentro del workspace. `model.history`
  lista versiones y `model.restore` permite recuperarlas. Es un respaldo local,
  no una copia externa del equipo.
- Las imágenes se importan y asignan en la misma transacción, por ejemplo
  `image.add` con `id` de un destino o mediante un lote con `view.addImage`.
- Las exportaciones SVG y PNG son transparentes. Usan SWT y fuentes sin abrir
  ventanas ni workbench. Linux sin servidor gráfico no está acreditado.
- No hay actualización automática de diagramas ya abiertos en Archi.

El código, las instrucciones completas y los límites operativos están en el
[repositorio público](https://github.com/h0w4r/archi-mcp-axioma).
Las dependencias conservan sus licencias y avisos, descritos en
[DEPENDENCIAS.md](headless/DEPENDENCIAS.md), y la licencia del repositorio se
incluye como [LICENSE](LICENSE).
