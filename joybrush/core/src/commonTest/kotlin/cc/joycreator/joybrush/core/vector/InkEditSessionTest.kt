package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-5.03 — the session that turns a finger into a re-shaped, re-weighted, re-brushed line.
 *
 * ## Where the numbers in here come from
 *
 * Every asserted number is **derived in the comment that asserts it**, and none of them is a number
 * the code printed. The hard ones are the falloff figures, and they are all one closed form:
 * `StrokeEdit.reshape` weights a sample by `w = (1 − (s/R)²)²` where `s` is ARC LENGTH from the
 * grabbed sample and `R` is the grab radius in doc px, so
 *
 *  - a sample 4 of arc from the grab at `R = 24` has `u = 1/6`, `u² = 1/36`, `w = (35/36)² = 1225/1296
 *    = 0.94521605`, and a 5 px drag moves it `4.7260802`;
 *  - a sample 5 of arc has `w = (551/576)² = 303601/331776 = 0.91507826`, and the same drag moves it
 *    `4.5753913` — **strictly less**, which is the monotonicity a constant-offset bug would break;
 *  - at zoom 8 the radius is `24/8 = 3`, so a sample 20 of arc is `u = 20/3 > 1` and does not move
 *    AT ALL (the shape ends; it does not fade out over a long tail), a sample 2 has `u = 2/3` and
 *    moves `8 × (5/9)² = 8 × 25/81 = 2.4691358` — 30.86 % of the offset — and a sample 0.5 of arc
 *    moves `8 × 1225/1296 = 7.5617284`, 94.52 % of it.
 *
 * Those three — 94.5 %, 30.9 %, 0 % — are the shape of the falloff at one zoom, and the 30.9 % is
 * the one that matters: it is neither "nearly the whole offset" nor "nothing", so a builder who
 * guessed the falloff gets one of those two wrong and this file catches it.
 *
 * The dab counts are derived the same way. `DabPlacer.emit` returns `max(2r x spacing,
 * minSpacingPx)`, and the shipped numbers are `r = 3` (`size.base = 6`, no pressure curve),
 * `spacing = 0.04` and `minSpacingPx = 0.5`, so the step is `max(0.24, 0.5) = 0.5` doc px and a line
 * of `L` doc px carries `floor(L / 0.5) + 1` dabs — the first one free, because the placer emits
 * for the first sample before it has measured any distance.
 *
 * Nothing here opens a file, so the whole file belongs in `commonTest`.
 */
class InkEditSessionTest {

    @Test
    fun quickSessionTapsCycleEvenThoughReplayRebuildsTheGeometry() {
        val session = session(listOf(rec("a", 10, 0f, 0f, 1f, 0f), rec("b", 10, 0f, 0f, 1f, 0f)))
        val got = (0..3).map { i ->
            session.tap(5.0, 0.0, i * 10.0, 1.0, brushOf)
            session.selection
        }
        assertEquals(listOf(listOf("b"), listOf("a"), listOf("b"), listOf("a")), got)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private fun brush(id: String, name: String, engine: String) = BrushPreset(
        id = id,
        name = name,
        engine = engine,
        size = Param(6f),
        tip = TipSpec(corner = 2f, hardness = Param(1f)),
        spacing = 0.04f,
        accumulate = "wash",
    )

    private fun inkBrush() = brush("joybrush.ink", "Ink", "stamp")
    private fun fillBrush() = brush("joybrush.fill", "Fill pen", ENGINE_FILL)
    private fun smudgeBrush() = brush("joybrush.smudge", "Smudge", "smudge")
    private fun wetBrush() = brush("joybrush.wet", "Wet", "wet")

    /** The document's library, keyed exactly as `StrokeRecord.brushId` names it. */
    private val library = mapOf(
        "joybrush.ink" to inkBrush(),
        "joybrush.fill" to fillBrush(),
        "joybrush.smudge" to smudgeBrush(),
        "joybrush.wet" to wetBrush(),
    )

    private fun brushFor(record: StrokeRecord): BrushPreset? = library[record.brushId]

    /**
     * The document-library lookup, as the VALUE every `lines` / `tap` / `band` call takes. A property
     * rather than a bare function name so that passing it needs no callable-reference syntax.
     */
    private val brushOf: (StrokeRecord) -> BrushPreset? = { brushFor(it) }

    /** [n] samples starting at ([x0], [y0]) and stepping ([dx], [dy]), 8 ms apart, seed 7. */
    private fun rec(
        id: String,
        n: Int,
        x0: Float,
        y0: Float,
        dx: Float,
        dy: Float,
        brushId: String = "joybrush.ink",
    ) = StrokeRecord(
        id = id,
        brushId = brushId,
        seed = 7L,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = List(n) { i -> PenSample(x = x0 + i * dx, y = y0 + i * dy, timeMs = 1000.0 + 8.0 * i) },
        colorArgb = 0xFF203040.toInt(),
        widthScale = 1f,
    )

    /**
     * The four-stroke fixture, ids in drawing order. Every line is 9 doc px long and its dabs have
     * radius 3, so a tap on one of them is a tap on one of them.
     *
     *  - `a` runs along `y = 10` from `x = 0` to `x = 9`;
     *  - `b` runs along `y = 50`, 40 doc px below `a`;
     *  - `c` runs down `x = 10` from `y = 0` to `y = 9`;
     *  - `d` runs down `x = 200`, alone in its own half of the page.
     */
    private fun four(): List<StrokeRecord> = listOf(
        rec("a", 10, 0f, 10f, 1f, 0f),
        rec("b", 10, 0f, 50f, 1f, 0f),
        rec("c", 10, 10f, 0f, 0f, 1f),
        rec("d", 10, 200f, 0f, 0f, 1f),
    )

    private fun session(records: List<StrokeRecord> = four()) = InkEditSession(records, "cel-1")

    /**
     * A session holding one long flat line, with it SELECTED.
     *
     * The selection is not decoration: `beginDrag` answers [InkEditResult.NoChange] when nothing is
     * selected, because a drag with no selection cannot reshape anything. A falloff test that
     * forgets to select is a test of nothing.
     */
    private fun flatSession(n: Int, step: Double): InkEditSession {
        val s = session(listOf(flat(n, step)))
        s.band(-1.0, -1.0, 10_000.0, 10_000.0, brushOf)
        assertEquals(listOf("flat"), s.selection, "the only line in the cel is selected, so it can be dragged")
        return s
    }

    /** The four fixture records as an id-keyed map: what a session starts out holding. */
    private fun originalRecords(): Map<String, StrokeRecord> = four().associateBy { it.id }

    /**
     * A line of [n] samples [step] doc px apart along `y = 0`, so a sample's ARC from sample 0 is
     * exactly its index times [step]. The falloff tests are arithmetic on that, which is why this
     * fixture exists in this shape.
     */
    private fun flat(n: Int, step: Double) = StrokeRecord(
        id = "flat",
        brushId = "joybrush.ink",
        seed = 7L,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = List(n) { i -> PenSample(x = (i * step).toFloat(), y = 0f, timeMs = 1000.0 + 8.0 * i) },
    )

    /**
     * The session's records RIGHT NOW, read without changing anything.
     *
     * [InkEditSession.beginDrag] snapshots and returns the current records and changes none of them,
     * and [InkEditSession.cancelDrag] puts back a snapshot that was never changed. That pair is the
     * only read of the records the public API offers, and it is what makes a verb that mutates and
     * *then* checks — the failure this row is most likely to grow — visible: such a verb leaves a
     * record here that is not the one it started from.
     */
    private fun currentRecords(s: InkEditSession): Map<String, StrokeRecord> {
        val opened = s.beginDrag(0.0, 0.0, 1.0)
        assertTrue(opened is InkEditResult.Changed, "the probe needs something selected: $opened")
        val out = (opened as InkEditResult.Changed).records
        s.cancelDrag()
        return out
    }

    /** Assert two positions agree to [tolerance], saying which one failed. */
    private fun assertNear(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: derived $expected, was $actual (±$tolerance)")
    }

    // ── 1. a tap selects one line and REPLACES ───────────────────────────────────────────────

    /**
     * A tap is one line, and it takes the place of whatever was selected.
     *
     * The two picks are the geometry, not a guess. `a`'s ribbon is `y = 10 ± 3`, so a tap at
     * `(4, 10)` scores `0 − 3 = −3` on `a` and `6.0828 − 3 = 3.0828` on `c`; the cluster tie is one
     * screen px, so `a` wins outright, and the double-tap cycle does not fire because the two taps
     * are 8.49 apart and the cycle reach is 6. Then `(10, 4)` scores `−3` on `c` and `3.0828` on
     * `a`, and the order reverses.
     */
    @Test
    fun aTapSelectsOneLineAndReplacesTheSelection() {
        val s = session()
        assertTrue(s.tap(4.0, 10.0, 0.0, 1.0, brushOf) is InkEditResult.Changed, "a tap on a line is a change")
        assertEquals(listOf("a"), s.selection, "(4, 10) is inside a's ribbon and 6.08 px from c's centreline")

        s.tap(10.0, 4.0, 1000.0, 1.0, brushOf)
        assertEquals(listOf("c"), s.selection, "a tap REPLACES: two taps are two selections, never a union")

        // The half of "replaces" the assertion above does not reach: a tap on empty paper takes
        // things AWAY. Without it, "replaces" is only ever tested against a selection of one.
        assertEquals(InkEditResult.NoChange, s.tap(150.0, 150.0, 2000.0, 1.0, brushOf), "nothing under the finger")
        assertEquals(emptyList(), s.selection, "…and the selection is emptied")
    }

    // ── 2. a band selects many, in drawing order, and ADDS ───────────────────────────────────

    @Test
    fun aBandSelectsManyInDrawingOrderAndAddsToWhatIsThere() {
        val whole = session()
        whole.band(0.0, 0.0, 300.0, 100.0, brushOf)
        assertEquals(listOf("a", "b", "c", "d"), whole.selection, "four lines, in drawing order")

        // The corners arrive in whatever order the finger drew them.
        val swapped = session()
        swapped.band(300.0, 100.0, 0.0, 0.0, brushOf)
        assertEquals(whole.selection, swapped.selection, "the rectangle is normalised inside")

        // The spec's own band for "over `a` only" — (0,0)-(20,20) — ALSO holds `c`, and it has to:
        // `c`'s dabs are at (10, 0) … (10, 9), and 10 ∈ [0, 20] with 9 ∈ [0, 20]. A point test says
        // so, and pretending otherwise would be the easy way to be wrong about a rectangle.
        val both = session()
        both.band(0.0, 0.0, 20.0, 20.0, brushOf)
        assertEquals(listOf("a", "c"), both.selection, "c's dabs are inside that rectangle")

        // A band that really is over `a` alone: `a`'s dabs are (0…9, 10), so y ∈ [5, 15] takes the
        // line, and x ≤ 9.5 stops short of `c`, which stands at x = 10.
        val s = session()
        s.band(0.0, 5.0, 9.5, 15.0, brushOf)
        assertEquals(listOf("a"), s.selection, "one line, and only that one")

        // …and it ADDS to an existing selection rather than replacing it (Decision 2).
        s.tap(10.0, 4.0, 0.0, 1.0, brushOf)
        assertEquals(listOf("c"), s.selection)
        s.band(0.0, 5.0, 9.5, 15.0, brushOf)
        assertEquals(listOf("a", "c"), s.selection, "a band adds, and the selection stays in drawing order")
    }

    // ── 3. a band is DAB POINTS and not a bounding box ──────────────────────────────────────

    /**
     * The load-bearing half of Decision 11.
     *
     * `e` walks the diagonal in 9 px steps, so its bounding box covers the strip (28, 0)-(35, 7)
     * completely — and **every one of its dabs has x == y**, which is why a strip that is wide in x
     * and short in y can never contain one. The x == y is exact rather than approximate: the
     * resampler interpolates `(fx, fy)` from equal inputs in `Double`, the placer's `lerp` runs the
     * same arithmetic in `Float`, and each converts with the same `/ zoom` and `.toFloat()`.
     *
     * So a band that tests the bounding box selects `e` here and a band that tests its points does
     * not, and there is nowhere else in this file where the two answers disagree.
     */
    @Test
    fun aBandIsDabPointsAndNotABoundingBox() {
        val s = session(four() + rec("e", 10, 0f, 0f, 9f, 9f))
        val e = s.lines(brushOf).single { it.id == "e" }
        assertTrue(e.pointCount > 100, "e is a real 114.55 doc px line, resampled and placed: ${e.pointCount} dabs")
        for (i in 0 until e.pointCount) {
            assertEquals(e.xs[i], e.ys[i], "dab $i is ON the diagonal, exactly")
        }
        // The bounding box does cover the strip, so a bbox test would have taken the line.
        assertTrue(e.xs.min() <= 28.0 && e.xs.max() >= 35.0, "x runs ${e.xs.min()} … ${e.xs.max()}")
        assertTrue(e.ys.min() <= 0.0 && e.ys.max() >= 7.0, "y runs ${e.ys.min()} … ${e.ys.max()}")

        // No dab of `e` is in that strip, and no dab of anything else is either: `a` is at y = 10,
        // `b` at y = 50, `c` at x = 10, `d` at x = 200.
        assertEquals(InkEditResult.NoChange, s.band(28.0, 0.0, 35.0, 7.0, brushOf), "no dab is in that rectangle")
        assertEquals(emptyList(), s.selection, "…so nothing joined")

        // The positive control: the same line, a rectangle it genuinely crosses.
        s.band(20.0, 20.0, 60.0, 60.0, brushOf)
        assertEquals(listOf("e"), s.selection, "20 ≤ x = y ≤ 60 does hold dabs")
    }

    // ── 4. a drag moves EVERY selected line, about ONE grab point, and falls off ─────────────

    /**
     * Decision 4 and Decision 3's cast, and the corrected assertion the spec's own review made.
     *
     * "The record moved 5 px" is false for every sample except the grabbed one: with `u = s/R` and
     * `w = (1 − u²)²`, a sample 4 of arc away moves `5 × 1225/1296 = 4.7260802` and a sample 5 of
     * arc away moves `5 × 303601/331776 = 4.5753913`. Asserting a flat 5 px would either fail or —
     * worse — pass against a builder who moved every sample by the whole offset.
     *
     * `c` is the other half. It is a DIFFERENT line whose own nearest sample to the shared grab is
     * `(10, 9)` at `√(36 + 1) = 6.0828` (the next, `(10, 8)`, is `√(36 + 4) = 6.3249`), and that
     * sample moves the whole 5.0. One grab point, two lines, one object.
     */
    @Test
    fun aDragMovesEverySelectedLineAboutTheOneGrabPointAndFallsOff() {
        val cWas = originalRecords().getValue("c")
        assertNear("c's (10, 9) is √37 from the grab", sqrt(37.0), hypot(cWas.samples[9].x - 4.0, cWas.samples[9].y - 10.0), 1e-6)
        assertNear("c's (10, 8) is √40, so it is not the nearest", sqrt(40.0), hypot(cWas.samples[8].x - 4.0, cWas.samples[8].y - 10.0), 1e-6)

        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        assertEquals(listOf("a", "c"), s.selection)
        assertTrue(s.beginDrag(4.0, 10.0, 1.0) is InkEditResult.Changed, "zoom 1 gives radius 24/1 = 24 doc px")

        val moved = (s.drag(5.0, 0.0) as InkEditResult.Changed).records
        val a = moved.getValue("a")
        val c = moved.getValue("c")

        val fourOfArc = 5.0 * (35.0 / 36.0).pow(2)   // w(4) x 5 = 4.7260802
        val fiveOfArc = 5.0 * (551.0 / 576.0).pow(2)  // w(5) x 5 = 4.5753913
        assertNear("sample 0, 4 of arc from the grab", 0.0 + fourOfArc, a.samples[0].x.toDouble(), 1e-3)
        assertNear("sample 4, the grabbed one", 4.0 + 5.0, a.samples[4].x.toDouble(), 1e-6)
        assertNear("sample 9, 5 of arc from the grab", 9.0 + fiveOfArc, a.samples[9].x.toDouble(), 1e-3)
        assertTrue(fiveOfArc < fourOfArc, "the falloff decreases in s: a constant offset would break this")

        assertEquals(15.0, c.samples[9].x.toDouble(), "c's (10, 9) is its grabbed sample, so it moves the whole 5.0")
        assertEquals(9.0, c.samples[9].y.toDouble(), "…in x only: the finger's offset is (5, 0)")
        assertNotEquals(cWas.samples, c.samples, "and c is a different line, not a copy of a")

        // The selection is a selection, not an edit, and one drag is one step.
        assertEquals(listOf("a", "c"), s.selection, "a drag does not change what is selected")
        assertTrue(s.endDrag() is InkEditResult.Changed)
        assertEquals(1, s.undo.size, "one drag, one undo step")
    }

    // ── 5. a whole drag is ONE undo step, holding the ORIGINAL records ───────────────────────

    @Test
    fun aWholeDragIsOneUndoStepHoldingTheRecordsItReplaced() {
        val original = originalRecords()
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        s.beginDrag(4.0, 10.0, 1.0)
        repeat(4) { s.drag(1.0, 0.0) }
        assertTrue(s.endDrag() is InkEditResult.Changed)

        assertEquals(1, s.undo.size, "four drag() calls and one endDrag() are ONE step")
        val step = s.undo.single()
        assertEquals("cel-1", step.celId, "the step knows which cel it belongs to")
        assertEquals(listOf("a", "c"), step.before.keys.toList(), "both selected lines are in it")
        for (id in listOf("a", "c")) {
            assertEquals(original.getValue(id), step.before.getValue(id), "$id is stored as it was BEFORE the drag")
            assertNotEquals(step.before.getValue(id), currentRecords(s).getValue(id), "…and not as it is now")
        }
    }

    // ── 6. a drag that moved nothing commits nothing ─────────────────────────────────────────

    @Test
    fun aDragThatMovedNothingCommitsNothing() {
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)

        assertTrue(s.beginDrag(4.0, 10.0, 1.0) is InkEditResult.Changed)
        assertEquals(InkEditResult.NoChange, s.drag(0.0, 0.0), "an offset of zero is not a change")
        assertEquals(InkEditResult.NoChange, s.endDrag(), "so there is nothing to commit")
        assertEquals(0, s.undo.size, "and the undo log is still empty")

        // A drag with nothing open under it is not a refusal, it is a drag that cannot reshape.
        assertEquals(InkEditResult.NoChange, s.drag(3.0, 0.0), "no beginDrag, no drag")
        assertEquals(0, s.undo.size, "…and still no step")
        assertEquals(InkEditResult.NoChange, s.endDrag(), "…and nothing to commit")
        assertEquals(InkEditResult.NoChange, s.cancelDrag(), "…and nothing to cancel")

        // A non-finite offset is the same answer, and it does not commit either.
        s.beginDrag(4.0, 10.0, 1.0)
        assertEquals(InkEditResult.NoChange, s.drag(Double.NaN, 0.0), "NaN is not a drag")
        assertEquals(InkEditResult.NoChange, s.drag(0.0, Double.POSITIVE_INFINITY), "…nor is infinity")
        assertEquals(InkEditResult.NoChange, s.endDrag())
        assertEquals(0, s.undo.size, "a gesture of two broken frames is still a gesture that moved nothing")
    }

    // ── 7. a second finger undoes the WHOLE drag ─────────────────────────────────────────────

    /**
     * The restore is compared with `==` against the originals, not with "different from the dragged
     * ones": a cancel that restored the last FRAME rather than the snapshot would satisfy the
     * weaker assertion and still leave a half-finished bend on the page. So the two frames are also
     * asserted to differ from EACH OTHER, which is what makes the snapshot assertion mean something.
     *
     * Nothing here uses the `currentRecords` probe: it opens a drag of its own and closes it again,
     * and that would destroy the very drag under test. The records are read off the
     * [InkEditResult.Changed] each call already carries, and the probe is used only afterwards, when
     * no gesture is in flight.
     */
    @Test
    fun aSecondFingerUndoesTheWholeDrag() {
        val original = originalRecords()
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        s.beginDrag(4.0, 10.0, 1.0)
        val afterOne = (s.drag(2.0, 0.0) as InkEditResult.Changed).records
        val afterTwo = (s.drag(2.0, 0.0) as InkEditResult.Changed).records
        assertNotEquals(original, afterOne, "the drag really did change the records")
        assertNotEquals(afterOne, afterTwo, "…and the two frames differ, so the last frame is not the snapshot")

        val restored = (s.cancelDrag() as InkEditResult.Changed).records
        assertEquals(original, restored, "the SNAPSHOT is back, exactly, and not the last frame")

        assertEquals(InkEditResult.NoChange, s.endDrag(), "the gesture is forgotten, so there is nothing to commit")
        assertEquals(0, s.undo.size, "a cancelled drag leaves no step")

        // …and a drag after the cancel cannot resurrect it.
        assertEquals(InkEditResult.NoChange, s.drag(2.0, 0.0), "the grab is gone")
        assertEquals(0, s.undo.size, "and the undo log is still empty")
        assertEquals(original, currentRecords(s), "so the records are still the restored ones")
    }

    // ── 8. the grab radius is SCREEN px, and the falloff is (1 − u²)² over ARC ───────────────

    @Test
    fun atZoomOneTheRadiusIsTwentyFourAndTheFalloffIsTheClosedForm() {
        val s = flatSession(400, 1.0)
        // The grab is sample 0, so a sample's arc from it is exactly its index.
        s.beginDrag(0.0, 0.0, 1.0)
        val r = (s.drag(8.0, 0.0) as InkEditResult.Changed).records.getValue("flat")

        // Sample 20: u = 20/24 = 5/6, u² = 25/36, 1 − u² = 11/36, w = 121/1296 = 0.09336420, so
        // an 8 px drag moves it 0.7469136 — 9.34 % of the offset. NOT "under 1 %", which would have
        // hidden a falloff far too generous.
        assertNear("sample 20 at radius 24", 8.0 * (11.0 / 36.0).pow(2), r.samples[20].x - 20.0, 1e-4)
        assertEquals(8.0, r.samples[0].x.toDouble(), "the grabbed sample moves the whole offset")
    }

    @Test
    fun atZoomEightTheRadiusIsThreeAndTheShapeEndsRatherThanFading() {
        val s = flatSession(400, 1.0)
        s.beginDrag(0.0, 0.0, 8.0)
        val r = (s.drag(8.0, 0.0) as InkEditResult.Changed).records.getValue("flat")

        // Radius 24/8 = 3, so sample 20 is u = 20/3 = 6.67 ≥ 1 and w = 0: the shape ENDS at the
        // radius rather than fading out over a long tail.
        assertEquals(20f, r.samples[20].x, "u > 1 is w = 0, exactly")

        // Sample 2 at the same zoom: u = 2/3, u² = 4/9, 1 − u² = 5/9, w = 25/81 = 0.30864198, so
        // it moves 2.4691358 — 30.86 % of the offset. NOT "nearly the full offset", which would
        // have hidden a falloff far too tight, and not 0 either.
        assertNear("sample 2 at radius 3", 8.0 * (5.0 / 9.0).pow(2), r.samples[2].x.toDouble() - 2.0, 1e-4)
    }

    /**
     * The third figure, and the monotonicity.
     *
     * A second fixture 800 samples 0.5 doc px apart, so that a sample 0.5 from the grab exists at
     * all: `u = 0.5/3 = 1/6`, `u² = 1/36`, `1 − u² = 35/36`, `w = 1225/1296 = 0.94521605`, so it
     * moves 7.5617284 — 94.52 %. The three figures together (94.5 %, 30.9 %, 0 %) are the
     * falloff's shape at one zoom.
     */
    @Test
    fun theFalloffIsMonotonicAndReachesExactlyZeroAtTheRadius() {
        val s = flatSession(800, 0.5)
        s.beginDrag(0.0, 0.0, 8.0)
        val r = (s.drag(8.0, 0.0) as InkEditResult.Changed).records.getValue("flat")

        assertNear("half a doc px away", 8.0 * (35.0 / 36.0).pow(2), r.samples[1].x.toDouble() - 0.5, 1e-4)

        // 0.5, 1, 2, 3 and 4 doc px of arc, which are samples 1, 2, 4, 6 and 8 of this fixture.
        val derived = listOf(
            8.0 * (35.0 / 36.0).pow(2),  // u = 1/6,  w = 0.94521605
            8.0 * (8.0 / 9.0).pow(2),    // u = 1/3,  w = 0.79012346
            8.0 * (5.0 / 9.0).pow(2),    // u = 2/3,  w = 0.30864198
            0.0,                         // u = 1,    exactly zero
            0.0,                         // u > 1,   still exactly zero
        )
        val indexOf = listOf(1, 2, 4, 6, 8)
        val moved = derived.indices.map { i ->
            val was = indexOf[i] * 0.5f
            assertNear("sample ${indexOf[i]}, ${0.5 * indexOf[i]} doc px of arc", derived[i], r.samples[indexOf[i]].x.toDouble() - was, 1e-4)
            r.samples[indexOf[i]].x - was
        }
        for (i in 1 until moved.size) {
            assertTrue(moved[i] <= moved[i - 1], "non-increasing in s: $moved")
        }
        assertEquals(0f, moved[3], "u = 1 is EXACTLY unmoved: the C² join at the edge of the grab")
        assertEquals(0f, moved[4], "and u > 1 is unmoved too, not merely small")
    }

    // ── 9. beginDrag refuses a zoom it cannot use ────────────────────────────────────────────

    /**
     * A division cannot fall back to 1:1 the way a tolerance can, so this is thrown rather than
     * read. The bad values are checked BEFORE the snapshot, so a session that has thrown is still
     * a session that works.
     */
    @Test
    fun beginDragRefusesAZoomItCannotUse() {
        val s = session()
        s.band(0.0, 0.0, 300.0, 100.0, brushOf)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("zoom $bad") { s.beginDrag(0.0, 10.0, bad) }
        }
        assertEquals(0, s.undo.size, "a refused zoom takes no step")
        assertTrue(s.beginDrag(4.0, 10.0, 1.0) is InkEditResult.Changed, "and the session is still draggable")
        assertTrue(s.beginDrag(4.0, 10.0, 1.0) is InkEditResult.Changed, "even a second time, so nothing above left it broken")
    }

    /**
     * The radius really is SCREEN px, which is the whole reason for dividing. At zoom 16 the reach
     * is `24/16 = 1.5` doc px, so the same 8 px drag on the same line:
     *
     *  - moves the sample 1 of arc away by `8 × (1 − (1/1.5)²)²` = `8 × 25/81` = **2.4691358**, and
     *  - does not move the sample 2 of arc away AT ALL, because `u = 2/1.5 = 4/3 > 1`.
     *
     * At 1:1 the same two samples move `8 × (143/144)²` = **7.8892747** and `8 × (11/36)²` =
     * **0.7469136**. The finger's reach is the same 24 px on the glass either way, and a drag that
     * carries a third of the line at 16x carries the whole of it at 1:1.
     */
    @Test
    fun theGrabRadiusIsTwentyFourScreenPixelsAtEveryZoom() {
        val zoomed = flatSession(400, 1.0)
        zoomed.beginDrag(0.0, 0.0, 16.0)
        val at16 = (zoomed.drag(8.0, 0.0) as InkEditResult.Changed).records.getValue("flat")
        assertNear("1 doc px at radius 1.5", 8.0 * (5.0 / 9.0).pow(2), at16.samples[1].x.toDouble() - 1.0, 1e-4)
        assertEquals(2f, at16.samples[2].x, "2 doc px is past a 1.5 doc px reach, so it does not move")

        val oneToOne = flatSession(400, 1.0)
        oneToOne.beginDrag(0.0, 0.0, 1.0)
        val at1 = (oneToOne.drag(8.0, 0.0) as InkEditResult.Changed).records.getValue("flat")
        assertNear("2 doc px at radius 24", 8.0 * (143.0 / 144.0).pow(2), at1.samples[2].x.toDouble() - 2.0, 1e-4)
        assertNear("20 doc px at radius 24", 8.0 * (11.0 / 36.0).pow(2), at1.samples[20].x.toDouble() - 20.0, 1e-4)
    }

    // ── 10. re-brush is ONE step for the selection, and keeps the seed ──────────────────────

    @Test
    fun rebrushIsOneStepForTheSelectionAndKeepsTheSeedAndTheSamples() {
        val original = originalRecords()
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        assertEquals(listOf("a", "c"), s.selection)

        val result = s.rebrush(fillBrush())
        assertTrue(result is InkEditResult.Changed, "the fill pen draws an ink shape (R21)")
        assertEquals(1, s.undo.size, "ONE step for two lines")
        assertEquals(listOf("a", "c"), s.undo.single().before.keys.toList(), "and it holds both")

        val after = (result as InkEditResult.Changed).records
        for (id in listOf("a", "c")) {
            val was = original.getValue(id)
            val now = after.getValue(id)
            assertEquals("joybrush.fill", now.brushId, "$id is drawn with the fill pen now")
            assertEquals(7L, now.seed, "$id keeps its seed: a re-brush that invented one redraws the line differently")
            assertEquals(was.samples, now.samples, "$id is NOT re-sampled and NOT re-smoothed")
            assertEquals(was.colorArgb, now.colorArgb, "$id keeps its colour")
            assertEquals(was.widthScale, now.widthScale, "$id keeps its weight")
            assertEquals(was.id, now.id, "$id is the same line, edited")
        }
    }

    // ── 11. the refusal has two doors and one sentence ──────────────────────────────────────

    @Test
    fun theReBrushRefusalHasTwoDoorsAndOneSentence() {
        // Door one: the picker asks with NO session and NO selection, because greying a brush has to
        // be possible before anything is selected. It is a companion function for that reason.
        val bare = session(emptyList())
        assertEquals(emptyList(), bare.selection, "nothing is selected")
        val sentence = InkEditSession.rebrushRefusal(smudgeBrush())
        assertNotNull(sentence, "a smudge brush cannot draw an ink line")
        assertTrue(sentence.contains("Smudge"), "the sentence names the brush, so a greyed row is explained: $sentence")
        assertEquals(sentence, InkEditSession.rebrushRefusal(smudgeBrush()), "the same call, no session and no state")
        assertNull(InkEditSession.rebrushRefusal(inkBrush()), "an ordinary pen may")
        assertNull(InkEditSession.rebrushRefusal(fillBrush()), "and so may the fill pen")

        // Door two: the backstop. A UI that forgot to ask must still not paint a smudge.
        for (illegal in listOf(smudgeBrush(), wetBrush())) {
            val original = originalRecords()
            val s = session()
            s.band(0.0, 0.0, 20.0, 20.0, brushOf)
            val refused = s.rebrush(illegal)
            assertTrue(refused is InkEditResult.Refused, "${illegal.name} is refused")
            assertEquals(
                InkEditSession.rebrushRefusal(illegal),
                (refused as InkEditResult.Refused).reason,
                "the backstop says the PICKER's sentence, verbatim: two copies of these words are two opinions",
            )
            assertEquals(0, s.undo.size, "a refusal is not a step")
            assertEquals(original, currentRecords(s), "every record is untouched — a verb that mutated first shows here")
            assertEquals(listOf("a", "c"), s.selection, "and the selection is unchanged: the refusal is total")
        }
    }

    // ── 12. a record that cannot be replayed is not a line ──────────────────────────────────

    @Test
    fun aMissingBrushMakesARecordUnpickable() {
        val missing = rec("e", 10, 300f, 300f, 0f, 1f, brushId = "not.in.library")
        val s = session(four() + missing)

        assertEquals(
            listOf("a", "b", "c", "d"),
            s.lines(brushOf).map { it.id },
            "four lines, by id: e's brush is not in this document's library",
        )

        // e runs down x = 300 from y = 300 to 309 — nowhere near the other four — so a tap on it
        // finds nothing at all, and a record that is not a line cannot be selected.
        assertEquals(InkEditResult.NoChange, s.tap(300.0, 305.0, 0.0, 1.0, brushOf), "there is no ribbon to hit")
        assertEquals(emptyList(), s.selection, "so nothing can be selected")
        // …and the four that CAN be replayed are still perfectly pickable.
        assertTrue(s.tap(4.0, 10.0, 0.0, 1.0, brushOf) is InkEditResult.Changed, "one unpickable record does not break the cel")
        assertEquals(listOf("a"), s.selection)
    }

    /** Decision 12 and this row's Q1: a `fill` recording replays to no dabs, so it is not a line. */
    @Test
    fun aFillRecordingIsNotALineAndSoCannotBeReshaped() {
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        assertEquals(listOf("a", "b", "c", "d"), s.lines(brushOf).map { it.id }, "four lines to start with")
        s.rebrush(fillBrush())
        assertEquals(
            listOf("b", "d"),
            s.lines(brushOf).map { it.id },
            "a and c are now fills: JB-5.01's Decision 12 gives a fill no dabs, so there is no line",
        )
        assertEquals(listOf("a", "c"), s.selection, "the records are still selected — they simply have no line")
        // It is a real shape, so this is the engine's rule and not a broken fixture.
        val filled = currentRecords(s).getValue("a")
        assertTrue(
            FillPen.outline(filled.samples, filled.smoothing, filled.screenPerDoc).size >= 3,
            "a fill recording really does enclose something",
        )
    }

    // ── 13. re-weight ────────────────────────────────────────────────────────────────────────

    @Test
    fun reweightIsOneStepPerCallForTheWholeSelectionAndIsClamped() {
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        assertEquals(listOf("a", "c"), s.selection)

        s.reweight(2f)
        assertEquals(1, s.undo.size, "one call on TWO lines is ONE step — not one step per line")
        assertEquals(listOf("a", "c"), s.undo.single().before.keys.toList())
        assertEquals(2f, currentRecords(s).getValue("a").widthScale, 1e-6f)

        s.reweight(0.5f)
        assertEquals(2, s.undo.size, "…and the second call is a second step")
        assertEquals(1f, currentRecords(s).getValue("a").widthScale, 1e-6f, "twice then half is once")

        // The clamps are StrokeEdit's, and the session reads the same two numbers rather than
        // keeping its own copy.
        s.reweight(1000f)
        assertEquals(StrokeEdit.MAX_WIDTH_SCALE, currentRecords(s).getValue("a").widthScale, "clamped to the widest")
        s.reweight(0.0001f)
        assertEquals(StrokeEdit.MIN_WIDTH_SCALE, currentRecords(s).getValue("a").widthScale, "clamped to the narrowest")

        // A factor that is not a number changes nothing, and is REFUSED rather than reported as a
        // change the session did not make.
        val settled = currentRecords(s)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(s.reweight(bad) is InkEditResult.Refused, "factor $bad is not a factor")
            assertEquals(4, s.undo.size, "factor $bad commits nothing")
            assertEquals(settled, currentRecords(s), "…and changes nothing")
        }

        // …and with nothing selected there is nothing to do.
        s.clearSelection()
        assertEquals(emptyList(), s.selection)
        assertEquals(InkEditResult.NoChange, s.reweight(2f), "no selection is not a change")
        assertEquals(4, s.undo.size, "…and is not a step")
    }

    // ── 14. recolour touches the colour and nothing else ─────────────────────────────────────

    @Test
    fun recolourIsOneStepAndTouchesOnlyTheColour() {
        val s = session()
        s.band(0.0, 0.0, 20.0, 20.0, brushOf)
        val before = currentRecords(s)
        assertTrue(s.recolor(0xFF00FF00.toInt()) is InkEditResult.Changed)
        val after = currentRecords(s)
        assertEquals(1, s.undo.size, "one step for the whole selection")
        for (id in listOf("a", "c")) {
            val was = before.getValue(id)
            val now = after.getValue(id)
            assertEquals(0xFF00FF00.toInt(), now.colorArgb, "$id is green")
            assertNotEquals(was.colorArgb, now.colorArgb, "$id really changed colour")
            assertEquals(was.id, now.id, "$id id")
            assertEquals(was.brushId, now.brushId, "$id brush")
            assertEquals(was.seed, now.seed, "$id seed")
            assertEquals(was.samples, now.samples, "$id samples")
            assertEquals(was.widthScale, now.widthScale, "$id widthScale")
            assertEquals(was.smoothing, now.smoothing, "$id smoothing")
            assertEquals(was.screenPerDoc, now.screenPerDoc, "$id screenPerDoc")
        }
        s.clearSelection()
        assertEquals(InkEditResult.NoChange, s.recolor(0xFF0000FF.toInt()), "nothing selected, nothing done")
        assertEquals(1, s.undo.size, "…and no step")
    }

    // ── 15. the undo log is bounded at 50 steps, and keeps the NEWEST ───────────────────────

    /**
     * Asserted BY VALUE, not by size: a builder who kept the OLDEST fifty would pass a size-only
     * assertion and would undo the wrong fifty gestures. Call `k` stores the record as it was after
     * `k − 1` re-weights, i.e. at `1.01^(k−1)`, so of 60 calls the surviving 50 are calls 11..60 and
     * their `before` values run from `1.01^10` to `1.01^59`.
     */
    @Test
    fun theUndoLogIsBoundedAtFiftyStepsAndKeepsTheNewest() {
        assertEquals(50, InkEditSession.MAX_UNDO_STEPS, "R37, Q3: steps, 50")
        val s = session()
        s.band(0.0, 5.0, 9.5, 15.0, brushOf)
        repeat(60) { s.reweight(1.01f) }

        assertEquals(50, s.undo.size, "60 gestures, 50 slots")
        val oldest = s.undo.first().before.getValue("a").widthScale
        val newest = s.undo.last().before.getValue("a").widthScale
        assertNear("the oldest surviving step", 1.01f.pow(10).toDouble(), oldest.toDouble(), 1e-4)
        assertNear("the newest step", 1.01f.pow(59).toDouble(), newest.toDouble(), 1e-4)
        // Two neighbouring powers differ by 1.01^n x 0.00995 — 0.011 and 0.018 here — so a
        // tolerance of 1e-4 separates the right answer from BOTH its neighbours by two orders.
        assertTrue(1.01f.pow(9) < oldest, "…and it is not the ninth")
        assertTrue(newest < 1.01f.pow(60), "…nor the sixtieth")
    }

    // ── 16. band and tap are TOTAL ───────────────────────────────────────────────────────────

    @Test
    fun bandAndTapRefuseBrokenCoordinatesAndAcceptAnEmptyBand() {
        val s = session()
        s.band(0.0, 5.0, 9.5, 15.0, brushOf)
        val settled = currentRecords(s)

        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(s.tap(bad, 4.0, 0.0, 1.0, brushOf) is InkEditResult.Refused, "tap x = $bad")
            assertTrue(s.tap(4.0, bad, 0.0, 1.0, brushOf) is InkEditResult.Refused, "tap y = $bad")
            assertTrue(s.band(bad, 0.0, 10.0, 10.0, brushOf) is InkEditResult.Refused, "band x0 = $bad")
            assertTrue(s.band(0.0, bad, 10.0, 10.0, brushOf) is InkEditResult.Refused, "band y0 = $bad")
            assertTrue(s.band(0.0, 0.0, bad, 10.0, brushOf) is InkEditResult.Refused, "band x1 = $bad")
            assertTrue(s.band(0.0, 0.0, 10.0, bad, brushOf) is InkEditResult.Refused, "band y1 = $bad")
        }
        assertEquals(listOf("a"), s.selection, "a NaN never empties a selection: that is what a containment test on NaN does")
        assertEquals(settled, currentRecords(s), "and a refusal changes no record")

        // An EMPTY band contains nothing, so nothing joins and it is not an error.
        assertEquals(InkEditResult.NoChange, s.band(5.0, 5.0, 5.0, 5.0, brushOf), "(5, 5) is a point, and no dab is on it")
        assertEquals(listOf("a"), s.selection, "…so the selection is untouched")
        assertEquals(InkEditResult.NoChange, s.band(5.0, 5.0, 5.0, 5.0, brushOf), "and repeating it is the same answer")
    }

    // ── THE PROPERTY: reshape and re-weight are SEPARATELY pinned ───────────────────────────

    /**
     * A reshape that changed the line but not its dabs, or that changed the dabs but not the line,
     * is a bug — and so is a re-weight that moved a dab. This test pins all four halves, each
     * against a number derived in its own comment rather than against the other half.
     *
     * The step is the arithmetic of the shipped brush: `DabPlacer.emit` returns
     * `max(2r x spacing, minSpacingPx)`, and `r = 3`, `spacing = 0.04`, `minSpacingPx = 0.5` give
     * `max(0.24, 0.5)` = **0.5 doc px**.
     *
     *  - `a` is 9 doc px long, so it starts at `floor(9 / 0.5) + 1` = **19** dabs.
     *  - after a 5 px drag it is **SHORTER**, not longer, and the count is the proof that this test
     *    is not self-fulfilling. The line's two ends sit 4 and 5 of arc from the grab and `w` is
     *    DECREASING in `s`, so the near end moves more than the far one and the line closes up. The
     *    arc is `(9 + 5 w(5)) − (0 + 5 w(4))` = `13.5753913 − 4.7260803` = **8.849311**, so
     *    `floor(8.849311 / 0.5) + 1` = **18**.
     *
     * A reader who assumed "the line moved, so there are more dabs" gets 20 and fails here, which is
     * the point of writing the arc down rather than the count.
     */
    @Test
    fun aReshapeMovesTheLineAndAReweightFattensItAndNeitherDoesTheOthersJob() {
        assertEquals(0.5f, max(2f * 3f * 0.04f, 0.5f), "the placer's step is the 0.5 doc px floor, not the brush's 0.24")

        val s = session()
        s.band(0.0, 5.0, 9.5, 15.0, brushOf)
        val drawn = s.lines(brushOf).single { it.id == "a" }
        assertEquals(19, drawn.pointCount, "9 doc px of arc at a step of 0.5, first dab free")
        assertTrue(drawn.halfWidths.all { it == 3.0 }, "size.base = 6 and no pressure curve: every radius is 3")

        s.beginDrag(4.0, 10.0, 1.0)
        s.drag(5.0, 0.0)
        s.endDrag()
        val reshaped = s.lines(brushOf).single { it.id == "a" }

        // (1) THE LINE MOVED — the record and the replay are pinned to the SAME derived number, so
        //     they cannot agree by accident and cannot disagree.
        val sample0 = 5.0 * (35.0 / 36.0).pow(2)   // 4.7260802: sample 0 is 4 of arc from the grab
        assertNear("the record's sample 0", sample0, currentRecords(s).getValue("a").samples[0].x.toDouble(), 1e-4)
        assertNear("the replay's first dab", sample0, reshaped.xs[0], 1e-4)

        // (2) THE DAB COUNT WENT WITH IT, and DOWN: the drag closed the line up, so there are fewer
        //     dabs on a line that has visibly moved. A count that did not move would be the bug.
        assertEquals(18, reshaped.pointCount, "8.849311 of arc at a step of 0.5 is 18 dabs, and it is not 19")

        // (3) A RESHAPE DOES NOT RE-WEIGHT: not one radius moved.
        assertTrue(reshaped.halfWidths.all { it == 3.0 }, "every half-width is still the brush's own 3")

        // (4) A RE-WEIGHT DOES NOT RESHAPE, and does not re-space either: `InkReplay` scales the
        //     radius AFTER the placer has chosen its step from the unscaled one, so a re-weighted
        //     line is fatter and NOT further apart. Every coordinate is identical and every radius
        //     is doubled — the mirror image of (1)-(3).
        s.reweight(2f)
        val fatter = s.lines(brushOf).single { it.id == "a" }
        assertContentEquals(reshaped.xs, fatter.xs, "a re-weight moves no dab: not one coordinate in x")
        assertContentEquals(reshaped.ys, fatter.ys, "…nor in y")
        assertEquals(reshaped.pointCount, fatter.pointCount, "…and re-spaces nothing")
        assertTrue(fatter.halfWidths.all { it == 6.0 }, "3 x 2 = 6 on every dab, and that is the ONLY difference")
    }
}
