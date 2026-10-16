package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.SourcePlacement
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Actual bound coordinator/cursor with fake source payloads; no GPU/pooling parity claim. */
class RecordedSceneJointBakeTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val address = LineAddress("paint", null, "line")
    private val pen = BrushPreset(id = "frozen", name = "Frozen", size = Param(8f))
    private fun record(id: String = "line", seq: Long = 7, locked: Boolean = false) = StrokeRecord(
        id, pen.id, 29L, 0f, 1f, listOf(PenSample(0f, 0f, 0.0)), seq = seq, locked = locked,
        capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain()))

    private class Driver : RecordedScene.CapturedSourceRenderer<Int> {
        var next = 100
        val payload = linkedMapOf(30 to "base")
        val minted = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val copies = mutableListOf<LineAddress>()
        val inputs = mutableListOf<RecordedScene.Unit<Int>>()
        var lineCalls = 0
        var lookCalls = 0
        var contextCalls = 0
        var failCopyAt = 0
        var failLook = false
        var live = true
        var alias: ((Int, Int) -> Int?)? = null
        var wrongKeys = false
        var wrongOp = false
        var afterContext: (() -> Unit)? = null
        var afterCopy: (() -> Unit)? = null
        var transformCopy: ((RecordedScene.Unit<Int>) -> RecordedScene.Unit<Int>)? = null
        var afterLook: (() -> Unit)? = null
        private fun mint(value: String): Int {
            val texture = next++
            minted += texture; payload[texture] = value
            return texture
        }
        override fun contextCurrent(): Boolean {
            contextCalls++
            afterContext?.invoke()
            return live
        }
        override fun admitCapturedSource(address: LineAddress, record: StrokeRecord, preset: BrushPreset) = true
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lineCalls++
            return RecordedScene.Unit(preset.blend, if (record.samples.isEmpty()) emptyMap()
                else (0L..1L).associateWith { mint("${record.id}:$it") })
        }
        override fun copyForBake(address: LineAddress, unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
            copies += address; inputs += unit
            if (copies.size == failCopyAt) {
                // Unreturned partial allocations remain the renderer's cleanup responsibility.
                release(mint("unreturned partial"))
                error("copy failure")
            }
            var index = 0
            val tiles = unit.tiles.entries.associate { (key, texture) ->
                val output = alias?.invoke(copies.size, index++) ?: mint(payload.getValue(texture))
                (if (wrongKeys) key + 50 else key) to output
            }
            val result = RecordedScene.Unit(if (wrongOp) "erase" else unit.op, tiles)
            val returned = transformCopy?.invoke(result) ?: result
            afterCopy?.invoke()
            return returned
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            lookCalls++
            if (failLook) error("look failure")
            val output = mint(sources.joinToString("|") { "${it.seq}:${it.op}:${payload.getValue(it.texture)}" })
            afterLook?.invoke()
            return output
        }
        override fun release(texture: Int) { released += texture }
        fun clearCalls() {
            minted.clear(); released.clear(); copies.clear(); inputs.clear()
            lineCalls = 0; lookCalls = 0; contextCalls = 0
        }
    }

    private data class Root(
        override val recorded: RecordedScene.State<Int>,
        override val cursor: UndoLog.Cursor<Int>,
        override val graphicsEpoch: Long = 9,
        override val protectedTextures: Set<Int> = setOf(50, 53),
    ) : RecordedScene.Publication<Int>

    private class Fixture(val driver: Driver, val history: UndoLog<Int>, val scene: RecordedScene<Int>, var root: Root) {
        fun publish(p: RecordedScene<Int>.JointStaged) {
            assertSame(root, p.expectedPublication)
            assertTrue(p.canPublish())
            root = root.copy(recorded = p.candidateState, cursor = p.historyProposal.cursor)
            p.adoptAfterPublication()
            p.releaseRetiredAfterPublication()
            p.historyProposal.retirements.forEach(driver::release)
        }
        fun nextReset() = Root(scene.emptyState(), history.emptyCursor(), root.graphicsEpoch + 1, emptySet())
        fun loseContext() {
            val next = nextReset()
            val ticket = scene.prepareResetHandoff(root, next, RecordedScene.ResetMode.CONTEXT_LOST)
            assertTrue(ticket.isCurrent())
            root = next
            ticket.acknowledgeAfterResetPublication(next)
        }
        fun replaceUnits(units: Map<LineAddress, RecordedScene.Cached<Int>>) {
            val before = root.recorded
            val owner = checkNotNull(before.javaClass.getDeclaredField("owner").also { it.isAccessible = true }.get(before))
            root = root.copy(recorded = RecordedScene.State(owner, before.planes, before.brushes,
                units, before.plans, before.looks, before.savedKeys))
        }
    }

    private fun fixture(records: List<StrokeRecord> = listOf(record()), cold: Boolean = false,
                        sizeOf: (Int) -> Long = { 4 }): Fixture {
        val driver = Driver()
        val history = UndoLog<Int>(1_000_000, sizeOf, driver::release)
        // Existing public history remains the same prefix; 52 is detached history ownership.
        history.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, 52, 53))))
        val cel = RecordedCel("paint", null, records,
            mapOf(0L to listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(30, "normal")))))
        val scene = if (cold) RecordedScene.createSaved(mapOf(plane to cel), mapOf(pen.id to pen), history,
            driver, mapOf(plane to setOf(0L, 1L)), mapOf(plane to mapOf(0L to 60, 1L to 61)))
        else RecordedScene.create(mapOf(plane to cel), mapOf(pen.id to pen), history, driver)
        val f = Fixture(driver, history, scene, Root(scene.snapshotState(), history.snapshotCursor()))
        scene.bindState { f.root }
        history.bindCursor { f.root.cursor }
        driver.clearCalls()
        return f
    }

    private fun assertReleasedOnce(f: Fixture, names: Set<Int>) {
        assertEquals(names, f.driver.released.toSet())
        assertEquals(names.size, f.driver.released.size)
    }

    @Test fun cancellationOwnsBothAllocationKindsAndLeavesRootAndCursorUntouched() {
        val f = fixture()
        val before = f.root
        val p = f.scene.stageJointBake(before, setOf(address))
        assertSame(before, f.root)
        assertSame(before.cursor, f.history.snapshotCursor())
        assertEquals(2, p.freshSourceTextures.size)
        assertTrue(p.freshCacheTextures.isNotEmpty())
        assertTrue(p.freshSourceTextures.intersect(p.freshCacheTextures).isEmpty())
        assertEquals(f.driver.minted.toSet(), p.freshCacheTextures + p.freshSourceTextures)
        assertTrue(f.driver.released.isEmpty())
        val owned = p.freshCacheTextures + p.freshSourceTextures
        p.close(); p.close()
        assertReleasedOnce(f, owned)
        assertSame(before, f.root)
        assertSame(before.cursor, f.history.snapshotCursor())
        assertEquals(0, f.driver.lineCalls)
    }

    @Test fun publishedBakeCopiesCanonicalSourcesAtTheirSeqAndSharesActualPublicHistory() {
        val f = fixture(records = listOf(record(), record("later", 12)))
        val before = f.root
        val originalLooks = before.recorded.looks.getValue(plane).mapValues { f.driver.payload.getValue(it.value) }
        val addresses = linkedSetOf(address, LineAddress("paint", null, "later"))
        val p = f.scene.stageJointBake(before, addresses)
        val step = p.historyProposal.step
        val copies = p.freshSourceTextures
        assertEquals(listOf(7L, 12L), step.lines.map { assertNotNull(it.before).seq })
        assertTrue(step.lines.all { it.after == null && it.before!!.record.capturedPhysicalSource != null })
        assertEquals(listOf(7L, 7L, 12L, 12L), step.slabs.map { it.seq })
        assertTrue(step.slabs.all { it.before == null && it.after!!.op == "normal" })
        assertEquals(copies, step.slabs.map { it.after!!.texture }.toSet())
        assertTrue(f.driver.inputs.flatMap { it.tiles.values }.none { it in before.recorded.looks.getValue(plane).values })
        for (change in step.slabs) {
            val id = if (change.seq == 7L) "line" else "later"
            assertEquals("$id:${change.key}", f.driver.payload.getValue(change.after!!.texture))
        }
        f.publish(p); p.close()
        assertSame(step, f.history.peekUndo())
        assertEquals(2, f.history.undoDepth)
        assertSame(before.cursor.undoSteps.single(), f.root.cursor.undoSteps.first())
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertEquals(originalLooks, f.scene.lookKeys(plane).associateWith { f.driver.payload.getValue(f.scene.look(plane, it)!!) })
        assertTrue(copies.none { it in f.driver.released })
        assertEquals(before.recorded.cacheTextures(), f.driver.released.toSet())
        f.scene.stageJoint(step, RecordedScene.Direction.UNDO).use(f::publish)
        assertSame(step, f.history.peekRedo())
        assertTrue(CapturedStrokeRules.sameRecord(record(), f.scene.planes.getValue(plane).records.first()))
        assertTrue(copies.none { it in f.driver.released }) // Redo history owns copied slabs.
        f.scene.stageJoint(step, RecordedScene.Direction.REDO).use(f::publish)
        assertSame(step, f.history.peekUndo())
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertTrue(copies.none { it in f.driver.released })
    }

    @Test fun staleLockedMissingColdAndUncachedScopesRefuseBeforeAnyRendererCallback() {
        for (reason in listOf("stale-root", "locked", "missing", "cold", "uncached", "stale-cache", "late-locked")) {
            val f = fixture(records = if (reason == "late-locked") listOf(record(), record("locked", 12, true))
                else listOf(record(locked = reason == "locked")), cold = reason == "cold")
            val expected = f.root
            if (reason == "stale-root") f.root = f.root.copy(protectedTextures = setOf(70))
            if (reason == "uncached") f.replaceUnits(emptyMap())
            if (reason == "stale-cache") {
                val old = f.root.recorded.units.getValue(address)
                f.replaceUnits(mapOf(address to RecordedScene.Cached(old.record.copy(seed = 31L), old.unit)))
            }
            val current = f.root
            val scope = when (reason) {
                "missing" -> setOf(LineAddress("paint", "wrong-frame", "line"))
                "late-locked" -> linkedSetOf(address, LineAddress("paint", null, "locked"))
                else -> setOf(address)
            }
            assertFails { f.scene.stageJointBake(if (reason == "stale-root") expected else current, scope) }
            assertSame(current, f.root)
            assertEquals(0, f.driver.contextCalls, reason)
            assertTrue(f.driver.copies.isEmpty(), reason)
            assertEquals(0, f.driver.lineCalls, reason)
            assertEquals(0, f.driver.lookCalls, reason)
            assertTrue(f.driver.released.isEmpty(), reason)
        }
    }

    @Test fun borrowedAliasesAndMalformedCopiesReleaseFreshSiblingsOnly() {
        for (kind in listOf("native", "history", "slab", "cache", "duplicate", "keys", "operator")) {
            val f = fixture()
            val before = f.root
            val borrowed = when (kind) {
                "native" -> 50
                "history" -> 52
                "slab" -> 30
                else -> before.recorded.units.getValue(address).unit.tiles.values.first()
            }
            var duplicate: Int? = null
            when (kind) {
                "keys" -> f.driver.wrongKeys = true
                "operator" -> f.driver.wrongOp = true
                "duplicate" -> f.driver.alias = { _, index ->
                    if (index == 0) null else duplicate ?: f.driver.minted.first().also { duplicate = it }
                }
                else -> f.driver.alias = { _, index -> if (index == 0) borrowed else null }
            }
            assertFailsWith<IllegalArgumentException> { f.scene.stageJointBake(before, setOf(address)) }
            assertSame(before, f.root)
            assertReleasedOnce(f, f.driver.minted.toSet())
            assertTrue(borrowed !in f.driver.released, kind)
            assertEquals(0, f.driver.lookCalls)
        }
    }

    @Test fun lateCopyAndLookFailureCleanEarlierReturnedSourcesWithoutMaskingFailure() {
        for (failure in listOf("copy", "look")) {
            val f = fixture(records = listOf(record(), record("later", 12)))
            val before = f.root
            if (failure == "copy") f.driver.failCopyAt = 2 else f.driver.failLook = true
            val error = assertFailsWith<IllegalStateException> {
                f.scene.stageJointBake(before, linkedSetOf(address, LineAddress("paint", null, "later")))
            }
            assertEquals("$failure failure", error.message)
            assertSame(before, f.root)
            assertSame(before.cursor, f.history.snapshotCursor())
            assertReleasedOnce(f, f.driver.minted.toSet())
        }
    }

    @Test fun callbackForeignPendingAliasIsBorrowedAndStaleOwnershipRemainsPrimaryFailure() {
        val f = fixture()
        val before = f.root
        var foreign: RecordedScene<Int>.JointStaged? = null
        f.driver.transformCopy = { copied ->
            val old = before.recorded.planes.getValue(plane).records.single()
            val source = assertNotNull(old.capturedPhysicalSource)
            val moved = old.copy(capturedPhysicalSource = source.copy(placement = source.placement.then(SourcePlacement(1, 0))))
            val step = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange("paint", null, old.id,
                CelComposer.Line(old.seq, old), CelComposer.Line(old.seq, moved))))
            val other = f.scene.stageJoint(step)
            foreign = other
            // The fake renderer cleans the fresh name it replaces before returning its batch.
            f.driver.release(copied.tiles.getValue(0L))
            RecordedScene.Unit(copied.op, mapOf(0L to other.freshCacheTextures.first(), 1L to copied.tiles.getValue(1L)))
        }
        val failure = assertFailsWith<IllegalStateException> { f.scene.stageJointBake(before, setOf(address)) }
        assertTrue(failure.message.orEmpty().contains("pending ownership changed"))
        val other = assertNotNull(foreign)
        assertSame(before, f.root)
        assertSame(before.cursor, f.history.snapshotCursor())
        assertTrue(other.freshCacheTextures.none { it in f.driver.released })
        assertReleasedOnce(f, f.driver.minted.toSet() - other.freshCacheTextures)
        assertTrue(other.canPublish())
        other.close()
        assertReleasedOnce(f, f.driver.minted.toSet())
    }

    @Test fun externalSlabStepCannotStealAnUnpublishedBakeCopy() {
        val f = fixture()
        val before = f.root
        val baking = f.scene.stageJointBake(before, setOf(address))
        val source = baking.freshSourceTextures.first()
        val step = UndoLog.Step<Int>(emptyList(), slabs = listOf(UndoLog.SlabChange("paint", null, 9L,
            20L, null, UndoLog.SlabValue(source, "normal"))))
        val contexts = f.driver.contextCalls
        val looks = f.driver.lookCalls
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(step) }
        assertEquals(contexts, f.driver.contextCalls)
        assertEquals(looks, f.driver.lookCalls)
        assertSame(before, f.root)
        assertSame(before.cursor, f.history.snapshotCursor())
        assertTrue(f.driver.released.isEmpty())
        assertTrue(baking.canPublish())
        val owned = baking.freshSourceTextures + baking.freshCacheTextures
        baking.close(); baking.close()
        assertReleasedOnce(f, owned)
    }

    @Test fun contextAndRootReentryRejectBeforePublicationAndReclaimSameEpochResults() {
        for (boundary in listOf("context", "copy", "look")) {
            val f = fixture()
            val cursor = f.root.cursor
            val switch = { f.root = f.root.copy(protectedTextures = f.root.protectedTextures + 70) }
            when (boundary) {
                "context" -> f.driver.afterContext = switch
                "copy" -> f.driver.afterCopy = switch
                else -> f.driver.afterLook = switch
            }
            assertFailsWith<IllegalStateException> { f.scene.stageJointBake(f.root, setOf(address)) }
            assertSame(cursor, f.history.snapshotCursor())
            assertEquals(1, f.scene.planes.getValue(plane).records.size)
            assertReleasedOnce(f, f.driver.minted.toSet())
            if (boundary == "context") assertTrue(f.driver.copies.isEmpty())
        }
    }

    @Test fun contextLossInsideCopyAbandonsEveryOldReturnedName() {
        val f = fixture()
        f.driver.afterCopy = f::loseContext
        assertFailsWith<IllegalStateException> { f.scene.stageJointBake(f.root, setOf(address)) }
        assertTrue(f.scene.planes.isEmpty())
        assertFalse(f.history.canUndo)
        assertTrue(f.driver.minted.isNotEmpty())
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun historySizingReentryCannotPublishOrLeakAlreadyCopiedSources() {
        var duringSize: (() -> Unit)? = null
        val f = fixture(sizeOf = { duringSize?.invoke(); 4L })
        val cursor = f.root.cursor
        duringSize = { f.root = f.root.copy(protectedTextures = f.root.protectedTextures + 70) }
        assertFailsWith<IllegalStateException> { f.scene.stageJointBake(f.root, setOf(address)) }
        assertSame(cursor, f.history.snapshotCursor())
        assertEquals(0, f.driver.lookCalls)
        assertReleasedOnce(f, f.driver.minted.toSet())
    }

    @Test fun emptyAuthoritativeFootprintDeletesOnlyTheRecordAndKeepsBaseOwnership() {
        val f = fixture(records = listOf(record().copy(samples = emptyList())))
        val base = f.scene.planes.getValue(plane).slabs.getValue(0L).single().value.texture
        f.scene.stageJointBake(f.root, setOf(address)).use { p ->
            assertTrue(p.freshSourceTextures.isEmpty())
            assertTrue(p.historyProposal.step.slabs.isEmpty())
            assertEquals(1, p.historyProposal.step.lines.size)
            f.publish(p)
        }
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertEquals(base, f.scene.planes.getValue(plane).slabs.getValue(0L).single().value.texture)
        assertFalse(base in f.driver.released)
        assertEquals(0, f.driver.lineCalls)
    }

    @Test fun pendingBakeSourcesParticipateInResetAbandonmentAndCloseIsIdempotent() {
        val f = fixture()
        val p = f.scene.stageJointBake(f.root, setOf(address))
        val pending = p.freshCacheTextures + p.freshSourceTextures
        val next = f.nextReset()
        assertFailsWith<IllegalStateException> { f.scene.prepareResetHandoff(f.root, next) }
        val ticket = f.scene.prepareResetHandoff(f.root, next, RecordedScene.ResetMode.CONTEXT_LOST)
        assertEquals(pending, ticket.abandonedJointCacheTextures)
        assertTrue(ticket.isCurrent())
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        assertFalse(p.canPublish())
        p.close(); p.close(); p.releaseRetiredAfterPublication()
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun closingPendingBakeThenSameContextResetReleasesSourcesExactlyOnce() {
        val f = fixture()
        val p = f.scene.stageJointBake(f.root, setOf(address))
        val pending = p.freshCacheTextures + p.freshSourceTextures
        p.close()
        assertReleasedOnce(f, pending)
        val next = f.nextReset()
        val ticket = f.scene.prepareResetHandoff(f.root, next)
        assertTrue(ticket.abandonedJointCacheTextures.isEmpty())
        assertTrue(ticket.isCurrent())
        f.root = next
        ticket.acknowledgeAfterResetPublication(next)
        ticket.releaseRetiredCachesAfterPublication { true }
        ticket.releaseRetiredCachesAfterPublication { true }
        p.close()
        assertTrue(p.freshSourceTextures.all { source -> f.driver.released.count { it == source } == 1 })
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
    }
}
