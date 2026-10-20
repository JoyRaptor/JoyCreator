package cc.joycreator.joybrush.androidkit.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.ImageMask
import cc.joycreator.joybrush.core.brush.SampledTipSpec
import cc.joycreator.joybrush.core.brush.SampledTipSet

/** Bitmap handles are sequential and recycled on success, dimension mismatch and pixel-read failure. */
object AndroidSamplePngDecoder {
    fun resolve(prepared: PreparedPngSamples, spec: SampledTipSpec): SampledTipSet =
        SampledTipSet(prepared.paths(), decode(prepared, spec), spec.coverage, spec.invert)

    fun decode(prepared: PreparedPngSamples, spec: SampledTipSpec): List<ImageMask> {
        if (prepared.paths() != spec.images) throw BrushException("PNG preparation does not match this sampled recipe")
        val masks = linkedMapOf<String, ImageMask>()
        for ((path, source) in prepared.unique()) {
            val options = BitmapFactory.Options().apply {
                inScaled = false; inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                if (Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val bitmap = BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size, options)
                ?: throw BrushException("PNG sample could not be decoded: $path")
            try {
                val header = source.header
                if (bitmap.width != header.width || bitmap.height != header.height)
                    throw BrushException("PNG decoder dimensions do not match the reserved header: $path")
                val argb = IntArray(header.width * header.height)
                bitmap.getPixels(argb, 0, header.width, 0, 0, header.width, header.height)
                masks[path] = ImageMask.fromArgb(header.width, header.height, argb, spec.coverage, spec.invert)
            } finally { bitmap.recycle() }
        }
        return List(prepared.size) { masks.getValue(prepared.path(it)) }
    }
}
