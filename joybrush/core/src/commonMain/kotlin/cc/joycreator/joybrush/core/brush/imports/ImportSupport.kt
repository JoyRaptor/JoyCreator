package cc.joycreator.joybrush.core.brush.imports

/**
 * What the three Phase 8 importers have in common, in one file so that "the same rule" is a fact
 * about the code rather than a promise made three times in three markdown files.
 *
 * JB-8.01 creates this file (spec Decision 2). JB-8.02 and JB-8.04 **read** it and do not edit it.
 */

/** A whole imported pack: the brushes that converted, and the ones that were refused with reasons. */
data class ImportLibrary(
    val brushes: List<ImportResult>,
    val refused: List<RefusedBrush>,
) {
    /** One sentence for a screen: how many converted, how many were refused and why. Never empty. */
    fun summary(): String {
        val converted = brushes.size
        val head = "$converted ${if (converted == 1) "brush" else "brushes"} converted"
        if (refused.isEmpty()) {
            return if (converted == 0) {
                "$head; nothing in this file could be read as a brush"
            } else {
                "$head, and none refused"
            }
        }
        val named = refused.take(4).joinToString("; ") { "${it.name}: ${it.reason}" }
        val rest = if (refused.size > 4) " (and ${refused.size - 4} more)" else ""
        return "$head, ${refused.size} refused: $named$rest"
    }
}

data class RefusedBrush(val index: Int, val name: String, val reason: String)

/**
 * The most `extensions` text one brush may carry, in characters, across every key.
 *
 * 256 KiB, because all three Phase 8 importers say 256 KiB and R23 says a number that is written
 * three times is one writer believing it three times. It counts the base64 of a stored tip or grain
 * image as well as every unmapped setting, so Decision 4a and Decision 5 decide what happens when
 * an image does not fit — not a fourth number in a fourth place.
 */
const val MAX_EXTENSION_BYTES = 256 * 1024

/**
 * The exact sentence every Phase 8 importer puts in a warning when a tip or grain texture is
 * carried but not drawn yet. LEAD_RULINGS R40, option (a), uniform for JB-8.01 / 8.02 / 8.04.
 *
 * It is `const` and not a builder-time constant so that the three importers cannot drift apart, and
 * the test for it in each row asserts the *string*, not the message around it.
 */
const val TEXTURE_NOT_DRAWN = "this brush's texture is kept but not drawn yet"

/**
 * A library-unique id for an imported brush, from the caller's [prefix] and the brush's own [name].
 *
 * One sanitiser for all three Phase 8 importers, because the three rules below are the ones that
 * matter and a second copy of them is three chances to disagree: lower-case; every run of characters
 * that is not `a`–`z`, `0`–`9`, `.`, `-` or `_` becomes a single `-`; the result never begins or ends
 * with `-`; empty parts are dropped; a result with nothing left in it is `"brush"`. Ids become file
 * and folder names, so this is the one place the "ids are safe" rule and the "a name from a stranger"
 * rule meet.
 */
fun brushId(prefix: String, name: String): String {
    fun clean(s: String) = s.lowercase()
        .map { c -> if (c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') c else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
    val parts = listOf(clean(prefix), clean(name)).filter { it.isNotEmpty() }
    return parts.joinToString(".").ifEmpty { "brush" }
}
