package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import java.security.MessageDigest

/**
 * What names a stored brush preview: the brush, in the version the person has it (any tuning is part of the brush), the
 * size of the picture, and the look it was drawn in. A preview is made once per brush version, not once per drawer open,
 * so two previews with the same name are the same picture. Pure.
 *
 * File name: `<sanitized id>_<16 hex of a hash of the brush file text, the style and [RENDERER_VERSION]>_<w>x<h>.png`.
 */
object BrushPreviewKey {

    /** Bump when the renderer's pictures change (a new reference paper, a new canned stroke): every stored preview is then stale. */
    const val RENDERER_VERSION = 1

    private const val ID_MAX = 48
    private val HASH = Regex("[0-9a-f]{16}")

    class Key(val idPart: String, val hash: String, val width: Int, val height: Int) {
        /** The name in memory and on disk (without the extension). */
        val stem: String get() = "${idPart}_${hash}_${width}x$height"
        val fileName: String get() = "$stem.png"
        override fun equals(other: Any?) = other is Key && other.stem == stem
        override fun hashCode() = stem.hashCode()
        override fun toString() = stem
    }

    fun of(preset: BrushPreset, width: Int, height: Int, style: BrushPreviewStyle): Key {
        val text = try { BrushJson.encode(preset) } catch (e: Exception) { preset.toString() }
        return Key(sanitize(preset.id), hash("v$RENDERER_VERSION|${style.key}|$text"), width, height)
    }

    /** [id] as a safe file-name part: letters, digits, dot and hyphen kept, every other run of characters one underscore. */
    fun sanitize(id: String): String {
        val cleaned = id.replace(Regex("[^A-Za-z0-9.\\-]+"), "_").trim('_').take(ID_MAX).trim('_')
        return cleaned.ifEmpty { "brush" }
    }

    /** The first 16 hex digits of the SHA-256 of [text]. */
    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }

    /** The parts of a stored preview's file name, or null if [fileName] is not one of ours. */
    fun parse(fileName: String): Key? {
        if (!fileName.endsWith(".png")) return null
        val stem = fileName.removeSuffix(".png")
        val size = stem.substringAfterLast('_', "")
        val rest = stem.substringBeforeLast('_', "")
        val w = size.substringBefore('x', "").toIntOrNull() ?: return null
        val h = size.substringAfter('x', "").toIntOrNull() ?: return null
        val hash = rest.substringAfterLast('_', "")
        val id = rest.substringBeforeLast('_', "")
        if (!HASH.matches(hash) || id.isEmpty()) return null
        return Key(id, hash, w, h)
    }
}
