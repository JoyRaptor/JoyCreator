package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CapturedSourceCopyPlanTest {
    private fun key(x: Int, y: Int) = Tiles.key(x, y)
    private fun source(window: CaptureRect? = null, exclusions: List<CaptureRect> = emptyList(),
                       dx: Int = 0, dy: Int = 0) = CapturedPhysicalSource(CaptureDomain(window, exclusions), SourcePlacement(dx, dy))

    /** Independent SMALL pixel oracle: no domain.clip, placement map or rectangle splitting. */
    private fun oracle(keys: Set<Long>, source: CapturedPhysicalSource, size: Int): Map<CapturePixel, CapturePixel> {
        fun inside(r: CaptureRect, x: Long, y: Long) = x >= r.x && y >= r.y &&
            x < r.x.toLong() + r.w && y < r.y.toLong() + r.h
        val out = mutableMapOf<CapturePixel, CapturePixel>()
        for (key in keys) for (y in 0 until size) for (x in 0 until size) {
            val sx = Tiles.tx(key).toLong() * size + x
            val sy = Tiles.ty(key).toLong() * size + y
            val domain = source.domain
            if (domain.window?.let { !inside(it, sx, sy) } == true || domain.excluded.any { inside(it, sx, sy) }) continue
            val dx = sx + source.placement.dx
            val dy = sy + source.placement.dy
            assertTrue(sx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && sy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            assertTrue(dx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && dy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            val previous = out.put(CapturePixel(dx.toInt(), dy.toInt()), CapturePixel(sx.toInt(), sy.toInt()))
            assertEquals(null, previous, "Oracle source pixels map injectively")
        }
        return out
    }

    /** Expand descriptors, asserting both local bounds and globally disjoint destination writes. */
    private fun expand(copies: List<CapturedSourceCopyPlan.Copy>, size: Int): Map<CapturePixel, CapturePixel> {
        val out = mutableMapOf<CapturePixel, CapturePixel>()
        val sourcePixels = mutableSetOf<CapturePixel>()
        for (copy in copies) {
            assertEquals(copy.from.w, copy.to.w)
            assertEquals(copy.from.h, copy.to.h)
            for (r in listOf(copy.from, copy.to)) {
                assertTrue(r.x >= 0 && r.y >= 0 && r.right <= size && r.bottom <= size)
            }
            for (y in 0 until copy.from.h) for (x in 0 until copy.from.w) {
                val sx = Tiles.tx(copy.sourceKey).toLong() * size + copy.from.x + x
                val sy = Tiles.ty(copy.sourceKey).toLong() * size + copy.from.y + y
                val dx = Tiles.tx(copy.destinationKey).toLong() * size + copy.to.x + x
                val dy = Tiles.ty(copy.destinationKey).toLong() * size + copy.to.y + y
                assertTrue(sx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && sy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                assertTrue(dx in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && dy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                val from = CapturePixel(sx.toInt(), sy.toInt())
                val to = CapturePixel(dx.toInt(), dy.toInt())
                assertTrue(sourcePixels.add(from), "Source domain was copied twice at $from")
                assertEquals(null, out.put(to, from), "Destination rectangles overlap at $to")
            }
        }
        return out
    }

    private fun checkPixels(keys: Set<Long>, source: CapturedPhysicalSource, size: Int = 4): CapturedSourceCopyPlan {
        val plan = CapturedSourceCopyPlan.prepare(keys, source, size)
        assertEquals(keys, plan.sourceKeys)
        assertEquals(oracle(keys, source, size), expand(plan.copies, size))
        assertEquals(plan.copies.map { it.destinationKey }.toSet(), plan.destinationKeys)
        assertTrue(plan.copies.all { it.sourceKey in keys })
        return plan
    }

    @Test fun identityCopiesOnlyActualSparseSourceTiles() {
        val keys = setOf(key(-2, 1), key(3, -1))
        val plan = checkPixels(keys, source())
        assertEquals(keys, plan.destinationKeys)
        assertEquals(2, plan.copies.size)
        assertTrue(plan.copies.all { it.from == CaptureRect(0, 0, 4, 4) && it.to == it.from })
    }

    @Test fun emptyActualSourceNeverEnumeratesAnUnboundedComplement() {
        val plan = CapturedSourceCopyPlan.prepare(emptySet(), source(dx = Int.MAX_VALUE, dy = Int.MIN_VALUE))
        assertTrue(plan.sourceKeys.isEmpty() && plan.destinationKeys.isEmpty() && plan.copies.isEmpty())
    }

    @Test fun windowInMissingGapDoesNotManufactureSourceOrDestination() {
        val plan = checkPixels(setOf(key(0, 0), key(10, 0)), source(CaptureRect(12, 0, 4, 4)))
        assertTrue(plan.destinationKeys.isEmpty() && plan.copies.isEmpty())
    }

    @Test fun halfOpenWindowIncludesLeftTopAndExcludesRightBottom() {
        val plan = checkPixels(setOf(key(0, 0), key(1, 0), key(0, 1)), source(CaptureRect(1, 1, 3, 3)))
        val pixels = expand(plan.copies, 4)
        assertEquals(9, pixels.size)
        assertTrue(CapturePixel(1, 1) in pixels && CapturePixel(3, 3) in pixels)
        assertTrue(CapturePixel(4, 1) !in pixels && CapturePixel(1, 4) !in pixels)
        assertEquals(setOf(key(0, 0)), plan.destinationKeys)
    }

    @Test fun multipleSameTileExclusionsKeepEveryComplementFragment() {
        val plan = checkPixels(setOf(key(0, 0)), source(exclusions = listOf(
            CaptureRect(0, 1, 1, 2), CaptureRect(2, 0, 1, 3))))
        assertEquals(11, expand(plan.copies, 4).size)
        assertTrue(plan.copies.size > 1)
    }

    @Test fun overlappingAndDuplicateExclusionsUseUnionWithoutDoubleCopies() {
        val a = CaptureRect(0, 0, 3, 2)
        val b = CaptureRect(1, 1, 3, 3)
        val plan = checkPixels(setOf(key(0, 0)), source(exclusions = listOf(b, a, a)))
        assertEquals(3, expand(plan.copies, 4).size)
    }

    @Test fun completelyExcludedActualTilesHaveExplicitEmptyDestination() {
        val plan = checkPixels(setOf(key(-1, -1)), source(exclusions = listOf(CaptureRect(-4, -4, 4, 4))))
        assertEquals(setOf(key(-1, -1)), plan.sourceKeys)
        assertTrue(plan.copies.isEmpty() && plan.destinationKeys.isEmpty())
    }

    @Test fun positivePlacementSplitsAtBothDestinationAxes() {
        val plan = checkPixels(setOf(key(0, 0)), source(dx = 1, dy = 1))
        assertEquals(setOf(key(0, 0), key(1, 0), key(0, 1), key(1, 1)), plan.destinationKeys)
        assertEquals(4, plan.copies.size)
        assertTrue(plan.copies.any { it.from != it.to })
    }

    @Test fun negativePlacementUsesFloorGridAcrossZero() {
        val plan = checkPixels(setOf(key(0, 0)), source(dx = -1, dy = -1))
        assertEquals(setOf(key(-1, -1), key(0, -1), key(-1, 0), key(0, 0)), plan.destinationKeys)
        assertEquals(CapturePixel(0, 0), expand(plan.copies, 4)[CapturePixel(-1, -1)])
    }

    @Test fun largeSignedPlacementPreservesSmallPixelMapAndSparseHoles() {
        checkPixels(setOf(key(-2, 1), key(0, 0), key(3, -1)), source(
            exclusions = listOf(CaptureRect(1, 1, 2, 2)), dx = -257, dy = 513))
    }

    @Test fun nonPowerOfTwoTileSizeAndNegativeOriginsAreSupported() {
        checkPixels(setOf(key(-1, -1), key(0, 1)), source(CaptureRect(-3, -3, 6, 9), dx = 2, dy = -4), 3)
    }

    @Test fun exactMaxExclusiveEdgeIsRetainedForAddressableSourceTile() {
        val maxTile = key(8388607, 8388607)
        val plan = CapturedSourceCopyPlan.prepare(setOf(maxTile), source())
        assertEquals(setOf(maxTile), plan.destinationKeys)
        assertEquals(CaptureRect(0, 0, 256, 256), plan.copies.single().from)
        assertEquals(plan.copies.single().from, plan.copies.single().to)
    }

    @Test fun finalAddressablePixelCanMoveIntoNegativeTile() {
        val plan = checkPixels(setOf(key(Int.MAX_VALUE, 0)),
            source(dx = Int.MIN_VALUE), 1)
        assertEquals(setOf(key(-1, 0)), plan.destinationKeys)
        assertEquals(CapturePixel(Int.MAX_VALUE, 0), expand(plan.copies, 1)[CapturePixel(-1, 0)])
    }

    @Test fun sourceAndDestinationBoundaryPaddingForOddTileSizeIsNotCopied() {
        val original = key(715827881, 0) // source pixels 2147483643..2147483645, tile size3
        val plan = checkPixels(setOf(original), source(dx = 2), 3)
        assertEquals(setOf(original, key(715827882, 0)), plan.destinationKeys)
        assertTrue(expand(plan.copies, 3).keys.any { it.x == Int.MAX_VALUE })
    }

    @Test fun minAddressableSourceAndMaxTranslationSplitAcrossZero() {
        val plan = CapturedSourceCopyPlan.prepare(setOf(key(-8388608, -8388608)),
            source(CaptureRect(Int.MIN_VALUE, Int.MIN_VALUE, 2, 2), dx = Int.MAX_VALUE, dy = Int.MAX_VALUE))
        assertEquals(4, plan.copies.size)
        assertEquals(setOf(CapturePixel(-1, -1), CapturePixel(0, -1), CapturePixel(-1, 0), CapturePixel(0, 0)),
            expand(plan.copies, 256).keys)
    }

    @Test fun placementOverflowRefusesWholePlan() {
        for (src in listOf(source(dx = 1), source(dy = 1))) assertFailsWith<IllegalArgumentException> {
            CapturedSourceCopyPlan.prepare(setOf(key(8388607, 8388607)), src)
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedSourceCopyPlan.prepare(setOf(key(-8388608, 0)), source(dx = -1))
        }
    }

    @Test fun clippedAwayOverflowDoesNotRejectSurvivingAddressableFragment() {
        val tile = key(8388607, 0)
        val plan = CapturedSourceCopyPlan.prepare(setOf(tile), source(
            CaptureRect(2147483392, 0, 255, 1), dx = 1))
        assertEquals(CaptureRect(0, 0, 255, 1), plan.copies.single().from)
        assertEquals(CaptureRect(1, 0, 255, 1), plan.copies.single().to)
        val none = CapturedSourceCopyPlan.prepare(setOf(tile), source(
            exclusions = listOf(CaptureRect(2147483392, 0, 256, 256)), dx = 1))
        assertTrue(none.copies.isEmpty())
    }

    @Test fun sourceTileOutsideAddressablePixelsRefusesRatherThanWrapping() {
        for (bad in listOf(key(Int.MAX_VALUE, 0), key(0, Int.MIN_VALUE))) {
            assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.prepare(setOf(bad), source()) }
        }
    }

    @Test fun largeBoundedQueryClipsWithoutAllocatingByWorldArea() {
        val plan = CapturedSourceCopyPlan.prepare(setOf(key(-1, -1)),
            source(CaptureRect(-5, -7, 2, 3), dx = 10, dy = 10), Int.MAX_VALUE)
        assertEquals(1, plan.copies.size)
        assertEquals(CaptureRect(Int.MAX_VALUE - 5, Int.MAX_VALUE - 7, 2, 3), plan.copies.single().from)
        assertEquals(CaptureRect(5, 3, 2, 3), plan.copies.single().to)
    }

    @Test fun invalidTileSizeAndNonpositiveLimitsRefuse() {
        for (size in listOf(0, -1)) assertFailsWith<IllegalArgumentException> {
            CapturedSourceCopyPlan.prepare(emptySet(), source(), size)
        }
        for (n in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.Limits(maxSourceTiles = n) }
            assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.Limits(maxDestinationTiles = n) }
            assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.Limits(maxCopies = n) }
        }
    }

    @Test fun sourceBudgetIsCheckedBeforeEnumeratingKeysEvenWhenClipWouldBeEmpty() {
        val tooMany = object : AbstractSet<Long>() {
            override val size: Int get() = 65
            override fun iterator(): Iterator<Long> = error("Source limit must be checked before copying or sorting")
        }
        assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.prepare(tooMany, source()) }
        assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.prepare(setOf(key(0, 0), key(1, 0)),
            source(CaptureRect(100, 100, 1, 1)), 4, CapturedSourceCopyPlan.Limits(maxSourceTiles = 1)) }
    }

    @Test fun destinationBudgetCountsDistinctTilesRatherThanCopies() {
        val keys = setOf(key(0, 0), key(1, 0))
        assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.prepare(keys, source(dx = 1), 4,
            CapturedSourceCopyPlan.Limits(maxDestinationTiles = 2)) }
        val plan = CapturedSourceCopyPlan.prepare(keys, source(dx = 1), 4,
            CapturedSourceCopyPlan.Limits(maxDestinationTiles = 3))
        assertEquals(3, plan.destinationKeys.size)
        assertEquals(4, plan.copies.size)
    }

    @Test fun copyBudgetRefusesWholePlanWithoutChangingInput() {
        val keys = mutableSetOf(key(0, 0))
        val src = source(exclusions = listOf(CaptureRect(1, 0, 1, 4)))
        assertFailsWith<IllegalArgumentException> { CapturedSourceCopyPlan.prepare(keys, src, 4,
            CapturedSourceCopyPlan.Limits(maxCopies = 1)) }
        assertEquals(setOf(key(0, 0)), keys)
        val plan = CapturedSourceCopyPlan.prepare(keys, src, 4, CapturedSourceCopyPlan.Limits(maxCopies = 2))
        assertEquals(2, plan.copies.size)
        assertEquals(oracle(keys, src, 4), expand(plan.copies, 4))
    }

    @Test fun inputAndResultCollectionsCannotMutatePreparedPlan() {
        val keys = mutableSetOf(key(0, 0), key(1, 0))
        val exclusions = mutableListOf(CaptureRect(0, 0, 1, 1))
        val src = source(exclusions = exclusions, dx = 1)
        val plan = CapturedSourceCopyPlan.prepare(keys, src, 4)
        val expectedKeys = keys.toSet()
        val expectedCopies = plan.copies.toList()
        keys.clear(); exclusions.clear()
        assertEquals(expectedKeys, plan.sourceKeys)
        assertEquals(expectedCopies, plan.copies)
        assertFails { (plan.sourceKeys as MutableSet<Long>).clear() }
        assertFails { (plan.destinationKeys as MutableSet<Long>).clear() }
        assertFails { (plan.copies as MutableList<CapturedSourceCopyPlan.Copy>).clear() }
        assertEquals(expectedCopies, plan.copies)
        assertEquals(listOf(CaptureRect(0, 0, 1, 1)), plan.source.domain.excluded)
    }

    @Test fun canonicalOrderingDoesNotDependOnCallerSetOrExclusionOrder() {
        val keys = listOf(key(1, 0), key(-1, -1), key(0, 0))
        val cuts = listOf(CaptureRect(0, 1, 1, 2), CaptureRect(2, 0, 1, 3))
        val a = CapturedSourceCopyPlan.prepare(keys.toSet(), source(exclusions = cuts, dx = -1, dy = 2), 4)
        val b = CapturedSourceCopyPlan.prepare(keys.reversed().toSet(), source(exclusions = cuts.reversed(), dx = -1, dy = 2), 4)
        assertEquals(a.sourceKeys.toList(), b.sourceKeys.toList())
        assertEquals(a.destinationKeys.toList(), b.destinationKeys.toList())
        assertEquals(a.copies, b.copies)
    }

    @Test fun deterministicSmallPixelCorpusMatchesIndependentOracleAndIsDisjoint() {
        val random = Random(49157)
        repeat(200) {
            val size = random.nextInt(1, 8)
            val keys = List(random.nextInt(0, 6)) { key(random.nextInt(-2, 3), random.nextInt(-2, 3)) }.toSet()
            fun rect() = CaptureRect(random.nextInt(-12, 13), random.nextInt(-12, 13),
                random.nextInt(1, 10), random.nextInt(1, 10))
            val src = source(if (random.nextBoolean()) rect() else null,
                List(random.nextInt(0, 6)) { rect() }, random.nextInt(-9, 10), random.nextInt(-9, 10))
            checkPixels(keys, src, size)
        }
    }

    @Test fun wrongLocalOriginPositiveControlIsDetectedByPixelOracle() {
        val keys = setOf(key(0, 0))
        val src = source(dx = 1)
        val plan = checkPixels(keys, src)
        val wrong = plan.copies.map { it.copy(to = it.from) }
        assertNotEquals(oracle(keys, src, 4), expand(wrong, 4), "Oracle must catch copying to the source-local origin")
    }
}
