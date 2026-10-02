package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.fill.MaskPaint
import cc.joycreator.joybrush.core.fill.MaskPaintMode
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.tipShape
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Dabs and a polygon, rasterised into a plain byte array. No GL, no phone, no window.
 *
 * THE LAYOUT IS the tile layout, because it is the layout every consumer already speaks:
 * PREMULTIPLIED RGBA8, `width * height * 4` bytes, row 0 = the destination's TOP row. That is
 * `RegionRenderer.renderPremultiplied`'s arrangement exactly, so an ink export and a document
 * export are the same bytes read the same way.
 *
 * ## THE ONE IDEA: ZOOM CHANGES THE GRID, NOT THE MARKS
 *
 * An ink line is a recording ([InkReplay]), and at 16x it is DRAWN AGAIN rather than magnified. So
 * nothing about the tessellation depends on the zoom — dabs stay at their document-space spacing —
 * and the zoom only changes how finely the tip's coverage is sampled. Consecutive dabs of radius `r`
 * are `spacing * 2r` doc px apart, which leaves a scallop at the midpoint of depth
 * `r - sqrt(r^2 - (d/2)^2) ~= r * spacing^2 / 2`. Ink ships `spacing = 0.04`, so that is
 * `r * 0.0008`, or `0.0004 * diameter` — for Ink's `size.base = 6`, 0.0024 doc px, and 0.038
 * screen px at 16x: a fortieth of a pixel. A re-raster therefore has no visible seam, and a
 * magnified bitmap always does, because a bitmap's edge is a staircase. That asymmetry is the whole
 * reason this file exists.
 *
 * ## WHAT IS NOT WRITTEN HERE
 *
 * The tip coverage ([TipMath]), the accumulation (`s' = cap * d + s * (1 - d)`), the commit
 * (`out = colour * a + dst * (1 - a)`, `a = s * k`), the tile layout and the non-zero fill rule
 * ([SelectionMask] + [MaskPaint]) ALL exist already. This file wires them to a destination rectangle
 * and a scale, and owns no arithmetic of its own — [premultiplied] is the single place the
 * accumulation and the commit are written, and [stamps] is that plus a quantisation, so a second
 * rule cannot grow up beside the first. `InkRasterTest` drives
 * [cc.joycreator.joybrush.core.paint.RefCanvas] by hand and demands element-for-element equality,
 * which is what keeps that true.
 *
 * The commit factor is `k = alphaOf(colorRgb) * (if (accumulate == BUILD_UP) opacity else 1f)`.
 * The `BUILD_UP` half is [cc.joycreator.joybrush.core.paint.RefCanvas.endStroke]'s; the alpha half is
 * here because a [cc.joycreator.joybrush.core.stroke.StrokeRecord] carries its colour with an alpha
 * and there is nowhere else for that byte to be read.
 */
object InkRaster {

    /**
     * Premultiplied RGBA8 bytes, `width * height * 4`, row 0 = the destination's TOP row.
     *
     * @param docX,docY the destination rectangle's top-left in DOCUMENT px. Every dab is placed at
     *   `((dab.x - docX) * scale, (dab.y - docY) * scale)` in destination px and drawn with radius
     *   `dab.radius * scale`; destination pixel `(px, py)` samples the document at
     *   `(docX + (px + 0.5) / scale, docY + (py + 0.5) / scale)`.
     * @return `null` for a destination of more than [MAX_REGION_PX] pixels, a non-finite or
     *   non-positive `scale`, or a `width`/`height` that overflows `Int` when multiplied.
     * @throws IllegalArgumentException if `width` or `height` is negative (a caller bug, refused in
     *   words exactly as `RegionRenderer.requireSize` refuses it).
     */
    fun stamps(
        dabs: List<Dab>,
        tip: TipShape,
        accumulate: Accumulate,
        opacity: Float,
        colorRgb: Int,
        docX: Int,
        docY: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): ByteArray? {
        val p = premultiplied(
            dabs, tip, accumulate, opacity, colorRgb, docX, docY, width, height, scale,
        ) ?: return null
        val out = ByteArray(p.size)
        for (i in p.indices) {
            // RegionRenderer.toByte255, exactly: `(v * 255f + 0.5f)` truncates toward zero, which
            // is round-half-up for a non-negative v, and the clamp is what a premultiplied colour
            // brighter than its own alpha would otherwise wrap into a dark pixel.
            out[i] = (p[i] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /**
     * [stamps] in premultiplied 0..1 floats — Decision 6's test seam, and the only place the
     * accumulation and the commit are written down. [stamps] is this plus a quantisation.
     */
    internal fun premultiplied(
        dabs: List<Dab>,
        tip: TipShape,
        accumulate: Accumulate,
        opacity: Float,
        colorRgb: Int,
        docX: Int,
        docY: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): FloatArray? {
        val count = pixelCount(width, height, scale) ?: return null
        if (count == 0L) return FloatArray(0)
        val px = FloatArray(count.toInt() * 4)

        val cr = channelOf(colorRgb, 16)
        val cg = channelOf(colorRgb, 8)
        val cb = channelOf(colorRgb, 0)
        // A NaN opacity is 1, the same rule `MaskPaint.opacityOf` uses and for the same reason: a
        // NaN that silently multiplied nothing would look exactly like a stroke at 0%.
        val o = if (opacity.isNaN()) 1f else opacity
        val k = alphaOf(colorRgb) * (if (accumulate == Accumulate.BUILD_UP) o else 1f)

        // The stroke buffer IS the destination: an ink raster is drawn onto transparent paper, so
        // `dst` is 0 for every pixel and `dst * (1 - a)` is 0. The commit is therefore
        // `colour * a` with `a = s * k`, which is RefCanvas.endStroke's formula with a destination
        // that is all zeroes. It is done IN PLACE: one array instead of two, which at the budget is
        // the difference between 128 MiB of scratch and 64.
        for (d in dabs) stampInto(px, width, height, d, tip, docX, docY, scale)

        for (i in 0 until px.size / 4) {
            val a = px[i * 4 + 3] * k
            px[i * 4] = cr * a
            px[i * 4 + 1] = cg * a
            px[i * 4 + 2] = cb * a
            px[i * 4 + 3] = a
        }
        return px
    }

    /**
     * The fill pen's shape: the closed polygon [cc.joycreator.joybrush.core.brush.FillPen.outline]
     * gives, filled NON-ZERO, so a loop drawn twice is still solid. The same `docX, docY, scale` and
     * the same return contract as [stamps]; `colorRgb`'s own alpha byte is the opacity.
     *
     * NO ARITHMETIC OF ITS OWN. The rule is [SelectionMask.polygon]'s (JB-2.05a, the repo's one
     * non-zero winding fill) and the composite is [MaskPaint.pixel]'s (JB-2.07a, the repo's one
     * fill composite); this only decides which DOCUMENT pixel each destination pixel asks about,
     * which is the address and nothing more.
     *
     * Two consequences that are DECISIONS rather than surprises, because a caller must be able to
     * predict them:
     *  - A polygon of fewer than three points is nothing, and so is one whose bounding box is wider
     *    than `SelectionMask.MAX_SELECT_SPAN` (16 384 doc px) — `polygon` answers
     *    [SelectionMask.EMPTY] for it. The fill pen is therefore refused by the LASSO's cap; the
     *    ceiling is the repo's, not this file's.
     *  - [MaskPaint.pixel] ignores `argb`'s own alpha and takes `opacity` separately, so
     *    `fill` passes `opacity = alphaOf(colorRgb)`. A transparent colour therefore fills nothing.
     */
    fun fill(
        outline: List<Pt>,
        colorRgb: Int,
        docX: Int,
        docY: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): ByteArray? {
        val count = pixelCount(width, height, scale) ?: return null
        if (count == 0L) return ByteArray(0)
        val mask = SelectionMask.polygon(outline)
        val out = ByteArray(count.toInt() * 4)
        if (mask.isEmpty) return out

        val dest = IntArray(4)
        val painted = IntArray(4)
        val opacity = alphaOf(colorRgb)
        for (row in 0 until height) {
            val dy = documentPx(docY, row, scale)
            for (column in 0 until width) {
                val coverage = mask.coverage(documentPx(docX, column, scale), dy)
                if (coverage == 0) continue
                MaskPaint.pixel(MaskPaintMode.FILL, colorRgb, opacity, coverage, dest, painted)
                val at = (row * width + column) * 4
                out[at] = painted[0].toByte()
                out[at + 1] = painted[1].toByte()
                out[at + 2] = painted[2].toByte()
                out[at + 3] = painted[3].toByte()
            }
        }
        return out
    }

    // ── the arithmetic, in one place ──────────────────────────────────────────────────────

    /**
     * One dab, accumulated into the destination's own alpha slot: `s' = cap * d + s * (1 - d)`
     * with `d = coverage * flow` ([cc.joycreator.joybrush.core.paint.Dab]'s KDoc and
     * [Accumulate]'s, and [cc.joycreator.joybrush.core.paint.RefCanvas.stamp]'s line).
     *
     * The dabs are visited IN ORDER and each one writes the pixels it touches, so a pixel touched by
     * three dabs sees them in stroke order — which is the same order `RefCanvas.addDabs` sees them,
     * because its tile bucketing preserves stroke order inside a tile and every dab that reaches a
     * given pixel reaches it in that order whatever the tiles were. That is what makes the two
     * float-identical rather than merely equal-looking.
     *
     * The origin is a SUBTRACTION and never a modulo: the dab's document position becomes a
     * DESTINATION position `((dab.x - docX) * scale, …)`, and the offset a pixel is tested at is
     * `px + 0.5 - that`. A dab at document (1 000 003, 200 000) with `docX = 1 000 000` therefore
     * lands on exactly the bytes a dab at (3, 0) with `docX = 0` lands on, and a 32-bit document
     * coordinate does not wrap — which it would if the addressing went through a tile key.
     */
    private fun stampInto(
        px: FloatArray,
        width: Int,
        height: Int,
        d: Dab,
        tip: TipShape,
        docX: Int,
        docY: Int,
        scale: Float,
    ) {
        // A dab with no place in the world is SKIPPED, not clamped: there is no nearby pixel to
        // clamp it to, and one NaN coordinate reaching `TipMath.coverage` would make every pixel it
        // touched NaN, which then quantises to 0 and paints a hole through a line that was fine.
        if (!d.x.isFinite() || !d.y.isFinite()) return
        val radius = radiusPx(d.radius, scale)
        if (radius <= 0f) return

        val cx = (d.x - docX) * scale
        val cy = (d.y - docY) * scale
        if (!cx.isFinite() || !cy.isFinite()) return
        val liveTip = d.tipShape(tip)
        val e = TipMath.extent(radius, liveTip.anchor)
        val x0 = max(floor(cx - e).toInt(), 0)
        val x1 = min(floor(cx + e).toInt(), width - 1)
        val y0 = max(floor(cy - e).toInt(), 0)
        val y1 = min(floor(cy + e).toInt(), height - 1)
        if (x0 > x1 || y0 > y1) return

        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) {
                // Pixel centres, as the GPU and RefCanvas both sample them, and the same two
                // quantities the shader forms: the tip's coverage at this offset, scaled by the
                // dab's flow. BOTH the offset and the radius are in DESTINATION pixels, which is
                // what `TipMath.coverage` needs: its antialiasing is `d(dx + 1) - d(dx)`, a step of
                // ONE PIXEL, and a pixel at 0.25x is four document pixels wide. Feeding it document
                // offsets with a destination radius would make the edge four times too soft and the
                // "same tip, finer grid" claim false.
                val cov = TipMath.coverage(x + 0.5f - cx, y + 0.5f - cy, radius, d.angle, liveTip) * d.flow
                if (cov <= 0f) continue
                // The stroke's own running value `s` LIVES in the destination's alpha slot, which is
                // where the commit reads it from a few lines later. `RefCanvas` keeps it in a
                // separate one-float-per-pixel buffer and spreads it into four channels at
                // `endStroke`; putting it in the alpha slot is the same arithmetic with one array
                // instead of two, and at the budget that is the difference between 128 MiB of
                // scratch and 64. The stride is the pixel's FOUR, not the pixel's one: `s` is a
                // coverage, not a colour channel.
                val at = (row + x) * 4 + 3
                px[at] = d.cap * cov + px[at] * (1f - cov)
            }
        }
    }

    // ── the doors, and the bad numbers ────────────────────────────────────────────────────

    /**
     * How many pixels the destination holds, or null for a request this cannot answer.
     *
     * FOUR refusals, and they are four different facts, kept apart exactly as
     * `RegionRenderer.requireSize` keeps its four apart:
     *
     *  - a NEGATIVE side throws [IllegalArgumentException], because that is a caller bug and a
     *    silent empty image would hide it;
     *  - a `scale` that is not a positive finite number returns null, because a NaN scale makes
     *    every destination coordinate NaN and answers a region full of nothing;
     *  - a destination of more than [MAX_REGION_PX] pixels returns null, checked in `Long` on the
     *    PRODUCT so it cannot wrap, because a declared size is a wish and this is a MEMORY budget —
     *    the same one [cc.joycreator.joybrush.core.render.RegionRenderer] enforces, REFERENCED rather
     *    than copied, and one check on the pixel count bounds every size derived from it because
     *    `px * 4` floats is the largest of them and `MAX_REGION_PX * 4` already fits an [Int];
     *  - a side of zero is ALLOWED and returns 0, because a region of no size is a legal thing to
     *    ask about — the same answer `RegionRenderer.render` gives.
     */
    private fun pixelCount(width: Int, height: Int, scale: Float): Long? {
        require(width >= 0 && height >= 0) { "a destination cannot be $width by $height" }
        if (!scale.isFinite() || scale <= 0f) return null
        val px = width.toLong() * height.toLong()
        if (px > MAX_REGION_PX) return null
        return px
    }

    /** A dab's radius in destination px, made safe: a number that is not a number draws nothing. */
    private fun radiusPx(radius: Float, scale: Float): Float {
        if (!radius.isFinite() || radius <= 0f) return 0f
        val r = radius * scale
        return if (r.isFinite()) r else 0f
    }

    /**
     * The document pixel destination column [px] samples: `docX + (px + 0.5) / scale`, floored to
     * the pixel that contains it.
     *
     * FLOORED, and written with a Double on purpose. The sum is done in Double so a `docX` of
     * 1 000 000 and a `scale` of 0.25 do not lose the half-pixel in Float's 24 bits of significand
     * before the floor decides which document pixel that is.
     */
    private fun documentPx(docOrigin: Int, index: Int, scale: Float): Int =
        floor(docOrigin.toDouble() + (index + 0.5) / scale.toDouble()).toInt()

    /** The straight sRGB channel [shift] bits up of a packed colour, 0..1. */
    private fun channelOf(rgb: Int, shift: Int): Float = ((rgb shr shift) and 0xFF) / 255f

    /** A packed colour's own alpha byte, 0..1 — the only per-record alpha a [StrokeRecord] has. */
    private fun alphaOf(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
}
