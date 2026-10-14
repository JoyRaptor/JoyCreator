package cc.joycreator.joybrush.androidkit.gl.lines

import kotlin.test.*

/** Pure transfer contracts. Real inventory and engine/GPU evidence remain required. */
class NativeBaseTransferProofTest {
    private val plane: Pair<String, String?> = "paint" to null
    private class Fixture {
        val plane: Pair<String, String?> = "paint" to null
        val document = Any(); val paper = Any(); val resolved = Any(); val cursor = Any()
        val owner = Any(); val clone = Any(); val token = Any(); val plan = Any()
        val oldTiles = linkedMapOf(3L to 10)
        val newTiles = linkedMapOf<Long, Int>()
        val old = mapOf(plane to NativeBaseTransferProof.Physical(owner, token, plan, oldTiles, oldTiles))
        val next = mapOf(plane to NativeBaseTransferProof.Physical(clone, token, plan, newTiles, newTiles))
        val context = NativeBaseTransferProof.Context(document, paper, resolved, 2, 4)
        val inventory = listOf(NativeBaseTransferProof.Occurrence(10, NativeBaseTransferProof.Slot(plane, 3)))
        val root = Any()
        fun proof(occurrences: List<NativeBaseTransferProof.Occurrence> = inventory,
                  formats: Set<Int> = setOf(10), history: Set<Int> = emptySet()) =
            NativeBaseTransferProof.capture(context, cursor, old, next, occurrences, formats, history)
        fun prepared() = proof().also { it.prepareRoot(root) }
        fun accepts(p: NativeBaseTransferProof, physical: Map<Pair<String, String?>, NativeBaseTransferProof.Physical> = old,
                    occurrences: List<NativeBaseTransferProof.Occurrence> = inventory,
                    formats: Set<Int> = setOf(10), history: Set<Int> = emptySet(), now: NativeBaseTransferProof.Context = context,
                    currentCursor: Any = cursor) = p.canPublish(null, now, currentCursor, physical, occurrences, formats, history)
    }

    @Test fun capturesBorrowedActualNamesWithNewEmptyAliasWithoutChangingOldStore() {
        val f = Fixture(); val p = f.prepared()
        assertTrue(f.accepts(p)); assertEquals(mapOf(3L to 10), f.oldTiles); assertTrue(f.newTiles.isEmpty())
        assertTrue(p.contextCurrent(null, f.context, f.old))
        assertFalse(p.contextCurrent(null, f.context, f.next))
    }

    @Test fun oldAndNewAcceptanceSwitchExactlyOnceButSurvivesLaterRealRootPublication() {
        val f = Fixture(); val p = f.prepared()
        p.acknowledgeInstalled(f.root)
        assertTrue(p.contextCurrent(f.root, f.context, f.next))
        assertTrue(p.contextCurrent(Any(), f.context, f.next))
        assertFalse(p.contextCurrent(f.root, f.context, f.old))
        assertFalse(p.contextCurrent(null, f.context, f.next))
        assertFalse(f.accepts(p))
        assertFailsWith<IllegalStateException> { p.acknowledgeInstalled(f.root) }
    }

    @Test fun sameMapKeyReplacementAndPreparedEmptyMapMutationRefuse() {
        val f = Fixture(); val p = f.prepared()
        f.oldTiles[3] = 99
        assertFalse(f.accepts(p)); assertFalse(p.contextCurrent(null, f.context, f.old))
        f.oldTiles[3] = 10
        f.newTiles[4] = 11
        assertFalse(f.accepts(p))
    }

    @Test fun frameReplacementOwnerRecreationAndPlanReplacementRefuseAfterTransfer() {
        val f = Fixture(); val p = f.prepared(); p.acknowledgeInstalled(f.root)
        fun changed(token: Any = f.token, plan: Any = f.plan, tiles: Map<Long, Int> = f.newTiles) =
            mapOf(plane to NativeBaseTransferProof.Physical(Any(), token, plan, tiles, tiles))
        assertTrue(p.contextCurrent(f.root, f.context, changed())) // Legitimate detached Layer clone.
        assertFalse(p.contextCurrent(f.root, f.context, changed(tiles = emptyMap())))
        assertFalse(p.contextCurrent(f.root, f.context, changed(token = Any())))
        assertFalse(p.contextCurrent(f.root, f.context, changed(plan = Any())))
        assertFalse(p.contextCurrent(f.root, f.context, emptyMap()))
        f.newTiles[3] = 10
        assertFalse(p.contextCurrent(f.root, f.context, f.next))
    }

    @Test fun cursorPaperDocumentLogicalAndPhysicalEpochChangesRefuse() {
        val f = Fixture(); val p = f.prepared()
        assertFalse(f.accepts(p, currentCursor = Any()))
        val bad = listOf(
            NativeBaseTransferProof.Context(Any(), f.paper, f.resolved, 2, 4),
            NativeBaseTransferProof.Context(f.document, Any(), f.resolved, 2, 4),
            NativeBaseTransferProof.Context(f.document, f.paper, Any(), 2, 4),
            NativeBaseTransferProof.Context(f.document, f.paper, f.resolved, 3, 4),
            NativeBaseTransferProof.Context(f.document, f.paper, f.resolved, 2, 5))
        bad.forEach { assertFalse(f.accepts(p, now = it)) }
    }

    @Test fun poolOrGlobalAppearanceDuringFinalValidationUsesCurrentOccurrences() {
        val f = Fixture(); val p = f.prepared()
        assertFalse(f.accepts(p, occurrences = f.inventory + NativeBaseTransferProof.Occurrence(10, null)))
        assertTrue(f.accepts(p))
    }

    @Test fun heldHistoryAliasUnknownFormatMissingOwnerAndWrongPhysicalSlotRefuse() {
        val f = Fixture()
        assertFailsWith<IllegalArgumentException> { f.proof(history = setOf(10)) }
        assertFailsWith<IllegalArgumentException> { f.proof(formats = emptySet()) }
        assertFailsWith<IllegalArgumentException> { f.proof(occurrences = emptyList()) }
        assertFailsWith<IllegalArgumentException> { f.proof(occurrences = listOf(
            NativeBaseTransferProof.Occurrence(10, NativeBaseTransferProof.Slot("sibling" to null, 3)))) }
        val p = f.prepared()
        assertFalse(f.accepts(p, formats = emptySet()))
        assertFalse(f.accepts(p, history = setOf(10)))
    }

    @Test fun sharedAliasIsOnlyLegitimateDeduplicationAndCannotRepoint() {
        val f = Fixture()
        val wrong = mapOf(plane to NativeBaseTransferProof.Physical(f.clone, f.token, f.plan, f.newTiles,
            linkedMapOf<Long, Int>()))
        assertFailsWith<IllegalArgumentException> {
            NativeBaseTransferProof.capture(f.context, f.cursor, f.old, wrong, f.inventory, setOf(10), emptySet())
        }
        // Empty frame stores must be distinct despite being value-equal.
        val frame: Pair<String, String?> = "paint" to "frame1"
        val oldEmpty = linkedMapOf<Long, Int>(); val nextEmpty = linkedMapOf<Long, Int>()
        val old = f.old + (frame to NativeBaseTransferProof.Physical(f.owner, f.token, f.plan, oldEmpty))
        val next = f.next + (frame to NativeBaseTransferProof.Physical(f.clone, f.token, f.plan, nextEmpty))
        val p = NativeBaseTransferProof.capture(f.context, f.cursor, old, next, f.inventory, setOf(10), emptySet())
        p.prepareRoot(f.root); p.acknowledgeInstalled(f.root)
        assertTrue(p.contextCurrent(f.root, f.context, next))
        val replaced = next + (frame to NativeBaseTransferProof.Physical(f.clone, f.token, f.plan,
            linkedMapOf<Long, Int>()))
        assertFalse(p.contextCurrent(f.root, f.context, replaced))
    }

    @Test fun duplicateCanonicalNamesAndReusedEmptyPhysicalStoresRefuse() {
        val f = Fixture(); val frame: Pair<String, String?> = "paint" to "frame1"
        val old = f.old + (frame to NativeBaseTransferProof.Physical(f.owner, f.token, f.plan, mapOf(3L to 10)))
        val next = f.next + (frame to NativeBaseTransferProof.Physical(f.clone, f.token, f.plan, emptyMap()))
        assertFailsWith<IllegalArgumentException> {
            NativeBaseTransferProof.capture(f.context, f.cursor, old, next, f.inventory, setOf(10), emptySet())
        }
        val reusedNext = f.next + (frame to NativeBaseTransferProof.Physical(f.clone, f.token, f.plan, f.newTiles))
        val distinctOld = f.old + (frame to NativeBaseTransferProof.Physical(f.owner, f.token, f.plan, linkedMapOf()))
        assertFailsWith<IllegalArgumentException> {
            NativeBaseTransferProof.capture(f.context, f.cursor, distinctOld, reusedNext, f.inventory, setOf(10), emptySet())
        }
    }

    @Test fun sameOriginalStoreCannotBeEmptiedAndReusedAsPlannedStore() {
        val f = Fixture()
        f.oldTiles.clear()
        val reused = mapOf(plane to NativeBaseTransferProof.Physical(f.clone, f.token, f.plan, f.oldTiles, f.oldTiles))
        assertFailsWith<IllegalArgumentException> {
            NativeBaseTransferProof.capture(f.context, f.cursor, f.old, reused, emptyList(), emptySet(), emptySet())
        }
    }
}
