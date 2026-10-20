package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.brush.AngleFrame
import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.ResponseSpec
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.guide.SymmetryCopy
import cc.joycreator.joybrush.core.guide.SymmetryMath
import cc.joycreator.joybrush.core.input.DirectionTracker
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeCapture

/**
 * The app's hard-coded round brush, as a symmetry lane needs it: the numbers the view's own
 * [DabPlacer] is built from when there is no brush FILE.
 *
 * It is here, in core, so a lane can be built and tested without an Android runtime: the JVM
 * test suite cannot construct the view (there is no Android on the test classpath), and a lane
 * that could only be built by the view could not be tested at all.
 */
data class HardcodedTip(
    /** The brush's size in document px (its DIAMETER). */
    val sizePx: Float,
    /** How small the tip may get at zero pressure, as a fraction of [sizePx]. */
    val minSizeFraction: Float,
    /** How much every dab adds. */
    val flow: Float,
    /** The dab spacing, a fraction of the dab's diameter. */
    val spacing: Float,
    /** The ceiling of the whole stroke (the WASH cap). */
    val cap: Float,
    /** True when the tip turns to face the way the pen is moving. */
    val followDirection: Boolean,
)

/**
 * ONE lane of a symmetry stroke: the WHOLE brush, run again on the pen's samples transformed
 * into this lane's own frame.
 *
 * ## The transform is applied ONCE, to the SAMPLE
 *
 * A lane transforms the pen's sample (its position, its lean and its twist) and then hands the
 * sample to the brush. The brush is never told about the copy: it sees a stroke, and it draws
 * that stroke. There is no transform of the finished dab anywhere in this class, and there must
 * not be one: a dab that was transformed twice would be drawn at twice the copy's rotation, and a
 * dab transformed INSTEAD of its sample would carry the original's shape into the copy.
 *
 * ## What a lane OWNS, and why it owns it
 *
 * - **Its own [StrokeSmoother].** The smoother is position-dependent: it resamples, it finds
 *   corners and it smooths over arc length, and it keeps all of that history in itself. One
 *   smoother for N lanes would give every lane the ORIGINAL's points.
 * - **Its own [BrushDabber]** (a brush FILE) or its own [DabPlacer] (the hard-coded brush). The
 *   dabber holds the random stream, the speed filter and the tip's direction; the direction in
 *   particular is the lane's OWN, because the lane is fed the samples already transformed.
 * - **Its own [SplitMix]** for the scatter, so two lanes never share a stream.
 * - **Its own seed**, which is the same for every lane when the guide's `symmetric` is on and a
 *   different one per lane when it is off.
 * - **Its own recording**, when the stroke is kept as an editable line: the lane records the samples
 *   it HEARD - the copy's own samples, before the response and the smoother - so a later replay of
 *   that recording draws this copy and not the original.
 *
 * ## The seed, and what "symmetric" means
 *
 * With the guide's `symmetric` ON (the default), every lane's seed is the SAME: every lane draws
 * the same size jitter, the same angle jitter and the same scatter values, so a copy's texture is
 * the original's texture seen in the copy's frame. The scatter's ACROSS component is negated for
 * a mirror copy, because a reflection reverses handedness.
 *
 * With it OFF, every lane has its OWN seed (`strokeSeed + index * 0x9E3779B97F4A7C15`), so each
 * copy's texture and jitter are its own; the lean and the twist are then left alone (the copy
 * keeps the original's lean), and the scatter is not negated across.
 *
 * ## Purity
 *
 * Nothing here touches Android, a view, a clock or a file. A lane is built, fed, asked for its
 * dabs, finished and dropped, and the whole of it can be exercised by the JVM test suite.
 */
class SymmetryLane private constructor(
    /**
     * The copy's own map, or null for the ORIGINAL lane (lane 0).
     *
     * Null and not an identity map, on purpose: an identity map would still take the sample
     * through the map's arithmetic, and a signed zero would come back as `+0.0f` - a change of
     * value in a stroke that is not a copy at all. The original lane hands its samples straight
     * on.
     */
    val copy: SymmetryCopy?,
    /**
     * The copy's own geometry: how far the copy is turned, and the axis it is reflected in. The
     * ORIGINAL's frame is [AngleFrame.IDENTITY], which changes nothing down to the bit.
     */
    val frame: AngleFrame,
    /** The brush FILE this lane draws with, or null for the app's hard-coded round brush. */
    val preset: BrushPreset?,
    /** The seed this lane's randomness comes from. */
    val seed: Long,
    /** True when the copies share ONE brush's randomness (the guide's `symmetric`). */
    private val symmetric: Boolean,
    /** The brush's own ear: how it hears the pen. Null for the hard-coded brush. */
    private val response: ResponseSpec?,
    /** The file dabber, when the lane draws from a brush FILE. Null otherwise. */
    val dabber: BrushDabber?,
    /** This lane's own dab placer. */
    private val placer: DabPlacer,
    /** This lane's own smoother. */
    private val smoother: StrokeSmoother,
    /** The file's scatter, when the lane draws from a brush FILE. Null otherwise. */
    private val scatter: ScatterSpec?,
    /** This lane's own scatter stream. Null when the lane scatters nothing. */
    private val scatterRng: SplitMix?,
) {

    /** True when this lane is the ORIGINAL stroke: the one the copies are copies of. */
    val isOriginal: Boolean get() = copy == null

    /**
     * This lane's own recording, for a stroke that is kept as an editable line.
     *
     * The seed is the LANE's seed - the exact seed this lane's dabber drew with (LEAD_RULINGS R13) -
     * so a later replay of the recording is the stroke that was drawn and not another one. The lane
     * owns that seed, so the lane is where the recording is made: nobody can hand a record a seed
     * the brush never used.
     */
    fun capture(id: String, brushId: String, smoothing: Float, screenPerDoc: Float, colorArgb: Int): StrokeCapture =
        StrokeCapture(id, brushId, seed, smoothing, screenPerDoc, colorArgb)

    /** The stroke's opacity, as the engine takes it (a WASH stroke's only channel for it). */
    val strokeOpacity: Float get() = dabber?.strokeOpacity ?: 1f

    /** The stroke's tip hardness, as the shader takes it: one uniform, evaluated at the first dab. */
    val strokeHardness: Float get() = dabber?.strokeHardness ?: Float.NaN

    /** The stroke's two grains, as the shader takes them. */
    val strokeGrain: GrainMath.StrokeGrain
        get() = dabber?.strokeGrain
            ?: GrainMath.StrokeGrain(GrainMath.GrainUniforms.OFF, GrainMath.GrainUniforms.OFF)

    private var cancelled = false

    /**
     * Feeds one pen sample and returns the points this lane's smoother released.
     *
     * [record] is this lane's OWN recording of the raw samples: it is given the lane's own copy of
     * the sample - the copy's map applied, BEFORE the brush's response and the smoother - so a later
     * replay of that recording draws exactly the copy that was painted. It is null when the stroke
     * is not kept as an editable line.
     *
     * The transform is applied ONCE, here: the same transformed sample is what the brush hears and
     * what the recording holds, so the two can never come apart.
     *
     * The order is the contract: the copy's map first (the position, and the lean and the twist
     * when the guide is symmetric), then the brush's own response curves, then the smoother. The
     * response only shapes the pressure and the tilt, and the transform only moves the sample, so
     * the two do not fight - but the order is written down so that nobody has to work it out.
     *
     * A cancelled lane releases nothing.
     */
    fun feed(sample: PenSample, record: StrokeCapture? = null): List<PenSample> {
        if (cancelled) return emptyList()
        val moved = if (copy == null) sample else SymmetryMath.apply(sample, copy, symmetric)
        // The recording holds the samples the BRUSH heard: a predicted or unplaceable sample is
        // dropped by the smoother and never reaches the brush, so it is not recorded either.
        if (record != null && moved.isPlaceable && !moved.predicted) record.add(moved)
        val heard = response?.apply(moved) ?: moved
        return smoother.add(heard)
    }

    /**
     * The dabs for the points the smoother released: this lane's placer, then the file's scatter.
     *
     * The scatter's ACROSS component is negated for a mirror copy, and only when the guide is
     * symmetric: a reflection reverses handedness, so an offset rebuilt in the copy's own frame
     * has to go the other way round the stroke or the copy comes out handedness-flipped rather
     * than mirrored.
     */
    fun dabs(released: List<PenSample>): List<Dab> {
        if (cancelled || released.isEmpty()) return emptyList()
        val placed = placer.add(released)
        if (placed.isEmpty()) return emptyList()
        val spec = scatter ?: return placed
        val rng = scatterRng ?: return placed
        return Scatter.expand(placed, spec, rng, ::dabInputsOf, flipAcross)
    }

    /**
     * The pen lifted: the smoother's catch-up tail, placed and scattered like any other dabs.
     *
     * A lane that is never finished LOSES its last dabs: the smoother holds them back until
     * nothing that arrives later can change them, and the tail is only released at the end.
     */
    fun finish(): List<Dab> {
        if (cancelled) return emptyList()
        val tail = smoother.finish()
        return dabs(tail)
    }

    /** Drops this lane: nothing more of it is to be drawn, and it draws nothing more. */
    fun cancel() {
        cancelled = true
    }

    /** True when this lane's scatter negates its across component: a mirror copy, symmetric on. */
    private val flipAcross: Boolean get() = symmetric && copy?.mirrored == true

    companion object {

        /**
         * A lane that draws with a brush FILE.
         *
         * The lane builds its own [BrushDabber] and its own [DabPlacer]: the dabber's tracker is
         * seeded from [frame], so the copy's FIRST dabs already point the copy's way.
         */
        fun forFile(
            copy: SymmetryCopy?,
            frame: AngleFrame,
            preset: BrushPreset,
            seed: Long,
            symmetric: Boolean,
            smoothing: Float,
            screenPerDoc: Float,
        ): SymmetryLane {
            val dabber = BrushDabber(preset, seed, frame)
            val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
            return SymmetryLane(
                copy = copy,
                frame = frame,
                preset = preset,
                seed = seed,
                symmetric = symmetric,
                response = preset.response,
                dabber = dabber,
                placer = placer,
                smoother = StrokeSmoother(smoothing, screenPerDoc),
                scatter = preset.scatter,
                scatterRng = SplitMix(seed xor Scatter.SALT),
            )
        }

        /**
         * A lane that draws with the app's hard-coded round brush.
         *
         * The lane builds its own [DabPlacer] and its own [DirectionTracker], and the tracker is
         * seeded with the lane's own heading, exactly as the file dabber's is.
         */
        fun forHardcoded(
            copy: SymmetryCopy?,
            frame: AngleFrame,
            tip: HardcodedTip,
            seed: Long,
            symmetric: Boolean,
            smoothing: Float,
            screenPerDoc: Float,
        ): SymmetryLane {
            val tracker = DirectionTracker(initialDirection = frame.transformedDefaultHeading)
            val placer = DabPlacer(
                spacing = tip.spacing,
                radiusOf = { s ->
                    0.5f * tip.sizePx * (tip.minSizeFraction + (1f - tip.minSizeFraction) * s.pressure)
                },
                angleOf = if (tip.followDirection) { s -> tracker.update(s) } else { _ -> 0f },
                flowOf = { tip.flow },
                cap = tip.cap,
            )
            return SymmetryLane(
                copy = copy,
                frame = frame,
                preset = null,
                seed = seed,
                symmetric = symmetric,
                response = null,
                dabber = null,
                placer = placer,
                smoother = StrokeSmoother(smoothing, screenPerDoc),
                scatter = null,
                scatterRng = null,
            )
        }
    }
}

/**
 * What [Scatter] gets to ask about one dab: its pressure and its tilt.
 *
 * A [Dab] does not carry the speed or the distance, so those are NaN - which the curves read as
 * "not available" and skip, leaving a scatter curve on speed contributing its base rather than a
 * number invented here.
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
