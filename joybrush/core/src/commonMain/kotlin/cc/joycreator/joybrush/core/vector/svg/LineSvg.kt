package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import cc.joycreator.joybrush.core.vector.LineShaper
import cc.joycreator.joybrush.core.vector.LineStyle

/**
 * Recorded lines to one SVG: each [StrokeRecord] is replayed ([InkReplay.dabs]), traced
 * ([StrokeOutline]) and written as ONE `<path>` ([SvgWriter]). Records and slabs use their persisted
 * `seq` order unless [export]'s `seqOf` says otherwise. Equal sequences retain input order.
 *
 * ## Scatter
 *
 * The outline is drawn around the PLACED dabs — the ones the dabber put on the pen's path, before
 * [cc.joycreator.joybrush.core.brush.Scatter] sprays them — so a scattered brush exports the
 * envelope of its path, not its leaves (an outline of leaves would be one shape per leaf, which is
 * the per-dab export R8 rules out). `InkReplay` does not expose its placed list, but it does not need
 * to: replaying with a [ScatterSpec] of amount 0 and count 1 makes `Scatter.expand` hand every placed
 * dab back unchanged (its `shifted` returns the source dab for a zero offset), and the dabber never
 * reads the scatter spec. Such strokes are listed in [Result.approximated].
 *
 * ## Opacity
 *
 * Flat, as an SVG fill must be: the colour's alpha byte × the brush opacity. For `buildup` brushes the
 * opacity is `opacity.base`, applied once at commit (`InkRaster`'s `k`). For `wash` brushes it is the
 * highest per-dab `cap` the stroke reached — the ceiling the stroke builds to, which follows a pressure
 * curve on opacity if the brush has one; a stroke whose caps vary exports at its darkest.
 *
 * ## Not exported (listed in [Result.skipped], with the reason)
 *
 * Erase lines (no SVG fill removes alpha; see [SvgWriter]), `behind` lines (drawing under what is
 * already there needs the earlier items reordered, which is only right while every one of them is a
 * plain normal fill), other blend modes than normal and multiply, image tips (the outline traces the
 * procedural superellipse, which is not that tip's shape) and every engine `InkReplay` refuses.
 */
object LineSvg {

    /** A record not written, by its index in the input list. */
    data class Skipped(val index: Int, val strokeId: String, val reason: String)

    /**
     * @property approximated records written, but less than exactly (scatter flattened to its path).
     * @property erasedLines erase lines left out (also in [skipped]).
     */
    data class Result(
        val svg: String,
        val skipped: List<Skipped>,
        val approximated: List<Skipped>,
        val erasedLines: Int,
    )

    /**
     * @param runsOf splits one stroke's dabs into the runs drawn; the default uses the brush's
     *   line dash settings. Every run becomes a sub-path of that stroke's single `<path>`.
     * @param slabs pixel slabs to interleave by `seq`.
     */
    fun export(
        width: Double,
        height: Double,
        records: List<StrokeRecord>,
        brushLookup: (String) -> BrushPreset?,
        seqOf: (index: Int, record: StrokeRecord) -> Long = { _, record -> record.seq },
        slabs: List<SvgWriter.Slab> = emptyList(),
        runsOf: (record: StrokeRecord, brush: BrushPreset, dabs: List<Dab>) -> List<List<Dab>> = ::shapedRuns,
    ): Result {
        cc.joycreator.joybrush.core.stroke.CapturedStrokeRules.requireOrdinary(records)
        val items = ArrayList<SvgWriter.Item>(records.size + slabs.size)
        val skipped = ArrayList<Skipped>()
        val approximated = ArrayList<Skipped>()
        var erased = 0
        for ((index, record) in records.withIndex()) {
            val brush = brushLookup(record.brushId)
            val why = InkReplay.refusal(record, brush) ?: when {
                brush!!.engine == ENGINE_WATERCOLOUR ->
                    "stroke \"${record.id}\" is asset-rendered watercolor and needs image export; SVG outlines cannot preserve its dab PNGs or pooling"
                (brush!!.tip.source != "procedural" || brush.tip.sampled != null) && brush.engine != ENGINE_FILL ->
                    "stroke \"${record.id}\" uses an image tip; an outline can only follow a procedural tip"
                brush!!.color.dynamics?.isActive == true ->
                    "stroke \"${record.id}\" varies its colour and needs image export; one SVG fill cannot preserve it"
                brush!!.tip.dual != null || brush.tip.quad != null || brush.tip.profile != null || brush.tip.fade != null ->
                    "stroke \"${record.id}\" uses Unibrush shape or softness and needs image export; this SVG outline cannot preserve it"
                blendOf(brush.blend) == null ->
                    "stroke \"${record.id}\" uses blend \"${brush.blend}\", which this export cannot write as an SVG fill"
                blendOf(brush.blend) == SvgWriter.Blend.ERASE ->
                    "stroke \"${record.id}\" erases; SVG has no fill that removes what is below it"
                else -> null
            }
            if (why != null) {
                if (brush != null && blendOf(brush.blend) == SvgWriter.Blend.ERASE) erased++
                skipped.add(Skipped(index, record.id, why))
                continue
            }
            val preset = brush!!
            val seq = seqOf(index, record)
            val blend = blendOf(preset.blend)!!
            if (preset.engine == ENGINE_FILL) {
                items.add(SvgWriter.Fill(seq, record.id,
                    FillPen.outline(record.samples, record.smoothing, record.screenPerDoc),
                    record.colorArgb, alphaOf(record.colorArgb), blend))
                continue
            }
            if (scatters(preset)) {
                approximated.add(Skipped(index, record.id,
                    "stroke \"${record.id}\" scatters; its outline follows the unscattered path"))
            }
            val dabs = placedDabs(record, preset)
            val contours = StrokeOutline.strokeContours(runsOf(record, preset, dabs), tipOf(preset))
            items.add(SvgWriter.Line(seq, record.id, contours, record.colorArgb,
                alphaOf(record.colorArgb) * opacityOf(preset, dabs), blend))
        }
        items.addAll(slabs)
        val written = SvgWriter.write(width, height, items)
        return Result(written.svg, skipped, approximated, erased + written.skippedErase)
    }

    /**
     * The dabs [record] places on its path, in stroke order, before scatter — `InkReplay.dabs` with
     * scatter and dash filtering turned off (see the class note). Taper and width scale remain;
     * dashRuns is applied once in [shapedRuns], preserving each dash as one closed sub-path.
     */
    fun placedDabs(record: StrokeRecord, brush: BrushPreset): List<Dab> {
        val line = brush.line?.copy(dashOn = 0f, dashOff = 0f)
        return InkReplay.dabs(record, brush.copy(scatter = ScatterSpec(), line = line))
    }

    private fun shapedRuns(record: StrokeRecord, brush: BrushPreset, dabs: List<Dab>): List<List<Dab>> {
        val widthScale = if (record.widthScale.isFinite()) record.widthScale else 1f
        return LineShaper.dashRuns(dabs, LineStyle.of(brush.line), brush.size.base * widthScale)
    }

    /** The stroke's tip, as `InkTiles` builds it for the CPU raster (live channels come from each dab). */
    fun tipOf(p: BrushPreset): TipShape = TipShape(p.tip.aspect, p.tip.corner, p.tip.taper, p.tip.hardness.base, p.tip.minPx)

    private fun scatters(p: BrushPreset): Boolean = p.scatter.amount.base != 0f || p.scatter.amount.inputs.isNotEmpty()

    private fun opacityOf(p: BrushPreset, dabs: List<Dab>): Float {
        val o = if (p.accumulate == "buildup") p.opacity.base
        else dabs.maxOfOrNull { if (it.cap.isFinite()) it.cap else 0f } ?: p.opacity.base
        return if (o.isNaN()) 1f else o.coerceIn(0f, 1f)
    }

    private fun blendOf(name: String): SvgWriter.Blend? = when (name) {
        "normal" -> SvgWriter.Blend.NORMAL
        "multiply" -> SvgWriter.Blend.MULTIPLY
        "erase" -> SvgWriter.Blend.ERASE
        else -> null
    }

    private fun alphaOf(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
}
