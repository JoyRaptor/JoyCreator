package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlinx.serialization.json.*
import javax.imageio.ImageIO
import kotlin.test.*

class SampledBrushArchiveTest {
    private fun png(argb: Int = -1): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, argb)
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "png", it)) }.toByteArray()
    }
    private val verifier = FrozenBrushAssets.PngVerifier { bytes, w, h ->
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        require(image.width == w && image.height == h)
    }
    private fun contents(bytes: ByteArray = png()): JbContents {
        val old = LineArchiveFixture.linesOnly()
        val brush = old.brushes.values.single()
        return old.copy(brushes = mapOf(brush.id to brush.copy(tip = brush.tip.copy(
            sampled = SampledTipSpec(listOf("tips/one.png", "tips/one.png"))))),
            brushAssets = mapOf(brush.id to FrozenBrushPngs.snapshot(mapOf("tips/one.png" to bytes))))
    }
    private fun encoded(c: JbContents) = ByteArrayOutputStream().also { JbArchive.write(it, c, verifier) }.toByteArray()
    private fun entries(bytes: ByteArray) = LineArchiveFixture.entries(bytes)
    private fun zip(entries: List<Pair<String, ByteArray>>) = LineArchiveFixture.zip(entries)

    @Test fun actualArchiveRestoresFrozenPngAndRawLinesWithoutTheBrushLibrary() {
        val pixels = png(0x7F123456)
        val c = contents(pixels)
        val bytes = encoded(c)
        val parts = entries(bytes)
        assertTrue(parts.any { it.first == FrozenBrushAssets.MANIFEST_ENTRY })
        assertEquals(1, parts.count { FrozenBrushAssets.isBlobEntry(it.first) }, "repeated sequence paths share one PNG")
        var decodes = 0
        val opened = JbArchive.read(ByteArrayInputStream(bytes), { error("new archives must not consult the library") },
            FrozenBrushAssets.PngVerifier { data, w, h -> decodes++; verifier.verify(data, w, h) })
        assertEquals(1, decodes)
        assertEquals(c.brushes, opened.brushes)
        // Archives restore drawing order, even if their input map supplied unsorted records.
        assertEquals(c.strokes.mapValues { (_, records) -> records.sortedBy { it.seq } }, opened.strokes)
        assertEquals(c.doc, opened.doc)
        val id = c.brushes.keys.single()
        assertContentEquals(pixels, opened.brushAssets.getValue(id).requirePng("tips/one.png"))
        opened.brushAssets.getValue(id).requirePng("tips/one.png").fill(0)
        assertContentEquals(pixels, opened.brushAssets.getValue(id).requirePng("tips/one.png"))
        val resaved = entries(encoded(opened)).toMap()
        for ((name, payload) in parts) assertContentEquals(payload, resaved.getValue(name), name)
    }

    @Test fun identicalJsonWithDifferentPngBytesGetsDifferentContentAddress() {
        val first = contents(png(0xFF000000.toInt()))
        val second = contents(png(0xFFFFFFFF.toInt()))
        assertEquals(first.brushes, second.brushes)
        val a = entries(encoded(first)).single { FrozenBrushAssets.isBlobEntry(it.first) }
        val b = entries(encoded(second)).single { FrozenBrushAssets.isBlobEntry(it.first) }
        assertNotEquals(a.first, b.first)
        assertFalse(a.second.contentEquals(b.second))
    }

    @Test fun missingCorruptExtraAndMismatchedAssetsRefuseOnRealArchiveRead() {
        val original = entries(encoded(contents()))
        val blob = original.single { FrozenBrushAssets.isBlobEntry(it.first) }
        val manifest = original.single { it.first == FrozenBrushAssets.MANIFEST_ENTRY }
        fun refuses(parts: List<Pair<String, ByteArray>>) {
            assertFailsWith<JbArchiveException> { JbArchive.read(ByteArrayInputStream(zip(parts))) }
        }
        refuses(original.filter { it.first != blob.first })
        refuses(original.filter { it.first != manifest.first })
        refuses(original.map { if (it.first == blob.first) it.first to png(0xFF123456.toInt()) else it })
        refuses(original + ("brush-assets/unlisted.png" to png()))
        val root = Json.parseToJsonElement(manifest.second.decodeToString()).jsonObject
        val brush = root.getValue("brushes").jsonArray.single().jsonObject
        val image = brush.getValue("images").jsonArray.single().jsonObject
        for (changed in listOf(JsonObject(image + ("width" to JsonPrimitive(3))),
            JsonObject(image + ("path" to JsonPrimitive("../tip.png"))))) {
            val changedBrush = JsonObject(brush + ("images" to JsonArray(listOf(changed))))
            val changedRoot = JsonObject(root + ("brushes" to JsonArray(listOf(changedBrush))))
            refuses(original.map { if (it.first == manifest.first) it.first to changedRoot.toString().encodeToByteArray() else it })
        }
    }

    @Test fun malformedPngOrMissingBundleCannotWriteEvenAnArchivePrefix() {
        for (c in listOf(contents().copy(brushAssets = emptyMap()), contents(byteArrayOf(1, 2, 3)))) {
            val out = ByteArrayOutputStream()
            assertFailsWith<JbArchiveException> { JbArchive.write(out, c) }
            assertEquals(0, out.size())
        }
        val malformed = png().copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        val out = ByteArrayOutputStream()
        assertFailsWith<JbArchiveException> { JbArchive.write(out, contents(malformed)) }
        assertEquals(0, out.size(), "all PNG chunk CRCs are checked before ZIP output")
    }

    @Test fun setPixelBudgetRunsBeforeAnyFullDecoder() {
        val largeHeader = png().also { bytes ->
            fun put(at: Int, value: Int) { for (i in 0..3) bytes[at + i] = (value ushr (24 - 8 * i)).toByte() }
            put(16, 2048); put(20, 2048)
            put(29, CRC32().apply { update(bytes, 12, 17) }.value.toInt())
        }
        val old = contents()
        val id = old.brushes.keys.single()
        val brush = old.brushes.getValue(id)
        val c = old.copy(brushes = mapOf(id to brush.copy(tip = brush.tip.copy(
            sampled = SampledTipSpec(listOf("large.png", "small.png"))))),
            brushAssets = mapOf(id to FrozenBrushPngs.snapshot(mapOf("large.png" to largeHeader, "small.png" to png()))))
        var calls = 0
        assertFailsWith<JbArchiveException> {
            JbArchive.write(ByteArrayOutputStream(), c, FrozenBrushAssets.PngVerifier { _, _, _ -> calls++ })
        }
        assertEquals(0, calls, "aggregate set pixel budget precedes pixel decoding")
        assertEquals(16 * 1024 * 1024, FrozenBrushAssets.MAX_PNG_BYTES)
        assertEquals(32L * 1024 * 1024, FrozenBrushAssets.MAX_SET_BYTES)
        assertEquals(64L * 1024 * 1024, FrozenBrushAssets.MAX_ARCHIVE_BYTES)
    }

    @Test fun explicitFullDecoderCanRefuseWithoutPublishingOrReplacingAFile() {
        val target = File.createTempFile("joybrush-sampled-", ".joybrush")
        try {
            val before = encoded(contents())
            target.writeBytes(before)
            assertFailsWith<JbArchiveException> {
                JbArchive.save(target, contents(), FrozenBrushAssets.PngVerifier { _, _, _ -> error("full decoder refused") })
            }
            assertContentEquals(before, target.readBytes())
            assertFalse(File(target.path + ".tmp").exists())
            val opened = JbArchive.open(target, null, verifier)
            assertTrue(opened.brushAssets.isNotEmpty())
        } finally {
            target.delete(); File(target.path + ".tmp").delete(); File(target.path + ".bak").delete()
        }
    }

    @Test fun legacyArchivesKeepTheirExactEntrySetAndNoResourceResolverIsRequired() {
        val old = LineArchiveFixture.linesOnly()
        val before = LineArchiveFixture.encoded(old)
        val back = JbArchive.read(ByteArrayInputStream(before))
        assertTrue(back.brushAssets.isEmpty())
        val after = LineArchiveFixture.encoded(back)
        assertEquals(entries(before).map { it.first }, entries(after).map { it.first })
        for ((index, item) in entries(before).withIndex()) assertContentEquals(item.second, entries(after)[index].second)
    }
}
