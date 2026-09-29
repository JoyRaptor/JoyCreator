/*
 * KeyedArchive.kt — the two property-list shapes a Procreate `Brush.archive` comes in.
 *
 * A `Brush.archive` is normally a **binary plist v0 written by NSKeyedArchiver** (`bplist00`): an
 * object table, `$objects` holding every value, and dictionaries whose entries are *UIDs* — indexes
 * into that table — rather than the values themselves. R4 §B.2: "the root dictionary is
 * `$objects[1]`", and the traversal rules are in Apple's `NSKeyedArchiver` documentation and in the
 * three MIT parsers R4 §B.2 names (dd-plist, bplist-parser, and freyalupen's `resolve_uids`, which
 * R4 calls "about 40 lines"). **No code is copied from any of them**; the bplist marker table is in
 * the open `bplist` format notes that every one of them follows, and every line of Kotlin here is
 * Joy Brush's own, with this project's budgets and this project's refusals.
 *
 * ## What JB-8.04 (Krita) may reuse
 *
 * This file is deliberately format-neutral, and the two entry points are all a second importer needs:
 *
 *  - **[read]** takes the bytes of a plist of *either* shape and says which shape it was, so a caller
 *    never has to sniff. Krita's `.bundle` `meta.xml` and the XML files under its `brushes/` folder
 *    are the same XML plist grammar, and a Krita `preset` is too.
 *  - **[PlistValue]**, **[root]** and the five accessors (`at`, `number`, `float`, `text`, `items`,
 *    `dict`) are the whole reading surface. `render` prints a value for `extensions` with a budget
 *    already applied, so a second importer gets "nothing is dropped in silence" for free.
 *  - The budgets are **ProcreateImport's**, not this file's, because Decision 11 puts them in the row
 *    that owns the format. JB-8.04 may point them at its own; it may not re-declare them here.
 *
 * What is *not* reusable: nothing in this file knows what a brush is. It reads property lists.
 */

package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException

// ---- the value tree -------------------------------------------------------------------------------------

/**
 * One plist value.
 *
 * A `Uid` is kept as a `Uid` and is not followed here: [KeyedArchive] resolves against `$objects` in
 * one later pass, because a UID's meaning depends on the table it belongs to, and a value that
 * resolved during the walk would have no place to report a dangling index from. `Data` is a plain
 * class rather than a data class for the reason every `ByteArray` in this codebase is one: the
 * generated `equals` is identity, so a `copy` into a map silently stops matching.
 */
internal sealed class PlistValue {

    /** `$null`, and a data marker with no payload. A Procreate archive writes this in place of an absent `bundledShapePath`. */
    object Absent : PlistValue()

    class Flag(val value: Boolean) : PlistValue()

    /** A binary-plist integer, always the signed 64-bit reading however wide it was stored. */
    class Num(val value: Long) : PlistValue()

    /** A binary-plist real, widened to `Double` however wide it was stored. */
    class Real(val value: Double) : PlistValue()

    class Data(val value: ByteArray) : PlistValue()

    class Text(val value: String) : PlistValue()

    /** An NSKeyedArchiver reference: an index into `$objects`. */
    class Uid(val index: Int) : PlistValue()

    /** A bplist `array` or `set`, and an XML `<array>`. */
    class Items(val values: List<PlistValue>) : PlistValue()

    class Dict(val values: Map<String, PlistValue>) : PlistValue()

    class Date(val value: Double) : PlistValue()
}

/** The value at [key], or null. Used all over the importer; the map itself is not exposed. */
internal fun PlistValue.Dict.at(key: String): PlistValue? = values[key]

/**
 * The **finite** number at [key], or null.
 *
 * Through `Double` so a real that overflows a `Float` is caught here rather than becoming an
 * `Infinity` three decisions later, and so an `Int` is read on the same terms as a `Real` — a
 * Procreate archive writes `maxSize` as a real in some versions and as an integer in others.
 */
internal fun PlistValue.Dict.number(key: String): Double? {
    val raw = values[key] ?: return null
    val v = when (raw) {
        is PlistValue.Real -> raw.value
        is PlistValue.Num -> raw.value.toDouble()
        else -> return null
    }
    return if (v.isFinite()) v else null
}

/** The finite number at [key] as a `Float`, or null. */
internal fun PlistValue.Dict.float(key: String): Float? = number(key)?.toFloat()

/** The text at [key], or null. An integer is **not** a name, so a `Num` is null here. */
internal fun PlistValue.Dict.text(key: String): String? = (values[key] as? PlistValue.Text)?.value

/** The array at [key], or null. */
internal fun PlistValue.Dict.items(key: String): List<PlistValue>? = (values[key] as? PlistValue.Items)?.values

/**
 * The dictionary at [key], or null.
 *
 * Every `*Curve` value in a Procreate archive is an **array of `"{x, y}"` strings** (R4 §B.2), and
 * the pairs are read as text rather than as numbers because that is what they are.
 */
internal fun PlistValue.Dict.dict(key: String): PlistValue.Dict? = values[key] as? PlistValue.Dict

/**
 * One value as the short text `extensions` carries.
 *
 * Bounded on three axes, all of them because the result ends up in a `brush.json` a person shares:
 * [depth] stops a value nested deeper than a screen can show, [ProcreateImport.MAX_RENDERED_CHARS]
 * stops one very long string, and a data blob becomes its byte count rather than its bytes.
 */
internal fun PlistValue.render(depth: Int = 0): String {
    if (depth > 8) return "…"
    return when (this) {
        is PlistValue.Absent -> ""
        is PlistValue.Flag -> if (value) "true" else "false"
        is PlistValue.Num -> value.toString()
        is PlistValue.Real -> if (value.isNaN()) "NaN" else if (value.isInfinite()) {
            if (value > 0) "Infinity" else "-Infinity"
        } else {
            value.toString()
        }
        is PlistValue.Data -> "${value.size} bytes"
        is PlistValue.Text -> value
        is PlistValue.Uid -> "reference ${index + 1}"
        is PlistValue.Date -> "date $value"
        is PlistValue.Items -> {
            val body = values.joinToString(", ", "[", "]") { it.render(depth + 1) }
            body.cap(ProcreateImport.MAX_RENDERED_CHARS)
        }
        is PlistValue.Dict -> {
            val sb = StringBuilder()
            sb.append('{')
            var first = true
            for ((k, v) in values) {
                if (!first) sb.append(", ")
                first = false
                sb.append(k).append('=').append(v.render(depth + 1))
                if (sb.length > ProcreateImport.MAX_RENDERED_CHARS) {
                    sb.append(" …")
                    break
                }
            }
            sb.append('}')
            sb.toString()
        }
    }
}

/** This text, cut to [cap] characters with an ellipsis. One place, so no caller has to remember. */
internal fun String.cap(chars: Int): String = if (length <= chars) this else substring(0, chars) + "…"

// ---- the archive ----------------------------------------------------------------------------------------

/**
 * One `Brush.archive` (or any other property list), parsed. [keyed] says which of the two shapes it
 * turned out to be.
 *
 * [root] is the brush's own dictionary, and it is a **method** rather than a property on purpose: an
 * NSKeyedArchiver archive's root does not exist until `$top["$top"]` has been followed, and a reader
 * that handed out a partly-resolved tree would make every caller carry a null check. Decision 7: a
 * UID that points outside `$objects` **refuses**, naming the index, because reading whatever happens
 * to be at that index is how a corrupt archive becomes a *different brush*.
 */
internal class KeyedArchive private constructor(
    private val objects: List<PlistValue>?,
    private val top: Map<String, PlistValue>,
    val keyed: Boolean,
) {

    /**
     * The brush dictionary, resolved.
     *
     * The route for a keyed archive, and it is two steps that the open format notes give exactly:
     * flat-table object 0 is the `$top` dictionary; `$top["$objects"]` is a UID to the **array**; and
     * the root is **`$objects[1]`** (R4 §B.2), which is a UID to the dictionary. `$objects[0]` is
     * the `$top` dictionary itself, which is why the root is the *second* element and not the first.
     *
     * Every `Uid(n)` resolved on the way is an index into the **flat** table, so
     * [KeyedArchive]'s `objects` is that table and not the array's contents.
     *
     * @throws BrushException on a dangling or cyclic UID, or if the shape is not what it must be.
     */
    fun root(): PlistValue.Dict {
        if (objects == null) return PlistValue.Dict(top)          // the flat XML case: the dict *is* the root
        val arrayRef = top["\$objects"] as? PlistValue.Uid
            ?: throw BrushException("the archive's \$top has no \"\$objects\" reference, so it holds no table")
        val table = objects.getOrNull(arrayRef.index) as? PlistValue.Items
            ?: throw BrushException(
                "the archive's \$top points at object ${arrayRef.index + 1}, which is not its \$objects array"
            )
        // `$objects[1]` is the root. A `$objects` with fewer than two entries is not a keyed archive.
        val rootRef = table.values.getOrNull(1) as? PlistValue.Uid
            ?: throw BrushException(
                "the archive's \$objects holds ${table.values.size} entries, and the root is the second " +
                    "one (R4 §B.2), so this is not an NSKeyedArchiver archive"
            )
        val resolved = Resolver(objects).resolve(rootRef, 0, HashSet())
        return (resolved as? PlistValue.Dict)
            ?: throw BrushException("the archive's root is a ${describe(resolved)}, not a dictionary")
    }

    companion object {

        /**
         * Read a property list of either shape.
         *
         * Decision 6, the sniff: `bplist00` → NSKeyedArchiver; anything starting with `<` → an XML
         * plist; **anything else is refused with a sentence naming what was found**, because a
         * `.brush` whose archive is a PNG or an AES container is a file this build cannot read and
         * saying "empty brush" would be a lie.
         *
         * @throws BrushException for a bad magic, a truncated structure, hostile XML, or any budget.
         *   These are faults in the FILE, so they travel out of `ProcreateImport.convertBrush`; in
         *   `convertBrushSet` the same fault inside one UUID folder becomes a refusal for that one
         *   brush, because the `.brushset` itself is perfectly readable.
         */
        fun read(bytes: ByteArray): KeyedArchive {
            // The byte order mark is checked **before** the sniff, not inside the XML branch: 0xEF is
            // above 0x20 and is not `<`, so a BOM-led file skips the sniff entirely and would otherwise
            // come out as "neither a binary plist nor an XML plist" — a true statement about the wrong
            // thing, and the one message a person with a BOM file would never be able to act on.
            if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == 0xEF) {
                throw BrushException(
                    "the archive starts with a UTF-8 byte order mark, which this build does not read; " +
                        "re-saving the file without a byte order mark will import it"
                )
            }
            if (bytes.size >= 8 && ascii(bytes, 0, 8) == BPLIST_MAGIC) {
                val (objects, top) = Bplist(bytes).read()
                return KeyedArchive(objects, top, true)
            }
            // An XML plist may start with whitespace, a comment or a prolog, so the sniff skips
            // whitespace and only then looks for '<'.
            val lead = bytes.indexOfFirst { (it.toInt() and 0xFF) > 0x20 }
            if (lead >= 0 && (bytes[lead].toInt() and 0xFF) == 0x3C) {
                return KeyedArchive(null, XmlPlist(bytes).rootDictionary().values, false)
            }
            throw BrushException(
                "its archive is neither a binary plist ($BPLIST_MAGIC) nor an XML plist: it starts " +
                    "\"${head(bytes)}\", so this build cannot read it"
            )
        }
    }
}

/** A one-word name for a value, for the message that says the root is not a dictionary. */
private fun describe(value: PlistValue): String = when (value) {
    is PlistValue.Dict -> "dictionary"
    is PlistValue.Items -> "array"
    is PlistValue.Text -> "string"
    is PlistValue.Num -> "integer"
    is PlistValue.Real -> "real"
    is PlistValue.Data -> "data blob"
    is PlistValue.Date -> "date"
    is PlistValue.Flag -> "boolean"
    is PlistValue.Uid -> "reference"
    is PlistValue.Absent -> "null"
}

private fun head(bytes: ByteArray): String {
    val n = minOf(bytes.size, 8)
    if (n == 0) return "(nothing)"
    val sb = StringBuilder(n)
    for (i in 0 until n) {
        val b = bytes[i].toInt() and 0xFF
        sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
    }
    return sb.toString()
}

private fun ascii(bytes: ByteArray, from: Int, count: Int): String {
    val sb = StringBuilder(count)
    for (i in from until from + count) sb.append((bytes[i].toInt() and 0xFF).toChar())
    return sb.toString()
}

// ---- UID resolution -------------------------------------------------------------------------------------

/**
 * Follows UIDs against `$objects`, with the two budgets that a hostile table needs.
 *
 * **A cycle is a refusal, not a `HashSet` of visited objects.** A UID that points at an object
 * already on the stack is a value that contains itself; there is no reading of it, and iterating
 * would be a stack overflow on a stranger's file. Decision 7's rule — a pointer that does not
 * resolve refuses the brush in words — is the same rule applied one step later.
 */
private class Resolver(private val objects: List<PlistValue>) {

    fun resolve(value: PlistValue, depth: Int, stack: HashSet<Int>): PlistValue {
        if (depth > ProcreateImport.MAX_DEPTH) {
            throw BrushException("the archive nests deeper than ${ProcreateImport.MAX_DEPTH}")
        }
        return when (value) {
            is PlistValue.Uid -> {
                val target = objects.getOrNull(value.index)
                    ?: throw BrushException(
                        "the archive points at object ${value.index + 1}, which is not in its \$objects " +
                            "table of ${objects.size}"
                    )
                if (!stack.add(value.index)) {
                    throw BrushException("the archive's object ${value.index + 1} contains itself")
                }
                try {
                    resolve(target, depth + 1, stack)
                } finally {
                    stack.remove(value.index)
                }
            }
            // `value.values`, not a bare `values`: the subject here is a *parameter*, so a smart cast
            // to `PlistValue.Items` does not bring its properties into scope unqualified the way a
            // `when (this)` would. That is the whole of the difference, and getting it wrong is a
            // compile error rather than a silent one — which is the good kind.
            is PlistValue.Items -> PlistValue.Items(value.values.map { resolve(it, depth + 1, stack) })
            is PlistValue.Dict -> PlistValue.Dict(
                value.values.entries.associateTo(LinkedHashMap()) { it.key to resolve(it.value, depth + 1, stack) }
            )
            else -> value
        }
    }
}

// ---- bplist v0 ------------------------------------------------------------------------------------------

/** The magic at byte 0 of a binary plist v0. */
private const val BPLIST_MAGIC = "bplist00"

/** The fixed record at the very end of a binary plist: 6 unused, sort version, offset size, ref size, count, root, table. */
private const val TRAILER = 32

/**
 * A data blob's ceiling.
 *
 * **1 MiB, and this is this file's own number, not a Procreate one.** No key in a Procreate
 * `Brush.archive` holds a blob, so any blob at all is already unusual; the number exists so that a
 * `0x4F` marker cannot ask for a 60 MiB allocation out of a small archive. A caller that meets one
 * in a real file has found something worth refusing.
 */
private const val MAX_DATA_BYTES = 1024 * 1024

/**
 * A big-endian cursor over the archive.
 *
 * **Big-endian, like `AbrCursor` and unlike `ByteReader`**, and for the same reason: two readers in
 * one package with opposite byte orders is how a file parses cleanly and means something else. A
 * bplist is big-endian. Every read is bounds-checked before it happens, so a truncated archive gives
 * a sentence that says so.
 */
private class PlistCursor(val data: ByteArray, var at: Int, val end: Int, val what: String) {

    fun remaining(): Int = end - at

    private fun need(n: Int) {
        if (n < 0 || at > end - n) {
            throw BrushException(
                "$what is truncated: $n more bytes were needed at offset $at, ${remaining()} are left"
            )
        }
    }

    fun u8(): Int {
        need(1)
        return data[at++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        at += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        at += 4
        return v
    }

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        at += 8
        return v
    }

    fun f64(): Double = Double.fromBits(u64())

    fun bytes(n: Int): ByteArray {
        need(n)
        val b = data.copyOfRange(at, at + n)
        at += n
        return b
    }
}

private fun hex(v: Int): String = "0123456789abcdef".substring(v shr 4, (v shr 4) + 1) +
    "0123456789abcdef".substring(v and 15, (v and 15) + 1)

/**
 * A bplist v0 object table.
 *
 * The layout, from the open format notes every parser follows: `"bplist00"`, then objects at offsets
 * taken from a table whose position is in the **32-byte trailer at the end of the file**, then each
 * object introduced by a marker byte whose high nibble is the type and whose low nibble is either a
 * small count or the width exponent of the count. Type `0x4` is data, `0x5` ASCII, `0x6` UTF-16BE,
 * `0x8` a UID, `0xA` an array, `0xC` a set, `0xD` a dictionary — and a low nibble of `0xF` means
 * "the count is the integer object that follows this marker".
 *
 * **The trailer is the only place the counts are, and a count is never an allocation.** Every count
 * is checked against a budget before the buffer for it exists, and the offset table is read inside
 * the file rather than trusted to be inside it. An object that is reached again while it is still
 * being read is left as a `Uid` — a cycle edge — rather than followed, so a self-referential table
 * terminates here and is refused in words by [Resolver].
 */
private class Bplist(private val data: ByteArray) {

    /** The flat object table, and `$top`. Both already read. */
    fun read(): Pair<List<PlistValue>, Map<String, PlistValue>> {
        if (data.size < 8 + TRAILER) {
            throw BrushException("its archive is ${data.size} bytes, too short to be a binary plist")
        }
        val trailer = PlistCursor(data, data.size - TRAILER, data.size, "the binary plist's trailer")
        // The trailer is 32 bytes: **5** unused, sort version, offset width, reference width, the
        // object count, the root object, and the offset table's own offset. 5 + 1 + 1 + 1 + 8 + 8 + 8.
        // (Six unused would make 33 and would shift every later field by a byte — which reads as a
        // table of nonsense offsets rather than as a wrong constant, so it is worth counting.)
        repeat(5) { trailer.u8() }                                                     // unused
        trailer.u8()                                                                    // sort version
        val offsetWidth = trailer.u8()
        val refWidth = trailer.u8()
        val count = trailer.u64()
        trailer.u64()                                                                   // the root object
        val tableAt = trailer.u64()
        if (offsetWidth !in 1..8 || refWidth !in 1..8) {
            throw BrushException(
                "its archive declares ${offsetWidth}-byte offsets and ${refWidth}-byte references, " +
                    "which is not a binary plist"
            )
        }
        if (count < 1 || count > ProcreateImport.MAX_OBJECTS) {
            throw BrushException(
                "its archive declares $count objects, at most ${ProcreateImport.MAX_OBJECTS}"
            )
        }
        if (tableAt < 8 || tableAt > Int.MAX_VALUE) {
            throw BrushException("its archive's offset table starts at $tableAt, outside the file")
        }
        val body = data.size - TRAILER
        val tableEnd = tableAt + count * offsetWidth
        if (tableEnd > body) {
            throw BrushException("its archive's offset table does not fit inside the file")
        }
        val table = PlistCursor(data, tableAt.toInt(), tableEnd.toInt(), "the offset table")
        val offsets = IntArray(count.toInt())
        for (i in offsets.indices) offsets[i] = readOffset(table, offsetWidth)
        for (i in offsets.indices) {
            if (offsets[i] < 8 || offsets[i] >= body) {
                throw BrushException(
                    "its archive's object ${i + 1} starts at offset ${offsets[i]}, outside the file"
                )
            }
        }

        val state = BplistState(data, offsets, refWidth, body)
        val objects = ArrayList<PlistValue>(offsets.size)
        for (i in offsets.indices) objects += state.get(i, 0)

        val top = objects.firstOrNull() as? PlistValue.Dict
            ?: throw BrushException("its archive has no \$top dictionary, so it was not written by an archiver")
        val tableRef = top.values["\$objects"] as? PlistValue.Uid
            ?: throw BrushException("its archive's \$top has no \"\$objects\" reference")
        val contents = objects.getOrNull(tableRef.index) as? PlistValue.Items
            ?: throw BrushException("its archive's \$objects is not an array of objects")
        if (contents.values.size > ProcreateImport.MAX_OBJECTS) {
            throw BrushException(
                "its archive's \$objects holds ${contents.values.size} entries, " +
                    "at most ${ProcreateImport.MAX_OBJECTS}"
            )
        }
        // **The flat table, not the `$objects` array's contents.** This is the one thing a UID means
        // and it is easy to get backwards: a `Uid(n)` anywhere in the archive is an index into the
        // *file's whole object table*, whose index 0 is the `$top` dictionary and whose index 1 is the
        // `$objects` array. The array's own elements are UIDs into that same flat table, so handing
        // back the elements as if they were the table shifts every reference by one and turns object
        // 2 into object 1 — an archive that parses cleanly and means a different brush.
        return objects to top.values
    }

    private fun readOffset(cur: PlistCursor, width: Int): Int {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or cur.u8().toLong()
        if (v > Int.MAX_VALUE) throw BrushException("its archive's offset table holds the offset $v")
        return v.toInt()
    }
}

/**
 * Reading one bplist object at a time, with the memo that makes a shared object shared.
 *
 * A real archive writes the same object once and refers to it twice, so each object is read **once**
 * and kept; the recursion is therefore bounded by the number of objects rather than by the number of
 * references, which is the difference between a table and a fork bomb. A reference is *not* followed
 * here at all — it is left as a `Uid` for [Resolver] — so this walk cannot be the thing that loops.
 */
private class BplistState(
    private val data: ByteArray,
    private val offsets: IntArray,
    private val refWidth: Int,
    private val body: Int,
) {

    private val values = arrayOfNulls<PlistValue>(offsets.size)
    private val reading = HashSet<Int>()
    private var nodes = 0

    fun get(index: Int, depth: Int): PlistValue {
        values[index]?.let { return it }
        // Already on the stack: this object contains itself. Left as a reference, and refused in
        // words by `Resolver`, which is where a bad index is reported from.
        if (!reading.add(index)) return PlistValue.Uid(index)
        try {
            val cur = PlistCursor(data, offsets[index], body, "object ${index + 1} of the binary plist")
            val value = readOne(cur, depth)
            values[index] = value
            return value
        } finally {
            reading.remove(index)
        }
    }

    private fun readOne(cur: PlistCursor, depth: Int): PlistValue {
        if (depth > ProcreateImport.MAX_DEPTH) {
            throw BrushException("the archive nests deeper than ${ProcreateImport.MAX_DEPTH}")
        }
        if (++nodes > ProcreateImport.MAX_OBJECTS) {
            throw BrushException(
                "the archive holds more than ${ProcreateImport.MAX_OBJECTS} values"
            )
        }
        val marker = cur.u8()
        val low = marker and 0x0F
        return when (marker shr 4) {
            0x0 -> when (marker) {
                0x00 -> PlistValue.Absent
                0x08 -> PlistValue.Flag(false)
                0x09 -> PlistValue.Flag(true)
                0x0F -> PlistValue.Flag(true)          // "fill": an empty placeholder; nothing reads one
                else -> throw BrushException("${cur.what}: unknown marker 0x${hex(marker)}")
            }
            // An integer is *signed*, of 1, 2, 4 or 8 bytes. Reading it unsigned is how a negative
            // `maxSize` becomes a very large one, which then fails a range check for the wrong reason.
            0x1 -> PlistValue.Num(integer(cur, widthOf(marker, cur.what)))
            0x2 -> when (val width = widthOf(marker, cur.what)) {
                4 -> PlistValue.Real((cur.u32() and 0xFFFFFFFFL).toInt().toFloat().toDouble())
                8 -> PlistValue.Real(cur.f64())
                else -> throw BrushException("${cur.what}: a real of $width bytes, which this build does not read")
            }
            0x3 -> PlistValue.Date(cur.f64())
            0x4 -> PlistValue.Data(cur.bytes(bounded(count(cur, low), MAX_DATA_BYTES, cur.what, "a data blob")))
            0x5 -> PlistValue.Text(
                latin1(cur.bytes(bounded(count(cur, low), ProcreateImport.MAX_STRING_CHARS, cur.what, "a string")))
            )
            0x6 -> {
                val n = bounded(count(cur, low), ProcreateImport.MAX_STRING_CHARS, cur.what, "a string")
                PlistValue.Text(utf16be(cur.bytes(n * 2)))
            }
            0x8 -> PlistValue.Uid(uid(cur, low))
            0xA, 0xC -> PlistValue.Items(references(cur, count(cur, low), cur.what))
            0xD -> dictionary(cur, count(cur, low), depth)
            else -> throw BrushException("${cur.what}: unknown marker 0x${hex(marker)}")
        }
    }

    private fun dictionary(cur: PlistCursor, count: Int, depth: Int): PlistValue.Dict {
        if (count > ProcreateImport.MAX_KEYS_PER_DICT) {
            throw BrushException(
                "${cur.what}: a dictionary of $count keys, at most ${ProcreateImport.MAX_KEYS_PER_DICT}"
            )
        }
        // The keys come first, as references; the values follow. A key is a reference to a *string*
        // object, which is why a key has to be resolved here and a value does not.
        val keyRefs = IntArray(count)
        for (i in 0 until count) keyRefs[i] = reference(cur, cur.what)
        val out = LinkedHashMap<String, PlistValue>(maxOf(4, count * 2))
        for (i in 0 until count) {
            val resolved = get(keyRefs[i], depth + 1)
            val key = (resolved as? PlistValue.Text)?.value
                ?: throw BrushException(
                    "${cur.what}: dictionary key ${i + 1} is a ${describe(resolved)}, not a string"
                )
            out[key] = PlistValue.Uid(reference(cur, cur.what))
        }
        return PlistValue.Dict(out)
    }

    private fun references(cur: PlistCursor, count: Int, what: String): List<PlistValue> {
        if (count > ProcreateImport.MAX_OBJECTS) {
            throw BrushException("$what: a collection of $count entries, at most ${ProcreateImport.MAX_OBJECTS}")
        }
        val out = ArrayList<PlistValue>(count)
        for (i in 0 until count) out += PlistValue.Uid(reference(cur, what))
        return out
    }

    /**
     * One `refWidth`-byte reference, **not** checked against the table size.
     *
     * The check is deliberately absent here and present in [Resolver], because Decision 7 wants the
     * refusal to name the index from the one place that can explain it — and a check in two places
     * is two messages for one fault.
     */
    private fun reference(cur: PlistCursor, what: String): Int {
        var v = 0L
        for (i in 0 until refWidth) v = (v shl 8) or cur.u8().toLong()
        if (v > Int.MAX_VALUE) throw BrushException("$what: a reference of $v objects")
        return v.toInt()
    }

    private fun uid(cur: PlistCursor, low: Int): Int {
        var v = 0L
        for (i in 0 until low + 1) v = (v shl 8) or cur.u8().toLong()
        if (v > Int.MAX_VALUE) throw BrushException("${cur.what}: a reference of $v objects")
        return v.toInt()
    }

    /** A `0x1n` marker's payload width: `1 shl n` bytes, 1, 2, 4 or 8. */
    private fun widthOf(marker: Int, what: String): Int {
        val width = 1 shl (marker and 0x0F)
        if (width > 8) throw BrushException("$what: a $width-byte number, which this build does not read")
        return width
    }

    /** A signed integer of [width] bytes, sign-extended. */
    private fun integer(cur: PlistCursor, width: Int): Long {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or cur.u8().toLong()
        if (width < 8) {
            val bits = width * 8
            if (v and (1L shl (bits - 1)) != 0L) v -= 1L shl bits
        }
        return v
    }

    /** The count a marker's low nibble states, or the integer object that follows a `0x?F` marker. */
    private fun count(cur: PlistCursor, low: Int): Int {
        if (low != 0x0F) return low
        val marker = cur.u8()
        if (marker shr 4 != 0x1) {
            throw BrushException(
                "${cur.what}: expected a count after a 0x?F marker, found 0x${hex(marker)}"
            )
        }
        val n = integer(cur, widthOf(marker, cur.what))
        if (n < 0) throw BrushException("${cur.what}: a count of $n")
        if (n > Int.MAX_VALUE) throw BrushException("${cur.what}: a count of $n, which this build will not allocate")
        return n.toInt()
    }

    private fun bounded(n: Int, cap: Int, what: String, noun: String): Int {
        if (n > cap) throw BrushException("$what: $noun of $n characters, at most $cap")
        return n
    }
}

/** Latin-1: the byte *is* the code point. One byte becomes one char, which is the bplist `0x5` rule. */
private fun latin1(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
    return sb.toString()
}

/** UTF-16BE, the bplist `0x6` rule. An odd byte count is a truncation, not a character. */
private fun utf16be(bytes: ByteArray): String {
    if (bytes.size % 2 != 0) {
        throw BrushException("the archive has a UTF-16 string of ${bytes.size} bytes, which is not a whole character")
    }
    val sb = StringBuilder(bytes.size / 2)
    var i = 0
    while (i < bytes.size) {
        val unit = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        sb.append(unit.toChar())
        i += 2
    }
    return sb.toString()
}

// ---- the XML plist --------------------------------------------------------------------------------------

/**
 * A small, hostile XML plist reader.
 *
 * Written here rather than taken from a platform plist parser for two reasons. `commonMain` may not
 * have one, and a parser that resolves `&xxe;` **is** an XXE: this one understands exactly five named
 * entities plus numeric character references, refuses a `<!DOCTYPE` outright, and has no notion of a
 * URL. A `.brush` is a stranger's file, and XXE is the first thing that comes to mind.
 *
 * The grammar is Apple's, and it is small: `<plist>` holding one `<dict>`, `<array>`, `<string>`,
 * `<integer>`, `<real>`, `<true/>`, `<false/>`, `<data>` and `<date>`. `<key>` appears only inside a
 * `<dict>`, paired with a value. A `<data>` or `<date>` is **refused in words** rather than skipped,
 * because a silent skip is a key the caller believes is absent.
 *
 * **JB-8.04 can use this as it stands** for a `.bundle`'s `meta.xml` and for the XML files under
 * its `brushes/` folder, which are the same grammar.
 */
private class XmlPlist(bytes: ByteArray) {

    private val text = decodeUtf8Strict(bytes, 0, bytes.size, "archive")
    private var at = 0

    fun rootDictionary(): PlistValue.Dict {
        skipSpace()
        if (at >= text.length) throw BrushException("its XML archive is empty")
        if (text[at] != '<') {
            throw BrushException("its XML archive starts with \"${text.take(20)}\" and never reaches a tag")
        }
        skipProlog()
        expect('<')
        if (readName() != "plist") {
            throw BrushException("its XML is not a plist: the root element is not <plist>")
        }
        skipToTagEnd()
        val value = readValue(0)
        return value as? PlistValue.Dict
            ?: throw BrushException("its XML plist holds a value, not a <dict>, so it is not a brush")
    }

    // ---- the scanner -------------------------------------------------------------------------------

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    /**
     * Past the prolog: whitespace, comments and `<?xml …?>`, and **never a `<!` declaration**.
     *
     * A `<!DOCTYPE` with an `ENTITY` is the XXE shape, and this reader refuses it before it has read
     * a single character of the declaration — so there is no path from here to a file or a URL.
     */
    private fun skipProlog() {
        while (true) {
            skipSpace()
            when {
                text.startsWith("<!--", at) -> {
                    val end = text.indexOf("-->", at + 4)
                    if (end < 0) throw BrushException("its XML has a comment that is never closed")
                    at = end + 3
                }
                text.startsWith("<?", at) -> {
                    val end = text.indexOf("?>", at + 2)
                    if (end < 0) throw BrushException("its XML has a \"<?\" that is never closed")
                    at = end + 2
                }
                text.startsWith("<!", at) -> throw BrushException(
                    "its XML carries a <!…> declaration, which this build refuses: a .brush is a " +
                        "stranger's file, and a declaration is how one asks for another"
                )
                else -> return
            }
        }
    }

    private fun expect(c: Char) {
        if (at >= text.length || text[at] != c) {
            val got = if (at < text.length) "\"${text[at]}\"" else "the end of the file"
            throw BrushException("its XML has $got where \"$c\" belongs")
        }
        at++
    }

    /**
     * Consume attributes up to and including the tag's `>`, and say whether it was `<tag/>`.
     *
     * A quoted attribute value may legally hold `>`, so the scan is quote-aware; a tag that runs off
     * the end of the file is a refusal rather than a `StringIndexOutOfBounds`.
     */
    private fun skipToTagEnd(): Boolean {
        var selfClosing = false
        while (at < text.length) {
            when (val c = text[at]) {
                '"', '\'' -> {
                    val end = text.indexOf(c, at + 1)
                    if (end < 0) throw BrushException("its XML has a \"$c\" that is never closed")
                    at = end + 1
                }
                '>' -> {
                    at++
                    return selfClosing
                }
                '/' -> {
                    if (at + 1 < text.length && text[at + 1] == '>') selfClosing = true
                    at++
                }
                else -> at++
            }
        }
        throw BrushException("its XML has a tag that is never closed")
    }

    private fun readName(): String {
        if (at < text.length && text[at] == '/') {
            throw BrushException(
                "its XML has a closing tag where a value was expected, so the file's tags do not match"
            )
        }
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '-' ||
                text[at] == '_' || text[at] == '.' || text[at] == ':')
        ) {
            at++
        }
        if (at == start) throw BrushException("its XML has \"<\" where an element name belongs")
        return text.substring(start, at)
    }

    // ---- values ------------------------------------------------------------------------------------

    private fun readValue(depth: Int): PlistValue {
        if (depth > ProcreateImport.MAX_DEPTH) {
            throw BrushException("its XML nests deeper than ${ProcreateImport.MAX_DEPTH}")
        }
        skipSpace()
        if (at >= text.length) throw BrushException("its XML ends where a value was expected")
        if (text[at] != '<') {
            throw BrushException("its XML has loose text \"${text.drop(at).take(20)}\" where a value belongs")
        }
        expect('<')
        val name = readName()
        val empty = skipToTagEnd()
        return when (name) {
            "string", "key" -> PlistValue.Text(if (empty) "" else readTextUntil(name))
            "integer" -> {
                val body = if (empty) "" else readTextUntil(name)
                val n = body.trim().toLongOrNull()
                    ?: throw BrushException("its XML has an <integer> of \"${body.trim()}\"")
                PlistValue.Num(n)
            }
            "real" -> {
                val body = if (empty) "" else readTextUntil(name)
                val n = body.trim().toDoubleOrNull()
                    ?: throw BrushException("its XML has a <real> of \"${body.trim()}\"")
                PlistValue.Real(n)
            }
            "true" -> {
                if (!empty) readTextUntil(name)
                PlistValue.Flag(true)
            }
            "false" -> {
                if (!empty) readTextUntil(name)
                PlistValue.Flag(false)
            }
            "array" -> if (empty) PlistValue.Items(emptyList()) else readArray(depth + 1)
            "dict" -> if (empty) PlistValue.Dict(emptyMap()) else readDictionary(depth + 1)
            "data" -> throw BrushException(
                "its XML holds a <data>, which is base64 this build does not read; nothing is skipped " +
                    "in silence"
            )
            "date" -> throw BrushException("its XML holds a <date>, which this build does not read")
            else -> throw BrushException("its XML holds a <$name>, which this build does not read")
        }
    }

    private fun readArray(depth: Int): PlistValue.Items {
        val out = ArrayList<PlistValue>()
        while (true) {
            skipSpace()
            if (text.startsWith("</array>", at)) {
                at += 8
                return PlistValue.Items(out)
            }
            if (at >= text.length) throw BrushException("its XML has an <array> that is never closed")
            if (out.size >= ProcreateImport.MAX_OBJECTS) {
                throw BrushException("its XML has an <array> of more than ${ProcreateImport.MAX_OBJECTS} items")
            }
            out += readValue(depth)
        }
    }

    private fun readDictionary(depth: Int): PlistValue.Dict {
        val out = LinkedHashMap<String, PlistValue>()
        while (true) {
            skipSpace()
            if (text.startsWith("</dict>", at)) {
                at += 7
                return PlistValue.Dict(out)
            }
            if (at >= text.length) throw BrushException("its XML has a <dict> that is never closed")
            if (out.size >= ProcreateImport.MAX_KEYS_PER_DICT) {
                throw BrushException(
                    "its XML has a <dict> of more than ${ProcreateImport.MAX_KEYS_PER_DICT} keys"
                )
            }
            skipSpace()
            // `<key` exactly: `<keynote>` is an element this reader does not have, and matching the
            // prefix would read a key out of it and invent a name.
            val isKey = text.startsWith("<key", at) &&
                (at + 5 >= text.length || text[at + 4] == '>' || text[at + 4] == '/' || text[at + 4] == ' ')
            if (!isKey) {
                throw BrushException("its XML has a <dict> with something that is not a <key> where a key belongs")
            }
            at += 4
            skipToTagEnd()
            val key = readTextUntil("key")
            out[key] = readValue(depth)
        }
    }

    /**
     * Character data up to `</name>`, with the five entities and nothing else.
     *
     * **An entity this reader does not know is a refusal, not a literal.** A caller that passed
     * `&xxe;` through would be writing a file path into a brush name, and one that dropped it would be
     * writing a different string than the file says. Both are worse than saying so.
     */
    private fun readTextUntil(name: String): String {
        val sb = StringBuilder()
        while (true) {
            if (at >= text.length) {
                throw BrushException("its XML has a <$name> that is never closed")
            }
            if (text.startsWith("</$name>", at)) {
                at += name.length + 3
                if (sb.length > ProcreateImport.MAX_STRING_CHARS) {
                    throw BrushException(
                        "its XML <$name> holds more than ${ProcreateImport.MAX_STRING_CHARS} characters"
                    )
                }
                return sb.toString()
            }
            val c = text[at]
            if (c == '&') {
                sb.append(readEntity())
                continue
            }
            sb.append(c)
            at++
            if (sb.length > ProcreateImport.MAX_STRING_CHARS) {
                throw BrushException(
                    "its XML <$name> holds more than ${ProcreateImport.MAX_STRING_CHARS} characters"
                )
            }
        }
    }

    private fun readEntity(): String {
        val end = text.indexOf(';', at)
        if (end < 0 || end - at > 12) throw BrushException("its XML has a \"&\" that is never closed")
        val body = text.substring(at + 1, end)
        at = end + 1
        when (body) {
            "amp" -> return "&"
            "lt" -> return "<"
            "gt" -> return ">"
            "quot" -> return "\""
            "apos" -> return "'"
        }
        if (body.startsWith("#")) {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
            if (code == null || code < 0 || code > 0x10FFFF) {
                throw BrushException("its XML has a character reference \"&$body;\" that is not a character")
            }
            return code.toChar().toString()
        }
        throw BrushException(
            "its XML uses the entity \"&$body;\", which is not one of amp, lt, gt, quot or apos: " +
                "this reader resolves nothing else, and a stranger's file does not get to define one"
        )
    }
}
