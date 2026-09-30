package cc.joycreator.joybrush.core.brush.imports

/**
 * What the Phase 8 importers have in common, in one file so that "the same rule" is a fact about the
 * code rather than a promise made three times in three markdown files.
 *
 * JB-8.01 created this file (its spec Decision 2). JB-8.02 and JB-8.04 **read** it.
 * **JB-8.04b is the first row to EDIT it**, and it edits it for one reason: the DEFLATE bomb caps had
 * nowhere else to live. `inflateRaw` is one function, but the numbers that make inflating safe were
 * `ProcreateImport`'s, so a second importer either shared the numbers or wrote its own. R23 settles which.
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

// ---- the DEFLATE bomb caps, promoted from ProcreateImport by JB-8.04b ------------------------------------
//
// **These two numbers are a security bound and this row does not get a vote on them.** They are *moved*,
// not chosen: the landed values, their KDoc and their derivation are in the contract below, and a value
// that changed here would be a decision nobody made. What this row decides is **where they live**.
//
// A zip bomb is refused twice, and the two refusals answer two different questions. The **ratio** answers
// "is this entry trying to be a bomb?", which is arithmetic over two numbers the file states and costs
// nothing to check. The **output ceiling** answers "how much may this build be made to hold?", which is
// the only one of the two that a file cannot talk its way past — the declared size is not believed, and
// `inflateRaw`'s own `maxOut` check fires *before* the bytes are written.

/**
 * How much bigger than its payload an entry may claim to be: 200:1.
 *
 * **Checked before anything is inflated, and that is the whole point of the number.** DEFLATE
 * reaches about 1 038:1 on a run of zeroes, so 200:1 is comfortably above every real brush
 * bitmap and far below what a bomb needs. A `.brush` that declares 100 MiB out of 1 KiB is
 * refused by arithmetic rather than by a memory limit, which costs nothing to refuse.
 *
 * **Where this is checkable, and where it is not:** a zip's central directory states both sizes, so this
 * applies to every method-8 entry. A PNG's `zTXt` states **no** uncompressed size, so for a compressed
 * text chunk this number has nothing to compare and the only bound is [MAX_INFLATED_BYTES], enforced
 * inside the `actual` before a byte is written. That asymmetry is a fact about two file formats, not a
 * gap, and it is why the compressed-chunk path may never ask for more than
 * `PngChunks.MAX_PNG_STRING_BYTES`.
 */
const val MAX_INFLATE_RATIO = 200L

/** What one deflate entry may inflate to. 64 MiB, and the declared size is **not** believed. */
const val MAX_INFLATED_BYTES = 64L * 1024 * 1024

/**
 * The `maxOut` to hand [inflateRaw], and **the only place that expression is written.**
 *
 * `minOf(wantsAtMost, MAX_INFLATED_BYTES)`, floored at 0. Two callers, two different reasons to pass
 * something less than the ceiling, and one rule: **you may ask for less when the destination has a
 * smaller cap, and never for more.**
 *
 *  - a **zip entry** passes the uncompressed size the central directory declares, so a file that lies
 *    about its size is bounded by what it claimed, exactly as JB-8.02 decided;
 *  - a **PNG text chunk** passes `PngChunks.MAX_PNG_STRING_BYTES`, because what comes out of a `zTXt` is a
 *    text chunk's text and the string cap is already the number that governs one. A compressed text chunk
 *    therefore cannot ask for more than a *compressed* text chunk is allowed to be, and Test 2 pins that
 *    it never can.
 *
 * This function exists so the *shape* is written once. Two `minOf` expressions in two files is the same
 * hazard as two constants in two files, and it is one that changes only when somebody notices.
 */
internal fun inflateMaxOut(wantsAtMost: Long): Int =
    minOf(wantsAtMost, MAX_INFLATED_BYTES).coerceAtLeast(0L).toInt()

/**
 * Do the two bytes at [at] form a zlib header? `CMF`'s low nibble is the compression method and must
 * be 8, and the pair must be a big-endian multiple of 31.
 *
 * **A test, not a requirement** — see the note in `ProcreateImport.Zip.read`. A false positive costs two
 * bytes of a stream that was never going to decode, so it fails loudly rather than silently, and a true
 * negative on a real zlib-wrapped entry is the case the spec describes.
 */
internal fun looksLikeZlib(data: ByteArray, at: Int): Boolean {
    if (at + 2 > data.size) return false
    val cmf = data[at].toInt() and 0xFF
    val flg = data[at + 1].toInt() and 0xFF
    return cmf and 0x0F == 8 && (cmf * 256 + flg) % 31 == 0
}

/** RFC 1950's two-byte header: `CMF` then `FLG`. */
internal const val ZLIB_HEADER_BYTES = 2

/** RFC 1950's four-byte Adler-32 of the *uncompressed* data. Not DEFLATE, so it comes off too. */
internal const val ZLIB_ADLER_BYTES = 4

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
