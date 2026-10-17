package cc.joycreator.joybrush.core.media

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Media Atlas v2 B1: the per-slot instances a pass draws (what jb_media_quad.vert and the dab shaders read under JB_ATLAS). */
class MediaAtlasDrawTest {
    private fun rects(d: MediaAtlasDraw) = (0 until d.rectCount).map { i -> d.rect.copyOfRange(i * 8, i * 8 + 8).toList() }

    /** Pixel centres of [x0, y0, x1, y1): the set a rasteriser covers. */
    private fun centres(x0: Float, y0: Float, x1: Float, y1: Float): Set<Pair<Int, Int>> = buildSet {
        for (y in kotlin.math.floor(y0).toInt() - 1..kotlin.math.ceil(y1).toInt()) for (x in kotlin.math.floor(x0).toInt() - 1..kotlin.math.ceil(x1).toInt())
            if (x + 0.5f > x0 && x + 0.5f < x1 && y + 0.5f > y0 && y + 0.5f < y1) add(x to y)
    }

    @Test fun aRectAcrossTheSeamsIsSplitIntoItsSlotsAndCoversExactlyTheSamePixels() {
        val d = MediaAtlasDraw()
        // A wet rect with fractional edges across both seams of a 2 × 2 window.
        val n = d.rects(100.25f, 200.75f, 400.5f, 300.0f, 8, 2, MediaAtlasDraw.identity(2))
        assertEquals(4, n)
        val all = HashSet<Pair<Int, Int>>()
        for (r in rects(d)) {
            val (sx, sy, tx, ty) = r
            assertEquals(tx * 256, sx); assertEquals(ty * 256, sy)   // identity: the slot is the tile, in place
            assertTrue(r[4] >= tx * 256 && r[6] <= (tx + 1) * 256 && r[5] >= ty * 256 && r[7] <= (ty + 1) * 256, "clip inside its tile: $r")
            val px = centres(r[4], r[5], r[6], r[7])
            assertTrue(px.none { it in all }, "slots never overlap"); all += px
        }
        assertEquals(centres(100.25f, 200.75f, 400.5f, 300.0f), all, "the slots together cover the rect's pixels, no more, no less")
    }

    @Test fun anEdgeOnATileBorderDoesNotDrawTheNextTile() {
        val d = MediaAtlasDraw()
        assertEquals(1, d.rects(0f, 0f, 256f, 256f, 8, 4, MediaAtlasDraw.identity(4)))
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 256f, 256f), rects(d).single())
        assertEquals(0, d.rects(10f, 10f, 10f, 50f, 8, 4, MediaAtlasDraw.identity(4)), "an empty rect draws nothing")
    }

    @Test fun theHalfResolutionBeadUsesHalfSizedSlotsInTheSameGrid() {
        val d = MediaAtlasDraw()
        val n = d.rects(100f, 20f, 200f, 60f, 7, 4, MediaAtlasDraw.identity(4))   // half-res cells: tiles of 128
        assertEquals(2, n)
        assertEquals(listOf(0f, 0f, 0f, 0f, 100f, 20f, 128f, 60f), rects(d)[0])
        assertEquals(listOf(128f, 0f, 1f, 0f, 128f, 20f, 200f, 60f), rects(d)[1])
    }

    @Test fun anyPlacementNegativeTilesAndAMissingTile() {
        val d = MediaAtlasDraw()
        // Tiles (-1,-1)..(0,0) placed anywhere in a 4-wide atlas; (0,-1) is not resident and is not drawn.
        val placed = mapOf((-1 to -1) to 9, (0 to 0) to 2, (-1 to 0) to 15)
        val n = d.rects(-10f, -10f, 10f, 10f, 8, 4) { x, y -> placed[x to y] ?: -1 }
        assertEquals(3, n)
        val byTile = rects(d).associateBy { it[2].toInt() to it[3].toInt() }
        assertEquals(listOf(256f, 512f), byTile.getValue(-1 to -1).take(2), "slot 9 = column 1, row 2")
        assertEquals(listOf(-10f, -10f, 0f, 0f), byTile.getValue(-1 to -1).drop(4))
        assertEquals(listOf(512f, 0f), byTile.getValue(0 to 0).take(2))
        assertEquals(listOf(768f, 768f), byTile.getValue(-1 to 0).take(2))
    }

    @Test fun dabsAreEmittedOncePerSlotTheyTouchInDabOrder() {
        val d = MediaAtlasDraw()
        val count = 3
        val src = FloatArray(count * DabBatch.FLOATS) { it.toFloat() }
        val boxes = arrayOf(floatArrayOf(10f, 10f, 20f, 20f), floatArrayOf(250f, 250f, 262f, 262f), floatArrayOf(300f, 10f, 310f, 20f))
        val n = d.dabs(src, count, 2, MediaAtlasDraw.identity(2)) { i, out -> boxes[i].copyInto(out) }
        assertEquals(1 + 4 + 1, n)
        val owners = (0 until n).map { i -> d.dabs[i * MediaAtlasDraw.DAB_FLOATS] / DabBatch.FLOATS }
        assertEquals(listOf(0f, 1f, 1f, 1f, 1f, 2f), owners, "order kept; the corner dab is in all four slots")
        for (i in 0 until n) {
            val o = i * MediaAtlasDraw.DAB_FLOATS
            val dab = owners[i].toInt()
            assertContentEquals(src.copyOfRange(dab * DabBatch.FLOATS, (dab + 1) * DabBatch.FLOATS), d.dabs.copyOfRange(o, o + DabBatch.FLOATS))
            val tx = d.dabs[o + DabBatch.FLOATS + 2]; val ty = d.dabs[o + DabBatch.FLOATS + 3]
            assertEquals(tx * 256, d.dabs[o + DabBatch.FLOATS]); assertEquals(ty * 256, d.dabs[o + DabBatch.FLOATS + 1])
            val b = boxes[dab]
            assertTrue(max(b[0], tx * 256) <= min(b[2], tx * 256 + 255) && max(b[1], ty * 256) <= min(b[3], ty * 256 + 255), "a dab copy touches its slot")
        }
    }

    @Test fun dabsOutsideTheResidentTilesAreNotDrawnAndBuffersGrow() {
        val d = MediaAtlasDraw()
        val count = 200
        val src = FloatArray(count * DabBatch.FLOATS)
        val n = d.dabs(src, count, 1, MediaAtlasDraw.identity(1)) { i, out -> floatArrayOf(i * 3f - 300f, 5f, i * 3f - 290f, 9f).copyInto(out) }
        assertEquals((0 until count).count { it * 3 - 290 >= 0 && it * 3 - 300 <= 255 }, n)
        assertFailsWith<IllegalArgumentException> { d.dabs(FloatArray(10), 1, 1, MediaAtlasDraw.identity(1)) { _, _ -> } }
        val many = d.rects(0f, 0f, 256f * 8, 256f * 8, 8, 8, MediaAtlasDraw.identity(8))
        assertEquals(64, many, "the rect buffer grows past its first 16 instances")
    }
}
