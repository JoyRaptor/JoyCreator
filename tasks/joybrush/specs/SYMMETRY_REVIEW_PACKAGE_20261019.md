# Symmetry — review package for the adversarial advisors (2026-10-19)

**For:** DeepSeek and Muse, as the code reviewers for this feature.
**From:** the JoyBrush Lead (opencode, MiniMax M3.1), orchestrating the build.
**What is asked of you:** read the code and the invariants below, and attack them. The feature's whole
value is that the copies are *real mirrored brushes* — a plausible-looking mirror that is a few degrees
or a few pixels out is the failure that matters, and it will not show up in a screenshot of a round brush.

---

## 1. What is built (all on `lead/symmetry-1019` in `C:\Temp\jb-symmetry`, plus two branches being merged)

| commit | what | where |
|---|---|---|
| `e7674b3bf` | the shared shape: `Guide.Symmetry`, `GuideSettings.symmetry`, the draw branch, `JbCanvasView.symmetryGuide` | `core/guide/*`, `androidkit/JbCanvasView.kt` |
| `9c82bd5f8` | **A1** the copy maths (`SymmetryMath`) and the persistence, + the review's fixes (the count cap, the barrel as a direction, one rule for a usable symmetry) | `core/guide/SymmetryMath.kt` (new), `core/guide/GuideSettings.kt` |
| `cd08fd4e6`, `1180447ff` | **A2** the `AngleFrame` (the copy's geometry) and the brush's use of it; the scatter's across-component negation | `core/brush/{AngleFrame,BrushDabber,Scatter}.kt` |
| `67767bd7a` | **A3** a copy's tracker starts at the copy's own heading | `core/input/DirectionTracker.kt`, `core/brush/*` |
| `d43fff47a` | **C** the owner-facing UI: the floating widget, the draggable line, the Guides row | `joybrush-android/…/chrome/*`, `JoyBrushActivity.kt` |
| `cff1f78a0` | **B** the stroke fan-out: one lane per copy, one engine stroke, one undo step | `core/symmetry/*` (new), `androidkit/JbCanvasView.kt` |
| `a19d73d2a` | the cap fix: the caps count COPIES, the note says the copy count, said once per family+cap | `core/symmetry/SymmetryLanes.kt`, `androidkit/SymmetryCapNote.kt` |
| `651755a6b` | **D1** the copies are ONE group of editable lines, one undo | `core/symmetry/SymmetryLane.kt`, `gl/lines/LineCommit.kt`, `gl/GlPaintEngine.kt` |

Everything is green: `:core:jvmTest` 2574, `:androidkit:test` 1061, `:joybrush-android:testDebugUnitTest` 317.

## 2. The invariants the code must hold (judge the code against these)

1. **A copy is an isometry of the page** — a rotation about the guide line's midpoint `C`, or a reflection
   across that line. Its transform is applied to the **pen sample exactly once** (position + azimuth +
   barrel), before the lane's brush sees it. **There is no transform of the finished dab.**
2. **The tip angle** is composed by `BrushDabber.look` as `base` → `+ direction` (only when
   `tip.followDirection`) → `+ jitter`. The lane's samples are already transformed, so the direction term
   is already the copy's. The `AngleFrame` turns the other two:

   | copy | followDirection | base becomes | jitter becomes |
   |---|---|---|---|
   | rotation θ | on | `base` | unchanged |
   | rotation θ | off | `base + θ` | unchanged |
   | mirror axis φ' | on | `−base` | `−jitter` |
   | mirror axis φ' | off | `2φ' − base` | `−jitter` |

   `φ'` is the **copy's own** axis: a kaleidoscope copy `R(θ)∘M(φ)` is a reflection about `φ + θ/2`.
3. **The azimuth** is turned by the copy's linear part applied to the direction VECTOR (`atan2(L·v)`), which
   is `a + θ` for a rotation and `2φ' − a` for a mirror. **The barrel is transformed the same way**,
   because `DirectionTracker.update()` returns the barrel as the tip's direction.
4. **The scatter** is built in each dab's own frame; a reflection maps the original's perpendicular onto
   the NEGATIVE of the copy's, so the **across** component must be negated for a mirror copy. The RNG
   stream must not move.
5. **One engine stroke**: `GlPaintEngine.beginStrokeBuffer` calls `cancelStroke()`, so there is only ever
   one live stroke. All the lanes' dabs go into one `addDabs` and one `endStroke` ⇒ **one undo step**.
6. **The copy's randomness**: with the guide's `symmetric` on (the default) every lane shares ONE seed
   (so the copies' textures are the mirror of each other); with it off every lane has its own seed, the
   lean and the twist are left alone, and the scatter is not negated across.
7. **The guide never reaches the drawing**: `GuideSettings` is app-wide (the same prefs value as the
   Ruler), it is not in `JbDocument`, and it is deliberately NOT in `GuideSettings.all()` so the snapper
   never sees it.

## 3. What to attack, in the order I would attack it

1. **The angle table, end to end.** Re-derive it from the geometry and check it against
   `core/brush/AngleFrame.kt` and `core/guide/SymmetryMath.kt`. Then check the *composed* angle a lane
   actually produces, including the tracker's first-dab heading (`core/input/DirectionTracker.kt`).
   A radial copy whose tip is out by θ, or a mirror whose tip is out by 2φ', is the failure that matters.
2. **The kaleidoscope copy's own axis.** It is the most load-bearing fact in the feature and the easiest
   to get half a turn wrong. `SymmetryMath.compose` is private and only valid for `R(θ)∘M(φ)`.
3. **The transform applied exactly once.** A second transform (on the dab, or on the scatter offsets)
   would put the copies in the wrong place. `core/symmetry/SymmetryLane.kt` is where this lives.
4. **The `decode` all-or-nothing trap.** `GuideSettings.decode` ends with
   `return if (refusalFor() == null) s else NONE` — one bad symmetry value would wipe the owner's grid,
   perspective and ruler. The symmetry must be validated LOCALLY in the decoder.
5. **The lane's first dabs** (the tracker's seeded heading) and **the per-lane flush** (a lane that never
   gets its `finish()` loses its catch-up tail — silent).
6. **The one undo step** (`RefCanvas.undoDepth == 1` after the fan-out) and **the group** (one `groupId`
   for every copy, so Select picks the set).
7. **The tests' oracles.** Any expected value that was read back out of the implementation instead of
   worked out longhand proves nothing. The deliberate-break logs (each agent broke its own code and
   recorded the failing test) are in the commit messages' agents' reports — spot-check that the breaks
   were the right ones.

## 4. Known limits and open questions (do not report these as bugs — they are decisions)

- **Overlap mode is not built.** The engine has one coverage channel per tile, so "the copies behave as
  two separate brushes where they cross" cannot be live. Three options are on the table: N sequential
  strokes at pen-up (a visible pop), a second coverage channel (2× the stroke-buffer memory, two lanes
  only), or no chip. The owner decides.
- **The image tip cannot be mirrored.** The tip's texture is sampled in the tip's own frame; a reflection
  of a texture is not a rotation of it. The GPU has the dab's angle but no "mirror me" flag.
- **The wet media phase is not built yet**, and the numbers say it is the hard one (see §5).
- **A borderline corner can make two lanes differ by one point**: each lane owns its own smoother, so the
  copy's corner decisions are made on the mirrored path — the same decisions mathematically, but the
  arithmetic is not bit-identical, and a corner within rounding of 55° could be cut in one lane and not in
  the other. A possible follow-up: decide the corners once on the original and map them.
- **With the symmetry toggle off**, the copy's tip is the original's brush in the copy's frame, not its
  mirror: the lean and the twist are deliberately left alone (that is what the owner asked for).

## 5. The number the owner must decide (the wet media phase)

The media engine keeps ONE 1024×1024 window that follows the pen. For symmetry it would be **pinned on
the symmetry centre** instead, so every copy's wet paint lives in ONE water field (the copies mix
physically where they meet) and the window never moves (a move is a flush + reload, 10–16 ms a frame on
the Note 9). The window's origin is **tile-aligned** (`MediaWindowMath.placeFor` = `floor(doc/256) − 2`),
so the centre lands 512–768 px from the window's left edge and 256–512 px from its right, and the safe
radius must be computed from those two distances — it is **not** a constant.

| brush | reach (doc px) | safe radius with the window's floor placement | with rounding |
|---|---|---|---|
| media watercolour | 90 | (38, 294] | (166, 422] |
| media oil | 120 | (8, 264] | (136, 392] |
| media pencil | 264 | **negative — cannot be copied** | (0, 192] |
| palette knife | 440 | **negative** | negative |

⇒ Watercolour and oil can be copied, with a modest zone (the owner must zoom in — which is exactly the
UX the owner asked for: a dotted perimeter, the canvas darkened 20 % outside it, and a spoken refusal
outside it). **The media pencils and the palette knife cannot be copied at all in a 1024² window** — they
need the bigger patch the Lead has recommended skipping. The owner's answer to that is needed before the
media phase is built.

## 6. How to build and test (do not run gradle directly — use the Lead's serialised wrapper)

```
& "C:\Program Files\Git\bin\bash.exe" -lc "cd /c/Temp/jb-symmetry && bash /c/+Projects/Screenrecorder/FadCam/tasks/joybrush/lead-scripts/lead-build.sh /c/Temp/jb-symmetry 2000 /c/Temp/jb-symmetry-logs/<your name>.log -p joybrush :core:jvmTest :androidkit:test :androidkit:compileKotlin --continue"
```
The wrapper takes the machine-wide Gradle lock and a RAM floor; use the 2000 MB floor (the machine's free
memory sits below 3000 MB). **No device action** without the owner saying so, and never `adb shell monkey`
(it turns the owner's screen-rotation lock off).

The plan of record is
`tasks/joybrush/specs/SYMMETRY_IMPLEMENTATION_PLAN_20261019_MINIMAX_M31.md` (corrected after the
DeepSeek/Muse cross-review of 2026-10-19; the corrections are listed at the top of that file).
