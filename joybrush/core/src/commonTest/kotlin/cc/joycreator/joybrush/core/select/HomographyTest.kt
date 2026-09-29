package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-2.05b — the quad-to-quad projective map.
 *
 * EVERY EXPECTED VALUE HERE IS DERIVED, and where the derivation is not obvious it is written out
 * beside the assertion. The four that carry the most weight:
 *
 *  - THE 8x8 SYSTEM. With `h33` pinned to 1, the requirement that `src_i` maps to `dst_i` is two
 *    linear equations per corner:
 *
 *        u*h0 + v*h1 + h2 - x*u*h6 - x*v*h7 = x
 *        u*h3 + v*h4 + h5 - y*u*h6 - y*v*h7 = y
 *
 *    Checked by hand on the unit square mapped to itself: the four `x = 0, y = 0` equations give
 *    h2 = h5 = 0 and then h1 = 0, h3 = 0; the equations at (1, 0) and (0, 1) then give h0 - h6 = 1
 *    and h4 - h7 = 1; and the two at (1, 1) give 1 - h7 = 1 and 1 - h6 = 1, so h6 = h7 = 0 and the
 *    matrix is the identity. That is the derivation the first test below re-runs in code.
 *
 *  - `then` IS "THIS FIRST". So `translate(10, 0).then(scale(2, 2))` applied to (1, 1) moves the
 *    point to (11, 1) and then scales it to (22, 2), while the same two in the other order
 *    (`scale(2, 2).then(translate(10, 0))`) give (12, 2). Both numbers are exact integers and both
 *    are asserted. (This header once had the two results swapped, while the test 155 lines below
 *    asserted the right one — a comment contradicting its own test.)
 *
 *  - A PROJECTIVE CENTRE IS THE INTERSECTION OF THE DIAGONALS. A map that takes one quad to
 *    another takes the intersection of the source's diagonals to the intersection of the
 *    destination's, because a projective map is a linear map on homogeneous coordinates and a
 *    homogeneous point is exactly a direction. `perspectiveCentreIsTheDiagonalIntersection` does
 *    the intersection with pencil and paper and compares.
 *
 *  - A POINT AT INFINITY IS A REFUSAL. `w = 0` is not a small number, it is the horizon; [Homography.apply]
 *    answers NaN and the tests check BOTH coordinates, because a NaN in one and a number in the
 *    other is how a caller ends up drawing a line to nowhere.
 */
class HomographyTest {

    // ---- helpers ---------------------------------------------------------------------------------

    /** The unit square as the overlay hands corners over: TL, TR, BR, BL. */
    private val unit: List<Pt> = listOf(
        Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0),
    )

    private fun assertNear(expected: Double, actual: Double, tol: Double, what: String) {
        assertTrue(
            abs(expected - actual) <= tol,
            "$what: expected $expected, got $actual (tolerance $tol)",
        )
    }

    /** Two matrices the same, all nine numbers, so a test cannot pass on six of them. */
    private fun assertSameMatrix(a: Homography, b: Homography, tol: Double, what: String) {
        for (i in 0 until 9) assertNear(a.m[i], b.m[i], tol, "$what m[$i]")
    }

    /** [p] turned about [c] by [r] radians, clockwise on screen, the way `Homography.rotate` does. */
    private fun turned(p: Pt, r: Double, c: Pt): Pt {
        val co = cos(r)
        val si = sin(r)
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Pt(c.x + co * dx - si * dy, c.y + si * dx + co * dy)
    }

    private fun scaled(p: Pt, sx: Double, sy: Double, c: Pt): Pt =
        Pt(c.x + (p.x - c.x) * sx, c.y + (p.y - c.y) * sy)

    // ---- 1. fromQuads ----------------------------------------------------------------------------

    @Test
    fun fromQuadsOfAUnitSquareOntoItselfIsIdentity() {
        val h = assertNotNull(Homography.fromQuads(unit, unit))
        assertSameMatrix(h, Homography.IDENTITY, 1e-9, "unit square onto itself")
        // And the identity is the identity on a point, which is the only claim that matters.
        for (p in listOf(Pt(3.25, -7.5), Pt(0.0, 0.0), Pt(-1.0, 1.0))) {
            val moved = h.apply(p)
            assertNear(p.x, moved.x, 1e-9, "x of $p")
            assertNear(p.y, moved.y, 1e-9, "y of $p")
        }
    }

    @Test
    fun fromQuadsOntoATranslatedSquareIsTheTranslate() {
        val dx = 3.5
        val dy = -7.25
        val dst = unit.map { Pt(it.x + dx, it.y + dy) }
        val h = assertNotNull(Homography.fromQuads(unit, dst))
        assertSameMatrix(h, Homography.translate(dx, dy), 1e-9, "translate")
    }

    @Test
    fun fromQuadsOntoARotatedSquareIsTheRotate() {
        val radians = 0.7
        val about = Pt(2.0, 3.0)
        val dst = unit.map { turned(it, radians, about) }
        val h = assertNotNull(Homography.fromQuads(unit, dst))
        assertSameMatrix(h, Homography.rotate(radians, about), 1e-9, "rotate")
    }

    @Test
    fun fromQuadsOntoAScaledSquareIsTheScale() {
        val sx = 2.5
        val sy = 0.75
        val about = Pt(-1.0, 4.0)
        val dst = unit.map { scaled(it, sx, sy, about) }
        val h = assertNotNull(Homography.fromQuads(unit, dst))
        assertSameMatrix(h, Homography.scale(sx, sy, about), 1e-9, "scale")
    }

    @Test
    fun fromQuadsOntoATrapezoidMovesAllFourCorners() {
        // A real corner-pin: the top edge is pulled right, the bottom left, so the two sides are
        // not parallel and the map is not affine.
        val dst = listOf(Pt(0.2, 0.0), Pt(0.9, 0.15), Pt(1.1, 0.8), Pt(0.0, 1.0))
        val h = assertNotNull(Homography.fromQuads(unit, dst))
        for (i in 0 until 4) {
            val moved = h.apply(unit[i])
            assertNear(dst[i].x, moved.x, 1e-6, "corner $i x")
            assertNear(dst[i].y, moved.y, 1e-6, "corner $i y")
        }
        // And the non-parallel sides really do make it projective: m[6] or m[7] is non-zero.
        assertFalse(h.isAffine, "a trapezoid is not affine")
    }

    @Test
    fun theThreeFactoriesAreAffine() {
        assertTrue(Homography.IDENTITY.isAffine, "identity")
        assertTrue(Homography.translate(4.0, -9.0).isAffine, "translate")
        assertTrue(Homography.rotate(1.1, Pt(3.0, 4.0)).isAffine, "rotate")
        assertTrue(Homography.scale(1.5, 0.5, Pt(0.0, 0.0)).isAffine, "scale")
    }

    @Test
    fun fromQuadsRefusesADegenerateSource() {
        // Points 0, 1 and 3 lie on y = x. The images of three collinear points are collinear
        // whatever the map, so the system has a free parameter and no unique answer.
        val src = listOf(Pt(0.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0), Pt(2.0, 2.0))
        assertNull(Homography.fromQuads(src, unit), "three collinear source corners")
    }

    @Test
    fun fromQuadsRefusesADegenerateDestination() {
        val dst = listOf(Pt(0.0, 0.0), Pt(2.0, 0.0), Pt(1.0, 0.0), Pt(0.0, 1.0))
        assertNull(Homography.fromQuads(unit, dst), "three collinear destination corners")
    }

    @Test
    fun fromQuadsRefusesNonFiniteCorners() {
        val bad = listOf(Pt(0.0, 0.0), Pt(1.0, Double.NaN), Pt(1.0, 1.0), Pt(0.0, 1.0))
        assertNull(Homography.fromQuads(bad, unit), "a NaN source corner")
        assertNull(Homography.fromQuads(unit, bad), "a NaN destination corner")
        val infinite = listOf(Pt(0.0, 0.0), Pt(1.0, Double.POSITIVE_INFINITY), Pt(1.0, 1.0), Pt(0.0, 1.0))
        assertNull(Homography.fromQuads(unit, infinite), "an infinite destination corner")
    }

    @Test
    fun fromQuadsRefusesAnythingButFourCorners() {
        assertNull(Homography.fromQuads(unit.take(3), unit.take(3)), "three corners a side")
        assertNull(Homography.fromQuads(unit + unit, unit), "eight corners a side")
    }

    @Test
    fun fromQuadsDoesNotThrowOnANonConvexQuad() {
        // A bow-tie: TL and BR swap sides. Not singular, not convex, and therefore NOT null. The
        // Studio overlay refuses one before it gets here, but a function that returned null for a
        // case it can solve would be wrong about a case it can solve.
        val bowtie = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(0.0, 10.0), Pt(10.0, 10.0))
        val moved = bowtie.map { Pt(it.x + 5.0, it.y + 7.0) }
        val h = assertNotNull(Homography.fromQuads(bowtie, moved), "a bow-tie is solvable")
        assertSameMatrix(h, Homography.translate(5.0, 7.0), 1e-9, "bow-tie")
    }

    // ---- 2. then, inverse ------------------------------------------------------------------------

    @Test
    fun thenIsThisFirstAndThenNext() {
        val move = Homography.translate(10.0, 0.0)
        val grow = Homography.scale(2.0, 2.0, Pt(0.0, 0.0))
        // move then grow: (1, 1) -> (11, 1) -> (22, 2).
        val a = move.then(grow).apply(Pt(1.0, 1.0))
        assertNear(22.0, a.x, 1e-9, "move.then(grow) x")
        assertNear(2.0, a.y, 1e-9, "move.then(grow) y")
        // grow then move: (1, 1) -> (2, 2) -> (12, 2).
        val b = grow.then(move).apply(Pt(1.0, 1.0))
        assertNear(12.0, b.x, 1e-9, "grow.then(move) x")
        assertNear(2.0, b.y, 1e-9, "grow.then(move) y")
        assertTrue(abs(a.x - b.x) > 1.0, "the order must matter, and it does")
    }

    @Test
    fun thenAgreesWithApplyingTwice() {
        val first = Homography.rotate(0.37, Pt(11.0, -4.0))
        val second = Homography.scale(1.7, 0.4, Pt(2.0, 9.0))
        val composed = first.then(second)
        for (p in listOf(Pt(0.0, 0.0), Pt(13.5, 2.25), Pt(-40.0, 91.0))) {
            val once = second.apply(first.apply(p))
            val direct = composed.apply(p)
            assertNear(once.x, direct.x, 1e-9, "composed x of $p")
            assertNear(once.y, direct.y, 1e-9, "composed y of $p")
        }
    }

    @Test
    fun inverseComposesBackToIdentity() {
        val dst = listOf(Pt(0.2, 0.0), Pt(0.9, 0.15), Pt(1.1, 0.8), Pt(0.0, 1.0))
        val h = assertNotNull(Homography.fromQuads(unit, dst))
        val back = assertNotNull(h.inverse(), "a trapezoid map is not singular")
        assertSameMatrix(h.then(back), Homography.IDENTITY, 1e-9, "h then h.inverse")
        assertSameMatrix(back.then(h), Homography.IDENTITY, 1e-9, "h.inverse then h")
        for (p in listOf(Pt(0.3, 0.6), Pt(5.0, -2.0))) {
            val there = h.apply(p)
            val backAgain = back.apply(there)
            assertNear(p.x, backAgain.x, 1e-9, "round trip x of $p")
            assertNear(p.y, backAgain.y, 1e-9, "round trip y of $p")
        }
    }

    @Test
    fun theIdentityInvertsToTheIdentity() {
        assertSameMatrix(assertNotNull(Homography.IDENTITY.inverse()), Homography.IDENTITY, 1e-12, "identity")
    }

    @Test
    fun inverseIsNullWhenSingular() {
        // A scale by zero: m is [0,0,0, 0,1,0, 0,0,1], whose determinant is exactly 0.
        assertNull(Homography.scale(0.0, 1.0, Pt(0.0, 0.0)).inverse(), "a zero scale")
        // And a matrix that is not a homography at all but is typed as one.
        val flat = Homography(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0))
        assertNull(flat.inverse(), "two identical rows")
    }

    // ---- 3. the horizon --------------------------------------------------------------------------

    @Test
    fun applyRefusesAPointOnTheHorizon() {
        // w = x, so every point with x = 0 is on the vanishing line and has no image.
        val horizon = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0))
        val onIt = horizon.apply(Pt(0.0, 5.0))
        assertTrue(onIt.x.isNaN(), "x of a point on the horizon is NaN, got ${onIt.x}")
        assertTrue(onIt.y.isNaN(), "y of a point on the horizon is NaN, got ${onIt.y}")
        // One pixel to the right of it the map is perfectly ordinary: w = 1.
        val offIt = horizon.apply(Pt(1.0, 5.0))
        assertNear(1.0, offIt.x, 1e-12, "x just off the horizon")
        assertNear(5.0, offIt.y, 1e-12, "y just off the horizon")
        // And BEHIND the horizon is not a refusal: `w` is negative, which is a real answer rather
        // than an absence of one. For this matrix the map is x' = x / w = 1 and y' = y / w, so
        // (-2, 3) lands at (1, -1.5) — reflected through w = 0 rather than thrown away.
        val behind = horizon.apply(Pt(-2.0, 3.0))
        assertNear(1.0, behind.x, 1e-12, "x behind the horizon")
        assertNear(-1.5, behind.y, 1e-12, "y behind the horizon")
    }

    @Test
    fun applyRefusesAPointOfNoFiniteImage() {
        // The whole last row is zero, so w = 0 at EVERY point and not just on a line: there is no
        // point on this canvas with an image, and 0/0 is a refusal rather than a number.
        val through = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        val p = through.apply(Pt(0.0, 0.0))
        assertTrue(p.x.isNaN() && p.y.isNaN(), "0/0 is a refusal, not a number")
    }

    // ---- 4. perspective -------------------------------------------------------------------------

    @Test
    fun perspectiveCentreIsTheDiagonalIntersection() {
        // A 64 x 64 source square whose centre is EXACTLY the centre of pixel (32, 32), so the
        // claim can be made about a pixel and not only about a point.
        val src = listOf(Pt(0.5, 0.5), Pt(64.5, 0.5), Pt(64.5, 64.5), Pt(0.5, 64.5))
        // Pinned to a trapezoid: the top edge is 48 wide, the bottom 56, and the sides are not
        // parallel, which is what makes the destination's diagonals meet somewhere other than the
        // middle of the figure.
        val dst = listOf(Pt(16.5, 8.5), Pt(64.5, 8.5), Pt(56.5, 56.5), Pt(0.5, 56.5))
        val h = assertNotNull(Homography.fromQuads(src, dst))

        // By pencil. TL->BR is (16.5, 8.5) + t(40, 48). TR->BL is (64.5, 8.5) + s(-64, 48).
        // Equal y forces t = s; equal x gives 16.5 + 40t = 64.5 - 64t, so 104t = 48 and t = 6/13.
        val t = 6.0 / 13.0
        val expectedX = 16.5 + 40.0 * t
        val expectedY = 8.5 + 48.0 * t
        // 6/13 of the way along TL->BR, checked against the whole line for both diagonals.
        assertNear(34.96153846153846, expectedX, 1e-9, "the analytic intersection x")
        assertNear(30.65384615384615, expectedY, 1e-9, "the analytic intersection y")

        val centre = h.apply(Pt(32.5, 32.5))
        assertNear(expectedX, centre.x, 1e-6, "the image of the source centre x")
        assertNear(expectedY, centre.y, 1e-6, "the image of the source centre y")
    }

    @Test
    fun aCornerPinIsNotACompositionOfTheThreeFactories() {
        // A guard on the whole point of the revision: if a trapezoid could be built by moving,
        // turning and scaling alone, the homography would be decoration.
        val src = listOf(Pt(0.0, 0.0), Pt(64.0, 0.0), Pt(64.0, 64.0), Pt(0.0, 64.0))
        val dst = listOf(Pt(16.0, 8.0), Pt(64.0, 8.0), Pt(56.0, 56.0), Pt(0.0, 56.0))
        val h = assertNotNull(Homography.fromQuads(src, dst))
        assertFalse(h.isAffine, "a corner-pin has a non-zero m[6] or m[7]")
        // And the matrix is the honest one for those corners, so the assertion above is about the
        // SHAPE of the map and not about a solve that happened to return something.
        for (i in 0 until 4) {
            val moved = h.apply(src[i])
            assertNear(dst[i].x, moved.x, 1e-6, "corner $i x")
            assertNear(dst[i].y, moved.y, 1e-6, "corner $i y")
        }
    }

    // ---- 5. the matrix is a value, not an alias --------------------------------------------------

    @Test
    fun theMatrixIsCopiedOnTheWayIn() {
        val source = doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)
        val h = Homography(source)
        source[0] = 99.0
        assertNear(2.0, h.m[0], 0.0, "the constructor copies")
        // And IDENTITY is not a door onto the zero matrix.
        val before = Homography.IDENTITY.m[0]
        assertNear(1.0, before, 0.0, "IDENTITY m[0]")
    }

    @Test
    fun aMatrixOfTheWrongSizeIsACallerBug() {
        var threw = false
        try {
            Homography(doubleArrayOf(1.0, 2.0))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a 2x2 is not a homography and must say so")
    }

    // ---- 6. a rotation round trip, the sign the overlay uses --------------------------------------

    @Test
    fun rotateIsClockwiseOnScreen() {
        // y grows DOWN on this canvas, so a positive angle takes +x towards +y. This is the same
        // sign as `ViewTransform.rotation`, and the two disagreeing would spin the overlay the
        // other way from the page.
        val h = Homography.rotate(PI / 2.0, Pt(0.0, 0.0))
        val p = h.apply(Pt(1.0, 0.0))
        assertNear(0.0, p.x, 1e-9, "a quarter turn of (1, 0) has no x")
        assertNear(1.0, p.y, 1e-9, "a quarter turn of (1, 0) has y = 1")
    }

    @Test
    fun rotateAboutAPointLeavesThatPointAlone() {
        val about = Pt(13.0, -7.0)
        val h = Homography.rotate(1.234, about)
        val p = h.apply(about)
        assertNear(about.x, p.x, 1e-9, "the pivot keeps its x")
        assertNear(about.y, p.y, 1e-9, "the pivot keeps its y")
        // And it keeps its distance, which is the property a spin arc is drawing.
        val a = Pt(20.0, -7.0)
        val b = h.apply(a)
        val before = abs(a.x - about.x) * abs(a.x - about.x) + abs(a.y - about.y) * abs(a.y - about.y)
        val after = abs(b.x - about.x) * abs(b.x - about.x) + abs(b.y - about.y) * abs(b.y - about.y)
        assertNear(before, after, 1e-9, "a turn preserves the radius")
    }

    @Test
    fun scaleAboutAPointLeavesThatPointAlone() {
        val about = Pt(-2.0, 6.0)
        val h = Homography.scale(3.0, 0.25, about)
        val p = h.apply(about)
        assertNear(about.x, p.x, 0.0, "the pivot keeps its x")
        assertNear(about.y, p.y, 0.0, "the pivot keeps its y")
    }
}
