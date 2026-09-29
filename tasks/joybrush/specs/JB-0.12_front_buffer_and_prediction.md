# JB-0.12 — Low-latency front buffer + motion prediction, with an off switch

| | |
|---|---|
| **Tier** | T1 (Half A is pure maths and any strong builder can write it; **Half B is the Lead's own work and must not be dispatched** — see the header note and Q5) |
| **Status** | 🟨 Draft — **four Lead rulings are outstanding (Q1–Q4) and the Android half cannot be written without them.** Half A is ready to build the moment the row is confirmed |
| **Needs** | 0.07 |
| **Owner area** | *(Half A)* NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/input/Latency.kt`, NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/input/LatencyTest.kt` · *(Half B — Lead only, and NOT until Q1–Q4 are ruled)* `joybrush/androidkit/.../gl/GlFrontBuffer.kt`, EDIT `joybrush/androidkit/.../JbCanvasView.kt`, `JoyBrushActivity.kt`, and the app build's dependency block |
| **Estimated size** | Half A ~160 lines + ~180 lines of tests · Half B ~400 lines, unestimable until Q1 |

> **Why this row is a Draft and not a Ready.** The maths and the policy are decidable and are
> written out in full below. What is not decidable by anyone but the Lead: **which** low-latency
> mechanism to take (a new AndroidX dependency in the app build, or hand-rolled EGL), **where** the
> on-screen switch goes and what it is called, what happens on a device below API 29, and whether
> replacing `GLSurfaceView` is acceptable while `JbCanvasView` is contended by two held patches.
> Each of those changes what the code is, not how it is written. Do not dispatch this row as it
> stands.

## Goal

Blueprint §3.4: *"Low latency: Google's front-buffer renderer (works from Android 10, so both Notes)
+ stroke prediction, with a switch to turn either off."*

Right now a mark appears on the glass one to two frames after the pen is there, which is the whole
difference between a drawing app that feels like glass and one that feels like a form. This spec
builds the two halves of that: **predict** where the pen is about to be and draw there, and **show
the newest dabs before the compositor's next vsync**. And it builds the off switch, because R7's
risk 2 is explicit that front-buffer rendering is the most device-specific thing in the whole plan
(graphics-core's own release notes are a list of per-device flicker fixes) and a phone that flickers
must be fixable by a person, in a setting, without a new build.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.input

/** What the drawing surface is allowed to do, chosen by a person. Both halves have a switch. */
data class LatencySettings(
    /** The front buffer: draw the newest increment before the compositor's next vsync. */
    val frontBuffer: Boolean = true,
    /** Draw points the pen has not reported yet. */
    val predict: Boolean = true,
)

enum class LowLatencyPath { MULTI_BUFFERED, FRONT_BUFFERED }

/** When a path may be used at all, and which one the settings choose. */
object LatencyPolicy {
    /** The front buffer needs Android 10 (API 29). Below that there is no such path. */
    const val FRONT_BUFFER_MIN_API = 29

    /** Front-buffered rendering is for the STROKE INCREMENT only. */
    fun pathFor(settings: LatencySettings, apiLevel: Int, drawing: Boolean): LowLatencyPath

    /** Why a request was refused, or null. Every sentence is for a person, not a log. */
    fun refusalFor(settings: LatencySettings, apiLevel: Int, drawing: Boolean): String?
}

/**
 * The carried pen state, for the two grain/smudge uniforms a stroke needs and the dab geometry
 * needs: how far the pen has leaned, and which way it leans. [Predicted] samples carry the last
 * REAL values, so a predicted dab's grain looks like the dab it is pretending to be.
 */
data class PenState(val tiltAmount: Float, val leanX: Float, val leanY: Float)

object LatencyPredictor {
    /** How far ahead to draw: one frame at 125 Hz. R7: more than one frame overshoots on a flick. */
    const val HORIZON_MS = 8.0

    /** The longest a predicted point may be from the last real one, in SCREEN px (R10's two units). */
    const val MAX_OFFSET_SCREEN_PX = 32f

    /** The real samples of the stroke so far, oldest first, and [screenPerDoc] = `view.zoom`. */
    constructor(screenPerDoc: Float)

    /** Feeds one REAL sample. Never call this with a predicted one (Decision 5). */
    fun addReal(sample: PenSample)

    /** The points to draw ahead of the pen right now. Empty when prediction is off or unusable. */
    fun predicted(count: Int = 1, enabled: Boolean = true): List<PenSample>

    /** How many predicted points are still on the glass, and where the real pen is. */
    fun outstanding(): Int

    /** A real sample arrived: erase that many predicted points, and reset the extrapolation. */
    fun onRealSampleArrived()

    /** The pen state the NEXT dab should carry. */
    fun penState(): PenState

    fun reset()
}
```

## Steps

1. Write `LatencyTest.kt` from the Tests section, first.
2. Write `Latency.kt` until `:core:jvmTest` is green. **This is the whole of Half A and it is
   complete on its own.**
3. Stop. Half B is the Lead's, and Q1–Q4 are answered first.

## Decisions

1. **The maths and the policy live in `core`; the Android half lives in `androidkit`.** The
   interesting, breakable parts of low-latency drawing are *policy* — when may I use the fast path,
   what counts as a real sample, how far ahead may I guess — and all of it is arithmetic over
   numbers. Putting it in `core` means it is tested in the cloud, on any machine, forever, and the
   Android half becomes a thin shell that can only be wrong in ways the tests cannot see.
   This is the same split JB-3.05a and JB-2.16a use, and the same reason: the phone judges feel, not
   arithmetic.

2. **`screenPerDoc` is `ViewTransform.zoom`, and the cap is a SCREEN distance converted to document
   px.** This is LEAD_RULINGS R10's distinction stated in full, because a spec that does not say it
   gets it backwards exactly as often as not: the caller passes `view.zoom` (**not** `1f / view.zoom`),
   the pen's samples are in **document** px, and the extrapolation cap
   `MAX_OFFSET_SCREEN_PX / screenPerDoc` is therefore a document-px distance. A non-finite or
   non-positive `screenPerDoc` is read as 1, the identical guard and the identical reason as
   `Nudge.stepDoc` and `SizeOpacityDrag` — a view that has not been laid out yet gets the
   conservative answer rather than a division by zero.

3. **The predictor is constant velocity over the last two REAL samples, with a one-frame horizon.**
   `v = (last − previous) / dt` in document px per second, and the predicted point is
   `last + v · (HORIZON_MS / 1000)`, clamped in LENGTH to `MAX_OFFSET_SCREEN_PX / screenPerDoc`.
   Constant velocity, not a Kalman filter, not a spring: R7 records that AndroidX's predictor is a
   Kalman filter that *delegates to a TFLite platform model on API 34+*, and the Note 9 is API 29
   and will never be 34, so the platform path is unreachable for the owner's own hardware. If the
   Lead takes the AndroidX library (Q1) this class is deleted rather than kept, which is why it is
   small and separately testable. The cap exists because R7's own note is that over-predicting
   overshoots at direction changes: a clamped prediction is wrong by a few pixels for one frame,
   and an unclamped one draws a hook.

4. **Fewer than two real samples, or no usable `dt`, predicts nothing.** A stroke's first sample has
   no velocity. A `dt` of zero or less (a duplicate or reversed timestamp — a real thing at 240 Hz
   with millisecond timestamps, R7 §1.2) keeps the **previous** velocity rather than dividing; on
   the very first pair there is no previous, so there is no prediction. A sample that is not
   placeable (`isPlaceable` false — JB-0.01's contract) is **not fed to the predictor at all**, for
   the same reason `StrokeSmoother` drops it: a NaN in the carried position is permanent and would
   poison every point for the rest of the stroke. A stroke's `predicted` samples are the only thing
   that may carry a NaN, and they never do.

5. **A predicted sample is never fed back.** `addReal` refuses a sample with `predicted = true`, in
   words (`require` / `IllegalArgumentException`, naming the reason), rather than ignoring it
   silently. Feeding a prediction back is how a predictor runs away: each invented point becomes
   the base for the next, and a 32 px cap becomes a 32 px step per frame forever. `PenSample`
   already carries the `predicted` flag for exactly this, and its own KDoc says predicted points
   "may be DRAWN for latency but are never stored in a stroke recording" — this is the code that
   makes that sentence true rather than aspirational. **A predicted sample is never written into a
   `StrokeRecord`, never becomes a `Dab`, and never reaches a tile** — R7's rule, and it is
   Decision 5 plus Half B's first bullet that together make it hold: the predictor can only be fed
   real samples, and the front layer is the only place a prediction is ever drawn.

6. **Every predicted sample carries `predicted = true`, and the twin of the last real sample's
   channels.** Pressure, tilt, azimuth and tool are copied from the last real sample rather than
   re-estimated: an invented pressure would put a wrong value in a brush's dynamics, and a wrong
   pressure on a predicted dab is a visible brightening at the tip of a stroke. Copying the pen's
   state is what a person's eye reads as "the same line, drawn sooner".

7. **The two switches are independent, and the front buffer is only for a stroke in progress.**
   `LatencyPolicy.pathFor` returns `FRONT_BUFFERED` only when **all** of: the setting is on, the
   device is API ≥ `FRONT_BUFFER_MIN_API`, and a stroke is in progress. Otherwise `MULTI_BUFFERED`.
   R7 records the constraint as confirmed: front-buffered rendering is for small incremental
   regions, and full-screen updates on the front buffer **tear** — so pan, zoom, undo, a layer
   change, a surface change, a resize and the end of a stroke all go multi-buffered. This is the
   single most important line in the spec, because getting it wrong is a visibly broken screen, not
   a slow one.

8. **Refusals are sentences for a person, and there are only three.** `refusalFor` returns exactly:
   - `"this phone is too old for the fast drawing mode"` when `apiLevel < 29` and the setting is on;
   - `"fast drawing is off"` when the setting is off;
   - `"fast drawing is only used while you are drawing"` when no stroke is in progress.
   `null` means the front path will be used. Nothing else is ever refused, and a refusal is never
   an exception: turning the fast path off must never be able to stop a person drawing.

9. **`onRealSampleArrived` erases what is on the glass.** `outstanding()` is the count of predicted
   points the caller has drawn and not yet replaced; every real sample decrements it to a floor of
   0, and the extrapolation restarts from the new last real sample. R7 quotes the rule from the
   AndroidX docs: *"predicted points are temporary; draw them only in the front layer/overlay and
   erase them when real events arrive; never commit."* It is stated as a **count** rather than as a
   "the last point was wrong" flag because a prediction that guessed a curve is several points out
   of date, not one, and a one-point flag leaves a visible tail on every flick.

10. **`penState()` is the pen state the next dab carries, and it is always finite.**
    `tiltAmount = sin(tilt)` with a non-finite `tilt` read as 0 (the same NaN row as JB-1.05c's
    Decision 1 — a pen with no tilt sensor is *upright*, which is what `tilt = 0` already means, so
    the fallback is the truthful one and not a made-up number); `leanX/leanY = (cos azimuth, sin
    azimuth)` with a non-finite azimuth read as `(0, 0)`. A predicted sample's `penState` is the
    last **real** sample's. This is the single object that both the grain uniforms (JB-1.05c) and a
    future smudge engine read, so "what does the shader think the pen is doing" has one answer.

11. **Half B, decisions that do NOT need a ruling** (recorded so the Lead's half is short):
    - The kill switch must be reachable **in the app**, at runtime, with no rebuild — R7 risk 2 and
      blueprint §3.4 both require it, and graphics-core's per-device flicker history is the reason.
    - The predicted points are drawn **only** in the front layer and never committed
      (Decision 5, 9).
    - A smudge or wet brush gets **no front-layer preview** while this build cannot read the canvas
      in the front buffer: `engine: "smudge"` / `"wet"` always take the multi-buffered path, where
      the live stroke is already previewed correctly. (Provisional, orchestrator's rule: R7 notes
      the front layer is exact only for normal-blend non-mixing brushes, and an *approximate*
      preview for a smudge is a moving smear that is wrong — worse than one frame late.)
    - The multi-buffered path must stay **completely working** with both switches off. That is the
      state the app ships in if `FRONT_BUFFER_MIN_API` fails, and it is what the owner sees on a
      phone where the fast path flickers, so it is not a fallback to be tested once.

## Tests

`LatencyTest.kt`, in `:core:jvmTest`. **These are the tests that make Half A provable, and they
cover every Decision above.**

1. **Policy, on/off, all four combinations.** `FRONT_BUFFERED` only when `frontBuffer = true`,
   `apiLevel = 29`, `drawing = true`. The other seven combinations of the three inputs give
   `MULTI_BUFFERED` — including `apiLevel = 28` with everything else on, and `apiLevel = 30` with
   the setting off, and `apiLevel = 30`, setting on, `drawing = false`.
2. `FRONT_BUFFER_MIN_API == 29`, and `refusalFor` returns the three sentences of Decision 8 in the
   three refusing cases and `null` in the one accepting case, by exact string.
3. **A finger's own NaN channels do not poison prediction.** A stroke fed samples with
   `tilt = NaN, azimuth = NaN` (a finger, by `PenSample`'s contract) predicts finite points at every
   step, and `penState()` is `(0, 0, 0)`. **This is the same shape as the `jb_grainLevel` NaN row
   both JB-1.02 reviewers demanded, and it is here for the same reason: a predicted point carrying
   a NaN would be the first NaN the GPU has ever seen from this code.**
4. **The extrapolation.** Two real samples 10 document px apart, 10 ms apart, at `screenPerDoc = 1`:
   the predicted point is at `x = last + 10 · 0.008` px and `y` unchanged. Asserted to 1e-4, with
   the arithmetic written into the test.
5. **The cap.** The same pair at `screenPerDoc = 1` with a 10 000 px/ s stroke: the predicted point
   is exactly `MAX_OFFSET_SCREEN_PX` document px from the last real one, not 80 px. At
   `screenPerDoc = 4` the same stroke's cap is `MAX_OFFSET_SCREEN_PX / 4` document px — the R10
   direction, pinned.
6. **Guards.** `screenPerDoc` of `0`, `-1`, `NaN` and `+Inf` each give the same answer as `1`.
   `dt ≤ 0` (two samples with the same timestamp, and with a reversed one) gives no prediction on
   the first pair and, after a usable pair, keeps the previous velocity exactly.
7. **No runaway.** Feed 50 real samples of a straight line, asking for 2 predicted points after
   each. Every predicted point is within `MAX_OFFSET_SCREEN_PX / screenPerDoc` of the last real
   sample, and the predicted points' positions are a *decreasing* distance from the pen (they are
   all extrapolations from the same base, not from each other). Add `addReal(predictedSample)` and
   assert it throws with a message naming `predicted`.
8. **A predicted sample is marked and inherits the pen.** `predicted(count = 3)` gives three
   samples, every one with `predicted = true`, and with the last real sample's `pressure`, `tilt`,
   `azimuth` and `tool` — including the NaNs of test 3, copied rather than replaced.
9. **Erasing.** `predicted(2)` then `onRealSampleArrived()` → `outstanding() == 0`; the same
   prediction then three arrivals → still `0`, never negative. And the next `predicted()` is
   extrapolated from the **new** last real sample, not the old one (assert the base point).
10. **An unplaceable sample is not fed.** A sample with `x = NaN` is dropped by `addReal` (assert
    `outstanding` and the base point are unchanged), and the samples after it still predict
    normally — the JB-0.01 rule that a corrupt recording cannot delete the rest of a stroke.
11. `reset()` empties the predictor: after a reset, `predicted()` is empty and `outstanding()` is 0.
12. **`penState()` is finite for every combination**, swept: tilt in `{0, π/4, π/2, NaN, +Inf, -Inf}`
    × azimuth in `{0, −π, π/2, NaN, +Inf}` → 30 results, all finite, `tiltAmount` in `0..1` and
    `leanX² + leanY²` within 1e-6 of 1 (or exactly `(0,0)` for a NaN azimuth).

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

### The device check (T3, the owner — not automatable and not promised here)

With the switch on, a fast flick shows the line reaching the pen tip. With it off, the same flick
shows a gap of one to two frames at the tip. That contrast **is** the evidence; a single run cannot
tell "low latency" from "a fast phone". R7 notes there are no published independent measurements of
`GLFrontBufferedRenderer` on the Note 9, so this has never been verified on the owner's hardware by
anyone. If the fast path flickers on his phone, the switch is the whole remedy and it must be
reachable without a new build.

## Do not

- Do not let a predicted point reach `StrokeRecord`, a `Dab`, or any tile. It is glass, not paint.
- Do not use the front path for a full-screen change (Decision 7). That is how you get tearing.
- Do not record anything the platform can give you. The owner's floor is the Note 9 (API 29), and
  R7 §1.2 is explicit that on API < 34 the timestamps are millisecond-resolution with jitter, which
  is why Decision 4 filters velocity instead of differencing one sample.
- Do not make the fast path required. Every feature here has a switch, and the app is correct with
  both off.
- Half B touches `JbCanvasView.kt`, which is **contended**: R10 Q6 holds a one-line frame-snapshot
  fix there, and R13 holds `gestures.handOver()`'s call in
  `tasks/joybrush/held/JbCanvasView_lead.patch`. Do not start Half B while either is outstanding.

## Definition of done

- [ ] Half A: `./gradlew -p joybrush :core:jvmTest` green (paste the summary).
- [ ] `git status --short` shows only the two Half-A files.
- [ ] Committed as `JB-0.12: low-latency policy and prediction maths`, pushed.
- [ ] Half B: **only after Q1–Q4 are ruled**, and then only by the Lead.

## Questions

_(Spec writer, 2026-09-29. `⚪ Outline` row: "JB-0.12 Low-latency front buffer + motion prediction,
with an off switch | T1 | 0.07". Half A is finished and needs no answer to build; Half B is not
writable without these four.)_

**Q1 — the dependency, or hand-rolled EGL. This is the whole architecture of Half B.** Two routes:

- **(a) AndroidX.** `androidx.graphics:graphics-core` (1.0.4, stable) for
  `GLFrontBufferedRenderer`, and `androidx.input:input-motionprediction` (1.0.0, stable) for
  `MotionEventPredictor`. Both are Apache-2.0, compatible with GPL-3.0. Both are *new dependencies
  in the app build*, which is app-file work under the serialised order. R7 also notes
  `GLFrontBufferedRenderer` **takes a `SurfaceView` and owns its own GL thread** — it cannot be
  bolted onto the existing `GLSurfaceView`, so route (a) means rewriting `JbCanvasView`'s renderer
  and lifecycle. AndroidX's predictor is a Kalman filter that delegates to a TFLite platform model
  on API 34+, and the Note 9 is API 29 and will never be 34, so the phone gets the generic Kalman
  path.
- **(b) No new dependency.** Keep `GLSurfaceView` and set
  `EGL_CONTEXT_CLIENT_VERSION` / swap behaviour for front buffering by hand, and use
  `LatencyPredictor` (this spec's own class) for the prediction.

I have written the spec so that **(a) and (b) share all the policy** — Decisions 1–10 are the same
either way, and `LatencyPredictor` is deleted rather than kept if you take (a). That is deliberate:
it means ruling Q1 does not invalidate the rest. But it *does* decide the size and the risk of Half
B, so I have not guessed it.

**Q2 — the switch is an on-screen promise, and I will not invent it.** Blueprint §3.4 says "a switch
to turn either off" and R7 says "keep a runtime kill-switch (Infinite Painter ships
Disabled/Active/Fastest)". Those are two different shapes: **one toggle for the whole feature**, or
**two independent toggles**, or a **three-state latency mode** (Off / Active / Fastest) like the app
the owner names as his reference. The choice decides whether `LatencySettings`'s two booleans are
the product or an implementation detail — and if it is the latter, the on-disk settings shape and
the settings screen are a different spec. My provisional rule is the two independent booleans,
because "the front path flickers on my phone but prediction is lovely" is a real and separate
complaint, and one toggle cannot answer it. Override it and `LatencySettings` changes shape.

**Q3 — what a phone below API 29 sees is a product decision, not a `?:`.** `FRONT_BUFFER_MIN_API =
29` is a hard platform fact and the Note 9 qualifies. But the app's `minSdk` is 24, so a 24–28
device exists in the supported set, and R7 risk 8 says "ensure a normal double-buffered path is
solid" for it. The options: hide the setting entirely below 29 (the app is simply the same app, one
frame later); show it greyed out with the sentence `refusalFor` already returns; or ship the
multi-buffered path everywhere and treat the fast path as an enhancement with no setting at all
(R7's "treat as an enhancement with no UI"). I have written `refusalFor`'s sentence for the second
option and defaulted `frontBuffer = true`, which means **today's code would offer a feature to a
device that cannot have it** — that default is wrong on at least one of the three answers, so tell
me which and I will change one line.

**Q4 — is replacing `GLSurfaceView` acceptable while `JbCanvasView` is contended?** R7 is explicit
that `GLFrontBufferedRenderer` owns its own `GLRenderer` and cannot be used with a `GLSurfaceView`,
so route (a) in Q1 is a rewrite of the view's renderer, its lifecycle, `onGl { queueEvent }`, and
the `viewSnapshot`/`drawView` handoff that R10's frame-snapshot fix is about to touch. Two patches
are already queued for that file (R10 Q6, R13's `handOver()`). Options: land both held patches
first, then rewrite; or keep `GLSurfaceView` and take route (b). I have not chosen, because the
answer depends on Q1 and on when the two held patches land — neither of which is mine to decide.
