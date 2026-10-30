package cc.joycreator.joybrush.core.brush

import kotlin.test.*

class FrozenBrushAssetRegistryTest {
    private fun preset() = BrushPreset(id = "library", name = "Sampled", size = Param(16f),
        tip = TipSpec(sampled = SampledTipSpec(listOf("tips/one.png"))))
    private fun pngs(vararg bytes: Byte) = FrozenBrushPngs.snapshot(mapOf("tips/one.png" to bytes))

    @Test fun definitionsAndActualImageBytesDetermineFrozenIdentity() {
        val first = FrozenBrushRegistry.freezeWithAssets(emptyMap(), emptyMap(), preset(), pngs(1, 2, 3)) { "first" }
        val reused = FrozenBrushRegistry.freezeWithAssets(first.entries, first.assets, preset(), pngs(1, 2, 3)) {
            error("identical JSON and bytes must reuse the original identity")
        }
        assertEquals("first", reused.id)
        assertTrue(reused.newEntries.isEmpty())
        val changed = FrozenBrushRegistry.freezeWithAssets(first.entries, first.assets, preset(), pngs(1, 2, 4)) { "second" }
        assertEquals("second", changed.id)
        assertEquals(setOf("first", "second"), changed.assets.keys)
        assertContentEquals(byteArrayOf(1, 2, 3), changed.assets.getValue("first").requirePng("tips/one.png"))
        assertContentEquals(byteArrayOf(1, 2, 4), changed.assets.getValue("second").requirePng("tips/one.png"))
        assertEquals(first.preset.copy(id = "same"), changed.preset.copy(id = "same"))
    }

    @Test fun missingExtraAndChangedResourcesCannotEnterAnExistingFrozenIdentity() {
        assertFailsWith<BrushException> { FrozenBrushRegistry.freeze(emptyMap(), preset()) { "missing" } }
        assertFailsWith<BrushException> {
            FrozenBrushRegistry.freezeWithAssets(emptyMap(), emptyMap(), preset(), FrozenBrushPngs.empty()) { "missing" }
        }
        val first = FrozenBrushRegistry.freezeWithAssets(emptyMap(), emptyMap(), preset(), pngs(1)) { "first" }
        assertFailsWith<BrushException> {
            FrozenBrushRegistry.freezeWithAssets(first.entries, emptyMap(), preset(), pngs(1)) { "unused" }
        }
        assertFailsWith<BrushException> {
            FrozenBrushRegistry.freezeWithAssets(first.entries, first.assets, preset(), pngs(2)) { "first" }
        }
        val extra = FrozenBrushPngs.snapshot(mapOf("tips/one.png" to byteArrayOf(1), "extra.png" to byteArrayOf(2)))
        assertFailsWith<BrushException> {
            FrozenBrushRegistry.freezeWithAssets(first.entries, first.assets, preset(), extra) { "second" }
        }
    }

    @Test fun assetMapsAndPngCopiesCannotMutateAFrozenResult() {
        val first = FrozenBrushRegistry.freezeWithAssets(emptyMap(), emptyMap(), preset(), pngs(1, 2)) { "first" }
        val known = first.assets.toMutableMap()
        val reused = FrozenBrushRegistry.freezeWithAssets(first.entries, known, preset(), pngs(1, 2)) { "unused" }
        known.clear()
        reused.assets.getValue("first").requirePng("tips/one.png").fill(0)
        assertContentEquals(byteArrayOf(1, 2), reused.assets.getValue("first").requirePng("tips/one.png"))
        assertTrue(reused.assets.getValue("first").sameBytes(pngs(1, 2)))
        assertFalse(reused.assets.getValue("first").sameBytes(pngs(2, 1)))
    }
}
