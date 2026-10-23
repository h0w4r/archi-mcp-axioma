# Axioma Live

[Project home](../README.md) / [Documentation](../docs/README.md) / Live updates

**Optional live viewing for models already open in Archi.** The headless MCP
server does not depend on this plug-in, does not start a workbench, and keeps
editing and exporting after Archi closes.

## Install and enable

1. Obtain a compiled Axioma Live `.archiplugin`, or build one using the commands
   below. This is a different artifact from the headless server ZIP.
2. In Archi, use **Help > Manage Plug-ins > Install New...** to select it, then
   restart Archi.
3. Open the same native model file that the MCP server uses.
4. Enable **Axioma > Actualizar modelos abiertos en vivo** (update open models
   live). The current plug-in's menu labels are Spanish.
5. Edit through MCP. Successful transactions are already saved automatically;
   there is no graphical approval step.

Live is **disabled by default** in a new installation. jArchi is neither
required nor a substitute for this plug-in. If you already use jArchi, its
scripting workflow remains separate; specific third-party plug-in combinations
are not covered by the current acceptance claim.

## What updates

The companion loads and validates changed files in isolation, compares them
with EMF Compare, and applies differences to clean models already open in Archi.
Names, content, positions, groups, and connections refresh in the existing tab.

- Model, view, editor, and graphical control instances remain in use.
- Zoom is preserved. Viewport and selection are restored when the new canvas
  bounds allow it and the selected objects still exist.
- Native GEF figures are rebuilt after references are consistent, rather than
  exposing partial structural changes to the editor's adapters.
- If an incoming change explicitly deletes an open view, that view's tab closes.
  Other views and models stay open.
- A clean external update clears undo history belonging to the previous state.
  It does not do this while human edits remain unsaved.

## How observation works

A SHA-256 fingerprint is checked at a 750 ms observation interval. Rapid saves
are coalesced until two consecutive observations agree. If a writer changes a
file during reading, Live retries rather than applying an incomplete version.

Reading and comparison run outside the UI thread. A full GUI model snapshot is
only taken when a changed file needs comparison, not at every idle observation.
Each model is tracked independently. The companion **never writes model files**
or saves the operator's edits on their behalf.

## Unsaved human changes

If an external save arrives while a model or one of its editors is dirty, Live
pauses that model with `CONFLICT_UNSAVED`. It does not discard local edits or
apply the external change over them. Other clean models can still update.

Resolve which changes to keep first, for example by preserving a separate copy
or undoing the local edit. Then select **Axioma > Reintentar tras resolver
conflictos** (retry after resolving conflicts). Retry checks the model again;
it is not permission to discard edits.

There is no automatic merge of simultaneous human edits, and Live does not lock
Archi's Save button. If you save from the GUI, the MCP server detects the
external file change and requires reloading before committing a stale session.
See [model safety](../docs/headless-operations.md).

## Errors and recovery

A stably unreadable file, invalid native model, or changed model identity causes
`ERROR_PAUSED`. The currently displayed version is retained. Archi's status bar
and Eclipse error log provide the reason. Correct the file, then request retry
from the Axioma menu.

EMF `ChangeRecorder` records applied data changes so a merge that does not
reproduce the incoming model can roll back. A GUI refresh error does not fake
or invalidate a successful server-side file commit.

## Build, test, and package

From the source checkout, prepare the headless runtime first, then run:

```powershell
./tools/build-live.ps1
./tools/prepare-live.ps1
python ./tools/test_live.py
./tools/package-live.ps1
```

The build uses libraries already included with Archi 5.10, including EMF Compare
and EMF Change. `prepare-live.ps1` only changes an Axioma-marked isolated runtime,
not the operator's Archi installation.

Acceptance uses a real workbench, native editors, and actual SWT/JFace menu
actions in an isolated profile. It covers structural changes, rapid saves,
zoom/selection, two models, human conflicts, invalid files, retry, deleted
views, and headless editing after the GUI closes. See the
[development guide](../docs/headless-development.md) for test and artifact details.

## Compatibility

Operational acceptance covers **Windows with Archi 5.10.0 and Java 21**.
Other versions, platforms, and arbitrary third-party extension combinations
have not been verified. Installing this companion is not required to use the
headless MCP server.

[Getting started](../docs/headless-getting-started.md) ·
[Architecture](../docs/headless-architecture.md) · [Documentation](../docs/README.md)
