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

## Z3's blocking design question — ANSWERED (2026-07-25)

Splitting each canvas surface into two instances (one under the PiP plane, one over) raises a
question that has to be settled before any code, or it becomes a bug: **which instance handles
touch?** Two `TextOverlayLayer`s both hit-testing the same screen means the top one silently
eats taps meant for an item behind the video.

**Decision: the BELOW instance is inert — `setClickable(false)`, no gesture callback, draw
only. Interaction stays with the existing (above) instance.**

Rationale, and the consequence to accept:
- Dragging an object that is *behind* a video is not a real workflow — you cannot see what you
  are grabbing. Users place it, then send it behind.
- The object remains fully editable via its timeline row (select, drawer, keyframes), which is
  where lane ordering is done anyway. So nothing becomes unreachable, only un-draggable
  in the canvas.
- Consequence: to drag it directly again, order its lane back above the video. That is a
  discoverable, reversible rule, unlike "sometimes taps land, sometimes they don't".
- The alternative — merged hit-testing across both instances, picking the topmost by the Z1
  ordering — is strictly better UX and strictly worse risk. It can be layered on later without
  changing anything below; the inert-below decision does not foreclose it.

Second question, also settled: **the below instance must not double-render.** Each instance is
fed its own bucket from `partitionAroundVideo`, never the full list, so an item is drawn by
exactly one of them. With the "below" bucket empty (every current project), the below instances
have nothing to draw and can skip layout entirely — keeping the whole feature free until used.

## Slices (for C)

- **Z1 — ✅ DONE (`88f522d`).** `LayerPreviewController.orderedVisualItems`, with all three
  `visible*` methods derived from it; equivalence proved over 11 real projects by
  `tasks/visible_equiv.py`.
- **Z2 — ✅ DONE.** `partitionAroundVideo` / `paintsBelowVideo` / `topPipLaneZ`. An item is
  BEHIND when its lane's zIndex is strictly below the highest PiP-bearing lane's; the PiPs
  themselves belong to neither bucket (they ARE the plane). Ties go ABOVE deliberately — equal
  zIndex means the user expressed no ordering, so "no opinion" keeps today's look instead of
  silently pushing content behind the video. **Inert by construction and proven so**: with no
  PiP, or with every zIndex at its default 0, the below bucket is empty and every consumer sees
  exactly today's order — asserted for all 11 real projects, plus synthetic active/tie cases.
- **Z3 — ✅ BUILT (visual verification owed).** Two new surfaces in the layout BENEATH
  `OverlayVideoPreviewView` — a second `SpriteOverlayView` and `TextOverlayLayer` — fed the
  "below" bucket; the existing surfaces now take the "above" bucket
  (`visibleTextOverlaysAboveVideo` / `visibleSpriteItemsAboveVideo`, converted mechanically at
  all 13 preview call sites). No new drawing code, same renderers.
  - The below instances are **draw-only**: both view classes gained `setInteractive(false)`,
    which returns early from touch. `android:clickable="false"` was NOT enough — it does not
    stop a custom view's own touch handling, and two hit-testing text layers would have had
    the top one silently eat taps meant for the bottom.
  - They are fed the REAL callbacks, not a no-op: the below surface still needs the content
    rect to lay items out, and the sprite one needs sheet/renderer lookups to draw at all.
  - Fed from `syncTimelineOverlays()` alone rather than all fourteen above-surface sites,
    because bucket membership only changes when a lane's zIndex or an item's lane changes, and
    every such path funnels through that sync.
- **Z4 — ✅ BUILT (frame-diff proof owed).** A second `CompositeExportOverlay` for the "below"
  bucket, added to the effect chain BEFORE the PiP blends — chain order IS paint order — and
  skipped entirely when that bucket is empty. The existing pass keeps the "above" bucket.
  Captions/waveforms deliberately stay in the ABOVE pass: they are clip- and instance-owned
  rather than lane-owned, so they have no lane z to sit below.
  - ⚠️ Z3 and Z4 had to land TOGETHER. Shipping Z4 alone would have been reachable-divergent:
    the lane move-up/down UI already writes zIndex, so a user could have made export honour an
    ordering the preview ignored — the exact failure this spec exists to prevent.
  - Still owed: the absolute-geometry A/B frame diff (memory: `ab-export-frame-diff-proof` —
    symmetric proofs miss flips).
- **Z5 — UI.** Nothing new: lane move-up/down already exists and already writes zIndex. The
  point of Z1-Z4 is that those controls finally do what their name says across types.

## Acceptance

1. A text on a lane below a PiP-bearing lane renders BEHIND the PiP, in preview and export.
2. Moving that lane above the PiP lane flips it, in both, with no other visual change.
3. A project that never reorders lanes is pixel-identical to today (the ordering degenerates to
   the current type order when every zIndex is 0 — assert this in the simulation, not by eye).
4. Export A/B frame diff confirms 1 and 2 with absolute geometry.

### RUN 2026-07-26 03:45–04:10, Note 9 — 1, 2 and 4 all PASS (Z3/Z4 debt cleared)

Three throwaway clones, identical in every respect except the one variable, each exported at
480p/Low (480x854, 4.229 s, 253 decoded frames) and diffed with `tasks/export_ab_diff.py`:

| fixture | text lane zIndex | PiP lane zIndex | text |
|---|---|---|---|
| `Zbelow`  | 0 | 5 | present |
| `Zabove`  | 9 | 5 | present |
| `Znotext` | – | 5 | removed |

Geometry is absolute and asymmetric — text centre (0.42, 0.60), PiP centre (0.45, 0.55);
`--check-asym` confirms no x-, y- or xy-flip maps either onto the other or onto itself, so a
flip cannot hide (the a4fbeba lesson).

**Acceptance 1 — text on the lower lane really is BEHIND the PiP.** In `Zbelow` the glyph run
`ZPROBE` comes out as `ZP…E`: measured over the text band (rows 429–564, threshold 40/255,
which is far above the 8/255 residual measured elsewhere), the hidden columns form **exactly
one contiguous band, x 148–283 (136 px)**, with surviving glyph pixels in **exactly two runs,
x 92–147 and x 285–311 — one on each side of it**. That band is the PiP rectangle. This is a
strictly stronger result than the full occlusion the fixture was aiming for: a
"text never drawn" bug would hide ALL glyph columns, and a "z ignored" bug would hide NONE.
Only real clipping by the PiP's silhouette produces a hidden middle with visible ends.

**Acceptance 2 — the flip changes nothing else.** `Zabove` shows the complete glyph run over
the PiP. Outside the text's own band, `Zbelow`, `Zabove` and `Znotext` are **pixel-identical**:
zero pixels above 8/255, worst residual 5–8, which is lossy-encode noise. So "with no other
visual change" is measured, not assumed.

**Acceptance 2, preview leg** — verified on the same fixtures before exporting: `ZPROBE` is
invisible at zIndex 0 and visible at zIndex 9, with nothing else in the frame moving.

**Acceptance 4 — confirmed**, per the two blocks above.

**Acceptance 3** stays where it was: proved by simulation (`tasks/visible_equiv.py`, all-zero
zIndex ⇒ below bucket empty ⇒ today's order) rather than by eye, which is what it asks for.
The neutral-substrate item-8 run adds a pixel-level version of the same claim — with every
zIndex at its default, a neutral lane and own-type lanes exported **0/253 differing frames**.

Two traps for whoever repeats this:
- Do NOT compare mp4 hashes. Separate encodes of the same composite differ in container bytes
  while every frame is identical.
- Do NOT threshold a cross-encode diff at 1. Lossy residual reaches 8/255 on high-contrast
  edges; an x-projection at that threshold reported occluded columns out to the canvas edge
  and made a clean result look broken. 40/255 separates signal from noise here.
- The PiP's `scale: 0.90` renders ~136 px wide on a 480 px canvas, so it is NOT a fraction of
  canvas width. Do not size a fixture assuming it is — that is why the intended full
  occlusion came out partial.
