# JB-3.05 — Playback: the frame stepper (the pure half)

**xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — the Lead's review fixes applied: test 3's
expected ping-pong sequence corrected to **six** frames (`0 1 2 3 4 5 4 3 2 1 0 …`, at most
`2n − 2` = **10** `ShowFrame`s a cycle) with the arithmetic written into the test; Decisions 13–14
(the play/pause pill and the loop chip) **deleted** under R33 — the peg bar owns PLAY and MODE, the
film strip has prev/next and nothing else, and there is one saturated control; the audio half
scoped out to **JB-0.02c**; owner area cut to two `:core` files. Re-deriving the contract from the
**landed** `PlaybackClock` rather than from the spec's restatement of it found two more defects in
the earlier draft: `Finish` cannot be emitted "exactly once" by a function of its three arguments,
and `nextWakeMs` needed a floor **above** `elapsed` because the clock answers `nextChangeMs(t) == t`
bit-for-bit at every backward boundary (Decision 7, and the test that proves it).

| | |
|---|---|
| **Tier** | T2 (one pure `:core` file and its test. No Android, no app file, no phone, no gradle task but `:core:jvmTest`) |
| **Status** | 🟦 **Ready.** The audio half is **not in this row** — it waits for **JB-0.02c** (R33), which owns the document field it needs. The transport (buttons, `Handler`, `MediaPlayer`) is **not in this row** either, for two reasons stated in *Boundaries* below: R30 item 1 puts `JoyBrushActivity.kt` in a one-row-at-a-time lock order, and `androidkit` has no Robolectric, so a `Handler` loop could not be tested. |
| **Needs** | 3.05a (`PlaybackClock` — 🟧 Built, green) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FrameStepper.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FrameStepperTest.kt` — **these two files and nothing else** |
| **Estimated size** | ~150 lines of Kotlin, ~300 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |
| **Naming** | The ROADMAP row is **JB-3.05**. JB-3.05a's Questions §4 calls this row "JB-3.05b" (3.05a being the clock). **One name only: the file is the ROADMAP row's number.** |

## What this row is, in one paragraph

`PlaybackClock` (JB-3.05a, built) already answers "which frame is up at elapsed time *t*",
"where should the sound be", "have we finished" and "when does the frame next change". What it
deliberately does **not** do is compare that answer with **what is actually on the screen**, and
that comparison is the whole of this row. `FrameStepper` is a pure function that takes the frame
the caller believes is up and returns a list of *things to do* — draw this frame, move the sound to
here, stop — and it never tells a caller to draw the frame that is already there. One parameter
(`frameIndex`) is the entire fix for the spin that a player otherwise walks into at every backward
boundary, because a caller cannot ask "what should I draw?" without also saying "here is what is
drawn".

## The trap this spec is built around, and what the landed clock actually does

`PlaybackClock.nextChangeMs` is, at a backward boundary, the **infimum** of the times at which the
frame differs — not a minimum. Read the landed code and it is exact and deliberate:

```kotlin
fun nextChangeMs(elapsedMs: Double): Double {
    if (count == 1) return Double.POSITIVE_INFINITY
    val clean = cleanElapsed(elapsedMs)
    val board = clean * rate
    if (mode == PlayMode.ONCE && board >= rangeMs) return Double.POSITIVE_INFINITY
    val cyclePos = board % periodMs
    val boundary = if (cyclePos < rangeMs) {
        nextForwardBoundaryAfter(cyclePos)
    } else {
        nextBackwardBoundaryAtOrAfter(cyclePos)
    }
    return clean + (boundary - cyclePos) / rate
}
```

Asked **exactly at** a backward boundary — the seam, or any interior falling edge — the difference
`(boundary − cyclePos)` is exactly `0.0`, so the answer is **bit-for-bit the elapsed that was passed
in**. `PlaybackClockTest.everyBackwardBoundaryIsExactlyItsOwnNextChange` pins that; it is a promise,
not an accident. And at that same instant `frameIndexAt` is still showing the **outgoing** frame
(`slotOnTheBackwardLeg` returns `k` when `edge == cyclePos`). So at a backward boundary, all three of
these are true at once:

* the frame on screen is still the outgoing one, so a comparison-based step emits **nothing**;
* `nextChangeMs` says the next change is **now**;
* a player that sleeps "until nextChangeMs" and re-asks at the *same* number gets the same answer
  for ever.

That third bullet is the spin, and a millisecond-resolution elapsed clock (`SystemClock.uptimeMillis`,
which is what a `Handler` delay is measured in) makes it a real one rather than a theoretical one.
**Decision 7 is the answer**, and it is this row's other load-bearing decision.

## Contract (verbatim)

**The clock this is written against**, pasted from the landed
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PlaybackClock.kt`
(🟧 Built). Its public surface, in full:

```kotlin
package cc.joycreator.joybrush.core.anim

enum class PlayMode { LOOP, PING_PONG, ONCE }

class PlaybackClock(
    board: Board,
    val mode: PlayMode = PlayMode.LOOP,
    firstFrame: Int = 0,
    lastFrame: Int = board.frames.size - 1,
    speed: Float = 1f,
) {
    /** Length of one pass through the range at speed 1, ms. */
    val rangeMs: Double

    fun frameIndexAt(elapsedMs: Double): Int
    fun isFinished(elapsedMs: Double): Boolean
    fun audioPositionMs(elapsedMs: Double): Double?
    fun nextChangeMs(elapsedMs: Double): Double
}
```

Note what is **not** on it: there is no `firstFrame`, `lastFrame`, `count` or `speed` getter. The
range is private. Decision 8 is about that, and it is not an oversight in the clock.

**What this row writes:**

```kotlin
package cc.joycreator.joybrush.core.anim

/**
 * What the screen must DO, at one instant, given what is currently on it. Pure: no clock, no
 * thread, no Android, no sound. A player that owns one of these and a Handler is a player; a
 * player that owns arithmetic is a bug waiting for a review.
 */
sealed class Step {
    /**
     * Put frame [index] on screen. Emitted ONLY when it differs from the frame the caller said was
     * up — that comparison is the reason this file exists.
     */
    data class ShowFrame(val index: Int) : Step()

    /**
     * The sound must be at [boardMs] of the BOARD's timeline, or SILENT when null.
     *
     * This is `PlaybackClock.audioPositionMs` verbatim, with **no tolerance, no smoothing and no
     * interpolation** — see Decision 6. The device side decides whether a change of 0.4 ms is worth
     * a `seekTo`; this file does not know what a `seekTo` costs.
     *
     * Where the BYTES come from is JB-0.02c's business (`Board.audio`, the archive entry, the size
     * bound). Nothing in this file opens a file, a stream or a player.
     */
    data class Audio(val boardMs: Double?) : Step()

    /**
     * ONCE has reached the end of its range. The last frame stays up.
     *
     * LEVEL-TRIGGERED, not once: `step` is a pure function of its three arguments, so it cannot
     * know it has already said this. A player must therefore treat it as a standing fact and stop.
     * See Decision 4.
     */
    object Finish : Step()

    /** Nothing changed. The overwhelmingly common answer, and it is not an error. */
    object Nothing : Step()
}

/**
 * Playback as a PURE FUNCTION of elapsed time.
 *
 * [step] takes the frame that is CURRENTLY on screen and never emits [Step.ShowFrame] for it. That
 * one parameter is the whole of the fix for the backward-boundary spin: a caller cannot ask "what
 * should I draw?" without also saying "here is what is drawn", so there is no code path in which
 * "something changed" is decided without comparing it to something.
 *
 * IMMUTABLE, like the clock, and for the clock's own reason: changing the board, the range, the
 * mode or the speed builds a new clock and a new stepper, which restarts playback at 0. This class
 * does not work around that, and it holds no state of its own — see Decision 2.
 */
class FrameStepper(val clock: PlaybackClock) {

    /**
     * What to do at [elapsedMs], given that [frameIndex] is on screen and the sound is at
     * [audioAtMs] on the board's timeline (`null` = silent).
     *
     * PURE: the same three arguments always give the same list. No "last frame" field, no
     * "already finished" field, no `var` of any kind.
     *
     * [frameIndex] is what the CALLER believes, and is not checked against the clock's range —
     * [PlaybackClock] does not expose the range, and a caller that lies here gets the clock's
     * answer back rather than an exception. See Decision 8.
     */
    fun step(elapsedMs: Double, frameIndex: Int, audioAtMs: Double?): List<Step>

    /**
     * The frame the clock says is up at [elapsedMs], whatever is on screen. For the film strip's
     * playhead marker, which is a READING and not a change, so it does not go through [step].
     */
    fun frameOnScreen(elapsedMs: Double): Int

    /**
     * How long until the player should look again, wall ms — a FLOOR for the handler's delay, not a
     * schedule and never [PlaybackClock.nextChangeMs] itself.
     *
     * **ALWAYS STRICTLY GREATER THAN THE ELAPSED, once the elapsed has been placed on a timeline**
     * (a `NaN`, a negative or an infinite `t` means the beginning, exactly as
     * `PlaybackClock` decides it — see Decision 7). That is the whole contract, and Decision 7 is
     * why it takes work to keep: at a backward boundary the clock answers `nextChangeMs(t) == t`
     * bit-for-bit and the frame has not changed yet, so a naive `min(nextChangeMs, t + MAX)` hands
     * a player back its own timestamp and a millisecond clock loops on it.
     *
     * A caller that sleeps until this and then calls [step] again makes progress. A caller that
     * treats it as "the frame changes here" is wrong, and at a backward boundary the difference is
     * the spin.
     */
    fun nextWakeMs(elapsedMs: Double): Double

    companion object {
        /** The longest a player may sleep before looking again, wall ms. 250 = the app's own tap
         *  threshold (`CanvasGestures.TAP_MS = 250L`), chosen so a Handler can never be parked past
         *  the point where a person would tap Stop. */
        const val MAX_SLEEP_MS: Double = 250.0

        /**
         * The smallest move this class will ask a player to make, wall ms. Used ONLY when the
         * clock says the next change is at or before now — which, by the clock's own pinned
         * contract, is exactly a backward boundary asked at exactly itself. See Decision 7 for why
         * 1 ms and not something derived from a frame.
         */
        const val MIN_WAKE_MS: Double = 1.0
    }
}
```

## Decisions

1. **`step` takes the frame that is on screen, and emits `ShowFrame` only for a DIFFERENT one.**
   This is the entire reason the class exists. *Why:* at a backward boundary `nextChangeMs` is an
   **infimum** equal to the elapsed passed in, and the frame is still the outgoing one, so a player
   that wakes on it, draws unconditionally and re-asks draws the same frame and re-asks at the same
   time, for ever. Putting the comparison in the signature means there is no version of this player
   that can spin.

2. **`step` is pure: `step(t, i, a) == step(t, i, a)` for any `t`, `i`, `a`.** Same inputs, same
   output, no hidden state, no accumulated "last frame". *Why:* a stepper with hidden state cannot
   be tested against a table of expected timelines, and a table of expected timelines is the only
   thing that catches a seam. (Decision 4 is what this costs, and it is a cheap price.)

3. **`ShowFrame` comes first in the returned list, then `Audio`, then `Finish`; an empty list is
   returned rather than `Step.Nothing`.** A caller that draws before it sounds never shows a picture
   a frame ahead of its sound. *Why:* the sound is the thing a person notices late, so it is the
   thing to be right about last. *(Changed from the earlier draft of this spec, which returned a
   singleton `Step.Nothing`: a list is what the caller has to handle anyway, and one less type in
   the contract is one less thing to get wrong. `Step.Nothing` is deleted, not deprecated.)*

4. **`Finish` is a LEVEL-triggered fact, not an edge, and this row does not make it fire once.**
   `PlaybackClock.isFinished(t)` is a pure function of `t`, so `step` past the end of an ONCE clock
   returns `[Finish]` (or `[ShowFrame(last), Finish]`) on **every** call, for ever. "Stop the player
   on the first `Finish`" is the transport's job and lives in *Carried forward* below. *Why:* the
   earlier draft of this spec asked for `Finish` exactly once and pinned it with a test that
   asserted `Nothing` on every later call — which no pure function of `(t, i, a)` can do, and the
   only way to make it pass is the hidden field Decision 2 forbids. LOOP and PING_PONG never finish,
   and `isFinished` says so at any `t` whatsoever.

5. **`Audio` carries `clock.audioPositionMs(elapsed)` EXACTLY — including the corrected PING_PONG
   formula.** On the forward leg it is `rangeStartMs + cyclePos`; on the backward leg it is `null`.
   It is **never** `rangeStartMs + (elapsed × speed mod rangeMs)`, which was Decision 5 as first
   written in JB-3.05a and was **wrong**: those two agree only inside the first cycle, so the sound
   desyncs from the picture after one loop. *Why:* I am restating a correction rather than trusting
   that everyone read the correction. `Audio` is emitted only when the value **differs** from the
   `audioAtMs` the caller passed in, which is what makes it a change rather than a reading.

6. **No epsilon, no smoothing, no `±` anywhere in an emitted value.** The stepper emits the clock's
   number and the DEVICE decides whether a 0.4 ms difference is worth a `seekTo` — that tolerance
   is a playback-engine fact (`MediaPlayer.seekTo` has millisecond granularity and a cost), not a
   timing fact, and putting it here would put a playback tolerance into the one function whose whole
   claim is that it never drifts. *Why:* the same reasoning JB-3.05a used to refuse a `seam − ε`.
   `MIN_WAKE_MS` below is **not** an epsilon on a value; it is a floor on a *delay*, and the two are
   different things.

7. **`nextWakeMs(t) > t` at every `t`, and the floor is a two-case rule, not `min`.**
   ```kotlin
   fun nextWakeMs(elapsedMs: Double): Double {
       // The clock's OWN rule, restated: `cleanElapsed` is private to it, and a `t` that cannot be
       // placed on a timeline means "the beginning". Restating it here rather than importing it is
       // not duplication — the two are one sentence, and this one has to run BEFORE the comparison
       // below, because every comparison against NaN is false.
       val t = if (elapsedMs.isFinite() && elapsedMs >= 0.0) elapsedMs else 0.0
       val next = clock.nextChangeMs(t)
       return when {
           next > t -> min(next, t + MAX_SLEEP_MS)
           else -> t + MIN_WAKE_MS       // next == t, bit-for-bit: a backward boundary
       }
   }
   ```
   The second case is reachable and is the row's second real finding: the clock returns
   `clean + (boundary − cyclePos) / rate`, which at a backward boundary asked at exactly itself is
   `clean` — the elapsed passed in — and `frameIndexAt` is still the outgoing frame, so `step`
   emits nothing. `min` alone would hand a player back its own timestamp. *Why 1 ms, and not
   something derived from the board:* it is a floor on a **delay**, not a tolerance on a value, and
   it has to be independent of the board so it cannot depend on a frame length this class does not
   hold. It is also exactly one tick of the clock a `Handler` delay is measured in, and it is 1/16.7
   of the shortest frame a board can have (`fps ≤ 60`, so ≥ `1000.0/60.0 = 16.667` ms), so a 1 ms
   step **cannot** step over a change: the frame that is coming is already up, and 1 ms later it is
   still up, and the `ShowFrame` that says so has already been emitted.

8. **`frameIndex` is not range-checked, and a frame outside the range is not an error.**
   `PlaybackClock` keeps `first`, `last` and `count` private and exposes no getter for any of them,
   so the stepper **cannot** validate the argument without editing a built, reviewed file that is
   not in its owner area. It does not try. If the caller passes a frame the clock could never show,
   the stepper simply emits the frame the clock does say is up — which is the right answer, because
   the caller's belief was the thing that was wrong. *Why:* the stepper is not the authority on the
   range, and an exception here would be a crash on a live animation for a mistake the very next
   emitted `ShowFrame` repairs. (If the Lead would rather `PlaybackClock` exposed `firstFrame` /
   `lastFrame`, that is a one-line change to a built file and a question, not something this row
   does.)

9. **`frameOnScreen(t)` is `clock.frameIndexAt(t)` and nothing else.** *Why:* the film strip's
   playhead marker is a *reading* — it draws where the playhead is every frame, whether or not the
   frame changed — and routing a reading through `step` would emit nothing most of the time and
   something at a boundary, which is the wrong shape for a marker. It is also the cheapest possible
   definition, and one that cannot drift from the clock.

### Boundaries of this row — what is deliberately NOT here

10. **There is no control, no pill, no chip, no layout and no Android import in this row.** R33: the
    **peg bar owns PLAY and MODE** (the owner's own idea — the pegs *are* the buttons) and the
    **film strip has prev/next and nothing else**. There is therefore **one** saturated control on
    the animation board, not two, and the earlier draft of this spec — which put a play/pause pill
    *and* a loop-mode chip on the strip while JB-3.02's peg bar also had a `PLAY` peg — is deleted,
    Decisions 13 and 14 with it. Nothing in this file is a control, so nothing in this file can be
    the second one. *Which* peg wears the saturated gradient is JB-3.02's business; the constraint
    this row states is only that there is one.

11. **The audio half is not here, and waits for JB-0.02c.** `PlaybackClock.audioPositionMs` and
    `Step.Audio` are pure arithmetic on the clock and need no document field, so they are here. What
    is not here is every part that needs a *track*: where it is referenced (`Board.audio`), where it
    lives (`audio/<boardId>.<ext>` inside the archive), how big it may be (`MAX_AUDIO_BYTES`,
    16 MiB, refused in words at attach time), and the `MediaPlayer` that plays it. Verified against
    the tree, 2026-09-29: `Board` is `id, name, kind, rect, clipToBoard, fps, frames, grid` — no
    audio; `JbContents` is `doc, tiles, strokes, thumbnailPng` — no audio bytes; `JbArchive`'s
    entry table has no audio entry. R33 makes that field a **new T1 row, JB-0.02c**, with a version
    bump per R30/R31.

12. **The transport is not here, and the reason is in the build files, not in taste.**
    `androidkit/build.gradle.kts` compiles against `android.jar` with `compileOnly`, has
    `testImplementation(kotlin("test"))` and **no Robolectric** anywhere in the `joybrush` build. A
    `Handler(Looper.getMainLooper())` loop in `androidkit` therefore **cannot be executed by
    `:androidkit:test`** — every framework call throws `Stub!`. Shipping an untested player loop is
    exactly what this project's quality bar forbids. And the other half is a lock: R30 item 1 puts
    `JoyBrushActivity.kt` in a one-row-at-a-time order in which JB-3.05 waits for the chrome row
    JB-2.01 rather than bolting on a control. Both reasons point the same way.

13. **No version number appears anywhere in this row, and no document or archive file is touched.**
    `DocModel.kt`, `DocJson.kt`, `JbArchive.kt` and `DOC_VERSION` are JB-0.02's and JB-0.02c's.
    R30 item 3: a version is assigned AT LANDING, by the builder, from the current number. This row
    adds no field and no word, so there is nothing to bump.

### Carried forward — the transport's rules, for whoever builds it later

These are settled and are recorded here so they are not re-litigated. **They are not this row's
work, they are not in the owner area, and no test in this spec asserts them**, because none of them
can be asserted without an Android loop (Boundary 12).

* Pressing Play **while at the end** starts from the beginning (owner, R18) — the transport builds a
  **new** `PlaybackClock` and a new `FrameStepper` on every press, so elapsed restarts at 0,
  including in `ONCE` and including when the person paused on the last frame.
* Pressing Play **while paused mid-range** resumes from the frame on screen, not from 0: the new
  clock is built with `firstFrame = <the frame showing>`. That is the one asymmetry with the rule
  above, and it is deliberate — "at the end" is a position, and the end is the only position with no
  next frame.
* Scrubbing the strip while playing **pauses and does not resume on lift**. The strip is the
  authority for where the playhead is and the player is the authority for what time it is; two
  authorities over one playhead is how they drift apart. (JB-3.03 Decision 2 says the strip never
  consults the clock, so the pause has to come from the transport's side.)
* The mode control is `LOOP / PING_PONG / ONCE`, in that order, wrapping, and changing it while
  playing **stops first and does not auto-resume** — a mode change is a range change, and
  `PlaybackClock` is immutable by design.
* The loop runs on a `Handler(Looper.getMainLooper())` posting itself with `nextWakeMs` as the
  delay, torn down in `onPause` and rebuilt in `onResume` with the playhead preserved and playback
  **paused**. A Handler that survives a backgrounded screen is a Handler drawing to a surface that
  is not there.
* A speed control is **not** shipped. `PlaybackClock` honours `speed` and the constructor parameter
  is there; nothing in the blueprint asks for a slider, and adding one means deciding where it lives
  in a chrome that does not exist yet.

## Decision → Test map (every numbered Decision above is checkable)

| Decision | Pinned by |
|---|---|
| 1 (comparison in the signature) | `aFrameAlreadyOnScreenIsNeverEmittedAgain`, `theStepperCannotEmitTheFrameItWasToldIsUp` |
| 2 (purity) | `stepIsAFunctionOfItsArgumentsAlone` — 5 000 random triples, each called twice, equal lists |
| 3 (order, and the empty list instead of `Nothing`) | `thePictureIsDrawnBeforeTheSoundIsMoved`, `anUnchangedMomentReturnsAnEmptyList` |
| 4 (`Finish` is level-triggered) | `finishIsAStandingFactAndOnlyForOnce` |
| 5 (corrected audio, and only on a change) | `theSoundIsTheCorrectedFormulaAndNotTheModulo`, `anUnchangedSoundPositionEmitsNothing` |
| 6 (no epsilon) | `theEmittedAudioIsBitIdenticalToTheClock` — exact equality, no tolerance argument anywhere in the file |
| 7 (`nextWakeMs > t`, two cases) | `nextWakeIsAlwaysStrictlyAfterNow` (**the 1 ms walk, four exact backward boundaries per cycle**), `nextWakeNeverParksTheHandlerForever`, `nextWakeIsTheClocksAnswerCappedAt250` |
| 8 (no range check on `frameIndex`) | `aFrameTheClockCouldNeverShowIsNotAnError` |
| 9 (`frameOnScreen` is a reading) | `frameOnScreenIsTheClocksOwnAnswer` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FrameStepperTest.kt`

**`commonTest`, not `jvmTest`, and that is deliberate.** Nothing in this row opens a file, a
stream or a socket, so there is nothing `commonTest` cannot do; a test that could live in
`commonMain`'s own test source set should (the iOS door, blueprint §3.1). The other source-level
tests in this project that open files are in `jvmTest` for the opposite reason.

**The two rules for this file, before anything else** (both are JB-3.05a's, unchanged):

1. **Every boundary is derived by indexing `AnimOps`, never by accumulating `hold × 1000 / fps` a
   second time.** `2 × (1000.0 / 12.0)` is not bit-identical to `2000.0 / 12.0`.
2. **`frameStartsMs` returns one start per frame and no trailing end.** The end of a board is
   `totalDurationMs`. Use the same `edge(k)` accessor `PlaybackClockTest` uses.

**The two fixtures.**

* **A — the four-frame board** `PlaybackClockTest` uses: 12 fps, holds `[1, 2, 1, 3]`, ids `f0..f3`.
* **B — the six-frame ping-pong board, and it is B on purpose:** 10 fps, holds `[1, 1, 1, 1, 1, 1]`,
  ids `f0..f5`, built by the same `boardOf(vararg holds, fps = …)` helper. **10 fps and not 12** so
  that one tick is exactly `100.0` ms and every boundary is a whole millisecond. The derived
  numbers, all of them from `AnimOps` and all of them exact:
  `frameStartsMs = [0, 100, 200, 300, 400, 500]`, `totalDurationMs = 600.0`, so
  `rangeMs = 600.0`, and from the clock's own rule `backSpanMs = rangeStarts[5] − rangeStarts[1] =
  500 − 100 = 400.0`, so `cycleMs = 600 + 400 = 1000.0`. **Every boundary in the cycle is therefore
  a whole millisecond, and a 1 ms walk steps exactly onto all of them** — including the seam at
  `600` and the three interior falling edges at `700, 800, 900`. That is what makes test 3 sharp
  instead of approximate.

1. `aFrameAlreadyOnScreenIsNeverEmittedAgain`: for board A, every board edge `e` and every frame
   `i` in the range, `step(e, i, null)` contains **no** `ShowFrame` naming `i`. Structure, not a
   count.
2. `theStepperCannotEmitTheFrameItWasToldIsUp`: the same over 2 000 pseudo-random `(t, i, a)`
   triples — a fixed LCG, no `kotlin.random` (its algorithm is not promised across versions).
3. **`theStepperDoesNotSpinAtABackwardBoundary` — the test this row exists for.** Board B, a
   `PING_PONG` clock over the whole range, speed 1, walked in 1 ms steps from `t = 0.0` to
   `t = 1000.0` (one whole `cycleMs`), carrying the shown frame forward exactly as a player would:
   `shown = the last ShowFrame's index, else 0`, and the sound likewise. Then assert, **with the
   arithmetic in the test's own comment**:
   - **The emitted sequence is exactly `[1, 2, 3, 4, 5, 4, 3, 2, 1, 0]` — ten emissions, `2n − 2`
     for `n = 6`.** The derivation, which the test writes out: the forward leg shows all `n = 6`
     frames, in the slots starting at `0, 100, 200, 300, 400, 500`; the backward leg replays the
     range's **interior** and therefore shows `n − 2 = 4` new frames — slots 4, 3, 2, 1, up over the
     half-open intervals `(600, 700]`, `(700, 800]`, `(800, 900]`, `(900, 1000]`, so nothing is
     emitted *at* 700, 800 or 900 but one step after each; and the wrap at `1000` shows frame 0
     again, which is the first emission of the *next* cycle. So a full cycle
     emits `6 + 4 = 10` times, which is `2n − 2` — and **not once per step**: the walk is 1 001
     steps and 10 of them emit. The visible sequence, sampled at slot midpoints
     (`50, 150, 250, 350, 450, 550, 650, 750, 850, 950`), is
     `0 1 2 3 4 5 4 3 2 1` (ten slots, the ends once each), and the leading `0` in
     `0 1 2 3 4 5 4 3 2 1 0 …` is the eleventh slot — the wrap, i.e. the first emission of the next
     cycle. **Both spellings are the same fact; the test pins the emission list because that is what
     a player sees.**
   - **No index is emitted twice in a row** and no index is emitted at a `t` where
     `clock.frameIndexAt(t)` is not equal to it (an index comparison against the clock, not against
     a written-out table).
   - **`step` emits nothing at `t = 600, 700, 800, 900`** — the four exact backward boundaries.
     Asserted positively, because it is the surprising half: at those instants the frame has *not*
     changed yet.
   - **And the one that fails without Decision 7:** at every one of the 1 001 steps,
     **`nextWakeMs(t) > t`**. With Decision 7's second branch deleted this assertion goes red at
     `t = 600, 700, 800, 900` and nowhere else, which is what makes it a real test.
   The failure mode this catches is a stepper that emits on every call, and its symptom in the field
   is a phone at 100 % battery playing a two-frame animation.
4. `stepIsAFunctionOfItsArgumentsAlone`: 5 000 triples, `step` called twice with each, the two lists
   equal (`==`, on the list of data-class `Step`s). No hidden state, no "last frame" field.
5. `thePictureIsDrawnBeforeTheSoundIsMoved`: on board B at a forward boundary where both fire, the
   list is `[ShowFrame, Audio]` in that order — asserted as an index comparison, not as a string.
6. `anUnchangedMomentReturnsAnEmptyList`: mid-frame, with the sound already at the clock's own
   answer, `step` returns an **empty list** — and `Step.Nothing` does not exist, which the test
   proves by not being able to reference it. (Assert the empty list; the deletion of the type is
   checked by the file compiling.)
7. `theSoundIsTheCorrectedFormulaAndNotTheModulo`: on a `PING_PONG` clock over board B at speed 1,
   for `elapsed` in the **second** cycle, the emitted `Audio.boardMs` equals
   `edge(board, 0) + (t mod 1000.0)` **exactly** — the expected value is derived from the **board**,
   not from the clock, because `rangeStartMs` is a private field of `PlaybackClock` and a test that
   read it would be asserting the clock against itself. (On this board `edge(board, 0) = 0.0`, so
   the expected value is just `t mod 1000.0`.) The test then asserts a hand-written
   `edge(board, 0) + (t mod 600.0)` — the wrong formula — **differs**, and writes the arithmetic:
   `rangeMs = 600`, `cycleMs = 1000`, so at `t = 1100.0` the emitted value is `100.0` and the modulo
   formula gives `500.0`. The two agree only on the **first cycle's forward leg** (`0 ≤ t < 600`),
   which is the comment's other half. On the backward leg the emitted value is `null`, from the seam
   (`cyclePos = 600`) onward, and the test asserts that at `t = 600, 650, 900, 1000`.
8. `anUnchangedSoundPositionEmitsNothing`: `step(t, shown, clock.audioPositionMs(t))` emits **no**
   `Audio` at any `t`, over 1 000 probes across both legs and three speeds. (The comparison is
   `==` on `Double?`, no tolerance — that is Decision 6.)
9. `theEmittedAudioIsBitIdenticalToTheClock`: for 3 000 probes across both legs, three speeds
   (`0.5f`, `1f`, `2.5f`) and a sub-range, every emitted `Audio.boardMs` equals
   `clock.audioPositionMs(t)` with **no tolerance argument at all**. If anyone adds an epsilon this
   test fails, which is the point.
10. `finishIsAStandingFactAndOnlyForOnce`: an `ONCE` clock over board A, past `rangeMs`, emits a
    list whose **last** element is `Finish` on **every** call — `step(t, 3, null)` and
    `step(t + 1, 3, null)` alike, which is the level-triggered half (Decision 4). When the shown
    frame is not yet the last, the list is `[ShowFrame(3), Finish]`; when it is, it is `[Finish]`
    alone. A `LOOP` and a `PING_PONG` clock never contain `Finish`, at `3 600 001` ms or anywhere
    else. And the test does **not** assert `Nothing` on a second call, because that is not a
    property a pure function can have.
11. `afterFinishOnlyTheLastFrameIsUp`: `frameOnScreen` is frame 3 at every `t` past the end of an
    `ONCE` clock over board A, including `t = rangeMs × 1000`.
12. **`nextWakeIsAlwaysStrictlyAfterNow`**: the invariant of Decision 7, on its own, over board B in
    all three modes, walking `t` from `0.0` to `3 000.0` in 1 ms steps, **and** at 2 000 pseudo-random
    `t`, **and** at the three values that cannot be placed on a timeline: `NaN`, `−1.0` and
    `Double.POSITIVE_INFINITY`. The clock's own `cleanElapsed` turns all three into `0.0`, so on
    board B the answer is the first boundary from zero, `100.0`, for each of them — **assert the
    value, not merely its finiteness**, and assert that none of the three is `NaN`. The `NaN` case
    is the one that must be there: every comparison against `NaN` is false, so a `nextWakeMs` that
    compared before normalising would return `NaN` and the invariant would be silently broken.
    (`step(NaN, i, a)` is well defined for the same reason: it is the step at `t = 0`, and the test
    says so rather than leaving it implied.) Non-vacuous: delete Decision 7's second branch and this
    is the test that goes red, at `t = 600, 700, 800, 900` in each PING_PONG cycle.
13. `nextWakeNeverParksTheHandlerForever`: a one-frame range (board A with `firstFrame = 2,
    lastFrame = 2`) and a finished `ONCE` clock both return `t + MAX_SLEEP_MS` — the clock answers
    `Double.POSITIVE_INFINITY` for both — and never `∞`. Assert the value, not merely "finite".
14. `nextWakeIsTheClocksAnswerCappedAt250`: inside a frame on board A, `nextWakeMs(t)` equals
    `clock.nextChangeMs(t)` **exactly**; when that is more than 250 ms away it is exactly
    `t + 250.0`; and `MAX_SLEEP_MS == 250.0` is asserted equal to `CanvasGestures.TAP_MS`'s value
    only in a comment, not by importing an Android class into a `commonTest`.
15. `aFrameTheClockCouldNeverShowIsNotAnError` (Decision 8): `step(0.0, 99, null)` on board A's
    clock does not throw and its `ShowFrame`, if any, names `clock.frameIndexAt(0.0)`. The comment
    records why: the range is private in `PlaybackClock`, and an exception here would crash a live
    animation over a mistake the next emission repairs.
16. `frameOnScreenIsTheClocksOwnAnswer`: at 3 000 probes across all three modes, two sub-ranges and
    three speeds, `frameOnScreen(t) == clock.frameIndexAt(t)` with no tolerance.
17. `aSubRangeStepsOverItsOwnFramesOnly`: board A, frames `1..2`, PING_PONG and LOOP. Every
    `ShowFrame` the walk emits names `1` or `2` — never `0` or `3` — over a whole cycle. This is the
    test that catches a stepper that forgets the range and starts emitting the board's frames.

**Non-vacuity proof the builder must run and paste** (all three, each is a one-line edit):

1. delete the `if (index != frameIndex)` guard from Decision 1 → tests 1, 2 and 3 go red;
2. delete the `else -> t + MIN_WAKE_MS` branch from Decision 7 → test 12 goes red at `t = 600, 700,
   800, 900` and nowhere else;
3. add a `private var finished = false` to make `Finish` fire once (the old Decision 4) → test 4
   goes red, because the second call now differs from the first.

A seam test that has never been seen to fail is a suspicion, not a test.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures. The builder
cannot run gradle if it has no JVM; in that case say so in the report rather than claiming a pass.

## Do not

- **Do not sleep on `nextChangeMs` and draw unconditionally.** That is the spin. `nextWakeMs` is a
  floor, and the comparison lives in `step`'s signature.
- Do not hand `nextWakeMs` back a value that is not `> elapsedMs`. Decision 7's second branch is not
  optional, and the four instants in test 3 are exactly where it is load-bearing.
- Do not give `FrameStepper` a `var`. Not a "last frame", not a "finished" flag, not a "last audio
  position". Purity is what makes the table of expected timelines possible (Decision 2), and the
  `Finish` semantics are the price, not the excuse.
- Do not re-derive `holdFrames * 1000.0 / fps`. Index `AnimOps`.
- Do not put an epsilon, a tolerance or a `±` into anything `PlaybackClock` produced, or into
  Decision 7's `MIN_WAKE_MS` (which is a floor on a delay, not a tolerance on a value).
- Do not reintroduce `rangeStartMs + (t mod rangeMs)` for PING_PONG. It was wrong as written and it
  is corrected in JB-3.05a; Decision 5 exists precisely so it cannot be.
- **Do not add a control, a pill, a chip, a `Handler`, a `MediaPlayer`, an `import android.*`, or
  any file outside the owner area.** R33 gives PLAY and MODE to the peg bar and gives the film
  strip prev/next and nothing else; one saturated control is the whole of it.
- Do not add a speed control, a frame counter, a loop count or a "reverse" button.
- Do not add a second audio track, a volume control or a mute. One track, no mixing — and the track
  is JB-0.02c's.
- Do not touch `DocModel.kt`, `DocJson.kt`, `JbArchive.kt`, `BrushJson.kt` or `DOC_VERSION` /
  `BRUSH_VERSION`. Nothing in this row is serialised.
- No new dependencies. This file is `commonMain`.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green, output pasted, 0 failures
- [ ] the three-part non-vacuity proof pasted (guard removed → 1/2/3 red; `MIN_WAKE_MS` branch
      removed → 12 red; `finished` flag added → 4 red)
- [ ] `git status --short` shows **only** the two owner-area files
- [ ] committed `JB-3.05: playback frame stepper`, pushed
- [ ] `INDEX.md` updated to "Built — awaiting T1 review"
- [ ] **No device check is owed by this row.** The Note 9 check ("a six-frame ping-pong plays, the
      sound is silent on the way back, Home → reopen leaves it paused") belongs to the transport row
      that builds a `Handler` and a `MediaPlayer`, and to JB-0.02c for the track. Saying so in the
      report is part of the row being done, not an omission.

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The frame stepper is complete and
pinned; the corrections applied are listed in the review's "Corrected numbers" plus two found by
reading the landed clock.)_

### For the Lead

**Q1 — the audio half is JB-0.02c, and this row now says so instead of asking.** The review asked me
to scope the audio half out, and the tree confirms there is nowhere for a track to live: `Board` is
`id, name, kind, rect, clipToBoard, fps, frames, grid`; `JbContents` is `doc, tiles, strokes,
thumbnailPng`; `JbArchive`'s entry table has no audio entry and no size bound. R33 has already
opened **JB-0.02c** for it (`Board.audio`, `audio/<boardId>.<ext>`, `MAX_AUDIO_BYTES` = 16 MiB
refused in words at attach time, copied INTO the archive, version bump per R30/R31), so the six
things I asked about in the earlier draft of this spec are answered by the ruling and the row exists.
**Nothing is left for you to rule on here.** What this row keeps is the pure half —
`PlaybackClock.audioPositionMs` and `Step.Audio` — because that is arithmetic on the clock and
needs no field. A player that has no track simply ignores `Step.Audio`.

**Q2 — `PlaybackClock` does not expose its range, so `frameIndex` cannot be validated (Decision 8).**
This is the one place where the contract I have written is narrower than the contract the earlier
draft wanted ("rejected in words unless it is a frame of the range"). The clock's `first`, `last`
and `count` are private with no getters, so the stepper cannot check without editing a built,
reviewed file. I chose to accept the caller's word and let the next `ShowFrame` repair it, because
throwing on a live animation is worse than one wrong frame for one tick. **If you would rather
`PlaybackClock` grew `val firstFrame` / `val lastFrame` (a two-line addition to a built file), say
so and I will add a test that refuses out-of-range indices** — it is a one-line change to a
`PlaybackClock.kt` that is not in this row's owner area, so it is your call, not this builder's.

### Ruled here, low-risk and reversible, flagged for you to confirm

**Q3 — PROVISIONAL (Claude to confirm): `MIN_WAKE_MS = 1.0` (Decision 7).** This is the only new
number in the row. The reasoning is in Decision 7: it is a floor on a *delay*, it must not depend on
a board (this class does not hold one), and 1 ms is one tick of the clock a `Handler` delay is
measured in and 1/16.7 of the shortest frame a board can hold, so it cannot step over a change. If
you would rather the second case sleep until the *next* boundary strictly after now — which needs
`PlaybackClock` to expose one, so it is not available today — say so and the alternative is to have
`nextWakeMs` return `t + MAX_SLEEP_MS` there and accept skipping a frame at every turn of a
ping-pong. I do not think that is the right trade, but it is a design call and it is yours.

**Q4 — PROVISIONAL (Claude to confirm): `step` returns an empty list, and `Step.Nothing` is deleted
(Decision 3).** Purely a shape decision about this row's own sealed class; nothing outside the two
owner-area files can observe it either way.

**Q5 — the one saturated control.** R33 settles ownership (peg bar = PLAY and MODE; strip = prev/next
and nothing else) but not which peg wears the saturated gradient. That is JB-3.02's file and I have
not touched it; I have only recorded in Boundary 10 that this row adds no control, so the count of
saturated controls on the animation board is whatever JB-3.02 and the chrome row decide, and it
must be one.
