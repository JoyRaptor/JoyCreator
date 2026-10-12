package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot

/**
 * JB-5.20e §K K15 — a pushed line stays a curve. The owner: "nudge needs to make sure it keeps the curve smooth".
 *
 * A nudge or a tip drag moves samples apart; where two neighbours end up further apart than [maxGapDoc], samples are
 * added between them on a Catmull–Rom curve through the four nearest samples, so the line replays as a curve and never
 * as a polygon. Each added sample blends its neighbours' pressure, tilt, lean (the short way round) and time, and keeps
 * the earlier one's barrel and tool. Existing samples are never moved or dropped, and nothing changes when no gap is
 * too long. Pure, no allocation beyond the result.
 */
object LineDensify {

    /** At most this many samples are added inside one gap (a guard against a wild drag). */
    const val MAX_PER_GAP = 64

    /**
     * [r] with gaps longer than [maxGapDoc] filled in; the same record when none is. A curve's chords are uneven, so a pass
     * can leave a chord a little over the limit on a tight bend: passes repeat (at most [MAX_PASSES]) until none is.
     */
    fun densify(r: StrokeRecord, maxGapDoc: Float): StrokeRecord {
        var out = r
        repeat(MAX_PASSES) {
            val next = pass(out, maxGapDoc)
            if (next === out) return out
            out = next
        }
        return out
    }

    /** At most this many passes (each fills what the last left over a hair too long). */
    const val MAX_PASSES = 4

    private fun pass(r: StrokeRecord, maxGapDoc: Float): StrokeRecord {
        val s = r.samples
        if (s.size < 2 || !(maxGapDoc > 0f)) return r
        var any = false
        for (i in 1 until s.size) if (hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y) > maxGapDoc) { any = true; break }
        if (!any) return r
        val out = ArrayList<PenSample>(s.size * 2)
        out.add(s[0])
        for (i in 1 until s.size) {
            val a = s[i - 1]
            val b = s[i]
            val gap = hypot(b.x - a.x, b.y - a.y)
            val n = minOf(MAX_PER_GAP, floor(gap / maxGapDoc).toInt())
            if (n >= 1) {
                val p0 = if (i >= 2) s[i - 2] else a
                val p3 = if (i + 1 < s.size) s[i + 1] else b
                for (k in 1..n) {
                    val t = k.toFloat() / (n + 1)
                    out.add(
                        a.copy(
                            x = cr(p0.x, a.x, b.x, p3.x, t),
                            y = cr(p0.y, a.y, b.y, p3.y, t),
                            timeMs = a.timeMs + (b.timeMs - a.timeMs) * t,
                            pressure = a.pressure + (b.pressure - a.pressure) * t,
                            tilt = lerpOrA(a.tilt, b.tilt, t),
                            azimuth = turn(a.azimuth, b.azimuth, t),
                        ),
                    )
                }
            }
            out.add(b)
        }
        return r.copy(samples = out)
    }

    /** Uniform Catmull–Rom through p1 → p2 at [t], with p0 and p3 as the neighbours. */
    internal fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun lerpOrA(a: Float, b: Float, t: Float): Float = if (a.isNaN() || b.isNaN()) a else a + (b - a) * t

    /** An angle from [a] toward [b] the short way round; NaN at either end keeps [a]. */
    private fun turn(a: Float, b: Float, t: Float): Float {
        if (a.isNaN() || b.isNaN()) return a
        var d = (b - a).toDouble()
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return (a + d * t).toFloat()
    }
}
