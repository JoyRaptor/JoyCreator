package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.core.media.MediaAtlasPage
import cc.joycreator.joybrush.core.media.MediaAtlasSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaAtlasResidencyTest {
    private fun page(key: Long) = MediaAtlasPage("layer", null, key)

    /** Models complete per-page state with ordinary values; it makes no shader or GPU-precision claim. */
    private class FakeBacking : MediaAtlasBacking {
        val pixels = mutableMapOf<Int, String>()
        val durable = mutableMapOf<MediaAtlasPage, String>()
        val events = mutableListOf<String>()
        var failLoad = false
        var failWrite = false
        var failEvicted = false
        var observe: (() -> Unit)? = null
        var observeCommit: (() -> Unit)? = null
        val admissions = mutableListOf<String>()

        override fun beginAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {
            admissions += "begin:${page.tileKey}:${victim?.page?.tileKey}"
        }

        override fun commitAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {
            admissions += "commit:${page.tileKey}:${victim?.page?.tileKey}"
            observeCommit?.invoke()
        }

        override fun rollbackAdmission(page: MediaAtlasPage, victim: MediaAtlasSlots.Resident?) {
            admissions += "rollback:${page.tileKey}:${victim?.page?.tileKey}"
        }

        override fun load(slot: Int, page: MediaAtlasPage) {
            events += "load:${page.tileKey}"
            observe?.invoke()
            pixels[slot] = "partially-loaded-${page.tileKey}"
            // A failed upload can leave staging corrupt; it is invisible and must be overwritten on retry.
            if (failLoad) { failLoad = false; error("load failed") }
            pixels[slot] = durable[page] ?: "zero:${page.tileKey}"
        }

        override fun writeBack(slot: Int, page: MediaAtlasPage) {
            events += "write:${page.tileKey}"
            observe?.invoke()
            val next = pixels[slot] ?: error("missing physical contents")
            if (failWrite) { failWrite = false; error("atomic write-back failed") }
            durable[page] = next
        }

        override fun evicted(slot: Int, page: MediaAtlasPage) {
            if (failEvicted) { failEvicted = false; error("eviction failed") }
        }
    }

    private data class Fixture(val slots: MediaAtlasSlots, val backing: FakeBacking, val residency: MediaAtlasResidency)
    private fun fixture(capacity: Int = 2): Fixture {
        val slots=MediaAtlasSlots(capacity+1, 1, (capacity+1).toLong())
        val backing=FakeBacking()
        return Fixture(slots,backing,MediaAtlasResidency(slots,backing))
    }

    @Test fun hitAndPinnedRefusalCallNoBackendAndDoNotChangePages() {
        val f=fixture(1); val a=page(1); val b=page(2)
        val slot=f.residency.ensure(a)!!; val before=f.backing.events.toList()
        assertEquals(slot,f.residency.ensure(a)); assertEquals(before,f.backing.events)
        f.slots.pin(a); assertNull(f.residency.ensure(b)); assertEquals(before,f.backing.events)
        assertEquals(a,f.slots.residents().single().page)
    }

    @Test fun completeLoadPrecedesDirtyWriteBackAndOldMappingRemainsVisibleInBothCallbacks() {
        val f=fixture(1); val old=page(1); val incoming=page(2)
        val oldSlot=f.residency.ensure(old)!!; f.backing.pixels[oldSlot]="painted-old"; f.slots.markDirty(old)
        val stage=oldSlot.let { (0 until 2).first { it != oldSlot } }
        f.backing.durable[incoming]="loaded-new"
        f.backing.observe = {
            assertEquals(oldSlot,f.slots.resident(old)?.slot)
            assertNull(f.slots.resident(incoming))
        }
        val next=f.residency.ensure(incoming)!!
        assertEquals(stage,next); assertEquals(listOf("load:1","load:2","write:1"), f.backing.events)
        assertEquals("painted-old",f.backing.durable[old]); assertEquals(incoming,f.slots.residents().single().page)
        assertEquals("loaded-new",f.backing.pixels[next]); assertNull(f.slots.resident(old))
    }

    @Test fun loadFailurePreservesVictimSlotMappingContentsAndDirtyFlagAndCanRetry() {
        val f=fixture(1); val old=page(1); val incoming=page(2); val slot=f.residency.ensure(old)!!
        f.backing.pixels[slot]="old-live"; f.slots.markDirty(old); f.backing.failLoad=true
        assertFailsWith<IllegalStateException> { f.residency.ensure(incoming) }
        assertEquals("old-live",f.backing.pixels[slot]); assertEquals(old,f.slots.residents().single().page)
        assertTrue(f.slots.resident(old)!!.dirty); assertNull(f.slots.resident(incoming))
        val stage = (0 until 2).first { it != slot }
        assertEquals("partially-loaded-2", f.backing.pixels[stage])
        assertEquals(listOf("load:1", "load:2"), f.backing.events) // Never write back after a failed load.
        assertNull(f.backing.durable[old])
        f.residency.ensure(incoming)
        assertEquals("old-live",f.backing.durable[old]); assertEquals(incoming,f.slots.residents().single().page)
        assertEquals("zero:2", f.backing.pixels[stage])
    }

    @Test fun failedAtomicWriteBackLeavesOldStateAndDirtyMappingVisibleThenRetrySucceeds() {
        val f=fixture(1); val old=page(1); val incoming=page(2); val slot=f.residency.ensure(old)!!
        f.backing.pixels[slot]="updated-old"; f.backing.durable[old]="durable-before"; f.slots.markDirty(old)
        f.backing.failWrite=true
        assertFailsWith<IllegalStateException> { f.residency.ensure(incoming) }
        assertEquals("updated-old",f.backing.pixels[slot]); assertEquals("durable-before",f.backing.durable[old])
        assertEquals(old,f.slots.residents().single().page); assertTrue(f.slots.resident(old)!!.dirty)
        assertNull(f.slots.resident(incoming))
        assertEquals(listOf("load:1","load:2","write:1"),f.backing.events)
        f.backing.events.clear()
        assertEquals("durable-before",f.backing.durable[old])
        f.residency.ensure(incoming)
        assertEquals(listOf("load:2","write:1"),f.backing.events)
        assertEquals("updated-old",f.backing.durable[old]); assertEquals(incoming,f.slots.residents().single().page)
    }

    @Test fun cleanVictimLoadsWithoutWriteBackAndPublishesOnce() {
        val f=fixture(1); val old=page(1); val next=page(2); val slot=f.residency.ensure(old)!!
        f.backing.pixels[slot]="clean"; f.backing.events.clear()
        f.residency.ensure(next)
        assertEquals(listOf("load:2"),f.backing.events)
        assertEquals(next,f.slots.residents().single().page)
    }

    @Test fun callbackFailuresRollbackAdmissionAndRetryPublishesOnce() {
        for (failurePoint in listOf("load", "evicted", "write")) {
            val f = fixture(1); val old = page(1); val incoming = page(2)
            val oldSlot = f.residency.ensure(old)!!
            f.backing.pixels[oldSlot] = "old-live"
            f.slots.markDirty(old)
            when (failurePoint) {
                "load" -> f.backing.failLoad = true
                "evicted" -> f.backing.failEvicted = true
                "write" -> f.backing.failWrite = true
            }

            assertFailsWith<IllegalStateException> { f.residency.ensure(incoming) }
            assertEquals(old, f.slots.residents().single().page, failurePoint)
            assertTrue(f.slots.resident(old)!!.dirty, failurePoint)
            assertNull(f.slots.resident(incoming), failurePoint)
            assertEquals("begin:2:1", f.backing.admissions[2], failurePoint)
            assertEquals("rollback:2:1", f.backing.admissions[3], failurePoint)

            f.backing.observeCommit = {
                assertEquals(incoming, f.slots.residents().single().page, failurePoint)
                assertNull(f.slots.resident(old), failurePoint)
            }
            f.residency.ensure(incoming)
            assertEquals(incoming, f.slots.residents().single().page, failurePoint)
            assertEquals("commit:2:1", f.backing.admissions.last(), failurePoint)
        }
    }
}
