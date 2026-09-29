package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.JbDocument

import kotlin.math.round

/**
 * Which of the two things a three-finger swipe does on this board. The corner badge draws this,
 * and the whole of the decision that goes with it is [ThreeFingerSwipe].
 */
enum class SwipeMode {
    /** Flip frames of the active animation board. */
    FRAMES,

    /** Adjust brush size (across) or opacity (up/down) — JB-2.16a's maths, unchanged. */
    BRUSH,
}

/**
 * The context-aware three-finger swipe. JB-3.08a, the owner's ruling on blueprint §6 question 1.
 *
 * One gesture, two jobs, and the document decides which: on an ANIMATION board with at least
 * [MIN_FLIPPABLE_FRAMES] frames a swipe **flips frames**; anywhere else — no active board, a canvas
 * board, an animation board with one frame — the same three fingers **adjust the brush**. The
 * badge in the corner shows which, and tapping it flips to the other one (Decision 2).
 *
 * There is no view, no clock and no platform type in here: the shell hands it numbers and reads
 * [Step]s back, so all of it is unit-testable on a computer. Finding three fingers and drawing the
 * badge are JB-3.08b.
 *
 * Three rules carry the whole contract:
 *
 *  1. **The mode is fixed at [begin]** (Decision 3). A frame added mid-gesture can change what
 *     [badge] says, but a gesture already in progress keeps the mode it started with, so a swipe
 *     cannot turn into a size change under the person's fingers.
 *  2. **The ends stop, and a push through them wraps** (Decision 4, owner 2026-09-29). The index is
 *     `clamp(start − round(dx / step), 0, last)`; once it is held at an end, [WRAP_PUSH_STEPS] more
 *     steps in the SAME direction carry it to the other end and the count starts again. Pushing the
 *     other way from an end never wraps: it simply walks back through the frames.
 *  3. **The brush maths is JB-2.16a's**, called rather than copied — [SizeOpacityDrag] is handed the
 *     total offset and read back, so the control on the canvas and this gesture cannot drift apart
 *     in feel.
 *
 * Every number from a gesture is guarded the way the engine guards everything (LEAD_RULINGS R1): a
 * non-finite offset is ignored, a [density] that is not a usable number is read as 1 (the same guard
 * and the same reason as [SizeOpacityDrag]'s), and a [move] before [begin] or after [end] does
 * nothing at all.
 */
class ThreeFingerSwipe(val density: Float = 1f) {

    // ---------------------------------------------------------------- the badge

    /**
     * The badge-tap overrides, ONE PER BOARD (Decision 2, as ruled in LEAD_RULINGS R25).
     *
     * The owner keyed the mode to the active board (blueprint §6), so an override is a statement
     * about ONE board and stays there: turn frame-flipping off on the animation, glance at the
     * sketch board, come back — the animation is as you left it. Nothing is forgotten by looking
     * elsewhere.
     *
     * Keyed by board id, so [badge] can stay a pure READ. It used to drop the override the moment it
     * was asked about another board, which meant merely DRAWING a second badge (or a test asking about
     * two documents) silently destroyed the person's choice.
     */
    private val overrides = HashMap<String, SwipeMode>()

    /**
     * What the badge shows right now for [doc]'s active board, with any override applied.
     *
     * A pure read: it changes nothing, so asking about any board any number of times is harmless. It
     * is allowed to change DURING a gesture (Decision 3) — it is what the badge draws — so it is never
     * read as "the mode this gesture is in". [begin] is what reads it, once.
     */
    fun badge(doc: JbDocument): SwipeMode {
        val boardId = doc.activeBoardId ?: return automatic(doc)
        // Only BRUSH is ever stored: an override to FRAMES is refused in [tapBadge] on any board that
        // cannot flip, and on one that can, FRAMES is already the automatic answer.
        return overrides[boardId] ?: automatic(doc)
    }

    /**
     * The badge was tapped: this active board's answer becomes the OTHER mode (Decision 2).
     *
     * Tapping again drops the override and the board goes back to its automatic answer, so the
     * badge is always one tap from both answers and never needs a third state.
     *
     * An override to [SwipeMode.FRAMES] on a board that cannot be flipped — fewer than
     * [MIN_FLIPPABLE_FRAMES] frames, or not an ANIMATION board at all — is REFUSED and the badge
     * stays [SwipeMode.BRUSH]: there is nothing there to flip, and a badge offering a mode the
     * gesture cannot honour is worse than no badge.
     *
     * Overrides of boards the document no longer has are dropped here (a tap is the one place this
     * class is allowed to write), so the map cannot grow past the number of boards ever tapped.
     */
    fun tapBadge(doc: JbDocument) {
        overrides.keys.retainAll(doc.boards.map { it.id }.toSet())
        val boardId = doc.activeBoardId ?: return
        val auto = automatic(doc)
        if (badge(doc) != auto) {
            overrides.remove(boardId)
            return
        }
        val flipped = if (auto == SwipeMode.FRAMES) SwipeMode.BRUSH else SwipeMode.FRAMES
        if (flipped == SwipeMode.FRAMES && frameCountOf(doc) < MIN_FLIPPABLE_FRAMES) {
            overrides.remove(boardId)
            return
        }
        overrides[boardId] = flipped
    }

    /** Decision 1: flip frames only where there are frames to flip. Everything else is the brush. */
    private fun automatic(doc: JbDocument): SwipeMode =
        if (frameCountOf(doc) >= MIN_FLIPPABLE_FRAMES) SwipeMode.FRAMES else SwipeMode.BRUSH

    /**
     * How many frames the active board has, or 0 when there is no board, it is not an
     * [BoardKind.ANIMATION] board, or its id names nothing in the document. Read defensively
     * because a stale `activeBoardId` is a normal thing for an undo stack to hand over, and the
     * answer is then simply "no frames", never an exception.
     */
    private fun frameCountOf(doc: JbDocument): Int {
        val active = doc.activeBoardId ?: return 0
        val board = doc.boards.firstOrNull { it.id == active } ?: return 0
        if (board.kind != BoardKind.ANIMATION) return 0
        return board.frames.size
    }

    // ---------------------------------------------------------------- the gesture

    sealed class Step {
        /** Show frame [index] of the active board (already clamped to 0 until frames − 1). */
        data class ShowFrame(val index: Int) : Step()

        /** The brush, in DOCUMENT px, and its opacity. */
        data class Brush(val size: Float, val opacity: Float) : Step()

        /** The index just reached the first (atEnd = false) or last (true) frame and stops there. */
        data class Ended(val index: Int, val atEnd: Boolean) : Step()

        /** Pushed through an end: now showing [index] at the other end. */
        data class Wrapped(val index: Int) : Step()

        object Nothing : Step()
    }

    // Decision 5's guard, the same one and the same reason as SizeOpacityDrag's: a zero or broken
    // density would make every step zero and the whole gesture dead on the first pixel.
    private val dp: Float = if (density.isFinite() && density > 0f) density else 1f

    /** Decision 4: the screen px that flips one frame. Never 0, because [dp] is guarded. */
    private val stepPx: Float = STEP_DP * dp

    private var running = false
    private var mode = SwipeMode.BRUSH
    private var frameCount = 0
    private var lastFrame = 0

    /** The index on screen, and the index/dx the running formula is measured from. */
    private var index = 0
    private var baseIndex = 0
    private var baseDx = 0f

    /** True once [Step.Ended] has been handed out for the end the index is sitting at. */
    private var endedSent = true

    private var brush: SizeOpacityDrag? = null
    private var brushSize = 0f
    private var brushOpacity = 0f

    /**
     * Three fingers came down. [frameIndex] is the frame showing, [size] and [opacity] the brush as
     * it now is, [screenPerDoc] the zoom — `ViewTransform.zoom`, screen px per document px.
     *
     * The mode is READ HERE AND NOW (Decision 3) and nowhere else: this is the one place a gesture
     * decides what it is, and everything after it — [move]s, a frame added, a badge tapped — is
     * already too late to change. The frame count is read here for the same reason, so a board that
     * loses frames mid-swipe cannot leave the arithmetic pointing past its own end.
     *
     * A [frameIndex] outside the board is pulled into it rather than refused: it comes from a
     * playhead and a clamped index is what the screen is showing anyway.
     */
    fun begin(doc: JbDocument, frameIndex: Int, size: Float, opacity: Float, screenPerDoc: Float) {
        mode = badge(doc)
        running = true
        frameCount = frameCountOf(doc)
        lastFrame = if (frameCount > 0) frameCount - 1 else 0
        index = frameIndex.coerceIn(0, lastFrame)
        baseIndex = index
        baseDx = 0f
        // A gesture that STARTS at an end has not reached it, so there is no tick to give: only a
        // change during the gesture ends [Step.Ended].
        endedSent = index == 0 || index == lastFrame
        brush = null
        if (mode == SwipeMode.BRUSH) {
            val drag = SizeOpacityDrag(size, opacity, screenPerDoc, dp)
            brush = drag
            brushSize = drag.size
            brushOpacity = drag.opacity
        }
    }

    /**
     * Total centroid offset since [begin], in screen px (+x right, +y down).
     *
     * The TOTAL, never the last step — the same rule as [SizeOpacityDrag], so a swipe assembled
     * from two-pixel frames flips the same frames as one built from sixty-pixel ones.
     */
    fun move(dxScreen: Float, dyScreen: Float): Step {
        if (!running) return Step.Nothing
        if (!dxScreen.isFinite()) return Step.Nothing
        if (!dyScreen.isFinite()) return Step.Nothing
        if (mode == SwipeMode.FRAMES) return stepFrames(dxScreen)
        return stepBrush(dxScreen, dyScreen)
    }

    /** The fingers are up. The badge override stays — it lasts as long as the canvas screen does. */
    fun end() {
        running = false
        brush = null
    }

    // ---------------------------------------------------------------- Decision 4: the flip

    /**
     * One flip step, and the whole of Decision 4.
     *
     * `index = clamp(baseIndex − round(shifted / step), 0, last)` — left is next, like turning a
     * page, right is previous. Vertical travel is ignored entirely: the axis belongs to the brush
     * mode, and a flip that also turned on the diagonal would not feel like turning a page.
     *
     * The push-through is measured from a WHOLE number of steps past the end, not from the sample
     * that happened to notice the end: at `shifted = baseIndex × step` the finger has travelled
     * exactly `baseIndex` steps, and the index is exactly 0. Counting from there makes the wrap a
     * property of [dxScreen] alone, so a fling arriving as one enormous sample and the same fling
     * arriving in sixty small ones wrap at the same place and never twice for one push.
     *
     * Wrapping restarts the whole formula from the wrap point ([baseIndex] := the new index, dx
     * measured from where the wrap happened), which is also why continuing to push runs on past
     * the new end in the direction the finger is already going: at the last frame, `baseIndex − r`
     * with a rising dx counts down, and at the first frame it counts up.
     */
    private fun stepFrames(dxScreen: Float): Step {
        val step = stepPx.toDouble()
        val shifted = (dxScreen - baseDx).toDouble()
        val held = clampIndex(baseIndex - round(shifted / step), lastFrame)
        val atFirst = held == 0
        val atLast = held == lastFrame

        if (atFirst || atLast) {
            val push = if (atLast) {
                (baseIndex - lastFrame).toDouble() * step - shifted
            } else {
                shifted - baseIndex.toDouble() * step
            }
            if (push >= WRAP_PUSH_STEPS.toDouble() * step) {
                val wrapped = if (atLast) 0 else lastFrame
                index = wrapped
                baseIndex = wrapped
                baseDx = dxScreen
                endedSent = true
                return Step.Wrapped(wrapped)
            }
        }

        if (held == index) {
            if ((atFirst || atLast) && !endedSent) {
                endedSent = true
                return Step.Ended(held, atLast)
            }
            return Step.Nothing
        }

        index = held
        if (atFirst || atLast) {
            // The index CHANGED and it landed on an end, so this is the arrival [Step.Ended] is
            // for; [Step.ShowFrame] is for the frames in between.
            endedSent = true
            return Step.Ended(held, atLast)
        }
        endedSent = false
        return Step.ShowFrame(held)
    }

    private fun clampIndex(value: Double, last: Int): Int {
        val whole = round(value)
        if (whole < 0.0) return 0
        if (whole > last.toDouble()) return last
        return whole.toInt()
    }

    // ---------------------------------------------------------------- Decision 5: the brush

    /** JB-2.16a, called: the axis lock, the exponential size and the linear opacity are all its. */
    private fun stepBrush(dxScreen: Float, dyScreen: Float): Step {
        val drag = brush ?: return Step.Nothing
        drag.move(dxScreen, dyScreen)
        if (drag.size == brushSize && drag.opacity == brushOpacity) return Step.Nothing
        brushSize = drag.size
        brushOpacity = drag.opacity
        return Step.Brush(drag.size, drag.opacity)
    }

    companion object {
        /** Screen px of horizontal travel per frame, in dp, scaled by [density]. */
        const val STEP_DP = 36f

        /** Whole steps past an end that carry it through to the other end. */
        const val WRAP_PUSH_STEPS = 3

        /** Below this many frames there is nothing to flip, so the badge cannot offer FRAMES. */
        const val MIN_FLIPPABLE_FRAMES = 2
    }
}
