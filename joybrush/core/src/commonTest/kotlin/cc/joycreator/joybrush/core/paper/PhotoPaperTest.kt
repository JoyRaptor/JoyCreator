package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The photo-paper engine (2026-10-06): contrast-keeping blend, fibre directions that stay true, the new catalogue fields. */
class PhotoPaperTest {

    /** A noise texture: independent values per texel, so patches at different offsets are uncorrelated (like real paper). */
    private fun noise(size: Int, seed: Long): PaperTexture {
        var x = seed
        val rgba = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            x = x * 6364136223846793005L + 1442695040888963407L
            val v = ((x ushr 33) and 255).toByte()
            rgba[i * 4] = v; rgba[i * 4 + 1] = v; rgba[i * 4 + 2] = v; rgba[i * 4 + 3] = -1
        }
        return PaperTexture(size, size, rgba)
    }

    private fun std(v: DoubleArray): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    @Test
    fun oneReadIsUntouchedAndAnEvenBlendOfThreeIsScaledByRootThree() {
        assertEquals(1f, HexTile.contrastKeep(floatArrayOf(1f, 0f, 0f)), 1e-6f)
        assertEquals(sqrt(3f), HexTile.contrastKeep(floatArrayOf(1f / 3, 1f / 3, 1f / 3)), 1e-5f)
    }

    /** The point of the change: where hexes blend, the paper keeps the contrast of one patch instead of washing out. */
    @Test
    fun theBlendKeepsThePapersContrastWhereHexesMeet() {
        val tex = noise(64, 7L)
        val mean = floatArrayOf(127.5f / 255, 127.5f / 255, 127.5f / 255)
        val out = FloatArray(3)
        val kept = ArrayList<Double>()
        val plain = ArrayList<Double>()
        val single = ArrayList<Double>()
        // The seams (no one read dominates) against the hex middles (one read): the old blend was flatter at the seams.
        for (k in 0 until 20000) {
            val px = (k * 7.31) % 400.0
            val py = (k * 3.97) % 400.0
            val g = HexTile.gammaWeights(HexTile.lattice(px, py, 24.0).w)
            val top = g.maxOrNull()!!
            if (top > 0.98f) {
                HexTile.sampleLook(tex, px, py, 24.0, true, out)
                single += out[0].toDouble()
            } else if (top < 0.6f) {
                HexTile.sampleLook(tex, px, py, 24.0, true, out, mean = mean)
                kept += out[0].toDouble()
                HexTile.sampleLook(tex, px, py, 24.0, true, out)
                plain += out[0].toDouble()
            }
        }
        // One read of the texture (bilinear, turned) is the paper's own contrast at this scale.
        val texStd = std(single.toDoubleArray())
        val keptStd = std(kept.toDoubleArray())
        val plainStd = std(plain.toDoubleArray())
        assertTrue(plainStd < 0.85 * texStd, "the old blend washes out at seams: $plainStd vs $texStd")
        assertTrue(abs(keptStd - texStd) < 0.15 * texStd, "the new blend keeps the paper's contrast: $keptStd vs $texStd")
    }

    /**
     * Fibres turn with the paper exactly as its slopes do: a rotated hex shows its patch turned, and the fibre direction
     * jb_paperFluid/sampleFluid returns must be that same turn (in the double-angle form, twice it) — or water would wick
     * across the very fibres the tooth runs along.
     */
    @Test
    fun fibresTurnWithThePaperExactlyAsItsSlopesDo() {
        val theta = 0.6
        val size = 32
        val fluid = ByteArray(size * size * 4)
        val surface = ByteArray(size * size * 4)
        val range = 0.5f
        for (i in 0 until size * size) {
            fluid[i * 4] = 128.toByte()
            fluid[i * 4 + 1] = Math.round(127.5 + 127.5 * cos(2 * theta)).toInt().toByte()
            fluid[i * 4 + 2] = Math.round(127.5 + 127.5 * sin(2 * theta)).toInt().toByte()
            fluid[i * 4 + 3] = 128.toByte()
            // A slope pointing along the same direction θ, half the range.
            surface[i * 4] = SurfaceMaps.encodeSlope((0.25 * cos(theta)).toFloat(), range).toByte()
            surface[i * 4 + 1] = SurfaceMaps.encodeSlope((0.25 * sin(theta)).toFloat(), range).toByte()
            surface[i * 4 + 2] = 128.toByte(); surface[i * 4 + 3] = 64.toByte()
        }
        val ft = PaperTexture(size, size, fluid)
        val st = PaperTexture(size, size, surface)
        val f = FloatArray(4); val sl = FloatArray(4)
        var checked = 0
        for (k in 0 until 4000) {
            val px = k * 13.7; val py = k * 5.3
            if (HexTile.gammaWeights(HexTile.lattice(px, py, 16.0).w).maxOrNull()!! < 0.999f) continue   // one hex: one rotation
            HexTile.sampleFluid(ft, px, py, 16.0, true, f)
            HexTile.sampleSurface(st, px, py, 16.0, true, range, sl)
            val fibre = atan2(f[2].toDouble(), f[1].toDouble()) / 2
            val slope = atan2(sl[1].toDouble(), sl[0].toDouble())
            val diff = abs(((fibre - slope) + PI / 2).mod(PI) - PI / 2)
            assertTrue(diff < 0.05, "at ($px, $py) the fibres read $fibre but the slopes $slope")
            assertTrue(hypot(f[1].toDouble(), f[2].toDouble()) > 0.95)
            assertEquals(0.5f, f[0], 0.01f)
            checked++
        }
        assertTrue(checked > 10, "only $checked one-hex points")
    }

    @Test
    fun theCatalogueChecksThePhysicalNumbersAndTheFluidMap() {
        val ok = SurfaceEntry("s", "S", "height_s.png", 64, 1f, 0.4f, 32f, true, packed = false, fluid = "fluid_s.png")
        val look = LookEntry("l", "L", "#808080", "look_l.jpg", "#808080", 2.5f, 32f, true, "s")
        assertEquals(emptyList(), PaperCatalogues.problems(PaperCatalogue(surfaces = listOf(ok), looks = listOf(look))))
        val bad = ok.copy(sizing = 2f, toothDepthMm = -1f, fluid = "a/b.png", fluidHexTexels = 4f)
        val problems = PaperCatalogues.problems(PaperCatalogue(surfaces = listOf(bad), looks = listOf(look))).joinToString()
        for (word in listOf("sizing", "toothDepthMm", "fluid", "fluidHexTexels")) assertTrue(problems.contains(word), problems)
        assertEquals(20f, PaperPhysical.DOC_PX_PER_MM)
    }
}
