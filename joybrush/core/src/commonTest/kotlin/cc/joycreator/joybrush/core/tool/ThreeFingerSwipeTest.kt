package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.tool.ThreeFingerSwipe.Step
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JB-3.08a, spec Tests 1–6, in that order.
 *
 * Every expected value carries its derivation (LEAD_RULINGS R9). The two rules the derivations lean
 * on, so no number here is a guess:
 *
 *  - **The step is [ThreeFingerSwipe.STEP_DP] × density = 36 px at density 1.** Every dx below is
 *    chosen either an exact number of steps (so `round(dx / 36)` is that integer under any rounding
 *    rule) or a few px either side of one (so it rounds the same way under every rule). Nothing here
 *    depends on what a half step rounds to.
 *  - **`round` is Kotlin's** (`kotlin.math.round`, ties to even), and it is used exactly as
 *    Decision 4 writes it: `clamp(start − round(dx / step), 0, frames − 1)`.
 */
class ThreeFingerSwipeTest {

    private val eps = 1e-4f

    // ---- the fixtures ----------------------------------------------------------------------------

    private fun canvasBoard(id: String = "b-canvas"): Board = Board(
        id = id,
        name = "Canvas",
        kind = BoardKind.CANVAS,
        rect = RectPx(0, 0, 800, 600),
    )

    /** An ANIMATION board at 12 fps holding `frames` frames called f1..fn, in that order. */
    private fun animBoard(id: String, frames: Int): Board = Board(
        id = id,
        name = "Anim",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, 128, 64),
        fps = 12f,
        frames = (0 until frames).map { Frame("f${it + 1}") },
    )

    /**
     * A document with [boards] and [active] active, one static PAINT layer. Nothing here is animated
     * in a board, because the swipe only ever reads the boards.
     */
    private fun docOf(active: String?, boards: List<Board>): JbDocument = JbDocument(
        id = "d1",
        name = "Test",
        boards = boards,
        layers = listOf(
            Layer(id = "l1", name = "Layer", kind = LayerKind.PAINT, cels = listOf(Cel("c1"))),
        ),
        activeLayerId = "l1",
        activeBoardId = active,
    )

    /** Canvas board + two animation boards: `b-anim1` with [anim1Frames] frames, `b-anim2` with 3. */
    private fun fixture(anim1Frames: Int = 10, active: String? = "b-anim1"): JbDocument = docOf(
        active = active,
        boards = listOf(
            canvasBoard(),
            animBoard("b-anim1", anim1Frames),
            animBoard("b-anim2", 3),
        ),
    )

    /** The canvas document: always the brush, which is what most of the BRUSH tests want. */
    private fun canvasDoc(): JbDocument = fixture(active = "b-canvas")

    /** Ten frames, showing frame 4, so the arithmetic in Test 4 has room on both sides. */
    private fun swipe(density: Float = 1f, doc: JbDocument = fixture()): ThreeFingerSwipe {
        val s = ThreeFingerSwipe(density)
        s.begin(doc, 4, 10f, 0.5f, 1f)
        return s
    }

    // ---- 1: the automatic mode -------------------------------------------------------------------

    @Test
    fun automaticModeIsFramesOnlyOnAnAnimationBoardWithAtLeastTwoFrames() {
        val s = ThreeFingerSwipe()

        // A canvas board: no frames to flip, so the three fingers are the brush's.
        assertEquals(SwipeMode.BRUSH, s.badge(fixture(active = "b-canvas")))

        // An animation board with ONE frame: flipping means flipping between two things, and there
        // is only one, so the badge says brush.
        assertEquals(SwipeMode.BRUSH, s.badge(fixture(anim1Frames = 1)))
        assertEquals(SwipeMode.BRUSH, s.badge(fixture(anim1Frames = 0)))

        // Two frames is the smallest board that can be flipped at all.
        assertEquals(SwipeMode.FRAMES, s.badge(fixture(anim1Frames = 2)))
        assertEquals(SwipeMode.FRAMES, s.badge(fixture(anim1Frames = 10)))

        // No active board at all, and an active board id naming nothing: both are "no frames".
        assertEquals(SwipeMode.BRUSH, s.badge(fixture(active = null)))
        assertEquals(SwipeMode.BRUSH, s.badge(fixture(active = "b-nope")))

        // A frame count nobody can reach is the same answer, not an exception.
        assertEquals(SwipeMode.BRUSH, s.badge(fixture(anim1Frames = -4)))
    }

    // ---- 2: the badge tap ------------------------------------------------------------------------

    @Test
    fun anOverrideIsRememberedForTheBoardItWasMadeOnAndForgottenOnAnother() {
        val s = ThreeFingerSwipe()
        val onAnim1 = fixture()
        val onAnim2 = fixture(active = "b-anim2")

        assertEquals(SwipeMode.FRAMES, s.badge(onAnim1))
        s.tapBadge(onAnim1)
        assertEquals(SwipeMode.BRUSH, s.badge(onAnim1))

        // A different board: the override is ignored, and dropped on the spot.
        assertEquals(SwipeMode.FRAMES, s.badge(onAnim2))

        // …so coming back does NOT bring it back, which is the spec's "switch back → FRAMES".
        assertEquals(SwipeMode.FRAMES, s.badge(onAnim1))

        // A fresh override, this time on b-anim2 — and each board keeps its own answer.
        s.tapBadge(onAnim2)
        assertEquals(SwipeMode.BRUSH, s.badge(onAnim2))
        assertEquals(SwipeMode.FRAMES, s.badge(onAnim1))

        // Tapping again returns to the automatic answer; tapping once more overrides again.
        s.tapBadge(onAnim2)
        assertEquals(SwipeMode.FRAMES, s.badge(onAnim2))
        s.tapBadge(onAnim2)
        assertEquals(SwipeMode.BRUSH, s.badge(onAnim2))
    }

    @Test
    fun anOverrideToFramesOnABoardThatCannotFlipIsRefused() {
        val s = ThreeFingerSwipe()

        // A canvas board: 0 frames, nothing to flip, so the badge stays BRUSH.
        val canvas = canvasDoc()
        s.tapBadge(canvas)
        assertEquals(SwipeMode.BRUSH, s.badge(canvas))
        s.tapBadge(canvas)
        assertEquals(SwipeMode.BRUSH, s.badge(canvas))

        // One frame is not two, and is refused the same way.
        val oneFrame = fixture(anim1Frames = 1)
        s.tapBadge(oneFrame)
        assertEquals(SwipeMode.BRUSH, s.badge(oneFrame))

        // The same document once it has a second frame DOES take the override, which is what makes
        // the refusal "not yet" rather than "never".
        val twoFrames = fixture(anim1Frames = 2)
        s.tapBadge(twoFrames)
        assertEquals(SwipeMode.BRUSH, s.badge(twoFrames))

        // No active board: there is no board to remember the tap against, so nothing happens.
        val none = fixture(active = null)
        s.tapBadge(none)
        assertEquals(SwipeMode.BRUSH, s.badge(none))
    }

    @Test
    fun anOverrideReachesTheGesture() {
        val s = ThreeFingerSwipe()
        val doc = fixture()
        s.tapBadge(doc)
        assertEquals(SwipeMode.BRUSH, s.badge(doc))

        s.begin(doc, 4, 10f, 0.5f, 1f)
        // 13 px locks the SIZE axis, which leaves the value alone, so nothing is reported…
        assertEquals(Step.Nothing, s.move(13f, 0f))
        // …and 173 px is one doubling later, which is the brush, not a frame.
        val step = s.move(173f, 0f) as Step.Brush
        assertEquals(20f, step.size, eps)
    }

    // ---- 3: the mode is fixed at begin ------------------------------------------------------------

    @Test
    fun theModeIsFixedAtBeginEvenWhenTheBadgeChangesUnderneath() {
        val s = ThreeFingerSwipe()
        val anim = fixture()
        val canvas = fixture(active = "b-canvas")

        assertEquals(SwipeMode.FRAMES, s.badge(anim))
        s.begin(anim, 4, 10f, 0.5f, 1f)

        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))

        // The document now says canvas, so the badge says brush…
        assertEquals(SwipeMode.BRUSH, s.badge(canvas))
        // …and the gesture in progress is still a flip: frame 6, frame 7, never a Brush step.
        assertEquals(Step.ShowFrame(6), s.move(-72f, 0f))
        assertEquals(Step.ShowFrame(7), s.move(-108f, 0f))
        assertEquals(Step.ShowFrame(8), s.move(-144f, 0f))

        s.end()
        assertEquals(Step.Nothing, s.move(-180f, 0f))
    }

    @Test
    fun theModeIsFixedAtBeginWhenItBeginsAsTheBrush() {
        val s = ThreeFingerSwipe()
        val canvas = canvasDoc()
        val anim = fixture()

        s.begin(canvas, 0, 10f, 0.5f, 1f)
        assertEquals(Step.Nothing, s.move(11f, 0f))
        assertEquals(Step.Nothing, s.move(13f, 0f))

        // Switched to the animation board mid-gesture: the badge now offers frames…
        assertEquals(SwipeMode.FRAMES, s.badge(anim))
        // …and this gesture still sizes the brush: 173 px is one doubling of 10 px past the lock
        // at 13 px, which is SizeOpacityDrag's own arithmetic.
        val step = s.move(173f, 0f) as Step.Brush
        assertEquals(20f, step.size, eps)
        assertEquals(0.5f, step.opacity, eps)
    }

    // ---- 4: the flip, the stop and the push-through ------------------------------------------------

    @Test
    fun oneStepFlipsOneFrameAndTinyMovesDoNothing() {
        val s = swipe()

        // −36 px is exactly one step: round(−36/36) = −1, so 4 − (−1) = 5.
        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))

        // 37 px is one step and a bit: round(−37/36) = −1, so still frame 5 and nothing to say.
        assertEquals(Step.Nothing, s.move(-37f, 0f))

        // −72 px is two steps: 4 + 2 = 6. 73 px is two steps and a bit, so still nothing.
        assertEquals(Step.ShowFrame(6), s.move(-72f, 0f))
        assertEquals(Step.Nothing, s.move(-73f, 0f))

        // Positive is the other way: one step back is frame 3.
        assertEquals(Step.ShowFrame(3), s.move(36f, 0f))
        assertEquals(Step.Nothing, s.move(35f, 0f))
    }

    @Test
    fun densityDoublesTheStep() {
        // At density 2 the step is 72 px. 35 px is under half a step (0.486) and does nothing,
        // where at density 1 it would have flipped.
        val s = swipe(density = 2f)
        assertEquals(Step.Nothing, s.move(-35f, 0f))
        // 37 px is just over half a step (0.514), so it flips exactly one frame.
        assertEquals(Step.ShowFrame(5), s.move(-37f, 0f))
        assertEquals(Step.Nothing, s.move(-107f, 0f))

        // 109 px is 1.51 steps, so it rounds to 2 and 4 + 2 = 6: the two-frame move that took
        // −72 px at density 1 takes −108 px here. 107 px is 1.49 steps, so it is still frame 5.
        assertEquals(Step.ShowFrame(6), s.move(-109f, 0f))
    }

    @Test
    fun theFirstEndStopsTicksOnceAndAPushThroughWrapsToTheLastFrame() {
        val s = swipe()

        assertEquals(Step.ShowFrame(3), s.move(36f, 0f))
        assertEquals(Step.ShowFrame(2), s.move(72f, 0f))
        assertEquals(Step.ShowFrame(1), s.move(108f, 0f))

        // 125 px is 3.47 steps: round = 3, so still frame 1 — the end is a half step further on.
        assertEquals(Step.Nothing, s.move(125f, 0f))

        // 127 px is 3.53 steps: round = 4, so 4 − 4 = 0. The index first reaches the end, which
        // is a tick and not a ShowFrame.
        assertEquals(Step.Ended(0, false), s.move(127f, 0f))

        // Pushing on: the push is measured in px from 4 WHOLE steps (144 px), and it takes 3 more
        // whole steps, 108 px, to carry through. 144 px is 0 px of push, 216 px is 72 px and 234 px
        // is 90 px — none of them reach 108, and every one of them is clamped at frame 0 anyway.
        assertEquals(Step.Nothing, s.move(144f, 0f))
        assertEquals(Step.Nothing, s.move(216f, 0f))
        assertEquals(Step.Nothing, s.move(234f, 0f))

        // 252 px is 144 + 3×36: three whole steps of push, so it carries through to the last frame.
        assertEquals(Step.Wrapped(9), s.move(252f, 0f))

        // The formula restarts from the wrap point, so keeping on in the same direction now walks
        // on from 9: 288 px is one step past 252, giving 8, and 324 px gives 7.
        assertEquals(Step.ShowFrame(8), s.move(288f, 0f))
        assertEquals(Step.ShowFrame(7), s.move(324f, 0f))
    }

    @Test
    fun theLastEndStopsTicksOnceAndAPushThroughWrapsToTheFirstFrame() {
        val s = swipe()

        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))
        assertEquals(Step.ShowFrame(6), s.move(-72f, 0f))
        assertEquals(Step.ShowFrame(7), s.move(-108f, 0f))
        assertEquals(Step.ShowFrame(8), s.move(-144f, 0f))

        // −180 px is five steps left: round(−5) = −5, so 4 + 5 = 9, the last frame. atEnd = true.
        assertEquals(Step.Ended(9, true), s.move(-180f, 0f))

        // The push is measured in px from 5 WHOLE steps left of frame 4, and takes 3 more whole
        // steps, 108 px: −216 is 36 px of push, −252 is 72 px and −270 is 90 px. None reach 108.
        assertEquals(Step.Nothing, s.move(-216f, 0f))
        assertEquals(Step.Nothing, s.move(-252f, 0f))
        assertEquals(Step.Nothing, s.move(-270f, 0f))

        // −288 px is −180 − 3×36: three whole steps of push, so it carries through to frame 0.
        assertEquals(Step.Wrapped(0), s.move(-288f, 0f))

        // On from the wrap point: −324 px is one step past −288, giving frame 1.
        assertEquals(Step.ShowFrame(1), s.move(-324f, 0f))
    }

    @Test
    fun reversingAtAnEndFlipsBackRatherThanWrapping() {
        val s = swipe()

        assertEquals(Step.Ended(0, false), s.move(127f, 0f))

        // The other way is not a push-through: it is 4 − round(−1) = 5, an ordinary flip.
        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))

        // Leaving the end un-ticks it, so coming back to the end ticks again — and once only.
        assertEquals(Step.Ended(0, false), s.move(127f, 0f))
        assertEquals(Step.Nothing, s.move(128f, 0f))

        // And walking back out of the end again is a flip, not a wrap.
        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))
    }

    @Test
    fun aGestureThatBeginsAtAnEndStillWraps() {
        // Nothing was "reached" — the finger went down on frame 0 — so there is no tick for it.
        val s = ThreeFingerSwipe()
        s.begin(fixture(), 0, 10f, 0.5f, 1f)
        assertEquals(Step.Nothing, s.move(-17f, 0f))
        assertEquals(Step.Nothing, s.move(-1f, 0f))

        // One step the other way is frame 1: round(−1) = −1, so 0 + 1 = 1.
        assertEquals(Step.ShowFrame(1), s.move(-36f, 0f))

        // Back to frame 0 and then pushing on: the push is counted from 0 whole steps, so 72 px is
        // two steps of push and 108 px is three.
        val atFirst = ThreeFingerSwipe()
        atFirst.begin(fixture(), 0, 10f, 0.5f, 1f)
        assertEquals(Step.Nothing, atFirst.move(72f, 0f))
        assertEquals(Step.Wrapped(9), atFirst.move(108f, 0f))

        // The same from the last frame: the push is counted from 0 whole steps there too.
        val atLast = ThreeFingerSwipe()
        atLast.begin(fixture(), 9, 10f, 0.5f, 1f)
        assertEquals(Step.Nothing, atLast.move(-72f, 0f))
        assertEquals(Step.Wrapped(0), atLast.move(-108f, 0f))
    }

    @Test
    fun aFlingArrivingInOneSampleAgreesWithTheSameFlingInMany() {
        // One sample of 252 px from frame 4: round(7) = 7, so 4 − 7 = −3, clamped to 0, and the push
        // is 252 − 144 = 108 px = three whole steps. So it wraps.
        val one = swipe()
        assertEquals(Step.Wrapped(9), one.move(252f, 0f))
        assertEquals(Step.ShowFrame(8), one.move(288f, 0f))

        // The same fling split up arrives at exactly the same place, having said the same things on
        // the way (each dx below is an exact number of steps or a few px past one).
        val many = swipe()
        assertEquals(Step.ShowFrame(3), many.move(36f, 0f))
        assertEquals(Step.ShowFrame(2), many.move(72f, 0f))
        assertEquals(Step.ShowFrame(1), many.move(108f, 0f))
        assertEquals(Step.Ended(0, false), many.move(127f, 0f))
        assertEquals(Step.Nothing, many.move(216f, 0f))
        assertEquals(Step.Wrapped(9), many.move(252f, 0f))
        assertEquals(Step.ShowFrame(8), many.move(288f, 0f))
    }

    @Test
    fun verticalTravelIsIgnoredWhenFlipping() {
        val s = swipe()

        assertEquals(Step.Nothing, s.move(0f, -5000f))
        // 17 px across is under half a step (0.472), and the 5000 px down with it changes nothing.
        assertEquals(Step.Nothing, s.move(-17f, 5000f))
        assertEquals(Step.ShowFrame(5), s.move(-36f, -5000f))
    }

    @Test
    fun theFrameShowingAtBeginIsPulledIntoTheBoard() {
        val doc = fixture()

        val past = ThreeFingerSwipe()
        past.begin(doc, 99, 10f, 0.5f, 1f)
        // 99 is pulled to 9, so one step back is frame 8.
        assertEquals(Step.ShowFrame(8), past.move(36f, 0f))

        val before = ThreeFingerSwipe()
        before.begin(doc, -5, 10f, 0.5f, 1f)
        // −5 is pulled to 0, so one step on is frame 1.
        assertEquals(Step.ShowFrame(1), before.move(-36f, 0f))
    }

    // ---- 5: the brush, through this class ---------------------------------------------------------

    @Test
    fun theBrushIsExactlyJB216aSizeMaths() {
        val s = ThreeFingerSwipe()
        s.begin(canvasDoc(), 0, 10f, 0.5f, 1f)

        // 11 px is short of the 12 dp lock, and 13 px locks SIZE without moving the value, so
        // neither reports anything — the axis locking is not a value changing.
        assertEquals(Step.Nothing, s.move(11f, 0f))
        assertEquals(Step.Nothing, s.move(13f, 0f))

        // 173 px is one doubling past the lock at 13: 10 × 2^((173−13)/160) = 20.
        var step = s.move(173f, 0f) as Step.Brush
        assertEquals(20f, step.size, eps)
        assertEquals(0.5f, step.opacity, eps)

        // −147 px is one halving past the lock: 10 × 2^(−1) = 5.
        step = s.move(-147f, 0f) as Step.Brush
        assertEquals(5f, step.size, eps)

        // 10 × 2^(−62.6) is a speck, so the floor holds it at SizeOpacityDrag's own MIN_SIZE.
        step = s.move(-10000f, 0f) as Step.Brush
        assertEquals(SizeOpacityDrag.MIN_SIZE, step.size, eps)

        // 10 × 2^(62.4) is enormous, so the ceiling holds it at BrushValidate's own MAX_SIZE.
        step = s.move(10000f, 0f) as Step.Brush
        assertEquals(SizeOpacityDrag.MAX_SIZE, step.size, eps)

        // No further travel, no further change, so no further report.
        assertEquals(Step.Nothing, s.move(10000f, 0f))
        assertEquals(0.5f, step.opacity, eps)
    }

    @Test
    fun theBrushIsExactlyJB216aOpacityMaths() {
        val s = ThreeFingerSwipe()
        s.begin(canvasDoc(), 0, 10f, 0.5f, 1f)

        // −13 px up locks OPACITY without moving the value, so there is nothing to report yet.
        assertEquals(Step.Nothing, s.move(0f, -13f))

        // −163 px is 150 px further up: 0.5 − (−150/300) = 1.0. The size axis never moved.
        var step = s.move(0f, -163f) as Step.Brush
        assertEquals(1.0f, step.opacity, eps)
        assertEquals(10f, step.size, eps)

        // 287 px is 300 px back down: 0.5 − (300/300) = −0.5, so the floor holds it at 0.01.
        step = s.move(0f, 287f) as Step.Brush
        assertEquals(SizeOpacityDrag.MIN_OPACITY, step.opacity, eps)
        assertEquals(10f, step.size, eps)

        // And up again it comes off the floor rather than sticking there.
        step = s.move(0f, -163f) as Step.Brush
        assertEquals(1.0f, step.opacity, eps)
    }

    // ---- 6: the guards ---------------------------------------------------------------------------

    @Test
    fun aMoveBeforeBeginOrAfterEndDoesNothing() {
        val s = ThreeFingerSwipe()

        assertEquals(Step.Nothing, s.move(36f, 0f))
        assertEquals(Step.Nothing, s.move(Float.NaN, 0f))

        s.begin(fixture(), 4, 10f, 0.5f, 1f)
        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))

        s.end()
        assertEquals(Step.Nothing, s.move(-72f, 0f))
        assertEquals(Step.Nothing, s.move(0f, 0f))

        // begin() again is a new gesture, so it is alive again.
        s.begin(fixture(), 4, 10f, 0.5f, 1f)
        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))
    }

    @Test
    fun aNonFiniteOffsetIsIgnoredAndTheGestureCarriesOn() {
        val s = swipe()

        assertEquals(Step.Nothing, s.move(Float.NaN, 0f))
        assertEquals(Step.Nothing, s.move(Float.POSITIVE_INFINITY, 0f))
        assertEquals(Step.Nothing, s.move(Float.NEGATIVE_INFINITY, 0f))
        assertEquals(Step.Nothing, s.move(-36f, Float.NaN))
        assertEquals(Step.Nothing, s.move(-36f, Float.POSITIVE_INFINITY))

        // The gesture is untouched by them: frame 5, then frame 6.
        assertEquals(Step.ShowFrame(5), s.move(-36f, 0f))
        assertEquals(Step.ShowFrame(6), s.move(-72f, 0f))
    }

    @Test
    fun aDensityThatIsNotAUsableNumberIsReadAsOne() {
        val zero = swipe(density = 0f)
        assertEquals(Step.ShowFrame(5), zero.move(-36f, 0f))

        val notANumber = swipe(density = Float.NaN)
        assertEquals(Step.ShowFrame(5), notANumber.move(-36f, 0f))

        val negative = swipe(density = -2f)
        assertEquals(Step.ShowFrame(5), negative.move(-36f, 0f))
    }

    @Test
    fun aBrushGestureIgnoresNonFiniteOffsetsToo() {
        val s = ThreeFingerSwipe()
        s.begin(canvasDoc(), 0, 10f, 0.5f, 1f)

        assertEquals(Step.Nothing, s.move(Float.NaN, 0f))
        assertEquals(Step.Nothing, s.move(13f, Float.POSITIVE_INFINITY))

        // The two non-finite moves are IGNORED, not fatal: the gesture is still alive afterwards.
        //
        // Two things about SizeOpacityDrag have to be true for the recovery move below to report
        // anything, and both are its Decision 1/2 rather than anything to do with the NaN:
        //
        //  - the first move past LOCK_TRAVEL_DP DECIDES the axis and is measured FROM ITS OWN POINT,
        //    so it lands on exactly the starting value. It is a move of its own, not a dead gesture.
        //  - size is exponential from the lock point, not linear, so the number is start * 2^(dx/160).
        //
        // So the recovery move needs two calls: one to lock, one to actually move the number. A
        // single 173 px call is the LOCK call, and returns Nothing for the most boring reason there
        // is — nothing changed yet.
        assertEquals(Step.Nothing, s.move(100f, 0f))
        val step = s.move(200f, 0f) as Step.Brush
        assertEquals(10f * 2f.pow(100f / 160f), step.size, eps)
    }
}
