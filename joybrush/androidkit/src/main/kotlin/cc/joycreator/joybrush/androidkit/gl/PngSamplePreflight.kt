package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.SampleMaskLimits
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import cc.joycreator.joybrush.core.brush.SampledTips
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32

data class SamplePngBudgets(
    val maxEncodedImageBytes: Int = PngSamplePreflight.MAX_ENCODED_IMAGE_BYTES,
    val maxEncodedSetBytes: Long = PngSamplePreflight.MAX_ENCODED_SET_BYTES,
    val maxImageDimension: Int = SampleMaskLimits.MAX_IMAGE_DIMENSION,
    val maxDecodedSetPixels: Long = SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS,
)

data class SamplePngHeader(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int)

/**
 * Owned encoded bytes plus verified headers. All assets have been read and their whole-set
 * source pixels reserved before the Android decoder is allowed to see the first image.
 */
class PreparedPngSamples internal constructor(
    private val orderedPaths: List<String>, private val encoded: Map<String, EncodedSamplePng>,
    val decodedPixels: Long, val encodedBytes: Long,
) {
    val size: Int get() = orderedPaths.size
    fun paths(): List<String> = orderedPaths.toList()
    fun header(index: Int): SamplePngHeader = encoded.getValue(orderedPaths[index]).header
    fun encodedCopy(index: Int): ByteArray = encoded.getValue(orderedPaths[index]).bytes.copyOf()
    internal fun unique(): Map<String, EncodedSamplePng> = encoded
    internal fun path(index: Int): String = orderedPaths[index]
}

/** Internal bytes are owned by the preflight reader; only the decoder gets direct access. */
internal class EncodedSamplePng(val header: SamplePngHeader, val bytes: ByteArray)

object PngSamplePreflight {
    const val MAX_ENCODED_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_ENCODED_SET_BYTES = 32L * 1024 * 1024
    private val SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    /** InputStream ownership transfers to this call; every opened stream is closed on every path. */
    fun read(spec: SampledTipSpec, open: (String) -> InputStream?, budgets: SamplePngBudgets = SamplePngBudgets()): PreparedPngSamples {
        val problems = SampledTips.problems(spec)
        if (problems.isNotEmpty()) throw BrushException(problems.joinToString("; "))
        if (budgets.maxEncodedImageBytes !in 33..MAX_ENCODED_IMAGE_BYTES || budgets.maxEncodedSetBytes !in 33L..MAX_ENCODED_SET_BYTES ||
            budgets.maxImageDimension !in 1..SampleMaskLimits.MAX_IMAGE_DIMENSION ||
            budgets.maxDecodedSetPixels !in 1L..SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS)
            throw BrushException("PNG sample budgets must fit the encoded, dimension and decoded-pixel limits")
        val ordered = spec.images.toList()
        val encoded = linkedMapOf<String, EncodedSamplePng>()
        var totalBytes = 0L; var totalPixels = 0L
        for (path in ordered.distinct()) {
            val remaining = budgets.maxEncodedSetBytes - totalBytes
            val limit = minOf(budgets.maxEncodedImageBytes.toLong(), remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (limit < 33) throw BrushException("sample set exceeds its encoded byte budget")
            val stream = open(path) ?: throw BrushException("sample image is missing: $path")
            val bytes = stream.use { readBounded(it, limit) }
            val header = inspect(bytes, budgets.maxImageDimension)
            val pixels = header.width.toLong() * header.height
            if (pixels > budgets.maxDecodedSetPixels - totalPixels)
                throw BrushException("sample set exceeds ${budgets.maxDecodedSetPixels} decoded pixels before bitmap decoding")
            totalPixels += pixels; totalBytes += bytes.size
            encoded[path] = EncodedSamplePng(header, bytes)
        }
        // Duplicate paths retain ordered weights, and the runtime set/atlas count every ordered
        // image toward the same budget. Reserve that conservative total before any decode too.
        var orderedPixels = 0L
        for (path in ordered) {
            val header = encoded.getValue(path).header
            val pixels = header.width.toLong() * header.height
            if (pixels > budgets.maxDecodedSetPixels - orderedPixels)
                throw BrushException("ordered sample set exceeds ${budgets.maxDecodedSetPixels} source pixels before bitmap decoding")
            orderedPixels += pixels
        }
        return PreparedPngSamples(ordered, encoded.toMap(), orderedPixels, totalBytes)
    }

    /** Validate standard PNG structure and CRCs without inflating/decompressing any pixel data. */
    fun inspect(bytes: ByteArray, maxDimension: Int = SampleMaskLimits.MAX_IMAGE_DIMENSION): SamplePngHeader {
        if (maxDimension !in 1..SampleMaskLimits.MAX_IMAGE_DIMENSION)
            throw BrushException("PNG sample dimension limit must be 1..${SampleMaskLimits.MAX_IMAGE_DIMENSION}")
        if (bytes.size > MAX_ENCODED_IMAGE_BYTES) throw BrushException("PNG sample exceeds $MAX_ENCODED_IMAGE_BYTES encoded bytes")
        if (bytes.size < 33 || !SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] })
            throw BrushException("sample image needs a complete PNG signature and IHDR")
        var at = 8; var header: SamplePngHeader? = null; var idat = false; var ended = false
        var palette = false; var leftIdat = false
        while (at < bytes.size) {
            if (bytes.size - at < 12) throw BrushException("PNG sample has a truncated chunk")
            val length = u32(bytes, at)
            val end = at.toLong() + 12 + length
            if (end > bytes.size || length > Int.MAX_VALUE) throw BrushException("PNG sample chunk overruns its encoded bytes")
            val size = length.toInt()
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            if (type.any { it !in 'A'..'Z' && it !in 'a'..'z' } || type[2] !in 'A'..'Z')
                throw BrushException("PNG sample has an invalid chunk type")
            val crc = CRC32(); crc.update(bytes, at + 4, size + 4)
            if (crc.value != u32(bytes, at + 8 + size)) throw BrushException("PNG sample $type checksum does not match")
            if (header == null && (type != "IHDR" || size != 13)) throw BrushException("PNG sample must start with one 13-byte IHDR")
            when (type) {
                "IHDR" -> {
                    if (header != null || at != 8 || size != 13) throw BrushException("PNG sample keeps a second or malformed IHDR")
                    val w = u32(bytes, at + 8); val h = u32(bytes, at + 12)
                    if (w !in 1L..maxDimension.toLong() || h !in 1L..maxDimension.toLong())
                        throw BrushException("PNG sample dimensions must be 1..$maxDimension before bitmap decoding")
                    val depth = bytes[at + 16].toInt() and 255; val color = bytes[at + 17].toInt() and 255
                    val allowed = when (color) {
                        0 -> depth in listOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> depth == 8 || depth == 16
                        3 -> depth in listOf(1, 2, 4, 8)
                        else -> false
                    }
                    if (!allowed || bytes[at + 18] != 0.toByte() || bytes[at + 19] != 0.toByte() ||
                        (bytes[at + 20].toInt() and 255) !in 0..1) throw BrushException("PNG sample uses unsupported IHDR coding")
                    header = SamplePngHeader(w.toInt(), h.toInt(), depth, color)
                }
                "PLTE" -> {
                    if (palette || idat || size == 0 || size % 3 != 0 || size > 768)
                        throw BrushException("PNG sample has a malformed or misplaced palette")
                    palette = true
                }
                "IDAT" -> {
                    if (leftIdat || header!!.colorType == 3 && !palette) throw BrushException("PNG sample has a misplaced image data chunk")
                    idat = true
                }
                "IEND" -> {
                    if (size != 0 || !idat || end != bytes.size.toLong()) throw BrushException("PNG sample needs one final empty IEND after image data")
                    ended = true
                }
                "acTL", "fcTL", "fdAT" -> throw BrushException("animated PNGs must be converted to a still sample first")
                else -> if (type[0] in 'A'..'Z') throw BrushException("PNG sample has an unsupported critical chunk $type")
            }
            if (idat && type != "IDAT") leftIdat = true
            at = end.toInt()
        }
        if (!ended || header == null) throw BrushException("PNG sample is missing its final IEND")
        return header
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 255) shl 24) or ((bytes[at + 1].toLong() and 255) shl 16) or
            ((bytes[at + 2].toLong() and 255) shl 8) or (bytes[at + 3].toLong() and 255)

    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 8192)); val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            // One lookahead byte distinguishes exactly-at-limit EOF from a too-large source.
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - count + 1).toInt())
            if (read < 0) break
            if (read == 0) {
                val one = stream.read(); if (one < 0) break
                if (count == limit) throw BrushException("PNG sample exceeds $limit encoded bytes")
                out.write(one); count++
            } else {
                if (read > limit - count) throw BrushException("PNG sample exceeds $limit encoded bytes")
                out.write(buffer, 0, read); count += read
            }
        }
        return out.toByteArray()
    }
}
