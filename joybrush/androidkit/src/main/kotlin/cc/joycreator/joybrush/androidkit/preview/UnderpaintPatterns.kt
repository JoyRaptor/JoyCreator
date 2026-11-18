package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * What a smudge, push or eraser works into on the brush settings stage (owner, 11-16 and 11-18). The first ruling was a
 * fine grey checkerboard; on the Note 9 it turned to grey too soon ("larger checkers would show more of the pull"), so
 * these are the options he picks from, each a picture made of whole pixels from its own top left. Sizes are in dp
 * ([dp] = px per dp) so a square looks the same on every phone.
 *
 * Pure pixel arithmetic, no GPU: [paint] writes tiles the canvas takes as the layer's own paint, so the brush reads them.
 */
object UnderpaintPatterns {

    /** One option: its [id] (stable, for settings and logs), the [name] shown to the owner, and its colour at a pixel. */
    class Pattern(val id: String, val name: String, val argbAt: (x: Int, y: Int, dp: Float) -> Int)

    private const val GREY_LIGHT = 0xFFC9C9C9.toInt()
    private const val GREY_DARK = 0xFF6B6B6B.toInt()

    private fun cell(v: Int, size: Float): Int = Math.floorDiv(v, size.toInt().coerceAtLeast(1))

    private fun checker(x: Int, y: Int, size: Float, a: Int, b: Int): Int = if ((cell(x, size) + cell(y, size)) and 1 == 0) a else b

    private fun hsv(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

    /** Averages two colours channel by channel (a woven thread over another). */
    private fun mix(a: Int, b: Int): Int {
        fun ch(c: Int, s: Int) = (c ushr s) and 0xFF
        val r = (ch(a, 16) + ch(b, 16)) / 2; val g = (ch(a, 8) + ch(b, 8)) / 2; val bl = (ch(a, 0) + ch(b, 0)) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private val FOUR = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt())

    /** A tartan thread colour at [v] along one direction (a 48 dp sett: navy, red, yellow over dark green). */
    private fun sett(v: Int, dp: Float): Int {
        val u = Math.floorMod(v, (48 * dp).toInt().coerceAtLeast(8)) / dp
        return when {
            u < 14f -> 0xFF1F2A5A.toInt()
            u in 20f..26f -> 0xFFB71C1C.toInt()
            u in 34f..36f -> 0xFFFBC02D.toInt()
            else -> 0xFF1B4D3E.toInt()
        }
    }

    val FINE = Pattern("checker-fine", "Fine checker (now)") { x, y, d -> checker(x, y, 4 * d, GREY_LIGHT, GREY_DARK) }

    val ALL: List<Pattern> = listOf(
        FINE,
        Pattern("checker-10", "Grey checker, small") { x, y, d -> checker(x, y, 10 * d, GREY_LIGHT, GREY_DARK) },
        Pattern("checker-20", "Grey checker, medium") { x, y, d -> checker(x, y, 20 * d, GREY_LIGHT, GREY_DARK) },
        Pattern("checker-40", "Grey checker, large") { x, y, d -> checker(x, y, 40 * d, GREY_LIGHT, GREY_DARK) },
        Pattern("checker-bw", "Black and white checker") { x, y, d -> checker(x, y, 12 * d, 0xFFF2F2F2.toInt(), 0xFF141414.toInt()) },
        Pattern("checker-four", "Four-colour checker") { x, y, d -> FOUR[Math.floorMod(cell(x, 12 * d) + 2 * cell(y, 12 * d), 4)] },
        Pattern("checker-two", "Orange and teal checker") { x, y, d -> checker(x, y, 16 * d, 0xFFF28C28.toInt(), 0xFF1A9E9E.toInt()) },
        Pattern("checker-rainbow", "Rainbow checker") { x, y, d ->
            val cx = cell(x, 14 * d); val cy = cell(y, 14 * d)
            hsv(cx * 37f + cy * 83f, 0.8f, if ((cx + cy) and 1 == 0) 0.95f else 0.6f)
        },
        Pattern("tartan", "Tartan") { x, y, d ->
            // A twill: the warp shows on one diagonal step, the weft on the next.
            val step = (1.5f * d).toInt().coerceAtLeast(1)
            if (Math.floorMod(Math.floorDiv(x + y, step), 2) == 0) sett(x, d) else mix(sett(x, d), sett(y, d))
        },
        Pattern("gingham", "Red gingham") { x, y, d ->
            val bx = cell(x, 12 * d) and 1 == 0; val by = cell(y, 12 * d) and 1 == 0
            when { bx && by -> 0xFFC62828.toInt(); bx || by -> 0xFFE88A8A.toInt(); else -> 0xFFFAFAFA.toInt() }
        },
        Pattern("stripes-rainbow", "Rainbow stripes") { x, _, d -> hsv(cell(x, 8 * d) * 30f, 0.85f, 0.9f) },
        Pattern("stripes-diagonal", "Diagonal stripes") { x, y, d -> if (cell(x + y, 10 * d) and 1 == 0) 0xFF263238.toInt() else 0xFFFFB300.toInt() },
        Pattern("graph", "Graph paper") { x, y, d ->
            val pitch = (12 * d).toInt().coerceAtLeast(4)
            val line = (0.6f * d).toInt().coerceAtLeast(1)
            val mx = Math.floorMod(x, pitch); val my = Math.floorMod(y, pitch)
            val major = Math.floorMod(Math.floorDiv(x, pitch), 5) == 0 && mx < line * 2 || Math.floorMod(Math.floorDiv(y, pitch), 5) == 0 && my < line * 2
            when { major -> 0xFF2F5D8C.toInt(); mx < line || my < line -> 0xFF7FA3C8.toInt(); else -> 0xFFF5F1E6.toInt() }
        },
        Pattern("dots", "Polka dots") { x, y, d ->
            val pitch = 14 * d
            val row = cell(y, pitch)
            val ox = if (row and 1 == 0) 0f else pitch / 2
            val fx = Math.floorMod((x + ox).toInt(), pitch.toInt().coerceAtLeast(2)) - pitch / 2
            val fy = Math.floorMod(y, pitch.toInt().coerceAtLeast(2)) - pitch / 2
            if (fx * fx + fy * fy <= (4 * d) * (4 * d)) 0xFF233A6B.toInt() else 0xFFF4EBD8.toInt()
        },
    )

    fun byId(id: String?): Pattern? = ALL.firstOrNull { it.id == id }

    /**
     * Paints [p] over [x0, x1) × [y0, y1) into [tiles] (premultiplied RGBA8 tiles by engine key), replacing what was
     * there, counted from [originX], [originY]. Any coordinates, negative too.
     */
    fun paint(p: Pattern, tiles: MutableMap<Long, ByteArray>, x0: Int, y0: Int, x1: Int, y1: Int, dp: Float,
              originX: Int = x0, originY: Int = y0) {
        if (x1 <= x0 || y1 <= y0) return
        val t = Tiles.SIZE
        for (ty in Math.floorDiv(y0, t)..Math.floorDiv(y1 - 1, t)) for (tx in Math.floorDiv(x0, t)..Math.floorDiv(x1 - 1, t)) {
            val tile = tiles.getOrPut(Tiles.key(tx, ty)) { ByteArray(t * t * 4) }
            val ox = tx * t; val oy = ty * t
            for (y in maxOf(y0, oy) until minOf(y1, oy + t)) for (x in maxOf(x0, ox) until minOf(x1, ox + t)) {
                val argb = p.argbAt(x - originX, y - originY, dp)
                val at = ((y - oy) * t + (x - ox)) * 4
                // Every option is opaque: premultiplied is the colour itself.
                tile[at] = (argb ushr 16).toByte(); tile[at + 1] = (argb ushr 8).toByte(); tile[at + 2] = argb.toByte(); tile[at + 3] = 0xFF.toByte()
            }
        }
    }
}
