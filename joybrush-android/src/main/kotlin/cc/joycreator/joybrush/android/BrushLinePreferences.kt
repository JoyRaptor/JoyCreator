package cc.joycreator.joybrush.android

import android.content.SharedPreferences
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR

/** Tool preference, not drawing content. Existing drawings and frozen brush definitions stay portable. */
internal class BrushLinePreferences(private val prefs: SharedPreferences) {
    fun enabled(brush: BrushPreset?): Boolean {
        val key = brush?.let { PREFIX + it.id }
        if (key != null && prefs.contains(key)) return prefs.getBoolean(key, false)
        return brush?.engine == ENGINE_WATERCOLOUR || prefs.getBoolean(LEGACY, false)
    }

    fun set(brush: BrushPreset?, enabled: Boolean) {
        prefs.edit().putBoolean(brush?.let { PREFIX + it.id } ?: LEGACY, enabled).apply()
    }

    companion object {
        private const val PREFIX = "joybrush.brushMakesLines."
        const val LEGACY = "joybrush.editableLines"
    }
}
