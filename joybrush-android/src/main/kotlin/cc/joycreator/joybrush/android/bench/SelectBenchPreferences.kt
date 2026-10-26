package cc.joycreator.joybrush.android.bench

import android.content.SharedPreferences

/** Restores only the explicitly owned preference keys after a Select bench run. */
internal class SelectBenchPreferences(prefs: SharedPreferences, keys: Set<String>) {
    private sealed interface SavedValue {
        data class StringValue(val value: String) : SavedValue
        data class BooleanValue(val value: Boolean) : SavedValue
        data class IntValue(val value: Int) : SavedValue
        data class LongValue(val value: Long) : SavedValue
        data class FloatValue(val value: Float) : SavedValue
        data class StringSetValue(val value: Set<String>) : SavedValue
    }

    private data class SavedEntry(val present: Boolean, val value: SavedValue?)

    private val preferences = prefs
    private val snapshot: Map<String, SavedEntry>
    private var restored = false

    init {
        val requested = keys.toSet()
        val all = prefs.all
        snapshot = requested.associateWith { key ->
            if (!all.containsKey(key)) {
                SavedEntry(present = false, value = null)
            } else {
                SavedEntry(present = true, value = snapshotValue(key, all[key]))
            }
        }
    }

    /** Restores the captured subset once; later preference changes and unrelated keys are preserved. */
    @Synchronized
    fun restore() {
        if (restored) return
        val editor = preferences.edit()
        snapshot.forEach { (key, entry) ->
            if (!entry.present) {
                editor.remove(key)
            } else {
                when (val value = checkNotNull(entry.value)) {
                    is SavedValue.StringValue -> editor.putString(key, value.value)
                    is SavedValue.BooleanValue -> editor.putBoolean(key, value.value)
                    is SavedValue.IntValue -> editor.putInt(key, value.value)
                    is SavedValue.LongValue -> editor.putLong(key, value.value)
                    is SavedValue.FloatValue -> editor.putFloat(key, value.value)
                    is SavedValue.StringSetValue -> editor.putStringSet(key, value.value.toSet())
                }
            }
        }
        editor.apply()
        restored = true
    }

    private fun snapshotValue(key: String, value: Any?): SavedValue = when (value) {
        is String -> SavedValue.StringValue(value)
        is Boolean -> SavedValue.BooleanValue(value)
        is Int -> SavedValue.IntValue(value)
        is Long -> SavedValue.LongValue(value)
        is Float -> SavedValue.FloatValue(value)
        is Set<*> -> {
            require(value.all { it is String }) { "Unsupported SharedPreferences value for '$key'" }
            SavedValue.StringSetValue(value.filterIsInstance<String>().toSet())
        }
        else -> throw IllegalArgumentException("Unsupported SharedPreferences value for '$key'")
    }
}
