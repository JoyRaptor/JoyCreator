package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.json.Json

/** Thrown by [BrushJson] when a brush file cannot be read or written. */
class BrushException(message: String) : Exception(message)

/** The format tag and version in every brush.json. Bump with the defaults in [BrushPreset]. */
const val BRUSH_FORMAT = "joybrush.brush"
/**
 * The newest brush version this build reads. THE ONE constant (R3): `BrushPreset.version`'s default
 * moves with it, in the same edit, and `EnumFreezeTest` pins it.
 *
 *  - **2** (JB-1.08a, R21) added `engine: "fill"` and `blend: "behind"` — [VERSION_FILL].
 *  - **3** (JB-1.06, R47) added `engine: "smudge"` and `engine: "push"`, and the `smudge` and `push`
 *    sections that go with them — [VERSION_SMUDGE].
 *
 * A word needs the version that introduced it, NOT the newest one: a fill pen is still a version-2
 * file, so a build that predates smudge can open it. [BrushJson.wordsNeedingVersion] carries the
 * per-word number.
 */
const val BRUSH_VERSION = 3

/** The brush version that introduced the fill pen's words. */
const val VERSION_FILL = 2

/** The brush version that introduced the smudge and push engines (JB-1.06). */
const val VERSION_SMUDGE = 3

/** Smudge (JB-1.06): drags the paint already on the layer, carrying ONE colour (blueprint §5, R8). */
const val ENGINE_SMUDGE = "smudge"

/** Push (JB-1.06): displaces the pixels under the tip along the stroke. */
const val ENGINE_PUSH = "push"

/** The fill pen is a BRUSH, not a tool (R21): the engine word that makes a stroke a filled shape. */
const val ENGINE_FILL = "fill"

/** Paints only where the layer is not already opaque, so a fill can go UNDER line art on its layer. */
const val BLEND_BEHIND = "behind"

/**
 * brush.json — one brush, the smallest thing a person can share. It is written by Joy Brush, by the
 * Phase 8 importers (Photoshop `.abr`, Procreate, MyPaint, Krita) and by hand, so it is written
 * pretty with keys in declaration order, states its defaults outright, and ignores keys it does not
 * know when reading — a brush from a later Joy Brush still opens here.
 */
object BrushJson {

    private val FORMAT = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(p: BrushPreset): String = try {
        FORMAT.encodeToString(BrushPreset.serializer(), p.copy(version = versionFor(p)))
    } catch (e: IllegalArgumentException) {
        // A preset built in memory can hold a NaN or an Infinity; JSON has no word for either, and
        // the person saving the brush should be told which brush failed, not which library threw.
        throw BrushException("brush cannot be written: ${e.message}")
    }

    /**
     * Read a brush file, refusing only what the file itself cannot say — a broken JSON document, or
     * a version-1 file using a version-2 word ([decodeChecked] below is the one that also asks
     * [BrushValidate]). A caller painting with the result is asking for the file to be *readable*;
     * a caller loading a brush a person chose is asking for it to be *usable*, and should say so.
     */
    fun decode(json: String): BrushPreset {
        val p = parse(json)
        // A word a version-1 file cannot mean. Refused here rather than in validation, because a
        // version-1 build would read this brush as an ordinary stamp pen and happily draw with it —
        // which is exactly the small mistake the version number exists to prevent. It is NOT the
        // "from a newer Joy Brush" sentence: this file is older than the word it uses.
        for (word in wordsNeedingVersion(p)) {
            if (p.version < word.minVersion) throw BrushException("${word.text} needs brush version ${word.minVersion}")
        }
        return p
    }

    /**
     * Read a brush file the way a person will actually load one: [decode], then every [BrushValidate]
     * rule, and refuse the file if any of them speaks. The problems come back as the sentences
     * [BrushValidate] already wrote, joined into one [BrushException] — a screen can show the whole
     * list, a log can show the first line, and neither has to be re-written here.
     *
     * Why this exists rather than a rule inside [decode]: a file that decodes is a file that paints.
     * Between those two facts sit a [BrushValidate] call and a person's attention, and a caller that
     * forgets the call never finds out. A hostile or merely hand-edited `brush.json` then reaches the
     * engine, where every number is clamped — so it does not crash, it draws *something else*: a
     * `"size": {"base": 0}` becomes a dotted stroke on [BrushDabber]'s `minPx` floor, an opacity of
     * 5 becomes 1, and the person who wrote the file is told none of it. That is the "silent
     * nonsense" this door exists to replace with a sentence. Validation can only protect the path
     * that asks for it, so the asking is here, in the name of the call, rather than in a convention
     * a fifth caller can forget.
     *
     * Note that this does *not* repeat [decode]'s version-word check: [BrushValidate] rule 1b is the
     * same sentence (`engine "fill" needs brush version 2`) and reports it alongside everything else
     * that is wrong, so one refusal lists all of it instead of stopping at the first.
     */
    fun decodeChecked(json: String): BrushPreset {
        val p = parse(json)
        val problems = BrushValidate.validate(p)
        if (problems.isNotEmpty()) {
            throw BrushException("brush.json cannot be used: " + problems.joinToString("; "))
        }
        return p
    }

    /** The file, as far as JSON is concerned. Everything above this line is about the brush. */
    private fun parse(json: String): BrushPreset = try {
        FORMAT.decodeFromString(BrushPreset.serializer(), json)
    } catch (e: IllegalArgumentException) {
        // SerializationException and JsonDecodingException both land here.
        throw BrushException("brush.json cannot be read: ${e.message}")
    }

    /**
     * The version this file is written with: the LOWEST one that can express the brush, so an
     * ordinary pen stays a version-1 file that an older Joy Brush can still open. A brush that
     * already carries a version of its own is never downgraded, so a file from a later build keeps
     * its number when it passes through here.
     */
    private fun versionFor(p: BrushPreset): Int {
        val needed = wordsNeedingVersion(p).maxOfOrNull { it.minVersion } ?: return p.version
        return if (p.version < needed) needed else p.version
    }

    /** A word a file can only say from [minVersion] on, named the way the file names it. */
    internal class VersionedWord(val text: String, val minVersion: Int)

    /**
     * The words that need a version above 1, each with the version that introduced it, so a refusal is
     * one sentence: `engine "fill" needs brush version 2`, `engine "smudge" needs brush version 3`.
     */
    internal fun wordsNeedingVersion(p: BrushPreset): List<VersionedWord> {
        val out = ArrayList<VersionedWord>(2)
        if (p.engine == ENGINE_FILL) out += VersionedWord("engine \"$ENGINE_FILL\"", VERSION_FILL)
        if (p.blend == BLEND_BEHIND) out += VersionedWord("blend \"$BLEND_BEHIND\"", VERSION_FILL)
        if (p.engine == ENGINE_SMUDGE) out += VersionedWord("engine \"$ENGINE_SMUDGE\"", VERSION_SMUDGE)
        if (p.engine == ENGINE_PUSH) out += VersionedWord("engine \"$ENGINE_PUSH\"", VERSION_SMUDGE)
        return out
    }
}
