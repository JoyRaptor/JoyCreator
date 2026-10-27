# Symmetry — implementation plan (2026-10-19) — DeepSeek

Author: **DeepSeek**
Status: **PLAN ONLY. No source has been changed.** This document is for audit and cross-review before implementation.
Source spec: [SYMMETRY_20261019.md](SYMMETRY_20261019.md).
Owner answers received 2026-10-19 are folded in below.

---

## 0. Owner decisions (locked for this plan)

| # | Question | Answer |
|---|---|---|
| 1 | Radial centre | **Middle of the guide line.** |
| 2 | Copies crossing | **Merge by default**, with an **Overlap** toggle on the floating widget. |
| 3 | Wet paint zoomed out | **Dotted safe-zone perimeter**, darken outside by 20% while live, and refuse drawing outside with a warning (“This is the largest area your device can safely paint with this brush”). |
| 4 | Wet-brush caps | **Yes**; measure per family, tell the user when capped. First values: ink/pen 30, pencil 16, watercolour 8, oil 4–8. |
| 5 | Scatter/tilt symmetry | **Symmetric by default**; a floating **Symmetry** toggle turns off symmetric tilt/scatter (positions still mirror). |
| 6 | Snapping | Vertical/horizontal **and 45°**, soft snap with a haptic tick. |
| 7 | Persistence | **App-wide, like the Ruler** (not inside the drawing). |

---

## 1. Corrections to the source spec (verified in code)

These do not change the product; they change where the work lands.

1. **There is no “Helpers” menu.** The triangle-ruler icon is the **Guides** button
   (`JoyBrushActivity.kt:650`), opening `guidesPanel()` (`JoyBrushActivity.kt:1562`).
   Symmetry belongs there, next to Ruler.
2. **The Ruler is not saved inside drawings.** `GuideSettings` is deliberately not in
   `JbDocument` (`GuideSettings.kt:7-9`); it is stored in `joybrush_chrome` prefs keyed
   `guides` (`JoyBrushActivity.kt:158, 176, 513, 1538`). Symmetry follows the same rule.
   **No `DOC_VERSION` bump is needed for the guide.**
3. **Body-drag and two-finger rotate do not exist yet, even for the Ruler.**
   `GuideOverlayView` only handles two endpoint handles (`GuideOverlayView.kt:106-159`).
   These interactions are new work for the Symmetry line (Phase C). Extending them to the
   Ruler afterwards is optional.
4. **`GuideSnapper` only projects positions, it does not mirror.** Symmetry must therefore
   be a stroke *transform*, not a guide-snap. Keep `Guide.Symmetry` out of
   `GuideSettings.all()` so the snapper never sees it.
5. **The engine accepts one pending line record per stroke**
   (`GlPaintEngine.kt:2129-2135`). Per-copy editable lines need a small list API (Phase B).
6. **`groupId` already exists and already expands selection**
   (`StrokeRecord.kt:47-59`, `SelectionState.kt:39-46`, `SelectTool.kt:120-123`).
   The engine only mints one for a stroke crossing planes (`GlPaintEngine.kt:2220-2221`);
   symmetry must mint one shared group for all lane records.
7. **One engine stroke already gives one undo step** (`GlPaintEngine.kt:4917`).
   Merge mode therefore gets E3 for free.
8. **Unibrush is not in this tree.** It is the stamp path here; its branch adds
   `TipAngleSource` on the same `BrushDabber.look` path. The sample hook below covers both.
9. **Media strokes are one `stroke` with one spline/wet/dry object**
   (`MediaCanvas.kt:127-143, 404-432`). Real symmetry for wet paint needs multiple lane
   objects sharing **one** engine/window (Phase D).

---

## 2. Architecture in one paragraph

`Guide.Symmetry` describes the line and its options. `SymmetryMath` turns it into a list of
affine copy transforms and applies one to a `PenSample` (position + azimuth/barrel; tilt,
pressure, time unchanged). `JbCanvasView` builds one **lane** per copy, each with its own
smoother, dabber, placer and line capture, and feeds every lane the transformed raw sample.
All lanes paint into **one engine stroke** in merge mode (one undo, no seam darkening), or
one engine stroke per lane in overlap mode (merged into one undo). Per-lane line records
share a `groupId`, so Select/recolour/re-brush act on the whole set. Wet media uses multiple
lane objects in `MediaCanvas` sharing one window centred on the guide midpoint, so water is
one physical field and runs on every copy. A floating pill next to the line holds the count
scrubber and the Mirror/Overlap/Symmetry toggles.

---

## 3. Work breakdown

Each phase ends at an audit gate. No phase begins until the previous gate passes.

### Phase A — Core model and maths (no wiring)

**A1. `Guide.Symmetry`** — `joybrush/core/.../core/guide/Guide.kt`

```kotlin
data class Symmetry(
    val a: Pt,
    val b: Pt,
    val count: Int = 2,        // 1 = off, 2 = mirror, 3..30 = radial
    val kaleido: Boolean = false,
    val overlap: Boolean = false,
    val symmetric: Boolean = true,
) : Guide() {
    val active: Boolean get() = count >= 2
}
```

**A2. `SymmetryMath`** — new `joybrush/core/.../core/guide/SymmetryMath.kt`

- `data class Copy(xx, xy, yx, yy, tx, ty, rotation, mirrored, index)`.
- `fun lineAngle(g): Double?`.
- `fun copies(g): List<Copy>` returns the **non-identity** copies; the original lane is
  always lane 0.
  - `count <= 1` → empty.
  - `count == 2` → `[mirror across line]`.
  - `count >= 3` → rotations about the midpoint `C` for `k = 1..N-1`; if `kaleido`, add
    `R_k ∘ M` for `k = 0..N-1`. Total lanes = `1 + (N-1) + N = 2N`.
  - Skip any generated transform that is numerically the identity (prevents a duplicate
    lane/extra darkening on degenerate input).
- `fun apply(s: PenSample, c: Copy, symmetric: Boolean): PenSample`
  - position through the affine map;
  - if `symmetric`: `azimuth` through the linear part of the map; `barrel` turned/mirrored
    with the same rule as `LineTransform.twist` (`LineTransform.kt:108-114`);
  - if not `symmetric`: azimuth/barrel unchanged;
  - `tilt`, `pressure`, `timeMs`, `tool`, `predicted` unchanged; NaN stays NaN.
- `fun offset(dx, dy, c): Pair<Float, Float>` — linear part only, for symmetric scatter.
- `fun angleMap(c: Copy, lineAngle: Double, symmetric: Boolean): ((Float) -> Float)?`
  - rotation: `angle + c.rotation`;
  - mirror: `2*lineAngle - angle`;
  - `null` when not symmetric or the map is the identity.
- `fun safeRadiusDocPx(windowPx: Double, margin: Double, reachPx: Double): Double` —
  `max(0, windowPx/2 - margin - reachPx - 4)` for the wet safe zone.
- Guard every number: non-finite or `a == b` → empty copies / no map.

**A3. `GuideSettings`** — `GuideSettings.kt`

- Add `val symmetry: Guide.Symmetry? = null` as a **top-level field**, not in `tracers`.
- `isEmpty` includes it.
- `all()` deliberately unchanged (snapper must not see it).
- `refusalFor()` validates the line (finite, `a != b`) and `count in 1..30`.
- `encode()` appends e.g. `sym ax ay bx by count kaleido overlap symmetric`.
- `decode()` reads it; the existing fallback to `NONE` stays.
- Existing tracers validation remains “only rulers and ellipses”.

**A4. `GuideLines`** — `GuideLines.kt`

- Add the exhaustive `is Guide.Symmetry` branch, drawing the infinite line through `a,b`
  using the existing `ruler`/`clip` code (`GuideLines.kt:203-214`).

**A5. `GuideSnapper`** — `GuideSnapper.kt`

- Add `is Guide.Symmetry -> Unit` in `candidates()` and `nearestTracer()` only to keep the
  `when` exhaustive. It is never passed in because `all()` excludes it.

**A6. Tests** — `joybrush/core/src/commonTest/.../guide/`

- `SymmetryMathTest`:
  - mirror of `(x,y)` across vertical/horizontal/45° lines;
  - mirror is an involution; reflection flips azimuth;
  - radial count 3/4/8 lands on expected points and rotation angles;
  - kaleido count 3 gives 6 lanes, no duplicates;
  - count 1/2 edge cases; degenerate line gives nothing;
  - symmetric=false keeps azimuth/barrel;
  - barrel turn/mirror matches `LineTransform.twist`;
  - NaN channels stay NaN;
  - safe radius formula.
- `GuideSettingsTest`: encode/decode round trip with all options; count 0/31 refused;
  symmetry is not in `all()`; not part of the document.
- `GuideLinesTest`: symmetry line crosses the view and is clipped.
- `GuideSnapperTest`: a symmetry guide produces no snapping even if passed.
- Run: `./gradlew -p joybrush :core:jvmTest`.

**Audit gate A:** maths, persistence text, and “no snapping” proven by tests.

---

### Phase B — Stroke fan-out and per-copy records

**B1. Lane state** — `JbCanvasView.kt:1064-1078`

Add:

```kotlin
private class SymmetryLane(
    val copy: SymmetryMath.Copy,
    val smoother: StrokeSmoother,
    val tracker: DirectionTracker,
    val dabber: BrushDabber?,
    val placer: DabPlacer?,
    val scatterRng: SplitMix?,
    val capture: StrokeCapture?,
    val seed: Long,
)
private var symmetryGuide: Guide.Symmetry? = null   // set by the activity
private var lanes: List<SymmetryLane> = emptyList()
```

Lane 0 stays the existing single-stroke fields to keep the hot path unchanged. Lanes 1..N
are the copies.

**B2. Build lanes at pen-down** — `startStroke()` (`JbCanvasView.kt:1658-1826`)

After the existing placer setup, if `symmetryGuide?.active == true` and the engine is a
supported family:

- compute `copies = SymmetryMath.copies(g)`;
- for each copy create `StrokeSmoother(amount, view.zoom)` and `DirectionTracker`;
- for a file stamp brush create `BrushDabber(p, laneSeed, angleMap)` and
  `DabPlacer(spacing = d.spacing, look = laneDabber::look)`;
- for the hard-coded `Brush` path create a `DabPlacer` with the lane tracker;
- `laneSeed`: the same `strokeSeed` when `symmetric`, otherwise
  `strokeSeed + index * 0x9E3779B97F4A7C15uL` so each copy is independently random;
- `scatterRng`: `SplitMix(laneSeed xor SCATTER_SALT)`.

Families handled in this phase: **stamp, file brush, vector/Unibrush (they are the same
path), eraser, smudge**. Tuft, the legacy watercolour POC, fill and media stay single-lane
until their own phase; the pill says “not available for this brush” rather than silently
doing nothing.

**B3. Feed lanes** — `feed()` / `feedOne()` (`JbCanvasView.kt:1875-1895, 2072-2101`)

- Keep lane 0 exactly as today.
- For each extra lane: `val t = SymmetryMath.apply(placedSample, lane.copy, g.symmetric)`;
  feed `lane.capture` (raw, after guide snap) and `lane.smoother` (after response).
- Collect released samples per lane. `paint` becomes `paintLane(lane, points)`; lane 0 keeps
  `paint(points)`.

**B4. Per-lane dabs and scatter**

- `paintLane` places dabs with the lane placer.
- Symmetric scatter: call `Scatter.expandTo(placed, p.scatter, lane.scatterRng, ...)` and
  transform each offset by `SymmetryMath.offset(dx, dy, lane.copy)` before emitting.
  Preferred implementation: add an optional `offsetMap: ((Float, Float) -> Pair<Float,
  Float>)? = null` parameter to `Scatter.expand`/`expandTo` (`Scatter.kt:88-117`), leaving
  all existing call sites untouched.
- Non-symmetric: no offset map, each lane uses its own RNG.
- Fixed tip angle: extend `BrushDabber` with
  `angleMap: ((Float) -> Float)? = null` and apply it to `base + angleJitter`, **before**
  `followDirection`/`tipRotation` (`BrushDabber.kt:124-134`). This avoids double-moving an
  angle that already follows the transformed path.

**B5. One stroke, one undo (merge mode)** — `paintLane`

- The first real dab of any lane calls `beginStrokeNow()` once (`glBegan` is shared).
- Every lane’s dabs go to the same `engine.addDabs(...)` buffer.
- `finishStroke()` flushes every lane’s smoother, then one `engine.endStroke()`.
- This gives one `UndoLog.Step` (`GlPaintEngine.kt:4917`).

**B6. Overlap mode**

- When `g.overlap` and the family is a stamp/vector/eraser:
  - one `engine.beginStroke` / `addDabs` / `endStroke` per lane;
  - after the last lane, `engine.undo.mergeNewest(engine.undo.undoDepth - mark)` makes the
    set one undo step (same pattern as `drawStrokes`, `JbCanvasView.kt:2040`);
  - each lane sets its own pending record before its `endStroke`.
- Overlap is refused for wet media and legacy watercolour; the pill says they always merge
  physically. This matches the spec’s Q2 note.

**B7. Per-lane line records** — `GlPaintEngine.kt:2129-2267`

- Change `PendingLine` to hold `List<StrokeRecord>` and add
  `fun setPendingLine(records: List<StrokeRecord>, newBrushes)`.
  Keep the old single-record overload delegating to the list for compatibility.
- `commitPendingLine` loops the records:
  - one record per lane → one `LineChange` each;
  - unique ids and ascending `seq` per plane (existing logic);
  - **one shared non-null `groupId`** for all lane records when there is more than one;
  - all changes go into the same `PendingItems` and the same undo step.
- `JbCanvasView` keeps a `LineContact` per lane (`lineContacts: List<LineContact>`) instead
  of one. `cancelStroke` cancels all.

**B8. Replay fidelity for fixed tip angles**

- Add `StrokeRecord.symmetry: StrokeSymmetry? = null`
  (`StrokeRecord.kt:47-59`) holding `rotation: Float`, `mirrored: Boolean`,
  `symmetric: Boolean`, `axis: Float`.
- `InkReplay` passes the same `angleMap` to its `BrushDabber`
  (`InkReplay.kt:222-230`).
- `StrokeCodec`: bump `VERSION` 4 → 5, add flag bit 6 `FLAG_SYMMETRY`, write
  `u8 kind`, `f32 angle`, `u8 symmetric` after the captured block. Old v1–v4 reads keep
  `symmetry = null`. **`DOC_VERSION` stays 11**: this is the stroke binary format, not
  `document.json`. Confirm with an archive round-trip test.
- `LineTransform.apply` copies the field unchanged (it already uses `copy`).

**B9. Tests**

- `SymmetryMathTest` (already Phase A).
- `JbCanvasView`/engine integration (JVM): one stroke with N lanes produces N line records
  sharing one `groupId`, and one undo step.
- Mirror a symmetric test brush across a vertical line and compare dab positions (paper
  aside).
- `InkReplay` round trip with a fixed-angle brush preserves the mirrored dab angles.
- `StrokeCodec` v4 → v5 → read; old v4 still reads.
- Run: `:core:jvmTest` and `:androidkit:test`.

**Audit gate B:** proof that a mirrored stroke is N records, one undo, and replays
identically; device smoke test on the Note 9 for stamp/vector.

---

### Phase C — Widget, line manipulation, persistence

**C1. Guides panel row** — `JoyBrushActivity.kt:1613-1633`

Add a “Symmetry” row after “Ruler”:

```kotlin
guides.copy(symmetry = Guide.Symmetry(
    screenOnPage(0.5f, 0.15f),
    screenOnPage(0.5f, 0.85f),
    count = 2,
))
```

- Off: `symmetry = null`.
- `guidesChanged()` also sets `canvas.symmetryGuide = guides.symmetry`.
- `setAdjustingGuides` accepts symmetry; “Move guides…” includes it.
- The row does **not** auto-enter adjust mode (owner: pick it, paint).

**C2. Line drawing and manipulation** — `GuideOverlayView.kt`

- Draw `settings.symmetry` via `GuideLines.visible` (separate from `all()`), in the guide
  colour, with a slightly stronger underlay.
- Handles: `a` and `b` (`sa`, `sb`) in `handles()`.
- Endpoint drag: existing path (`moved`).
- Body drag: if DOWN is within `24dp` of the infinite line and no handle was grabbed,
  translate both `a` and `b` by the drag delta.
- Two-finger rotate: on `ACTION_POINTER_DOWN` with a symmetry body grab, record the
  midpoint and start angle; on `ACTION_MOVE` rotate both endpoints about the midpoint.
- Soft snap: when an endpoint drag or rotation ends (and optionally live), snap the line
  angle to the nearest of 0/45/90/135/180° within **3°**, keeping the line length and the
  anchor point. Call `performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)`.
- While `adjusting` is false the overlay takes no touch, exactly as today.

**C3. Floating pill** — new `joybrush-android/.../chrome/SymmetryPillView.kt`

A real Android view added to the root after `GuideOverlayView`
(`JoyBrushActivity.kt:462-465`), so it can use normal widgets and is accessible.

- Shows: `Count` number (scrubbable), `Mirror` chip (kaleido, count ≥ 3), `Overlap` chip,
  `Symmetry` chip (tilt/scatter), and a small `Move` chip that toggles
  `setAdjustingGuides(true)`.
- Count scrubbing: horizontal drag on the number, ~14dp per step, clamp 1..30; commits on
  finger-up. 1 = off (line dims, painting returns to normal, pill stays so it can be
  scrubbed back up).
- Positioned near the line midpoint, clamped inside the screen; hidden when there is no
  symmetry.
- `onChanged` mirrors the Ruler’s `(next, done)` contract and reuses `guidesChanged()`.

**C4. Persistence**

- `GuideSettings.encode/decode` is the only change; prefs key `guides` is reused.
- No `JbDocument` field, no `DOC_VERSION` bump, no export change.

**C5. Tests**

- `GuideSettingsTest` round trip (Phase A).
- `GuideLinesTest` symmetry segments.
- Manual: line appears vertical at screen centre; count pill scrubs 1..30; Mirror/Overlap/
  Symmetry chips toggle; soft snap at 0/45/90 with haptic; reopening the app keeps it.
- Instrumented logic where possible without a GPU: `SymmetryMath` snap helper.

**Audit gate C:** UI acceptance on device; no change to Ruler behaviour.

---

### Phase D — Wet media (watercolour, oil, pencils) and safe zone

This is the risky phase. The goal is to preserve the spec’s promise: **the brush is mirrored,
the physical water field is shared**.

**D1. Media lanes in one window** — `MediaCanvas.kt`

- Replace `private var stroke: Stroke?` with `private var strokes: List<Stroke>`.
- `begin(...)` builds one `Stroke` per lane with the same preset/seed/colour. Each lane has
  its own `MediaSpline` and `WetStroke`/`DryStroke`/`PasteStroke` through the existing
  `build()` (`MediaCanvas.kt:404-432`).
- `add(...)` takes `List<List<MediaSample>>` (one list per lane) or is called once per lane.
- `end()` finishes every lane’s spline/wet/thin, runs the last frame, calls
  `e.endStroke()` **once**.
- Water is one `MediaLayerEngine` field, so copies meeting at the line mix physically, as
  the spec requires.

**D2. One shared window, fixed on the symmetry centre** — `MediaCanvas.run()` / `MediaWindow`

- At `begin`, place the window at the symmetry midpoint `C`, not the pen
  (`MediaCanvas.kt:176`, `MediaWindow.kt:92-98`).
- Do not call `follow`/`holdWhole` per lane. Instead compute the union of every lane’s
  frame rect (`frameRect`, `MediaCanvas.kt:532-552`) and:
  - if the union fits `MediaWindowMath.SPAN`, run all lanes in that one window;
  - if it does not fit, drop the out-of-zone samples and warn once (D3), never silently
    clip.
- The existing per-frame split cap (`MAX_WINDOW_PARTS = 8`) still applies; with a fixed
  window it should not be reached when the safe zone is respected.

**D3. Safe zone (owner’s chosen UX)**

- Core helper `SymmetryZone` (or `SymmetryMath.safeRadiusDocPx`) using
  `MediaWindowMath.PX`, `MARGIN`, the brush reach and a 4px pad.
- The safe zone is a **circle centred on the guide midpoint**: for an isometry, every copy
  of a point at distance `d` from `C` is at distance `d`.
- `GuideOverlayView` draws, only while a wet media brush is selected and symmetry is active:
  - a dotted circle perimeter;
  - a 20% dark overlay outside the circle (single `Path` with `EVEN_ODD`, cached paint and
    `DashPathEffect`, no per-frame allocation).
- `JbCanvasView.feedMedia`:
  - if the first sample is outside the radius → refuse the stroke with the owner’s sentence;
  - if a later sample leaves it → drop that sample and show the warning once per stroke;
  - no partial copies are painted outside the zone.
- The circle is recomputed when the guide moves, the brush changes, or the zoom changes.
- Because the window is 1024 px, zoomed out the circle is small; this is exactly the
  “zoom in to paint watercolour with symmetry” behaviour the owner asked for, but visual
  rather than a dialog.

**D4. Per-family caps**

- At `startMediaStroke` (`JbCanvasView.kt:1533-1583`) compute the requested lane count and
  cap by medium:
  - dry/pencil: 16 lanes;
  - wet/watercolour: 8 lanes;
  - paste/oil: 8 lanes (measure 4 vs 8 on the Note 9 and tune).
- Kaleido doubles the count:
  `lanes = count * (if (kaleido) 2 else 1)`; cap applies to lanes.
- When capped, use the capped set for that stroke and show one notice:
  “This brush keeps symmetry to N copies on this phone.”
- No cap for ink/pen/stamp/vector/eraser (still bounded by 30 copies / 60 kaleido lanes;
  add a hard 60-lane guard).
- Measure and record Note 9 timings per family at N = 2, 8, 30 (E10) before tuning.

**D5. Undo**

- Media already commits one step per stroke (`MediaCanvas.end()`), so all lanes land in one
  undo step. Add a test at the `reportMediaHistory`/document-stamp level.

**D6. Tests**

- JVM: `SymmetryZone` radius; lane union rect; cap selection; `MediaCanvas` lane
  bookkeeping with a fake/engine-free harness if available.
- Device: Note 9, 22 brush list, watercolour/oil/pencil at N = 2, 8, 30, on all four
  boards; record frame time and memory. Negative control: the same stroke with symmetry off.
- Verify water runs down on both sides of a vertical mirror and the paper texture is not
  mirrored.

**Audit gate D:** device evidence and a measured cap table. If the shared-window refactor
cannot be made safe, ship Phases A–C and keep wet media single-lane with the safe-zone
refusal; do not ship broken copies.

---

### Phase E — Polish and hardening

- Kaleidoscope ghost cursors (optional, Phase 4 of the spec).
- 45° snap tick checked on device.
- Editable-line re-brush/recolour on a group: verify Select picks the set and one undo
  restores all.
- Boards: stamp/vector copies outside a board are clipped like any stroke; media is refused
  on tile boards as today.
- Performance: measure the fan-out cost in the input path; if N=30 stamp is too slow, cap
  live dabs per frame without changing the final stroke.
- Update the source spec’s E9 wording (app-wide, not in the drawing).

---

## 4. Detailed rules the implementation must obey

1. **Mirror the brush, not pixels.** Every lane re-runs response, smoothing, dab placement,
   speed, scatter and tip angle on transformed samples. Never transform finished pixels.
2. **Positions transform; page physics does not.** Paper grain, water gravity, impasto light
   stay in page space.
3. **Pressure, time, tilt magnitude unchanged.** Azimuth/barrel transform with the copy.
4. **Symmetric mode:** same seed, transformed azimuth/barrel, transformed scatter offsets,
   transformed fixed tip angle.
   **Non-symmetric mode:** positions still transform; tilt/scatter/fixed angle do not.
5. **One undo.** Merge mode = one engine stroke. Overlap = N strokes + `mergeNewest`.
   Media = one media stroke.
6. **No double-darkening at the seam in merge mode.** One stroke buffer does this.
7. **Group semantics:** all lane records share one non-null `groupId`; one line record per
   lane (never one record describing only the original).
8. **No silent failure.** Unsupported brush families, caps, safe-zone refusals and media
   limits all say a sentence.
9. **Guides never export and never enter the document.** Symmetry follows the Ruler.
10. **No per-frame allocation** in the overlay (paints, dash effect, path cached).

---

## 5. Files expected to change

**Core**
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/guide/Guide.kt`
- `.../guide/GuideSettings.kt`
- `.../guide/GuideLines.kt`
- `.../guide/GuideSnapper.kt`
- `.../guide/SymmetryMath.kt` (new)
- `.../guide/SymmetryZone.kt` (new, or inside SymmetryMath)
- `.../brush/BrushDabber.kt`
- `.../brush/Scatter.kt`
- `.../stroke/StrokeRecord.kt`
- `.../stroke/StrokeCodec.kt`
- `.../vector/InkReplay.kt`
- `joybrush/core/src/commonTest/.../guide/SymmetryMathTest.kt` (new)
- `.../guide/GuideSettingsTest.kt`
- `.../guide/GuideLinesTest.kt`
- `.../guide/GuideSnapperTest.kt`
- `.../stroke/StrokeCodecTest.kt`

**Android kit**
- `joybrush/androidkit/.../JbCanvasView.kt`
- `joybrush/androidkit/.../gl/GlPaintEngine.kt`
- `joybrush/androidkit/.../gl/media/MediaCanvas.kt`
- `joybrush/androidkit/.../gl/media/MediaWindow.kt` (only if the fixed-centre placement
  needs an API; prefer using `place` directly)
- `joybrush/androidkit/src/test/...` integration tests

**Android app**
- `joybrush-android/.../chrome/GuideOverlayView.kt`
- `joybrush-android/.../chrome/SymmetryPillView.kt` (new)
- `joybrush-android/.../JoyBrushActivity.kt`

**Docs**
- `tasks/joybrush/specs/SYMMETRY_20261019.md` (E9 correction and this plan linked)
- `tasks/todo.md` (implementation checklist, only when work starts)

---

## 6. Verification plan

| Gate | What must be true | Evidence |
|---|---|---|
| A | Maths and persistence correct; symmetry does not snap | `:core:jvmTest` green; new tests with a deliberately wrong control |
| B | N lanes = N records, one undo; replay identical | `:core:jvmTest`, `:androidkit:test`, device stamp/vector smoke |
| C | Line/count/chips work; snapping and haptics; remembered app-wide | device screenshots + reopen test |
| D | Wet copies share water; safe zone blocks outside; caps measured | Note 9 timings and memory at N = 2/8/30; screenshots |
| E | No regressions in Ruler/guides, Select, undo, save | existing suites + device |

Build/verification commands:
- `./gradlew -p joybrush :core:jvmTest`
- `./gradlew -p joybrush :androidkit:test`
- `./gradlew -p joybrush :androidkit:compileKotlin`
- app build/install via the project’s watcher/runbook; **never** `adb shell monkey`.

---

## 7. Risks and mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| `JbCanvasView` is the hottest input path | High | Lane 0 unchanged; extra lanes only when symmetry is active; per-frame work proportional to N and measured |
| Media refactor (one window, multiple lanes) | High | Keep single-lane path intact behind `strokes.size == 1`; gate D behind tests; fall back to refuse rather than clip |
| Fixed tip angle replay | Medium | Store `StrokeRecord.symmetry`; bump `StrokeCodec.VERSION`; round-trip test |
| Per-lane records confuse Select/save | Medium | Shared `groupId`, existing `withGroups`; save path already handles lists |
| Overlap mode history | Medium | `undo.mergeNewest`; test exact depth |
| Safe zone too restrictive when zoomed out | Medium | Owner approved; visual perimeter explains it |
| Caps surprise the user | Low | Notice on stroke start; cap table documented |
| New guide state breaks Ruler | Low | Symmetry is a separate field; `all()` unchanged |

---

## 8. Open questions for the audit (not for the owner unless the audit disagrees)

1. **Safe-zone shape.** A circle centred on the guide midpoint is the mathematically
   correct “all copies fit one 1024px window” region. The owner said “perimeter”; is a
   dotted circle acceptable, or should it be a rounded rectangle approximating the window?
2. **Adjust mode entry.** The plan uses the floating pill’s “Move” chip to make the line
   draggable, so painting is never blocked by the line. Is that acceptable vs. always-on
   handles?
3. **Wet caps.** Start at pencil 16, wet 8, oil 8 (measure 4 vs 8). The owner said oil
   “4–8”; the audit should pick the measured value.
4. **Overlap for wet media.** Plan says wet always merges (shared water). Confirm this is
   acceptable even when the Overlap chip is on.
5. **Line records for legacy watercolour/tuft.** Plan keeps them single-lane in Phase B;
   should they be symmetrical at all, or wait for the media phase?
6. **30 vs 60 lanes.** A hard guard of 60 lanes for kaleido+30 is proposed; confirm the
   memory/perf budget.

---

## 9. Acceptance criteria (owner-visible)

- Guides button → Symmetry → a thin vertical line appears through the screen centre and
  painting is already mirrored (count 2).
- Dragging the line/endpoints moves it; two fingers rotate it; 0/45/90 snaps feel soft and
  tick.
- The count pill scrubs 1..30; 1 turns painting back to normal; 2 mirrors; 3..30 radiates.
- Mirror/Overlap/Symmetry chips behave as decided.
- Watercolour on a vertical mirror runs down on both sides; grain/paper stay put.
- Select picks all copies; recolour/re-brush/delete apply to all; one undo.
- Zoomed far out with wet paint shows the dotted safe perimeter and the 20% darkening;
  painting outside warns and does nothing.
- The setting is remembered app-wide; it never exports and never enters the drawing file.
