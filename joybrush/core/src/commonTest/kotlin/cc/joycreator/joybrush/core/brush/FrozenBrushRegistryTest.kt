package cc.joycreator.joybrush.core.brush

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrozenBrushRegistryTest {
    private fun pen(size: Param = Param(8f)) = BrushPreset(
        id = "library-pen", name = "Pen", size = size,
    )

    @Test fun unchangedPresetReusesFrozenIdWithoutCallingAllocator() {
        val first = FrozenBrushRegistry.freeze(emptyMap(), pen()) { "frozen-1" }
        val reused = FrozenBrushRegistry.freeze(first.entries, pen()) {
            error("identical definitions must not allocate")
        }
        assertEquals("frozen-1", reused.id)
        assertEquals(first.entries, reused.entries)
        assertTrue(reused.newEntries.isEmpty())
        assertContentEquals(first.bytes, reused.bytes)
        assertEquals(reused.id, reused.preset.id)
    }

    @Test fun changedWidthUnderSameLibraryIdNeverOverwritesEarlierCopy() {
        val first = FrozenBrushRegistry.freeze(emptyMap(), pen()) { "frozen-1" }
        val wider = FrozenBrushRegistry.freeze(first.entries, pen(Param(16f))) { "frozen-2" }
        assertNotEquals(first.id, wider.id)
        assertEquals(8f, wider.entries.getValue(first.id).size.base)
        assertEquals(16f, wider.entries.getValue(wider.id).size.base)
        assertEquals(setOf("frozen-2"), wider.newEntries.keys)
        assertEquals("library-pen", pen().id)
    }

    @Test fun changedCurveUnderSameLibraryIdGetsFreshDefinition() {
        val straight = Param(8f, listOf(InputCurve(BrushInput.pressure,
            listOf(listOf(0f, 0f), listOf(1f, 1f)))))
        val curved = straight.copy(inputs = listOf(InputCurve(BrushInput.pressure,
            listOf(listOf(0f, 0f), listOf(0.5f, 0.2f), listOf(1f, 1f)))))
        val first = FrozenBrushRegistry.freeze(emptyMap(), pen(straight)) { "frozen-1" }
        val second = FrozenBrushRegistry.freeze(first.entries, pen(curved)) { "frozen-2" }
        assertEquals(straight, second.entries.getValue(first.id).size)
        assertEquals(curved, second.preset.size)
        assertEquals(2, second.entries.size)
    }

    @Test fun reuseIsIndependentOfLibraryIdAndMapOrder() {
        val a = pen().copy(id = "a")
        val z = pen().copy(id = "z")
        val frozen = FrozenBrushRegistry.freeze(linkedMapOf("z" to z, "a" to a),
            pen().copy(id = "other-library")) { error("must reuse") }
        assertEquals("a", frozen.id)
    }

    @Test fun savedAndLoadedDefinitionResolvesWithoutTheLibrary() {
        val frozen = FrozenBrushRegistry.freeze(emptyMap(), pen()) { "frozen-1" }
        val loaded = BrushJson.decodeChecked(frozen.bytes.decodeToString())
        val entries = FrozenBrushRegistry.validate(mapOf(loaded.id to loaded))
        assertEquals(frozen.preset, FrozenBrushRegistry.resolve(entries, frozen.id))
        assertNull(FrozenBrushRegistry.resolve(entries, "library-pen"))
        assertNull(FrozenBrushRegistry.resolve(entries, "missing"))
        val reused = FrozenBrushRegistry.freeze(entries, pen()) { error("must reuse after load") }
        assertContentEquals(frozen.bytes, reused.bytes)
    }

    @Test fun callerCollectionsAndReturnedBytesCannotChangeFrozenResult() {
        val point = mutableListOf(0f, 0f)
        val points = mutableListOf<List<Float>>(point, mutableListOf(1f, 1f))
        val inputs = mutableListOf(InputCurve(BrushInput.pressure, points))
        val extensions = mutableMapOf("original" to "yes")
        val preset = pen(Param(8f, inputs)).copy(extensions = extensions)
        val frozen = FrozenBrushRegistry.freeze(emptyMap(), preset) { "frozen-1" }
        val expected = frozen.bytes
        point[1] = 0.8f
        points.clear()
        inputs.clear()
        extensions.clear()
        val exposed = frozen.bytes
        exposed.fill(0)
        assertContentEquals(expected, frozen.bytes)
        assertEquals(listOf(0f, 0f), frozen.preset.size.inputs[0].curve[0])
        assertEquals(mapOf("original" to "yes"), frozen.preset.extensions)
        assertContentEquals(expected, BrushJson.encode(frozen.preset).encodeToByteArray())
    }

    @Test fun loadedCallerAndReturnedNestedCollectionsCannotChangeOutcome() {
        val point = mutableListOf(0f, 0f)
        val original = pen(Param(8f, listOf(InputCurve(BrushInput.pressure,
            listOf(point, listOf(1f, 1f)))))).copy(id = "old")
        val loaded = mutableMapOf("old" to original)
        val frozen = FrozenBrushRegistry.freeze(loaded, pen(Param(12f))) { "new" }
        point[1] = 0.9f
        loaded.clear()
        val exposed = frozen.entries.getValue("old")
        (exposed.size.inputs[0].curve[0] as MutableList<Float>)[1] = 0.7f
        (frozen.preset.extensions as? MutableMap<String, String>)?.put("changed", "yes")
        assertEquals(listOf(0f, 0f), frozen.entries.getValue("old").size.inputs[0].curve[0])
        assertTrue(frozen.preset.extensions.isEmpty())
        assertEquals(setOf("old", "new"), frozen.entries.keys)
    }

    @Test fun generatedIdCollisionsRefuseWithoutChangingAnyExistingDefinition() {
        val first = FrozenBrushRegistry.freeze(emptyMap(), pen()) { "frozen-1" }
        val entries = first.entries
        val expected = BrushJson.encode(entries.getValue("frozen-1"))
        var calls = 0
        assertFailsWith<BrushException> {
            FrozenBrushRegistry.freeze(entries, pen(Param(16f))) { calls++; "frozen-1" }
        }
        assertEquals(1, calls)
        assertEquals(expected, BrushJson.encode(entries.getValue("frozen-1")))
        assertEquals(1, entries.size)
    }

    @Test fun unsafeGeneratedIdsFailClosed() {
        for (id in listOf("", "../brush", "a..b", "a/b", "a\\b", " white ", "x".repeat(129))) {
            assertFailsWith<BrushException>(id) {
                FrozenBrushRegistry.freeze(emptyMap(), pen()) { id }
            }
        }
    }

    @Test fun entireLoadedMapMustValidateBeforeResolveOrReuse() {
        val good = pen().copy(id = "good")
        val badCopies = listOf(
            mapOf("good" to good, "wrong-key" to pen()),
            mapOf("good" to good, "bad" to pen(Param(0f)).copy(id = "bad")),
            mapOf("good" to good, "bad" to pen().copy(id = "bad", version = BRUSH_VERSION + 1)),
            mapOf("good" to good, "bad" to pen().copy(id = "bad", version = 1,
                paper = PaperResponse(influence = 1f))),
        )
        for (entries in badCopies) {
            assertFailsWith<BrushException> { FrozenBrushRegistry.validate(entries) }
            assertFailsWith<BrushException> { FrozenBrushRegistry.resolve(entries, "good") }
            assertFailsWith<BrushException> { FrozenBrushRegistry.resolve(entries, "missing") }
            assertFailsWith<BrushException> {
                FrozenBrushRegistry.freeze(entries, pen()) { error("invalid map must fail first") }
            }
        }
    }

    @Test fun invalidCandidateDoesNotAllocateOrModifyLoadedMap() {
        val original = pen().copy(id = "old")
        val loaded = mapOf("old" to original)
        assertFailsWith<BrushException> {
            FrozenBrushRegistry.freeze(loaded, pen(Param(Float.NaN))) { error("must not allocate") }
        }
        assertEquals(mapOf("old" to original), loaded)
    }

    @Test fun invalidCandidateVersionsFailBeforeEncodingOrAllocation() {
        val original = pen().copy(id = "old")
        val loaded = mapOf("old" to original)
        val invalid = listOf(
            pen().copy(version = BRUSH_VERSION + 1),
            pen().copy(version = 0),
            pen().copy(version = 1, paper = PaperResponse(influence = 1f)),
        )
        for (candidate in invalid) {
            var allocations = 0
            assertFailsWith<BrushException> {
                FrozenBrushRegistry.freeze(loaded, candidate) { allocations++; "new" }
            }
            assertEquals(0, allocations)
            assertEquals(mapOf("old" to original), loaded)
        }
    }
}
