package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Exact empty-root and ownership contracts with actual facades, fake GL names/immutable root. */
class RecordedResetHandoffTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(8f))
    private fun record(key: Long = 0) = StrokeRecord("line", pen.id, 19, 0f, 1f,
        listOf(PenSample(key.toFloat(), 0f, 0.0)), seq = 1)

    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        var lines = 0
        var looks = 0
        var contexts = 0
        val released = mutableListOf<Int>()
        var afterLine: (() -> Unit)? = null
        var afterRelease: (() -> Unit)? = null
        override fun contextCurrent(): Boolean { contexts++; return true }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lines++
            val result = RecordedScene.Unit(preset.blend, mapOf(record.samples.first().x.toLong() to next++))
            afterLine?.invoke()
            return result
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            looks++
            return next++
        }
        override fun release(texture: Int) { released += texture; afterRelease?.invoke() }
    }
    private data class Root(
        override val recorded: RecordedScene.State<Int>,
        override val cursor: UndoLog.Cursor<Int>,
        override val graphicsEpoch: Long = 7,
        override val protectedTextures: Set<Int> = setOf(50),
        val marker: Int = 0,
    ) : RecordedScene.Publication<Int>

    private inner class Fixture(val driver: Driver, val history: UndoLog<Int>, val scene: RecordedScene<Int>,
                                var root: Root) {
        var reads = 0
        var rejectReads = false
        var onRead: (() -> Unit)? = null
        fun read(): Root {
            reads++
            check(!rejectReads) { "post-publication lookup forbidden" }
            val answered = root
            onRead?.invoke()
            return answered
        }
        fun next(): Root = Root(scene.emptyState(), history.emptyCursor(), root.graphicsEpoch + 1, emptySet())
        fun edit(): UndoLog.Step<Int> {
            val old = root.recorded.planes.getValue(plane).records.single()
            return UndoLog.Step(emptyList(), lines = listOf(UndoLog.LineChange("paint", null, old.id,
                CelComposer.Line(1, old), CelComposer.Line(1, record(2)))))
        }
        fun adopt(p: RecordedScene<Int>.JointStaged) {
            assertTrue(p.canPublish())
            val protected = root.protectedTextures.toMutableSet()
            for (c in p.historyProposal.step.changes) {
                c.before?.let { protected.remove(it) }
                c.after?.let { protected.add(it) }
            }
            root = root.copy(recorded = p.candidateState, cursor = p.historyProposal.cursor,
                protectedTextures = protected.toSet())
            p.adoptAfterPublication()
        }
    }

    private fun fixture(physical: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(plane to RecordedCel(
        "paint", null, listOf(record()), mapOf(9L to listOf(RecordedCel.PixelSlab(0, UndoLog.SlabValue(30, "normal"))))))): Fixture {
        val driver = Driver()
        val history = UndoLog<Int>(100_000, { 4 }, driver::release)
        val keys = physical.mapValues { (_, cel) -> cel.records.flatMap { it.samples.take(1).map { s -> s.x.toLong() } }.toSet() }
        var name = 10
        val scene = RecordedScene.createSaved(physical, mapOf(pen.id to pen), history, driver, keys,
            keys.mapValues { (_, set) -> set.associateWith { name++ } })
        val f = Fixture(driver, history, scene, Root(scene.snapshotState(), history.snapshotCursor()))
        scene.bindState(f::read)
        history.bindCursor { f.read().cursor }
        driver.contexts = 0
        return f
    }

    @Test fun exactEmptyRootAcknowledgementHandsOffBothFacadesWithoutLookupRenderOrRelease() {
        val f = fixture()
        val before = f.root
        val next = f.next()
        val p = f.scene.prepareResetHandoff(before, next)
        assertEquals(listOf(10), p.retiredCacheTextures)
        assertTrue(p.retiredHistoryTextures.isEmpty())
        assertTrue(p.abandonedJointCacheTextures.isEmpty())
        assertSame(before, f.root)
        assertTrue(p.isCurrent())
        val reads = f.reads
        f.root = next
        f.rejectReads = true
        p.acknowledgeAfterResetPublication(next)
        assertEquals(reads, f.reads)
        assertEquals(0, f.driver.lines); assertEquals(0, f.driver.looks); assertEquals(0, f.driver.contexts)
        assertTrue(f.driver.released.isEmpty())
        assertFalse(f.scene.isStateBound); assertFalse(f.history.isCursorBound)
        assertSame(next.recorded, f.scene.snapshotState())
        assertSame(next.cursor, f.history.snapshotCursor())
        assertTrue(f.scene.heldCacheTextures().isEmpty())
        f.scene.close(); f.history.clear()
        assertTrue(f.driver.released.isEmpty())
        p.releaseRetiredCachesAfterPublication { true } // Uses the scene renderer in the same physical context.
        assertEquals(listOf(10), f.driver.released)
        assertFalse(30 in f.driver.released) // Physical source remains engine-owned, outside both ledgers.
    }

    @Test fun foreignSceneOrHistoryResetValuesRefuseBeforeProviderCallbacks() {
        val f = fixture()
        val other = fixture()
        val reads = f.reads
        assertFailsWith<IllegalArgumentException> {
            f.scene.prepareResetHandoff(f.root, f.next().copy(recorded = other.scene.emptyState()))
        }
        assertFailsWith<IllegalArgumentException> {
            f.scene.prepareResetHandoff(f.root, f.next().copy(cursor = other.history.emptyCursor()))
        }
        assertEquals(reads, f.reads)
        assertTrue(f.driver.released.isEmpty())
        assertTrue(f.scene.isStateBound)
    }

    @Test fun apparentlyContentFreeBrushStateAndPersistentHandlesAreNotTrueEmptyValues() {
        val f = fixture(physical = emptyMap())
        val step = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, null, 51)))
        val lease = f.scene.stageJoint(step, frozen = mapOf(pen.id to pen, "new" to pen.copy(id = "new")))
        val metadataOnly = lease.candidateState
        lease.close()
        assertTrue(metadataOnly.planes.isEmpty())
        assertTrue(metadataOnly.cacheTextures().isEmpty())
        assertTrue(metadataOnly.brushes.isNotEmpty())
        val reads = f.reads
        assertFailsWith<IllegalArgumentException> { f.scene.prepareResetHandoff(f.root, f.next().copy(recorded = metadataOnly)) }
        assertFailsWith<IllegalArgumentException> { f.scene.prepareResetHandoff(f.root, f.next().copy(protectedTextures = setOf(50))) }
        assertEquals(reads, f.reads)
    }

    @Test fun staleEpochAndEquivalentRootArgumentsRefuseAndTicketsAreOneShot() {
        val f = fixture()
        val next = f.next()
        val reads = f.reads
        assertFailsWith<IllegalArgumentException> {
            f.scene.prepareResetHandoff(f.root, next.copy(graphicsEpoch = f.root.graphicsEpoch))
        }
        assertEquals(reads, f.reads)
        val p = f.scene.prepareResetHandoff(f.root, next)
        assertFailsWith<IllegalStateException> { p.acknowledgeAfterResetPublication(next.copy()) }
        assertTrue(f.scene.isStateBound)
        f.root = next
        p.acknowledgeAfterResetPublication(next)
        assertFailsWith<IllegalStateException> { p.acknowledgeAfterResetPublication(next) }
        assertFalse(p.isCurrent())
    }

    @Test fun sameContextResetRefusesOpenFreshLeaseBeforeRootReadThenSucceedsAfterCancellation() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        val fresh = lease.freshCacheTextures
        val reads = f.reads
        assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(f.root, f.next()) }
        assertEquals(reads, f.reads)
        assertFalse(f.scene.isMaterialized(plane))
        lease.close()
        assertEquals(fresh, f.driver.released.toSet())
        val p = f.scene.prepareResetHandoff(f.root, f.next())
        assertTrue(p.isCurrent())
        assertEquals(listOf(10), p.retiredCacheTextures)
    }

    @Test fun sameContextResetRefusesAdoptedPendingRetirementEvenAfterLeaseClose() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        f.adopt(lease)
        lease.close()
        val reads = f.reads
        assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(f.root, f.next()) }
        assertEquals(reads, f.reads)
        lease.releaseRetiredAfterPublication()
        val p = f.scene.prepareResetHandoff(f.root, f.next())
        assertTrue(p.isCurrent())
        assertEquals(f.scene.heldCacheTextures(), p.retiredCacheTextures.toSet())
        assertTrue(p.retiredCacheTextures.none { it in f.driver.released })
    }

    @Test fun externallyExecutedRetirementNeedsExplicitAcknowledgementToQuiesceRegistry() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        f.adopt(lease)
        lease.retiredCacheTextures.forEach(f.driver::release)
        assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(f.root, f.next()) }
        lease.acknowledgeRetiredExternallyAfterPublication()
        val releases = f.driver.released.toList()
        lease.releaseRetiredAfterPublication()
        assertEquals(releases, f.driver.released)
        assertTrue(f.scene.prepareResetHandoff(f.root, f.next()).isCurrent())
        assertFailsWith<IllegalStateException> { lease.acknowledgeRetiredExternallyAfterPublication() }
    }

    @Test fun actualContextLossInvalidatesOpenJointLeaseWithoutDeletingAnyOldName() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        val fresh = lease.freshCacheTextures
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next, RecordedScene.ResetMode.CONTEXT_LOST)
        assertEquals(fresh, ticket.abandonedJointCacheTextures)
        assertEquals(listOf(10), ticket.retiredCacheTextures)
        assertTrue(ticket.isCurrent())
        f.root = next
        f.rejectReads = true
        ticket.acknowledgeAfterResetPublication(next)
        assertFalse(lease.canPublish())
        lease.close(); lease.releaseRetiredAfterPublication()
        assertFailsWith<IllegalStateException> { lease.adoptAfterPublication() }
        assertTrue(f.driver.released.isEmpty())
        f.scene.checkStateBindingAllowed(); f.history.checkCursorBindingAllowed()
    }

    @Test fun actualContextLossAlsoAbandonsAdoptedPendingCacheRetirement() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        f.adopt(lease)
        val oldLive = f.scene.heldCacheTextures()
        val pending = lease.retiredCacheTextures.toSet()
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next, RecordedScene.ResetMode.CONTEXT_LOST)
        assertEquals(oldLive, ticket.retiredCacheTextures.toSet())
        assertEquals(pending, ticket.abandonedJointCacheTextures)
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        lease.releaseRetiredAfterPublication(); lease.close()
        assertTrue(f.driver.released.isEmpty())
        assertTrue(f.scene.heldCacheTextures().isEmpty())
    }

    @Test fun sameContextResetRefusesDuringJointWarmAndRenderBeforeAnyProviderRead() {
        val f = fixture()
        var attempts = 0
        f.driver.afterLine = {
            attempts++
            val reads = f.reads
            assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(f.root, f.next()) }
            assertEquals(reads, f.reads)
        }
        f.scene.stageJoint(f.edit()).use { }
        assertEquals(2, attempts)
        assertTrue(f.scene.prepareResetHandoff(f.root, f.next()).isCurrent())
    }

    @Test fun actualContextLossDuringFirstWarmCallbackHandsOffThenStopsAllFurtherWorkAndOldDeletes() {
        val f = fixture()
        var next: Root? = null
        f.driver.afterLine = {
            f.driver.afterLine = null
            val empty = f.next()
            val ticket = f.scene.prepareResetHandoff(f.root, empty, RecordedScene.ResetMode.CONTEXT_LOST)
            assertTrue(ticket.isCurrent())
            f.root = empty
            ticket.acknowledgeAfterResetPublication(empty)
            next = empty
        }
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.edit()) }
        assertEquals(1, f.driver.lines)
        assertEquals(0, f.driver.looks)
        assertTrue(f.driver.released.isEmpty())
        assertSame(assertNotNull(next).recorded, f.scene.snapshotState())
        assertFalse(f.history.canUndo)
        f.scene.checkStateBindingAllowed() // In-flight counter unwound rather than underflowing.
    }

    @Test fun changingJointLeaseOwnershipMakesAnAlreadyPreparedContextLossTicketStale() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        val ticket = f.scene.prepareResetHandoff(f.root, f.next(), RecordedScene.ResetMode.CONTEXT_LOST)
        assertTrue(ticket.isCurrent())
        lease.close()
        assertFalse(ticket.isCurrent())
        assertFailsWith<IllegalStateException> { ticket.acknowledgeAfterResetPublication(ticket.resetPublication) }
        assertTrue(f.scene.isStateBound)
        assertTrue(f.scene.prepareResetHandoff(f.root, f.next()).isCurrent())
    }

    @Test fun providerFailureOrRootSwitchUnwindsWithoutHandoffOrResourceRelease() {
        val f = fixture()
        val before = f.root
        f.onRead = { error("read failed") }
        assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(before, f.next()) }
        f.onRead = { f.onRead = null; f.root = f.root.copy(marker = 1) }
        assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(before, f.next()) }
        assertTrue(f.scene.isStateBound); assertTrue(f.history.isCursorBound)
        assertTrue(f.driver.released.isEmpty())
        assertTrue(f.scene.prepareResetHandoff(f.root, f.next()).isCurrent())
    }

    @Test fun finalTicketRootSwitchWithSameCursorIsStaleBeforePublication() {
        val f = fixture()
        val ticket = f.scene.prepareResetHandoff(f.root, f.next())
        f.root = f.root.copy(marker = 1)
        assertFalse(ticket.isCurrent())
        assertTrue(f.scene.isStateBound)
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun boundLegacyMutatorsStillRefuseAndUnboundOrdinaryHistoryCanResumeAfterReset() {
        val f = fixture()
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next)
        assertFailsWith<IllegalStateException> { f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.close() }
        assertFailsWith<IllegalStateException> { f.history.clear() }
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        f.history.push(UndoLog.Step(listOf(UndoLog.TileChange("ordinary", 0, 60, 61))))
        assertEquals(1, f.history.undoDepth)
        f.history.undo(); f.history.clear()
        assertEquals(listOf(61), f.driver.released)
        f.scene.forget(); f.scene.close()
        assertEquals(listOf(61), f.driver.released)
    }

    @Test fun liveResetIsRefusedWhileCacheReleaseCallbackIsInProgress() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        var attempts = 0
        f.driver.afterRelease = {
            attempts++
            assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(f.root, f.next()) }
        }
        lease.close()
        assertTrue(attempts > 0)
        f.driver.afterRelease = null
        assertTrue(f.scene.prepareResetHandoff(f.root, f.next()).isCurrent())
    }

    @Test fun historyOwnershipExportIsSeparateFromCurrentSceneCacheAndPhysicalSourceOwnership() {
        val f = fixture()
        val native = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        f.scene.stageJoint(native).use { p ->
            f.adopt(p)
            p.releaseRetiredAfterPublication()
        }
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next)
        assertEquals(listOf(50), ticket.retiredHistoryTextures)
        assertEquals(listOf(10), ticket.retiredCacheTextures)
        assertFalse(30 in ticket.retiredCacheTextures)
        assertFalse(30 in ticket.retiredHistoryTextures)
        assertFalse(51 in ticket.retiredHistoryTextures)
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun cacheExecutorRequiresAcknowledgementAndChecksPhysicalContextBeforeEveryOwnedRelease() {
        val f = fixture()
        val lease = f.scene.stageJoint(f.edit())
        f.adopt(lease)
        lease.releaseRetiredAfterPublication()
        f.driver.released.clear()
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next)
        var checks = 0
        var physicalContext = 1
        assertFailsWith<IllegalStateException> {
            ticket.releaseRetiredCachesAfterPublication { checks++; true }
        }
        assertEquals(0, checks)
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        f.driver.afterRelease = { physicalContext = 2 }
        ticket.releaseRetiredCachesAfterPublication { checks++; physicalContext == 1 }
        assertEquals(1, f.driver.released.size)
        assertEquals(2, checks)
        ticket.releaseRetiredCachesAfterPublication { error("idempotent executor queried context again") }
        assertEquals(1, f.driver.released.size)
    }

    @Test fun deadContextCacheExecutorNeverQueriesPhysicalContextOrReleasesAnOldName() {
        val f = fixture()
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next, RecordedScene.ResetMode.CONTEXT_LOST)
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        ticket.releaseRetiredCachesAfterPublication { error("dead context check must not run") }
        ticket.releaseRetiredCachesAfterPublication { error("second dead context check must not run") }
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun exportedResetCacheLedgerExternalAcknowledgementPreventsExecutorDoubleRelease() {
        val f = fixture()
        val next = f.next()
        val ticket = f.scene.prepareResetHandoff(f.root, next)
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        ticket.retiredCacheTextures.forEach(f.driver::release)
        ticket.acknowledgeRetiredCachesExternallyAfterPublication()
        ticket.releaseRetiredCachesAfterPublication { error("external executor already completed") }
        assertEquals(listOf(10), f.driver.released)
        assertFailsWith<IllegalStateException> { ticket.acknowledgeRetiredCachesExternallyAfterPublication() }
    }
}
