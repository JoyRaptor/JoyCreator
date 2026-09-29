package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 3x3 PROJECTIVE map. One class, and it is the reason this spec needed revising: the Studio's
 * `TransformOverlayView` speaks in QUADS (four corners it drags), and moving pixels corner-to-corner
 * is not an affine map. A homography is: it carries move, scale, rotate, skew AND perspective (the
 * corner-pin) in one matrix, and it is the only one of those that the corner-pin needs.
 *
 * ROW-MAJOR [m] m[0..8], as a column vector, so a point (x, y) goes to
 *
 *     x' = (m[0]x + m[1]y + m[2]) / w      y' = (m[3]x + m[4]y + m[5]) / w
 *     w  =  m[6]x + m[7]y + m[8]
 *
 * which is the GL engine's `docToClip` layout with the same row/col order, so a matrix handed to
 * `glUniformMatrix3fv` is not transposed on the way.
 *
 * [m] IS COPIED. Nine doubles is nothing next to a 256 KB tile, and an aliased array is how
 * `IDENTITY` ends up being the zero matrix because something wrote nine numbers through a `val`
 * that looked like a value.
 *
 * EVERY OPERATION REFUSES IN WORDS WHERE THE MATHS HAS NO ANSWER. A point whose `w` is zero is a
 * point at infinity — it has no place on this canvas — and [apply] says so by returning a NaN point
 * rather than by returning an infinity that a caller would then subtract. A matrix with no inverse
 * gives null from [inverse] rather than a matrix full of NaN. Nothing in this class ever returns a
 * NaN coefficient: a NaN that escapes is a NaN somebody has to debug three files away.
 */
class Homography(matrix: DoubleArray) {

    /** Row-major 3x3. A private copy of the caller's array; see the class note. */
    val m: DoubleArray = matrix.copyOf()

    init {
        require(this.m.size == 9) {
            "a homography is 3x3, so nine numbers; this one was given ${matrix.size}"
        }
    }

    /**
     * The point [p] goes to, or `Pt(NaN, NaN)` when there is no such point.
     *
     * THE REFUSAL IS `w`, AND IT IS A REFUSAL. `w` is the projective denominator and it reaches
     * zero for real inputs — drag one corner of a quad far enough past the vanishing line and its
     * two neighbours' images go to infinity, and the two images either side of it swap over. The
     * honest answer to "where does this land" there is "nowhere I can draw", and the only way to
     * say that with a `Pt` is NaN. Returning `Double.POSITIVE_INFINITY` instead would be a guess
     * dressed as a number, and one `abs(x - otherX)` away from a document-sized array index.
     */
    fun apply(p: Pt): Pt {
        val x = p.x
        val y = p.y
        val w = m[6] * x + m[7] * y + m[8]
        if (!w.isFinite() || abs(w) < W_EPS) return NAN_POINT
        val nx = (m[0] * x + m[1] * y + m[2]) / w
        val ny = (m[3] * x + m[4] * y + m[5]) / w
        if (!nx.isFinite() || !ny.isFinite()) return NAN_POINT
        return Pt(nx, ny)
    }

    /**
     * This first, then [next] — `p.then(q).apply(x) == q.apply(this.apply(x))`, and the returned
     * matrix is the plain matrix product `q * this`.
     *
     * The ORDER IS THE WHOLE POINT and it is the opposite of the usual reading of the word
     * "then", which is why it is spelled out here and tested in `HomographyTest`: `translate(10, 0)`
     * `.then(scale(2, 2))` moves FIRST and scales second — the point goes (1,1) → (11,1) → (22,2) —
     * and the other order, `scale(2, 2).then(translate(10, 0))`, gives (12,2). The two are different
     * pictures, and an earlier version of this comment claimed the opposite order here, which is
     * exactly the kind of false claim the adversarial round exists to catch.
     */
    fun then(next: Homography): Homography = Homography(mul(next.m, m))

    /**
     * The matrix that undoes this one, or null when there is none.
     *
     * NULL IS AN ANSWER, NOT A GAP. A scale by zero, a quad flattened to a line, a matrix typed in
     * by a caller with one row of zeroes: all of them are singular, and all of them have no
     * inverse. The adjugate divided by the determinant gives infinities and NaNs for each, and a
     * caller that does not check for them gets a resampler that samples at NaN and writes NaN
     * bytes. So the determinant is measured and the answer is null.
     */
    fun inverse(): Homography? {
        val a0 = m[0]; val a1 = m[1]; val a2 = m[2]
        val b0 = m[3]; val b1 = m[4]; val b2 = m[5]
        val c0 = m[6]; val c1 = m[7]; val c2 = m[8]
        val det = a0 * (b1 * c2 - b2 * c1) - a1 * (b0 * c2 - b2 * c0) + a2 * (b0 * c1 - b1 * c0)
        if (!det.isFinite() || abs(det) < DET_EPS) return null
        val k = 1.0 / det
        // The adjugate: cofactors, transposed. Written out rather than built in loops so that the
        // nine indices can be read against the matrix they claim to invert.
        return Homography(
            doubleArrayOf(
                (b1 * c2 - b2 * c1) * k, (a2 * c1 - a1 * c2) * k, (a1 * b2 - a2 * b1) * k,
                (b2 * c0 - b0 * c2) * k, (a0 * c2 - a2 * c0) * k, (a2 * b0 - a0 * b2) * k,
                (b0 * c1 - b1 * c0) * k, (a1 * c0 - a0 * c1) * k, (a0 * b1 - a1 * b0) * k,
            ),
        )
    }

    /**
     * True when this map is affine: the last row is (0, 0, 1) and the projective denominator is
     * therefore the constant 1.
     *
     * Not an optimisation hint for a fast path. It is a QUESTION with a yes/no answer — "is this
     * corner-pin actually a skew?" — and a caller that wants to draw an axis-aligned box around a
     * rotated image needs to be able to ask it without dividing by a denominator and hoping.
     */
    val isAffine: Boolean
        get() = abs(m[6]) <= AFFINE_EPS && abs(m[7]) <= AFFINE_EPS

    companion object {

        /** Nine numbers, row-major, [Homography.apply] one point at a time. */
        val IDENTITY: Homography = Homography(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        )

        /**
         * The map taking `src[0..3]` to `dst[0..3]`, corners in the order TL, TR, BR, BL — the order
         * `TransformOverlayView` hands its handles over in.
         *
         * THE SOLVE IS THE ORDINARY ONE: eight unknowns with `h33` pinned to 1, which turns the
         * eight `src_i -> dst_i` cross-product equations into a linear 8x8 system solved by Gaussian
         * elimination with partial pivoting. `h33 = 1` is a choice, not a law: a map whose
         * honest form has `h33 = 0` is one this cannot express, and it comes back null rather than
         * as a matrix fitted to the nearest legal thing.
         *
         * TWO REFUSALS, both of them words and neither of them an exception:
         *  - a non-finite corner, or anything other than exactly four corners on each side;
         *  - a degenerate quad, meaning three of the four corners of EITHER quad collinear. That is
         *    caught twice on purpose — once as an explicit triangle area, which names the reason,
         *    and once as a zero pivot, which catches a quad that is degenerate in a way the area
         *    test's tolerance does not. A lasso that can produce one exists, and a caller of a
         *    gesture is a person.
         *
         * A NON-CONVEX quad is NOT refused. The Studio overlay refuses one before this is ever
         * called, but a bow-tie is not singular: it has an honest projective map, it is just an
         * inside-out one, and answering null for it would make this function wrong about a case it
         * can actually solve. It must not throw, and it does not.
         */
        fun fromQuads(src: List<Pt>, dst: List<Pt>): Homography? {
            if (src.size != 4 || dst.size != 4) return null
            for (i in 0 until 4) {
                if (!src[i].x.isFinite() || !src[i].y.isFinite()) return null
                if (!dst[i].x.isFinite() || !dst[i].y.isFinite()) return null
            }
            if (hasCollinearTriple(src) || hasCollinearTriple(dst)) return null

            // Two rows per corner, h8 pinned to 1:
            //   u*h0 + v*h1 + h2 - x*u*h6 - x*v*h7 = x
            //   u*h3 + v*h4 + h5 - y*u*h6 - y*v*h7 = y
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val u = src[i].x
                val v = src[i].y
                val x = dst[i].x
                val y = dst[i].y
                val r0 = i * 2
                a[r0][0] = u
                a[r0][1] = v
                a[r0][2] = 1.0
                a[r0][6] = -x * u
                a[r0][7] = -x * v
                b[r0] = x
                val r1 = r0 + 1
                a[r1][3] = u
                a[r1][4] = v
                a[r1][5] = 1.0
                a[r1][6] = -y * u
                a[r1][7] = -y * v
                b[r1] = y
            }
            val h = solve8(a, b) ?: return null
            return Homography(
                doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0),
            )
        }

        /**
         * A move. [dx] is in document px, and the arithmetic is in Double because a document
         * coordinate can be a pixel index and a half.
         */
        fun translate(dx: Double, dy: Double): Homography = Homography(
            doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0),
        )

        /**
         * A turn about [about], in radians, POSITIVE CLOCKWISE ON SCREEN because y grows down —
         * the same sign as `ViewTransform.rotation`, so the spin arc and this cannot disagree.
         */
        fun rotate(radians: Double, about: Pt): Homography {
            val c = cos(radians)
            val s = sin(radians)
            return Homography(
                doubleArrayOf(
                    c, -s, about.x - c * about.x + s * about.y,
                    s, c, about.y - s * about.x - c * about.y,
                    0.0, 0.0, 1.0,
                ),
            )
        }

        /**
         * A scale about [about]. The two factors are separate because a non-uniform one is what a
         * two-finger stretch with a locked axis does, and pretending it is a zoom is how a document
         * ends up 1.3 times wider than it was tall.
         */
        fun scale(sx: Double, sy: Double, about: Pt): Homography = Homography(
            doubleArrayOf(
                sx, 0.0, about.x - sx * about.x,
                0.0, sy, about.y - sy * about.y,
                0.0, 0.0, 1.0,
            ),
        )

        /** The matrix product `n * p`: apply `p` first, then `n`. Row-major, nine terms. */
        private fun mul(n: DoubleArray, p: DoubleArray): DoubleArray {
            val out = DoubleArray(9)
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    var s = 0.0
                    for (k in 0 until 3) s += n[i * 3 + k] * p[k * 3 + j]
                    out[i * 3 + j] = s
                }
            }
            return out
        }

        /**
         * True when any three of the four points are collinear, which is exactly when a quad cannot
         * pin down a projective map: the three images are then collinear whatever the map, so the
         * system has a free parameter instead of a solution.
         */
        private fun hasCollinearTriple(q: List<Pt>): Boolean {
            val a = q[0]
            val b = q[1]
            val c = q[2]
            val d = q[3]
            return triangleArea(a, b, c) <= AREA_EPS ||
                triangleArea(a, b, d) <= AREA_EPS ||
                triangleArea(a, c, d) <= AREA_EPS ||
                triangleArea(b, c, d) <= AREA_EPS
        }

        /** Twice the area of the triangle, so the test needs no division by two. */
        private fun triangleArea(a: Pt, b: Pt, c: Pt): Double =
            abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x))

        /**
         * Gaussian elimination with partial pivoting, in place, and a back substitution.
         *
         * PARTIAL PIVOTING IS NOT AN OPTIMISATION HERE. The four rows come from four corners that
         * can be anywhere, and a corner order that happens to give a tiny leading coefficient is
         * an ordinary thing for a person to drag. Without the swap the solve silently loses half
         * its digits and the corner comes back a pixel out, which is a bug nobody can see.
         *
         * The matrix is destroyed. It is built two lines above the call and is never wanted again.
         *
         * @return the eight unknowns, or null when a pivot is below [PIVOT_EPS] (a singular
         *   system) or when any of them came out non-finite (a system that is nominally solvable
         *   and is not, in Double).
         */
        private fun solve8(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            for (col in 0 until 8) {
                var pivotRow = col
                var best = abs(a[col][col])
                for (r in col + 1 until 8) {
                    val candidate = abs(a[r][col])
                    if (candidate > best) {
                        best = candidate
                        pivotRow = r
                    }
                }
                if (!best.isFinite() || best <= PIVOT_EPS) return null
                if (pivotRow != col) {
                    val rowSwap = a[pivotRow]
                    a[pivotRow] = a[col]
                    a[col] = rowSwap
                    val valueSwap = b[pivotRow]
                    b[pivotRow] = b[col]
                    b[col] = valueSwap
                }
                val pivot = a[col][col]
                for (r in col + 1 until 8) {
                    val factor = a[r][col] / pivot
                    if (factor == 0.0) continue
                    a[r][col] = 0.0
                    for (c in col + 1 until 8) a[r][c] -= factor * a[col][c]
                    b[r] -= factor * b[col]
                }
            }
            val x = DoubleArray(8)
            for (r in 7 downTo 0) {
                var sum = b[r]
                for (c in r + 1 until 8) sum -= a[r][c] * x[c]
                val pivot = a[r][r]
                if (pivot == 0.0 || !pivot.isFinite()) return null
                x[r] = sum / pivot
            }
            for (i in 0 until 8) if (!x[i].isFinite()) return null
            return x
        }

        /** No such point. A single instance, so a NaN point costs no allocation in a resample loop. */
        private val NAN_POINT: Pt = Pt(Double.NaN, Double.NaN)

        /**
         * How close `w` may get to zero and still count as a point. 1e-9 in document px is a
         * hundred million times below one pixel, so a map that reaches it has genuinely thrown the
         * point past the horizon rather than merely sent it far away.
         */
        private const val W_EPS = 1e-9

        /** Determinant floor for [inverse], same reasoning: below this the matrix has collapsed. */
        private const val DET_EPS = 1e-12

        /** How close the last row must be to (0, 0, ·) for [isAffine] to say yes. */
        private const val AFFINE_EPS = 1e-12

        /**
         * Twice-triangle-area floor for a degenerate quad, in px². A quad of four corners a person
         * dragged is never this close to a line by accident.
         */
        private const val AREA_EPS = 1e-9

        /**
         * Pivot floor for the 8x8 solve, in the units the matrix is built in — document px, and
         * products of them. Well under 1e-9 of a pixel, so it fires on a genuinely singular system
         * and not on a well-conditioned one.
         */
        private const val PIVOT_EPS = 1e-12
    }
}
