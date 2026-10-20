package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.ImageMask
import cc.joycreator.joybrush.core.brush.SampleMaskAtlas
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PngSamplePreflightTest {
    @Test fun allUniqueHeadersAndPixelsAreReservedBeforeTheDecodeBoundary() {
        val assets = mapOf("a.png" to png(3, 2), "b.png" to png(2, 3))
        val opened = mutableListOf<String>(); var closed = 0
        val prepared = PngSamplePreflight.read(SampledTipSpec(listOf("a.png", "b.png", "a.png")), { path ->
            opened.add(path)
            object : ByteArrayInputStream(assets.getValue(path)) { override fun close() { closed++; super.close() } }
        })
        assertEquals(listOf("a.png", "b.png"), opened)
        assertEquals(2, closed); assertEquals(3, prepared.size)
        assertEquals(18L, prepared.decodedPixels)
        assertEquals(assets.values.sumOf { it.size }.toLong(), prepared.encodedBytes)
        assertEquals(SamplePngHeader(3, 2, 8, 6), prepared.header(0))
        val exported = prepared.encodedCopy(0); exported.fill(0)
        assertContentEquals(assets.getValue("a.png"), prepared.encodedCopy(2))
        assertFailsWith<BrushException> {
            PngSamplePreflight.read(SampledTipSpec(listOf("a.png", "b.png")), { ByteArrayInputStream(assets.getValue(it)) },
                SamplePngBudgets(maxDecodedSetPixels = 11))
        }
        assertFailsWith<BrushException> {
            PngSamplePreflight.read(SampledTipSpec(listOf("a.png", "b.png", "a.png")), { ByteArrayInputStream(assets.getValue(it)) },
                SamplePngBudgets(maxDecodedSetPixels = 17))
        }
    }

    @Test fun encodedBudgetsAreEnforcedEvenWhenStreamsDoNotDeclareALength() {
        val bytes = png(1, 1)
        var closed = false
        val exact = SamplePngBudgets(maxEncodedImageBytes = bytes.size, maxEncodedSetBytes = bytes.size.toLong())
        assertEquals(1, PngSamplePreflight.read(SampledTipSpec(listOf("a.png")), { ByteArrayInputStream(bytes) }, exact).size)
        assertFailsWith<BrushException> {
            PngSamplePreflight.read(SampledTipSpec(listOf("a.png")), {
                object : ByteArrayInputStream(bytes + byteArrayOf(0)) { override fun close() { closed = true; super.close() } }
            }, exact)
        }
        assertTrue(closed)
        assertFailsWith<BrushException> {
            PngSamplePreflight.read(SampledTipSpec(listOf("a.png", "b.png")), { ByteArrayInputStream(bytes) }, exact)
        }
        assertFailsWith<BrushException> { PngSamplePreflight.read(SampledTipSpec(listOf("missing.png")), { null }) }
    }

    @Test fun signatureDimensionsCrcAndCodingAreCheckedWithoutBitmapAllocation() {
        assertEquals(SamplePngHeader(2, 3, 8, 6), PngSamplePreflight.inspect(png(2, 3)))
        for (bad in listOf(png(0, 1), png(2049, 1), png(-1, 1), png(1, 1, depth = 3), png(1, 1, color = 1),
            png(1, 1).copyOf(20), png(1, 1).also { it[0] = 0 }, png(1, 1).also { it[16] = (it[16].toInt() xor 1).toByte() }))
            assertFailsWith<BrushException> { PngSamplePreflight.inspect(bad) }
    }

    @Test fun callerBudgetsCanTightenButCannotRaiseTheHardLimits() {
        var opens = 0
        for (budget in listOf(
            SamplePngBudgets(maxEncodedImageBytes = PngSamplePreflight.MAX_ENCODED_IMAGE_BYTES + 1),
            SamplePngBudgets(maxEncodedSetBytes = PngSamplePreflight.MAX_ENCODED_SET_BYTES + 1),
            SamplePngBudgets(maxImageDimension = 2049),
            SamplePngBudgets(maxDecodedSetPixels = 4L * 1024 * 1024 + 1),
        )) assertFailsWith<BrushException> {
            PngSamplePreflight.read(SampledTipSpec(listOf("a.png")), { opens++; ByteArrayInputStream(png(1, 1)) }, budget)
        }
        assertEquals(0, opens, "invalid budgets are rejected before reading assets")
    }

    @Test fun duplicateHeadersAnimationChunkOverrunsAndTrailingDataAreRefused() {
        val header = ihdr(1, 1, 8, 6)
        for (bad in listOf(
            png(1, 1, extra = chunk("IHDR", header)), png(1, 1, extra = chunk("acTL", ByteArray(8))),
            png(1, 1) + byteArrayOf(0), png(1, 1).dropLast(12).toByteArray(),
            png(1, 1).also { for (i in 8..11) it[i] = 255.toByte() },
        )) assertFailsWith<BrushException> { PngSamplePreflight.inspect(bad) }
    }

    // These are structurally valid PNG containers with a dummy IDAT. Pixel decompression is
    // deliberately outside this pure header test; the Android decoder must refuse dummy pixels.
    private fun png(w: Int, h: Int, depth: Int = 8, color: Int = 6, extra: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr(w, h, depth, color)) +
            extra + chunk("IDAT", byteArrayOf(0)) + chunk("IEND", byteArrayOf())

    private fun ihdr(w: Int, h: Int, depth: Int, color: Int): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { it.writeInt(w); it.writeInt(h); it.write(byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0)) }
    }.toByteArray()

    private fun chunk(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        val bytes = type.toByteArray(Charsets.US_ASCII); val crc = CRC32().apply { update(bytes); update(payload) }
        DataOutputStream(out).use { it.writeInt(payload.size); it.write(bytes); it.write(payload); it.writeInt(crc.value.toInt()) }
    }.toByteArray()
}

class SampledMaskTexturesTest {
    private class FakeGl : SampleMaskGl {
        var next = 1; var max = 64; var failUpload = false; var afterUpload: (() -> Unit)? = null
        val generated = mutableListOf<Int>(); val deleted = mutableListOf<Int>()
        val uploads = mutableListOf<Triple<Int, Pair<Int, Int>, ByteArray>>()
        val binds = mutableListOf<Pair<Int, Int>>()
        override fun maxTextureSize() = max
        override fun genTexture(): Int { val texture = next++; generated.add(texture); return texture }
        override fun uploadR8(texture: Int, width: Int, height: Int, bytes: ByteArray, contextCurrent: () -> Boolean) {
            assertTrue(contextCurrent()); uploads.add(Triple(texture, width to height, bytes.copyOf()))
            afterUpload?.invoke()
            if (failUpload) throw BrushException("injected upload failure")
        }
        override fun deleteTexture(texture: Int) { deleted.add(texture) }
        override fun bindTexture(unit: Int, texture: Int) { binds.add(unit to texture) }
        override fun checkError(operation: String) = Unit
    }

    private fun atlas() = SampleMaskAtlas.pack(listOf(ImageMask.fromR8(3, 3, ByteArray(9) { 128.toByte() })), 64)

    @Test fun oneAtlasIsUploadedOnceAndBoundPerBatchWithASafeDisabledPlaceholder() {
        val gl = FakeGl(); val owner = SampledMaskTextures(gl, { 1L })
        owner.create(); owner.create()
        assertEquals(64, owner.maxTextureSize); assertEquals(1, gl.uploads.size)
        assertContentEquals(byteArrayOf(255.toByte()), gl.uploads[0].third)
        val binding = owner.begin(atlas())
        assertEquals(2, gl.uploads.size); assertEquals(5 to 5, gl.uploads[1].second)
        owner.bindForBatch(binding, 3); owner.bindForBatch(null, 3)
        assertEquals(listOf(3 to binding.texture, 3 to 1), gl.binds)
        assertEquals(setOf(1, binding.texture), owner.heldTextureHandles())
        owner.end(binding)
        assertEquals(listOf(binding.texture), gl.deleted); assertEquals(setOf(1), owner.heldTextureHandles())
        owner.release(); assertEquals(listOf(binding.texture, 1), gl.deleted)
        assertTrue(owner.heldTextureHandles().isEmpty())
    }

    @Test fun anotherLiveSetCannotSilentlyReplaceTheActiveTexture() {
        val gl = FakeGl(); val owner = SampledMaskTextures(gl, { 1L }); owner.create()
        val active = owner.begin(atlas()); val generated = gl.generated.toList()
        assertFailsWith<BrushException> { owner.begin(atlas()) }
        assertEquals(generated, gl.generated); assertTrue(gl.deleted.isEmpty())
        owner.end(active); val next = owner.begin(atlas())
        owner.end(active); owner.bindForBatch(next, 3)
        assertEquals(next.texture, gl.binds.last().second)
        assertFailsWith<BrushException> { owner.bindForBatch(active, 3) }
    }

    @Test fun failedUploadDeletesOnlyItsNewNameAndLeavesThePlaceholderUsable() {
        val gl = FakeGl(); val owner = SampledMaskTextures(gl, { 1L }); owner.create()
        gl.failUpload = true
        assertFailsWith<BrushException> { owner.begin(atlas()) }
        assertEquals(listOf(2), gl.deleted); assertEquals(setOf(1), owner.heldTextureHandles())
        owner.bindForBatch(null, 3)
        gl.failUpload = false
        val next = owner.begin(atlas()); assertEquals(3, next.texture)
        owner.release(); assertEquals(setOf(1, 2, 3), gl.deleted.toSet())
    }

    @Test fun contextLossAbandonsNamesAndStaleEndCannotDeleteReplacementResources() {
        var epoch = 1L; val gl = FakeGl(); val owner = SampledMaskTextures(gl, { epoch }); owner.create()
        val old = owner.begin(atlas())
        epoch = 2
        owner.create(); val replacement = owner.begin(atlas())
        assertTrue(gl.deleted.isEmpty(), "old names must never be deleted in a new context")
        owner.end(old); owner.bindForBatch(replacement, 3)
        assertFailsWith<BrushException> { owner.bindForBatch(old, 3) }
        owner.release()
        assertEquals(listOf(4, 3), gl.deleted)
    }

    @Test fun contextLossDuringUploadSkipsDeletionAndStorageBudgetRefusesBeforeAllocation() {
        var epoch = 1L; val gl = FakeGl(); val owner = SampledMaskTextures(gl, { epoch }); owner.create()
        gl.afterUpload = { epoch++ }
        assertFailsWith<BrushException> { owner.begin(atlas()) }
        assertTrue(gl.deleted.isEmpty()); assertTrue(owner.heldTextureHandles().isEmpty())
        gl.afterUpload = null
        owner.create(); assertFailsWith<BrushException> { owner.bindForBatch(SampledAtlasBinding(2, 5, 5, atlas().rects()), 3) }
        val bounded = SampledMaskTextures(gl, { epoch }, maxUploadPixels = 24)
        bounded.create(); val count = gl.generated.size
        assertFailsWith<BrushException> { bounded.begin(atlas()) }
        assertEquals(count, gl.generated.size, "reject before generating or uploading another GPU name")
        val smallGl = FakeGl().apply { max = 4 }
        val smallGpu = SampledMaskTextures(smallGl, { epoch }); smallGpu.create()
        assertFailsWith<BrushException> { smallGpu.begin(atlas()) }
        assertEquals(listOf(1), smallGl.generated, "actual GPU dimension cap precedes allocation too")
        owner.forget(); assertTrue(owner.heldTextureHandles().isEmpty())
    }
}
