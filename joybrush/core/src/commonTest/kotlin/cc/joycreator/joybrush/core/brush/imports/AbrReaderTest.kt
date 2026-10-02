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

    /**
     * JB-8.01b: ag-psd reads the minor version only on the 6/7/9/10 path and refuses anything but 1
     * and 2 — the `samp` preamble is 10 bytes for 1 and 264 for 2, so any other number has no
     * layout to read. Both real minors still read.
     */
    @Test
    fun anUnknownMinorVersionIsRefused() {
        for (minor in listOf(0, 3, 9)) {
            val thrown = assertFailsWith<BrushException>("minor $minor") {
                AbrReader.read(file(subVersion = minor, desc = descSection(listOf(brush("x")))))
            }
            assertTrue(thrown.message.orEmpty().contains("minor version $minor"), thrown.message.orEmpty())
        }
        for (minor in listOf(1, 2)) {
            AbrReader.read(file(subVersion = minor, desc = descSection(listOf(brush("x")))))
        }
    }

    /**
     * JB-8.01b: `patt` and `phry` are skipped by their declared length — patterns are not imported
     * (JB-1.05d) and the preset group hierarchy is not read, but neither may stop the file. The
     * payloads here are deliberately not parseable as anything.
     */
    @Test
    fun pattAndPhrySectionsAreSkippedByLength() {
        val garbage = ByteArray(64) { (it * 7 + 3).toByte() }
        val read = AbrReader.read(
            file(
                desc = descSection(listOf(brush("Round", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(40))))))),
                patt = garbage,
                phry = garbage,
            )
        )
        assertEquals(1, read.brushes.size)
        assertTrue(read.brushes.single() is AbrBrush.Read)
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
     * better answer than refusing the file. (JB-8.01b: the hand-laid list carries no byte length,
     * because a real one has none — the third entry simply runs out of section.)
     */
    @Test
    fun aBrushListCountThatDisagreesWithTheSectionIsRefused() {
        val one = brush("a", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(11)))))
        val two = brush("b", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(22)))))
        // A hand-laid list: count 3, only the two entries that are there behind it.
        val body = Bytes()
        body.raw(one); body.raw(two)
        val list = Bytes()
        list.ascii("VlLs"); list.u32(3); list.raw(body.toByteArray())
        val desc = Bytes()
        desc.u32(16); desc.raw(descriptorBody("", "null", itemOf("Brsh", list.toByteArray())))
        val read = AbrReader.read(file(desc = desc.toByteArray()))
        assertEquals(2, read.brushes.filterIsInstance<AbrBrush.Read>().size, "${read.brushes}")
        val missing = read.brushes.filterIsInstance<AbrBrush.Unreadable>().single()
        assertTrue(missing.reason.contains("brush 3"), missing.reason)
        assertTrue(missing.reason.contains("truncated"), missing.reason)
    }

    /**
     * JB-8.01b: a `VlLs` is a count and then that many self-describing values — no byte length is
     * read, so the bytes after the last item still parse. Two `long`s and then a third item prove
     * the reader stopped after exactly two values rather than eating four bytes as a length.
     */
    @Test
    fun aListReadsExactlyItsCountAndTheBytesAfterItStillParse() {
        val read = AbrReader.read(
            file(desc = descSection(listOf(brush(
                "L",
                itemOf("Key1", listV(longV(11), longV(22))),
                itemOf("Aftr", longV(7)),
            ))))
        )
        val descriptor = (read.brushes.single() as AbrBrush.Read).descriptor
        assertEquals(listOf(11.0, 22.0), descriptor.list("Key1")?.map { (it as AbrValue.Num).value })
        assertEquals(7f, descriptor.number("Aftr"))
    }

    /**
     * JB-8.01b: after a failed entry the rest cannot be found without reading it, so they are
     * reported unreadable with that said outright. Count 4, two entries behind it: brush 3 carries
     * the truncation, brush 4 carries the consequence.
     */
    @Test
    fun brushesAfterAFailedEntrySayTheyCannotBeFound() {
        val one = brush("a", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(11)))))
        val two = brush("b", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(22)))))
        val body = Bytes()
        body.raw(one); body.raw(two)
        val list = Bytes()
        list.ascii("VlLs"); list.u32(4); list.raw(body.toByteArray())
        val desc = Bytes()
        desc.u32(16); desc.raw(descriptorBody("", "null", itemOf("Brsh", list.toByteArray())))
        val read = AbrReader.read(file(desc = desc.toByteArray()))
        assertEquals(2, read.brushes.filterIsInstance<AbrBrush.Read>().size)
        val missing = read.brushes.filterIsInstance<AbrBrush.Unreadable>()
        assertEquals(2, missing.size)
        assertTrue(missing[0].reason.contains("brush 3"), missing[0].reason)
        assertTrue(missing[0].reason.contains("truncated"), missing[0].reason)
        assertTrue(missing[1].reason.contains("brush 4"), missing[1].reason)
        assertTrue(missing[1].reason.contains("cannot be found without reading it"), missing[1].reason)
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
     */
    @Test
    fun aSampledTipReadsAsItsGrayMean() {
        val gray = RAMP
        val row = packLiterals(gray)
        // Table-first: one `u16` row byte count per row, then the PackBits rows back to back —
        // the layout real files use (JB-8.01b).
        val payload = Bytes()
        repeat(4) { payload.u16(row.size) }
        repeat(4) { payload.raw(row) }
        val file = file(
            desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID, itemOf("Angl", longV(0))))))),
            samp = sampSection(listOf(TipEntry(UUID, bottom = 4, right = 4, payload = payload.toByteArray()))),
        )
        val read = AbrReader.read(file)
        val tip = read.tipsById.getValue(AbrReader.normaliseId(UUID))
        assertEquals(4L, tip.width)
        assertEquals(4L, tip.height)
        assertEquals(null, tip.error)
        assertEquals(0.5f, read.meanAlpha(tip), 1e-6f)
        // And the stored bytes are the file's own bytes, encoding and all.
        assertContentEquals(payload.toByteArray(), read.storedBytes(tip))
    }

    /**
     * JB-8.01b: a minor-2 `samp` entry carries 264 bytes of preamble and an `i16` depth with a
     * one-byte compression — the shape every real file in the corpus has. The same 4 × 4 ramp
     * reads as a gray mean of exactly 0.5.
     */
    @Test
    fun aMinor2SampledTipReadsAsItsGrayMean() {
        val row = packLiterals(RAMP)
        val payload = Bytes()
        repeat(4) { payload.u16(row.size) }
        repeat(4) { payload.raw(row) }
        val file = file(
            subVersion = 2,
            desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID))))),
            samp = sampSection(
                listOf(TipEntry(UUID, bottom = 4, right = 4, payload = payload.toByteArray())),
                subVersion = 2,
            ),
        )
        val read = AbrReader.read(file)
        val tip = read.tipsById.getValue(AbrReader.normaliseId(UUID))
        assertEquals(4L, tip.width)
        assertEquals(4L, tip.height)
        assertEquals(null, tip.error)
        assertEquals(8, tip.depth)
        assertEquals(1, tip.compression)
        assertEquals(0.5f, read.meanAlpha(tip), 1e-6f)
    }

    /** A `depth` this build does not read is a refusal by name, never a number that looks right. */
    @Test
    fun aTipDepthOrCompressionThisBuildDoesNotReadIsRefusedByName() {
        // 2 × 2 tips, so the raw payload is 2 × 2 = 4 bytes and the PackBits one is a `u16` count
        // plus one literal run of the same 4 bytes.
        val gray = ByteArray(2) { 128.toByte() }
        fun with(depth: Int, compression: Int, tipError: (String?) -> Unit) {
            val payload = Bytes()
            if (compression == 0) {
                for (i in 0 until 4) payload.u8(gray[i % 2].toInt())
            } else {
                val row = packLiterals(gray)
                repeat(2) { payload.u16(row.size) }
                repeat(2) { payload.raw(row) }
            }
            val read = AbrReader.read(
                file(
                    desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID))))),
                    samp = sampSection(
                        listOf(TipEntry(UUID, bottom = 2, right = 2, depth = depth, compression = compression, payload = payload.toByteArray()))
                    ),
                )
            )
            tipError(read.tipsById.getValue(AbrReader.normaliseId(UUID)).error)
        }
        with(8, 0) { assertEquals(null, it) }
        with(8, 1) { assertEquals(null, it) }
        with(32, 0) { assertTrue(it.orEmpty().contains("depth is 32"), it.orEmpty()) }
        // 16-bit RLE has no oracle — ag-psd throws "not implemented" — so it is refused by name.
        // The 2 × 2 PackBits payload builds the same way; the refusal comes before any pixel is read.
        with(16, 1) { assertTrue(it.orEmpty().contains("RLE"), it.orEmpty()) }
        // Compression code 2 is zlib in the newer files, and this row refuses it rather than
        // becoming the owner of an inflater.
        with(8, 2) { assertTrue(it.orEmpty().contains("compressed with code 2"), it.orEmpty()) }
    }

    /**
     * JB-8.01b: 16-bit raw tips read — two bytes per sample, big-endian. A 2 × 2 tip of the samples
     * 0x1234, 0x5678, 0x9ABC, 0xDEF0 is 8 stored bytes, kept byte for byte.
     */
    @Test
    fun aSixteenBitRawTipReads() {
        val payload = byteArrayOf(
            0x12, 0x34, 0x56, 0x78,
            0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
        )
        val read = AbrReader.read(
            file(
                desc = descSection(listOf(brush("S", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(TipEntry(UUID, bottom = 2, right = 2, depth = 16, compression = 0, payload = payload))),
            )
        )
        val tip = read.tipsById.getValue(AbrReader.normaliseId(UUID))
        assertEquals(2L, tip.width)
        assertEquals(2L, tip.height)
        assertEquals(null, tip.error)
        assertContentEquals(payload, read.storedBytes(tip))
    }

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

/** A `VlLs`: an item count, then each item with its own type. There is no byte length in a real file. */
internal fun listV(vararg values: ByteArray): ByteArray {
    val body = Bytes()
    for (v in values) body.raw(v)
    return Bytes().also {
        it.ascii("VlLs"); it.u32(values.size.toLong()); it.raw(body.toByteArray())
    }.toByteArray()
}

/** A list of [n] `bool`s, the cheapest 5 bytes per node in the whole format. */
internal fun boolList(n: Int): ByteArray {
    val body = ByteArray(n * 5)
    for (i in 0 until n) {
        body[i * 5] = 'b'.code.toByte(); body[i * 5 + 1] = 'o'.code.toByte()
        body[i * 5 + 2] = 'o'.code.toByte(); body[i * 5 + 3] = 'l'.code.toByte()
        body[i * 5 + 4] = 1
    }
    return Bytes().also {
        it.ascii("VlLs"); it.u32(n.toLong()); it.raw(body)
    }.toByteArray()
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

/** One `samp` entry: a `u32` length, a Pascal id, a skip, four `i32` bounds, `i16` depth, `u8` compression, pixels. */
internal class TipEntry(
    val id: String,
    val top: Int = 0,
    val left: Int = 0,
    val bottom: Int,
    val right: Int,
    val depth: Int = 8,
    val compression: Int = 1,
    val payload: ByteArray,
)

internal fun sampSection(entries: List<TipEntry>, subVersion: Int = 1): ByteArray = Bytes().also { section ->
    for (e in entries) {
        val entry = Bytes()
        entry.u8(e.id.length); entry.ascii(e.id)
        repeat(if (subVersion == 1) 10 else 264) { entry.u8(0) }
        entry.i32(e.top); entry.i32(e.left); entry.i32(e.bottom); entry.i32(e.right)
        entry.u16(e.depth); entry.u8(e.compression)
        entry.raw(e.payload)
        val length = entry.size
        while (entry.size % 4 != 0) entry.u8(0)
        section.u32(length.toLong())
        section.raw(entry.toByteArray())
    }
}.toByteArray()

/** One `patt` record: a `u32` length, a `u8`-length id, then whatever the record holds. */
internal fun patternRecord(id: String, body: ByteArray = ByteArray(0)): ByteArray = Bytes().also {
    it.u32((1 + id.length + body.size).toLong())
    it.u8(id.length); it.ascii(id); it.raw(body)
}.toByteArray()

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
    phry: ByteArray? = null,
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
    phry?.let { section("phry", it) }
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
