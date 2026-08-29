# PLAN — SPEC_20260829_IMAGE_ANIM_PRESETS: Image animation presets (documentary moves)

**Lane:** `SPEC_20260829_IMAGE_ANIM_PRESETS` — claim before first edit (`LANES.md:155`)
**Status:** PLAN (awaiting user confirmation before implementation per AGENTS.md §2)
**Spec:** `tasks/SPEC_20260829_IMAGE_ANIM_PRESETS.md` (244 lines, written 2026-08-29)
**Depends on:** `SPEC_20260829_KEYFRAME_SHAPES` §6 (amber preset-owned state, `KeyframeGlyph` colour-as-caller)
**Contended file:** `FaditorEditorActivity.java` — DO NOT TAKE until `LANES.md:CAPTION_LAYERS` shows IDLE (spec §4 Phase 3). Phases 1+2 are free.

---

## 0. Why / governing sentence

> "They should LOOK like keyframes and you should see the same rubber bands where applicable etc, and be able to tweak them as if they were real, so when the transition from wizard-type preset gets converted to pro manual edits it feels seamless — you're not having to change your mental model."

**So: real keyframes in the real model from the first moment.** Preset OWNS two `presetOwned=true` keys per affected track at the item's visible ends; user drag in timeline clears all flags + sets preset NONE in one undo step. Preview edits update preset params and rewrite owned keys WITHOUT converting.

Also a teaching tool: preset animation IS keyframe animation, safely tweakable.

---

## 1. What already exists (inventory)

| Thing | Where | Notes |
|---|---|---|
| Image overlay model | `model/TextOverlayItem.java:843` `isImage()` `imageUri != null`, line 848 | position `centerX/Y` 0..1, `sizeFraction`, `scaleX/Y` + `scaleLinked`, `rotationDeg`, `opacity`, `startMs/endMs`, `layerId`, `keyframes:KeyframeSet` |
| Animation | `keyframe/KeyframeSet.java:20` tracks `X,Y,SCALE,SCALE_X,SCALE_Y,OPACITY,ROTATION` | `X/Y` are centre 0..1, `SCALE` absolute fraction, `OPACITY` 0..1 |
| One key | `keyframe/Keyframe.java:14` fields `timeMs,value,easing` | **Add `presetOwned` here** |
| Curves | `keyframe/Easing.java` 15 values | DO NOT ADD |
| Glyph | `keyframe/KeyframeGlyph.java` `silhouetteFor`/`curveFor`/`pathFor` | colour supplied by caller — amber is caller choice |
| Codec | `keyframe/KeyframeCodec.java` writes `{t,v,e}` | **Add `"p":true` only when presetOwned** |
| Persistence overlay | `project/ProjectStorage.java:2179,2248,2941,3032` `oJson.add("keyframes", tracksJson)` via hand-rolled loops (NOT via KeyframeCodec for TextOverlayItem) + also `keyframe/KeyframeCodec.toJson` for Clip masks | Must update BOTH writers/readers for TextOverlayItem overlay path |
| Row renderer | `layers/LayerRowRenderer.java` draws keyframe diamonds, audio fade handles `drawFadeHandles`, envelope `drawItemOpacityEnvelope` | Phase 2 site: amber + image-opacity fade handles |
| Audio fades | `model/AudioClip.java:478` `getFadeInMs/setFadeInMs`, `layers/LayerGestureController.java:GestureKind.FADE_IN/OUT`, `LayerRowRenderer.java:2630 drawFadeHandles` | **Reuse, do not duplicate** for image opacity |
| Timeline item wrapper | `layers/Track.java`, `TimedItem.java` | Generic; no change |
| Preview canvas | `FaditorEditorActivity.java` + `LayerPreviewController` / `TextOverlayItem.animated*` | reads `KeyframeSet.valueAt` |

Trap carryovers: `getSelectedClip()` → `getClip(0)` when nothing selected — use `clipUnderPlayhead()`; written-never-read if draw site doesn't check flag; child clipped by parent at bar corners.

---

## 2. Phase decomposition (spec §4 order, respect LANES)

### Phase 1 — Model & engine (no contention, start here)

**2.1 `keyframe/Keyframe.java` — one field**

```java
/** True while this key is maintained by a preset (amber). Cleared permanently on first timeline drag. */
public boolean presetOwned = false;
```
- `copy()` must copy flag.
- Verify `KeyframeTrack.put` preserves flag when overwriting same time? Currently `put` overwrites `value`+`easing` on existing time but would drop `presetOwned`. Decide: `put` should also accept/clear flag? For preset rewrite we will remove then put, so not critical — but audit `moveKeyframeLocalTime` which uses `put` after `removeAt` — ensure moved key's flag travels via `found.copy()` (it does if copy carries flag).
- Check `TransformSnapshot` equality: currently compares time/value/easing only — must include presetOwned + preset kind or undo restores colour but not descriptor (acceptance #8 half-undo).

**2.2 `keyframe/KeyframeCodec.java` — sparse write, tolerant read**

- `toJson`: add `if (k.presetOwned) kj.addProperty("p", true)` — ONLY when true, so every existing file stays byte-identical (old builds ignore unknown key).
- `fromJson`: `boolean presetOwned = kj.has("p") && kj.get("p").getAsBoolean();` default false. Pass to `tr.put(..., easing)` then set flag on found entry? Or extend `put` signature to accept flag. Simplest: after `put`, find key at that time and set `presetOwned`. Keeps `put` API unchanged for callers that shouldn't set amber.
- Verify both codec paths are tolerant (skip malformed key, don't throw).

**2.3 NEW `model/ImageAnimPreset.java`**

```java
public enum Kind { NONE, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN, ZOOM_IN, ZOOM_OUT,
                    SLIDE_IN_LEFT, SLIDE_IN_RIGHT, SLIDE_IN_TOP, SLIDE_IN_BOTTOM }

public class ImageAnimPreset {
  Kind kind = Kind.NONE;
  // Params that preview edits tweak without converting:
  // - zoom region: centre + scale fraction for ZOOM (default ~70% centred)
  // - possibly pan offset? For now min needed to answer "where do keys go on trim"
}
```

- Stored on `TextOverlayItem` alongside keyframes — **NOT second source of truth**. Only answers: on trim/move where do owned keys go? → ends.
- Fields: `Kind kind`; `float zoomRegionScale = 0.7f`, `float zoomRegionCenterX/Y = 0.5` (for ZOOM_IN/OUT); maybe `float rotation` tweak? Keep minimal — spec says preview zoom/position/rotation tweaks update preset params. So need generic `float paramScale, paramX/Y, paramRotation` or just store the two keyframe values themselves.
- Simplest: store the two endpoint values per animated property that the preset wrote, and preview edits mutate those stored values then rewrite keys. For PAN_* that is X or Y pair; for ZOOM it's SCALE (+ optional X/Y for region); for SLIDE it's X/Y + OPACITY.
- But spec says preset IS descriptor that makes trim-follow two-liner: re-place owned keys at new ends. So descriptor only needs to know KIND + user-tweaked params, not the exact values (those live on keys). On trim, read current owned keys' values and re-put them at new timeMs.
- Persist via `ProjectStorage` alongside TextOverlayItem: field `imageAnimPreset` as `{kind:"PAN_RIGHT", scale:..., x:..., y:...}` only when kind != NONE (tolerant read).
- `copyWithNewId` must deep-copy preset.

**2.4 `model/TextOverlayItem.java` — field + behaviour**

- Add `@Nullable ImageAnimPreset imageAnimPreset` with getter/setter, lazy `getOrCreatePreset()`.
- **Fit / Fill FIRST** (spec §3.5 — independently valuable afternoon):

```
Fit: scale so whole image visible inside canvas — min(canvasW/imgW, canvasH/imgH) scaled to sizeFraction? Need to inspect current image preview scaling: sizeFraction is fraction of video height; image drawn with intrinsic aspect. Need helper: given imageUri's intrinsic size vs canvas aspect, compute sizeFraction + scaleX/Y to just-fit or just-fill.
```

Implementation notes:
- Need image dimensions (decode bounds). Reuse existing image thumb path? `TextOverlayItem` doesn't store dimensions; preview does `ImageView` measure. For model calc, may need to defer to view layer or store dims on apply. Simpler: compute from `sizeFraction` + aspect maths assuming current sizeFraction is the drawn height — Fit = adjust sizeFraction so that longer axis fits.
- Ask: where does canvas aspect come from? Timeline's export size or preview's `getVideoContentRect()`. Must read same source that preview uses so Fit in preview matches export. Canvas aspect can change (black spacer bug §3.3) — clamp must recompute.
- Land as two methods `applyFit(canvasW, canvasH, imgW, imgH)` and `applyFill(...)` that set `sizeFraction/scaleX/scaleY` and optionally recenter to 0.5,0.5.
- No keyframes involved — direct static values. Must be undoable (TransformSnapshot already captures).

- **Sticky bookends:** method `applyPreset(Kind)` writes exactly two keys per affected track at `0` and `durationMs` (where duration = `endMs - startMs` or `timelineDuration` if open-ended). Both `presetOwned=true`, easing `EASE_IN_OUT`. Need to know canvas size + image size to compute values:
  - PAN: cover scale = minimum scale covering canvas across whole path (sample 16 points). Then start X/Y at one edge, end at opposite. Must compute cover scale per spec §3.3 and clamp.
  - ZOOM_IN: t0 = centred region at ~70% (zoomed in) → t1 = full cover? ZOOM_OUT opposite. Preview drag updates region.
  - SLIDE_IN_*: fit-to-canvas scale (not cover), X/Y off-screen at t0 → on-screen at t1, plus OPACITY 0→1.
- **Trim handling:** `setTrimmedTimeRange` already does `keyframes.shiftAll` for left-edge trim (keeps project-time). For preset, need additional step AFTER shift: find all `presetOwned` keys and re-place them at new ends `0` and `newDuration`. Two keys stuck to edges; no interior scaling. Must run whenever trim/move changes duration or start. Hook inside `setTrimmedTimeRange` or new method `reflowPresetOwnedKeys()`. For move (start shifts, duration same) — if time base shift already moved keys, then reflowing would double-move; need to decide: sticky means keys stay at visible ends, not at project times. So for preset-owned keys, ignore the `shiftAll` result and force to ends. For non-preset keys, keep shiftAll behaviour.
- **Conversion:** dragging an owned key in timeline clears `presetOwned` on EVERY key of that item + sets preset NONE, in ONE undo step. Need to hook `LayerRowRenderer`/`LayerGestureController` keyframe drag path — currently `moveKeyframeLocalTime` moves all tracks at oldLocal→newLocal. Intercept: if any moved key had `presetOwned`, then clear all flags and null preset, and push one undo. Also need to handle generic timeline diamond drag for TextOverlayItem (currently in `EditorTimelineView`? Need to locate).
- **Preview tweak does NOT convert:** editing zoom/position/rotation in preview updates preset params and rewrites owned keys. So preview gesture handler must check `imageAnimPreset != null && kind != NONE && key.presetOwned` then call `updatePresetFromPreview(newValue)` not `clearPreset`.

**2.5 `project/ProjectStorage.java` — serialization for new fields**

- TextOverlayItem serializer at `~2248` hand-writes keyframes by iterating `tr.keyframes` and writing `t,v,e` — must add `if (k.presetOwned) kj.addProperty("p", true)`.
- Deserializer at `~3032` hand-reads — must read `p` and set flag.
- Separate codec `KeyframeCodec` also used for Clip masks — update there too even though this spec doesn't use it for images, for consistency.
- New field `imageAnimPreset`: write `if (preset != null && preset.kind != NONE) { JsonObject pj = new JsonObject(); pj.addProperty("kind", preset.kind.name()); // + params } oJson.add("imageAnimPreset", pj);` Read tolerantly: absent → null/NONE; unknown enum → NONE.
- Also need to handle `TransformSnapshot` persistence via undo snapshots (which use `toJson`/`fromJson` whole-project) — already covered if ProjectStorage handles it.
- Verify byte-identical for old projects: when no preset and no presetOwned, no `p` and no `imageAnimPreset` keys written — md5 check.

---

### Phase 2 — Drawing (no FaditorEditorActivity)

**2.6 `layers/LayerRowRenderer.java` — amber + fade handles**

- **Amber:** in `drawItemKeyframeDiamonds` / `drawItemOpacityEnvelope` / new image path: where `KeyframeGlyph.pathFor` is called, choose colour based on `key.presetOwned`. Spec says use EXISTING glyph renderer, colour only — `KeyframeGlyph` already separates shape from colour, so caller just picks Paint colour `AMBER = 0xFFFFC107` or `0xFFE6A23C` when flag set. Must keep hollow/solid/selected/× states unchanged — colour is an additional dimension. No new silhouette.
- Need to know how diamonds are currently coloured: `kfDiamondPaint` green. For preset-owned, override to amber while keeping alpha for ghosted/hidden.
- Must handle consolidated diamonds (`KF_CONSOLIDATE_TOLERANCE_MS`) — if bucket contains mixed owned/non-owned? Then that bucket has both — spec says preset writes exactly two keys at ends, so no consolidation there. But if user had prior custom keys with mixed, consolidation may merge. Rule: if ANY key in bucket is presetOwned, draw amber? Simpler: don't consolidate preset keys — they are at ends, unlikely to collide.
- **Fade handles:** "exactly like volume fade handles for audio, stackable on any opacity below". Must share gesture code `LayerGestureController` FADE_IN/OUT and `LayerRowRenderer.drawFadeHandles`/`hitTest`. Currently gated `if (item.getAudioClip() != null) drawFadeHandles`. Extend to also `if (item.getTextOverlay()!=null && item.getTextOverlay().isImage())` . Same geometry: top 12dp × 20dp inboard of trim, triangle, selection-only, trim wins. Same hitTest precedence (trim outer 16dp full height, fade top 12×20 inboard, never overlapping).
- **Stackable multiply:** audio fades multiply `volumeLevel × envelope`. For image opacity, need `finalOpacity = baseAnimatedOpacity(t) × fadeMultiplier(t)` where `fadeMultiplier` is derived from fade handles (presumably two extra opacity keyframes 0→1 at ends?). Spec says "Do not have the fade write opacity keyframes" and "fade multiplies the existing opacity animation rather than replacing it." So fade must be SEPARATE from `KeyframeSet.OPACITY` track. Audio uses `VolumeKeyframe` envelope separate from volumeLevel; image needs analogous `fadeInMs/fadeOutMs` fields on `TextOverlayItem` (like `AudioClip.getFadeInMs` convention) and rendering multiplies.
- Check current `TextOverlayItem.animatedOpacity(timelineMs)` reads `KeyframeSet.OPACITY` with fallback static. For stacking, need `animatedOpacityWithFade` that returns `baseOpacity * fadeFactor(localMs)` where fadeFactor interpolates 0→1 over fadeIn and 1→0 over fadeOut, with clamp 0..1 and 0.5-dur limit.
- Preview (`LayerPreviewController`/`TextOverlayRenderer`?) and export (`CompositeExportOverlay`) both read `animatedOpacity` — must switch to multiplied version. Verify export already reads `KeyframeSet` (it does via same call), so if we only make `animatedOpacity` return multiplied value, export follows for free — but verify rather than assume (spec §4 out-of-scope note).
- Persist fade durations: add `long imageFadeInMs, imageFadeOutMs` on `TextOverlayItem`, serialized as `imageFadeInMs/out` (tolerant, default 0, clamped dur/2). Provide `getFadeInMs/setFadeInMs` mirroring `AudioClip` API so `LayerGestureController.armFade` can delegate (follow-up DRY like B1.F). For now duplicate then deduplicate.

---

### Phase 3 — Drawer UI (HELD until CAPTION_LAYERS IDLE)

**2.7 `FaditorEditorActivity.java` — preset picker + Fit/Fill + preview tweak wiring**

- Location: image-overlay drawer (where scale/position/rotation/FX live). Need to find current image drawer entry point: search `isImage`, `imageUri`, `ObjectDrawer`, `AudioDrawerTabs` analog.
- UI:
  - Row of preset chips: PAN_LEFT etc. + NONE. Tapping writes preset (with replace warning if needed). Chips show amber dot when active.
  - Two buttons Fit / Fill (always visible for images, per §3.5 — land before presets).
  - Zoom region handle in preview: dragging image in preview when preset ZOOM_* updates preset param (scale/center) and rewrites owned keys, staying amber.
  - Replace warning: when `applyPreset` called and item has `>2 keys on any track OR 2 not exactly at ends`, AND not all are presetOwned, show dialog "You seem to have a custom animation. [Replace] [Keep]" — only on destructive. Swapping presetOwned→presetOwned must NOT nag.
- Undo: preset apply + fit/fill + preview tweak each one-undo step. Convert case (timeline drag) also one-undo that restores flags + preset kind — requires snapshot capture of `TransformSnapshot` extended with presetOwned+preset.

---

## 3. Detailed design questions to answer before coding

1. **Cover scale math (§3.3):** sample 16 points along animation path (pan path is linear X or Y, zoom path is scale). For each sampled (x,y,scale), compute `requiredScale = max(canvasW / (imgW*scale), canvasH / (imgH*scale))` ??? Actually `sizeFraction` is height-fraction; need mapping from scale to coverage. Simpler: compute effective image size on canvas at scale, then required enlarge to cover worst-case translation. Need to read `TextOverlayRenderer` scaling to replicate exactly — preview vs export must use same math to avoid tears. Re-run when canvas aspect changes — listen where aspect is derived (timeline total? preview rect?).
2. **Square refuse (§3.3, acceptance #10):** `PAN_*` on item where `imgAspect ≈ canvasAspect` within epsilon → refuse with Toast "Image already fills canvas — no room to pan. Try Fill or a different crop." Must not silently apply two identical keys.
3. **Easing default:** `EASE_IN_OUT` circle glyph — both keys use it (first key's easing governs segment leaving it, second key's easing irrelevant until next segment). Set on both for consistency.
4. **Fit/Fill vs presetOwned:** Fit/Fill are NOT preset-owned; they write static values (or maybe clear preset? Decide: Fit/Fill on preset-owned item should update preset cover scale? Probably clear preset or recompute preset's cover after? Spec says Fit/Fill stand alone, no animation — so applying Fit while preset active should keep preset? But Fit changes scale that pan depends on. Simplest: Fit/Fill clears preset (since static vs animated conflict) or rewrites preset's cover scale. Document choice.)
5. **Timeline keyframe gesture interception:** locate where `moveKeyframeLocalTime` is called from `EditorTimelineView` / `LayerGestureController` for TextOverlayItem — trace both. Currently `TextOverlayItem.moveKeyframeLocalTime` moves every track at that time. Need to add `boolean wasPreset = anyMovedKey.presetOwned` then after move, if wasPreset → clear all.
6. **Preview gesture:** where does preview drag write `centerX/Y` + `addPropertyKeyframeAt`? That's `FaditorEditorActivity` touch on preview. Need to branch: if presetOwned active, don't call `addPropertyKeyframeAt` directly; instead update preset's stored region and call `rewritePresetKeys()`.

---

## 4. Execution order (checklist)

- [ ] 1. Claim `LANES.md` lane `SPEC_20260829_IMAGE_ANIM_PRESETS` ACTIVE with exact file list per §4 phases; `git status` clean check.
- [ ] 2. `Keyframe.java` — add `presetOwned`, copy, maybe equals/hash audit.
- [ ] 3. `KeyframeCodec.java` — read/write `p`.
- [ ] 4. `model/ImageAnimPreset.java` NEW — enum Kind + params + copy().
- [ ] 5. `TextOverlayItem.java` — field `imageAnimPreset` + `imageFadeInMs/OutMs` + `getAnimatedOpacityWithFade()` + `applyFit/Fill` + `applyPreset` + `reflowPresetOwnedKeys` + `clearPresetOwnership` + `updatePresetFromPreview` + `TransformSnapshot` extension.
- [ ] 6. `project/ProjectStorage.java` — TextOverlayItem keyframe loops: handle `p`; new `imageAnimPreset` + `imageFade*` keys; verify byte-identical for old files.
- [ ] 7. `layers/LayerRowRenderer.java` — amber colour branch in keyframe diamond/envelope draw; image fade handles (share geometry with audio); fade multiplier visual (opacity envelope already draws baseOpacity — ensure it multiplies for preview of fade).
- [ ] 8. `layers/LayerGestureController.java` — fade drag for image items delegates to TextOverlayItem fade setters (reuse AudioClip convention); timeline key drag conversion (clear presetOwned) with one undo.
- [ ] 9. **HOLD** — verify Phase 1+2 build green (`build.log` BUILD SUCCESSFUL newer than edit), `adb devices`, old-project identical rendering screenshot, Fit/Fill 4 screenshots, ZOOM_IN amber keys at ends, trim-follow before/after.
- [ ] 10. Wait for `LANES.md:CAPTION_LAYERS` IDLE before touching `FaditorEditorActivity.java`.
- [ ] 11. `FaditorEditorActivity.java` — Fit/Fill buttons, preset picker chips, replace warning dialog, preview tweak path (no convert), trim reflow wiring, undo snapshot extension.
- [ ] 12. Full acceptance (§5 1-12) — device screenshots, export frame compare, fades-compose mid-fade screenshot, square-refuse toast.
- [ ] 13. Lane release + pathspec commit.

---

## 5. Risks & mitigations

- **Three-way overlap** (LANES.md:129) — CAPTION_LAYERS and AUDIO_SYNC_TRUTH also touch `FaditorEditorActivity.java`. Phases 1+2 are disjoint, but phase 3 must wait. Mitigation: never edit that file until IDLE; post on LANES if not.
- **Two answers to one question** — fade handle geometry, envelope, and glyph colour must be single-source. Mitigation: share `LayerRowRenderer.FADE_W/H`, reuse `AudioClip` fade accessors pattern, single `familyOf` for glyph.
- **Written-never-read** — amber flag written but draw site not reading → invisible. Mitigation: grep draw sites before marking done; acceptance #3 checks old projects have NO amber.
- **Stale R.jar** — typecheck may report green with shrunk source count. Mitigation: compare `typecheck.sh` source count before/after; verify `build.log` mtime.
- **Working-tree hazard** — uncommitted work destroyed by periodic clean. Mitigation: `git add` each file immediately after write, before verification; commit with explicit pathspec `git commit -m "..." -- path...`.

---

## 6. Verification commands (no gradle per rule 6)

- Save; wait for watcher; `tail -n 20 build.log` + `stat build.log` mtime vs last edit.
- `adb devices` paste.
- Old project: open fixture, screenshot, `grep -c '"p":' project.json` should be 0.
- Fit/Fill: tall vs wide image × Fit/Fill on 16:9 canvas — 4 screenshots + measure `animatedSizeFraction`.
- `ZOOM_IN`: check `keyframe.Keyframe` count 2 per track, both `presetOwned`, `timeMs` 0 and duration, `easing EASE_IN_OUT`.
- Trim: drag out point, verify `presetOwned` keys moved to new duration endpoint.
- Preview tweak: change preview scale, verify still `presetOwned` true.
- Timeline drag: drag amber key, verify all `presetOwned` false + `preset.kind==NONE`, undo restores.
- No background peek: sample 16 positions, assert `max(canvasW/(imgW*scaleAt), ...)` ≤ current cover.
- Square refuse: square image on square canvas, apply PAN_* → Toast.
- Export: 10s export, frame at 5s vs preview at 5s side-by-side.
- Fades compose: preset opacity + fade handle mid-fade `finalOpacity == baseOpacity * fadeFactor`.

---

## 7. Next step

**Await user confirmation of this plan** (AGENTS.md Task Management step 2) before claiming lane ACTIVE and starting implementation. On go: claim lane, execute §4 in order, staging per working-tree hazard.

Open questions for JoyRaptor before coding:

1. Fit/Fill on an item with active preset — should it clear the preset, keep it (recomputing cover), or be refused with replace dialog? Draft assumes clear + toast, but needs ruling.
2. Canvas aspect source of truth for cover-scale — is it `Timeline` export width/height, `FaditorEditorActivity.getVideoContentRect()`, or `ExportManager` presentation size? Need to read current preview/export matching site (SPEC_20260825_PREVIEW_MATCHES_EXPORT).
3. Image dimensions availability in model layer for cover math — decode bounds synchronously or defer to preview layer?
