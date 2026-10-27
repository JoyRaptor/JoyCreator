# Symmetry — Muse implementation plan (2026-10-19)

Status: **PLAN ONLY. No source changed.** For cross-review against other agents before choosing an executor.
Source spec: [SYMMETRY_20261019.md](SYMMETRY_20261019.md).
Prior plan (reference, not superseded until audit picks): [SYMMETRY_IMPLEMENTATION_PLAN_20261019.md](SYMMETRY_IMPLEMENTATION_PLAN_20261019.md).
Owner answers: screenshot in request + text Q2–Q6, folded below as locked decisions.

---

## 0. Owner decisions — locked for this plan

| # | Question | Answer (owner) |
|---|---|---|
| Q1 | Radial centre for 3+ copies | **Middle of the guide line** (`C = (a+b)/2`). No separate centre handle. |
| Q2 | Copies meeting/crossing the mirror line | **Merge by default** (one stroke buffer, no seam double-darkening). **Overlap** is a **floating toggle on the widget**. Wet watercolour/oil always merge physically (one shared water field) even when Overlap is on — pill says so. |
| Q3 | Wet paint zoomed far out (whole-page copy area won't fit in memory) | **Safe-zone UX, not silent clip, not dialog-refuse:** dotted-line perimeter for max safe draw zone; **darken canvas outside zone 20% while live**; **drawing outside warns** ("This is the largest area your device can safely paint with this brush") and paints nothing outside. Only visible when it matters (wet brush + symmetry + zoomed out). This is a UI/engine feature for wet brushes. |
| Q4 | Per-family copy caps on Note 9 | **Yes, cap and tell.** First values (to be measured in Phase D): ink/pen ~30, pencil ~16, watercolour ~8, oil ~4–8. One notice per stroke when capped. |
| Q5 | Scatter/jitter + tilt per copy | **Identical/symmetric by default** with a floating toggle on the widget labelled **"Symmetry of jitter/tilt"**. Default ON = everything as symmetric as possible (tilt offset/mirrored as needed, scatters same). OFF = positions still mirror, but tilt angle and scatter do **not** try to be symmetric (each copy random/independent). |
| Q6 | Snapping | **45° also, soft snap** (0/45/90/135/180 within ~3°, haptic tick, escapable). |
| Q7 | Persistence | **App-wide like the Ruler** (chrome prefs, not in the drawing, never exports). |

Count semantics (from spec, unchanged): pill scrubs **1 = off, 2 = mirror, 3–30 = radial**; **Mirror chip** next to it turns radial into kaleidoscope (each radial copy also mirrored → `2N` lanes).

---

## 1. Ground truth — corrections to the source spec (verified in code this session)

These change where work lands, not what the owner sees.

1. **No "Helpers" menu.** Triangle-ruler icon is the **Guides** button (`JoyBrushActivity.kt:650`), opening `guidesPanel()` (`JoyBrushActivity.kt:1562`). Symmetry row goes there, after Ruler.
2. **Ruler is app-wide, not in the drawing.** `GuideSettings` is deliberately not in `JbDocument` (`GuideSettings.kt:7-9`); stored in `joybrush_chrome` prefs key `guides` (`JoyBrushActivity.kt:158,176,513,1538`, `guidesChanged():1534-1540`). Symmetry follows the same rule. **No `DOC_VERSION` bump for the guide.** (`StrokeCodec.VERSION` bump 4→5 in Phase B is the *stroke binary* format, not `document.json`.)
3. **Body-drag + two-finger rotate don't exist, even for Ruler.** `GuideOverlayView` handles two endpoint handles only (`GuideOverlayView.kt:106-159`, `handles():106-111`, `moved():148-159`, `onTouchEvent:116-146`, single-touch, miss = pass-through). Both interactions are **new work for the Symmetry line** (Phase C). Do not retrofit Ruler in this job.
4. **`GuideSnapper` only projects positions.** `map():73-109` promises "x,y and nothing else"; `candidates():146-184` offers directions for Grid/Iso/Persp only, `nearestTracer():209-228` measures Ruler/ellipse only. Symmetry must be a **stroke transform (fan-out), not a snap**. Keep it out of `GuideSettings.all()` so the snapper never sees it. Interaction order: **snap master sample first, then fan-out; lanes never re-snap** (re-snapping per lane would break symmetry).
5. **One live stroke today.** `JbCanvasView.kt:1064-1078` holds single `smoother/tracker/placer/strokePreset/strokeDabber/strokeWatercolour/scatterRng/strokeSeed/glBegan/lineContact`. Entry `startStroke():1658`, `beginStrokeNow():1840`, `feed():1875`, `feedOne():2072`, `paint():1979`, `finishStroke():1907`, `cancelStroke():1962`. Fan-out hooks here (Phase B), lane 0 = existing fields untouched.
6. **One pending line record per engine stroke.** `GlPaintEngine.kt:2128-2135` (`PendingLine(record)`), `commitPendingLine():2209-2260` fans out to one plane only. Multi-copy editable lines need a list API + shared `groupId` (Phase B).
7. **`groupId` + selection expansion already exist.** `StrokeRecord.kt:47-60` (`seq/groupId/locked`), `SelectionState.withGroups():40-46`, `SelectTool.kt:120-123`. Engine only mints a group for cross-plane strokes (`GlPaintEngine.kt:2221`). Symmetry mints one shared group per stroke.
8. **One engine stroke = one undo step.** `endStrokeInner():4837-4918`, single `undo.push(Step(...))` at `:4917`. Merge mode gets this for free; overlap mode uses N strokes + `undo.mergeNewest(depth-mark)` — canonical pattern is `JbCanvasView.drawStrokes():2021-2040`.
9. **Media is one `Stroke` with one spline/wet/dry object.** `MediaCanvas.kt:127-143` (`private class Stroke`), `:143 stroke:Stroke?`, `begin():168`, `add():211`, `end():297`, `build():404-432`. Real wet symmetry = multiple lane objects sharing **one** `MediaLayerEngine`/`MediaWindow` (Phase D), never N windows.
10. **Window is 1024 px.** `MediaWindowMath.kt:16-18` (`TILES=4, PX=1024`), `:21 MARGIN=128`, `:32 SPAN=512`, `placeFor/follow/holdWhole/within/holds`, split cap `MAX_WINDOW_PARTS=8` (`MediaCanvas.kt:679`). Safe-zone circle derives from these (Phase D).
11. **Stamp-only helpers.** `BrushDabber.kt:32` (one per stroke, 3 RNG draws/dab `:99-101`, tip angle `:124-130`), `Scatter.kt:88-107` (`expand/expandTo`, `SALT=0x5CA7L :74`, must receive the *same* lane RNG the dabber advanced), `DabPlacer` (`add():56`, `look` once/dab). Media never touches these.
12. **Unibrush is the stamp path in this tree** (`BrushDabber.look` path). The sample hook covers it; no separate Codex coordination needed unless the branch adds `TipAngleSource` — re-check at Phase B gate.
13. **`PenSample` channels.** `PenSample.kt:32` (`x,y,timeMs,pressure,tilt,azimuth,barrel,tool,predicted`); NaN = no sensor (`hasTilt/hasAzimuth/hasBarrel`). `MotionEventSamples.from()` never sets `barrel`/`predicted`. Transform touches `x/y` + optionally `azimuth/barrel/tip-angle`; `pressure/tilt-magnitude/timeMs/tool/predicted` pass through.

---

## 2. Architecture (one paragraph + flow)

`Guide.Symmetry(a,b,count,kaleido,overlap,symmetric)` describes the line and options. `SymmetryMath` (new, pure, tested) turns it into affine `Copy` transforms about `C=(a+b)/2` and applies one to a `PenSample` + scatter offsets + tip angles. `JbCanvasView` builds one **lane** per copy (own `StrokeSmoother`, `DirectionTracker`, `BrushDabber`, `DabPlacer`, `SplitMix`, `StrokeCapture`) and feeds every lane the transformed raw sample each input batch — "N brushes at once". Stamp/vector/eraser/smudge lanes paint into **one** engine stroke in merge mode (one undo, no seam darkening) or N strokes merged to one undo in overlap mode; per-lane `StrokeRecord`s share one `groupId`. Wet media builds N lane splines/strokes in `MediaCanvas` sharing **one window centred on C** so water is one physical field and runs downhill on every copy. A floating pill (`SymmetryPillView`) next to the line holds Count scrub + Mirror/Overlap/"Symmetry of jitter/tilt"/Move chips. Guide persists app-wide in `joybrush_chrome`.

Flow per input batch:

```
MotionEvent → MotionEventSamples.from → snapper.map (master only)
  → SymmetryMath.apply × N lanes → per-lane response.apply → per-lane smoother.add
  → per-lane placer.add → per-lane BrushDabber.look → Scatter.expand (lane RNG + offsetMap)
  → concat → engine.addDabs (merge) or per-lane begin/add/end + mergeNewest (overlap)
  → MediaCanvas.add per lane (wet, shared window)
```

---

## 3. Work breakdown (gates are hard: no next phase until gate passes)

### Phase M-A — Core model + maths (no wiring)

**A1. `Guide.Symmetry`** — `joybrush/core/.../core/guide/Guide.kt:17-55` (append variant):

```kotlin
data class Symmetry(
  val a: Pt,
  val b: Pt,
  val count: Int = 2,          // 1=off, 2=mirror, 3..30=radial
  val kaleido: Boolean = false, // Mirror chip (radial→kaleido), count>=3 only
  val overlap: Boolean = false, // Overlap toggle; wet always merges
  val symmetric: Boolean = true // "Symmetry of jitter/tilt" toggle
) : Guide() {
  val active: Boolean get() = count >= 2
}
```

**A2. `SymmetryMath`** — new `.../core/guide/SymmetryMath.kt` (pure, no Android):

- `data class Copy(xx,xy,yx,yy,tx,ty, rotation:Double, mirrored:Boolean, index:Int)` — affine + metadata.
- `fun center(g): Pt?` → `(a+b)/2`, null on degenerate.
- `fun lineAngle(g): Double?` → `atan2(b.y-a.y, b.x-a.x)`, null if `a==b` or non-finite.
- `fun copies(g): List<Copy>` — **non-identity copies only**; lane 0 is always the original:
  - `count<=1` → `emptyList()`; `count==2` → `[mirror across line]`; `count>=3` → rotations `R(2πk/N)` about C for `k=1..N-1`, plus if `kaleido` add `R_k∘M` for `k=0..N-1` (total lanes `1+(N-1)+N = 2N`).
  - Drop any generated transform numerically identity (epsilon `1e-9`) — prevents duplicate lane + seam darkening on degenerate input.
  - Guard: non-finite `a/b` or `a==b` → empty.
- `fun apply(s:PenSample, c:Copy, symmetric:Boolean): PenSample`:
  - `x/y` through affine; `timeMs/pressure/tool/predicted` unchanged; `tilt magnitude` unchanged.
  - if `symmetric`: `azimuth` through linear part (see y-down note below); `barrel` turned/mirrored with same rule as `LineTransform.twist`; NaN stays NaN.
  - else: `azimuth/barrel` unchanged.
  - Non-placeable (`!isPlaceable`) → return `s` unchanged.
- `fun offset(dx:Float, dy:Float, c:Copy): Pair<Float,Float>` — linear part only, for symmetric scatter.
- `fun angleMap(c:Copy, lineAngle:Double, symmetric:Boolean): ((Float)->Float)?`:
  - rotation → `{ it + c.rotation.toFloat() }`; mirror → `{ (2*lineAngle - it) }`; null when `!symmetric` or identity. Applied to `base+angleJitter` **before** `followDirection/tipRotation` (`BrushDabber.kt:124-130`) to avoid double-move.
- `fun snapAngle(rad:Double): Double?` — soft-snap helper: nearest of `{0,45,90,135,180}°` within `3°` (≈0.0524 rad), else null. Pure for tests + overlay.
- `fun safeRadiusDocPx(windowPx:Double=1024.0, margin:Double=128.0, reachPx:Double, pad:Double=4.0): Double` → `max(0, windowPx/2 - margin - reachPx - pad)`. Circle centred on C: for an isometry every copy of a point at distance `d` from C is at distance `d`.
- **y-down note:** app is y-down, media lab y-up (spec §2 table ⚠). Azimuth reflection must be `mirror: 2*lineAngle − azimuth` in *document* frame (y-down), then `MediaInput.sample` converts (`az+PI`, `MediaInput.kt:26-32`) as today. Pin with a test: vertical mirror flips azimuth sign around line axis; wash still runs DOWN on both sides because gravity stays in page space.

**A3. `GuideSettings`** — `GuideSettings.kt:11-123`:

- Add `val symmetry: Guide.Symmetry? = null` as **top-level field**, never in `tracers` (tracer validation `":47-53"` stays "only rulers and ellipses").
- `isEmpty` includes it; `all()` **unchanged** (snapper must never see symmetry).
- `refusalFor()`: finite `a/b`, `a!=b`, `count in 1..30`.
- `encode()` appends `sym ax ay bx by count kaleido overlap symmetric` (ints `0/1` for bools); `decode()` reads it, keeps skip-unreadable + `NONE` fallback (`:97-119`).

**A4. `GuideLines`** — `GuideLines.kt:62-82`: add exhaustive `is Guide.Symmetry` branch reusing `ruler()`/`clip()` (`:203-214`, `FAR=1e9`, `MAX_SEGMENTS=2000`). Draws infinite line through `a,b` clipped to view.

**A5. `GuideSnapper`** — `GuideSnapper.kt:146-228`: add `is Guide.Symmetry -> Unit` in `candidates()` + `nearestTracer()` only for exhaustiveness. Never passed in (see A3).

**A6. Tests** — `.../commonTest/.../guide/`:

- New `SymmetryMathTest`: mirror across vertical/horizontal/45°; involution (`M(M(p))==p`); azimuth reflection; radial 3/4/8 positions + rotation angles; kaleido-3 → 6 lanes no duplicates; count 1/0/31 edges; degenerate `a==b`/NaN → empty; `symmetric=false` keeps azimuth/barrel; `angleMap` matches `LineTransform.twist`; NaN channels stay NaN; `snapAngle` detents + 3° boundary; `safeRadiusDocPx` formula.
- `GuideSettingsTest`: encode/decode round-trip all options; `count 0/31` refused; symmetry absent from `all()`; doc-JSON unchanged (never part of document).
- `GuideLinesTest`: symmetry segment crosses view + clipped.
- `GuideSnapperTest`: symmetry guide produces no snap even if passed directly.
- Run: `./gradlew -p joybrush :core:jvmTest`.

**Gate A:** maths + persistence text + "never snaps" green. No UI, no engine wiring.

---

### Phase M-B — Stroke fan-out + per-copy records (stamp/vector/eraser/smudge)

Goal: mirror the *brush*, not pixels. Every lane re-runs response, smoothing, placement, scatter, tip angle on transformed samples.

**B1. Lane state** — `JbCanvasView.kt:1064-1078` (append, lane 0 = existing fields untouched):

```kotlin
private class SymmetryLane(
  val copy: SymmetryMath.Copy,
  val smoother: StrokeSmoother,
  val tracker: DirectionTracker,   // only for hard-coded Brush path; file path uses dabber's internal tracker
  val dabber: BrushDabber?,
  val placer: DabPlacer?,
  val scatterRng: SplitMix?,
  val capture: StrokeCapture?,
  val seed: Long,
)
@Volatile var symmetryGuide: Guide.Symmetry? = null  // set by activity via guidesChanged()
private var symmetryLanes: List<SymmetryLane> = emptyList()
private var symmetryLineContacts: List<LineContact> = emptyList() // parallel to lanes+lane0
```

**B2. Build lanes at pen-down** — `startStroke():1658-1826`, after existing placer setup (`:1755-1803`):

- If `symmetryGuide?.active != true` → existing path, zero overhead.
- Else compute `copies = SymmetryMath.copies(g)`; `lanes = count*(if kaleido 2 else 1)`; hard guard **60 lanes** (30×kaleido).
- Per copy: `StrokeSmoother(amount, view.zoom)` (`:1755` pattern, `amount` from `:1683`), `DirectionTracker`, file-brush `BrushDabber(p, laneSeed, angleMap)` + `DabPlacer(spacing=d.spacing, look=laneDabber::look)` (`:1793-1796` pattern), hard-coded `Brush` path `DabPlacer` with lane tracker (`:1764` pattern).
- `laneSeed = strokeSeed` when `g.symmetric` else `strokeSeed + index*0x9E3779B97F4A7C15uL` (independent randomness when toggle off).
- `scatterRng = SplitMix(laneSeed xor SCATTER_SALT)` (`SCATTER_SALT` `:3151` / `Scatter.SALT :74` — same number, two homes; read lane value, don't fork).
- `capture = StrokeCapture(newLineName("ln"), f.id, laneSeed, amount, view.zoom, ...)` per lane (`:1803` pattern).
- Families in this phase: **stamp, file brush, vector/Unibrush (same path), eraser, smudge**. Tuft (`strokeTuft :1077`), legacy watercolour POC (`strokeWatercolour :1071`), fill (`FillPenRaster`), media (`ENGINE_MEDIA :1537`) stay **single-lane with a pill sentence** ("not available for this brush yet" / media → Phase D). Never silently do nothing.

**B3. Feed lanes** — `feed():1875-1894`, `feedOne():2072-2101`:

- Lane 0 exactly as today (`MotionEventSamples.from :1883` → `snap?.map :1884-1894` → `response.apply :2092` → `smoother.add :2093` → `paint :1894`).
- For each extra lane: `t = SymmetryMath.apply(masterSnappedSample, lane.copy, g.symmetric)`; feed `lane.capture.add(t)` (raw-after-snap, pre-response — matches `StrokeCapture` contract `:27`) then `lane.smoother.add(response.apply(t))`.
- Refactor `paint(points)` → `paintLane(laneIndex, points)`; lane 0 keeps calling with index 0 so diff stays reviewable.

**B4. Per-lane dabs + scatter + tip angle:**

- `paintLane` uses lane placer (`placer.add(points)` `:1992`); hard-coded path keeps pressure-radius + lane tracker angle.
- Symmetric scatter: `Scatter.expandTo(placed, p.scatter, lane.scatterRng, ::dabInputsOf, offsetMap)` where `offsetMap = { dx,dy -> SymmetryMath.offset(dx,dy,lane.copy) }`. Preferred: add optional `offsetMap: ((Float,Float)->Pair<Float,Float>)? = null` to `Scatter.expand/expandTo` (`Scatter.kt:88-117`), default null → existing call sites untouched. Non-symmetric: no map + independent lane RNG.
- Fixed tip angle: extend `BrushDabber` ctor with `angleMap: ((Float)->Float)? = null`, apply to `base+angleJitter` **before** `followDirection/tipRotation` (`BrushDabber.kt:124-130`). `InkReplay` passes the same map at replay (B8).
- `DabPlacer` interpolates `pressure/tilt/azimuth/barrel/time` per dab (`DabPlacer:90,110`) — transformed azimuth flows through automatically.

**B5. Merge mode (default, Q2)** — `paintLane` + `finishStroke():1907`:

- First real dab of *any* lane calls `beginStrokeNow()` once (`glBegan` shared, `:1840-1871`).
- All lanes' dabs → same `engine.addDabs(...)` (`:2007`, `GlPaintEngine.addDabs():4628`, buckets by tile, any position).
- `finishStroke()` flushes every lane smoother (`smoother.finish()`), then **one** `engine.endStroke()` (`:1956`) → one `UndoLog.Step` (`:4917`). No seam darkening by construction (one buffer).
- `cancelStroke():1962` cancels all captures + `engine.cancelStroke()`.

**B6. Overlap mode (floating toggle, Q2):**

- When `g.overlap` and family is stamp/vector/eraser/smudge: one `engine.beginStroke/addDabs/endStroke` per lane; each lane `setPendingLine` before its `endStroke`; after last lane `engine.undo.mergeNewest(engine.undo.undoDepth - mark)` → one undo step (pattern `drawStrokes :2040`).
- Overlap **refused for wet media + legacy watercolour** (they share one physical field — spec Q2 note). Pill shows "always merges" sentence when those brushes selected. No silent fallback.

**B7. Per-lane line records** — `GlPaintEngine.kt:2129-2267`:

- `PendingLine` → hold `List<StrokeRecord>`; add `setPendingLine(records:List<StrokeRecord>, newBrushes)`; keep single-record overload delegating (compat).
- `commitPendingLine` loops records: one `LineChange` per lane, unique ids + ascending `seq` per plane (existing `:2228-2260` logic), **one shared non-null `groupId`** (`"g-"+UUID` when >1 lane, else existing `:2221` rule), all into same `PendingItems` + same undo step.
- `JbCanvasView` keeps `lineContacts: List<LineContact>` (`LineContact :661`); `finishStroke` builds per-lane `capture.finish()` (`StrokeCapture.finish():34`, `seq=0/groupId=null` at birth, engine assigns).

**B8. Replay fidelity (fixed tip angles + re-brush):**

- Add `StrokeRecord.symmetry: StrokeSymmetry? = null` (`StrokeRecord.kt:47-60`, last + default like `colorArgb/widthScale` precedent):
  `data class StrokeSymmetry(rotation:Float, mirrored:Boolean, symmetric:Boolean, axis:Float)`.
- `InkReplay.replay():106-140,222-231` passes stored `angleMap` to its `BrushDabber`; `replayAdmitted():151-167` same; `dabInputsOf :246` untouched.
- `StrokeCodec`: bump `VERSION 4→5`, add `FLAG_SYMMETRY = 1 shl 6`, write `u8 kind, f32 rotation/axis, u8 mirrored+symmetric` after captured block, before `sampleCount`. Old v1–v4 decode → `symmetry=null`. `OLDEST_VERSION` stays 1. `DOC_VERSION` stays 11. Archive round-trip test required.
- `LineTransform.apply` copies field via `copy` (already does).

**B9. Tests:**

- JVM `:core:jvmTest` + `:androidkit:test`: one stroke N lanes → N records, one shared `groupId`, one undo step; mirror of symmetric test brush → mirrored dab positions (paper aside); `InkReplay` fixed-angle round-trip preserves mirrored angles; `StrokeCodec` v4→v5 + old v4 reads; `mergeNewest` depth exact in overlap mode.
- Device smoke (Note 9): stamp/vector/eraser at N=2,8,30.

**Gate B:** N records + one undo + identical replay proven; Note 9 stamp/vector smoke. Media still single-lane.

---

### Phase M-C — Widget, line manipulation, persistence (app-wide, Q7)

**C1. Guides panel row** — `JoyBrushActivity.kt:1613-1633` (after Ruler row):

```kotlin
guides.copy(symmetry = Guide.Symmetry(screenOnPage(0.5f,0.15f), screenOnPage(0.5f,0.85f), count=2))
```

- Vertical mid-screen default (spec §1.1). Off = `symmetry=null`. Toggle row shows `Symmetry ✓` like Ruler.
- `guidesChanged():1534-1540` also sets `canvas.symmetryGuide = guides.symmetry`; prefs key `guides` reused (`:1538`); `guidesBtn.on` includes symmetry; `setAdjustingGuides` accepts symmetry.
- Row does **not** auto-enter adjust mode (owner: pick it, paint). Pill's Move chip enters it.

**C2. Line draw + manipulation** — `GuideOverlayView.kt:26-164`:

- Draw `settings.symmetry` via `GuideLines.visible` (separate from `all()`), guide violet + stronger underlay (`onDraw :62-85` two-pass pattern), plus safe-zone overlay when active (Phase D hook — paint/path cached, no per-frame alloc).
- Handles `sa/sb` in `handles()` (`:106-111` pattern, edge-pinned `:91-92`).
- Endpoint drag: existing `moved()` path.
- **Body drag (new):** DOWN within `24dp` of infinite line + no handle grabbed → translate both `a,b` by drag delta.
- **Two-finger rotate (new):** `ACTION_POINTER_DOWN` with body grab → record midpoint + start angle; `ACTION_MOVE` → rotate both endpoints about midpoint (length preserved).
- **Soft snap (Q6):** on endpoint-drag end + rotate end (and optionally live): `SymmetryMath.snapAngle` to nearest `0/45/90/135/180` within `3°`, keep length + anchor; `performHapticFeedback(CLOCK_TICK)`. No precedent in joybrush-* (only `LONG_PRESS` in `LayerColumnView:529`) — add tick here, don't spread.
- `adjusting==false` → no touch, as today (`:117`).

**C3. Floating pill** — new `joybrush-android/.../chrome/SymmetryPillView.kt`:

- Real Android view added to root after `GuideOverlayView` (`JoyBrushActivity.kt:462-465`), normal widgets + accessible. Reuse `pillButton()/pill()` (`:2650-2667`), `menuRow` (`:1087-1107`), `ChromeKit.label/dp`, scrub gesture from `SelectCountView` (`steps=dx/dp(12) :161`) / `WeightDrag` (`:234-253`).
- Contents: **Count** scrubbable number (h-drag ~14dp/step, clamp 1..30, commit on finger-up; 1=off → line dims, painting normal, pill stays); **Mirror** chip (kaleido, enabled count≥3); **Overlap** chip (Q2); **Symmetry** chip = "Symmetry of jitter/tilt" (Q5, default ON); **Move** chip → `setAdjustingGuides(true)`.
- Position: near line midpoint `C`, clamped on-screen; hidden when `symmetry==null`; shows cap/safe-zone sentences ("This brush keeps symmetry to N copies on this phone", "always merges", "not available for this brush").
- `onChanged(next,done)` mirrors Ruler `(next,done)` contract → `guidesChanged()`.

**C4. Persistence (Q7):** `GuideSettings.encode/decode` only; prefs `joybrush_chrome/guides` reused; no `JbDocument`, no `DOC_VERSION`, no export change. Reopen app → line/count/chips intact.

**C5. Tests:** `GuideSettingsTest` round-trip (Phase A) + `GuideLinesTest` segments + `snapAngle` unit; manual: vertical centre line on enable, scrub 1..30, chips toggle, 0/45/90 soft-snap + tick, reopen persists, Ruler untouched.

**Gate C:** UI acceptance on device + Ruler regression check.

---

### Phase M-D — Wet media + safe zone + caps (risky phase, Q3+Q4)

Promise: brush mirrored, water shared. Copies meeting at the line mix physically (Q2 wet note).

**D1. Media lanes in one window** — `MediaCanvas.kt:127-432`:

- `stroke:Stroke? :143` → `strokes:List<Stroke>` (keep single-lane fast path behind `size==1`).
- `begin():168` builds one `Stroke` per lane (same preset/seed/colour) via existing `build():404-432` (`MediaSpline` + `Wet/Dry/PasteStroke` per lane).
- `add()` takes per-lane `List<MediaSample>` (or called once per lane); `end():297` finishes every spline/wet/thin, runs last frame, **one** `e.endStroke()` → one media undo step (`begin/endMediaStroke`, `isMediaStep/recordWater`, `reportMediaHistory :1634`).
- One `MediaLayerEngine` field → wet copies share water (mix, drain, bead interaction across the line).

**D2. One shared window fixed on C** — `MediaCanvas.run():440-563`, `MediaWindow.kt:93-144`:

- At `begin`, `place()` at symmetry midpoint C, not pen (`:176`, `MediaWindowMath.placeFor`).
- No per-lane `follow/holdWhole`. Compute **union** of all lanes' `frameRect()` (`:532-552`); if union fits `SPAN` (`:32`) → run all in that window; else drop out-of-zone samples + warn once (D3), never silent clip. `MAX_WINDOW_PARTS=8 :679` still applies; respected safe zone should never hit it.

**D3. Safe zone (Q3) — dotted perimeter + 20% darken + warning:**

- Core radius `SymmetryMath.safeRadiusDocPx(PX=1024, MARGIN=128, reachPx, 4)` — `reachPx` from `MediaCanvas:668` (`referenceMm*PPM`, dry `×12` for flat pencil) / `frameRect` pad.
- Circle centred on C (isometry property). Recompute on guide move / brush change / zoom change.
- `GuideOverlayView` draws **only while wet-media brush + symmetry active**: dotted circle (`DashPathEffect`, cached paint/path, `EVEN_ODD` 20% dark outside). Zoomed-out → small circle: the visual "zoom in to paint watercolour with symmetry".
- `JbCanvasView.feedMedia():1585-1616`: first sample outside → refuse stroke with owner's sentence; later sample leaves → drop sample + warn once per stroke. No partial copies outside.
- Dry/stamp/vector never show the zone.

**D4. Per-family caps (Q4):**

- At `startMediaStroke():1533-1583`: `lanes = count*(if kaleido 2 else 1)`, cap by medium — **dry/pencil 16, wet/watercolour 8, paste/oil 8 (measure 4 vs 8 on Note 9, tune)**. Ink/pen/stamp/vector/eraser uncapped (hard 60-lane guard).
- On cap: use capped set for that stroke + one notice "This brush keeps symmetry to N copies on this phone." Record Note 9 timings per family at N=2/8/30 on all four boards (22-brush list) before tuning (E10).

**D5. Undo:** media one step per stroke already; add test at `reportMediaHistory`/document-stamp level (`DocumentStamp`, `staleDrawing`, `stopWater/resumeWater(false)` shared once — never per lane).

**D6. Tests:** JVM zone radius / union rect / cap selection / lane bookkeeping (fake harness); device: water runs DOWN both sides of vertical mirror, paper/grain unmirrored; frame-time + memory at N=2/8/30 + symmetry-off control.

**Gate D:** device evidence + measured cap table. Fallback: ship M-A..M-C, keep wet media single-lane + safe-zone refusal. **Never ship broken copies.**

---

### Phase M-E — Polish + hardening

- Kaleido ghost cursors (optional, behind flag if slow).
- 45° tick verified on device; escapability (slow drag defeats snap).
- Group edits: Select picks set (`withGroups`), recolour/re-brush/reweight/delete apply to all, one undo restores all; `duplicate` re-mints ids (`SelectTool:364-371` pattern).
- Boards: stamp/vector outside board clipped like any stroke; media on tile refused as today; Animation draws current frame.
- Perf: fan-out cost in hot path; if N=30 stamp too slow, cap live dabs/frame without changing final stroke.
- Docs: fix source spec E9 wording (app-wide, not in drawing) + link this plan.
- Commands: `./gradlew -p joybrush :core:jvmTest`, `./gradlew -p joybrush :androidkit:test`, `:androidkit:compileKotlin`; app via watcher/runbook; **never `adb shell monkey`** (phone rule — rotation lock).

---

## 4. Rules the implementation must obey

1. Mirror the brush, never pixels. Every lane re-runs response/smoothing/placement/speed/scatter/tip-angle on transformed samples.
2. Positions transform; page physics doesn't (gravity, grain, impasto light stay in page space).
3. `pressure/timeMs/tilt-magnitude/tool` unchanged; `azimuth/barrel` transform only when Symmetry-toggle ON; NaN stays NaN.
4. Symmetric ON: same seed, transformed azimuth/barrel, transformed scatter offsets, transformed fixed tip angle. OFF: positions still mirror; tilt/scatter independent per lane.
5. One undo: merge = one engine stroke; overlap = N strokes + `mergeNewest`; media = one media stroke.
6. No double-darkening at seam in merge (one buffer). Overlap darkens like real brushes — that's the point of the toggle.
7. Group: N records share one non-null `groupId`; never one record for the original only.
8. No silent failure: unsupported families, caps, safe-zone, wet-merge-everything all say a sentence.
9. Guides never export, never enter document. Symmetry follows Ruler (app-wide prefs).
10. No per-frame allocation in overlay or input fan-out; extra lanes only when `active`.

---

## 5. Files expected to change

**Core (`joybrush/core/...`):**
- `core/guide/Guide.kt` — add `Symmetry`
- `core/guide/SymmetryMath.kt` — **new** (copies/apply/offset/angleMap/snapAngle/safeRadius)
- `core/guide/GuideSettings.kt` — `symmetry` field + `refusalFor/encode/decode`
- `core/guide/GuideLines.kt` — `is Symmetry` branch
- `core/guide/GuideSnapper.kt` — exhaustive `is Symmetry -> Unit` only
- `core/brush/BrushDabber.kt` — `angleMap` param
- `core/brush/Scatter.kt` — `offsetMap` param
- `core/stroke/StrokeRecord.kt` — `StrokeSymmetry?` + `symmetry` field
- `core/stroke/StrokeCodec.kt` — `VERSION 5`, `FLAG_SYMMETRY`
- `core/vector/InkReplay.kt` — lane `angleMap` at replay
- `commonTest/.../guide/SymmetryMathTest.kt` — **new**; `GuideSettingsTest/GuideLinesTest/GuideSnapperTest/StrokeCodecTest` — extend

**Android kit (`joybrush/androidkit/...`):**
- `JbCanvasView.kt` — `symmetryGuide`, `SymmetryLane`, `startStroke/feed/feedOne/paintLane/scattered/finish/cancel`, `feedMedia` zone gate, `startMediaStroke` caps
- `gl/GlPaintEngine.kt` — `PendingLine(List)`, `setPendingLine(list)`, `commitPendingLine` loop + shared group
- `gl/media/MediaCanvas.kt` — `strokes:List`, per-lane begin/add/end, union-rect window
- `gl/media/MediaWindow.kt` — only if fixed-C placement needs API (prefer `place` directly)
- `src/test/...` — lane/undo/group integration tests

**App (`joybrush-android/...`):**
- `chrome/GuideOverlayView.kt` — symmetry line + handles + body-drag + two-finger rotate + soft snap + safe-zone (dotted + 20% darken)
- `chrome/SymmetryPillView.kt` — **new** (Count/Mirror/Overlap/Symmetry/Move)
- `JoyBrushActivity.kt` — Guides-panel Symmetry row, `guidesChanged`, prefs, pill host (`:462-465`)

**Docs:** this file; source spec E9 correction link; `tasks/todo.md` checklist only when work starts.

---

## 6. Verification

| Gate | Must be true | Evidence |
|---|---|---|
| A | Maths + persistence + never-snaps | `:core:jvmTest` green + wrong-control test |
| B | N lanes = N records, one undo, identical replay | `:core:jvmTest` + `:androidkit:test` + Note 9 stamp/vector smoke |
| C | Line/count/chips/45°-snap/haptic/app-wide persist; Ruler untouched | device screenshots + reopen test |
| D | Wet copies share water; zone blocks outside + 20% darken; caps measured | Note 9 timings/memory N=2/8/30, screenshots, cap table |
| E | No regressions (guides/Select/undo/save/boards) | existing suites + device |

---

## 7. Risks + mitigations

| Risk | Sev | Mitigation |
|---|---|---|
| `JbCanvasView` hot path | High | Lane 0 byte-identical when off; extra work ∝N only when active; measure N=30 stamp |
| Media one-window multi-lane refactor | High | Single-lane path behind `size==1`; gate D; fallback refuse-don't-clip |
| Tip-angle replay drift | Med | Store `StrokeSymmetry`; VERSION bump; round-trip test |
| Select/save confusion | Med | Shared `groupId`, existing `withGroups`; save handles lists |
| Overlap history depth | Med | `mergeNewest`; exact-depth test |
| Zone too tight zoomed-out | Med | Owner-approved visual; sentence explains |
| Cap surprise | Low | Per-stroke notice + documented table |
| Ruler breakage | Low | Separate field; `all()` unchanged |

---

## 8. Open questions for audit (not owner unless audit disagrees)

1. Safe-zone shape: dotted **circle** (mathematically correct for isometries) vs rounded-rect approximating the 1024 window? Plan picks circle per Q3 "perimeter".
2. Adjust entry via pill Move chip (painting never blocked) vs always-on handles?
3. Wet caps: start pencil 16 / wet 8 / oil 8 (measure 4 vs 8)? Audit picks measured oil value.
4. Overlap chip on with wet brush: force merge (plan) — acceptable?
5. Legacy watercolour POC / tuft / fill in Phase B: single-lane + sentence, or symmetry too?
6. 60-lane hard guard for kaleido-30 — memory/perf budget OK?

---

## 9. Acceptance (owner-visible)

- Guides → Symmetry → thin vertical centre line, painting already mirrored (count 2).
- Endpoints/body/two-finger move it; 0/45/90 soft-snaps tick.
- Pill scrubs 1..30 (1=off/normal, 2=mirror, 3..30 radial); Mirror/Overlap/"Symmetry of jitter/tilt" chips behave per Q2/Q5.
- Watercolour vertical mirror runs DOWN both sides; grain/paper unmirrored.
- Select picks all copies; recolour/re-brush/delete act on all; one undo.
- Zoomed-out wet shows dotted perimeter + 20% outside darken; painting outside warns + paints nothing.
- Remembered app-wide; never exports, never in drawing file.
