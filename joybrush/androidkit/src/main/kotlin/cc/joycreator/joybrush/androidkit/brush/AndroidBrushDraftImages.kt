package cc.joycreator.joybrush.androidkit.brush

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cc.joycreator.joybrush.androidkit.io.FrozenBrushAssets
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import java.io.IOException

/** Android stream decoding only; the Activity/URI launcher remains the host's responsibility. */
object AndroidBrushDraftImages {
    val verifier = FrozenBrushAssets.PngVerifier { bytes, width, height ->
        val options = BitmapFactory.Options().apply {
            inScaled = false;inPremultiplied = false;inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            ?: throw IOException("A selected brush PNG could not be decoded")
        try {
            if (bitmap.width != width || bitmap.height != height)
                throw IOException("The selected brush PNG differs from its reserved dimensions")
        } finally { bitmap.recycle() }
    }
    fun session(recipe: BrushPreset, resources: FrozenBrushPngs): DraftBrushImageSession =
        DraftBrushImageSession(recipe,resources,verifier)
    fun loader(): BrushPngPickerLoader = BrushPngPickerLoader(verifier)
}
