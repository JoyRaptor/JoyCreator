package cc.joycreator.joybrush.androidkit.io

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** RGBA8 is four bytes per pixel, which is also the distance from a pixel to the one left of it. */
private const val BYTES_PER_PIXEL = 4

/** The five filter types, in the order the PNG spec numbers them. */
private const val FILTER_NONE = 0
private const val FILTER_SUB = 1
private const val FILTER_UP = 2
private const val FILTER_AVERAGE = 3
private const val FILTER_PAETH = 4
private const val FILTER_COUNT = 5

/** Width and height are four bytes each in IHDR, so neither can exceed this. */
private const val MAX_DIMENSION = 65535

/**
 * The compressed stream is cut into IDAT chunks of at most this many bytes.
 *
 * A PNG with one enormous IDAT is legal and most readers manage it, but the file has to be held in
 * memory whole by whoever is looking at it, and a phone gallery is not where to find out how much
 * memory a 16384-square sprite sheet wants. Several smaller IDATs are identical on the wire as far
 * as any reader is concerned, and the PNG spec asks for a 2^15-byte window rather than a single
 * chunk for the same reason.
 */
private const val MAX_IDAT_BYTES = 1024 * 1024

/**
 * Writes a complete, exact PNG file from straight (un-premultiplied) RGBA8 pixels.
 *
 * EXACT, NOT GOOD ENOUGH. `Bitmap.compress` cannot run in a JVM test and it premultiplies, so
 * anything it produces is a guess that only gets checked by opening the file. This is the opposite
 * kind of code: a PNG is a fixed layout of big-endian integers, a zlib stream and two checksums,
 * all of which can be written down and checked, and [encode] writes down exactly that. The tests
 * beside this file re-derive the file from the pixels, walk its chunks and recompute every CRC,
 * because a wrong CRC usually still DISPLAYS — which is how a subtle error reaches the owner as
 * "this file is broken on my phone" instead of as an error anywhere.
 *
 * WHAT IT EXPECTS. Straight RGBA8, row 0 at the top, row-major, four bytes per pixel, exactly
 * `width * height * 4` of them. That is what [cc.joycreator.joybrush.core.render.RegionRenderer]
 * returns, and this writer passes those four channels through untouched.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. It does not premultiply, and it does not flatten. A document
 * with `includeInExport = false` has no paper in the exported file, so the pixels the renderer
 * hands over are 0,0,0,0 and they go into the PNG as 0,0,0,0 — alpha 0, NOT white. Filling
 * transparency with white here would put a white box behind every drawing somebody made with the
 * paper turned off, and would do it to the thumbnail of every layer as well. Colour type 6 stores
 * all four channels, so the colour under a zero alpha survives into the file whether or not any
 * viewer chooses to show it.
 *
 * NOTHING OPTIONAL IS WRITTEN. No gamma, no sRGB, no text, no time, no physical pixel dimensions.
 * Every one of those is something a reader may interpret, and sRGB in particular is assumed by
 * every reader that matters, so writing it would only add a chance to be wrong. The output is
 * signature, IHDR, one or more IDAT, IEND, and nothing else.
 */
object PngWriter {

    /**
     * The 8 bytes every PNG file starts with: 137, then PNG, then CR LF LF.
     *
     * Not the letters PNG on their own. A reader handed a .png whose first byte is 0x50 rather
     * than 0x89 is entitled to refuse the file, and some of them do.
     */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** IEND carries nothing. A shared empty array is read-only, so one is enough. */
    private val NO_DATA = ByteArray(0)

    /**
     * A complete PNG file: colour type 6 (truecolour with alpha), bit depth 8, no interlacing.
     *
     * @param width, height 1..65535, and `rgba` must be exactly `width * height * 4` bytes.
     * @param rgba straight RGBA8, row 0 at the top, not premultiplied. Passed through as it is.
     * @param compressionLevel 0..9, or -1 for the JDK default. 0 stores the scanlines uncompressed,
     *   which is legal and useful when a file is being diffed or measured rather than looked at.
     * @throws IllegalArgumentException on a size that cannot be a picture of that shape, or a
     *   compression level [Deflater] would not accept.
     */
    fun encode(width: Int, height: Int, rgba: ByteArray, compressionLevel: Int = 6): ByteArray =
        encode(width, height, rgba, compressionLevel, MAX_IDAT_BYTES)

    /**
     * The same file, cut into IDAT chunks of at most [maxIdatBytes].
     *
     * INTERNAL, and deliberately not part of the contract. The limit is a real production number
     * and a test cannot cross it honestly: the only pixels that refuse to compress are a
     * multi-megabyte image. A 1024 by 1024 of noise did try, and took the test JVM's heap down with
     * it. What is actually worth testing here is the SPLITTING, and the splitting does not care
     * what the limit is, so a test drives it with a 16 KB image and a 4 KB limit and reaches the
     * same code path for a thousandth of the memory.
     */
    internal fun encode(
        width: Int,
        height: Int,
        rgba: ByteArray,
        compressionLevel: Int,
        maxIdatBytes: Int,
    ): ByteArray {
        require(maxIdatBytes >= 1) { "an IDAT chunk cannot be $maxIdatBytes bytes" }
        requireSize(width, height, rgba)
        require(compressionLevel in -1..9) { "compression level $compressionLevel is not -1..9" }

        val raw = filter(rgba, width, height)
        val out = ByteArrayOutputStream(raw.size / 2 + 1024)
        out.write(SIGNATURE)
        writeChunk(out, "IHDR", ihdr(width, height))

        // nowrap is false, which is the whole point: the deflate data comes wrapped in a zlib
        // stream, so the Adler-32 of the scanlines is computed and checked by the JDK rather than
        // left to me. A bare deflate stream has no Adler-32 and some readers reject those.
        val deflated = deflate(raw, compressionLevel)

        // A zlib stream is never empty — a 2-byte header and a 4-byte Adler-32 are the minimum —
        // so this do-while always writes at least one IDAT and never writes an empty one.
        var at = 0
        do {
            val end = minOf(maxIdatBytes, deflated.size)
            writeChunk(out, "IDAT", deflated, at, end)
            at = end
        } while (at < deflated.size)

        writeChunk(out, "IEND", NO_DATA)
        return out.toByteArray()
    }

    /**
     * The scanline stream, filtered, ready to be deflated: one filter byte and then
     * `width * 4` bytes, for every row.
     *
     * THE COUNT IS THE WHOLE TRAP. It is `height * (1 + width * 4)`, not `height * width * 4` and
     * not `height * width * 3`. A reader that is given a stream one filter byte short per row
     * starts row two half a pixel early and every row after that is shifted, which is a picture
     * that looks almost right and is wrong everywhere.
     */
    private fun filter(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val stride = width * BYTES_PER_PIXEL
        val out = ByteArray(height * (1 + stride))
        var at = 0

        // Two row buffers swapped rather than copied, and one scratch block that holds all five
        // candidate filterings of the row being chosen. So a row is filtered once per candidate
        // and the winner is copied out, instead of filtering every candidate twice — once to score
        // it and again to write it. At 16384 wide that is the difference between one pass over
        // 262 kB and five.
        var up = ByteArray(stride)
        var cur = ByteArray(stride)
        val scratch = ByteArray(FILTER_COUNT * stride)

        for (y in 0 until height) {
            rgba.copyInto(cur, 0, y * stride, y * stride + stride)

            var best = FILTER_NONE
            var bestScore = -1L
            for (type in 0 until FILTER_COUNT) {
                val score = filterRow(cur, up, stride, scratch, type * stride, type)
                // STRICTLY less, so a tie keeps the earlier filter and None wins. That is not just
                // tidiness: it makes the output a function of the pixels and the dimensions alone,
                // so encoding the same picture twice gives the same bytes, and a 1x1 — where all
                // five filters score identically because there is no left and nothing above — comes
                // out as a plain None row that can be checked by hand.
                if (bestScore < 0L || score < bestScore) {
                    bestScore = score
                    best = type
                }
            }

            out[at++] = best.toByte()
            scratch.copyInto(out, at, best * stride, best * stride + stride)
            at += stride

            val swap = up
            up = cur
            cur = swap
        }
        return out
    }

    /**
     * Writes one filtered row into [dst] at [dstAt] and returns the heuristic's score for it.
     *
     * @param up the row above, all zeroes for row 0.
     */
    private fun filterRow(
        cur: ByteArray,
        up: ByteArray,
        stride: Int,
        dst: ByteArray,
        dstAt: Int,
        type: Int,
    ): Long {
        var score = 0L
        for (i in 0 until stride) {
            val x = cur[i].toInt() and 0xFF
            // Left, above, above-left. Before the first pixel of the row there is no neighbour, and
            // the spec says to treat those as zero rather than to leave them out.
            val a = if (i >= BYTES_PER_PIXEL) cur[i - BYTES_PER_PIXEL].toInt() and 0xFF else 0
            val b = up[i].toInt() and 0xFF
            val c = if (i >= BYTES_PER_PIXEL) up[i - BYTES_PER_PIXEL].toInt() and 0xFF else 0

            // Every filter is `x` minus a prediction, and the prediction is a byte arithmetic
            // average rather than a rounded one: the spec says floor of (left + above) over 2.
            // `and 0xFF` on the way out is the wrap into a byte, for the filters that subtract.
            val v = when (type) {
                FILTER_NONE -> x
                FILTER_SUB -> x - a
                FILTER_UP -> x - b
                FILTER_AVERAGE -> x - (a + b) / 2
                else -> x - paeth(a, b, c)
            }
            val byte = (v and 0xFF).toByte()
            dst[dstAt + i] = byte

            // The heuristic libpng uses: the sum of the absolute values of the filtered bytes read
            // as SIGNED, so 0x80 scores 128 and 0x00 scores 0. A filter's whole job is to turn a
            // row into mostly zeros, and this is the number that says how well it managed.
            val s = byte.toInt()
            score += if (s < 0) -s.toLong() else s.toLong()
        }
        return score
    }

    /** Paeth's predictor: whichever of left, above and above-left is closest to left + above - c. */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = if (p > a) p - a else a - p
        val pb = if (p > b) p - b else b - p
        val pc = if (p > c) p - c else c - p
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /**
     * IHDR: width, height, then the five single bytes that fix the rest of the file.
     *
     * BIG-ENDIAN, four bytes each, and in this order. The scanline data that follows is not
     * big-endian and is not in this order, which is exactly why a writer that reuses one integer
     * helper for both produces a file that opens nowhere.
     */
    private fun ihdr(width: Int, height: Int): ByteArray {
        val b = ByteArray(13)
        putInt(b, 0, width)
        putInt(b, 4, height)
        b[8] = 8    // bit depth: 8 bits per channel
        b[9] = 6    // colour type 6: truecolour with alpha, which is what RGBA8 is
        b[10] = 0   // compression method: the only one PNG defines
        b[11] = 0   // filter method: adaptive, so all five filters above are legal
        b[12] = 0   // interlace method: none
        return b
    }

    /**
     * One length-prefixed, CRC-suffixed chunk.
     *
     * The length is the DATA length alone. The CRC covers the type and the data and nothing else —
     * not the length, not the CRC itself — and that is the byte most often got wrong, because a
     * wrong CRC still displays in a great many readers and only shows up on the ones that check.
     */
    private fun writeChunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
        from: Int = 0,
        to: Int = data.size,
    ) {
        val length = to - from
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(out, length)
        out.write(typeBytes)
        out.write(data, from, length)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, from, length)
        writeInt(out, crc.value.toInt())
    }

    /** Deflate, as a zlib stream. Level 0 stores, which is still a valid PNG. */
    private fun deflate(raw: ByteArray, level: Int): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(raw)
            d.finish()
            val out = ByteArrayOutputStream(raw.size / 4 + 64)
            val buf = ByteArray(8 * 1024)
            var n = d.deflate(buf)
            while (n > 0) {
                out.write(buf, 0, n)
                n = d.deflate(buf)
            }
            // finish() with all of the input already set means deflate() returns 0 only once it is
            // done, so the loop above cannot spin. Asserted anyway, because the alternative failure
            // is an IDAT a few bytes short: a file that opens, and then comes up grey in a viewer
            // that is stricter about the zlib trailer than the writer that made it.
            check(d.finished()) { "deflate stopped after ${out.size()} bytes with the input all given" }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** Four bytes, most significant first. Used for every number the file contains. */
    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v ushr 24) and 0xFF).toByte()
        b[at + 1] = ((v ushr 16) and 0xFF).toByte()
        b[at + 2] = ((v ushr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }

    /**
     * Four bytes, most significant first, into a stream.
     *
     * `ushr` rather than `shr` so a negative `v` — which is what a CRC above 2^31 arrives as —
     * writes its high byte rather than a run of 0xFF.
     */
    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /**
     * The shape of the picture, checked before anything is allocated.
     *
     * THE SIZE IS COMPUTED IN LONG, and that is not tidiness. 65535 by 65535 by 4 is about 17 GB,
     * which is past the end of an Int, so the Int product wraps to a NEGATIVE number. Comparing an
     * array's size against a negative expectation rejects everything for the wrong reason, and a
     * caller who had padded its array to match the wrapped value would get past this check and be
     * read off the end of. In Long the check is exact, and it is this check — not the dimension
     * check above it — that is the real ceiling on how large a picture can be: a 17 GB image is a
     * legal PNG that no JVM can hold in a ByteArray, and the message says so instead of the write
     * failing later with something about a negative array size.
     */
    private fun requireSize(width: Int, height: Int, rgba: ByteArray) {
        require(width in 1..MAX_DIMENSION) {
            "a PNG cannot be $width pixels wide; it is 1 to $MAX_DIMENSION"
        }
        require(height in 1..MAX_DIMENSION) {
            "a PNG cannot be $height pixels tall; it is 1 to $MAX_DIMENSION"
        }
        val want = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        require(rgba.size.toLong() == want) {
            "a ${width}x$height RGBA8 image is $want bytes, but ${rgba.size} were given"
        }
        // rgba's size fitted an Int, so `want` did too, and the arithmetic in filter() is now safe.
        // The one filter byte per row is all that is left over the top, and that is the only way the
        // scanline stream can still pass the end of an Int.
        val raw = height.toLong() * (1L + width * BYTES_PER_PIXEL)
        require(raw <= Int.MAX_VALUE) {
            "a ${width}x$height image needs $raw filtered bytes, past what one array can hold"
        }
    }
}
