package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Thrown when a recording cannot be replayed. The message is the whole error report. */
class InkReplayException(message: String) : Exception(message)

/**
 * An ink line's recording, turned back into the marks it drew.
 *
 * ON AN INK LAYER THE PIXELS ARE NOT THE TRUTH — THE RECORDING IS (blueprint §2). So the same line
 * zoomed to 16x is drawn again from [StrokeRecord] rather than magnified, and a finished line can
 * afterwards be given a different brush, width or colour (LEAD_RULINGS R20) simply by asking again.
 * This object is the pure half of that: no GL, no phone, no screen, no frame. It is two functions
 * of `(record, brush)`, and both are testable on any JVM.
 *
 * ## THE ONE PROMISE
 *
 * **The same recording draws the same marks, every time, forever** — that is the whole contract, and
 * it is what `StrokeRecord.seed` and [cc.joycreator.joybrush.core.brush.SplitMix] exist to keep. Two
 * calls with the same arguments give field-identical [Dab] lists, and nothing about the current zoom,
 * the screen or the device enters the answer. There is no clock, no counter and no `Random` in this
 * file, and none may be added: a stroke that draws differently each time it is opened is not a
 * recording, it is a suggestion.
 *
 * ## WHY THE REPLAY IS A TRANSCRIPTION AND NOT A DESIGN
 *
 * Every step below is one the live drawing path already takes, in the order
 * `JbCanvasView.startStroke` / `JbCanvasView.feed` / `JbCanvasView.paint` takes it, with the same
 * objects and the same values. A replay that "improved" any of them would draw a different line
 * from the one it is supposed to be replaying, and there would be no way to tell which of the two is
 * right. So: a [StrokeSmoother] fed one sample at a time and finished (the CLASS, not
 * `StrokeSmoother.smoothAll`, so `droppedSamples` is readable); one [BrushDabber] seeded with
 * `record.seed`; one [DabPlacer] taking that dabber's `spacing` and its `look`; and ONE
 * [Scatter.expand] over the whole dab list with `SplitMix(record.seed xor Scatter.SALT)`. The scatter
 * is fed the whole stroke at once rather than batch by batch because every draw a scatter makes is in
 * the same order whatever the batches were, so the leaves are the same — which is what
 * `JbCanvasView.paint`'s own note says.
 *
 * ## WHAT THIS FILE DOES NOT KNOW
 *
 * Not the eraser, not the picker, not GL, not the zoom. The destination rectangle and the scale
 * belong to [InkRaster], which is a different question: replay asks "which marks?", raster asks
 * "which pixels?".
 */
object InkReplay {

    /**
     * The dabs one recording draws, in stroke order, exactly as the pen drew them the first time.
     *
     * Tessellates in DOCUMENT space using [record]'s own `smoothing` and `screenPerDoc`, seeds the
     * dabber and the scatter stream with [StrokeRecord.seed], and expands the scatter through the
     * same [Scatter] the drawing view used with the same [Scatter.SALT].
     *
     * Each dab's radius is multiplied by [StrokeRecord.widthScale] (Decision 9).
     *
     * `engine == "fill"` records draw NO dabs — a fill is a shape, and its raster is
     * [InkRaster.fill]'s business. Every other engine than "stamp" or "fill" is REFUSED by name
     * (Decision 6).
     *
     * There is **no dab cap** (Decision 14). Capping dabs means refusing a stroke somebody drew, and
     * that belongs on `spacing` if it belongs anywhere, not on the ink path. The count is bounded by
     * the placer instead: `DabPlacer.emit` returns `max(2r * spacing, minSpacingPx)` and
     * `minSpacingPx` defaults to 0.5 doc px, so a stroke of `L` doc px can never place more than
     * `L / 0.5` dabs however small the brush is.
     *
     * @throws InkReplayException if `brush` is null, or its engine cannot draw an ink line.
     */
    fun dabs(record: StrokeRecord, brush: BrushPreset?): List<Dab> {
        refusal(record, brush)?.let { throw InkReplayException(it) }
        val preset = brush!!
        // A fill is a SHAPE, not a row of dabs: its raster is `InkRaster.fill` over
        // `FillPen.outline(record.samples, record.smoothing, record.screenPerDoc)`. Asking the
        // dabber for dabs here would draw the fill pen as a line of beads, which is the outline of
        // the shape and not the shape.
        if (preset.engine == ENGINE_FILL) return emptyList()

        val placed = placedDabs(record, preset)
        val scattered = Scatter.expand(
            placed,
            preset.scatter,
            SplitMix(record.seed xor Scatter.SALT),
        ) { dab -> dabInputsOf(dab) }
        return rescaled(scattered, widthScaleOf(record.widthScale))
    }

    /**
     * Every problem that stops [record] from being drawn, in words. Empty = it draws.
     *
     * The same sentence [dabs] throws, without the throw, for a UI that wants to grey a stroke out
     * before the person taps it. Two sentences exist and they are written ONCE, here: a refusal a
     * person can read and a refusal a test can catch are the same words, and a file that grew two
     * would grow two opinions about the same fact.
     */
    fun refusal(record: StrokeRecord, brush: BrushPreset?): String? = when {
        // Not drawn as a default nib. A line that suddenly appears in the wrong brush is worse than
        // a line that is visibly missing and says why.
        brush == null ->
            "stroke \"${record.id}\" was drawn with brush \"${record.brushId}\", " +
                "which is not in this document's library"
        // `smudge` and `wet` read the pixels UNDERNEATH, and an ink layer has none to read — it has
        // recordings. `StrokeEdit.INK_ENGINES` is the one place that says WHICH engines may, so a
        // fifth engine is a change in one file rather than a change in a list written twice.
        !StrokeEdit.drawsInkLines(brush.engine) ->
            "stroke \"${record.id}\" was drawn with a \"${brush.engine}\" brush, which cannot draw " +
                "an ink line: it reads the pixels underneath, and an ink layer has none"
        else -> null
    }

    // ── the live path, walked in the live path's own order ─────────────────────────────

    /**
     * Smoothing, dabbing and placement: the first three steps of the live path and no more.
     *
     * `add` per sample and `finish` at the end, not `StrokeSmoother.smoothAll`, because the
     * streaming form is the one `JbCanvasView.feed` uses and `smoothAll` is documented to be
     * equivalent — so the two are the same answer, and the streaming one is the one that can be
     * asked how many samples were dropped. A recording that lost a sample to a corrupt file draws
     * the rest of the line and says so, rather than drawing nothing or throwing away 999 good
     * samples over one bad float.
     */
    private fun placedDabs(record: StrokeRecord, preset: BrushPreset): List<Dab> {
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val out = ArrayList<PenSample>(record.samples.size)
        for (s in record.samples) out.addAll(smoother.add(s))
        out.addAll(smoother.finish())
        if (out.isEmpty()) return emptyList()
        val dabber = BrushDabber(preset, record.seed)
        return DabPlacer(spacing = dabber.spacing, look = dabber::look).add(out)
    }

    /**
     * What [Scatter] gets to ask about one dab, and it is `JbCanvasView.dabInputsOf` VERBATIM.
     *
     * A [Dab] carries only [Dab.pressure], so every other input is unknowable from a replay. Tilt,
     * speed, direction, lean, distance, random, strokeRandom and barrel are all `NaN`, which the
     * curves read as "not available" and skip — so a scatter curve on tilt contributes its BASE
     * rather than a number invented here. Inventing real values would make a replayed stroke
     * scatter differently from the one that was drawn, which is the one thing this file must never
     * do. It is eight lines of deliberate duplication and `InkReplayTest` pins the answer.
     */
    private fun dabInputsOf(dab: Dab) = DabInputs(
        pressure = dab.pressure,
        tilt = Float.NaN,
        speedPxPerS = Float.NaN,
        direction = Float.NaN,
        lean = Float.NaN,
        distancePx = Float.NaN,
        random = Float.NaN,
        strokeRandom = Float.NaN,
        barrel = Float.NaN,
    )

    /**
     * [StrokeRecord.widthScale] applied, because it exists and nothing else applies it.
     *
     * It is applied to each dab's RADIUS, at the very end, which is what makes
     * `DabPlacer`'s own `step = 2r * spacing` come out of the UNSCALED radius: a re-weighted line's
     * dabs are further apart as well as fatter, which is exactly what the live path would have done
     * with a bigger brush. Clamped to 0..[DabPlacer.MAX_RADIUS_PX] by the same guard
     * `DabPlacer.emit` applies and for the same reason — a file that was never validated must not
     * make a radius that cannot be allocated.
     *
     * A [StrokeRecord] is a PLAIN VALUE and its `widthScale` is not checked when the record is
     * built, so this is the point that reads it. A `widthScale` that is not a number is read as 0
     * (LEAD_RULINGS R1/R10: a broken number is read as a safe one, at the point that reads it), and
     * so is any radius, before the multiply rather than after.
     */
    private fun rescaled(dabs: List<Dab>, scale: Float): List<Dab> {
        if (scale == 1f) return dabs
        val out = ArrayList<Dab>(dabs.size)
        for (d in dabs) {
            val wanted = (if (d.radius.isFinite()) d.radius else 0f) * scale
            val radius = if (wanted.isFinite()) wanted.coerceIn(0f, DabPlacer.MAX_RADIUS_PX) else 0f
            out.add(if (radius == d.radius) d else d.copy(radius = radius))
        }
        return out
    }

    /** A `widthScale` that is not a number is read as 0, which draws the line as nothing. */
    private fun widthScaleOf(widthScale: Float): Float = if (widthScale.isFinite()) widthScale else 0f
}
