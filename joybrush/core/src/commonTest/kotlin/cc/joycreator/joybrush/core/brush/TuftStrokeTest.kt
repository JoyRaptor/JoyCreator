package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.TuftMath
import cc.joycreator.joybrush.core.paint.TuftStamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tuft engine against the owner's rulings (R9 §3A, O1–O13). Each test names the ruling it holds the brush to. */
class TuftStrokeTest {

    private fun sable(size: Float = 18f, edit: (TuftSpec) -> TuftSpec = { it }) = BrushPreset(
        id = "t", name = "T", engine = ENGINE_TUFT, size = Param(size), tuft = edit(TuftSpec()),
    )

    /** A straight line along +x, one sample per px, [pxPerMs] fast, pressure from [pressure]. */
    private fun line(length: Int, pxPerMs: Float, y: (Int) -> Float = { 0f }, pressure: (Float) -> Float): List<PenSample> =
        (0..length).map { i -> PenSample(i.toFloat(), y(i), timeMs = i / pxPerMs.toDouble(), pressure = pressure(i / length.toFloat())) }

    private fun draw(preset: BrushPreset, samples: List<PenSample>, seed: Long = 7L): Pair<List<TuftStamp>, List<TuftStamp>> {
        val t = TuftStroke(preset, seed)
        return t.add(samples) to t.finish()
    }

    private fun footprints(s: List<TuftStamp>) = s.filter { it.kind == TuftStamp.KIND_FOOTPRINT }
    private fun plain(s: List<TuftStamp>) = s.filter { it.kind != TuftStamp.KIND_FOOTPRINT }
    private fun length(s: TuftStamp) = hypot(s.bx - s.ax, s.by - s.ay)

    // ── replay ──

    @Test
    fun theSameStrokeGivesTheSameFootprintsHoweverItIsBatched() {
        val samples = line(400, 1.5f, y = { i -> 20f * sin(i / 40f) }) { f -> 0.2f + 0.8f * sin(PI.toFloat() * f) }
        val p = sable { it.copy(spatter = 1f, strays = 1f) }
        val whole = TuftStroke(p, 99L).let { it.add(samples) + it.finish() }
        val parts = TuftStroke(p, 99L).let { t ->
            val out = ArrayList<TuftStamp>()
            var i = 0
            var n = 1
            while (i < samples.size) {
                val end = minOf(samples.size, i + n)
                out += t.add(samples.subList(i, end))
                i = end
                n = n % 7 + 1
            }
            out + t.finish()
        }
        assertEquals(whole, parts)
    }

    // ── R9 §3.1: the hairline shelf ──

    // ── owner, 2026-09-30: three zones — detail, line weight, pressed flat for shadows — and tilt multiplies ──

    @Test
    fun pressingFlatSpreadsWideForShadowsAndTiltMultipliesIt() {
        fun widthAt(p: Float, tilt: Float = Float.NaN, edit: (TuftSpec) -> TuftSpec = { it }) =
            footprints(draw(sable(edit = edit), (0..60).map { i -> PenSample(i.toFloat(), 0f, i / 0.3, p, tilt = tilt, azimuth = -2.3f) }).first).last().ra
        val line = widthAt(0.82f)
        val flat = widthAt(1f)
        assertTrue(flat > 2.2f * line, "pressed flat ($flat) is far wider than line weight ($line)")
        assertTrue(widthAt(1f, edit = { it.copy(flatten = 0f) }) < 1.3f * line, "Press flat at 0 turns it off")
        // A steep pen is a multiplier on the diagonal: the footprint grows long along the lean.
        fun reach(tilt: Float): Float {
            val f = footprints(draw(sable(), (0..60).map { i -> PenSample(i.toFloat(), 0f, i / 0.3, 0.7f, tilt = tilt, azimuth = -2.3f) }).first).last()
            return hypot(f.bx - f.ax, f.by - f.ay) + f.ra + f.rb
        }
        assertTrue(reach(1.2f) > 1.5f * reach(0f), "a steep pen multiplies the mark: ${reach(1.2f)} vs ${reach(0f)}")
    }

    // ── owner, 2026-09-30, round three: the tilted brush ──

    /** A bowl drawn AGAINST the lean, as his 3s were: the footprint must never jump sideways (the brush "flipping"). */
    @Test
    fun aCurveAgainstTheLeanNeverFlipsTheBrush() {
        val lean = (-PI * 0.25).toFloat()
        val samples = (0..700).map { i ->
            val a = i / 110.0
            PenSample((150 * cos(a)).toFloat(), (150 * sin(a)).toFloat(), timeMs = i / 1.2, pressure = 0.7f, tilt = 0.8f, azimuth = lean)
        }
        val fp = footprints(draw(sable(), samples).first)
        for ((a, b) in fp.zipWithNext()) {
            val jumpA = hypot(b.ax - a.ax, b.ay - a.ay)
            val jumpB = hypot(b.bx - a.bx, b.by - a.by)
            assertTrue(jumpA < 3f && jumpB < 6f, "a footprint jumped: belly $jumpA, tip $jumpB at (${b.ax}, ${b.ay})")
        }
    }

    /** The point stays at the pen and the body lies out along the lean, longer the steeper — oblong even when light. */
    @Test
    fun aTiltedBrushLiesOutAlongTheLeanEvenWhenLight() {
        val lean = 0f
        fun last(p: Float, tilt: Float) =
            footprints(draw(sable(), (0..80).map { i -> PenSample(0f, i.toFloat(), i / 0.3, p, tilt = tilt, azimuth = lean) }).first).last()
        val light = last(0.12f, 1.1f)
        assertTrue(light.ax - 0f > 8f, "the belly lies out along the lean (+x): ${light.ax}")
        assertTrue(light.ra > 3f, "a light touch on its side is still almost as big: ${light.ra}")
        assertTrue(light.graze > 0.3f, "and it grazes: ${light.graze}")
        val hard = last(0.95f, 1.1f)
        assertTrue(hard.graze < 0.05f, "pressed hard it is black: ${hard.graze}")
        val upright = last(0.12f, 0f)
        assertTrue(abs(upright.ax) < 0.5f && upright.ra < 1.5f, "upright and light stays a hairline at the pen")
        val steeper = last(0.5f, 1.3f)
        val shallower = last(0.5f, 0.6f)
        assertTrue(steeper.ax > shallower.ax, "steeper lies out further: ${steeper.ax} vs ${shallower.ax}")
    }

    /** Pivoting the pen round a fixed point sweeps the belly round it; walking the point round a circle does not. */
    @Test
    fun turningThePenRoundItsPointSweepsTheBelly() {
        val t = TuftStroke(sable(), 3L)
        val out = ArrayList<TuftStamp>()
        out += t.add(listOf(PenSample(0f, 0f, 0.0, 0.4f, tilt = 1f, azimuth = 0f)))
        for (i in 1..90) out += t.dwell(PenSample(0f, 0f, i * 4.0, 0.4f, tilt = 1f, azimuth = (i * 4 * PI / 180).toFloat()))
        val bellies = footprints(out)
        assertTrue(bellies.size > 20, "turning in place lays footprints: ${bellies.size}")
        val angles = bellies.map { kotlin.math.atan2(it.ay, it.ax) }
        assertTrue(angles.maxOrNull()!! - angles.minOrNull()!! > 2.5, "the belly swings round the point")
        assertTrue(bellies.all { abs(it.bx) < 1f && abs(it.by) < 1f }, "the point never leaves the pen")
    }

    @Test
    fun aMashedBrushMayLiftSplit() {
        var split = 0
        for (seed in 1L..40L) {
            val tail = footprints(draw(sable { it.copy(splay = 1f) }, line(300, 1.5f) { 1f }, seed).second)
            // Prongs run side by side: two footprints at the same distance along, apart across the stroke.
            val ys = tail.map { it.ay }.distinctBy { (it * 2).toInt() }
            if (tail.isNotEmpty() && ys.size > 3 && (tail.maxOf { it.ay } - tail.minOf { it.ay }) > 3f) split++
        }
        assertTrue(split in 3..39, "some mashed lifts split, not all: $split of 40")
        var never = 0
        for (seed in 1L..20L) {
            val tail = footprints(draw(sable { it.copy(splay = 0f) }, line(300, 1.5f) { 1f }, seed).second)
            if (tail.isNotEmpty() && tail.maxOf { it.ay } - tail.minOf { it.ay } > 3f) never++
        }
        assertEquals(0, never, "Splay at 0: a single point")
    }

    /** Dryness creeps in early, grows gently, and never reaches nothing: an empty brush still dry-brushes. */
    @Test
    fun theBrushDriesGraduallyAndNeverRunsToNothing() {
        val fp = footprints(draw(sable { it.copy(ink = 0f, dry = 0f, settle = 0f, corner = 0f) }, line(20000, 1f) { 0.8f }).first)
        val quarters = fp.chunked(fp.size / 4).map { c -> c.map { it.dry }.average() }
        assertTrue(quarters.zipWithNext().all { (a, b) -> b >= a - 1e-3 }, "drier as it goes: $quarters")
        assertTrue(quarters[0] > 0.02, "a little dryness early: $quarters")
        assertTrue(fp.last().dry < 0.8f, "never to nothing: ${fp.last().dry}")
    }

    @Test
    fun theResponseCurvesShapeWhatTheBrushHears() {
        val straight = ResponseSpec()
        val s = PenSample(0f, 0f, 0.0, 0.5f, tilt = 0.7f)
        assertEquals(s, straight.apply(s))
        val soft = ResponseSpec(pressure = listOf(0f, 0.6f, 0.4f, 1f))
        assertTrue(soft.apply(s).pressure > 0.6f, "a curve above the line makes a light touch heavier")
        val hard = ResponseSpec(tilt = listOf(0.6f, 0f, 1f, 0.4f))
        assertTrue(hard.apply(s).tilt < 0.7f)
        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) assertEquals(x, ResponseCurve.eval(ResponseCurve.LINEAR, x), 1e-3f)
        val finger = s.copy(tool = cc.joycreator.joybrush.core.input.Tool.FINGER, pressure = 1f)
        assertEquals(1f, soft.apply(finger).pressure)
        val p = BrushPreset(id = "b", name = "B", size = Param(4f), version = 1, response = soft)
        assertTrue(BrushValidate.validate(p).any { it.contains("needs brush version 5") })
        assertTrue(BrushJson.encode(p).contains("\"version\": 5"))
    }

    @Test
    fun aFingerDrawsLineWeightNotAShadow() {
        val finger = (0..60).map { i -> PenSample(i.toFloat(), 0f, i / 0.3, 1f, tool = cc.joycreator.joybrush.core.input.Tool.FINGER) }
        assertTrue(footprints(draw(sable(), finger).first).last().ra < 9.5f)
    }

    @Test
    fun theFirstPartOfThePressureStaysAHairlineAndTheBellyOpensAfter() {
        fun widthAt(p: Float) = footprints(draw(sable(), line(60, 0.3f) { p }).first).last().ra
        val tip = 0.5f
        assertTrue(widthAt(0.3f) < 0.2f * 9f, "30% pressure should still be thin, was ${widthAt(0.3f)}")
        assertTrue(widthAt(1f) > 0.85f * 9f, "full pressure should open the belly, was ${widthAt(1f)}")
        assertTrue(widthAt(0.02f) < tip * 1.6f, "the lightest touch is the needle point, was ${widthAt(0.02f)}")
    }

    // ── O5: very light work is thin in every direction ──

    @Test
    fun theLightestTouchIsRoundWithNoTrailingBristles() {
        val fast = footprints(draw(sable(), line(200, 2f) { 0.02f }).first)
        assertTrue(fast.all { length(it) < 0.2f }, "a feather-light footprint has no length, max ${fast.maxOf { length(it) }}")
    }

    // ── O8: a little pressure at speed is thin but long ──

    @Test
    fun speedLengthensTheContactWithoutFatteningTheLine() {
        val slow = footprints(draw(sable(), line(300, 0.1f) { 0.18f }).first).drop(100)
        val fast = footprints(draw(sable(), line(300, 2.5f) { 0.18f }).first).drop(100)
        val slowLen = slow.map { length(it) }.average()
        val fastLen = fast.map { length(it) }.average()
        assertTrue(fastLen > 1.8 * slowLen, "fast contact $fastLen should be much longer than slow $slowLen")
        val fastW = fast.map { it.ra }.average()
        assertTrue(fastW < 0.2 * 9, "and still thin: $fastW")
    }

    // ── O7: the brush steadies itself across the stroke, never along it ──

    @Test
    fun jitterAcrossTheStrokeBecomesACalmDriftAndTheLineNeverLags() {
        val jitter = { i: Int -> if (i % 2 == 0) 1.5f else -1.5f }
        val samples = line(300, 0.8f, y = jitter) { 0.7f }
        fun spread(p: BrushPreset): Double {
            val ys = footprints(draw(p, samples).first).drop(40).map { it.ay.toDouble() }
            val mean = ys.average()
            return sqrt(ys.sumOf { (it - mean) * (it - mean) } / ys.size)
        }
        val steadied = spread(sable())
        val raw = spread(sable { it.copy(steady = 0f) })
        assertTrue(steadied < 0.35 * raw, "steadied spread $steadied should be well under the raw $raw")
        val last = footprints(draw(sable(), samples).first).last()
        assertTrue(abs(last.ax - 300f) < 1.5f, "the mark keeps up with the pen along the stroke: ${last.ax}")
    }

    // ── O9: a sharp turn leaves a thicker spot ──

    @Test
    fun aSwitchbackLeavesAThickerSpotWhileTheBristlesResettle() {
        val out = ArrayList<PenSample>()
        var t = 0.0
        for (i in 0..150) { out += PenSample(i.toFloat(), 0f, t, 0.6f); t += 1.0 }
        for (i in 1..150) { out += PenSample(150f - i, 4f, t, 0.6f); t += 1.0 }
        val fp = footprints(draw(sable(), out).first)
        val straight = fp.filter { it.ax in 40f..110f && it.ay < 1f }.map { it.ra }.average()
        val atTurn = fp.filter { it.ax > 130f && it.ay > 1f }.maxOf { it.ra }
        assertTrue(atTurn > 1.1 * straight, "the turn ($atTurn) should be wider than the straight ($straight)")
        val none = footprints(draw(sable { it.copy(corner = 0f) }, out).first)
        val atTurnNone = none.filter { it.ax > 130f && it.ay > 1f }.maxOf { it.ra }
        assertTrue(atTurn > atTurnNone, "the Turn blot slider drives it: $atTurn vs $atTurnNone")
    }

    // ── O10: fast curves are solid inside and dry outside ──

    @Test
    fun aFastSweepPutsTheInkOnTheInsideOfTheCurve() {
        val r = 120f
        val cx = 0f
        val cy = 0f
        val samples = (0..600).map { i ->
            val a = i / r
            PenSample(cx + r * cos(a), cy + r * sin(a), timeMs = i / 4.0, pressure = 0.8f)
        }
        val fp = footprints(draw(sable { it.copy(sweep = 1f) }, samples).first).drop(80)
        val biased = fp.filter { abs(it.bias) > 0.05f }
        assertTrue(biased.size > fp.size / 2, "most of a fast circle should be biased, ${biased.size}/${fp.size}")
        for (s in biased) {
            val dx = s.bx - s.ax
            val dy = s.by - s.ay
            val n = hypot(dx, dy)
            val nx = -dy / n
            val ny = dx / n
            val towardCentre = (cx - s.ax) * nx + (cy - s.ay) * ny
            assertTrue(towardCentre * s.bias > 0f, "ink goes to the INSIDE: bias ${s.bias} at (${s.ax}, ${s.ay})")
        }
        val calm = footprints(draw(sable { it.copy(sweep = 0f) }, samples).first)
        assertTrue(calm.all { it.bias == 0f }, "Sweep at 0 turns it off")
    }

    // ── O3: long heavy strokes run dry, and a fuller brush runs dry later ──

    @Test
    fun longHeavyStrokesRunDryAndInkHoldsOut() {
        val samples = line(4000, 1.2f) { 0.8f }
        fun dryAtEnd(ink: Float) = footprints(draw(sable { it.copy(ink = ink, dry = 0f, settle = 0f) }, samples).first)
            .takeLast(50).map { it.dry }.average()
        val thin = dryAtEnd(0.1f)
        val full = dryAtEnd(1f)
        assertTrue(thin > 0.5, "a small load is dry by the end: $thin")
        assertTrue(full < thin, "a big load lasts longer: $full vs $thin")
        val start = footprints(draw(sable { it.copy(ink = 0.1f, dry = 0f) }, samples).first).take(20).maxOf { it.dry }
        assertTrue(start < 0.05f, "every stroke starts loaded: $start")
    }

    // ── O6: slow lines settle solid ──

    @Test
    fun slowLinesAreThickerAndMoreSolidThanFastOnes() {
        val p = sable { it.copy(dry = 1f, ink = 0.05f) }
        val slow = footprints(draw(p, line(1500, 0.05f) { 0.25f }).first).takeLast(200)
        val fast = footprints(draw(p, line(1500, 3f) { 0.25f }).first).takeLast(200)
        assertTrue(slow.map { it.ra }.average() > fast.map { it.ra }.average(), "slow is a little thicker")
        assertTrue(slow.map { it.dry }.average() < fast.map { it.dry }.average(), "slow is more solid")
    }

    // ── O1: spatter on jolts, not on calm strokes ──

    @Test
    fun spatterComesFromJoltsNotFromCalmStrokes() {
        val calm = line(400, 0.5f) { 0.6f }
        assertTrue(plain(draw(sable { it.copy(spatter = 1f, strays = 0f) }, calm).let { it.first + it.second }).isEmpty(),
            "a calm stroke throws nothing")
        // A sudden press, over and over, at speed.
        val jolty = line(600, 2f) { f -> if ((f * 20).toInt() % 2 == 0) 0.1f else 1f }
        var drops = 0
        for (seed in 1L..10L) drops += plain(draw(sable { it.copy(spatter = 1f, strays = 0f) }, jolty, seed).let { it.first + it.second }).size
        assertTrue(drops > 5, "jolts throw ink: $drops drops over ten strokes")
        var none = 0
        for (seed in 1L..10L) none += plain(draw(sable { it.copy(spatter = 0f, strays = 0f) }, jolty, seed).let { it.first + it.second }).size
        assertEquals(0, none, "Spatter at 0 turns it off")
    }

    // ── O4: stray hairs only with the belly down ──

    @Test
    fun strayHairsShowOnlyWhenPaintingWithTheBelly() {
        var light = 0
        var heavy = 0
        for (seed in 1L..20L) {
            light += plain(draw(sable { it.copy(strays = 1f, spatter = 0f) }, line(800, 0.6f) { 0.15f }, seed).first).size
            heavy += plain(draw(sable { it.copy(strays = 1f, spatter = 0f) }, line(800, 0.6f) { 0.95f }, seed).first).size
        }
        assertEquals(0, light, "no hairs on light work")
        assertTrue(heavy > 20, "belly strokes catch a hair: $heavy")
        var stutter = 0
        for (seed in 1L..20L) stutter += plain(draw(sable { it.copy(strays = 1f, spatter = 0f) }, line(800, 0.6f) { 0.95f }, seed).first).count { it.dry > 0f }
        assertTrue(stutter > 0, "some hairs stutter on the tooth")
        val hair = plain(draw(sable { it.copy(strays = 1f, spatter = 0f) }, line(800, 0.6f) { 0.95f }, 3L).first)
        assertTrue(hair.all { it.ra < 1f }, "a hair is a hairline")
    }

    // ── O11: a slow lift is a needle, a quick one keeps going, rougher when the bristles are spread ──

    @Test
    fun aFastLiftCarriesOnAndASpreadBrushEndsRough() {
        val slowEnd = draw(sable(), line(200, 0.05f) { 0.8f }).second
        assertTrue(footprints(slowEnd).isEmpty(), "a slow lift adds no tail")
        val fast = line(300, 3f) { 0.8f }
        val tail = footprints(draw(sable(), fast).second)
        assertTrue(tail.size > 3, "a fast lift carries on: ${tail.size} footprints")
        val closed = footprints(draw(sable { it.copy(splay = 0f) }, fast).second).last().ra
        val spread = footprints(draw(sable { it.copy(splay = 1f, flatten = 0f) }, line(300, 3f) { f -> if (f > 0.95f) 1f else 0.4f }).second).last().ra
        assertTrue(spread > closed, "spread bristles end wider ($spread) than closed ones ($closed)")
    }

    // ── the shape ──

    @Test
    fun theTeardropIsInsideWhereItShouldBe() {
        val s = TuftStamp(ax = 0f, ay = 0f, bx = 20f, by = 0f, ra = 5f, rb = 1f)
        assertTrue(TuftMath.distance(0f, 0f, s) < -4.9f)
        assertTrue(TuftMath.distance(20f, 0f, s) < -0.9f)
        assertTrue(TuftMath.distance(10f, 0f, s) < 0f)
        assertTrue(TuftMath.distance(10f, 6f, s) > 0f, "the waist is narrower than the belly")
        assertTrue(TuftMath.distance(-6f, 0f, s) > 0f)
        assertEquals(1f, TuftMath.coverage(0f, 0f, s))
        assertEquals(0f, TuftMath.coverage(30f, 0f, s))
        // One circle inside the other: the bigger one, not NaN.
        val round = TuftStamp(ax = 0f, ay = 0f, bx = 0.05f, by = 0f, ra = 5f, rb = 1f)
        assertTrue(abs(TuftMath.distance(0f, 5f, round)) < 0.1f)
        val bb = TuftMath.bounds(s)
        assertTrue(bb[0] <= -5f && bb[2] >= 21f && bb[1] <= -5f && bb[3] >= 5f)
    }

    // ── the file ──

    @Test
    fun aTuftBrushIsAVersionFourWordAndItsSlidersAreRanged() {
        val p = sable().copy(version = 3)
        assertTrue(BrushValidate.validate(p).any { it.contains("engine \"tuft\" needs brush version 4") })
        assertTrue(BrushValidate.validate(sable()).isEmpty(), BrushValidate.validate(sable()).toString())
        val bad = sable { it.copy(dry = 2f, tipPx = 0f) }
        val msg = BrushValidate.validate(bad).joinToString()
        assertTrue(msg.contains("tuft.dry 2.0") && msg.contains("tuft.tipPx"), msg)
        val text = BrushJson.encode(sable().copy(version = 4))
        assertTrue(text.contains("\"version\": 4") && text.contains("\"tuft\""), text)
        assertEquals(sable().copy(version = 4), BrushJson.decode(text))
    }
}
