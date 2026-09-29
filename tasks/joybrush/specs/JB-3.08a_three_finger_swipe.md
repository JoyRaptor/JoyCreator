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
   the NEXT frame (like turning a page), right shows the previous. `index = clamp(start − round(dx /
   step), 0, frames − 1)` — clamped, not wrapped: a flip-book stops at its ends, and wrap-around at
   speed is disorienting. Vertical travel is ignored. Each `move` returns `ShowFrame` only when the
   index CHANGES, else `Nothing` (so the caller redraws only when needed).
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
4. FRAMES: 10 frames, start at 4, dx = −36 → ShowFrame(5); −72 → 6; +200 → clamps at 0; tiny
   moves in between return Nothing; density 2 doubles the step.
5. BRUSH: same numbers as JB-2.16a tests 2 and 4, through this class.
6. move without begin → Nothing; NaN → Nothing.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file; no Android. Do not reimplement the size/opacity maths — call JB-2.16a.

## Definition of done
Tests pass (paste) · commit `JB-3.08a: three-finger swipe logic` · ROADMAP row → 🟧 Built.

## Questions
- For the owner (answer any time, the constant is one line): at the ends of the animation, should a
  flip STOP (as specified) or WRAP around to the other end?
