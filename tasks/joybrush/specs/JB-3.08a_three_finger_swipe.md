# JB-3.08a — The context-aware three-finger swipe: which mode, and what a swipe does (the logic)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.16a (`SizeOpacityDrag`), JB-3.01 (animation model) — 3.01 Built |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/ThreeFingerSwipe.kt`, NEW `.../commonTest/.../tool/ThreeFingerSwipeTest.kt` |
| **Estimated size** | ~140 lines + ~180 lines of tests |

## Goal
Owner's ruling (blueprint §6, question 1): a three-finger swipe **flips frames** when the ACTIVE board
is an animation board with ≥ 2 frames, and otherwise **adjusts brush size/opacity**. A corner badge
(running figure / brush) always shows which; tapping the badge overrides the automatic choice. The
mode NEVER changes mid-gesture. This spec is the decision logic and the swipe maths only — the
Android wiring (finding three fingers in `CanvasGestures`, drawing the badge) is JB-3.08b.

## Contract
```kotlin
package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.doc.JbDocument

enum class SwipeMode { FRAMES, BRUSH }

/** Lives as long as the canvas screen. */
class ThreeFingerSwipe(val density: Float = 1f) {
    /** What the badge shows right now for [doc] (its active board), with any override applied. */
    fun badge(doc: JbDocument): SwipeMode
    /** The badge was tapped: flip the mode for THIS active board until it changes. */
    fun tapBadge(doc: JbDocument)

    sealed class Step {
        /** Show frame [index] of the active board (already clamped to 0 until frames − 1). */
        data class ShowFrame(val index: Int) : Step()
        data class Brush(val size: Float, val opacity: Float) : Step()
        /** The index just reached the first (atEnd = false) or last (true) frame and stops there. */
        data class Ended(val index: Int, val atEnd: Boolean) : Step()
        /** Pushed through an end: now showing [index] at the other end. */
        data class Wrapped(val index: Int) : Step()
        object Nothing : Step()
    }
    /** Three fingers came down. [frameIndex] = the frame showing; brush values as now; zoom now. */
    fun begin(doc: JbDocument, frameIndex: Int, size: Float, opacity: Float, screenPerDoc: Float)
    /** Total centroid offset since begin, screen px (+x right, +y down). */
    fun move(dxScreen: Float, dyScreen: Float): Step
    fun end()
}
```

## Decisions
1. **Automatic mode** = FRAMES when `doc.activeBoardId` names a board of kind ANIMATION with
   `frames.size >= 2`; otherwise BRUSH (no active board, a canvas board, one frame…).
2. **Override** (badge tap) is remembered TOGETHER WITH the board id it was made on. When the active
   board is a different board, the override is ignored (and forgotten). Tapping again returns to
   automatic. An override to FRAMES on a board that has < 2 frames is refused (the badge stays BRUSH)
   — there is nothing to flip.
3. **Mode is fixed at `begin`.** `badge()` may change during a gesture (e.g. a frame gets added) but
   the gesture in progress keeps the mode it began with.
4. **FRAMES:** a flip every `36 × density` screen px of HORIZONTAL travel; finger moving LEFT shows
   the NEXT frame (like turning a page), right shows the previous. The ends STOP (owner, 2026-09-29):
   `index = clamp(start − round(dx / step), 0, frames − 1)`. **Push-through wrap** (owner's idea, to
   avoid scrolling all the way back): once the index is held at an end, keep pushing the SAME way for
   a further `3 × step` and it wraps to the other end (last → first, or first → last), continuing
   from there. The push distance restarts after each wrap, so a long swipe wraps at most once per
   3-step push. Returns `Step.Ended(atEnd)` exactly once each time the index first reaches an end
   (the caller gives a small haptic tick, so the stop is felt), and `Step.Wrapped` on a wrap (a
   stronger tick). After a wrap, the formula restarts from the wrap point (start := the new index,
   dx measured from where the wrap happened). Vertical travel is ignored. Each `move` returns `ShowFrame` only when the index
   CHANGES, else `Nothing`.
5. **BRUSH:** exactly JB-2.16a's `SizeOpacityDrag` (axis lock, exponential size, linear opacity);
   returns `Brush` whenever a value changes.
6. Non-finite offsets → `Nothing`. `move` before `begin` or after `end` → `Nothing`.

## Tests
1. Auto mode: canvas board → BRUSH; animation board with 1 frame → BRUSH; with 2 → FRAMES; no
   active board → BRUSH.
2. Override: tap on the animation board → BRUSH; switch active board to another animation board →
   FRAMES (override forgotten); switch back → FRAMES (still forgotten). Tap twice → automatic.
   Override to FRAMES on a canvas board with 0 frames is refused.
3. Mode fixed: begin in FRAMES, change the doc so the badge would say BRUSH, keep moving → still
   `ShowFrame` steps.
4. FRAMES: 10 frames, start at 4, dx = −36 → ShowFrame(5); −72 → 6; tiny moves in between return
   Nothing; density 2 doubles the step. Right to the start: reaching 0 returns Ended(0, false) once;
   pushing on by < 3 steps → Nothing; by 3 steps → Wrapped(9); keep pushing → 8, 7 … Symmetric at
   the last frame (Ended(9, true), then Wrapped(0)). Reversing direction at an end never wraps.
5. BRUSH: same numbers as JB-2.16a tests 2 and 4, through this class.
6. move without begin → Nothing; NaN → Nothing.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file; no Android. Do not reimplement the size/opacity maths — call JB-2.16a.

## Definition of done
Tests pass (paste) · commit `JB-3.08a: three-finger swipe logic` · ROADMAP row → 🟧 Built.

## Questions
- Answered by the owner 2026-09-29: stop at the ends, with push-through to wrap (Decision 4).
- Builder (JB-3.08a, built, tests unrun by the builder). **Where the push clock starts.** Decision 4
  says "once the index is held at an end, keep pushing the SAME way for a further `3 × step`". I read
  "held" as the mathematical clamp, so the push is measured in px from a WHOLE number of steps past
  the begin index (`baseIndex × step`), not from the `move` that happened to report the end. With
  10 frames from frame 4 that means `Ended(0,false)` at dx = 126 (3.53 steps, the first sample past
  the clamp) and `Wrapped(9)` at dx = 252 (144 + 3×36). Reading it the other way gives `Ended` at
  144 and `Wrapped` at 252 as well, but the wrap then depends on how finely `move` was called. I
  chose the reading that makes the wrap a pure function of `dxScreen`, because `move` is documented
  as taking the TOTAL offset. **Confirm, or say "count from the sample that reported Ended".**
- Builder: **a gesture that BEGINS on an end frame.** It has not "reached" anything, so it gets no
  `Ended` tick — but the push clock runs from dx = 0, so 3 steps of pushing from finger-down wraps.
  A begin at frame 9 of 10, pushed −108 px, returns `Wrapped(0)`.
- Builder: **the end just wrapped onto gets no `Ended`.** A wrap returns `Wrapped` and suppresses
  `Ended` until the index leaves that end again, so one arrival is one tick, not two.
- Builder: Decision 2's refusal is read as covering a CANVAS board too (it cannot legally hold
  frames, so it is always < 2) and a one-frame ANIMATION board; both keep the badge on BRUSH.
