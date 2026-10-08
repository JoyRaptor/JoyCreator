package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paper.HexTile
import cc.joycreator.joybrush.core.paper.PaperTexture
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Row 13 CHARACTERIZATION, preceding any shader change.
 *
 * Actual TilePainting creates translated dab copies. TipMath, HexTile and GrainMath are the
 * production CPU twins of jb_contact/jb_paper: no texture/hash/threshold math is copied here.
 * RefCanvas and InkRaster have NO paper-grain implementation, so calling those would merely
 * produce a smooth nib and vacuously miss this bug. This test observes the two opposing boundary
 * limits of the same dab, before board clipping, using precisely the shader's doc=centre+offset.
 *
 * The tests deliberately assert today's defect, including the insufficiency of scale snapping.
 * The tile-periodic wrapper must add separate equality tests while retaining this unarmed
 * canonical-sampler baseline. Scale snapping alone does not change these results.
 */
class TilePaperGrainSeamTest {
    private val pitch = 2.0 // Document px per surface texel; source texture period = 32 doc px.
    private val texture = PaperTexture(16, 16, ByteArray(16 * 16 * 4).also { bytes ->
        for (y in 0 until 16) for (x in 0 until 16) {
            val at = (y * 16 + x) * 4
            val height = 32 + ((x * 37 + y * 61 + x * y * 13) % 193)
            bytes[at] = 127 // Actual flat signed-slope encoding.
            bytes[at + 1] = 127
            bytes[at + 2] = height.toByte()
            bytes[at + 3] = ((height * height + 127) / 255).toByte()
        }
    })
    private val tip = TipShape(hardness = 1f)

    @Test fun wrappedGrainyDabUsesDifferentPaperAtOppositeEdgesWhenPeriodDoesNotDivideBoard() {
        val mismatch = seamMismatch(RectPx(-20, -10, 29, 37), horizontal = true, rotatable = true)
        assertTrue("nondividing period must visibly expose absolute-position paper: $mismatch", mismatch > .05f)
    }

    @Test fun snappingTexturePeriodToBoardStillDoesNotPeriodizeGlobalHexHashes() {
        // 32 / (16*2) = exactly one image repeat across either board axis. Even nonrotatable
        // paper keeps per-hex random offsets, so simply snapping scale cannot satisfy row 13.
        val board = RectPx(-20, -10, 32, 32)
        for (rotate in listOf(false, true)) for (horizontal in listOf(false, true)) {
            val mismatch = seamMismatch(board, horizontal, rotate)
            assertTrue("snapped period, horizontal=$horizontal rotated=$rotate: $mismatch", mismatch > .05f)
        }
        // Positive control: the underlying image REALLY IS periodic at that scale.
        val a = FloatArray(4); val b = FloatArray(4)
        texture.bilinear(3.375, 7.625, a)
        texture.bilinear(3.375 + board.w / pitch, 7.625 + board.h / pitch, b)
        assertArrayEquals(a, b, 0f)
    }

    @Test fun wrappingKeepsNibAndFlowExactlyEqualSoPaperIsTheCause() {
        val board = RectPx(-20, -10, 29, 37)
        val dab = Dab((board.x + board.w).toFloat(), board.y + 18.5f, 6f, flow = .4f, cap = .7f)
        val copies = TilePainting.dabs(board, dab).sortedBy { it.x }
        assertEquals(2, copies.size)
        assertEquals(board.x.toFloat(), copies.first().x, 0f)
        assertEquals((board.x + board.w).toFloat(), copies.last().x, 0f)
        for (dy in -4..4) {
            val left = smoothCoverage(copies.first(), 0f, dy.toFloat())
            val right = smoothCoverage(copies.last(), 0f, dy.toFloat())
            assertEquals(left, right, 0f)
            assertTrue(left > .1f) // A real painted interior, not two zero-coverage pixels.
        }
    }

    /** Opposite boundaries get the same LOCAL dab offset but different ABSOLUTE doc positions. */
    private fun seamMismatch(board: RectPx, horizontal: Boolean, rotatable: Boolean): Float {
        val dab = if (horizontal) Dab((board.x + board.w).toFloat(), board.y + board.h / 2f, 6f)
            else Dab(board.x + board.w / 2f, (board.y + board.h).toFloat(), 6f)
        val copies = TilePainting.dabs(board, dab).sortedBy { if (horizontal) it.x else it.y }
        assertEquals(2, copies.size)
        val left = copies.first(); val right = copies.last()
        var maximum = 0f
        for (i in -8..8) {
            val dx = if (horizontal) 0f else i / 2f
            val dy = if (horizontal) i / 2f else 0f
            assertEquals(smoothCoverage(left, dx, dy), smoothCoverage(right, dx, dy), 0f)
            val a = grainCoverage(left, dx, dy, rotatable)
            val b = grainCoverage(right, dx, dy, rotatable)
            maximum = maxOf(maximum, abs(a - b))
        }
        println("TilePaperGrain seam ${board.w}x${board.h}, axis=${if (horizontal) "x" else "y"}, " +
            "rotatable=$rotatable, peak dab coverage difference=$maximum")
        return maximum
    }

    private fun smoothCoverage(dab: Dab, dx: Float, dy: Float): Float =
        TipMath.coverage(dx, dy, dab.radius, dab.angle, tip) * dab.flow

    private fun grainCoverage(dab: Dab, dx: Float, dy: Float, rotatable: Boolean): Float {
        val tipCov = TipMath.coverage(dx, dy, dab.radius, dab.angle, tip)
        val surface = FloatArray(4)
        // jb_contact's height-only paper branch calls jb_paperHeight(centre+offset), whose
        // height is sampleSurface.B with document position / physical texel size.
        HexTile.sampleSurface(texture, (dab.x + dx) / pitch, (dab.y + dy) / pitch,
            hexTexels = 12.0, rotatable = rotatable, slopeRange = .1f, out = surface,
            footprint = 1.0 / pitch)
        val level = GrainMath.grainLevel(.5f, tipCov, dx / dab.radius, dy / dab.radius,
            0f, 0f, 0f, 0f, 0f)
        return GrainMath.grainedCoverage(tipCov, GrainMath.heightCoverage(surface[2], level, .35f)) * dab.flow
    }
}
