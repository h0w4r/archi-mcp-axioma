# Operations and model safety

[Project home](../README.md) / [Documentation](README.md) / Operations

## What a successful edit means

For a committed model edit, Axioma works on an isolated copy, applies the
requested operations, validates the native result, and saves it before returning
`persisted: true`. Saving uses a temporary file and atomic replacement on the
same filesystem. Reads do not advance the model revision.

Mutations are saved automatically. `axioma_model.save` also exists as an explicit
save operation; there is no GUI approval step or requirement to click Save in
Archi. A dry run returns a preview and `persisted: false` without committing.

`dryRun` is not supported for model creation, opening, reloading, closing, or
view export. Use a separate sandbox workspace when testing those operations.

## Revisions and fingerprints

| Value | Scope | Use it for |
| --- | --- | --- |
| `revision` | Current open session | Detecting stale requests with `expectedRevision` |
| `fingerprint` | SHA-256 of the file content | Comparing durable state across processes or restarts |

A reopened model starts a new session revision. Reloading an already-open model
advances that session's revision. Never assume the revision value from an old
process is still current. Query `axioma_model.info` before a guarded edit, and
use `expectedFingerprint` for comparisons across sessions.

## Two processes or an external writer

Each model has an in-process lock. Saving also uses a sidecar file lock to
coordinate Axioma writers and fingerprint checks to detect external changes.

These are **not distributed locks over every application**. Archi's GUI Save
button is not disabled, and an unrelated writer does not have to respect the
sidecar lock. Fingerprint checks provide optimistic conflict detection around
the save operation, not a global editing lease.

On a conflict:

1. Keep the returned error code and `correlationId`.
2. Inspect the current file and decide which changes must be preserved.
3. Use `axioma_model.reload` when ready to adopt the current on-disk state.
4. Read the new revision/fingerprint, then prepare the next transaction.

Do not overwrite a changed file merely to clear a conflict. If a connection is
lost during saving, inspect the file and fingerprint before replaying the
request. The server does not provide automatic idempotent replay of uncertain
requests.

## Local history and recovery

`axioma_model.history` lists locally retained versions. `restore` accepts the
selected version's `fingerprint`; restoration also preserves the state it
replaces and still checks for external conflicts.

History is stored in `.axioma/history` inside the workspace. It is local to the
machine, is not automatically pruned, and is not a substitute for an external
backup. Monitor its growth according to your own retention needs.

## Images and native archives

Image bytes are preserved in native archives during save, restart, and history
restoration. Imported images must become referenced within the same transaction:

- `axioma_image.add` can receive the `id` of a compatible visual object or profile.
- A batch can import an image and reference its returned path in `view.addImage`.

Archi does not preserve a library of unreferenced images. Axioma does not claim
a successful standalone import that would silently disappear on the next save.

## Workspace boundaries

Models, imported image files, and export destinations must resolve inside the
configured workspace, including paths resolved through links. `.axioma/` is
reserved for server state. `create` does not overwrite an existing model;
export needs explicit `overwrite: true` to replace an existing output.

## Live viewing and human changes

The [Live companion](../live/README.md) never writes the native model file. If
external changes arrive while a GUI model is dirty, that model pauses with
`CONFLICT_UNSAVED`. Resolve the human edit first, then request a retry.

Live does not automatically merge simultaneous human edits, replace an
unsaved editor, or prevent a user from saving in the GUI. Other clean models
continue to be observed independently.

## Current limits

- Operational acceptance covers Windows, Archi 5.10.0, and Java 21 only.
- Export uses SWT and fonts without opening a workbench or visible windows.
  Linux requires a graphics server such as Xvfb; that environment is unverified.
- Connection-to-connection routing is reported separately in `notRouted`;
  manual native bendpoints remain available.
- The headless API is nine domain tools over stdio, not the original server's
  70 HTTP tools or resource names.
- Third-party plug-in combinations and other MCP clients are not universally
  covered by the acceptance claim. jArchi is not required or invoked.
- There is no automatic replay, off-machine backup, distributed editing lease,
  or automatic human-edit merge.

[Tool reference](headless-tools.md) · [Axioma Live](../live/README.md) ·
[Documentation](README.md)
