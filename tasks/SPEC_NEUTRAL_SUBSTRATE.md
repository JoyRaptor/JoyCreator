# SPEC: Neutral substrate — "any object on any layer"

> **HANDOFF (2026-07-25, Fable 5 frontier pass) — READ THIS BLOCK FIRST.**
> **DONE + COMMITTED this pass:** S0 (inert foundation, byte-identical proof below), S4+S5
> (preview/export filter relax — one shared site), and S1 (payloadCompatible relax).
> **CHEAPER MODEL STARTS ON: S2** (creation UI), then S3 (row cosmetics), then the S2b
> sprite-move gap. All remaining work is mechanical; the sites are exact.
> **INVARIANTS YOU MUST NOT BREAK:**
> 1. A project with ZERO `TrackKind.LAYER` defs must behave byte-identically to before —
>    every neutral-substrate branch is gated on a LAYER def existing.
> 2. `overlayClips`' layerId must NEVER be null (null = master-clip semantics;
>    `isOverlayClip()` breaks). `stageMoveItemToLayerTrack` already handles this
>    (`clipToStored`) — keep that shape in any new code.
> 3. Cross-type z-order inside a mixed lane is the GLOBAL SURFACE STACK, not track order
>    (see §Z-ORDER). Do not try to interleave types per-lane — that is a compositor
>    unification, explicitly out of scope.
> 4. Preview/export visibility decisions live ONLY in `LayerPreviewController.visible*`
>    (shared authority). Never add a second filter in ExportManager.
> 5. Default rows ("text"/"sprite"/"video"/"audio") stay TYPE-PURE (§DECISION). Only
>    user-created LAYER tracks are neutral.
> 6. AUDIO never joins a LAYER lane (different band; `payloadCompatible` keeps audio→AUDIO).

## Feature

A user-created floating lane ("layer") holds text + sticker + sprite + image + video (PiP)
items MIXED: drop any visual object onto it, one timeline row, correct preview, correct export.

## DECISION (recommended; ratify with JoyRaptor/JoyRaptor)

**User-created-neutral**: only lanes the user explicitly creates get `TrackKind.LAYER` and
accept every visual payload. The default "text"/"sprite"/"video" rows stay type-pure.

- JoyRaptor's phrasing "payloadCompatible relaxed for the floating band" has two readings:
  (a) every floating row accepts anything, or (b) only user-created lanes do.
- Recommend **(b)**: predictable default rows (the "Text" row never silently becomes a junk
  drawer), much smaller blast radius (`getLayers()` default-bucket passes untouched,
  auto-lane routing like `assignTextOverlayToFreeLane` FaditorEditorActivity:15148 untouched),
  and (a) remains a pure superset we can enable later by widening `payloadCompatible` only.
- Consequence of (b): dragging a sprite onto the default "Text" row still rejects. That is
  the intended predictability, not a bug.

## Z-ORDER MODEL (the S4/S5 architectural finding)

The preview is NOT one compositor. It is five stacked type-specific surfaces
(activity_faditor_editor.xml:536-566, bottom→top):
`OverlayVideoPreviewView` (PiP) → `WaveformOverlayView` → `LayerImageOverlayView` →
`SpriteOverlayView` → `TextOverlayLayer`.
Export matches by construction: PiPs composite in the GL effect chain (below everything;
see CompositeExportOverlay.java:183-186 z-unification note), then `CompositeExportOverlay`
draws sprites FIRST, then text/captions (CompositeExportOverlay.java ~410 "drawn FIRST"
comment) — same global order.

Therefore, inside a mixed lane:
- **Cross-type** stacking (a text vs a PiP on the same lane) = the global surface stack
  (PiP under sprite under text), identical in preview and export. Track z / item order
  cannot override it. This is the documented, accepted semantic.
- **Per-type** stacking still follows track order: each `visible*` method walks tracks
  zIndex-sorted and appends that track's items of its own type, so two texts on different
  lanes keep lane-relative z, whether the lanes are TEXT or LAYER.

True per-lane cross-type interleaving would require unifying five surfaces into one
compositor (and the export bitmap/GL split) — flagged, out of scope.

## Slices

### S0 — inert model foundation — FRONTIER — ✅ DONE
Sites:
- `layers/TrackKind.java`: new `LAYER` enum value (name-serialized only, no ordinal use
  anywhere; `fromName` falls back to VIDEO on OLD builds reading NEW projects — degrades to
  a PiP-kind row rather than crashing, acceptable).
- `model/Timeline.getLayers()` (~1112): neutral merge pass. LAYER-def ids are collected up
  front; each per-type leftover flush SKIPS those buckets; after the video pass, one Track
  per LAYER def is built holding its text + sprite + video items (type-grouped item order —
  see Z-ORDER). Shared video-item construction extracted to `videoTimedItem(Clip)` so the
  overlay-field mirroring (overlayStartMs/transform/blend) cannot drift.
- `model/Timeline.layerTrackHasItems()` (~2048): now also scans `spriteOverlays` and
  `overlayClips` — pre-existing bug: a user SPRITE/VIDEO def with remaining items could be
  pruned by `maybeRemoveEmptyLayerTrack`, orphaning items into defensive leftover buckets.
  Mandatory for LAYER lanes (they'd be pruned while still holding sprites/PiPs).
- Storage: FREE. ProjectStorage serializes `def.getKind().name()` (~1864) and reads via
  `TrackKind.fromName` (~2452). No schema bump.
Acceptance (met): zero LAYER defs ⇒ byte-identical `getLayers()` output (neutral ids set is
empty, every new branch dead); compile green.

### S1 — drop compatibility — FRONTIER (tiny but semantics-bearing) — ✅ DONE
- `layers/LayerGestureController.payloadCompatible` (~1702): a `LAYER` candidate accepts any
  payload EXCEPT audio (audio band is physically separate; the same-band guard in
  `updateDragTarget` already blocks cross-band drops — this keeps the model consistent
  anyway). Default rows unchanged (DECISION (b)).

### S2 — creation UI — MECHANICAL — TODO (start here)
- `FaditorEditorActivity.stageCreateLayerAndMoveItem` (~12174): for the floating band,
  `newKind` = `TrackKind.LAYER` (currently TEXT), name `"Layer " + n` (currently "Text n").
  Everything else (zIndex splice, undo halves, def restore) is already kind-agnostic.
- Existing TEXT/STICKER/SPRITE user tracks stay their old kinds — no migration. (Optional
  later: a "convert lane to neutral" affordance = `LayerTrackDef.setKind(LAYER)` + save.)
- Acceptance: drag a text item to the new-layer drop zone → lane named "Layer n" appears;
  a PiP item can then be dropped onto it; save/reload keeps the lane + both items on it;
  undo of the creation removes lane + returns item.

### S2b — sprite/PiP payloads in drop staging — MECHANICAL — TODO
- `stageCreateLayerAndMoveItem` (~12178) returns null for sprite AND clip payloads —
  sprites/PiPs can't create a new lane by drop. `stageMoveItemToLayerTrack` (~12121)
  handles text/audio/clip but NOT sprite — a sprite dropped on an existing lane silently
  reverts. Add the `item.getSprite()` branch mirroring the text branch (default id is
  "sprite" for the omit-when-default convention), and widen stageCreateLayerAndMoveItem to
  sprite + overlay-clip payloads (clip: NEVER store null layerId — invariant 2).
- Note default-id nuance: the omit-when-default check currently tests only "text"/"audio";
  a sprite's default is "sprite", a PiP's is "video". Follow `clipFromStored`'s shape.

### S3 — mixed-row cosmetics — MECHANICAL — TODO
- `layers/LayerRowRenderer`: item bodies already render per-ITEM kind (`kindForItem` ~1871)
  — nothing to do there. Row header: give LAYER rows their own glyph/label (e.g. "◆"/name);
  mute icon (~613) currently shows the no-audio variant for LAYER — pass hasAudio=true when
  the lane holds a PiP with audio, or just always audio-capable for LAYER.
- Row height: LAYER uses the default (non-AUDIO) height — correct, leave it.

### S4 — preview z-order across mixed lanes — FRONTIER — ✅ DONE
- `compositor/LayerPreviewController`: `visibleTextOverlays` (:64), `visibleSpriteItems`
  (:112), `visibleOverlayVideoClips` (:161) each also accept `TrackKind.LAYER` tracks; the
  existing per-item payload null-checks (+ per-object eye) pick out that surface's items.
  Track-level hidden/zIndex flags now apply to a LAYER lane exactly like any other.

### S5 — export mapping for mixed lanes — FRONTIER — ✅ DONE (same edit as S4)
- Export consumes the SAME three methods (ExportManager:2311/2316/2321) — no export-side
  change. `usesLayerFeaturesAffectingExport` (:2088) already forces the full re-encode path
  whenever ANY extra layer def exists, LAYER included.

## Validation recipe (for the device pass — not yet run)

1. Inject into a sandbox project.json: one `trackDefs` entry `{id:"L1",kind:"LAYER",
   name:"Layer 1"}`; set one textOverlay's layerId="L1", one spriteOverlay's layerId="L1",
   one overlayClip's layerId="L1".
2. Open editor: expect ONE "Layer 1" row containing all three item bodies (text pill,
   sprite thumb, PiP block), not three rows.
3. Preview: all three visible; text above sprite above PiP (surface stack).
4. Lane eye (hide) on "Layer 1": all three vanish from preview.
5. Export: absolute-geometry A/B frame diff (memory: ab-export-frame-diff-proof) vs the
   same project with the items on their default lanes — item pixels identical (lane
   membership must not move pixels), and the eye-hidden variant exports without them.
6. Save/reload: still one row (round-trip through trackDefs + serialized layers array).
