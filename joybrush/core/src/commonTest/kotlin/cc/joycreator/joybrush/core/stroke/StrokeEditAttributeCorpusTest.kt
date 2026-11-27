package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JB-NOW-25 — editable stroke attribute invariants.
 *
 * What this covers that [StrokeEditTest] does not: every documented UNTOUCHED field of each of
 * the four [StrokeEdit] ops is compared by RAW BITS ([Float.toRawBits] / [Double.toRawBits]),
 * because data-class `==` treats `-0.0f == 0.0f` and `NaN == NaN` and would hide exactly the
 * corruptions this file hunts. The selected change of each op is specified independently
 * (hand-computed falloff weights, exact width products, exact id/colour values); nothing is
 * checked against itself.
 *
 * Per-op contract pinned here (from [StrokeEdit] KDoc; the op's own selected change is the one
 * field this file never demands preserved):
 * - reshape: only x/y of samples within radius may change. Preserved bit-exact: id, brushId,
 *   seed, smoothing, screenPerDoc, colorArgb, widthScale, every raw sensor channel
 *   (pressure/tilt/azimuth/barrel incl. distinct NaN payloads and -0.0), every timeMs, every
 *   tool, sample order, predicted flags.
 * - reweight: only widthScale may change (multiplicative, clamped to
 *   MIN..MAX_WIDTH_SCALE). The sample list instance is shared, never rebuilt.
 * - rebrush: only brushId may change. Samples, seed and every other field are bit-exact.
 * - recolor: only colorArgb may change.
 *
 * Also pinned: no-op edits (zero drag, NaN inputs, factor 1, same brush/colour, empty stroke)
 * and boundary selections (single/two-sample strokes, grab ties, samples exactly at the
 * radius, non-finite samples never grabbed, NaN gaps contributing zero arc length).
 *
 * Deliberately NOT demanded (the op intentionally changes these, per contract): reshaped
 * positions/bounds, reweighted replay width, rebrushed rendered shape (physics lives in
 * [StrokeEditTest]'s fill-shape tests), recoloured pixels. No capture/session behaviour and no
 * JB-5.20 wiring is opened here.
 *
 * Bounds: <= 12 samples per fixture, deterministic ids/seeds (repro `jb25-*`). CPU only.
 * No Gradle run per contract; symbols verified by reading production sources.
 */
class StrokeEditAttributeCorpusTest {

    // ---- fixtures ---------------------------------------------------------------------------

    /** Distinct sensor channels per sample, incl. -0.0 and distinct NaN payloads. */
    private fun richSamples(n: Int, x0: Float = 0f, dx: Float = 1f): List<PenSample> =
        List(n) { i ->
            PenSample(
                x = x0 + i * dx,
                y = (i % 3).toFloat(),
                timeMs = 1_700_000.0 + i * 8.125,
                pressure = if (i % 4 == 0) -0.0f else 0.1f + i * 0.07f,
                tilt = when (i % 4) {
                    0 -> Float.fromBits(0x7F800001)
                    1 -> -0.0f
                    2 -> 0.5f + i
                    else -> Float.fromBits(0x7FC00001 + i)
                },
                azimuth = when (i % 3) {
                    0 -> Float.NaN
                    1 -> Float.fromBits(0x7F800002 + i)
                    else -> -1.25f - i * 0.5f
                },
                barrel = when (i % 3) {
                    0 -> 0.2f * i
                    1 -> Float.fromBits(0x7FC00002 + i)
                    else -> Float.NaN
                },
                tool = Tool.entries[i % Tool.entries.size],
            )
        }

    private fun record(
        id: String = "jb25-base",
        brushId: String = "pencil",
        seed: Long = 0x25ED_5EED_1234L,
        smoothing: Float = 0.25f,
        screenPerDoc: Float = -0.0f,
        samples: List<PenSample> = richSamples(11),
        colorArgb: Int = 0xFF203040.toInt(),
        widthScale: Float = 1f,
    ): StrokeRecord = StrokeRecord(
        id = id, brushId = brushId, seed = seed, smoothing = smoothing,
        screenPerDoc = screenPerDoc, samples = samples,
        colorArgb = colorArgb, widthScale = widthScale,
    )

    // ---- assertion core: raw bits everywhere --------------------------------------------------

    private fun assertSensorBits(expected: PenSample, actual: PenSample, tag: String) {
        assertEquals(expected.pressure.toRawBits(), actual.pressure.toRawBits(), "$tag pressure bits")
        assertEquals(expected.tilt.toRawBits(), actual.tilt.toRawBits(), "$tag tilt bits")
        assertEquals(expected.azimuth.toRawBits(), actual.azimuth.toRawBits(), "$tag azimuth bits")
        assertEquals(expected.barrel.toRawBits(), actual.barrel.toRawBits(), "$tag barrel bits")
        assertEquals(expected.timeMs.toRawBits(), actual.timeMs.toRawBits(), "$tag timeMs bits")
        assertEquals(expected.tool, actual.tool, "$tag tool")
        assertEquals(expected.predicted, actual.predicted, "$tag predicted")
    }

    private fun assertSampleListSensorBits(expected: List<PenSample>, actual: List<PenSample>, tag: String) {
        assertEquals(expected.size, actual.size, "$tag sample count")
        for (i in expected.indices) assertSensorBits(expected[i], actual[i], "$tag sample $i")
    }

    private fun assertMetadataBits(expected: StrokeRecord, actual: StrokeRecord, tag: String) {
        assertEquals(expected.id, actual.id, "$tag id")
        assertEquals(expected.seed, actual.seed, "$tag seed")
        assertEquals(expected.smoothing.toRawBits(), actual.smoothing.toRawBits(), "$tag smoothing bits")
        assertEquals(expected.screenPerDoc.toRawBits(), actual.screenPerDoc.toRawBits(), "$tag screenPerDoc bits")
    }

    // ---- 1. reshape: selected x/y change, everything else bit-exact ------------------------------

    @Test
    fun reshapeMovesSelectedSamplesAndPreservesEveryUntouchedBit() {
        // Flat line 1 px apart: arc[i] == i exactly, so u = |i-5|/4 is exact in binary.
        // (richSamples' staggered y would make arc lengths irrational; exactness needs this.)
        val r = record(samples = richSamples(11).map { it.copy(y = 0f) })
        val moved = StrokeEdit.reshape(r, grabX = 5f, grabY = 0f, dx = 3f, dy = -2f, radius = 4f)

        // The grab moves the whole offset.
        assertEquals(8f, moved.samples[5].x, "grabbed sample moves dx in full")
        assertEquals(-2f, moved.samples[5].y, "grabbed sample moves dy in full")

        // Samples exactly AT the radius (arc distance 4: indices 1 and 9) do not move at all.
        for (i in listOf(0, 1, 9, 10)) {
            assertEquals(r.samples[i].x.toRawBits(), moved.samples[i].x.toRawBits(), "sample $i x bits at/beyond radius")
            assertEquals(r.samples[i].y.toRawBits(), moved.samples[i].y.toRawBits(), "sample $i y bits at/beyond radius")
        }

        // One hand-computed interior point: i=4, s=1, R=4 -> u=0.25, w=(1-1/16)^2=225/256,
        // dx*w = 675/256 = 2.63671875, dy*w = -450/256 = -1.7578125. All exact in binary.
        assertEquals(6.63671875f, moved.samples[4].x, 1e-6f, "interior weight (1-(s/R)^2)^2, dx side")
        assertEquals(-1.7578125f, moved.samples[4].y, 1e-6f, "interior weight, dy side (0 + -1.7578125)")

        // Monotone falloff on both sides of the grab (strictly decreasing displacement).
        val disp = { i: Int -> kotlin.math.abs(moved.samples[i].x - r.samples[i].x) }
        for (i in 2..5) assertTrue(disp(i) > disp(i - 1), "right-side falloff must shrink toward radius, at $i")
        for (i in 5..8) assertTrue(disp(i) > disp(i + 1), "left-side falloff must shrink toward radius, at $i")

        // Untouched record fields, bit-exact (screenPerDoc is -0.0f here on purpose).
        assertMetadataBits(r, moved, "reshape")
        assertEquals(r.brushId, moved.brushId, "reshape keeps brushId")
        assertEquals(r.colorArgb, moved.colorArgb, "reshape keeps colorArgb")
        assertEquals(r.widthScale.toRawBits(), moved.widthScale.toRawBits(), "reshape keeps widthScale bits")

        // Every raw sensor channel, time and tool survive on moved AND unmoved samples alike.
        assertSampleListSensorBits(r.samples, moved.samples, "reshape")
    }

    // ---- 2. reshape: sensor payloads survive on the samples that moved ----------------------------

    @Test
    fun reshapeKeepsDistinctNaNPayloadsOnMovedSamples() {
        val r = record(id = "jb25-nan", samples = richSamples(7))
        val moved = StrokeEdit.reshape(r, grabX = 3f, grabY = 0f, dx = -5f, dy = 7f, radius = 10f)

        // The grab itself moved; a far sample within the large radius moved too.
        assertEquals(-2f, moved.samples[3].x, "grabbed sample takes the full dx")
        assertTrue(moved.samples[0].x != r.samples[0].x, "radius 10 reaches the whole short line")

        // Distinct NaN payloads and -0.0 survive the move bit-exact (copies, not arithmetic).
        assertSampleListSensorBits(r.samples, moved.samples, "reshape-nan")
        // Sample order is the time order: times are strictly increasing and unchanged.
        val times = moved.samples.map { it.timeMs }
        assertEquals(r.samples.map { it.timeMs }, times, "reshape keeps sample time/order")
        for (i in 1 until times.size) assertTrue(times[i] > times[i - 1], "times stay ordered at $i")
    }

    // ---- 3. reshape: boundary selections ------------------------------------------------------------

    @Test
    fun reshapeBoundarySelections() {
        // Single-sample stroke: the only finite sample is grabbed whatever the radius.
        val one = record(id = "jb25-one", samples = richSamples(1))
        val oneMoved = StrokeEdit.reshape(one, grabX = 100f, grabY = -50f, dx = 1f, dy = 1f, radius = 10f)
        assertEquals(1f, oneMoved.samples[0].x, "lone sample takes dx in full")
        assertEquals(1f, oneMoved.samples[0].y, "lone sample takes dy in full (0 + 1)")
        assertSensorBits(one.samples[0], oneMoved.samples[0], "lone sample sensors")

        // Tie for nearest: samples at x=0 and x=2, grab at x=1, nudge radius 0.
        // Strict `<` keeps the FIRST minimum, so index 0 moves and index 1 is untouched.
        val pair = record(
            id = "jb25-tie",
            samples = listOf(
                PenSample(x = 0f, y = 0f, timeMs = 1.0, pressure = 0.5f),
                PenSample(x = 2f, y = 0f, timeMs = 2.0, pressure = 0.9f),
            ),
        )
        val nudged = StrokeEdit.reshape(pair, grabX = 1f, grabY = 0f, dx = 5f, dy = 0f, radius = 0f)
        assertEquals(5f, nudged.samples[0].x, "tie goes to the lower index")
        assertEquals(pair.samples[1], nudged.samples[1], "the other tied sample is bit-identical")

        // Sample exactly at the radius stays; the one halfway takes w=(1-1/4)^2=9/16 of dx=4.
        val edge = record(
            id = "jb25-edge",
            samples = List(5) { i -> PenSample(x = i.toFloat(), y = 0f, timeMs = i.toDouble()) },
        )
        val edged = StrokeEdit.reshape(edge, grabX = 0f, grabY = 0f, dx = 4f, dy = 0f, radius = 2f)
        assertEquals(4f, edged.samples[0].x, "grab moves in full")
        assertEquals(3.25f, edged.samples[1].x, "half-radius weight 9/16 x 4 = 2.25, exact")
        assertEquals(edge.samples[2], edged.samples[2], "arc distance == radius does not move")
        assertEquals(edge.samples[3], edged.samples[3], "beyond radius does not move")
        assertEquals(edge.samples[4], edged.samples[4], "far end does not move")
    }

    // ---- 4. reshape: non-finite samples are never grabbed; NaN gaps add no arc ----------------------

    @Test
    fun reshapeSkipsNonFiniteSamplesAndTreatsNaNGapsAsZeroLength() {
        val nanTilt = Float.fromBits(0x7F800001)
        val r = record(
            id = "jb25-nan-gap",
            samples = listOf(
                PenSample(x = 0f, y = 0f, timeMs = 1000.0, pressure = 0.5f, tilt = 1f, tool = Tool.STYLUS),
                PenSample(x = Float.NaN, y = 0f, timeMs = 1008.0, pressure = 0.6f, tilt = nanTilt, tool = Tool.ERASER),
                PenSample(x = 2f, y = 0f, timeMs = 1016.0, pressure = 0.7f, tilt = 2f, tool = Tool.FINGER),
            ),
        )
        // Both NaN-adjacent segments contribute zero arc length (documented), so with grab at
        // sample 0 every finite sample sits at arc distance 0 and takes the full offset.
        val moved = StrokeEdit.reshape(r, grabX = 0f, grabY = 0f, dx = 4f, dy = 0f, radius = 1f)
        assertEquals(4f, moved.samples[0].x, "grab moves in full")
        assertEquals(6f, moved.samples[2].x, "past-the-gap sample is at arc 0, moves in full")
        assertTrue(moved.samples[1].x.isNaN(), "NaN stays NaN: there is no partly-NaN line")
        assertSensorBits(r.samples[1], moved.samples[1], "NaN sample sensors still copied verbatim")

        // And a NaN-position sample is never the nearest: tie at distance 5 goes to index 0.
        val far = record(
            id = "jb25-nan-skip",
            samples = listOf(
                PenSample(x = 0f, y = 0f, timeMs = 1.0),
                PenSample(x = Float.NaN, y = Float.NaN, timeMs = 2.0),
                PenSample(x = 10f, y = 0f, timeMs = 3.0),
            ),
        )
        val picked = StrokeEdit.reshape(far, grabX = 5f, grabY = 0f, dx = 1f, dy = 0f, radius = 0f)
        assertEquals(1f, picked.samples[0].x, "nearest finite sample (tie) is nudged")
        assertEquals(far.samples[2], picked.samples[2], "far sample untouched by the nudge")
        assertTrue(picked.samples[1].x.isNaN() && picked.samples[1].y.isNaN(), "NaN sample not grabbed")
    }

    // ---- 5. reshape no-ops ----------------------------------------------------------------------------

    @Test
    fun reshapeNoOpsReturnEqualRecords() {
        val r = record(samples = richSamples(8))

        // A zero drag rebuilds positions as x + 0*w == x: data-class equal (not asserted bit-exact,
        // since -0.0 + 0.0 is +0.0 by IEEE; the op intentionally touches x/y here).
        assertEquals(r, StrokeEdit.reshape(r, grabX = 4f, grabY = 0f, dx = 0f, dy = 0f, radius = 4f), "zero drag")

        // Non-finite drag/grab inputs return the record itself, untouched.
        for ((gx, gy, dx, dy) in listOf(
            listOf(Float.NaN, 0f, 1f, 0f),
            listOf(0f, Float.NaN, 1f, 0f),
            listOf(0f, 0f, Float.NaN, 0f),
            listOf(0f, 0f, 1f, Float.NaN),
            listOf(0f, 0f, Float.POSITIVE_INFINITY, 0f),
            listOf(Float.NEGATIVE_INFINITY, 0f, 1f, 0f),
        )) {
            assertSame(r, StrokeEdit.reshape(r, gx, gy, dx, dy, 4f), "non-finite input ($gx,$gy)+($dx,$dy)")
        }

        // An empty stroke has nothing to drag and returns itself, never an error.
        val empty = r.copy(id = "jb25-empty", samples = emptyList())
        assertSame(empty, StrokeEdit.reshape(empty, 0f, 0f, 5f, 5f, 10f), "empty stroke")
    }

    // ---- 6. reweight: exact selected changes, samples shared, metadata bit-exact ------------------------

    @Test
    fun reweightChangesOnlyWidthScaleWithExactValues() {
        val r = record(id = "jb25-weight", widthScale = 0.5f, samples = richSamples(6))

        val doubled = StrokeEdit.reweight(r, 4f)
        assertEquals(2f.toRawBits(), doubled.widthScale.toRawBits(), "0.5 x 4 is exactly 2")
        val back = StrokeEdit.reweight(doubled, 0.25f)
        assertEquals(0.5f.toRawBits(), back.widthScale.toRawBits(), "2 x 0.25 composes back to 0.5")

        assertEquals(20f, StrokeEdit.reweight(r, 40f).widthScale, "0.5 x 40 clamps to the widest")
        assertEquals(0.05f, StrokeEdit.reweight(r, 0.01f).widthScale, "0.5 x 0.01 clamps to the narrowest")
        assertEquals(0.05f, StrokeEdit.reweight(r, -2f).widthScale, "a negative factor is a very thin line")
        assertEquals(2f, StrokeEdit.reweight(r.copy(widthScale = Float.NaN), 2f).widthScale, "NaN weight reads as neutral 1")

        // Everything but the width is preserved; the sample list is shared, never rebuilt.
        for (e in listOf(doubled, back)) {
            assertSame(r.samples, e.samples, "reweight shares the sample list")
            assertSampleListSensorBits(r.samples, e.samples, "reweight")
            assertMetadataBits(r, e, "reweight")
            assertEquals(r.brushId, e.brushId, "reweight keeps brushId")
            assertEquals(r.colorArgb, e.colorArgb, "reweight keeps colorArgb")
        }

        // No-ops: factor 1 is data-class equal; non-finite factors return the record itself.
        assertEquals(r, StrokeEdit.reweight(r, 1f), "factor 1 changes nothing")
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertSame(r, StrokeEdit.reweight(r, bad), "factor $bad is not a factor")
        }
        // The bounds are the codec's bounds: one number, one place.
        assertEquals(0.05f, StrokeEdit.MIN_WIDTH_SCALE)
        assertEquals(20f, StrokeEdit.MAX_WIDTH_SCALE)
    }

    // ---- 7. rebrush: only the brush id changes ------------------------------------------------------------

    @Test
    fun rebrushChangesOnlyTheBrushId() {
        val r = record(id = "jb25-rebrush", brushId = "pencil", samples = richSamples(9))

        val filled = StrokeEdit.rebrush(r, ENGINE_FILL)
        assertEquals(ENGINE_FILL, filled.brushId, "rebrush names the new brush")
        assertMetadataBits(r, filled, "rebrush")
        assertSampleListSensorBits(r.samples, filled.samples, "rebrush")
        assertEquals(r.samples.map { it.x.toRawBits() }, filled.samples.map { it.x.toRawBits() }, "rebrush keeps x bits")
        assertEquals(r.samples.map { it.y.toRawBits() }, filled.samples.map { it.y.toRawBits() }, "rebrush keeps y bits")
        assertEquals(r.colorArgb, filled.colorArgb, "rebrush keeps colour")
        assertEquals(r.widthScale.toRawBits(), filled.widthScale.toRawBits(), "rebrush keeps width bits")

        // Rebrushing to the same brush is data-class equal; a round trip restores the record.
        assertEquals(r, StrokeEdit.rebrush(r, "pencil"), "same-brush rebrush changes nothing")
        assertEquals(r, StrokeEdit.rebrush(filled, "pencil"), "rebrush round trip restores the record")
    }

    // ---- 8. recolor: only the colour changes ------------------------------------------------------------------

    @Test
    fun recolorChangesOnlyTheColor() {
        val r = record(id = "jb25-recolor", colorArgb = 0xFF203040.toInt(), samples = richSamples(9))

        for (argb in listOf(0x00000000, 0xFF00FF00.toInt(), 0x00FFFFFF, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val dyed = StrokeEdit.recolor(r, argb)
            assertEquals(argb, dyed.colorArgb, "recolor sets colour $argb exactly")
            assertMetadataBits(r, dyed, "recolor $argb")
            assertEquals(r.brushId, dyed.brushId, "recolor keeps brushId")
            assertSampleListSensorBits(r.samples, dyed.samples, "recolor $argb")
            assertEquals(r.samples.map { it.x.toRawBits() }, dyed.samples.map { it.x.toRawBits() }, "recolor keeps x bits")
            assertEquals(r.widthScale.toRawBits(), dyed.widthScale.toRawBits(), "recolor keeps width bits")
        }

        assertEquals(r, StrokeEdit.recolor(r, 0xFF203040.toInt()), "same-colour recolor changes nothing")
    }

    // ---- 9. a composed edit chain keeps identity and raw sensors end to end --------------------------------------

    @Test
    fun composedEditsKeepIdentityAndRawSensorsEndToEnd() {
        val r = record(id = "jb25-chain", seed = 0xC0FFEE1234L, samples = richSamples(12))

        val e1 = StrokeEdit.reshape(r, grabX = 6f, grabY = 0f, dx = 2f, dy = 3f, radius = 4f)
        val e2 = StrokeEdit.reweight(e1, 2f)
        val e3 = StrokeEdit.rebrush(e2, "marker")
        val e4 = StrokeEdit.recolor(e3, 0xFFABCDEF.toInt())

        // Identity survives the whole chain: same line, edited.
        for ((tag, e) in listOf("reshape" to e1, "reweight" to e2, "rebrush" to e3, "recolor" to e4)) {
            assertEquals("jb25-chain", e.id, "$tag keeps the id")
            assertEquals(r.seed, e.seed, "$tag keeps the seed")
        }

        // Each op's selected change is present and only that op made it.
        assertEquals(8f, e4.samples[6].x, "reshape's grab offset survives the chain")
        assertEquals(2f.toRawBits(), e4.widthScale.toRawBits(), "reweight's product survives (1 x 2)")
        assertEquals("marker", e4.brushId, "rebrush's brush survives")
        assertEquals(0xFFABCDEF.toInt(), e4.colorArgb, "recolor's colour survives")

        // Raw sensors, times, tools and order survive every op end to end.
        assertSampleListSensorBits(r.samples, e4.samples, "chain")
        assertEquals(r.samples.map { it.timeMs }, e4.samples.map { it.timeMs }, "chain keeps time/order")
        assertEquals(r.smoothing.toRawBits(), e4.smoothing.toRawBits(), "chain keeps smoothing bits")
        assertEquals(r.screenPerDoc.toRawBits(), e4.screenPerDoc.toRawBits(), "chain keeps screenPerDoc bits")
    }
}
