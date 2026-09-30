package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import kotlin.math.floor

/**
 * Supplies one tile of the layer AS IT WAS WHEN THE STROKE BEGAN: 256 x 256 RGBA8, PREMULTIPLIED, row 0 = the tile's top
 * document row; null if the tile is empty.
 */
fun interface TileReader {
    fun tile(tx: Int, ty: Int): ByteArray?
}

/**
 * One smudge stroke's colours (JB-1.06, LEAD_RULINGS R47): for each dab, the ONE carried colour it paints with.
 *
 * **HOW A SMUDGE BECOMES A STROKE THE ENGINE ALREADY KNOWS HOW TO DRAW.** A smudge dab writes
 * `lerp(canvas, carried, t)` with `t = flow x tip coverage`. Written as "over" that is exactly a dab of the carried
 * colour with alpha `t`: `carried x t + canvas x (1 - t)`. And a run of such dabs in order is what the stroke buffer already
 * accumulates (`s' = c*t + s*(1-t)`, fixed-function blending, no read-back), then commits over the layer. So the GPU never
 * has to read the canvas per dab; the only thing that does is THIS class, which decides each dab's colour.
 *
 * **What the carried colour picks up is the layer as it was when the stroke began.** The stroke buffer is not the layer
 * until pen-up, so nothing here can see the smear the stroke itself has made so far. That is deliberate: it is what makes
 * a smudge one batched draw per tile instead of a GPU read-back per dab, it is deterministic (the same recorded stroke on the
 * same layer gives the same pixels: R20's "re-brush and it draws as if that pen"), and a stroke that doubles back over
 * itself picks up the original paint rather than its own smear — which is why a second stroke exists. The colour is
 * measured under the tip: the coverage-weighted mean of the pre-stroke pixels inside the footprint.
 *
 * The patent rule lives in [SmudgeCarried] and is not restated here: this class holds exactly one of them and replaces it
 * after every dab. Pure Kotlin, testable with a fake [TileReader].
 */
class SmudgeStroke(
    private val reader: TileReader,
    start: SmudgeCarried,
    private val tip: TipShape,
) {
    /** The carried colour the NEXT dab will paint with. The only state this class has besides the tile cache. */
    var carried: SmudgeCarried = start
        private set

    private val cache = HashMap<Long, ByteArray?>()
    private val under = FloatArray(4)
    private var first = true

    /**
     * The colour each of [dabs] paints with, as 4 floats per dab (premultiplied r, g, b, a), in order. Dab `k` paints with
     * the carried colour as it stood BEFORE dab `k` looked at the canvas (`Smudge.dab` writes with the old colour; the
     * update comes after), then the carried colour is updated from what is under dab `k`.
     */
    fun colours(dabs: List<Dab>): FloatArray {
        val out = FloatArray(dabs.size * 4)
        for ((i, d) in dabs.withIndex()) {
            // A smudge STARTS carrying what is under the pen (R47, amending Decision 3): a finger dragged through paint
            // moves that paint, it does not first add the brush's own colour. `load` is how much of the chosen colour then
            // creeps in; with load 0 the brush never adds a colour that was not on the layer. On bare canvas there is
            // nothing to start with, so it starts as the chosen colour (and, Decision 4, paints nothing until it meets paint).
            if (first && canvasUnder(d, under)) {
                carried = carried.copy(carriedR = under[0], carriedG = under[1], carriedB = under[2], carriedA = under[3])
            }
            first = false
            val c = carried
            out[i * 4] = c.carriedR; out[i * 4 + 1] = c.carriedG; out[i * 4 + 2] = c.carriedB; out[i * 4 + 3] = c.carriedA
            if (canvasUnder(d, under)) carried = c.afterDab(under[0], under[1], under[2], under[3])
        }
        return out
    }

    /**
     * The coverage-weighted mean of the pre-stroke pixels under [d]'s tip, premultiplied, into [out]; false if there is
     * nothing there to pick up (no pixel of the footprint has any alpha), which leaves the carried colour alone.
     */
    internal fun canvasUnder(d: Dab, out: FloatArray): Boolean {
        val r = d.radius
        if (!(r > 0f) || !r.isFinite()) return false
        var wSum = 0f
        var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            // A GRID x GRID lattice over the tip's bounding square, sample points at the cell centres.
            val dx = ((gx + 0.5f) / GRID * 2f - 1f) * r
            val dy = ((gy + 0.5f) / GRID * 2f - 1f) * r
            val w = TipMath.coverage(dx, dy, r, d.angle, tip)
            if (!(w > 0f)) continue
            val px = floor(d.x + dx).toInt()
            val py = floor(d.y + dy).toInt()
            val tx = tileOf(px)
            val ty = tileOf(py)
            val key = Tiles.key(tx, ty)
            val tile = if (cache.containsKey(key)) cache[key] else reader.tile(tx, ty).also { cache[key] = it }
            if (tile == null) { wSum += w; continue }   // an empty tile is transparent pixels, which still count as "under the tip"
            val i = ((py - ty * Tiles.SIZE) * Tiles.SIZE + (px - tx * Tiles.SIZE)) * 4
            sr += w * (tile[i].toInt() and 0xFF)
            sg += w * (tile[i + 1].toInt() and 0xFF)
            sb += w * (tile[i + 2].toInt() and 0xFF)
            sa += w * (tile[i + 3].toInt() and 0xFF)
            wSum += w
        }
        if (!(wSum > 0f) || !(sa > 0f)) return false
        val k = 1f / (255f * wSum)
        // A mean of values in 0..1 is in 0..1; in Float it can land a hair past (1.0000001), which SmudgeCarried refuses.
        out[0] = (sr * k).coerceIn(0f, 1f); out[1] = (sg * k).coerceIn(0f, 1f)
        out[2] = (sb * k).coerceIn(0f, 1f); out[3] = (sa * k).coerceIn(0f, 1f)
        return true
    }

    private fun tileOf(v: Int): Int = if (v >= 0) v / Tiles.SIZE else -((-v + Tiles.SIZE - 1) / Tiles.SIZE)

    companion object {
        /** Samples per side. Odd, so the tip's centre is a sample; 49 per dab is nothing next to what a dab costs to draw. */
        const val GRID = 7
    }
}
