package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The container: the big-endian cursor, the `8BIM` sections, the budgets, the `samp` tips and
 * PackBits.
 *
 * Every fixture here is a **hand-built byte string** rather than a download, so the suite needs no
 * network and every byte is accounted for in the code that writes it. Where a number is asserted it
 * is derived in the test: `MAX_* + 1` is written as `MAX_* + 1` and the derivation of the number it
 * must exceed is in the comment above it.
 *
 * The byte builders are top-level and `internal` because [AbrImportTest] builds the same containers
 * and a second copy of "how an Action Descriptor is written" would be a second thing to get wrong.
 */
class AbrReaderTest {

    // ---- 1. big-endian, not little ------------------------------------------------------------------

    /**
     * `.abr` is big-endian. The other reader in this package, `ByteReader`, is little-endian, and a
     * builder who reaches for it gets a file that parses cleanly and means something else — so the
     * endianness is pinned here rather than assumed.
     *
     * 0x01020304 read big-endian is 16 909 060; read little-endian it would be 67 305 985. The
     * assertion names the big-endian value, so a little-endian reader fails rather than passes.
     */
    @Test
    fun theContainerIsReadBigEndian() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val cur = AbrCursor(bytes, 0, bytes.size, "test")
        assertEquals(0x01020304L, cur.u32())
        assertEquals(0x0506, cur.u16())
        assertEquals(0, cur.remaining())
        // And a signed read, so the bounds do not depend on the sign: -1 as i32 is 0xFFFFFFFF.
        val signed = AbrCursor(byteArrayOf(-1, -1, -1, -1), 0, 4, "test")
        assertEquals(-1, signed.i32())
    }

    // ---- 2. every budget is a refusal -----------------------------------------------------------------

    /** `MAX_FILE_BYTES` + 1. 64 MiB is R40's number; one byte over is not a brush pack. */
    @Test
    fun aFileOverTheSizeCapIsRefusedAndNamed() {
        val oversized = ByteArray((AbrImport.MAX_FILE_BYTES + 1).toInt())
        val thrown = assertFailsWith<BrushException> { AbrReader.read(oversized) }
        assertTrue(thrown.message.orEmpty().contains("${AbrImport.MAX_FILE_BYTES}"), thrown.message.orEmpty())
        // The cap's own edge is not a refusal — it is the largest file this build reads.
        val atCap = ByteArray((AbrImport.MAX_FILE_BYTES).toInt())
        // …and a 64 MiB array of zeros is still not an .abr, which is the point: the cap is a cap and
        // not a substitute for the magic.
        assertFailsWith<BrushException> { AbrReader.read(atCap) }
    }

    /**
     * `MAX_SECTIONS` + 1 sections. 4 096 is a pack's whole budget; section 4 097 is not read.
     * Each section here is 12 bytes of header with an empty payload, so the fixture is ~49 kB.
     */
    @Test
    fun aFileWithTooManySectionsIsRefusedAndNamed() {
        val file = file(sections = AbrImport.MAX_SECTIONS + 1)
        val thrown = assertFailsWith<BrushException> { AbrReader.read(file) }
        assertTrue(thrown.message.orEmpty().contains("${AbrImport.MAX_SECTIONS} sections"), thrown.message.orEmpty())
        // One fewer is not a refusal on this rule — with a `desc`, because a file with no brushes in
        // it is refused for that and would mask the thing this test is about.
        AbrReader.read(file(sections = 4, desc = descSection(listOf(brush("x")))))
    }

    /** A section whose declared length runs past the end of the file. The declared length is a wish. */
    @Test
    fun aSectionLengthThatOverrunsTheFileIsRefused() {
        val out = Bytes()
        out.u16(6); out.u16(1)
        out.ascii("8BIM"); out.ascii("desc"); out.u32(1000)
        out.raw(ByteArray(10))                        // …with 10 bytes behind it
        val thrown = assertFailsWith<BrushException> { AbrReader.read(out.toByteArray()) }
        assertTrue(thrown.message.orEmpty().contains("truncated"), thrown.message.orEmpty())
    }

    /**
     * Descriptor depth `MAX_DESCRIPTOR_DEPTH` + 1. The root is depth 1, so a chain of 33 nested
     * `Objc` values reaches depth 33. A chain of 8 is a real file's depth and must read.
     */
    @Test
    fun aDescriptorTooDeepIsRefusedAndNamed() {
        val deep = nestedDescriptors(40)
        val thrown = assertFailsWith<BrushException> { AbrReader.read(file(desc = descSection(listOf(brush("deep", itemOf("K", deep)))))) }
        assertTrue(thrown.message.orEmpty().contains("nests deeper than ${AbrImport.MAX_DESCRIPTOR_DEPTH}"), thrown.message.orEmpty())
        // …and a real depth is not a refusal.
        val fine = AbrReader.read(file(desc = descSection(listOf(brush("fine", itemOf("K", nestedDescriptors(8)))))))
        assertEquals(1, fine.brushes.size)
    }

    /**
     * `MAX_DESCRIPTOR_NODES` + 1 entries in the tree. The budget counts one node per value, and the
     * cheapest value is a `bool` (a 4-character type and one byte), so 11 lists of 20 000 bools is
     * 220 011 nodes in about 1.1 MB — reached from inside the 10th list, and the fixture never
     * finishes being written because the reader stops there.
     */
    @Test
    fun aDescriptorTreeOverTheNodeBudgetIsRefusedAndNamed() {
        val lists = (0 until 11).map { itemOf("K$it", boolList(AbrImport.MAX_LIST_ITEMS)) }
        val thrown = assertFailsWith<BrushException> {
            AbrReader.read(file(desc = descSection(listOf(brush("wide", *lists.toTypedArray())))))
        }
        assertTrue(thrown.message.orEmpty().contains("${AbrImport.MAX_DESCRIPTOR_NODES} entries"), thrown.message.orEmpty())
        // One list of 20 000 is 20 001 nodes, well inside the budget.
        AbrReader.read(file(desc = descSection(listOf(brush("ok", itemOf("K", boolList(AbrImport.MAX_LIST_ITEMS)))))))
    }

    /** `MAX_LIST_ITEMS` + 1 entries in one list. */
    @Test
    fun aListWithTooManyItemsIsRefusedAndNamed() {
        val thrown = assertFailsWith<BrushException> {
            AbrReader.read(file(desc = descSection(listOf(brush("wide", itemOf("K", boolList(AbrImport.MAX_LIST_ITEMS + 1)))))))
        }
        assertTrue(thrown.message.orEmpty().contains("${AbrImport.MAX_LIST_ITEMS} items"), thrown.message.orEmpty())
        // The cap's own edge reads.
        AbrReader.read(file(desc = descSection(listOf(brush("ok", itemOf("K", boolList(AbrImport.MAX_LIST_ITEMS)))))))
    }

    /**
     * `MAX_STRING_BYTES` + 1 bytes of string. A string is a character count, so 32 769 characters is
     * 65 538 bytes, and the check is on the bytes because that is what a cursor consumes.
     */
    @Test
    fun aStringOverTheCapIsRefusedAndNamed() {
        val tooLong = "x".repeat(AbrImport.MAX_STRING_BYTES / 2 + 1)
        val thrown = assertFailsWith<BrushException> {
            AbrReader.read(file(desc = descSection(listOf(brush("s", itemOf("K", textV(tooLong)))))))
        }
        assertTrue(thrown.message.orEmpty().contains("a string is at most ${AbrImport.MAX_STRING_BYTES}"), thrown.message.orEmpty())
        // Half of that is fine.
        AbrReader.read(file(desc = descSection(listOf(brush("s", itemOf("K", textV("x".repeat(1000))))))))
    }

    /** `MAX_BRUSHES` + 1 entries in the `Brsh` list. 2 048 is R40's number. */
    @Test
    fun moreBrushesThanTheCapIsRefusedAndNamed() {
        val many = (0..AbrImport.MAX_BRUSHES).map { brush("brush $it") }
        val thrown = assertFailsWith<BrushException> { AbrReader.read(file(desc = descSection(many))) }
        assertTrue(thrown.message.orEmpty().contains("${AbrImport.MAX_BRUSHES} brushes"), thrown.message.orEmpty())
        // The cap's own edge reads — asserted through the importer, because a 2 048-brush file that
        // merely *read* would not prove the brushes were kept.
        assertEquals(AbrImport.MAX_BRUSHES, AbrReader.read(file(desc = descSection(many.dropLast(1)))).brushes.size)
    }

    /** A version this build does not read is a file fault, said in words (ag-psd throws on v1/v2 too). */
    @Test
    fun anUnknownVersionIsRefused() {
        for (version in listOf(1, 2, 3, 5, 11)) {
            val thrown = assertFailsWith<BrushException>("version $version") {
                AbrReader.read(file(version = version, desc = descSection(listOf(brush("x")))))
            }
            assertTrue(thrown.message.orEmpty().contains("version $version"), thrown.message.orEmpty())
        }
        for (version in listOf(6, 7, 9, 10)) {
            AbrReader.read(file(version = version, desc = descSection(listOf(brush("x")))))
        }
    }

    // ---- 3/4. a truncated file and a count that lies ---------------------------------------------------

    /** A file cut in half mid-`desc` says so and returns nothing (D1). */
    @Test
    fun aTruncatedFileIsARefusalAndNotAPartialBrush() {
        val whole = file(desc = descSection(listOf(brush("Round", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(40))))))))
        val half = whole.copyOfRange(0, whole.size / 2)
        val thrown = assertFailsWith<BrushException> { AbrReader.read(half) }
        assertTrue(thrown.message.orEmpty().contains("truncated"), thrown.message.orEmpty())
    }

    /**
     * A `Brsh` list that claims three brushes and holds two: the third is **refused**, and the two it
     * really has are not thrown away with it. A count is never believed, and refusing one entry is a
     * better answer than refusing the file.
     *
     * The list is hand-laid because [descSection] writes a count that agrees with its entries, and
     * this is the case where they do not: `VlLs`, count 3, and then two entries and nothing. There is
     * no length to bound the entries with — that is the point of JB-8.01b — so the third entry runs
     * into the end of the section and says so.
     */
    @Test
    fun aBrushListCountThatDisagreesWithTheSectionIsRefused() {
        val one = brush("a", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(11)))))
        val two = brush("b", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(22)))))
        val list = Bytes()
        list.ascii("VlLs"); list.u32(3); list.raw(one); list.raw(two)
        val desc = Bytes()
        desc.u32(16); desc.raw(descriptorBody("", "null", itemOf("Brsh", list.toByteArray())))
        val read = AbrReader.read(file(desc = desc.toByteArray()))
        assertEquals(2, read.brushes.filterIsInstance<AbrBrush.Read>().size, "${read.brushes}")
        val missing = read.brushes.filterIsInstance<AbrBrush.Unreadable>().single()
        assertTrue(missing.reason.contains("brush 3"), missing.reason)
        assertTrue(missing.reason.contains("truncated"), missing.reason)
    }

    /**
     * The sentence that goes with a refused brush after the first one: a real list's entries cannot be
     * skipped, so the brushes after a failure are not "lost" — they are *not findable*, and the message
     * says that rather than repeating a reason about a different brush.
     */
    @Test
    fun aBrushAfterAFailedEntrySaysTheOnesAfterItCannotBeFound() {
        val unknown = Bytes()
        unknown.ascii("wobb"); unknown.u32(0)
        val read = AbrReader.read(
            file(desc = descSection(listOf(
                brush("First", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20))))),
                brush("Second", itemOf("K", unknown.toByteArray())),
                brush("Third", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20))))),
            )))
        )
        assertEquals(1, read.brushes.filterIsInstance<AbrBrush.Read>().size, "${read.brushes}")
        val refused = read.brushes.filterIsInstance<AbrBrush.Unreadable>()
        assertEquals(2, refused.size, "${refused}")
        assertTrue(refused[0].reason.contains("brush 2"), refused[0].reason)
        assertTrue(
            refused[1].reason.contains("the brushes after it cannot be found without reading it"),
            refused[1].reason,
        )
        assertTrue(refused[1].reason.contains("brush 3"), refused[1].reason)
    }

    /**
     * A `VlLs` **has no byte length**, and that is asserted rather than assumed: the bytes after the
     * count are `Objc` (`0x4f626a63`, about 1.3 GB), which is exactly what a reader that believed a
     * length there would have to refuse. Every real `.abr` therefore depends on this.
     */
    @Test
    fun aListIsACountAndThenItsItemsAndHasNoLength() {
        // One brush, written the way the reader expects, must read. If a length were expected after the
        // count this would consume `Objc` and fail.
        val read = AbrReader.read(file(desc = descSection(listOf(brush("Round", itemOf("Brsh", computedTip()))))))
        assertEquals("Round", (read.brushes.single() as AbrBrush.Read).descriptor.text("Nm  "))
        // And the first four bytes of the list really are the first item's type tag, not a length:
        val list = listV(brush("Round"))
        assertEquals(0x4f626a63L, (((list[4].toInt() and 0xFF).toLong() shl 24) or
            ((list[5].toInt() and 0xFF).toLong() shl 16) or
            ((list[6].toInt() and 0xFF).toLong() shl 8) or
            (list[7].toInt() and 0xFF).toLong()), "the bytes after the count are not a length")
    }

    /** A signature that is not `8BIM` is not an `.abr`, and it says which four bytes it found. */
    @Test
    fun aFileThatIsNotAnAbrIsRefused() {
        val out = Bytes()
        out.u16(6); out.u16(1)
        out.ascii("8BIX"); out.ascii("desc"); out.u32(0)
        val thrown = assertFailsWith<BrushException> { AbrReader.read(out.toByteArray()) }
        assertTrue(thrown.message.orEmpty().contains("8BIX"), thrown.message.orEmpty())
    }

    // ---- 5. PackBits ------------------------------------------------------------------------------------

    /**
     * PackBits, derived from the encoding rules rather than from a file: a header byte `n` in
     * 0..127 means "copy the next `n + 1` bytes", a header of -1..-127 means "repeat the next byte
     * `1 - n` times", and -128 writes nothing.
     *
     * The three runs below are 10 literal bytes, then 5 × 0xAA, then 3 × 0x00 — 7 bytes of encoding
     * for 18 bytes of data, which is a compression ratio only a real tip manages.
     */
    @Test
    fun packBitsDecodesEveryRunType() {
        val encoded = byteArrayOf(
            9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,      // 10 literal bytes
            (1 - 5).toByte(), 0xAA.toByte(),        // 5 × 0xAA
            (1 - 3).toByte(), 0x00.toByte(),        // 3 × 0x00
            (-128).toByte(),                        // the no-op header
        )
        val out = ByteArray(18)
        val written = AbrReader.packBits(encoded, 0, encoded.size, out, 0, 18)
        assertEquals(18, written)
        val expected = ByteArray(18) { i ->
            when {
                i < 10 -> i.toByte()
                i < 15 -> 0xAA.toByte()
                else -> 0x00.toByte()
            }
        }
        assertContentEquals(expected, out)
    }

    /**
     * A 40 000-byte row, which is a real Photoshop tip and is far past what a run header can cover in
     * one go: 128-byte literal runs, 313 of them. The gray ramp is `i % 256`, so a decoder that
     * shifted a run boundary or read the header as unsigned fails on the first short run.
     */
    @Test
    fun packBitsDecodesALongRampAndRefusesATruncatedRun() {
        val ramp = ByteArray(40_000) { (it % 256).toByte() }
        val encoded = packLiterals(ramp)
        // 40 000 = 312 × 128 + 64, so 313 runs, and a run header is one byte: 40 000 + 313.
        assertEquals(313, 40_000 / 128 + 1)
        assertEquals(40_000 + 313, encoded.size)

        val out = ByteArray(40_000)
        assertEquals(40_000L, AbrReader.packBits(encoded, 0, encoded.size, out, 0, 40_000))
        assertContentEquals(ramp, out)

        // The same payload with its last run cut off whole — 1 header byte and 64 data bytes, the
        // last of the 313 runs — so the row can never be finished. Filling the rest with zeros would
        // be a tip the artist did not draw.
        val cut = encoded.copyOfRange(0, encoded.size - 65)
        val thrown = assertFailsWith<BrushException> {
            AbrReader.packBits(cut, 0, cut.size, ByteArray(40_000), 0, 40_000)
        }
        assertTrue(thrown.message.orEmpty().contains("truncated"), thrown.message.orEmpty())
    }

    /** A run whose value byte is missing is a refusal, and so is one that overruns the payload. */
    @Test
    fun packBitsRefusesEveryHalfWrittenRun() {
        // A repeat run of 5 with no value byte behind it.
        val noValue = byteArrayOf((1 - 5).toByte())
        assertFailsWith<BrushException> { AbrReader.packBits(noValue, 0, 1, ByteArray(5), 0, 5) }
        // A literal run of 5 with only 2 bytes behind it.
        val shortLiteral = byteArrayOf(4, 1, 2)
        val thrown = assertFailsWith<BrushException> {
            AbrReader.packBits(shortLiteral, 0, shortLiteral.size, ByteArray(5), 0, 5)
        }
        assertTrue(thrown.message.orEmpty().contains("overruns the payload"), thrown.message.orEmpty())
    }

    // ---- the `samp` section, read end to end ----------------------------------------------------------

    /**
     * A `samp` tip reads as a gray mean, and it reads PackBits on the way. The mean of the ramp
     * 0, 85, 170, 255 is (0 + 85 + 170 + 255) / (4 × 255) = 510 / 1020 = 0.5 exactly, so the
     * assertion below is a division, not an eyeball.
     *
     * The payload is a 4 × 4 tip: the table of four `u16` counts, then four PackBits rows. Each row is
     * the 4-byte ramp packed as one literal run, which is 1 header byte + 4 data bytes = 5, so the
     * payload is 4 × 2 + 4 × 5 = 28 bytes and that is what [AbrFile.storedBytes] must return.
     */
    @Test
    fun aSampledTipReadsAsItsGrayMean() {
        val rows = List(4) { packLiterals(RAMP) }
        val payload = packBitsPayload(rows)
        assertEquals(4 * 2 + 4 * 5, payload.size, "the row-count table plus four 5-byte rows")
        val file = file(
            desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID, itemOf("Angl", longV(0))))))),
            samp = sampSection(listOf(TipEntry(UUID, bottom = 4, right = 4, payload = payload))),
        )
        val read = AbrReader.read(file)
        val tip = read.tipsById.getValue(AbrReader.normaliseId(UUID))
        assertEquals(4L, tip.width)
        assertEquals(4L, tip.height)
        assertEquals(null, tip.error)
        assertEquals(8, tip.depth)
        assertEquals(1, tip.compression)
        assertEquals(0.5f, read.meanAlpha(tip), 1e-6f)
        // And the stored bytes are the file's own bytes, encoding and all.
        assertContentEquals(payload, read.storedBytes(tip))
    }

    /**
     * The entry's **own length** is what finds the next entry, and the padding inside it is not pixels.
     *
     * Two tips, each with 10 zero bytes of trailing padding behind its pixels — the two real entries in
     * `testdata-local` carry 9 and 10 such bytes. A reader that found the next entry at the end of the
     * pixels instead would read `00 00 00 00` as an entry length of zero, stop there, and lose the
     * second tip; a reader that put the padding into the image would give the wrong mean.
     *
     * The two tips have different bounds so that the assertions below can tell which is which.
     */
    @Test
    fun aSampEntryEndsAtItsOwnLengthAndThePaddingIsNotPixels() {
        val first = TipEntry(UUID, bottom = 2, right = 2, compression = 0, payload = ByteArray(4) { 255 }, trailing = 10)
        val other = "a1b2c3d4-1122-3344-5566-778899aabbdd"
        val second = TipEntry(other, bottom = 4, right = 4, compression = 0, payload = ByteArray(16) { 0 }, trailing = 3)
        val read = AbrReader.read(file(samp = sampSection(listOf(first, second))))
        assertEquals(2, read.tipsById.size, "${read.tipsById.keys}")
        // 255 × 4 / (255 × 4) = 1, and 0 × 16 / (255 × 16) = 0: the padding is not in either mean.
        assertEquals(1f, read.meanAlpha(read.tipsById.getValue(AbrReader.normaliseId(UUID))), 1e-6f)
        assertEquals(0f, read.meanAlpha(read.tipsById.getValue(AbrReader.normaliseId(other))), 1e-6f)
        // …and each stored region is exactly the pixels, 4 and 16 bytes, with no padding behind it.
        assertEquals(4, read.storedBytes(read.tipsById.getValue(AbrReader.normaliseId(UUID))).size)
        assertEquals(16, read.storedBytes(read.tipsById.getValue(AbrReader.normaliseId(other))).size)
    }

    /**
     * The PackBits row counts are a **table that comes first**, and this is the assertion that would
     * fail if they were read interleaved again.
     *
     * The fixture is two rows of four bytes, each row PackBits-encoded as one 5-byte literal run, so
     * the true payload is `0005 0005` then `04 00 01 02 03` twice = 18 bytes. Read as "the count is in
     * front of the row", the first count is `0x0400` = 1024, which no payload behind it can honour, and
     * the tip is refused; read as a table, both rows decode and the mean is exactly 0.5.
     */
    @Test
    fun packBitsRowCountsAreATableThatComesFirst() {
        val row = packLiterals(RAMP)
        val payload = packBitsPayload(List(2) { row })
        assertEquals(18, payload.size, "two u16 counts plus two 5-byte rows")
        val read = AbrReader.read(
            file(
                desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(TipEntry(UUID, bottom = 2, right = 4, payload = payload))),
            )
        )
        val tip = read.tipsById.getValue(AbrReader.normaliseId(UUID))
        assertEquals(null, tip.error, "${tip.error}")
        assertEquals(0.5f, read.meanAlpha(tip), 1e-6f)
    }

    /** A `depth` this build does not read is a refusal by name, never a number that looks right. */
    @Test
    fun aTipDepthOrCompressionThisBuildDoesNotReadIsRefusedByName() {
        // 2 × 2 tips. The raw payload is 2 × 2 × 1 = 4 bytes at 8 bit and 8 at 16 bit
        // (`width × height × bytesPerSample`). The PackBits payload is a table of two `u16` counts
        // (2 × 2 = 4 bytes) followed by two 3-byte literal runs (1 header byte + 2 data bytes).
        val gray = ByteArray(2) { 128.toByte() }
        val packed = packLiterals(gray)
        assertEquals(3, packed.size, "one literal run of 2 bytes is 1 header byte + 2 data bytes")
        fun with(depth: Int, compression: Int, tipError: (String?) -> Unit) {
            val stored = 4 * if (depth == 16) 2 else 1
            val payload = if (compression == 0) {
                ByteArray(stored) { gray[it % 2] }
            } else {
                packBitsPayload(List(2) { packed })
            }
            val read = AbrReader.read(
                file(
                    desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID))))),
                    samp = sampSection(
                        listOf(TipEntry(UUID, bottom = 2, right = 2, depth = depth, compression = compression, payload = payload))
                    ),
                )
            )
            tipError(read.tipsById.getValue(AbrReader.normaliseId(UUID)).error)
        }
        with(8, 0) { assertEquals(null, it) }
        with(8, 1) { assertEquals(null, it) }
        // 16-bit raw reads. The mean it produces is in the next test, which spells the bytes out.
        with(16, 0) { assertEquals(null, it) }
        with(32, 0) { assertTrue(it.orEmpty().contains("depth is 32"), it.orEmpty()) }
        // 1-bit is refused too (JB-8.01b): it stores ceil(width / 8) bytes per row, and reading it as one
        // byte per pixel gives an image eight times too wide that still decodes cleanly.
        with(1, 0) { assertTrue(it.orEmpty().contains("depth is 1"), it.orEmpty()) }
        // Compression code 2 is zlib in the newer files, and this row refuses it rather than
        // becoming the owner of an inflater.
        with(8, 2) { assertTrue(it.orEmpty().contains("compressed with code 2"), it.orEmpty()) }
    }

    /** A 16-bit raw tip: the high byte of each `u16` is the sample, and the mean says so. */
    @Test
    fun aSixteenBitTipContributesItsHighByte() {
        // 0x807F, 0x7F80, 0x0080, 0xFF00 read as samples 0x80, 0x7F, 0x00, 0xFF: the same ramp, reversed
        // halves. Their mean is (128 + 127 + 0 + 255) / (4 × 255) = 510 / 1020 = 0.5 exactly, and a
        // reader that kept both bytes would average the low bytes too and get something else.
        val payload = Bytes().also {
            it.u16(0x807F); it.u16(0x7F80); it.u16(0x0080); it.u16(0xFF00)
        }.toByteArray()
        val read = AbrReader.read(
            file(
                desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(TipEntry(UUID, bottom = 2, right = 2, depth = 16, compression = 0, payload = payload))),
            )
        )
        val tip = read.tipsById.getValue(AbrReader.normaliseId(UUID))
        assertEquals(null, tip.error, "${tip.error}")
        assertEquals(0.5f, read.meanAlpha(tip), 1e-6f)
    }

    /**
     * A sub-version this build does not read is a file fault in words (ag-psd `abr.ts:527` refuses it
     * too). The two sub-versions are the only two `samp` preambles this reader knows — 10 bytes and
     * 264 — and reading one as the other puts every tip's bounds 254 bytes off.
     */
    @Test
    fun anUnknownSubVersionIsRefusedAndNamed() {
        for (sub in listOf(0, 3, 9, 255)) {
            val thrown = assertFailsWith<BrushException>("sub-version $sub") {
                AbrReader.read(file(subVersion = sub, desc = descSection(listOf(brush("x")))))
            }
            assertTrue(thrown.message.orEmpty().contains("sub-version $sub"), thrown.message.orEmpty())
        }
        // Both real sub-versions read. Sub-version 2 is the 264-byte preamble, and both files in the
        // real corpus are version 6 or 10, sub-version 2.
        for (sub in listOf(1, 2)) {
            AbrReader.read(
                file(
                    subVersion = sub,
                    desc = descSection(listOf(brush("x", itemOf("Brsh", sampledTip(UUID))))),
                    samp = sampSection(listOf(TipEntry(UUID, bottom = 1, right = 1, compression = 0, payload = byteArrayOf(7), preamble = if (sub == 1) 10 else 264))),
                )
            )
        }
    }

    /**
     * A Unicode string is NUL-terminated and the terminator is counted, so every name in a real file
     * is one character longer than the name it holds. `"Round\0"` must read as `"Round"`, because a
     * name that still carries a NUL becomes a folder name with a NUL in it.
     *
     * A NUL in the *middle* is the artist's character and stays: only trailing ones go.
     */
    @Test
    fun aNulTerminatedNameIsReadWithoutItsTerminator() {
        assertEquals("Round", AbrReader.readUnicode(cursorOver(nulString("Round"))))
        assertEquals("", AbrReader.readUnicode(cursorOver(nulString(""))))
        // Two terminators, which is what a writer that pads to a boundary would produce.
        assertEquals("Round", AbrReader.readUnicode(cursorOver(nulString("Round", terminators = 2))))
        // A NUL in the middle is the artist's character and stays; only trailing ones go.
        val middle = "Ro${nulCharacter}und"
        assertEquals(middle, AbrReader.readUnicode(cursorOver(nulString(middle))))
        // And through the whole reader, because this is the one place ag-psd's `data.json` and this
        // build disagree about the same bytes: it says "Soft Round" for a name stored as "Soft Round\0".
        val read = AbrReader.read(
            file(desc = descSection(listOf(
                Bytes().also {
                    it.ascii("Objc")
                    it.raw(descriptorBody("", "null", itemOf("Nm  ", nulTerminatedText("Soft Round"))))
                }.toByteArray()
            )))
        )
        assertEquals("Soft Round", (read.brushes.single() as AbrBrush.Read).descriptor.text("Nm  "))
    }

    /** Spelled out, not written as a literal, so nothing can turn it into an invisible byte. */
    private val nulCharacter: String = "\\u0000"

    /**
     * A Unicode string as a `samp`/`desc` entry stores it: a `u32` character count **that includes the
     * NUL terminator**, then that many 16-bit units.
     */
    private fun nulString(s: String, terminators: Int = 1): ByteArray = Bytes().also {
        it.u32((s.length + terminators).toLong())
        for (c in s) it.u16(c.code)
        repeat(terminators) { it.u16(0) }
    }.toByteArray()

    /** The same string as a `TEXT` descriptor value, which is what an item's value has to be. */
    private fun nulTerminatedText(s: String, terminators: Int = 1): ByteArray =
        Bytes().also {
            it.ascii("TEXT")
            it.raw(nulString(s, terminators))
        }.toByteArray()

    private fun cursorOver(bytes: ByteArray): AbrCursor = AbrCursor(bytes, 0, bytes.size, "test")

    // ---- the descriptor layer ------------------------------------------------------------------------

    /**
     * Both documented ways of writing a four-character key: Photoshop writes `u32 0` and then the
     * four bytes, and the other form is a real length followed by them. Both must read the same.
     */
    @Test
    fun bothWaysOfWritingAKeyReadTheSame() {
        // The same one item, keyed the two documented ways: `u32 0` then four bytes, and a real
        // byte count then the bytes.
        val shortBrush = objcV("", "null", itemOf("Nm  ", textV("Round")))
        val longBrush = Bytes().also {
            it.ascii("Objc"); it.u32(0); it.raw(stringId("null")); it.u32(1)
            it.raw(keyWithLength("Nm  ")); it.raw(textV("Round"))
        }.toByteArray()
        for (body in listOf(shortBrush, longBrush)) {
            val read = AbrReader.read(file(desc = descSection(listOf(body))))
            val brush = read.brushes.single() as AbrBrush.Read
            assertEquals("Round", brush.descriptor.text("Nm  "))
        }
    }

    /**
     * An `'obj '`/`'prop'` reference: the value points at another item of **the same descriptor** by
     * index, and the reader resolves it. The index is out of range here, which is a refusal rather
     * than a null the caller would read as "this brush has no tip".
     */
    @Test
    fun aPropertyReferenceResolvesAndARefusedOneIsNamed() {
        // A descriptor whose `Brsh` is the brush itself: item 0 is the name, item 1 the tip, and a
        // third item points at item 1 by index. The reader must resolve the pointer, not return null.
        val tip = computedTip(itemOf("Dmtr", longV(40)))
        val pointer = Bytes()
        pointer.ascii("obj "); pointer.ascii("prop"); pointer.u32(1)
        val root = objcV(
            "",
            "null",
            itemOf("Nm  ", textV("R")),
            itemOf("Brsh", tip),
            itemOf("Pt2_", pointer.toByteArray()),
        )
        val read = AbrReader.read(file(desc = descSection(listOf(root))))
        val descriptor = (read.brushes.single() as AbrBrush.Read).descriptor
        assertEquals(40f, descriptor.descriptor("Pt2_")?.number("Dmtr"))
        // …and one that points past the end of the item list refuses the brush it is on, by name.
        val bad = Bytes()
        bad.ascii("obj "); bad.ascii("prop"); bad.u32(99)
        val broken = AbrReader.read(
            file(desc = descSection(listOf(brush("x", itemOf("K", bad.toByteArray())))))
        )
        val refused = broken.brushes.single() as AbrBrush.Unreadable
        assertTrue(refused.reason.contains("item 100"), refused.reason)
    }

    /** A descriptor type this build does not know refuses the brush it sits on, and names the type. */
    @Test
    fun anUnknownDescriptorTypeIsRefusedByName() {
        val odd = Bytes()
        odd.ascii("wobb"); odd.u32(0)
        val read = AbrReader.read(
            file(desc = descSection(listOf(brush("x", itemOf("K", odd.toByteArray())))))
        )
        val refused = read.brushes.single() as AbrBrush.Unreadable
        assertTrue(refused.reason.contains("wobb"), refused.reason)
    }

    companion object {
        /** The UUID the `samp` fixtures use, and the one `sampledData` points at. */
        const val UUID = "d2b1c3f4-1122-3344-5566-778899aabbcc"
    }
}

// ---- the byte builders ---------------------------------------------------------------------------------
//
// Everything a fixture is made of, in one place. The layouts follow the Adobe-documented Action
// Descriptor rules (R4 §B.1) and the `samp` entry layout, and every one of them is written out long
// hand so that a test failure points at a byte rather than at a helper.

/**
 * The gray ramp every tip fixture is made of: 0, 85, 170 and 255.
 *
 * 85 = 255 / 3 exactly, so the four values are 0, 1/3, 2/3 and 1 of full, and the mean of a row of
 * them is (0 + 85 + 170 + 255) / (4 × 255) = 510 / 1020 = **0.5 exactly**. Every hardness assertion in
 * these two files therefore rests on a division rather than on a number somebody eyeballed.
 */
internal val RAMP = byteArrayOf(0.toByte(), 85.toByte(), 170.toByte(), 255.toByte())

/** A growable big-endian byte sink. */
internal class Bytes {
    private val out = ArrayList<Byte>()

    val size: Int get() = out.size

    fun u8(v: Int) {
        out.add((v and 0xFF).toByte())
    }

    fun u16(v: Int) {
        u8(v shr 8); u8(v)
    }

    fun i32(v: Int) {
        u8(v shr 24); u8(v shr 16); u8(v shr 8); u8(v)
    }

    /** Big-endian `u32`: four `u8`s, not four `i32`s — one `i32` is already four bytes. */
    fun u32(v: Long) {
        u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); u8(v.toInt())
    }

    fun u64(v: Long) {
        for (i in 7 downTo 0) u8((v ushr (i * 8)).toInt())
    }

    fun ascii(s: String) {
        for (c in s) u8(c.code)
    }

    fun raw(b: ByteArray) {
        for (x in b) out.add(x)
    }

    fun toByteArray(): ByteArray = ByteArray(size) { out[it] }
}

/**
 * A key, in whichever of the two documented forms fits it.
 *
 * Four characters or fewer: a `u32` of 0, then exactly four bytes, space-padded — which is why
 * Photoshop's own four-letter keys are `H   `, `Mnm ` and `Cnt `. Anything longer carries a real byte
 * count, because a key is not always four characters: `sampledData`, `useTexture` and
 * `dtipsHardness` are all longer, and the reader's `readKey` reads both forms for the same reason
 * (it is the same field).
 */
internal fun key(k: String): ByteArray =
    if (k.length <= 4) Bytes().also { it.u32(0); it.ascii(k.padEnd(4, ' ')) }.toByteArray()
    else stringId(k)

/**
 * A **class or string** id, which is the other documented form: a real `u32` byte count and then the
 * bytes. `computedBrush`, `sampledBrush` and a pattern's UUID are written this way — a class name is
 * not four characters and is not padded to four.
 */
internal fun stringId(s: String): ByteArray = Bytes().also { it.u32(s.length.toLong()); it.ascii(s) }.toByteArray()

/** The other documented way: a real byte length, then the bytes. */
internal fun keyWithLength(k: String): ByteArray = Bytes().also { it.u32(k.length.toLong()); it.ascii(k) }.toByteArray()

/** One descriptor item: its key, then the value — which carries its own four-character type. */
internal fun itemOf(k: String, value: ByteArray): ByteArray =
    Bytes().also { it.raw(key(k)); it.raw(value) }.toByteArray()

internal fun longV(v: Long): ByteArray = Bytes().also { it.ascii("long"); it.u32(v) }.toByteArray()

/** A `doub`, from its IEEE 754 bits, so a fixture can say +Infinity or NaN exactly. */
internal fun doubBits(bits: Long): ByteArray = Bytes().also { it.ascii("doub"); it.u64(bits) }.toByteArray()

internal fun doubV(v: Double): ByteArray = doubBits(v.toRawBits())

internal fun boolV(v: Boolean): ByteArray = Bytes().also { it.ascii("bool"); it.u8(if (v) 1 else 0) }.toByteArray()

/** An `enum` value: the type, then the type id, then the value id. Both ids are string ids. */
internal fun enumV(type: String, value: String): ByteArray = Bytes().also {
    it.ascii("enum"); it.raw(stringId(type)); it.raw(stringId(value))
}.toByteArray()

/** A `TEXT` value: a character count, then that many big-endian 16-bit units. */
internal fun textV(s: String): ByteArray =
    Bytes().also { it.ascii("TEXT"); it.u32(s.length.toLong()); for (c in s) it.u16(c.code) }.toByteArray()

/**
 * A `VlLs`: an item count, then each item with its own four-character type in front of it.
 *
 * **There is no byte length after the count** (ag-psd `descriptor.ts:488-499`, and the four real
 * `.abr` files). The previous version of this builder wrote one, because the reader used to read one;
 * every fixture built from it was a file Photoshop could not have written. The section's own end is
 * the bound, and [AbrCursor] refuses to read past it.
 */
internal fun listV(vararg values: ByteArray): ByteArray {
    val out = Bytes()
    out.ascii("VlLs")
    out.u32(values.size.toLong())
    for (v in values) out.raw(v)
    return out.toByteArray()
}

/** A list of [n] `bool`s, the cheapest 5 bytes per node in the whole format. */
internal fun boolList(n: Int): ByteArray {
    val out = Bytes()
    out.ascii("VlLs")
    out.u32(n.toLong())
    val body = ByteArray(n * 5)
    for (i in 0 until n) {
        body[i * 5] = 'b'.code.toByte(); body[i * 5 + 1] = 'o'.code.toByte()
        body[i * 5 + 2] = 'o'.code.toByte(); body[i * 5 + 3] = 'l'.code.toByte()
        body[i * 5 + 4] = 1
    }
    out.raw(body)
    return out.toByteArray()
}

/**
 * A bare Action Descriptor — a name, a class id and items, with **no** four-character type in front.
 *
 * Only the `desc` section's root is written this way: it is a descriptor, not a value. Everything
 * that is a *value* goes through [objcV], which is this with `"Objc"` in front — and a list of
 * brushes is a list of values, so a brush is an `Objc`.
 */
internal fun descriptorBody(name: String, classId: String, vararg items: ByteArray): ByteArray = Bytes().also {
    it.u32(name.length.toLong()); for (c in name) it.u16(c.code)
    it.raw(stringId(classId))
    it.u32(items.size.toLong())
    for (i in items) it.raw(i)
}.toByteArray()

/** An `Objc` value: the type, then the descriptor. */
internal fun objcV(name: String, classId: String, vararg items: ByteArray): ByteArray =
    Bytes().also { it.ascii("Objc"); it.raw(descriptorBody(name, classId, *items)) }.toByteArray()

/** A brush preset descriptor: `Nm  ` first, then whatever else the brush carries. */
internal fun brush(name: String, vararg items: ByteArray): ByteArray =
    objcV("", "null", itemOf("Nm  ", textV(name)), *items)

internal fun computedTip(vararg items: ByteArray): ByteArray = objcV("", "computedBrush", *items)

internal fun sampledTip(uuid: String, vararg items: ByteArray): ByteArray =
    objcV("", "sampledBrush", itemOf("sampledData", textV(uuid)), *items)

/** A dynamics object in the shape every Photoshop dynamics object has. */
internal fun dynamics(control: Long, jitter: Long = 0, fadeSteps: Long = 0, minimum: Long = 100): ByteArray =
    objcV(
        "", "null",
        itemOf("bVTy", longV(control)),
        itemOf("fStp", longV(fadeSteps)),
        itemOf("jitter", longV(jitter)),
        itemOf("Mnm ", longV(minimum)),
    )

/** [depth] nested `Objc` values, so a test can walk the reader to a chosen depth. */
internal fun nestedDescriptors(depth: Int): ByteArray =
    if (depth <= 0) longV(7) else objcV("", "null", itemOf("K", nestedDescriptors(depth - 1)))

/** The `desc` section: a `u32` descriptor version, then a descriptor whose `Brsh` is the list. */
internal fun descSection(brushes: List<ByteArray>, version: Long = 16): ByteArray =
    Bytes().also {
        it.u32(version)
        it.raw(descriptorBody("", "null", itemOf("Brsh", listV(*brushes.toTypedArray()))))
    }.toByteArray()

/**
 * One `samp` entry, written the way a real one is (ag-psd `abr.ts:536-582`, checked byte for byte
 * against `testdata-local/abr/sample-and-pattern` and `…/special`):
 *
 *     u32 length, rounded up to a multiple of 4   ← everything behind it
 *     u8 idLength + id
 *     the sub-version's preamble: 10 bytes, or 264 for sub-version 2
 *     i32 top, left, bottom, right
 *     i16 depth, u8 compression
 *     the payload
 *
 * The previous version of this builder wrote no entry length and derived the bounds' position from a
 * hard-coded offset counted from the entry's first byte, and wrote `depth` and `compression` as two
 * `u32`s. All three were wrong, and no real file could have been produced by them.
 */
internal class TipEntry(
    val id: String,
    val top: Int = 0,
    val left: Int = 0,
    val bottom: Int,
    val right: Int,
    val depth: Int = 8,
    val compression: Int = 1,
    val payload: ByteArray,
    /** Preamble bytes counted from the first byte after the id: 10, or 264 for sub-version 2. */
    val preamble: Int = 10,
    /**
     * Padding written behind the payload, still inside the declared length. The two real entries carry
     * 9 and 10 such bytes, so a fixture may too; the reader must find the next entry at the declared
     * length and not at the end of the payload.
     */
    val trailing: Int = 0,
)

internal fun sampSection(entries: List<TipEntry>): ByteArray = Bytes().also { section ->
    for (e in entries) {
        val body = Bytes()
        body.u8(e.id.length); body.ascii(e.id)
        body.raw(ByteArray(e.preamble))
        body.i32(e.top); body.i32(e.left); body.i32(e.bottom); body.i32(e.right)
        body.u16(e.depth); body.u8(e.compression)
        body.raw(e.payload)
        body.raw(ByteArray(e.trailing))
        var length = body.size.toLong()
        while ((length and 3L) != 0L) length++
        section.u32(length)
        section.raw(body.toByteArray())
        // The padding the length promises but the body does not carry, so the fixture is honest about
        // where the next entry starts.
        section.raw(ByteArray((length - body.size).toInt()))
    }
}.toByteArray()

/**
 * A PackBits `samp` payload: **`height` u16 row byte counts, then that many bytes per row.**
 *
 * The counts form a table that comes *before* any row. The previous version of this builder put each
 * row's count immediately in front of that row, which is the interleaved form; against the real files
 * that reads the first row's tail as the second row's header and every row after the first decodes as
 * noise. Checked against `sample-and-pattern`, whose table is `0008 000f 0016 001b …` and whose 105
 * rows all decode to exactly 112 bytes.
 */
internal fun packBitsPayload(rows: List<ByteArray>): ByteArray = Bytes().also { out ->
    for (r in rows) out.u16(r.size)
    for (r in rows) out.raw(r)
}.toByteArray()

/**
 * The bytes of one `patt` record — **deliberately not modelled**.
 *
 * JB-8.01b stopped parsing the `patt` section (the spec for that row says to skip it by its declared
 * length; patterns are JB-1.05d's business, not this row's), and the previous version of this builder
 * invented a layout for it — a `u32` length then a `u8`-length id — exactly as the reader used to
 * invent one. No real `.abr` record has been shown to have that shape, so a fixture that writes it is
 * a second lie about the format in the same file as the lie this row removed. What a fixture needs is
 * only "a `patt` section this build skips", so this is [body] with a recognisable id in it.
 */
internal fun patternRecord(id: String, body: ByteArray = ByteArray(0)): ByteArray = Bytes().also {
    it.raw(keyWithLength(id)); it.raw(body)
}.toByteArray()

/** A `patt` section: the records concatenated, and skipped whole by the reader. */
internal fun pattSection(records: List<ByteArray>): ByteArray = Bytes().also {
    for (r in records) it.raw(r)
}.toByteArray()

/**
 * A whole `.abr`: a `u16` version, a `u16` sub-version, then `8BIM` sections, each padded to a
 * four-byte boundary. [sections] adds that many empty `VMsk` sections, for the section-count budget.
 */
internal fun file(
    version: Int = 6,
    subVersion: Int = 1,
    desc: ByteArray? = null,
    samp: ByteArray? = null,
    patt: ByteArray? = null,
    sections: Int = 0,
): ByteArray = Bytes().also {
    it.u16(version); it.u16(subVersion)
    fun section(name: String, payload: ByteArray) {
        it.ascii("8BIM"); it.ascii(name); it.u32(payload.size.toLong()); it.raw(payload)
        while (it.size % 4 != 0) it.u8(0)
    }
    samp?.let { section("samp", it) }
    desc?.let { section("desc", it) }
    patt?.let { section("patt", it) }
    repeat(sections) { section("VMsk", ByteArray(0)) }
}.toByteArray()

/**
 * PackBits literal runs of at most 128 bytes, which is the longest a run header can express: a header
 * of `n` in 0..127 copies `n + 1` bytes, so 127 is the largest and 128 bytes is the longest run.
 */
internal fun packLiterals(bytes: ByteArray): ByteArray = Bytes().also { out ->
    var i = 0
    while (i < bytes.size) {
        val take = minOf(128, bytes.size - i)
        out.u8(take - 1)
        for (k in 0 until take) out.u8(bytes[i + k].toInt() and 0xFF)
        i += take
    }
}.toByteArray()
