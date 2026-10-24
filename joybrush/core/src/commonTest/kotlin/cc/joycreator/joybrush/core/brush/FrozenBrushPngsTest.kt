package cc.joycreator.joybrush.core.brush

import kotlin.test.*

class FrozenBrushPngsTest {
    @Test fun snapshotOwnsBytesAndEveryLookupCopiesOutward() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = mutableMapOf("tips/one.png" to bytes)
        val made = FrozenBrushPngs.snapshot(source)
        bytes.fill(0); source.clear()
        assertContentEquals(byteArrayOf(1, 2, 3), made.requirePng("tips/one.png"))
        made.requirePng("tips/one.png").fill(0)
        assertContentEquals(byteArrayOf(1, 2, 3), made.requirePng("tips/one.png"))
        assertEquals(setOf("tips/one.png"), made.paths)
        assertEquals(3L, made.encodedBytes)
        assertNull(made.png("missing.png"))
        assertFailsWith<IllegalArgumentException> { made.requirePng("missing.png") }
    }

    @Test fun requiredSequenceCanRepeatButCannotReferToMissingImagesOrHideExtras() {
        val bundle = FrozenBrushPngs.snapshot(mapOf("one.png" to byteArrayOf(1), "two.png" to byteArrayOf(2)))
        bundle.requirePaths(listOf("two.png", "one.png", "two.png"))
        assertFailsWith<IllegalArgumentException> { bundle.requirePaths(listOf("one.png")) }
        assertFailsWith<IllegalArgumentException> { bundle.requirePaths(listOf("one.png", "missing.png")) }
    }

    @Test fun pathAndEncodedBudgetsRejectBeforeResolution() {
        for (path in listOf("/tip.png", "../tip.png", "a/../tip.png", "a//tip.png", "a/./tip.png",
            "C:/tip.png", "a\\tip.png", "tip.raw", "tip.png\u0000", "a..b.png")) {
            assertFailsWith<IllegalArgumentException>(path) { FrozenBrushPngs.snapshot(mapOf(path to byteArrayOf(1))) }
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenBrushPngs.snapshot(mapOf("one.png" to ByteArray(3)), FrozenBrushPngs.Limits(maxBytesPerImage = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenBrushPngs.snapshot(mapOf("one.png" to ByteArray(2), "two.png" to ByteArray(2)),
                FrozenBrushPngs.Limits(maxTotalBytes = 3))
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenBrushPngs.snapshot(mapOf("one.png" to ByteArray(1)), FrozenBrushPngs.Limits(maxImages = 0))
        }
    }
}
