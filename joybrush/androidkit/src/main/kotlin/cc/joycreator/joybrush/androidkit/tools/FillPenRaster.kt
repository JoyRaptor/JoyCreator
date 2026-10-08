package cc.joycreator.joybrush.androidkit.tools

import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.doc.RegionPaintPlan
import cc.joycreator.joybrush.core.fill.MaskPaint
import cc.joycreator.joybrush.core.fill.MaskPaintMode
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** The shipped fill pen's raster adapter. No GL, document mutation, or history lives here. */
object FillPenRaster {
    // The mask, read-back tiles, replacement tiles and GL upload may coexist on a phone.
    const val MAX_TILES = 64
    const val MAX_PIXELS = 4_194_304L
    const val MAX_DESTINATION_TILES = 128
    private const val MAX_SAMPLES = 32_768
    private const val MAX_SCREEN_PATH = 32_768.0
    private const val MAX_COORD = 1_073_741_824.0

    /**
     * Exact core outline and nonzero, antialiased fill. Refuse oversized gestures BEFORE the
     * smoother expands their path or the polygon allocates coverage. Empty/degenerate gestures
     * are ordinary no-ops. Predicted/broken samples never enter a committed shape.
     */
    fun prepare(samples: List<PenSample>, smoothing: Float, screenPerDoc: Float): SelectionMask {
        require(samples.size <= MAX_SAMPLES) { "Fill stroke is too long; draw a smaller shape" }
        val usable = samples.filter { it.isPlaceable && !it.predicted }
        if (usable.size < FillPen.MIN_CORNERS) return SelectionMask.EMPTY
        checkBounds(usable.map { Pt(it.x.toDouble(), it.y.toDouble()) })
        val zoom = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc.toDouble() else 1.0
        var path = 0.0
        for (i in 1 until usable.size) {
            path += hypot(
                usable[i].x.toDouble() - usable[i - 1].x.toDouble(),
                usable[i].y.toDouble() - usable[i - 1].y.toDouble(),
            ) * zoom
            require(path <= MAX_SCREEN_PATH) { "Fill stroke is too long; draw a smaller shape" }
        }
        val amount = if (smoothing.isFinite()) smoothing.coerceIn(0f, 1f) else 0f
        val outline = FillPen.outline(usable, amount, zoom.toFloat())
        checkBounds(outline)
        return SelectionMask.polygon(outline)
    }

    /**
     * Read the fixed active layer's visible tiles, and return ONLY changed full-tile bytes.
     * The caller must commit this map through GlPaintEngine.replaceTiles in ONE transaction:
     * its immutable ownership plan copies only each cel's own slices, preserving shared pixels
     * hidden below boards and all other frames. Inputs and read-back arrays remain untouched.
     */
    fun plan(
        mask: SelectionMask,
        argb: Int,
        opacity: Float,
        mode: MaskPaintMode,
        ownership: RegionPaintPlan? = null,
        readTile: (Long) -> ByteArray?,
    ): Map<Long, ByteArray?> {
        if (mask.isEmpty || (!opacity.isNaN() && opacity <= 0f)) return emptyMap()
        val keys = mask.tileKeys
        require(keys.size <= MAX_TILES) { "Fill is too large; draw a smaller shape" }
        val bounds = mask.bounds() ?: return emptyMap()
        require(bounds.w.toLong() * bounds.h <= MAX_PIXELS) { "Fill is too large; draw a smaller shape" }
        // A single visible tile can span many physical animation cels. Count their actual
        // destinations before any read-back or candidate paint tile is allocated, rather than
        // assuming the visible tile count also bounds the atomic ownership commit's memory.
        if (ownership != null) {
            var destinations = 0
            for (key in keys) {
                destinations += ownership.tileSlices(key).map { it.plane.celId }.toSet().size
                require(destinations <= MAX_DESTINATION_TILES) {
                    "Fill touches too many animation regions; draw a smaller shape"
                }
            }
        }
        val layer = LinkedHashMap<Long, ByteArray>()
        for (key in keys) readTile(key)?.let { layer[key] = it }
        return MaskPaint.apply(layer, mask, argb, opacity, mode)
    }

    private fun checkBounds(points: List<Pt>) {
        if (points.isEmpty()) return
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            require(p.x.isFinite() && p.y.isFinite() &&
                p.x >= -MAX_COORD && p.x <= MAX_COORD && p.y >= -MAX_COORD && p.y <= MAX_COORD
            ) { "Fill is outside addressable canvas coordinates" }
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        val left = floor(minX); val top = floor(minY)
        val right = ceil(maxX); val bottom = ceil(maxY)
        val width = right - left; val height = bottom - top
        if (width <= 0.0 || height <= 0.0) return
        // Double checks precede conversion, including the tile budget at negative origins.
        require(width <= 16_384.0 && height <= 16_384.0 && width * height <= MAX_PIXELS) {
            "Fill is too large; draw a smaller shape"
        }
        val cols = floor((right - 1.0) / Tiles.SIZE) - floor(left / Tiles.SIZE) + 1.0
        val rows = floor((bottom - 1.0) / Tiles.SIZE) - floor(top / Tiles.SIZE) + 1.0
        require(cols * rows <= MAX_TILES) { "Fill is too large; draw a smaller shape" }
    }
}
