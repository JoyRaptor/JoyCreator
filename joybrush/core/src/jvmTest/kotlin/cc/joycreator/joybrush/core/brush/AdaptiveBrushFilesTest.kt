package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.TuftMath
import cc.joycreator.joybrush.core.paint.TuftStamp
import cc.joycreator.joybrush.core.paint.tipShape
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Acceptance of shipped files through production geometry; these tests make no visual-quality claim. */
class AdaptiveBrushFilesTest {
    private val halfTilt = (PI / 4).toFloat()
    private fun preset(folder: String): BrushPreset {
        val p = BrushJson.decode(File(joybrushRoot(), "brushes/$folder/brush.json").readText())
        assertEquals(emptyList(), BrushValidate.validate(p), folder)
        return p
    }
    private fun sample(p: Float, tilt: Float, az: Float = 0f, x: Float = 0f, t: Double = 0.0) =
        PenSample(x, 0f, pressure = p, tilt = tilt, azimuth = az, timeMs = t)
    private fun dab(preset: BrushPreset, p: Float, tilt: Float, az: Float = 0f): Dab {
        val brush = BrushDabber(preset, 17L)
        return DabPlacer(brush.spacing, brush::look).add(listOf(sample(p, tilt, az))).single()
    }
    private fun shape(p: BrushPreset) = TipShape(p.tip.aspect, p.tip.corner, p.tip.taper, p.tip.hardness.base, p.tip.minPx)
    private fun coverage(d: Dab, p: BrushPreset, localX: Float, localY: Float): Float =
        TipMath.coverage(cos(d.angle) * localX - sin(d.angle) * localY,
            sin(d.angle) * localX + cos(d.angle) * localY, d.radius, d.angle, d.tipShape(shape(p)))

    @Test fun allShelfFilesValidateAndCreativeInstrumentsDoNotMultiplyIntoSizeVariants() {
        val entries = File(joybrushRoot(), "brushes/index.txt").readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val files = entries.map { entry ->
            // The index uses folder names; tolerate an explicit brush.json suffix without inventing presets.
            val folder = entry.removeSuffix("/brush.json")
            preset(folder)
        }
        assertEquals(files.size, files.map { it.id }.toSet().size, "duplicate shelf identity")
        for (folder in listOf("pencil", "sable", "bristle", "flatpaint")) {
            val p = preset(folder)
            assertEquals(1, files.count { it.id == p.id }, "$folder must be on the shelf once")
            assertTrue(files.none { it.id.startsWith(p.id + ".small") || it.id.startsWith(p.id + ".medium") ||
                it.id.startsWith(p.id + ".large") }, "$folder contact replaces size variants")
        }
    }

    @Test fun pencilKeepsAnUprightPointButExposesALongSideAtFortyFiveDegrees() {
        val p = preset("pencil")
        assertTrue(p.version >= VERSION_CONTACT)
        val point = dab(p, 0.25f, 0f)
        val side = dab(p, 0.25f, halfTilt)
        assertTrue(point.radius * 2f <= 5f, "upright detail diameter ${point.radius * 2}")
        assertTrue(side.radius * 2f >= 45f, "45 degree side diameter ${side.radius * 2}")
        assertTrue(side.aspect < -0.7f, "a pencil side must stay narrow across its long axis")
        assertTrue(side.anchor >= 0.8f, "side contact must grow away from the point")
        val centre = -side.radius * side.anchor
        assertTrue(coverage(side, p, centre + side.radius * 0.5f, 0f) > 0.5f)
        assertTrue(coverage(side, p, centre, side.radius * 0.5f) < 0.01f,
            "actual transverse coverage must remain narrow, not become a centred round blob")
        assertTrue(coverage(side, p, side.radius * 0.3f, 0f) < 0.01f,
            "contact must not grow equally beyond the pencil point")
    }

    @Test fun increasingPressureInOnePencilStrokeChangesTheLiveDepositNotOnlyTheInitialUniform() {
        val p = preset("pencil")
        val b = BrushDabber(p, 17L)
        val soft = b.look(sample(0.08f, halfTilt), 0f, 0)
        val firm = b.look(sample(0.9f, halfTilt, x = 20f, t = 20.0), 20f, 1)
        assertTrue(firm.radius > soft.radius)
        assertTrue(firm.cap > soft.cap)
        assertTrue(firm.paperDepth > soft.paperDepth, "paper engagement must vary inside a stroke")
        assertTrue(firm.hardness > soft.hardness, "pressure must firm the near edge")
        assertTrue(firm.radius * 2f <= 110f, "a leaned contact remains bounded")
    }

    @Test fun pencilSideHasANearToFarToothGradientUsingItsLiveGrainDepth() {
        val p = preset("pencil")
        val d = dab(p, 0.25f, halfTilt)
        val amount = GrainMath.tiltAmount(d.tilt)
        // Sample interior points on the long contact. Lean and local coordinates share a frame here.
        fun level(x: Float) = GrainMath.grainLevel(d.paperDepth, 1f, x, 0f, 1f, 0f,
            amount, p.paperGrain.tiltGradient, p.paperGrain.radial)
        val near = level(-d.anchor + 0.55f)
        val far = level(-d.anchor - 0.55f)
        assertTrue(near > far, "near=$near far=$far")
        // Integrate a uniform distribution of tooth heights: stricter engagement must deposit less.
        fun deposit(l: Float) = (0..100).sumOf {
            GrainMath.heightCoverage(it / 100f, l, p.paperGrain.edge).toDouble()
        }
        assertTrue(deposit(near) > deposit(far))
    }

    @Test fun absentTiltGivesFinitePencilDetailRatherThanAShadingBlob() {
        val p = preset("pencil")
        val d = dab(p, 0.25f, Float.NaN, Float.NaN)
        assertTrue(listOf(d.radius, d.angle, d.flow, d.cap, d.aspect, d.anchor, d.hardness, d.paperDepth).all { it.isFinite() })
        assertTrue(d.radius * 2f <= 5f)
        assertTrue(coverage(d, p, 0f, 0f).isFinite())
    }

    @Test fun flatPaintChangesItsContactThroughPressureAndTiltAndReallyEnablesPickup() {
        val p = preset("flatpaint")
        assertEquals(ENGINE_SMUDGE, p.engine)
        assertTrue(p.smudge.pickup > 0f)
        val point = dab(p, 0.08f, 0f)
        val middle = dab(p, 0.55f, 0f)
        val belly = dab(p, 0.9f, halfTilt)
        assertTrue(middle.radius > point.radius)
        assertTrue(belly.radius > middle.radius)
        assertTrue(belly.tipShape(shape(p)).aspect < -0.4f)
        assertTrue(p.paper.wet > 0f, "wet contact uses the document paper response")
    }

    @Test fun sableAndDryBristleExposeDistinctTipAndTiltedBellyThroughRealTuftFootprints() {
        for (folder in listOf("sable", "bristle")) {
            val p = preset(folder)
            assertEquals(ENGINE_TUFT, p.engine)
            fun footprints(pressure: Float, tilt: Float): List<TuftStamp> = TuftStroke(p, 17L).add(
                (0..100).map { sample(pressure, tilt, x = it.toFloat(), t = it * 2.0) }
            ).filter { it.kind == TuftStamp.KIND_FOOTPRINT }
            val light = footprints(0.03f, 0f)
            val heavy = footprints(0.9f, halfTilt)
            assertTrue(light.isNotEmpty() && heavy.isNotEmpty(), folder)
            val tipWidth = light.maxOf { it.ra * 2f }
            val bellyWidth = heavy.maxOf { it.ra * 2f }
            assertTrue(bellyWidth > tipWidth * 2f, "$folder: tip=$tipWidth belly=$bellyWidth")
            assertTrue(heavy.all { s -> TuftMath.bounds(s).all { it.isFinite() } }, folder)
            assertTrue(heavy.any { it.ra > it.rb }, "$folder must retain a fine point beside its belly")
        }
        val dry = preset("bristle")
        assertTrue(dry.tuft.dry > preset("sable").tuft.dry, "dry bristle has its own material character")
        assertTrue(dry.paper.influence > 0f)
    }
}
