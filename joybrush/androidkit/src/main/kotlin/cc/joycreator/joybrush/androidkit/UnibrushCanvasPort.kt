package cc.joycreator.joybrush.androidkit

import android.graphics.Bitmap
import cc.joycreator.joybrush.androidkit.preview.BrushPreviewRequest
import cc.joycreator.joybrush.androidkit.preview.BrushPreviewTicket
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.input.StabilizerPreference

/** Owned capability boundary. Only the Lead-integrated View implements it; no fallback resource renderer. */
interface UnibrushCanvasPort {
    var stabilizerPreference: StabilizerPreference
    var secondaryColorArgb: Int?
    var brushPngs: ((BrushPreset) -> FrozenBrushPngs?)?
    fun renderBrushPreview(snapshot: BrushPreviewRequest, onRetired: (() -> Unit)?,
        onBitmap: (Bitmap?) -> Unit): BrushPreviewTicket
}
