# Placed images, editable copies and Joybot image jobs

Owner request, October14: capture this alongside ongoing engine work; do not replace the
current recorded-stroke/history/save milestone. This is a proposed architecture and lane
boundary, not implemented functionality or a claim about Concepts/Photoshop internals.

## Owner intent

Place many pictures cheaply, move/scale them, and draw editable strokes over them without
immediately converting every source pixel into canvas paint. Keep explicit choices:

- **Reference image:** independently transformed placed asset, source pixels unchanged.
- **Edit original / portal:** edit a source-backed image surface and deliberately save back
  to its source. Decide overlay treatment, writable formats and source conflict behavior
  before enabling this; a document save must not silently overwrite an external original.
- **Drop to canvas:** materialize an independent editable image board. Preserve original
  pixel width/height, image-to-document transform and export grid, so the edited result can
  export at original resolution with exact pixel alignment even after on-canvas scaling.

The owner also wants to select a sketch/board region, ask Joybot to color it using an image
provider, or generate an image using a long prompt and attached references through their
chosen provider/account. Keep original sketch/reference layers and apply returned output
as a separately undoable asset or board.

## Responsibilities and handoff contracts

| Lane | Owns |
|---|---|
| JoyBrush engine lead | Placed asset/document identity, source-pixel and display ownership, image transforms, visible-region tile requests and cache budgets, drawing overlays, rasterization/copy transaction, shared undo, board pixel grid, snapshot/export and recovery integration. |
| Files/storage specialist | Durable local/Android document references and read/write permissions, linked versus embedded copies, relink/missing/moved files, source identity/version checks, atomic save-back and external edit conflicts. |
| Joybot specialist | User intent and prompt assembly, selected provider/model/account, credential handling, request cost/consent flow, asynchronous job state/cancel/retry, reference packaging and invoking validated canvas actions. |
| UI specialist | Reference/Edit original/Drop to canvas choices, link/missing/read-only status, progress and result placement, accessibility and owner-approved visual styling. |

Engine-to-storage: asset id, durable locator, source identity/version, pixel dimensions,
orientation/color metadata, availability, and read-region capability. Keep a separate
document-owned transform; decode/cache display pixels on demand. Portable embedding and
linking are explicit storage policies, not accidental effects of using a cache.

Engine-to-Joybot: an immutable selected region/board snapshot, document and region identity,
revision, exact crop/grid/transform, user-selected reference assets, and export bytes suitable
for the selected provider. A job result carries its originating revision and requested placement.
If the user edits the canvas while the job runs, never overwrite those edits on completion;
offer/place the result independently. Apply through one validated, undoable canvas transaction.

Provider credentials stay with the existing Joybot/provider layer. This brief does not verify
that a named model or OpenRouter route supports image generation/editing or reference inputs;
the Joybot specialist must discover the configured provider's actual capabilities.

## Fit with current work

Recorded strokes and native/source slabs establish coherent ownership, drawing-over order,
undo and saving. Image assets build on those contracts. Media Atlas A2 concerns paint/media
simulation and periodic tiles; linked-image decoding needs a related bounded residency policy,
but it should not be conflated with persistent paint simulation payloads.

No assigned implementation job or completion claim follows from this brief. Continue the
current engine milestone first; coordinate these interfaces before specialists edit shared
canvas/engine/save files. Existing Studio asset mechanisms are a source to inspect for reuse,
not evidence that this canvas feature is already wired.
