package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.tipShape
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** CPU tile rebuild. Records keep list order; duplicate ids do not identify or reorder items. */
object InkTiles {
    data class Refusal(val index: Int, val strokeId: String, val reason: String)
    data class Result(val pixels: ByteArray?, val refusals: List<Refusal>)

    class Prepared internal constructor(internal val marks: List<Mark>, val refusals: List<Refusal>)
    internal class Mark(val record: StrokeRecord, val brush: BrushPreset, val dabs: List<Dab>, val outline: List<Pt>)

    /** Replay once for a cel, then rebuild several tiles without repeating smoothing and placement. */
    fun prepare(records: List<StrokeRecord>, brushLookup: (String) -> BrushPreset?): Prepared {
        val marks = ArrayList<Mark>()
        val refused = ArrayList<Refusal>()
        for ((index, record) in records.withIndex()) {
            val brush = brushLookup(record.brushId)
            val why = InkReplay.refusal(record, brush) ?: when {
                brush!!.tip.source != "procedural" -> "stroke \"${record.id}\" uses an image tip; CPU ink rendering has no image source"
                brush.tipTexture.enabled -> "stroke \"${record.id}\" uses tip grain; CPU ink rendering cannot reproduce it"
                blendMode(brush.blend) == null && brush.blend != "behind" ->
                    "stroke \"${record.id}\" uses unsupported blend \"${brush.blend}\""
                else -> null
            }
            if (why != null) {
                refused.add(Refusal(index, record.id, why))
                continue
            }
            val preset = brush!!
            marks.add(Mark(record, preset, InkReplay.dabs(record, preset),
                if (preset.engine == ENGINE_FILL) FillPen.outline(record.samples, record.smoothing, record.screenPerDoc)
                else emptyList()))
        }
        return Prepared(marks, refused)
    }

    private fun blendMode(name: String): BlendMode? =
        if (name == "erase") BlendMode.ERASE_BELOW
        else BlendMode.entries.firstOrNull { it.name.lowercase() == name }

    fun render(records: List<StrokeRecord>, tx: Int, ty: Int, brushLookup: (String) -> BrushPreset?): Result =
        render(prepare(records, brushLookup), tx, ty)

    /** A transparent tile is null, with refusals still present. No store, GL or undo ownership. */
    fun render(cel: Prepared, tx: Int, ty: Int): Result {
        origin(tx, ty) // a tile outside document space is refused even when nothing would reach it
        val px = FloatArray(RegionRenderer.TILE_BYTES)
        for (mark in cel.marks) buffer(mark, tx, ty)?.let { applyOp(mark.brush.blend, it, px) }
        return Result(quantise(px), cel.refusals)
    }

    // ── the shared steps (JB-5.20 D8a): the composer draws lines with exactly these, so a baked line is the same pixels ──

    /** The tile's top-left in document px, refused when the tile leaves Int document space. */
    internal fun origin(tx: Int, ty: Int): Pair<Int, Int> {
        val x = tx.toLong() * TILE_SIZE
        val y = ty.toLong() * TILE_SIZE
        require(x in Int.MIN_VALUE.toLong()..(Int.MAX_VALUE.toLong() - TILE_SIZE + 1) &&
            y in Int.MIN_VALUE.toLong()..(Int.MAX_VALUE.toLong() - TILE_SIZE + 1)) { "Ink tile is outside document coordinates" }
        return x.toInt() to y.toInt()
    }

    /** Whether [mark] can reach tile ([tx], [ty]). Conservative; pixel coverage is still InkRaster's exact rule. */
    internal fun reaches(mark: Mark, tx: Int, ty: Int): Boolean {
        val (x, y) = origin(tx, ty)
        val p = mark.brush
        if (p.engine == ENGINE_FILL) {
            return mark.outline.isNotEmpty() && mark.outline.maxOf { it.x } >= x && mark.outline.minOf { it.x } <= x + TILE_SIZE &&
                mark.outline.maxOf { it.y } >= y && mark.outline.minOf { it.y } <= y + TILE_SIZE
        }
        val tip = tipOf(p)
        return mark.dabs.any { dab ->
            val extent = TipMath.extent(dab.radius, dab.tipShape(tip).anchor) + 1f
            dab.x + extent >= x && dab.x - extent <= x + TILE_SIZE &&
                dab.y + extent >= y && dab.y - extent <= y + TILE_SIZE
        }
    }

    /**
     * [mark]'s own stroke buffer on tile ([tx], [ty]): premultiplied RGBA8 in the tile layout, before its operator is
     * applied; null when it cannot reach the tile. A line composites as ONE unit (D8a): this buffer, then [applyOp] once.
     */
    internal fun buffer(mark: Mark, tx: Int, ty: Int): ByteArray? {
        if (!reaches(mark, tx, ty)) return null
        val (x, y) = origin(tx, ty)
        val p = mark.brush
        val bytes = if (p.engine == ENGINE_FILL) InkRaster.fill(mark.outline, mark.record.colorArgb, x, y, TILE_SIZE, TILE_SIZE, 1f)
        else InkRaster.stamps(mark.dabs, tipOf(p),
            if (p.accumulate == "buildup") Accumulate.BUILD_UP else Accumulate.WASH,
            p.opacity.base, mark.record.colorArgb, x, y, TILE_SIZE, TILE_SIZE, 1f)
        return checkNotNull(bytes) { "A fixed-size ink tile must fit InkRaster's budget" }
    }

    /**
     * Applies [bytes] (premultiplied RGBA8, tile layout) to the float tile [px] with [op]: `normal`, `erase`, `behind` or
     * a blend mode's lower-case name. The one place a line's (or a baked line's slab's) operator is written.
     */
    internal fun applyOp(op: String, bytes: ByteArray, px: FloatArray) {
        val mode = if (op == "behind") BlendMode.NORMAL else requireNotNull(blendMode(op)) { "unsupported operator \"$op\"" }
        val s = FloatArray(4)
        val d = FloatArray(4)
        val o = FloatArray(4)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i + 3] == 0.toByte()) { i += 4; continue }
            for (c in 0..3) { s[c] = (bytes[i + c].toInt() and 255) / 255f; d[c] = px[i + c] }
            if (op == "behind") Blend.apply(mode, d, s, o) else Blend.apply(mode, s, d, o)
            for (c in 0..3) px[i + c] = o[c]
            i += 4
        }
    }

    /** Whether [op] is an operator [applyOp] knows. */
    internal fun knowsOp(op: String): Boolean = op == "behind" || blendMode(op) != null

    /** The float tile as bytes, rounded once; null when nothing in it is visible. */
    internal fun quantise(px: FloatArray): ByteArray? {
        val out = ByteArray(RegionRenderer.TILE_BYTES) { (px[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
        return if (out.indices.step(4).any { out[it + 3] != 0.toByte() }) out else null
    }

    private fun tipOf(p: BrushPreset) = TipShape(p.tip.aspect, p.tip.corner, p.tip.taper, p.tip.hardness.base, p.tip.minPx)
}
