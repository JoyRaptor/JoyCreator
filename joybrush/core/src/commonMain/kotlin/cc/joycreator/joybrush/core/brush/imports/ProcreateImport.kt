package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ColorJitter
import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.TipSpec

/**
 * Procreate `.brush` and `.brushset` → Joy Brush presets. JB-8.02.
 *
 * A `.brush` is a **zip**; inside it `Brush.archive` is a property list — usually a binary plist
 * written by `NSKeyedArchiver`, sometimes a flat XML one (Decision 6) — beside `Shape.png` and
 * `Grain.png`. A `.brushset` is a zip of UUID folders in that same layout plus `brushset.plist`, which
 * gives the order (Decision 15) and, when it has one, the set's name (Decision 16).
 *
 * **Two of the three things this row is about are about honesty, not mapping.**
 *
 * The first is what happens to input Joy Brush cannot express. Every Procreate setting is MAPPED (a
 * [BrushPreset] field carries it), LOSSY (the nearest field carries it, a warning says what was lost,
 * and the raw value survives in `extensions`), or REFUSED (the brush is dropped with a sentence,
 * because the alternative is a *different brush* wearing the same name). The one thing that is never
 * allowed is silence: a value that was guessed is a value somebody has to be told about.
 *
 * The second is R40, uniform across JB-8.01, this row and JB-8.04: **an importer stores image tips
 * and image grain, sets `source = "image"`, and says so out loud.** Nothing in the engine samples an
 * image tip or an image grain today — there is no image reader anywhere in `joybrush/` and
 * `jb_dab.frag` samples no texture — while `BrushValidate` rule 23 checks only that the path is
 * non-blank, so a preset can validate clean, name a file nobody has written, and draw as procedural.
 * **The warning is the only thing standing between a person and that.** It is [TEXTURE_NOT_DRAWN] by
 * value, never a retyped literal, and it rides on every brush that carried a bitmap *and* on every
 * brush refused for naming a Procreate built-in. The condition on removing it is JB-1.05d, and
 * nothing here may claim a texture is drawn before that exists.
 *
 * The third is R4 §5 and it is a legal rule, not a preference: **a Procreate built-in shape or grain
 * is never extracted and never substituted.** Those images are Savage Interactive's property. A
 * brush that names one and does not carry it is refused, in words, naming the resource.
 */
object ProcreateImport {

    // ---- budgets (Decision 11). Every one is a cap *and* a refusal, and every message names it ----

    /** The whole file. A `.brushset` is a pack, so this is the pack cap: 256 MiB. */
    const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    /** Entries in one zip. 4 096: a 200-brush pack with a thumbnail and a dual brush is under 1 000. */
    const val MAX_ENTRIES = 4_096

    /** One entry's bytes **as they sit in the file** (its compressed or stored payload). 64 MiB. */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /**
     * An entry name, in characters.
     *
     * 512, and it is a *name* cap rather than a *file* cap because an id becomes a file name, and a
     * stranger's name is the thing that has to be bounded before it is stored, matched or quoted.
     */
    const val MAX_ENTRY_NAME_CHARS = 512

    /**
     * How much bigger than its payload an entry may claim to be: 200:1.
     *
     * **Checked before anything is inflated, and that is the whole point of the number.** DEFLATE
     * reaches about 1 038:1 on a run of zeroes, so 200:1 is comfortably above every real brush
     * bitmap and far below what a bomb needs. A `.brush` that declares 100 MiB out of 1 KiB is
     * refused by arithmetic rather than by a memory limit, which costs nothing to refuse.
     */
    const val MAX_INFLATE_RATIO = 200L

    /** What one deflate entry may inflate to. 64 MiB, and the declared size is **not** believed. */
    const val MAX_INFLATED_BYTES = 64L * 1024 * 1024

    /** `$objects` entries in one archive. 200 000: a Procreate brush writes about forty. */
    const val MAX_OBJECTS = 200_000

    /** Nesting depth while reading a plist, in either shape. 32. */
    const val MAX_DEPTH = 32

    /** Keys in one dictionary. 20 000. */
    const val MAX_KEYS_PER_DICT = 20_000

    /** One string, in characters, in either plist shape. 64 Ki. */
    const val MAX_STRING_CHARS = 64 * 1024

    /** Brushes in one pack. 2 048. */
    const val MAX_BRUSHES = 2_048

    /**
     * How much of one value `extensions` will print (see `PlistValue.render` in [KeyedArchive]).
     *
     * 1 024 characters. `extensions` is lossless *storage* of a setting a person may want back, so
     * this is generous for a scalar and a real bound for a nested dictionary: the value is printed,
     * and a printer that walks forever is a denial of service wearing a brush pack's clothes. 1 024
     * prints every scalar and every short list in a real archive whole; nothing this importer maps is
     * longer than a UUID.
     */
    const val MAX_RENDERED_CHARS = 1_024

    // ---- the file's own names -----------------------------------------------------------------------

    /** The archive inside every `.brush`, at the top of it or of a `.brushset` folder. */
    const val ARCHIVE_NAME = "Brush.archive"

    /** The `.brushset`'s index: the order, and sometimes the set's name. */
    const val SET_PLIST_NAME = "brushset.plist"

    /** The tip bitmap, and the grain bitmap. The names here are the *foreign* ones. */
    const val SHAPE_NAME = "Shape.png"
    const val GRAIN_NAME = "Grain.png"

    /** The dual brush's own folder. Its archive is part of the brush above it, not a brush. */
    const val DUAL_FOLDER = "Sub01/"

    /**
     * Decision 16's separator: U+00B7 MIDDLE DOT with one space each side, so a set called
     * "My Pack" and a brush called "Round Hard" give `"My Pack · Round Hard"`.
     *
     * **PROVISIONAL — Claude to confirm:** R40 ruled that the set's name becomes a *prefix*; the
     * shape of that prefix is this row's naming default and is one edit. It is a `const` so the
     * tests can name the exact characters rather than restating them.
     */
    const val SET_SEPARATOR = " · "

    // ---- the contract ------------------------------------------------------------------------------

    /**
     * One `.brush` (a zip) → its one brush.
     *
     * **Everything that goes wrong here throws**, because a `.brush` has exactly one brush in it and
     * there is no sibling to protect: a file that is not a zip, a zip with no `Brush.archive`, a
     * plist that is neither a binary nor an XML one, a dangling UID, a bad curve, a brush that names
     * a Procreate built-in. Each is a [BrushException] with a sentence.
     *
     * The same fault inside a `.brushset` is a [RefusedBrush] for that one folder instead, because
     * there the pack itself is perfectly readable — see [convertBrushSet].
     *
     * @param idPrefix the caller's library prefix; the id is `brushId(idPrefix, name)`. A single
     *   `.brush` opened on its own gets **no** set prefix, because there is no set (Decision 16).
     * @throws BrushException if the file cannot be read, or if its one brush cannot be built.
     */
    fun convertBrush(bytes: ByteArray, idPrefix: String): ImportLibrary {
        val zip = Zip.read(bytes)
        val notes = PackNotes()
        val archive = zip.find(ARCHIVE_NAME)
            ?: throw BrushException("this .brush has no $ARCHIVE_NAME, so it holds no brush")
        val root = KeyedArchive.read(zip.read(archive)).root()
        val name = nameOf(root, 0)
        val result = ProcreateBrush(zip, notes, hasDual(zip), "").build(root, name, brushId(idPrefix, name))
        return ImportLibrary(listOf(result.withPackNotes(notes)), emptyList())
    }

    /**
     * A `.brushset` (a zip of UUID folders plus `brushset.plist`) → every brush inside it.
     *
     * Order comes from `brushset.plist` (Decision 15); a missing or unreadable plist imports every
     * folder in **name** order and says so once. The set's name **prefixes** every brush's name
     * (Decision 16), so three packs that each contain a "Round Hard" still get three different ids.
     *
     * A fault in one folder is that folder's [RefusedBrush] and the rest of the pack still imports;
     * a fault in the pack itself (not a zip, no brushes, a budget) throws.
     */
    fun convertBrushSet(bytes: ByteArray, idPrefix: String): ImportLibrary {
        val zip = Zip.read(bytes)
        val notes = PackNotes()
        val brushes = ArrayList<ImportResult>()
        val refused = ArrayList<RefusedBrush>()

        val folders = brushFolders(zip)
        if (folders.size > MAX_BRUSHES) {
            throw BrushException("this pack holds ${folders.size} brushes, at most $MAX_BRUSHES")
        }
        val setName = readSetName(zip, folders, notes)

        for ((index, folder) in folders.withIndex()) {
            try {
                val entry = zip.find(folder.archive)
                    ?: throw BrushException("its folder has no $ARCHIVE_NAME inside it")
                val root = KeyedArchive.read(zip.read(entry)).root()
                val own = nameOf(root, index)
                val name = if (setName == null) own else setName + SET_SEPARATOR + own
                brushes += ProcreateBrush(zip, notes, hasDual(zip, folder.prefix), folder.prefix)
                    .build(root, name, brushId(idPrefix, name))
            } catch (e: BrushException) {
                refused += RefusedBrush(index, folder.uuid, e.message ?: "it cannot be read")
            }
        }
        return ImportLibrary(brushes.withPackNotes(notes), refused)
    }

    // ---- the pack's own facts -----------------------------------------------------------------------

    /** One brush inside the pack: the UUID folder, the archive it holds, and that folder's prefix. */
    private class BrushFolder(val uuid: String, val archive: String, val prefix: String)

    /**
     * Every `<uuid>/Brush.archive` in the zip, in folder-name order.
     *
     * **One folder deep only, and never a nested `Sub01/`**: a dual brush's own archive belongs to
     * the brush above it, not to the pack, and importing it as a brush of its own would give one
     * artist one dual brush and two presets.
     */
    private fun brushFolders(zip: Zip): ArrayList<BrushFolder> {
        val out = ArrayList<BrushFolder>()
        for (entry in zip.entries) {
            val slash = entry.name.indexOf('/')
            if (slash <= 0 || entry.name.indexOf('/', slash + 1) >= 0) continue
            if (!entry.name.regionMatches(slash + 1, ARCHIVE_NAME, 0, ARCHIVE_NAME.length, true)) continue
            val uuid = entry.name.substring(0, slash)
            out += BrushFolder(uuid, entry.name, uuid + "/")
        }
        out.sortBy { it.uuid }
        return out
    }

    /**
     * The set's name, or null when the pack names nothing.
     *
     * Decision 16: "the set name comes from `brushset.plist`", and R4 §B.2 says the same file carries
     * "set name and order". The root dictionary is read for a `name`, then a `title`, then a
     * `setName`; the first non-blank one wins.
     *
     * **PROVISIONAL — Claude to confirm:** no public `brushset.plist` this row could check carries a
     * set name (brushkit's carries a `brushes` array and nothing else), so *which key* a real pack
     * would use is a naming default and one edit. What is not provisional is the fallback: no name
     * means no prefix, and one sentence per pack.
     */
    private fun readSetName(zip: Zip, folders: ArrayList<BrushFolder>, notes: PackNotes): String? {
        val plist = zip.find(SET_PLIST_NAME)
        if (plist == null) {
            notes.plain("this pack has no $SET_PLIST_NAME, so every folder is imported in name order")
            return null
        }
        val root = try {
            KeyedArchive.read(zip.read(plist)).root()
        } catch (e: BrushException) {
            notes.plain(
                "this pack's $SET_PLIST_NAME cannot be read (${e.message}), so every folder is imported " +
                    "in name order and the pack names no set"
            )
            return null
        }
        orderByPlist(root, folders)
        val key = SET_NAME_KEYS.firstOrNull { !root.text(it).isNullOrBlank() }
        if (key == null) {
            notes.plain(
                "this pack's $SET_PLIST_NAME names no set (looked for " +
                    "${SET_NAME_KEYS.joinToString(", ")}), so every brush keeps its own name"
            )
            return null
        }
        return root.text(key)
    }

    /**
     * Re-sort [folders] into the order `brushset.plist` states, leaving the rest where it was.
     *
     * The plist lists brush ids, and a `.brushset`'s folders are named after exactly those ids, so
     * the rank comes from the folder name. A folder the plist does not mention **keeps its place at
     * the end rather than being dropped**, because dropping it would lose a brush the pack really
     * does ship, and losing a brush is worse than importing it in the wrong order.
     */
    private fun orderByPlist(root: PlistValue.Dict, folders: ArrayList<BrushFolder>) {
        val listed = root.items("brushes") ?: return
        val rank = HashMap<String, Int>()
        for (item in listed) {
            val id = (item as? PlistValue.Dict)?.text("brushID") ?: continue
            if (!rank.containsKey(id)) rank[id] = rank.size
        }
        if (rank.isEmpty()) return
        folders.sortWith(compareBy({ rank[it.uuid] ?: Int.MAX_VALUE }, { it.uuid }))
    }

    /** One name for the sentence, and it is said rather than defaulted in silence. */
    private fun nameOf(root: PlistValue.Dict, index: Int): String =
        root.text("name")?.takeIf { it.isNotBlank() } ?: "brush ${index + 1}"

    private fun hasDual(zip: Zip, prefix: String = ""): Boolean =
        zip.entries.any { it.name.startsWith(prefix + DUAL_FOLDER, ignoreCase = true) }

    /** The pack-level sentences, said **once**, on the first brush that converted. */
    private fun List<ImportResult>.withPackNotes(notes: PackNotes): List<ImportResult> {
        val sentences = notes.sentences()
        if (sentences.isEmpty() || isEmpty()) return this
        val first = this[0]
        return listOf(first.copy(warnings = first.warnings + sentences)) + drop(1)
    }

    private fun ImportResult.withPackNotes(notes: PackNotes): ImportResult =
        copy(warnings = warnings + notes.sentences())

    private val SET_NAME_KEYS = listOf("name", "title", "setName")
}

// ---- the whole-pack facts (Decision 10) -------------------------------------------------------------------

/**
 * The facts that are true of the **pack**, not of one brush, collected while the brushes are read
 * and said once at the end.
 *
 * There is exactly one kind here that must be said once: **an unverified stored scale.** R4 §B.2
 * marks several of Procreate's value scales `≈` and gives no range, and Decision 10 turns that into
 * one sentence per `convertBrush` / `convertBrushSet` call. JB-8.03's review's Finding 2 is the
 * reason: a warning that fires on all 200 brushes of a pack is a warning nobody reads.
 *
 * Everything else — a clamp, a lost setting, a dropped curve — is genuinely per brush and goes in
 * that brush's own warnings.
 *
 * The sentence is marked [SCALE_WARNING_MARKER] so a test can **count** it. "Exactly one" is a
 * number, and a number nobody can assert is a number nobody has checked.
 */
internal class PackNotes {

    private val approximate = LinkedHashSet<String>()
    private val unresolved = LinkedHashSet<String>()
    private val percentage = LinkedHashSet<String>()
    private val plain = ArrayList<String>()

    /** A field whose stored scale no document this build can check states. */
    fun approximate(field: String) {
        approximate += field
    }

    /** A colour jitter read as 0..1 because its value could have been 0..100. */
    fun unresolvedScale(field: String) {
        unresolved += field
    }

    /** A colour jitter read as a percentage because its value was over 1. */
    fun percentageScale(field: String) {
        percentage += field
    }

    /** A pack fact that is not a scale: a missing or unreadable `brushset.plist`, for instance. */
    fun plain(sentence: String) {
        plain += sentence
    }

    fun sentences(): List<String> {
        val out = ArrayList<String>(2)
        val facts = ArrayList<String>(3)
        if (approximate.isNotEmpty()) {
            facts += approximate.joinToString(", ") +
                " were taken exactly as stored, because nothing this build can check states their units"
        }
        if (unresolved.isNotEmpty()) {
            facts += unresolved.joinToString(", ") +
                " were read as 0..1, but the archive may store 0..100; the raw value is in extensions"
        }
        if (percentage.isNotEmpty()) {
            facts += percentage.joinToString(", ") + " were over 1, so they were read as percentages"
        }
        if (facts.isNotEmpty()) out += "$SCALE_WARNING_MARKER: " + facts.joinToString("; ")
        out += plain
        return out
    }
}

/**
 * The substring that marks Decision 10's one-per-pack sentence, so a test can count it.
 *
 * `internal` and not `private`, because the count is the assertion and the assertion has to name the
 * same string the importer emits. A test that retyped the sentence would keep passing if the
 * sentence moved; a test that counts this constant cannot.
 */
internal const val SCALE_WARNING_MARKER = "unverified stored scale"

// ---- the zip ---------------------------------------------------------------------------------------------

/**
 * The zip reader, in `commonMain`, with no `java.*` in it (the Do-not list).
 *
 * What is hand-written here is **structure**: the end-of-central-directory record, the central
 * directory, the local file headers, and the two-byte zlib header. What is *not* here is DEFLATE —
 * that is [inflateRaw], an `expect` with a `java.util.zip.Inflater` `actual`, by ruling R40. The
 * division is the point: parsing a directory is arithmetic, and a decoder is a Huffman table walker
 * whose bugs are a silently wrong image rather than an exception.
 *
 * Every count and every size the file states is checked against a budget *before* it is used to
 * allocate or to index, and every name is checked against the segment rule (Decision 12) before it
 * is stored, matched, or printed — because a hostile `.brush` must not be able to write an escaping
 * name either, and this importer writes nothing at all, which is the strongest form of that rule.
 */
private class Zip private constructor(
    private val data: ByteArray,
    val entries: List<Entry>,
) {

    /** One entry of the central directory. Every size is a bound, not a promise. */
    class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localOffset: Int,
    )

    /** The entry called [name], case-insensitively, or null. Zip names are ASCII in every real pack. */
    fun find(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The entry at [path], or null. `path` is the whole name, folder prefix and all.
     *
     * A `.brush`'s bitmaps are at the **top** of the zip and a `.brushset`'s are **inside a UUID
     * folder**, so every lookup has to know which pack it is in. Getting this wrong is not a small
     * thing: a flat `find("Shape.png")` finds nothing in a `.brushset` (so every brush in a pack
     * imports with a procedural tip and no R40 warning), and in the one case where a top-level
     * `Shape.png` *does* exist it hands the **same bitmap to every brush in the pack**.
     */
    fun findIn(prefix: String, path: String): Entry? = find(prefix + path)

    /**
     * One entry's bytes, bounded on the way in and on the way out.
     *
     * The order of the three refusals is not arbitrary and a test depends on it:
     *  1. the entry's own bytes against [ProcreateImport.MAX_ENTRY_BYTES] — what is *in the file*;
     *  2. the declared expansion against [ProcreateImport.MAX_INFLATE_RATIO] — **a zip bomb is
     *     refused by arithmetic, before a byte is inflated**, which is the only way to refuse one
     *     that costs nothing;
     *  3. the declared expansion against [ProcreateImport.MAX_INFLATED_BYTES].
     *
     * A STORED entry (method 0) is copied, not inflated — Decision 5 calls it "a real case", because
     * entry 0 of a zip is conventionally stored and a `.brush` written by one tool and packed by
     * another really does contain them.
     */
    fun read(entry: Entry): ByteArray {
        if (entry.compressedSize > ProcreateImport.MAX_ENTRY_BYTES) {
            throw BrushException(
                "\"${entry.name}\" holds ${entry.compressedSize} bytes, " +
                    "at most ${ProcreateImport.MAX_ENTRY_BYTES}"
            )
        }
        if (entry.method == DEFLATED && entry.uncompressedSize > 0 &&
            entry.uncompressedSize > entry.compressedSize * ProcreateImport.MAX_INFLATE_RATIO
        ) {
            throw BrushException(
                "\"${entry.name}\" claims ${entry.uncompressedSize} bytes out of ${entry.compressedSize}, " +
                    "over the ${ProcreateImport.MAX_INFLATE_RATIO}:1 limit this build will expand"
            )
        }
        if (entry.method == DEFLATED && entry.uncompressedSize > ProcreateImport.MAX_INFLATED_BYTES) {
            throw BrushException(
                "\"${entry.name}\" inflates to ${entry.uncompressedSize} bytes, " +
                    "at most ${ProcreateImport.MAX_INFLATED_BYTES}"
            )
        }
        if (entry.method != STORED && entry.method != DEFLATED) {
            throw BrushException(
                "\"${entry.name}\" uses compression method ${entry.method}; this build reads " +
                    "$STORED (stored) and $DEFLATED (deflate), and will not guess at the rest"
            )
        }

        val local = entry.localOffset
        if (local < 0 || local + LOCAL_HEADER > data.size) {
            throw BrushException("\"${entry.name}\" starts at byte $local, outside the file")
        }
        if (le32(data, local, "the local header of \"${entry.name}\"") != LOCAL_SIGNATURE) {
            throw BrushException("\"${entry.name}\" has no local file header at byte $local")
        }
        // The local header repeats the name and the sizes, and the data starts after **its own** name
        // and extra fields. Those lengths are not necessarily the central directory's, and reading
        // from the central directory's is how a reader lands in the middle of a name.
        val localName = le16(data, local + 26, "the name length of \"${entry.name}\"")
        val localExtra = le16(data, local + 28, "the extra length of \"${entry.name}\"")
        val at = local + LOCAL_HEADER + localName + localExtra
        if (at < 0 || at + entry.compressedSize > data.size) {
            throw BrushException(
                "\"${entry.name}\" claims ${entry.compressedSize} bytes from byte $at, " +
                    "past the end of the file"
            )
        }
        if (entry.method == STORED) {
            if (entry.compressedSize != entry.uncompressedSize) {
                throw BrushException(
                    "\"${entry.name}\" is stored but says ${entry.uncompressedSize} bytes out of " +
                        "${entry.compressedSize}, which a stored entry cannot be"
                )
            }
            return data.copyOfRange(at, at + entry.compressedSize)
        }

        // **Decision 5, corrected — and this is a factual error in the spec, not a preference.**
        //
        // The spec says a `.brush` entry "carries the zlib two-byte header" and that this reader must
        // check it and hand `inflateRaw` the bytes after it. That premise is false, and it was found by
        // building a fixture rather than by reading: **a zip entry's method 8 is raw DEFLATE
        // (RFC 1951), with no zlib and no gzip wrapper** — PKWARE APPNOTE 4.4.5, method 8 — and two
        // independent writers agree. The JDK's `ZipOutputStream` constructs its `Deflater` with
        // `nowrap = true`, and .NET's `ZipArchive` does the same; a probe of both puts a deflate block
        // header where a zlib header would be.
        //
        // Requiring the header, as written, would refuse **every** real `.brush` file. So the wrapper is
        // **tolerated rather than required**, and both of its ends come off when it is there: RFC 1950
        // is a two-byte header *and* a four-byte Adler-32 trailer, and the trailer is not DEFLATE — so
        // leaving it would trip `inflateRaw`'s trailing-bytes check, which is a check worth having.
        //
        // A false positive is a first byte whose low nibble is 8 (1 part in 16) *and* whose two-byte
        // pair is a multiple of 31 (1 part in 31) — about 0.2%. One costs four bytes of the stream, so
        // the inflater then reports "ends early" rather than returning something short. Loud, not silent.
        //
        // What survives of Decision 5 unchanged, and is the part that matters: the entry is **bounded**
        // — `maxOut` is the declared size capped at MAX_INFLATED_BYTES, the declared size is not
        // believed, and trailing bytes after the DEFLATE data are refused by `inflateRaw`'s own
        // `remaining` check.
        val wrapped = looksLikeZlib(data, at)
        val from = at + if (wrapped) ZLIB_HEADER_BYTES else 0
        val length = entry.compressedSize - if (wrapped) ZLIB_HEADER_BYTES + ZLIB_ADLER_BYTES else 0
        if (length < 1) throw BrushException("\"${entry.name}\" holds no DEFLATE data")
        val maxOut = minOf(entry.uncompressedSize.toLong(), ProcreateImport.MAX_INFLATED_BYTES).toInt()
        return inflateRaw(data, from, length, maxOut)
    }

    companion object {

        const val STORED = 0
        const val DEFLATED = 8
        private const val LOCAL_HEADER = 30
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val END_SIGNATURE = 0x06054b50L
        private const val END_SIZE = 22
        private const val CENTRAL_SIZE = 46

        /** The largest value any of these 32-bit fields can hold, which is how Zip64 is spotted. */
        private const val ZIP64 = 0xFFFFFFFFL

        /**
         * Read a zip's directory.
         *
         * @throws BrushException if the file is not a zip, is over
         *   [ProcreateImport.MAX_ARCHIVE_BYTES], holds too many entries, needs Zip64, or carries an
         *   entry name the segment rule refuses (Decision 12).
         */
        fun read(bytes: ByteArray): Zip {
            if (bytes.size > ProcreateImport.MAX_ARCHIVE_BYTES) {
                throw BrushException(
                    "this file is ${bytes.size} bytes, at most ${ProcreateImport.MAX_ARCHIVE_BYTES}"
                )
            }
            if (bytes.size < END_SIZE) {
                throw BrushException(
                    "this file is ${bytes.size} bytes, too short to be a zip: a zip ends with a " +
                        "$END_SIZE-byte directory record"
                )
            }
            // The end record is at the very end unless the archive has a comment, which is 0..65535
            // bytes. So the search is the last 65 557 bytes, backwards, and stops at the first hit.
            val earliest = maxOf(0, bytes.size - (END_SIZE + 0xFFFF))
            var end = -1
            for (i in bytes.size - END_SIZE downTo earliest) {
                if (le32(bytes, i, "the end of the zip directory") == END_SIGNATURE) {
                    end = i
                    break
                }
            }
            if (end < 0) {
                throw BrushException(
                    "this file is not a zip: it has no end-of-directory record, so it is not a .brush " +
                        "or a .brushset this build can open"
                )
            }
            val total = le16(bytes, end + 10, "the entry count")
            val directoryAt = le32(bytes, end + 16, "the directory offset")
            if (total == 0xFFFF || directoryAt == ZIP64) {
                throw BrushException("this zip needs the Zip64 extensions, which this build does not read")
            }
            if (total > ProcreateImport.MAX_ENTRIES) {
                throw BrushException(
                    "this zip holds $total entries, at most ${ProcreateImport.MAX_ENTRIES}"
                )
            }
            if (directoryAt > Int.MAX_VALUE || directoryAt + CENTRAL_SIZE > bytes.size) {
                throw BrushException("this zip's directory starts at $directoryAt, outside the file")
            }

            val entries = ArrayList<Entry>(total)
            var at = directoryAt.toInt()
            for (n in 0 until total) {
                if (le32(bytes, at, "directory entry ${n + 1}") != CENTRAL_SIGNATURE) {
                    throw BrushException("this zip's directory entry ${n + 1} has no directory signature")
                }
                val method = le16(bytes, at + 10, "the method of entry ${n + 1}")
                val compressed = le32(bytes, at + 20, "the stored size of entry ${n + 1}")
                val uncompressed = le32(bytes, at + 24, "the expanded size of entry ${n + 1}")
                val nameLength = le16(bytes, at + 28, "the name length of entry ${n + 1}")
                val extraLength = le16(bytes, at + 30, "the extra length of entry ${n + 1}")
                val commentLength = le16(bytes, at + 32, "the comment length of entry ${n + 1}")
                val local = le32(bytes, at + 42, "the offset of entry ${n + 1}")
                if (compressed == ZIP64 || uncompressed == ZIP64 || local == ZIP64) {
                    throw BrushException("this zip needs the Zip64 extensions, which this build does not read")
                }
                if (compressed > Int.MAX_VALUE || uncompressed > Int.MAX_VALUE || local > Int.MAX_VALUE) {
                    throw BrushException("this zip's entry ${n + 1} is larger than this build can hold")
                }
                val nameAt = at + CENTRAL_SIZE
                if (nameAt + nameLength > bytes.size) {
                    throw BrushException("this zip's entry ${n + 1} runs past the end of the file")
                }
                val name = zipLatin1(bytes, nameAt, nameLength)
                unsafeReason(name)?.let { throw BrushException("this zip's entry ${n + 1} is refused: $it") }
                entries += Entry(name, method, compressed.toInt(), uncompressed.toInt(), local.toInt())
                at = nameAt + nameLength + extraLength + commentLength
            }
            return Zip(bytes, entries)
        }
    }
}

/**
 * Why an entry name is refused, or null when it is fine. **The segment rule, not a substring test.**
 *
 * A substring check for `".."` is not the rule and does not catch the three cases that matter:
 * `".. "` (a segment of dots and a space), `"a/./b"` (a segment that *is* `.`), and `"a/ /../b"`
 * (a segment that is only a space). All three climb out, and all three are caught here because the
 * test is on each segment after `trimEnd(' ', '.')`.
 *
 * This is a **restatement, not a copy**: `JbArchive.unsafeReason` is `private` and lives in
 * `androidkit`, which `commonMain` cannot see (Decision 12 and the Do-not list say so outright). The
 * comment naming the other copy is what makes this honest, and the two must be changed together.
 */
private fun unsafeReason(name: String): String? {
    if (name.isEmpty()) return "its name is empty"
    if (name.length > ProcreateImport.MAX_ENTRY_NAME_CHARS) {
        return "its name is ${name.length} characters, at most ${ProcreateImport.MAX_ENTRY_NAME_CHARS}"
    }
    for (c in name) {
        if (c.code < 0x20 || c.code == 0x7F) return "its name holds a control character"
    }
    if (name.contains('\\')) return "its name holds a backslash"
    if (name.startsWith("/")) return "its name starts with a slash"
    if (name.contains(':')) return "its name holds a colon"
    for (segment in name.split('/')) {
        if (segment.isEmpty()) return "its name holds an empty path segment"
        val trimmed = segment.trimEnd(' ', '.')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            return "its path segment \"$segment\" climbs out of the archive"
        }
    }
    return null
}

private fun le16(bytes: ByteArray, at: Int, what: String): Int {
    if (at < 0 || at + 2 > bytes.size) {
        throw BrushException("this zip is truncated: $what needs 2 bytes at $at")
    }
    return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
}

private fun le32(bytes: ByteArray, at: Int, what: String): Long {
    if (at < 0 || at + 4 > bytes.size) {
        throw BrushException("this zip is truncated: $what needs 4 bytes at $at")
    }
    var v = 0L
    for (i in 0 until 4) v = v or ((bytes[at + i].toInt() and 0xFF).toLong() shl (8 * i))
    return v
}

/**
 * Zip names as Latin-1, which never fails and never throws a byte away.
 *
 * Every name this importer matches — `Brush.archive`, `Shape.png`, `Grain.png`, `brushset.plist`, a
 * UUID folder — is ASCII, so a UTF-8 decode would add a failure mode (a name that is not valid
 * UTF-8, which a zip may legally hold) without changing a single answer. A name with high bytes in
 * it is printed in a refusal message and that is all it is ever used for.
 */
private fun zipLatin1(bytes: ByteArray, at: Int, length: Int): String {
    val sb = StringBuilder(length)
    for (i in 0 until length) sb.append((bytes[at + i].toInt() and 0xFF).toChar())
    return sb.toString()
}

private fun hex2(v: Int): String {
    val digits = "0123456789abcdef"
    return "${digits[v shr 4]}${digits[v and 15]}"
}

/**
 * Do the two bytes at [at] form a zlib header? `CMF`'s low nibble is the compression method and must
 * be 8, and the pair must be a big-endian multiple of 31.
 *
 * **A test, not a requirement** — see the note in [Zip.read]. A false positive costs two bytes of a
 * stream that was never going to decode, so it fails loudly rather than silently, and a true negative
 * on a real zlib-wrapped entry is the case the spec describes.
 */
private fun looksLikeZlib(data: ByteArray, at: Int): Boolean {
    if (at + 2 > data.size) return false
    val cmf = data[at].toInt() and 0xFF
    val flg = data[at + 1].toInt() and 0xFF
    return cmf and 0x0F == 8 && (cmf * 256 + flg) % 31 == 0
}

/** RFC 1950's two-byte header: `CMF` then `FLG`. */
private const val ZLIB_HEADER_BYTES = 2

/** RFC 1950's four-byte Adler-32 of the *uncompressed* data. Not DEFLATE, so it comes off too. */
private const val ZLIB_ADLER_BYTES = 4

// ---- one brush -------------------------------------------------------------------------------------------

/**
 * One preset being built, field by field.
 *
 * A class rather than one long function because the spec's own Step 5 warns that the R40 store is
 * "the row a builder will think is finished". Here the images are *decided* with the tip ([shape])
 * and with the grain ([grain]) and the bytes are written at [finish], where the extensions budget is
 * already known — because Decision 3 needs the budget and Decision 3's warning needs both facts.
 *
 * **It is not called `BrushImport`, and that is a bug this file already paid for once.** `AbrImport.kt`
 * has a `private class BrushImport` in this same package, and a *class*'s simple name resolves by
 * name across the package rather than by signature: this file's own `BrushImport` was invisible, and
 * every use of it resolved to the other file's, producing errors that named `AbrImport`'s members
 * (`StoredTip`, which has never existed here). Private top-level *functions* resolve by name **and**
 * signature, so a same-named one with a different parameter list is fine — `latin1` exists in both
 * `PngChunks.kt` and `KeyedArchive.kt` today and both compile. **A new top-level class in this package
 * must have a name no other file in it uses.**
 */
private class ProcreateBrush(
    private val zip: Zip,
    private val notes: PackNotes,
    private val dual: Boolean,
    /**
     * The pack folder this brush's files live in: `""` for a `.brush`, `"<uuid>/"` for one folder of
     * a `.brushset`. Every `Shape.png` and `Grain.png` lookup goes through it — see [Zip.findIn] for
     * what goes wrong without it.
     */
    private val folder: String,
) {

    private val warnings = ArrayList<String>()
    private val extensions = LinkedHashMap<String, String>()
    private val rawKept = LinkedHashSet<String>()

    private var aspect = 0f
    private var angleBase = 0f
    private var followDirection = false
    private var sizeBase = 0f
    private val sizeInputs = ArrayList<InputCurve>()
    private val opacityInputs = ArrayList<InputCurve>()
    private var spacing = DEFAULT_SPACING
    private var scatterAmount = 0f
    private var scatterCount = 1
    private var countJitter = 0f
    private var smoothing = DEFAULT_SMOOTHING
    private var color = ColorJitter()
    private var blend = "normal"
    private var grainDepth = 0f
    private var grainScale = 1f
    private var grainEnabledByDepth = false
    private var grainImagePresent = false
    private var storedTip: Stored? = null
    private var storedGrain: Stored? = null

    /** An image that will be stored base64'd, if it fits. Decided on the tip row, written at finish. */
    private class Stored(val fileName: String, val bytes: ByteArray)

    fun build(root: PlistValue.Dict, name: String, id: String): ImportResult {
        shape(root)
        sizeDynamics(root)
        opacityDynamics(root)
        shapeSettings(root)
        strokePath(root)
        grain(root)
        colour(root)
        rendering(root)
        taper(root)
        dual()
        unknowns(root)
        return finish(root, name, id)
    }

    // ---- the tip (R40) -----------------------------------------------------------------------------

    /**
     * The tip: which bitmap, how big, and R40's sentences.
     *
     * **Decision 8, the legal rule, comes first and it is a refusal.** A `bundledShapePath` naming a
     * Procreate built-in with no `Shape.png` beside it means the brush needs an image that is Savage
     * Interactive's property. Joy Brush does not ship it, may not extract it, and — R40 and the
     * spec's Do-not list agree twice — may not invent a lookalike. A round dab wearing the name
     * would be a *different brush*, which is the one outcome this importer exists to prevent.
     *
     * **R40's store, decided here while the tip is in front of me.** The bytes go into `extensions`
     * base64'd, `tip.source` becomes `"image"`, `tip.image` becomes `"tip.png"` — the name says what
     * the bytes are, and it is a bare file name in the *brush folder*, not a path inside the foreign
     * `.brush` — and [TEXTURE_NOT_DRAWN] is said. Whether it then *fits* is [finish]'s business,
     * because only there is the extensions budget known.
     *
     * `size.base` is `maxSize` when the archive has one and the `Shape.png`'s own `IHDR` width when it
     * does not: four big-endian bytes at a fixed offset, a header read and not a decode. The number
     * is real and paints today; the bitmap is for JB-1.05d.
     */
    private fun shape(root: PlistValue.Dict) {
        val bytes = zip.findIn(folder, ProcreateImport.SHAPE_NAME)?.let { zip.read(it) }
        val bundled = root.text("bundledShapePath")
        if (bytes == null && !bundled.isNullOrBlank()) {
            throw BrushException(
                "it needs Procreate's built-in shape \"$bundled\", which Joy Brush does not ship and " +
                    "may not extract (R4 §5), and the archive carries no ${ProcreateImport.SHAPE_NAME}"
            )
        }
        val maxSize = root.float("maxSize")
        sizeBase = when {
            maxSize != null -> diameter(maxSize, "maxSize")
            bytes != null -> {
                // The `IHDR` is at file offsets 16..20. A `Shape.png` that is not a PNG is refused
                // here, in words, because a shape that cannot say how wide it is cannot size a tip.
                val width = readPngHeader(bytes)?.width
                    ?: throw BrushException(
                        "its ${ProcreateImport.SHAPE_NAME} has no IHDR chunk, so its width cannot be " +
                            "read for size.base"
                    )
                if (width <= 0) throw BrushException("its Shape.png declares a width of $width px")
                diameter(width.toFloat(), "the Shape.png width")
            }
            else -> {
                // Decision 6's empty brush: a name, an identifier and an author and nothing else. It
                // is a real brush, so it converts — and it says what it is standing in for.
                warn("it says nothing about how big its tip is; used $DEFAULT_DIAMETER_PX px")
                DEFAULT_DIAMETER_PX
            }
        }
        if (bytes != null) {
            storedTip = Stored(TIP_FILE_NAME, bytes)
        } else if (bundled.isNullOrBlank()) {
            warn("it carries no shape bitmap, so the tip is a default round stamp and there is nothing to draw from")
        }
    }

    private fun diameter(raw: Float, source: String): Float {
        if (!raw.isFinite()) throw BrushException("its $source is $raw, which is not a size")
        if (raw > BrushValidate.MAX_SIZE_PX) {
            warn("$source $raw px clamped to ${BrushValidate.MAX_SIZE_PX} (a diameter this build will draw)")
            return BrushValidate.MAX_SIZE_PX
        }
        if (raw <= 0f) {
            warn("$source $raw px clamped to $MIN_DIAMETER_PX (a diameter must be above 0)")
            return MIN_DIAMETER_PX
        }
        return raw
    }

    // ---- the four curves ---------------------------------------------------------------------------

    /**
     * `dynamicsPressureSize` / `dynamicsTiltSize`, each with its `+Curve` partner.
     *
     * A Procreate curve is an **array of `"{x, y}"` strings** (R4 §B.2), read as text because that is
     * what it is. Every point is then held to the same rules `BrushValidate` applies, and a failure
     * is a **refusal, not a trim**: a curve quietly cut to 64 points is a curve the artist did not
     * draw, and a point whose x is 1.4 is a ramp `Curve.eval` would silently clamp.
     *
     * The curve attaches whenever the archive carries one that parses. The scalar amount beside it is
     * kept raw, because how much of that curve Procreate means to apply is not written down anywhere
     * this build can check.
     */
    private fun sizeDynamics(root: PlistValue.Dict) {
        curve(root, "dynamicsPressureSize", BrushInput.pressure, sizeInputs)
        curve(root, "dynamicsTiltSize", BrushInput.tilt, sizeInputs)
    }

    private fun opacityDynamics(root: PlistValue.Dict) {
        curve(root, "dynamicsPressureOpacity", BrushInput.pressure, opacityInputs)
        curve(root, "dynamicsTiltOpacity", BrushInput.tilt, opacityInputs)
    }

    private fun curve(root: PlistValue.Dict, amount: String, input: BrushInput, into: MutableList<InputCurve>) {
        // **The pack note is added only when the key is actually present.** The inherited first
        // version added `dynamicsTiltSize` and `dynamicsTiltOpacity` unconditionally, so every single
        // brush in every pack carried an unverified-scale warning about two settings it did not have —
        // which is Decision 10's Finding 2 in a new costume, and a sentence that is not about the file
        // in front of you.
        val declared = root.at(amount)
        if (declared != null) {
            raw(declared, amount)
            if (input == BrushInput.tilt) notes.approximate(amount)
        }
        val points = root.items(amount + "Curve") ?: return
        if (points.isEmpty()) return
        if (into.size >= BrushValidate.MAX_INPUTS) {
            throw BrushException(
                "$amount would be input ${into.size + 1} on one setting, and a setting takes at most " +
                    "${BrushValidate.MAX_INPUTS}"
            )
        }
        if (points.size > BrushValidate.MAX_CURVE_POINTS) {
            throw BrushException("$amount has ${points.size} points, at most ${BrushValidate.MAX_CURVE_POINTS}")
        }
        val out = ArrayList<List<Float>>(points.size)
        for ((i, point) in points.withIndex()) {
            val text = (point as? PlistValue.Text)?.value
                ?: throw BrushException("$amount point ${i + 1} is not a \"{x, y}\" string")
            val (x, y) = parsePoint(text, amount, i + 1)
            if (x !in 0f..1f) {
                throw BrushException("$amount point ${i + 1}: x $x is not in 0..1, so it is a ramp nobody drew")
            }
            if (!y.isFinite()) throw BrushException("$amount point ${i + 1}: y $y is not a number")
            out += listOf(x, y)
        }
        into += InputCurve(input, out)
    }

    /**
     * `"{x, y}"` → the two numbers, or a refusal naming the key and the point.
     *
     * A Procreate point is written with braces, a comma and spaces: `"{0.25, 0.75}"`. Three failures
     * are named separately because they are three different mistakes — not a point at all, one
     * number where two are needed, and a number that will not parse.
     */
    private fun parsePoint(text: String, key: String, at: Int): Pair<Float, Float> {
        val body = text.trim().removePrefix("{").removeSuffix("}").trim()
        val parts = body.split(',')
        if (parts.size != 2) throw BrushException("$key point $at is \"$text\": ${parts.size} numbers, not 2")
        val x = parts[0].trim().toFloatOrNull()
            ?: throw BrushException("$key point $at is \"$text\": x is not a number")
        val y = parts[1].trim().toFloatOrNull()
            ?: throw BrushException("$key point $at is \"$text\": y is not a number")
        return x to y
    }

    // ---- shape settings ----------------------------------------------------------------------------

    private fun shapeSettings(root: PlistValue.Dict) {
        root.float("shapeAngle")?.let { angleBase = it }
        root.float("shapeRoundness")?.let { raw ->
            // As stored, clamped to `tip.aspect`'s own -1..1 with both numbers named. Procreate's
            // roundness is a 0..1 graph and Joy's aspect is -1..1, and nothing this build can check
            // states the mapping, so the number is taken as stored and the clamp says so.
            if (raw < -1f || raw > 1f) {
                val clamped = raw.coerceIn(-1f, 1f)
                warn("shapeRoundness $raw clamped to $clamped (tip.aspect is -1..1)")
                aspect = clamped
            } else {
                aspect = raw
            }
        }
        root.float("shapeRotation")?.let { value ->
            // "Touch rotation / follow stroke?" — R4 puts the question mark on it, and this row maps
            // it the only way that cannot be wrong about magnitude: non-zero means the tip follows the
            // stroke, zero means it does not. The *amount* is unverified, so it goes in the one
            // pack-level sentence and not into a per-brush warning (Decision 10).
            followDirection = value > 0f
            notes.approximate("shapeRotation")
        }
        root.float("shapeCount")?.let { value ->
            val wanted = value.toInt()
            val clamped = wanted.coerceIn(1, 16)
            if (clamped != wanted) warn("shapeCount $wanted clamped to $clamped (1..16 dabs per stamp)")
            scatterCount = clamped
        }
        root.float("shapeCountJitter")?.let { countJitter = unit("shapeCountJitter", it) }
        for (key in FLIP_KEYS) {
            root.at(key)?.let { value ->
                warn(
                    "$key has no Joy Brush field — the tip is never flipped — so the jitter is lost; " +
                        "kept raw in extensions"
                )
                raw(value, key)
            }
        }
    }

    // ---- stroke path -------------------------------------------------------------------------------

    /**
     * Spacing, scatter and StreamLine — **three of the scales R4 marks `≈` and does not give.**
     *
     * `plotSpacing` is the sharpest case. R4 §B.2 records p2k using `sqrt(v·100)/10` and calling it
     * "a guess", and the Procreate Handbook calls the control non-linear. So there are three available
     * readings and no way to choose between them from the file itself.
     *
     * **This importer takes the value as stored and says so, once, for the pack.** It does not apply
     * a third party's guessed curve: a guess made here would be Joy Brush's own unverified number
     * wearing p2k's clothes, and the only thing distinguishing it from a measured one would be this
     * comment. The raw is in `extensions` either way, and the stop rule is explicit — "if the scale
     * of an `≈` field turns out to be unknowable from the fixtures you have, keep the raw value in
     * `extensions` and warn — do not pick a number."
     */
    private fun strokePath(root: PlistValue.Dict) {
        root.at("plotSpacing")?.let { value ->
            raw(value, "plotSpacing")
            numberOf(value)?.let { v ->
                if (v < SPACING_MIN || v > SPACING_MAX) {
                    val clamped = v.coerceIn(SPACING_MIN, SPACING_MAX)
                    warn("plotSpacing $v clamped to $clamped ($SPACING_MIN..$SPACING_MAX)")
                    spacing = clamped
                } else {
                    spacing = v
                }
            }
            notes.approximate("plotSpacing")
        }
        root.at("plotJitter")?.let { value ->
            raw(value, "plotJitter")
            scatterAmount = unit("plotJitter", numberOf(value) ?: 0f)
            notes.approximate("plotJitter")
        }
        root.at("plotSmoothing")?.let { value ->
            raw(value, "plotSmoothing")
            smoothing = unit("plotSmoothing", numberOf(value) ?: 0f)
            notes.approximate("plotSmoothing")
        }
        // Everything else in these two sections — `plotJitterTilt*`, `dynamicsFalloff`,
        // `plotFFTSmoothingAmount`, the moving-average filter — has no field and no stated scale, and
        // the sweep at [unknowns] keeps each one raw and names the lot.
    }

    // ---- grain (R40 again) -------------------------------------------------------------------------

    /**
     * The grain: the same R40 store, the same legal rule, and the same sentence.
     *
     * `grainDepth` is a level and `GrainSpec.depth` is a `Param`, so it becomes the base.
     * `grainDepthMinimum` is a *different* number with no field of its own (Joy's `GrainSpec` has one
     * depth, not a range), so it is kept raw and said.
     *
     * **PROVISIONAL — Claude to confirm:** Decision 1 lists `grainDepth` and `grainDepthMinimum`
     * together against `paperGrain.depth`. Folding the minimum into a `distance` ramp would invent a
     * sensor Procreate did not state, so this keeps the minimum raw and says so; that is one edit if
     * the Lead prefers a curve.
     */
    private fun grain(root: PlistValue.Dict) {
        val bytes = zip.findIn(folder, ProcreateImport.GRAIN_NAME)?.let { zip.read(it) }
        val bundled = root.text("bundledGrainPath")
        if (bytes == null && !bundled.isNullOrBlank()) {
            // Decision 8 again, and the reason **also** carries TEXTURE_NOT_DRAWN so that a grep for
            // the R40 sentence finds the brushes refused for a texture as well as the ones that
            // stored one.
            throw BrushException(
                "it needs Procreate's built-in grain \"$bundled\", which Joy Brush does not ship and " +
                    "may not extract (R4 §5), and the archive carries no ${ProcreateImport.GRAIN_NAME}; " +
                    TEXTURE_NOT_DRAWN
            )
        }
        root.at("grainDepth")?.let { grainDepth = unit("grainDepth", numberOf(it) ?: 0f) }
        root.at("grainDepthMinimum")?.let { value ->
            raw(value, "grainDepthMinimum")
            warn(
                "grainDepthMinimum has no Joy Brush field of its own (paperGrain.depth is one level, " +
                    "not a range), so only grainDepth became the depth; kept raw in extensions"
            )
        }
        root.at("textureScale")?.let { value ->
            raw(value, "textureScale")
            val v = numberOf(value) ?: 0f
            // Rule 14: above 0 and at most 64. Both ends are clamped rather than refused, because the
            // number is a number and only Joy's range is narrower.
            if (!v.isFinite() || v <= 0f || v > MAX_GRAIN_SCALE) {
                val clamped = if (!v.isFinite() || v <= 0f) 1f else MAX_GRAIN_SCALE
                warn(
                    "textureScale $v clamped to $clamped (a grain scale is above 0 and at most " +
                        "$MAX_GRAIN_SCALE)"
                )
                grainScale = clamped
            } else {
                grainScale = v
            }
            notes.approximate("textureScale")
        }
        for (key in TEXTURE_LOSSY) {
            root.at(key)?.let { value ->
                raw(value, key)
                warn(
                    "$key has no Joy Brush field — the grain it adjusts is not drawn yet — so it is " +
                        "lost; kept raw in extensions"
                )
            }
        }
        if (bytes != null) {
            storedGrain = Stored(GRAIN_FILE_NAME, bytes)
            grainImagePresent = true
        } else if (bundled.isNullOrBlank()) {
            grainEnabledByDepth = grainDepth > 0f
        }
    }

    // ---- colour -------------------------------------------------------------------------------------

    /**
     * Hue, saturation and lightness jitter, on the **magnitude-decides** rule (Decision 3a).
     *
     * `ColorJitter`'s three floats are ranged `0f..1f` by `BrushValidate` rule 15, and Procreate's
     * stored range is written down nowhere this build can check — R4 §B.2 lists colour jitter among
     * the *Clean* mappings while its fidelity table marks the same keys `≈`, and the two disagree. So:
     *
     *  - `v == 0` → `0`. Both readings agree, so there is nothing to say.
     *  - `0 < v <= 1` → `v`, **unresolved**: it is either `v` or `v / 100`, and the one pack sentence
     *    says so rather than each of 200 brushes saying it.
     *  - `v > 1` → `v / 100` clamped to 1, with a per-brush clamp warning naming both numbers.
     *
     * A blind `/100` here is the exact failure JB-8.01 already fell into: a real `0.5` would become
     * `0.005`, which is *still inside* `0f..1f`, so rule 15 says nothing, no clamp fires, and **no
     * warning can be produced**. A silently 100×-wrong colour jitter is a brush that imports
     * successfully and draws differently.
     */
    private fun colour(root: PlistValue.Dict) {
        color = ColorJitter(
            hue = colourJitter(root, "dynamicsJitterHue"),
            saturation = colourJitter(root, "dynamicsJitterSaturation"),
            value = colourJitter(root, "dynamicsJitterLightness"),
            perStroke = true,
        )
    }

    private fun colourJitter(root: PlistValue.Dict, key: String): Float {
        val value = root.at(key) ?: return 0f
        raw(value, key)                                  // the raw always survives
        val v = numberOf(value) ?: return 0f
        if (!v.isFinite() || v == 0f) return 0f
        if (v <= 1f) {
            notes.unresolvedScale(key)
            return v.coerceIn(0f, 1f)
        }
        notes.percentageScale(key)
        val divided = v / 100f
        if (divided > 1f) {
            warn("$key $v clamps to 1.0 (colour jitter is 0..1, and $v is not a fraction of it)")
            return 1f
        }
        return divided
    }

    // ---- rendering ----------------------------------------------------------------------------------

    private fun rendering(root: PlistValue.Dict) {
        for (key in BLEND_KEYS) {
            val value = root.at(key) ?: continue
            raw(value, key)
            val text = root.text(key) ?: continue
            when (text.lowercase()) {
                "normal" -> blend = "normal"
                "erase" -> blend = "erase"
                else -> warn(
                    "$key \"$text\" is not one of normal or erase, so the stroke is composited as " +
                        "normal and the mode is lost; kept raw in extensions"
                )
            }
            return
        }
    }

    // ---- the taper family (Decision 9) ---------------------------------------------------------------

    /**
     * The taper family, **LOSSY with a warning** (R40).
     *
     * R4 §C is explicit and is the reason this is not simply left in the sweep: *"Photoshop has no
     * taper tip shape. Procreate 'taper' means stroke-end size and opacity taper, a different thing.
     * Keep both."* So a Procreate taper does **not** go into `tip.taper`, which is a *shape* taper,
     * and `tip.taper` stays `0f`. The raw pairs go to one `extensions` key under one warning.
     *
     * **R40 ruled this LOSSY rather than REFUSED**, and the reason is in the ruling: refusing taper
     * refuses most of Procreate, and the brush format has no stroke envelope to refuse *into*.
     */
    private fun taper(root: PlistValue.Dict) {
        val found = LinkedHashMap<String, PlistValue>()
        for (key in root.values.keys.sorted()) {
            if (TAPER_PREFIXES.none { key.startsWith(it) }) continue
            root.values[key]?.let { found[key] = it }
        }
        if (found.isEmpty()) return
        extensions[TAPER_KEY] = PlistValue.Dict(found).render()
        val named = found.keys.take(4).joinToString(", ")
        val rest = if (found.size > 4) " and ${found.size - 4} more" else ""
        warn(
            "the taper family (${found.size} settings: $named$rest) is a stroke-end size and opacity " +
                "taper, which Joy Brush has no field for; tip.taper is a shape taper and is left at " +
                "0; kept raw in extensions"
        )
    }

    // ---- the dual brush ----------------------------------------------------------------------------

    private fun dual() {
        if (!dual) return
        extensions[DUAL_KEY] = "true"
        warn(
            "this brush is a dual brush (${ProcreateImport.DUAL_FOLDER}), and Joy Brush has no masking " +
                "tip, so only the first brush of the pair is imported; kept raw in extensions"
        )
    }

    /**
     * Every key this importer did not read, kept raw and named.
     *
     * Nothing is dropped in silence. A Procreate brush carries around forty settings and this row
     * maps perhaps fifteen of them, so this sweep is not an edge case — it is where the other half of
     * the brush lives, and a person who cannot see it will one day be surprised that their glaze
     * brush does not glaze.
     */
    private fun unknowns(root: PlistValue.Dict) {
        val left = root.values.keys.filter { it !in READ_KEYS }
        for (key in left) root.values[key]?.let { raw(it, key) }
        if (left.isNotEmpty()) {
            val named = left.take(6).joinToString(", ")
            val rest = if (left.size > 6) " and ${left.size - 6} more" else ""
            warn("${left.size} setting${rest} this build does not map ($named); kept raw in extensions")
            return
        }
        // Decision 6's empty brush: nothing but a name, an identifier and an author. It converts,
        // and it says that what it converted to is a default stamp rather than the artist's brush.
        val settings = root.values.keys.count { it in READ_KEYS && it !in METADATA_KEYS }
        if (settings == 0) {
            warn(
                "its archive carries no settings this build maps" +
                    (root.text("authorName")?.let { ", only a name and an author" } ?: ", only a name") +
                    ", so this is a default round stamp tip at $DEFAULT_DIAMETER_PX px"
            )
        }
    }

    // ---- finish -------------------------------------------------------------------------------------

    private fun finish(root: PlistValue.Dict, name: String, id: String): ImportResult {
        var tip = TipSpec(
            aspect = aspect,
            angle = Param(angleBase),
            followDirection = followDirection,
        )
        // R40's store, written now that the extensions budget is known. Over the cap the image is
        // dropped **whole**: a prefix of an image is a file that is not the thing it says it is.
        storedTip?.let { stored ->
            val b64 = procreateBase64Of(stored.bytes)
            if (extensionChars() + b64.length <= MAX_EXTENSION_BYTES) {
                extensions[TIP_IMAGE_KEY] = b64
                tip = tip.copy(source = "image", image = stored.fileName)
                warn(
                    "$TEXTURE_NOT_DRAWN; its tip is the archive's Shape.png, ${stored.bytes.size} bytes " +
                        "stored as ${b64.length} base64 characters for JB-1.05d. size.base is the " +
                        "diameter, not the bitmap, so the brush paints at the right size today"
                )
            } else {
                warn(
                    "$TEXTURE_NOT_DRAWN; its tip is the archive's Shape.png, ${stored.bytes.size} bytes, " +
                        "which is ${b64.length} base64 characters — over the $MAX_EXTENSION_BYTES-" +
                        "character extensions cap — so none of it is stored and the tip stays procedural"
                )
            }
        }

        var paperGrain = GrainSpec(
            // A grain image that did not fit leaves the grain **off**, not whatever the depth said.
            enabled = if (grainImagePresent) false else grainEnabledByDepth,
            scale = grainScale,
            depth = Param(grainDepth),
        )
        storedGrain?.let { stored ->
            val b64 = procreateBase64Of(stored.bytes)
            if (extensionChars() + b64.length <= MAX_EXTENSION_BYTES) {
                extensions[GRAIN_IMAGE_KEY] = b64
                paperGrain = paperGrain.copy(enabled = true, source = "image", image = stored.fileName)
                warn(
                    "$TEXTURE_NOT_DRAWN; its grain is the archive's Grain.png, ${stored.bytes.size} " +
                        "bytes stored as ${b64.length} base64 characters for JB-1.05d, and a depth of " +
                        "$grainDepth is what stands in for it until then"
                )
            } else {
                // Decision 3: over the cap, `source` stays "cloud" and `enabled` stays **false**,
                // because a grain that is on with no picture behind it is a stronger claim than one
                // that is off.
                warn(
                    "$TEXTURE_NOT_DRAWN; its grain is the archive's Grain.png, ${stored.bytes.size} " +
                        "bytes, which is ${b64.length} base64 characters — over the " +
                        "$MAX_EXTENSION_BYTES-character extensions cap — so none of it is stored, " +
                        "paperGrain stays off and its source stays cloud"
                )
            }
        }

        if (rawKept.isNotEmpty()) {
            val named = rawKept.take(8).joinToString(", ")
            val rest = if (rawKept.size > 8) " and ${rawKept.size - 8} more" else ""
            warn("kept raw in extensions: $named$rest")
        }

        val characters = extensionChars()
        if (characters > MAX_EXTENSION_BYTES) {
            throw BrushException("its extensions come to $characters characters, at most $MAX_EXTENSION_BYTES")
        }

        val preset = BrushPreset(
            id = id,
            name = name,
            engine = "stamp",
            tip = tip,
            size = Param(sizeBase, sizeInputs),
            opacity = Param(1f, opacityInputs),
            spacing = spacing,
            paperGrain = paperGrain,
            scatter = ScatterSpec(Param(scatterAmount), scatterCount, countJitter),
            color = color,
            blend = blend,
            smoothing = smoothing,
            // R4 §5 and blueprint §5: importing a file the user owns is fine, shipping a converted
            // third-party pack is not ours to do. A `.brush` carries no licence field, so the field
            // says "unknown" — and **never "CC0"**, which is `BrushPreset`'s own default and would be
            // a claim about someone else's work that nobody in this file made.
            license = LICENSE_KEYS.firstOrNull { !root.text(it).isNullOrBlank() }?.let { root.text(it)!! }
                ?: "unknown",
            author = root.text("authorName") ?: "",
            sourceFormat = "procreate",
            extensions = extensions,
        )

        // Decision 14: everything ends up validate-clean or absent. Every value above has already been
        // clamped or refused, so this should always be empty; if it is not, the *mapping* is wrong and
        // the house rule is to refuse in words rather than ship a brush the validator will reject.
        val problems = BrushValidate.validate(preset)
        if (problems.isNotEmpty()) {
            throw BrushException("it would not be a legal brush: " + problems.joinToString("; "))
        }
        return ImportResult(preset, warnings)
    }

    // ---- small helpers -----------------------------------------------------------------------------

    private fun extensionChars(): Int {
        var total = 0
        for (v in extensions.values) total += v.length
        return total
    }

    private fun warn(message: String) {
        warnings += message
    }

    /** One raw value into `extensions` under this row's own prefix, and remembered for the summary. */
    private fun raw(value: PlistValue, key: String) {
        extensions[PROCREATE_PREFIX + key] = value.render()
        rawKept += key
    }

    private fun numberOf(value: PlistValue): Float? = when (value) {
        is PlistValue.Real -> if (value.value.isFinite()) value.value.toFloat() else null
        is PlistValue.Num -> value.value.toFloat()
        else -> null
    }

    /** A number straight into a 0..1 field, clamped with both numbers named. Never guessed at. */
    private fun unit(field: String, value: Float): Float {
        if (!value.isFinite()) {
            warn("$field is $value, which is not a number; used 0")
            return 0f
        }
        if (value < 0f) {
            warn("$field $value clamped to 0 (the field is 0..1)")
            return 0f
        }
        if (value > 1f) {
            warn("$field $value clamped to 1 (the field is 0..1)")
            return 1f
        }
        return value
    }

    companion object {

        /**
         * The diameter a brush with no `maxSize` and no `Shape.png` gets, in px.
         *
         * **No Procreate default is documented in R4**, so this is a stated choice and not a sourced
         * number: 13 px is the value JB-8.01 uses for the same "the file states no diameter" case, so
         * the two importers do not disagree with each other, and the brush says it in a warning.
         * Anything in `(0, 4096]` validates, so this is a starting size and not a claim about the
         * artist.
         */
        private const val DEFAULT_DIAMETER_PX = 13f

        /** Below this a dab is not a brush, and the validator has no lower rule to catch it. */
        private const val MIN_DIAMETER_PX = 0.01f

        /** `BrushValidate` rule 4's range, restated because the clamp has to say both numbers. */
        private const val SPACING_MIN = 0.005f
        private const val SPACING_MAX = 5f
        private const val DEFAULT_SPACING = 0.04f

        /** `BrushPreset`'s own default, restated so the warning can name the number it kept. */
        private const val DEFAULT_SMOOTHING = 0.3f

        /** `BrushValidate` rule 14's grain-scale ceiling, restated for the same reason. */
        private const val MAX_GRAIN_SCALE = 64f

        /**
         * R40: "the file name names the encoding of the stored bytes, never the foreign file's name."
         * These really are PNGs in the archive, and a brush folder is `<folder>/brush.json` with
         * `TipSpec.image` documented as a path **inside the brush folder** — so `shape.png`, which is
         * a path inside the foreign `.brush`, would name a file no brush folder will ever contain.
         */
        private const val TIP_FILE_NAME = "tip.png"
        private const val GRAIN_FILE_NAME = "grain.png"

        private const val TIP_IMAGE_KEY = "procreate.tipImage"
        private const val GRAIN_IMAGE_KEY = "procreate.grainImage"
        private const val TAPER_KEY = "procreate.taper"
        private const val DUAL_KEY = "procreate.dual"
        private const val PROCREATE_PREFIX = "procreate."

        /** Keys that would name a licence. None is in a real `.brush`; all three are checked anyway. */
        private val LICENSE_KEYS = listOf("license", "licenseName", "licence")

        private val FLIP_KEYS = listOf("shapeFlipXJitter", "shapeFlipYJitter")

        private val BLEND_KEYS = listOf("blendMode", "extendedBlend")

        private val TEXTURE_LOSSY = listOf(
            "grainBlendMode", "textureBrightness", "textureContrast", "textureInverted",
            "textureMovement", "textureRotation", "textureOrientation", "grainOrientation",
        )

        /**
         * The taper family's prefixes (Decision 9). Two of them, and the `*Shape` / `*Linked`
         * siblings come along for free because the test is a prefix and not an exact key list.
         */
        private val TAPER_PREFIXES = listOf("pencilTaper", "taper")

        /**
         * Keys that carry no setting: a name, an id, an author, a licence, and the two paths that
         * name a Procreate built-in. Used to tell "this archive has no settings" from "this archive
         * has settings none of which this build maps".
         */
        private val METADATA_KEYS = setOf(
            "name", "identifier", "authorName", "license", "licenseName", "licence",
            "bundledShapePath", "bundledGrainPath",
        )

        /**
         * Every key this importer reads. Anything else is kept raw by [unknowns], which is what makes
         * "nothing is dropped in silence" a fact about the code rather than an intention.
         */
        private val READ_KEYS: Set<String> = METADATA_KEYS + setOf(
            "maxSize", "shapeAngle", "shapeRoundness", "shapeRotation",
            "shapeCount", "shapeCountJitter", "shapeFlipXJitter", "shapeFlipYJitter",
            "plotSpacing", "plotJitter", "plotSmoothing",
            "dynamicsPressureSize", "dynamicsPressureSizeCurve",
            "dynamicsTiltSize", "dynamicsTiltSizeCurve",
            "dynamicsPressureOpacity", "dynamicsPressureOpacityCurve",
            "dynamicsTiltOpacity", "dynamicsTiltOpacityCurve",
            "grainDepth", "grainDepthMinimum", "textureScale",
            "grainBlendMode", "textureBrightness", "textureContrast", "textureInverted",
            "textureMovement", "textureRotation", "textureOrientation", "grainOrientation",
            "dynamicsJitterHue", "dynamicsJitterSaturation", "dynamicsJitterLightness",
            "blendMode", "extendedBlend",
        )
    }
}

// ---- base64 (RFC 4648 §4) --------------------------------------------------------------------------------

/**
 * RFC 4648 §4 "Base 64 encoding": the standard alphabet `A–Z a–z 0–9 + /` with `=` padding.
 *
 * Derived, not recalled: three input bytes are 24 bits and each of the four output characters takes
 * six, so the four indices are `(n >> 18) & 63`, `(n >> 12) & 63`, `(n >> 6) & 63`, `n & 63`, where
 * `n` is the three bytes big-endian. One trailing byte is 8 bits and pads with `==`; two are 16 and
 * pad with `=`. No line breaks, because `extensions` is a JSON string.
 *
 * **This is the same function JB-8.01 has, and it is duplicated rather than shared.** That copy is
 * `private` in `AbrImport.kt`, which this row does not own and may not edit, and editing another
 * row's file is exactly what the owner-area rule forbids. It is an encoder, not a rule or a budget:
 * it has no opinion about brushes, and the test checks it against an independently written decoder
 * rather than against itself. **If `ImportSupport` ever grows a `base64Of`, these two must be
 * reconciled in the same commit.**
 *
 * The two copies are named apart (`procreateBase64Of` here) because a same-named, same-signature
 * private function in one package is one symbol, and which file the compiler picks is not something
 * a reader should have to work out.
 */
private const val PROCREATE_BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun procreateBase64Of(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 18) and 63])
        sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 12) and 63])
        sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 6) and 63])
        sb.append(PROCREATE_BASE64_ALPHABET[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 18) and 63])
            sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 12) and 63])
            sb.append("==")
        }
        2 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16 or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 18) and 63])
            sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 12) and 63])
            sb.append(PROCREATE_BASE64_ALPHABET[(n ushr 6) and 63])
            sb.append('=')
        }
    }
    return sb.toString()
}
