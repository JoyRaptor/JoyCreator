package cc.joycreator.joybrush.android.select

import android.content.Context

/** K18: the finger choice survives reopening the app; the Paint shelf default belongs to the activity. */
class SelectPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("joybrush_select", Context.MODE_PRIVATE)

    var fingerSelects: Boolean
        get() = preferences.getBoolean("finger_selects", false)
        set(value) { preferences.edit().putBoolean("finger_selects", value).apply() }
}
