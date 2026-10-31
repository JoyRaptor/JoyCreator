package cc.joycreator.joybrush.android.chrome

import android.content.SharedPreferences
import cc.joycreator.joybrush.core.input.StabilizerPreference
import kotlinx.serialization.json.Json

/** Separate from old smoothing. Historical brush settings/tuning are never rewritten. */
class StabilizerPreferenceStore(private val prefs: SharedPreferences) {
    fun load(): StabilizerPreference {
        val text=prefs.getString(KEY,null)?:return StabilizerPreference()
        if(text.length>4096)return StabilizerPreference()
        return try { Json.decodeFromString(StabilizerPreference.serializer(),text).also {
            require(it.mine.problems().isEmpty())
        } } catch(_:Exception) { StabilizerPreference() }
    }
    fun save(value:StabilizerPreference) {
        require(value.mine.problems().isEmpty()){value.mine.problems().joinToString("; ")}
        prefs.edit().putString(KEY,Json.encodeToString(StabilizerPreference.serializer(),value)).apply()
    }
    companion object { const val KEY="unibrush.stabilizer.v1" }
}

/** Lead changes this only after the renderer AND recorded resource publication gates are satisfied. */
object UnibrushActivation { const val SHIPPING_ENABLED=true }
