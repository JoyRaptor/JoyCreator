package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
