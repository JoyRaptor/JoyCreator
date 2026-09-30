package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JB-1.06 — the CPU half of the engine: which colour each dab of a smudge stroke paints with. */
class SmudgeStrokeTest {

    /** A layer that is one opaque colour everywhere inside tile (0,0), and empty elsewhere. */
    private fun flat(r: Int, g: Int, b: Int, a: Int = 255) = TileReader { tx, ty ->
        if (tx != 0 || ty != 0) null else ByteArray(Tiles.SIZE * Tiles.SIZE * 4).also { t ->
            for (i in t.indices step 4) {
                t[i] = (r * a / 255).toByte(); t[i + 1] = (g * a / 255).toByte(); t[i + 2] = (b * a / 255).toByte(); t[i + 3] = a.toByte()
            }
        }
    }

    private val hard = TipShape(hardness = 1f)
    private fun dab(x: Float, y: Float, r: Float = 4f) = Dab(x = x, y = y, radius = r, angle = 0f)

    @Test
    fun overOnePlainColourEveryDabPaintsWithTheClosedFormCarriedColour() {
        // The layer is (51, 102, 153)/255 = (0.2, 0.4, 0.6) opaque everywhere. The carried colour STARTS as what is under the
        // first dab (R47), then follows c' = (1-load)(1-pickup) c + (1-load) pickup canvas + load L (derivation: SmudgeTest).
        // Dab k paints with c BEFORE its own update, so dab 0 paints the canvas colour itself (a no-op on the picture).
        val l = floatArrayOf(0.9f, 0.1f, 0.4f)
        val canvas = floatArrayOf(0.2f, 0.4f, 0.6f)
        val stroke = SmudgeStroke(flat(51, 102, 153), SmudgeCarried(l[0], l[1], l[2], 1f, 0.25f, 0.1f), hard)
        val dabs = List(30) { dab(60f + it * 3f, 100f) }
        val colours = stroke.colours(dabs)
        val c = canvas.copyOf()
        for (k in dabs.indices) {
            for (i in 0..2) assertEquals(c[i], colours[k * 4 + i], 2e-3f, "dab $k channel $i")
            assertEquals(1f, colours[k * 4 + 3], 1e-6f)
            for (i in 0..2) c[i] = 0.9f * 0.75f * c[i] + 0.9f * 0.25f * canvas[i] + 0.1f * l[i]
        }
    }

    @Test
    fun overAnEmptyLayerNothingIsPickedUpSoEveryDabPaintsTheChosenColour() {
        val stroke = SmudgeStroke(TileReader { _, _ -> null }, SmudgeCarried(0.9f, 0.1f, 0.4f, 1f, 0.5f, 0.5f), hard)
        val colours = stroke.colours(List(10) { dab(20f + it * 4f, 20f) })
        for (k in 0 until 10) {
            assertEquals(listOf(0.9f, 0.1f, 0.4f, 1f), colours.slice(k * 4 until k * 4 + 4))
        }
    }

    @Test
    fun aStrokeFromPaintIntoBareCanvasCarriesThePaintOnAndFadesAsItLoadsItsOwnColour() {
        // Left half of tile (0,0) is opaque red, right half empty. The stroke starts on the red and ends in the empty half.
        val reader = TileReader { tx, ty ->
            if (tx != 0 || ty != 0) null else ByteArray(Tiles.SIZE * Tiles.SIZE * 4).also { t ->
                for (y in 0 until 256) for (x in 0 until 128) { val i = (y * 256 + x) * 4; t[i] = 255.toByte(); t[i + 3] = 255.toByte() }
            }
        }
        val stroke = SmudgeStroke(reader, SmudgeCarried(0f, 0f, 1f, 1f, 1f, 0f), hard) // brush colour blue; full pickup, no load
        val colours = stroke.colours(List(60) { dab(40f + it * 3f, 100f, 3f) })
        // The stroke starts carrying the red under the pen, NOT the brush's blue (R47): the brush colour is only what an
        // empty start falls back to, and `load` is 0 here so it never creeps in.
        assertEquals(listOf(1f, 0f, 0f, 1f), colours.slice(0 until 4))
        assertEquals(listOf(1f, 0f, 0f, 1f), colours.slice(4 until 8), "pickup 1: the carried colour IS the red under the last dab")
        // As the tip leaves the paint, the mean under it thins: the last dab that still touched any red picked up a
        // mostly-empty footprint, so the carried colour is a FAINT red (premultiplied red == its alpha, both small). Once
        // the tip is wholly over bare canvas nothing is picked up any more (Decision 4), so it then stops changing rather
        // than fading to nothing, and every later dab paints with that same faint red.
        val last = colours.slice(colours.size - 8 until colours.size)
        assertEquals(last.slice(0 until 4), last.slice(4 until 8), "the colour stopped changing over bare canvas")
        assertEquals(last[0], last[3], 1e-6f, "pure red: premultiplied red equals alpha")
        assertTrue(last[3] > 0f && last[3] < 0.2f, "a faint red, not the full red and not nothing: ${last[3]}")
    }

    @Test
    fun theSameStrokeOnTheSameLayerGivesBitIdenticalColours() {
        fun run() = SmudgeStroke(flat(200, 30, 90), SmudgeCarried(0f, 1f, 0f, 1f, 0.3f, 0.2f), hard)
            .colours(List(50) { dab(10f + it * 2f, 50f + (it % 5)) })
        assertTrue(run().contentEquals(run()))
    }

    @Test
    fun aTipOverTheEdgeOfATileReadsTheNeighbourTooAndANegativeSideIsHandled() {
        // A dab centred at x = -2 lies in tile (-1, 0): the reader is asked for it, floor-divided, not truncated toward zero.
        val asked = ArrayList<Pair<Int, Int>>()
        val stroke = SmudgeStroke(TileReader { tx, ty -> asked += tx to ty; null }, SmudgeCarried(1f, 0f, 0f, 1f, 0.5f, 0.5f), hard)
        stroke.colours(listOf(dab(-2f, 5f, 4f)))
        assertTrue((-1 to 0) in asked && (0 to 0) in asked, "asked for $asked")
        assertTrue(asked.none { it.first < -1 || it.first > 0 }, "asked for $asked")
    }

    @Test
    fun aDabIsAskedForEachTileOnlyOncePerStroke() {
        var reads = 0
        val stroke = SmudgeStroke(TileReader { _, _ -> reads++; null }, SmudgeCarried(1f, 0f, 0f, 1f, 0.5f, 0.5f), hard)
        stroke.colours(List(100) { dab(50f + it, 50f, 3f) })
        assertTrue(reads <= 2, "each tile is read once and cached: $reads")
    }
}
