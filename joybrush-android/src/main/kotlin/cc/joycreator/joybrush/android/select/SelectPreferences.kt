package cc.joycreator.joybrush.android.select

import android.content.Context

/** K18: the finger choice survives reopening the app; the Paint shelf default belongs to the activity. */
class SelectPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("joybrush_select", Context.MODE_PRIVATE)

    var fingerSelects: Boolean
        get() = preferences.getBoolean("finger_selects", false)
        set(value) { preferences.edit().putBoolean("finger_selects", value).apply() }

    var pickLocked: Boolean
        get() = preferences.getBoolean("pick_locked", false)
        set(value) { preferences.edit().putBoolean("pick_locked", value).putBoolean("locked_option_revealed", true).apply() }

    /** D2: reveal after encountering a lock, and never strand an existing persisted choice. */
    val lockedOptionRevealed: Boolean
        get() = preferences.getBoolean("locked_option_revealed", false) || preferences.contains("pick_locked")

    fun observeLockedLines(hasLocked: Boolean) {
        if (hasLocked && !lockedOptionRevealed)
            preferences.edit().putBoolean("locked_option_revealed", true).apply()
    }

    var dimRest: Boolean
        get() = preferences.getBoolean("dim_rest", true)
        set(value) { preferences.edit().putBoolean("dim_rest", value).apply() }
}
