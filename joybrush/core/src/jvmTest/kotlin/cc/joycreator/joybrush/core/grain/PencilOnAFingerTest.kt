package cc.joycreator.joybrush.core.grain

import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.paper.HexTile
import cc.joycreator.joybrush.core.paper.PaperTexture
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * THE case both JB-1.02 reviewers demanded (JB-1.05c Decision 1): the SHIPPED pencil, drawn by a FINGER
 * (tilt = NaN, azimuth = NaN), through the REAL grain texture the phone loads. Before the NaN guard this
 * poisoned every dab (0 * NaN = NaN) and the pencil drew nothing, while every pen test passed.
 */
class PencilOnAFingerTest {

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "brushes/pencil/brush.json").isFile || File(it, "joybrush/brushes/pencil/brush.json").isFile }
        .let { if (File(it, "brushes").isDirectory) it else File(it, "joybrush") }

    private val pencil = BrushJson.decode(File(root(), "brushes/pencil/brush.json").readText())
    private val surface: PaperTexture = run {
        val img = ImageIO.read(File(root(), "assets/paper/${GrainMath.DEFAULT_SURFACE}"))
        val bytes = ByteArray(img.width * img.height * 4)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val argb = img.getRGB(x, y); val i = (y * img.width + x) * 4
            bytes[i] = (argb ushr 16).toByte(); bytes[i + 1] = (argb ushr 8).toByte()
            bytes[i + 2] = argb.toByte(); bytes[i + 3] = (argb ushr 24).toByte()
        }
        PaperTexture(img.width, img.height, bytes)
    }
    private val heights = Array(64) { y -> FloatArray(64) { x ->
        val out = FloatArray(4)
        HexTile.sampleSurface(surface, x * 8.0, y * 8.0, GrainMath.SURFACE_HEX_TEXELS.toDouble(),
            GrainMath.SURFACE_ROTATABLE, GrainMath.SURFACE_SLOPE_RANGE, out)
        out[2]
    } }

    private fun coverageAt(u: GrainMath.GrainUniforms, nx: Float, ny: Float, tilt: Float, az: Float, tx: Int, ty: Int): Float {
        val level = GrainMath.grainLevel(
            u.depth, 1f, nx, ny, GrainMath.leanX(az), GrainMath.leanY(az),
            GrainMath.tiltAmount(tilt), u.tiltGradient, u.radial,
        )
        // JB-9.03b: heights are the real CPU hex reads used by the GPU, not repeating B texels.
        val h = heights[Math.floorMod(ty, heights.size)][Math.floorMod(tx, heights[0].size)]
        return GrainMath.grainedCoverage(1f, GrainMath.heightCoverage(h, level, u.edge))
    }

    @Test
    fun theShippedPencilStillDrawsUnderAFinger() {
        val u = GrainMath.paperUniformsFor(pencil.paperGrain, 0.9f) // pressure 1: curve top
        assertTrue(u.enabled, "the shipped pencil has its paper grain on")
        var anyPaint = false
        for (i in -2..2) for (j in -2..2) {
            val nx = i / 2f; val ny = j / 2f
            val level = GrainMath.grainLevel(u.depth, 1f, nx, ny, GrainMath.leanX(Float.NaN), GrainMath.leanY(Float.NaN),
                GrainMath.tiltAmount(Float.NaN), u.tiltGradient, u.radial)
            assertTrue(level.isFinite() && level in 0f..1f, "finger level at ($nx,$ny) = $level")
        }
        for (ty in heights.indices) for (tx in heights[0].indices) {
            val c = coverageAt(u, 0f, 0f, Float.NaN, Float.NaN, tx, ty)
            assertTrue(c.isFinite() && c in 0f..1f)
            if (c > 0f) anyPaint = true
        }
        assertTrue(anyPaint, "a finger pencil must still put paint down: the failure mode is every dab NaN")
    }

    @Test
    fun aRealPenLeaningOneWayDrawsDifferentlyOnTheLeanSideSoTheGrainIsNotSimplyOff() {
        val u = GrainMath.paperUniformsFor(pencil.paperGrain, 0.3f) // light pressure: the texture decides
        var lean = 0f; var away = 0f
        for (ty in heights.indices) for (tx in heights[0].indices) {
            lean += coverageAt(u, 0.6f, 0f, 0.5f, 0f, tx, ty)
            away += coverageAt(u, -0.6f, 0f, 0.5f, 0f, tx, ty)
        }
        assertTrue(lean > away, "the lean side takes more paint: lean=$lean away=$away")
        assertNotEquals(lean, away)
        assertEquals(0f, GrainMath.tiltAmount(Float.NaN))
    }
}
