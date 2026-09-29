package cc.joycreator.joybrush.core.export

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A structural walk of an encoded GIF, and an LZW decoder to go with it.
 *
 * The reason this exists rather than a few `assertEquals(bytes[0], 0x47)` style checks: the failure
 * modes this encoder has to not have are STRUCTURAL. A wrong code width or a missing sub-block
 * terminator does not throw — it produces a file that opens in the writer's own tool and shows
 * garbage, or a truncated picture, in everything else. So the tests below re-parse the file block
 * by block with a parser written from the format and nothing from the encoder, and they run the
 * LZW stream back through a decoder written as a READER (its width rule expressed over the table the
 * reader has built, not copied from the writer's). An encoder that is wrong about when the width
 * grows fails here, and it fails with a message naming the pixel count.
 *
 * Nothing in here is in `commonMain`: this is a test oracle, and shipping it would be shipping two
 * implementations of the same format.
 */
private class ParsedFrame(
    val delayCs: Int,
    val minCodeSize: Int,
    val indices: IntArray,
)

private class ParsedGif(
    val width: Int,
    val height: Int,
    val tableSlots: Int,
    val palette: IntArray,
    val hasLoopExtension: Boolean,
    val frames: List<ParsedFrame>,
    val subBlockCount: Int,
)

/** Parses a whole GIF89a file, or throws with the offset it gave up at. */
private fun parseGif(bytes: ByteArray): ParsedGif {
    fun u8(at: Int): Int = bytes[at].toInt() and 0xFF
    fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    fun need(at: Int, what: String) {
        if (at >= bytes.size) throw IllegalStateException("file ends at ${bytes.size}, needed $what at $at")
    }

    need(6, "signature")
    val signature = String(bytes, 0, 6, Charsets.US_ASCII)
    if (signature != "GIF89a") throw IllegalStateException("signature is \"$signature\", not GIF89a")

    val width = u16(6)
    val height = u16(8)
    val packed = u8(10)
    val hasGlobalTable = (packed and 0x80) != 0
    if (!hasGlobalTable) throw IllegalStateException("no global colour table")
    // The logical screen descriptor is SEVEN bytes: width(2), height(2), packed(1), background
    // colour index(1), pixel aspect ratio(1). So the colour table starts at 13, not 11. Both of
    // these offsets were 11 while the encoder wrote a five-byte descriptor, and they agreed with
    // each other and with nothing else.
    val tableSlots = 1 shl ((packed and 0x07) + 1)
    val palette = IntArray(tableSlots)
    for (i in 0 until tableSlots) {
        val at = 13 + i * 3
        need(at + 2, "colour table")
        palette[i] = (u8(at) shl 16) or (u8(at + 1) shl 8) or u8(at + 2)
    }

    val frames = ArrayList<ParsedFrame>()
    var hasLoop = false
    var subBlocks = 0
    var at = 13 + tableSlots * 3
    var pendingDelay = 0
    var pendingDisposal = 0
    var pendingTransparent = -1
    var sawTrailer = false

    while (at < bytes.size) {
        need(at, "block introducer")
        when (u8(at)) {
            0x3B -> { // trailer
                sawTrailer = true
                at++
            }
            0x21 -> { // extension
                need(at + 1, "extension label")
                val label = u8(at + 1)
                var cursor = at + 2
                if (label == 0xF9) {
                    need(cursor, "graphic control size")
                    val size = u8(cursor)
                    if (size != 4) throw IllegalStateException("GCE size is $size, not 4")
                    need(cursor + 5, "GCE body")
                    val gcePacked = u8(cursor + 1)
                    pendingDisposal = (gcePacked shr 2) and 0x07
                    pendingDelay = u16(cursor + 2)
                    pendingTransparent = if ((gcePacked and 0x01) != 0) u8(cursor + 4) else -1
                } else if (label == 0xFF) {
                    need(cursor, "app id size")
                    val idSize = u8(cursor)
                    need(cursor + idSize, "app id")
                    val id = String(bytes, cursor + 1, idSize, Charsets.US_ASCII)
                    if (id == "NETSCAPE2.0") hasLoop = true
                }
                // Step over the extension's sub-blocks, whatever they are. This is the generic form
                // and it covers both cases above without a special case: the graphic control
                // extension IS a sub-block, the length byte 0x04 being the one written at `at + 2`.
                while (true) {
                    need(cursor, "extension sub-block")
                    val n = u8(cursor)
                    cursor++
                    if (n == 0) break
                    need(cursor + n - 1, "extension sub-block body")
                    cursor += n
                    subBlocks++
                }
                at = cursor
            }
            0x2C -> { // image descriptor
                need(at + 9, "image descriptor")
                // The descriptor is 10 bytes from its introducer: 0x2C, Left(2), Top(2), Width(2),
                // Height(2), packed(1). So Width is at at+5 and Height at at+7, and reading Height
                // at at+6 yields the HIGH byte of Width as the LOW byte of Height — which reads as
                // a height of `width * 256`. That is what this line was, and it failed 13 of this
                // file's tests with a message ("frame is 8x2048, screen is 8x8") that reads like a
                // stride bug and is not one. Nothing in the encoder was ever wrong.
                val frameW = u16(at + 5)
                val frameH = u16(at + 7)
                val imagePacked = u8(at + 9)
                if ((imagePacked and 0x80) != 0) throw IllegalStateException("unexpected local colour table")
                if ((imagePacked and 0x40) != 0) throw IllegalStateException("file is interlaced")
                var cursor = at + 10
                need(cursor, "min code size")
                val minCodeSize = u8(cursor)
                cursor++
                val data = ArrayList<Byte>()
                while (true) {
                    need(cursor, "image sub-block")
                    val n = u8(cursor)
                    cursor++
                    if (n == 0) break
                    need(cursor + n - 1, "image sub-block body")
                    for (i in 0 until n) data.add(bytes[cursor + i])
                    cursor += n
                    subBlocks++
                }
                if (frameW != width || frameH != height) {
                    throw IllegalStateException("frame is ${frameW}x$frameH, screen is ${width}x$height")
                }
                if (pendingDisposal != 2) {
                    throw IllegalStateException("disposal is $pendingDisposal, not 2")
                }
                if (pendingTransparent != 0) {
                    throw IllegalStateException("transparent index is $pendingTransparent, not 0")
                }
                val indices = lzwDecode(data.toByteArray(), minCodeSize)
                if (indices.size != width * height) {
                    throw IllegalStateException(
                        "frame decoded to ${indices.size} pixels, expected ${width * height}",
                    )
                }
                frames.add(ParsedFrame(pendingDelay, minCodeSize, indices))
                at = cursor
            }
            else -> throw IllegalStateException("unknown block introducer 0x${u8(at).toString(16)} at $at")
        }
        if (sawTrailer) break
    }
    if (!sawTrailer) throw IllegalStateException("no trailer; the file is truncated")
    if (at != bytes.size) {
        throw IllegalStateException("trailer is at ${at - 1} but the file is ${bytes.size} bytes")
    }
    return ParsedGif(width, height, tableSlots, palette, hasLoop, frames, subBlocks)
}

/**
 * GIF LZW, written as a reader.
 *
 * The width rule is the reader's own: it grows the width when the table the reader has JUST built
 * has filled the current width, and it never grows past 12. [GifEncoder.lzwEncode] expresses the
 * same boundary from the writer's side; the two agreeing is the test, and if they ever stop
 * agreeing this is where it shows.
 */
private fun lzwDecode(data: ByteArray, minCodeSize: Int): IntArray {
    val clearCode = 1 shl minCodeSize
    val endCode = clearCode + 1
    val prefix = IntArray(4096) { -1 }
    val suffix = IntArray(4096)
    for (i in 0 until clearCode) suffix[i] = i

    val out = ArrayList<Int>()
    var next = clearCode + 2
    var codeSize = minCodeSize + 1
    var bitPos = 0
    var prev = -1

    fun bit(at: Int): Int = (data[at shr 3].toInt() shr (at and 7)) and 1

    fun readCode(): Int {
        if (bitPos + codeSize > data.size * 8) return -1
        var v = 0
        for (i in 0 until codeSize) {
            v = v or (bit(bitPos + i) shl i)
        }
        bitPos += codeSize
        return v
    }

    fun expand(code: Int): IntArray {
        if (code < next) {
            val scratch = IntArray(4096)
            var length = 0
            var c = code
            while (true) {
                scratch[length++] = suffix[c]
                if (c < clearCode) break
                c = prefix[c]
                if (length >= scratch.size) throw IllegalStateException("LZW string did not terminate")
            }
            val result = IntArray(length)
            for (i in 0 until length) result[i] = scratch[length - 1 - i]
            return result
        }
        if (code == next && prev >= 0) {
            // The code that refers to itself: the string is the previous one plus its own first
            // symbol. This is the case an encoder that gets its dictionary wrong cannot produce, and
            // a decoder that does not handle it is why some viewers show fewer pixels than the
            // writer packed.
            val base = expand(prev)
            val result = IntArray(base.size + 1)
            base.copyInto(result)
            result[base.size] = base[0]
            return result
        }
        throw IllegalStateException("LZW code $code is outside the table (next is $next)")
    }

    while (true) {
        val code = readCode()
        if (code < 0) break
        if (code == clearCode) {
            next = clearCode + 2
            codeSize = minCodeSize + 1
            prev = -1
            continue
        }
        if (code == endCode) break
        val decoded = expand(code)
        for (v in decoded) out.add(v)
        if (prev >= 0 && next < 4096) {
            prefix[next] = prev
            suffix[next] = decoded[0]
            next++
            // `>=`, and this is deliberately NOT the same comparison the writer uses. The reader
            // builds its table one entry LATER than the writer does, so the writer widens when its
            // next free code passes the current width (`>`) and the reader widens when its own
            // reaches it (`>=`). The two rules are one entry apart on purpose: copy the writer's
            // comparison here and every stream that crosses a width boundary desynchronises.
            if (next >= (1 shl codeSize) && next < 4096) codeSize++
        }
        prev = code
    }
    val pixels = IntArray(out.size)
    for (i in out.indices) pixels[i] = out[i]
    return pixels
}

// ---------------------------------------------------------------------- fixtures

/** An RGBA8 image of one colour, laid out row-major with row 0 at the top. */
private fun solidFrame(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int): ByteArray {
    val out = ByteArray(w * h * 4)
    var i = 0
    while (i < w * h) {
        val at = i * 4
        out[at] = r.toByte()
        out[at + 1] = g.toByte()
        out[at + 2] = b.toByte()
        out[at + 3] = a.toByte()
        i++
    }
    return out
}

/**
 * A deterministic spread of colours, from an explicit LCG rather than a random source.
 *
 * An LCG written out here rather than `Random(seed)` for one reason: this must be the same pixels
 * on every platform forever, and an arithmetic sequence cannot drift with a library change the way a
 * generator's output can. Int overflow is defined in Kotlin, so this is well-defined everywhere.
 */
private fun noisyFrame(w: Int, h: Int, colourCount: Int): ByteArray {
    val out = ByteArray(w * h * 4)
    var state = 0x2545F491
    var i = 0
    while (i < w * h) {
        state = state * 1103515245 + 12345
        val at = i * 4
        out[at] = (((state shr 16) and 0x7F) * colourCount / 128).toByte()
        out[at + 1] = (((state shr 8) and 0x7F) * colourCount / 128).toByte()
        out[at + 2] = ((state and 0x7F) * colourCount / 128).toByte()
        out[at + 3] = 0xFF.toByte()
        i++
    }
    return out
}

// ---------------------------------------------------------------------- the tests

/**
 * The encoder's structural and determinism contract.
 *
 * The two properties everything else is in service of:
 *
 *  1. **Byte-identical output for the same frames.** Not "an equivalent GIF" — the same bytes. The
 *     same frames exported twice, on two platforms, in two years, have to be the same file, or a
 *     re-export is a diff and a bug report is unfalsifiable.
 *  2. **A file that parses.** Verified by re-reading the bytes with [parseGif] rather than by
 *     spotting header bytes, because a structurally wrong GIF does not fail — it shows a truncated
 *     or garbage picture somewhere other than here.
 */
class GifEncoderTest {

    @Test
    fun headerAndScreenDescriptorAreGif89aAtTheFrameSize() {
        val gif = GifEncoder(64, 48)
        gif.addFrame(solidFrame(64, 48, 255, 0, 0, 255), 100)
        val bytes = gif.finish()
        val parsed = parseGif(bytes)

        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(64, parsed.width)
        assertEquals(48, parsed.height)
        // Global table present, 8 bits per primary, no sort flag, and a table size that is a power
        // of two — here 2 entries for one colour plus the transparent slot.
        assertEquals(0xF0, bytes[10].toInt() and 0xFF)
        assertEquals(2, parsed.tableSlots)
        assertEquals(1, parsed.frames.size)
    }

    @Test
    fun oneFrameThirtyTwoTimesIsOneFile() {
        val gif = GifEncoder(8, 8)
        repeat(32) { i -> gif.addFrame(solidFrame(8, 8, i * 7, 0, 0, 255), 40) }
        val parsed = parseGif(gif.finish())
        assertEquals(32, parsed.frames.size)
        // Each frame's own picture, not a delta: disposal 2 plus a full-canvas descriptor means a
        // reader never has to reconstruct frame n from frame n-1.
        for (frame in parsed.frames) assertEquals(8 * 8, frame.indices.size)
    }

    @Test
    fun aSingleColourFramePutsThatColourInTheTable() {
        val gif = GifEncoder(4, 4)
        gif.addFrame(solidFrame(4, 4, 0x33, 0x66, 0x99, 255), 100)
        val parsed = parseGif(gif.finish())

        assertEquals(2, parsed.tableSlots)
        // Slot 0 is the transparent one and its RGB is never shown; slot 1 is the colour itself,
        // reproduced exactly because a single-colour box's mean is that colour.
        assertEquals(0x336699, parsed.palette[1])
        assertEquals(1, parsed.frames[0].indices[0])
        assertTrue(parsed.frames[0].indices.all { it == 1 })
    }

    @Test
    fun aTenMillisecondFrameIsHeldForTwoHundredths() {
        val gif = GifEncoder(2, 2)
        gif.addFrame(solidFrame(2, 2, 0, 0, 0, 255), 10)
        // 10 ms is 1 cs, which is inside the "as fast as you can" range every decoder substitutes a
        // speed for, so it is floored at 2 cs. The alternative is a frame that plays at a duration
        // the file never stated.
        assertEquals(2, parseGif(gif.finish()).frames[0].delayCs)
    }

    @Test
    fun delaysRoundHalfUpAndNeverReachZero() {
        val cases = listOf(
            0 to 2,     // nothing asked for; still floored
            1 to 2,
            4 to 2,     // 0.4 cs, rounds to 0, floored
            14 to 2,    // 1.4 cs, rounds to 1, floored
            15 to 2,    // 1.5 cs, rounds half up to 2
            16 to 2,    // 1.6 cs, rounds to 2
            24 to 2,
            25 to 3,    // 2.5 cs, rounds half up to 3
            100 to 10,
            1000 to 100,
            655350 to 65535, // the u16 field, saturated rather than wrapped
            1000000 to 65535,
            -50 to 2,   // a negative hold is not a hold
        )
        for ((ms, expected) in cases) {
            val gif = GifEncoder(1, 1)
            gif.addFrame(solidFrame(1, 1, 255, 255, 255, 255), ms)
            val parsed = parseGif(gif.finish())
            assertEquals(expected, parsed.frames[0].delayCs, "delay $ms ms")
        }
    }

    @Test
    fun twoEncodesOfTheSameFramesAreByteIdentical() {
        val frames = listOf(
            solidFrame(24, 16, 255, 0, 0, 255),
            solidFrame(24, 16, 0, 255, 0, 200),
            solidFrame(24, 16, 0, 0, 255, 0),
        )
        val delays = listOf(100, 33, 250)

        fun encode(): ByteArray {
            val gif = GifEncoder(24, 16)
            for (i in frames.indices) gif.addFrame(frames[i], delays[i])
            return gif.finish()
        }

        val first = encode()
        val second = encode()
        assertContentEquals(first, second, "two encodes of the same frames differ")
        // And finishing one encoder twice is the same promise at a smaller scale.
        val gif = GifEncoder(24, 16)
        gif.addFrame(frames[0], 100)
        assertContentEquals(gif.finish(), gif.finish(), "finish() is not repeatable")
    }

    @Test
    fun aOnePixelRedFrameIsExactlyTheseSixtyTwoBytes() {
        // Hand-derived, byte for byte, so the LZW table can be checked against a page of arithmetic
        // rather than against the encoder that produced it. Palette: one opaque colour, so one
        // colour at index 1 and a two-entry table. LZW with minCodeSize 2 gives clear=4, end=5 and a
        // 3-bit opening width, so the stream is clear, the one pixel's index, end: 4, 1, 5 packed
        // least-significant bit first is 0b0100 1100 0000 0001 -> 4C 01, which is TWO bytes and so a
        // two-byte sub-block. (This array used to say 3 and to carry a third data byte, which is how
        // a 60-byte expectation matched nothing and a 1x1 GIF came out with no terminator.)
        //
        // 62 = 6 signature + 7 screen descriptor + 6 colour table + 19 NETSCAPE + 8 graphic control
        // + 10 image descriptor + 1 min code size + 1 sub-block length + 2 data + 1 terminator
        // + 1 trailer.
        val expected = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            0x01, 0x00, // screen width 1
            0x01, 0x00, // screen height 1
            0xF0.toByte(), // global table, 8-bit colour resolution, 2-entry table
            0x00, 0x00, // background colour index (the transparent slot), pixel aspect ratio
            0x00, 0x00, 0x00, // slot 0: the transparent index
            0xFF.toByte(), 0x00, 0x00, // slot 1: red
            0x21, 0xFF.toByte(), 0x0B, // application extension, 11-byte id
            0x4E, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2E, 0x30, // NETSCAPE2.0
            0x03, 0x01, 0x00, 0x00, 0x00, // sub-block: the loop block, count 0 = forever, then terminator
            0x21, 0xF9.toByte(), 0x04, 0x09, // graphic control: disposal 2, transparency on
            0x02, 0x00, // delay 2 cs
            0x00, // transparent index 0
            0x00, // block terminator
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // image descriptor
            0x02, // minimum code size
            0x02, 0x4C, 0x01, 0x00, // image data: a two-byte sub-block, then its terminator
            0x3B, // trailer
        )
        assertEquals(62, expected.size)

        val gif = GifEncoder(1, 1)
        gif.addFrame(solidFrame(1, 1, 255, 0, 0, 255), 10)
        assertContentEquals(expected, gif.finish())
    }

    @Test
    fun sixteenIdenticalPixelsCrossBothLZWWidthBoundaries() {
        // The case that catches a code-width off-by-one, derived by hand.
        //
        // minCodeSize 2, so clear=4, end=5, first free code 6, opening width 3. The writer hands out
        // a code BEFORE the reader gets round to minting the matching one, so the writer is exactly
        // one entry ahead and must widen one code LATER than "the table is full" sounds like:
        //   clear 4   3 bits
        //   1         3 bits   (code 6 minted, next 7)
        //   6         3 bits   (code 7 minted, next 8 — still 3 bits: 8 does not exceed 8)
        //   7         3 bits   (code 8 minted, next 9 — now 9 exceeds 8, so the width becomes 4)
        //   8, 9, 1   4 bits each
        //   end 5     4 bits
        // Packed least-significant bit first that is 8C 8F 19 05. A writer that widened one code
        // early produces 8C 0F 33 0A, and one that widened one code late produces something else
        // again; all three decode to *something* in a tolerant reader, which is why this pins the
        // bytes and not only the pixel count.
        val gif = GifEncoder(16, 1)
        gif.addFrame(solidFrame(16, 1, 0, 0, 255, 255), 100)
        val bytes = gif.finish()

        val parsed = parseGif(bytes)
        assertEquals(2, parsed.frames[0].minCodeSize)
        assertTrue(parsed.frames[0].indices.all { it == 1 }, "a run of 16 identical pixels must decode to 16 of them")
        assertEquals(16, parsed.frames[0].indices.size)

        // And the exact stream, located by finding the image data rather than by counting from a
        // number that would have to be kept in step with the header.
        val imageData = findImageData(bytes, 0)
        assertContentEquals(byteArrayOf(0x8C.toByte(), 0x8F.toByte(), 0x19, 0x05), imageData)
    }

    @Test
    fun aFullyTransparentFrameEncodesToNothingVisibleAndStillParses() {
        val gif = GifEncoder(8, 8)
        gif.addFrame(solidFrame(8, 8, 200, 100, 50, 0), 100)
        val parsed = parseGif(gif.finish())

        // No opaque pixel anywhere, so the table is the transparent index plus one padding slot and
        // every pixel is index 0. Two slots, because a GIF table may not be shorter.
        assertEquals(2, parsed.tableSlots)
        assertTrue(parsed.frames[0].indices.all { it == 0 }, "an empty frame must be all index 0")
        assertEquals(64, parsed.frames[0].indices.size)
    }

    @Test
    fun alphaAtOrAboveOneTwentyEightIsPaintedAndBelowItIsNot() {
        val frame = solidFrame(4, 1, 10, 20, 30, 255)
        // 128 is painted (the threshold is "at or above"), 127 is not. Pixel 1's alpha lives at byte
        // 4 + 3; a 4x1 frame is 16 bytes, so the "4 * 4 + 3" this used to say was off the end of the
        // array and the test was asserting on a crash rather than on a threshold.
        frame[3] = 128.toByte()
        frame[4 + 3] = 127.toByte()
        val gif = GifEncoder(4, 1)
        gif.addFrame(frame, 100)
        val indices = parseGif(gif.finish()).frames[0].indices
        assertTrue(indices[0] != 0, "alpha 128 is painted")
        assertEquals(0, indices[1], "alpha 127 is transparent")
    }

    @Test
    fun moreThanTwoHundredAndFiftySixColoursStillFitAndAreStable() {
        // GIF has 256 slots and one of them is the transparent index, so a frame with more distinct
        // colours than that has to lose some. It loses them by median cut, over the WEIGHTED colour
        // count and in a total order, so what it loses is the same on every run — which is the only
        // thing that makes the loss acceptable.
        val frame = noisyFrame(48, 48, 300)
        val gif = GifEncoder(48, 48)
        gif.addFrame(frame, 100)
        val bytes = gif.finish()

        val parsed = parseGif(bytes)
        assertEquals(256, parsed.tableSlots, "the table must round up to 256, not overflow")
        assertEquals(8, parsed.frames[0].minCodeSize, "256 slots means a minimum code size of 8")
        assertEquals(48 * 48, parsed.frames[0].indices.size)
        assertTrue(parsed.frames[0].indices.all { it in 0..255 })

        val again = GifEncoder(48, 48)
        again.addFrame(noisyFrame(48, 48, 300), 100)
        assertContentEquals(bytes, again.finish(), "quantising to 256 colours is not stable")
    }

    @Test
    fun aFrameThatDoesNotCompressIsSplitIntoSubBlocksOfAtMostTwoHundredAndFiftyFive() {
        // Noise compresses badly, so this is how to get past one 255-byte sub-block and exercise the
        // chunking and its terminator. 64x64 of it, twice, so the second frame's data lands on a
        // different sub-block boundary than the first's.
        val gif = GifEncoder(64, 64)
        gif.addFrame(noisyFrame(64, 64, 251), 100)
        gif.addFrame(noisyFrame(64, 64, 199), 100)
        val bytes = gif.finish()

        val parsed = parseGif(bytes)
        assertEquals(2, parsed.frames.size)
        for (frame in parsed.frames) assertEquals(64 * 64, frame.indices.size)

        // Every image sub-block is a length byte, so with the extension blocks counted too, a file
        // this size must have used more blocks than its data would fit in one-per-255.
        val imageBytes = findImageData(bytes, 0).size + findImageData(bytes, 1).size
        assertTrue(
            imageBytes > 255,
            "the fixture stopped compressing badly enough to cross a sub-block; it made $imageBytes bytes",
        )
        // And the reassembled stream is longer than any one block, which is the actual claim.
        assertTrue(parsed.subBlockCount > 4, "only ${parsed.subBlockCount} sub-blocks in the file")
    }

    @Test
    fun aSingleFrameWithNoLoopCarriesNoLoopingExtension() {
        val looping = GifEncoder(2, 2, loop = true)
        looping.addFrame(solidFrame(2, 2, 0, 0, 0, 255), 100)
        val withLoop = looping.finish()
        assertTrue(parseGif(withLoop).hasLoopExtension)

        val once = GifEncoder(2, 2, loop = false)
        once.addFrame(solidFrame(2, 2, 0, 0, 0, 255), 100)
        val withoutLoop = once.finish()
        assertTrue(!parseGif(withoutLoop).hasLoopExtension, "loop = false must not write the extension")
        assertEquals(withLoop.size - 19, withoutLoop.size, "the extension is 19 bytes")
    }

    @Test
    fun noFramesIsRefusedRatherThanWrittenAsAnEmptyFile() {
        val gif = GifEncoder(4, 4)
        val failure = assertFailsWith<IllegalStateException> { gif.finish() }
        assertTrue(failure.message!!.contains("no frames"), "message should say what is wrong: ${failure.message}")
    }

    @Test
    fun aFrameOfTheWrongSizeIsRefusedAtTheCallThatGotItWrong() {
        val gif = GifEncoder(4, 4)
        val failure = assertFailsWith<IllegalArgumentException> {
            gif.addFrame(ByteArray(4 * 4 * 4 - 1), 100)
        }
        // The message has to name BOTH numbers: what arrived and what was wanted. Asserting on one
        // literal substring ("64 bytes") was passing over a message that said "is 64" and no more.
        assertTrue(failure.message!!.contains("63"), "message should name what arrived: ${failure.message}")
        assertTrue(failure.message!!.contains("64"), "message should name what was wanted: ${failure.message}")
        assertTrue(failure.message!!.contains("4x4"), "message should name the size: ${failure.message}")

        val zero = assertFailsWith<IllegalArgumentException> { GifEncoder(0, 4) }
        assertTrue(zero.message!!.contains("width"), "message should name the axis: ${zero.message}")
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The reassembled LZW stream of frame [which], found by walking the file.
     *
     * Reusing the parser's walk rather than hardcoding an offset, so a change to the header does not
     * turn this into a test of the wrong bytes.
     */
    private fun findImageData(bytes: ByteArray, which: Int): ByteArray {
        fun u8(at: Int) = bytes[at].toInt() and 0xFF
        val tableSlots = 1 shl ((u8(10) and 0x07) + 1)
        var at = 13 + tableSlots * 3
        var seen = 0
        while (at < bytes.size) {
            when (u8(at)) {
                0x3B -> throw IllegalStateException("no image block $which")
                0x21 -> {
                    at += 2
                    while (true) {
                        val n = u8(at)
                        at++
                        if (n == 0) break
                        at += n
                    }
                }
                0x2C -> {
                    at += 10
                    // The LZW minimum code size byte belongs to the image block, not to the data, so
                    // it is stepped over on BOTH paths. Stepping over it only on the path that
                    // collects the data is how this walk used to run off the end of the file when it
                    // was asked for the second frame: it read the code size as a sub-block length.
                    at++
                    if (seen == which) {
                        val data = ArrayList<Byte>()
                        while (true) {
                            val n = u8(at)
                            at++
                            if (n == 0) break
                            for (i in 0 until n) data.add(bytes[at + i])
                            at += n
                        }
                        return data.toByteArray()
                    }
                    while (true) {
                        val n = u8(at)
                        at++
                        if (n == 0) break
                        at += n
                    }
                    seen++
                }
                else -> throw IllegalStateException("unknown block 0x${u8(at).toString(16)} at $at")
            }
        }
        throw IllegalStateException("no image block $which")
    }
}
