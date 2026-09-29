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

## Questions
_(builder: stealth/space-bunny-alpha, 2026-09-29. Fixing Finding 1 of `JB-3.05a__muse-spark.md` —
"nextChangeMs at the seam and every interior backward boundary returns the next boundary along". Three
things I settled inside my own area and one I did not.)_

### 1. "A backward boundary" is a class, not the seam — and the ruling already says so

The review's recommendation ("one branch: `cyclePos == rangeMs` — and symmetric falling-edge
boundaries") is now one rule rather than a special case, and the code says why in one place:

> **ONE RULE, two comparisons.** A FORWARD boundary is a frame START, so the incoming frame is
> already up at it and the next change is the start after it — strictly greater. A BACKWARD boundary
> is a frame END in cycle time, so the outgoing frame is the one up at it and the change is
> immediately after — at or after, which is the infimum.

The backward edges are the interior starts mirrored about the last frame's start,
`u[k] = rangeMs + (starts[count-1] - starts[k])` for `k = count-1 … 1`: the seam at `k = count-1`
(`= rangeMs`), the interior falling edges between, and the turn at `k = 1` (`= cycleMs`). A six-frame
range has **four** backward boundaries, three of them interior, and a seven-frame range has five.
Note the turn is *not* one of them — at the turn the frame really does change AT the boundary, so it
answers forwards like any other frame start. That asymmetry is now pinned by a test rather than left
to be rediscovered.

### 2. The floating-point rule: **no epsilon, and no snapping either** — settled deliberately

`nextChangeMs` rebuilds its answer as `elapsed + (boundary - position) / speed`. Asked exactly at a
boundary that difference is exactly zero, so the returned wall time is the elapsed that was passed
in, bit for bit. There is therefore **no `seam - 1e-12` to snap**: exactness is a property of the
arithmetic rather than something a tolerance has to buy back. Where the caller's own arithmetic lands
an ulp off — reachable only at a speed that is not a power of two, since dividing and re-multiplying
by 1 is exact — the clock answers with the boundary it is genuinely inside or genuinely past. A
tolerance there would turn a correct answer into a slightly wrong one silently, in the one function
whose entire claim is that it never drifts. Decision: **none, and pinned with exact equality on every
boundary, at four speeds.**

**What did need fixing is `frameIndexAt`, not `nextChangeMs`.** Turning `cyclePos` back into a
forward position (`last - (cyclePos - rangeMs)`) and looking that up cannot be done exactly:
`rangeMs + (last - starts[k])` is a rounded sum, so the round trip comes back a hair either side of
`starts[k]` even when `cyclePos` is EXACTLY the edge. On the uneven board
`holds(3,1,2,1,3,2,1)` ranged 1..6, `nextChangeMs(1333.333…)` reported the change at 1333.333… while
`frameIndexAt(1333.333…)` was already showing the frame it was supposed to change TO. The seam would
show the incoming frame instead of the outgoing one, which contradicts the ruling it is supposed to
implement. So the backward leg now reads its slot off the falling edges themselves
(`slotOnTheBackwardLeg`), comparing the same edge the same way `nextChangeMs` does — the two can no
longer disagree, because there is only one comparison left to disagree about. This is in scope: it
is the same seam, and without it "the ruling holds" is false by an ulp on some boards.

### 3. **UNRESOLVED — `nextChangeMs` inside an ONCE clock's LAST frame.** Needs an orchestrator ruling.

Writing the exhaustive timeline test turned up a second thing, on a different boundary and a
different mode, which I have deliberately **not** fixed because it is not this finding:

> An ONCE clock's frame never changes after its last frame's own start. Decision 7 says
> `nextChangeMs` is the smallest time at which `frameIndexAt` changes, and the ruling already settled
> the degenerate case — "a one-frame range never changes frame, so `nextChangeMs` returns infinity for
> it in every mode". The last frame of an ONCE range is that same situation and is not covered by the
> words: `positionOf` parks it at `rangeMs − ε`, the frame on either side of `rangeMs` is the same
> index, and `nextChangeMs` hands back `rangeMs` — a time at which nothing changes.

By the ruling's own logic the answer there should be `Double.POSITIVE_INFINITY`, and it is a
three-line change. I have not made it, because (a) it is a second finding and the review explicitly
recorded the ONCE clamp as verified correct, and (b) it is the orchestrator's call whether ONCE's
end should report `∞` like a one-frame range or keep reporting `rangeMs` as "playback stops here".
`nextChangeMsIsTheInfimumOfWhenTheFrameActuallyChanges` therefore skips ONCE's second clause and says
so in the test's own comment, rather than pinning either answer.

### 4. For JB-3.05b: at a backward boundary the change has **no minimum**, so "sleep until
### `nextChangeMs`" is not a loop on its own

A player that sleeps until `nextChangeMs(t)` and then asks again without comparing frames will spin at
every backward boundary: the answer at the boundary is the boundary, and the frame it is told to show
is still the outgoing one until the very next instant. The infimum is what it must sleep until, and
then it has to redraw — it just cannot treat "woke up" as "something changed". This is a consequence
of the ruling, not a defect in it, and it is recorded here so JB-3.05b does not read it as a clock
bug and try to fix it with an epsilon.


