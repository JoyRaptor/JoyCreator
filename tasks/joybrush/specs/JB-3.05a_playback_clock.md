# JB-3.05a — Playback clock: loop / ping-pong / once, a play range, and where the sound is

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟧 Built — see ROADMAP.md. **Decision 3 and Decision 5 below are superseded by the orchestrator ruling at the end of this file, which three empty dispatches forced.** |
| **Depends on** | JB-3.01 (`AnimOps.frameStartsMs`, `totalDurationMs`) — Built |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PlaybackClock.kt`, NEW `.../commonTest/.../anim/PlaybackClockTest.kt` |
| **Estimated size** | ~150 lines + ~200 lines of tests |

## Goal
Phase 3 needs playback with an audio track (blueprint §7). The one thing that makes playback feel
wrong is DRIFT — frames slowly falling behind the sound. So playback is a pure function of elapsed
time, never a counter that adds frame lengths. This spec is that function; the player loop and the
audio output are JB-3.05b.

## Contract
```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board

enum class PlayMode { LOOP, PING_PONG, ONCE }

/**
 * Immutable: build a new one when the board, mode, range or speed changes (and restart the clock).
 * @param firstFrame, lastFrame  play range, frame INDICES into board.frames, inclusive.
 * @param speed  1 = the board's fps; 0.5 = half speed. 0.1 .. 4.
 */
class PlaybackClock(
    board: Board,
    val mode: PlayMode = PlayMode.LOOP,
    firstFrame: Int = 0,
    lastFrame: Int = board.frames.size - 1,
    speed: Float = 1f,
) {
    /** Length of one pass through the range at speed 1, ms. */
    val rangeMs: Double
    /** The frame index to show [elapsedMs] after play was pressed (wall clock, NOT speed-adjusted). */
    fun frameIndexAt(elapsedMs: Double): Int
    /** ONCE: true after the last frame's time has passed. LOOP / PING_PONG: always false. */
    fun isFinished(elapsedMs: Double): Boolean
    /**
     * Where the sound should be, in ms of the BOARD's own timeline (0 = board frame 0), for this
     * [elapsedMs] — or null when the sound must be silent (the backwards leg of PING_PONG, or ONCE
     * after it finished).
     */
    fun audioPositionMs(elapsedMs: Double): Double?
    /** When the next frame change happens after [elapsedMs] (wall ms) — the player sleeps until then. */
    fun nextChangeMs(elapsedMs: Double): Double
}
```

## Decisions
1. **Range:** `firstFrame`/`lastFrame` are clamped into the board's frames and swapped if reversed.
   `rangeMs` = sum of the range's frame durations, from `AnimOps.frameStartsMs` / `totalDurationMs`
   (the same arithmetic, so the two can never disagree). A board with no frames is refused
   (`IllegalArgumentException`) — there is nothing to play.
2. **Board time** `t = elapsedMs × speed` (speed clamped 0.1..4; non-finite → 1). Negative or NaN
   elapsed → treated as 0.
3. **LOOP:** position = `t mod rangeMs`. **ONCE:** position = `min(t, rangeMs − ε)` (shows the last
   frame after the end). **PING_PONG:** one cycle is forward then backward, and the two END frames
   are NOT shown twice in a row (A B C D C B | A B C …): the backward leg covers the range minus the
   first and last frames. A one-frame range just shows that frame.
4. The frame at a position is the one whose start ≤ position < start + duration, using the range's
   own starts (range start = 0). The index returned is into `board.frames`.
5. **Audio:** LOOP → `rangeStartMs + (t mod rangeMs)`, where rangeStartMs = the board time of
   `firstFrame`. ONCE → the same until finished, then null. PING_PONG → the forward leg as LOOP,
   null on the backward leg (sound played backwards is never wanted).
6. **No drift, by construction:** every answer is computed from `elapsedMs` alone. Test 6 pins it.
7. `nextChangeMs` returns the smallest wall time > elapsedMs at which `frameIndexAt` changes
   (for ONCE after finishing: `Double.POSITIVE_INFINITY`).

## Tests
Use a board at 12 fps with frames of holds [1, 2, 1, 3] (durations 83.33…, 166.67, 83.33, 250 ms).
1. LOOP from elapsed 0 steps 0,1,1,2,3,3,3 at the right boundaries, then wraps to 0 at 583.33 ms.
2. Range 1..2 LOOP: shows only 1 and 2; audio position starts at 83.33 ms (frame 1's start).
3. ONCE: last frame shown forever after the end; `isFinished` flips exactly at rangeMs; audio null after.
4. PING_PONG on 4 equal frames: sequence 0 1 2 3 2 1 0 1 …; audio null during 2 1 of the backward leg.
5. speed 2: every boundary at half the wall time; speed 0 → clamped to 0.1; speed NaN → 1.
6. **Drift:** at 12 fps LOOP, `frameIndexAt(1 hour + 1 ms)` equals the index computed from
   `(3 600 001 mod rangeMs)` directly — no accumulated error.
7. `nextChangeMs` from 0 = 83.33…; from inside the 3-hold frame = its end; ONCE finished → ∞.
8. Range 5..2 on a 7-frame board is swapped to 2..5; range 0..99 is clamped; empty board throws.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file (AnimOps is called, not changed). No Android, no audio code.

## Definition of done
Tests pass (paste) · commit `JB-3.05a: playback clock` · ROADMAP row → 🟧 Built.

## Orchestrator ruling (PROVISIONAL — Claude to confirm) — why three dispatches produced nothing

A builder given this spec spends its whole budget in Decision 3 and never writes a file. Two reasons,
both mine to fix rather than the agent's:

1. **"The backward leg covers the range minus the first and last frames" never says which interval it
   traverses.** It has to be the forward interval `[starts[1], starts[count-1]]` to produce the
   `A B C D C B` this same paragraph asks for, but that was not stated, and the plain reading also
   admits a second interval.
2. **The seam is unspecified and it changes two function bodies.** At the instant the backward leg
   begins, is the frame D or C? Half-open or closed? That decides whether D is shown twice at one
   instant, and it decides what `nextChangeMs` returns there.

**Ruling.**

- **Seam convention.** Forward leg **half-open `[0, rangeMs)`**; backward leg **`[rangeMs, cycleMs)`**,
  where `cycleMs = rangeMs + (starts[count-1] - starts[1])` for a range of three or more frames, and
  `cycleMs = rangeMs` for one or two. At `cyclePos == rangeMs` the last forward frame is **still on
  screen**. So every frame is shown for exactly its own duration in measure, none is shown twice in a
  row, and the sequence is exactly `A B C D C B A B C …`.
- **The backward position** is `backPos = starts[count-1] - (cyclePos - rangeMs)`, traversing
  `[starts[1], starts[count-1])` and landing on the frame whose start ≤ it — the same
  `start ≤ position < start + duration` rule as the forward leg, applied to the reversed position.
- **`nextChangeMs` at the seam returns `rangeMs`.** The index is still the last forward frame *at*
  `rangeMs` and differs immediately after, so the change has no minimum: `rangeMs` is its
  **infimum**, and that is what a player must sleep until. Do not "fix" it to `rangeMs + ε`.
- **Decision 5's PING_PONG audio formula was wrong as written.** `rangeStartMs + (t mod rangeMs)`
  desyncs sound from picture after the first cycle, because `t mod rangeMs` and the cycle position
  are equal only inside the first cycle. **The audio follows the frame:** on the forward leg it is
  `rangeStartMs + cyclePos`, on the backward leg `null`. That is Decision 6's own no-drift intent, and
  the whole reason playback was put on a clock. LOOP's and ONCE's formulas are unchanged and correct.
- **The audio stops a hair before the frame does** — at exactly the seam the frame is still the last
  forward one while the audio has gone `null`. A measure-zero instant no player can observe; stated
  here so nobody later reads it as an inconsistency.
- **A one-frame range never changes frame**, so `nextChangeMs` returns `Double.POSITIVE_INFINITY` for
  it in every mode. That follows from the contract's own wording and the spec did not say it.
- **An unplayable fps surfaces `AnimOps`' own exception**, not a new one: the clock calls
  `frameStartsMs`, which is where the 1..60 rule already lives, so the two cannot disagree.
- **The durations come from `AnimOps.frameStartsMs(board)`, range-relative by subtraction.** A test
  that needs a boundary must derive it the same way (index the same list) rather than accumulate
  `hold * 1000 / fps` itself: `2 * (1000.0/12.0)` is **not** bit-identical to `2000.0/12.0`, and a
  boundary off by one ulp lands on the wrong side of the comparison that decides the frame.

