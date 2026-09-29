package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-5.11 - the round trip from a gesture back to recordings.
 *
 * ## EVERY NUMBER HERE IS DERIVED, AND THE DERIVATION IS IN THE TEST
 *
 * The fixture brush is the shipped Ink, and its numbers are what make the geometry checkable by
 * hand rather than by feel:
 *
 *  - `size = Param(6f)` is a DIAMETER in document px, and `BrushDabber.look` computes
 *    `radius = max(diameter, tip.minPx) / 2 = max(6, 1) / 2 = 3.0`. Every dab of every line below
 *    therefore has half-width exactly 3.0 doc px.
 *  - `spacing = 0.04f`, so `DabPlacer`'s step is `2 * 3.0 * 0.04 = 0.24` doc px: the replayed line
 *    is sampled eight times finer than the recording it came from, which is the whole reason a
 *    `Piece` has to be re-sliced against the SAMPLES and not against the dabs.
 *  - `VectorEraser` measures `pad = radius + max(h0, h1)`, so a dot eraser of radius R reaches
 *    `R + 3.0` doc px either side of its centre. Every cut edge quoted below is `centre - (R + 3)`
 *    or `centre + (R + 3)`, written out at each use.
 *
 * The recordings are sampled every 2 doc px by default. That number sets the accuracy of every
 * bracketing in the round trip, and it is why the cut-edge assertions use a tolerance of ONE SAMPLE
 * STEP rather than an exact number: `VectorEraser` returns a cut edge as a float on a polyline of
 * `Float` positions, and the sample it lands on is whichever side of that float the bracket falls.
 * The tolerance is derived, not relaxed: the bracket is by construction accurate to one sample.
 */
class InkEraseTest {

    // ---- 1. the three modes ---------------------------------------------------------------------

    /**
     * PARTIAL cuts, WHOLE_STROKE removes, TO_INTERSECTION trims - the three modes asserted through
     * the recording round trip, which is the point: each one fails if the sample bracketing loses
     * the cut.
     *
     * GEOMETRY. `h` is (0,0)-(200,0) in 101 samples, `v` is (100,-20)-(100,20) in 21. A dot eraser at
     * (140, 0) of radius 3 has `pad = 3 + 3 = 6`, so it cuts x from 134 to 146 on any line it
     * reaches. It reaches `h` and nothing else: `v` is 40 doc px away in x and 40 > 6.
     */
    @Test
    fun partialCutsWholeStrokeRemovesAndToIntersectionTrims() {
        val h = lineH("h", 101, 0.0, 0.0)
        val v = lineV("v", 21, 100.0, -20.0)
        val eraser = dot(140.0, 0.0, 3.0)
        val reach = 3.0 + 3.0 // eraser radius + the dab's own half-width
        val cutFrom = 140.0 - reach // 134
        val cutTo = 140.0 + reach // 146
        val step = 2.0 // one sample: the bracket's own accuracy

        // ---- PARTIAL: two survivors of h, and v untouched ----
        val partial = erased(listOf(h, v), eraser, InkEraseMode.PARTIAL)
        assertEquals(listOf("h", "h~2", "v"), partial.records.map { it.id }, "drawing order is kept")
        assertEquals(cutFrom, partial.records[0].samples.last().x.toDouble(), step, "ink stops at 134")
        assertEquals(cutTo, partial.records[1].samples.first().x.toDouble(), step, "and starts again at 146")
        assertEquals(200.0, partial.records[1].samples.last().x.toDouble(), step, "and runs to the end")
        // An ABSENT id in VectorEraser's answer means the eraser never touched the line, so v is
        // not in `before` and comes back as the very same instance.
        assertTrue(!partial.before.containsKey("v"), "an untouched line costs no undo step")
        assertTrue(partial.records[2] === v, "an untouched line is the caller's own record, not a copy")
        // A line cut in two is two records, and the two of them together plus the cut is the whole
        // line: 68 + 28 = 96 of 101 samples, the 5 gone being x = 136..144 inside the 12 doc px cut.
        assertEquals(68, partial.records[0].samples.size, "x = 0..134 inclusive, every 2 doc px")
        assertEquals(28, partial.records[1].samples.size, "x = 146..200 inclusive, every 2 doc px")

        // ---- WHOLE_STROKE: h is gone from the cel entirely ----
        val whole = erased(listOf(h, v), eraser, InkEraseMode.WHOLE_STROKE)
        assertEquals(listOf("v"), whole.records.map { it.id }, "a present id with an empty list is a line GONE")
        assertEquals(setOf("h"), whole.before.keys, "and undo has to bring it back")
        assertTrue(whole.records[0] === v)

        // ---- TO_INTERSECTION: the overhang stops at the crossing ----
        // The only crossing on h is where v meets it, at x = 100. The touched stretch is
        // 134..146, the nearest crossing at or before it is 100, and there is none at or after it,
        // so the cut runs from 100 to the end of h and one survivor is left.
        val toIntersection = erased(listOf(h, v), eraser, InkEraseMode.TO_INTERSECTION)
        assertEquals(listOf("h", "v"), toIntersection.records.map { it.id })
        assertEquals(0.0, toIntersection.records[0].samples.first().x.toDouble(), step)
        assertEquals(100.0, toIntersection.records[0].samples.last().x.toDouble(), step, "the overhang is gone")
        assertTrue(toIntersection.records[1] === v)
    }

    // ---- 2. survivors are real recordings --------------------------------------------------------

    /**
     * A survivor is a RECORDING, not a bag of rebuilt coordinates, and the only thing that proves it
     * is identity: the [PenSample] values in a survivor must be the very objects the original
     * recording holds, at the very indices it holds them, with no gap.
     *
     * A builder who cut the line and re-interpolated coordinates would pass every positional
     * assertion in this file and fail this one, because a re-interpolated sample is a different
     * object even when it prints the same.
     *
     * GEOMETRY. 200 samples 2 doc px apart is 398 doc px. A dot at (140, 0) radius 3 cuts x 134..146,
     * so the survivors are x 0..134 (68 samples) and x 146..398 (127 samples), and the 5 samples
     * strictly inside the cut - x = 136, 138, 140, 142, 144 - belong to neither.
     */
    @Test
    fun survivorsAreTheOriginalSamplesByIdentityAndReBrush() {
        val h = lineH("h", 200, 0.0, 0.0)
        assertEquals(398.0, h.samples.last().x.toDouble(), 0.0, "199 steps of 2 doc px")

        val out = erased(listOf(h), dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL)
        val survivors = out.records
        assertEquals(2, survivors.size)
        assertEquals(68 + 127, survivors.sumOf { it.samples.size }, "the 5 samples inside the cut are gone")

        var previousEnd = -1
        for (survivor in survivors) {
            // Everything that is not the sample list is the recording's, unchanged and in full.
            assertEquals(7L, survivor.seed, "the seed is the recording's (LEAD_RULINGS R13)")
            assertEquals("joybrush.ink", survivor.brushId)
            assertEquals(1f, survivor.widthScale)
            assertEquals(INK_COLOUR, survivor.colorArgb)
            assertEquals(h.smoothing, survivor.smoothing)
            assertEquals(h.screenPerDoc, survivor.screenPerDoc)

            // Contiguity by IDENTITY: a contiguous sublist of the original, at one offset, no rebuild.
            val at = offsetOf(h.samples, survivor.samples)
            assertTrue(at > previousEnd, "survivors are disjoint and in order along the line")
            previousEnd = at + survivor.samples.size

            // And it is a line again: re-brushing changes only the brush id and still draws.
            val rebrushed = survivor.copy(brushId = PENCIL_ID)
            assertTrue(
                InkReplay.dabs(rebrushed, pencilBrush()).isNotEmpty(),
                "a half-erased line must still re-brush (LEAD_RULINGS R20)",
            )
        }
        assertEquals(0, offsetOf(h.samples, survivors[0].samples), "the first survivor starts at x = 0")
        assertEquals(146.0, survivors[1].samples.first().x.toDouble(), 2.0, "the second starts at the cut edge")
    }

    // ---- 3. ids ---------------------------------------------------------------------------------

    /**
     * A line cut into N comes back as N records with N DIFFERENT ids, and the first one keeps the
     * original.
     *
     * This is the assertion that keeps JB-5.02's MAJOR from coming back through this door: two ink
     * records sharing an id make a picker's `indexOf` stop on the first copy, so taps after the
     * second return the same line for ever.
     *
     * GEOMETRY. The path dips onto the line at x = 20, 80 and 200 and comes straight back up to
     * y = 7, which is 7 doc px above the line and outside `pad = 3 + 3 = 6`, so the three runs
     * between the dips touch nothing. The three touches are therefore 20 +/- 6, 80 +/- 6 and
     * 200 +/- 6, i.e. x 14..26, 74..86 and 194..the end. The last one reaches the end of the line,
     * so the complement is three pieces, not four.
     */
    @Test
    fun aLineCutIntoThreeBecomesThreeDistinctIdsTheFirstKeepingTheOriginal() {
        val h = lineH("h", 101, 0.0, 0.0)
        val out = erased(listOf(h), dipPath(), InkEraseMode.PARTIAL)
        val ids = out.records.map { it.id }

        assertEquals(3, out.records.size, "three touches leave three pieces when the last reaches the end")
        assertEquals(listOf("h", "h~2", "h~3"), ids)
        assertEquals(3, ids.toSet().size, "and the three ids are three DIFFERENT ids")
        assertEquals(setOf("h"), out.before.keys, "one original record in, one undo step out")
    }

    // ---- 4. the two speck filters, each on its own -----------------------------------------------

    /**
     * THE INHERITED ONE, as a precondition and not a re-test: a piece of less than half a document
     * pixel of centreline is dropped by `VectorEraser.MIN_PIECE_ARC`, which lives in
     * `VectorEraser.kt`, is applied ONCE to all three modes, and has NO copy in `InkErase.kt` (there
     * is no threshold literal for arc length anywhere in that file - that is the trap this test
     * exists to catch, and a second copy would be a second rule about the same geometry).
     *
     * THE PROOF THAT IT IS NOT THIS FILE'S 2-SAMPLE FLOOR: the two cases below differ by 0.2 doc px
     * of the eraser path and nothing else, and both gap pieces would hold about the same number of
     * samples. The 0.4-doc-px one is dropped; the 0.6-doc-px one is kept, holding SEVEN samples -
     * comfortably over the floor of two. So a floor in SAMPLES cannot be what removed the first,
     * and the filter that did remove it measures ARC.
     *
     * GEOMETRY. The recording is sampled every 0.1 doc px, so a 0.4-doc-px piece holds four or five
     * samples and a 0.6-doc-px piece holds six or seven. The eraser is a U of radius 0.5, so
     * `pad = 0.5 + 3 = 3.5`, and its two vertical legs touch the line over 100 +/- 3.5 and
     * x2 +/- 3.5. The gap between the two touches is therefore `x2 - 107.0` doc px, which is how the
     * two cases are set up: 107.4 leaves 0.4 and 107.6 leaves 0.6. The horizontal run at y = 7 is
     * 7 doc px above the line and `7 > 3.5`, so it is never a third touch.
     */
    @Test
    fun theInheritedHalfDocPxFloorDropsThePieceAndThisFilesSampleFloorDidNot() {
        val line = lineH("s", 2001, 0.0, 0.0, step = 0.1)
        assertEquals(200.0, line.samples.last().x.toDouble(), 0.0, "2000 steps of 0.1 doc px")

        // The two gaps, stated as arithmetic on the floor rather than as two typed-in numbers: the
        // U's legs stand at x = 100 and x = x2, so the untouched run between their two touches is
        // `(x2 - pad) - (100 + pad)`, with `pad = radius 0.5 + the dab half-width 3.0 = 3.5`.
        val firstLeg = 100.0
        val pad = 0.5 + 3.0
        val narrowGap = 107.4 - pad - (firstLeg + pad)
        val wideGap = 107.6 - pad - (firstLeg + pad)
        assertTrue(narrowGap < MIN_PIECE_ARC, "the narrow case must be UNDER the floor: $narrowGap")
        assertTrue(wideGap > MIN_PIECE_ARC, "and the wide case over it: $wideGap")

        val narrow = erased(listOf(line), uPath(107.4), InkEraseMode.PARTIAL)
        assertEquals(2, narrow.records.size, "the ${narrowGap}-doc-px piece is dropped, so two records")
        assertEquals(firstLeg - pad, narrow.records[0].samples.last().x.toDouble(), 0.1, "x = 100 - 3.5")
        assertEquals(107.4 + pad, narrow.records[1].samples.first().x.toDouble(), 0.1, "x = 107.4 + 3.5")

        val wide = erased(listOf(line), uPath(107.6), InkEraseMode.PARTIAL)
        assertEquals(3, wide.records.size, "a ${wideGap}-doc-px piece is over the floor, so it stays")
        val speck = wide.records[1]
        assertEquals(firstLeg + pad, speck.samples.first().x.toDouble(), 0.1, "x = 100 + 3.5")
        assertEquals(107.6 - pad, speck.samples.last().x.toDouble(), 0.1, "x = 107.6 - 3.5")
        assertTrue(
            speck.samples.size >= 2,
            "the kept piece holds ${speck.samples.size} samples over $wideGap doc px, so the " +
                "2-SAMPLE floor could not have removed its ${narrowGap}-doc-px twin two lines above",
        )
        assertEquals(wideGap, (107.6 - pad) - (firstLeg + pad), 0.1, "and its arc is the gap it was cut to")
    }

    /**
     * THIS FILE'S OWN FLOOR, at the only boundary it can be seen at.
     *
     * The floor is two SAMPLES, and the narrowest piece that survives the inherited arc rule is 0.5
     * doc px. On a recording sampled every 2 doc px a 0.5-doc-px piece therefore brackets to exactly
     * two samples - the last sample at or before the cut's near edge and the first at or after its
     * far edge - and that piece is KEPT. A floor of three would delete it, which is what makes this
     * the non-vacuity test for the constant rather than an assertion about arithmetic.
     *
     * GEOMETRY. Same U, `pad = 0.5 + 3 = 3.5`, legs at x = 100 and x = 107.5, so the touches are
     * 96.5..103.5 and 104.0..111.0, the gap piece is 103.5..104.0 which is exactly 0.5 doc px and so
     * exactly on the floor, and the pieces are x 0..96.5, 103.5..104.0 and 111.0..200. On a 2-doc-px
     * recording those bracket to x 0..98 (50 samples), x 102..104 (2 samples) and x 110..200
     * (46 samples): 50 + 2 + 46 = 98 of 101, the 3 gone being x = 100 and x = 106 and x = 108.
     */
    @Test
    fun aSurvivorOfExactlyTwoSamplesIsALineAndIsKept() {
        val line = lineH("s", 101, 0.0, 0.0, step = 2.0)
        val out = erased(listOf(line), uPath(107.5), InkEraseMode.PARTIAL)

        assertEquals(3, out.records.size, "0.5 doc px is exactly on the inherited floor, so it is kept")
        assertEquals(listOf(50, 2, 46), out.records.map { it.samples.size }, "derived above")
        assertEquals(2, out.records[1].samples.size, "the middle piece is the floor's own boundary")
        assertTrue(
            InkReplay.dabs(out.records[1].copy(brushId = PENCIL_ID), pencilBrush()).isNotEmpty(),
            "two samples really do re-brush into a line: DabPlacer makes a segment of them",
        )
    }

    /**
     * The guarantee the sample floor rests on, asserted over a battery rather than in one case.
     *
     * `InkErase.survivorSamples` takes the first sample at or before the piece's start and the last
     * at or after its end, over a non-decreasing array of sample arc lengths. If those lengths are
     * `L0 < L1` then by construction `sampleArc[i0] <= L0 < L1 <= sampleArc[i1]`, so `i0 < i1` and
     * every survivor holds at least two samples. The floor of two is therefore unreachable by the
     * bracketing - which is worth PINNING rather than leaving as a belief, because the moment the
     * bracketing changes this test is what notices.
     *
     * The battery is two sample spacings, four eraser radii and all three modes, and it asserts
     * both that survivors came back (a battery that cut nothing would pass for the wrong reason)
     * and that none of them is a single sample.
     */
    @Test
    fun everySurvivorHoldsAtLeastTwoSamples() {
        var made = 0
        for (step in listOf(2.0, 0.1)) {
            val line = lineH("s", (200.0 / step).toInt() + 1, 0.0, 0.0, step = step)
            val cross = lineV("v", 21, 100.0, -20.0)
            for (radius in listOf(0.5, 3.0, 8.0, 40.0)) {
                for (mode in InkEraseMode.entries) {
                    val out = erased(listOf(line, cross), dot(140.0, 0.0, radius), mode)
                    for (record in out.records) {
                        val size = record.samples.size
                        assertTrue(
                            size >= 2,
                            "spacing $step, eraser radius $radius, $mode: survivor ${record.id} " +
                                "holds $size samples, and one sample is a dab rather than a line",
                        )
                        made++
                    }
                }
            }
        }
        assertTrue(made > 20, "the battery produced only $made survivors; it must be cutting something")
    }

    // ---- 5. one gesture is one pass ---------------------------------------------------------------

    /**
     * ONE pass over the records as they arrived, never over the survivors this run is building.
     *
     * Running the eraser repeatedly over its own output is the classic eraser-drag bug: a fast drag
     * cuts a staircase. A builder who looped `run` over its own records would produce more pieces
     * than touches, and their edges would sit at the previous cut rather than at the path.
     *
     * GEOMETRY. The path dips onto the line at x = 20, 80 and 200 and returns to y = 7, which is
     * `7 > pad = 3 + 3 = 6` above it, so exactly three stretches are touched: 14..26, 74..86 and
     * 194..200. Three touches make four survivors, and the third touch reaches the end of the line,
     * which removes one of them: three. The cut edges therefore sit at the path's own crossing
     * positions plus and minus 6 doc px of half-width, and nowhere else.
     */
    @Test
    fun aPathTouchingThreePlacesCutsThreeTimesAndNotAStaircase() {
        val h = lineH("h", 101, 0.0, 0.0)
        val out = erased(listOf(h), dipPath(), InkEraseMode.PARTIAL)

        assertEquals(3, out.records.size, "three touches, and the last one runs off the end: three pieces")
        assertEquals(0.0, out.records[0].samples.first().x.toDouble(), 0.0)
        assertEquals(14.0, out.records[0].samples.last().x.toDouble(), 2.0, "20 - 6")
        assertEquals(26.0, out.records[1].samples.first().x.toDouble(), 2.0, "20 + 6")
        assertEquals(74.0, out.records[1].samples.last().x.toDouble(), 2.0, "80 - 6")
        assertEquals(86.0, out.records[2].samples.first().x.toDouble(), 2.0, "80 + 6")
        assertEquals(194.0, out.records[2].samples.last().x.toDouble(), 2.0, "200 - 6")
        assertEquals(listOf(8, 25, 55), out.records.map { it.samples.size }, "x = 0..14, 26..74, 86..194")
    }

    // ---- 6. THE CASE THAT MATTERS MOST: a partial erase, one line left alone, one sentence ----------

    /**
     * A drag that crosses three lines it can erase and one it cannot must ERASE THE THREE, REPORT
     * THE FOURTH, and LEAVE THE FOURTH EXACTLY AS IT WAS - with one undo step that restores what
     * changed and nothing else.
     *
     * The old wording of this row refused the whole gesture, which is worse for the person than
     * erasing what can be erased: a layer with one missing brush id would have an eraser that does
     * nothing at all, every time, for a reason they have to work out.
     *
     * FOUR PROPERTIES, FOUR ASSERTIONS, because one assertion cannot say which half broke:
     *  1. the rest really was erased, measured AGAINST A BASELINE - the same fixture with the
     *     un-replayable record removed must give byte-identical survivors;
     *  2. the fourth is REPORTED, once, naming its stroke and its brush, in `InkReplay`'s own words;
     *  3. the fourth is BYTE-IDENTICAL - the very same record instance comes back, and it
     *     contributes nothing to the undo step;
     *  4. the undo step restores EXACTLY the input cel, which is the only claim about an undo that
     *     is worth making.
     *
     * GEOMETRY. The eraser is a single vertical segment from (140, -10) to (140, 110), so every
     * horizontal line it crosses is cut over x 134..146 - `pad = 3 + 3 = 6` either side of 140. It
     * crosses all three of a (y = 0), b (y = 50) and c (y = 100), and c's brush is not in the
     * library, so c has no replayable geometry at all.
     */
    @Test
    fun aLineThatCannotBeReplayedIsLeftAloneAndReportedWhileTheRestIsErased() {
        val a = lineH("a", 101, 0.0, 0.0)
        val b = lineH("b", 101, 0.0, 50.0)
        val c = lineH("c", 101, 0.0, 100.0, brushId = MISSING_ID)
        val path = EraserPath(doubleArrayOf(140.0, 140.0), doubleArrayOf(-10.0, 110.0), 3.0)

        val out = erased(listOf(a, b, c), path, InkEraseMode.PARTIAL)

        // ---- 1. the rest was erased, against a baseline, not by assertion ----
        val baseline = erased(listOf(a, b), path, InkEraseMode.PARTIAL)
        val survivorsHere = out.records.filter { it.id != "c" }
        assertEquals(baseline.records, survivorsHere, "c's presence changes nothing about a or b")
        assertEquals(listOf("a", "a~2", "b", "b~2"), survivorsHere.map { it.id })
        assertEquals(68, survivorsHere[0].samples.size, "x = 0..134, every 2 doc px")
        assertEquals(28, survivorsHere[1].samples.size, "x = 146..200")
        assertTrue(baseline.before.isNotEmpty(), "the baseline must be cutting something at all")

        // ---- 2. the fourth is reported, once, in InkReplay's own words ----
        assertEquals(1, out.notReplayed.size, "exactly one report, not one per gesture and not none")
        val report = out.notReplayed[0]
        assertEquals("c", report.strokeId)
        assertEquals(MISSING_ID, report.brushId, "the id brushOf was asked for, which is the record's own")
        assertEquals(InkReplay.refusal(c, null), report.reason, "the wording exists in one place: InkReplay's")
        assertTrue(report.reason.contains("c"), "the sentence names the stroke")
        assertTrue(report.reason.contains(MISSING_ID), "and the brush")

        // ---- 3. the fourth is byte-identical, and costs no undo step ----
        val cameBack = out.records.filter { it.id == "c" }
        assertEquals(1, cameBack.size, "c is not deleted: a later build with that brush can still draw it")
        assertTrue(cameBack[0] === c, "c comes back as the caller's own record, not an equal copy")
        assertTrue(!out.before.containsKey("c"), "c never changed, so undo does not claim it did")

        // ---- 4. one undo step restores exactly the input cel ----
        assertEquals(setOf("a", "b"), out.before.keys, "only what changed, and nothing else")
        assertTrue(out.before["a"] === a)
        assertTrue(out.before["b"] === b)
        assertEquals(listOf(a, b, c), undoOf(out), "undo puts the cel back exactly as it was")

        // The same shape with a brush that EXISTS and simply cannot draw an ink line, so the
        // sentence is InkReplay's other one and names the engine rather than a missing id.
        val smudged = lineH("c", 101, 0.0, 100.0, brushId = SMUDGE_ID)
        val brushes = library(inkBrush(), pencilBrush(), smudgeBrush())
        val withSmudge = InkErase.run("cel", listOf(a, b, smudged), path, InkEraseMode.PARTIAL, brushes)
        val erasedSmudge = asErased(withSmudge)
        assertEquals(1, erasedSmudge.notReplayed.size)
        assertEquals(SMUDGE_ID, erasedSmudge.notReplayed[0].brushId)
        assertEquals(InkReplay.refusal(smudged, smudgeBrush()), erasedSmudge.notReplayed[0].reason)
        assertTrue(erasedSmudge.notReplayed[0].reason.contains("smudge"), "the engine is named")
        assertTrue(erasedSmudge.records.any { it === smudged })

        // And the third report, which is OURS rather than InkReplay's: a `fill` record. The fill pen
        // is a legal ink brush, so `InkReplay.refusal` says nothing about it - but its raster is
        // `InkRaster.fill` over `FillPen.outline`, so it has no centreline to cut and the eraser
        // cannot invent one. Reported, left alone, and not deleted, exactly as the other two are.
        val shape = lineH("c", 101, 0.0, 100.0, brushId = FILL_ID)
        val withFill = asErased(
            InkErase.run(
                "cel", listOf(a, b, shape), path, InkEraseMode.PARTIAL,
                library(inkBrush(), pencilBrush(), fillBrush()),
            ),
        )
        assertEquals(1, withFill.notReplayed.size, "a fill under the eraser is one report, like any other")
        assertEquals(FILL_ID, withFill.notReplayed[0].brushId)
        assertTrue(withFill.notReplayed[0].reason.contains("c"), "the sentence names the stroke")
        assertTrue(withFill.notReplayed[0].reason.contains(FILL_ID), "and the brush")
        assertTrue(withFill.notReplayed[0].reason.contains("fill"), "and says what is wrong with it")
        assertTrue(withFill.records.any { it === shape }, "and the shape is left alone, not deleted")
        assertEquals(setOf("a", "b"), withFill.before.keys, "and it costs no undo step")
        // The rest of the cel is still erased, so the fill is a report and not a refusal.
        assertEquals(baseline.records, withFill.records.filter { it.id != "c" })
    }

    // ---- 7. an un-replayable line somewhere else is not an event -----------------------------------

    /**
     * A missing brush ON the cel is not the same fact as a missing brush UNDER the eraser, and only
     * the second one is worth a sentence: the difference is whether the person was trying to erase
     * something.
     *
     * The first half is `Untouched` with no report at all, and the second half is the same fixture
     * with the eraser moved onto `a` - which is the non-vacuity, because "there is a missing brush
     * on this layer" must not be reported by every stroke of the eraser.
     */
    @Test
    fun anUnReplayableLineTheEraserNeverReachedIsNotEvenAReport() {
        val a = lineH("a", 101, 0.0, 0.0)
        val b = lineH("b", 101, 0.0, 50.0)
        val c = lineH("c", 101, 0.0, 100.0, brushId = MISSING_ID)
        val records = listOf(a, b, c)

        // 500 doc px below the nearest line (y = 100), so nothing at all is touched.
        val far = InkErase.run("cel", records, dot(140.0, 500.0, 3.0), InkEraseMode.PARTIAL, library())
        assertTrue(far is InkEraseOutcome.Untouched, "nothing came near anything, so nothing happened")

        // The same fixture with the eraser onto `a` only: 50 doc px from b and 100 from c, both
        // outside pad = 6, so `a` is cut and the one line the person rubbed over is reported.
        val near = asErased(InkErase.run("cel", records, dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL, library()))
        assertEquals(listOf("a", "a~2", "b", "c"), near.records.map { it.id })
        assertEquals(setOf("a"), near.before.keys)
        assertEquals(1, near.notReplayed.size, "the person was trying to erase something, so they hear about it")
        assertEquals("c", near.notReplayed[0].strokeId)
    }

    // ---- 8. Untouched and fully erased are different answers ---------------------------------------

    /**
     * "Empty result" and "no change" are not the same thing, and this is the test that stops the
     * first from quietly losing a whole line.
     *
     * `Untouched` is returned for four reasons and no others: an empty record list, a path with no
     * samples, a radius that is zero or is not a number, and an eraser that reached no line it
     * could measure. Every one of them is asserted, and every one of them is a gesture that
     * changed nothing - which is what makes `Untouched` safe to skip.
     */
    @Test
    fun untouchedIsOnlyEverReturnedForAGestureThatChangedNothing() {
        val h = lineH("h", 101, 0.0, 0.0)
        val brush = library(inkBrush(), pencilBrush())

        // An eraser 500 doc px from the only line.
        assertTrue(
            InkErase.run("cel", listOf(h), dot(140.0, 500.0, 3.0), InkEraseMode.PARTIAL, brush)
                is InkEraseOutcome.Untouched,
        )
        // An empty record list.
        assertTrue(
            InkErase.run("cel", emptyList(), dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL, brush)
                is InkEraseOutcome.Untouched,
        )
        // A half-built path: the person has put the eraser down and has not moved it yet.
        assertTrue(
            InkErase.run("cel", listOf(h), EraserPath(DoubleArray(0), DoubleArray(0), 3.0), InkEraseMode.PARTIAL, brush)
                is InkEraseOutcome.Untouched,
        )
        // A radius that is not a usable number would make VectorEraser compare against NaN and match
        // nothing, silently; a sentence is better than silence.
        for (radius in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(
                InkErase.run("cel", listOf(h), dot(140.0, 0.0, radius), InkEraseMode.PARTIAL, brush)
                    is InkEraseOutcome.Untouched,
                "radius $radius must be Untouched and must not throw",
            )
        }

        // And the other answer: the eraser really did take the line, so the cel is empty AND the
        // undo step is not. That is the pair `Untouched` must never be confused with.
        val whole = erased(listOf(h), dot(140.0, 0.0, 3.0), InkEraseMode.WHOLE_STROKE)
        assertEquals(emptyList(), whole.records, "nothing is left of the cel")
        assertEquals(listOf("h"), whole.before.keys.toList(), "the undo step still has the line in it")
        assertTrue(whole.before["h"] === h)

        // WHAT THE CONTRACT DOES NOT CARRY, asserted rather than wished for. A record the eraser
        // took WHOLE leaves no survivor holding its place, so nothing in this outcome says where it
        // stood among the records that were never touched. The round trip is exact when a cut
        // leaves a survivor (test 6 proves it); it is not exact here, and pretending otherwise is
        // the one thing a caller must not do. `before` does iterate in the cel's own order, so the
        // RELATIVE order of several removed records survives; the interleaving does not.
        val two = lineH("k", 101, 0.0, 50.0)
        val both = erased(
            listOf(h, two),
            EraserPath(doubleArrayOf(140.0, 140.0), doubleArrayOf(-10.0, 110.0), 3.0),
            InkEraseMode.WHOLE_STROKE,
        )
        assertEquals(listOf("h", "k"), both.before.keys.toList(), "and in the cel's own order")
    }

    // ---- 9. hostile and empty input ----------------------------------------------------------------

    /**
     * Nothing here throws, and a recording with no samples is not a line, is not erased, and is not
     * reported - there is no brush to complain about and the eraser did nothing to it.
     */
    @Test
    fun emptyAndHostileInputIsUntouchedAndAValuelessRecordIsSimplyNotALine() {
        val h = lineH("h", 101, 0.0, 0.0)
        val empty = rec("z", emptyList())
        val brush = library(inkBrush())

        for (path in listOf(
            EraserPath(DoubleArray(0), DoubleArray(0), 3.0),
            dot(140.0, 0.0, 0.0),
            dot(Double.NaN, 0.0, 3.0),
            EraserPath(doubleArrayOf(140.0), doubleArrayOf(0.0), Double.NaN),
        )) {
            assertTrue(
                InkErase.run("cel", listOf(h), path, InkEraseMode.PARTIAL, brush) is InkEraseOutcome.Untouched,
                "a hostile path must be Untouched and must not throw: $path",
            )
        }

        val out = erased(listOf(h, empty), dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL)
        assertEquals(listOf("h", "h~2", "z"), out.records.map { it.id }, "z is untouched and still on the cel")
        assertTrue(out.records[2] === empty)
        assertEquals(emptyList<NotReplayed>(), out.notReplayed, "a record with no samples is not a report")
        assertEquals(setOf("h"), out.before.keys, "and it costs no undo step")
    }

    // ---- 10. one gesture is one undo step -----------------------------------------------------------

    /**
     * The undo step holds the records that changed and nothing else, asserted in BOTH directions,
     * because both mistakes are silent: a step carrying untouched records is wasteful, and a step
     * missing a changed one is broken undo.
     */
    @Test
    fun theUndoStepHoldsExactlyTheRecordsThatChangedAndNoOthers() {
        val a = lineH("a", 101, 0.0, 0.0)
        val b = lineH("b", 101, 0.0, 500.0) // never touched
        val c = lineH("c", 101, 0.0, 50.0, brushId = MISSING_ID)
        val out = erased(listOf(a, b, c), dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL)

        assertEquals(setOf("a"), out.before.keys, "b was never touched and c never changed")
        for (id in out.before.keys) {
            assertTrue(
                out.records.none { it == out.before.getValue(id) },
                "$id is in the undo step, so it must differ from what is on the cel now",
            )
        }
        assertTrue(out.before.getValue("a") === a)
        assertEquals(listOf(a, b, c), undoOf(out))
    }

    // ---- 11. the whole surviving cel, in drawing order ----------------------------------------------

    /**
     * A caller replaces the cel with this list, so an order change is a visible reordering of the
     * drawing: the survivors of a cut line take the position the original held, and every other
     * record keeps its own.
     */
    @Test
    fun theSurvivingCelComesBackInDrawingOrderWithSurvivorsWhereTheOriginalWas() {
        val records = (0 until 5).map { lineH("r$it", 101, 0.0, it * 100.0) }
        // GEOMETRY, and the reason the path has to step aside rather than run straight down. A plain
        // vertical sweep from y = -10 to y = 310 at x = 140 would cross r0, r1, r2 and r3 alike,
        // because a segment of the path passes over each of them. So the path goes down over r0,
        // steps out to x = 300 at y = 10, runs up past r1 at x = 300, and steps back in at y = 190.
        // The two short verticals are on r0 (y = 0) and r2 (y = 200); everything else is at least
        // 10 doc px above or below a line, and `pad = 3 + 3 = 6`, so nothing else is touched: the
        // y = 10 and y = 190 runs are 10 from the nearest line, and the x = 300 leg is 100 doc px to
        // the right of where r1 and r3 end.
        val path = EraserPath(
            doubleArrayOf(140.0, 140.0, 300.0, 300.0, 140.0, 140.0),
            doubleArrayOf(-10.0, 10.0, 10.0, 190.0, 190.0, 210.0),
            3.0,
        )
        val out = erased(records, path, InkEraseMode.PARTIAL)

        assertEquals(
            listOf("r0", "r0~2", "r1", "r2", "r2~2", "r3", "r4"),
            out.records.map { it.id },
            "survivors stand where their original stood, and the rest keep their relative order",
        )
        val byId = records.associateBy { it.id }
        for (id in listOf("r1", "r3", "r4")) {
            assertTrue(out.records.any { it === byId.getValue(id) }, "$id is untouched and passes through")
        }
        val untouched = setOf("r1", "r3", "r4")
        assertEquals(
            records.filter { it.id in untouched }.toSet(),
            out.records.filter { it.id in untouched }.toSet(),
            "and the three untouched records are the only ones besides the cut pair",
        )
    }

    // ---- 12. idempotence ------------------------------------------------------------------------------

    /**
     * Running the eraser again over its own output changes nothing.
     *
     * Both halves of the answer are asserted, because "nothing happened" has two honest shapes: the
     * eraser may find nothing left to reach (`Untouched`), or it may graze the very edge of what it
     * cut last time and cut a zero-width nothing out of it. The second is not a change - a
     * zero-width cut leaves the complement whole, so the record comes back identical - and the test
     * does not care which shape it got, only that no sample and no id moved.
     *
     * The first pass is asserted to have really cut something, so this cannot pass for the wrong
     * reason.
     */
    @Test
    fun runningTwiceWithTheSamePathChangesNothingTheSecondTime() {
        val h = lineH("h", 101, 0.0, 0.0)
        val first = erased(listOf(h), dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL)
        assertEquals(2, first.records.size, "the first pass must have really cut something")
        assertTrue(first.before.isNotEmpty(), "and it must have changed something to undo")

        val again = InkErase.run("cel", first.records, dot(140.0, 0.0, 3.0), InkEraseMode.PARTIAL, library())
        if (again is InkEraseOutcome.Erased) {
            assertEquals(first.records, again.records, "same ids, same sample counts, same order")
            assertTrue(again.before.isEmpty(), "and nothing changed, so undo has nothing to hold")
        }
        // `Untouched` means the caller keeps the cel it already has, so both shapes must leave the
        // records exactly where the first pass left them.
        val afterSecond = if (again is InkEraseOutcome.Erased) again.records else first.records
        assertEquals(first.records, afterSecond, "either shape leaves the cel exactly as it was")

        val whole = erased(listOf(h), dot(140.0, 0.0, 3.0), InkEraseMode.WHOLE_STROKE)
        assertEquals(emptyList(), whole.records)
        val twice = InkErase.run("cel", whole.records, dot(140.0, 0.0, 3.0), InkEraseMode.WHOLE_STROKE, library())
        assertEquals(
            emptyList(),
            if (twice is InkEraseOutcome.Erased) twice.records else whole.records,
            "an empty cel stays empty, and a second pass cannot find anything to bring back",
        )
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    /**
     * The shipped Ink, so a dab's half-width is exactly 3.0 doc px and every cut edge in this file
     * is arithmetic. `size` is a DIAMETER, which is the whole reason the reach is `radius + 3`.
     */
    private fun inkBrush(
        id: String = INK_ID,
        name: String = "Ink",
        engine: String = "stamp",
    ) = BrushPreset(
        id = id,
        name = name,
        engine = engine,
        size = Param(6f),
        tip = TipSpec(corner = 2f, hardness = Param(1f)),
        spacing = 0.04f,
        accumulate = "wash",
    )

    private fun pencilBrush() = inkBrush(id = PENCIL_ID, name = "Pencil")

    /** An engine that exists in the library and cannot draw an ink line: it reads pixels underneath. */
    private fun smudgeBrush() = inkBrush(id = SMUDGE_ID, name = "Smudge", engine = "smudge")

    private fun fillBrush() = inkBrush(id = FILL_ID, name = "Fill", engine = ENGINE_FILL)

    /** The library a cel draws from: anything not named here is a brush this build does not have. */
    private fun library(
        vararg brushes: BrushPreset = arrayOf(inkBrush(), pencilBrush()),
    ): (StrokeRecord) -> BrushPreset? = { record -> brushes.firstOrNull { it.id == record.brushId } }

    private fun rec(
        id: String,
        points: List<Pair<Double, Double>>,
        brushId: String = INK_ID,
        seed: Long = 7L,
    ) = StrokeRecord(
        id = id,
        brushId = brushId,
        seed = seed,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = points.mapIndexed { i, (x, y) -> PenSample(x.toFloat(), y.toFloat(), i * 8.0, 1f) },
        colorArgb = INK_COLOUR,
        widthScale = 1f,
    )

    /** `n` samples `step` doc px apart along y = y0, starting at x = x0. */
    private fun lineH(
        id: String,
        n: Int,
        x0: Double,
        y0: Double,
        step: Double = 2.0,
        brushId: String = INK_ID,
    ) = rec(id, (0 until n).map { (x0 + it * step) to y0 }, brushId = brushId)

    /** `n` samples `step` doc px apart along x = x0, starting at y = y0. */
    private fun lineV(id: String, n: Int, x0: Double, y0: Double, step: Double = 2.0) =
        rec(id, (0 until n).map { x0 to (y0 + it * step) })

    /** An eraser that never moved: one sample is a dot, by `EraserPath`'s own KDoc. */
    private fun dot(x: Double, y: Double, radius: Double) =
        EraserPath(doubleArrayOf(x), doubleArrayOf(y), radius)

    /**
     * Down onto the line at x = 20, 80 and 200 and back up to y = 7, which is `7 > pad = 6` above
     * it. So exactly three stretches of the line are touched, at 20, 80 and 200, each over +/- 6.
     */
    private fun dipPath() = EraserPath(
        doubleArrayOf(20.0, 20.0, 20.0, 80.0, 80.0, 80.0, 200.0, 200.0, 200.0),
        doubleArrayOf(7.0, 0.0, 7.0, 7.0, 0.0, 7.0, 7.0, 0.0, 7.0),
        3.0,
    )

    /**
     * The same U at radius 0.5 - so `pad = 3.5` - with its legs at x = 100 and x = [second], and the
     * gap it leaves on the line is `second - 107.0` doc px.
     */
    private fun uPath(second: Double) = EraserPath(
        doubleArrayOf(100.0, 100.0, second, second),
        doubleArrayOf(0.0, 7.0, 7.0, 0.0),
        0.5,
    )

    // ---- helpers -------------------------------------------------------------------------------------

    private fun erased(
        records: List<StrokeRecord>,
        path: EraserPath,
        mode: InkEraseMode,
        brushes: (StrokeRecord) -> BrushPreset? = library(),
    ): InkEraseOutcome.Erased =
        asErased(InkErase.run("cel", records, path, mode, brushes))

    private fun asErased(outcome: InkEraseOutcome): InkEraseOutcome.Erased {
        assertTrue(
            outcome is InkEraseOutcome.Erased,
            "expected Erased and got Untouched, which means the eraser reached nothing",
        )
        return outcome as InkEraseOutcome.Erased
    }

    /**
     * Undo, as a caller has to do it with this contract: a group of survivors is keyed by the
     * original id, which is the only id in the group that carries no `~n` suffix, and `before` holds
     * the record to put back in that group's first position.
     *
     * EXACT FOR A CUT, WHICH IS THE CASE THAT MATTERS. A record the eraser took WHOLE leaves no
     * survivor to hold its place, so this cannot place it and must not pretend to; test 8 asserts
     * what the contract really carries in that case, which is the record and its place in `before`'s
     * own iteration order.
     */
    private fun undoOf(outcome: InkEraseOutcome.Erased): List<StrokeRecord> {
        val out = ArrayList<StrokeRecord>(outcome.before.size)
        val restored = HashSet<String>()
        for (record in outcome.records) {
            val key = outcome.before[record.id]?.id ?: unsuffixedKeyIn(outcome.before, record.id)
            if (key == null) {
                out.add(record)
                continue
            }
            if (restored.add(key)) out.add(outcome.before.getValue(key))
        }
        return out
    }

    private fun unsuffixedKeyIn(before: Map<String, StrokeRecord>, id: String): String? {
        val cut = id.lastIndexOf('~')
        if (cut <= 0) return null
        val base = id.substring(0, cut)
        return if (before.containsKey(base)) base else null
    }

    /**
     * Where [window] sits inside [original], or -1. Compared by IDENTITY, so a rebuilt sample is not
     * the sample it was copied from and a non-contiguous run is not a run at all.
     */
    private fun offsetOf(original: List<PenSample>, window: List<PenSample>): Int {
        if (window.isEmpty() || window.size > original.size) return -1
        for (i in 0..original.size - window.size) {
            var same = true
            for (k in window.indices) {
                if (original[i + k] !== window[k]) {
                    same = false
                    break
                }
            }
            if (same) return i
        }
        return -1
    }

    private companion object {
        const val INK_ID = "joybrush.ink"
        const val PENCIL_ID = "joybrush.pencil"
        const val SMUDGE_ID = "joybrush.smudge"
        const val FILL_ID = "joybrush.fill"
        const val MISSING_ID = "joybrush.not-in-this-document"

        /** Straight (non-premultiplied) sRGB with an alpha byte, as `StrokeRecord` stores it. */
        const val INK_COLOUR = 0xFF3366CC.toInt()

        /**
         * `VectorEraser.MIN_PIECE_ARC`, which is private there, written out because the geometry in
         * this file is arithmetic on it and an expected value with no derivation is a guess. The
         * rule that OWNS the threshold is `VectorEraser.kt`, applied once for all three modes; this
         * is a transcription for a test, not a second rule in the engine.
         */
        const val MIN_PIECE_ARC = 0.5
    }
}
