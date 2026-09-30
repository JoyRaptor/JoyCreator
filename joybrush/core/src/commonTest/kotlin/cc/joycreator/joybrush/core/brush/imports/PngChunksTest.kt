package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PngChunks.kt`, the reader JB-8.02 landed for [KritaImport] and for Procreate's `IHDR` read.
 *
 * **Every fixture here is a concatenation of hand-written chunks**, so the test proves the *reader* rather
 * than a writer: there is no PNG library in `commonTest` and none is wanted, because a library that wrote
 * these files would be a second implementation of the thing under test. The CRCs are computed rather than
 * left as zeroes so that a fixture really is a structurally valid PNG — although the reader does not check
 * them, and says so in its own comment.
 *
 * The seven cases are the spec's, and case 4 is the one worth reading twice: **the reader's contract is
 * narrower than the importer's.** `readPngTextChunks` returns a `zTXt` with `compressed = true` and an
 * **empty** `text` and it *keeps reading the chunks after it*; refusing it in words is
 * `KritaImport.readKppFile`'s job. "The reader tried to decode it" and "the caller forgot to check" are two
 * different failures and have to produce two different messages, so this file pins the reader's half.
 */
class PngChunksTest {

    // ---- 1. signature, chunk walk, order --------------------------------------------------------------

    /**
     * Three text chunks come back in file order with their keyword and payload intact, and a chunk of an
     * unknown type with a **zero length** is stepped over rather than treated as a fault.
     *
     * The zero-length `prVt` is a real thing: PNG reserves the type `prVt` for "may be private" and some
     * writers emit it empty, and a reader that tried to find a NUL in an empty payload would call that a
     * malformed text chunk. The `zzZz` with four bytes of payload is the other half of the same rule: an
     * unknown type is skipped **by its own declared length**, which is the one use of a length a file states
     * that cannot invent a number.
     */
    @Test
    fun threeTextChunksComeBackInFileOrderAndAnUnknownTypeIsSkipped() {
        val bytes = png(
            chunk("prVt", ByteArray(0)),
            text("version", "0.5"),
            chunk("zzZz", byteArrayOf(1, 2, 3, 4)),
            text("Title", "Ink Default"),
            text("Software", "Krita 4.2.6"),
        )
        val chunks = readPngTextChunks(bytes)
        assertEquals(3, chunks.size, "$chunks")
        assertEquals(listOf("version", "Title", "Software"), chunks.map { it.keyword })
        assertEquals(listOf("0.5", "Ink Default", "Krita 4.2.6"), chunks.map { it.text })
        assertTrue(chunks.none { it.compressed }, "$chunks")
    }

    // ---- 2. a chunk length that overruns the file ----------------------------------------------------

    /**
     * Decision 8. A chunk that claims more bytes than the file has left is a refusal, and the message names
     * **both** the chunk type and the length, because "bad PNG" is not a sentence a person can act on.
     *
     * The file is 8 of signature + 8 of chunk header + 4 of payload = 20 bytes, and the chunk declares 100.
     */
    @Test
    fun aChunkLengthThatOverrunsTheFileRefusesNamingTheTypeAndTheLength() {
        val bytes = png(chunk("IHDR", ByteArray(4))) + be32(100) + "tEXt".toByteArray() + ByteArray(4)
        val e = assertFailsWith<BrushException> { readPngTextChunks(bytes) }
        val why = e.message ?: ""
        assertTrue(why.contains("tEXt"), why)
        assertTrue(why.contains("100"), why)
        assertTrue(why.contains("truncated"), why)
    }

    // ---- 3. the high bit, read as a signed Int --------------------------------------------------------

    /**
     * Decision 8's other half, and the one that is a real bug rather than a nicety: PNG's chunk length is
     * a `u32`, so `0xFFFFFFFF` is 4 294 967 295 and **not** −1. Read as a signed 32-bit `Int` and added to
     * the position, it walks the reader backwards into a header it then reads as a length.
     *
     * The assertion is on **the refusal message** and not on "it did not crash": a test that only checked
     * `assertFailsWith` would pass on a reader that threw `ArrayIndexOutOfBoundsException` from a bad
     * addition, which is the opposite of what this case is for.
     */
    @Test
    fun aLengthWithTheHighBitSetRefusesRatherThanBecomingMinusOne() {
        val bytes = png(chunk("IHDR", ByteArray(4))) + be32(0xFFFFFFFF) + "tEXt".toByteArray() + ByteArray(4)
        val e = assertFailsWith<BrushException> { readPngTextChunks(bytes) }
        val why = e.message ?: ""
        // The number in the message is the *unsigned* reading. 4 294 967 295 is the whole point: a message
        // saying "-1" would be the bug this test exists to catch.
        assertTrue(why.contains("4294967295"), why)
        assertTrue(why.contains("tEXt"), why)
    }

    // ---- 4. a compressed chunk: the reader's own, narrower contract ------------------------------------

    /**
     * Decision 5, from the reader's side. A `zTXt` is returned with `compressed = true` and an **empty**
     * `text** — the reader never inflates — **and the walk continues**, so a `preset` chunk that comes
     * *after* the compressed one is still found.
     *
     * That last clause is the non-vacuity of this test: a reader that threw on the `zTXt` would satisfy a
     * weaker assertion and cost a person their brush.
     */
    @Test
    fun aCompressedChunkIsReturnedAsCompressedWithNoTextAndTheReaderKeepsGoing() {
        val bytes = png(
            // keyword, NUL, compression method 0, then bytes that are deliberately not a DEFLATE stream.
            chunk("zTXt", "version".toByteArray() + byteArrayOf(0, 0) + byteArrayOf(9, 9, 9, 9)),
            text("preset", "<paintop id=\"Pixel\"/>"),
        )
        val chunks = readPngTextChunks(bytes)
        assertEquals(2, chunks.size, "$chunks")
        val compressed = chunks[0]
        assertEquals("version", compressed.keyword)
        assertTrue(compressed.compressed, "$compressed")
        assertEquals("", compressed.text, "a compressed chunk's text must be empty, never a guess")
        // …and the chunk after it is still there, in full.
        assertEquals("preset", chunks[1].keyword)
        assertEquals("<paintop id=\"Pixel\"/>", chunks[1].text)
        assertFalse(chunks[1].compressed)
    }

    // ---- 5. iTXt, both ways --------------------------------------------------------------------------

    /**
     * An `iTXt` with a zero compression flag is read: keyword, NUL, flag, method, a language tag, a NUL, a
     * translated keyword, a NUL, then the text. A **non-zero** flag is returned `compressed = true` with no
     * text, for the same reason a `zTXt` is.
     */
    @Test
    fun anUncompressedInternationalChunkIsReadAndACompressedFlagIsNot() {
        // keyword, NUL, flag = 0, method = 0, an empty language tag + NUL, an empty translated keyword + NUL,
        // then the text. Five bytes between the keyword's NUL and the text, and the walk's own arithmetic
        // (flag at keyword + 1, language at flag + 2, translated one byte later) is what pins the count.
        val flat = "preset".toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + "<paintop/>".toByteArray()
        val squeezed = "preset".toByteArray() + byteArrayOf(0, 1, 0, 0, 0) + byteArrayOf(1, 2, 3)
        val chunks = readPngTextChunks(png(chunk("iTXt", flat), chunk("iTXt", squeezed)))
        assertEquals(2, chunks.size, "$chunks")
        assertEquals("<paintop/>", chunks[0].text)
        assertFalse(chunks[0].compressed)
        assertEquals("preset", chunks[1].keyword)
        assertTrue(chunks[1].compressed, "$chunks")
        assertEquals("", chunks[1].text)
    }

    // ---- 6. the IHDR ---------------------------------------------------------------------------------

    /**
     * Decision 6: the `IHDR`'s width and height are read, and **that is all** — four big-endian bytes each
     * at file offsets 16..20 and 20..24, which is 8 of signature + 4 of length + 4 of type + 8 of header
     * before them. A header that ends before byte 24 cannot hold them, whatever its `IHDR` claims its length
     * is, and that is a refusal rather than a null: a file that *has* an `IHDR` and cannot say how wide it
     * is is not a PNG.
     */
    @Test
    fun theHeaderWidthAndHeightAreReadAndAShortHeaderRefuses() {
        val header = readPngHeader(png(ihdr(320, 240)))
        assertEquals(320, header?.width)
        assertEquals(240, header?.height)
        // A file with no IHDR at all is **null**, not a refusal: the caller has two different questions —
        // "how big is this image" and "is this file sound" — and this is the first one's answer.
        assertNull(readPngHeader(png(text("preset", "x"))))
        // Four bytes of payload: the length is under the eight the width and the height need.
        val e = assertFailsWith<BrushException> { readPngHeader(png(chunk("IHDR", ByteArray(4)))) }
        assertTrue((e.message ?: "").contains("24 bytes"), e.message ?: "")
    }

    // ---- 7. every budget refuses, at cap + 1, naming itself --------------------------------------------

    /**
     * Four caps, four refusals, each at **cap + 1**, and each message naming its own cap — a refusal that
     * does not say which number was too big is a refusal nobody can act on.
     *
     * **Why a cap + 1 and not a cap:** "at the cap it works and at cap + 1 it does not" is the only claim
     * that rules out an off-by-one that refuses a legal file. The two byte-count caps are checked *before*
     * the file is walked, so the 64 MiB fixture needs no valid signature and the 32 MiB one needs eight
     * bytes: a length that is over the chunk cap is refused by arithmetic, and refusing it costs nothing.
     */
    @Test
    fun everyPngBudgetRefusesAtCapPlusOneAndNamesItself() {
        val overFile = assertFailsWith<BrushException> {
            readPngTextChunks(ByteArray(MAX_PNG_BYTES.toInt() + 1))
        }
        assertTrue((overFile.message ?: "").contains(MAX_PNG_BYTES.toString()), overFile.message ?: "")

        val overChunkBytes = assertFailsWith<BrushException> {
            readPngTextChunks(png() + be32(MAX_PNG_CHUNK_BYTES + 1) + "tEXt".toByteArray())
        }
        assertTrue(
            (overChunkBytes.message ?: "").contains(MAX_PNG_CHUNK_BYTES.toString()),
            overChunkBytes.message ?: ""
        )

        // MAX_PNG_CHUNKS + 1 chunks, each a zero-length unknown type: 4 bytes + 4 of type + 4 of CRC.
        val tooMany = ArrayList<ByteArray>()
        repeat(MAX_PNG_CHUNKS + 1) { tooMany += chunk("zzZz", ByteArray(0)) }
        val overChunks = assertFailsWith<BrushException> { readPngTextChunks(png(*tooMany.toTypedArray())) }
        assertTrue((overChunks.message ?: "").contains(MAX_PNG_CHUNKS.toString()), overChunks.message ?: "")

        // One text chunk one byte over the string cap. The cap is 8 MiB (JB-8.04 re-pointed it from 64 KiB
        // for exactly this reason), so this is an 8 MiB allocation rather than a synthetic little number.
        val overText = assertFailsWith<BrushException> {
            readPngTextChunks(png(text("preset", "x".repeat(MAX_PNG_STRING_BYTES + 1))))
        }
        assertTrue(
            (overText.message ?: "").contains(MAX_PNG_STRING_BYTES.toString()),
            overText.message ?: ""
        )
    }

    // ---- 8. JB-8.04b: the compressed-chunk reader, additively -------------------------------------------

    /**
     * **Two assertions, and the second is the point.** [readPngTextChunks] still returns a `zTXt` with
     * `compressed = true` and an **empty** `text` — that contract is JB-8.04's and it is unchanged —
     * **and** [readPngCompressedText] hands back the same chunk's text, inflated.
     *
     * A builder who quietly made `readPngTextChunks` inflate would satisfy the first assertion and fail
     * the second, which is exactly why both are here: the second is what keeps the pinned contract honest
     * while the new function lives beside it.
     */
    @Test
    fun aCompressedTextChunkIsInflatedByASeparateCallAndTheOldContractIsUnchanged() {
        val xml = """<paintop id="Pixel"><spacing mode="space" value="0.3"/></paintop>"""
        val bytes = png(
            text("version", "0.5"),
            // keyword, NUL, compression method 0, then a **stored DEFLATE block** (RFC 1951 §3.2.4:
            // BFINAL = 1, BTYPE = 00, LEN, ~LEN, then the bytes) — a real stream, not a made-up header.
            zTxt("preset", storedDeflateBlock(xml.toByteArray())),
            text("Software", "Krita 4.2.6"),
        )
        // The old contract, byte for byte: compressed, empty, and the walk still going.
        val chunks = readPngTextChunks(bytes)
        val preset = chunks.first { it.keyword == "preset" }
        assertTrue(preset.compressed, "$preset")
        assertEquals("", preset.text, "a compressed chunk's text must stay empty, never a guess")
        assertEquals(listOf("version", "preset", "Software"), chunks.map { it.keyword })
        // And the new call, which is the other side of the same boundary.
        assertEquals(xml, readPngCompressedText(bytes, "preset"))
    }

    /**
     * The trap this is here to catch: **a `zTXt` is Latin-1 and an `iTXt` is UTF-8**, and the failure is
     * silent — every ASCII brush name passes either way, so a test written in ASCII cannot see it.
     *
     * The first pair carries `Café` as four Latin-1 bytes (`0x43 0x61 0x66 0xE9`), which is **not** valid
     * UTF-8, so the `iTXt` path must refuse rather than guess: "compressed" is not "UTF-8". The `zTXt`
     * path is one character `é` (U+00E9) and its `length` is the byte count, because Latin-1 is one byte
     * per character by definition.
     *
     * The second pair (`0x41 0xC3 0xA9`) is valid UTF-8 *and* legal Latin-1, so **both** halves answer and
     * the two answers are different strings of different lengths — 3 characters read as Latin-1 against 2
     * read as UTF-8. That is the assertion that fails if either path decodes as the other's encoding.
     */
    @Test
    fun aCompressedTextIsDecodedWithItsOwnChunksEncodingAndNotWithTheOthers() {
        val latin1Bytes = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte())          // "Café" in Latin-1
        val asLatin1 = assertNotNull(
            readPngCompressedText(png(zTxt("preset", storedDeflateBlock(latin1Bytes))), "preset"),
        )
        assertEquals(latin1Bytes.size, asLatin1.length, "one byte is one Latin-1 character")
        assertEquals('é', asLatin1[3], "0xE9 is U+00E9, read as itself")
        assertEquals("Café", asLatin1)

        val asUtf8OrRefusal = try {
            readPngCompressedText(png(iTxtCompressed("preset", storedDeflateBlock(latin1Bytes))), "preset")
        } catch (e: BrushException) {
            null // "0xE9" starts a three-byte sequence and is not followed by two continuations.
        }
        assertTrue(
            asUtf8OrRefusal == null || asUtf8OrRefusal != asLatin1,
            "the iTXt path must not answer with the zTXt path's Latin-1 string",
        )

        // The same two chunk types, same bytes, and now **both** decode — so the difference is observable
        // without arguing about which refusal is nicer. "0x41 0xC3 0xA9" is 3 bytes of Latin-1 ("AÃ©") and
        // 2 characters of UTF-8 ("Aé").
        val bothValid = byteArrayOf(0x41, 0xC3.toByte(), 0xA9.toByte())
        val zBoth = assertNotNull(
            readPngCompressedText(png(zTxt("preset", storedDeflateBlock(bothValid))), "preset"),
        )
        val iBoth = assertNotNull(
            readPngCompressedText(png(iTxtCompressed("preset", storedDeflateBlock(bothValid))), "preset"),
        )
        assertEquals(3, zBoth.length, "zTXt is Latin-1: three bytes, three characters")
        assertEquals(2, iBoth.length, "iTXt is UTF-8: 0xC3 0xA9 is one character")
        assertEquals("Aé", iBoth)
        assertTrue(zBoth != iBoth, "the two chunk types must not decode one another's encoding")
    }

    /**
     * Three answers a caller has to be able to tell apart: **no such chunk**, **not deflate**, and
     * **compressed with a method this build does not read**.
     *
     * A `null` and an empty string are different answers and a caller cannot tell them apart, which is
     * exactly the failure the [PngTextChunk] KDoc was written to prevent — so the first assertion is that
     * a file with only an uncompressed `preset` gives `null`, not `""`.
     */
    @Test
    fun aCompressedKeywordThatIsNotThereIsNullAndAPayloadThatIsNotDeflateRefuses() {
        assertNull(
            readPngCompressedText(png(text("preset", """<paintop id="Pixel"/>""")), "preset"),
            "an uncompressed chunk is not a compressed one that failed to decode",
        )
        assertNull(readPngCompressedText(png(zTxt("version", storedDeflateBlock("0.5".toByteArray()))), "preset"))

        val junk = assertFailsWith<BrushException> {
            readPngCompressedText(png(zTxt("preset", byteArrayOf(1, 2, 3, 4))), "preset")
        }
        assertTrue((junk.message ?: "").isNotBlank(), "a payload that is not a stream refuses in words")

        val otherMethod = assertFailsWith<BrushException> {
            readPngCompressedText(png(zTxt("preset", ByteArray(4), method = 1)), "preset")
        }
        assertTrue((otherMethod.message ?: "").contains("1"), otherMethod.message ?: "")
    }

    /**
     * **The two orderings this file must not get wrong**, which is the whole of the shape of
     * [readPngCompressedText] and are the Do-not list's first two PNG entries.
     *
     * (a) **The keyword is compared before the chunk's shape is judged.** A `zTXt` carrying some *other*
     * keyword with a compression method this build refuses must not raise against a caller that asked about
     * a different one — the answer is `null`, which is what "this file has no such chunk" means, not an
     * exception about somebody else's chunk.
     *
     * (b) **An `iTXt` whose compression flag is `0` is skipped, never speculatively inflated** (Decision 8).
     * Its text here is a genuine stored DEFLATE block, so a reader that inflated on the strength of "this
     * looks compressed" would happily hand back the string inside it. Turning a loud refusal into a silent
     * guess is the outcome this project keeps forbidding, and `null` is the answer.
     */
    @Test
    fun aBadShapeOnAnotherKeywordIsNotRaisedAndAnUnflaggedInternationalChunkIsNeverInflated() {
        // (a) keyword "version", method 1 — refused *if and only if* the caller asked about "version".
        val elsewhere = png(zTxt("version", ByteArray(4), method = 1))
        assertNull(
            readPngCompressedText(elsewhere, "preset"),
            "another keyword's bad shape is not this caller's to hear about",
        )
        val asked = assertFailsWith<BrushException> { readPngCompressedText(elsewhere, "version") }
        val askedWhy = asked.message ?: ""
        assertTrue(askedWhy.contains("method 1"), askedWhy)
        assertTrue(askedWhy.contains("zTXt"), askedWhy)

        // (b) flag 0, so the text is not compressed at all — and it happens to be a real DEFLATE stream, so a
        // reader that guessed "this looks compressed" would have something plausible to hand back.
        val sneaky = png(iTxtUncompressed("preset", storedDeflateBlock("<paintop id=\"Pixel\"/>".toByteArray())))
        assertNull(readPngCompressedText(sneaky, "preset"), "flag 0 is never speculatively inflated")

        // …and the *same* chunk with plain text instead: the uncompressed reader has it, and the compressed
        // reader still says `null`, because flag 0 is not a question it answers.
        val plain = png(iTxtUncompressed("preset", "<paintop id=\"Pixel\"/>".toByteArray()))
        val chunk = readPngTextChunks(plain).single()
        assertFalse(chunk.compressed, "$chunk")
        assertEquals("<paintop id=\"Pixel\"/>", chunk.text)
        assertNull(readPngCompressedText(plain, "preset"), "flag 0 is skipped, not answered")
    }

    // ---- the byte builders ----------------------------------------------------------------------------

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * A whole PNG: the 8-byte signature and nothing else, or the signature plus the given chunks.
     *
     * **Every builder here is array-based rather than an `ArrayList<Byte>`.** The 8 MiB text-chunk fixture
     * would otherwise be three `ArrayList<Byte>`s of eight million boxed entries, and a test that needs most
     * of the heap to build its own input is a test that fails on someone else's machine.
     */
    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArray(SIGNATURE.size + chunks.sumOf { it.size })
        SIGNATURE.copyInto(out, 0)
        var at = SIGNATURE.size
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }

    /** 4 of length, 4 of type, the payload, 4 of CRC — the PNG specification's own layout. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val crc = crc32(type.toByteArray(), data)
        val out = ByteArray(12 + data.size)
        be32(data.size.toLong()).copyInto(out, 0)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        data.copyInto(out, 8)
        be32(crc).copyInto(out, 8 + data.size)
        return out
    }

    /** `tEXt`: the keyword, a NUL, then Latin-1 text — which is what the PNG specification says `tEXt` is. */
    private fun text(keyword: String, body: String): ByteArray {
        val data = ByteArray(keyword.length + 1 + body.length)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0
        for (i in body.indices) data[keyword.length + 1 + i] = body[i].code.toByte()
        return chunk("tEXt", data)
    }

    /**
     * `zTXt`: the keyword, a NUL, the compression-method byte, then [payload] — which is what the PNG
     * specification says the chunk is, in that order and with no length of its own.
     *
     * [method] is a parameter rather than a constant because **a compression method other than 0 is a
     * refusal that names itself**, and a fixture that could only ever say 0 could not test it.
     */
    private fun zTxt(keyword: String, payload: ByteArray, method: Int = 0): ByteArray {
        val data = ByteArray(keyword.length + 2 + payload.size)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0              // the keyword's NUL
        data[keyword.length + 1] = method.toByte()
        payload.copyInto(data, keyword.length + 2)
        return chunk("zTXt", data)
    }

    /**
     * A **compressed** `iTXt`: the keyword, a NUL, the compression flag `1`, the method `0`, an empty
     * language tag and its NUL, an empty translated keyword and its NUL, then [payload].
     *
     * Five bytes between the keyword's NUL and the text, and `internationalChunk`'s own arithmetic — flag at
     * `keyword + 1`, language at `flag + 2`, translated one byte later — is what pins the count.
     */
    private fun iTxtCompressed(keyword: String, payload: ByteArray): ByteArray {
        val data = ByteArray(keyword.length + 5 + payload.size)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0              // the keyword's NUL
        data[keyword.length + 1] = 1          // compression flag
        data[keyword.length + 2] = 0          // compression method
        data[keyword.length + 3] = 0          // an empty language tag, then its NUL
        data[keyword.length + 4] = 0          // an empty translated keyword, then its NUL
        payload.copyInto(data, keyword.length + 5)
        return chunk("iTXt", data)
    }

    /**
     * An **uncompressed** `iTXt`: the keyword, a NUL, compression flag `0`, method `0`, an empty language tag
     * and its NUL, an empty translated keyword and its NUL, then [text]. The five bytes between the keyword's
     * NUL and the text are `internationalChunk`'s own arithmetic, which is what pins the count.
     *
     * **This is the shape Decision 8 must never inflate**, so it is built with a real DEFLATE stream in it: a
     * reader that guessed "this looks compressed" would have something plausible to return.
     */
    private fun iTxtUncompressed(keyword: String, text: ByteArray): ByteArray {
        val data = ByteArray(keyword.length + 5 + text.size)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0              // the keyword's NUL
        data[keyword.length + 1] = 0          // compression flag: **not** compressed
        data[keyword.length + 2] = 0          // compression method
        data[keyword.length + 3] = 0          // an empty language tag, then its NUL
        data[keyword.length + 4] = 0          // an empty translated keyword, then its NUL
        text.copyInto(data, keyword.length + 5)
        return chunk("iTXt", data)
    }

    /**
     * RFC 1951 §3.2.4's **stored block**: `BFINAL = 1` and `BTYPE = 00` in one header byte, then `LEN` and
     * its ones-complement `~LEN` little-endian, then the bytes themselves.
     *
     * A stored block is a legal DEFLATE stream and it exercises everything except Huffman, which is what
     * `commonTest` can honestly build: `java.util.zip.Deflater` may not appear here. **A stored block never
     * starts with a zlib header** (`0x01 & 0x0F == 1`, not 8), so these fixtures do not quietly depend on
     * the wrapper tolerance either — the `jvmTest` file is where a real Huffman stream and a real zlib
     * stream are compressed by the JDK.
     */
    private fun storedDeflateBlock(data: ByteArray): ByteArray {
        require(data.size <= 0xFFFF) { "a stored DEFLATE block holds at most 65 535 bytes" }
        val out = ByteArray(5 + data.size)
        out[0] = 0x01                                    // BFINAL = 1, BTYPE = 00
        out[1] = (data.size and 0xFF).toByte()           // LEN, low
        out[2] = ((data.size shr 8) and 0xFF).toByte()   // LEN, high
        out[3] = (data.size.inv() and 0xFF).toByte()     // ~LEN, low
        out[4] = ((data.size.inv() shr 8) and 0xFF).toByte()
        data.copyInto(out, 5)
        return out
    }

    /** A real 13-byte `IHDR`: width, height, bit depth 8, colour type 6 (RGBA), and the rest zeroes. */
    private fun ihdr(width: Int, height: Int): ByteArray {
        val data = ByteArray(13)
        writeBe32(data, 0, width)
        writeBe32(data, 4, height)
        data[8] = 8
        data[9] = 6
        return chunk("IHDR", data)
    }

    private fun writeBe32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v shr 24).toByte()
        b[at + 1] = (v shr 16).toByte()
        b[at + 2] = (v shr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun be32(v: Long): ByteArray = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
    )

    /**
     * CRC-32 as PNG states it: the IEEE 802.3 polynomial `0xEDB88320`, reflected, initial and final value
     * `0xFFFFFFFF`, over the chunk type and its payload.
     *
     * Written out rather than taken from `java.util.zip.CRC32`, because `commonTest` may not use `java.*`
     * (the spec's own rule) and because a fixture built with the same library the code under test uses would
     * prove nothing.
     */
    private fun crc32(vararg parts: ByteArray): Long {
        var c = 0xFFFFFFFFL
        for (part in parts) {
            for (b in part) {
                c = c xor (b.toLong() and 0xFF)
                repeat(8) {
                    c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1
                }
            }
        }
        return (c xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}
