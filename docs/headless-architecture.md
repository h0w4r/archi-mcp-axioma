# Headless architecture

[Project home](../README.md) / [Documentation](README.md) / Architecture

## Design in one paragraph

Axioma runs an independent Equinox application,
`io.github.h0w4r.axioma.headless.server`. An MCP client starts it over stdio;
the process uses Archi's native metamodel and archive APIs without starting a
workbench. The protocol delegates to a model-specific transaction core, while
native adaptation, persistence, and rendering have separate responsibilities.
The optional Live bundle belongs to the GUI side and is not a server dependency.

## Responsibility map

| Area | Main implementation | Responsibility |
| --- | --- | --- |
| Protocol | `HeadlessApplication`, `ToolCatalog` | Equinox entry point, MCP registration, schemas, and response envelopes |
| Core | `AxiomaService`, `InputContract`, `ModelRegistry` | Dispatch, input validation, explicit model sessions, transaction controls, and batch execution |
| Persistence | `WorkspaceFiles`, `NativeModelStore` | Workspace containment, native load/save, file fingerprints, atomic replacement, and history |
| Native adaptation | `NativeOperations`, `ImageOperations` | Native concepts, relationships, folders, specializations, visual objects, and image references |
| Rendering | `RenderPort`, `NativeRenderer` | Export boundary and native SVG/Batik PNG rendering |
| Geometry bridge | `AxiomaLayoutBridge` | Reuse of selected upstream ELK/layout code |
| Optional Live | `LiveSyncService`, `LiveComparison` | Detect external saves and reconcile clean GUI models |
| GUI refresh | `GuiNotificationFence`, `GuiRefreshTransaction` | Protect native adapters while rebuilding actual editor figures |

## Source navigation

- [Protocol sources](../headless/src/io/github/h0w4r/axioma/headless/protocol)
- [Core sources](../headless/src/io/github/h0w4r/axioma/headless/core)
- [Persistence sources](../headless/src/io/github/h0w4r/axioma/headless/persistence)
- [Native adapter and renderer sources](../headless/src/io/github/h0w4r/axioma/headless/archi)
- [Layout bridge](../headless/src/net/vheerden/archi/mcp/model/AxiomaLayoutBridge.java)
- [Live sources](../live/src/io/github/h0w4r/axioma/live)

## The transaction path

1. `HeadlessApplication` receives a real MCP tool call.
2. `InputContract` checks the action's required and allowed inputs.
3. `AxiomaService` dispatches to the requested domain and explicit model.
4. `ModelRegistry` locks the session and checks the expected state.
5. A native copy receives the operations; invalid changes do not replace the
   committed in-memory model.
6. `NativeModelStore` validates and saves the candidate with conflict checks,
   a cooperative file lock, history, and atomic replacement.
7. The registry adopts the committed candidate, advances the revision, and
   returns the durable fingerprint and operation result.

Dry runs return the validated preview without the persistence/adoption step.
Read-only actions use the current model without advancing its revision.

## Process and graphics boundaries

Each server process has a separate Equinox profile and communicates through
stdio. Dependency output is redirected to stderr so it does not corrupt MCP
stdout. The server does not listen on an HTTP port or expose the old GUI
approval surface.

Model editing and persistence do not create an SWT `Display`. Export has an
isolated graphics path using SWT, fonts, Archi's SVG exporter, and Batik. That
still needs native graphics dependencies, even though no workbench or visible
window is opened.

## Optional Live boundary

The separate `io.github.h0w4r.axioma.live` bundle observes open models by file
fingerprint. It waits for stable observations, captures a clean GUI model,
compares in the background with EMF Compare, and applies changes on the UI
thread with EMF change recording and rollback.

Native editor figures are detached before structural updates and rebuilt only
after references are consistent. The existing model, view, editor, and control
instances remain in use. Epoch and dirty-state checks reject stale prepared
comparisons. Conflicts are isolated per model; the companion never saves files.

## jArchi and the inherited server

Neither the headless bundle nor Live calls jArchi. Existing jArchi scripts are
a separate optional workflow, not a runtime bridge or a hidden dependency.

The original [GUI/HTTP component](../net.vheerden.archi.mcp) is retained with its
history and dependencies. The headless server selectively reuses geometry code,
not its active-editor assumption or GUI approval flow. Read
[the legacy architecture](architecture.md) only for that original component.

[Development](headless-development.md) · [Operations](headless-operations.md) ·
[Documentation](README.md)
