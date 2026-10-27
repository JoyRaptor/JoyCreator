package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The live preview in a brush's settings (owner, 2026-09-30: "as I adjust the sliders I can clearly see exactly what is
 * changing on a sample stroke — versatile enough in tilt, pressure and speed"). The same strokes every time, so a slider
 * move is the only thing that changes:
 *
 *  - **the long stroke** across the top walks every mode in one go: a hairline (light, slow), line weight, then pressed
 *    flat and laid over for shadow (heavy, tilted, fast), and a quick lift;
 *  - **hair spikes**: pressed at the root, flicked off;
 *  - **switchbacks** at line weight;
 *  - **the owner's two circles**: the point pressed in one place with the pen turned round it, and the point walked
 *    round a rim with the base of the brush over the centre — they must look different;
 *  - **a laid-over shadow pass**, heavy and then light at the same angle (black, then grazing);
 *  - **a jolt**: sudden presses at speed;
 *  - **whiskers**: light and fast.
 *
 * Positions are SCREEN px inside a [width] × [height] box from (0, 0), times in ms (real hand speeds). The lean points up
 * and to the left, as a right hand holds a pen. Pure and repeatable.
 */
object BrushPreviewStrokes {

    /** The pen leans up-left (radians, document space: 0 = +x, y grows down). */
    private val LEAN = (-PI * 0.75).toFloat()

    fun strokes(width: Float, height: Float): List<List<PenSample>> {
        val w = width
        val h = height
        val out = ArrayList<List<PenSample>>()

        // The long stroke: hairline → line weight → pressed flat and laid over → quick lift.
        out += path(n = (w * 1.2f).toInt(), x = { t -> w * (0.03f + 0.94f * t) },
            y = { t -> h * (0.3f + 0.16f * sin(2f * PI.toFloat() * t)) },
            pressure = { t ->
                when {
                    t < 0.2f -> 0.04f + 0.26f * (t / 0.2f)
                    t < 0.45f -> 0.3f + 0.45f * ((t - 0.2f) / 0.25f)
                    t < 0.75f -> 0.75f + 0.25f * ((t - 0.45f) / 0.3f)
                    else -> 1f - 0.75f * ((t - 0.75f) / 0.25f)
                }
            },
            tilt = { t -> 1.15f * smooth(0.4f, 0.7f, t) - 0.5f * smooth(0.8f, 1f, t) },
            speed = { t -> 0.35f + 1.6f * smooth(0.25f, 0.6f, t) + 1.4f * smooth(0.85f, 1f, t) })

        val lowTop = h * 0.6f
        val lowBottom = h * 0.96f
        val lowH = lowBottom - lowTop
        // Hair spikes.
        for (i in 0 until 3) {
            val x0 = w * (0.02f + i * 0.055f)
            out += path(n = 60, x = { t -> x0 + w * 0.03f * t }, y = { t -> lowBottom - lowH * t },
                pressure = { t -> if (t < 0.3f) 1f else (1f - (t - 0.3f) / 0.7f).coerceAtLeast(0f) },
                tilt = { 0.3f }, speed = { 2.6f })
        }
        // Switchbacks at line weight.
        run {
            val x0 = w * 0.2f
            val span = w * 0.14f
            val legs = 4
            out += path(n = (span * legs).toInt(), x = { t ->
                val leg = (t * legs).toInt().coerceAtMost(legs - 1)
                val f = t * legs - leg
                if (leg % 2 == 0) x0 + span * f else x0 + span * (1f - f)
            }, y = { t -> lowTop + lowH * t }, pressure = { 0.62f }, tilt = { 0.2f }, speed = { 1.1f })
        }
        // The owner's two circles (2026-09-30), which must look different:
        val r = lowH * 0.42f
        // (a) the point pressed in one place, the butt of the pen turned round it: the belly sweeps round the point.
        run {
            val cx = w * 0.42f
            val cy = lowTop + lowH * 0.5f
            out += (0..120).map { i ->
                val a = (i / 120f) * 2f * PI.toFloat()
                PenSample(cx, cy, timeMs = i * 6.0, pressure = 0.35f, tilt = 1.0f, azimuth = a)
            }
        }
        // (b) the point walked round the rim with the base of the brush held over the centre.
        run {
            val cx = w * 0.42f + r * 2.6f
            val cy = lowTop + lowH * 0.5f
            out += (0..160).map { i ->
                val a = (i / 160f) * 2f * PI.toFloat()
                PenSample(cx + r * kotlin.math.cos(a), cy + r * sin(a), timeMs = i * 4.0, pressure = 0.45f, tilt = 1.0f,
                    azimuth = a + PI.toFloat())
            }
        }
        // A laid-over shadow pass: heavy, then the same angle light (black, then wispy grazing).
        out += path(n = (w * 0.14f).toInt(), x = { t -> w * (0.66f + 0.14f * t) }, y = { lowTop + lowH * 0.25f },
            pressure = { 0.95f }, tilt = { 1.2f }, speed = { 1.3f })
        out += path(n = (w * 0.14f).toInt(), x = { t -> w * (0.66f + 0.14f * t) }, y = { lowTop + lowH * 0.75f },
            pressure = { 0.15f }, tilt = { 1.2f }, speed = { 1.3f })
        // A jolt: sudden presses at speed.
        out += path(n = (w * 0.15f).toInt(), x = { t -> w * (0.83f + 0.15f * t) }, y = { lowTop + lowH * 0.2f },
            pressure = { t -> if (((t * 5f).toInt() % 2) == 0) 0.15f else 1f }, tilt = { 0.3f }, speed = { 2.4f })
        // Whiskers.
        for (i in 0 until 3) {
            val x0 = w * (0.84f + i * 0.045f)
            out += path(n = 40, x = { t -> x0 + w * 0.035f * t }, y = { t -> lowBottom - lowH * 0.5f * t },
                pressure = { t -> 0.18f * sin(PI.toFloat() * t) + 0.03f }, tilt = { 0.2f }, speed = { 2.2f })
        }
        return out
    }

    /** One stroke of the [sheet]: drawn in the second colour when [second] (it crosses wet paint of the first). */
    class Specimen(val samples: List<PenSample>, val second: Boolean = false)

    /** A labelled group of the [sheet]: its caption, centred at [centreX] (fraction of the width) under the bottom band. */
    class Caption(val text: String, val centreX: Float)

    /**
     * The specimen sheet (owner, 2026-10-27: "a swoosh and several other pressure and tilt settings, nicely arranged, so
     * that when I am moving any of those sliders I can see exactly what's happening"). One thing changes at a time:
     *
     *  - **the swoosh** across the top: every mode in one stroke (light to full pressure and back, upright to laid over,
     *    slow to fast), crossed by **a stroke in the second colour** while it is wet, so Covers, Carries and blending show;
     *  - **pressure**: five strokes, the same in all but how hard (0.1 to 1);
     *  - **tilt**: four strokes, upright to laid flat;
     *  - **speed**: slow, medium, fast;
     *  - **turn**: the owner's two circles (2026-09-30), the pen turned round its point and the point walked round a rim.
     *
     * SCREEN px inside [width] x [height] from (0, 0); times in ms at real hand speeds; pure and repeatable.
     */
    fun sheet(width: Float, height: Float): List<Specimen> {
        val w = width
        val h = height
        val out = ArrayList<Specimen>()
        // The swoosh: an S across the top band.
        out += Specimen(path(n = (w * 1.1f).toInt(), x = { t -> w * (0.04f + 0.92f * t) },
            y = { t -> h * (0.22f - 0.13f * sin(2f * PI.toFloat() * t)) },
            pressure = { t -> 0.05f + 0.95f * sin(PI.toFloat() * t).let { it * it } },
            tilt = { t -> 1.2f * smooth(0.3f, 0.6f, t) * (1f - smooth(0.75f, 1f, t)) },
            speed = { t -> 0.4f + 2.2f * smooth(0.55f, 0.95f, t) }))
        // Across it, while it is wet, in the second colour: what the brush picks up and lays over.
        out += Specimen(path(n = (h * 0.45f).toInt(), x = { t -> w * (0.47f + 0.06f * t) }, y = { t -> h * (0.04f + 0.38f * t) },
            pressure = { t -> 0.35f + 0.5f * sin(PI.toFloat() * t) }, tilt = { 0.4f }, speed = { 0.9f }), second = true)

        val top = h * 0.5f
        val bottom = h * 0.86f
        val tall = bottom - top
        fun short(x0: Float, pressure: Float, tilt: Float, speed: Float) = Specimen(path(n = (tall * 1.1f).toInt(),
            x = { t -> x0 + tall * 0.18f * t }, y = { t -> bottom - tall * t },
            pressure = { pressure }, tilt = { tilt }, speed = { speed }))
        val step = w * 0.045f
        // Pressure: x 0.04 .. 0.26.
        listOf(0.1f, 0.3f, 0.5f, 0.75f, 1f).forEachIndexed { i, p -> out += short(w * 0.04f + i * step, p, 0.3f, 1.1f) }
        // Tilt: x 0.32 .. 0.49.
        listOf(0f, 0.45f, 0.9f, 1.3f).forEachIndexed { i, t -> out += short(w * 0.32f + i * step, 0.6f, t, 1.1f) }
        // Speed: x 0.55 .. 0.66 (px per ms: a slow draw, a stroke, a flick).
        listOf(0.25f, 1.1f, 3.2f).forEachIndexed { i, v -> out += short(w * 0.55f + i * step, 0.6f, 0.3f, v) }
        // Turn: the two circles.
        val r = tall * 0.36f
        val cy = top + tall * 0.5f
        run {
            val cx = w * 0.77f
            out += Specimen((0..120).map { i ->
                val a = (i / 120f) * 2f * PI.toFloat()
                PenSample(cx, cy, timeMs = i * 6.0, pressure = 0.35f, tilt = 1.0f, azimuth = a)
            })
        }
        run {
            val cx = w * 0.9f
            out += Specimen((0..160).map { i ->
                val a = (i / 160f) * 2f * PI.toFloat()
                PenSample(cx + r * kotlin.math.cos(a), cy + r * sin(a), timeMs = i * 4.0, pressure = 0.45f, tilt = 1.0f,
                    azimuth = a + PI.toFloat())
            })
        }
        return out
    }

    /** The [sheet]'s group captions, in the order drawn. */
    val captions: List<Caption> = listOf(
        Caption("pressure", 0.04f + 2 * 0.045f + 0.01f),
        Caption("tilt", 0.32f + 1.5f * 0.045f + 0.01f),
        Caption("speed", 0.55f + 0.045f + 0.01f),
        Caption("turn", 0.835f),
    )

    private fun path(n: Int, x: (Float) -> Float, y: (Float) -> Float, pressure: (Float) -> Float, tilt: (Float) -> Float,
                     speed: (Float) -> Float): List<PenSample> {
        val count = n.coerceAtLeast(2)
        val out = ArrayList<PenSample>(count + 1)
        var time = 0.0
        var px = x(0f)
        var py = y(0f)
        for (i in 0..count) {
            val t = i / count.toFloat()
            val nx = x(t)
            val ny = y(t)
            time += hypot(nx - px, ny - py) / speed(t).coerceAtLeast(0.01f)
            px = nx; py = ny
            out += PenSample(nx, ny, timeMs = time, pressure = pressure(t).coerceIn(0f, 1f), tilt = tilt(t).coerceIn(0f, 1.5f),
                azimuth = LEAN)
        }
        return out
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
