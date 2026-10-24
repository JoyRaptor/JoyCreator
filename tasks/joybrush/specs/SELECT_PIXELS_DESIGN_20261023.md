# Paint selection transaction proposal — reviewed against October24 source

Status: DESIGN FOR ENGINE LEAD REVIEW; no Phase B implementation or approval.
Phase A must be integrated first. This note does not enable pixel lift, clipping,
colour adjustment or the outside veil. Select's current host returns null for
`liftPixels`, stores an unused `paintClip`, and has no dimming implementation.

## Existing pieces and their limits

`SelectionMask` is immutable sparse coverage. `Resample.lift` produces complementary
premultiplied byte arrays; `Resample.warp` inverse maps them. The current engine's
`beginLift/setLiftTransform/previewLift/endLift` are LINE lift APIs. Reuse their
transient lifecycle and drawing transform, not their assumption that lifted input
is a set of recorded lines. There is no existing engine pixel-lift transaction to
wire by changing the host's null return.

The 16,384px `MAX_SELECT_SPAN` is an address/extent limit, not a memory allowance.
A 64×64 grid of 256px coverage tiles is 256MiB; unaligned bounds can reach 65×65,
about264MiB. The existing comment claiming4MB is incorrect. A span-limited RGBA8
warp can exceed1GiB. Admission must precede mask rasterisation, tile readback,
split, warp and GPU allocation; a size check after constructing them is too late.

## First safe implementation boundary

Start with one visible, unlocked raster-only physical plane, with no realistic
media stores, mask editing, periodic board or line/slab items on affected keys.
Resolve physical source and destination cels from the captured region plan; do
not read a visible projection and write it back into a shared cel. Unsupported
owners refuse before lifting. This milestone is explicitly partial Phase B,
not acceptance of paint selections on every layer.

Keep an engine-owned transient ticket with exact document generation, layer/cel
identities, region plan, graphics/physical epochs, history cursor and source
texture identities. One open/layer/frame/undo/context change invalidates it.
The UI receives one terminal result even if queued GL work becomes stale.

Admission produces a finite source key set and a checked destination footprint.
It accounts for region-slice multiplicity, transformed bounds and bilinear filter
reach. A new drag transform requires fresh admission before allocating or
replacing its preview. Refusal retains the previous accepted transform/source;
it must not interpret an empty warp result as a successful Delete.

Borrow original resident tiles. Allocate remainder and lifted data without
changing permanent stores. A transient display overlay shows the proposed result
through the existing homography draw path. Every save/read/export/history/stroke
entry settles or cancels this preview first. No preview pixels enter a thumbnail
or archive, and preview changes never create undo steps.

On release, validate the ticket again and prepare ALL changed source/destination
tiles. Only after every output succeeds does one transaction install them and
push one `UndoLog.Step`. Existing original handles become `before` owners; they
do not need duplicate before-textures. Outputs become `after` owners. Failure
retires only fresh outputs and preserves all original pixels, history and mask.
Successful commit refreshes the lift at identity from the committed output;
cancel restores the prior committed result without another history step.

## Lines and slabs on the same plane

For each crossed tile NativeLines requires `tile = base ∘ ordered items`.
Overwriting its base with final composite pixels would double-render the items.
Calling existing `replaceTiles` over such keys already refuses; keep that gate.

The general item-bearing transaction needs one reviewed extension: preflight
the COMPLETE affected line-footprint closure, rasterise each affected line at its
own seq to a slab, retain unaffected records, and apply the pixel edit as ordered
slab changes, all in the SAME undo step as line removal and tile recomposition.
Do not flatten the whole layer or bake unrelated lines silently. Whole affected
line footprints may extend far outside the mask; include those keys and every
physical cel in admission. If the closure exceeds the allowance, refuse without
partially baking. Undo restores exact records, slabs, bases, physical pixels and
selection identities. This extension needs a dedicated NativeLines/LineItems
transaction API; separate Bake then pixel Replace steps violate one-gesture undo.

The exact soft-edge composition must be specified and tested before this API:
`lifted + remaining = original` in Resample is additive byte complement, NOT
proof that normal source-over of the two restores the original. In particular,
a half-selected opaque pixel becomes two alpha halves and source-over yields
about75% alpha. Identity lift/commit must be a bit-exact no-op, including soft
coverage and existing alpha. Preview and commit must use the same reviewed
cut/overlap reconstruction, rather than independently stacking the complements.

## Memory admission on the Note9

At256px, RGBA8 is256KiB/tile and coverage is64KiB/tile. Borrowed resident originals
remain in the machine-wide inventory while a lift holds them; transferring them
to Undo does not allocate another copy. New allocations are a separate peak:

| Work | Conservative additional memory |
|---|---|
| Mask |64KiB CPU per source mask key; GPU mask only if the chosen renderer needs it |
| Split |512KiB CPU +512KiB GPU per source tile for lifted/remainder |
| Warped preview |256KiB CPU +256KiB GPU per destination tile |
| Preview replacement |Old and new preview can coexist; include another256KiB GPU per old destination |
| Commit |Up to256KiB GPU per changed physical source/destination key until publication |

A1440×2960 document-pixel view at1× covers at least72 aligned tiles (6×12),
18MiB per RGBA8 store and4.5MiB coverage, before unaligned/filter expansion.
At lower zoom document coverage grows with inverse zoom squared. A64-source-tile
first admission limit matches the existing FillPenRaster precedent but does not
cover that whole screen; say so and offer a smaller selection. It is not evidence
of the eventual full-screen experience. Choose a larger dynamic allowance only
after measured whole-process headroom and actual GPU residency establish it.
No quota may rely on Undo trimming: the newest step may exceed its soft budget.

## Paint-through clipping and remaining actions

Clipping is a separate capability. Snapshot immutable coverage and physical
ownership at stroke DOWN. Every supported brush path must apply coverage to its
final contribution, including watercolour pooling/media and erase. Masking only
the dab centre or final displayed look is insufficient. Preserve one contact,
one undo and unchanged pixels outside coverage. Keep the capability off for a
brush family until GPU comparison proves its boundary behaviour. Report refused
capabilities truthfully; the current claim that painting inside works is false.

Duplicate uses keep-source; Delete clears only lifted coverage; Fill and relative
HSB use the same preflight/preview/one-step transaction. Pixel Copy includes
coverage, premultiplied colour, relative coordinates and a bounded session budget.
Paste takes fresh destination ownership and never writes the referenced drawing.
The UI specialist owns the veil and appearance after engine semantics pass.

## Completion gates

- Exact source/destination coverage and region/frame ownership, negative keys,
  transparent keys, overlap, alpha and identity/no-op tests.
- Budget refusal before read/allocation; allocation fault at each stage leaves
  original stores/cursor unchanged; prior preview remains valid on refusal.
- One gesture/one undo, redo, repeated commits while lifted, Delete/Fill/Copy,
  save/reopen and truthful source-aware thumbnail/export.
- Context replacement abandons all transient names without recycling them into
  the new context; failed replacement still terminally resolves the UI ticket.
- Note9 full-tile reference comparisons plus actual gestures and white/dark
  screenshots on the exact integrated APK. Host tests alone close none of these
  device gates.

Engine Lead approval is required by Phase B's brief before implementation. The
design above retains that gate and explicitly separates the initial plain-plane
slice from the harder mixed-item and all-brush clipping milestones.
