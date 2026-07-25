# SPEC: cross-type z-order (the substrate's other half)

> Opened 2026-07-25 (Opus 5) from gap 1 of `REVIEW_ORDER_20260725.md`. The neutral substrate
> lets any object live on any lane. This is the other half of the promise: making lane ORDER
> decide what paints on top, across item types.

## The problem, stated precisely

Today paint order is **by TYPE, globally**, not by lane. Both paths agree with each other —
that part is not broken — but neither consults lane z across types.

Preview — five sibling Views, bottom→top (`activity_faditor_editor.xml` 536-566):
`OverlayVideoPreviewView` (PiP) → `WaveformOverlayView` → `LayerImageOverlayView` →
`SpriteOverlayView` → `TextOverlayLayer`.

Export — mirrors it by construction: PiPs composite in the GL effect chain (below), then
`CompositeExportOverlay` draws sprites first and text/captions after
(`CompositeExportOverlay` ~410: *"drawn FIRST so they sit above the video but BELOW text"*).

Within a type, lane z IS honored: every `LayerPreviewController.visible*` walks lanes in
zIndex order and appends that type's items. So two texts on different lanes stack correctly.
Across types, lane z is ignored entirely.

**User-visible consequence:** put a PiP on the top lane and a text on the bottom lane; the text
still paints over the PiP. Move the lanes; nothing changes. For a feature called "layers", that
reads as broken. It is pre-existing, but the substrate made it reachable-by-accident: stacking
two types is now the obvious thing to try.

## The invariant that makes this hard

Preview and export must never diverge (the project's single-authority rule — the reason
`LayerPreviewController` exists). So a preview-only fix is not a fix; it would trade a visible
z bug for an invisible export mismatch, which is strictly worse. Any solution has to land in
both paths, or in neither.

## Options considered

**A. Reorder the preview Views per frame.** Android z within a parent follows child order, so
the five surfaces could be re-sorted whenever lane z changes. Cheap, and it handles the common
case (all PiPs above all texts). But it cannot express INTERLEAVING — text A above a PiP above
text B — because a whole surface moves at once. And it fixes only the preview, breaking the
invariant above. **Rejected as a solution; possible as a stopgap only if paired with the export
change, which it isn't.**

**B. One z-ordered composite pass in both paths.** A single ordering — (lane z, then item order
within lane) — computed once in `LayerPreviewController` and consumed by both:
- Preview: draw every visual item in that order onto one surface. Text/sprite/image are already
  canvas draws, so they merge easily. The PiP is the hard one: it is a live `ExoPlayer` on a
  `TextureView`, so it must become a texture/bitmap the compositor draws, not a sibling View.
- Export: sprites/text/captions must move INTO the GL chain as textures (or PiP frames must come
  back OUT into the bitmap overlay — rejected: PiPs were moved to GL precisely because CPU-side
  frame decode was the bottleneck).
This is the correct end state and a genuinely large change.

**C. Two-bucket compromise.** Support only "this lane is above/below the video layer" —
i.e. allow lanes to sit in front of OR behind the PiP surface, without full interleaving.
Preview: two overlay Views (one under, one over the PiP surface), each drawing its lanes'
items in order. Export: two `CompositeExportOverlay` passes, one before and one after the PiP
composite in the effect chain. Expresses the case users actually hit (a title over a PiP; a
watermark under it) at a fraction of B's cost, and it is a strict subset of B — nothing built
here is thrown away when B lands.

## Recommendation

**Do C first, then B if the interleaving case ever bites.** C delivers the user-visible promise
("move the lane, the stacking changes") for the realistic cases and keeps preview and export in
lockstep, because both consume the same single ordering. B is the honest end state but is a
multi-session compositor rewrite that should not be started without device verification
available — and every C-stage artifact (the global ordering, the split passes) is reused by it.

## Slices (for C)

- **Z1 — the ordering, one authority.** `LayerPreviewController.orderedVisualItems(timeline)`:
  every visible item of every type, sorted by (lane zIndex, lane emission order, item order),
  each tagged with its payload type. Pure, testable off-device — extend
  `tasks/getlayers_equiv.py`-style simulation rather than trusting inspection.
- **Z2 — split the buckets.** Partition that ordering at the PiP surface: items on lanes BELOW
  the topmost PiP-bearing lane vs items above it. Define the tie-break explicitly and write it
  down; ambiguity here is what produces "it looked right in preview" bugs.
- **Z3 — preview.** A second overlay View beneath `OverlayVideoPreviewView` fed the "below"
  bucket; the existing surfaces take the "above" bucket. No new drawing code — the same item
  renderers, pointed at a filtered list.
- **Z4 — export.** A second `CompositeExportOverlay` instance for the "below" bucket, inserted
  into `assembleClipVideoEffects` BEFORE the PiP composite; the existing one keeps the "above"
  bucket. Must be proven with an absolute-geometry A/B frame diff (memory:
  `ab-export-frame-diff-proof` — symmetric proofs miss flips).
- **Z5 — UI.** Nothing new: lane move-up/down already exists and already writes zIndex. The
  point of Z1-Z4 is that those controls finally do what their name says across types.

## Acceptance

1. A text on a lane below a PiP-bearing lane renders BEHIND the PiP, in preview and export.
2. Moving that lane above the PiP lane flips it, in both, with no other visual change.
3. A project that never reorders lanes is pixel-identical to today (the ordering degenerates to
   the current type order when every zIndex is 0 — assert this in the simulation, not by eye).
4. Export A/B frame diff confirms 1 and 2 with absolute geometry.
