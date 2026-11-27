package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.PaperResources
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.TuftShading
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.paper.LookEntry
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import kotlin.test.*

/** Real begin/ticket/lifecycle and seeded metadata only. No GL copy or appearance proof. */
class GlLiveRecordedSourceLeaseTest {
    private val rgbaBytes = Tiles.SIZE.toLong() * Tiles.SIZE * 4

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T
    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)
    private fun owner(e: GlPaintEngine): Any = get<LinkedHashMap<String, Any>>(e, "localLayers").getValue("paint")
    private fun tiles(owner: Any) = get<HashMap<Long, Int>>(owner, "tiles")
    private fun scratch(e: GlPaintEngine) = get<HashMap<Long, Int>>(e, "strokeTiles")
    private fun ledger(e: GlPaintEngine): RecordedWorkingTileLedger = get(e, "recordedWorkingTiles")
    private fun epoch(e: GlPaintEngine): Long = get(e, "physicalContextEpoch")
    private fun pool(e: GlPaintEngine) = get<ArrayDeque<Int>>(e, "freeLayerTex")
    private fun paper(color: Int = -1) = ResolvedPaper(null, null, color, 1f, 0f, 0f, false)
    private fun engine() = GlPaintEngine().also {
        it.initWith {}
        it.addLayer("paint")
        put(it, "resolvedPaper", paper())
    }
    private fun begin(e: GlPaintEngine, blend: StrokeBlend = StrokeBlend.NORMAL) = e.beginStroke(
        "paint", 0xff123456.toInt(), 0.65f, Accumulate.WASH, blend, TipShape(),
    )
    private fun assertNoPublication(e: GlPaintEngine) {
        assertFalse(e.hasJointPaintPublication)
        assertNull(get<Any?>(e, "localRecordedScene"))
        assertEquals(0L, e.recordedLinePreparationCalls)
        assertEquals(0L, e.recordedCompositionCalls)
    }

    @Test fun admissionRequiresARealBeginBeforeAnyScratchAndOnlyOncePerBegin() {
        val e = engine()
        assertFailsWith<IllegalStateException> { e.admitLiveRecordedSource("paint") }
        begin(e)
        assertFailsWith<IllegalArgumentException> { e.admitLiveRecordedSource("paint", -1) }
        assertFailsWith<IllegalArgumentException> { e.admitLiveRecordedSource("paint", Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { e.admitLiveRecordedSource("other") }
        scratch(e)[0L] = 71
        assertFailsWith<IllegalStateException> { e.admitLiveRecordedSource("paint") }
        scratch(e).clear() // Seeded metadata, never allocated by GL.
        val ticket = e.admitLiveRecordedSource("paint", 0)
        assertFailsWith<IllegalStateException> { e.admitLiveRecordedSource("paint", 0) }
        ticket.close()
        assertFailsWith<IllegalStateException> { e.admitLiveRecordedSource("paint", 0) }
        assertNoPublication(e)
    }

    @Test fun emptyFinishIsSingleUseAndPreservesActualOwnerCursorAndLiveScratch() {
        val e = engine(); begin(e)
        val originalOwner = owner(e)
        val originalTiles = tiles(originalOwner)
        val cursor = e.undo.snapshotCursor()
        val identity = get<Any>(e, "liveStrokeIdentity")
        val ticket = e.admitLiveRecordedSource("paint", 0)
        assertNull(ticket.capturedDomains)
        assertNull(ticket.sharedDocumentCelId)
        assertEquals(setOf<String?>(null), ticket.domains.keys)
        val source = ticket.domains.getValue(null)
        assertNull(source.domain.window)
        assertTrue(source.domain.excluded.isEmpty())
        assertTrue(source.domain.contains(Int.MIN_VALUE, Int.MAX_VALUE))
        assertFails { (ticket.domains as MutableMap<String?, CapturedPhysicalSource>).clear() }
        val lease = ticket.finish()
        assertSame(ticket.domains, lease.domains)
        assertEquals("normal", lease.unit.op)
        assertTrue(lease.unit.tiles.isEmpty())
        assertEquals(0L, lease.allocatedBytes)
        assertEquals(epoch(e), lease.unit.physicalEpoch)
        assertSame(originalOwner, owner(e)); assertSame(originalTiles, tiles(owner(e)))
        assertSame(cursor, e.undo.snapshotCursor()); assertSame(identity, get(e, "liveStrokeIdentity"))
        assertTrue(e.strokeInProgress)
        assertTrue(scratch(e).isEmpty()); assertTrue(pool(e).isEmpty())
        assertFailsWith<IllegalStateException> { ticket.finish() }
        ticket.close(); lease.close(); lease.close()
        assertTrue(e.strokeInProgress)
        assertNoPublication(e)
        e.cancelStroke()
        assertFalse(e.strokeInProgress)
    }

    @Test fun closingTheTicketDoesNotTouchItsLiveSourceOrHistory() {
        val e = engine(); begin(e)
        val cursor = e.undo.snapshotCursor()
        val ticket = e.admitLiveRecordedSource("paint")
        scratch(e)[5L] = 73
        ticket.close(); ticket.close()
        assertEquals(mapOf(5L to 73), scratch(e))
        assertTrue(pool(e).isEmpty()); assertSame(cursor, e.undo.snapshotCursor())
        assertFailsWith<IllegalStateException> { ticket.finish() }
        assertNoPublication(e)
    }

    @Test fun previewProjectionCacheChangesDoNotInvalidateStablePhysicalSources() {
        val e = engine(); begin(e)
        val physical = owner(e)
        val store = tiles(physical)
        val cursor = e.undo.snapshotCursor()
        val ticket = e.admitLiveRecordedSource("paint")
        get<HashMap<Long, Int>>(physical, "projected")[7L] = 106
        val visible = mapOf(8L to 107)
        put(physical, "visibleProjection", visible)
        ticket.finish().use { lease ->
            assertTrue(lease.unit.tiles.isEmpty())
            assertSame(store, tiles(physical))
            assertSame(cursor, e.undo.snapshotCursor())
            assertEquals(mapOf(7L to 106), get<HashMap<Long, Int>>(physical, "projected"))
            assertSame(visible, get(physical, "visibleProjection"))
        }
        assertNoPublication(e)
    }

    @Test fun cancelAndAnEqualNewBeginCannotReviveAnOldTicket() {
        val e = engine(); begin(e)
        val first = e.admitLiveRecordedSource("paint", 0)
        val identity = get<Any>(e, "liveStrokeIdentity")
        e.cancelStroke()
        assertFailsWith<IllegalStateException> { first.finish() }
        begin(e)
        assertNotSame(identity, get(e, "liveStrokeIdentity"))
        e.admitLiveRecordedSource("paint", 0).finish().close()

        e.cancelStroke(); begin(e)
        val replaced = e.admitLiveRecordedSource("paint", 0)
        begin(e) // The public begin itself cancels/replaces the preceding empty contact.
        assertFailsWith<IllegalStateException> { replaced.finish() }
        e.admitLiveRecordedSource("paint", 0).finish().close()
    }

    @Test fun changedCursorPaperResourcesOwnerOrRuntimeRefusesBeforeCopy() {
        val changes: List<(GlPaintEngine) -> Unit> = listOf(
            { e -> e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 0L, null, 81)))) },
            { e -> put(e, "localDocumentPaper", Paper(color = "#102030")) },
            { e -> put(e, "resolvedPaper", paper(0xff102030.toInt())) },
            { e -> put(e, "loadedPaper", PaperResources.Loaded(paper(), null, null, emptyList())) },
            { e -> put(e, "surfaceTexture", 82) },
            { e -> put(e, "localGraphicsEpoch", get<Long>(e, "localGraphicsEpoch") + 1) },
            { e -> tiles(owner(e))[2L] = 83 },
            { e -> put(owner(e), "name", "changed") },
            { e -> put(e, "opacity", 0.2f) },
            { e -> put(e, "wcPrepared", true) },
        )
        changes.forEach { change ->
            val e = engine(); begin(e)
            val ticket = e.admitLiveRecordedSource("paint")
            scratch(e)[9L] = 84
            change(e)
            assertFailsWith<IllegalStateException> { ticket.finish() }
            assertEquals(mapOf(9L to 84), scratch(e))
            assertTrue(pool(e).isEmpty()); assertTrue(ledger(e).snapshot(epoch(e)).isEmpty())
            assertFailsWith<IllegalStateException> { ticket.finish() }
            assertNoPublication(e)
        }
    }

    @Test fun actualRegionPlanProducesImmutableDomainsAndMutableFramesInvalidateTheTicket() {
        val e = engine()
        val ids = ArrayDeque(listOf("board", "paint", "shared"))
        var doc = DocOps.newDocument("doc", "Art", 512, 512) { ids.removeFirst() }
        var serial = 0
        doc = RegionDocumentOps.create(doc, "A", RectPx(3, 5, 100, 80)) { "r-${++serial}" }.doc
        doc = RegionDocumentOps.create(doc, "B", RectPx(200, 220, 90, 70)) { "r-${++serial}" }.doc
        e.setBoardDocument(doc)
        begin(e)
        val plan = get<RegionPaintPlan>(e, "strokePlan")
        val ticket = e.admitLiveRecordedSource("paint", 0)
        val captured = assertNotNull(ticket.capturedDomains)
        assertEquals(plan.shared.celId, ticket.sharedDocumentCelId)
        assertEquals(plan.frames.map { it.celId }.toSet() + setOf<String?>(null), ticket.domains.keys)
        for (frame in plan.frames) {
            assertEquals(CaptureRect(frame.rect.x, frame.rect.y, frame.rect.w, frame.rect.h),
                ticket.domains.getValue(frame.celId).domain.window)
            assertFalse(ticket.domains.getValue(null).domain.contains(frame.rect.x, frame.rect.y))
        }
        val snapshot = ticket.domains.mapValues { it.value.domain }
        @Suppress("UNCHECKED_CAST")
        val mutable = plan.frames as MutableList<RegionFrame>
        mutable[0] = mutable[0].copy(rect = RectPx(-500, -500, 10, 10))
        assertEquals(snapshot, ticket.domains.mapValues { it.value.domain })
        assertEquals(captured.planes.size, ticket.domains.size)
        assertFailsWith<IllegalStateException> { ticket.finish() }
        assertNoPublication(e)
    }

    @Test fun nullPlanRetainsARealSharedIdentityWithoutInventingOne() {
        val e = engine()
        val actual = owner(e)
        put(actual, "sharedCel", "actual-shared")
        get<MutableMap<String, HashMap<Long, Int>>>(actual, "cels")["actual-shared"] = tiles(actual)
        begin(e)
        val ticket = e.admitLiveRecordedSource("paint", 0)
        assertNull(ticket.capturedDomains)
        assertEquals("actual-shared", ticket.sharedDocumentCelId)
        assertEquals(setOf<String?>(null), ticket.domains.keys)
        ticket.finish().close()
    }

    @Test fun unsupportedRuntimeAndMissingPaperRefuseWithoutTakingAnAdmission() {
        val changes: List<(GlPaintEngine) -> Unit> = listOf(
            { e -> put(e, "blend", StrokeBlend.ERASE) },
            { e -> put(e, "opacity", Float.NaN) },
            { e -> put(owner(e), "clip", true) },
            { e -> put(e, "tileBoardId", "periodic") },
            { e -> put(e, "framePreviews", mapOf("board" to "frame")) },
            { e -> put(e, "tuft", TuftShading(1f, 1f, 1f, 1f, 1f, "", 1f)) },
            { e -> put(e, "resolvedPaper", null) },
            { e -> put(e, "resolvedPaper", paper().copy(look = LookEntry("image", "Image", "#FFFFFF", "missing.png"))) },
            { e -> put(e, "loadedPaper", PaperResources.Loaded(paper(), null, null, listOf("missing paper"))) },
            { e -> get<HashMap<String, HashMap<Long, Int>>>(owner(e), "floats")["state"] = hashMapOf(0L to 91) },
        )
        changes.forEach { change ->
            val e = engine(); begin(e); change(e)
            assertFails { e.admitLiveRecordedSource("paint") }
            assertFalse(get(e, "liveSourceAdmissionTaken"))
            assertTrue(scratch(e).isEmpty()); assertTrue(pool(e).isEmpty())
            assertNoPublication(e)
        }
        val withScene = engine()
        withScene.createSavedRecordedScene(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        begin(withScene)
        assertFailsWith<IllegalStateException> { withScene.admitLiveRecordedSource("paint") }
    }

    @Test fun zeroBudgetChargesActualNativeOwnersEvenForAnEmptyContact() {
        val e = engine()
        tiles(owner(e))[3L] = 92
        begin(e)
        val cursor = e.undo.snapshotCursor()
        val ticket = e.admitLiveRecordedSource("paint", 0)
        assertFailsWith<IllegalArgumentException> { ticket.finish() }
        assertEquals(mapOf(3L to 92), tiles(owner(e)))
        assertSame(cursor, e.undo.snapshotCursor()); assertTrue(e.strokeInProgress)
        assertTrue(pool(e).isEmpty()); assertTrue(scratch(e).isEmpty())
        assertNoPublication(e)
    }

    @Test fun budgetIncludesCurrentScratchPreviousStagedRawAndFreshRgbaOutputsBeforeGl() {
        val e = engine(); begin(e)
        ledger(e).register(epoch(e), listOf(101), rgbaBytes)
        // Its actual coverage format is R8 in this no-GL context.
        val scratchBytes = Tiles.SIZE.toLong() * Tiles.SIZE
        val ticket = e.admitLiveRecordedSource("paint", rgbaBytes + scratchBytes)
        scratch(e)[7L] = 102
        val cursor = e.undo.snapshotCursor()
        val refusal = assertFailsWith<IllegalArgumentException> { ticket.finish() }
        assertTrue(refusal.message.orEmpty().contains("exceeds budget"))
        assertEquals(mapOf(7L to 102), scratch(e))
        assertEquals(mapOf(101 to rgbaBytes), ledger(e).snapshot(epoch(e)))
        assertSame(cursor, e.undo.snapshotCursor()); assertTrue(pool(e).isEmpty())
        assertNoPublication(e)
    }

    @Test fun contextReplacementAbandonsOldTicketAndLeaseNamesEvenWhenNamesAreReused() {
        val e = engine(); begin(e)
        val oldTicket = e.admitLiveRecordedSource("paint", 0)
        e.initWith {}
        e.addLayer("paint"); put(e, "resolvedPaper", paper()); begin(e)
        assertFailsWith<IllegalStateException> { oldTicket.finish() }
        val lease = e.admitLiveRecordedSource("paint", 0).finish()
        // Seed the actual close callback's unit: this tests metadata retirement, not GPU output.
        put(lease.unit, "tiles", mapOf(4L to 103))
        ledger(e).register(epoch(e), listOf(103), rgbaBytes)
        e.initWith {}
        ledger(e).register(epoch(e), listOf(103), rgbaBytes)
        lease.close(); lease.close()
        assertTrue(pool(e).isEmpty())
        assertEquals(mapOf(103 to rgbaBytes), ledger(e).snapshot(epoch(e)))
    }

    @Test fun currentLeaseCloseRetiresOnlyItsSeededOutputsExactlyOnce() {
        val e = engine(); begin(e)
        val lease = e.admitLiveRecordedSource("paint", 0).finish()
        put(lease.unit, "tiles", mapOf(4L to 104))
        ledger(e).register(epoch(e), listOf(104), rgbaBytes)
        scratch(e)[8L] = 105
        val cursor = e.undo.snapshotCursor()
        lease.close(); lease.close()
        assertEquals(listOf(104), pool(e).toList())
        assertTrue(ledger(e).snapshot(epoch(e)).isEmpty())
        assertEquals(mapOf(8L to 105), scratch(e))
        assertTrue(e.strokeInProgress); assertSame(cursor, e.undo.snapshotCursor())
        assertNoPublication(e)
    }
}
