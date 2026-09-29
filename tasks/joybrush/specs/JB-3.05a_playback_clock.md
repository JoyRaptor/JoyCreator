# JB-3.05a — Playback clock: loop / ping-pong / once, a play range, and where the sound is

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
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

## Questions
