package cc.joycreator.joybrush.android.chrome

import android.content.SharedPreferences

/** The secondary mixing colour is independent of brush recipes and their attribution. */
class UnibrushPalettePreferenceStore(private val prefs: SharedPreferences) {
    /** Preserves all four ARGB bytes, including a deliberately transparent colour. */
    var secondaryColorArgb: Int
        get() = try {
            prefs.getInt(SECONDARY_KEY, DEFAULT_SECONDARY_ARGB)
        } catch (_: ClassCastException) {
            DEFAULT_SECONDARY_ARGB
        }
        set(value) {
            prefs.edit().putInt(SECONDARY_KEY, value).apply()
        }

    companion object {
        const val SECONDARY_KEY = "unibrush.secondary-argb.v1"
        const val DEFAULT_SECONDARY_ARGB: Int = -1 // Opaque white, 0xffffffff.
    }
}
