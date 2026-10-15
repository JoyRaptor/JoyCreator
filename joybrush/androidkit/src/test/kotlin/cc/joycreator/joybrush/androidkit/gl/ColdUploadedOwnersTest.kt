package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import kotlin.test.*

class ColdUploadedOwnersTest {
    @Test fun orphanFloatRegistryDoesNotProtectANameButRealFreePoolOwnershipDoes() {
        val engine=GlPaintEngine()
        @Suppress("UNCHECKED_CAST")
        val registry=GlPaintEngine::class.java.getDeclaredField("floatNames").apply { isAccessible=true }
            .get(engine) as MutableSet<Int>
        @Suppress("UNCHECKED_CAST")
        val pool=GlPaintEngine::class.java.getDeclaredField("freeFloatTex").apply { isAccessible=true }
            .get(engine) as ArrayDeque<Int>
        registry.addAll(listOf(17,18)); pool.addLast(18)
        @Suppress("UNCHECKED_CAST")
        val owners=GlPaintEngine::class.java.getDeclaredMethod("activeTextureOwners").apply { isAccessible=true }
            .invoke(engine) as Set<Int>
        assertFalse(17 in owners,"A format tag cannot retain an otherwise orphaned upload")
        assertTrue(18 in owners,"A real pooled allocation must not be deleted by rollback")
    }

    @Test fun freshUninitializedEngineRefusesBeforeGraphicsCalls() {
        assertFailsWith<IllegalStateException> { GlPaintEngine().inspectColdUploadedOwners() }
        assertFailsWith<IllegalStateException> { GlPaintEngine().coldUploadProbeContext() }
    }

    @Test fun inspectionDetachesCallerCollectionsWithoutClaimingTextureFormat() {
        val address = ColdUploadedOwners.Address(ColdRecordedLoadPlan.Kind.MASK_RGBA8,"paint",null,7)
        val entries = mutableListOf(ColdUploadedOwners.Owner(address,17))
        val forbidden = mutableSetOf(21)
        val floats = mutableSetOf(29)
        var active = true
        val snapshot = ColdUploadedOwners(entries,forbidden,floats,{ active },1)
        entries.clear(); forbidden.clear(); floats.clear()
        assertEquals(listOf(ColdUploadedOwners.Owner(address,17)),snapshot.entries)
        assertTrue(snapshot.isForbidden(21))
        assertTrue(snapshot.floatRegistered(29))
        assertFalse(snapshot.floatRegistered(17))
        assertFails { (snapshot.entries as MutableList<ColdUploadedOwners.Owner>).clear() }
        assertTrue(snapshot.current()); active=false; assertFalse(snapshot.current())
    }
}
