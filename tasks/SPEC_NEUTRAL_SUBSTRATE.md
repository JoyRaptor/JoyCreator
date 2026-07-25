# SPEC: Neutral substrate — "any object on any layer"

> **HANDOFF (2026-07-25, Fable 5 frontier pass) — READ THIS BLOCK FIRST.**
> **THE DESIGN IS FULL NEUTRALITY** (JoyRaptor, settled — see DECISION). Any visual object on any
> floating lane, including the seeded ones. Audio is the only separate band.
> **DONE + COMMITTED this pass:** S0, S1, S2, S2b, S4, S5. Only S3 (row cosmetics) is left.
> **DEVICE STATUS — READ BEFORE CLAIMING THIS WORKS.**
> - VERIFIED (Note 9, on the pre-rewrite build): injecting a `LAYER` def into sandbox project
>   `129d8643` produced ONE merged row — the editor's OWN autosave re-serialized a single
>   `kind=LAYER "Layer 1"` track holding `[textOverlay, sprite, clip]` (serializeTrack writes
>   live `getLayers()` output, so the merge is proven end-to-end), and the lane's sprite
>   rendered in preview. That validates the merge + S4 shape.
> - **NOT YET VERIFIED: the full-neutrality rewrite** (layerId-first routing, S1/S2/S2b, and
>   payloads landing on ANOTHER type's seeded lane). It is compile-green only. The device run
>   was aborted mid-test because a screenshot showed the phone in HUMAN use — tap injection
>   stopped there, per house rules.
> - The sandbox project was RESTORED to its exact original 37879 bytes. **Caution for whoever
>   resumes:** the app rotates its own `project.json.bak`, so that file is NOT a safe
>   pristine snapshot — capture the original to the host before injecting anything.
> - Fixture recipes (python, run against a host-side copy then `adb push` + `run-as cp`):
>   (a) merge test — add `{id:"L1",kind:"LAYER",name:"Layer 1"}` to
>   `timeline.layers.trackDefs` and set one text + one sprite + one overlayClip `layerId` to
>   `"L1"`; (b) full-neutrality test — NO new def at all: set a sprite's and an overlayClip's
>   `layerId` to `"text"` and a text overlay's to `"sprite"`, then expect the seeded "Text"
>   row to hold the sprite + PiP and the "Sprite" row to hold the text.
> **ALL SLICES S0–S5 ARE NOW DONE.** What remains is DEVICE VERIFICATION (queue at the
> bottom) — the model half is proven by `tasks/getlayers_equiv.py`, but no gesture has been
> driven on a phone.
> **INVARIANTS YOU MUST NOT BREAK:**
> 1. **Existing projects keep their exact row ORDER.** Kind no longer gates membership, but
>    it still decides which emission phase a lane appears in (`emitDefs` calls in
>    `getLayers()`). Do not reorder or collapse those phases.
> 2. `overlayClips`' layerId must NEVER be null (null = master-clip semantics;
>    `isOverlayClip()` breaks). Both staging paths store a literal id for clips — keep that.
> 3. The omit-when-default convention is PER PAYLOAD TYPE (text→"text", sprite→"sprite",
>    audio→"audio"). Do not go back to a single hardcoded pair.
> 4. Cross-type z-order inside a mixed lane is the GLOBAL SURFACE STACK, not lane order
>    (see §Z-ORDER). Do not try to interleave types per-lane — that is a compositor
>    unification, explicitly out of scope.
> 5. Preview/export visibility decisions live ONLY in `LayerPreviewController.visible*`
>    (shared authority). Never add a second filter in ExportManager.
> 6. AUDIO stays its own band: an audio lane takes only audio, a floating lane never does.
> 7. Do NOT reintroduce a `getKind()` membership check in the preview/export filters or in
>    `payloadCompatible`. Every such check deleted this pass was a bug under this design.

## Feature

A user-created floating lane ("layer") holds text + sticker + sprite + image + video (PiP)
items MIXED: drop any visual object onto it, one timeline row, correct preview, correct export.

## DECISION — SETTLED (JoyRaptor, 2026-07-25): FULL NEUTRALITY

**Every floating row is a neutral substrate.** Any visual object can go on any floating lane,
including the seeded "text"/"sprite"/"video" lanes. Rows are not typed bins.

> JoyRaptor: *"as a user, so much better to have the layers just be a substrate that anything can
> go on so that you can stack things however you like. Having these limitations and labeled
> rows that only certain things can go in is frustrating. The only specific track that should
> be separate is audio-only rows, because they don't get visually rendered anyway."*

An earlier draft of this spec recommended user-created-lanes-only neutrality for blast-radius
reasons. **That was rejected and is not the design.** The rationale against it: predictability
of a typed row is worth less than the freedom to stack anything anywhere, and a half-neutral
model is *more* confusing than either extreme (some rows reject drops for invisible reasons).

Consequences, all implemented:
- `TrackKind` no longer gates membership anywhere. It survives as (a) the row's label /
  affordance hint and (b) the key that decides where in the band a lane is EMITTED, which is
  what keeps existing projects' row order unchanged.
- `Timeline.getLayers()` routes **layerId-first**: a row is the set of visual items sharing a
  layerId, whatever backing list they live in.
- The three seeded ids ("text"/"sprite"/"video") are just lanes that happen to be where
  never-homed items land. A lane stays alive while ANY payload type has items on it.
- New lanes created by any affordance are `TrackKind.LAYER`.
- **AUDIO is the one real split** — audio clips are never visually composited, so an audio
  lane takes only audio and a floating lane never takes audio.

### Open follow-on (JoyRaptor, same conversation) — NOT BUILT, own lane
> *"if a video has an audio track, then it should have the same sort of drop-down drawer for
> the audio specifically, like we have in the main drawer... that way you can visually see
> things going on in the preview tape at the same time as transcripts and audio waves."*

I.e. a video/master row gets an EXPANDABLE audio sub-drawer showing that clip's waveform (and
transcript) aligned under its visual tape, instead of the audio living only in a separate band.
This is a timeline-UI feature, not a substrate change — it needs its own spec (row-height /
expand state / waveform-cache reuse / transcript alignment). Do not fold it into these slices.

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

## ADVERSARIAL REVIEW (2026-07-25, post-`d420272`) — 2 real bugs found + fixed

Reviewing my own rewrite before building on it. Both bugs were introduced BY the rewrite;
neither would have shown up in a compile or a happy-path click-through.

**BUG 1 — the floating band is not all lanes (data-corrupting).** `FaditorEditorActivity`
(~10605) builds the band as `getLayers()` + `getVisualizerTracks()` + `getCaptionTracks()`.
CAPTION and VISUALIZER rows are READ-ONLY VIEWS: captions are clip-owned, and
`WaveformOverlayInstance` has no `layerId` at all. The rewritten `payloadCompatible` accepted
any visual payload on any non-AUDIO row, so dropping a text on the CC row would set its
`layerId = "caption"` — an id nothing routes to, orphaning the item into a phantom lane.
FIX: `TrackKind.isLane()`, a deliberate WHITELIST (`VIDEO/IMAGE/TEXT/STICKER/SPRITE/LAYER`).
A future kind therefore defaults to not-a-lane: a refused drop is an annoyance, a silently
orphaned item is data loss.

**BUG 2 — orphan ids lost their row position and name.** I had replaced the three per-phase
leftover flushes with ONE flush at the end of the band. Real projects DO carry orphan layerIds
(the sandbox project has a legacy `sprite-<uuid>` sprite with no def), so those rows jumped to
the bottom of the band and were renamed "Sprite" → "Layer" — violating invariant 1.
FIX: per-phase flushes restored in the exact historical order, each skipping ids owned by a
def. That skip is load-bearing in its own right: without it a LAYER def holding text sits in
the text map, the text flush would emit its items under a leftover name, and the def would
then emit an EMPTY lane.

**PROOF (not an argument): `tasks/getlayers_equiv.py`.** Simulates the OLD and NEW emission
algorithms over real `project.json` files and diffs the emitted row sequence (id, kind, name,
ordered item ids). Pre-sort order is compared — `sortBandByZIndex` is unchanged and stable, so
equal input order ⇒ equal output order. Run against 10 projects pulled from the Note 9:
**9 identical**; the 1 diff is the LAYER fixture, showing exactly the intended merge (3 split
rows → 1) while the orphan sprite row keeps its kind, name and position. Re-run it after any
future `getLayers()` edit:
```bash
python tasks/getlayers_equiv.py "/path/to/pulled/proj_*.json"
```

**NOT a bug, but a semantic worth stating: one lane = one timeline track.**
`LayerGestureController.nearestFreeStart` treats every sibling in a row as an occupied block
regardless of payload type, so two items on ONE lane can never overlap in TIME. That is the
industry-standard model (Premiere/CapCut: a track holds mixed media, but clips on it cannot
overlap; simultaneity comes from stacking tracks) and it is preserved deliberately — a row is
drawn as one horizontal strip, so overlapping bodies would collide and be unpickable.
Consequence to watch in hand-testing: dropping a sprite onto a Text lane that is already busy
at that moment SLIDES it to the nearest free slot rather than layering it. If that reads as
broken to users, the fix is UX (reject + hint, or auto-pick a free lane), NOT relaxing the
resolver.

**Verified-not-broken while looking (no change needed):** schema stamping. A payload can only
reach a foreign seeded lane if that lane exists, which implies sprites (stamp v9) or overlay
clips (v8), and a user lane implies a def (v8) — so every reachable cross-lane state already
stamps ≥ 8. `layerId` is serialized unconditionally for all four payload types, so membership
round-trips regardless of the stamp.

## Slices

### S0 — model foundation — FRONTIER — ✅ DONE (rewritten to layerId-first)
Sites:
- `layers/TrackKind.java`: new `LAYER` enum value (name-serialized only, no ordinal use
  anywhere; `fromName` falls back to VIDEO on OLD builds reading NEW projects — degrades to
  a PiP-kind row rather than crashing, acceptable).
- `model/Timeline.getLayers()` (~1112): rewritten as **layerId-first routing**. Three
  grouping maps (text/sprite/video) are built up front and bundled in a `LaneBuckets`
  holder; `buildLaneTrack(id, …)` drains ALL THREE by id, so a lane is whatever shares that
  layerId. Emission order preserves the historical band order: seeded "text", TEXT/STICKER
  defs, seeded "sprite", SPRITE defs, seeded "video", VIDEO/IMAGE defs, LAYER defs, then one
  final orphan flush (a single flush, so an orphan id holding several types is ONE lane).
  A seeded lane is emitted when ANY payload type has items for it. The three old per-type
  builders (`buildTextTrack`/`buildSpriteTrack`/`buildVideoTrack`) collapsed into
  `buildLaneTrack`; overlay-clip item construction stayed factored as `videoTimedItem(Clip)`
  so the persisted-field mirroring (overlayStartMs/transform/blend) cannot drift.
- `model/Timeline.layerTrackHasItems()` (~2048): now also scans `spriteOverlays` and
  `overlayClips` — pre-existing bug: a user SPRITE/VIDEO def with remaining items could be
  pruned by `maybeRemoveEmptyLayerTrack`, orphaning items into defensive leftover buckets.
  Mandatory for LAYER lanes (they'd be pruned while still holding sprites/PiPs).
- Storage: FREE. ProjectStorage serializes `def.getKind().name()` (~1864) and reads via
  `TrackKind.fromName` (~2452). No schema bump.
Acceptance (met): zero LAYER defs ⇒ byte-identical `getLayers()` output (neutral ids set is
empty, every new branch dead); compile green.

### S1 — drop compatibility — FRONTIER — ✅ DONE (rewritten for full neutrality)
- `layers/LayerGestureController.payloadCompatible` (~1702) is now two rules instead of a
  per-kind table: an AUDIO candidate takes only audio; every other (floating) row takes any
  visual payload. Net deletion of code.

### S2 — creation UI — ✅ DONE (promoted from MECHANICAL: it is the UX of the decision)
Every "new lane" affordance now creates a neutral `TrackKind.LAYER` lane:
- `stageCreateLayerAndMoveItem` (~12194): floating band → `LAYER`, named `"Layer n"`
  (was TEXT/"Text n"). Audio band still → `AUDIO`.
- `assignTextOverlayToFreeLane` (~15148): candidate lanes are now every non-AUDIO def (was
  TEXT-only — without this a neutral lane would never be reused and every new overlay would
  spawn another lane); the lane it creates when all are busy is `LAYER`/"Layer n".
- `moveOverlayItemToNewLayer` (~17853) and add-image-as-new-layer (~16111): `LAYER` kind,
  names kept ("Image n"/"Text n" still describe what was put there).
- Existing TEXT/STICKER/SPRITE/VIDEO defs keep their stored kind — no migration needed,
  because kind no longer gates membership. They are already neutral lanes.

### S2b — sprite/PiP payloads in drop staging — ✅ DONE (was blocking the whole feature)
`stageMoveItemToLayerTrack` handled text/audio/clip but NOT sprite (a sprite dropped on
another lane silently reverted), and `stageCreateLayerAndMoveItem` returned null for both
sprite and clip payloads (neither could open a lane). Both now handle all four payloads via
a shared `applyMovedLayerId` helper, so the apply/redo/undo halves cannot drift.
- Default-lane id is now PER PAYLOAD TYPE for the omit-when-default convention: text→"text",
  sprite→"sprite", audio→"audio"; an overlay clip ALWAYS stores a literal id (invariant 2).
  A payload landing on another type's seeded lane stores that id literally and merges there.

### S3 — mixed-row cosmetics — ✅ DONE (was mostly already built)
Audit found the row header had ALREADY been made neutral by JoyRaptor's 2026-07-19 change: the
kind badge moved off the gutter onto each OBJECT (`payloadKindOf` per item), and the header
keeps only caret + mute — "no name, no kind identity" (`LayerRowRenderer` ~605). Nothing to
add there; item bodies were already per-item-kind.
- The one real gap, now fixed: the mute icon's applicability keyed off
  `kind == AUDIO || VIDEO || MASTER`. Once lanes stopped being branded by type that stopped
  being the same question as "holds audio" — a neutral lane holding a PiP drew the greyed
  not-applicable icon, and an emptied VIDEO-kind lane still drew the live one. Now decided by
  CONTENT via `rowCarriesAudio(Track)`: MASTER/AUDIO always, else any audio clip or overlay
  (PiP) clip among the row's items.
- Row height: a neutral lane uses the default (non-AUDIO) height — correct, left alone.
- Deliberately NOT done: relabelling the seeded "Text"/"Sprite"/"PiP" rows by content. They
  are stable, user-renameable (TrackFlags `customName`), and churning a row's name as its
  contents change would be worse than a slightly stale label.

### S4 — preview z-order across mixed lanes — FRONTIER — ✅ DONE
- `compositor/LayerPreviewController`: the kind checks in `visibleTextOverlays`,
  `visibleSpriteItems` and `visibleOverlayVideoClips` were DELETED outright. `getLayers()`
  returns only floating lanes, so each method now walks every lane in zIndex order and lets
  its existing per-item payload check (+ per-object eye) select its surface's items.
  Lane-level hidden/zIndex flags apply uniformly to every lane.
- `visibleImageItems` still filters `TrackKind.IMAGE` and is genuinely inert: no code path
  ever creates an IMAGE-kind lane (images ride `TextOverlayItem.isImage()`). Left alone.

### S5 — export mapping for mixed lanes — FRONTIER — ✅ DONE (same edit as S4)
- Export consumes the SAME three methods (ExportManager:2311/2316/2321) — no export-side
  change. `usesLayerFeaturesAffectingExport` (:2088) already forces the full re-encode path
  whenever ANY extra layer def exists, LAYER included.

## Validation queue (device — OWED for the full-neutrality rewrite)

Model-level (injection, cheap, deterministic — use recipe (b) in the handoff block):
1. Sprite + PiP with `layerId:"text"` and a text overlay with `layerId:"sprite"` ⇒ the
   seeded "Text" row shows the sprite + PiP bodies, the "Sprite" row shows the text pill.
   No extra rows appear, nothing is dropped.
2. Preview renders all of them; z is the surface stack (PiP under sprite under text).
3. Lane eye on a mixed row hides every payload type on it at once.
4. Save/reload keeps the same row membership (layerIds round-trip literally).

Gesture-level (tap injection — needs an idle device; drag-and-drop drifts per the
device-input-injection-limits memory, so prefer short drags with generous settle):
5. Drag a sprite onto the "Text" row ⇒ accepted (was rejected before S1) and it lands.
6. Drag a PiP onto a text-holding lane ⇒ accepted; undo returns it with its layerId literal
   (never null — invariant 2).
7. Drop any item into the between-rows gap ⇒ a lane named "Layer n" appears (not "Text n"),
   and a DIFFERENT payload type can then be dropped onto it.

Export:
8. Absolute-geometry A/B frame diff (memory: ab-export-frame-diff-proof) vs the same project
   with items on their own-type lanes — item pixels must be IDENTICAL (lane membership must
   not move pixels), and an eye-hidden lane must export without any of its payloads.
