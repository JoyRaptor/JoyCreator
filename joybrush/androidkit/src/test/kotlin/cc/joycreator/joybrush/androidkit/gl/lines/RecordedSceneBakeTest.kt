package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Contract simulations only: canonical RGBA source bytes, NOT GPU/WC/physical-clip parity. */
class RecordedSceneBakeTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(8f), opacity = Param(.5f))
    private val brushes = listOf(pen, pen.copy(id = "erase", blend = "erase"),
        pen.copy(id = "behind", blend = "behind")).associateBy { it.id }
    private val address = LineAddress("paint", null, "line")
    private fun record(id: String = "line", seq: Long = 1, x: Float = 0f,
                       brushId: String = "pen", locked: Boolean = false) =
        StrokeRecord(id, brushId, 19, 0f, 1f, listOf(PenSample(x, 0f, 0.0)),
            seq = seq, locked = locked, colorArgb = 0xff4060c0.toInt())
    private fun cel(records: List<StrokeRecord> = listOf(record()), layer: String = "paint",
                    frame: String? = null) = RecordedCel<Int>(layer, frame, records)

    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        val pixels = linkedMapOf<Int, ByteArray>()
        val copied = mutableListOf<LineAddress>()
        val copyInputs = mutableListOf<RecordedScene.Unit<Int>>()
        val outputs = mutableListOf<RecordedScene.Unit<Int>>()
        val released = mutableListOf<Int>()
        val lineCalls = mutableListOf<LineAddress>()
        val lookCalls = mutableListOf<Pair<Pair<String, String?>, Long>>()
        val lookSources = mutableListOf<List<RecordedScene.Source<Int>>>()
        var failCopyAt: Int? = null
        var failLookAt: Int? = null
        var mutateCopy: ((Int, RecordedScene.Unit<Int>) -> RecordedScene.Unit<Int>)? = null
        var lookAlias: Int? = null
        var live = true
        var wide = false
        override fun contextCurrent(): Boolean = live
        private fun fresh(bytes: ByteArray): Int {
            val texture = next++
            pixels[texture] = bytes.copyOf()
            return texture
        }
        override fun line(address: LineAddress, record: StrokeRecord,
                          preset: BrushPreset): RecordedScene.Unit<Int> {
            lineCalls += address
            val alpha = (255 * preset.opacity.base + .5f).toInt()
            val bytes = byteArrayOf((64 * alpha / 255).toByte(), (96 * alpha / 255).toByte(),
                (192 * alpha / 255).toByte(), alpha.toByte())
            val key = record.samples.firstOrNull()?.x?.toLong()
            val tiles = if (key == null) emptyMap() else linkedMapOf(key to fresh(bytes)).also {
                if (wide) it[key + 1] = fresh(bytes)
            }
            return RecordedScene.Unit(preset.blend, tiles)
        }
        override fun copyForBake(address: LineAddress, unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
            copied += address
            copyInputs += unit
            if (copied.size == failCopyAt) {
                // Renderer owns and cleans work which it never returned to the scene.
                val partial = fresh(byteArrayOf(0, 0, 0, 0))
                release(partial)
                error("injected copy failure")
            }
            val result = RecordedScene.Unit(unit.op,
                unit.tiles.mapValues { (_, texture) -> fresh(pixels.getValue(texture)) })
            val output = mutateCopy?.invoke(copied.size, result) ?: result
            outputs += output
            return output
        }
        override fun look(plane: Pair<String, String?>, key: Long,
                          sources: List<RecordedScene.Source<Int>>): Int? {
            lookCalls += plane to key
            lookSources += sources.toList()
            if (lookCalls.size == failLookAt) error("injected composition failure")
            lookAlias?.let { return it }
            val dst = FloatArray(4)
            for (source in sources) {
                val src = pixels.getValue(source.texture).map { (it.toInt() and 255) / 255f }
                when (source.op) {
                    "erase" -> for (c in 0..3) dst[c] *= 1 - src[3]
                    "behind" -> {
                        val remaining = 1 - dst[3]
                        for (c in 0..3) dst[c] += src[c] * remaining
                    }
                    "normal" -> for (c in 0..3) dst[c] = src[c] + dst[c] * (1 - src[3])
                    else -> error("Operator outside this contract fixture")
                }
            }
            // Preserve explicit fully-transparent derived caches just like the GL contract.
            return fresh(ByteArray(4) { (dst[it] * 255 + .5f).toInt().toByte() })
        }
        override fun release(texture: Int) { released += texture }
        fun clearCalls() {
            copied.clear(); copyInputs.clear(); outputs.clear(); lineCalls.clear()
            lookCalls.clear(); lookSources.clear()
        }
    }
    private class Fixture(val renderer: Driver, val history: UndoLog<Int>,
                          val scene: RecordedScene<Int>)
    private fun fixture(planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(plane to cel()),
                        driver: Driver = Driver(), sizeOf: (Int) -> Long = { 4 }): Fixture {
        val history = UndoLog<Int>(1_000_000, sizeOf, driver.released::add)
        val scene = RecordedScene.create(planes, brushes, history, driver)
        driver.clearCalls()
        return Fixture(driver, history, scene)
    }
    private fun assertOnce(f: Fixture, textures: Set<Int>) {
        for (texture in textures) assertEquals(1, f.renderer.released.count { it == texture }, "texture $texture")
    }
    private fun slabTextures(f: Fixture) = f.scene.planes.values.flatMap { cel ->
        cel.slabs.values.flatten().map { it.value.texture }
    }.toSet()

    @Test fun scopeUsesFullAddressWithSameIdOnOtherPhysicalPlanes() {
        val frame: Pair<String, String?> = "paint" to "frame"
        val other: Pair<String, String?> = "other" to null
        val f = fixture(mapOf(plane to cel(), frame to cel(frame = "frame"), other to cel(layer = "other")))
        val frameCel = f.scene.planes.getValue(frame)
        val otherCel = f.scene.planes.getValue(other)
        val frameLook = f.scene.look(frame, 0)
        val otherLook = f.scene.look(other, 0)
        f.scene.stageBake(setOf(address)).use {
            assertEquals(mapOf(plane to setOf(0L)), it.affected)
            assertTrue(it.commit())
        }
        assertEquals(listOf(address), f.renderer.copied)
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertSame(frameCel, f.scene.planes.getValue(frame))
        assertSame(otherCel, f.scene.planes.getValue(other))
        assertEquals(frameLook, f.scene.look(frame, 0))
        assertEquals(otherLook, f.scene.look(other, 0))
        assertTrue(f.renderer.lineCalls.isEmpty())
        assertEquals(listOf(plane to 0L), f.renderer.lookCalls)
    }

    @Test fun bakePreservesOriginalSequenceAndCanonicalOperatorInOneSharedUndo() {
        val driver = Driver().also { it.wide = true }
        val f = fixture(mapOf(plane to cel(listOf(record(seq = 7, brushId = "erase"),
            record("later", 12, brushId = "behind")))), driver)
        val oldCache = f.scene.heldCacheTextures()
        f.scene.stageBake(linkedSetOf(address, LineAddress("paint", null, "later"))).use { assertTrue(it.commit()) }
        val step = assertNotNull(f.history.peekUndo())
        assertEquals(1, f.history.undoDepth)
        assertEquals(listOf(7L, 12L), step.lines.map { assertNotNull(it.before).seq })
        assertTrue(step.lines.all { it.after == null })
        assertEquals(listOf(7L, 7L, 12L, 12L), step.slabs.map { it.seq })
        assertEquals(listOf("erase", "erase", "behind", "behind"), step.slabs.map { assertNotNull(it.after).op })
        assertEquals(setOf(0L, 1L), f.scene.planes.getValue(plane).slabs.keys)
        assertTrue(slabTextures(f).intersect(oldCache).isEmpty())
        assertTrue(slabTextures(f).intersect(f.scene.heldCacheTextures()).isEmpty())
        assertTrue(step.changes.isEmpty())
    }

    @Test fun simulatedCanonicalSourceSlabAndLookBytesMatchWithOpacityIncludedOnce() {
        for (op in listOf("pen", "erase", "behind")) {
            val base = RecordedCel.PixelSlab(0L, UndoLog.SlabValue(7, "normal"))
            val driver = Driver().also { it.pixels[7] = byteArrayOf(30, 40, 50, 127) }
            val f = fixture(mapOf(plane to RecordedCel("paint", null, listOf(record(brushId = op)),
                mapOf(0L to listOf(base)))), driver)
            val beforeLook = driver.pixels.getValue(assertNotNull(f.scene.look(plane, 0))).copyOf()
            f.scene.stageBake(setOf(address)).use { assertTrue(it.commit()) }
            val input = driver.copyInputs.single()
            val slab = f.scene.planes.getValue(plane).slabs.getValue(0).last()
            assertContentEquals(driver.pixels.getValue(input.tiles.getValue(0)), driver.pixels.getValue(slab.value.texture))
            assertEquals(128, driver.pixels.getValue(slab.value.texture)[3].toInt() and 255)
            assertContentEquals(beforeLook, driver.pixels.getValue(assertNotNull(f.scene.look(plane, 0))))
            assertEquals(input.op, slab.value.op)
        }
    }

    @Test fun lateCopyFailureCleansEarlierSourcesAndRendererPartialOutputOnly() {
        val f = fixture(mapOf(plane to cel(listOf(record(), record("later", 2, 2f)))))
        val before = f.scene.planes
        val held = f.scene.heldCacheTextures()
        f.renderer.failCopyAt = 2
        val firstFresh = f.renderer.next
        assertFailsWith<IllegalStateException> {
            f.scene.stageBake(linkedSetOf(address, LineAddress("paint", null, "later")))
        }
        assertSame(before, f.scene.planes)
        assertFalse(f.history.canUndo)
        assertTrue(f.renderer.lookCalls.isEmpty())
        assertEquals(setOf(firstFresh, firstFresh + 1), f.renderer.released.toSet())
        assertOnce(f, f.renderer.released.toSet())
        assertTrue(held.intersect(f.renderer.released.toSet()).isEmpty())
    }

    @Test fun lateCompositionFailureReclaimsSourcesAndEarlierLookWithoutDoubleRelease() {
        val f = fixture(mapOf(plane to cel(listOf(record(), record("later", 2, 2f)))))
        val before = f.scene.planes
        val held = f.scene.heldCacheTextures()
        f.renderer.failLookAt = 2
        val firstFresh = f.renderer.next
        assertFailsWith<IllegalStateException> {
            f.scene.stageBake(linkedSetOf(address, LineAddress("paint", null, "later")))
        }
        assertSame(before, f.scene.planes)
        assertFalse(f.history.canUndo)
        assertEquals((firstFresh..firstFresh + 2).toSet(), f.renderer.released.toSet())
        assertOnce(f, f.renderer.released.toSet())
        assertTrue(held.intersect(f.renderer.released.toSet()).isEmpty())
    }

    @Test fun wholeScopeLockMissingLineMissingCelAndEmptyRefuseBeforeAnyCopy() {
        val locked = LineAddress("paint", null, "locked")
        val f = fixture(mapOf(plane to cel(listOf(record(), record("locked", 2, locked = true)))))
        val before = f.scene.planes
        for (scope in listOf(linkedSetOf(address, locked), linkedSetOf(address, LineAddress("paint", null, "gone")),
            linkedSetOf(address, LineAddress("gone", "frame", "line")), emptySet())) {
            assertFails { f.scene.stageBake(scope) }
            assertTrue(f.renderer.copied.isEmpty())
            assertTrue(f.renderer.lineCalls.isEmpty())
            assertTrue(f.renderer.lookCalls.isEmpty())
            assertTrue(f.renderer.released.isEmpty())
            assertSame(before, f.scene.planes)
            assertFalse(f.history.canUndo)
        }
    }

    @Test fun missingCachedSourceAlsoRefusesWholeScopeBeforeCopy() {
        val f = fixture(mapOf(plane to cel(listOf(record(), record("later", 2, 2f)))))
        // Fault injection only: normal APIs always publish complete immutable source indexes.
        val stateField = f.scene.javaClass.getDeclaredField("state").also { it.isAccessible = true }
        val state = stateField.get(f.scene)
        val unitsField = state.javaClass.getDeclaredField("units").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val units = unitsField.get(state) as Map<LineAddress, RecordedScene.Cached<Int>>
        unitsField.set(state, units.filterKeys { it.recordId != "later" })
        assertFailsWith<IllegalStateException> {
            f.scene.stageBake(linkedSetOf(address, LineAddress("paint", null, "later")))
        }
        assertTrue(f.renderer.copied.isEmpty())
        assertTrue(f.renderer.lookCalls.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun discardAndStaleProposalReleaseEveryFreshSourceAndCacheExactlyOnce() {
        val f = fixture()
        val before = f.scene.planes
        val first = f.renderer.next
        val discarded = f.scene.stageBake(setOf(address))
        discarded.close(); discarded.close()
        assertOnce(f, setOf(first, first + 1))
        assertSame(before, f.scene.planes)
        assertFalse(f.history.canUndo)
        val second = f.renderer.next
        val stale = f.scene.stageBake(setOf(address))
        f.history.push(UndoLog.Step(listOf(UndoLog.TileChange("plain", 9, null, 99))))
        assertFalse(stale.commit())
        stale.close(); stale.close()
        assertOnce(f, setOf(second, second + 1))
        assertSame(before, f.scene.planes)
        assertEquals(1, f.history.undoDepth)
    }

    @Test fun publicationBudgetFailureReclaimsProposalAndKeepsSceneAndHistory() {
        var failBudget = false
        val f = fixture(sizeOf = { if (failBudget) error("injected budget failure") else 4 })
        val previous = UndoLog.Step(listOf(UndoLog.TileChange("plain", 9, 98, 99)))
        f.history.push(previous)
        val before = f.scene.planes
        val first = f.renderer.next
        val proposal = f.scene.stageBake(setOf(address))
        failBudget = true
        assertFailsWith<IllegalStateException> { proposal.commit() }
        proposal.close()
        assertSame(before, f.scene.planes)
        assertSame(previous, f.history.peekUndo())
        assertEquals(1, f.history.undoDepth)
        assertOnce(f, setOf(first, first + 1))
        assertFalse(98 in f.renderer.released)
    }

    @Test fun engineStylePreparedUndoRedoRestoresRecordsAndSameSlabSources() {
        val f = fixture()
        val beforeLook = f.renderer.pixels.getValue(assertNotNull(f.scene.look(plane, 0))).copyOf()
        f.scene.stageBake(setOf(address)).use { assertTrue(it.commit()) }
        val step = assertNotNull(f.history.peekUndo())
        val bakedSources = slabTextures(f)
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, false), RecordedScene.Direction.UNDO).use {
            assertTrue(it.commit())
        }
        assertEquals(listOf(record()), f.scene.planes.getValue(plane).records)
        assertTrue(slabTextures(f).isEmpty())
        assertSame(step, f.history.peekRedo())
        assertTrue(bakedSources.intersect(f.renderer.released.toSet()).isEmpty())
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, true), RecordedScene.Direction.REDO).use {
            assertTrue(it.commit())
        }
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertEquals(bakedSources, slabTextures(f))
        assertSame(step, f.history.peekUndo())
        assertContentEquals(beforeLook, f.renderer.pixels.getValue(assertNotNull(f.scene.look(plane, 0))))
        f.scene.close(); f.scene.close()
        assertTrue(bakedSources.intersect(f.renderer.released.toSet()).isEmpty())
    }

    @Test fun undoneBakeSourcesAreReleasedBySharedHistoryDiscardNotByScene() {
        val f = fixture()
        f.scene.stageBake(setOf(address)).use { assertTrue(it.commit()) }
        val step = assertNotNull(f.history.peekUndo())
        val sources = slabTextures(f)
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, false), RecordedScene.Direction.UNDO).use {
            assertTrue(it.commit())
        }
        f.history.clear()
        assertOnce(f, sources)
    }

    @Test fun malformedOperatorAndTileKeysCleanEveryReturnedFreshSibling() {
        for (mode in listOf("operator", "missing", "extra", "duplicate")) {
            val driver = Driver().also { it.wide = true }
            val f = fixture(driver = driver)
            val first = driver.next
            driver.mutateCopy = { _, copy ->
                when (mode) {
                    "operator" -> RecordedScene.Unit("erase", copy.tiles)
                    // Renderer cleans a fresh tile it allocated but excludes from its output.
                    "missing" -> {
                        driver.release(copy.tiles.getValue(1))
                        RecordedScene.Unit(copy.op, mapOf(0L to copy.tiles.getValue(0)))
                    }
                    "extra" -> {
                        val extra = driver.next++
                        driver.pixels[extra] = byteArrayOf(0, 0, 0, 0)
                        RecordedScene.Unit(copy.op, copy.tiles + (99L to extra))
                    }
                    else -> {
                        driver.release(copy.tiles.getValue(1))
                        RecordedScene.Unit(copy.op, mapOf(0L to copy.tiles.getValue(0), 1L to copy.tiles.getValue(0)))
                    }
                }
            }
            assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address)) }
            assertOnce(f, (first..first + if (mode == "extra") 2 else 1).toSet())
            assertFalse(f.history.canUndo)
            assertTrue(driver.lookCalls.isEmpty())
        }
    }

    @Test fun cacheSourceSlabAliasesAreRejectedWithoutReleasingExistingHandles() {
        for (aliasKind in listOf("source", "cache", "slab")) {
            val driver = Driver().also { it.wide = true; it.pixels[7] = byteArrayOf(1, 2, 3, 4) }
            val f = fixture(mapOf(plane to RecordedCel("paint", null, listOf(record()), mapOf(0L to listOf(
                RecordedCel.PixelSlab(0L, UndoLog.SlabValue(7, "normal")))))), driver)
            val existing = f.scene.heldCacheTextures() + 7
            val first = driver.next
            driver.mutateCopy = { _, copy ->
                val alias = when (aliasKind) {
                    "source" -> driver.copyInputs.single().tiles.getValue(0)
                    "cache" -> assertNotNull(f.scene.look(plane, 0))
                    else -> 7
                }
                // Clean the omitted copy, then return an early invalid alias and a fresh sibling.
                driver.release(copy.tiles.getValue(0))
                RecordedScene.Unit(copy.op, linkedMapOf(0L to alias, 1L to copy.tiles.getValue(1)))
            }
            assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address)) }
            assertOnce(f, setOf(first, first + 1))
            assertTrue(existing.intersect(driver.released.toSet()).isEmpty())
            assertFalse(f.history.canUndo)
        }
    }

    @Test fun undoOwnedSlabAliasIsRejectedAndFreshSiblingIsCleaned() {
        val driver = Driver().also { it.wide = true }
        val f = fixture(driver = driver)
        val historyOwned = 900
        driver.pixels[historyOwned] = byteArrayOf(9, 8, 7, 6)
        val undoStep = UndoLog.Step(emptyList(), slabs = listOf(UndoLog.SlabChange(
            "other", null, 0, 0, UndoLog.SlabValue(historyOwned, "normal"), null)))
        f.history.push(undoStep)
        val firstCopy = driver.next
        driver.mutateCopy = { _, copy ->
            // The renderer relinquishes its first allocation and returns a history-owned handle
            // plus the still-fresh second tile. The scene must reject the alias and clean the sibling.
            driver.release(copy.tiles.getValue(0))
            RecordedScene.Unit(copy.op, linkedMapOf(0L to historyOwned, 1L to copy.tiles.getValue(1)))
        }
        val before = f.scene.planes
        assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address)) }
        assertEquals(setOf(firstCopy, firstCopy + 1), driver.released.toSet())
        assertOnce(f, setOf(firstCopy, firstCopy + 1))
        assertFalse(historyOwned in driver.released)
        assertTrue(historyOwned in f.history.heldTextures())
        assertSame(before, f.scene.planes)
        assertSame(undoStep, f.history.peekUndo())
    }

    @Test fun redoOnlySlabAliasIsRejectedAndFreshSiblingIsCleaned() {
        val driver = Driver().also { it.wide = true }
        val f = fixture(driver = driver)
        val historyOwned = 901
        driver.pixels[historyOwned] = byteArrayOf(6, 7, 8, 9)
        val redoStep = UndoLog.Step(emptyList(), slabs = listOf(UndoLog.SlabChange(
            "other", null, 0, 0, null, UndoLog.SlabValue(historyOwned, "normal"))))
        f.history.push(redoStep)
        assertSame(redoStep, f.history.undo())
        assertTrue(historyOwned in f.history.heldTextures())
        val firstCopy = driver.next
        driver.mutateCopy = { _, copy ->
            driver.release(copy.tiles.getValue(0))
            RecordedScene.Unit(copy.op, linkedMapOf(0L to historyOwned, 1L to copy.tiles.getValue(1)))
        }
        val before = f.scene.planes
        assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address)) }
        assertEquals(setOf(firstCopy, firstCopy + 1), driver.released.toSet())
        assertOnce(f, setOf(firstCopy, firstCopy + 1))
        assertFalse(historyOwned in driver.released)
        assertTrue(historyOwned in f.history.heldTextures())
        assertSame(before, f.scene.planes)
        assertSame(redoStep, f.history.peekRedo())
    }

    @Test fun laterCopyCannotAliasAnEarlierCopyAndStillCleansAllFreshSiblings() {
        val driver = Driver().also { it.wide = true }
        val f = fixture(mapOf(plane to cel(listOf(record(), record("later", 2, 3f)))), driver)
        val first = driver.next
        driver.mutateCopy = { index, copy ->
            if (index == 1) copy else {
                driver.release(copy.tiles.getValue(3))
                RecordedScene.Unit(copy.op, linkedMapOf(3L to driver.outputs.first().tiles.getValue(0),
                    4L to copy.tiles.getValue(4)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            f.scene.stageBake(linkedSetOf(address, LineAddress("paint", null, "later")))
        }
        assertOnce(f, (first..first + 3).toSet())
        assertFalse(f.history.canUndo)
        assertTrue(driver.lookCalls.isEmpty())
    }

    @Test fun composedCacheCannotAliasFreshBakeSlabAndStageFailureReleasesItOnce() {
        val f = fixture()
        val first = f.renderer.next
        f.renderer.lookAlias = first
        assertFailsWith<IllegalArgumentException> { f.scene.stageBake(setOf(address)) }
        assertEquals(listOf(first), f.renderer.released)
        assertFalse(f.history.canUndo)
    }

    @Test fun contextLossBeforeProposalCloseNeverDeletesNamesInReplacementContext() {
        val f = fixture()
        val staged = f.scene.stageBake(setOf(address))
        f.scene.forget()
        assertFalse(staged.commit())
        staged.close(); staged.close(); f.scene.close()
        assertTrue(f.renderer.released.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun noDabLineBakesAsRecordDeletionWithNoInventedSlabs() {
        val f = fixture(mapOf(plane to cel(listOf(record().copy(samples = emptyList())))))
        f.scene.stageBake(setOf(address)).use { assertTrue(it.commit()) }
        val step = assertNotNull(f.history.peekUndo())
        assertEquals(1, step.lines.size)
        assertTrue(step.slabs.isEmpty())
        assertFalse(f.scene.hasContent)
        assertTrue(f.renderer.lookCalls.isEmpty())
    }

    @Test fun rendererWithoutCopyCapabilityExplicitlyRefusesBake() {
        val driver = object : RecordedScene.Renderer<Int> {
            override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset) =
                RecordedScene.Unit(preset.blend, mapOf(0L to 1))
            override fun look(plane: Pair<String, String?>, key: Long,
                              sources: List<RecordedScene.Source<Int>>): Int = 2
            override fun release(texture: Int) { error("Existing textures must not be released") }
        }
        val history = UndoLog<Int>(1_000_000, { 4 }, {})
        val scene = RecordedScene.create(mapOf(plane to cel()), brushes, history, driver)
        assertFailsWith<UnsupportedOperationException> { scene.stageBake(setOf(address)) }
        assertFalse(history.canUndo)
        assertEquals(listOf(record()), scene.planes.getValue(plane).records)
    }
}
