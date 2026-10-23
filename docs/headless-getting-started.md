# Getting started

[Project home](../README.md) / [Documentation](README.md) / Getting started

## Before you begin

The verified environment is **Windows with Archi 5.10.0 and Java 21**.

| Requirement | Needed for |
| --- | --- |
| Local Archi 5.10.0 distribution | Native model, archive, and rendering libraries |
| PowerShell 7 (`pwsh`) | Build, preparation, and launch scripts |
| JDK 21 with `JAVA_HOME` set | Building from source only |
| An MCP client supporting stdio | Starting and using the server |
| Python 3 and `psutil` | Automated acceptance only |

The launcher uses the Java runtime bundled with the prepared Archi copy.
There is no paid API or jArchi binary required by Axioma. Any cost or approval
policy imposed by your AI model or MCP client is independent of this server.

## Option A: build from source

Run in PowerShell 7. Change the Archi path if needed.

```powershell
git clone https://github.com/h0w4r/archi-mcp-axioma.git
Set-Location archi-mcp-axioma
./tools/build-headless.ps1 -ArchiHome 'C:/Program Files/Archi'
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi'
```

`build-headless.ps1` compiles against the installed Archi distribution and
reuses the dependencies already present in the repository. It writes a bundle
under `build/headless/` and a local pointer in `build/headless-latest.json`.

`prepare-headless.ps1` copies Archi into `build/runtime/Archi` and registers the
new bundle there. It does **not** modify the source installation. Keep the
checkout, bundle, and prepared runtime at stable paths; preparation records
absolute locations. Rerun preparation if a bundle moves.

## Option B: use a compiled ZIP

Extract the compiled headless ZIP to a permanent directory and run:

```powershell
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi' -BundlePath './bundle'
```

The ZIP contains the compiled Axioma bundle and launchers, not a complete Archi
distribution. See the [package guide](../headless/PAQUETE.md).

## Configure your MCP client

Adapt the executable arguments below to your own paths. Client configuration
formats differ; `mcpServers` is a generic example, not a universal file format.

```json
{
  "mcpServers": {
    "archi-mcp-axioma": {
      "command": "pwsh",
      "args": [
        "-NoProfile", "-File",
        "C:/projects/archi-mcp-axioma/tools/start-headless.ps1",
        "-Workspace", "C:/models/architecture"
      ]
    }
  }
}
```

The client owns the process and stdio connection. Closing stdin stops the
server. Protocol output goes to stdout; diagnostics and progress go to stderr.
Each process gets an isolated Equinox profile.

## Verify the connection

Call **`axioma_runtime`** with:

```json
{"action": "info"}
```

Check the returned `data` for `mode: "headless"`, `workbenchRunning: false`,
`approvalMode: "direct"`, and the expected `workspace` path. Tool discovery
should expose nine `axioma_*` tools.

Then call **`axioma_model`** with:

```json
{"action": "create", "name": "Architecture Sandbox", "path": "sandbox.archimate"}
```

Keep the returned `data.modelId`. Pass it explicitly to model-specific calls;
there is no global active model. `create` refuses to overwrite an existing
file. Use `open` for a model that already exists.

Next, follow the [batch example](headless-tools.md#example-create-a-connected-view)
to build a connected view. Successful mutations are validated and saved before
returning `persisted: true`.

## Workspace and existing plug-ins

- Native models, imported images, and exports must be inside the chosen
  workspace. Symlink-resolved paths must stay inside it too.
- `.axioma/` is reserved for local history and process profiles. It is not a
  destination for user models.
- Existing jArchi scripts can remain a separate workflow. Axioma neither invokes
  jArchi nor requires its removal. Specific third-party plug-in combinations
  are not covered by the current acceptance claim.
- To watch saved changes in an open editor, install the separate
  [Live companion](../live/README.md). jArchi alone does not provide that feature.

## If startup fails

Check that `pwsh` is available, `JAVA_HOME` points to a JDK when building, and
the configured launcher path exists. Confirm preparation completed and the
bundle has not moved. Read stderr for the actual error; do not treat tool
enumeration alone as proof that model editing works.

[Tool reference](headless-tools.md) · [Operations](headless-operations.md) ·
[Documentation](README.md)
