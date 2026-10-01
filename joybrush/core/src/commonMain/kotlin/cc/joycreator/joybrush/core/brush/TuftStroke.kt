package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.TuftShading
import cc.joycreator.joybrush.core.paint.TuftStamp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One stroke of the tuft engine (R9 §3B): a sable brush reduced to a handful of numbers that change as the pen moves,
 * and the footprints ([TuftStamp]) it leaves. ONE per stroke; feed it the SMOOTHED samples in order.
 *
 * What it keeps, and which of the owner's rulings each serves (R9 §3A):
 *
 *  - **belly width** `w` — pressure through a "hairline shelf" curve, so the first part of the pressure stays a
 *    needle and the belly opens after (R9 §3.1). Slow light lines thicken a little (O6); fast lines thin; a laid-over
 *    pen spreads it; the turn blot widens it (O9).
 *  - **contact length** `len` — how far the bristles trail behind the pen. Zero at the lightest touch, so light work is
 *    round and thin in every direction (O5); grows with pressure and with speed, so a stroke with a little pressure
 *    at speed is thin but LONG (O8).
 *  - **bend direction** `b` — where the trailing bristles point. It swings toward the travel direction over a
 *    distance, not instantly, so a switchback leaves the footprint side-on for a moment: the thick spot (O9).
 *  - **the built-in steadiness** — only the part of the hand's motion ACROSS the stroke is smoothed, over a distance
 *    that grows with the contact length. The mark never lags along the stroke, a hairline is never smoothed, and a
 *    pressed brush turns jitter into a calm drift of the whole line (O7).
 *  - **ink load** — full at every touch-down, drains with width × distance (O3). Nobody re-dips.
 *  - **dryness** — from speed × pressure, from an emptying load, and from a turn; lowered when slow (O6, O3, O9).
 *  - **sweep bias** — on a fast curve, ink goes to the inside and the outside runs dry (O10).
 *  - **splay** — bristles spread on jolts, heavy dry pressing and quick lifts, and close again over time — fast when
 *    wet, slowly when dry. A lift keeps the splay it had, so a slow lift is a clean needle and a quick one a rough,
 *    split end (O11).
 *  - **spatter** on jolts: a quick flick, a sudden press, a snap of direction (O1).
 *  - **stray hairs** that only touch when the belly is down, and come and go (O4).
 *
 * Everything comes from the samples and the [seed]: the same stroke gives the same footprints however the samples were
 * batched, which is what lets a stroke be replayed and re-brushed (blueprint §2).
 *
 * @param screenPerDoc the zoom the stroke was drawn at. Speed is judged in SCREEN px, as the hand feels it, so the brush
 *   behaves the same zoomed in or out.
 */
class TuftStroke(preset: BrushPreset, seed: Long, screenPerDoc: Float = 1f) {

    private val spec = preset.tuft
    private val tipR = (spec.tipPx.finiteOr(1f) / 2f).coerceIn(0.2f, 32f)
    private val bellyR = max(preset.size.base.finiteOr(12f) / 2f, tipR)
    private val bristleLen = 3f * bellyR
    private val zoom = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc else 1f
    private val cap = preset.opacity.base.finiteOr(1f).coerceIn(0f, 1f)
    private val gamma = 1f + 3f * u(spec.shelf)

    private val rng = SplitMix(seed)

    // ── state ──
    private var started = false
    private var rawX = 0f
    private var rawY = 0f
    private var fx = 0f              // the steadied (footprint) position
    private var fy = 0f
    private var timeMs = 0.0
    private var tx = 0f              // travel direction (unit), valid once hasDir
    private var ty = 0f
    private var hasDir = false
    private var bx = 0f              // bend direction: belly → trailing tip (unit)
    private var by = 0f
    private var speed = 0f           // doc px / s, eased
    private var pSlow = 0f
    private var vSlow = 0f
    private var sdx = 0f             // eased travel direction, for the direction-snap jolt
    private var sdy = 0f
    private var curvature = 0f       // signed, 1/doc px, eased over distance
    private var load = 1f
    private var splay = 0f
    private var jolt = 0f
    private var arc = 0f

    private var cur = Look()
    private var untilNext = 0f
    private var lastEmittedW = 0f
    private var lastBellyX = 0f
    private var lastBellyY = 0f
    /** How hard the brush has been mashed lately (pressed flat and spread): what makes a lift split (owner, 2026-09-30). */
    private var mashed = 0f

    private val hairs = ArrayList<Hair>()

    /** What one footprint looks like; interpolated between samples when stamps fall between them. */
    private class Look(
        var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var len: Float = 0f,
        var bx: Float = 1f, var by: Float = 0f, var rb: Float = 0f,
        var dry: Float = 0f, var splay: Float = 0f, var arc: Float = 0f,
        var pc: Float = 0f, var nx: Float = 0f, var ny: Float = 1f,
        /** Where the belly sits, from the pen: out along the lean when the pen is tilted (owner, 2026-09-30). */
        var ox: Float = 0f, var oy: Float = 0f,
        /** The sweep, as a vector toward the inside of the curve, scaled by how hard (O10). */
        var sx: Float = 0f, var sy: Float = 0f,
        /** How lightly the far end of a laid-over brush grazes the paper: wispy and scratchy there. */
        var graze: Float = 0f,
    ) {
        fun copy() = Look(x, y, w, len, bx, by, rb, dry, splay, arc, pc, nx, ny, ox, oy, sx, sy, graze)
    }

    private class Hair(
        val side: Float, val offset: Float, val phase: Float, val freq: Float, val threshold: Float, val stutter: Float,
    ) {
        var on = false
        var hx = 0f
        var hy = 0f
    }

    /** The footprints for these samples. Call with every smoothed sample, in order, however they are batched. */
    fun add(samples: List<PenSample>): List<TuftStamp> {
        val out = ArrayList<TuftStamp>()
        for (s in samples) if (s.isPlaceable) step(s, out)
        return out
    }

    /**
     * The pen changed without moving: pressed harder, or tilted and turned round its point (owner, 2026-09-30: "the point
     * never leaves, but the butt of the pen goes round it"). The smoother releases nothing for a pen that does not move,
     * so the caller hands such a sample here instead of to [add]; it is read AT the brush's current place.
     */
    fun dwell(sample: PenSample): List<TuftStamp> {
        val out = ArrayList<TuftStamp>()
        if (!started || !sample.timeMs.isFinite()) return out
        step(sample.copy(x = rawX, y = rawY), out)
        return out
    }

    /**
     * The lift (O11) and its spatter (O1). Call once, after the last [add]. A fast lift carries on a little along the
     * stroke as the bristles leave the paper; how pointed that end is depends on how spread the bristles still were.
     */
    fun finish(): List<TuftStamp> {
        val out = ArrayList<TuftStamp>()
        if (!started) return out
        val vN = speedN()
        val end = cur.copy()
        // A brush that was mashed flat may come off split, in two or three points (owner, 2026-09-30).
        val prongs = if (hasDir && mashed > 0.3f && rng.nextFloat() < u(spec.splay) * 0.9f * mashed) {
            if (rng.nextFloat() < 0.4f) 3 else 2
        } else 1
        if (prongs > 1) {
            val tail = max(u(spec.trail) * (speed * TAIL_SECONDS * (0.5f + vN)), end.w * 2.5f).coerceAtMost(8f * bellyR)
            for (i in 0 until prongs) {
                val across = ((i + 0.5f) / prongs - 0.5f) * 2f * end.w * 0.8f
                val turn = (i - (prongs - 1) / 2f) * 0.1f + (rng.nextFloat() - 0.5f) * 0.08f
                val c = cos(turn)
                val sn = sin(turn)
                val dx = tx * c - ty * sn
                val dy = tx * sn + ty * c
                val length = tail * (0.65f + 0.6f * rng.nextFloat())
                val startW = end.w / prongs * 1.15f
                val step = max(MIN_SPACING, 0.2f * min(startW, 4f))
                var d = 0f
                while (d <= length) {
                    val f = d / length
                    val k = end.copy()
                    k.x = end.x + end.nx * across + dx * d
                    k.y = end.y + end.ny * across + dy * d
                    k.w = tipR * 0.6f + (startW - tipR * 0.6f) * (1f - f).pow(2f)
                    k.ox = end.ox * (1f - f) / prongs
                    k.oy = end.oy * (1f - f) / prongs
                    k.len = end.len * (1f - 0.7f * f) / prongs
                    k.rb = min(k.rb, k.w)
                    k.dry = max(end.dry, f * 0.7f)
                    k.arc = end.arc + d + i * 97f
                    out.add(stampOf(k))
                    d += step
                }
            }
        } else if (hasDir && vN > 0.15f && end.pc > 0.03f && u(spec.trail) > 0f) {
            val tail = min(u(spec.trail) * (speed * TAIL_SECONDS * (0.5f + vN) + 2f * end.w), 8f * bellyR)
            if (tail > 0.5f) {
                val needle = min(end.w, tipR)
                val endR = needle + (end.w * 0.6f - needle) * end.splay
                val step = max(MIN_SPACING, 0.2f * min(end.w, 4f))
                var d = step
                while (d <= tail) {
                    val f = d / tail
                    val k = end.copy()
                    k.x = end.x + tx * d
                    k.y = end.y + ty * d
                    // A needle, not a wedge: the width falls away fast, then runs thin.
                    k.w = endR + (end.w - endR) * (1f - f).pow(2.4f)
                    k.len = end.len * (1f - 0.6f * f)
                    k.ox = end.ox * (1f - f)
                    k.oy = end.oy * (1f - f)
                    k.rb = min(k.rb, k.w)
                    k.dry = max(end.dry, f * (0.5f + 0.5f * end.splay))
                    k.arc = end.arc + d
                    out.add(stampOf(k))
                    d += step
                }
            }
        }
        // The lift throws ink when it is quick or jolty, from what is left in the brush.
        val strength = u(spec.spatter) * (0.3f + 0.7f * load) * max(jolt, smooth(0.35f, 1f, vN))
        val drops = floor(strength * 6f + rng.nextFloat()).toInt()
        for (i in 0 until drops) out.add(droplet(end, max(jolt, vN)))
        return out
    }

    // ── one sample ───────────────────────────────────────────────────────────

    private fun step(s: PenSample, out: MutableList<TuftStamp>) {
        // A finger or a mouse reports full pressure all the time; read it as line weight, not as pressing flat.
        val p = if (s.tool == Tool.FINGER || s.tool == Tool.MOUSE) NO_PRESSURE else s.pressure.finiteOr(1f).coerceIn(0f, 1f)
        if (!started) {
            started = true
            rawX = s.x; rawY = s.y; fx = s.x; fy = s.y
            timeMs = s.timeMs
            pSlow = p
            setUpHairs()
            cur = look(p, s, mis = 0f)
            emit(cur, out)
            return
        }
        val dxr = s.x - rawX
        val dyr = s.y - rawY
        val ds = sqrt(dxr * dxr + dyr * dyr)
        val dt = max(0.0, s.timeMs - timeMs).toFloat()
        timeMs = s.timeMs
        rawX = s.x; rawY = s.y

        // Speed, over time, as BrushDabber measures it.
        if (dt > 0f) {
            val raw = ds / dt * 1000f
            if (raw.isFinite()) speed += (raw - speed) * (1f - exp(-dt / SPEED_TAU_MS))
        }
        val vN = speedN()

        // Travel direction and how fast it is turning.
        // The travel direction is eased over a short DISTANCE, so a hand's tremor — which flips the raw direction
        // from one sample to the next — does not read as the stroke turning. A real turn gets through in a few px.
        if (ds > 1e-4f) {
            val nx = dxr / ds
            val ny = dyr / ds
            if (hasDir) {
                val ease = 1f - exp(-ds / (DIR_EASE_PX + 0.5f * cur.w + 0.3f * cur.len))
                val turn = atan2(tx * ny - ty * nx, tx * nx + ty * ny) * ease
                val c = cos(turn)
                val sn = sin(turn)
                val ntx = tx * c - ty * sn
                val nty = tx * sn + ty * c
                tx = ntx; ty = nty
                val k = turn / ds
                curvature += (k - curvature) * (1f - exp(-ds / (3f + 0.5f * cur.w)))
            } else {
                // First movement: the bristles trail straight behind.
                tx = nx; ty = ny
                bx = -nx; by = -ny
                sdx = nx; sdy = ny
            }
            hasDir = true
        }

        // The jolt (O1): a sudden press, a sudden change of speed, a snap of direction — against slow trackers.
        val kSlow = if (dt > 0f) 1f - exp(-dt / SLOW_TAU_MS) else 0f
        val jPress = max(0f, p - pSlow) * 2.2f
        val jSpeed = abs(vN - vSlow) * 1.8f
        val jTurn = if (hasDir) (1f - (tx * sdx + ty * sdy).coerceIn(-1f, 1f)) * 1.25f * min(1f, vN * 2f) else 0f
        jolt = max(jPress, max(jSpeed, jTurn)).coerceIn(0f, 1f)
        val lifting = max(0f, pSlow - p) * 2f
        pSlow += (p - pSlow) * kSlow
        vSlow += (vN - vSlow) * kSlow
        if (hasDir) {
            sdx += (tx - sdx) * kSlow; sdy += (ty - sdy) * kSlow
            val n = sqrt(sdx * sdx + sdy * sdy)
            if (n > 1e-6f) { sdx /= n; sdy /= n }
        }

        // The bristles swing toward trailing the stroke (O9), over a distance: stiff sable swings fast.
        val pc = curve(p)
        if (hasDir && ds > 0f) {
            // Only the travel pulls the tip. The tilt moves the BELLY (see look), never this direction: adding the two made
            // them cancel when the stroke ran against the lean, and the brush flipped over (the owner's jogging 3s).
            val gx = -tx
            val gy = -ty
            val lag = bristleLen * (0.12f + 0.8f * (1f - u(spec.snap))) * (0.4f + 0.6f * pc) + 1f
            val k = 1f - exp(-ds / lag)
            val ang = atan2(bx * gy - by * gx, bx * gx + by * gy)
            val a = ang * k
            val c = cos(a)
            val sn = sin(a)
            val nbx = bx * c - by * sn
            val nby = bx * sn + by * c
            bx = nbx; by = nby
        }
        val mis = if (hasDir) ((1f - (bx * -tx + by * -ty)) / 2f).coerceIn(0f, 1f) else 0f

        // Ink drains with the width laid down (O3).
        val w0 = cur.w.coerceAtLeast(tipR)
        val capacity = 2f * bellyR * (30f + 900f * u(spec.ink) * u(spec.ink))
        // A pressed-flat belly lays more ink, but not in proportion: the bristles spread the same load thinner. A nearly
        // empty brush gives up what it has slowly, so it keeps dry-brushing for a long while instead of stopping.
        load = max(0f, load - ds * (w0 / bellyR).pow(0.6f) * (0.25f + 0.75f * pc) * (0.35f + 0.65f * load) / capacity)

        // Splay (O11): opens quickly, closes over time — fast when wet, slowly when dry.
        val engaged = smooth(0.03f, 0.25f, p)
        val dryNow = dryness(vN, pc, mis, engaged)
        val target = (u(spec.splay) * (0.9f * jolt + 0.5f * dryNow + 0.3f * pc * pc * (1f - 0.5f * load) + 1.2f * lifting * engaged))
            .coerceIn(0f, 1f)
        val tau = if (target > splay) SPLAY_OPEN_MS else SPLAY_CLOSE_MS + SPLAY_CLOSE_DRY_MS * (1f - load)
        if (dt > 0f) splay += (target - splay) * (1f - exp(-dt / tau))

        // The steadied position: only the motion ACROSS the stroke is smoothed, over the contact's length (O7).
        val prevFx = fx
        val prevFy = fy
        if (hasDir) {
            val ex = rawX - fx
            val ey = rawY - fy
            val along = ex * tx + ey * ty
            val cx = ex - along * tx
            val cy = ey - along * ty
            val lenNow = cur.len
            val lambda = u(spec.steady) * (0.7f * lenNow + 0.6f * cur.w)
            val kc = if (lambda < 0.05f) 1f else 1f - exp(-ds / lambda)
            fx += along * tx + cx * kc
            fy += along * ty + cy * kc
            // Never let the mark wander off the pen: the leftover across the stroke is bounded.
            val rx = rawX - fx
            val ry = rawY - fy
            val ra = rx * tx + ry * ty
            val rcx = rx - ra * tx
            val rcy = ry - ra * ty
            val rc = sqrt(rcx * rcx + rcy * rcy)
            val limit = max(0.5f, 0.8f * cur.w + 0.3f * lenNow)
            if (rc > limit) {
                val f = 1f - limit / rc
                fx += rcx * f
                fy += rcy * f
            }
        } else {
            fx = rawX; fy = rawY
        }
        val moved = sqrt((fx - prevFx) * (fx - prevFx) + (fy - prevFy) * (fy - prevFy))
        arc += moved

        val prev = cur
        val next = look(p, s, mis)
        cur = next

        // Footprints along the steadied path, spaced by the width, each one a blend of the two looks.
        if (moved > 1e-4f) {
            var along = untilNext
            while (along <= moved) {
                val f = along / moved
                val k = lerp(prev, next, f)
                emit(k, out)
                along += max(MIN_SPACING, SPACING * min(k.w, 2f * bellyR))
            }
            untilNext = along - moved
        } else {
            // Still: pressing down grows the blot, and turning the pen swings the belly round its point.
            val bx0 = next.x + next.ox
            val by0 = next.y + next.oy
            val swung = sqrt((bx0 - lastBellyX) * (bx0 - lastBellyX) + (by0 - lastBellyY) * (by0 - lastBellyY))
            if (next.w > lastEmittedW * 1.08f + 0.2f || swung > max(0.5f, 0.2f * next.w)) emit(next, out)
        }
        mashed = max(mashed * (1f - min(1f, moved / (6f * bellyR + 1f))), smooth(FLAT_FROM, 1f, p) * u(spec.flatten) + 0.5f * splay)

        // Spatter on a jolt (O1): the harder the jolt and the fuller the brush, the likelier.
        val rate = u(spec.spatter) * (0.3f + 0.7f * load) * jolt * jolt * SPATTER_PER_MS
        // Not at touch-down: a brush that has only just met the paper has nothing to throw yet.
        val landed = arc > 2f * cur.w + 4f
        val chance = if (landed && jolt > 0.25f && dt > 0f) 1f - exp(-rate * dt) else 0f
        if (rng.nextFloat() < chance) {
            val n = 1 + floor(rng.nextFloat() * 3f * jolt).toInt()
            for (i in 0 until n) out.add(droplet(next, jolt))
        }

    }

    /** The look of the brush now. */
    private fun look(p: Float, s: PenSample, mis: Float): Look {
        val pc = curve(p)
        val vN = speedN()
        val engaged = smooth(0.03f, 0.25f, p)
        val slowness = 1f - smooth(0.02f, 0.12f, vN)

        // Three zones (owner, 2026-09-30): the hairline shelf for detail, the line weight up to the belly, then pressing
        // flat spreads the bristles to their widest for shadows.
        val flat = u(spec.flatten) * FLATTEN_MAX * smooth(FLAT_FROM, 1f, p)
        // The tilt (owner, 2026-09-30): the point stays at the pen, and the body of the brush lies out along the lean,
        // longer the steeper the pen. Even a light touch lays the side down, almost as big — but only grazing.
        val tf = tiltAmount(s.tilt)
        val lay = if (s.azimuth.isFinite()) smooth(0.25f, 0.85f, tf) else 0f
        val tiltK = 0.3f + 1.3f * u(spec.tilt)
        val pressW = tipR + (bellyR - tipR) * pc + bellyR * flat * (1f - 0.6f * lay)
        val sideW = bellyR * lay * tiltK * (0.35f + 0.35f * p)
        var w = max(pressW, sideW)
        val ext = bristleLen * lay * tiltK * (0.45f + 0.4f * p) * (1f + 0.4f * flat * lay)
        val ox = if (lay > 0f) cos(s.azimuth) * ext else 0f
        val oy = if (lay > 0f) sin(s.azimuth) * ext else 0f
        val graze = u(spec.graze) * lay * (1f - p).pow(1.3f)
        // The sign carries which end grazes to the shader (TuftStamp.graze): negative = the point end.
        val grazeSigned = if (spec.grazeAtPoint) -graze else graze
        w *= 1f - u(spec.speedThin) * 0.4f * smooth(0.15f, 1f, vN)
        w += u(spec.settle) * slowness * (0.15f * w + 0.25f * tipR)
        w *= 1f + u(spec.corner) * 1.2f * mis * engaged
        w = max(w, tipR * 0.5f)

        var len = bristleLen * engaged * (0.12f + 0.4f * pc + u(spec.trail) * 0.9f * min(vN, 1f) * (0.4f + 0.6f * engaged))
        len *= 1f + 0.5f * flat / FLATTEN_MAX
        // The bristles can only trail over paper the brush has already crossed (no tail behind the touch-down).
        len = min(len, min(bristleLen * 2.5f, arc * 0.8f))

        val needle = min(w, tipR)
        val rb = needle + (w * 0.7f - needle) * splay

        val bLen = sqrt(bx * bx + by * by)
        val ubx = if (bLen > 1e-6f) bx / bLen else 1f
        val uby = if (bLen > 1e-6f) by / bLen else 0f
        // Sweep (O10): toward the inside of the curve, as hard as it is being thrown there. Turned into a side of the
        // footprint in stampOf, against the footprint's own axis.
        var sx = 0f
        var sy = 0f
        if (hasDir && curvature != 0f) {
            val screenV = speed * zoom
            val aLat = screenV * screenV * abs(curvature) / zoom
            val strength = u(spec.sweep) * smooth(0.08f, 1f, aLat / SWEEP_REF)
            val sign = if (curvature > 0f) 1f else -1f
            sx = -ty * sign * strength
            sy = tx * sign * strength
        }

        return Look(
            x = fx, y = fy, w = w, len = len, bx = ubx, by = uby, rb = rb,
            dry = (dryness(vN, pc, mis, engaged) + 0.3f * graze).coerceIn(0f, 1f), splay = splay, arc = arc,
            pc = pc, nx = if (hasDir) -ty else 0f, ny = if (hasDir) tx else 1f, ox = ox, oy = oy, sx = sx, sy = sy, graze = grazeSigned,
        )
    }

    private fun dryness(vN: Float, pc: Float, mis: Float, engaged: Float): Float {
        val dSpeed = u(spec.dry) * smooth(0.3f, 1f, vN) * (0.3f + 0.7f * pc)
        // Dryness creeps in early and grows as the brush empties, but never to nothing: an empty brush still dry-brushes.
        val dLoad = LOAD_DRY_MAX * (1f - load).pow(1.2f)
        val dCorner = u(spec.corner) * mis * 0.7f * engaged * min(1f, vN * 3f)
        var d = 1f - (1f - dSpeed) * (1f - dLoad) * (1f - dCorner)
        d *= 1f - u(spec.settle) * 0.85f * (1f - smooth(0.02f, 0.12f, vN))
        return d.coerceIn(0f, 1f)
    }

    private fun emit(k: Look, out: MutableList<TuftStamp>) {
        out.add(stampOf(k))
        lastEmittedW = k.w
        lastBellyX = k.x + k.ox
        lastBellyY = k.y + k.oy
        emitHairs(k, out)
    }

    private fun stampOf(k: Look): TuftStamp {
        val len = max(k.len, MIN_LEN)
        val ax = k.x + k.ox
        val ay = k.y + k.oy
        var bx = k.x + k.bx * len
        var by = k.y + k.by * len
        if ((bx - ax) * (bx - ax) + (by - ay) * (by - ay) < MIN_LEN * MIN_LEN) { bx = ax + k.bx * MIN_LEN; by = ay + k.by * MIN_LEN }
        // The side the ink is thrown to, against the footprint's axis (belly → tip): jb_tuft.frag's +normal side.
        val axl = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(1e-6f)
        val nx = -(by - ay) / axl
        val ny = (bx - ax) / axl
        val bias = (k.sx * nx + k.sy * ny).coerceIn(-1f, 1f)
        return TuftStamp(
            ax = ax, ay = ay, bx = bx, by = by,
            ra = k.w, rb = k.rb.coerceIn(0.05f, k.w), flow = 1f, cap = cap,
            dry = k.dry, bias = bias, splay = k.splay, arc = k.arc, kind = TuftStamp.KIND_FOOTPRINT, graze = k.graze,
        )
    }

    // ── stray hairs (O4) ─────────────────────────────────────────────────────

    private fun setUpHairs() {
        val strays = u(spec.strays)
        val count = min(MAX_HAIRS, floor(strays * 3.5f + rng.nextFloat() * strays * 2.5f).toInt())
        for (i in 0 until count) {
            val side = if (rng.nextFloat() < 0.5f) -1f else 1f
            // Some hairs run long, some catch in short bits, some stutter on the paper's tooth (owner, 2026-09-30).
            val stutterDraw = rng.nextFloat()
            hairs.add(Hair(
                side = side,
                offset = 0.04f + rng.nextFloat() * 0.4f,
                phase = rng.nextFloat() * 1000f,
                freq = 1f / (bellyR * (1f + rng.nextFloat() * 8f)),
                threshold = 0.35f + rng.nextFloat() * 0.35f,
                stutter = if (stutterDraw < 0.45f) 0f else 0.25f + 0.55f * rng.nextFloat(),
            ))
        }
    }

    private fun emitHairs(k: Look, out: MutableList<TuftStamp>) {
        if (hairs.isEmpty()) return
        val belly = k.w > 0.55f * bellyR && k.w > 2f
        // Which way the bristles are swung across the stroke: a hair on that side presses into the paper.
        val swing = if (hasDir) (k.bx * ty - k.by * tx) else 0f
        val hairR = max(0.35f, tipR * 0.45f)
        for (h in hairs) {
            // Two rhythms: long runs, broken now and then into shorter bits.
            val gate = 0.65f * valueNoise(k.arc * h.freq + h.phase) + 0.35f * valueNoise(k.arc * h.freq * 4f + h.phase * 1.7f) +
                0.25f * h.side * swing
            val on = belly && gate > h.threshold
            if (!on) { h.on = false; continue }
            val px = k.x + k.ox + k.nx * h.side * k.w * (1f + h.offset) + k.bx * k.len * 0.35f
            val py = k.y + k.oy + k.ny * h.side * k.w * (1f + h.offset) + k.by * k.len * 0.35f
            if (h.on) {
                out.add(TuftStamp(ax = px, ay = py, bx = h.hx, by = h.hy, ra = hairR, rb = hairR, cap = cap, dry = h.stutter,
                    kind = TuftStamp.KIND_HAIR))
            }
            h.on = true
            h.hx = px; h.hy = py
        }
    }

    // ── spatter (O1) ─────────────────────────────────────────────────────────

    /** One droplet thrown ahead of the brush, a little outward on a curve; small ones common, big ones rare. */
    private fun droplet(k: Look, force: Float): TuftStamp {
        val spread = (rng.nextFloat() - 0.5f) * 0.6f
        var dx = if (hasDir) tx else 1f
        var dy = if (hasDir) ty else 0f
        val c = cos(spread)
        val sn = sin(spread)
        val rx = dx * c - dy * sn
        val ry = dx * sn + dy * c
        dx = rx; dy = ry
        // Out of the curve, away from the inside the ink was thrown to.
        dx -= k.sx * 0.4f
        dy -= k.sy * 0.4f
        val n = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
        dx /= n; dy /= n
        val vN = speedN()
        val dist = k.w + 2f + (0.5f + rng.nextFloat() * 2.5f) * bellyR * (0.4f + vN)
        val r = max(0.35f, bellyR * 0.22f * rng.nextFloat().pow(3) * (0.6f + force))
        val tail = r * (0.5f + 3f * vN * rng.nextFloat())
        val cx = k.x + dx * dist
        val cy = k.y + dy * dist
        return TuftStamp(ax = cx, ay = cy, bx = cx - dx * tail, by = cy - dy * tail, ra = r, rb = r * 0.45f, cap = cap,
            kind = TuftStamp.KIND_PLAIN)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun speedN(): Float = (speed * zoom / SPEED_REF).coerceIn(0f, 1.5f)

    /** The hairline shelf: the first part of the pressure stays thin, then the belly opens. */
    private fun curve(p: Float): Float = p.coerceIn(0f, 1f).pow(gamma)

    private fun lerp(a: Look, b: Look, f: Float): Look {
        fun m(x: Float, y: Float) = x + (y - x) * f
        var nbx = m(a.bx, b.bx)
        var nby = m(a.by, b.by)
        val n = sqrt(nbx * nbx + nby * nby)
        if (n > 1e-6f) { nbx /= n; nby /= n } else { nbx = b.bx; nby = b.by }
        return Look(
            x = m(a.x, b.x), y = m(a.y, b.y), w = m(a.w, b.w), len = m(a.len, b.len), bx = nbx, by = nby,
            rb = m(a.rb, b.rb), dry = m(a.dry, b.dry), splay = m(a.splay, b.splay),
            arc = m(a.arc, b.arc), pc = m(a.pc, b.pc), nx = b.nx, ny = b.ny,
            ox = m(a.ox, b.ox), oy = m(a.oy, b.oy), sx = m(a.sx, b.sx), sy = m(a.sy, b.sy), graze = m(a.graze, b.graze),
        )
    }

    /** A smooth 1-D noise in 0..1, the same on every platform (a hashed lattice with a cubic blend). */
    private fun valueNoise(x: Float): Float {
        val i = floor(x)
        val f = x - i
        val a = hash(i.toInt())
        val b = hash(i.toInt() + 1)
        val t = f * f * (3f - 2f * f)
        return a + (b - a) * t
    }

    private fun hash(i: Int): Float {
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun tiltAmount(tilt: Float): Float = if (tilt.isFinite()) sin(tilt.coerceIn(0f, 1.5707964f)) else 0f

    companion object {
        /**
         * The whole-stroke numbers `jb_tuft.frag` takes for [preset]: how fine the streaks are (the Bristles slider),
         * how long they run (longer on a bigger brush), the Paper tooth slider, and a per-stroke offset from [seed].
         */
        fun shading(preset: BrushPreset, seed: Long): TuftShading {
            val t = preset.tuft
            val bellyR = max(preset.size.base.finiteOr(12f) / 2f, 0.5f)
            val fine = u(t.bristles)
            return TuftShading(
                bristles = 8f + 40f * fine,
                streakPx = max(6f, bellyR * (1.5f + 4f * (1f - fine))),
                tooth = u(t.tooth),
                seed = (SplitMix(seed xor SHADING_SALT).nextFloat() * 97f),
                action = u(t.action),
                paperAsset = PAPER_TOOTH,
                paperPitchPx = GrainMath.pitchPxFor(PAPER_TOOTH_SCALE),
            )
        }

        /** The page's tooth: the fine cloud, at the Pencil's paper scale. */
        const val PAPER_TOOTH = "cloud_fine_256.png"
        const val PAPER_TOOTH_SCALE = 1.5f
        private const val SHADING_SALT = 0x7F7L

        /** At the Press flat slider's top, the belly spreads to this many MORE belly widths. */
        private const val FLATTEN_MAX = 4f
        /** Where on the pressure the press-flat zone begins. */
        private const val FLAT_FROM = 0.72f
        private const val MAX_HAIRS = 5
        /** The driest an empty brush gets from its load alone: still enough ink to dry-brush. */
        private const val LOAD_DRY_MAX = 0.72f
        /** What a finger or a mouse draws at: line weight. */
        private const val NO_PRESSURE = 0.6f

        /** Screen px per second that counts as "fast" (speed 1). */
        private const val SPEED_REF = 2500f
        private const val SPEED_TAU_MS = 50f
        /** The travel direction's easing distance, px, before the brush's own size is added. */
        private const val DIR_EASE_PX = 2f
        private const val SLOW_TAU_MS = 60f
        private const val SPLAY_OPEN_MS = 8f
        private const val SPLAY_CLOSE_MS = 35f
        private const val SPLAY_CLOSE_DRY_MS = 220f
        /** Screen px/s² of sideways acceleration that counts as a hard sweep. */
        private const val SWEEP_REF = 15000f
        private const val SPATTER_PER_MS = 0.08f
        /** How long, in seconds of travel, a fast lift carries on as the bristles leave the paper. */
        private const val TAIL_SECONDS = 0.035f
        /** Footprint spacing as a fraction of the belly half-width, and its floor in px. */
        private const val SPACING = 0.2f
        private const val MIN_SPACING = 0.3f
        /** A footprint always keeps a direction, even when it is round. */
        private const val MIN_LEN = 0.05f

        private fun u(v: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
