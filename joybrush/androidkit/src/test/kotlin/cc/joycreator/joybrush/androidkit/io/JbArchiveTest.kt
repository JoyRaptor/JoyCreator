package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-0.08a. Half of these tests are the happy path and half are attempts to make a `.joybrush` lose
 * somebody's drawing: a name that climbs out of the folder, a size that lies, an archive cut in
 * half, and a save that fails halfway.
 *
 * NOTE: [JbContents] holds ByteArrays, so `==` on it compares arrays by REFERENCE and two
 * byte-identical drawings look different. Every comparison here goes through contentEquals, which is
 * also the only reason a real difference would be caught rather than hidden.
 *
 * This is a CLASS on purpose. JUnit 4 runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports a green build and executes nothing — which is worse than a red one.
 */
class JbArchiveTest {

    private val PAINT_LAYER = "L1"
    private val PAINT_CEL = "c1"
    private val INK_LAYER = "L2"
    private val INK_CEL = "c2"
    private val BOARD = "b1"
    private val STROKES_PATH = "layers/L2/c2/strokes.jbs"
    private val NO_MIMETYPE = "this archive has no \"mimetype\" entry"
    private val NOT_A_ZIP = "this archive cannot be read:"
    private val DISK_WENT_AWAY = "the disk went away"

    private val tileOrder = compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third })
    private val celOrder = compareBy<Pair<String, String>>({ it.first }, { it.second })

    // ---------------------------------------------------------------- fixtures

    private fun doc(): JbDocument = JbDocument(
        id = "doc-1",
        name = "Round trip",
        boards = listOf(
            Board(id = BOARD, name = "Board 1", kind = BoardKind.CANVAS, rect = RectPx(0, 0, 300, 300)),
        ),
        layers = listOf(
            Layer(
                id = PAINT_LAYER,
                name = "Paint",
                kind = LayerKind.PAINT,
                cels = listOf(Cel(id = PAINT_CEL, tiles = listOf("0_0", "1_0", "-1_-2"))),
            ),
            Layer(
                id = INK_LAYER,
                name = "Ink",
                kind = LayerKind.INK,
                cels = listOf(Cel(id = INK_CEL)),
            ),
        ),
        activeLayerId = PAINT_LAYER,
        activeBoardId = BOARD,
    )

    /** Every channel stated outright: a NaN does not survive `==` in a data class equals. */
    private fun strokeRecords(): List<StrokeRecord> = listOf(
        StrokeRecord(
            id = "s1",
            brushId = "brush-ink",
            seed = 7L,
            smoothing = 0.5f,
            screenPerDoc = 1.25f,
            samples = listOf(
                PenSample(1f, 2f, 0.0, 1f, 0.25f, -1.5f, 0.1f),
                PenSample(3f, 4f, 16.0, 0.5f, 0.5f, 1.5f, -0.2f),
            ),
        ),
        StrokeRecord(
            id = "s2",
            brushId = "brush-pencil",
            seed = 9L,
            smoothing = 0f,
            screenPerDoc = 0.5f,
            samples = listOf(PenSample(0f, 0f, 1.0, 1f, 0f, 0f, 0f)),
        ),
    )

    private fun tileBytes(seed: Int): ByteArray {
        val b = ByteArray(TILE_BYTES)
        for (i in b.indices) b[i] = ((i * 31 + seed * 7) and 0xFF).toByte()
        return b
    }

    private fun contents(): JbContents = JbContents(
        doc = doc(),
        tiles = mapOf(
            Triple(PAINT_LAYER, PAINT_CEL, "0_0") to tileBytes(1),
            Triple(PAINT_LAYER, PAINT_CEL, "1_0") to tileBytes(2),
            Triple(PAINT_LAYER, PAINT_CEL, "-1_-2") to tileBytes(3),
        ),
        strokes = mapOf(Pair(INK_LAYER, INK_CEL) to strokeRecords()),
    )

    /** The same document with an ink cel that says where its strokes are. */
    private fun docWithDeclaredStrokes(): JbDocument {
        val d = doc()
        return d.copy(
            layers = d.layers.map {
                if (it.id == INK_LAYER) it.copy(cels = listOf(Cel(id = INK_CEL, strokesFile = STROKES_PATH))) else it
            }
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun encoded(c: JbContents): ByteArray {
        val out = ByteArrayOutputStream()
        JbArchive.write(out, c)
        return out.toByteArray()
    }

    private fun read(archive: ByteArray): JbContents = JbArchive.read(ByteArrayInputStream(archive))

    private fun refusal(archive: ByteArray): String =
        assertFailsWith<JbArchiveException> { JbArchive.read(ByteArrayInputStream(archive)) }.message.orEmpty()

    /**
     * Builds an archive by hand, entry for entry, so a test can lie about one of them.
     *
     * `mimetype` is STORED because the reader requires it to be, and because a STORED entry keeps its
     * size in its own header — which is what lets a test corrupt a size in place.
     */
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

    /** Every entry a good archive holds, in the order it holds them. */
    private fun entriesOf(c: JbContents): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        out.add("mimetype" to JB_MIMETYPE.toByteArray(Charsets.US_ASCII))
        out.add("document.json" to DocJson.encode(c.doc).toByteArray(Charsets.UTF_8))
        for (key in c.tiles.keys.sortedWith(tileOrder)) {
            out.add("layers/${key.first}/${key.second}/${key.third}.rgba" to c.tiles.getValue(key))
        }
        for (cel in c.strokes.keys.sortedWith(celOrder)) {
            out.add("layers/${cel.first}/${cel.second}/strokes.jbs" to StrokeCodec.encodeAll(c.strokes.getValue(cel)))
        }
        return out
    }

    private fun tempDir(): File = Files.createTempDirectory("joybrush-jbtest").toFile()

    // ---------------------------------------------------------------- the archive

    @Test
    fun roundTripKeepsTheDocumentTheTilesAndTheStrokes() {
        val original = contents()
        val back = read(encoded(original))

        assertEquals(original.doc, back.doc)
        assertEquals(original.tiles.keys, back.tiles.keys)
        for (key in original.tiles.keys) {
            assertContentEquals(original.tiles.getValue(key), back.tiles.getValue(key), "tile $key came back different")
        }
        assertEquals(original.strokes, back.strokes)
        assertNull(back.thumbnailPng)
    }

    @Test
    fun roundTripKeepsTheThumbnail() {
        val png = ByteArray(64) { (it * 3).toByte() }
        val back = read(encoded(contents().copy(thumbnailPng = png)))
        assertContentEquals(png, assertNotNull(back.thumbnailPng))
    }

    /**
     * The same drawing written twice is the same length, so nothing is written in a different order
     * from one save to the next. The bytes are NOT compared: a zip carries a timestamp, and whether
     * the JDK fills one in is not this test's business.
     */
    @Test
    fun writingIsDeterministic() {
        assertEquals(encoded(contents()).size, encoded(contents()).size)
    }

    @Test
    fun mimetypeIsTheFirstEntryAndStored() {
        val zis = ZipInputStream(ByteArrayInputStream(encoded(contents())))
        val first = assertNotNull(zis.nextEntry)
        assertEquals("mimetype", first.name)
        assertEquals(ZipEntry.STORED, first.method)
        // Counted from the constant, never by hand: it is 22, and a hand-counted length in a test is
        // a test that fails for the wrong reason. The content assertion below is the real one.
        assertEquals(JB_MIMETYPE.length.toLong(), first.size)
        assertEquals(JB_MIMETYPE, zis.readBytes().decodeToString())
    }

    @Test
    fun theDocumentEntryIsDeflated() {
        val zis = ZipInputStream(ByteArrayInputStream(encoded(contents())))
        zis.nextEntry
        val second = assertNotNull(zis.nextEntry)
        assertEquals("document.json", second.name)
        assertEquals(ZipEntry.DEFLATED, second.method)
    }

    // ---------------------------------------------------------------- zip-slip

    @Test
    fun refusesEveryEntryNameThatCouldClimbOutOfTheFolder() {
        val hostile = listOf(
            "../escape",
            "..",
            "layers/../../escape",
            "layers/L1/../../escape.txt",
            "/absolute/escape",
            "\\absolute\\escape",
            "C:\\escape",
            "C:/escape",
            "\\\\server\\share\\escape",
            "layers\\..\\..\\escape",
            "layers/L1/c1/..\\\\escape",
            "layers//escape",
            // Windows trims trailing dots and spaces off a path part, so these are all ".." by the
            // time a desktop opens them, and a check for a plainly written ".." does not see them.
            "layers/.. /escape",
            "layers/.../escape",
            "layers/ .. /escape",
            // U+0001, written as a character rather than a source escape so that nothing about this
            // file's encoding can quietly turn the test into a different test.
            "layers/$PAINT_LAYER/$PAINT_CEL/" + 1.toChar() + "escape",
        )
        for (bad in hostile) {
            val message = refusal(rawArchive(entriesOf(contents()) + (bad to ByteArray(8) { 3.toByte() })))
            assertTrue(
                message.startsWith("entry \"$bad\" is refused:"),
                "\"$bad\" was refused, but with: $message",
            )
        }
    }

    @Test
    fun aRefusedNameNeverReachesTheArchiveOnTheWayOutEither() {
        // A layer id is a caller's string. It must not be able to write an entry name that climbs out.
        val d = doc().copy(
            layers = listOf(
                Layer(
                    id = "../evil",
                    name = "Evil",
                    kind = LayerKind.PAINT,
                    cels = listOf(Cel(id = PAINT_CEL, tiles = listOf("0_0"))),
                ),
                Layer(id = INK_LAYER, name = "Ink", kind = LayerKind.INK, cels = listOf(Cel(id = INK_CEL))),
            ),
            activeLayerId = null,
        )
        val hostile = JbContents(
            doc = d,
            tiles = mapOf(Triple("../evil", PAINT_CEL, "0_0") to tileBytes(1)),
            strokes = mapOf(Pair(INK_LAYER, INK_CEL) to strokeRecords()),
        )
        val out = ByteArrayOutputStream()
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(out, hostile) }.message.orEmpty()
        assertTrue(
            message.startsWith("entry name \"layers/../evil/$PAINT_CEL/0_0.rgba\" is refused:"),
            "unexpected message: $message",
        )
        // Refused before a single byte went out, so the caller's stream is untouched.
        assertEquals(0, out.size())
    }

    // ---------------------------------------------------------------- what read refuses

    @Test
    fun refusesAWrongSizeTile() {
        val archive = rawArchive(
            entriesOf(contents()).map {
                if (it.first == "layers/$PAINT_LAYER/$PAINT_CEL/0_0.rgba") it.first to ByteArray(100) else it
            }
        )
        assertEquals(
            "tile \"layers/$PAINT_LAYER/$PAINT_CEL/0_0.rgba\" is 100 bytes, a paint tile is 262144",
            refusal(archive),
        )
    }

    @Test
    fun refusesATileTheCelDoesNotList() {
        val archive = rawArchive(
            entriesOf(contents()) + listOf("layers/$PAINT_LAYER/$PAINT_CEL/9_9.rgba" to tileBytes(9))
        )
        val message = refusal(archive)
        assertTrue(message.contains("which the document does not list"), message)
    }

    @Test
    fun refusesATileWhoseNameIsNotATileKey() {
        val archive = rawArchive(
            entriesOf(contents()) + listOf("layers/$PAINT_LAYER/$PAINT_CEL/banana.rgba" to tileBytes(9))
        )
        assertEquals(
            "entry \"layers/$PAINT_LAYER/$PAINT_CEL/banana.rgba\" is a tile, but \"banana\" is not a \"tx_ty\" tile key",
            refusal(archive),
        )
    }

    @Test
    fun refusesAMissingTile() {
        val archive = rawArchive(entriesOf(contents()).filterNot { it.first == "layers/$PAINT_LAYER/$PAINT_CEL/0_0.rgba" })
        assertEquals(
            "the document lists tile \"0_0\" of layer \"$PAINT_LAYER\" cel \"$PAINT_CEL\", and this archive does not have it",
            refusal(archive),
        )
    }

    @Test
    fun refusesAnArchiveMissingTheStrokesItPromises() {
        val entries = entriesOf(contents())
            .filterNot { it.first == "document.json" || it.first.endsWith("strokes.jbs") }
            .toMutableList()
        entries.add(1, "document.json" to DocJson.encode(docWithDeclaredStrokes()).toByteArray(Charsets.UTF_8))
        assertEquals(
            "cel \"$INK_CEL\" of layer \"$INK_LAYER\" says its strokes are in \"$STROKES_PATH\", " +
                "and this archive does not have them",
            refusal(rawArchive(entries)),
        )
    }

    /**
     * A file with no bytes at all is out of stream before a local header can be found, so this one
     * is the JDK's refusal and ours only in the wording: `getNextEntry()` reads a 30-byte header and
     * an empty file is out of bytes, which arrives here as the IOException catch.
     */
    @Test
    fun refusesAZeroLengthFile() {
        val message = refusal(ByteArray(0))
        assertTrue(
            message.startsWith(NOT_A_ZIP) || message == NO_MIMETYPE,
            "a 0-byte file was refused, but with: $message",
        )
    }

    /**
     * Sixty-four bytes that are not a local header. Unlike the empty file there IS enough here to
     * read a header, so `getNextEntry()` finds no signature, returns null, and the refusal is
     * genuinely ours: there are no entries at all, so there is no mimetype.
     */
    @Test
    fun refusesSixtyFourBytesThatAreNotAZip() {
        val message = refusal(ByteArray(64))
        assertTrue(
            message == NO_MIMETYPE || message.startsWith(NOT_A_ZIP),
            "64 non-zip bytes were refused, but with: $message",
        )
    }

    @Test
    fun refusesAMimetypeThatIsNotFirst() {
        val entries = ArrayList(entriesOf(contents()))
        val mimetype = entries.removeAt(0)
        entries.add(1, mimetype)
        assertEquals(
            "the first entry must be \"mimetype\" but it is \"document.json\"",
            refusal(rawArchive(entries)),
        )
    }

    @Test
    fun refusesAMimetypeThatSaysSomethingElse() {
        val archive = rawArchive(
            entriesOf(contents()).map { if (it.first == "mimetype") it.first to "application/zip".toByteArray() else it }
        )
        assertEquals(
            "\"mimetype\" is not \"$JB_MIMETYPE\" (it is 15 bytes)",
            refusal(archive),
        )
    }

    @Test
    fun refusesAnArchiveWithNoDocument() {
        val archive = rawArchive(entriesOf(contents()).filterNot { it.first == "document.json" })
        assertEquals("this archive has no \"document.json\" entry", refusal(archive))
    }

    /**
     * Which tile wins? Neither: a second entry under the same name is a smuggle, not an update.
     *
     * The archive has to be spliced by hand because `ZipOutputStream` keeps its own set of names and
     * throws `ZipException: duplicate entry` before the second one reaches the disk — so this is one
     * thing the JDK writer will not let us build and another tool still can. ZipInputStream walks
     * local headers in order and never reads the central directory, so cutting the first entry out of
     * a finished archive and dropping it back in after the last local header is enough.
     */
    @Test
    fun refusesATwiceWrittenEntry() {
        val good = encoded(contents())
        val firstEntry = good.copyOfRange(0, 30 + "mimetype".length + JB_MIMETYPE.length)
        val centre = centralDirectoryOffset(good)
        val duplicated = good.copyOfRange(0, centre) + firstEntry + good.copyOfRange(centre, good.size)
        assertEquals("entry \"mimetype\" is in this archive twice", refusal(duplicated))
    }

    /** Where the central directory starts, which the last 22 bytes of every zip state exactly. */
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

    @Test
    fun refusesATruncatedArchive() {
        val bytes = encoded(contents())
        // Cut inside the tiles, so this is a half-inflated deflate stream rather than just a short file.
        val message = refusal(bytes.copyOf(bytes.size * 2 / 3))
        assertTrue(message.isNotEmpty())
    }

    /**
     * The mimetype is STORED, so its size sits in its own local header at offset 22, and that is the
     * number a reader would be tempted to allocate from. Lie about it, little-endian.
     *
     * `0x7FFFFFFF` and not `0xFFFFFFFF` on purpose: ZipInputStream reads that field into an int, so
     * all-ones arrives as -1 and is the JDK's own "size unknown" sentinel. `0x7FFFFFFF` is a real
     * number, so the JDK hands the entry over untouched and THIS guard is the one that refuses it,
     * before a single byte of the entry is read.
     */
    @Test
    fun refusesAnEntryThatLiesAboutItsSize() {
        val lying = encoded(contents()).copyOf()
        lying[22] = 0xFF.toByte()
        lying[23] = 0xFF.toByte()
        lying[24] = 0xFF.toByte()
        lying[25] = 0x7F.toByte()
        val message = refusal(lying)
        assertTrue(
            message.startsWith("entry \"mimetype\" says it is") || message.startsWith(NOT_A_ZIP),
            "the lying size was refused, but with: $message",
        )
    }

    @Test
    fun ignoresAnEntryItHasNeverHeardOf() {
        val archive = rawArchive(
            entriesOf(contents()) + listOf(
                "future/whatever.bin" to ByteArray(4096) { 7.toByte() },
                "layers" to ByteArray(0),
            )
        )
        val back = read(archive)
        assertEquals(contents().doc, back.doc)
        assertEquals(3, back.tiles.size)
        assertEquals(1, back.strokes.size)
        assertEquals(strokeRecords(), back.strokes.getValue(Pair(INK_LAYER, INK_CEL)))
    }

    @Test
    fun acceptsADirectoryEntry() {
        val entries = ArrayList(entriesOf(contents()))
        entries.add(1, "layers/" to ByteArray(0))
        assertEquals(3, read(rawArchive(entries)).tiles.size)
    }

    // ---------------------------------------------------------------- what write refuses

    @Test
    fun refusesToWriteATileOfTheWrongSize() {
        val c = contents()
        val bad = c.copy(tiles = c.tiles + (Triple(PAINT_LAYER, PAINT_CEL, "0_0") to ByteArray(10)))
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(ByteArrayOutputStream(), bad) }
            .message.orEmpty()
        assertEquals(
            "tile \"0_0\" of layer \"$PAINT_LAYER\" cel \"$PAINT_CEL\" is 10 bytes, a paint tile is 262144",
            message,
        )
    }

    @Test
    fun refusesToWriteATileTheCelDoesNotList() {
        val c = contents()
        val bad = c.copy(tiles = c.tiles + (Triple(PAINT_LAYER, PAINT_CEL, "9_9") to tileBytes(9)))
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(ByteArrayOutputStream(), bad) }
            .message.orEmpty()
        assertEquals("tile \"9_9\" is in layer \"$PAINT_LAYER\" cel \"$PAINT_CEL\", which does not list it", message)
    }

    @Test
    fun refusesToWriteATileForACelTheDocumentHasNotGot() {
        val c = contents()
        val bad = c.copy(tiles = mapOf(Triple(PAINT_LAYER, "no-such-cel", "0_0") to tileBytes(1)))
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(ByteArrayOutputStream(), bad) }
            .message.orEmpty()
        assertTrue(message.contains("no-such-cel"), message)
    }

    @Test
    fun refusesToWriteACelThatPromisesStrokesAndHasNone() {
        val bad = JbContents(
            doc = docWithDeclaredStrokes(),
            tiles = contents().tiles,
            strokes = emptyMap(),
        )
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(ByteArrayOutputStream(), bad) }
            .message.orEmpty()
        assertEquals(
            "cel \"$INK_CEL\" of layer \"$INK_LAYER\" says its strokes are in \"$STROKES_PATH\", and no strokes were given",
            message,
        )
    }

    @Test
    fun refusesToWriteADocumentThatIsNotValid() {
        val c = contents()
        val bad = c.copy(doc = c.doc.copy(layers = emptyList()))
        val message = assertFailsWith<JbArchiveException> { JbArchive.write(ByteArrayOutputStream(), bad) }
            .message.orEmpty()
        assertTrue(message.startsWith("this document cannot be saved:"), message)
        assertTrue(message.contains("no layers"), message)
    }

    // ---------------------------------------------------------------- saving to a file

    @Test
    fun savingTwiceKeepsTheFirstVersionInTheBakFile() {
        val dir = tempDir()
        try {
            val file = File(dir, "drawing.joybrush")
            JbArchive.save(file, contents())
            val first = file.readBytes()
            assertFalse(File(file.path + ".tmp").exists())
            assertFalse(File(file.path + ".bak").exists())

            val second = contents()
            JbArchive.save(file, second.copy(doc = second.doc.copy(name = "Second")))
            assertFalse(first.contentEquals(file.readBytes()))
            assertFalse(File(file.path + ".tmp").exists())

            assertEquals("Round trip", JbArchive.open(File(file.path + ".bak")).doc.name)
            assertEquals("Second", JbArchive.open(file).doc.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFailedSaveLeavesTheOldFileAndNoTemporary() {
        val dir = tempDir()
        try {
            val file = File(dir, "drawing.joybrush")
            val good = contents()
            JbArchive.save(file, good)
            val goodBytes = file.readBytes()

            val broken = good.copy(doc = good.doc.copy(layers = emptyList()))
            assertFailsWith<JbArchiveException> { JbArchive.save(file, broken) }

            assertContentEquals(goodBytes, file.readBytes(), "the good file was touched by a failed save")
            assertFalse(File(file.path + ".tmp").exists(), "a .tmp was left behind")
            assertFalse(File(file.path + ".bak").exists())
            assertEquals(contents().doc, JbArchive.open(file).doc)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savingSomewhereThatIsNotThereFailsWithoutTouchingAnything() {
        val dir = tempDir()
        try {
            assertFailsWith<JbArchiveException> {
                JbArchive.save(File(dir, "no-such-dir/x.joybrush"), contents())
            }
            assertFalse(File(dir, "no-such-dir").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openRefusesAFileThatIsNotThere() {
        val dir = tempDir()
        try {
            val message = assertFailsWith<JbArchiveException> { JbArchive.open(File(dir, "nothing.joybrush")) }
                .message.orEmpty()
            assertTrue(message.contains("no file at"), message)
        } finally {
            dir.deleteRecursively()
        }
    }

/**
 * A save that dies half way out of a stream is reported, and what it left behind is not a drawing.
 *
 * The stream is told to die on its fourth CALL, not after N bytes. A byte count would be a test of
 * how ZipOutputStream chunks its output and how well the tile filler compresses — neither is a promise
 * this file makes, and both would break the next time a buffer size changed, for no benefit. What is
 * a promise is: it is reported, and the bytes that landed are not a `.joybrush`.
 */
    @Test
    fun writeReportsAnOutputStreamThatFailsHalfWay() {
        val sink = ByteArrayOutputStream()
        var calls = 0
        val stream = object : OutputStream() {
            override fun write(one: Int) {
                calls++
                sink.write(one)
                if (calls >= 4) throw IOException(DISK_WENT_AWAY)
            }

            override fun write(bytes: ByteArray, off: Int, len: Int) {
                calls++
                sink.write(bytes, off, len)
                if (calls >= 4) throw IOException(DISK_WENT_AWAY)
            }
        }

        val message = assertFailsWith<JbArchiveException> { JbArchive.write(stream, contents()) }.message.orEmpty()
        assertEquals("the archive could not be written: $DISK_WENT_AWAY", message)

        // It really did die mid-archive rather than before starting.
        assertTrue(sink.size() > 0, "nothing was written, so this failed before it started")

        // And what landed is not a `.joybrush`: no central directory was ever finished.
        assertFailsWith<JbArchiveException> { read(sink.toByteArray()) }
    }
}
