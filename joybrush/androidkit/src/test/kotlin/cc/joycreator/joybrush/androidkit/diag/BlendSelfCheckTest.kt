package cc.joycreator.joybrush.androidkit.diag

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.RegionRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure half of the on-device blend check (JB-2.20b): the drawing, the expected picture, the comparison. */
class BlendSelfCheckTest {

    private val scene = BlendSelfCheck.build()

    @Test
    fun theDrawingHasOneLayerPerModeOnTopOfABackdrop() {
        assertEquals(1 + BlendMode.entries.size, scene.layers.size)
        assertEquals(BlendSelfCheck.BASE_ID, scene.layers.first().id)
        assertEquals(BlendMode.entries.toSet(), scene.layers.drop(1).map { it.blend }.toSet(), "every mode, once")
        assertEquals(scene.layers.size, scene.layers.map { it.id }.toSet().size, "layer ids are unique")
        for (l in scene.layers) for ((_, bytes) in l.tiles) assertEquals(256 * 256 * 4, bytes.size)
        assertTrue(scene.layers.any { it.opacity < 1f }, "layer opacity must be on the line too")
    }

    @Test
    fun theExpectedPictureIsOpaqueExceptWhereEraseBelowCutAHole() {
        val expected = BlendSelfCheck.expected(scene)
        assertEquals(BlendSelfCheck.WIDTH * BlendSelfCheck.HEIGHT * 4, expected.size)
        val eraseN = BlendMode.ERASE_BELOW.ordinal
        val holes = BlendSelfCheck.spotsOf(eraseN)
        for (y in 0 until BlendSelfCheck.HEIGHT) for (x in 0 until BlendSelfCheck.WIDTH) {
            val a = expected[(y * BlendSelfCheck.WIDTH + x) * 4 + 3].toInt() and 0xFF
            val inEraseCell = holes.any { x / BlendSelfCheck.CELL == it[0] / BlendSelfCheck.CELL && y / BlendSelfCheck.CELL == it[1] / BlendSelfCheck.CELL }
            if (!inEraseCell) assertEquals(255, a, "($x,$y) is outside the ERASE_BELOW cells, so the paper shows")
        }
        // ...and inside them the hole is real: the solid half is fully erased.
        val solid = holes[1]
        assertEquals(0, expected[(solid[1] * BlendSelfCheck.WIDTH + solid[0]) * 4 + 3].toInt() and 0xFF)
    }

    @Test
    fun theModesReallyLookDifferentSoTheCheckCannotPassByEveryModeBeingNormal() {
        val expected = BlendSelfCheck.expected(scene)
        val allNormal = scene.doc.copy(layers = scene.doc.layers.map { it.copy(blend = BlendMode.NORMAL) })
        val normal = RegionRenderer.render(allNormal, scene.source, RectPx(0, 0, BlendSelfCheck.WIDTH, BlendSelfCheck.HEIGHT), null, BlendSelfCheck.PAPER)
        var different = 0
        for ((n, mode) in BlendMode.entries.withIndex()) {
            if (mode == BlendMode.NORMAL) continue
            val differs = BlendSelfCheck.spotsOf(n).any { s ->
                val i = (s[1] * BlendSelfCheck.WIDTH + s[0]) * 4
                (0..2).any { c -> kotlin.math.abs((expected[i + c].toInt() and 0xFF) - (normal[i + c].toInt() and 0xFF)) > BlendSelfCheck.TOLERANCE }
            }
            if (differs) different++
        }
        assertTrue(different >= 22, "only $different of 26 non-NORMAL modes look different from NORMAL at the spots")
    }

    /** What the screen would hold if it drew the export's picture exactly: the same pixels, premultiplied. */
    private fun asTheScreenHoldsIt(straight: ByteArray): ByteArray {
        val out = straight.copyOf()
        for (i in out.indices step 4) {
            val a = straight[i + 3].toInt() and 0xFF
            for (c in 0..2) out[i + c] = (((straight[i + c].toInt() and 0xFF) * a + 127) / 255).toByte()
        }
        return out
    }

    @Test
    fun anIdenticalPictureIsAPassAndOneWrongSpotNamesItsMode() {
        val expected = BlendSelfCheck.expected(scene)
        val screen = asTheScreenHoldsIt(expected)
        assertTrue(BlendSelfCheck.compare(expected, screen).ok)
        assertEquals(0, BlendSelfCheck.compare(expected, screen).worst)

        val n = BlendMode.MULTIPLY.ordinal
        val bad = screen.copyOf()
        val spot = BlendSelfCheck.spotsOf(n)[1]
        val i = (spot[1] * BlendSelfCheck.WIDTH + spot[0]) * 4
        bad[i] = (((bad[i].toInt() and 0xFF) + 40) and 0xFF).toByte()
        val r = BlendSelfCheck.compare(expected, bad)
        assertFalse(r.ok)
        assertEquals(listOf(BlendMode.MULTIPLY), r.failing)
        assertTrue(r.sentence().contains("MULTIPLY"))
    }

    @Test
    fun aSmallRoundingDifferenceIsNotAFailureButAnErasedHoleThatIsNotOneIs() {
        val expected = BlendSelfCheck.expected(scene)
        val near = asTheScreenHoldsIt(expected)
        for (i in near.indices) if (i % 4 != 3 && (near[i - i % 4 + 3].toInt() and 0xFF) == 255) near[i] = (((near[i].toInt() and 0xFF) + 2).coerceAtMost(255)).toByte()
        val r = BlendSelfCheck.compare(expected, near)
        assertTrue(r.ok, r.sentence())
        assertTrue(r.sentence().startsWith("All 27 blend modes match the export"))

        // The screen forgot to erase: it still holds opaque paper where the export has a hole.
        val noHole = asTheScreenHoldsIt(expected)
        val solid = BlendSelfCheck.spotsOf(BlendMode.ERASE_BELOW.ordinal)[1]
        val j = (solid[1] * BlendSelfCheck.WIDTH + solid[0]) * 4
        noHole[j] = 128.toByte(); noHole[j + 1] = 128.toByte(); noHole[j + 2] = 128.toByte(); noHole[j + 3] = 255.toByte()
        assertEquals(listOf(BlendMode.ERASE_BELOW), BlendSelfCheck.compare(expected, noHole).failing)
    }

    /**
     * Is [BlendSelfCheck.TOLERANCE] honest? The screen holds the stack in RGBA8, so it rounds after every
     * layer; the export keeps floats to the end. This runs the SAME composite maths (`Blend.apply`) over
     * the check drawing with the screen's rounding, and asserts the export and this simulated screen stay
     * inside the tolerance at every spot. If a mode ever needed a bigger tolerance to pass, this fails
     * first and says which — rather than a phone reporting a mismatch that is only rounding.
     */
    @Test
    fun theToleranceCoversTheScreensEightBitRoundingForEveryMode() {
        val expected = BlendSelfCheck.expected(scene)
        val paper = floatArrayOf(128 / 255f, 128 / 255f, 128 / 255f, 1f)
        val s = FloatArray(4); val out = FloatArray(4)
        var worst = 0
        val simulated = BlendSelfCheck.spotsOf(0).let { ByteArray(expected.size) }   // only the spots are filled
        for ((n, mode) in BlendMode.entries.withIndex()) {
            val layer = scene.layers.first { it.id == BlendSelfCheck.layerIdOf(mode) }
            for (spot in BlendSelfCheck.spotsOf(n)) {
                val d = paper.copyOf()
                for (l in listOf(scene.layers.first(), layer)) {
                    val tile = l.tiles[cc.joycreator.joybrush.core.paint.Tiles.key(spot[0] / 256, spot[1] / 256)]
                    val i = ((spot[1] % 256) * 256 + (spot[0] % 256)) * 4
                    for (c in 0..3) s[c] = if (tile == null) 0f else (tile[i + c].toInt() and 0xFF) / 255f * l.opacity
                    cc.joycreator.joybrush.core.render.Blend.apply(l.blend, s, d, out)
                    for (c in 0..3) d[c] = Math.round(out[c] * 255f) / 255f      // the screen's RGBA8
                }
                val j = (spot[1] * BlendSelfCheck.WIDTH + spot[0]) * 4
                for (c in 0..3) simulated[j + c] = Math.round(d[c] * 255f).toByte()
            }
        }
        val r = BlendSelfCheck.compare(expected, simulated)
        worst = r.worst
        assertTrue(r.ok, "the tolerance ${BlendSelfCheck.TOLERANCE} is too tight for plain rounding: ${r.sentence()}")
        println("BlendSelfCheck: worst rounding difference on a simulated screen = $worst / 255")
    }
}
