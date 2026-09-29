# JB-2.11 — Hold to shape: the timer, the live preview, and resizing before you lift

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.10 (`ShapeRecognizer`, `ShapePerfecter` — Built 🟩), JB-1.05 (brushes in the view) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/shape/HoldToShape.kt`; NEW `.../commonTest/.../shape/HoldToShapeTest.kt`; EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (the hold gate and the preview call, named in Decision 5) |
| **Estimated size** | ~220 lines of core + ~200 lines of tests; ~60 lines of view wiring |

## Goal

OWNER_CONSTRAINTS: *"**Shape auto-detect:** hold to turn a rough circle/triangle/rectangle/arc into a
precise shape **while keeping the stroke's pressure and tilt**, so it stays organic, not
computer-perfect."*

JB-2.10 built the maths: recognise, then perfect. **This row is the half a person touches**: how long
the hold has to be, what they see while they hold, and how they resize the shape before lifting the
pen. Three decisions, and each one is a thing a person will notice within ten seconds of getting it
wrong.

## Contract

```kotlin
package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.input.PenSample

/**
 * The hold. One instance per stroke; feed it every sample as it arrives, then call [lift] with the
 * samples the stroke ended with.
 *
 * ALL of this is pure arithmetic on times and points — no clock, no view. The shell passes
 * `nowMs` and the screen point of the pen; the tests pass numbers.
 */
class HoldToShape(val density: Float = 1f, val enabled: Boolean = true) {

    /** What the person is being shown right now. */
    sealed class State {
        /** Drawing as usual. The samples the caller already drew stay drawn. */
        object Idle : State()
        /**
         * The pen has been still long enough and a shape was recognised. [shape] is what will be
         * drawn; the stroke so far has been WITHDRAWN from the screen and is not in the undo stack.
         */
        data class Shaping(val shape: Shape, val outline: List<PenSample>) : State()
        /** The pen is still but the stroke is not a shape. [stalled] is how long it has been still. */
        data class Waiting(val stalledMs: Long) : State()
        /** The pen moved again: any shape is abandoned and the ORIGINAL stroke comes back. */
        data class Abandoned(val reason: String) : State()
    }

    val state: State

    /** A raw sample, in the order it arrived. `nowMs` is the sample's own time. */
    fun add(sample: PenSample)

    /**
     * The pen lifted. Returns what to draw, in ONE list:
     *  - never held → the original samples;
     *  - held and recognised → `ShapePerfecter.perfect(samples, shape)` — the SAME sample count,
     *    with pressure/tilt/time copied through (JB-2.10's promise, kept here);
     *  - held and not recognised → the original samples.
     * Never empty for a stroke that had usable points, and never a mixture of the two.
     */
    fun lift(): List<PenSample>

    /**
     * RESIZE WHILE STILL HELD, before the lift: the shape is scaled about its own centre by
     * [scale] (a plain multiply, no homography — a shape is not a photo) and re-perfected from the
     * SAME original samples, so the pressure and tilt series is untouched by the resize.
     * Returns the new outline. A [scale] that is not finite or ≤ 0 is refused and the outline is
     * returned unchanged.
     */
    fun resize(scale: Float): List<PenSample>

    /** The pen moved more than the still-tolerance since the hold began. */
    fun moved(): State.Abandoned
}
```

## Decisions

1. **The hold time is 500 ms of stillness, in ms not dp, and it is not configurable in v1.**
   Configurable would be a slider nobody has an opinion about yet. 500 ms is the number Procreate
   and Concepts both feel like; it is one constant (`HOLD_MS`) and changing it is one line.
2. **"Still" means every sample within `6 × density` screen px of the pen's position when the hold
   began.** Six dp is the same constant JB-2.03a uses for its long-press eyedropper and it is
   deliberately the same number: both are "has the person committed to this gesture" thresholds, and
   two different values for one question would be two answers.
3. **The recogniser runs ONCE, at 500 ms, not on every sample.** `ShapeRecognizer.recognize` is
   O(points) and a 20 000-sample stroke would re-recognise 20 000 times. It runs on the samples as
   they stand at the hold, and if it returns null the state is `Waiting` and the recogniser does
   **not** run again until the pen moves (Decision 4).
4. **A `Waiting` stroke that never recognises is the ORIGINAL stroke, committed unchanged.** No
   message, no second look, no "did you mean…?". A person who held on a scribble gets their scribble
   back, which is what they drew. `Waiting` exists so the UI can show a subtle "listening" ring if
   it wants one, not so it can nag.
5. **The hold gate lives in the view, at ONE place, and it is a function so a test can reach it.**
   `HoldToShape` is pure; the view calls `add`/`lift`/`resize` from `feed`/`finishStroke` and draws
   the `Shaping` outline on the same transparent overlay View JB-2.06b/JB-2.03a already created
   (**one overlay only**). The view does not decide anything the class decides.
6. **The preview is the perfected outline drawn at 60 % opacity in the brush colour, with the rough
   stroke still visible underneath** — the same overlay treatment JB-2.06b gives the fill pen. The
   rough stroke is withdrawn from the ENGINE (it was never committed — a stroke is committed on
   pen-up, so withdrawing is simply not committing it) rather than erased from it. **Nothing is
   added to the undo stack until the lift.**
7. **Resize is one axis: horizontal drag = scale, and it is exponential with the SAME constant as
   JB-2.16a** (`SizeOpacityDrag.SIZE_PER_DOUBLING_DP` = 160 dp per doubling), so "drag right, it
   gets bigger" feels the same on the brush swatch and on a held shape. Vertical drag is ignored
   (there is nothing for it to mean); it is not "rotate".
8. **The resize range is 0.25× to 4× of the recognised shape**, and it clamps silently at the ends
   because a drag past the end of a scale is not bad input, it is a drag. This is the ONE clamp in
   this spec, and it is a clamp rather than a refusal on purpose: unlike a size (JB-2.16a, which
   clamps too) there is no meaning attached to 4.01×.
9. **Moving the pen by more than the still-tolerance ABANDONS the shape and the original stroke
   comes back.** Not the resized one, not a partial one: the original. `Abandoned` carries the
   reason in words so the view can show it if it wants to, and the default is to show nothing —
   the stroke appearing again IS the message.
10. **Hold-to-shape is OFF for the fill pen.** `engine == "fill"` records a closed shape already; a
    "perfect" version of a lasso is a lasso. The gate is on the brush's engine, not on the layer.
11. **Hold-to-shape does not run while `smoothing` is at 0 and the stroke is 3 points.** That is not
    a rule — it falls out of JB-2.10 Decision 1 ("fewer than 5 points, or `L < 12` screen px →
    null"). Stated so a builder does not add a second minimum.

## Tests

`HoldToShapeTest` (JVM, `:core:jvmTest`):
1. **Never held:** 40 samples 16 ms apart, moving; `lift()` returns the input list **equal, same
   instances** (not a copy) — asserts `State.Idle` throughout and the exact list back.
2. **Held and recognised:** the same 40 samples, then 500 ms of a pen at the same point →
   `State.Shaping` with a non-null shape, and `lift()` returns a list of the SAME LENGTH whose
   positions differ from the input's. Same count is the load-bearing assertion.
3. **Feel survives:** every output sample's `pressure`, `tilt`, `azimuth`, `barrel`, `timeMs` and
   `tool` equals its input's, element for element. (JB-2.10 test 10's promise, pinned again at the
   UI's door because this is where a builder would break it.)
4. **Waiting then abandoning:** a scribble held for 500 ms → `State.Waiting`; moving 7 dp →
   `State.Abandoned`; `lift()` returns the **original** samples, not an empty list and not a partial.
5. **The recogniser runs once:** a held stroke of 5000 samples produces exactly one
   `recognize` call (a counting fake, injected — the constructor takes the recogniser as a
   `(List<PenSample>, Float) -> Shape?` defaulting to `ShapeRecognizer::recognize`). This is the test
   for Decision 3, and it fails loudly if someone moves the call into `add`.
6. **Density:** at density 2 the still-tolerance is 12 screen px — 11 px is still, 13 is not — and
   the hold is still 500 ms (Decision 1: ms, not dp).
7. **Zoom invariance:** the same stroke at `screenPerDoc` 0.25 and 4 recognises the same shape,
   because the tolerances are in screen px.
8. **`resize`:** 160 dp right of the hold doubles the shape's extent; left halves; ±10 000 clamps to
   4× and 0.25×. `resize(0f)`, `resize(-1f)`, `resize(Float.NaN)` return the **unchanged** outline,
   never an empty one and never a crash. And after a resize, `lift()`'s samples still have the
   input's pressure/tilt series (test 3 again, through the resize).
9. **`enabled = false`:** every sample goes to `Idle`, `lift()` returns the input, and `resize`
   returns an empty list (there is nothing being shaped — and an empty list here is legitimate, which
   is why Decision 6 says the shell must check `state` and not the list's size).
10. **Garbage in:** a non-finite sample coordinate is dropped and counted the way JB-0.01's
    `StrokeSmoother` does — the house rule is a time series, a missing sample is ordinary, and the
    count is what stops "faithful" meaning "silent".

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Owner check (Note 9, with the S Pen)

Draw a rough circle, stop, keep the pen down → after about half a second the perfect circle appears
at 60 % over your rough one. Drag right before lifting → it grows; left → it shrinks. Keep holding
and wiggle → the rough circle comes back, unchanged. Draw a scribble and hold → nothing happens, and
lifting gives you the scribble. Zoom to 400 % and do it again → the same circle is recognised.

## Do not

- **Do not touch JB-2.10's thresholds.** If a test cannot pass with them, write it in Questions and
  stop (JB-2.10's own "Do not").
- Do not add a second overlay View. JB-2.06b/JB-2.03a create one; this row draws on it.
- Do not add a settings UI, a "was this a shape?" message, or a rotate gesture.
- Do not fit in document px — every tolerance here is screen px, ÷ `screenPerDoc`.
- Do not commit anything to the engine before the lift.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] committed `JB-2.11: hold-to-shape UI`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### 🔴 For the Lead

1. **500 ms is a number I picked.** It is the one value in this spec a person will have an
   immediate opinion about — too short and every stroke snaps, too long and holding feels broken.
   Procreate and Concepts are both around this, but I have not measured either. **If you have a
   preference, override it; the constant is one line and every tolerance in the class scales off
   it.** If not, this is exactly the kind of thing the owner should try on the Note 9 in the JB-2.30
   check, and I would rather the first value shipped be a plain guess than a hidden guess.
2. **Should the hold work for the FILL PEN (Decision 10) is mine, but the reason is not obvious
   enough.** My reasoning: the fill pen's stroke IS a closed polygon, so a "recognised" version of
   it is a lasso snapped to a shape — which is a genuinely useful thing, and I have ruled it off
   because a fill pen that waits 500 ms before filling makes the fill feel broken. **If the owner
   wants snap-to-shape for the fill pen, that is a different feature (recognise the closed outline,
   perfect it, then fill it) and it should be its own row rather than a flag here.**
3. **The preview at 60 % over the rough stroke** (Decision 6) is the fill pen's treatment, reused so
   the two read the same. An alternative is to REPLACE the rough stroke with the perfect one at
   full opacity the instant the hold completes, which is cleaner to look at and loses the "this is
   what you drew, this is what it became" comparison that makes the feature legible. I chose the
   overlay. Cheap to change, but it is a look decision and it is yours.

### Low-risk, ruled provisionally

4. **Resize is horizontal-drag only, exponential, 160 dp per doubling — the SAME constant as the
   brush swatch** (Decision 7). If the two ever diverge the controls stop feeling like one app, and
   nothing but a test would catch it; I have named the constant in the code so it is obvious.
5. **The resize clamp is 0.25×…4× and clamps silently** (Decision 8) — the one clamp in this spec,
   and deliberate, for the reason in the decision.
6. **Moving abandons the shape and restores the ORIGINAL stroke** (Decision 9), not the resized one.
7. **Not configurable in v1** (Decision 1).
