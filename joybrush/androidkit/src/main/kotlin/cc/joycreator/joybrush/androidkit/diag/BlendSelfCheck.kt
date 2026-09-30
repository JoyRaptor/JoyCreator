package cc.joycreator.joybrush.androidkit.diag

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import kotlin.math.abs

/**
 * The owner check for JB-2.20b, made one tap: does the phone's screen composite every blend mode the
 * way the EXPORT does?
 *
 * [build] makes a small drawing — a grey-paper page, a colourful backdrop layer whose top half is
 * opaque and whose bottom half is half-transparent, and then one layer per [BlendMode] (27), each
 * with a half-opaque swatch on the left and a solid one on the right, drawn in two cells: one over
 * the opaque backdrop and one over the half-transparent backdrop (the un-premultiply is where a
 * shader port goes wrong, and an opaque backdrop would not show it). Every third layer also carries a
 * layer opacity below 1, so `u_layerOpacity` is on the line too.
 *
 * The screen draws that drawing through the real engine ([cc.joycreator.joybrush.androidkit.JbCanvasView.runBlendCheck]);
 * [expected] is the same drawing through [RegionRenderer], the CPU renderer every export uses; [compare]
 * says how far apart they are. Pure Kotlin, so everything but the GL draw is unit-tested.
 */
object BlendSelfCheck {

    const val CELL = 64
    const val COLS = 9
    const val ROWS = 6          // 3 rows over the opaque backdrop, 3 over the half-transparent one
    const val WIDTH = COLS * CELL
    const val HEIGHT = ROWS * CELL
    const val PAPER = "#808080"

    /** Eight-bit rounding happens once per layer on the GPU and once at the end on the CPU (a simulation of it stays within 1: BlendSelfCheckTest). */
    const val TOLERANCE = 4

    const val BASE_ID = "base"
    fun layerIdOf(mode: BlendMode) = "m${mode.ordinal}"

    /** One layer of the check drawing: its tiles (premultiplied RGBA8, 256 x 256), its mode and its opacity. */
    class CheckLayer(val id: String, val blend: BlendMode, val opacity: Float, val tiles: Map<Long, ByteArray>)

    class Scene(val doc: JbDocument, val layers: List<CheckLayer>) {
        val source = TileSource { layerId, _, tx, ty -> layers.firstOrNull { it.id == layerId }?.tiles?.get(Tiles.key(tx, ty)) }
    }

    /** Where mode number [n] (0..26) is drawn: its cell over the opaque backdrop, and over the translucent one. */
    fun cellOf(n: Int, translucent: Boolean): IntArray {
        val col = n % COLS
        val row = n / COLS + (if (translucent) 3 else 0)
        return intArrayOf(col * CELL, row * CELL)
    }

    fun build(): Scene {
        val modes = BlendMode.entries
        val layers = ArrayList<CheckLayer>()
        layers += CheckLayer(BASE_ID, BlendMode.NORMAL, 1f, baseTiles())
        for ((n, mode) in modes.withIndex()) {
            val opacity = if (n % 3 == 2) 0.8f else 1f
            layers += CheckLayer(layerIdOf(mode), mode, opacity, swatchTiles(n))
        }
        val doc = JbDocument(
            id = "blend-check", name = "Blend check", paper = Paper(color = PAPER),
            boards = listOf(Board("b", "b", BoardKind.CANVAS, RectPx(0, 0, WIDTH, HEIGHT))),
            layers = layers.map {
                Layer(
                    id = it.id, name = it.id, kind = LayerKind.PAINT, opacity = it.opacity, blend = it.blend,
                    cels = listOf(Cel("c", it.tiles.keys.map { k -> "${Tiles.tx(k)}_${Tiles.ty(k)}" })),
                )
            },
        )
        return Scene(doc, layers)
    }

    /** The drawing as the EXPORT renders it: straight RGBA8, WIDTH x HEIGHT, row 0 = top. */
    fun expected(scene: Scene): ByteArray =
        RegionRenderer.render(scene.doc, scene.source, RectPx(0, 0, WIDTH, HEIGHT), null, PAPER)

    /** Where each mode is looked at: the left (half-opaque) and right (solid) swatch of both its cells. */
    fun spotsOf(n: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (translucent in listOf(false, true)) {
            val (x0, y0) = cellOf(n, translucent).let { it[0] to it[1] }
            out += intArrayOf(x0 + 16, y0 + 32)
            out += intArrayOf(x0 + 48, y0 + 32)
        }
        return out
    }

    class Report(val worstByMode: Map<BlendMode, Int>) {
        val worst: Int get() = worstByMode.values.maxOrNull() ?: 0
        val failing: List<BlendMode> get() = worstByMode.filterValues { it > TOLERANCE }.keys.toList()
        val ok: Boolean get() = failing.isEmpty()

        /** One sentence for the person. */
        fun sentence(): String =
            if (ok) "All 27 blend modes match the export (worst difference $worst out of 255)."
            else "${failing.size} of 27 blend modes do NOT match the export: " +
                failing.joinToString(", ") { "${it.name} (${worstByMode.getValue(it)})" } + "."
    }

    /**
     * [expected] is the export's picture (STRAIGHT RGBA8, WIDTH x HEIGHT, row 0 = top); [actual] is what the
     * screen drew, read back as it is held (PREMULTIPLIED, same layout). They are compared premultiplied,
     * alpha included: where ERASE_BELOW has cut a hole the screen holds (0,0,0,0) and the export's straight
     * colour there means nothing, so comparing straight colours would report a difference that is not one.
     */
    fun compare(expected: ByteArray, actual: ByteArray): Report {
        require(expected.size == WIDTH * HEIGHT * 4 && actual.size == expected.size) {
            "a check picture is ${WIDTH * HEIGHT * 4} bytes, got ${expected.size} and ${actual.size}"
        }
        val worst = LinkedHashMap<BlendMode, Int>()
        for ((n, mode) in BlendMode.entries.withIndex()) {
            var w = 0
            for (spot in spotsOf(n)) {
                val i = (spot[1] * WIDTH + spot[0]) * 4
                val ea = expected[i + 3].toInt() and 0xFF
                w = maxOf(w, abs(ea - (actual[i + 3].toInt() and 0xFF)))
                for (c in 0..2) {
                    val e = ((expected[i + c].toInt() and 0xFF) * ea + 127) / 255
                    w = maxOf(w, abs(e - (actual[i + c].toInt() and 0xFF)))
                }
            }
            worst[mode] = w
        }
        return Report(worst)
    }

    /** The largest channel difference between two pictures of the same layout (both premultiplied), over every pixel. */
    fun worstDifference(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size) { "two check pictures must be the same size, got ${a.size} and ${b.size}" }
        var w = 0
        for (i in a.indices) w = maxOf(w, abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)))
        return w
    }

    // ---- the drawing -------------------------------------------------------------------------------

    private fun newTiles(): HashMap<Long, ByteArray> = HashMap()

    private fun put(tiles: HashMap<Long, ByteArray>, x: Int, y: Int, r: Int, g: Int, b: Int, a: Int) {
        val key = Tiles.key(x / Tiles.SIZE, y / Tiles.SIZE)
        val bytes = tiles.getOrPut(key) { ByteArray(Tiles.SIZE * Tiles.SIZE * 4) }
        val i = ((y % Tiles.SIZE) * Tiles.SIZE + (x % Tiles.SIZE)) * 4
        // Premultiplied, rounded to nearest, exactly as an engine tile would hold it.
        bytes[i] = ((r * a + 127) / 255).toByte()
        bytes[i + 1] = ((g * a + 127) / 255).toByte()
        bytes[i + 2] = ((b * a + 127) / 255).toByte()
        bytes[i + 3] = a.toByte()
    }

    private fun baseTiles(): Map<Long, ByteArray> {
        val t = newTiles()
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val r = 40 + x * 215 / (WIDTH - 1)
            val g = 60 + (y % (HEIGHT / 2)) * 180 / (HEIGHT / 2 - 1)
            val b = 200 - x * 150 / (WIDTH - 1)
            put(t, x, y, r, g, b, if (y < HEIGHT / 2) 255 else 128)
        }
        return t
    }

    private fun swatchTiles(n: Int): Map<Long, ByteArray> {
        val t = newTiles()
        for (translucent in listOf(false, true)) {
            val (x0, y0) = cellOf(n, translucent).let { it[0] to it[1] }
            for (y in y0 + 4 until y0 + CELL - 4) for (x in x0 + 4 until x0 + CELL - 4) {
                if (x < x0 + CELL / 2) put(t, x, y, 200, 60, 90, 128)     // half-opaque red-ish
                else put(t, x, y, 30, 160, 220, 255)                       // solid blue-ish
            }
        }
        return t
    }
}
