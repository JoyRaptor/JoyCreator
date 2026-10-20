package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * One copy of a stroke under a symmetry guide: the map that carries the original onto it.
 *
 * The map is the affine one `p' = L·p + t`, with `L = [[xx, xy], [yx, yy]]`. It is stored flat and
 * plain because that is what the maths is: a mirror is `L = 2uuᵀ − I` about the unit direction `u`
 * of the line, a rotation is `L = [[cosθ, −sinθ], [sinθ, cosθ]]` about the middle of the line, and
 * a kaleidoscope copy is one after the other.
 *
 * @property rotation the rotation COMPOSED INTO this copy, radians. For a rotation copy it is
 *   the copy's own turn. For a kaleidoscope copy it is the turn that was composed in with the
 *   mirror, and the copy's own axis is [mirrorAxis] — its linear part is a REFLECTION, so a
 *   caller that turns a direction by this number would turn a kaleidoscope copy twice. Use the
 *   copy's linear part for the direction and this number only for the turn it carries.
 * @property mirrored true when the copy is a reflection, i.e. when its map reverses the
 *   handedness: `det(L) < 0`. A plain mirror and a kaleidoscope copy are both reflections.
 * @property mirrorAxis the angle of the line the copy is mirrored about, radians, or null for a
 *   copy that mirrors nothing (a turn). For a plain mirror that is the guide's own line; for a
 *   kaleidoscope copy it is the guide's line turned by HALF the copy's rotation, because that is
 *   the line the copy is really mirrored about. A line's angle is only known modulo PI.
 */
data class SymmetryCopy(
    val xx: Double,
    val xy: Double,
    val yx: Double,
    val yy: Double,
    val tx: Double,
    val ty: Double,
    val rotation: Double,
    val mirrored: Boolean,
    val mirrorAxis: Double?,
)

/**
 * The geometry of a symmetry guide (JB-2.12a): where the copies of a stroke are, and what each
 * copy does to the pen's channels.
 *
 * A [Guide.Symmetry] is a LINE from `a` to `b` and a number of copies. Lane 0 is always the
 * original stroke, so [copies] hands back the OTHER lanes only:
 *
 * - `count = 1` is off: no copies at all.
 * - `count = 2` is one mirror across the line AB: TWO lanes in all, the original and the mirror.
 *   The mirror on top (`kaleido`) changes NOTHING here: there is no radial copy to mirror, and
 *   mirroring the mirror would land back on the original.
 * - `count = 3..30` is that many copies around the line's MIDPOINT, the original among them: the
 *   rotations by `2πk/N` for `k = 1..N-1`. With `kaleido` on top, every one of those rotations
 *   also gets a mirrored twin (`R_k ∘ M`), so the guide makes `2N` lanes in all.
 *
 * All lengths are DOCUMENT px, all angles RADIANS, and the document's y grows DOWN — so a positive
 * angle turns the same way on the page as it does on the screen.
 *
 * Every function here is pure and total: rubbish in gives nothing out, never an exception and
 * never a NaN. A guide with a point in it, a line with no direction or a count of zero is not a
 * crash, it is a guide that draws nothing. A count over [MAX_COUNT] is capped, not obeyed: the
 * cap is the ONE number the rule has, and the copy of a guide that wants a billion lanes is
 * thirty.
 */
object SymmetryMath {

    /** How close a copy's map may be to the identity before it counts as the identity. */
    private const val IDENTITY_EPS = 1.0e-9

    /** How close a line's angle must be to a multiple of 45° for it to snap: 3°, in radians. */
    const val SNAP_TOLERANCE = 3.0 * PI / 180.0

    /** The most copies a guide may make: 30 around, 60 with the mirror on top of them. */
    const val MAX_COUNT = 30

    /**
     * The middle of the line AB, where the radial copies turn around, or null when there is no
     * middle to speak of: the two ends are the same point, or one of them is not a number.
     */
    fun centre(g: Guide.Symmetry): Pt? {
        if (!finite(g.a) || !finite(g.b)) return null
        if (g.a.x == g.b.x && g.a.y == g.b.y) return null
        return Pt((g.a.x + g.b.x) / 2.0, (g.a.y + g.b.y) / 2.0)
    }

    /**
     * The angle of the line AB, radians, or null when the line has no direction: the two ends are
     * the same point, or one of them is not a number.
     */
    fun lineAngle(g: Guide.Symmetry): Double? {
        if (!finite(g.a) || !finite(g.b)) return null
        val dx = g.b.x - g.a.x
        val dy = g.b.y - g.a.y
        if (dx == 0.0 && dy == 0.0) return null
        return atan2(dy, dx)
    }

    /**
     * The copies of [g] — the lanes BESIDE the original, never the original itself.
     *
     * The list is empty for a guide that is off (`count` 1 or less), for a line with no direction
     * (`a == b`) and for a point that is not a number. A copy that comes out as the identity is
     * dropped, so a degenerate input can never add a lane that draws the same stroke twice.
     *
     * The count is CAPPED here, at the one place that reads it, with [MAX_COUNT]: a guide that
     * asks for a billion copies gets the thirty it is allowed and not a billion loops. The
     * settings refuse a count over the cap in words; the maths here simply caps it, so a guide
     * that is handed in by hand cannot hang the drawing.
     */
    fun copies(g: Guide.Symmetry): List<SymmetryCopy> {
        val n = g.count.coerceIn(1, MAX_COUNT)
        if (n <= 1) return emptyList()
        if (!finite(g.a) || !finite(g.b)) return emptyList()
        val dx = g.b.x - g.a.x
        val dy = g.b.y - g.a.y
        val len = hypot(dx, dy)
        if (!(len > 0.0) || !len.isFinite()) return emptyList()
        val ux = dx / len
        val uy = dy / len
        val out = ArrayList<SymmetryCopy>()

        if (n == 2) {
            // The mirror alone — two lanes in all. Kaleido is meaningless here: there is no
            // radial copy to mirror, and mirroring the mirror would land back on the original.
            val m = mirror(g.a, ux, uy)
            if (!isIdentity(m)) out.add(m)
            return out
        }

        val c = centre(g) ?: return emptyList()
        for (k in 1 until n) {
            val r = rotation(c, 2.0 * PI * k / n)
            if (!isIdentity(r)) out.add(r)
        }
        if (g.kaleido) {
            for (k in 0 until n) {
                // The mirror first, then the turn: R_k ∘ M.
                val composed = compose(rotation(c, 2.0 * PI * k / n), mirror(g.a, ux, uy))
                if (!isIdentity(composed)) out.add(composed)
            }
        }
        return out
    }

    /**
     * The sample to draw instead of [s] under the copy [c].
     *
     * The channels follow the same rules the Select box's own transform uses
     * (`cc.joycreator.joybrush.core.select.LineTransform`):
     *
     * - `x` and `y` go through the map.
     * - `azimuth` (which way the pen leans) goes through the map's LINEAR part, applied to the
     *   direction itself: `a + θ` for a rotation, `2φ − a` for a mirror. NaN stays NaN, and so
     *   does an infinite reading: an angle that is not a number is not a direction.
     * - `barrel` goes through the SAME rule, because the barrel is the TIP'S DIRECTION as far as
     *   the brush is concerned (see [twist]): `b + θ` for a rotation, `2φ − b` for a mirror.
     * - `tilt` (how far the pen leans) keeps its magnitude: leaning the same amount to the other
     *   side is the same lean. `pressure`, `timeMs`, `tool` and `predicted` are untouched.
     *
     * With [symmetric] off — the owner's "every copy draws its own" — the lean and the twist are
     * left exactly as they were and only the position is copied: the copies share the shape of the
     * stroke but not its randomness.
     *
     * A sample that is not placeable (a position or a time that is not a number) comes back as the
     * very same object.
     */
    fun apply(s: PenSample, c: SymmetryCopy, symmetric: Boolean): PenSample {
        if (!s.isPlaceable) return s
        val x = s.x.toDouble()
        val y = s.y.toDouble()
        val nx = c.xx * x + c.xy * y + c.tx
        val ny = c.yx * x + c.yy * y + c.ty
        if (!nx.isFinite() || !ny.isFinite()) return s
        val azimuth = if (symmetric) lean(s.azimuth, c) else s.azimuth
        val barrel = if (symmetric) twist(s.barrel, c) else s.barrel
        return s.copy(x = nx.toFloat(), y = ny.toFloat(), azimuth = azimuth, barrel = barrel)
    }

    /**
     * The nearest of 0°, 45°, 90° and 135° to [rad] — as radians — when it is within
     * [SNAP_TOLERANCE] (3°), and null when it is not.
     *
     * A line has no direction, so the angle is folded onto 0..180° first: −1° and 179° are the
     * same line, and both must be able to snap to it. The answer is folded back the same way: the
     * line at 180° IS the line at 0°, so the answer for it is 0 and not PI.
     *
     * Pure, so the overlay can use it later.
     */
    fun snapAngle(rad: Double): Double? {
        if (!rad.isFinite()) return null
        var a = rad % PI
        if (a < 0.0) a += PI
        val step = PI / 4.0
        val k = round(a / step)
        if (k < 0.0 || k > 4.0) return null
        val snapped = k * step
        if (abs(a - snapped) > SNAP_TOLERANCE) return null
        return if (snapped >= PI) 0.0 else snapped
    }

    // ── the maps ───────────────────────────────────────────────────────────────

    /**
     * The mirror across the line through [a] in the unit direction (ux, uy).
     *
     * The linear part of a reflection in a line is `2uuᵀ − I`, and the map is
     * `p' = a + L(p − a)`, i.e. `t = a − L·a`: the mirror is about the line, not about the
     * origin, which is the whole difference between mirroring a page and mirroring a picture.
     */
    private fun mirror(a: Pt, ux: Double, uy: Double): SymmetryCopy {
        val xx = 2.0 * ux * ux - 1.0
        val xy = 2.0 * ux * uy
        val yx = xy
        val yy = 2.0 * uy * uy - 1.0
        val tx = a.x - (xx * a.x + xy * a.y)
        val ty = a.y - (yx * a.x + yy * a.y)
        return SymmetryCopy(xx, xy, yx, yy, tx, ty, 0.0, true, atan2(uy, ux))
    }

    /** The rotation by [theta] about [c]. */
    private fun rotation(c: Pt, theta: Double): SymmetryCopy {
        val cs = cos(theta)
        val sn = sin(theta)
        val xx = cs
        val xy = -sn
        val yx = sn
        val yy = cs
        val tx = c.x - (xx * c.x + xy * c.y)
        val ty = c.y - (yx * c.x + yy * c.y)
        return SymmetryCopy(xx, xy, yx, yy, tx, ty, theta, false, null)
    }

    /**
     * [outer] after [inner]: the map that puts [inner] first and then [outer].
     *
     * ONLY VALID FOR `R(θ) ∘ M(φ)` — a turn AFTER a mirror. The axis rule below is the one for
     * that shape: a turn after a mirror is still a mirror, about the mirrored line turned by HALF
     * the turn, and that is the axis the copy is really reflected in.
     *
     * It is NOT a general composition rule and must not be used as one: two mirrors compose to a
     * TURN (not a mirror), and a mirror after a turn has axis `φ − θ/2`, not `φ + θ/2`. The only
     * call site is the kaleidoscope's `R_k ∘ M`, and it is private so that no other shape can
     * reach it by accident.
     */
    private fun compose(outer: SymmetryCopy, inner: SymmetryCopy): SymmetryCopy {
        val xx = outer.xx * inner.xx + outer.xy * inner.yx
        val xy = outer.xx * inner.xy + outer.xy * inner.yy
        val yx = outer.yx * inner.xx + outer.yy * inner.yx
        val yy = outer.yx * inner.xy + outer.yy * inner.yy
        val tx = outer.xx * inner.tx + outer.xy * inner.ty + outer.tx
        val ty = outer.yx * inner.tx + outer.yy * inner.ty + outer.ty
        val mirrored = outer.mirrored || inner.mirrored
        val axis = if (mirrored) (inner.mirrorAxis ?: 0.0) + outer.rotation / 2.0 else null
        return SymmetryCopy(xx, xy, yx, yy, tx, ty, outer.rotation + inner.rotation, mirrored, axis)
    }

    /** Whether [c] is the identity map, to [IDENTITY_EPS]. */
    private fun isIdentity(c: SymmetryCopy): Boolean =
        abs(c.xx - 1.0) < IDENTITY_EPS && abs(c.xy) < IDENTITY_EPS &&
            abs(c.yx) < IDENTITY_EPS && abs(c.yy - 1.0) < IDENTITY_EPS &&
            abs(c.tx) < IDENTITY_EPS && abs(c.ty) < IDENTITY_EPS

    // ── the channels ───────────────────────────────────────────────────────────

    /**
     * The lean direction through the copy's linear part: the direction itself is turned, not the
     * angle, so a mirror really does put the lean on the other side.
     *
     * A reading that is not a number stays as it was: a NaN is how an absent sensor is spelled,
     * and an INFINITY is not a direction at all (`cos(∞)` is NaN, and a NaN would be handed to the
     * brush as a lean).
     */
    private fun lean(azimuth: Float, c: SymmetryCopy): Float {
        if (!azimuth.isFinite()) return azimuth
        val vx = cos(azimuth.toDouble())
        val vy = sin(azimuth.toDouble())
        val tx = c.xx * vx + c.xy * vy
        val ty = c.yx * vx + c.yy * vy
        if (tx * tx + ty * ty < 1.0e-18) return azimuth
        return atan2(ty, tx).toFloat()
    }

    /**
     * The barrel through the copy — and the barrel is a DIRECTION here, not a twist.
     *
     * WHY: [cc.joycreator.joybrush.core.input.DirectionTracker.update] hands the barrel back as
     * it stands (`if (s.hasBarrel && s.barrel.isFinite()) return s.barrel`), so the barrel channel
     * IS the tip's direction as far as the brush is concerned. A copy therefore REFLECTS it the
     * way it reflects the lean: the direction `(cos b, sin b)` goes through the copy's linear part
     * and the answer is its angle — `b + θ` for a turn, `2φ − b` for a mirror about φ.
     *
     * The cost, and it is a real one: a brush curve that treats the barrel as a TWIST about the
     * pen's own axis sees the reflected value where it expected a negated one. That is the lesser
     * evil, because the tip's direction is what the eye sees.
     */
    private fun twist(barrel: Float, c: SymmetryCopy): Float {
        if (!barrel.isFinite()) return barrel
        val vx = cos(barrel.toDouble())
        val vy = sin(barrel.toDouble())
        val tx = c.xx * vx + c.xy * vy
        val ty = c.yx * vx + c.yy * vy
        if (tx * tx + ty * ty < 1.0e-18) return barrel
        return atan2(ty, tx).toFloat()
    }

    private fun finite(p: Pt): Boolean = p.x.isFinite() && p.y.isFinite()
}
