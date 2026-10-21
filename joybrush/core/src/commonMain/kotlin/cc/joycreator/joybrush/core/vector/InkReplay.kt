package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.StrokeColors
import cc.joycreator.joybrush.core.brush.SampledTipSet
import cc.joycreator.joybrush.core.brush.watercolourRuntime
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.grain.GrainMath
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
     * [InkRaster.fill]'s business. "watercolour" replays its own image-dab engine; every other
     * engine is REFUSED by name (Decision 6).
     *
     * Stamp brushes have **no dab cap** (Decision 14). Capping dabs means refusing a stroke somebody drew, and
     * that belongs on `spacing` if it belongs anywhere, not on the ink path. The count is bounded by
     * the placer instead: `DabPlacer.emit` returns `max(2r * spacing, minSpacingPx)` and
     * `minSpacingPx` defaults to 0.5 doc px, so a stroke of `L` doc px can never place more than
     * `L / 0.5` dabs however small the brush is.
     * Watercolour uses the same 6,000-stamp phone budget as its live path and the approved POC;
     * a recording that needs another stamp is refused, never returned as a truncated editable line.
     * Its own distance filtering/curves/scatter replace the generic smoother and line shaping.
     *
     * @throws InkReplayException if `brush` is null, its engine cannot draw an ink line,
     * or watercolor replay exceeds its dab budget.
     */
    fun dabs(record: StrokeRecord, brush: BrushPreset?, sampledTips: SampledTipSet? = null,
             dualMask: cc.joycreator.joybrush.core.brush.DualMask? = null): List<Dab> = replay(record, brush, sampledTips, dualMask).dabs

    /**
     * One replay: [Replayed.dabs], and the WHOLE-STROKE values the same [BrushDabber] fixed at the
     * first dab — [BrushDabber.strokeHardness] (the tip's hardness for a pre-contact brush, whose
     * dabs carry no hardness of their own) and [BrushDabber.strokeOpacity] (the commit opacity of a
     * `buildup` brush). `JbCanvasView.beginStrokeNow` hands the GPU exactly these two, so a CPU
     * raster that used the file's `base` values instead would draw a pressure-curved brush with the
     * wrong edge and the wrong strength. [Replayed.strokeGrain] retains that dabber's first-dab
     * grain depths too, rather than trying to recover them from shaped or scattered marks.
     * They come from THIS dabber, the one that drew the dabs,
     * rather than a second one asked again, so they cannot disagree with the dabs.
     *
     * A fill, or a recording with no surviving samples, never asks the dabber for a dab, so these are
     * the dabber's own starting values — the bases, which is also what the view would use.
     */
    class Replayed(val dabs: List<Dab>, val strokeHardness: Float, val strokeOpacity: Float,
                   val strokeGrain: GrainMath.StrokeGrain)

    /** [dabs] with its whole-stroke numbers; see [Replayed]. Same refusals, same throw. */
    fun replay(record: StrokeRecord, brush: BrushPreset?, sampledTips: SampledTipSet? = null,
               dualMask: cc.joycreator.joybrush.core.brush.DualMask? = null): Replayed {
        refusal(record, brush, sampledTips, dualMask)?.let { throw InkReplayException(it) }
        val preset = brush!!
        if(preset.engine == cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR) {
            val options=preset.watercolourRuntime()
            val watercolor=cc.joycreator.joybrush.core.brush.WatercolourDabber(options,record.seed)
            val dabs=watercolor.replay(record.samples)
            if (watercolor.capped) throw InkReplayException(
                "stroke \"${record.id}\" needs more than ${watercolor.maxDabs} watercolor dabs; " +
                    "cannot replay it as an editable line without truncating it",
            )
            return Replayed(rescaled(dabs,widthScaleOf(record.widthScale)),preset.tip.hardness.base,watercolor.strokeOpacity,
                GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF))
        }
        val dabber = BrushDabber(preset, record.seed, StrokeColors(record.colorArgb, record.secondaryColorArgb ?: -1), sampledTips, dualMask)
        // A fill is a SHAPE, not a row of dabs: its raster is `InkRaster.fill` over
        // `FillPen.outline(record.samples, record.smoothing, record.screenPerDoc)`. Asking the
        // dabber for dabs here would draw the fill pen as a line of beads, which is the outline of
        // the shape and not the shape.
        if (preset.engine == ENGINE_FILL) return Replayed(emptyList(), dabber.strokeHardness, dabber.strokeOpacity, dabber.strokeGrain)

        // The brush's whole-stroke shaping (brush version 9, `line`): taper and dashes measured along the drawn path, so
        // after placement and BEFORE the scatter. A brush with no `line` section gets its placed dabs back unchanged. A
        // wire's width is a property of the zoom it is drawn at, so it is applied by the renderer, not here. Lengths are
        // measured in the line's FINAL width (the brush's size times the record's widthScale), so re-weighting a line
        // keeps its proportions: a line made twice as heavy tapers over twice the distance (Lead review M3).
        val style = LineStyle.of(preset.line)
        val diameter = preset.size.base * widthScaleOf(record.widthScale)
        val placed = LineShaper.dash(LineShaper.taper(placedDabs(record, preset, dabber), style, diameter), style, diameter)
        val scattered = Scatter.expand(
            placed,
            preset.scatter,
            SplitMix(record.seed xor Scatter.SALT),
        ) { dab -> dabInputsOf(dab) }
        return Replayed(rescaled(scattered, widthScaleOf(record.widthScale)), dabber.strokeHardness, dabber.strokeOpacity, dabber.strokeGrain)
    }

    /**
     * Replay an unshaped stamp/WC recipe, admitting each final width-scaled/scattered dab BEFORE
     * retaining it in the result. Stamp admission runs during segment resampling/placement, so a
     * throwing sink can refuse a huge segment before its remaining points/dabs are allocated.
     * A refusal returns no partial replay and propagates the original exception unchanged.
     * This does not bound retained smoother history or work for arbitrary long in-place strokes.
     * WC keeps its existing finite replay budget and its no-truncation refusal contract.
     */
    fun replayAdmitted(record: StrokeRecord, brush: BrushPreset?, admit: (Dab) -> Unit): Replayed {
        return replayAdmitted(record, brush, null, admit)
    }

    fun replayAdmitted(record: StrokeRecord, brush: BrushPreset?, sampledTips: SampledTipSet?, admit: (Dab) -> Unit): Replayed =
        replayAdmitted(record, brush, sampledTips, null, admit)

    fun replayAdmitted(record: StrokeRecord, brush: BrushPreset?, sampledTips: SampledTipSet?,
        dualMask: cc.joycreator.joybrush.core.brush.DualMask?, admit: (Dab) -> Unit): Replayed {
        refusal(record, brush, sampledTips, dualMask)?.let { throw InkReplayException(it) }
        val preset = brush!!
        if (preset.line != null || (preset.engine != "stamp" && preset.engine != cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR)) {
            throw InkReplayException("stroke \"${record.id}\" needs an unshaped stamp or watercolor recipe for admitted replay")
        }
        val out = ArrayList<Dab>()
        fun retain(dab: Dab) {
            admit(dab)
            out.add(dab)
        }
        if (preset.engine == cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR) {
            val replayed = replay(record, preset)
            for (dab in replayed.dabs) retain(dab)
            return Replayed(out, replayed.strokeHardness, replayed.strokeOpacity, replayed.strokeGrain)
        }
        val dabber = BrushDabber(preset, record.seed, StrokeColors(record.colorArgb, record.secondaryColorArgb ?: -1), sampledTips, dualMask)
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val scatter = SplitMix(record.seed xor Scatter.SALT)
        val scale = widthScaleOf(record.widthScale)
        val placed: (Dab) -> Unit = { dab ->
            Scatter.expandTo(dab, preset.scatter, scatter, ::dabInputsOf) { retain(rescaledDab(it, scale)) }
        }
        val smoothed: (PenSample) -> Unit = { placer.addTo(it, placed) }
        for (sample in record.samples) smoother.addTo(preset.response.apply(sample), smoothed)
        smoother.finishTo(smoothed)
        return Replayed(out, dabber.strokeHardness, dabber.strokeOpacity, dabber.strokeGrain)
    }

    /**
     * Every problem that stops [record] from being drawn, in words. Empty = it draws.
     *
     * The same sentence [dabs] throws, without the throw, for a UI that wants to grey a stroke out
     * before the person taps it. Two sentences exist and they are written ONCE, here: a refusal a
     * person can read and a refusal a test can catch are the same words, and a file that grew two
     * would grow two opinions about the same fact.
     */
    fun refusal(record: StrokeRecord, brush: BrushPreset?, sampledTips: SampledTipSet? = null,
                dualMask: cc.joycreator.joybrush.core.brush.DualMask? = null): String? = when {
        record.capturedPhysicalSource != null ->
            "stroke \"${record.id}\" needs physical capture clipping and integer placement"
        // Not drawn as a default nib. A line that suddenly appears in the wrong brush is worse than
        // a line that is visibly missing and says why.
        brush == null ->
            "stroke \"${record.id}\" was drawn with brush \"${record.brushId}\", " +
                "which is not in this document's library"
        brush.tip.dual != null && (dualMask == null || !dualMask.matches(brush.tip.dual, record.seed)) ->
            "stroke \"${record.id}\" needs its prepared frozen dual-tip mask and seed"
        brush.tip.sampled != null && (sampledTips == null || !sampledTips.matches(brush.tip.sampled)) ->
            "stroke \"${record.id}\" needs its complete frozen sampled-tip images"
        // `smudge` and `wet` read the pixels UNDERNEATH, and an ink layer has none to read — it has
        // recordings. `StrokeEdit.INK_ENGINES` is the one place that says WHICH engines may, so a
        // fifth engine is a change in one file rather than a change in a list written twice.
        !StrokeEdit.drawsInkLines(brush.engine) ->
            "stroke \"${record.id}\" was drawn with a \"${brush.engine}\" brush, which cannot draw " +
                "an ink line: it reads the pixels underneath, and an ink layer has none"
        brush.engine == cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR && brush.watercolour == null ->
            "stroke \"${record.id}\" has missing watercolor settings"
        brush.engine == cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR && brush.line != null ->
            "stroke \"${record.id}\" uses line shaping that the watercolor engine does not support"
        else -> null
    }

    // ── the live path, walked in the live path's own order ─────────────────────────────

    /**
     * The brush's response curves, smoothing, dabbing and placement: the first steps of the live path and no more.
     *
     * `add` per sample and `finish` at the end, not `StrokeSmoother.smoothAll`, because the
     * streaming form is the one `JbCanvasView.feed` uses and `smoothAll` is documented to be
     * equivalent — so the two are the same answer, and the streaming one is the one that can be
     * asked how many samples were dropped. A recording that lost a sample to a corrupt file draws
     * the rest of the line and says so, rather than drawing nothing or throwing away 999 good
     * samples over one bad float.
     */
    private fun placedDabs(record: StrokeRecord, preset: BrushPreset, dabber: BrushDabber): List<Dab> {
        val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
        val out = ArrayList<PenSample>(record.samples.size)
        // The brush's own pressure and tilt curves come first, as in `JbCanvasView.feedOne`: the record keeps what the pen
        // said, and the brush decides what it hears, so a line re-brushed to a brush with other curves is heard anew.
        for (s in record.samples) out.addAll(smoother.add(preset.response.apply(s)))
        out.addAll(smoother.finish())
        if (out.isEmpty()) return emptyList()
        return DabPlacer(spacing = dabber.spacing, look = dabber::look).add(out)
    }

    /**
     * What [Scatter] gets to ask about one dab, and it is `JbCanvasView.dabInputsOf` VERBATIM.
     *
     * A [Dab] carries [Dab.pressure] and [Dab.tilt] — `DabPlacer` copies the tilt off the recorded
     * sample, and it is NaN for a finger — so those two are passed exactly as the view passes them.
     * Every other input is unknowable from a replay: speed, direction, lean, distance, random,
     * strokeRandom and barrel are all `NaN`, which the curves read as "not available" and skip — so
     * a scatter curve on speed contributes its BASE rather than a number invented here. Inventing
     * real values, OR dropping the tilt the view used (this once passed `tilt = Float.NaN`), would
     * make a replayed stroke scatter differently from the one that was drawn, which is the one thing
     * this file must never do. It is eight lines of deliberate duplication and `InkReplayTest` pins
     * the answer.
     */
    private fun dabInputsOf(dab: Dab) = DabInputs(
        pressure = dab.pressure,
        tilt = dab.tilt,
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
     * It is applied to each dab's RADIUS, at the very end, AFTER `DabPlacer` has placed the dabs —
     * so `DabPlacer`'s own `step = 2r * spacing` comes out of the UNSCALED radius and a re-weighted
     * line's dabs are exactly where they were, only fatter (the count is unchanged and
     * `InkReplayTest` pins it). That is not what a bigger brush would have done live — a bigger
     * brush steps further — so a thickened line's dabs overlap relatively more than the same width
     * drawn fresh. Clamped to 0..[DabPlacer.MAX_RADIUS_PX] by the same guard `DabPlacer.emit`
     * applies and for the same reason — a file that was never validated must not make a radius
     * that cannot be allocated.
     *
     * A [StrokeRecord] is a PLAIN VALUE and its `widthScale` is not checked when the record is
     * built, so this is the point that reads it: see [widthScaleOf]. A radius that is not a number
     * is read as 0, before the multiply rather than after.
     */
    private fun rescaled(dabs: List<Dab>, scale: Float): List<Dab> {
        if (scale == 1f) return dabs
        val out = ArrayList<Dab>(dabs.size)
        for (d in dabs) out.add(rescaledDab(d, scale))
        return out
    }

    private fun rescaledDab(dab: Dab, scale: Float): Dab {
        if (scale == 1f) return dab
        val wanted = (if (dab.radius.isFinite()) dab.radius else 0f) * scale
        val radius = if (wanted.isFinite()) wanted.coerceIn(0f, DabPlacer.MAX_RADIUS_PX) else 0f
        return if (radius == dab.radius) dab else dab.copy(radius = radius)
    }

    /**
     * A `widthScale` that is not a number (NaN or +-Infinity) is read as 1, the neutral width: the
     * line draws at its brush's own size. That is the answer `StrokeCodec.decode` and
     * `StrokeEdit.reweight` already give, and it must be the same answer in all three — otherwise
     * one broken record draws as nothing in memory and as a normal line after a save and reload.
     * (This once read it as 0, following JB-5.01's test 9, which justified 0 as "the same rule as
     * `StrokeEdit.reweight`" — and reweight reads it as 1.) A FINITE scale, however large, is a
     * number and is clamped through the radius in [rescaled] instead (LEAD_RULINGS R44 item 3).
     */
    private fun widthScaleOf(widthScale: Float): Float = if (widthScale.isFinite()) widthScale else 1f
}
