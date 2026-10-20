package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.SymmetryMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The media lanes: the copies of a realistic stroke (JB-2.12a's Phase E).
 *
 * A media lane is the copy's MAP and the seed: the media engine makes the lane's own spline and
 * its own wet, dry or paste builder, and every lane shares the ONE engine, the ONE window and the
 * ONE undo step.
 *
 * Every oracle here is worked out LONGHAND from the geometry — a mirror about the line x = 200 is
 * `x -> 400 - x`, and the reflection of a direction about the vertical is `pi - a` — and never read
 * back out of the code under test.
 */
class MediaLaneTest {

    // ---- the fixtures ----------------------------------------------------------------------------

    /** A vertical mirror line at x = 200: the mirror of a point is `x -> 400 - x`, and y is left alone. */
    private fun mirrorGuide(symmetric: Boolean = true) =
        Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 2, symmetric = symmetric)

    /** A plain guide of eight around the middle of the line: seven copies and the original. */
    private fun eightGuide() =
        Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 8)

    /** A kaleidoscope of eight around the middle of the line: fifteen copies and the original. */
    private fun kaleidoGuide() =
        Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 8, kaleido = true)

    private val seed = 20261019L

    // ---- 1. the lane's samples are the transformed ones --------------------------------------------

    /**
     * A media lane hears the pen's sample TRANSFORMED into its own frame: the position goes through
     * the copy's map, and so do the lean and the twist when the guide is symmetric.
     *
     * The oracle is the geometry: a mirror about the vertical line x = 200 puts a point at
     * `400 - x`, and it reflects the lean about the vertical, which is `pi - a`.
     */
    @Test
    fun theMediaLanesSamplesAreTheTransformedOnes() {
        val set = SymmetryLanes.buildMedia(mirrorGuide(symmetric = true), SymmetryFamily.MEDIA_WET, seed)
        assertEquals(2, set.lanes.size, "a mirror is two lanes: the original and the copy")
        val s = PenSample(150f, 30f, 100.0, pressure = 0.7f, tilt = 0.4f, azimuth = 0.3f)

        val original = set.lanes[0].transform(s)
        assertEquals(150f, original.x, "the original hears the pen itself")
        assertEquals(30f, original.y)
        assertEquals(0.3f, original.azimuth)

        val copy = set.lanes[1].transform(s)
        assertEquals(250f, copy.x, "the copy is the mirror: 400 - 150")
        assertEquals(30f, copy.y, "a mirror about the vertical leaves y alone")
        assertEquals(0.3f, original.azimuth, "the original's lean is untouched")
        // The lean is reflected about the vertical line: pi - a.
        assertTrue(abs((PI - 0.3).toFloat() - copy.azimuth) < 1e-4f,
            "the copy's lean: expected ${(PI - 0.3).toFloat()}, got ${copy.azimuth}")
        // The pressure and the tilt keep their magnitude: leaning the same amount to the other side
        // is the same lean.
        assertEquals(0.7f, copy.pressure)
        assertEquals(0.4f, copy.tilt)
    }

    /**
     * With the guide NOT symmetric, the copies keep the original's lean and twist: the position is
     * copied, the randomness is not.
     */
    @Test
    fun anAsymmetricGuideCopiesThePositionAndNotTheLean() {
        val set = SymmetryLanes.buildMedia(mirrorGuide(symmetric = false), SymmetryFamily.MEDIA_WET, seed)
        val s = PenSample(150f, 30f, 100.0, azimuth = 0.3f, barrel = 0.9f)
        val copy = set.lanes[1].transform(s)
        assertEquals(250f, copy.x)
        assertEquals(0.3f, copy.azimuth, "the lean is the original's own")
        assertEquals(0.9f, copy.barrel, "and so is the twist")
    }

    /**
     * EVERY copy keeps its distance from the symmetry centre: a mirror about a line THROUGH the
     * centre and a turn about the centre both fix that distance.
     *
     * This is why the safe zone can be tested on the pen alone: the copy of a sample is as far from
     * the centre as the sample is, so a sample inside the zone has every copy inside it too.
     */
    @Test
    fun everyCopyKeepsItsDistanceFromTheCentre() {
        val guide = kaleidoGuide()
        val centre = assertNotNull(SymmetryMath.centre(guide), "the guide has no middle")
        val set = SymmetryLanes.buildMedia(guide, SymmetryFamily.MEDIA_WET, seed)
        assertTrue(set.lanes.size > 4, "the kaleidoscope made ${set.lanes.size} lanes")
        val s = PenSample(260f, 30f, 100.0)
        val d0 = hypot(s.x - centre.x, s.y - centre.y)
        for (lane in set.lanes) {
            val m = lane.transform(s)
            assertEquals(d0, hypot(m.x - centre.x, m.y - centre.y), 1e-3,
                "a copy keeps its distance from the centre")
        }
    }

    // ---- 2. the seed ------------------------------------------------------------------------------

    /**
     * The media lanes share ONE seed: the copies are the same brush (the owner), and the media
     * engine's wet stroke draws its water, its bead and its grain from that seed.
     */
    @Test
    fun theMediaLanesShareTheSeed() {
        for (guide in listOf(eightGuide(), kaleidoGuide(), mirrorGuide(symmetric = false))) {
            val set = SymmetryLanes.buildMedia(guide, SymmetryFamily.MEDIA_WET, seed)
            assertTrue(set.lanes.size > 1)
            for (lane in set.lanes) assertEquals(seed, lane.seed, "a lane has its own seed")
        }
    }

    // ---- 3. the cap ------------------------------------------------------------------------------

    /**
     * The caps are the owner's answer and they are counted in COPIES: the media families keep
     * sixteen (the pencil), eight (the watercolour) and eight (the oil).
     */
    @Test
    fun theCapAppliesToTheMediaFamilies() {
        assertEquals(16, SymmetryLanes.copyCapFor(SymmetryFamily.MEDIA_DRY))
        assertEquals(8, SymmetryLanes.copyCapFor(SymmetryFamily.MEDIA_WET))
        assertEquals(8, SymmetryLanes.copyCapFor(SymmetryFamily.MEDIA_PASTE))

        val wet = SymmetryLanes.buildMedia(eightGuide(), SymmetryFamily.MEDIA_WET, seed)
        assertEquals(7, wet.requestedCopies, "a guide of eight asks for seven copies")
        assertEquals(7, wet.copies)
        assertEquals(8, wet.lanes.size, "seven copies and the original")
        assertTrue(!wet.capped, "seven copies is under the wet family's eight")
        assertNull(wet.cap)

        // A guide that asks for more: the wet family keeps eight copies, the dry one sixteen.
        val big = Guide.Symmetry(Pt(200.0, -100.0), Pt(200.0, 100.0), count = 30, kaleido = true)
        val wetBig = SymmetryLanes.buildMedia(big, SymmetryFamily.MEDIA_WET, seed)
        assertEquals(59, wetBig.requestedCopies, "a kaleidoscope of thirty asks for fifty-nine copies")
        assertEquals(8, wetBig.copies, "the wet family keeps eight copies")
        assertEquals(9, wetBig.lanes.size)
        assertTrue(wetBig.capped)
        assertEquals(SymmetryFamily.MEDIA_WET, wetBig.cap!!.family)
        assertEquals(8, wetBig.cap!!.copies, "the cap reports copies, not lanes")

        val dryBig = SymmetryLanes.buildMedia(big, SymmetryFamily.MEDIA_DRY, seed)
        assertEquals(16, dryBig.copies, "the dry family keeps sixteen copies")
        assertEquals(17, dryBig.lanes.size)

        // And the cap the app says out loud is the same number, without building the lanes.
        assertEquals(8, SymmetryLanes.capFor(big, SymmetryFamily.MEDIA_WET)?.copies)
        assertNull(SymmetryLanes.capFor(mirrorGuide(), SymmetryFamily.MEDIA_WET), "one copy is under every cap")
    }

    // ---- 4. one frame for the copies --------------------------------------------------------------

    /**
     * The copies' dabs go into ONE batch, so that the water runs once for all of them and they mix
     * where they meet: ONE water field, not one per copy.
     */
    @Test
    fun theCopiesDabsGoIntoOneBatch() {
        val a = DabBatch(FloatArray(20) { 1f }, 1, doubleArrayOf(0.0, 0.0, 10.0, 10.0), doubleArrayOf(0.0, 0.0, 10.0, 10.0))
        val b = DabBatch(FloatArray(40) { 2f }, 2, doubleArrayOf(20.0, 20.0, 30.0, 30.0),
            doubleArrayOf(20.0, 20.0, 30.0, 30.0, 25.0, 25.0, 35.0, 35.0))
        val merged = assertNotNull(DabBatch.merge(listOf(a, b)), "the copies' dabs are one batch")
        assertEquals(3, merged.count, "both lanes' dabs are in it")
        assertEquals(3 * DabBatch.FLOATS, merged.dabs.size)
        assertEquals(1f, merged.dabs[0], "the first lane's first dab")
        assertEquals(2f, merged.dabs[DabBatch.FLOATS], "the second lane's first dab")
        assertEquals(2f, merged.dabs[DabBatch.FLOATS * 2], "the second lane's second dab")
        assertEquals(0.0, merged.dirty!![0], "the dirty box is the union")
        assertEquals(30.0, merged.dirty!![2])
        assertEquals(3 * 4, merged.boxes!!.size, "the boxes are the dabs' own")
        assertNull(DabBatch.merge(listOf()), "nothing to draw is no batch")
    }
}
