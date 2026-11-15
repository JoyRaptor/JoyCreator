package cc.joycreator.joybrush.android.chrome

import android.content.Context

/**
 * TAB-1 K10 (owner, 11-15): starred brushes and the ones used most recently, for the brush panel's
 * "Mine" list: "ordered by your most recent, as well as starred favourites that are always at the top".
 *
 * Kept in the app's own preferences by brush id: a star or a recent pick is a convenience of this
 * phone, not part of a drawing or of a brush file.
 */
class BrushMarks(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starred ids, the newest star first. */
    fun starred(): List<String> = read(K_STARS)

    /** Recently picked ids, the latest first, at most [RECENT_MAX]. */
    fun recent(): List<String> = read(K_RECENT)

    fun isStarred(id: String): Boolean = id in starred()

    fun toggleStar(id: String) {
        val now = starred().toMutableList()
        if (!now.remove(id)) now.add(0, id)
        write(K_STARS, now)
    }

    fun noteUsed(id: String) {
        val now = recent().toMutableList()
        now.remove(id)
        now.add(0, id)
        write(K_RECENT, now.take(RECENT_MAX))
    }

    /** Starred first (newest star first), then recent ones that are not starred. */
    fun mine(): List<String> {
        val stars = starred()
        return stars + recent().filter { it !in stars }
    }

    private fun read(key: String): List<String> =
        prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun write(key: String, ids: List<String>) {
        prefs.edit().putString(key, ids.joinToString("\n")).apply()
    }

    private companion object {
        const val PREFS = "jb_brush_marks"
        const val K_STARS = "starred"
        const val K_RECENT = "recent"
        const val RECENT_MAX = 20
    }
}
