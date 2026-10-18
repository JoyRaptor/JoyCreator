package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.io.ByteWriter
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.stroke.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*

internal object LineArchiveFixture {
    val brush = BrushPreset(id = "frozen-ink", name = "Ink", size = Param(4f))
    fun tile(value: Int) = ByteArray(TILE_BYTES).also { it[0] = value.toByte(); it[3] = -1 }
    fun contents(): JbContents {
        val cel = Cel("c", tiles = listOf("0_0"), strokesFile = "layers/l/c/strokes.jbs",
            slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 2, "multiply")), looks = listOf("0_0"))
        val doc = JbDocument(id = "lines", name = "Mixed",
            boards = listOf(Board("b", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
            layers = listOf(Layer("l", "Paint", LayerKind.PAINT, cels = listOf(cel))), brushes = listOf(brush.id))
        val record = StrokeRecord("a", brush.id, 123, 0f, 1f,
            listOf(PenSample(20f, 20f, 0.0, 1f), PenSample(24f, 20f, 16.0, 0.5f)),
            colorArgb = 0x88334455.toInt(), widthScale = 1.25f, seq = 1, locked = true, groupId = "group-星")
        return JbContents(doc, mapOf(Triple("l", "c", "0_0") to tile(5)),
            mapOf(("l" to "c") to listOf(record.copy(id = "b", seq = 3), record)),
            slabs = mapOf(SlabKey("l", "c", "0_0", 2) to tile(9)),
            looks = mapOf(Triple("l", "c", "0_0") to tile(13)), brushes = mapOf(brush.id to brush))
    }
    /** [c] with every upper slab given an operator this screen cannot show yet ("overlay"): still refused on open (v1.2). */
    fun overlay(c: JbContents): JbContents = c.copy(doc = c.doc.copy(layers = c.doc.layers.map { l ->
        l.copy(cels = l.cels.map { cel -> cel.copy(slabs = cel.slabs.map { if (it.seq >= 1) it.copy(op = "overlay") else it }) })
    }))
    /** Editable lines v1: the mixed fixture without its upper pixel slab, i.e. ordinary lines, a saved look and a frozen brush. */
    fun linesOnly(): JbContents {
        val mixed = contents()
        return mixed.copy(doc = mixed.doc.copy(layers = mixed.doc.layers.map { l ->
            l.copy(cels = l.cels.map { it.copy(slabs = emptyList()) })
        }), slabs = emptyMap())
    }
    /** The same lines carrying a captured physical source: the exact-provenance format, which this build refuses to show. */
    fun capturedLines(): JbContents = linesOnly().let { c ->
        c.copy(strokes = c.strokes.mapValues { (_, rs) -> rs.map { it.copy(capturedPhysicalSource = CapturedPhysicalSource(
            CaptureDomain(CaptureRect(-10, -20, 80, 90), listOf(CaptureRect(3, 4, 5, 6))), SourcePlacement(7, -8))) } })
    }
    fun encoded(contents: JbContents) = ByteArrayOutputStream().also { JbArchive.write(it, contents) }.toByteArray()
    fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildList { while (true) { val entry = zip.nextEntry ?: break; add(entry.name to zip.readBytes()) } }
    }
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((name, bytes) in entries) {
            val entry = ZipEntry(name)
            if (name == "mimetype") { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong(); entry.crc = CRC32().also { it.update(bytes) }.value }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    /** Historical JBS bodies written from the published v1/v2 layout, never by the current encoder. */
    fun legacy(version: Int, record: StrokeRecord): ByteArray = ByteWriter().let { w ->
        w.bytes("JBS1".encodeToByteArray()); w.u16(version); w.u16(0)
        w.utf8(record.id); w.utf8(record.brushId); w.i64(record.seed); w.f32(record.smoothing); w.f32(record.screenPerDoc)
        if (version == 2) { w.u32(record.colorArgb.toLong() and 0xFFFF_FFFFL); w.f32(record.widthScale) }
        w.u32(record.samples.size.toLong())
        for (s in record.samples) { w.f64(s.timeMs); w.f32(s.x); w.f32(s.y); w.f32(s.pressure); w.u8(s.tool.ordinal) }
        w.toByteArray()
    }
    fun oldInk(): ByteArray {
        val source = contents().strokes.getValue("l" to "c").sortedBy { it.seq }
        val recordings = listOf(legacy(1, source[0]), legacy(2, source[1]))
        val strokes = ByteWriter().let { w -> w.u32(2); for (b in recordings) { w.u32(b.size.toLong()); w.bytes(b) }; w.toByteArray() }
        val text = """{"format":"joybrush.document","version":9,"id":"old","name":"Old ink",
          "boards":[{"id":"b","name":"Canvas","kind":"CANVAS","rect":{"x":0,"y":0,"w":64,"h":64}}],
          "layers":[{"id":"l","name":"Ink","kind":"INK","cels":[{"id":"c","strokesFile":"layers/l/c/strokes.jbs"}]}]}"""
        return zip(listOf("mimetype" to JB_MIMETYPE.toByteArray(), "document.json" to text.toByteArray(), "layers/l/c/strokes.jbs" to strokes))
    }
}

class LineArchiveTest {
    private fun read(bytes: ByteArray, resolver: ((String) -> BrushPreset?)? = null) = JbArchive.read(ByteArrayInputStream(bytes), resolver)
    private fun failure(bytes: ByteArray) = assertFailsWith<JbArchiveException> { read(bytes) }.message.orEmpty()
    private fun failure(contents: JbContents) = assertFailsWith<JbArchiveException> { LineArchiveFixture.encoded(contents) }.message.orEmpty()

    @Test fun mixedArchiveRetainsOperatorsRecordsAndFrozenBrushDefinition() {
        val expected = LineArchiveFixture.contents()
        val bytes = LineArchiveFixture.encoded(expected)
        var library = LineArchiveFixture.brush.copy(size = Param(100f))
        val back = read(bytes) { library }
        assertEquals(expected.doc, back.doc)
        assertEquals(expected.strokes.mapValues { it.value.sortedBy { r -> r.seq } }, back.strokes)
        for ((key, tile) in expected.tiles) assertContentEquals(tile, back.tiles.getValue(key))
        for ((key, tile) in expected.slabs) assertContentEquals(tile, back.slabs.getValue(key))
        for ((key, tile) in expected.looks) assertContentEquals(tile, back.looks.getValue(key))
        assertEquals(expected.brushes, back.brushes)
        library = library.copy(size = Param(200f))
        assertEquals(4f, read(bytes) { library }.brushes.getValue(LineArchiveFixture.brush.id).size.base)
    }

    @Test fun oldInkUsesGenuineV1V2BodiesAndRequiresExactResolverIds() {
        val bytes = LineArchiveFixture.oldInk()
        val before = bytes.copyOf()
        assertTrue(failure(bytes).contains("older drawing"))
        val seen = mutableListOf<String>()
        val back = read(bytes) { seen += it; LineArchiveFixture.brush }
        assertEquals(listOf(LineArchiveFixture.brush.id), seen)
        assertEquals(10, back.doc.version, "Historical INK migration does not invent v11 captured domains")
        assertEquals(LayerKind.PAINT, back.doc.layers.single().kind)
        assertEquals(listOf(1L, 2L), back.strokes.getValue("l" to "c").map { it.seq })
        assertTrue(back.strokes.values.flatten().all { !it.locked && it.groupId == null })
        assertEquals(StrokeCodec.DEFAULT_COLOR_ARGB, back.strokes.getValue("l" to "c")[0].colorArgb)
        assertEquals(1f, back.strokes.getValue("l" to "c")[0].widthScale)
        assertEquals(0x88334455.toInt(), back.strokes.getValue("l" to "c")[1].colorArgb)
        assertEquals(1.25f, back.strokes.getValue("l" to "c")[1].widthScale)
        assertEquals(back.brushes, read(LineArchiveFixture.encoded(back)).brushes)
        assertTrue(assertFailsWith<JbArchiveException> { read(bytes) { LineArchiveFixture.brush.copy(id = "other") } }.message.orEmpty().contains("requested"))
        assertTrue(assertFailsWith<JbArchiveException> { read(bytes) { LineArchiveFixture.brush.copy(size = Param(0f)) } }.message.orEmpty().contains("legacy brush"))
        assertContentEquals(before, bytes)
    }

    @Test fun slabSequenceSupportsLongBoundaryAndRefusesOverflowOrAliases() {
        val base = LineArchiveFixture.contents()
        val cel = base.doc.layers.single().cels.single().copy(strokesFile = null, looks = emptyList(),
            slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", Long.MAX_VALUE)))
        val current = base.copy(doc = base.doc.copy(brushes = emptyList(), layers = base.doc.layers.map { it.copy(cels = listOf(cel)) }),
            strokes = emptyMap(), brushes = emptyMap(), looks = emptyMap(), slabs = mapOf(SlabKey("l", "c", "0_0", Long.MAX_VALUE) to LineArchiveFixture.tile(20)))
        val bytes = LineArchiveFixture.encoded(current)
        assertContentEquals(LineArchiveFixture.tile(20), read(bytes).slabs.getValue(SlabKey("l", "c", "0_0", Long.MAX_VALUE)))
        val entries = LineArchiveFixture.entries(bytes)
        val overflow = entries.map { (name, payload) -> name.replace(".s${Long.MAX_VALUE}.", ".s9223372036854775808.") to payload }
        assertTrue(failure(LineArchiveFixture.zip(overflow)).contains("supported range"))
        val alias = "layers/l/c/0_0.s0${Long.MAX_VALUE}.rgba" to LineArchiveFixture.tile(30)
        assertTrue(failure(LineArchiveFixture.zip(entries + alias)).contains("twice"))
    }

    @Test fun missingExtraAndMalformedPayloadsFailBeforeWritingOrReading() {
        val c = LineArchiveFixture.contents()
        for (broken in listOf(c.copy(slabs = emptyMap()), c.copy(looks = emptyMap()), c.copy(brushes = emptyMap()),
            c.copy(slabs = c.slabs + (SlabKey("l", "c", "0_0", 9) to LineArchiveFixture.tile(1))),
            c.copy(looks = c.looks + (Triple("l", "c", "9_9") to LineArchiveFixture.tile(1))),
            c.copy(brushes = c.brushes.mapValues { it.value.copy(id = "wrong") }),
            c.copy(brushes = c.brushes.mapValues { it.value.copy(size = Param(0f)) }))) {
            val out = ByteArrayOutputStream()
            assertFailsWith<JbArchiveException> { JbArchive.write(out, broken) }
            assertEquals(0, out.size(), "validation must precede the first archive byte")
        }
        val entries = LineArchiveFixture.entries(LineArchiveFixture.encoded(c))
        for (suffix in listOf(".s2.rgba", ".look.rgba", ".json")) {
            val path = entries.first { it.first.endsWith(suffix) && it.first != "document.json" }.first
            assertTrue(failure(LineArchiveFixture.zip(entries.filterNot { it.first == path })).isNotBlank())
            assertTrue(failure(LineArchiveFixture.zip(entries.map { if (it.first == path) it.first to ByteArray(3) else it })).isNotBlank())
        }
        val duplicateBrush = entries.first { it.first.startsWith("brushes/") }
        assertTrue(failure(LineArchiveFixture.zip(entries + ("brushes/another.json" to duplicateBrush.second))).contains("twice"))
        assertTrue(failure(c.copy(strokes = c.strokes.mapValues { (_, records) -> records.map { it.copy(seq = 1) } })).contains("seq"))
    }

    @Test fun purePixelMediaArchiveKeepsExistingEntryNamesAndBytes() {
        val base = LineArchiveFixture.contents()
        val cel = Cel("c", tiles = listOf("0_0"), floatTiles = listOf("0_0"))
        val payload = ByteArray(MediaStores.tileBytes(TILE_SIZE, "p0")) { (it % 127).toByte() }
        val pixel = base.copy(doc = base.doc.copy(brushes = emptyList(), layers = base.doc.layers.map { it.copy(cels = listOf(cel)) }),
            strokes = emptyMap(), slabs = emptyMap(), looks = emptyMap(), brushes = emptyMap(),
            mediaTiles = mapOf(MediaTileKey("l", "c", "0_0", "p0") to payload,
                MediaTileKey("l", "c", "0_0", MediaStores.GROUND) to LineArchiveFixture.tile(17)))
        val old = LineArchiveFixture.entries(LineArchiveFixture.encoded(pixel.copy(doc = pixel.doc.copy(version = 9)))).toMap()
        val current = LineArchiveFixture.entries(LineArchiveFixture.encoded(pixel)).toMap()
        assertEquals(old.keys, current.keys)
        for (name in old.keys - "document.json") assertContentEquals(old.getValue(name), current.getValue(name), name)
        assertEquals(old.getValue("document.json").decodeToString().replace("\"version\": 9", "\"version\": 11"), current.getValue("document.json").decodeToString())
        val back = read(LineArchiveFixture.encoded(pixel))
        for ((key, tile) in pixel.mediaTiles) assertContentEquals(tile, back.mediaTiles.getValue(key))
        for ((key, tile) in pixel.tiles) assertContentEquals(tile, back.tiles.getValue(key))
    }
}
