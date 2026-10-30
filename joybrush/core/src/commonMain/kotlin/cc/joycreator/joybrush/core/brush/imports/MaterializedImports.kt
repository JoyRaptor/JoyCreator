package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.brush.SampleCoverage
import cc.joycreator.joybrush.core.brush.SampleMaskLimits
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import cc.joycreator.joybrush.core.brush.TipSpec

data class ImportedPngDimensions(val width: Int, val height: Int)

/** Platform boundary: inspect validates the complete PNG container, not just an IHDR claim. */
interface ImportedTipPngCodec {
    fun encodeAlpha(width: Int, height: Int, alpha: ByteArray): ByteArray
    fun inspect(png: ByteArray): ImportedPngDimensions
}

/** Explicit PNG interpretation; importing never guesses a mask convention from its file name. */
data class MaterializedImportOptions(val coverage: SampleCoverage = SampleCoverage.alpha, val invert: Boolean = false)

/** A converted definition with its real, detached PNGs. Duplicate brush names cannot alias assets. */
class MaterializedBrushImport internal constructor(val result: ImportResult, val images: FrozenBrushPngs) {
    val preset get() = result.preset
    val warnings get() = result.warnings
    val assets: Map<String, ByteArray> get() = images.paths.associateWith { images.requirePng(it) }
}

data class MaterializedImportLibrary(val brushes: List<MaterializedBrushImport>, val refused: List<RefusedBrush>) {
    fun library(): ImportLibrary = ImportLibrary(brushes.map { it.result }, refused)
}

/** One bounded opt-in conversion. Existing import signatures never create this session. */
internal class MaterializedImportSession(private val codec: ImportedTipPngCodec,
                                         private val options: MaterializedImportOptions = MaterializedImportOptions()) {
    private val limits = FrozenBrushPngs.Limits()
    private val kept = ArrayList<FrozenBrushPngs>()
    private var encodedBytes = 0L

    internal class Png(val bytes: ByteArray, val dimensions: ImportedPngDimensions)

    fun png(bytes: ByteArray): Png {
        if (bytes.isEmpty() || bytes.size > limits.maxBytesPerImage)
            throw BrushException("the sampled tip PNG exceeds ${limits.maxBytesPerImage} encoded bytes")
        val size = codec.inspect(bytes)
        dimensions(size.width, size.height)
        return Png(bytes, size)
    }

    fun alpha(width: Int, height: Int, bytes: ByteArray): Png {
        dimensions(width, height)
        if (bytes.size.toLong() != width.toLong() * height) throw BrushException("the sampled tip has incomplete alpha pixels")
        val result = png(codec.encodeAlpha(width, height, bytes))
        if (result.dimensions != ImportedPngDimensions(width, height))
            throw BrushException("the sampled tip PNG encoder changed its source dimensions")
        return result
    }

    fun tip(tip: TipSpec): TipSpec = tip.copy(source = "image", image = TIP_FILE,
        sampled = SampledTipSpec(listOf(TIP_FILE), coverage = options.coverage, invert = options.invert))

    /** Call only after the entire definition passes validation; a refused brush reserves no assets. */
    fun record(result: ImportResult, png: Png?): ImportResult {
        val bytes = png?.bytes?.size?.toLong() ?: 0L
        if (bytes > limits.maxTotalBytes - encodedBytes)
            throw BrushException("the imported pack exceeds ${limits.maxTotalBytes} materialized PNG bytes")
        val frozen = if (png == null) FrozenBrushPngs.empty()
            else FrozenBrushPngs.snapshot(mapOf(TIP_FILE to png.bytes), limits)
        kept.add(frozen); encodedBytes += bytes
        return result
    }

    fun finish(library: ImportLibrary): MaterializedImportLibrary {
        check(kept.size == library.brushes.size) { "materialized import result order changed" }
        return MaterializedImportLibrary(library.brushes.mapIndexed { i, result -> MaterializedBrushImport(result, kept[i]) },
            library.refused.toList())
    }

    private fun dimensions(width: Int, height: Int) {
        if (width !in 1..SampleMaskLimits.MAX_IMAGE_DIMENSION || height !in 1..SampleMaskLimits.MAX_IMAGE_DIMENSION ||
            width.toLong() * height > SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS)
            throw BrushException("the sampled tip exceeds ${SampleMaskLimits.MAX_IMAGE_DIMENSION}px or the decoded pixel budget")
    }

    companion object { const val TIP_FILE = "tip.png" }
}
