package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.shape.Pt

/**
 * One cel as one SVG document (VECTOR_RENDER_DESIGN §4; JB-5.20 D1): its lines, fill shapes and pixel
 * slabs in `seq` order, so what covers what in the file is what covered what on the canvas.
 *
 * - **A line is exactly one `<path>`** (R8): its outline's contours — one per dash, or per dot of a
 *   dotted line — are sub-paths of that one element, filled `fill-rule="nonzero"`, which is what keeps
 *   a self-overlapping turn solid (see [StrokeOutline]). The element's `id` is the record id.
 * - **Colour** is the record's straight sRGB as `fill="#rrggbb"`; `fill-opacity` is the line's flat
 *   opacity (the caller folds the colour's own alpha in, see [Line.opacity]).
 * - **Blend:** normal is plain; multiply is `style="mix-blend-mode:multiply"`, inside a group with
 *   `isolation:isolate` so it multiplies with the cel's own content below it and not with whatever the
 *   file is later placed on — the same as a line composited onto its own cel (D8a).
 * - **Erase lines are SKIPPED and counted** ([Result.skippedErase]). An erase line removes the alpha of
 *   what is below it; SVG has no fill that does that. The honest equivalent is a `<mask>` holding every
 *   earlier item minus the erase outline, re-nested at every erase in `seq` order — not written here. A
 *   file that silently drew erasers as black strokes, or dropped them without saying so, would be worse.
 * - **Fill shapes** are a closed polygon (FillPen's outline), `fill-rule="nonzero"`, one `<path>`.
 * - **Pixel slabs** are `<image>` elements carrying the caller's PNG as a base64 data URI, at their
 *   own `seq`, so a layer that mixes pixels and lines exports honestly (§4 "Pixels").
 *
 * Output is deterministic: no clock, no locale, no hash order; numbers are written by
 * [SvgText.num] (2 decimals). The same input gives byte-identical text. Units are document px:
 * `width`/`height` and the `viewBox` are the document size, so 1 user unit = 1 doc px.
 */
object SvgWriter {

    enum class Blend { NORMAL, MULTIPLY, ERASE }

    /** One thing in a cel, placed by [seq] (ascending; ties keep list order). */
    sealed class Item {
        abstract val seq: Long
    }

    /**
     * A line. [opacity] is the flat opacity of the whole outline, 0..1 (NaN reads as 1):
     * the colour's alpha byte × the brush opacity — [LineSvg] works it out from a record and brush.
     */
    data class Line(
        override val seq: Long,
        val id: String,
        val contours: List<StrokeOutline.Contour>,
        val colorArgb: Int,
        val opacity: Float,
        val blend: Blend = Blend.NORMAL,
    ) : Item()

    /** A filled shape (the fill pen, flood fill): a closed polygon in doc px, filled nonzero. */
    data class Fill(
        override val seq: Long,
        val id: String,
        val outline: List<Pt>,
        val colorArgb: Int,
        val opacity: Float,
        val blend: Blend = Blend.NORMAL,
    ) : Item()

    /** A pixel slab: [png] bytes drawn into the doc-px rectangle ([x], [y], [width], [height]). */
    class Slab(
        override val seq: Long,
        val id: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val png: ByteArray,
        val blend: Blend = Blend.NORMAL,
    ) : Item()

    /**
     * @property svg the document text (UTF-8 when encoded; it declares so).
     * @property skippedErase how many erase items were left out (see the class note).
     * @property written how many elements were written.
     */
    data class Result(val svg: String, val skippedErase: Int, val written: Int)

    fun write(width: Double, height: Double, items: List<Item>): Result {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\"")
        sb.append(" width=\"").append(SvgText.num(width)).append("\" height=\"").append(SvgText.num(height)).append('"')
        sb.append(" viewBox=\"0 0 ").append(SvgText.num(width)).append(' ').append(SvgText.num(height)).append("\">\n")
        sb.append("<g style=\"isolation:isolate\">\n")
        var skipped = 0
        var written = 0
        for (item in items.sortedBy { it.seq }) { // sortedBy is stable: equal seqs keep the caller's order
            val blend = when (item) { is Line -> item.blend; is Fill -> item.blend; is Slab -> item.blend }
            if (blend == Blend.ERASE) { skipped++; continue }
            when (item) {
                is Line -> {
                    if (item.contours.isEmpty()) continue
                    shape(sb, item.id, SvgText.pathData(item.contours), item.colorArgb, item.opacity, blend)
                }
                is Fill -> {
                    val d = SvgText.polygonData(item.outline)
                    if (d.isEmpty()) continue
                    shape(sb, item.id, d, item.colorArgb, item.opacity, blend)
                }
                is Slab -> {
                    sb.append("<image id=\"").append(SvgText.attr(item.id)).append('"')
                    sb.append(" x=\"").append(SvgText.num(item.x)).append("\" y=\"").append(SvgText.num(item.y)).append('"')
                    sb.append(" width=\"").append(SvgText.num(item.width)).append("\" height=\"").append(SvgText.num(item.height)).append('"')
                    sb.append(" preserveAspectRatio=\"none\"")
                    blendStyle(sb, blend)
                    sb.append(" xlink:href=\"data:image/png;base64,").append(SvgText.base64(item.png)).append("\"/>\n")
                }
            }
            written++
        }
        sb.append("</g>\n</svg>\n")
        return Result(sb.toString(), skipped, written)
    }

    private fun shape(sb: StringBuilder, id: String, d: String, argb: Int, opacity: Float, blend: Blend) {
        sb.append("<path id=\"").append(SvgText.attr(id)).append('"')
        sb.append(" fill=\"").append(SvgText.rgb(argb)).append('"')
        sb.append(" fill-opacity=\"").append(SvgText.opacity(opacity)).append('"')
        sb.append(" fill-rule=\"nonzero\"")
        blendStyle(sb, blend)
        sb.append(" d=\"").append(d).append("\"/>\n")
    }

    private fun blendStyle(sb: StringBuilder, blend: Blend) {
        if (blend == Blend.MULTIPLY) sb.append(" style=\"mix-blend-mode:multiply\"")
    }
}
