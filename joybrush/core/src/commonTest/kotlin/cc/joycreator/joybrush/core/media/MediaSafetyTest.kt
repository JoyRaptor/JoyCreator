package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.brush.MediaPresets
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The media symmetry's safe zone (JB-2.12a's Phase E).
 *
 * The media engine keeps ONE 1024 × 1024 window. Under a symmetry guide it is PINNED on the
 * symmetry centre, so that every copy's wet paint lives in ONE water field; the zone is the circle
 * about that centre in which the pen may move.
 *
 * Every oracle here is worked out LONGHAND from the geometry — the window is four tiles of 256 px,
 * so its origin is `tile × 256` and its far edge is 1024 px away — and never read back out of the
 * code under test. A test whose oracle is the code proves nothing.
 */
class MediaSafetyTest {

    // ---- the fixtures ----------------------------------------------------------------------------

    /** The window's tile, in document px. */
    private val tile = 256

    /** The window's width, in document px (four tiles). */
    private val windowPx = 1024

    /**
     * The distance from the centre to the window's nearest edge, worked out longhand: the window's
     * left edge is `tx` tiles away, its right edge 1024 px further, and the same above and below.
     */
    private fun nearestEdgeLonghand(tx: Int, ty: Int, cx: Double, cy: Double): Double {
        val ox = tx * tile.toDouble()
        val oy = ty * tile.toDouble()
        return min(min(cx - ox, ox + windowPx - cx), min(cy - oy, oy + windowPx - cy))
    }

    // ---- 1. the pin's placement -------------------------------------------------------------------

    /**
     * The pin's placement is ROUNDED, so the centre is never nearer than 384 px to an edge — where
     * the floored placement (the one the pen's window uses) can leave it 256 px from one.
     *
     * The window's origin is tile-aligned, and the centre walks through a whole tile: the rounded
     * placement keeps 384 px on every side, the floored one gets within a hair of 256 px.
     */
    @Test fun theRoundedPinGivesTheCentreAtLeast384PxOnEverySide() {
        var worstRounded = Double.MAX_VALUE
        var worstFloored = Double.MAX_VALUE
        var c = 2000.0
        while (c < 2256.0) {
            val (rx, ry) = MediaWindowMath.placeForRounded(c, c)
            val (fx, fy) = MediaWindowMath.placeFor(c, c)
            worstRounded = min(worstRounded, nearestEdgeLonghand(rx, ry, c, c))
            worstFloored = min(worstFloored, nearestEdgeLonghand(fx, fy, c, c))
            c += 0.0625
        }
        assertTrue(worstRounded >= 384.0 - 1e-9, "the rounded pin's worst case was $worstRounded")
        assertTrue(worstFloored <= 256.1, "the floored pin's worst case was $worstFloored")
        // And the maths agrees with the longhand oracle, all the way along.
        c = 2000.0
        while (c < 2256.0) {
            val (rx, ry) = MediaWindowMath.placeForRounded(c, c)
            assertEquals(nearestEdgeLonghand(rx, ry, c, c), MediaSafety.nearestEdge(rx, ry, c, c), 1e-9)
            c += 0.0625
        }
    }

    /**
     * The rounding DOUBLES the worst case of the radius: 384 − 128 − 4 = 252 against the floored
     * 256 − 128 − 4 = 124.
     */
    @Test fun theRoundedPinDoublesTheWorstCaseRadius() {
        val rounded = 384.0 - MediaSafety.MARGIN - MediaSafety.PAD
        val floored = 256.0 - MediaSafety.MARGIN - MediaSafety.PAD
        assertEquals(252.0, rounded, 1e-9)
        assertEquals(124.0, floored, 1e-9)
        assertTrue(rounded > 2 * floored, "the rounded pin's worst-case radius ($rounded) is not twice the floored one's ($floored)")
    }

    // ---- 2. the reach -----------------------------------------------------------------------------

    /**
     * The reach is the brush's own, and it comes straight off the radius: a brush that reaches
     * further has a smaller zone, and one that reaches further than the window can hold has none.
     */
    @Test fun theReachIsSubtractedFromTheRadius() {
        val (tx, ty) = MediaWindowMath.placeForRounded(1000.0, 1000.0)
        val edge = nearestEdgeLonghand(tx, ty, 1000.0, 1000.0)
        val bare = MediaSafety.safeRadius(tx, ty, 1000.0, 1000.0, 0.0)
        assertEquals(edge - MediaSafety.MARGIN - MediaSafety.PAD, bare, 1e-9, "the margin and the pad")
        for (reach in listOf(90.0, 120.0, 264.0, 440.0)) {
            assertEquals(bare - reach, MediaSafety.safeRadius(tx, ty, 1000.0, 1000.0, reach), 1e-9, "the reach $reach")
        }
    }

    /**
     * The reaches are the media engine's own, read from the shipped brushes: 264 document px for the
     * media pencils, 90 for the watercolour, 120 for the oil and 440 for the palette knife.
     */
    @Test fun theReachesAreTheShippedBrushesOwn() {
        val byId = MediaPresets.ALL.associateBy { it.id }
        assertEquals(264.0, byId.getValue("media:dry:Proto Pencil").media!!.reachDocPx(), 1e-9, "the Proto Pencil")
        assertEquals(90.0, byId.getValue("media:wet:All-round").media!!.reachDocPx(), 1e-9, "the watercolour's All-round")
        assertEquals(120.0, byId.getValue("media:paste:All-round").media!!.reachDocPx(), 1e-9, "the oil's All-round")
        assertEquals(440.0, byId.getValue("media:paste:Palette knife 2").media!!.reachDocPx(), 1e-9, "the palette knife")
    }

    // ---- 3. the radius per family -----------------------------------------------------------------

    /**
     * The safe radius of each media family, in the pin's worst placement (the centre 384 px from the
     * nearest edge) and in its best (the centre in the middle of the window).
     *
     * The pencil and the palette knife have NO safe zone in the worst placement — the pencil's reach
     * is 264 px and the knife's is 440, and the window cannot hold them — so their stroke is refused
     * in words. The watercolour and the oil have room.
     */
    @Test fun theSafeRadiusOfEachMediaFamily() {
        val worst = 1920.0     // the pin's worst placement: 384 px from the left and the top edge
        val best = 2048.0      // the pin's best placement: the middle of the window
        assertEquals(384.0, nearestEdgeLonghand(
            MediaWindowMath.placeForRounded(worst, worst).first,
            MediaWindowMath.placeForRounded(worst, worst).second, worst, worst), 1e-9)
        assertEquals(512.0, nearestEdgeLonghand(
            MediaWindowMath.placeForRounded(best, best).first,
            MediaWindowMath.placeForRounded(best, best).second, best, best), 1e-9)

        val pencil = 264.0; val water = 90.0; val oil = 120.0; val knife = 440.0
        assertEquals(-12.0, MediaSafety.safeRadiusFor(worst, worst, pencil), 1e-9, "the pencil, worst placement")
        assertEquals(162.0, MediaSafety.safeRadiusFor(worst, worst, water), 1e-9, "the watercolour, worst placement")
        assertEquals(132.0, MediaSafety.safeRadiusFor(worst, worst, oil), 1e-9, "the oil, worst placement")
        assertEquals(-188.0, MediaSafety.safeRadiusFor(worst, worst, knife), 1e-9, "the palette knife, worst placement")

        assertEquals(116.0, MediaSafety.safeRadiusFor(best, best, pencil), 1e-9, "the pencil, best placement")
        assertEquals(290.0, MediaSafety.safeRadiusFor(best, best, water), 1e-9, "the watercolour, best placement")
        assertEquals(260.0, MediaSafety.safeRadiusFor(best, best, oil), 1e-9, "the oil, best placement")
        assertEquals(-60.0, MediaSafety.safeRadiusFor(best, best, knife), 1e-9, "the palette knife, best placement")
    }

    // ---- 4. the refusal ---------------------------------------------------------------------------

    /**
     * A brush with no safe zone is REFUSED, in words: the owner's sentence, once.
     *
     * A radius of zero or less has no inside at all — not even the centre is in it — so every
     * sample is outside and the stroke cannot be painted at all.
     */
    @Test fun aBrushWithNoSafeZoneIsRefusedInWords() {
        val (tx, ty) = MediaWindowMath.placeForRounded(1920.0, 1920.0)
        val radius = MediaSafety.safeRadius(tx, ty, 1920.0, 1920.0, 264.0)
        assertTrue(radius <= 0.0, "the pencil's radius in the worst placement: $radius")
        assertFalse(MediaSafety.insideZone(1920.0, 1920.0, radius, 1920.0, 1920.0), "not even the centre is safe")
        assertFalse(MediaSafety.insideZone(1920.0, 1920.0, radius, 1921.0, 1920.0))
        assertEquals("This is the largest area your device can safely paint with this brush.", MediaSafety.REFUSAL)
    }

    // ---- 5. the per-side test ---------------------------------------------------------------------

    /**
     * The zone is judged from the CENTRE, side by side — not from the window's edges.
     *
     * The centre here is 384 px from the window's left edge and 640 px from its right one, so the
     * window has room on the right that the zone does not: a sample 300 px to the RIGHT of the
     * centre is well inside the window (340 px from its edge, past the margin) and yet it is
     * OUTSIDE the zone, because the zone is the circle about the centre.
     */
    @Test fun theZoneIsJudgedFromTheCentreAndNotFromTheWindowsEdges() {
        val (tx, ty) = MediaWindowMath.placeForRounded(1920.0, 1920.0)
        val radius = MediaSafety.safeRadius(tx, ty, 1920.0, 1920.0, 0.0)
        assertEquals(252.0, radius, 1e-9, "the zone about the centre")
        val right = 1920.0 + 300.0
        // The window would have held it: 340 px from the right edge, past the margin.
        assertTrue(MediaWindowMath.holds(tx, ty, doubleArrayOf(right, 1920.0, right, 1920.0)),
            "the window's own test would have allowed it")
        assertFalse(MediaSafety.insideZone(1920.0, 1920.0, radius, right, 1920.0),
            "the zone is the circle about the centre, and the sample is outside it")
        // Inside the circle is inside the zone, on either side.
        assertTrue(MediaSafety.insideZone(1920.0, 1920.0, radius, 1920.0 + 240.0, 1920.0))
        assertTrue(MediaSafety.insideZone(1920.0, 1920.0, radius, 1920.0 - 240.0, 1920.0))
        assertFalse(MediaSafety.insideZone(1920.0, 1920.0, radius, 1920.0 - 260.0, 1920.0))
        // And a point off the line is judged by its DISTANCE, not by its two sides.
        assertFalse(MediaSafety.insideZone(1920.0, 1920.0, radius, 1920.0 + 200.0, 1920.0 + 200.0))
        assertTrue(MediaSafety.insideZone(1920.0, 1920.0, radius, 1920.0 + 100.0, 1920.0 + 100.0))
    }
}
