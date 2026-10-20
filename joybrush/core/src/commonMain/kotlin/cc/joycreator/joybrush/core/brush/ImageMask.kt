package cc.joycreator.joybrush.core.brush

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Proposed defaults; encoded-PNG byte/decode-work budgets belong to the platform import boundary. */
object SampleMaskLimits {
    const val MAX_IMAGE_DIMENSION = 2048
    const val MAX_TOTAL_SOURCE_PIXELS = 4L * 1024 * 1024
    // Separate upload-storage cap: padding and shelf fragmentation are not decoded source pixels.
    const val MAX_ATLAS_PIXELS = 8L * 1024 * 1024
}

/** Immutable R8 pixels, top row first. Neither input arrays nor exported arrays can mutate a mask. */
class ImageMask private constructor(val width: Int, val height: Int, private val r8: ByteArray) {
    val longSide: Int = max(width, height)
    // Bilinear support includes half a native texel beyond the source image's physical edge.
    // Large stamps made from tiny images must include these in their dab bounds.
    val supportHalfWidth: Float get() = (width + 1f) / longSide
    val supportHalfHeight: Float get() = (height + 1f) / longSide
    fun bytes(): ByteArray = r8.copyOf()

    internal fun pixel(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height)
        r8[y * width + x].toInt() and 255 else 0

    /**
     * q is centered on the image with its long side spanning -1..1; native aspect is preserved.
     * uv = (q * longSide / imageSize + 1)/2, then texel = uv*imageSize - .5.
     * Outside texels are transparent, including inversion: no CLAMP_TO_EDGE color extension.
     */
    fun sample(qx: Float, qy: Float): Float {
        if (!qx.isFinite() || !qy.isFinite()) return 0f
        val x = qx.toDouble() * longSide * .5 + (width - 1) * .5
        val y = qy.toDouble() * longSide * .5 + (height - 1) * .5
        if (x <= -1.0 || x >= width || y <= -1.0 || y >= height) return 0f
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val a = pixel(x0, y0) / 255f; val b = pixel(x0 + 1, y0) / 255f
        val c = pixel(x0, y0 + 1) / 255f; val d = pixel(x0 + 1, y0 + 1) / 255f
        val top = a + (b - a) * fx; val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    companion object {
        private fun count(width: Int, height: Int, maxDimension: Int): Int {
            if (maxDimension <= 0 || width !in 1..maxDimension || height !in 1..maxDimension)
                throw BrushException("sample image must be 1..$maxDimension pixels on each side")
            val count = width.toLong() * height
            if (count > SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS || count > Int.MAX_VALUE)
                throw BrushException("sample image exceeds the decoded pixel budget")
            return count.toInt()
        }

        fun fromR8(width: Int, height: Int, bytes: ByteArray,
                   maxDimension: Int = SampleMaskLimits.MAX_IMAGE_DIMENSION): ImageMask {
            val count = count(width, height, maxDimension)
            if (bytes.size != count) throw BrushException("sample mask needs exactly $count R8 bytes")
            return ImageMask(width, height, bytes.copyOf())
        }

        /** ARGB must be straight sRGB (Android decode: inPremultiplied=false and inScaled=false). */
        fun fromArgb(width: Int, height: Int, argb: IntArray, coverage: SampleCoverage,
                     invert: Boolean = false, maxDimension: Int = SampleMaskLimits.MAX_IMAGE_DIMENSION): ImageMask {
            val count = count(width, height, maxDimension)
            if (argb.size != count) throw BrushException("sample image needs exactly $count ARGB pixels")
            val bytes = ByteArray(count)
            for (i in argb.indices) {
                val p = argb[i]
                val alpha = (p ushr 24) and 255
                val value = when (coverage) {
                    SampleCoverage.alpha -> alpha
                    SampleCoverage.luminance -> {
                        val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
                        val luminance = (.2126 * r + .7152 * g + .0722 * b) / 255.0
                        // Luminance inversion preserves source transparency. Alpha inversion
                        // intentionally inverts alpha itself inside the image rectangle.
                        (alpha * (if (invert) luminance else 1.0 - luminance)).roundToInt().coerceIn(0, 255)
                    }
                }
                // Invert only actual image pixels, never the padded/out-of-image zero samples.
                bytes[i] = (if (invert && coverage == SampleCoverage.alpha) 255 - value else value).toByte()
            }
            return ImageMask(width, height, bytes)
        }
    }
}

/** Source-image edges in atlas normalized coordinates; row zero is the atlas's top row. */
data class SampleAtlasRect(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                           val imageWidth: Int, val imageHeight: Int, val x: Int, val y: Int)

/** Immutable variable-rectangle R8 atlas, with a transparent native-pixel gutter around every image. */
class SampleMaskAtlas private constructor(val width: Int, val height: Int, private val r8: ByteArray,
                                         rects: List<SampleAtlasRect>) {
    private val entries = rects.toList()
    val size: Int get() = entries.size
    fun rect(index: Int): SampleAtlasRect = entries[index]
    fun rects(): List<SampleAtlasRect> = entries.toList()
    fun bytes(): ByteArray = r8.copyOf()
    private fun pixel(x: Int, y: Int): Float = (r8[y * width + x].toInt() and 255) / 255f

    /** CPU counterpart of the GPU atlas lookup, including its logical-image support guard. */
    fun sample(index: Int, qx: Float, qy: Float): Float {
        val rect = entries[index]
        if (!qx.isFinite() || !qy.isFinite()) return 0f
        val longSide = max(rect.imageWidth, rect.imageHeight)
        val sx = qx.toDouble() * longSide * .5 + (rect.imageWidth - 1) * .5
        val sy = qy.toDouble() * longSide * .5 + (rect.imageHeight - 1) * .5
        // Essential even with a gutter: far outside this image must not sample a neighbor.
        if (sx <= -1.0 || sx >= rect.imageWidth || sy <= -1.0 || sy >= rect.imageHeight) return 0f
        val x = rect.x + sx; val y = rect.y + sy
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val a = pixel(x0, y0); val b = pixel(x0 + 1, y0)
        val c = pixel(x0, y0 + 1); val d = pixel(x0 + 1, y0 + 1)
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy
    }

    companion object {
        private data class Cell(val index: Int, val x: Int, val y: Int)
        private data class Shelf(val y: Int, val height: Int, var cursor: Int)

        /** All dimensions, aggregate decoded pixels and final upload area are checked before allocation. */
        fun pack(images: List<ImageMask>, maxTextureSize: Int,
                 maxSourcePixels: Long = SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS,
                 maxAtlasPixels: Long = SampleMaskLimits.MAX_ATLAS_PIXELS): SampleMaskAtlas {
            if (images.size !in 1..SampledTips.MAX_IMAGES) throw BrushException("an atlas needs 1..16 images")
            if (maxTextureSize <= 0 || maxSourcePixels <= 0 || maxAtlasPixels <= 0)
                throw BrushException("sample image texture and pixel budgets must be positive")
            var decoded = 0L
            for (image in images) {
                decoded += image.width.toLong() * image.height
                if (decoded > maxSourcePixels) throw BrushException("sample set exceeds $maxSourcePixels decoded pixels")
                if (image.width.toLong() + 2 > maxTextureSize || image.height.toLong() + 2 > maxTextureSize)
                    throw BrushException("sample image plus its transparent border exceeds the GPU texture size $maxTextureSize")
            }
            // Stable first-fit-decreasing shelf packing; original indices survive the sorting.
            val ordered = images.indices.sortedWith(compareByDescending<Int> { images[it].height }
                .thenByDescending { images[it].width }.thenBy { it })
            val shelves = ArrayList<Shelf>(); val cells = ArrayList<Cell>(images.size)
            var usedWidth = 0; var usedHeight = 0
            for (index in ordered) {
                val image = images[index]; val w = image.width + 2; val h = image.height + 2
                var shelf = shelves.firstOrNull { it.height >= h && it.cursor.toLong() + w <= maxTextureSize }
                if (shelf == null) {
                    if (usedHeight.toLong() + h > maxTextureSize) throw BrushException("sample set does not fit the GPU texture size $maxTextureSize")
                    shelf = Shelf(usedHeight, h, 0); shelves.add(shelf); usedHeight += h
                }
                cells.add(Cell(index, shelf.cursor + 1, shelf.y + 1))
                shelf.cursor += w; usedWidth = max(usedWidth, shelf.cursor)
            }
            val pixels = usedWidth.toLong() * usedHeight
            if (pixels > maxAtlasPixels || pixels > Int.MAX_VALUE)
                throw BrushException("sample atlas exceeds $maxAtlasPixels upload pixels after border/packing")
            val bytes = ByteArray(pixels.toInt())
            val rects = arrayOfNulls<SampleAtlasRect>(images.size)
            for (cell in cells) {
                val image = images[cell.index]
                for (y in 0 until image.height) for (x in 0 until image.width)
                    bytes[(cell.y + y) * usedWidth + cell.x + x] = image.pixel(x, y).toByte()
                rects[cell.index] = SampleAtlasRect(cell.x.toFloat() / usedWidth, cell.y.toFloat() / usedHeight,
                    (cell.x + image.width).toFloat() / usedWidth, (cell.y + image.height).toFloat() / usedHeight,
                    image.width, image.height, cell.x, cell.y)
            }
            return SampleMaskAtlas(usedWidth, usedHeight, bytes, rects.map { checkNotNull(it) })
        }
    }
}
