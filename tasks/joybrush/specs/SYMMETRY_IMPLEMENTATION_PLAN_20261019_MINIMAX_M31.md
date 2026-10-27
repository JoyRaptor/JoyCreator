# Symmetry — implementation plan (MiniMax M3.1, 2026-10-19)

**Author label: MiniMax M3.1** — written by the `minimax/minimax-m3.1-flash` model, for cross-review against
the other models' plans. **Updated 2026-10-20 to the AS BUILT**: Phases A, B, C and D1 are on the branch and
green; D2, E and F are not. The **AS BUILT** section below says what exists, commit by commit; the rest of
the plan still reads as the plan, with the corrections the build forced into it marked where they land.

**Source spec:** [SYMMETRY_20261019.md](SYMMETRY_20261019.md) (the Lead's spec, 10-19).
**Owner's answers:** the screenshot of 10-19 plus the owner's typed follow-up, folded in as §0.
**Code read for this plan (all verified today, 2026-10-19, on `main` @ `b00b82667` + the 10-19 commits):**
`core/guide/{Guide,GuideSettings,GuideLines,GuideSnapper}.kt`, `core/brush/{BrushDabber,Scatter,Response,BrushPreset}.kt`,
`core/input/{PenSample,StrokeSmoother}.kt`, `core/paint/{DabPlacer,Dab}.kt`, `core/select/LineTransform.kt`,
`core/media/{MediaWindowMath,MediaInput}.kt`, `core/stroke/{StrokeRecord,StrokeCodec,StrokeCapture}.kt`,
`core/paint/UndoLog.kt`, `androidkit/JbCanvasView.kt`, `androidkit/gl/GlPaintEngine.kt`,
`androidkit/gl/media/{MediaCanvas,MediaWindow}.kt`, `joybrush-android/…/chrome/GuideOverlayView.kt`,
`joybrush-android/…/chrome/{ReferenceView,Popovers,ValueHud}.kt`, `joybrush-android/…/select/{SelectCountView,SelectBarView}.kt`,
`joybrush-android/…/JoyBrushActivity.kt`, and `tasks/joybrush/HANDOFF_20261019_LEAD.md`.
**Code read for the AS BUILT update (2026-10-20, on the branch `lead/symmetry-1019` in `C:\Temp\jb-symmetry`):**
`core/symmetry/{SymmetryLane,SymmetryLanes}.kt`, `core/guide/SymmetryMath.kt`, `core/brush/AngleFrame.kt`,
`core/input/DirectionTracker.kt`, `core/guide/GuideSettings.kt`, `androidkit/SymmetryCapNote.kt`,
`androidkit/gl/lines/LineCommit.kt`, `joybrush-android/…/chrome/SymmetryWidgetView.kt`.

**What this plan claims that the source spec does not:** (a) the exact per-channel transform table, including the
one rule everyone gets wrong (the tip angle under a mirror); (b) that a *whole-screen patch* is **not** needed for
wet symmetry, because the existing 1024 px media window can be **pinned on the symmetry centre** — the owner's
safe-zone rule is exactly the guard that makes that legal; (c) that **overlap mode cannot be live in the current
engine** and what the honest options are; (d) that **no binary-format change is needed for Phase B**, and exactly
what **Phase D2's** does need (a mirrored copy's tip angle cannot be rebuilt from samples alone).

**Corrections from the cross-review of 2026-10-19** (DeepSeek and Muse read this plan; both were right about
three things, and the plan below is fixed):
1. **The dab was transformed twice.** The first version transformed the sample into the copy's frame *and* then
   transformed the finished dab after the scatter. The second transform is wrong — the lane's brush already
   produced the copy's dabs. Fixed in §2.2, §2.8 and Phase B3: there is now **one** transform, on the sample,
   and the copy's map reaches an angle only through the brush's angle frame (§2.3).
2. **The safe radius was a constant; it cannot be.** The media window's origin is tile-aligned, so it cannot be
   centred on `C`. Fixed in §2.7: the radius is computed from the actual origin, and the placement rounds.
3. **A mis-citation.** "`Dab` is Kotlin-side only" was wrong: that remark is about `tilt` and `azimuth`. The
   image-tip limit stands, for a different reason (§F3).
4. **Two things the cross-review got wrong too, and this plan now has them right:** the scatter's **across
   component must be negated for a mirror copy** (or the copy's texture is handedness-flipped, not mirrored —
   §2.4), and the safe radius's *range* is (128, 384], not a fixed 384 (§2.7).
5. **Two product objections taken:** the always-grabbable line can intercept a stroke that starts near the axis
   (§8.8), and Phase F's overlap choice is a punt (§8.9). The group also now lands **before** the codec bump
   (**D1**, with the bump left in **D2**), so "select and recolour all the copies" arrives a phase earlier.
6. **The build's own corrections (2026-10-20).** The build taught this plan seven things, and every one of them
   is corrected in place below: the lanes live in **pure core**, not in the view (§3, §4 Phase B); the **barrel**
   is a **direction**, not a twist (§2.2); the **count cap** is `SymmetryMath.MAX_COUNT`, enforced in `copies()`
   and shared by `refusalFor()` and the decoder (§1.4, §4 Phase A); the **caps count COPIES** and the lanes are
   copies + 1 (§0 Q4, §4 Phase B); a **copy's tracker starts at the copy's own heading** (§2.3, §4 Phase B); the
   **kaleidoscope copy's own axis is `φ + θ/2`** (§2.2, §2.3); and the **safe radius is per brush family**, not a
   number (§2.7). The cross-review's corrections above stand — the build did not undo one of them.

---

## AS BUILT (2026-10-20) — the commits, and what each one delivered

The plan of record is this file; the build is the branch `lead/symmetry-1019` in `C:\Temp\jb-symmetry`. The
commits below are the whole of it, in order, and each one is a phase of this plan made real. Everything is
green: `:core:jvmTest` 2574, `:androidkit:test` 1061, `:joybrush-android:testDebugUnitTest` 317.

| commit | what it delivered | where it landed |
|---|---|---|
| `e7674b3bf` | the shared shape: `Guide.Symmetry`, `GuideSettings.symmetry`, the draw branch, `JbCanvasView.symmetryGuide` | `core/guide/*`, `androidkit/JbCanvasView.kt` |
| `9c82bd5f8` | **Phase A** — the copy maths (`SymmetryMath`) and the persistence, plus the review's fixes: the count cap, the barrel as a direction, and ONE rule for a usable symmetry | `core/guide/SymmetryMath.kt` (new), `core/guide/GuideSettings.kt` |
| `cd08fd4e6`, `1180447ff` | **Phase A** — the `AngleFrame` (the copy's geometry) and the brush's use of it; the scatter's across-component negation | `core/brush/{AngleFrame,BrushDabber,Scatter}.kt` |
| `67767bd7a` | **Phase A** — a copy's tracker starts at the copy's own heading | `core/input/DirectionTracker.kt`, `core/brush/*` |
| `d43fff47a` | **Phase C** — the owner-facing UI: the floating widget, the draggable line, the Guides row | `joybrush-android/…/chrome/*`, `JoyBrushActivity.kt` |
| `cff1f78a0` | **Phase B** — the stroke fan-out: one lane per copy, one engine stroke, one undo step | `core/symmetry/*` (new), `androidkit/JbCanvasView.kt` |
| `a19d73d2a` | the cap fix: the caps count **COPIES**, the note says the copy count, and it is said once per (family, cap) | `core/symmetry/SymmetryLanes.kt`, `androidkit/SymmetryCapNote.kt` |
| `651755a6b` | **Phase D1** — the copies are ONE group of editable lines, one undo | `core/symmetry/SymmetryLane.kt`, `gl/lines/LineCommit.kt`, `gl/GlPaintEngine.kt` |

**What is not built:** **D2** (the copy's angle frame in the record — it needs the codec bump), **E** (the wet
media) and **F** (overlap). §4 says what each of those still owes, and §8's open questions are still open.

---

## 0. The owner's answers (locked for this plan)

| # | Question | Answer (as given) | Consequence in this plan |
|---|---|---|---|
| 1 | Radial centre | **Middle of the line** | `C = (a+b)/2`, the line's midpoint, is the rotation centre. |
| 2 | Copies crossing the line | **Merge is default; overlap is a floating toggle on the widget** | Merge = one engine stroke, one buffer, no seam darkening. Overlap = see §2.6 — it is *not* free. |
| 3 | Watercolour/oil zoomed far out | **Dotted safe-zone perimeter; draws don't work outside it; drawing out of it gets a warning. Perhaps even darken the canvas outside the zone 20% while toggled live.** "This is a UI/engine feature for wet brushes." | §4 Phase E: a dotted circle + 20 % darkening outside it, shown only when a **media** brush is in hand; samples outside are refused with the owner's sentence. |
| 4 | Per-family caps | **Yes, cap and tell me.** ink/pen ~30, pencil ~16, watercolour ~8, oil ~4–8 | Caps per family, counted in **COPIES** (the owner's own unit), with a spoken notice when they bite: *"This brush keeps symmetry to N copies on this phone."* The lanes are copies + 1 — the original is always there. |
| 5 | Scatter/jitter identical per copy? | **Identical symmetric by default**, with a floating **"Symmetry of jitter/tilt"** toggle on the widget. Off ⇒ tilt angle and scatter are *not* symmetric (each copy random). | §2.4: one seed policy decides it. On = every lane shares the seed; off = every lane gets its own. |
| 6 | Snapping | **45° too, soft snap** so it can be used if wanted | §4 Phase C: soft snap to 0/45/90/135/180 within 3°, haptic tick. |
| 7 | Persistence | **App-wide, like the Ruler** | `GuideSettings` + the `guides` prefs key. **No document change, no `DOC_VERSION` bump, no export change.** |

Two readings I had to fix, stated so they can be overruled:

* **"3–30 = radial"** is read as *N spokes including the original* (count 3 ⇒ three lanes at 120°), matching the
  spec's E1 and the prior plans. Count 2 is the **mirror** (two lanes), count 1 is off. The **Mirror** chip
  (kaleidoscope) turns radial into radial+mirrored ⇒ **2N lanes**. ⚠ **The count is a lane count; the CAP is a
  copy count.** A guide at count 3 makes two copies and three lanes, so a cap of "8 copies" is nine lanes —
  and the number the app says out loud is the **copy** count, because that is the unit the owner asked in.
* **"Merge default, overlap a toggle"** — the Overlap chip lives on the same floating widget as the count, the
  Mirror chip and the Symmetry chip. One widget, four controls.

---

## 1. Corrections to the source spec (verified in code today)

These do not change the product. They change where the work lands, and two of them change the size of the job.

1. **There is no "Helpers" menu.** The triangle-ruler icon is the **Guides** button (`JoyBrushActivity.kt:650`),
   opening `guidesPanel()` (`:1562`). Symmetry is a row there, next to **Ruler** (`:1613`).
2. **The Ruler is not in the drawing.** `GuideSettings` is deliberately **not** in `JbDocument`
   (`DocModel.kt:284-301`); it lives in the `joybrush_chrome` prefs under the key `guides`
   (`JoyBrushActivity.kt:158, 176, 513, 1533-1540`). Symmetry follows the Ruler exactly ⇒ **no document field,
   no `DOC_VERSION` bump, nothing to export, nothing to migrate.**
3. **Adding a `Guide` subtype costs three exhaustive `when` branches**: `GuideLines.visible` (`GuideLines.kt:74`),
   `GuideSnapper.candidates` (`:149`), `GuideSnapper.nearestTracer` (`:213`). Every other `when` over `Guide` has
   an `else`.
4. **`GuideSettings.decode` is all-or-nothing**: `return if (s.refusalFor() == null) s else NONE`
   (`GuideSettings.kt:118`). One bad field kills the grid *and* the ruler. ⇒ the symmetry line must be validated
   **locally in `decode`** (skip the line) and only *named* by `refusalFor()` for the live path.
5. **`decode` skips any line with a non-numeric token** (`GuideSettings.kt:107`). Booleans must therefore be
   written as `0`/`1`, like `snap` already is.
6. **`GuideSnapper` only projects positions** — `s.copy(x = …, y = …)`, nothing else (`GuideSnapper.kt:193-200`).
   It cannot mirror. ⇒ symmetry is a **stroke transform**, and symmetry must be kept **out of `GuideSettings.all()`**
   so the snapper never sees it.
7. **`GuideOverlayView` has endpoint handles only** (`GuideOverlayView.kt:106-111, 113-146`): no body drag, no
   two-finger rotate, and it takes **no touch at all** unless `adjusting` is on. Both of the spec's line
   interactions are **new work**, and they are new for the Ruler too.
8. **The engine takes ONE pending line record per stroke** (`GlPaintEngine.kt:2129-2135`). N editable copies need
   a list API — **Phase D1, and it is done** (`setPendingLines`).
9. **`groupId` exists and Select already expands groups** (`StrokeRecord.kt:58`, `SelectionState.kt:39-44`,
   `SelectTool.kt:122`). The engine mints one today only for a stroke that crossed planes
   (`GlPaintEngine.kt:2221`).
10. **One engine stroke = exactly one undo step** — a single `undo.push` at `GlPaintEngine.kt:4917`, carrying the
    tiles *and* the line records *and* the slabs.
11. **`undo.mergeNewest(n)` already exists** (`UndoLog.kt:465-513`) and is used exactly this way by
    `JbCanvasView.drawStrokes` (`:2040`). That is the precedent for "N strokes, one undo step".
12. **Unibrush / Digital Pen is not in this tree** (it is Codex's lane). The hook this plan adds is at the
    **sample** level, so Codex's branch gets symmetry for free if it consumes transformed samples.
13. **The spec's "fixed tip angle / `tipRotation` / `TipAngleSource` do not exist.** The real mechanisms are
    `preset.tip.angle` (an absolute angle in **degrees**), `preset.tip.followDirection` (a boolean) and
    `preset.angleJitter` (`BrushDabber.kt:124-130`).
14. **A dab's `tilt` and `azimuth` are Kotlin-side only** — "it is NOT in the GPU instance buffer"
    (`Dab.kt:9-12` says this of `tilt`/`azimuth`, **not** of the whole dab: the position, radius and **angle** are
    uploaded). So the GPU has the dab's angle but no "mirror me" flag, and an **image tip cannot be mirrored** by
    rotating it — the tip's texture is sampled in the tip's own frame, and a reflection of a texture is not a
    rotation of it (§F3).
15. **The whole-screen patch is not approved.** The Lead recommends SKIP (10-19 handoff, "waiting on the owner"
    item 2: "~3× graphics memory, no help zoomed out"). The spec's E4 assumes it. **This plan does not need it**
    (§2.7) — that is the single biggest difference from the source spec.
16. **The media window follows the pen** (`MediaWindow.follow`, `MediaWindow.kt:101-104`); a move is a full flush
    + reload, 10–16 ms a frame on the Note 9. The media phase must **stop it following** (§4 Phase E).
17. `ResponseSpec.apply` (`Response.kt:28-35`) changes **pressure and tilt only** — never the position, never the
    azimuth. This matters: it is why one shared smoother is exact (§2.3).
18. `Scatter.SALT = 0x5CA7L` is duplicated as `JbCanvasView.SCATTER_SALT` (`Scatter.kt:74`,
    `JbCanvasView.kt:3151`). Do not add a third copy.

---

## 2. The design

### 2.1 The law

Every copy is the image of the original stroke under an **isometry** of the page: a rotation about `C` (radial)
or a reflection across the line `AB` (mirror / kaleidoscope). A copy is a **full brush**: its own response,
smoothing, dab placement, speed, scatter and tip — run on the pen's samples *transformed* into the copy's frame.
The page's own physics (paper tooth, water gravity, impasto light) stays in page space, so a mirrored wash runs
**down on both sides** and the grain is not flipped.

**Positions transform; channels transform only where the copy's frame says they do; pressure, time and the tilt
magnitude never change.**

### 2.2 The transform, per channel

Let `L` be the copy's linear part and `t` its translation, so `p' = L·p + t`.
`C` = the line's midpoint. `φ` = the line's angle. `θ` = the rotation angle of the copy.
**`φ'` = the axis THE COPY is reflected in** — for a plain mirror that is the guide's own line `φ`, and for a
**kaleidoscope copy it is `φ + θ/2`** (the note under the table says why; it is the fact the whole feature
rests on). Every `2φ` in this plan is a `2φ'`.

| channel | rotation copy (θ about C) | mirror copy (reflection about φ') | why |
|---|---|---|---|
| position `x,y` | `C + R(θ)(p − C)` | reflect about φ' | the copy is elsewhere on the page |
| dab **angle** | `A' = A + θ` | `A' = 2φ' − A` | the angle is a *direction*, and a reflection maps a direction at `A` to `2φ' − A` |
| **azimuth** (the pen's lean) | `a' = a + θ` | `a' = 2φ' − a` | same rule as the angle |
| **barrel** (the pen's twist) | `b' = b + θ` | `b' = 2φ' − b` | **the barrel is a DIRECTION here, not a twist** — `DirectionTracker.update()` hands the barrel back as it stands, so the barrel channel *is* the tip's direction, and a copy reflects it exactly as it reflects the lean |
| **tilt** (magnitude) | unchanged | unchanged | how far the pen leans is not a direction |
| **pressure**, **time** | unchanged | unchanged | the pen is the pen |
| **stroke direction** | follows from the transformed positions | follows | never computed separately |
| **scatter offsets** | the same random values; the copy's own frame already carries the rotation | the same random values **with the across component negated** | §2.4 — the one asymmetry |
| **seed** | same for every copy (default) | same (default) / own (toggle off) | §2.4 |

**The barrel is a direction, not a twist (the build's correction).** The first version of this plan carried the
barrel as a signed twist about the pen's own axis and negated it under a mirror. The build says otherwise:
`DirectionTracker.update()` returns the barrel **as it stands** (`if (s.hasBarrel && s.barrel.isFinite())
return s.barrel`), so as far as the brush is concerned the barrel channel *is* the tip's direction. A copy
therefore turns it by `θ` and reflects it by `2φ' − b` — the same rule as the lean. The cost is real: a brush
curve that treats the barrel as a twist sees the reflected value where it expected a negated one. That is the
lesser evil, because the tip's direction is what the eye sees.

**The axis is the COPY's own — say it loudly, because the whole feature rests on it.** A kaleidoscope copy is
the composition `R(θ) ∘ M(φ)` — a turn **after** a mirror — and that composition is *itself* a reflection,
about the axis **`φ + θ/2`**. The copy is therefore a reflection about `φ + θ/2`, **not** about the guide's
line. A copy reflected about the guide's line would be a whole extra half-turn out, and every lane of the
kaleidoscope with it — a picture that is wrong in a way that is hard to see and impossible to unsee. The built
code keeps this in one place: `AngleFrame` has **three** factories — `rotated(θ)`, `reflected(copyAxis)` and
`kaleidoscope(rotation, guideAxis)` — so a caller cannot hand a frame the guide's line where the copy's own
axis is wanted, and a reflection is never given a rotation as well (its own axis already carries it).

**The position is transformed exactly once**, on the sample, before the lane's brush sees it (§2.8). Everything
downstream — the dab's position, its azimuth, its barrel, its travel direction — is already the copy's, because
the lane's own brush produced it. The **only** place the copy's map reaches an angle is the brush's *angle
frame* (§2.3), and it is applied **once**. (Correction, 10-19 cross-review: the first version of this plan also
transformed the finished dab after the scatter, which applied the copy twice.)

### 2.3 The rule everyone gets wrong: the **tip angle** under a mirror

`BrushDabber.look` composes the dab's angle as
`angle = base` → `if (followDirection) angle += direction` → `angle += jitter` (`BrushDabber.kt:124-130`), where
`base = preset.tip.angle` (degrees), `direction` is the stroke direction from the dabber's own tracker, and
`jitter` is the signed random `preset.angleJitter` draw.

Applying the copy's map to the **composed** angle is exactly right — but only if the map is applied to the whole
angle, not to its parts. For a **rotation** the map is linear, so it distributes and
`base + dir' + jitter` (with `dir' = dir + θ`) already equals `A + θ` ✓ — no correction needed.
For a **mirror** the map is *affine* (`A' = 2φ' − A`) and does **not** distribute: a lane fed the mirrored
samples computes `base + (2φ' − dir) + jitter`, but the true mirrored tip is
`2φ' − (base + dir + jitter)`. The difference is `2·(base + jitter)` — the **handedness flip** the spec mentions.

So the lane's brush needs a small, explicit *angle frame* — built as `core/brush/AngleFrame.kt`:

| copy | `followDirection` | base becomes | jitter becomes |
|---|---|---|---|
| rotation θ | on | `base` (it is an offset **from the direction**, and the direction already rotated) | unchanged |
| rotation θ | off | `base + θ` (it is an **absolute** angle) | unchanged |
| mirror φ' | on | `−base` (handedness: a right-leaning tip mirrors to a left-leaning one) | `−jitter` |
| mirror φ' | off | `2φ' − base` (absolute) | `−jitter` |

This is a four-line change inside `BrushDabber.look` plus one optional constructor argument. It is the difference
between "the copy is the mirror image of the brush" and "the copy is the original brush pasted sideways".

**And the copy's tracker must START at the copy's own heading (the build's second correction).** The frame owns
the base and the jitter; it does **not** own the direction — the lane is fed the samples already transformed, so
the tracker's answer there *is* the copy's direction, and turning it again would apply the copy twice. But the
tracker holds a heading of its own until the pen has moved, and that heading was the **original's**: a copy's
first dabs pointed the wrong way, by `θ` for a rotation and by `2φ'` for a reflection. ⇒ the tracker is built
with the copy's own heading — `DirectionTracker(initialDirection = frame.transformedDefaultHeading)` — which for a
kaleidoscope copy is `2φ + θ`, the half-turn already in it. The seed is a *starting point*, not a lock: the
first real movement seeds the direction outright, exactly as it always did.

### 2.4 The owner's toggle: *identical* vs *independent*

Q5 asks whether the randomness is identical in every copy (symmetric) or different (not). The mechanism is
**the seed**, and nothing else:

* **Symmetric (default).** Every lane's `BrushDabber` is seeded with the **same** `strokeSeed`, and every lane's
  scatter RNG is `SplitMix(laneSeed xor SCATTER_SALT)` with the same `laneSeed`. Every lane therefore draws the
  *same* random sequence ⇒ identical size jitter, identical angle jitter, identical scatter offsets, identical
  `random` input to the brush's curves.
* **The one asymmetry: a mirror also flips the scatter's *across* component.** The scatter's offsets are built in
  the dab's own frame (`Scatter.kt:126-131`: the tangent is `(cos A, sin A)`, the perpendicular is its quarter
  turn), and a reflection maps the original's perpendicular onto the **negative** of the copy's. Rebuilding the
  offset in the copy's frame with the same random number therefore flips the texture's left/right — the copy
  looks *handedness-flipped*, not *mirrored*. ⇒ for a **mirror** copy the across draw must be negated
  (`across' = −across`), the same flip as the jitter's (§2.3). For a **rotation** no sign change is needed: the
  copy's frame already carries it. (This detail was in none of the three plans; it is what makes the copy's
  texture the *exact* mirror of the original's rather than a re-rolled one.)
* **Independent (toggle off).** Each lane gets `laneSeed = strokeSeed + index·0x9E3779B97F4A7C15` ⇒ its own size
  jitter, angle jitter and scatter. The **azimuth is also left untransformed** in this mode ("tilt angle … does
  not try to be symmetric"), so each copy registers the pen's own lean.

The seed policy is the *only* difference between the two modes. The toggle is called **"Symmetry of jitter/tilt"**
in the widget, default **on**, and it is a persistent setting like the others.

### 2.5 One brush per lane, or one brush for all?

The spec says "two brushes at the same time". The mathematically exact model is: **each lane runs the whole brush
(response → smoother → placement → dab → scatter) on the lane's transformed samples.** That is N smoothers,
N placers, N dabbers, N scatter streams.

Two facts make a cheaper variant legitimate, and they are worth stating because they decide the cost:

* The **response** changes pressure and tilt only (`Response.kt:28-35`) — never the position, never the azimuth.
* The **smoother** is a Gaussian over arc length with an angle-based corner test and a scale-invariant speed term
  (`StrokeSmoother.kt:12-71`). Under an isometry it is **equivariant**: smoothing the transformed stream gives the
  transformed smoothing.

⇒ the lanes' geometry is identical, so **one shared smoother is exact**, and the only per-lane difference is the
seed and the azimuth. That gives two candidate pipelines:

| | shared (fast) | per-lane (exact, "two brushes") |
|---|---|---|
| smoothers | 1 | N |
| placers / dabbers | 1 (symmetric mode) | N |
| azimuth seen by the brush's curves | the **original** lean ✗ | the **lane's** lean ✓ |
| replay of a mirrored copy | needs the copy's transform stored | needs the copy's transform stored (same) |
| CPU cost at N=30 | ~1× | ~N× |

**This plan takes the per-lane path** (N smoothers, N dabbers) because the owner's Q5 answer is about *how the
brush feels* in each copy, and because the azimuth reaching the brush's own curves is part of "mirror the brush, not
the pixels". The shared-smoother variant is documented in §7 as the **optimisation to take if the N=30 timing gate
fails** — it is a one-line change (feed the lanes from the shared released list) once the per-lane path is proven.
**And the per-lane path is what was built** (2026-10-20): every lane owns its own smoother, its own dabber and its
own scatter stream. The shared-smoother variant stays the documented fallback.

### 2.6 Merge vs overlap — what the engine can actually do

* **Merge (default).** All lanes' dabs go into **one** `engine.addDabs(...)` buffer and **one** `engine.endStroke()`
  ⇒ one `UndoLog.Step` (`GlPaintEngine.kt:4917`) and, because the stroke's coverage is a single channel, the
  copies that cross the line form one mark instead of a doubled one. **This is the whole of Q2's "merge".**
* **Overlap.** "Two real brushes overlapping" means the crossing must be committed **twice**. The engine's stroke
  buffer is **one coverage channel per tile** (`GlPaintEngine.kt:542`; the RGBA path exists only for smudge and
  watercolour, `:4577`), so two independent passes cannot be represented in one live stroke. Committing N strokes
  sequentially is possible (`undo.mergeNewest` — `UndoLog.kt:465`, used by `drawStrokes`, `JbCanvasView.kt:2040`)
  but **not live**: the engine has one stroke buffer, and `beginStroke` cancels the previous one
  (`GlPaintEngine.kt:4570`).

  **Finding: a live, faithful overlap is not available in this engine.** The honest options, for the owner to
  choose between in Phase F:
  1. **Sequential commits at pen-up** (N strokes from a buffered per-lane dab list, merged into one undo step).
     The live preview shows the merged look; the copies' own strokes appear at pen-up. *A visible pop.*
  2. **A second coverage channel** in the stamp pass (the engine's existing RGBA stroke-tile path), so lane 0 and
     lane 1 keep separate coverage and the commit compounds them. Live and exact — but 2 lanes only, 2× the
     stroke-buffer memory, and a shader change.
  3. **Keep merge only** and drop the Overlap chip.

  This plan builds **merge** first (Phase B) and treats overlap as **Phase F**, gated on the owner's choice. The
  chip is present in the widget from Phase C but says what it will do before it does it. **As built (2026-10-20):
  merge is DONE and overlap is NOT** — the chip is on the widget and the engine still cannot do what it says.

### 2.7 Wet paint: the safe zone replaces the whole-screen patch

The spec's E4 (and the Lead's 10-19 note) assume wet symmetry needs a **whole-screen patch** (~700 MB on the
Note 9) because the 1024 px media window follows one pen. This plan does not need it, because the copies of a
point are all at the **same distance from `C`**:

* a rotation about `C` preserves the distance to `C`;
* a reflection across the line `AB` preserves the distance to any point **on** that line — and `C` is on it.

⇒ **the set of points whose every copy stays inside a disc is exactly a disc centred on `C`.** The safe zone is a
**circle**, and it is not an approximation: it is the exact invariant set of the group.

So the media phase is:

* the window is **placed once, on `C`**, at the start of the stroke, and **does not follow the pen** during it
  (`MediaWindow.place` pins the window on the point it is given);
* ⚠ **but the window's origin is TILE-ALIGNED, so it cannot be centred on `C`.** `MediaWindowMath.placeFor` is
  `floor(doc / 256) − 2` (`MediaWindowMath.kt:24-25`): `C` lands **512–768 px** from the window's left edge and
  **256–512 px** from its right, never at 512. Centring it would need a sub-tile offset, and the media store is
  keyed by whole tiles.
* ⇒ **the safe radius is a function of where `C` falls in its 256-px tile**, not a constant:
  `R = min(C − left, right − C) − MARGIN − reach − pad`, which lies in **(128, 384] − reach** doc px
  (`PX = 1024`, `MARGIN = 128`, `Tiles.SIZE = 256`) — the worst case just past a tile boundary, the best mid-tile.
* ⇒ **place the window by rounding, not flooring** (as `placeForRect` already does, `MediaWindowMath.kt:36-37`):
  then `C` lands 384–640 px from the left edge and the worst case **doubles**, from 128 to **256** doc px.
* ⇒ **the reach is PER BRUSH FAMILY, and that is what decides the wet phase.** The reach is how far the brush
  paints beyond the pen: it is a property of the brush, not of the window. The safe radius per family is below.
* every lane's dabs land in that one window ⇒ **one water field** ⇒ the copies mix physically where they meet
  (§4.5 of the spec) and a mirrored wash runs down on both sides ✓;
* a sample (or a copy of it) outside `R` is **refused**, with the owner's sentence;
* the whole thing is bounded by a window that **already exists** (240 MB wet, per `LayerBudget`) ⇒ **no new
  memory class, no `DocModel` change, no atlas work, and no owner decision about the whole-screen patch.**

**The reach per family, and the safe radius it leaves** — the window placed by **flooring** (the engine's own
placement, `MediaWindowMath.placeFor`) and by **rounding** (`placeForRect`):

| brush | reach (doc px) | safe radius, window placed by FLOORING | by ROUNDING |
|---|---|---|---|
| media watercolour | 90 | (38, 294] | (166, 422] |
| media oil | 120 | (8, 264] | (136, 392] |
| media pencil | 264 | **negative — cannot be copied** | (0, 192] |
| palette knife | 440 | **negative** | negative |

⇒ **the watercolour and the oil can be copied**, with a modest zone — the owner zooms in, which is exactly the
UX they asked for (a dotted perimeter, the canvas darkened 20 % outside it, a spoken refusal outside it). **The
media pencils and the palette knife have NO safe radius at all in a 1024² window**: their reach is wider than the
window is, so every copy of a dab would leave it ⇒ they must be **refused in words** until the owner answers on
the bigger patch the Lead has recommended skipping. A brush that cannot be copied says so; it does not draw one
lonely stroke and call it symmetry.

The cost that remains: the wet pass runs over the window's wet rect instead of a 1024² neighbourhood of one pen,
so the per-frame GPU cost rises with the spread of the copies — bounded by the safe circle, and by the per-family
caps (Q4). This is the same trade the Lead is already making with the "growing patch" idea, at a fixed size.

### 2.8 The data flow

```
pen samples (document px)
  └─ GuideSnapper.map            position only; symmetry is NOT a snap
      └─ per lane k = 0..N-1
          ├─ transform(sample, copy_k)        position + azimuth + barrel   (SymmetryMath.apply)
          ├─ response.apply                  pressure + tilt only
          ├─ lane_k.smoother.add             lane k's own smoother
          ├─ lane_k.capture.add              (Phase D1) the lane's own raw samples
          └─ lane_k.placer.add → dabs        lane k's own dabber (lane seed) + lane k's angle frame
              └─ scatter(dabs, lane_k.rng, flipAcross = copy_k.mirrored)   lane k's own scatter
                  └─ engine.addDabs(all lanes)   ONE buffer (merge)  →  ONE endStroke  →  ONE undo step
```

**Where the lane lives: PURE CORE, and the view is WIRING.** The whole lane — the transform, the response, the
smoother, the placer, the dabber, the scatter and the capture — is `core/symmetry/SymmetryLane.kt`, and the set
of lanes is `core/symmetry/SymmetryLanes.kt`. `JbCanvasView` builds them, feeds them and hands their dabs to the
engine; once the symmetry is on, the view holds no brush state of its own. **Why it must be there:** the JVM
suite **cannot construct `JbCanvasView`** — there is no Android runtime on the test classpath — so a lane that
could only be built by the view could not be tested at all. The fan-out is therefore provable **off-device**:
the mirror proof, the scatter proof, the cap proof and the no-symmetry golden test are all core tests, not
phone tests. (This is the build's biggest structural correction to the first version of this plan, which put
the lanes in the view.)

Lane 0 is the original: its transform is the identity, so the **no-symmetry path is byte-for-byte the code of
today** if the fan-out is written as "lane 0 keeps the existing fields, lanes 1.. are new".

**There is no second transform of the dab.** The lane's samples are already in the copy's frame, so the dab's
position, azimuth, barrel and travel direction are the copy's — the lane's own brush produced them. The dab's
*angle* is likewise the lane's, which is exactly why the brush needs the **angle frame** (§2.3): the frame is
where the copy's map reaches an angle, and it reaches it **once**.
(Correction, 10-19 cross-review: the first version of this plan transformed the finished dab after the scatter
as well, which applied the copy a second time and would have put every copy in the wrong place.)

---

## 3. What changes where (file map) — as built

**Core (`joybrush/core`)**

| file | change | phase |
|---|---|---|
| `core/guide/Guide.kt` | `data class Symmetry(val a: Pt, val b: Pt, val count: Int = 2, val kaleido: Boolean = false, val overlap: Boolean = false, val symmetric: Boolean = true) : Guide()` — `count` 1 = off, 2 = mirror, 3..30 = radial spokes | **A ✓** |
| `core/guide/SymmetryMath.kt` **(new)** | `data class SymmetryCopy(xx, xy, yx, yy, tx, ty, rotation, mirrored, mirrorAxis)`; `copies(g)` — capped by `MAX_COUNT` —, `apply(sample, copy, symmetric)`, `centre(g)`, `lineAngle(g)`, `snapAngle(rad)` | **A ✓** |
| `core/guide/GuideSettings.kt` | `val symmetry: Guide.Symmetry? = null`; `isEmpty` includes it; **`all()` deliberately excludes it**; `refusalFor()` names it; `encode`/`decode` gain a `sym ax ay bx by count kaleido overlap symmetric` line (all numeric, booleans as 0/1); the decoder validates the symmetry **locally** | **A ✓** |
| `core/guide/GuideLines.kt` | `is Guide.Symmetry` branch: the infinite line through `a,b`, drawn with the Ruler's `clip` code | **A ✓** |
| `core/guide/GuideSnapper.kt` | `is Guide.Symmetry -> Unit` in `candidates()` and `-> Double.MAX_VALUE` in `nearestTracer()`, to keep the `when`s exhaustive | **A ✓** |
| `core/brush/AngleFrame.kt` **(new)** | the copy's geometry: `rotated(θ)`, `reflected(copyAxis)`, `kaleidoscope(rotation, guideAxis)`; `base()`, `jitter()`, `flipAcross`, `transformedDefaultHeading` | **A ✓** |
| `core/brush/BrushDabber.kt` | the angle frame (the §2.3 table), and the dabber's tracker seeded from it | **A ✓** |
| `core/brush/Scatter.kt` | the **across** component negated for a mirror copy; the RNG stream does not move | **A ✓** |
| `core/input/DirectionTracker.kt` | `initialDirection` — a copy's tracker starts at the copy's own heading | **A ✓** |
| `core/symmetry/SymmetryLane.kt` **(new)** | ONE lane: the whole brush, run again on the transformed samples. Its own smoother, its own dabber/placer, its own scatter stream, its own seed, its own recording | **B ✓** |
| `core/symmetry/SymmetryLanes.kt` **(new)** | the lane set, the family table, the **copy** caps, the cap sentence, and the refusal for a family that cannot be copied | **B ✓** |
| `core/stroke/StrokeRecord.kt` | **Phase D2 only**: `val symmetry: StrokeSymmetry? = null` (last, with a default) | D2 — not built |
| `core/stroke/StrokeCodec.kt` | **Phase D2 only**: `VERSION 4 → 5`, flag bit 6 | D2 — not built |
| `core/vector/InkReplay.kt` | **Phase D2 only**: build the dabber with the record's angle frame | D2 — not built |

**Android kit (`joybrush/androidkit`)**

| file | change | phase |
|---|---|---|
| `JbCanvasView.kt` | **wiring only**: reads `symmetryGuide` at pen-down, builds the core lanes, feeds them, hands their dabs to the engine, one `endStroke`. It holds no brush state of its own once the symmetry is on | **B ✓** |
| `SymmetryCapNote.kt` **(new)** | "said it once": the memory of the cap note, per (family, cap) | **B ✓** |
| `gl/lines/LineCommit.kt` **(new)** | the rules that make the copies ONE thing to Select: one `groupId` for the whole set, one `seq` per record | **D1 ✓** |
| `gl/GlPaintEngine.kt` | one record per lane, all sharing one group, all landing in one undo step | **D1 ✓** |
| `gl/media/MediaCanvas.kt` | **Phase E only**: `strokes: List<Stroke>`, one window pinned on `C`, the union-rect check, the safe-zone refusal | E — not built |
| `gl/media/MediaWindow.kt` | **Phase E only**: a "pinned" flag so `follow`/`holdWhole` cannot move the window during a symmetry stroke | E — not built |

**App (`joybrush-android`)**

| file | change | phase |
|---|---|---|
| `chrome/GuideOverlayView.kt` | draw the symmetry line; the line's drag, the body drag and the two-finger rotate; the 45° soft snap. The safe-zone circle and the 20 % darkening are **Phase E** | **C ✓** (safe zone: E) |
| `chrome/SymmetryWidgetView.kt` **(new)** | the floating widget: the count scrubber (1..30) and the Mirror / Overlap / Symmetry chips | **C ✓** |
| `JoyBrushActivity.kt` | the **Symmetry** row in `guidesPanel()`; `guidesChanged()` pushes `guides.symmetry` to the canvas; the widget's wiring; the refusal toasts | **C ✓** |

**Not in the tree yet:** `safeRadius(...)` (Phase E, in `SymmetryMath`), the record's `symmetry` field and the
codec bump (Phase D2), and the media window's pinned flag (Phase E).

---

## 4. The phases

Each phase ends at a gate. **No phase starts before the previous gate passes.** No phase begins with a phone
install; the device gates are the owner's, per `HANDOFF_20261019_LEAD.md`.

**Where the phases stand (2026-10-20):**

| phase | what it is | state |
|---|---|---|
| **A** | the core geometry — the copy maths, the angle frame, the persistence | **DONE** ✓ |
| **B** | the fan-out — one lane per copy, one engine stroke, one undo step | **DONE** ✓ |
| **C** | the UI — the widget, the line, the Guides row, the 45° snap | **DONE** ✓ |
| **D1** | the copies as ONE group of editable lines — **no binary format change** | **DONE** ✓ |
| **D2** | the copy's angle frame in the record — **this is the one that needs the codec bump** | **not started** |
| **E** | the wet media — the pinned window, the safe zone, the per-family reach | **in progress** |
| **F** | the overlap — the engine cannot do it live, so the owner chooses | **not started — the owner must choose** |

### Phase A — the maths and the persistence (pure, no wiring) — **DONE** ✓

**A1. `Guide.Symmetry`** — as in §3. `count` in `1..30`; `active = count >= 2`.

**A2. `SymmetryMath`** (new, core, no Android):

```kotlin
data class SymmetryCopy(
    val xx: Double, val xy: Double, val yx: Double, val yy: Double,  // the linear part
    val tx: Double, val ty: Double,                                   // the translation
    val rotation: Double, val mirrored: Boolean,
    val mirrorAxis: Double?,   // the axis THE COPY is reflected in — §2.2
)
```

* `centre(g) = Pt((a.x+b.x)/2, (a.y+b.y)/2)`.
* `copies(g): List<SymmetryCopy>` — the **non-identity** copies only; lane 0 is always the original.
  * `count <= 1` → empty.
  * `count == 2` → one mirror across `AB`.
  * `count >= 3` → the rotations by `2πk/N` about `C`, `k = 1..N-1`.
  * `kaleido` → also `R_k ∘ M` for `k = 0..N-1` ⇒ `2N` lanes in total.
  * **Drop any copy that is numerically the identity** (a degenerate line, a zero rotation) — a duplicate lane
    would double-darken and cost a frame.
  * Non-finite input, or `a == b` → **empty** (no copies, no crash).
  * ⚠ **the count is CAPPED here, in `copies()`, with `MAX_COUNT` (30)** — the ONE number the rule has. A guide
    that asks for a billion copies gets the thirty it is allowed and not a billion loops; a guide handed in by
    hand cannot hang the drawing. The settings **refuse** a count over the cap in words (§A3); the maths here
    simply caps it.
* `apply(sample, copy, symmetric): PenSample` — the §2.2 table. `tilt`, `pressure`, `timeMs`, `tool`, `predicted`
  unchanged; **NaN stays NaN**; `azimuth` transformed only when `symmetric`; the **barrel transformed as a
  DIRECTION** — a turn by `θ`, a reflection about `φ'` — and **not** as a twist (§2.2), because
  `DirectionTracker.update()` hands the barrel back as it stands and the barrel channel *is* the tip's direction.
* the copy's **angle frame** — `AngleFrame`, in `core/brush/AngleFrame.kt`, built by the **three** factories
  `rotated(θ)`, `reflected(copyAxis)` and `kaleidoscope(rotation, guideAxis)`: it says how the base and the
  jitter change, whether the scatter's across component is negated, and where the copy's tracker **starts**.
  **There is no `applyDab`**: the lane's samples are transformed once, so the dabs the lane's brush produces are
  already the copy's (position, azimuth, barrel, and the `travelX/travelY` pair the push brushes read —
  `Dab.kt:24-25`).
* `safeRadius(windowLeft, windowRight, centre, margin, reach)` — the radius from the **actual** window origin
  (§2.7), never a constant. **Phase E — not built.**

**A3. `GuideSettings`** — the new field, `isEmpty`, `refusalFor()` (a sentence naming the symmetry), and
`encode`/`decode`. The `sym` line is **validated inside `decode`** (skip it if the points are not finite, the
count is out of `1..MAX_COUNT`, or `a == b`) so a bad symmetry never takes the ruler and the grid with it.
`all()` is **not** changed. ⚠ **ONE rule for a usable symmetry**, and `refusalFor()` and the decoder both ask
**it** — the same number, `SymmetryMath.MAX_COUNT`, from the same place — so a rule that lived in only one of
them could not let a symmetry through the reader that the writer refuses. And the decoder validates
**locally**: `decode` ends with `return if (s.refusalFor() == null) s else NONE`, so a symmetry that fails its
own check is **skipped** — the grid, the perspective and the ruler all survive it.

**A4. `GuideLines` / `GuideSnapper`** — the three exhaustive branches.

**A5. Tests** — `joybrush/core/src/commonTest/.../guide/`:

* `SymmetryMathTest`
  * the mirror of a point across a vertical, a horizontal and a 45° line;
  * the mirror is an **involution** (`M(M(p)) == p`, to 1e-9);
  * radial 3/4/8 land on the expected points and angles about `C`;
  * kaleido 3 ⇒ 6 lanes, no duplicates, no identity copy;
  * count 1 ⇒ no copies; count 2 ⇒ exactly one mirror; a degenerate line ⇒ none;
  * `apply` keeps `pressure`/`timeMs`/`tilt` and keeps NaN as NaN;
  * `apply` with `symmetric = false` leaves the azimuth alone;
  * **the angle rule**: a mirror turns a tip at `A` into `2φ' − A`; a rotation into `A + θ`; the four cases of the
    §2.3 table (this is the test that catches the handedness bug);
  * **the kaleidoscope's own axis**: a kaleidoscope copy is reflected about `φ + θ/2` and **not** about the
    guide's line (this is the test that catches the half-turn);
  * **the count cap**: a guide that asks for more than `MAX_COUNT` gets `MAX_COUNT` copies — the same number the
    settings refuse;
  * the scatter-offset map is the linear part alone (no translation);
  * `safeRadius` at `reach = 0` and at a large reach. **Phase E — not built.**
* `GuideSettingsTest` — the round trip **including every symmetry option**; a `sym` line with a bad count is
  skipped and the rest of the settings survive — the grid, the perspective and the ruler with them; `symmetry`
  is **not** in `all()`; the settings still never reach the document (the existing
  `aGuideIsNeverPartOfTheDocument` test).
* `GuideLinesTest` — the symmetry line is drawn right across the view and is clipped to it.
* `GuideSnapperTest` — a symmetry guide never snaps, even if it is handed to the snapper directly.

**Gate A** — `:core:jvmTest` green, including a **negative control**: a deliberately wrong mirror (e.g. `A + θ`
instead of `2φ' − A`) must fail the new test. (House rule: a harness that cannot fail proves nothing —
`tasks/lessons.md`, "Compiling is not working".) **PASSED** — the suite is green (2574 tests).

### Phase B — the fan-out (pixels). The engine change. — **DONE** ✓

**B1. The lane — in PURE CORE, and the view is WIRING.** ⚠ **The build's biggest structural correction to this
plan: the lanes do NOT live in `JbCanvasView`.** They live in `core/symmetry/SymmetryLane.kt` (one lane) and
`core/symmetry/SymmetryLanes.kt` (the set, the families, the caps), and the view is the wiring that builds them,
feeds them and hands their dabs to the engine.

```kotlin
class SymmetryLane(
    val copy: SymmetryCopy?,      // null for the ORIGINAL lane
    val frame: AngleFrame,         // the copy's own geometry
    val seed: Long,
    val dabber: BrushDabber?,      // a brush FILE
    val placer: DabPlacer,         // the hard-coded brush's own placer
    val smoother: StrokeSmoother,  // the lane's own smoother
    val scatterRng: SplitMix?,     // the lane's own scatter stream
)
```

**Why it must be in core:** the JVM suite **cannot construct `JbCanvasView`** — there is no Android runtime on
the test classpath — so a lane that could only be built by the view could not be tested at all. In core, the
fan-out is provable **off-device**: the mirror proof, the scatter proof, the cap proof and the no-symmetry
golden test are all core tests, and none of them needs a phone.

**Lane 0 is the ORIGINAL, and it is the same core lane** — built with `copy = null` and `AngleFrame.IDENTITY`, so
it hands its samples straight on (an identity map would still take the sample through the map's arithmetic, and
a signed zero would come back as `+0.0f` — a change of value in a stroke that is not a copy at all). The code
path is identical whether there is one lane or thirty, and **the view keeps no brush state of its own once the
symmetry is on**.

**B2. Building the lanes** — `SymmetryLanes.build(guide, brush, family, seed, smoothing, screenPerDoc)`, called
from the view's `startStroke` (the guide is read **once at pen-down** and frozen for the whole stroke):

* `copies = SymmetryMath.copies(symmetry)`; if empty ⇒ no lanes, today's behaviour exactly.
* the **caps** (Q4) are applied here, and they are counted in **COPIES** — the owner's own unit:
  * stamp / vector / ink: **30 copies** (the owner's "ink/pen up to ~30");
  * media **dry** (pencils): **16 copies**; media **wet** (watercolour): **8**; media **paste** (oil): **8**
    (the owner said 4–8; measure and tune — §4 E6) — these three are **PROVISIONAL** until the media phase
    measures them on the phone, and they live in **one table** so that phase cannot leave a family behind. (The
    numbers are in the table for the media phase's sake; **until Phase E exists those families are refused in
    words** — see the last bullet.)
  * the eraser, smudge and push take the **hard guard**, and the guard is **60 LANES = 59 copies** — exactly what
    a kaleidoscope of thirty makes, so the two numbers cannot drift apart;
  * ⇒ **lanes = copies + 1**, the original always being there. The rule is in **one place**
    (`SymmetryLanes.laneCapFor`), so the copy cap and the lane limit cannot drift;
  * when a cap bites ⇒ **say so once per (family, cap)**: *"This brush keeps symmetry to N copies on this
    phone."* — the number is the **copy** count, and the memory of having said it lives on the **view's** side
    (`SymmetryCapNote`), because the core only reports **which** cap applied.
* the **seed policy** (Q5): `symmetric` ⇒ every lane's seed is `strokeSeed`; otherwise
  `strokeSeed + index·0x9E3779B97F4A7C15`. The scatter RNG is `SplitMix(laneSeed xor SCATTER_SALT)` — the
  existing salt (`Scatter.SALT`), not a new one.
* the **families**: stamp, file brush, vector/digital pen, eraser, smudge, push ⇒ lanes. **Tuft, the legacy
  watercolour engine, the fill pen and the media brushes are refused in words** — *"Symmetry is not available
  for this brush."* — because the house rule is *no silent failure*: a person who asks for symmetry and gets one
  stroke with no word has been told a lie by omission.

**B3. Feeding the lanes** — in `feedOne` (`:2072`) and `paint` (`:1979`):

* lane 0 is exactly today's code path;
* lane *k*: `t = SymmetryMath.apply(raw, lane.copy, symmetry.symmetric)` → `response.apply(t)` →
  `lane.smoother.add(t)` → `lane.placer.add(released)` → `Scatter.expand(placed, spec, lane.scatterRng, …)`;
* the dabs of every lane are concatenated into **one** list and go into **one** `engine.addDabs(...)`;
* **no transform after the scatter** — the dabs are already the copy's (§2.8);
* ⚠ **the lane's tracker starts at the copy's own heading** (§2.3):
  `DirectionTracker(initialDirection = frame.transformedDefaultHeading)`, or a copy's **first** dabs point the
  wrong way — by `θ` for a rotation and by `2φ'` for a reflection.

**Order note (this matters):** the scatter runs **after** the transform, so each copy's texture is built in **its
own frame** — the copy's scatter is the original's texture seen in the copy's frame, not the original's offsets
pasted sideways. One correction: for a **mirror** copy the across draw must also be **negated**, or the copy's
texture is handedness-flipped instead of mirrored (§2.4). A rotation needs no sign change.

**B4. The seam** — one stroke buffer ⇒ no double darkening where the copies meet (Q2's merge) ✓, and one
`endStroke` ⇒ one undo step ✓.

**B5. The line capture** — each lane records **its own** raw samples: the copy's own samples, before the
response and the smoother, so a later replay of that recording draws **this** copy and not the original. The
seed recorded is the **lane's** seed — the exact seed the lane's dabber drew with — so a replay is the stroke
that was drawn and not another one. The lane owns that seed, so the lane is where the recording is made: nobody
can hand a record a seed the brush never used. (Phase B drew pixels only; **Phase D1** turned the capture into
a set of editable lines — see below.)

**B6. `cancelStroke`** cancels every lane (no lane may keep a live smoother or a half-placed dab).

**B7. Tests** — in **core** (`joybrush/core/src/commonTest/.../symmetry/`), because the lanes are in core:

* a stroke with N lanes produces N× the dabs, in **one** engine stroke, and **one** undo step;
* the **mirror proof**: a fixed test brush, one stroke, symmetry on with a vertical line ⇒ the dab positions of
  lane 1 are the mirror of lane 0's (paper aside), and the **tip angles** are `2φ' − A` (the §2.3 table);
* the **scatter proof**: with `symmetric = true` the two lanes' scatter offsets are mirror images of each other;
  with `symmetric = false` they differ;
* the **cap proof**: a watercolour preset with count 30 asks for 8 **copies** (nine lanes) and says so — and the
  sentence says **copies**;
* the **first-dab proof**: a copy's first dabs point the copy's way (§2.3);
* the **no-symmetry proof**: with symmetry off, the dabs are bit-identical to today's (a golden test on a fixed
  sample list) — the regression guard for the hot path.

**Gate B** — the unit suites green, the mirror proof green, and a **device smoke** on the Note 9 for stamp,
vector and the eraser (the owner installs; the Lead's phone rules apply: `am start`, never `monkey`). **The
suites are green** (`:core:jvmTest` 2574, `:androidkit:test` 1061); **the device smoke is the owner's and is
still open.**

### Phase C — the widget, the line, the snap, the safe-zone art — **DONE** ✓ (the safe-zone art is Phase E)

**C1. The Guides panel row** — `JoyBrushActivity.guidesPanel()` (`:1562`): a **Symmetry** row after **Ruler**.

* on: `guides = guides.copy(symmetry = Guide.Symmetry(screenOnPage(0.5f, 0.15f), screenOnPage(0.5f, 0.85f), count = 2))`
  — a vertical line through the screen's middle, exactly as the owner asked ("a thin line appears through the
  middle of the screen, vertical, and painting works at once");
* off: `symmetry = null`;
* the row does **not** enter adjust mode (the owner: "pick it, the line appears, and you paint");
* `guidesChanged()` also does `canvas.symmetry = guides.symmetry`;
* `setAdjustingGuides`'s predicate gains `|| guides.symmetry != null` (`:1542`), and the panel's
  "Move guides…" row appears when only symmetry is on;
* "Clear guides" clears it too (`GuideSettings(snapEnabled = …)` already does).

**C2. The line and its manipulation** — `GuideOverlayView`:

* draw the symmetry line from `settings.symmetry` **separately from `all()`** (the snapper must not see it) with
  the Ruler's two-pass look (a dark underlay so it reads on any paper — `:70-84`);
* handles `sa` / `sb` for the two ends (the existing key scheme; the current index parse is fragile, so the
  symmetry keys get their own parse);
* **endpoint drag** — the existing path (`moved()`), which is absolute positioning;
* **body drag** — if the DOWN is within `24 dp` of the infinite line and no handle was grabbed, translate **both**
  ends by the drag delta (the current `moved()` is absolute, so the body needs its own delta path);
* **two-finger rotate** — on `ACTION_POINTER_DOWN` over the line, record the midpoint and the start angle; on
  `ACTION_MOVE`, rotate both ends about the midpoint. `ReferenceView` already does exactly this two-finger
  pattern (`chrome/ReferenceView.kt`, `startPair`/`move`) — copy it, do not invent one;
* **the line is grabbable whenever symmetry is on**, not only in adjust mode: the owner wants no hoops. The Ruler
  keeps its current adjust-mode-only behaviour (changing the Ruler is out of scope).
* **the soft 45° snap** — when the line's angle changes, find the nearest multiple of 45°; if it is within **3°**,
  set the angle to exactly that (keeping the length and the anchor: the grabbed end for an endpoint drag, the
  midpoint for a rotation) and `performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)`. "Soft" = the line is
  pulled to the angle but the finger may keep dragging past it — no detent, no re-snap fight. The tick fires
  **once per snap**, not on every move frame.
* a touch that misses the line and the handles returns `false`, so the pen still paints (the overlay is
  transparent and pass-through today — `:113-146`).

**C3. The floating widget** — new `chrome/SymmetryWidgetView`:

* a real Android `View` added to the root **after `guideOverlay`** and **before the chrome `overlays`**
  (`JoyBrushActivity.kt:451-499`), so it is above the drawing and the guide line and under the top bar;
* **the count scrubber** — a pill modelled on `select/SelectCountView.kt`: a horizontal drag, `12 dp` per step
  (`SelectCountView.STEP_DP`), clamped `1..30`, a tap cycles by one, and it announces itself as a `SeekBar` for
  accessibility (the existing pattern, `:208-226`);
* **the chips** — `Mirror` (kaleido; live only for count ≥ 3), `Overlap` (see §2.6 — present, with its state
  explained), `Symmetry` (the "jitter/tilt" toggle, default **on**). The chip look is `select/SelectBarView`'s
  `ChipView` (the owner's cyan accent, the word cut out) — copied, not reinvented;
* the widget is **anchored to the line's midpoint** in screen space and clamped inside the screen with
  `Popovers.place`'s idiom (`chrome/Popovers.kt:112-157`);
* it hides when there is no symmetry, and when the count is 1 the line dims but the widget stays (so the count
  can be scrubbed back up);
* every change goes back through the same `guidesChanged()` path, so the prefs, the overlay and the canvas never
  disagree.

**C4. The safe-zone art** (Q3) — in `GuideOverlayView.onDraw`, **only while a media brush is in hand and
symmetry is on**. **Phase E — not built:**

* a **dotted circle** at the safe radius `R` about `C` (a `DashPathEffect` on a circle path);
* a **20 % darkening outside it** — a `Path` (circle + full-screen rect) with `FillType.EVEN_ODD`, filled black
  at alpha `51`;
* **no per-frame allocation** (house rule from `tasks/lessons.md`): the `Paint`, the `DashPathEffect` and the
  `Path` are fields, rebuilt only when `R`, the view size or the guide changes;
* the darkening must not eat the guides' own colours — it goes under the line, over the canvas.

**C5. The persistence** — `GuideSettings.encode/decode` only. The prefs key `guides` is reused. **No
`JbDocument` field, no `DOC_VERSION` bump, no export change, and the setting is remembered app-wide like the
Ruler** (Q7).

**C6. Tests** — `GuideSettingsTest` (the round trip with every option), `GuideLinesTest`, plus a JVM harness for
the snap helper (the angle → the snapped angle, inside and outside the 3° window) and for the safe radius.

**Gate C** — the widget and the line work on the device; the Ruler is unchanged; the setting survives a restart.

### Phase D — editable lines: the copies as one group

The spec's E5: each copy is its own line record, sharing a `groupId`, so Select picks the set and
recolour/re-brush apply to all, in one undo.

**The build split this phase in two, and the split is a gift.** The **group** needed **no** binary format change
at all — the `groupId` has been in the recording format since v3 — so it landed and shipped. The **angle frame**,
the copy's own geometry in the record, does need the codec bump, and that is still to do.

| | what | binary format | state |
|---|---|---|---|
| **D1** | the copies are **ONE group** of editable lines | **unchanged** — `groupId` is already in v3 | **DONE** ✓ |
| **D2** | the copy's **angle frame** in the record | **bump** — `StrokeCodec` 4 → 5, flag bit 6 | **not started** |

### Phase D1 — the copies as ONE group of editable lines — **DONE** ✓

**D1.1. The capture** — each lane captures **its own transformed raw samples** (the same raw samples the live
lane fed the brush, so a replay reproduces the live stroke) and **its own seed** — the lane's seed, the exact
seed the lane's dabber drew with.

**D1.2. The commit** — `GlPaintEngine.setPendingLine(record, brushes)` → `setPendingLines(records:
List<StrokeRecord>, brushes)` (the single-record overload delegates, so nothing else changes).
`commitPendingLine` (`:2209`) then makes **one record per plane per source record**, all sharing **one**
`groupId` — the group is minted once for the whole set (`"g-" + UUID.randomUUID()`) and applied to every record,
with a distinct ascending `seq` per record (`NativeLines.nextSeq`, `:117-121`). Every `LineChange` lands in the
**same** `UndoLog.Step` as the tiles (`:4917-4918`). The rules live in `gl/lines/LineCommit.kt`, and they are
rules about ids, sequence numbers and one shared group — **none of which needs a GPU**, so they are testable on
their own.

**D1.3. No binary format change.** The `groupId` is **already** in the recording format (v3, `FLAG_GROUP`,
`StrokeCodec.kt:69-70`), so the group, Select and the one-undo behaviour needed **no** binary change at all.
⇒ the owner gets "select and recolour all the copies" without the codec bump, and the bump is isolated in D2
behind its own round-trip test. (Change from the first version of this plan, 10-19 cross-review: the group was
bundled with the codec. The build proved that the split is real.)

**D1.4. Tests** — N lanes ⇒ N records with one shared `groupId`; Select expands the group
(`SelectionState.kt:39-46`); a recolour applies to all; **one** undo restores all; a v4 record still reads.

**Gate D1** — the unit suites green and a device check that a mirrored stroke can be selected, recoloured and
undone in one step. **The suites are green**; the device check is the owner's.

### Phase D2 — the copy's angle frame in the record — **not started**

**D2.1. The replay** — `InkReplay` rebuilds each lane's brush from its record: the record's samples, its seed and
**its copy's angle frame**. ⇒ `StrokeRecord` needs one new field, `symmetry: StrokeSymmetry?` (rotation, mirror
axis, `symmetric` flag), last with a default; `StrokeCodec` **4 → 5** with flag bit 6, written after the captured
block, read only when the flag is set; v1–v4 records still read (they have no symmetry ⇒ `null` ⇒ the plain
replay). `DOC_VERSION` **stays 11** — this is the stroke binary format, not `document.json`.

*Why the record needs the transform (and why it is not optional):* a lane's tip angle is
`base' + dir' + jitter'`, and `base'`/`jitter'` are the **mirrored** ones (§2.3). A replay that re-ran the brush
on the mirrored samples with the *unmirrored* base would put the tip the wrong way round. The transform is
therefore part of the record. (It is a small field with a precedent — `groupId` in v3.)

⚠ **The frame that goes in the record is the copy's OWN** — a kaleidoscope copy's axis is `φ + θ/2`, **not** the
guide's line (§2.2). A record that carried the guide's line would replay the copy half a turn out.

**D2.2. Tests** — a v4 record still reads; a v5 record round-trips through the codec; a replayed mirrored line
has the mirrored tip angles; a replayed kaleidoscope copy is reflected about `φ + θ/2`.

**Gate D2** — the codec round trip green (v4 → v5 → read; a v4 still reads) and a replayed mirrored line has
the mirrored tip angles.

### Phase E — wet media (watercolour, oil, the media pencils) — **in progress** (the numbers say it is the hard one)

**E1. The lanes in `MediaCanvas`** — `private var stroke: Stroke?` becomes `strokes: List<Stroke>`
(`MediaCanvas.kt:127-143`). Each lane gets its own `MediaSpline` and its own `WetStroke`/`DryStroke`/`PasteStroke`
through the existing `build()` (`:404-432`), with the same preset, colour and seed. `add(samples)` takes one list
per lane. `end()` finishes every lane and calls `e.endStroke()` **once** ⇒ **one** media step
(`GlPaintEngine.endMediaStroke`, `:5660-5667`).

**E2. The window pinned on `C`** — at `begin`, place the window on `C` (not the pen) and **pin it**: the
`follow`/`holdWhole`/`followCentred` moves are suppressed for the stroke's life. The `run()` ladder
(`MediaCanvas.kt:440-495`) gets a pinned branch: compute the **union** of every lane's dab rect; if it is inside
the window (with the margin), run the frame as usual; if it is not, the samples outside are **refused** (E3) —
never silently clipped.

**E3. The safe zone** (Q3) — the radius from §2.7, recomputed when the guide moves, the brush changes or the zoom
changes. In `feedMedia` (`:1585-1617`):

* a sample whose distance from `C` exceeds `R` — **or whose copy would** — is refused;
* the first refusal of a stroke says the owner's sentence, once: *"This is the largest area your device can
  safely paint with this brush."*;
* the copies are **not** painted outside the zone: no partial copies, no clipped dabs;
* ⚠ **the reach is per brush family, and two families have NO safe radius at all** (§2.7): the **media pencils**
  (reach 264 doc px) and the **palette knife** (reach 440) are wider than the 1024² window, so every copy of a
  dab would leave it ⇒ they are **refused in words** until the owner answers on the bigger patch the Lead has
  recommended skipping. The **watercolour** (90) and the **oil** (120) can be copied, with a modest zone.

**E4. The caps** (Q4) — the media caps from B2 apply to the media lanes, counted in **copies**: pencil 16,
watercolour 8, oil 8 — **PROVISIONAL** until the phone measures them.

**E5. The overlap chip** — refused for wet media, with a sentence: wet copies share one water field and always
mix physically (the spec's own note, §5 Q2).

**E6. The measurement** — on the Note 9, the 22-brush list, all four boards, at N = 2, 8 and 30, with the
same-hour baseline rule (`HANDOFF_20261019_LEAD.md`: single runs vary ±20 %; compare against a same-hour main
baseline) and the Lead's rule that a media change reaches main only after the FULL list runs on the phone in the
same hour as a main baseline, with the Lead looking at the screenshots.

**E7. Tests** — JVM: the safe radius, the union rect, the cap table, the lane bookkeeping. Device: water runs
down on **both** sides of a vertical mirror; the paper grain is **not** mirrored; the copies mix where they meet.

**Gate E** — the device evidence and a measured cap table. **If the pinned window cannot be made safe, ship
A–D1 (done) and keep the wet brushes refused in words with the safe-zone refusal** — do not ship broken copies.

### Phase F — overlap, the hard parts, and hardening — **not started; the owner must choose**

**F1. Overlap — NOT BUILT, and the reason is the engine, not the schedule.** The engine's stroke buffer is
**one coverage channel per tile** (`GlPaintEngine.kt:542`; the RGBA path exists only for smudge and watercolour,
`:4577`), so two independent passes cannot be represented in one live stroke, and `beginStrokeBuffer` calls
`cancelStroke()` — there is only ever one live stroke. ⇒ "the copies behave as two separate brushes where they
cross" **cannot be live**. The three options of §2.6 are still on the table and the **owner picks**: N sequential
strokes at pen-up (a visible pop), a second coverage channel (2× the stroke-buffer memory, two lanes only), or
no chip. The plan's recommendation: option 1 (sequential commits at pen-up, one undo step) **only if** the owner
accepts the pop; otherwise drop the chip. Option 2 is a separate, gated piece of engine work with a real memory
cost. **What the owner must not be shown is a chip that does not do what it says.**

**F2. The families left out** — tuft, the legacy watercolour engine and the fill pen, each with its own lane
story (the tuft lays footprints, not dabs; the fill pen is a closed path). Until then the app says
*"Symmetry is not available for this brush."* — **as built**, the refusal is already in the core
(`SymmetryLanes.refusalFor`).

**F3. The hard parts** — the **image tip**. A procedural tip (superellipse + aspect + corner) mirrors exactly by
the angle rule. An **image** tip cannot: the tip's texture is sampled in the tip's own frame, and a reflection of
a texture is not a rotation of it — the GPU has the dab's angle but no "mirror me" flag (`Dab.kt:9-12` makes
*tilt* and *azimuth* Kotlin-side only; the position, radius and angle are uploaded). Either the shader grows a
mirrored-sampling flag, or the app says so for image-tip brushes. **This plan does not pretend the image tip
mirrors.**

**F4. Hardening** — the input-path cost at N = 30 (the shared-smoother optimisation of §2.5 if the gate fails);
the per-frame allocation check in the overlay; the guides' regression sweep (the Ruler, the grid, the ellipse);
the `unbrushed` families' notice.

---

## 5. Tests, in one place

| layer | what | where |
|---|---|---|
| pure maths | the mirror involution, the radial angles, the angle rule (§2.3), the NaN rules, the count cap; the safe radius is **Phase E** | `core/commonTest/…/guide/SymmetryMathTest.kt` |
| persistence | the round trip with every option; a bad `sym` line is skipped and the rest survives; symmetry is not in `all()`; symmetry never reaches the document | `core/commonTest/…/guide/GuideSettingsTest.kt` |
| drawing | the symmetry line is drawn across the view and clipped | `core/commonTest/…/guide/GuideLinesTest.kt` |
| snapping | symmetry never snaps | `core/commonTest/…/guide/GuideSnapperTest.kt` |
| engine | N lanes ⇒ N× dabs, one stroke, **one** undo step; the mirror proof on dab positions **and tip angles**; the scatter proof; the cap proof; the no-symmetry golden test; the first-dab proof | **`core/commonTest/…/symmetry/`** — the lanes are in core, so the JVM suite proves them without a phone |
| the angle frame | the four cases of the §2.3 table; the kaleidoscope's own axis `φ + θ/2`; the tracker's starting heading | `core/commonTest/…/brush/` |
| lines | N records, one `groupId`, Select expands it, one undo; the codec v4→v5→read and a replay's mirrored tip angles are **Phase D2** | `core/commonTest/…/stroke/`, `androidkit/src/test/…` |
| media | the safe radius, the union rect, the caps, the lane bookkeeping | `core/commonTest/…/media/`, `androidkit/src/test/…` |
| device | stamp/vector/eraser smoke; the widget; the 45° snap and its tick; the safe zone's darkening and the warning; water on both sides; the caps' notice; the app-wide persistence across a restart | the Note 9, the owner's gates |

**Every new harness needs a negative control** (a deliberately wrong implementation that the harness must catch) —
`tasks/lessons.md`, "Compiling is not working".

## 6. Build and verification commands

The exact commands the Lead's scripts use (`tasks/joybrush/lead-scripts/full-then-apk.sh`):

```
./gradlew -p joybrush :core:jvmTest :androidkit:test --continue     # the engine suites
./gradlew :joybrush-android:testDebugUnitTest --continue             # the app suite
./gradlew -p joybrush :androidkit:compileKotlin                      # the fast compile check
```

(`joybrush` is a **standalone** Gradle build with the modules `:core` and `:androidkit`; the app lives in the
root build as `:joybrush-android`.) The Lead's wrapper adds the machine-wide Gradle lock and the free-RAM gate —
use `bash $S/lead-build.sh …` rather than a bare `gradlew` when the Lead is running builds.

Device work goes through the Lead's scripts (`tasks/joybrush/lead-scripts/`), the same-hour baseline rule, and
**`am start`, never `adb shell monkey`** (the owner's rotation lock — `AGENTS.md`, `tasks/DEVICE_CONTROL_RUNBOOK.md`).

## 7. Risks

| risk | severity | mitigation |
|---|---|---|
| The tip angle is mirrored wrongly (handedness) | **high** | the §2.3 table is explicit and has its own test; a wrong mirror must fail it |
| The fan-out makes the input path too slow at N = 30 | high | lane 0 untouched; the shared-smoother variant (§2.5) as the fallback; measured on the Note 9 at N = 2/8/30 |
| The wet window pinned on `C` freezes the water or crawls | high | the window does not move at all during the stroke (no flush/reload); the safe zone bounds the area; Gate E's fallback is "ship A–D1, keep the wet brushes refused in words" |
| The wet pass cost rises with the copies' spread | high | the per-family caps; the safe circle; the same-hour baseline comparison |
| Overlap cannot be live | medium | found and stated (§2.6); the chip is honest about it; the owner chooses in Phase F |
| The image tip cannot be mirrored | medium | stated (§F3); a notice, or a scoped shader change |
| The symmetry guide leaks into the snapper | medium | `all()` excludes it; a test asserts it |
| A bad symmetry setting wipes the other guides | medium | `decode` validates the `sym` line locally and skips it; a test asserts the ruler survives |
| The line blocks painting | medium | the body grab is a thin target (12 dp); a miss falls through to the canvas (as the overlay does today); the always-on-grab vs Move-chip choice is the owner's (§8.8) |
| The widget fights the pen for touches | medium | the widget is above the overlay and takes only its own touches; the count scrub starts only after a 4 dp slop |
| The 45° snap fights the user's drag | low | soft: the angle is pulled, never latched; the tick fires once per snap |
| The caps surprise the user | low | the note is said **once per (family, cap)**, and the count on the widget still shows what was asked for |
| The Ruler regresses | low | symmetry is a separate field; `all()` is unchanged; the Ruler's adjust-mode behaviour is untouched |

## 8. Open questions (for the audit, not for the owner unless the audit disagrees)

1. **Overlap** — the three options of §2.6, or drop the chip? (The owner asked for the toggle; the engine cannot
   deliver it live without a memory cost. This is the plan's weakest point and it should be argued, not hidden.)
2. **The widget's contents** — is one widget with four controls (count, Mirror, Overlap, Symmetry) what the owner
   means by "the widget", or does the owner want the chips somewhere else (the Guides panel)? The owner's answer
   says "on the widget", so this plan assumes one widget.
3. **The safe circle** — the owner said "dotted line perimeter"; this plan uses a **circle** because a circle
   centred on `C` is the *exact* invariant set of the copy group (§2.7). A rounded rectangle approximating the
   window would be larger but less truthful. Confirm a circle is acceptable.
4. **The darkening's scope** — the owner said "perhaps even darken the canvas outside the zone 20 % while its
   toggled live". This plan shows it **only for media brushes** (the engine's bounded patch), because a stamp
   brush has no window and no limit. Confirm.
5. **The cap table** — the owner's starting numbers (ink 30, pencil 16, watercolour 8, oil 4–8) are guesses to be
   **measured**; the audit should pick the measured values, not the guessed ones. **As built** the table is in the
   code (`SymmetryLanes.CAPS`), in **copies**, with the media three marked **PROVISIONAL** — the media phase is
   the one that measures them.
6. **The media window's pinned placement** — pinning the window on `C` changes the media engine's behaviour for
   symmetry strokes only. The Lead's "growing patch" work (the top engine item for next week) may make the safe
   zone growable; if it lands first, Phase E should use it instead of a fixed window.
7. **The order of the phases** — **D1** (the grouped editable lines) is **DONE**: the copies are one group and
   Select takes the set, so "select and recolour all the copies" is Phase D1, not Phase B. **D2** (the angle frame
   in the record) is the part that was gated behind the codec work, and it is still to do — until it lands, a
   replayed mirrored line puts the tip the wrong way round (§D2.1).
8. **The line's grab radius** — the cross-review is right that an always-grabbable line can intercept a stroke
   that starts near the mirror axis, and painting *on* the axis is not rare. This plan's answer: the **body grab
   is a thin target** (12 dp, tighter than the 13 dp handles) and the axis is a degenerate place to paint (the
   copies overlap there anyway). The alternative — a Move chip that enters the guides' adjust mode (both other
   plans) — makes the line modal and shows every other guide's handles. **The owner should pick**: a thin
   always-on grab, or a Move chip.
9. **Overlap, again** — the cross-review calls Phase F's choice a punt. It is one, deliberately: the engine
   cannot do a live overlap, and the three options have different costs (a pop, a memory cost, or no chip). What
   the owner must not be shown is a chip that does not do what it says.
10. **The media pencils and the palette knife** — they have **no safe radius at all** in a 1024² window (§2.7):
    their reach is wider than the window, so every copy of a dab would leave it. They need the bigger patch the
    Lead has recommended skipping. **The owner must choose before the media phase is built:** refuse them in
    words, or approve the patch. The watercolour and the oil do not need the answer.

## 9. Acceptance criteria (what the owner will see)

* Guides → **Symmetry** ⇒ a thin vertical line through the middle of the screen appears, and painting is already
  mirrored (count 2). No hoops, no adjust mode.
* The line can be dragged by its ends, dragged by its body, and turned with two fingers; it snaps softly to
  0/45/90/135/180 with a small tick.
* The floating widget: drag the number to scrub 1..30 (1 = off, 2 = mirror, 3..30 = radial); **Mirror** turns
  radial into kaleidoscope; **Overlap** is present and says what it does; **Symmetry** (jitter/tilt) is on by
  default and turning it off gives each copy its own texture.
* The copies cross the line **without** a seam in merge mode; in overlap mode they behave as two brushes.
  ⚠ *the overlap half is **Phase F** and is not built — the engine has one coverage channel per tile.*
* **The copies of a stroke are ONE group**: select one copy and the whole set comes with it — recolour, re-brush
  and delete apply to all of them, and **one** undo puts them all back.
* With a watercolour or oil brush: a dotted circle shows the safe zone, the canvas outside it is darkened 20 %,
  and painting outside it says *"This is the largest area your device can safely paint with this brush."* and
  paints nothing there. ⚠ *Phase E — not built.*
* A watercolour wash on a vertical mirror runs **down on both sides**; the paper grain is not flipped; the copies
  mix where they meet. ⚠ *Phase E — not built.*
* A wet brush says *"This brush keeps symmetry to N copies on this phone."* when the cap bites.
* **The wet media brushes that cannot be copied say so in words** — the media pencils, the palette knife, the
  tuft, the legacy watercolour engine and the fill pen all answer *"Symmetry is not available for this brush."*
  A brush that cannot be copied never draws one lonely stroke and calls it symmetry.
* The setting is remembered **app-wide**, like the Ruler; it never exports and never enters the drawing file.

## 10. What this plan does **not** do

* It does not build a whole-screen patch (the owner has not approved it, and this plan does not need it).
* It does not touch the atlas (parked), the tuft engine, the legacy watercolour engine or the fill pen.
* It does not change the Ruler's behaviour, the guides' snapping, or the document format.
* It does not make overlap live (see §2.6) — it says so instead.
* It does not make an image tip mirror (see §F3) — it says so instead.
* It does not make the media pencils or the palette knife copyable: they have no safe radius in a 1024² window
  (§2.7) — it says so instead.
* It does not claim any timing until it is measured on the Note 9, in the same hour as a main baseline.
