# Adversarial review — JB-1.08a The fill pen (outline maths + brush v2 + shipped preset)

- Reviewer: claude (second adversarial pass; **muse-spark reviewed this task first** —
  `tasks/joybrush/reviews/JB-1.08a__muse-spark.md`, which filed **no findings**). Where I overlap I
  say so explicitly.
- Task status: 🟧 Built. Suite run by me at HEAD: `./gradlew -p joybrush :core:jvmTest` →
  **659 tests, 1 failure**, and the failure is **not** in this task
  (`ThreeFingerSwipeTest.anOverrideIsRememberedForTheBoardItWasMadeOnAndForgottenOnAnother`,
  JB-3.08a). `FillPenTest`, `BrushTest`, `DocModelTest`, `EnumFreezeTest` all green.
- Spec reviewed: `tasks/joybrush/specs/JB-1.08a_fill_pen.md` (contract, Decisions 1–5, tests 1–6,
  orchestrator rulings Q1/Q2/Q6, builder Q1–Q6).
- §5b: I edited only this file. No git. No source edits.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER, no MAJOR, one MINOR.** The outline maths is correct — I re-derived the two
closed forms and traced the resampler. The one finding is a *proof* gap, not a wrong answer.

---

## Finding 1 (MINOR, non-vacuity — does NOT contradict the spec): the pre-smoothing de-duplication
## is load-bearing and **no test supplies the input that distinguishes it**

**File:line.** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/FillPen.kt:41`
(`withoutConsecutiveDuplicates(samples)`), the guard at `:42`, the helper at `:55-63`, and the KDoc
paragraph asserting it at `:36-38`:

> *"Consecutive duplicate points are dropped on both sides: a pen that stands still, or reports the
> same reading twice, must not turn into a zero-length edge the fill rule has to think about."*

**The claim is TRUE. Nothing pins it.**

**The distinguishing input.** Three samples, the first two identical, then a real move — i.e. the
ordinary shape of "the pen paused for one report, then travelled":

```kotlin
val at = { x: Double, y: Double, t: Double -> PenSample(x.toFloat(), y.toFloat(), t, pressure = 0.5f) }
val pausedThenMoved = listOf(at(0.0, 0.0, 0.0), at(0.0, 0.0, 5.0), at(10.0, 0.0, 10.0))
```

* **As built** (`FillPen.kt:41-42`): `usable` = 2 points → `usable.size < MIN_CORNERS` → **`emptyList()`**.
* **With `withoutConsecutiveDuplicates` deleted** (the buggy version the KDoc claims to forbid):
  3 samples pass the guard, and `StrokeSmoother.feedPath`
  (`core/.../input/StrokeSmoother.kt:161-179`) treats the zero-length segment as a no-op
  (`segLen = 0`, `while (along <= segLen)` is `1.0 <= 0.0` = false, `untilNext` unchanged), then
  resamples `(0,0) → (10,0)` at `STEP = 1` screen px and pushes x = 1…10, with `finish()`'s
  catch-up suppressed (`hypot(10-10, 0) = 0`, not `> 1e-9`). Result: **11 collinear points** — the
  "zero-length edge … the fill rule has to think about", and a degenerate sliver returned where the
  contract says "empty list".

**Why the suite is blind to it.** Every test that appears to cover this supplies an input on which
the two versions *agree*:

| `FillPenTest.kt` | input | with dedup | without dedup |
|---|---|---|---|
| `:255` `still` | 50 samples, all `(7,7)` | `usable`=1 → EMPTY | `usable`=50 → `smoothAll` = 1 point → `out.size<3` → EMPTY |
| `:257-259` `withRepeat` | `still` + one more `(7,7)` | `usable`=1 → EMPTY | same as above → EMPTY |
| `:261` `still.take(3)` | 3 samples, all `(7,7)` | `usable`=1 → EMPTY | `smoothAll` = 1 point → EMPTY |
| `:249-253` two-sample | 2 samples | `usable`=2 → EMPTY | `usable`=2 → EMPTY |
| `:274-282` duplicate test | `doubled.add(s)` inserts a **byte-identical** `PenSample` (same `timeMs`) | — | `push` interpolates `ts` between `a` and `b`; identical `timeMs` ⇒ identical output ⇒ **byte-equal either way** |

So: delete `FillPen.kt:55-63` entirely and `FillPenTest` is **4/4 green**.

**Suggested test (belongs in the source tree, not here — §5b):** assert
`FillPen.outline(pausedThenMoved, 0f, 1f) == emptyList()`, and as the non-vacuity control assert that
`pausedThenMoved` *without* the repeat (`listOf(at(0,0,0), at(10,0,10))`) is also `emptyList()`
(2 points) while a genuinely-collinear-but-long 3-sample stroke
(`at(0,0,0), at(5,0,5), at(10,0,10)`) is **not** rejected by the pre-check — which is the only way
to show the pre-check is doing work rather than being covered by the post-check.

**Not a spec contradiction.** Decision 3 ("Consecutive duplicate points are dropped") and the Q3
ruling ("counted on BOTH sides") are both honoured. This is assertion discipline, of the same class
as `mimo`'s m1 on JB-3.01.

---

## Verified CORRECT (with reasoning, not test-counting)

1. **The two-sided `< 3` guard is the ruled behaviour and is implemented as ruled.**
   `FillPen.kt:42` (pre) and `:49` (post), `MIN_CORNERS = 3` at `:53`. Q3's ruling is that a
   two-sample flick must be empty rather than a 51-point zero-area sliver; the pre-check is what
   delivers it, and Finding 1 above shows the post-check alone would *not* (it returns 11 points).
   I re-verified: with two samples 50 px apart, `smoothAll` alone yields 51 collinear points, so the
   pre-check is the load-bearing half — and it is the untested half.
2. **The "C" expected area is a genuine closed form, not a fitted number.**
   `FillPenTest.kt:176-184`: a 270° arc of radius `r`, so the region is the disc minus the 90° minor
   segment, area `r²(θ − sin θ)/2` at `θ = 1.5π` = `r²(0.75π + ½)`. I re-derived it independently:
   `πr² − ½r²(π/2 − 1) = r²(3π/4 + ½)`. Same number. The assertion is `exact * 0.002`, and the
   1-px-resampling deficit of an inscribed 271-gon is ≈ 0.006 % — the bound is 300× the error, so it
   is a real bound rather than a rubber stamp.
3. **The chord assertions are correct and non-trivial.** `FillPenTest.kt:194` asserts
   `|last − first| = 2r·sin(θ/2) = 141.421…`; for the arc's own endpoints `(500,400)` and `(600,500)`
   the true distance is `√(100² + 100²) = 141.421…`. The containment probe at `:206`
   (`contains(outline, Pt(580, 470))` must be false) — I checked the geometry: the chord is `y = x − 100`,
   the centre `(500,500)` is above it, `(580,470)` is below it, and `|(580,470)−(500,500)| = 85.4 < 100`
   is inside the circle. So the point is inside the disc and outside the C. The test is right.
4. **The square assertions are sound.** The resample grid starts at the first sample and steps 1
   screen px, and the square's corners sit at path distances 0/200/400/600/800, so the grid lands on
   them exactly; `CORNER_ANGLE = 55°` vs a 90° turn with the ±`k` = ±6 maximality window makes each
   corner a corner, so the outline is the drawn boundary and the shoelace is exactly 40 000. The test
   asserts 2 % (`FillPenTest.kt:136`) — loose, but the additional independent checks at `:139-161`
   (fan area, per-point distance to the boundary, corner survival, perimeter, two containment probes)
   are the real evidence and none of them is self-referential.
5. **Decision 5 (pressure/tilt recorded, not read) holds.** `FillPen.kt:46` reads only `s.x`/`s.y`;
   `PenSample`'s pressure/tilt/azimuth/barrel are carried through `StrokeSmoother` and discarded here.
6. **Decision 1 + Q2(b) version rule is exactly as ruled.** `BrushJson.kt:20` `BRUSH_VERSION = 2`
   (single constant, no second number), `:111-112` `versionFor` writes the *lowest* expressing
   version, `:62-64` `decode` refuses a v1 `fill`/`behind` with the exact sentence
   `engine "fill" needs brush version 2`, mirrored in `BrushValidate.kt:65-69` (rule 1b) as Q4
   requires. `BrushPreset.kt:61` default is `BRUSH_VERSION`. No rename of any existing field or value.
7. **The shipped preset is byte-exact to the spec's JSON** and to Q1's `size.base = 8` ruling
   (`joybrush/brushes/fill/brush.json`, plus the `fill` line in `brushes/index.txt`). The size rule is
   correctly *not* skipped for `engine: "fill"` (`BrushValidate.kt:78-81` runs unconditionally).
8. **No `BlendMode.entries` in `commonMain` production code** and no `Ordinal` reads on `BlendMode`
   anywhere — I grepped every `.kt` under `joybrush/`; the only `.ordinal` write is
   `StrokeCodec.kt:66` for `Tool`, a different enum. So the fill pen's `engine: "fill"` genuinely
   reaches nothing that would mis-render it today.

---

## Note on the framing I was given (not a code finding)

My brief said the outline maths is *"proven against a Python re-implementation"*. **There is no
Python re-implementation of the fill pen in the repo.** I listed every `.py`/`.sh`/`.js` outside
`.claude/`, `build/`, `node_modules/`, `out-*`: the only new one is `tools/blend-golden/glsl_model.py`
(JB-2.20a). Nothing outside `core` mentions `FillPen` at all.

The *code's* own account is accurate and better than the brief's: `FillPenTest.kt:17-25` says the
expected numbers "come from two models that do not share code with the implementation: the closed
circular-segment area `r²(θ − sin θ)/2` for the 'C', and for the square the facts that every point of
the outline must still lie ON the drawn boundary…". That is exactly what the file does, and I have
re-derived both models above. So this is a **wording defect in the briefing, not a false claim in the
tree** — recording it so the log does not record a proof that does not exist.

---

## Recommendation

No send-back. Fix Finding 1 by adding the one test (or, if the Lead prefers, accept it — the
behaviour is right and the KDoc is true, so this is documentation-grade, not risk-grade). Do **not**
record "proven against a Python re-implementation" anywhere for this task: the proof is the two
analytic models in `FillPenTest`, and they hold.
