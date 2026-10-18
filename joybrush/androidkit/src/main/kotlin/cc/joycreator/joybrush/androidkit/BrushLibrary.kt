package cc.joycreator.joybrush.androidkit

import android.util.Log
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate

/**
 * The brushes that ship with the app (JB-1.05b).
 *
 * They live in `joybrush/brushes` -- one folder per brush, each holding a `brush.json` in the
 * format [BrushJson] reads -- and are packaged into this jar under `/joybrush/brushes` exactly
 * like the shaders are. Which folders exist is the resource `/joybrush/brushes/index.txt`, one
 * name per line, so adding a brush later is a folder and a line, and nothing here has to change.
 *
 * A brush that will not decode, or that [BrushValidate] refuses, is skipped with a log line rather
 * than taken down with it: one unusable file in a pack must not cost the person every other brush.
 * The skip is not silent to the person either: [unusableSentence] says how many were lost, for the screen to show
 * once, the first time the drawer opens.
 * The list is read once and kept, because these files cannot change while the app is running.
 */
object BrushLibrary {

    private const val TAG = "JoyBrush"

    /** Where the packaged brushes live inside this jar, with a leading slash. */
    private const val ROOT = "/joybrush/brushes"

    /** The list of folder names, one per line. */
    private const val INDEX = "index.txt"

    /** What reading the packaged brushes gave: the ones that load, and the folders that did not. */
    internal class Loaded(val brushes: List<BrushPreset>, val skipped: List<String>, val indexMissing: Boolean = false)

    private var cache: Loaded? = null

    /** The built-in brushes, in `index.txt` order. Empty if none were packaged. */
    fun builtIn(): List<BrushPreset> = loaded().brushes

    /** The folders of packaged brushes that would not load (and so are missing from [builtIn]). Empty when all loaded. */
    fun unusable(): List<String> = loaded().skipped

    /** One calm sentence for the person when any packaged brush could not be loaded, else null. */
    fun unusableSentence(): String? = loaded().let { if (it.indexMissing) ALL_LOST else sentenceFor(it.skipped.size) }

    private fun loaded(): Loaded {
        val hit = cache
        if (hit != null) return hit
        val made = load(folderNames(), ::readText) { Log.w(TAG, it) }
        cache = made
        return made
    }

    /** Reads [names] through [read]; every brush that is lost is logged, and once more as a count. Pure but for [warn]. */
    internal fun load(names: List<String>?, read: (String) -> String?, warn: (String) -> Unit): Loaded {
        if (names == null) {
            warn("the packaged brush list ($INDEX) is missing: no built-in brush folder can be loaded")
            return Loaded(cc.joycreator.joybrush.core.brush.MediaPresets.ALL.toList(), emptyList(), indexMissing = true)
        }
        val out = ArrayList<BrushPreset>()
        val skipped = ArrayList<String>()
        for (name in names) {
            val json = read("$name/brush.json")
            if (json == null) {
                warn("brush $name has no packaged brush.json")
                skipped.add(name)
                continue
            }
            val preset = decode(name, json, warn)
            if (preset != null) out.add(preset) else skipped.add(name)
        }
        if (skipped.isNotEmpty()) warn("built-in brushes could not be loaded and are missing from the drawer (${skipped.size}): " + skipped.joinToString(", "))
        // Pencil, watercolour and oil (brush version 8): built from the media lab's own tables, so the two never disagree.
        out.addAll(cc.joycreator.joybrush.core.brush.MediaPresets.ALL)
        return Loaded(out, skipped)
    }

    /** The packaged brush list itself is missing: every folder brush is gone, and that is said too. */
    internal const val ALL_LOST = "The built-in brushes could not be loaded."

    internal fun sentenceFor(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 brush could not be loaded."
        else -> "$count brushes could not be loaded."
    }

    /** The folder names in `index.txt`, blanks and `#` comments dropped; null when the list itself is missing. */
    private fun folderNames(): List<String>? {
        val index = readText(INDEX) ?: return null
        val names = ArrayList<String>()
        for (line in index.lineSequence()) {
            val name = line.trim()
            if (name.isEmpty() || name.startsWith("#")) continue
            names.add(name)
        }
        return names
    }

    /** Decode, then validate, writing down every reason a brush was refused. */
    private fun decode(name: String, json: String, warn: (String) -> Unit): BrushPreset? {
        val preset = try {
            BrushJson.decode(json)
        } catch (e: BrushException) {
            warn("brush $name will not decode: " + e.message)
            null
        }
        if (preset == null) return null
        val problems = BrushValidate.validate(preset)
        if (problems.isNotEmpty()) {
            warn("brush $name is not usable: " + problems.joinToString("; "))
            return null
        }
        return preset
    }

    /** A packaged resource as text, or null when the app was built without it. */
    private fun readText(relative: String): String? {
        val stream = BrushLibrary::class.java.getResourceAsStream("$ROOT/$relative") ?: return null
        return stream.bufferedReader().use { it.readText() }
    }
}
