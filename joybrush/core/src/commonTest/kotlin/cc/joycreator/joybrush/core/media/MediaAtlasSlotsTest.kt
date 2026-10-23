package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaAtlasSlotsTest {
    private fun page(layer: String = "L", plane: String? = null, key: Long = 1) = MediaAtlasPage(layer, plane, key)
    private fun slots(n: Int = 3) = MediaAtlasSlots(n, 17, n.toLong() * 17)
    private fun commit(s: MediaAtlasSlots, p: MediaAtlasPage): Int {
        val r = assertIs<MediaAtlasSlots.Reservation>(s.begin(p)); return s.commit(r)
    }

    @Test fun exactBudgetIncludesStagingAndRejectsOneByteShortAndOverflow() {
        assertEquals(51L, MediaAtlasSlots(3, 17, 51).workingBytes)
        assertEquals(2, MediaAtlasSlots(3, 17, 51).capacity)
        assertFailsWith<IllegalArgumentException> { MediaAtlasSlots(3, 17, 50) }
        assertFailsWith<IllegalArgumentException> { MediaAtlasSlots(Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE) }
    }

    @Test fun identityIncludesLayerNullableCelAndSignedTileKeyButNotStore() {
        val s = slots(8)
        val a = page("A", null, Long.MIN_VALUE); val b = page("B", null, Long.MIN_VALUE)
        val cel = page("A", "cel-1", Long.MIN_VALUE); val otherCel = page("A", "cel-2", Long.MIN_VALUE)
        val nullIsDistinct = page("A", "null", Long.MIN_VALUE)
        val ids = listOf(a,b,cel,otherCel,nullIsDistinct).map { commit(s,it) }
        assertEquals(5, ids.toSet().size)
        assertEquals(5, s.residents().size) // p0/p1/paper/water/ground are subresources of each page slot.
        assertNull(s.resident(page("A", null, Long.MAX_VALUE)))
    }

    @Test fun cacheHitsAndAllPinnedRefusalDoNotStartReplacementOrChangeMappings() {
        val s = slots(3); val a = page(key=1); val b = page(key=2); val c = page(key=3)
        val sa = commit(s,a); commit(s,b); s.pin(a); s.pin(b)
        assertEquals(sa, assertIs<MediaAtlasSlots.Present>(s.begin(a)).slot)
        assertEquals(2, s.residents().size)
        assertEquals(MediaAtlasSlots.Refused, s.begin(c))
        assertEquals(setOf(a,b), s.residents().map { it.page }.toSet())
        assertEquals(1, s.resident(a)?.pins); assertEquals(1, s.resident(b)?.pins)
    }

    @Test fun lruIsDeterministicAndPinCyclesProtectThenReleaseVictim() {
        val s = slots(); val a=page(key=1); val b=page(key=2); val c=page(key=3); val d=page(key=4)
        commit(s,a); commit(s,b); s.pin(a)
        val first = assertIs<MediaAtlasSlots.Reservation>(s.begin(c)); assertEquals(b, first.victim?.page); s.cancel(first)
        s.unpin(a); s.touch(a)
        val second = assertIs<MediaAtlasSlots.Reservation>(s.begin(c)); assertEquals(b, second.victim?.page); s.commit(second)
        val third = assertIs<MediaAtlasSlots.Reservation>(s.begin(d)); assertEquals(a, third.victim?.page)
    }

    @Test fun pendingReservationKeepsOldMappingVisibleAndBlocksMutationUntilCommit() {
        val s=slots(2); val old=page(key=1); val incoming=page(key=2); val oldSlot=commit(s,old)
        s.markDirty(old); val r=assertIs<MediaAtlasSlots.Reservation>(s.begin(incoming))
        assertEquals(oldSlot, s.resident(old)?.slot); assertNull(s.resident(incoming)); assertTrue(s.resident(old)!!.dirty)
        assertFailsWith<IllegalStateException> { s.pin(old) }
        assertFailsWith<IllegalStateException> { s.markDirty(old) }
        assertFailsWith<IllegalStateException> { s.begin(page(key=3)) }
        assertFailsWith<IllegalStateException> { s.commit(MediaAtlasSlots(2,17,34).let { assertIs<MediaAtlasSlots.Reservation>(it.begin(page(key=9))) }) }
        s.commit(r); assertNull(s.resident(old)); assertEquals(r.slot, s.resident(incoming)?.slot)
    }

    @Test fun cancellationReusesReservationAndTokensAreSingleUseAndNotPortable() {
        val s=slots(2); val a=page(key=1); val b=page(key=2); commit(s,a)
        val canceled=assertIs<MediaAtlasSlots.Reservation>(s.begin(b)); val slot=canceled.slot
        s.cancel(canceled); assertNull(s.resident(b)); assertNotEquals(null,s.resident(a))
        assertFailsWith<IllegalStateException> { s.cancel(canceled) }
        val retry=assertIs<MediaAtlasSlots.Reservation>(s.begin(b)); assertEquals(slot,retry.slot)
        val foreign=slots(2); assertFailsWith<IllegalStateException> { foreign.commit(retry) }
        s.commit(retry); assertFailsWith<IllegalStateException> { s.commit(retry) }
    }

    @Test fun dirtyAndPinStateDoNotChangeLruButTouchDoes() {
        val s=slots(); val a=page(key=1); val b=page(key=2); val c=page(key=3)
        commit(s,a); commit(s,b); s.markDirty(a); s.pin(a); s.unpin(a)
        val first = assertIs<MediaAtlasSlots.Reservation>(s.begin(c)); assertEquals(a, first.victim?.page); s.cancel(first)
        s.touch(a); val r=assertIs<MediaAtlasSlots.Reservation>(s.begin(c)); assertEquals(b,r.victim?.page)
    }

    @Test fun cleanRemoveAndClearForTheAtlasHost() {
        val s = slots(4)
        val a = page(key = 1); val b = page(key = 2)
        val sa = commit(s, a); commit(s, b)
        s.markDirty(a); s.clean(a)
        assertEquals(false, s.resident(a)!!.dirty, "written back: no write-back owed before reuse")
        s.pin(b)
        assertFailsWith<IllegalStateException> { s.remove(b) }
        s.unpin(b); s.remove(a)
        assertNull(s.resident(a))
        assertEquals(sa, commit(s, page(key = 3)), "the freed slot is handed out again")
        s.clear()
        assertTrue(s.residents().isEmpty())
        assertFailsWith<IllegalStateException> { s.clean(a) }
    }

    @Test fun withALayoutAFramesPagesGatherNearWhatItHolds() {
        // 4 columns x 4 rows, 15 pages + staging. Hold the pages in the top-left corner; the oldest unheld pages are far away.
        val s = MediaAtlasSlots(16, 1, 16, columns = 4)
        val pages = (0L until 15L).map { page(key = it) }
        val at = pages.associateWith { commit(s, it) }
        assertEquals((0 until 15).toList(), at.values.toList(), "an empty atlas fills in order")
        for (k in listOf(0L, 1L, 4L)) s.pin(pages[k.toInt()])
        // Least recently used first: make the far corner's pages the oldest, then one near the corner.
        for (k in listOf(14L, 13L, 11L, 5L)) s.touch(pages[k.toInt()])
        for (k in listOf(2L, 3L, 6L, 7L, 8L, 9L, 10L, 12L)) s.touch(pages[k.toInt()])
        val r = assertIs<MediaAtlasSlots.Reservation>(s.begin(page(key = 99)))
        assertEquals(5, r.victim!!.slot, "of the 4 oldest (slots 14, 13, 11, 5) the one beside the held corner gives way")
        assertEquals(15, r.slot, "the new page takes the free slot")
        s.commit(r)
        val r2 = assertIs<MediaAtlasSlots.Reservation>(s.begin(page(key = 100)))
        assertEquals(5, r2.slot, "and the next new page lands where that one was, beside the corner")
        s.cancel(r2)
        val plain = MediaAtlasSlots(16, 1, 16)
        pages.forEach { commit(plain, it) }
        plain.pin(pages[0])
        assertEquals(1, assertIs<MediaAtlasSlots.Reservation>(plain.begin(page(key = 99))).victim!!.slot, "no layout: least recently used, as before")
    }
}
