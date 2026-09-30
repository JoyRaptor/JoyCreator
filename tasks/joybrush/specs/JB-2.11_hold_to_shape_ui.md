# JB-2.11 — Hold to shape: the hold, the decision, and resizing before the lift (CORE HALF)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 **Ready.** This is the **core half only.** The view half is cut and named in **Cut from this spec** below; it is the Lead's row, because R30 puts `JbCanvasView.kt` in the Lead's hands. |
| **Who** | original spec writer · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — re-scoped to the core half per the Lead's review (🟦 "core half"); **R39's contradiction fixed** (the rough stroke STAYS in the engine's stroke buffer while the preview draws over it — the draft said both "withdrawn from the screen" and "withdrawn from the ENGINE", which are different claims and both wrong); the fill pen's OFF is now a decision with a real constant to test it against (`ENGINE_FILL`); 500 ms labelled a guess instead of presented as derived; the contract re-fitted to the **landed** `Shape` / `ShapeRecognizer` / `ShapePerfecter` / `PenSample` API (it had no zoom at all, so a builder would have had to invent one); two API holes closed (`tick`, because a pen that is perfectly still sends no events, and the recogniser seam, so "runs once" is testable); the test file moved from `jvmTest` to **`commonTest`**, which is both the source-set rule and the only place it can see the landed `StrokeMaker`; three new questions the old Questions had stopped asking. |
| **Depends on** | JB-2.10 (`ShapeRecognizer`, `ShapePerfecter` — 🟩 Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/shape/HoldToShape.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/shape/HoldToShapeTest.kt` · **nothing else.** Two files. |
| **Estimated size** | ~200 lines of core + ~230 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. (`commonTest` is compiled into the JVM test compilation, so this command runs this suite.) |

## Goal

OWNER_CONSTRAINTS: *"**Shape auto-detect:** hold to turn a rough circle/triangle/rectangle/arc into a
precise shape **while keeping the stroke's pressure and tilt**, so it stays organic, not
computer-perfect."*

JB-2.10 built the maths — recognise, then perfect. This row is the **decision** in front of that
maths: how long the hold has to be, when the shape is decided, what happens to a stroke that never
becomes one, and how the shape is resized before the pen lifts. All of it is arithmetic on times and
points, so all of it is testable on a computer with no phone, no view and no engine.

**This row has no opinion about what the person sees.** Where the draft used to say "the preview is
drawn at 60 % over the rough stroke on the one overlay View", this row now says what the CALLER must
do and nothing about how it looks. That is deliberate: R30 reserves `JbCanvasView.kt` and
`JoyBrushActivity.kt` for the Lead, so a spec that promises a builder both halves cannot be
dispatched without two builders in one file.

## Cut from this spec, and where it now lives

Everything in this table was in the draft and is **not** in this spec. Nothing was deleted silently.

| Cut | Where it lives now |
|---|---|
| The hold gate in the view: where `HoldToShape` is constructed, fed and read | **JB-2.11b — the Lead's row.** `JbCanvasView.kt` is R30 item 2, Lead only. |
| The preview: the overlay `View`, the 60 % opacity, the "one overlay only" rule | **JB-2.11b.** Note that no overlay `View` exists in `androidkit` today — searching that module for "overlay" finds two comments about a *diagnostics* overlay and one unrelated blend-mode colour key, no class — so the draft's "the same transparent overlay View JB-2.06b/JB-2.03a already created" was a claim about the future, and JB-2.11b must not assume it. |
| "the rough stroke is withdrawn from the engine" | **Wrong, and R39 fixes it** — see Decision 6. There is no view question left to answer. |
| The resize **gesture** (which drag, what a long-press means) | **JB-2.11b.** This spec owns the *mapping* from a horizontal screen-px travel to a scale (Decision 7) and nothing about how the travel is obtained. |
| The owner check on the Note 9, with the S Pen | **JB-2.11b** — it needs a phone. |
| Q3 of the draft, "the preview at 60 % over the rough stroke, or replace it outright" | **JB-2.11b's** question. It is a look decision, and it was the draft's own Q3; it is restated there, not dropped. |
| "brushes in the view" (JB-1.05) as a dependency | **JB-2.11b.** The core half needs nothing but JB-2.10. |

## Contract

Pasted from what is actually landed. The three types below are quoted from
`core/src/commonMain/kotlin/cc/joycreator/joybrush/core/shape/` and `…/input/PenSample.kt`; only
`HoldToShape` is new.

```kotlin
// shape/Shape.kt  (landed, JB-2.10, verbatim — the four cases, unchanged)

package cc.joycreator.joybrush.core.shape

/** A point in document (or, inside the recogniser, screen) px. */
data class Pt(val x: Double, val y: Double)

/** A perfect shape that a rough stroke was recognised as (JB-2.10). All values in document px. */
sealed class Shape {
    data class Line(val a: Pt, val b: Pt) : Shape()

    /** Circle when rx == ry. rotation in radians. */
    data class Ellipse(val center: Pt, val rx: Double, val ry: Double, val rotation: Double) : Shape()

    /** Arc of a circle from startAngle sweeping sweep radians (sign = direction drawn). */
    data class Arc(val center: Pt, val radius: Double, val startAngle: Double, val sweep: Double) : Shape()

    /** Closed polygon with corners in drawing order: triangle (3) or rectangle/quad (4). */
    data class Polygon(val corners: List<Pt>) : Shape()
}
```

```kotlin
// shape/ShapeRecognizer.kt:43  and  shape/ShapePerfecter.kt:25  (landed signatures, verbatim)

object ShapeRecognizer {
    fun recognize(points: List<PenSample>, screenPerDoc: Float = 1f): Shape?
}

object ShapePerfecter {
    fun perfect(points: List<PenSample>, shape: Shape): List<PenSample>
}
```

```kotlin
// input/PenSample.kt:32  (landed, the fields this class copies through, verbatim)

data class PenSample(
    val x: Float,
    val y: Float,
    val timeMs: Double,
    val pressure: Float = 1f,
    val tilt: Float = Float.NaN,
    val azimuth: Float = Float.NaN,
    val barrel: Float = Float.NaN,
    val tool: Tool = Tool.STYLUS,
    val predicted: Boolean = false,
) {
    val isPlaceable: Boolean get() = x.isFinite() && y.isFinite() && timeMs.isFinite()
}
```

```kotlin
package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag

/**
 * Hold-to-shape part 3: the HOLD, the decision, and the resize (JB-2.11). One instance per stroke,
 * built at pen-down.
 *
 * Pure arithmetic on times and points. No clock, no view, no engine, no Android. The shell hands it
 * samples in DOCUMENT px (the same samples `JbCanvasView.feed` gives the smoother, after
 * `view.screenToDoc`) and reads [state] back.
 *
 * ## What the CALLER must do with this — the whole of R39, in one place
 *
 * While the pen is down the caller keeps drawing the ORIGINAL samples exactly as it always does. The
 * rough stroke therefore stays in the engine's stroke buffer, on the screen, uncommitted — a stroke
 * is only committed on pen-up, and nothing here has committed anything. The [State.Shaping] outline
 * is an OVERLAY drawn on top of it; it is not a replacement for it and the caller does not withdraw,
 * erase or re-feed anything to make it appear.
 *
 * On the lift the caller calls [lift] and gets back ONE list. It then cancels the in-flight stroke
 * (`GlPaintEngine.cancelStroke()` discards the uncommitted buffer) and feeds that list through the
 * SAME path it would have used anyway — the same brush, the same layer, the same dabs,
 * `endStroke()` — so the result is ONE stroke and ONE undo step. There is no "replay", no second
 * brush, and no way for a person to undo the shape and find the rough stroke underneath.
 *
 * [lift] therefore returns the samples to feed, and never a mixture: either the whole original
 * stroke, or the whole perfected one.
 */
class HoldToShape(
    /** `ViewTransform.zoom` — screen px per document px, read once at pen-down. */
    val screenPerDoc: Float = 1f,
    /** Display density, so the still-tolerance is a number of dp and not a number of millimetres. */
    val density: Float = 1f,
    /** `BrushPreset.engine`. The fill pen is off (Decision 9). */
    val engine: String = "stamp",
    /** The recogniser seam, so "it runs once" is a test and not a claim. */
    private val recognize: (List<PenSample>, Float) -> Shape? = ShapeRecognizer::recognize,
) {

    /** What the person is being shown right now. */
    sealed class State {
        /** Drawing as usual. The samples the caller already drew stay drawn. */
        object Idle : State()
        /**
         * The pen has been still long enough and a shape was recognised. [shape] is what will be
         * drawn; [outline] is that shape with the stroke's pressure and tilt series carried over,
         * re-derived from the CURRENT samples and the CURRENT scale on every change.
         */
        data class Shaping(val shape: Shape, val outline: List<PenSample>) : State()
        /** The pen is still but the stroke is not a shape. [stalledMs] is how long it has been still. */
        data class Waiting(val stalledMs: Long) : State()
        /** The hold is over and the ORIGINAL stroke is what will be committed. */
        data class Abandoned(val reason: String) : State()
    }

    /** Where the hold has got to. Read this; never infer it from the size of a returned list. */
    val state: State

    /** False for the fill pen, true otherwise (Decision 9). */
    val enabled: Boolean

    /**
     * Samples refused because they had no place or no time in the world, counted the way
     * `StrokeSmoother.droppedSamples` counts them. Zero in every real stroke.
     */
    val droppedSamples: Int

    /** One sample, in the order it arrived. `sample.timeMs` is the clock, and so is [tick]. */
    fun add(sample: PenSample)

    /**
     * Time has passed with no new sample. **The host must call this** — a pen that is perfectly
     * still sends no `MotionEvent`, and without this the hold could only ever fire on the next
     * movement, which is the one thing the person is not doing. Call it on every `MotionEvent` that
     * carries no new sample, and from whatever the view already runs per frame.
     *
     * `nowMs` is the same clock as `PenSample.timeMs` (Android's event time is uptimeMillis).
     * Non-finite or earlier-than-the-last-reading values are ignored, and `state` does not move.
     */
    fun tick(nowMs: Double)

    /**
     * RESIZE while still held: the shape is scaled about its own centre by the drag so far.
     * [dxScreen] is the total HORIZONTAL screen-px travel since the hold completed, +x right.
     * Returns the new outline, and [state] carries it too.
     *
     * Ignored — and an **empty list** returned, because there is no outline to draw — unless the
     * state is [State.Shaping], and ignored outright (outline unchanged) if [dxScreen] is not a
     * finite number. That empty list is legitimate, which is exactly why a caller must read [state]
     * and never the size of what came back.
     */
    fun resize(dxScreen: Float): List<PenSample>

    /**
     * The pen lifted. Returns what to feed through the normal path, in ONE list:
     *  - never held → the original samples, the same instances;
     *  - held and recognised → `ShapePerfecter.perfect(samples, scaledShape)` — the SAME sample
     *    count, with pressure/tilt/azimuth/barrel/tool/time copied through;
     *  - held and not recognised, or abandoned → the original samples.
     * Never empty for a stroke that had usable points, and never a mixture of the two.
     */
    fun lift(): List<PenSample>

    companion object {
        /**
         * Milliseconds of stillness that turn a stroke into a shape candidate.
         *
         * **THIS IS A GUESS AND IT IS LABELLED AS ONE.** It is not derived from anything in this
         * repo, it has not been measured on a phone, and it is the one value in this row a person
         * will have an immediate opinion about. It is a single constant and every tolerance in the
         * class is either independent of it or scaled off it, so changing it is one line.
         */
        const val HOLD_MS = 500L

        /**
         * How far the pen may drift and still count as "still", in dp, multiplied by [density] at
         * the use site (R32). See Q4 — the draft claimed this was the same number as JB-2.03a's
         * long-press slop, and 2.03a says 6 *screen px*, unqualified.
         */
        const val STILL_DP = 6f

        /** Travel that doubles the shape, in dp — read from the brush swatch's own constant. */
        const val SIZE_PER_DOUBLING_DP = SizeOpacityDrag.SIZE_PER_DOUBLING_DP

        /** Smallest scale factor the resize may produce, whatever the drag says. */
        const val MIN_SCALE = 0.25f

        /** Biggest scale factor the resize may produce, whatever the drag says. */
        const val MAX_SCALE = 4f
    }
}
```

`SizeOpacityDrag` is `cc.joycreator.joybrush.core.tool.SizeOpacityDrag`, built and reviewed
(`SIZE_PER_DOUBLING_DP = 160f` at `SizeOpacityDrag.kt:159`). It is referenced, not copied — see
Decision 7.

## Decisions

1. **The hold is 500 ms of stillness, in ms and not dp, and it is a guess for the owner to tune.**
   Not configurable in v1: a slider nobody has an opinion about yet is a slider with one. The draft
   presented this number as if it had been derived ("the number Procreate and Concepts both feel
   like" — neither was measured, here or anywhere in this repo). It is now labelled a guess in the
   constant's own KDoc, because a number nobody derived is exactly the number a builder must not
   then treat as a requirement.
2. **"Still" is measured in SCREEN px, from the position the pen was last anchored at, and a
   movement past it re-anchors and restarts the clock.** One rule, used in `Idle` and `Waiting` (in
   `Shaping` a movement is the resize instead — Decision 10): a sample further than
   `STILL_DP × density` screen px away moves the anchor to that sample and sets the still-since time
   to its `timeMs`. Screen px because the tolerances in `ShapeRecognizer` are screen px and a circle
   must be recognised the same way at 25 % and at 400 % zoom.
3. **`tick` exists because a still pen sends no events.** See the KDoc. Without it the feature fires
   only on the next movement, which is the one thing the person is not doing — the draft's contract
   said in prose that "the shell passes `nowMs`" and then had no way to pass it.
4. **The recogniser runs ONCE per stroke, at the moment the hold completes, and never again.** It is
   O(points), and a 20 000-sample stroke re-recognising on every sample would be 20 000 recognitions.
   A stroke that comes back null is `Waiting` forever; it does not get a second look when it grows
   (Decision 5).
5. **A `Waiting` stroke is the ORIGINAL stroke, committed unchanged.** No message, no second look, no
   "did you mean…?". A person who held on a scribble gets their scribble back, which is what they
   drew. `Waiting` exists so a caller *may* show a subtle "listening" ring; nothing in this row draws
   one, and nothing may nag.
6. **The rough stroke is NOT withdrawn, and it is NOT replaced. R39, stated once.** While held, the
   caller draws the original samples as usual, so they are in the engine's stroke buffer, on the
   screen, uncommitted; the `Shaping` outline goes over the top. On the lift the caller cancels the
   in-flight stroke and feeds `lift()`'s list through the same path — one stroke, one undo step.
   **The draft said two incompatible things about this** — "the stroke so far has been WITHDRAWN from
   the screen" in the `Shaping` KDoc, and "the rough stroke is withdrawn from the ENGINE … rather than
   erased from it" in Decision 6 — and neither is what happens. `GlPaintEngine` has exactly the two
   doors this needs and no third: `endStroke()` commits the buffer into the layer as one
   `UndoLog.Step` (`GlPaintEngine.kt:374-403`), and `cancelStroke()` throws the buffer away without
   pushing an undo step (`:405-408`). A stroke in flight lives only in that buffer
   (`GlPaintEngine.kt:240-241`).
7. **Resize is horizontal travel → an exponential scale, through the brush swatch's own constant.**
   `2^(dxScreen / (160 dp × density))`, referenced from `SizeOpacityDrag.SIZE_PER_DOUBLING_DP` rather
   than retyped, so the brush swatch and a held shape cannot drift apart in feel. *Why in core and
   not in the view:* the draft's argument for sharing the constant was "nothing but a test would catch
   it", and a test cannot catch a constant that lives in a file it may not read. A vertical drag is
   ignored — there is nothing for it to mean, and it is not "rotate" (Decision 8).
8. **The resize range is 0.25× to 4× and it clamps silently at the ends.** It is a clamp and not a
   refusal on purpose: a drag past the end of a scale is a drag, not bad input, and unlike a size
   (JB-2.16a, which clamps too) there is no meaning attached to 4.01×.
9. **The fill pen is OFF, and the gate is on the brush's engine, not on the layer.**
   `enabled = engine != ENGINE_FILL`, where `ENGINE_FILL` is the landed
   `cc.joycreator.joybrush.core.brush.ENGINE_FILL = "fill"` (`BrushJson.kt:23`), compared by reference
   so the string is not retyped. The reason: a fill pen's stroke already IS a closed polygon
   (`BrushPreset.engine` documents it, and `InkReplay` returns an empty stroke list for it), so a
   "recognised" version of it is a lasso snapped to a shape — and a fill pen that waits 500 ms before
   filling feels broken. **If the owner wants snap-to-shape for the fill pen, that is a different
   feature** — recognise the closed outline, perfect it, then fill it — and it should be its own row,
   not a flag in this one. (This was Q2 of the draft, asked and answered; the answer is now a
   decision and a test.)
10. **Movement past the still-tolerance ends the hold in `Waiting`, and in `Shaping` only if it is
    VERTICAL-dominant.** The draft said "moving the pen by more than the still-tolerance ABANDONS the
    shape" and, three lines earlier, that resize is a horizontal drag — and those two are
    incompatible, because a drag of 160 dp is 160 dp of movement. The rule, exactly:
    - in `Idle` and `Waiting`, a sample past the tolerance re-anchors (Decision 2); in `Waiting` it
      also ends the hold, with no second look.
    - in `Shaping` there is **no re-anchoring at all** — a movement is only ever a resize or an
      abandonment. `abs(dy) > abs(dx)` **and** further than `STILL_DP × density` abandons; everything
      else, including a tie, is a resize by the horizontal part. The tie rule is the house one,
      `abs(dx) >= abs(dy)` → the drag wins (`SizeOpacityDrag.kt:100-101`), so a diagonal drag that is
      a resize in intent does not throw the shape away.
    - the shape is never re-decided once it exists (Decision 4).
    See Q2 — this is the one place where the behaviour a person sees changed.
11. **A sample that is not placeable is dropped and counted, never smoothed or used.** The house
    rule, from `StrokeSmoother` (`droppedSamples`, `StrokeSmoother.kt:121-137`): a stroke is a time
    series, a missing sample is ordinary, and the count is what stops "faithful" from meaning
    "silent". `HoldToShape` normally receives the smoother's output, which is already filtered, so
    this is a backstop — but a caller that wires the raw stream in would otherwise hand a NaN to
    `Geometry`, and a non-finite coordinate is permanent damage to a path.
12. **No second minimum.** Whether a stroke is long enough to be a shape is
    `ShapeRecognizer`'s business, not this class's: `MIN_POINTS = 5`, `MIN_LENGTH = 12.0` screen px
    (`ShapeRecognizer.kt:22-23`). Stated so a builder does not add a threshold of their own, and so
    nobody reads "smoothing 0 and three points" as a rule — it falls out of those two constants.

## Cut: what the caller does with all of this (the contract, not the look)

For the view row, so it is written down once and not twice. **This is not a design suggestion; it is
what Decision 7 and R39 already require.**

- **Pen-down:** `HoldToShape(view.zoom, resources.displayMetrics.density, preset.engine)`. Keep it for
  the stroke, exactly as `StrokeSmoother(amount, screenPerDoc = view.zoom)` is kept
  (`JbCanvasView.kt:306`).
- **Every sample:** `add(s)` after the smoother has released it, and draw it as usual.
- **Every event with no new sample, and every frame:** `tick(ev.eventTime)`. On the first frame where
  `state` is `Shaping`, the overlay draws `outline`; on `Waiting` it may draw a ring, or nothing.
- **Horizontal travel while `Shaping`:** `resize(dxFromHold)` and redraw the outline.
- **Pen-up:** `val s = h.lift()`, then `engine.cancelStroke()`, then feed `s` down the **same**
  `feed`/`paint` path with a fresh smoother, then `engine.endStroke()`. One `UndoLog.Step`.

## Tests

**`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/shape/HoldToShapeTest.kt`** —
`commonTest`, not `jvmTest`, and that is not a preference:

- JB-1.07's Decision 8 is the standing rule — a test that opens a file is a `jvmTest` test — and
  **nothing in this suite opens a file**. It is pure arithmetic, so it belongs beside
  `ShapeRecognizerTest` and `ShapePerfecterTest`, which is where the maths it builds on is tested.
- It also **needs** to be here: the fixture helper `StrokeMaker` is an `internal object` in
  `commonTest` (`commonTest/…/shape/StrokeMaker.kt:15`). An `internal` declaration is not visible
  from the `jvmTest` compilation, so a suite in `jvmTest` could not use the hand-drawn strokes the
  land already ships.
- The draft's header said `commonTest` and its Tests section said `:core:jvmTest`; they are the same
  command, and the file path in the owner area was the one that was wrong.

Every case, with the numbers derived:

1. **Never held.** 40 samples 16 ms apart at `(i * 3, i * 2)`, `screenPerDoc = 1f`. Each step is
   `hypot(3, 2) = 3.6` px, so after **two** steps the pen is 7.2 px from the anchor — past
   `STILL_DP = 6` — and the anchor re-anchors. The still time therefore never exceeds 32 ms and can
   never reach 500. Assert `state == Idle` after every `add` and after `tick(last + 500)`, and that
   `lift()` returns the input list `assertSame`-identical, not a copy. *(The fixture's step size is
   load-bearing: a "moving" stroke slower than 3 px per step would legitimately be still, the hold
   would fire, and this test would be asserting the wrong thing.)*
2. **The hold fires at 500 ms and not at 499.** Same stroke, but fed to a fresh instance as a settled
   line; `tick(anchorTime + 499.0)` → still `Idle`; `tick(anchorTime + 500.0)` → `Shaping` with a
   non-null `shape`. The comparison is `now − stillSince >= HOLD_MS`: at exactly 500 it fires, and a
   builder who writes `>` misses by one millisecond, which is the whole reason this case exists.
3. **Held and recognised.** A rough closed ellipse (fixture below) plus 500 ms of a pen at the same
   point → `Shaping`, and `lift()` returns a list of the **same length** whose positions differ from
   the input's. Same count is the load-bearing assertion: `ShapePerfecter.perfect` returns exactly
   `points.size` samples (`ShapePerfecter.kt:25, 39-42`), and a builder who resampled would break the
   pressure series without breaking anything else.
4. **Feel survives.** Every output sample's `pressure`, `tilt`, `azimuth`, `barrel`, `timeMs` and
   `tool` equals its input's, element for element. This pins the landed
   `ShapePerfecterTest.perfectingACircleKeepsEveryFeelChannel` again at this door, because this is
   where a builder would break it.
5. **Waiting then abandoning.** `StrokeMaker.scribble(3)` held 500 ms → `Waiting(stalledMs >= 500)`;
   then a sample 7 px away at density 1 (`STILL_DP = 6`, so 7 > 6) → `Abandoned`; `lift()` returns the
   **original** samples — not empty, not partial, not the outline.
6. **The recogniser runs once, ever.** A counting fake `(List<PenSample>, Float) -> Shape?` is injected
   through the constructor; a 5 000-sample stroke fed one sample at a time, with `tick` called between
   every two, produces **exactly one** call, and its arguments are the samples as they stood at the
   hold and `screenPerDoc`. A recogniser that returns null is still called exactly once — feed 200 more
   samples and `tick(60 000.0)` and the count stays 1 (Decision 4).
7. **Density.** At `density = 2f` the still-tolerance is 12 screen px, and the test is the CLOCK, not
   a state name: a sample 11 px from the anchor does **not** re-anchor it, so a `tick(anchorTime +
   500.0)` still fires; a sample 13 px away does re-anchor, so the same tick does **not** fire and
   `tick(thatSample.timeMs + 500.0)` does. The hold is still 500 ms at any density, because it is ms
   (Decision 1).
8. **Zoom invariance.** The same fixture at `screenPerDoc` 0.25, 1 and 4 recognises the same
   `Shape.Ellipse`: same centre and same `rx`/`ry` to within **1e-3 document px**, `rotation` exactly
   `0.0`. The tolerance is not sloppiness — `recognize` multiplies the points by `k` and divides the
   result by `k` (`ShapeRecognizer.kt:46, 66`), and that round trip is not bit-exact. Do **not**
   "fix" the maths to make an `assertEquals` pass; assert the tolerance and say why in the test.
9. **`resize`.** At `density = 1f`, `resize(160f)` doubles the shape's extent about its centre and
   `resize(320f)` is another doubling (`2^(320/160)`); `resize(-160f)` halves; `resize(-10 000f)` and
   `resize(10 000f)` clamp to `MIN_SCALE` and `MAX_SCALE` — 2^(10000/160) = 2^62.5, so the clamp is
   load-bearing and not decorative. `resize(Float.NaN)` and `resize(Float.POSITIVE_INFINITY)` return
   the outline **unchanged** — never empty, never a crash. And after any resize, `lift()`'s samples
   still carry the input's pressure/tilt series (test 4 again, through the resize).
10. **The resize constant is the swatch's constant.** Assert `HoldToShape.SIZE_PER_DOUBLING_DP ==
    SizeOpacityDrag.SIZE_PER_DOUBLING_DP` (both 160f today) and that at `density = 3f` the doubling
    takes 480 screen px. The first assertion is the only thing in the codebase that can catch the two
    drifting apart; the second is the density half of R32.
11. **Resize needs `Shaping`, and an abandoned stroke cannot be resized.** From `Idle` and from
    `Waiting`, `resize(1000f)` returns an empty list and leaves `state` alone. From `Abandoned` the
    same, and `lift()` still returns the original.
12. **Horizontal resizes, vertical abandons** (Decision 10), in `Shaping`, at `density = 1f` — five
    movements from the point the hold completed, one test, because the *tie* is the case a builder
    gets wrong and it is the one `SizeOpacityDrag` already had to settle. `|dy| > |dx|` and
    `hypot(dx, dy) > 6` together abandon; anything else is a resize of the horizontal part.

    | movement | `abs(dy) > abs(dx)` | distance | result |
    |---|---|---|---|
    | `(+20, +5)` | no | 20.6 | **resize** by +20 |
    | `(+20, +20)` | no (tie, and `abs(dx) >= abs(dy)`) | 28.3 | **resize** by +20 |
    | `(+2, +5)` | yes | 5.4 (inside the tolerance) | **resize** by +2, no abandon |
    | `(+5, +20)` | yes | 20.6 | **`Abandoned`** |
    | `(+0, +40)` | yes | 40 | **`Abandoned`**, and `lift()` is the original stroke |
13. **The fill pen is off.** `HoldToShape(engine = ENGINE_FILL)`: `enabled` is false, every sample
    leaves the state `Idle`, `tick` never leaves `Idle`, and `lift()` returns the input. The same
    stroke with `engine = "stamp"` is `Shaping` (Decision 9).
14. **Garbage in.** A `PenSample(NaN, 0f, 0.0)` and a `PenSample(0f, 0f, Double.NaN)` are both dropped
    and `droppedSamples == 2`; the stroke still recognises and `lift()` still returns every good
    sample. Assert the second half with the counting fake from test 6 — the argument it captured
    contains neither bad sample — so "dropped" is proven, not assumed.
15. **A guard value cannot poison the class.** `density = 0f`, `density = -1f`, `density = NaN` and
    `screenPerDoc = 0f` are all treated as 1f, the same guard `SizeOpacityDrag` applies to its own
    `density` (`SizeOpacityDrag.kt:58`) — otherwise a zero density divides by zero in the tolerance
    and a zero zoom divides by zero in the recognition, and both would show up as a stroke that
    silently never becomes a shape.

**Fixture (named in the test file, not retyped per test):** the rough circle is
`StrokeMaker.ellipse(cx = 300.0, cy = 300.0, rx = 120.0, ry = 120.0, rotationDeg = 0.0, turns = 1.0,
seed = 7)` — hand-drawn (≈1.5 px resample, ±1.5 px jitter, a 2 px wobble, pressure 0.3 → 0.9 → 0.4,
tilt rising 0.2 → 0.7), which is what makes test 4 worth running; 2π × 120 ≈ 754 px of path is about
503 samples, well past the recogniser's `MIN_POINTS = 5` and `MIN_LENGTH = 12`. The "moving" stroke in
tests 1 and 2 is 40 samples of `(i * 3, i * 2)` at `timeMs = i * 16.0`, and why its step size is
load-bearing is in test 1. The scribble in test 5 is `StrokeMaker.scribble(seed = 3)` — a 200-step
random walk, which the recogniser answers with `null` (its RDP reduces to far more than two vertices,
so it is neither a line nor an arc); assert that null, so a future change to `ShapeRecognizer` that
starts recognising scribbles turns this red instead of silently changing the test's meaning.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. (Run it in a worktree of your own,
per R43; never on the owner's PC.)

## Do not

- **Do not touch JB-2.10's thresholds or files.** `ShapeRecognizer`, `ShapePerfecter`, `Geometry` and
  `Shape` are landed and reviewed. If a test cannot pass with them, write it in Questions and stop —
  that is JB-2.10's own "Do not".
- **Do not edit `JbCanvasView.kt`, `GlPaintEngine.kt` or any other file.** The owner area is two new
  files. The view half is JB-2.11b (R30 item 2, Lead only) and this row is dispatchable precisely
  because it does not touch them.
- **Do not add a clock, a view, an Android import, or a settings UI.** Pure arithmetic on numbers.
- **Do not commit anything to the engine, cancel anything, or call into `GlPaintEngine`.** The
  "one stroke, one undo step" rule is a rule about what the CALLER does with `lift()`; the class never
  learns that an engine exists (Decision 7).
- **Do not re-type `160f`.** `SizeOpacityDrag.SIZE_PER_DOUBLING_DP` is the constant (Decision 7).
- Do not re-type `"fill"`. `ENGINE_FILL` is the constant (Decision 9).
- Do not make the hold configurable, add a "was this a shape?" message, or add a rotate gesture.
- Do not fit in document px — every tolerance here is screen px, divided by `screenPerDoc` exactly
  the way `ShapeRecognizer` divides it.
- Do not put a file-reading test here. If you need one, it is a `jvmTest` test and it does not
  belong to this row.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] `git status --short` shows exactly the two owner-area paths, both `A` (added), nothing else
- [ ] `HoldToShape` has no import outside `core` (check: the only imports are `ENGINE_FILL`,
      `PenSample`, `SizeOpacityDrag` and `kotlin.math`)
- [ ] committed `JB-2.11: hold-to-shape core (hold, decide, resize)`
- [ ] ROADMAP row set by the Lead (this row does not edit ROADMAP.md)

## Stop rule

**Stop and write in Questions, do not guess, if any of these is true when you start:**

- `ShapeRecognizer.recognize`, `ShapePerfecter.perfect` or the `Shape` subclasses do not have the
  signatures pasted above. A different `Shape` case set means this class's `resize` needs a fifth
  branch, and *which* five is a design decision.
- A test needs `STILL_DP`, `HOLD_MS`, `MIN_SCALE` or `MAX_SCALE` to be anything other than the
  constant's value. These are the owner's to tune (Q1), not a builder's.
- The resize cannot be expressed as a horizontal screen-px travel from the host's existing gesture
  stream. That is a JB-2.11b problem and the answer is a question, not a new API here.
- You believe the abandonment rule in Decision 10 is wrong. Say so with the gesture that breaks it.
  Do not implement your own reading — the old rule and the resize were mutually exclusive and that
  is why this one was written down.

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. Two are new and one is a change
of behaviour; the rest of the draft's questions are answered or moved.)_

### 🔴 For the Lead

1. **500 ms is a guess for the owner, and R39 says so.** It is now labelled as one in the constant's
   KDoc. Nothing in this repo derives it and I have not measured Procreate or Concepts; the draft
   said "500 ms is the number Procreate and Concepts both feel like", which is a claim about two
   applications that was never checked. **If the owner has a preference, it is one line.** If not, it
   is the first number the Note 9 check in JB-2.11b should argue about, and I would rather it shipped
   as an open guess than as a hidden one.
2. **Movement past the still-tolerance can no longer abandon in every direction (Decision 10) — this
   is a behaviour change from the draft, and the draft's owner check promised the old one.** The
   draft said, in the same breath, that a horizontal drag resizes the shape and that moving the pen
   abandons it. A 160 dp drag to double is 160 dp of movement, so as written **the resize could never
   happen**. I ruled: horizontal = resize, vertical-dominant (with the house tie rule) beyond the
   tolerance = abandon, and the shape is never re-decided. The draft's owner check said "keep holding
   and wiggle → the rough circle comes back"; under this rule a side-to-side wiggle resizes instead
   and only a downward or upward move brings the rough stroke back. **The alternative, if you prefer
   the old feel, is a dedicated abandon gesture (a long-press, or a two-finger tap), and that is a
   view decision JB-2.11b would answer — say so and I will re-scope this decision.**
3. **The fill pen stays off, as a decision rather than a question** (Decision 9, R39). The draft
   asked; R39 answered. Kept here so the reason is on the record: a fill pen's stroke is already a
   closed polygon, so recognising it produces a lasso snapped to a shape, and a fill that waits 500 ms
   feels broken. If the owner wants snap-to-shape for the fill pen it is its own row, not a flag.
4. **The 6 dp still-tolerance does not match JB-2.03a, and the draft said it did.** 2.03a's spec
   (`JB-2.03a_colour_pill_and_eyedropper.md:43,47`) says "a press that moved > **6 px** before 450 ms
   is a stroke as always" and "moved < 6 screen px → eyedropper", unqualified by density, and 2.03a
   has not been built, so there is no constant in the tree to share. On a density-3 phone this row's
   tolerance is 18 px and 2.03a's would be 6. The draft called this "deliberately the same number:
   both are 'has the person committed to this gesture' thresholds, and two different values for one
   question would be two answers" — which is a good argument and is **not satisfied today**. Two
   ways to satisfy it: 2.03a becomes density-correct (6 dp), or this row adopts 2.03a's 6 screen px
   and loses the zoom/density invariance everything else here has. I kept the dp version (R32) and am
   flagging the disagreement rather than resolving it in a file that is not mine.
5. **Does JB-2.11b exist yet, and is it yours?** Everything in **Cut from this spec** lands there.
   It is `JbCanvasView.kt` plus one overlay View, and R30 item 2 makes both the Lead's. If the Lead
   would rather own the wiring as part of JB-2.01's chrome, say so and the cut is the same either
   way — but the row should be named on the board, or the core half ships with nothing drawing it.
6. **The preview's look — 60 % over the rough stroke, or replace the rough stroke outright** — was Q3
   of the draft and is **JB-2.11b's** question, not this row's. Noted here so it is not lost: the
   draft chose the overlay because the alternative loses the "this is what you drew, this is what it
   became" comparison that makes the feature legible, and cheap either way.

### Low-risk, ruled provisionally (PROVISIONAL — Claude to confirm)

7. **The type name `HoldToShape` and the file location** (`core/shape/HoldToShape.kt`, beside the two
   classes it drives). JB-2.10's KDoc already calls itself "Hold-to-shape, part 1" and
   `ShapePerfecter` "part 2", so this is part 3 of a named feature rather than a new invention.
8. **`tick(nowMs: Double)` rather than an injected clock** (Decision 3), because a lambda clock makes
   the class untestable in the way every other class in this module is tested. Reversible: it is one
   parameter.
9. **The recogniser seam as a constructor parameter** with `ShapeRecognizer::recognize` as the
   default, so "runs once" is a test rather than a comment. Same shape as `SizeOpacityDrag`'s
   injected collaborators.
10. **Dropped samples are counted, not refused** (Decision 11), following `StrokeSmoother` exactly.
