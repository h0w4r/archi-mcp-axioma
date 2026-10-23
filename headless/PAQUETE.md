# Archi MCP Axioma: headless package

This package contains the compiled 0.1.0 server, not a complete Archi installation.
The verified environment is Windows with Archi 5.10.0, Java 21, and PowerShell 7.
It does not require jArchi or an open Archi workbench. An existing jArchi
installation is not used as an MCP bridge.

## Prepare the runtime

Extract the ZIP to a permanent directory. From that directory, run:

```powershell
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi' -BundlePath './bundle'
```

This creates an isolated Archi copy under `build/runtime/Archi`; the source
installation is not modified. Keep `bundle` in place after preparation because
Equinox records its absolute location. If it moves, rerun preparation.

## Connect an MCP client

Adapt the example paths and surrounding configuration format to your client:

```json
{
  "mcpServers": {
    "archi-mcp-axioma": {
      "command": "pwsh",
      "args": [
        "-NoProfile", "-File", "C:/path/to/axioma/tools/start-headless.ps1",
        "-Workspace", "C:/models/architecture"
      ]
    }
  }
}
```

The transport is stdio. The process ends when stdin closes. Errors and progress
go to stderr, not stdout. Call `axioma_runtime` with `action: "info"` to check
the runtime, then `axioma_model` with `action: "create"` or `"open"` to obtain
an explicit `modelId`.

## Behavior at a glance

- Successful edits are saved automatically and return `persisted: true`, a
  revision, and a file SHA-256 fingerprint.
- `dryRun: true` previews a transaction without writing. It is unsupported for
  model creation, opening, closing, reloading, and view export.
- `expectedRevision` and `expectedFingerprint` detect stale state. Revisions
  belong to the open session; fingerprints can be compared across restarts.
- Local history lives under `.axioma/history` in the workspace. Use `model.history`
  to list versions and `model.restore` to recover one. It is not an off-machine
  backup.
- Images must be imported and referenced in the same transaction, using
  `image.add` with a destination `id`, or a batch including `view.addImage`.
- SVG and PNG exports are transparent. They use SWT and fonts without opening
  a workbench. Linux without a graphics server has not been verified.
- Refreshing already-open views requires the separately distributed, optional
  Live companion. The server does not depend on it.

## Documentation and licenses

Full setup, tools, architecture, development, and operational limits are in the
[online documentation](https://github.com/h0w4r/archi-mcp-axioma/blob/main/docs/README.md).
The repository retains its [MIT license](https://github.com/h0w4r/archi-mcp-axioma/blob/main/LICENSE).

In the compiled ZIP, dependency details are at `headless/DEPENDENCIAS.md` and the
license is at `LICENSE`. In the source checkout, see
[dependency details](DEPENDENCIAS.md) and [the license](../LICENSE).
