package cc.joycreator.joybrush.core.paint

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The CPU reference painter: the exact maths the GPU engine performs, written plainly so it can be
 * unit-tested anywhere and used as the "golden" answer when checking the GPU on a device.
 *
 * Pixels are PREMULTIPLIED RGBA floats 0..1 in sRGB space (blueprint §3.4: sRGB compositing by
 * default). Tiles are sparse and copy-on-write, exactly as on the GPU, and undo uses [UndoLog].
 *
 * A stroke:
 * 1. [beginStroke] — colour, opacity, accumulate mode, blend, tip.
 * 2. [addDabs] — each dab adds into the stroke buffer: `s' = cap·d + s·(1 − d)` (see [Accumulate]).
 * 3. [endStroke] — each touched tile is rebuilt as a NEW tile: with `a = s × (opacity if BUILD_UP else 1)`,
 *    NORMAL: `out = colour·a + dst·(1 − a)`; ERASE: `out = dst·(1 − a)`; MULTIPLY and SCREEN: the W3C modes on the
 *    premultiplied stroke colour `colour·a`, as `render/Blend` and `jb_commit.frag` apply them. Old tiles go to undo.
 * Optional per-dab colour accumulates RGB moments `m' = colour·cap·d + m·(1 − d)` beside the same mask;
 * commit uses `m·k` for RGB and the original mask for alpha. A missing dab colour uses the stroke colour.
 */
class RefCanvas(private val tileSize: Int = Tiles.SIZE, undoBudgetBytes: Long = 64L shl 20) {

    private val px = tileSize * tileSize
    private val layers = HashMap<String, HashMap<Long, FloatArray>>()
    val undo = UndoLog<FloatArray>(undoBudgetBytes, sizeOf = { it.size * 4L }, release = { })

    // Active stroke.
    private var layerId: String? = null
    private var r = 0f; private var g = 0f; private var b = 0f
    private var opacity = 1f
    private var mode = Accumulate.WASH
    private var blend = StrokeBlend.NORMAL
    private var tip = TipShape()
    private val stroke = HashMap<Long, FloatArray>()
    // RGB moments use the same coverage decay as the mask, with straight per-dab colour.
    // Absent for ordinary fixed-colour strokes: their allocation and arithmetic stay unchanged.
    private val strokeColors = HashMap<Long, FloatArray>()

    /** Tiles of a layer (read-only view for tests and exporters). */
    fun tiles(layer: String): Map<Long, FloatArray> = layers[layer] ?: emptyMap()

    /** Premultiplied RGBA at a document pixel (0s where nothing is painted). */
    fun pixel(layer: String, x: Int, y: Int): FloatArray {
        val tx = floorDiv(x, tileSize); val ty = floorDiv(y, tileSize)
        val t = layers[layer]?.get(Tiles.key(tx, ty)) ?: return FloatArray(4)
        val i = ((y - ty * tileSize) * tileSize + (x - tx * tileSize)) * 4
        return floatArrayOf(t[i], t[i + 1], t[i + 2], t[i + 3])
    }

    fun beginStroke(layer: String, red: Float, green: Float, blue: Float, opacity: Float,
                    mode: Accumulate, blend: StrokeBlend, tip: TipShape) {
        check(layerId == null) { "a stroke is already in progress" }
        layerId = layer; r = red; g = green; b = blue
        this.opacity = opacity.coerceIn(0f, 1f); this.mode = mode; this.blend = blend; this.tip = tip
        stroke.clear(); strokeColors.clear()
    }

    /** The cap a dab should carry for the active stroke's mode. */
    fun capForStroke(): Float = if (mode == Accumulate.WASH) opacity else 1f

    fun addDabs(dabs: List<Dab>) {
        checkNotNull(layerId) { "no stroke in progress" }
        val resolved = dabs.map { d ->
            if (d.anchor.isFinite() && (d.sampled != null || tip.sampled == null)) d else d.copy(
                anchor = if (d.anchor.isFinite()) d.anchor else tip.anchor, sampled = d.sampled ?: tip.sampled)
        }
        for ((key, list) in Tiles.bucket(resolved, tileSize)) {
            val buf = stroke.getOrPut(key) { FloatArray(px) }
            val ox = Tiles.tx(key) * tileSize
            val oy = Tiles.ty(key) * tileSize
            for (d in list) {
                var colors = strokeColors[key]
                if (colors == null && d.color != null) {
                    // Colour may first appear in a later event batch. Preserve the contribution
                    // already accumulated under the stroke's fixed colour before switching paths.
                    colors = FloatArray(px * 3)
                    for (i in 0 until px) {
                        colors[i * 3] = r * buf[i]
                        colors[i * 3 + 1] = g * buf[i]
                        colors[i * 3 + 2] = b * buf[i]
                    }
                    strokeColors[key] = colors
                }
                stamp(buf, colors, ox, oy, d)
            }
        }
    }

    private fun stamp(buf: FloatArray, colors: FloatArray?, ox: Int, oy: Int, d: Dab) {
        val liveTip = d.tipShape(tip)
        val e = TipMath.extent(d.radius, liveTip.anchor, liveTip.sampled, liveTip.aspect)
        val x0 = max(floor(d.x - e).toInt(), ox); val x1 = min(floor(d.x + e).toInt(), ox + tileSize - 1)
        val y0 = max(floor(d.y - e).toInt(), oy); val y1 = min(floor(d.y + e).toInt(), oy + tileSize - 1)
        for (y in y0..y1) for (x in x0..x1) {
            // Pixel centres, as the GPU samples them.
            val cov = TipMath.coverage(x + 0.5f - d.x, y + 0.5f - d.y, d.radius, d.angle, liveTip) * d.flow
            if (cov <= 0f) continue
            val i = (y - oy) * tileSize + (x - ox)
            buf[i] = d.cap * cov + buf[i] * (1f - cov)
            if (colors != null) {
                val c = d.color
                val j = i * 3
                colors[j] = (c?.r ?: r) * d.cap * cov + colors[j] * (1f - cov)
                colors[j + 1] = (c?.g ?: g) * d.cap * cov + colors[j + 1] * (1f - cov)
                colors[j + 2] = (c?.b ?: b) * d.cap * cov + colors[j + 2] * (1f - cov)
            }
        }
    }

    /** Commits the stroke; returns the number of tiles changed. */
    fun endStroke(): Int {
        val layer = checkNotNull(layerId) { "no stroke in progress" }
        val tiles = layers.getOrPut(layer) { HashMap() }
        val changes = ArrayList<UndoLog.TileChange<FloatArray>>()
        val k = if (mode == Accumulate.BUILD_UP) opacity else 1f
        for ((key, s) in stroke) {
            val colors = strokeColors[key]
            val before = tiles[key]
            if (before == null && blend == StrokeBlend.ERASE) continue
            val after = FloatArray(px * 4)
            for (i in 0 until px) {
                val a = s[i] * k
                val j = i * 4
                val dr = before?.get(j) ?: 0f; val dg = before?.get(j + 1) ?: 0f
                val db = before?.get(j + 2) ?: 0f; val da = before?.get(j + 3) ?: 0f
                // The mask is authoritative alpha, including when successive caps differ.
                val sr = if (colors == null) r * a else colors[i * 3] * k
                val sg = if (colors == null) g * a else colors[i * 3 + 1] * k
                val sb = if (colors == null) b * a else colors[i * 3 + 2] * k
                if (blend == StrokeBlend.ERASE) {
                    after[j] = dr * (1 - a); after[j + 1] = dg * (1 - a); after[j + 2] = db * (1 - a); after[j + 3] = da * (1 - a)
                } else if (blend == StrokeBlend.MULTIPLY) {
                    // W3C multiply, premultiplied: s·d + s·(1 − da) + d·(1 − a) per channel, with s = colour · a.
                    after[j] = sr * dr + sr * (1 - da) + dr * (1 - a); after[j + 1] = sg * dg + sg * (1 - da) + dg * (1 - a)
                    after[j + 2] = sb * db + sb * (1 - da) + db * (1 - a); after[j + 3] = a + da * (1 - a)
                } else if (blend == StrokeBlend.SCREEN) {
                    // W3C screen, premultiplied: s + d·(1 − s) per channel.
                    after[j] = sr + dr * (1 - sr); after[j + 1] = sg + dg * (1 - sg)
                    after[j + 2] = sb + db * (1 - sb); after[j + 3] = a + da * (1 - a)
                } else {
                    after[j] = sr + dr * (1 - a); after[j + 1] = sg + dg * (1 - a)
                    after[j + 2] = sb + db * (1 - a); after[j + 3] = a + da * (1 - a)
                }
            }
            tiles[key] = after
            changes.add(UndoLog.TileChange(layer, key, before, after))
        }
        stroke.clear(); strokeColors.clear(); layerId = null
        if (changes.isNotEmpty()) undo.push(UndoLog.Step(changes))
        return changes.size
    }

    fun cancelStroke() { stroke.clear(); strokeColors.clear(); layerId = null }

    fun undoStep(): Boolean {
        val s = undo.undo() ?: return false
        for (c in s.changes) put(c.layerId, c.key, c.before)
        return true
    }

    fun redoStep(): Boolean {
        val s = undo.redo() ?: return false
        for (c in s.changes) put(c.layerId, c.key, c.after)
        return true
    }

    private fun put(layer: String, key: Long, t: FloatArray?) {
        val m = layers.getOrPut(layer) { HashMap() }
        if (t == null) m.remove(key) else m[key] = t
    }

    private fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b).toInt()
}
