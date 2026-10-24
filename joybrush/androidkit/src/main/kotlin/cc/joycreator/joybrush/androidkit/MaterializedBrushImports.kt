package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.androidkit.gl.PngSamplePreflight
import cc.joycreator.joybrush.androidkit.io.PngWriter
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.SampleMaskLimits
import cc.joycreator.joybrush.core.brush.imports.AbrImport
import cc.joycreator.joybrush.core.brush.imports.ImportedPngDimensions
import cc.joycreator.joybrush.core.brush.imports.ImportedTipPngCodec
import cc.joycreator.joybrush.core.brush.imports.KritaImport
import cc.joycreator.joybrush.core.brush.imports.MaterializedImportLibrary
import cc.joycreator.joybrush.core.brush.imports.MaterializedImportOptions
import cc.joycreator.joybrush.core.brush.imports.ProcreateImport

/** New import boundary with real PNG assets; it does not alter or reinterpret installed old presets. */
object MaterializedBrushImports {
    val pngCodec: ImportedTipPngCodec = object : ImportedTipPngCodec {
        override fun encodeAlpha(width: Int, height: Int, alpha: ByteArray): ByteArray {
            if (width !in 1..SampleMaskLimits.MAX_IMAGE_DIMENSION || height !in 1..SampleMaskLimits.MAX_IMAGE_DIMENSION ||
                width.toLong() * height > SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS || alpha.size.toLong() != width.toLong() * height)
                throw BrushException("sampled tip alpha exceeds the source pixel budget or is incomplete")
            val rgba = ByteArray(alpha.size * 4)
            for (i in alpha.indices) {
                rgba[i * 4] = 255.toByte(); rgba[i * 4 + 1] = 255.toByte(); rgba[i * 4 + 2] = 255.toByte()
                rgba[i * 4 + 3] = alpha[i]
            }
            return PngWriter.encode(width, height, rgba)
        }

        override fun inspect(png: ByteArray): ImportedPngDimensions = PngSamplePreflight.inspect(png).let {
            ImportedPngDimensions(it.width, it.height)
        }
    }

    fun abr(bytes: ByteArray, prefix: String): MaterializedImportLibrary = AbrImport.convertWithAssets(bytes, prefix, pngCodec)

    /** Null uses Procreate's documented white-is-opaque convention; explicit options override it. */
    fun procreateBrush(bytes: ByteArray, prefix: String, options: MaterializedImportOptions? = null): MaterializedImportLibrary =
        if (options == null) ProcreateImport.convertBrushWithAssets(bytes, prefix, pngCodec)
        else ProcreateImport.convertBrushWithAssets(bytes, prefix, pngCodec, options)

    fun procreateSet(bytes: ByteArray, prefix: String, options: MaterializedImportOptions? = null): MaterializedImportLibrary =
        if (options == null) ProcreateImport.convertBrushSetWithAssets(bytes, prefix, pngCodec)
        else ProcreateImport.convertBrushSetWithAssets(bytes, prefix, pngCodec, options)

    fun kritaKpp(bytes: ByteArray, prefix: String, options: MaterializedImportOptions = MaterializedImportOptions()): MaterializedImportLibrary =
        KritaImport.convertKppWithAssets(bytes, prefix, pngCodec, options)

    fun kritaBundle(bytes: ByteArray, prefix: String, options: MaterializedImportOptions = MaterializedImportOptions()): MaterializedImportLibrary =
        KritaImport.convertBundleWithAssets(bytes, prefix, pngCodec, options)
}
