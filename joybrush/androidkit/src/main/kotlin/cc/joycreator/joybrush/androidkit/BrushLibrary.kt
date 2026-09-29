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
 * The list is read once and kept, because these files cannot change while the app is running.
 */
object BrushLibrary {

    private const val TAG = "JoyBrush"

    /** Where the packaged brushes live inside this jar, with a leading slash. */
    private const val ROOT = "/joybrush/brushes"

    /** The list of folder names, one per line. */
    private const val INDEX = "index.txt"

    private var cache: List<BrushPreset>? = null

    /** The built-in brushes, in `index.txt` order. Empty if none were packaged. */
    fun builtIn(): List<BrushPreset> {
        val hit = cache
        if (hit != null) return hit
        val out = ArrayList<BrushPreset>()
        for (name in folderNames()) {
            val json = readText("$name/brush.json")
            if (json == null) {
                Log.w(TAG, "brush $name has no packaged brush.json")
                continue
            }
            val preset = decode(name, json)
            if (preset != null) out.add(preset)
        }
        cache = out
        return out
    }

    /** The folder names in `index.txt`, blanks and `#` comments dropped. */
    private fun folderNames(): List<String> {
        val index = readText(INDEX) ?: return emptyList()
        val names = ArrayList<String>()
        for (line in index.lineSequence()) {
            val name = line.trim()
            if (name.isEmpty() || name.startsWith("#")) continue
            names.add(name)
        }
        return names
    }

    /** Decode, then validate, writing down every reason a brush was refused. */
    private fun decode(name: String, json: String): BrushPreset? {
        val preset = try {
            BrushJson.decode(json)
        } catch (e: BrushException) {
            Log.w(TAG, "brush $name will not decode: " + e.message)
            null
        }
        if (preset == null) return null
        val problems = BrushValidate.validate(preset)
        if (problems.isNotEmpty()) {
            Log.w(TAG, "brush $name is not usable: " + problems.joinToString("; "))
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
