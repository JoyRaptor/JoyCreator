package cc.joycreator.joybrush.android

import android.content.SharedPreferences
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR

/** Tool preference, not drawing content. Existing drawings and frozen brush definitions stay portable. */
internal class BrushLinePreferences(private val prefs: SharedPreferences) {
    fun enabled(brush: BrushPreset?): Boolean {
        if (!canSwitch(brush)) return false
        val key = PREFIX + requireNotNull(brush).id
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        return brush.engine == ENGINE_WATERCOLOUR || brush.id in LINE_IDS
    }

    fun set(brush: BrushPreset?, enabled: Boolean) {
        if (!canSwitch(brush)) return
        prefs.edit().putBoolean(PREFIX + requireNotNull(brush).id, enabled).apply()
    }

    companion object {
        private const val PREFIX = "joybrush.brushMakesLines."
        // K25: pixel-reading/stateful engines cannot become vector lines, even with a stale saved ON flag.
        fun canSwitch(brush: BrushPreset?): Boolean = brush != null && brush.engine !in PIXELS_ALWAYS

        private val PIXELS_ALWAYS = setOf("media", "smudge", "push", "wet", "blur", "clone", "paste")
        private val LINE_IDS = setOf(
            "joybrush.ink", // The shipped Ink brush, not a name/substring guess for imported painting brushes.
            "joybrush.lines.brush_pen", "joybrush.lines.chisel_marker", "joybrush.lines.dash_pen",
            "joybrush.lines.fill_pen", "joybrush.lines.fineliner", "joybrush.lines.pen",
            "joybrush.lines.shading_pencil", "joybrush.lines.sketch_pencil", "joybrush.lines.watercolour",
            "joybrush.lines.wire",
        )
    }
}
