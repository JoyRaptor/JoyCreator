package cc.joycreator.joybrush.androidkit.io

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-2.14a. The owner is an artist, so a PNG that is subtly wrong is worse than one that is
 * obviously broken: an obviously broken one gets reported, and a subtly wrong one opens on the
 * desktop and not on the phone.
 *
 * THREE INDEPENDENT WITNESSES, because agreeing with yourself proves nothing:
 *
 *  - `ImageIO` is a decoder that did not write this file, and it is the only check here that
 *    exercises a real PNG reader end to end.
 *  - [unfilter] re-derives the pixels from the zlib stream using the filters as the spec defines
 *    them, which pins the scanline layout, the filter choice and the zlib wrapper without trusting
 *    any reader's idea of them.
 *  - [chunks] and [assertEveryChunkCrc] walk the file's own structure and recompute every CRC
 *    from the bytes, so a CRC that is wrong FAILS here instead of being read as fine by a lenient
 *    decoder.
 *
 * This is a CLASS on purpose, for the reason [JbArchiveTest] gives: JUnit 4 runs methods on an
 * instance, so a file of top-level `@Test` functions compiles, reports green and executes nothing.
 */
class PngWriterTest {

    // ---------------------------------------------------------------- the four the spec asks for

    @Test
    fun threeByTwoRoundTripsThroughImageIo() {
        // Six pixels chosen to catch the ways a writer goes wrong: opaque, half alpha, fully
        // transparent, alpha 1, alpha 254 and white.
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),             // opaque red
            0, 255.toByte(), 0, 128.toByte(),             // green at half alpha
            0, 0, 0, 0,                                  // no paper under this one
            18, 52, 86, 1,                               // alpha 1, where a rounding slip shows
            200.toByte(), 100, 50, 254.toByte(),         // alpha 254
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), // opaque white
        )
        val file = PngWriter.encode(3, 2, rgba)

        val img = decode(file)
        assertEquals(3, img.width)
        assertEquals(2, img.height)
        assertSamePixels(3, 2, rgba, img)

        // The transparent pixel is the owner's ruling made into an assertion: no paper in the
        // export means alpha 0, not white, and not the last colour that passed through.
        val transparent = img.getRGB(2, 0)
        assertEquals(0, transparent ushr 24 and 0xFF, "the transparent pixel must stay alpha 0")
        assertEquals(0, transparent and 0xFFFFFF, "and must not be given a colour, least of all white")

        assertContentEquals(rgba, unfilter(3, 2, rawScanlines(file)), "pixels, by hand")
    }

    @Test
    fun aThreeHundredByTwoHundredGradientRoundTripsExactly() {
        val rgba = gradient(300, 200)
        val file = PngWriter.encode(300, 200, rgba)
        assertSamePixels(300, 200, rgba, decode(file))
        assertContentEquals(rgba, unfilter(300, 200, rawScanlines(file)), "pixels, by hand")
        assertEveryChunkCrc(file)
    }

    @Test
    fun aWrongNumberOfBytesIsRefused() {
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(3, 2, ByteArray(3 * 2 * 4 - 1)) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(3, 2, ByteArray(3 * 2 * 4 + 1)) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(3, 2, ByteArray(0)) }
        // 3 by 2 and 6 by 1 want the SAME 24 bytes, and BOTH are legal, so neither a check that
        // only counted bytes nor one that keyed off the wrong shape would notice the difference.
        // What this pins is that the pixels are read at the width they were given: reshaped, they
        // must come back as a 6 by 1 picture, not as the 3 by 2 one they were made for.
        val rgba = gradient(3, 2)
        val reshaped = PngWriter.encode(6, 1, rgba)
        assertEquals(6, decode(reshaped).width)
        assertEquals(1, decode(reshaped).height)
        assertContentEquals(rgba, unfilter(6, 1, rawScanlines(reshaped)), "pixels, reshaped")
        assertSamePixels(6, 1, rgba, decode(reshaped))
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(1, 1, ByteArray(4), 10) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(1, 1, ByteArray(4), -2) }
    }

    @Test
    fun theFileOpensWithTheSignatureAndSaysTheRightThingsInIhdr() {
        val file = PngWriter.encode(1, 1, byteArrayOf(1, 2, 3, 4))
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            file.copyOfRange(0, 8),
            "the 8-byte PNG signature, which is not the letters PNG on their own",
        )
        val cs = chunks(file)
        assertEquals(3, cs.size, "IHDR, IDAT and IEND, and nothing else")
        assertEquals("IHDR", cs[0].first)
        assertEquals(13, readInt(file, 8), "IHDR's length field is 13")
        val d = cs[0].second
        assertEquals(13, d.size)
        assertEquals(1, readInt(d, 0), "width, four bytes, big-endian")
        assertEquals(1, readInt(d, 4), "height, four bytes, big-endian")
        assertEquals(8, d[8].toInt(), "bit depth")
        assertEquals(6, d[9].toInt(), "colour type 6, truecolour with alpha")
        assertEquals(0, d[10].toInt(), "compression method")
        assertEquals(0, d[11].toInt(), "filter method")
        assertEquals(0, d[12].toInt(), "interlace method")
        assertEquals("IEND", cs[2].first, "IEND is last")
        assertEquals(0, cs[2].second.size, "IEND carries nothing")
    }

    // ---------------------------------------------------------------- structure and checksums

    @Test
    fun everyChunkCarriesACrcOfItsTypeAndData() {
        assertEveryChunkCrc(PngWriter.encode(9, 5, gradient(9, 5)))
    }

    @Test
    fun theChunksAccountForEveryByteOfTheFile() {
        // A length field that is one too small does not fail loudly: the walk carries on from the
        // wrong place and the next CRC is computed over the wrong bytes. Ending exactly on the end
        // of the file is what makes the walk trustworthy.
        val file = PngWriter.encode(4, 4, gradient(4, 4))
        val cs = chunks(file)
        assertEquals("IHDR", cs[0].first)
        assertEquals("IEND", cs[cs.size - 1].first)
        val accounted = cs.sumOf { 12 + it.second.size }
        assertEquals(file.size, 8 + accounted, "no trailing bytes, and no chunk counted twice")
    }

    @Test
    fun anImageTooBigToHoldIsRefusedWithTheNumberItWanted() {
        // 65535 by 65535 by 4 is 17179344900, past the end of an Int, so an Int multiply wraps to
        // a negative size. The check is in Long, so this is refused as "the wrong number of bytes"
        // instead of matching some wrapped value and going on to read past the end of the array.
        val e = assertFailsWith<IllegalArgumentException> { PngWriter.encode(65535, 65535, ByteArray(0)) }
        assertTrue(
            e.message.orEmpty().contains("17179344900"),
            "the message should say how many bytes a 65535x65535 image is: ${e.message}",
        )
        // The ends of the legal range, which are legal, so these must NOT throw.
        PngWriter.encode(1, 1, ByteArray(4))
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(65536, 1, ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(1, 65536, ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(0, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(1, 0, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { PngWriter.encode(-1, 1, ByteArray(0)) }
    }

    // ---------------------------------------------------------------- awkward shapes

    @Test
    fun onePixelIsOneFilterByteAndFourPixelBytes() {
        val rgba = byteArrayOf(255.toByte(), 0, 0, 128.toByte())
        val file = PngWriter.encode(1, 1, rgba)
        // A 1x1 has no neighbour to the left and no row above, so all five filters predict zero,
        // all five produce the same four bytes and all five score the same. Ties keep the first
        // filter tried, which is None — so the entire scanline stream of a 1x1 is five bytes and
        // can be read off the page. 00, then the pixel.
        assertContentEquals(byteArrayOf(0, 255.toByte(), 0, 0, 128.toByte()), rawScanlines(file))
        assertSamePixels(1, 1, rgba, decode(file))
    }

    @Test
    fun oddWidthsAndOddHeightsBothSurvive() {
        for (w in intArrayOf(1, 2, 3, 5, 7, 15, 17)) {
            for (h in intArrayOf(1, 2, 3, 7, 11)) {
                val rgba = gradient(w, h)
                val file = PngWriter.encode(w, h, rgba)
                assertContentEquals(rgba, unfilter(w, h, rawScanlines(file)), "pixels, ${w}x$h")
                assertSamePixels(w, h, rgba, decode(file))
            }
        }
    }

    @Test
    fun theScanlineStreamIsOneFilterBytePerRowAndNoMore() {
        // THE COUNT. `height * (1 + width * 4)`, and a reader that is one byte short per row is
        // shifted by half a pixel from row two onwards: a picture that looks almost right.
        for ((w, h) in listOf(1 to 1, 3 to 2, 7 to 1, 1 to 7, 4 to 4, 17 to 5)) {
            val file = PngWriter.encode(w, h, gradient(w, h))
            val raw = rawScanlines(file)
            assertEquals(h * (1 + w * 4), raw.size, "the raw stream of a ${w}x$h image")
            assertContentEquals(gradient(w, h), unfilter(w, h, raw), "pixels, ${w}x$h")
        }
    }

    @Test
    fun aFullyTransparentImageIsTransparentRatherThanWhite() {
        val rgba = ByteArray(4 * 3 * 4) // every byte zero, which is a whole board of no paper
        val file = PngWriter.encode(4, 3, rgba)
        assertContentEquals(rgba, unfilter(4, 3, rawScanlines(file)))
        val img = decode(file)
        assertSamePixels(4, 3, rgba, img)
        for (y in 0 until 3) {
            for (x in 0 until 4) {
                val argb = img.getRGB(x, y)
                assertEquals(0, argb ushr 24 and 0xFF, "alpha at $x,$y")
                assertEquals(0, argb and 0xFFFFFF, "no colour, and above all no white, at $x,$y")
            }
        }
    }

    @Test
    fun aSinglePixelOfAlphaZeroStaysAlphaZero() {
        val rgba = byteArrayOf(0, 0, 0, 0)
        val file = PngWriter.encode(1, 1, rgba)
        val argb = decode(file).getRGB(0, 0)
        assertEquals(0, argb ushr 24 and 0xFF, "alpha must be 0, not rounded up to 1")
        assertContentEquals(rgba, unfilter(1, 1, rawScanlines(file)))
    }

    @Test
    fun aTransparentPixelKeepsItsColourInTheFileEvenThoughNoViewerShowsIt() {
        val rgba = byteArrayOf(0, 0, 255.toByte(), 0)
        // Colour type 6 stores all four channels, so the blue under the zero alpha goes into the
        // file. Asserted on the BYTES and not through ImageIO on purpose: whether a decoder hands
        // back the colour under a fully transparent pixel is the decoder's business, and asserting
        // that here would make this test fail on a JDK rather than on a bug in the writer.
        assertContentEquals(rgba, unfilter(1, 1, rawScanlines(PngWriter.encode(1, 1, rgba))))
    }

    // ---------------------------------------------------------------- determinism and level

    @Test
    fun encodingTheSamePictureTwiceGivesTheSameBytes() {
        val rgba = gradient(37, 23)
        assertContentEquals(
            PngWriter.encode(37, 23, rgba),
            PngWriter.encode(37, 23, rgba),
            "two encodes of one picture must be one file, or a diff of it is noise",
        )
    }

    @Test
    fun everyCompressionLevelGivesTheSamePixelsAndTheSameFilterChoice() {
        val rgba = gradient(31, 17)
        val atZero = rawScanlines(PngWriter.encode(31, 17, rgba, 0))
        for (level in -1..9) {
            val raw = rawScanlines(PngWriter.encode(31, 17, rgba, level))
            assertContentEquals(rgba, unfilter(31, 17, raw), "pixels at compression level $level")
            // The filter is a function of the pixels and the dimensions, and of nothing else. A
            // choice that drifted with the level would still give back the right pixels, so this
            // is the only thing that notices.
            assertContentEquals(atZero, raw, "filter choice must not depend on level $level")
        }
    }

    @Test
    fun aLargerImageWithManyRowsRoundTrips() {
        val rgba = gradient(512, 512)
        val file = PngWriter.encode(512, 512, rgba)
        assertContentEquals(rgba, unfilter(512, 512, rawScanlines(file)))
        assertSamePixels(512, 512, rgba, decode(file))
        assertEveryChunkCrc(file)
    }

    @Test
    fun aStreamLongerThanTheIdatLimitIsCutIntoSeveralIdatsAndStillReadsBack() {
        // 64 by 64 of noise is about 16 KB of zlib stream, so a 4 KB limit splits it four or five
        // ways. Crossing the REAL 1 MiB limit needs pixels that do not compress, which needs a
        // four-megabyte image, and a four-megabyte image is what got this JVM's heap taken out from
        // under it. The splitting is the same code either way: a 1024 by 1024 image would have
        // proved nothing extra that this does not.
        val rgba = noise(64, 64)
        // Two limits, one round and one awkward, so the tail chunk is very unlikely to land on an
        // exact multiple of either. The assertions below do not depend on that: they hold whether
        // the last chunk is full or short.
        for (limit in intArrayOf(4096, 997)) {
            val file = PngWriter.encode(64, 64, rgba, 6, limit)
            val cs = chunks(file)
            val idats = cs.filter { it.first == "IDAT" }

            assertTrue(idats.size >= 2, "limit $limit: expected a split, got ${idats.size} IDAT")
            assertEquals("IHDR", cs[0].first, "limit $limit: IHDR is still first")
            assertEquals("IEND", cs[cs.size - 1].first, "limit $limit: IEND is still last")
            for (k in 0 until idats.size - 1) {
                assertEquals(limit, idats[k].second.size, "limit $limit: IDAT $k should be full")
            }
            assertTrue(
                idats[idats.size - 1].second.size in 1..limit,
                "limit $limit: the last IDAT holds the remainder",
            )

            assertEveryChunkCrc(file)
            // Inflate the IDATs together, because that is what a reader does: the split is a
            // detail of the framing and the image behind it is unchanged.
            assertContentEquals(rgba, unfilter(64, 64, rawScanlines(file)), "limit $limit: pixels")
            assertSamePixels(64, 64, rgba, decode(file))
        }
    }

    /**
     * Pixels that do not compress, so the zlib stream is about the size of the raw one. Bits 16
     * and up of an LCG, which is enough: this is here to be incompressible, not to be random.
     */
    private fun noise(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        var x = 0x12345678
        for (i in out.indices) {
            x = x * 1103515245 + 12345
            out[i] = (x ushr 16).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------- reading a file back

    /** Signature, then every chunk as (type, data). Walks the length fields, so it must land exactly. */
    private fun chunks(file: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = 8
        while (at < file.size) {
            val length = readInt(file, at)
            out.add(Pair(String(file, at + 4, 4, Charsets.US_ASCII), file.copyOfRange(at + 8, at + 8 + length)))
            at += 12 + length
        }
        return out
    }

    private fun idatOf(file: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, data) in chunks(file)) {
            if (type == "IDAT") out.write(data)
        }
        return out.toByteArray()
    }

    /** Recomputes each chunk's CRC from the bytes in front of it. A wrong CRC fails HERE. */
    private fun assertEveryChunkCrc(file: ByteArray) {
        var at = 8
        var seen = 0
        while (at < file.size) {
            val length = readInt(file, at)
            val crc = CRC32()
            crc.update(file, at + 4, 4 + length) // the type and the data, and nothing else
            assertEquals(crc.value.toInt(), readInt(file, at + 8 + length), "CRC of the chunk at $at")
            at += 12 + length
            seen++
        }
        assertTrue(seen >= 3, "at least IHDR, IDAT and IEND, saw $seen")
        assertEquals(file.size, at, "the chunks must reach exactly the end of the file")
    }

    /** The scanline bytes as the zlib stream holds them: filter bytes, filtered data, no more. */
    private fun rawScanlines(file: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(idatOf(file))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n = inflater.inflate(buf)
            while (n > 0) {
                out.write(buf, 0, n)
                n = inflater.inflate(buf)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * The pixels, with the filters undone from the spec's definitions rather than from the writer's.
     * Must come back equal to the array handed to [PngWriter.encode], byte for byte.
     */
    private fun unfilter(width: Int, height: Int, raw: ByteArray): ByteArray {
        val stride = width * 4
        val out = ByteArray(width * height * 4)
        var up = IntArray(stride)
        var cur = IntArray(stride)
        var at = 0
        for (y in 0 until height) {
            val type = raw[at].toInt() and 0xFF
            val body = at + 1
            for (i in 0 until stride) {
                val x = raw[body + i].toInt() and 0xFF
                val a = if (i >= 4) cur[i - 4] else 0
                val b = up[i]
                val c = if (i >= 4) up[i - 4] else 0
                val v = when (type) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    else -> x + paethPredictor(a, b, c)
                }
                cur[i] = v and 0xFF
            }
            for (i in 0 until stride) out[y * stride + i] = cur[i].toByte()
            val swap = up
            up = cur
            cur = swap
            at = body + stride
        }
        return out
    }

    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = if (p > a) p - a else a - p
        val pb = if (p > b) p - b else b - p
        val pc = if (p > c) p - c else c - p
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun decode(file: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(file))
            ?: error("ImageIO could not read the PNG this writer just produced")

    /**
     * The four bytes at [at] as the int [BufferedImage.getRGB] would hand back for them.
     *
     * ALPHA GOES IN THE HIGH BYTE. `getRGB` returns non-premultiplied sRGB as 0xAARRGGBB, so this
     * packs A, R, G, B from the top down.
     *
     * Packing them the way they ARRIVE — red first, alpha last — produces a perfectly plausible
     * Int that is a different pixel: a transparent black comes out as 255, an opaque red as
     * 0xFF0000FF and a half-alpha red as 0xFF000080. Each of those reads like a sensible value in
     * a failure message, which is precisely how the wrong order hides: the decoded value looked
     * like a packed ARGB int, and so did the expectation, and they were both right about
     * everything except which end the alpha went on.
     */
    private fun argbOf(rgba: ByteArray, at: Int): Int =
        ((rgba[at + 3].toInt() and 0xFF) shl 24) or
            ((rgba[at].toInt() and 0xFF) shl 16) or
            ((rgba[at + 1].toInt() and 0xFF) shl 8) or
            (rgba[at + 2].toInt() and 0xFF)

    /** Every channel of every pixel, through a real reader. */
    private fun assertSamePixels(width: Int, height: Int, rgba: ByteArray, img: BufferedImage) {
        val stride = width * 4
        val row = IntArray(width)
        for (y in 0 until height) {
            img.getRGB(0, y, width, 1, row, 0, width)
            for (x in 0 until width) {
                assertEquals(argbOf(rgba, y * stride + x * 4), row[x], "pixel $x,$y")
            }
        }
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    /**
     * A gradient across the whole range of every channel, alpha included, in `width * height * 4`
     * bytes. Red runs 0 to 255 and alpha runs 255 to 0 as x goes across, so one image holds fully
     * opaque, fully transparent and everything between.
     */
    private fun gradient(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                // Each channel swept across its WHOLE range, because a gradient that quietly
                // collapsed to a constant would still pass every round trip. The `coerceAtLeast(1)`
                // is not decoration: a 1-pixel-wide image has width - 1 == 0, and these divides
                // would throw.
                val red = (x * 255) / (width - 1).coerceAtLeast(1)
                val green = (y * 255) / (height - 1).coerceAtLeast(1)
                out[i] = red.toByte()
                out[i + 1] = green.toByte()
                out[i + 2] = ((x + y) % 256).toByte()
                out[i + 3] = (255 - red).toByte() // alpha the other way, so 0 and 255 both appear
            }
        }
        return out
    }
}
