package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Editing a finished ink line.
 *
 * On an INK layer a stroke IS its recording (LEAD_RULINGS R20): there is no separate set of pixels
 * to tweak, so "re-shape and re-weight a finished line" (blueprint §7) is exactly "edit the record
 * and redraw it". These are therefore four PURE functions of a [StrokeRecord] — no rendering, no
 * canvas, no undo stack, no state. The renderer (the Lead's JB-5.01) reads the record's CURRENT
 * brush; the session that turns a finger into one undo step is JB-5.03's `InkEditSession`.
 *
 * Two rules every edit obeys, and they are the reason this object exists at all:
 *
 *  1. **The id is kept.** It is the same line, edited — the identity the cel's map is keyed by, and
 *     what a re-brush must not disturb. Undo stores the record it replaced, not a new line.
 *  2. **The seed is kept** (R13, R20). The seed is what the `BrushDabber` drew with, so keeping it
 *     is what makes a re-brushed line land its scatter and jitter in the same places. A re-brush that
 *     invented a seed would redraw the line differently, which is the one thing "swappable" must not
 *     mean.
 */
object StrokeEdit {

    /** The narrowest a re-weighted line may get: below this it is a scratch, not a line. */
    const val MIN_WIDTH_SCALE = 0.05f

    /** The widest. [StrokeCodec] reads this same constant, so the file and the edit cannot disagree. */
    const val MAX_WIDTH_SCALE = 20f

    /**
     * The engines that may draw an INK layer, in R20's words: the ink layer holds strokes, not
     * pixels, so only the engines that decide from the recording alone are legal there.
     *
     * `smudge` and `wet` read the pixels UNDER the pen, and an ink layer has none to read — there is
     * nothing for them to smear — so picking one while ink strokes are selected is refused in words.
     * The refusal itself belongs to whoever owns the gesture (JB-5.03's `InkEditSession`, which
     * returns `InkEditResult.Refused` with a sentence), because deciding WHEN to say it needs the
     * brush picker; this set is the one place that says WHICH brushes may.
     */
    val INK_ENGINES: Set<String> = setOf("stamp", ENGINE_FILL)

    /** Whether a brush with this `engine` can draw an ink line. See [INK_ENGINES]. */
    fun drawsInkLines(engine: String): Boolean = engine in INK_ENGINES

    /**
     * Drag the line: the sample nearest ([grabX], [grabY]) moves by ([dx], [dy]); samples within
     * [radius] DOCUMENT px of it ALONG THE LINE move by that offset scaled by a falloff; the rest
     * stay exactly where they were. Every other field is unchanged, the id included.
     *
     * The falloff is `w = (1 − (s/R)²)²` for arc length `s < R` and 0 beyond, where `s` is measured
     * along the stroke from the grabbed sample in BOTH directions. It is 1 at the grab and 0 at the
     * radius, and it is monotonically decreasing in `s` (d/du of `(1−u²)²` is `−4u(1−u²) ≤ 0` for
     * `u = s/R`), which is what makes a dragged line bend smoothly into the part that did not move
     * instead of showing a seam at the edge of the grab. The ends of the line move like any other
     * sample: they are part of the line, and a line whose middle moved while its ends did not is not
     * a drag.
     *
     * `radius` ≤ 0 or not a number moves the grabbed sample alone (a tap-nudge); a `dx`, `dy` or grab
     * point that is not a number returns the record untouched, because a NaN would poison every
     * sample it touched and there is no honest "partly NaN" line.
     */
    fun reshape(
        r: StrokeRecord,
        grabX: Float,
        grabY: Float,
        dx: Float,
        dy: Float,
        radius: Float,
    ): StrokeRecord {
        if (!dx.isFinite() || !dy.isFinite() || !grabX.isFinite() || !grabY.isFinite()) return r
        val n = r.samples.size
        if (n == 0) return r

        val arc = arcLengths(r.samples)
        val grab = nearestSample(r.samples, grabX, grabY)
        val reach = radius.isFinite() && radius > 0f

        val out = ArrayList<PenSample>(n)
        for (i in 0 until n) {
            val s = r.samples[i]
            val w = when {
                !reach -> if (i == grab) 1f else 0f
                else -> {
                    val u = abs(arc[i] - arc[grab]) / radius
                    if (u >= 1f) 0f else {
                        val k = 1f - u * u
                        k * k
                    }
                }
            }
            out.add(if (w == 0f) s else s.copy(x = s.x + dx * w, y = s.y + dy * w))
        }
        return r.copy(samples = out)
    }

    /**
     * Re-weight: this line's width is multiplied by [factor] and clamped to
     * [MIN_WIDTH_SCALE]..[MAX_WIDTH_SCALE]. Multiplicative on purpose, so "twice as heavy" means the
     * same thing on a thin line and a thick one, and so several re-weights in a row compose.
     *
     * A [factor] that is not a number changes nothing, and a record whose own `widthScale` is not a
     * number is read as 1 (the neutral width) before the multiply — otherwise one bad field would
     * make every later edit of that line a no-op forever.
     */
    fun reweight(r: StrokeRecord, factor: Float): StrokeRecord {
        if (!factor.isFinite()) return r
        return r.copy(widthScale = (finiteOr(r.widthScale, 1f) * factor).coerceIn(MIN_WIDTH_SCALE, MAX_WIDTH_SCALE))
    }

    /**
     * Re-brush: the same line, drawn with another pen. The samples and the seed are the recording and
     * are kept exactly, so what changes is only WHICH brush reads them.
     *
     * That single fact is what makes both directions of R20 work, and the shape on the far side is
     * the engine's, not this function's:
     *
     *  - **To the fill pen** (`engine "fill"`, LEAD_RULINGS R21): the line stops being dabs and
     *    becomes the closed region the samples enclose — [cc.joycreator.joybrush.core.brush.FillPen]
     *    takes the same samples, smooths them the way a stamp stroke's centreline is smoothed, and
     *    closes them with a straight segment from the last point back to the first. The shape is
     *    therefore the CENTRELINE of what was drawn, grown to a region; the pencil's tip taper, dab
     *    spacing, size and scatter describe how the ink was laid down and are not part of the outline,
     *    so none of them can smear the fill. Re-brushing a *closed* pencil loop gives the whole disc
     *    or blob it traced, not a ring of dabs; re-brushing an *open* one gives that region plus one
     *    straight edge joining the ends.
     *  - **Away from the fill pen** (to a pencil, say): the same points are now a centreline again,
     *    so the outline stroke appears — the boundary a fill had, drawn as a line.
     *
     * Only [INK_ENGINES] may be named here in practice; a `smudge`/`wet` brush cannot be refused by
     * this function, because it is handed a brush ID and knows nothing about engines. The caller
     * that has the [cc.joycreator.joybrush.core.brush.BrushPreset] asks [drawsInkLines] and says the
     * refusal in words (R20).
     */
    fun rebrush(r: StrokeRecord, brushId: String): StrokeRecord = r.copy(brushId = brushId)

    /** Recolour: straight sRGB, exactly as [StrokeRecord.colorArgb] stores it. */
    fun recolor(r: StrokeRecord, argb: Int): StrokeRecord = r.copy(colorArgb = argb)

    // ── the arithmetic ────────────────────────────────────────────────────────────────────────

    /** `arc[i]` = length of the path from the first sample to sample `i`. */
    private fun arcLengths(samples: List<PenSample>): FloatArray {
        val arc = FloatArray(samples.size)
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            // A sample the codec read with no place in the world contributes nothing rather than
            // poisoning every arc length after it with NaN.
            arc[i] = if (len.isFinite()) arc[i - 1] + len.toFloat() else arc[i - 1]
        }
        return arc
    }

    /** The sample closest to the grab. A sample with no place in the world is never the one. */
    private fun nearestSample(samples: List<PenSample>, x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.POSITIVE_INFINITY
        for (i in samples.indices) {
            val s = samples[i]
            if (!s.x.isFinite() || !s.y.isFinite()) continue
            val d = hypot((s.x - x).toDouble(), (s.y - y).toDouble())
            if (d < bestD) {
                bestD = d.toFloat()
                best = i
            }
        }
        return best
    }

    /** LEAD_RULINGS R1/R10: a value that is not a usable number is read as a safe one, never propagated. */
    private fun finiteOr(v: Float, safe: Float): Float = if (v.isFinite()) v else safe
}
