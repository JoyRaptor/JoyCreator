package cc.joycreator.joybrush.core.stroke

import kotlin.random.Random
import kotlin.test.*

class CapturedPhysicalSourceTest {
    @Test fun `rectangles require positive extents`() {
        for ((w, h) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1)) {
            assertFailsWith<IllegalArgumentException> { CaptureRect(0, 0, w, h) }
        }
    }

    @Test fun `final addressable pixel has a Long exclusive edge`() {
        val r = CaptureRect(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1)
        assertEquals(Int.MAX_VALUE + 1L, r.right)
        assertEquals(Int.MAX_VALUE + 1L, r.bottom)
        assertTrue(r.contains(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(r, CaptureDomain(r).clip(r).single())
    }

    @Test fun `rectangle endpoints cannot extend past the Int pixel range`() {
        assertFailsWith<IllegalArgumentException> { CaptureRect(Int.MAX_VALUE, 0, 2, 1) }
        assertFailsWith<IllegalArgumentException> { CaptureRect(0, Int.MAX_VALUE, 1, 2) }
        assertEquals(Int.MIN_VALUE + 1L, CaptureRect(Int.MIN_VALUE, Int.MIN_VALUE, 1, 1).right)
    }

    @Test fun `rectangle boundaries are exactly half open`() {
        val r = CaptureRect(-3, -5, 7, 11)
        assertTrue(r.contains(-3, -5))
        assertTrue(r.contains(3, 5))
        for ((x, y) in listOf(-4 to -5, -3 to -6, 4 to 5, 3 to 6)) assertFalse(r.contains(x, y))
    }

    @Test fun `touching rectangles have no intersection`() {
        val r = CaptureRect(-2, -2, 4, 4)
        assertNull(r.intersect(CaptureRect(2, -2, 3, 4)))
        assertNull(r.intersect(CaptureRect(-2, 2, 4, 3)))
        assertEquals(CaptureRect(1, 1, 1, 1), r.intersect(CaptureRect(1, 1, 4, 4)))
    }

    @Test fun `tile query keeps negative origins and partial pixel ownership`() {
        val domain = CaptureDomain(CaptureRect(-7, -11, 20, 30))
        assertEquals(listOf(CaptureRect(-7, -11, 7, 11)), domain.clipTile(-1, -1, 256))
        assertEquals(listOf(CaptureRect(0, 0, 13, 19)), domain.clipTile(0, 0, 256))
    }

    @Test fun `tile origins outside addressable pixels refuse instead of wrapping`() {
        assertFailsWith<IllegalArgumentException> { CaptureRect.tile(Int.MAX_VALUE, 0, 256) }
        assertFailsWith<IllegalArgumentException> { CaptureRect.tile(0, Int.MIN_VALUE, 256) }
        assertFailsWith<IllegalArgumentException> { CaptureRect.tile(0, 0, 0) }
        assertEquals(CaptureRect(2147483392, 0, 256, 256), CaptureRect.tile(8388607, 0, 256))
        assertEquals(Int.MIN_VALUE, CaptureRect.tile(-8388608, 0, 256).x)
    }

    @Test fun `unbounded complement only enumerates its bounded query`() {
        val domain = CaptureDomain()
        val query = CaptureRect(-1000, -2000, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(listOf(query), domain.clip(query))
        assertTrue(domain.contains(Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test fun `disjoint window and query is empty`() {
        val domain = CaptureDomain(CaptureRect(-5, -5, 3, 3))
        assertEquals(emptyList(), domain.clip(CaptureRect(0, 0, 4, 4)))
        assertFalse(domain.contains(0, 0))
    }

    @Test fun `window and exclusion edges agree with point membership`() {
        val domain = CaptureDomain(CaptureRect(-5, -5, 10, 10), listOf(CaptureRect(-2, -2, 4, 4)))
        assertCoverage(domain, CaptureRect(-7, -7, 14, 14))
        assertTrue(domain.contains(-3, 0))
        assertFalse(domain.contains(-2, 0))
        assertTrue(domain.contains(2, 0))
    }

    @Test fun `multiple exclusions in the same tile preserve every remaining pixel once`() {
        val domain = CaptureDomain(excluded = listOf(
            CaptureRect(3, 7, 20, 30), CaptureRect(30, 4, 17, 40), CaptureRect(80, 20, 30, 10)))
        assertCoverage(domain, CaptureRect.tile(0, 0, 256))
    }

    @Test fun `overlapping and duplicate exclusions form their exact union`() {
        val a = CaptureRect(-5, -2, 10, 5)
        val b = CaptureRect(-2, -5, 5, 10)
        val domain = CaptureDomain(excluded = listOf(a, b, a))
        assertEquals(2, domain.excluded.size)
        assertCoverage(domain, CaptureRect(-8, -8, 16, 16))
        assertFalse(domain.contains(0, 0))
        assertFalse(domain.contains(-4, 0))
        assertFalse(domain.contains(0, -4))
        assertTrue(domain.contains(-4, -4))
    }

    @Test fun `canonical values and results do not depend on caller ordering`() {
        val rects = listOf(CaptureRect(1, 2, 5, 6), CaptureRect(-3, -8, 2, 7), CaptureRect(1, 2, 5, 4))
        val a = CaptureDomain(excluded = rects)
        val b = CaptureDomain(excluded = rects.reversed() + rects.first())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(listOf(rects[1], rects[2], rects[0]), a.excluded)
        assertEquals(a.clip(CaptureRect(-10, -10, 30, 30)), b.clip(CaptureRect(-10, -10, 30, 30)))
    }

    @Test fun `caller mutation cannot change domain or an earlier clipping result`() {
        val cuts = mutableListOf(CaptureRect(1, 1, 2, 2))
        val domain = CaptureDomain(excluded = cuts)
        val query = CaptureRect(0, 0, 4, 4)
        val before = domain.clip(query)
        val hash = domain.hashCode()
        cuts[0] = query
        cuts.clear()
        assertFalse(domain.contains(1, 1))
        assertEquals(hash, domain.hashCode())
        assertEquals(before, domain.clip(query))
        assertFails { (domain.excluded as MutableList<CaptureRect>)[0] = query }
        assertFails { (before as MutableList<CaptureRect>).clear() }
        assertEquals(before, domain.clip(query))
    }

    @Test fun `exclusion budget is checked before duplicate removal`() {
        val cut = CaptureRect(0, 0, 1, 1)
        assertFailsWith<IllegalArgumentException> {
            CaptureDomain(excluded = List(CAPTURE_DOMAIN_MAX_EXCLUSIONS + 1) { cut })
        }
        assertEquals(listOf(cut), CaptureDomain(excluded = List(CAPTURE_DOMAIN_MAX_EXCLUSIONS) { cut }).excluded)
    }

    @Test fun `sixty four crossing exclusions have bounded exact fragments`() {
        val cuts = (0 until 32).map { CaptureRect(it * 4 + 1, 0, 1, 130) } +
            (0 until 32).map { CaptureRect(0, it * 4 + 1, 130, 1) }
        val domain = CaptureDomain(excluded = cuts)
        val query = CaptureRect(0, 0, 130, 130)
        val pieces = domain.clip(query)
        assertEquals(33 * 33, pieces.size)
        assertTrue(pieces.size <= CAPTURE_DOMAIN_MAX_CLIP_RECTS)
        assertCoverage(domain, query)
    }

    @Test fun `full exclusion and outside exclusions do not create phantom fragments`() {
        val query = CaptureRect(-3, -3, 6, 6)
        assertEquals(emptyList(), CaptureDomain(excluded = listOf(query)).clip(query))
        assertEquals(listOf(query), CaptureDomain(excluded = listOf(CaptureRect(3, 3, 6, 6))).clip(query))
    }

    @Test fun `adjacent bands with equal free spans merge vertically`() {
        val domain = CaptureDomain(excluded = listOf(CaptureRect(2, 0, 2, 2), CaptureRect(2, 2, 2, 2)))
        assertEquals(listOf(CaptureRect(0, 0, 2, 4), CaptureRect(4, 0, 2, 4)), domain.clip(CaptureRect(0, 0, 6, 4)))
    }

    @Test fun `random bounded queries match an independent membership oracle`() {
        val random = Random(15015)
        repeat(60) {
            val window = if (it % 3 == 0) null else CaptureRect(random.nextInt(-10, 1), random.nextInt(-10, 1), 15, 15)
            val cuts = List(8) { CaptureRect(random.nextInt(-12, 12), random.nextInt(-12, 12), random.nextInt(1, 10), random.nextInt(1, 10)) }
            val domain = CaptureDomain(window, cuts)
            assertCoverage(domain, CaptureRect(-12, -12, 24, 24)) { x, y ->
                (window == null || window.contains(x, y)) && cuts.none { it.contains(x, y) }
            }
        }
    }

    @Test fun `source and destination points roundtrip exactly`() {
        val placement = SourcePlacement(-513, 257)
        val target = placement.sourceToDestination(255, -256)
        assertEquals(CapturePixel(-258, 1), target)
        assertEquals(CapturePixel(255, -256), placement.destinationToSource(target.x, target.y))
        assertEquals(CapturePixel(0, 0), SourcePlacement(Int.MIN_VALUE, 0).destinationToSource(Int.MIN_VALUE, 0))
    }

    @Test fun `point mapping refuses positive and negative overflow`() {
        assertFailsWith<IllegalArgumentException> { SourcePlacement(1, 0).sourceToDestination(Int.MAX_VALUE, 0) }
        assertFailsWith<IllegalArgumentException> { SourcePlacement(-1, 0).sourceToDestination(Int.MIN_VALUE, 0) }
        assertFailsWith<IllegalArgumentException> { SourcePlacement(0, 1).destinationToSource(0, Int.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { SourcePlacement(Int.MIN_VALUE, 0).destinationToSource(0, 0) }
    }

    @Test fun `rectangle mapping preserves extents and final exclusive edge`() {
        val source = CaptureRect(Int.MAX_VALUE - 4, -256, 4, 256)
        val placement = SourcePlacement(1, 256)
        val destination = CaptureRect(Int.MAX_VALUE - 3, 0, 4, 256)
        assertEquals(destination, placement.sourceToDestination(source))
        assertEquals(source, placement.destinationToSource(destination))
        assertEquals(Int.MAX_VALUE + 1L, destination.right)
    }

    @Test fun `rectangle mapping refuses endpoint overflow instead of clipping it`() {
        assertFailsWith<IllegalArgumentException> { SourcePlacement(1, 0).sourceToDestination(CaptureRect(Int.MAX_VALUE, 0, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { SourcePlacement(0, 1).destinationToSource(CaptureRect(0, Int.MIN_VALUE, 1, 1)) }
    }

    @Test fun `translation composition is exact and refuses cumulative overflow`() {
        val a = SourcePlacement(-256, 19)
        val b = SourcePlacement(513, -20)
        assertEquals(SourcePlacement(257, -1), a.then(b))
        val first = a.sourceToDestination(100, -100)
        assertEquals(b.sourceToDestination(first.x, first.y), a.then(b).sourceToDestination(100, -100))
        assertFailsWith<IllegalArgumentException> { SourcePlacement(Int.MAX_VALUE, 0).then(SourcePlacement(1, 0)) }
        assertFailsWith<IllegalArgumentException> { SourcePlacement(0, Int.MIN_VALUE).then(SourcePlacement(0, -1)) }
    }

    @Test fun `placed clipping maps the same source across tile origins`() {
        val domain = CaptureDomain(CaptureRect(-2, -2, 4, 4), listOf(CaptureRect(-1, -1, 2, 2)))
        val source = CapturedPhysicalSource(domain, SourcePlacement(256, -256))
        val query = CaptureRect(252, -260, 8, 8)
        val pieces = source.clipDestination(query)
        for (y in query.y until query.bottom.toInt()) for (x in query.x until query.right.toInt()) {
            assertEquals(if (domain.contains(x - 256, y + 256)) 1 else 0, pieces.count { it.contains(x, y) })
        }
        assertEquals(listOf(CaptureRect(257, -256, 1, 1), CaptureRect(256, -255, 2, 1)),
            source.clipDestinationTile(1, -1, 256))
    }

    @Test fun `placed unbounded complement preserves excluded and owned destinations`() {
        val source = CapturedPhysicalSource(CaptureDomain(excluded = listOf(CaptureRect(-4, -4, 8, 8))), SourcePlacement(-257, 513))
        val query = CaptureRect(-265, 505, 16, 16)
        val pieces = source.clipDestination(query)
        for (y in 505 until 521) for (x in -265 until -249) {
            assertEquals(if (x in -261 until -253 && y in 509 until 517) 0 else 1, pieces.count { it.contains(x, y) })
        }
    }

    @Test fun `unaddressable inverse query refuses even for an empty domain result`() {
        val source = CapturedPhysicalSource(CaptureDomain(CaptureRect(0, 0, 1, 1)), SourcePlacement(1, 0))
        assertFailsWith<IllegalArgumentException> { source.clipDestination(CaptureRect(Int.MIN_VALUE, 0, 1, 1)) }
    }

    @Test fun `source values use domain and placement equality without exposing mutable state`() {
        val a = CapturedPhysicalSource(CaptureDomain(excluded = listOf(CaptureRect(1, 2, 3, 4))), SourcePlacement(5, 6))
        val b = CapturedPhysicalSource(CaptureDomain(excluded = listOf(CaptureRect(1, 2, 3, 4))), SourcePlacement(5, 6))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(placement = SourcePlacement(6, 6)))
        val pieces = a.clipDestination(CaptureRect(0, 0, 12, 12))
        assertFails { (pieces as MutableList<CaptureRect>).add(CaptureRect(0, 0, 1, 1)) }
        assertEquals(pieces, a.clipDestination(CaptureRect(0, 0, 12, 12)))
    }

    private fun assertCoverage(domain: CaptureDomain, query: CaptureRect,
                               owned: (Int, Int) -> Boolean = domain::contains) {
        val pieces = domain.clip(query)
        assertTrue(pieces.size <= CAPTURE_DOMAIN_MAX_CLIP_RECTS)
        for (piece in pieces) assertEquals(piece, piece.intersect(query))
        for (y in query.y until query.bottom.toInt()) for (x in query.x until query.right.toInt()) {
            assertEquals(if (owned(x, y)) 1 else 0, pieces.count { it.contains(x, y) }, "pixel $x,$y")
        }
    }
}
