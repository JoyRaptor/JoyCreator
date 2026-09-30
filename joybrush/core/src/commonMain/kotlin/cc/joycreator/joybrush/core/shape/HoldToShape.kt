package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Hold-to-shape part 3: the HOLD, the decision, and the resize (JB-2.11). One instance per stroke,
 * built at pen-down.
 *
 * Pure arithmetic on times and points. No clock, no view, no engine, no Android. The shell hands it
 * samples in DOCUMENT px (the same samples `JbCanvasView.feed` gives the smoother, after
 * `view.screenToDoc`) and reads [state] back.
 *
 * ## What the CALLER must do with this — the whole of R39, in one place
 *
 * While the pen is down the caller keeps drawing the ORIGINAL samples exactly as it always does. The
 * rough stroke therefore stays in the engine's stroke buffer, on the screen, uncommitted — a stroke
 * is only committed on pen-up, and nothing here has committed anything. The [State.Shaping] outline
 * is an OVERLAY drawn on top of it; it is not a replacement for it and the caller does not withdraw,
 * erase or re-feed anything to make it appear.
 *
 * On the lift the caller calls [lift] and gets back ONE list. It then cancels the in-flight stroke
 * (`GlPaintEngine.cancelStroke()` discards the uncommitted buffer) and feeds that list through the
 * SAME path it would have used anyway — the same brush, the same layer, the same dabs,
 * `endStroke()` — so the result is ONE stroke and ONE undo step. There is no "replay", no second
 * brush, and no way for a person to undo the shape and find the rough stroke underneath.
 *
 * [lift] therefore returns the samples to feed, and never a mixture: either the whole original
 * stroke, or the whole perfected one. When the state is [State.Shaping], that list IS the outline
 * the caller was drawing as a preview, so what the person saw is exactly what is committed.
 *
 * ## Time, and the two places it can come from
 *
 * A sample's own `timeMs` is the clock, and so is [tick]. The hold can complete on either, and both
 * go through the same [decide] so a pen that rests and keeps reporting jittery samples behaves the
 * same as a pen that is perfectly still and reports nothing (Decision 3).
 */
class HoldToShape(
    /** `ViewTransform.zoom` — screen px per document px, read once at pen-down. */
    val screenPerDoc: Float = 1f,
    /** Display density, so the still-tolerance is a number of dp and not a number of millimetres. */
    val density: Float = 1f,
    /** `BrushPreset.engine`. The fill pen is off (Decision 9). */
    val engine: String = "stamp",
    /** The recogniser seam, so "it runs once" is a test and not a claim. */
    private val recognize: (List<PenSample>, Float) -> Shape? = ShapeRecognizer::recognize,
) {

    /** What the person is being shown right now. */
    sealed class State {
        /** Drawing as usual. The samples the caller already drew stay drawn. */
        object Idle : State()

        /**
         * The pen has been still long enough and a shape was recognised. [shape] is what will be
         * drawn; [outline] is that shape with the stroke's pressure and tilt series carried over,
         * re-derived from the CURRENT samples and the CURRENT scale on every change.
         */
        data class Shaping(val shape: Shape, val outline: List<PenSample>) : State()

        /** The pen is still but the stroke is not a shape. [stalledMs] is how long it has been still. */
        data class Waiting(val stalledMs: Long) : State()

        /** The hold is over and the ORIGINAL stroke is what will be committed. */
        data class Abandoned(val reason: String) : State()
    }

    /** Where the hold has got to. Read this; never infer it from the size of a returned list. */
    val state: State get() = stateValue

    /** False for the fill pen, true otherwise (Decision 9). */
    val enabled: Boolean = engine != ENGINE_FILL

    /**
     * Samples refused because they had no place or no time in the world, counted the way
     * `StrokeSmoother.droppedSamples` counts them. Zero in every real stroke.
     */
    var droppedSamples: Int = 0
        private set

    // The two constructor numbers above are the public record of the call, kept exactly as they were
    // passed. These are the copies the maths uses, so one broken number is fixed once here instead of
    // poisoning every later frame — the same rule as `SizeOpacityDrag` and `StrokeSmoother` (R1, R10).
    private val zoom: Float = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc else 1f
    private val dp: Float = if (density.isFinite() && density > 0f) density else 1f

    /** How far the pen may drift and still count as "still", screen px. Never 0, so it divides safely. */
    private val stillScreen: Double = (STILL_DP * dp).toDouble()

    /** Screen px of horizontal travel that doubles the shape. Never 0 for the same reason. */
    private val doublingScreen: Double = (SIZE_PER_DOUBLING_DP * dp).toDouble()

    /** Everything the caller has drawn this stroke, in order. The only copy; never handed out raw. */
    private val samples = ArrayList<PenSample>()

    private var stateValue: State = State.Idle

    /** The last point the pen was anchored at, document px, and whether there is one yet. */
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var anchored = false

    /** `timeMs` of the anchor: the still clock runs from here, and only a move past the tolerance resets it. */
    private var stillSince = 0.0

    /** The recognised shape, unscaled, and the scale currently applied to it. */
    private var base: Shape? = null
    private var scale = 1.0

    /** Where the pen was when the hold completed — the origin every resize measures from. */
    private var holdX = 0.0
    private var holdY = 0.0

    /**
     * Decision 4: the recogniser runs once per stroke, at the hold, and never again.
     *
     * The `Idle`-only dispatch in [add] and [tick] is what already makes that true, and a mutation
     * that deletes this latch alone does not change a single test result. It is kept as a second
     * guard on the same rule, so that a later edit to the dispatch cannot quietly reintroduce the
     * second look this row forbids.
     */
    private var decided = false

    private var lastTick = Double.NEGATIVE_INFINITY

    /** The answer [lift] already gave, so a second call returns the same list rather than a new one. */
    private var lifted: List<PenSample>? = null

    /**
     * One sample, in the order it arrived. `sample.timeMs` is the clock, and so is [tick].
     *
     * What a sample does depends on the state, and that is the whole of Decision 10:
     *  - [State.Idle]: recorded; a sample further than [STILL_DP] × density from the anchor
     *    re-anchors and restarts the still clock, and a sample that has reached the hold decides it.
     *  - [State.Waiting]: recorded; one past the tolerance abandons the hold, and there is no second
     *    look (Decision 4).
     *  - [State.Shaping]: recorded — the caller is drawing these as usual, so they belong to the
     *    stroke — and then a movement is either a resize by its horizontal part or an abandonment,
     *    never a re-anchoring. The shape itself is never re-decided.
     *  - [State.Abandoned]: recorded and nothing else.
     *
     * A sample that is not placeable is dropped and counted in every state except the disabled one
     * (Decision 11). After [lift] this call does nothing: the instance is finished.
     */
    fun add(sample: PenSample) {
        if (lifted != null) return
        // The fill pen is off. This class then remembers the stroke without looking at it, so that
        // `lift()` still answers, and nothing else happens: no state, no still clock, no drops.
        if (!enabled) {
            samples.add(sample)
            return
        }
        if (!sample.isPlaceable) {
            droppedSamples++
            return
        }
        when (stateValue) {
            is State.Shaping -> {
                samples.add(sample)
                shapedMove(sample)
            }
            is State.Abandoned -> samples.add(sample)
            is State.Waiting -> {
                samples.add(sample)
                if (fromAnchor(sample) > stillScreen) abandon()
            }
            State.Idle -> {
                samples.add(sample)
                if (!anchored) anchorTo(sample)
                else if (fromAnchor(sample) > stillScreen) anchorTo(sample)
                decide(sample.timeMs)
            }
        }
    }

    /**
     * Time has passed with no new sample. **The host must call this** — a pen that is perfectly
     * still sends no `MotionEvent`, and without this the hold could only ever fire on the next
     * movement, which is the one thing the person is not doing. Call it on every `MotionEvent` that
     * carries no new sample, and from whatever the view already runs per frame.
     *
     * `nowMs` is the same clock as `PenSample.timeMs` (Android's event time is uptimeMillis).
     * Non-finite or earlier-than-the-last-reading values are ignored, and `state` does not move.
     */
    fun tick(nowMs: Double) {
        if (lifted != null || !enabled) return
        if (!nowMs.isFinite() || nowMs < lastTick) return
        lastTick = nowMs
        when (stateValue) {
            is State.Waiting -> stateValue = State.Waiting(stalledMsOf(nowMs))
            is State.Shaping, is State.Abandoned -> Unit
            State.Idle -> decide(nowMs)
        }
    }

    /**
     * RESIZE while still held: the shape is scaled about its own centre by the drag so far.
     * [dxScreen] is the total HORIZONTAL screen-px travel since the hold completed, +x right.
     * Returns the new outline, and [state] carries it too.
     *
     * Ignored — and an **empty list** returned, because there is no outline to draw — unless the
     * state is [State.Shaping], and ignored outright (outline unchanged) if [dxScreen] is not a
     * finite number. That empty list is legitimate, which is exactly why a caller must read [state]
     * and never the size of what came back.
     */
    fun resize(dxScreen: Float): List<PenSample> {
        if (lifted != null) return emptyList()
        if (stateValue !is State.Shaping) return emptyList()
        if (dxScreen.isFinite()) scaleTo(2.0.pow(dxScreen.toDouble() / doublingScreen))
        return (stateValue as State.Shaping).outline
    }

    /**
     * The pen lifted. Returns what to feed through the normal path, in ONE list:
     *  - never held → the original samples, the same instances;
     *  - held and recognised → `ShapePerfecter.perfect(samples, scaledShape)` — the SAME sample
     *    count, with pressure/tilt/azimuth/barrel/tool/time copied through;
     *  - held and not recognised, or abandoned → the original samples.
     * Never empty for a stroke that had usable points, and never a mixture of the two.
     *
     * Calling it twice returns the same list, and anything the caller does afterwards is ignored:
     * [state] keeps the last state so the outline is still readable.
     */
    fun lift(): List<PenSample> {
        lifted?.let { return it }
        val out = (stateValue as? State.Shaping)?.outline ?: samples
        lifted = out
        return out
    }

    // ── the decision ──────────────────────────────────────────────────────────────────────────

    /**
     * The hold has completed at [now] if `now − stillSince` is at least [HOLD_MS], and not before.
     * Both [add] and [tick] come through here, and [decided] makes it once per stroke (Decision 4).
     */
    private fun decide(now: Double) {
        if (decided || !enabled || lifted != null) return
        if (!anchored || samples.isEmpty()) return
        if (now - stillSince < HOLD_MS.toDouble()) return
        decided = true
        val asDrawn = samples.toList()
        holdX = anchorX
        holdY = anchorY
        val shape = recognize(asDrawn, zoom)
        if (shape == null) {
            stateValue = State.Waiting(stalledMsOf(now))
        } else {
            base = shape
            scale = 1.0
            stateValue = State.Shaping(shape, ShapePerfecter.perfect(asDrawn, shape))
        }
    }

    private fun anchorTo(sample: PenSample) {
        anchored = true
        anchorX = sample.x.toDouble()
        anchorY = sample.y.toDouble()
        stillSince = sample.timeMs
    }

    private fun fromAnchor(sample: PenSample): Double {
        val dx = (sample.x - anchorX) * zoom
        val dy = (sample.y - anchorY) * zoom
        return hypot(dx, dy)
    }

    private fun stalledMsOf(now: Double): Long = (now - stillSince).toLong()

    // ── the resize ────────────────────────────────────────────────────────────────────────────

    /**
     * Decision 10, the horizontal part. A movement that is vertical-dominant AND further than the
     * still-tolerance gives the shape back; everything else, ties included, is a resize by the
     * horizontal part. The tie goes to the drag, as it does in `SizeOpacityDrag.move`.
     */
    private fun shapedMove(sample: PenSample) {
        val dx = (sample.x - holdX) * zoom
        val dy = (sample.y - holdY) * zoom
        if (abs(dy) > abs(dx) && hypot(dx, dy) > stillScreen) {
            abandon()
            return
        }
        scaleTo(2.0.pow(dx / doublingScreen))
    }

    /** Decision 8: a clamp at both ends, silently — a drag past the end of a scale is a drag. */
    private fun scaleTo(factor: Double) {
        val b = base ?: return
        scale = factor.coerceIn(MIN_SCALE.toDouble(), MAX_SCALE.toDouble())
        val shaped = scaled(b, scale)
        stateValue = State.Shaping(shaped, ShapePerfecter.perfect(samples, shaped))
    }

    private fun scaled(s: Shape, f: Double): Shape {
        fun q(p: Pt) = Pt(p.x * f, p.y * f)
        return when (s) {
            is Shape.Line -> Shape.Line(q(s.a), q(s.b))
            is Shape.Ellipse -> Shape.Ellipse(q(s.center), s.rx * f, s.ry * f, s.rotation)
            is Shape.Arc -> Shape.Arc(q(s.center), s.radius * f, s.startAngle, s.sweep)
            is Shape.Polygon -> Shape.Polygon(s.corners.map(::q))
        }
    }

    private fun abandon() {
        if (stateValue is State.Abandoned) return
        stateValue = State.Abandoned(ABANDONED_MOVING)
        base = null
        scale = 1.0
    }

    companion object {
        /**
         * Milliseconds of stillness that turn a stroke into a shape candidate.
         *
         * **THIS IS A GUESS AND IT IS LABELLED AS ONE.** It is not derived from anything in this
         * repo, it has not been measured on a phone, and it is the one value in this row a person
         * will have an immediate opinion about. It is a single constant and every tolerance in the
         * class is either independent of it or scaled off it, so changing it is one line.
         */
        const val HOLD_MS = 500L

        /**
         * How far the pen may drift and still count as "still", in dp, multiplied by [density] at
         * the use site (R32). See Q4 — the draft claimed this was the same number as JB-2.03a's
         * long-press slop, and 2.03a says 6 *screen px*, unqualified.
         */
        const val STILL_DP = 6f

        /** Travel that doubles the shape, in dp — read from the brush swatch's own constant. */
        const val SIZE_PER_DOUBLING_DP = SizeOpacityDrag.SIZE_PER_DOUBLING_DP

        /** Smallest scale factor the resize may produce, whatever the drag says. */
        const val MIN_SCALE = 0.25f

        /** Biggest scale factor the resize may produce, whatever the drag says. */
        const val MAX_SCALE = 4f

        /** The only reason this row ever abandons a hold, and it is stated so the string is in one place. */
        private const val ABANDONED_MOVING = "moved while holding"
    }
}
