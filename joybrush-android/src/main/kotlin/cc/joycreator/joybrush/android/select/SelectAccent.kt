package cc.joycreator.joybrush.android.select

import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.android.chrome.ChromeKit

/** Reversible colour trial: cyan retains the shared selection meaning; room colours remain an alternative. */
enum class SelectAccent {
    CYAN, CYAN_GRADIENT, ROOM_GRADIENT;

    fun shader(kit: ChromeKit, width: Float, height: Float): Shader {
        val cyan = kit.p.stateSelected
        val colours = when (this) {
            CYAN -> intArrayOf(cyan, cyan)
            CYAN_GRADIENT -> intArrayOf(cyan, ColorUtils.blendARGB(cyan, kit.p.ground, 0.15f))
            ROOM_GRADIENT -> intArrayOf(kit.p.roomEnd, kit.p.roomStart)
        }
        // A true 45-degree diagonal, also on wide pills (same as CSS 135deg).
        val reach = (width + height) / 4f
        return LinearGradient(width / 2f - reach, height / 2f - reach,
            width / 2f + reach, height / 2f + reach, colours, null, Shader.TileMode.CLAMP)
    }
}
