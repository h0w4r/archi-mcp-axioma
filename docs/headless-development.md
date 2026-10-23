# Development guide

[Project home](../README.md) / [Documentation](README.md) / Development

Commands below run from the repository root in PowerShell 7. The verified build
environment is Windows, Archi 5.10.0, and JDK 21 with `JAVA_HOME` set.

## Build the headless server

```powershell
./tools/build-headless.ps1 -ArchiHome 'C:/Program Files/Archi'
./tools/prepare-headless.ps1 -ArchiHome 'C:/Program Files/Archi'
```

The build emits a versioned bundle under `build/headless/` and updates
`build/headless-latest.json`. Preparation copies the local Archi distribution
into an isolated runtime and registers the bundle's absolute location.
Repeat preparation if that location changes.

## Run real headless acceptance

```powershell
python -m pip install -r ./tools/requirements-headless-test.txt
python ./tools/test_headless.py
python ./tools/test_headless_extended.py
python ./tools/test_headless_concurrency.py
```

| Test | Operational coverage |
| --- | --- |
| `test_headless.py` | Real stdio, multiple models, restart persistence including visual geometry, layout, transparent export, dry runs, failed batches, and conflicts |
| `test_headless_extended.py` | All 61 element types and 11 relationship types, relationship endpoints, profiles, native images, history restoration, cascade deletion, and two processes |
| `test_headless_concurrency.py` | 24 concurrent requests, stale-revision rejection, real file-lock rejection, and recovery after unlocking |

These clients start actual Equinox/MCP processes and read durable native files.
They do not use a mock model to claim operational acceptance. Results and
transcripts stay under the ignored `.axioma-local/` directory.

The clients observe output, CPU, and I/O from managed processes. Business
operations are not canceled merely because total elapsed time is long.
An optional `AXIOMA_TEST_RUNTIME` environment variable selects an alternate
prepared runtime for acceptance.

## Build and test Live separately

Prepare the headless runtime first, then run:

```powershell
./tools/build-live.ps1 -ArchiHome 'C:/Program Files/Archi'
./tools/prepare-live.ps1
python ./tools/test_live.py
```

This acceptance path uses a real workbench with native editors and SWT/JFace
menu actions in an isolated profile. It covers figure updates, structural
changes, two open models, rapid saves, zoom/selection retention, dirty-model
protection, invalid files, retry, view deletion, and continued MCP editing after
the GUI closes.

`prepare-live.ps1` requires an Axioma-marked runtime; it does not install the
companion into the operator's Archi installation. Live is not required for the
headless suite.

## Package compiled artifacts

After building the corresponding bundle:

```powershell
./tools/package-headless.ps1
./tools/package-live.ps1
```

| Output | Contents |
| --- | --- |
| `dist/archi-mcp-axioma-headless-<version>.zip` | Compiled server bundle, launch/preparation scripts, package README, and license notices |
| `dist/archi-mcp-axioma-live-<version>.archiplugin` | Independent optional Archi plug-in with its README and license |

The headless ZIP does not redistribute a complete Archi installation. Packaging
prints the artifact SHA-256. Scripts refuse to overwrite an existing staging
directory for the same version; build a new version instead of silently
replacing an artifact.

For Live package acceptance, `AXIOMA_TEST_LIVE_PACKAGE` can point to the compiled
`.archiplugin`. The verification app checks recognition through Archi's native
package validator. Loading and testing an extracted bundle does not by itself
claim that the operator's installation was modified through the GUI installer.

## Scope of the inherited build guides

[BUILDING.md](../BUILDING.md), [MAINTAINING.md](../MAINTAINING.md), and the inherited
CI workflow describe the original GUI/HTTP component. Their JUnit results do
not replace the Axioma headless or Live acceptance suites above.

[Architecture](headless-architecture.md) · [Axioma Live](../live/README.md) ·
[Documentation](README.md)
