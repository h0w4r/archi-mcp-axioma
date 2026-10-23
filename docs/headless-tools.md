# Tool reference

[Project home](../README.md) / [Documentation](README.md) / Tool reference

The headless server exposes **nine tools**, each with an `action` selector.
Discover their current input schemas through your client's MCP `tools/list`.
Unknown parameters are rejected instead of being silently ignored.

## Tool catalog

| Tool | Actions |
| --- | --- |
| `axioma_runtime` | `info` |
| `axioma_model` | `create`, `open`, `list`, `info`, `update`, `save`, `reload`, `close`, `validate`, `history`, `restore` |
| `axioma_element` | `types`, `schema`, `create`, `get`, `list`, `update`, `delete` |
| `axioma_relationship` | `types`, `schema`, `validTypes`, `create`, `get`, `list`, `update`, `delete`, `reconnect` |
| `axioma_view` | `create`, `get`, `list`, `update`, `delete`, `addElement`, `addConnection`, `addGroup`, `addNote`, `addReference`, `addImage`, `addLine`, `updateObject`, `removeObject`, `moveObject`, `setBendpoints`, `layout`, `export` |
| `axioma_folder` | `create`, `get`, `list`, `update`, `move`, `delete` |
| `axioma_profile` | `create`, `get`, `list`, `update`, `assign`, `delete` |
| `axioma_image` | `add`, `list`, `assign` |
| `axioma_batch` | `apply` |

## IDs and common inputs

| Input | Meaning |
| --- | --- |
| `modelId` | Explicit model ID returned by `axioma_model.create` or `open` |
| `id` | Object ID for the selected action; a visual object ID for `updateObject` |
| `viewId` | View containing the visual objects being added, moved, laid out, or exported |
| `elementId` / `relationshipId` | Logical concept represented by a visual object or connection |
| `sourceId` / `targetId` | Logical relationship endpoints, including other relationships where native rules allow |
| `sourceVisualId` / `targetVisualId` | Visual connection endpoints |
| `parentId` / `folderId` | Parent visual/container or destination folder, depending on the action |
| `expectedRevision` | Expected in-session revision for a transaction |
| `expectedFingerprint` | Expected file SHA-256 fingerprint for a transaction |
| `dryRun` | Validate an edit without saving it; unsupported for lifecycle and export operations |

Consult the action schema for required fields. Not every input is meaningful or
accepted for every action. Coordinates are integer pixels relative to the
visual object's container.

## Discover native types first

Use `axioma_element.types` and `axioma_relationship.types` rather than inventing
type names. Use `schema` for scalar attributes and `validTypes` for a specific
pair of logical endpoints. There is no fixed diagram template limiting which
valid ArchiMate concepts you can combine.

Element and relationship properties include documentation, user properties,
scalar native `attributes`, and presentation `features` where supported.
Those maps are not an unrestricted route for replacing IDs or native references.

## Example: create a connected view

First create or open a model. Replace `YOUR_MODEL_ID` below with its real ID,
then call **`axioma_batch`** with these arguments:

```json
{
  "action": "apply",
  "modelId": "YOUR_MODEL_ID",
  "operations": [
    {"domain": "element", "action": "create", "type": "BusinessActor", "name": "Operator", "as": "actor"},
    {"domain": "element", "action": "create", "type": "BusinessProcess", "name": "Manage requests", "as": "process"},
    {"domain": "relationship", "action": "create", "type": "AssignmentRelationship", "sourceId": "$actor.id", "targetId": "$process.id", "as": "assignment"},
    {"domain": "view", "action": "create", "name": "Business overview", "as": "view"},
    {"domain": "view", "action": "addElement", "viewId": "$view.id", "elementId": "$actor.id", "x": 30, "y": 30, "as": "actorVisual"},
    {"domain": "view", "action": "addElement", "viewId": "$view.id", "elementId": "$process.id", "x": 300, "y": 30, "as": "processVisual"},
    {"domain": "view", "action": "addConnection", "viewId": "$view.id", "relationshipId": "$assignment.id", "sourceVisualId": "$actorVisual.id", "targetVisualId": "$processVisual.id"}
  ]
}
```

Each `as` declares an alias usable by later operations, such as `$actor.id`.
A batch operates on **one model**. Its operations may target elements,
relationships, views, folders, profiles, or images. Lifecycle calls and exports
are not batch operations. Put `dryRun`, `expectedRevision`, and
`expectedFingerprint` on the outer batch, not individual steps.

Set `dryRun: true` on the outer arguments to preview without writing. Submit
the real transaction separately after checking its expected revision or
fingerprint. A failed native edit or validation does not commit earlier steps.

## Read the result correctly

Success responses contain `correlationId` and `data`. A committed edit's `data`
includes `persisted`, `revision`, `fingerprint`, and `result`. The model-creation
response instead returns the model summary, including `modelId` and its file
fingerprint; it does not use the mutation envelope.

Within a batch result:

- `operations` contains each step's intermediate result.
- `operationResultsAreIntermediate: true` explicitly marks that distinction.
- `effectiveObjects` contains the final state of affected objects.
- `references` resolves aliases against the final state, including subsequent
  updates or deletions.

For example, use `data.result.references.view.id` from the committed batch above
as the view ID for later calls. Do not assume the first intermediate object
snapshot remains the final version after later batch steps.

An error has `isError: true` and an `error` object containing `code`, `message`,
and `correlationId`. Read operations do not advance the revision.

## Layout and export

Call **`axioma_view`** to lay out the view, substituting real IDs:

```json
{"action": "layout", "modelId": "YOUR_MODEL_ID", "viewId": "YOUR_VIEW_ID", "direction": "RIGHT"}
```

Then export it:

```json
{"action": "export", "modelId": "YOUR_MODEL_ID", "viewId": "YOUR_VIEW_ID", "path": "business-overview.svg", "format": "svg"}
```

Use `format: "png"` and a `.png` path for a transparent PNG. Export refuses an
existing destination unless `overwrite: true` is supplied. The path extension
must match the format, and the destination must remain inside the workspace.

ELK routes connections between objects. Connections whose endpoint is another
connection are preserved and reported in `notRouted`, not falsely reported as
routed. Use `setBendpoints` for those paths. Its native offsets are
`startX`, `startY`, `endX`, and `endY`, relative to endpoint centers; an empty
list clears the bendpoints.

For a represented relationship's endpoint change, remove its visual
connections, `reconnect` the logical relationship, and add the replacement
connections inside one batch.

[Getting started](headless-getting-started.md) ·
[Operations](headless-operations.md) · [Documentation](README.md)
