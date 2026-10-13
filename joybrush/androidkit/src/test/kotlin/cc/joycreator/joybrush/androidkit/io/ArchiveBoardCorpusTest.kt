package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DOC_VERSION
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.hasPixels
import cc.joycreator.joybrush.core.doc.RegionFrames
import cc.joycreator.joybrush.core.doc.SpriteGrid
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.HalfFloat
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-NOW-28: actual archive roundtrip/corruption matrix on a small supported mixed-board document.
 *
 * The corpus is one drawing with three board kinds (CANVAS image, two-frame region ANIMATION, SPRITE
 * with a grid), a region PAINT layer whose two frame cels share the tile key "0_0" with different
 * exact byte patterns, a static INK layer with stroke records, a static PAINT layer (JB-2.40) with one float
 * store plus its look tile, and a thumbnail. Every payload goes through the real [JbArchive.write]
 * and [JbArchive.read]; nothing here mocks the reader or writer.
 *
 * The corruption half mutates real ZIP entries by hand (duplicate/truncate/misname tile and media
 * entries, rewritten document metadata) and asserts the exact documented acceptance/refusal wording
 * from [JbArchive] and [cc.joycreator.joybrush.core.doc.DocOps]/`DocJson`. No future JB-2.40
 * requirements are asserted. Plain JVM only (`java.util.zip`, `java.io`), like [JbArchive] itself,
 * so this runs under the current androidkit JVM tests with no Android runtime.
 *
 * This is a CLASS on purpose, for the reason [JbArchiveTest] gives: JUnit 4 runs methods on an
 * instance, so top-level `@Test` functions would compile green and execute nothing.
 */
class ArchiveBoardCorpusTest {

    private val boardImg = "b-img"
    private val boardAnim = "b-anim"
    private val boardSprite = "b-sprite"
    private val frame0 = "f0"
    private val frame1 = "f1"
    private val paintLayer = "L-paint"
    private val sharedCel = "c-shared"
    private val frameCel0 = "c-f0"
    private val frameCel1 = "c-f1"
    private val inkLayer = "L-ink"
    private val inkCel = "c-i0"
    private val mediaLayer = "L-media"
    private val mediaCel = "c-m0"

    private val tileF0 = "layers/L-paint/c-f0/0_0.rgba"
    private val tileF1 = "layers/L-paint/c-f1/0_0.rgba"
    private val mediaEntry = "layers/L-media/c-m0/0_0.p0.f16"
    private val strokesEntry = "layers/L-ink/c-i0/strokes.jbs"

    // ---------------------------------------------------------------- fixtures

    /** Three board kinds in one valid document: image, two-frame region animation, sprite grid. */
    private fun mixedDoc(): JbDocument = JbDocument(
        id = "doc-board-corpus",
        name = "Board corpus",
        boards = listOf(
            Board(id = boardImg, name = "Image", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 128, 128)),
            Board(
                id = boardAnim, name = "Walk", kind = BoardKind.ANIMATION, rect = RectPx(256, 0, 64, 64),
                fps = 12f, frames = listOf(Frame(frame0, 1), Frame(frame1, 2)), currentFrameId = frame0,
            ),
            // 2 cols x 1 row of 32x32 cells exactly fills the 64x32 rect.
            Board(
                id = boardSprite, name = "Sprites", kind = BoardKind.SPRITE, rect = RectPx(0, 256, 64, 32),
                grid = SpriteGrid(cols = 2, rows = 1, cellW = 32, cellH = 32),
            ),
        ),
        layers = listOf(
            Layer(
                id = paintLayer, name = "Paint", kind = LayerKind.PAINT,
                sharedCelId = sharedCel,
                cels = listOf(
                    Cel(id = sharedCel, tiles = listOf("0_0")),
                    Cel(id = frameCel0, tiles = listOf("0_0")),
                    Cel(id = frameCel1, tiles = listOf("0_0")),
                ),
                regions = listOf(RegionFrames(boardAnim, mapOf(frame0 to frameCel0, frame1 to frameCel1))),
            ),
            Layer(
                id = inkLayer, name = "Ink", kind = LayerKind.PAINT,
                cels = listOf(Cel(id = inkCel, strokesFile = strokesEntry)),
            ),
            // JB-2.40: MEDIA is legacy and refused at version 9; media state lives on a PAINT layer.
            Layer(
                id = mediaLayer, name = "Wash", kind = LayerKind.PAINT,
                cels = listOf(Cel(id = mediaCel, tiles = listOf("0_0"), floatTiles = listOf("0_0"))),
            ),
        ),
        activeLayerId = paintLayer,
        activeBoardId = boardImg,
        brushes = listOf("brush-ink", "brush-pencil"),
    )

    /** Same boards with raster layers only: the shape [BoardSnapshot.capture] accepts. */
    private fun rasterDoc(): JbDocument {
        val full = mixedDoc()
        return full.copy(brushes = emptyList(), layers = full.layers.filter { it.id != inkLayer })
    }

    private fun tileBytes(seed: Int): ByteArray {
        val b = ByteArray(TILE_BYTES)
        for (i in b.indices) b[i] = ((i * 31 + seed * 7) and 0xFF).toByte()
        return b
    }

    /** Real half floats: a wash holding 0.2 in the first channel of every texel. */
    private fun mediaState(): ByteArray =
        HalfFloat.encode(FloatArray(TILE_SIZE * TILE_SIZE * 4) { if (it % 4 == 0) 0.2f else 0.001f * (it % 7) })

    private fun strokeRecords(): List<StrokeRecord> = listOf(
        StrokeRecord(
            id = "i1", seq = 1, brushId = "brush-ink", seed = 7L, smoothing = 0.5f, screenPerDoc = 1.25f,
            samples = listOf(
                PenSample(1f, 2f, 0.0, 1f, 0.25f, -1.5f, 0.1f),
                PenSample(3f, 4f, 16.0, 0.5f, 0.5f, 1.5f, -0.2f),
            ),
        ),
        StrokeRecord(
            id = "i2", seq = 2, brushId = "brush-pencil", seed = 9L, smoothing = 0f, screenPerDoc = 0.5f,
            samples = listOf(PenSample(0f, 0f, 1.0, 1f, 0f, 0f, 0f)),
        ),
    )

    private fun contents(): JbContents = JbContents(
        doc = mixedDoc(),
        tiles = mapOf(
            Triple(paintLayer, sharedCel, "0_0") to tileBytes(11),
            Triple(paintLayer, frameCel0, "0_0") to tileBytes(21),
            Triple(paintLayer, frameCel1, "0_0") to tileBytes(22),
            Triple(mediaLayer, mediaCel, "0_0") to tileBytes(33),
        ),
        strokes = mapOf(Pair(inkLayer, inkCel) to strokeRecords()),
        thumbnailPng = ByteArray(32) { (it * 5 + 1).toByte() },
        mediaTiles = mapOf(MediaTileKey(mediaLayer, mediaCel, "0_0", "p0") to mediaState()),
        brushes = listOf("brush-ink", "brush-pencil").associateWith { BrushPreset(id = it, name = it, size = Param(4f)) },
    )

    // ---------------------------------------------------------------- helpers

    private fun encoded(c: JbContents): ByteArray {
        val out = ByteArrayOutputStream()
        JbArchive.write(out, c)
        return out.toByteArray()
    }

    private fun read(archive: ByteArray): JbContents = JbArchive.read(ByteArrayInputStream(archive))

    private fun refusal(archive: ByteArray): String =
        assertFailsWith<JbArchiveException> { JbArchive.read(ByteArrayInputStream(archive)) }.message.orEmpty()

    private val tileOrder = compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third })
    private val celOrder = compareBy<Pair<String, String>>({ it.first }, { it.second })
    private val mediaOrder =
        compareBy<MediaTileKey>({ it.layerId }, { it.celId }, { it.key }, { it.store })

    /** Every entry a good archive holds, in writer order. `mimetype` is STORED so sizes stay in-header. */
    private fun entriesOf(c: JbContents): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        out.add("mimetype" to JB_MIMETYPE.toByteArray(Charsets.US_ASCII))
        out.add("document.json" to DocJson.encode(c.doc).toByteArray(Charsets.UTF_8))
        for (key in c.tiles.keys.sortedWith(tileOrder)) {
            out.add("layers/${key.first}/${key.second}/${key.third}.rgba" to c.tiles.getValue(key))
        }
        for (key in c.mediaTiles.keys.sortedWith(mediaOrder)) {
            out.add("layers/${key.layerId}/${key.celId}/${key.key}.${key.store}${MediaStores.EXT}" to c.mediaTiles.getValue(key))
        }
        for (cel in c.strokes.keys.sortedWith(celOrder)) {
            out.add("layers/${cel.first}/${cel.second}/strokes.jbs" to StrokeCodec.encodeAll(c.strokes.getValue(cel)))
        }
        for ((index, id) in c.doc.brushes.sorted().withIndex()) {
            out.add("brushes/${(index + 1).toString().padStart(3, '0')}-$id.json" to BrushJson.encode(c.brushes.getValue(id)).toByteArray(Charsets.UTF_8))
        }
        val thumb = c.thumbnailPng
        if (thumb != null) out.add("thumbnail.png" to thumb)
        return out
    }

    private fun rawArchive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val zos = ZipOutputStream(out)
        for ((name, bytes) in entries) {
            val entry = ZipEntry(name)
            if (name == "mimetype") {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            zos.putNextEntry(entry)
            zos.write(bytes)
            zos.closeEntry()
        }
        zos.finish()
        return out.toByteArray()
    }

    /** Rewrites an archive with another `document.json`, keeping every other entry byte-identical. */
    private fun replaceDocument(archive: ByteArray, doc: JbDocument): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(archive)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries += e.name to (
                    if (e.name == "document.json") DocJson.encode(doc).toByteArray(Charsets.UTF_8)
                    else z.readBytes()
                    )
            }
        }
        return rawArchive(entries)
    }

    /** Where the central directory starts: the last 22 bytes of a comment-free zip say exactly. */
    private fun centralDirectoryOffset(archive: ByteArray): Int {
        val eocd = archive.size - 22
        val expected = intArrayOf(0x50, 0x4B, 0x05, 0x06)
        for (i in expected.indices) {
            check(archive[eocd + i].toInt() and 0xFF == expected[i]) { "not a zip written without a comment" }
        }
        return (archive[eocd + 16].toInt() and 0xFF) or
            ((archive[eocd + 17].toInt() and 0xFF) shl 8) or
            ((archive[eocd + 18].toInt() and 0xFF) shl 16) or
            ((archive[eocd + 19].toInt() and 0xFF) shl 24)
    }

    /**
     * Splices a second local file header for [name] just before the central directory. Hand-crafted
     * STORED bytes (never `ZipOutputStream`, which refuses duplicates before they reach the disk),
     * so the reader meets the name twice. `ZipInputStream` walks local headers and never consults
     * the shifted central directory, which is exactly why the duplicate is detected there.
     */
    private fun withDuplicatedTileEntry(archive: ByteArray, name: String, payload: ByteArray): ByteArray {
        val centre = centralDirectoryOffset(archive)
        val header = ByteArrayOutputStream()
        fun le(v: Int) {
            header.write(v and 0xFF); header.write((v ushr 8) and 0xFF)
            header.write((v ushr 16) and 0xFF); header.write((v ushr 24) and 0xFF)
        }
        fun leShort(v: Int) {
            header.write(v and 0xFF); header.write((v ushr 8) and 0xFF)
        }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(payload) }.value
        le(0x04034b50)
        leShort(20); leShort(0); leShort(0); leShort(0); leShort(0)
        le(crc.toInt()); le(payload.size); le(payload.size)
        leShort(nameBytes.size); leShort(0)
        header.write(nameBytes)
        header.write(payload)
        val dup = header.toByteArray()
        return archive.copyOfRange(0, centre) + dup + archive.copyOfRange(centre, archive.size)
    }

    // ---------------------------------------------------------------- roundtrip

    @Test
    fun mixedBoardRoundTripKeepsEveryCelTileStrokesStateAndThumbnail() {
        val original = contents()
        // The corpus can actually tell its frame cels apart: same key, different exact bytes.
        assertFalse(
            original.tiles.getValue(Triple(paintLayer, frameCel0, "0_0"))
                .contentEquals(original.tiles.getValue(Triple(paintLayer, frameCel1, "0_0"))),
            "frame cels share a tile key but must not share bytes",
        )

        val back = read(encoded(original))

        // Boards survive whole: animation frames with holds/fps/cursor plus the sprite grid.
        assertEquals(original.doc, back.doc)
        assertEquals(original.tiles.keys, back.tiles.keys)
        for (key in original.tiles.keys) {
            assertContentEquals(original.tiles.getValue(key), back.tiles.getValue(key), "tile $key came back different")
        }
        // No leak across frames: each frame cel reopened its own exact pattern, not its sibling's.
        val backF0 = back.tiles.getValue(Triple(paintLayer, frameCel0, "0_0"))
        val backF1 = back.tiles.getValue(Triple(paintLayer, frameCel1, "0_0"))
        assertContentEquals(original.tiles.getValue(Triple(paintLayer, frameCel0, "0_0")), backF0)
        assertContentEquals(original.tiles.getValue(Triple(paintLayer, frameCel1, "0_0")), backF1)
        assertFalse(backF0.contentEquals(backF1), "frame 1 reopened frame 0's tile")
        assertFalse(
            back.tiles.getValue(Triple(paintLayer, sharedCel, "0_0")).contentEquals(backF0),
            "shared canvas reopened a frame tile",
        )
        assertEquals(strokeRecords(), back.strokes.getValue(Pair(inkLayer, inkCel)))
        assertEquals(original.mediaTiles.keys, back.mediaTiles.keys)
        for ((key, value) in original.mediaTiles) {
            assertContentEquals(value, back.mediaTiles.getValue(key), "media $key came back different")
        }
        assertEquals(0.2f, HalfFloat.decode(back.mediaTiles.getValue(MediaTileKey(mediaLayer, mediaCel, "0_0", "p0")))[0], 1e-3f)
        assertContentEquals(requireNotNull(original.thumbnailPng), requireNotNull(back.thumbnailPng))
    }

    @Test
    fun snapshotOfMixedBoardsCapturesEveryFrameAndReopens() {
        val doc = rasterDoc()
        val paints = mapOf(
            Pair(paintLayer, sharedCel) to tileBytes(11),
            Pair(paintLayer, frameCel0) to tileBytes(21),
            Pair(paintLayer, frameCel1) to tileBytes(22),
            Pair(mediaLayer, mediaCel) to tileBytes(33),
        )
        val state = mediaState()
        // Physical cel readback, independent of the displayed board/frame: every cel names its own tile.
        val snapshot = BoardSnapshot.capture(
            doc,
            keys = { layer, cel ->
                if (Pair(layer, cel) in paints) listOf(Tiles.key(0, 0)) else emptyList()
            },
            read = { layer, cel, _ -> paints[Pair(layer, cel)] },
            mediaKeys = { layer, _ -> if (layer == mediaLayer) mapOf("p0" to listOf(Tiles.key(0, 0))) else emptyMap() },
            mediaRead = { layer, _, store, _ -> if (layer == mediaLayer && store == "p0") state else null },
        )
        assertEquals(
            setOf(
                Triple(paintLayer, sharedCel, "0_0"),
                Triple(paintLayer, frameCel0, "0_0"),
                Triple(paintLayer, frameCel1, "0_0"),
                Triple(mediaLayer, mediaCel, "0_0"),
            ),
            snapshot.tiles.keys,
        )
        val back = read(encoded(snapshot))
        assertEquals(doc.boards, back.doc.boards)
        for ((cel, bytes) in paints) {
            assertContentEquals(bytes, back.tiles.getValue(Triple(cel.first, cel.second, "0_0")), "cel $cel came back different")
        }
        assertFalse(
            back.tiles.getValue(Triple(paintLayer, frameCel0, "0_0"))
                .contentEquals(back.tiles.getValue(Triple(paintLayer, frameCel1, "0_0"))),
            "snapshot frames leaked across cels",
        )
        assertContentEquals(state, back.mediaTiles.getValue(MediaTileKey(mediaLayer, mediaCel, "0_0", "p0")))
    }

    @Test
    fun snapshotRefusesOrdinaryLayerLineMetadataInsteadOfDroppingStrokes() {
        var reads = 0
        val message = assertFailsWith<IllegalArgumentException> {
            BoardSnapshot.capture(mixedDoc(), { _, _ -> reads++; emptyList() }, { _, _, _ -> reads++; null })
        }.message.orEmpty()
        assertTrue(message.contains("cannot be exported by this renderer yet"), message)
        assertTrue(message.contains(inkLayer) && message.contains(inkCel), message)
        assertEquals(0, reads, "snapshot capability refusal must precede payload lookup")
    }

    // ---------------------------------------------------------------- corruption matrix

    @Test
    fun duplicateTileEntryIsRefused() {
        val duplicated = withDuplicatedTileEntry(encoded(contents()), tileF0, tileBytes(99))
        assertEquals("entry \"$tileF0\" is in this archive twice", refusal(duplicated))
    }

    @Test
    fun truncatedArchiveIsRefused() {
        val bytes = encoded(contents())
        // Cut inside the tiles, so this is a half-inflated deflate stream rather than a short file.
        assertTrue(refusal(bytes.copyOf(bytes.size * 2 / 3)).isNotEmpty())
    }

    @Test
    fun truncatedMediaTileIsRefusedWithExactSize() {
        // 524288 is 256x256 texels of 8-byte half-float RGBA, the documented rest size of one store tile.
        val archive = rawArchive(
            entriesOf(contents()).map { if (it.first == mediaEntry) it.first to ByteArray(100) { 7 } else it },
        )
        assertEquals("media tile \"$mediaEntry\" is 100 bytes, a media tile is 524288", refusal(archive))
    }

    @Test
    fun misnamedTilePathIsRefused() {
        val short = "layers/L-paint/c-f1.rgba"
        val archive = rawArchive(
            entriesOf(contents()).map { if (it.first == tileF1) short to it.second else it },
        )
        assertEquals(
            "entry \"$short\" is a tile path, but not layers/<layer>/<cel>/<tx>_<ty>.rgba",
            refusal(archive),
        )
    }

    @Test
    fun unknownMediaStoreIsRefusedOnRead() {
        val mud = "layers/L-media/c-m0/0_0.mud.f16"
        val archive = rawArchive(
            entriesOf(contents()).map { if (it.first == mediaEntry) mud to it.second else it },
        )
        assertEquals(
            "entry \"$mud\" is media state for \"mud\", which is not one of p0, p1, paper, w0, w1",
            refusal(archive),
        )
    }

    @Test
    fun misnamedStrokesPathIsRefused() {
        val short = "layers/L-ink/strokes.jbs"
        val archive = rawArchive(
            entriesOf(contents()).map { if (it.first == strokesEntry) short to it.second else it },
        )
        assertEquals(
            "entry \"$short\" is a strokes path, but not layers/<layer>/<cel>/strokes.jbs",
            refusal(archive),
        )
    }

    // ---------------------------------------------------------------- document metadata

    @Test
    fun unknownDocumentKeyIsRefusedByName() {
        val json = DocJson.encode(contents().doc)
        val withFuture = (json.dropLast(1) + ",\n  \"futureSetting\": true\n}").toByteArray(Charsets.UTF_8)
        val archive = rawArchive(
            entriesOf(contents()).map { if (it.first == "document.json") it.first to withFuture else it },
        )
        assertEquals(
            "\"document.json\" cannot be read: this file has \"futureSetting\", " +
                "which this version of Joy Brush does not know, at \$",
            refusal(archive),
        )
    }

    @Test
    fun newerDocumentVersionIsRefusedWithActionableMessage() {
        val json = DocJson.encode(contents().doc).replace("\"version\": $DOC_VERSION", "\"version\": 999")
        val archive = rawArchive(
            entriesOf(contents()).map {
                if (it.first == "document.json") it.first to json.toByteArray(Charsets.UTF_8) else it
            },
        )
        assertEquals(
            "the document in this archive is not valid: document is from a newer Joy Brush " +
                "(version 999, this build reads $DOC_VERSION)",
            refusal(archive),
        )
    }

    @Test
    fun invalidDocumentJsonIsRefused() {
        val archive = rawArchive(
            entriesOf(contents()).map {
                if (it.first == "document.json") it.first to "{ not json".toByteArray(Charsets.UTF_8) else it
            },
        )
        val message = refusal(archive)
        assertTrue(message.startsWith("\"document.json\" cannot be read:"), message)
    }

    @Test
    fun swappedDocumentThatRenamesALayerRefusesMissingTiles() {
        val full = mixedDoc()
        // The active layer is renamed with it: validation refuses a dangling active layer first, and
        // this case is about the missing tiles, not that.
        val renamed = full.copy(
            layers = full.layers.map { if (it.id == paintLayer) it.copy(id = "L-renamed") else it },
            activeLayerId = "L-renamed",
        )
        // The reader checks the document's listed tiles, so the renamed layer's own cel
        // reports the absence before the orphaned files are reached.
        val message = refusal(replaceDocument(encoded(contents()), renamed))
        assertEquals(
            "the document lists tile \"0_0\" of layer \"L-renamed\" cel \"$sharedCel\", " +
                "and this archive does not have it",
            message,
        )
    }
}
