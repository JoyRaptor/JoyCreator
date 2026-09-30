package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.AnimOps
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.CelWork
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.NewFrame
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The film strip's geometry, its gesture rules and its five document operations. JB-3.03.
 *
 * THE SEVEN NUMBERS THIS WHOLE FILE IS BUILT ON, and every expectation below is one of them read
 * back off the same fixture: four frames `f0..f3` holding **1, 2, 1, 3** ticks, on a 12 fps board,
 * at **density 1**, where one tick is `TICK_PX_DP × 1` = **44 px**. So the cells are 44, 88, 44, 132
 * and their left edges are 0, 44, 132, 176 and the strip is 308 px wide. Any test here that needs a
 * different number derives it in its own comment, and a test that cannot is a test that gets
 * "fixed" to match whatever the code did.
 *
 * **THE READING RULE, and it is not optional.** [FilmStrip] is built from a `Board` it was GIVEN
 * and never re-reads anything, so the geometry of a hold that has just changed is a property of a
 * NEW strip built on the document the operation RETURNED — that is [draggedStrip] below, and it is
 * pasted exactly as the spec gives it. Asserting `cellWidth` on the original strip after a drag
 * reads the pre-drag hold (f1 is 88 px wide, not 44) and passes while proving nothing. Test 4 and
 * test 12 both assert the ORIGINAL strip's stale number as well, so the staleness is a claim here
 * rather than an accident waiting to happen.
 *
 * The oracle for every document operation is [DocOps.validate]: an operation that hands back a
 * document the validator dislikes has handed back something a renderer cannot draw, so each of
 * those tests asserts the shape it expected and THEN the validation, in that order.
 */
class FilmStripTest {

    /** Ids `gen0`, `gen1`, ... in the order they are asked for, and the order they were asked in. */
    private class Ids {
        private val asked = ArrayList<String>()
        fun next(): String {
            val id = "gen${asked.size}"
            asked.add(id)
            return id
        }

        val seen: List<String> get() = asked
    }

    /**
     * The board every test in this file measures, derived once so no test can quietly change it:
     * `f0` 1 tick, `f1` 2, `f2` 1, `f3` 3 — so the cells are 44, 88, 44 and 132 px at density 1.
     */
    private fun heldFrames(): List<Frame> = listOf(
        Frame("f0", holdFrames = 1),
        Frame("f1", holdFrames = 2),
        Frame("f2", holdFrames = 1),
        Frame("f3", holdFrames = 3),
    )

    /**
     * One canvas board, one ANIMATION board at 12 fps holding [frames], one static PAINT layer and
     * one PAINT layer animated in the animation board (every frame showing its single cel `c-anim`).
     *
     * `AnimOpsTest`'s fixture, copied rather than shared, because `commonTest` fixtures that are
     * shared between rows are shared by edit: a change made for one row's question silently changes
     * another row's arithmetic. Every frame showing ONE cel is deliberate and is what makes test 15
     * able to say "no new cel" and mean it.
     */
    private fun fixture(frames: List<Frame> = heldFrames()): JbDocument {
        var n = 0
        val base = DocOps.newDocument("doc", "Test", 800, 600) { "auto${n++}" }
        val canvas = base.boards.single().copy(id = "b-canvas", name = "Canvas")
        val anim = Board(
            id = "b-anim", name = "Anim", kind = BoardKind.ANIMATION,
            rect = RectPx(0, 0, 128, 64), fps = 12f, frames = frames,
        )
        val background =
            Layer(id = "l-static", name = "Static", kind = LayerKind.PAINT, cels = listOf(Cel("c-static")))
        val animated = Layer(
            id = "l-anim", name = "Anim", kind = LayerKind.PAINT, animatedIn = "b-anim",
            cels = listOf(Cel("c-anim")), frameCel = frames.associate { it.id to "c-anim" },
        )
        return base.copy(
            boards = listOf(canvas, anim),
            layers = listOf(background, animated),
            activeLayerId = "l-anim",
            activeBoardId = "b-anim",
        )
    }

    private fun JbDocument.board(id: String = "b-anim"): Board = boards.first { it.id == id }

    private fun JbDocument.frame(id: String): Frame = board().frames.first { it.id == id }

    private fun JbDocument.layer(id: String): Layer = layers.first { it.id == id }

    private fun JbDocument.frameIds(id: String = "b-anim"): List<String> = board(id).frames.map { it.id }

    /** A strip rebuilt on the document the drag RETURNED — the only strip that has the new hold. */
    private fun draggedStrip(doc: JbDocument, frameId: String, dxPx: Float): FilmStrip {
        val strip = FilmStrip(doc.board("b-anim"))
        val after =
            strip.setHoldByDrag(doc, "b-anim", frameId, strip.holdOf(doc, "b-anim", frameId), dxPx)
        return FilmStrip(after.board("b-anim"))
    }

    /** The gesture, at density 1, on the document it is given. */
    private fun gestureOn(doc: JbDocument) = FilmStripGesture(doc, "b-anim")

    // ---------- 1. the cell IS the hold ----------

    /**
     * `holdFrames × 44`: f0 = 1×44 = 44, f1 = 2×44 = 88, f2 = 1×44 = 44, f3 = 3×44 = 132.
     *
     * Left edges are the widths before them: 0; 0+44 = 44; 44+88 = 132; 132+44 = 176. The strip is
     * the sum, 44+88+44+132 = 308, and it is ALSO f3's right edge, 176+132 = 308, which is the
     * second half of the claim: the strip has no width of its own, it is the last cell's edge.
     */
    @Test
    fun aCellIsAsWideAsItsHold() {
        val doc = fixture()
        assertEquals(
            listOf(1, 2, 1, 3),
            doc.board().frames.map { it.holdFrames },
            "the fixture, so every number below is traceable to it",
        )
        val strip = FilmStrip(doc.board())

        assertEquals(44f, strip.tickPx, "44 dp at density 1")
        assertEquals(listOf(44f, 88f, 44f, 132f), (0..3).map { strip.cellWidth(it) })
        assertEquals(listOf(0f, 44f, 132f, 176f), (0..3).map { strip.cellLeft(it) })
        assertEquals(308f, strip.stripWidth(), "44 + 88 + 44 + 132")
        assertEquals(strip.cellLeft(3) + strip.cellWidth(3), strip.stripWidth(), "and the last cell's right edge")
    }

    /**
     * The same board at 24 fps, and NOTHING about the strip changes.
     *
     * One tick is `1000 / 12` = 83.33 ms at 12 fps and `1000 / 24` = 41.67 ms at 24 fps — a factor of
     * two, from nothing but the board's rate. So any strip that measured in TIME would change every
     * cell's width when only the fps changed, and the same hold would be a different number of
     * pixels on a different board. A tick is 44 dp and not a duration, so f3 is 3 × 44 = 132 px at
     * both rates, and 132 must not be a coincidence of 12 fps. The two boards really are different
     * in time, which is asserted rather than assumed: 7 ticks is 583.3 ms at 12 fps and 291.7 ms at
     * 24.
     */
    @Test
    fun aCellIsNotSizedByWallTime() {
        val at12 = fixture().board()
        val at24 = at12.copy(fps = 24f)
        assertTrue(
            AnimOps.totalDurationMs(at24) < AnimOps.totalDurationMs(at12),
            "the two boards must really differ in time, or this test is about nothing",
        )

        assertEquals(132f, FilmStrip(at24).cellWidth(3), "3 ticks × 44 px, whatever the fps")
        assertEquals(FilmStrip(at12).cellWidth(3), FilmStrip(at24).cellWidth(3))
    }

    /**
     * 44 dp is 44 px, 110 px and 132 px. The Lead's number for density 3 is 132, and 132 also being
     * f3's width at density 1 is a coincidence of this fixture's holds, not of the constant.
     *
     * Three densities because "44 dp" written once as a pixel is the slip R32 exists to stop, and one
     * density is exactly the test that would let it through.
     */
    @Test
    fun aOneTickFrameIsTheTouchFloorAtEveryDensity() {
        val board = fixture().board()
        assertEquals(44f, FilmStrip(board, 1f).cellWidth(0), "1 tick × 44 × 1")
        assertEquals(110f, FilmStrip(board, 2.5f).cellWidth(0), "1 tick × 44 × 2.5")
        assertEquals(132f, FilmStrip(board, 3f).cellWidth(0), "1 tick × 44 × 3")
    }

    /**
     * 999 ticks drawn as 999 × 44 = **43 956** px at density 1 and 999 × 132 = **131 868** px at
     * density 3. No cap on the drawn width, and no `Float` trouble: 43 956 and 131 868 are both
     * whole numbers well inside a `Float`'s exact-integer range, so the answer is exactly the
     * arithmetic and not the nearest representable thing to it.
     *
     * The cap is on the HOLD and it belongs to `AnimOps` (999 is private there and is not restated
     * in the strip), so the number is asked of the model first and the strip is built on the document
     * that came back. The last line is the reading rule: the ORIGINAL strip still says 44 px for
     * f0, which is a stale number that looks right, and is the reason [draggedStrip] exists.
     *
     * (The old spec's Decision 4 said 43 956 and its test 3 wrote 88 912. The two contradicted each
     * other inside one file, and 43 956 is the one the arithmetic supports.)
     */
    @Test
    fun aNineHundredAndNinetyNineTickFrameStillDrawsItsWholeWidth() {
        val doc = fixture()
        val held = AnimOps.setHold(doc, "b-anim", "f0", 999)
        assertEquals(999, held.frame("f0").holdFrames, "999 is the model's number, asked of the model")

        val at1 = FilmStrip(held.board())
        assertEquals(43_956f, at1.cellWidth(0), "999 × 44, and no cap on a drawn width")
        assertEquals(131_868f, FilmStrip(held.board(), 3f).cellWidth(0), "999 × 132")
        assertEquals(44f, FilmStrip(doc.board()).cellWidth(0), "the ORIGINAL strip is stale by design")
    }

    // ---------- 6. the hit test is half-open ----------

    /**
     * The boundary is 44, 132 and 176, and every one of them is asked from BOTH sides plus the
     * exact pixel: 43.999 → 0, **44 → 1**, 131.999 → 1, **132 → 2**, 175.999 → 2, **176 → 3**, and
     * 0 → 0. A boundary tested from one side only is a boundary that can be off by one in the
     * direction nobody looked, and the direction nobody looks at a left edge is from the left.
     */
    @Test
    fun thePixelOnABoundaryBelongsToTheFrameThatStartsThere() {
        val strip = FilmStrip(fixture().board())
        assertEquals(0, strip.frameAt(0f), "the strip's own left end is inside the first cell")
        assertEquals(0, strip.frameAt(43.999f))
        assertEquals(1, strip.frameAt(44f), "the exact pixel on a boundary is the frame that STARTS there")
        assertEquals(1, strip.frameAt(131.999f))
        assertEquals(2, strip.frameAt(132f))
        assertEquals(2, strip.frameAt(175.999f))
        assertEquals(3, strip.frameAt(176f))
    }

    /** Before the strip is frame 0, past the end is the last frame, and a `NaN` is the last frame. */
    @Test
    fun beforeTheStripIsFrameZeroPastTheEndIsTheLastFrameAndANaNIsTheLastFrame() {
        val strip = FilmStrip(fixture().board())
        assertEquals(0, strip.frameAt(-1f))
        assertEquals(3, strip.frameAt(1e9f))
        assertEquals(3, strip.frameAt(Float.NaN), "a NaN is not before the strip; it is nowhere")
        assertEquals(3, strip.frameAt(Float.POSITIVE_INFINITY))
    }

    // ---------- 7. the edge zone is ONE-SIDED and 24 deep ----------

    /**
     * The four zones are `[20, 44]`, `[108, 132]`, `[152, 176]` and `[284, 308]` — each one
     * `[right edge − 24, right edge]`, because `edgeGrabPx` = 24 at density 1 and the right edges
     * are 44, 132, 176 and 308.
     *
     * The four Lead numbers first, in this order, because they are the four the shape of the zone
     * decides: **44 → 0** (the edge is inside its own zone), **20 → 0** (24 dp to the left, which is
     * the number that kills both 12 dp readings), **44.001 → −1** (a thousandth to the RIGHT, which
     * is the number that fails the moment the zone is made two-sided — the old centred zone would
     * have said 0), **0 → −1** (the strip's own left end is not a frame's right edge; there is no
     * frame before f0 to lengthen).
     *
     * Then 307.999 → **3**, which is the other half: f3's right edge is at 308 and its zone is
     * `[284, 308]`, so a point inside that zone belongs to frame **3** — the frame whose edge it is.
     * The old draft wrote 2 here, which is a different frame's zone and contradicts the sentence
     * directly above it.
     *
     * 19.999 → −1 closes the far end: the zone is 24 deep, not 24 plus a tolerance.
     */
    @Test
    fun anEdgeIsGrabbedFromItsLeftOnly() {
        val strip = FilmStrip(fixture().board())
        assertEquals(24f, strip.edgeGrabPx, "24 dp at density 1")
        assertEquals(
            listOf(44f, 132f, 176f, 308f),
            (0..3).map { strip.cellLeft(it) + strip.cellWidth(it) },
            "the four right edges the four zones are built from",
        )

        assertEquals(0, strip.edgeAt(44f), "the edge itself is inside its own zone")
        assertEquals(0, strip.edgeAt(20f), "24 dp to the left, inclusive")
        assertEquals(-1, strip.edgeAt(44.001f), "a thousandth to the RIGHT is nothing: the zone is one-sided")
        assertEquals(-1, strip.edgeAt(0f), "the strip's left end is not an edge")
        assertEquals(-1, strip.edgeAt(19.999f), "and 24 dp is 24 dp, not 24 dp plus a tolerance")
        assertEquals(3, strip.edgeAt(307.999f), "inside f3's own zone, so f3 — the frame whose edge it is")
    }

    /**
     * 2000 probes spread over the whole strip, and then the three interior boundaries asked three
     * ways. Two claims, and the sweep is the second one.
     *
     * **Every probe yields exactly one answer, and the answer is a zone.** The sweep does not just
     * count answers (one `Int` is one answer by construction); it checks that every positive answer
     * really is a `x` inside `[edge − 24, edge]` of THAT frame's right edge, and that all four
     * frames' zones are reached — so a zone that had been moved, narrowed or misattributed would
     * have to be found by the sweep and not merely by the three hand-picked boundaries below.
     *
     * **No two zones can overlap, and the reason is arithmetic rather than a convention:** a cell is
     * at least one tick wide and `edgeGrabPx` (24) is less than a tick (44), so consecutive right
     * edges are more than one zone-depth apart. That is what the two assertions at the end are, and
     * it is why `edgeAt` is allowed to return the first zone it finds. Ask for `i, i, −1` at each
     * boundary b: `b − 0.001` and `b` are both inside frame **i**'s own zone, and the first `−1`
     * can only appear on the far side of `b`, which is the disjointness itself. A test expecting
     * `i−1` there is asking for the zone of the frame that STARTS at b, which this contract does
     * not have.
     */
    @Test
    fun thereIsNoLeftEdgeZoneSoTwoZonesCannotOverlap() {
        val strip = FilmStrip(fixture().board())
        val reached = HashSet<Int>()
        val step = 330f / 2000f
        for (k in 0 until 2000) {
            val x = -10f + k * step
            val answer = strip.edgeAt(x)
            assertTrue(answer <= 3, "$x answered $answer, which is not a frame of this board")
            if (answer >= 0) {
                val edge = strip.cellLeft(answer) + strip.cellWidth(answer)
                assertTrue(
                    x >= edge - strip.edgeGrabPx && x <= edge,
                    "$x answered $answer, but frame $answer's edge is at $edge and its zone is " +
                        "[${edge - strip.edgeGrabPx}, $edge]",
                )
                reached.add(answer)
            }
        }
        assertEquals(setOf(0, 1, 2, 3), reached, "every frame's own zone must be reachable, or the sweep proves nothing")

        for (i in 0..2) {
            val b = strip.cellLeft(i) + strip.cellWidth(i)
            assertEquals(i, strip.edgeAt(b - 0.001f), "$i just before the boundary at $b")
            assertEquals(i, strip.edgeAt(b), "$i ON the boundary at $b, which is inside its own zone")
            assertEquals(-1, strip.edgeAt(b + 0.001f), "and nothing at all just past the boundary at $b")
        }

        assertTrue(
            strip.edgeGrabPx < strip.tickPx,
            "24 < 44: a zone shallower than a cell is what makes two zones disjoint",
        )
        for (i in 0..3) {
            assertTrue(
                strip.cellWidth(i) > strip.edgeGrabPx,
                "cell $i is ${strip.cellWidth(i)} px wide, so its zone and its neighbour's are " +
                    "${strip.cellWidth(i) - strip.edgeGrabPx} px apart",
            )
        }
    }

    /**
     * The property a hit test is judged by, in the form [SpriteGridMath.cellAt]'s own KDoc states it
     * for cells: the frame the strip says is under a cell's own left edge is that cell.
     *
     * Asked from both sides of every boundary, and cross-checked against the MODEL's half-open rule
     * rather than against a second opinion invented here: `AnimOps.frameAt(board, starts[i])` is
     * frame i — `starts[i] <= timeMs`, breaking at the first start past it — and the two must name
     * the same frame at every boundary, which is the "three implementations that disagree by one
     * frame" failure Decision 6 exists to stop.
     */
    @Test
    fun theStripIsHalfOpenInExactlyTheSameWayTheModelIs() {
        val board = fixture().board()
        val strip = FilmStrip(board)
        val starts = AnimOps.frameStartsMs(board)
        val eps = 0.001f
        for (i in 0..3) {
            val left = strip.cellLeft(i)
            assertEquals(i, strip.frameAt(left), "cell $i starts at $left, so $left is cell $i")
            assertEquals(
                AnimOps.frameAt(board, starts[i]).id,
                board.frames[strip.frameAt(left)].id,
                "the model and the strip must name the same frame at boundary $i",
            )
            assertEquals(i, strip.frameAt(left + eps), "just inside the cell that starts there")
            if (i > 0) assertEquals(i - 1, strip.frameAt(left - eps), "just before it, the cell that ends there")
        }
    }

    /**
     * A non-finite coordinate is neither an edge nor a move, and the second half of that is the one
     * that needs the setup: the gesture goes down at **x = 0**, which is in no edge zone at all
     * (every zone starts at 20 or further right), so the grab is a [Grab.Scrub] and the frame it
     * last published is `frameAt(0f)` = 0.
     *
     * That setup is the whole test. A gesture that went down on frame 3 would have `lastPublished` = 3
     * and would agree with the lookup's "non-finite is the last frame" answer, so a
     * `previewFrameAt = frameAt` shortcut would pass this test. From frame 0 it cannot: the preview
     * must answer 0 for a `NaN` and for `+Inf`, where the lookup answers 3.
     *
     * The last line is the other half of Decision 7 and is what stops the obvious wrong fix: a
     * FINITE coordinate is a real position and the preview really does follow it, so the test cannot
     * be satisfied by a preview that never moves.
     */
    @Test
    fun aNonFiniteCoordinateIsNeitherAnEdgeNorAMove() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())
        assertEquals(-1, strip.edgeAt(Float.NaN))
        assertEquals(-1, strip.edgeAt(Float.NEGATIVE_INFINITY))
        assertEquals(-1, strip.edgeAt(Float.POSITIVE_INFINITY))

        val g = gestureOn(doc)
        assertEquals(Grab.Scrub, g.down(0f), "0 is in no edge zone, so this is a scrub and lastPublished is 0")
        assertEquals(3, strip.frameAt(Float.NaN), "what the LOOKUP says, and what the preview must not say")
        assertEquals(0, g.previewFrameAt(Float.NaN), "the frame it last published")
        assertEquals(0, g.previewFrameAt(Float.POSITIVE_INFINITY), "and again, rather than the last frame")
        assertEquals(1, g.previewFrameAt(44f), "but a real position really does move the preview")
    }

    // ---------- 12. the step, the rounding, the clamps ----------

    /**
     * At density 1 a tick is 44 px, so half a tick is **22 px** and a drag of exactly ±22 px is
     * exactly on the tie. Ties away from zero gives +1 and −1.
     *
     * `kotlin.math.round` is ties-to-EVEN and would give **0** for both, and that is what makes this
     * a test rather than a tautology. Both directions, because "away from zero" is a claim about the
     * negative side too — and the negative side is asked on **f1 (hold 2)** on purpose: asked on f0
     * (hold 1) a −22 drag would ask for 0, the model clamps it up to 1, and the correct answer and
     * the wrong one are both 1. There has to be somewhere for the frame to FALL for the claim to be
     * visible.
     *
     * The two non-tie probes pin the other half of "round": 21 px is under half a tick and must add
     * nothing, 23 px is over it and must add one. A `ceil` would pass the tie probes and fail these.
     */
    @Test
    fun theDragStepRoundsTiesAwayFromZero() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())
        assertEquals(22f, strip.tickPx / 2f, "half a tick, and the tie is a real one")

        val up = strip.setHoldByDrag(doc, "b-anim", "f0", 1, 22f)
        assertEquals(2, strip.holdOf(up, "b-anim", "f0"), "+22 px is exactly half a tick and adds ONE")

        val down = strip.setHoldByDrag(doc, "b-anim", "f1", 2, -22f)
        assertEquals(1, strip.holdOf(down, "b-anim", "f1"), "-22 px removes one, from a frame with room to lose it")

        assertEquals(1, strip.holdOf(strip.setHoldByDrag(doc, "b-anim", "f0", 1, 21f), "b-anim", "f0"), "21 px is under half a tick")
        assertEquals(2, strip.holdOf(strip.setHoldByDrag(doc, "b-anim", "f0", 1, 23f), "b-anim", "f0"), "23 px is over it")
    }

    /**
     * f1 (hold 2) dragged **−1000 px** asks for `2 − round(1000 / 44)` = `2 − 23` = **−21**, and
     * **−2000 px** asks for `2 − round(2000 / 44)` = `2 − 45` = **−43**. The model clamps both up to
     * one tick and the cell reads **44 px** on a strip rebuilt from the document that came back.
     *
     * The finger keeps going and the cell stops: that is the visible clamp the house wants, and it
     * is visible because it is the MODEL's number rather than a local one.
     *
     * The last two lines are the reading rule stated as a test. On the ORIGINAL strip f1 is still
     * 88 px wide, which is the stale number that looks right — a view that measured its own strip
     * after a drag would draw the old width and report no error.
     */
    @Test
    fun draggingLeftStopsAtOneTickAndTheCellStopsShrinking() {
        val doc = fixture()
        assertEquals(44f, draggedStrip(doc, "f1", -1000f).cellWidth(1), "asked for -21, clamped to one tick")
        assertEquals(44f, draggedStrip(doc, "f1", -2000f).cellWidth(1), "asked for -43, still one tick")
        assertEquals(88f, FilmStrip(doc.board()).cellWidth(1), "the ORIGINAL strip, which was never told")
    }

    /**
     * f0 (hold 1) dragged **+100 000 px**: `round(100 000 / 44)` = `floor(2272.727 + 0.5)` = **2273**,
     * so the strip asks for 2274 and the answer read back out of the document is the model's own
     * 999. A drag only reaches the clamp once `holdAtDown + step > 999`, i.e. past about 43 900 px at
     * density 1 — the old draft's +10 000 px is a step of 227 and a hold of 228, which never touches
     * the limit, and "fixing" that test to 228 would have quietly deleted the claim it exists to
     * make.
     *
     * Both expectations are asked of `AnimOps` rather than written down, so this test FOLLOWS the
     * model if the model's limit ever moves and J2 is the one that notices a second copy of it
     * appearing in the strip:
     *  - `AnimOps.setHold(doc, "b-anim", "f0", 2274)` is what the strip's own arithmetic must have
     *    produced, so the step of 2273 is pinned as well as the clamp;
     *  - and the cell is then 999 × 44 = **43 956** px, which is 2274 × 44 = 100 056 px if the
     *    read-back is ever dropped and the requested number is returned instead.
     */
    @Test
    fun draggingRightStopsAtTheModelsOwnLimit() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())
        val after = strip.setHoldByDrag(doc, "b-anim", "f0", 1, 100_000f)
        val limit = AnimOps.setHold(doc, "b-anim", "f0", 12_345)

        assertEquals(
            limit.frame("f0").holdFrames,
            strip.holdOf(after, "b-anim", "f0"),
            "the model's own limit, however high it is, and not a number written down here",
        )
        assertEquals(
            AnimOps.setHold(doc, "b-anim", "f0", 2274),
            after,
            "1 + round(100000 / 44) = 2274 was asked for, and the model answered it identically",
        )
        assertEquals(43_956f, FilmStrip(after.board()).cellWidth(0), "999 × 44, not 2274 × 44")
    }

    // ---------- 14, 15. the `+`, the menu, and the model beneath them ----------

    /**
     * The `+` TAP, which is DUPLICATE (R33). One more frame after the one under the `+`, a NEW cel
     * holding a copy of the pixels, one `CelWork.CopyCel` for the one animated layer, and the id
     * generator asked in the shape `AnimOps.addFrame` promises: the new FRAME first, then one cel
     * per animated layer — `gen0` and then `gen1`.
     *
     * A DUPLICATE rather than a BLANK, and "a NEW cel rather than a second name for the old one" is
     * the part that is really under test: painting on the copy must not be able to touch the
     * original, and it cannot if the two frames have two cels. Shape first, validation second.
     */
    @Test
    fun theAddButtonDuplicatesTheFrameUnderIt() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())
        val ids = Ids()
        val res = strip.addAfter(doc, "b-anim", "f1", NewFrame.DUPLICATE, ids::next)

        assertEquals(listOf("f0", "f1", "gen0", "f2", "f3"), res.doc.frameIds(), "inserted after f1, not appended")
        assertEquals(listOf("gen0", "gen1"), ids.seen, "the new frame's id is drawn before the new cel's")
        assertEquals(1, res.doc.frame("gen0").holdFrames, "a new frame is held for one tick and adjusted after the fact")

        assertEquals(1, res.work.size, "one CopyCel per ANIMATED layer, and l-static is not animated here")
        val copy = res.work.single()
        assertTrue(copy is CelWork.CopyCel, "a duplicate is a copy, not a share: $copy")
        assertEquals(
            CelWork.CopyCel(layerId = "l-anim", fromCelId = "c-anim", toCelId = "gen1"),
            copy,
            "from the source frame's cel into the brand new one, on the animated layer",
        )
        assertEquals(listOf("c-anim", "gen1"), res.doc.layer("l-anim").cels.map { it.id }, "a NEW cel, so the two cannot scribble on each other")
        assertEquals("gen1", res.doc.layer("l-anim").frameCel["gen0"])
        assertEquals(emptyList(), DocOps.validate(res.doc), "a shape test alone passes just as happily on a broken document")
    }

    /**
     * The long-press menu is four model operations and no new code, and each of the four is checked
     * against the thing it is really for.
     *
     * **BLANK** — a frame, a cel, and no pixel work at all: an empty cel has nothing to copy.
     * **LINK** — a frame, NO new cel, no pixel work, and the layer's mapping sends the new frame to
     * the SAME cel id as its source: that is the whole of how "hold" is stored, and it is why
     * deleting either frame of a linked pair costs nothing.
     * **HOLD ±** — one code path, proved by equality: a `setHoldByBump(+1)` and a
     * `setHoldByDrag(+tickPx)` from the same hold of 2 produce the SAME DOCUMENT, and it holds 3.
     * **DELETE** — the frame is gone and so is the cel only it was showing, reported as a
     * `DropCel` so the engine can free its tiles; `c-anim` stays, because three other frames still
     * show it. (The duplicate is what makes the test possible at all: in the base fixture every
     * frame shares one cel, so there is no cel that only one frame shows.)
     *
     * `DocOps.validate` clean after each of the four.
     */
    @Test
    fun theMenuIsFourModelOperationsAndNothingElse() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())

        val blankIds = Ids()
        val blank = strip.addAfter(doc, "b-anim", "f1", NewFrame.BLANK, blankIds::next)
        assertEquals(5, blank.doc.board().frames.size)
        // The id generator is asked TWICE and in a fixed order: `AnimOps.addFrame` draws the new
        // FRAME's id first (line 233) and only then one cel per animated layer (line 285), and
        // `l-static` is not animated in this board so it contributes no id at all. So the new frame
        // is `gen0` and the new BLANK cel is `gen1` — the first id is not the cel's, and asserting
        // otherwise was a real mistake in an earlier draft of this test.
        assertEquals(listOf("gen0", "gen1"), blankIds.seen, "the frame's id is drawn before the cel's")
        assertEquals(listOf("f0", "f1", "gen0", "f2", "f3"), blank.doc.frameIds())
        assertEquals(listOf("c-anim", "gen1"), blank.doc.layer("l-anim").cels.map { it.id }, "an empty cel, and it is its own")
        assertEquals("gen1", blank.doc.layer("l-anim").frameCel["gen0"], "and it is the new frame that shows it")
        assertTrue(blank.work.isEmpty(), "an empty cel has nothing to copy, so there is no CelWork: ${blank.work}")
        assertEquals(emptyList(), DocOps.validate(blank.doc))

        val linkIds = Ids()
        val link = strip.addAfter(doc, "b-anim", "f1", NewFrame.LINK, linkIds::next)
        assertEquals(5, link.doc.board().frames.size)
        assertEquals(listOf("gen0"), linkIds.seen, "a link needs one id, for the frame: ${linkIds.seen}")
        assertEquals(listOf("c-anim"), link.doc.layer("l-anim").cels.map { it.id }, "no new cel: two names for one cel")
        assertTrue(link.work.isEmpty(), "a link shares a cel, it does not copy one: ${link.work}")
        assertEquals("c-anim", link.doc.layer("l-anim").frameCel["gen0"])
        assertEquals(
            link.doc.layer("l-anim").frameCel["f1"],
            link.doc.layer("l-anim").frameCel["gen0"],
            "the same cel as its source, which is what makes the pair safe to delete from",
        )
        assertEquals(emptyList(), DocOps.validate(link.doc))

        val bumped = strip.setHoldByBump(doc, "b-anim", "f1", 2, +1)
        val dragged = strip.setHoldByDrag(doc, "b-anim", "f1", 2, strip.tickPx)
        assertEquals(bumped, dragged, "HOLD + is the edge drag by one whole tick: one function, not two")
        assertEquals(3, strip.holdOf(bumped, "b-anim", "f1"))
        assertEquals(emptyList(), DocOps.validate(bumped))

        val dupIds = Ids()
        val dup = strip.addAfter(doc, "b-anim", "f3", NewFrame.DUPLICATE, dupIds::next)
        val newest = dup.doc.board().frames.last().id
        val del = strip.delete(dup.doc, "b-anim", newest)
        assertEquals(listOf("f0", "f1", "f2", "f3"), del.doc.frameIds(), "the last frame is gone")
        assertEquals(listOf("c-anim"), del.doc.layer("l-anim").cels.map { it.id }, "and so is the cel only it showed")
        assertEquals(1, del.work.size, "reported, not freed by the model")
        assertEquals(CelWork.DropCel(layerId = "l-anim", celId = "gen1"), del.work.single())
        assertEquals(emptyList(), DocOps.validate(del.doc))
    }

    // ---------- 16, 17. the playhead is an ID ----------

    /**
     * Playhead on f1, f1 deleted: the answer is **f2**, the frame now at index 1, and explicitly not
     * f3. (The assertion that the frame at index 1 is f2 is the derivation — a playhead that landed
     * one frame LATER than the hole would be the off-by-one this project keeps shipping, so "f2" is
     * asserted as the frame that replaced the deleted one and not as a remembered string.)
     */
    @Test
    fun deletingTheFrameUnderThePlayheadMovesItToTheFrameThatReplacedIt() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())
        val after = AnimOps.deleteFrame(doc, "b-anim", "f1")

        assertEquals(listOf("f0", "f2", "f3"), after.doc.frameIds())
        assertEquals("f2", after.doc.frameIds()[1], "the frame that now sits where f1 was")
        assertEquals("f2", strip.playheadAfterDelete(after.doc.board(), "f1", "f1"), "not f3, and not an index")
        assertNull(strip.playheadAfterDelete(after.doc.board(), "f1", null), "no playhead, nothing to re-anchor")
    }

    /**
     * Deleting any OTHER frame leaves the playhead exactly where it was, by ID, whatever the
     * indices did — and this test also records the hazard, because on the second case the two
     * behaviours coincide and a test that separated them would be separating the wrong things.
     *
     * Playhead on f2, f3 (the LAST frame) deleted → still **f2**: f2's index did not move either, so
     * this case proves the ID rule is not just an index rule that happened to agree.
     * Playhead on f3, f0 deleted → still **f3**, and f3 now sits at index **2**. An index playhead
     * pinned at 3 is out of range on a three-frame board after this delete, and clamping it to 2
     * happens to name f3 too — so this case RECORDS that coincidence rather than claiming to
     * distinguish the two. Test 16 is the case that separates them, and it is the one Decision 1
     * cites.
     */
    @Test
    fun deletingTheLastFrameLeavesThePlayheadAloneAndSoDoesDeletingAnyOtherFrame() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())

        val noLast = AnimOps.deleteFrame(doc, "b-anim", "f3")
        assertEquals("f2", strip.playheadAfterDelete(noLast.doc.board(), "f3", "f2"), "the last frame went; the playhead did not")

        val noFirst = AnimOps.deleteFrame(doc, "b-anim", "f0")
        assertEquals(listOf("f1", "f2", "f3"), noFirst.doc.frameIds())
        assertEquals(2, noFirst.doc.frameIds().indexOf("f3"), "the playhead's frame has moved DOWN one index")
        assertEquals("f3", strip.playheadAfterDelete(noFirst.doc.board(), "f0", "f3"))
        assertEquals(
            "f3",
            noFirst.doc.frameIds()[2],
            "which is the coincidence this case records: a clamped index of 2 names f3 as well",
        )
    }

    // ---------- 18, 19, 20, 21, 22. the gesture ----------

    /**
     * A second finger during a scrub: the lift commits nothing, not even a playhead move that the
     * finger had already previewed. The preview itself is still ANSWERED afterwards, because a
     * cancelled gesture has stopped playing, not stopped understanding where the finger is.
     *
     * `cancel()` is terminal, so the `up` after it is a no-op rather than a late playhead move — and
     * `up(176)` is the last frame, so a `cancel` that only forgot the document and not the
     * playhead would be caught here.
     */
    @Test
    fun aSecondFingerEndsAScrubWithNothingCommitted() {
        val doc = fixture()
        val g = gestureOn(doc)
        assertEquals(Grab.Scrub, g.down(10f), "10 is in no edge zone")
        assertEquals(1, g.previewFrameAt(44f), "the finger has moved over frame 1")

        assertEquals(StripStep.Nothing, g.cancel())
        assertEquals(3, g.previewFrameAt(176f), "still answered: 176 is frame 3's left edge")
        assertEquals(StripStep.Nothing, g.up(176f), "and a late lift moves nothing")
        assertTrue(g.doc === doc, "the gesture never replaced the document it was given")
    }

    /**
     * A second finger during an EDGE DRAG: the preview had reached 5 and the document still says 1,
     * which is the whole claim. `down(44f)` is inside f0's own edge zone, so this is
     * [Grab.Edge] with the hold read from the document at finger-down; `previewHoldAt(200)` is
     * `1 + round(156 / 44)` = `1 + floor(3.545 + 0.5)` = **5**.
     *
     * The document half of the assertion is checked against the FRAMES, not against the `doc`
     * property: `g.doc === doc` is true by construction (nothing replaces the field), so it would
     * pass against code that had written a hold and thrown the document away. The holds are the
     * claim.
     */
    @Test
    fun aSecondFingerEndsAnEdgeDragWithNoHoldChange() {
        val doc = fixture()
        val g = gestureOn(doc)
        assertEquals(Grab.Edge(0, 1), g.down(44f), "44 is f0's right edge, and f0 is held for one tick")
        assertEquals(5, g.previewHoldAt(200f), "1 + round(156 / 44)")
        assertEquals(heldFrames(), g.doc.board().frames, "the preview was 5 and the document still says 1")

        assertEquals(StripStep.Nothing, g.cancel())
        assertEquals(StripStep.Nothing, g.up(200f), "the drag is abandoned, not committed late")
        assertEquals(heldFrames(), g.doc.board().frames, "and nothing was written on the way out")
    }

    /**
     * The most important test in the file. A gesture that goes down in no zone, crosses into one
     * mid-gesture, and lifts INSIDE it is still a scrub.
     *
     * `down(200f)`: the four zones are `[20,44]`, `[108,132]`, `[152,176]` and `[284,308]`, so 200 is
     * in none of them and the grab is [Grab.Scrub] — asserted against the strip's own answer rather
     * than assumed. `up(120f)`: 120 IS inside frame 1's zone (asserted too, because "the move
     * crossed an edge" is the premise and not something to take on trust), and the result is a
     * playhead move to f1 and NOTHING else.
     *
     * A strip that re-decided the grab on every move would answer `HoldChanged` here, and one that
     * never re-decided but moved the playhead on an edge grab would answer `PlayheadTo` in test 21
     * instead. This is the whole of Decision 8, and a scrub that changes a hold by accident is the
     * bug the decision exists to prevent.
     */
    @Test
    fun aDragThatCrossesAnEdgeMidGestureDoesNotBecomeAnEdgeGrab() {
        val doc = fixture()
        val strip = FilmStrip(doc.board())
        val g = gestureOn(doc)

        assertEquals(-1, strip.edgeAt(200f), "200 is in no zone")
        assertEquals(1, strip.edgeAt(120f), "120 IS inside frame 1's zone, so the move does cross an edge")
        assertEquals(Grab.Scrub, g.down(200f), "decided at the finger-down, and only at the finger-down")

        val step = g.up(120f)
        assertTrue(step is StripStep.PlayheadTo, "a scrub publishes a playhead and no document: $step")
        assertEquals(StripStep.PlayheadTo("f1"), step)
        assertEquals(heldFrames(), g.doc.board().frames, "and no hold was touched")
    }

    /**
     * One [Grab.Edge] grab, FIVE moves, ONE write. `down(44f)` and the offsets are 6, 56, 46, 86 and
     * 150 px, i.e. 0, 1, 1, 2 and 3 ticks by `floor(v + 0.5)`, so the previews are 1, 2, 2, 3 and 4
     * — all five answered, and the document is still the one it was given after every one of them.
     *
     * **The count is the point.** A drag that committed on every move would have produced five
     * `HoldChanged` and put five entries on the undo stack, and a person who dragged to a hold and
     * then moved one pixel back would have had to undo twice to get where they started. So the
     * commit is asserted to be the document `AnimOps.setHold(doc, "b-anim", "f0", 4)` produces — the
     * final offset, 150 px, and not the sum of the moves — and a second lift is asserted to write
     * nothing, which is the "one write per gesture" as a property rather than a promise.
     */
    @Test
    fun theGestureWritesTheDocumentOnceAndOnlyOnTheLift() {
        val doc = fixture()
        val g = gestureOn(doc)
        assertEquals(Grab.Edge(0, 1), g.down(44f))

        assertEquals(
            listOf(1, 2, 2, 3, 4),
            listOf(50f, 100f, 90f, 130f, 194f).map { g.previewHoldAt(it) },
            "offsets 6, 56, 46, 86, 150 px are 0, 1, 1, 2, 3 ticks on a hold of 1",
        )
        assertEquals(heldFrames(), g.doc.board().frames, "five previews, zero writes")

        val step = g.up(194f)
        assertTrue(step is StripStep.HoldChanged, "the lift is where the document is written: $step")
        val changed = step as StripStep.HoldChanged
        assertEquals("f0", changed.frameId, "44 is f0's right edge, so the drag is f0's and nobody else's")
        assertEquals(4, changed.holdFrames, "read back out of the document that came back")
        assertEquals(AnimOps.setHold(doc, "b-anim", "f0", 4), changed.doc, "1 + round(150 / 44) = 4")
        // f0 1 -> 4 and the other three UNTOUCHED, so the holds are [4, 2, 1, 3] and not [1, 4, 1, 3].
        // The edited frame is the FIRST one: `down(44f)` is inside f0's own edge zone [20, 44], and
        // f0 is the frame that was held for 1 tick, so it is f0 that goes to 4. An earlier draft of
        // this line expected [1, 4, 1, 3], which is what the result would be if the drag were on
        // f1 — the assertion's own message said "only f0 moved", and the list contradicted it.
        assertEquals(listOf(4, 2, 1, 3), changed.doc.board().frames.map { it.holdFrames }, "and only f0 moved")
        assertEquals(heldFrames(), doc.board().frames, "the document the gesture was given is untouched")
        assertEquals(StripStep.Nothing, g.up(194f), "a second lift is not a second write")
    }

    /**
     * A drag is measured from the finger-DOWN. `down(44f)`, moves to 50, 100, 150 and 194, so the
     * total offset is `194 − 44` = **150 px**, the step is `round(150 / 44)` =
     * `floor(3.409 + 0.5)` = **3**, and the hold is `1 + 3` = **4**.
     *
     * The two wrong answers, with their own arithmetic, because these are the two bugs the test
     * exists to catch and a test that only says "4" leaves the next reader to guess which one it is:
     *  - **summing every move's own offset from the finger-down**: `6 + 56 + 46 + 86 + 150` = 344 px
     *    → `round(344 / 44)` = `floor(7.818 + 0.5)` = 8 → a hold of **9**;
     *  - **measuring from the last move only**: `194 − 130` = 64 px → `round(64 / 44)` = 1 → a hold
     *    of **2**.
     *
     * Neither is 4, and neither depends on how many MOVE events the platform happened to deliver —
     * which is the property that makes the finger-down the only defensible origin.
     */
    @Test
    fun aDragIsMeasuredFromTheFingerDownAndNotFromTheLastMove() {
        val doc = fixture()
        val g = gestureOn(doc)
        assertEquals(Grab.Edge(0, 1), g.down(44f))
        for (x in listOf(50f, 100f, 150f)) g.previewHoldAt(x)

        val step = g.up(194f)
        assertTrue(step is StripStep.HoldChanged, "$step")
        val changed = step as StripStep.HoldChanged
        assertEquals(4, changed.holdFrames, "150 px from the down, not 344 px summed and not 64 px from the last move")
        assertEquals(AnimOps.setHold(doc, "b-anim", "f0", 4), changed.doc)
    }

    // ---------- 23, 24, 25. the controls and the edges of the board ----------

    /**
     * A control that is offered when the model will throw is a bug report, and one that is silently
     * dead is worse — so both halves are checked: [FilmStrip.canDelete] is false on a one-frame board
     * AND [AnimOps.deleteFrame] really does refuse, and both are the other way round on two frames.
     */
    @Test
    fun theDeleteButtonIsDeadOnAOneFrameBoardAndTheModelAlsoRefuses() {
        val one = fixture(listOf(Frame("f0", holdFrames = 1)))
        val oneStrip = FilmStrip(one.board())
        assertFalse(oneStrip.canDelete(), "the control must be visibly dead, not silently dead")
        assertFailsWith<DocException> { oneStrip.delete(one, "b-anim", "f0") }

        val two = fixture(listOf(Frame("f0", holdFrames = 1), Frame("f1", holdFrames = 1)))
        val twoStrip = FilmStrip(two.board())
        assertTrue(twoStrip.canDelete())
        val res = twoStrip.delete(two, "b-anim", "f0")
        assertEquals(listOf("f1"), res.doc.frameIds())
        assertEquals(emptyList(), DocOps.validate(res.doc))
    }

    /**
     * The strip's only stepper is prev/next, and it STOPS at the ends: `f0 − 1` is `f0`, `f3 + 1` is
     * `f3`, `f1 + 1` is `f2`, and no playhead at all stepping forward is `f0` — there is a next
     * frame and nothing was chosen yet.
     *
     * Wrapping would give `f3` for the first of those, and that is asserted not to be the case:
     * wrapping is playback's business (the peg bar owns PLAY, R33) and a strip that wrapped would
     * give the person a second, differently-behaving sense of "next" on one screen.
     */
    @Test
    fun aStepOfThePlayheadStopsAtTheEndsAndNeverWraps() {
        val strip = FilmStrip(fixture().board())
        assertEquals("f0", strip.stepPlayhead("f0", -1), "stopped, not wrapped to f3")
        assertEquals("f3", strip.stepPlayhead("f3", +1), "stopped, not wrapped to f0")
        assertEquals("f2", strip.stepPlayhead("f1", +1))
        assertEquals("f0", strip.stepPlayhead("f1", -1))
        assertEquals("f0", strip.stepPlayhead(null, +1), "no playhead yet, and the next frame is the first")
    }

    /**
     * The strip is a function of `frames` and `density` and nothing else, and this is the test that
     * stops somebody adding an fps term "for accuracy" — or a rect term, or a layer term.
     *
     * Seven ticks is `1000 / 6`, `1000 / 12`, `1000 / 24` and `1000 / 60` ms per tick — a factor of
     * ten from one board to the other — and the four boards have the same four cells: 44, 88, 44,
     * 132 px. The rect and the layer count are changed too, on a document whose ANIMATION board is
     * untouched, and the widths do not move, because the strip is handed a `Board` and a `Board` has
     * no layers in it at all.
     */
    @Test
    fun theCellWidthIsUnchangedByEveryOtherBoardProperty() {
        val doc = fixture()
        val expected = listOf(44f, 88f, 44f, 132f)
        for (fps in listOf(6f, 12f, 24f, 60f)) {
            assertEquals(
                expected,
                (0..3).map { FilmStrip(doc.board().copy(fps = fps)).cellWidth(it) },
                "$fps fps must not change a single pixel",
            )
        }

        val elsewhere = doc.board().copy(rect = RectPx(0, 0, 4096, 4096), fps = 24f, clipToBoard = true)
        assertEquals(expected, (0..3).map { FilmStrip(elsewhere).cellWidth(it) }, "nor the board's rect")

        val extra = Layer(id = "l-extra", name = "Extra", kind = LayerKind.PAINT, cels = listOf(Cel("c-extra")))
        val withLayers = doc.copy(layers = doc.layers + extra)
        assertEquals(3, withLayers.layers.size, "the second document really does have more layers")
        assertEquals(
            expected,
            (0..3).map { FilmStrip(withLayers.board()).cellWidth(it) },
            "nor the number of layers, which a Board cannot even see",
        )
    }
}
