/*
 * AbrReader.kt — the Photoshop `.abr` container and its Action Descriptors.
 *
 * Format knowledge ported from **ag-psd** (Agamnentzar, https://github.com/Agamnentzar/ag-psd),
 * `src/abr.ts` and `src/descriptor.ts`, which is MIT-licensed. R4 §5: keep the copyright notice on
 * anything ported, so it is reproduced here in full.
 *
 *     MIT License
 *
 *     Copyright (c) 2017 Agamnentzar
 *
 *     Permission is hereby granted, free of charge, to any person obtaining a copy
 *     of this software and associated documentation files (the "Software"), to deal
 *     in the Software without restriction, including without limitation the rights
 *     to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *     copies of the Software, and to permit persons to whom the Software is
 *     furnished to do so, subject to the following conditions:
 *
 *     The above copyright notice and this permission notice shall be included in all
 *     copies or substantial portions of the Software.
 *
 *     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *     IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *     FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *     AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *     LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *     OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *     SOFTWARE.
 *
 * What is taken is **the format**: the container layout, the Action Descriptor OSType table and the
 * `bVTy` control table are facts about files, and the three MIT parsers (ag-psd, abrkit,
 * abr-to-krita) agree on them. Every line of Kotlin here is Joy Brush's own, and the budgets, the
 * refusals and the message wording are this project's.
 *
 * Where the knowledge is *not* solid, the code says so at the point it matters:
 *  - **The container and the descriptor OSTypes are Adobe-documented.** R4 §B.1: Adobe's public
 *    *Photoshop File Formats Specification* documents Action Descriptors, patterns and VMA lists.
 *    The `8BIM` section list and the `desc`/`samp`/`patt`/`phry` keys come from there and from the
 *    three MIT parsers.
 *  - **The brush key schema (`Brsh`, `Dmtr`, `szVr`, `bVTy`, …) is not documented by anyone.** R4
 *    §A.2 marks it [O2]: read out of real files by the parsers above. The keys are read by name and a
 *    key this build has never heard of is kept raw in `extensions` rather than guessed at.
 *  - **The `samp` entry layout is ag-psd's, verified byte for byte against real files
 *    (JB-8.01b).** Each entry is framed by its own `u32` length (padded to 4): a Pascal id with no
 *    padding, 10 bytes of preamble for minor version 1 or 264 for minor 2, bounds as top, left,
 *    bottom, right `int32`, then `depth` as `int16` and `compression` as one byte. An entry whose
 *    `depth` is not 8 or 16, whose compression is not 0/1, or whose 16-bit pixels are
 *    RLE-compressed is **not read at all**: the brush that names it is refused with a sentence. A
 *    wrong width must cost a brush and a warning, never a number that looks right.
 */

package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException

// ---- what the file turned out to be --------------------------------------------------------------

/** One `.abr`, read. [bytes] is the whole file; every tip records offsets into it rather than a copy. */
internal class AbrFile(
    val version: Int,
    val subVersion: Int,
    val brushes: List<AbrBrush>,
    private val tips: List<AbrTip>,
    private val bytes: ByteArray,
) {
    /** The tips by their id, normalised once. See [AbrReader.normaliseId]. */
    val tipsById: Map<String, AbrTip> = tips.associateBy { AbrReader.normaliseId(it.id) }

    /**
     * The bytes of [tip] exactly as the file stores them — compressed if it is compressed.
     *
     * This is what R40 stores in `extensions["abr.tipImage"]`, and it is why the stored file's name
     * says which encoding it is: the returned array is a PackBits stream when `compression == 1` and
     * raw gray when it is 0.
     */
    fun storedBytes(tip: AbrTip): ByteArray = bytes.copyOfRange(tip.dataStart, tip.dataEnd)

    /**
     * The tip's gray bytes, one sample per byte, decoded here and thrown away by the caller.
     *
     * Not an image: no PNG, no inflate, no colour, no scaling. PackBits RLE is undone because
     * Decision 4's hardness stand-in is `0.25 + 0.7 × meanAlpha` and a mean is one pass over the
     * decoded samples. `depth` 8 and 16 are read (a 16-bit sample contributes its high byte);
     * anything else is refused, because "what does a 32-bit tip's alpha mean" is not a question an
     * importer should answer on a stranger's file.
     */
    fun grayBytes(tip: AbrTip): ByteArray {
        tip.error?.let { throw BrushException("its sampled tip cannot be read: $it") }
        val bps = AbrReader.bytesPerSample(tip.depth.toLong())
        val width = tip.width
        val height = tip.height
        val rowBytes = width * bps
        // Output is one gray byte per pixel; 16-bit raw samples contribute their high byte.
        // `width * height * bps` is already bounded by `MAX_TIP_BYTES` in tipError.
        val out = ByteArray((width * height).toInt())
        val cur = AbrCursor(bytes, tip.dataStart, tip.dataEnd, "sampled tip \"${tip.id}\"")
        // PackBits rows are preceded by one `u16` table of row byte counts (JB-8.01b); it is read
        // once, before any row, and the rows below only consume what the table promised.
        val counts: IntArray? = if (tip.compression == AbrReader.COMPRESSION_PACKBITS) {
            val rows = height.toInt()
            IntArray(rows) { cur.u16() }
        } else {
            null
        }
        var written = 0L
        for (row in 0 until height) {
            when (tip.compression) {
                AbrReader.COMPRESSION_RAW -> {
                    if (bps == 1L) {
                        cur.copyInto(out, written, width)
                    } else {
                        for (col in 0 until width) {
                            out[(written + col).toInt()] = (cur.u16() ushr 8).toByte()
                        }
                    }
                    written += width
                }
                AbrReader.COMPRESSION_PACKBITS -> {
                    val declared = counts!![row.toInt()]
                    if (declared.toLong() > cur.remaining()) {
                        throw BrushException(
                            "sampled tip \"${tip.id}\" row ${row + 1} claims $declared bytes, " +
                                "only ${cur.remaining()} are left: the file is truncated"
                        )
                    }
                    written += AbrReader.packBits(bytes, cur.at, cur.at + declared, out, written, rowBytes)
                    cur.skip(declared.toLong())
                }
                else -> throw BrushException("sampled tip \"${tip.id}\" uses compression ${tip.compression}")
            }
        }
        return out
    }

    /** 0..1, the mean of the tip's gray samples — the "alpha" of a tip that has only one channel. */
    fun meanAlpha(tip: AbrTip): Float {
        val gray = grayBytes(tip)
        var total = 0.0
        for (b in gray) total += (b.toInt() and 0xFF)
        val full = 255.0 * gray.size
        if (gray.isEmpty() || full <= 0.0) return 0f
        return (total / full).toFloat()
    }
}

/** One entry of the descriptor's `Brsh` list: either a brush, or the sentence saying it is not one. */
internal sealed class AbrBrush {
    class Read(val descriptor: AbrDescriptor) : AbrBrush()
    class Unreadable(val reason: String) : AbrBrush()
}

/**
 * One `samp` entry: the tip's own bounds, its depth and compression, and where its stored bytes are.
 *
 * [error] is set instead of the bounds when the entry's own numbers are impossible (D10: the width or
 * height out of Long, zero, negative, over the cap). The entry is *kept* so that the brush naming it
 * is refused with this sentence rather than with "not in this file", which would be a different lie.
 */
internal class AbrTip(
    val id: String,
    val top: Long,
    val left: Long,
    val bottom: Long,
    val right: Long,
    val depth: Int,
    val compression: Int,
    val dataStart: Int,
    val dataEnd: Int,
    val error: String?,
) {
    /** `right - left`, in Long and **after** the bounds have been checked. Never an Int subtraction. */
    val width: Long get() = right - left

    /** `bottom - top`, in Long. */
    val height: Long get() = bottom - top
}

/** One Action Descriptor: a class and a list of keyed values. */
internal class AbrDescriptor(val name: String, val classId: String, val items: List<AbrItem>) {
    private val byKey: Map<String, AbrValue> = LinkedHashMap<String, AbrValue>().also { m ->
        for (i in items) m[i.key] = i.value
    }

    fun value(key: String): AbrValue? = byKey[key]
    fun descriptor(key: String): AbrDescriptor? = (byKey[key] as? AbrValue.Desc)?.descriptor
    fun list(key: String): List<AbrValue>? = (byKey[key] as? AbrValue.Items)?.values

    /** The finite number at [key]. A string, a NaN, an Infinity and a missing key are all null. */
    fun number(key: String): Float? {
        val n = (byKey[key] as? AbrValue.Num)?.value ?: return null
        if (n.isNaN() || n.isInfinite()) return null
        if (n > MAX_EXACT_FLOAT) return null
        return n.toFloat()
    }

    /** The word at [key]: a text, or the value half of an enumeration. */
    fun text(key: String): String? = when (val v = byKey[key]) {
        is AbrValue.Text -> v.value
        is AbrValue.Enum -> v.valueId
        is AbrValue.Blob -> AbrReader.hexOf(v.value)
        else -> null
    }

    fun flag(key: String): Boolean? = when (val v = byKey[key]) {
        is AbrValue.Num -> v.value != 0.0
        is AbrValue.Text -> v.value.isNotEmpty()
        else -> null
    }
}

internal class AbrItem(val key: String, val value: AbrValue)

/** One descriptor value. The type is a fact about the file, so it is kept. */
internal sealed class AbrValue {
    /** `'obj '` + `'prop'`: the item at [index] of the descriptor that holds this one. */
    class Ref(val index: Int) : AbrValue()

    class Desc(val descriptor: AbrDescriptor) : AbrValue()
    class Num(val value: Double) : AbrValue()
    class Text(val value: String) : AbrValue()
    class Enum(val typeId: String, val valueId: String) : AbrValue()
    class Items(val values: List<AbrValue>) : AbrValue()
    class Blob(val value: ByteArray) : AbrValue()
}

private const val MAX_EXACT_FLOAT = 1.0e18

// ---- the reader -----------------------------------------------------------------------------------

internal object AbrReader {

    /** The only `.abr` versions this build reads. v1/v2 have a record layout of their own (R4 §B.1). */
    private val VERSIONS = setOf(6, 7, 9, 10)

    private const val SIG_8BIM = "8BIM"

    /** Bytes ag-psd skips after a `samp` entry's Pascal id: 10 for minor version 1, 264 for 2. */
    private const val SAMP_SKIP_SUB1 = 10
    private const val SAMP_SKIP_SUB2 = 264

    /**
     * Read one `.abr`.
     *
     * A fault in the *file* — bad magic, a version we do not read, a section length that overruns,
     * any budget — throws [BrushException]. A fault in one *brush* does not: it becomes
     * [AbrBrush.Unreadable] with the sentence, and the brushes around it still convert (D1).
     */
    fun read(bytes: ByteArray): AbrFile = try {
        readChecked(bytes)
    } catch (e: AbrCap) {
        throw BrushException(e.message ?: "a .abr is over one of this build's limits")
    }

    private fun readChecked(bytes: ByteArray): AbrFile {
        if (bytes.size > AbrImport.MAX_FILE_BYTES) {
            throw AbrCap(
                "a .abr is at most ${AbrImport.MAX_FILE_BYTES} bytes, this one is ${bytes.size}"
            )
        }
        val cur = AbrCursor(bytes, 0, bytes.size, "this .abr")
        val version = cur.u16()
        val subVersion = cur.u16()
        if (version !in VERSIONS) {
            throw BrushException(
                "a .abr of version $version cannot be read; this build reads 6, 7, 9 and 10"
            )
        }
        // ag-psd reads the minor version only on the 6/7/9/10 path and refuses anything but 1/2:
        // the `samp` preamble is 10 bytes for 1 and 264 for 2, so any other number has no layout.
        if (subVersion != 1 && subVersion != 2) {
            throw BrushException(
                "a .abr of minor version $subVersion cannot be read; this build reads 1 and 2"
            )
        }

        val tips = ArrayList<AbrTip>()
        var brushes: List<AbrBrush>? = null
        var sections = 0

        while (cur.remaining() >= 4) {
            sections++
            if (sections > AbrImport.MAX_SECTIONS) {
                throw AbrCap("a .abr carries at most ${AbrImport.MAX_SECTIONS} sections")
            }
            val signature = cur.ascii(4)
            if (signature != SIG_8BIM) {
                throw BrushException(
                    "section $sections starts \"$signature\", not \"$SIG_8BIM\": this is not a .abr"
                )
            }
            val key = cur.ascii(4)
            val length = cur.u32()
            if (length > AbrImport.MAX_SECTION_BYTES) {
                throw AbrCap("section $sections (\"$key\") is at most ${AbrImport.MAX_SECTION_BYTES} bytes, it claims $length")
            }
            if (length > cur.remaining().toLong()) {
                throw BrushException(
                    "section $sections (\"$key\") claims $length bytes, only ${cur.remaining()} are left: " +
                        "the file is truncated"
                )
            }
            val start = cur.at
            val end = start + length.toInt()
            val section = cur.region(end)
            // Case-insensitive, because the four keys that matter are lower-case in every file
            // R4 §B.1 describes and one writer's capital P must not read as "no brushes".
            when (key.lowercase()) {
                "samp" -> readTips(section, tips, subVersion)
                "desc" -> brushes = readBrushes(section)
                // `patt` (patterns, JB-1.05d) and `phry` (the preset group hierarchy) are skipped by
                // their own declared length: this importer reads nothing out of either, and skipping
                // a payload is the one use of a declared length that cannot invent a number.
                else -> {}
            }
            cur.seek(end)
            cur.align4()
        }

        if (brushes == null) {
            throw BrushException("this .abr has no \"desc\" section, so it holds no brush descriptors")
        }
        if (brushes.size > AbrImport.MAX_BRUSHES) {
            throw AbrCap("a .abr carries at most ${AbrImport.MAX_BRUSHES} brushes, this one claims ${brushes.size}")
        }
        return AbrFile(version, subVersion, brushes, tips, bytes)
    }

    // ---- `samp` -------------------------------------------------------------------------------------

    /**
     * The `samp` section, entry by entry against ag-psd's `readAbr` (`case 'samp'`).
     *
     * Each entry is framed by its own `u32` length (padded to 4): a Pascal id with no padding, then
     * 10 bytes of preamble for minor version 1 or 264 for minor 2, then the bounds as **top, left,
     * bottom, right** `int32`, then `depth` as `int16` and `compression` as one byte. The section
     * cursor is put at the end of the entry whatever the pixels did, so no trailer is assumed and
     * no entry can run into the next.
     */
    private fun readTips(cur: AbrCursor, into: MutableList<AbrTip>, subVersion: Int) {
        val skip = if (subVersion == 1) SAMP_SKIP_SUB1 else SAMP_SKIP_SUB2
        while (cur.remaining() > 0) {
            if (cur.remaining() < 4) {
                // Fewer than four bytes cannot hold another entry's length: trailing padding inside
                // the section. Everything read so far is kept (see the `idLength` break below).
                break
            }
            val brushLength = cur.u32()
            var padded = brushLength
            while ((padded and 3L) != 0L) padded++
            if (padded > cur.remaining().toLong()) {
                throw BrushException(
                    "a sampled tip claims $brushLength bytes, only ${cur.remaining()} are left: " +
                        "the file is truncated"
                )
            }
            val brushEnd = cur.at + padded.toInt()
            val entry = cur.region(brushEnd)
            val idLength = entry.u8()
            if (idLength > entry.remaining() || idLength > AbrImport.MAX_STRING_BYTES) {
                // The entry after the last one is not there. Everything read so far is kept and every
                // brush that wanted a later tip is refused with "not in this file" — a shorter list
                // is a visible loss, a wrong list is not.
                break
            }
            val id = entry.ascii(idLength)
            entry.skip(skip.toLong())
            val top = entry.i32().toLong()
            val left = entry.i32().toLong()
            val bottom = entry.i32().toLong()
            val right = entry.i32().toLong()
            val depth = entry.i16()
            val compression = entry.u8()
            val dataStart = entry.at
            // The pixel region is bounded by the entry, and its own size is computed from bounds
            // that have been range-checked, so nothing below is an allocation a file chose freely.
            val error = tipError(top, left, bottom, right, depth.toLong(), compression.toLong(), entry.remaining())
            val dataEnd = pixelEnd(entry, top, left, bottom, right, depth.toLong(), compression.toLong(), error)
            into += AbrTip(id, top, left, bottom, right, depth, compression, dataStart, dataEnd, error)
            if (into.size > AbrImport.MAX_LIST_ITEMS) {
                throw AbrCap("a .abr carries at most ${AbrImport.MAX_LIST_ITEMS} sampled tips")
            }
        }
    }

    /**
     * Why this tip's own numbers cannot be used, or null. D10: the subtraction is in Long, and a
     * result that is zero, negative, over [AbrImport.MAX_TIP_DIM] or over `Int.MAX_VALUE` is refused
     * by name rather than becoming a negative width.
     */
    private fun tipError(
        top: Long,
        left: Long,
        bottom: Long,
        right: Long,
        depth: Long,
        compression: Long,
        remaining: Int,
    ): String? {
        val width = right - left
        val height = bottom - top
        if (width <= 0L || height <= 0L) {
            return "its bounds are $left,$top to $right,$bottom, a ${if (width <= 0) "width" else "height"} of ${
                if (width <= 0) width else height
            } px"
        }
        if (width > Int.MAX_VALUE.toLong() || height > Int.MAX_VALUE.toLong()) {
            return "it is ${width}x$height px, which is more pixels than this build can address"
        }
        if (width > AbrImport.MAX_TIP_DIM || height > AbrImport.MAX_TIP_DIM) {
            return "it is ${width}x$height px, at most ${AbrImport.MAX_TIP_DIM} on a side"
        }
        if (width * height > AbrImport.MAX_TIP_PIXELS) {
            return "it is ${width * height} pixels, at most ${AbrImport.MAX_TIP_PIXELS}"
        }
        val bps = AbrReader.bytesPerSample(depth)
        if (bps == 0L) {
            return "its depth is $depth bits; this build reads 8 and 16 bit gray"
        }
        if (width * height * bps > AbrImport.MAX_TIP_BYTES) {
            return "it decodes to ${width * height * bps} bytes, at most ${AbrImport.MAX_TIP_BYTES}"
        }
        if (compression != COMPRESSION_RAW.toLong() && compression != COMPRESSION_PACKBITS.toLong()) {
            return "its pixels are compressed with code $compression (zlib in the newer files), " +
                "which this build does not decode"
        }
        // ag-psd throws on 16-bit RLE ("not implemented"), so a 16-bit PackBits row has no oracle:
        // refusing it is the only answer that cannot silently draw the wrong tip.
        if (depth == 16L && compression == COMPRESSION_PACKBITS.toLong()) {
            return "its 16-bit pixels are RLE-compressed, which this build does not decode"
        }
        val rowBytes = width * bps
        if (compression == COMPRESSION_PACKBITS.toLong() && remaining < rowBytes) {
            return "it holds $remaining bytes of pixel data for a row of $rowBytes"
        }
        if (compression == COMPRESSION_RAW.toLong() && remaining.toLong() < rowBytes * height) {
            return "it holds $remaining bytes of pixel data for ${rowBytes * height} bytes"
        }
        return null
    }

    /** Where this tip's stored pixels end, or the section end when the tip is already known bad. */
    private fun pixelEnd(
        cur: AbrCursor,
        top: Long,
        left: Long,
        bottom: Long,
        right: Long,
        depth: Long,
        compression: Long,
        error: String?,
    ): Int {
        if (error != null) return cur.end
        val width = right - left
        val height = bottom - top
        val bps = bytesPerSample(depth)
        val rowBytes = width * bps
        val total = if (compression == COMPRESSION_PACKBITS.toLong()) {
            // The row byte counts come first as one `u16` table, then the PackBits rows back to
            // back — the same layout Photoshop uses for RLE image data, verified against real
            // files (JB-8.01b). The table is walked before a single row is, so a count can never
            // be read out of another row's bytes.
            var at = cur.at
            val rows = height.toInt()
            val counts = LongArray(rows)
            for (row in 0 until rows) {
                if (at + 2 > cur.end) {
                    throw BrushException("sampled tip pixel row ${row + 1} is truncated")
                }
                counts[row] = (((cur.data[at].toInt() and 0xFF) shl 8) or (cur.data[at + 1].toInt() and 0xFF)).toLong()
                at += 2
            }
            var sum = (at - cur.at).toLong()
            for (row in counts.indices) {
                val n = counts[row]
                if (n > cur.end - at) {
                    throw BrushException("sampled tip pixel row ${row + 1} claims $n bytes, the section ends first")
                }
                at += n.toInt()
                sum += n
            }
            sum
        } else {
            rowBytes * height
        }
        val end = cur.at + total.toInt()
        if (end > cur.end) {
            throw BrushException("sampled tip claims ${total} bytes of pixels, the section has ${cur.end - cur.at}")
        }
        return end
    }

    /** Bytes per sample for a bit depth, or 0 for a depth this build does not read. */
    fun bytesPerSample(depth: Long): Long = when (depth) {
        8L -> 1L
        16L -> 2L
        else -> 0L
    }

    /**
     * PackBits RLE (Apple's packbits, the scheme Adobe uses for `samp` compression code 1).
     *
     * `n` in 0..127 copies the next `n + 1` bytes; `n` in -127..-1 repeats the next byte `1 - n`
     * times; `n == -128` writes nothing. Decoded into [out] at [outAt], and the run stops when the
     * row is [exact] bytes long.
     *
     * **A run that overruns the payload is a refusal, not a row of zeros.** Filling the rest would be
     * a tip the artist did not draw, drawn at the right size and in the right place — the one
     * outcome this whole spec exists to prevent. Overrunning the *row* is different: the row is
     * already complete, so the run is simply cut, and no byte past [outAt] + [exact] is touched.
     */
    fun packBits(data: ByteArray, from: Int, to: Int, out: ByteArray, outAt: Long, exact: Long): Long {
        var i = from
        val end = outAt + exact
        var o = outAt
        while (o < end) {
            if (i >= to) {
                throw BrushException("PackBits payload is truncated: ${end - o} bytes of the row were never written")
            }
            val n = data[i++].toInt()
            when {
                n in 0..127 -> {
                    val count = n.toLong() + 1
                    if (i.toLong() + count > to.toLong()) {
                        throw BrushException("PackBits literal run of $count overruns the payload")
                    }
                    val room = end - o
                    val take = if (count < room) count else room
                    for (k in 0 until take) out[(o + k).toInt()] = data[i + k.toInt()]
                    i += take.toInt()
                    o += take
                }
                n in -127..-1 -> {
                    if (i >= to) {
                        throw BrushException("PackBits repeat run has no value byte")
                    }
                    val b = data[i++]
                    val count = 1L - n
                    val room = end - o
                    val take = if (count < room) count else room
                    for (k in 0 until take) out[(o + k).toInt()] = b
                    o += take
                }
                // -128 is a no-op by definition; it is the one header byte that writes nothing.
                else -> {}
            }
        }
        return o - outAt
    }

    // ---- `desc` --------------------------------------------------------------------------------------

    /**
     * The `desc` section: a `u32` descriptor version, then one Action Descriptor whose `Brsh` list
     * holds the presets (R4 §B.1, ag-psd `readVersionAndDescriptor`).
     *
     * **A count is never believed.** The `Brsh` list is an ordinary `VlLs` — a count and then that
     * many self-describing values — so its entries are read one at a time and the first entry that
     * will not parse refuses *itself and every entry after it*: without the invented byte length
     * there is no way to find where the next entry starts, and inventing a place is how a file
     * turns into a different brush. The brushes before it still convert, which is the whole of D1.
     */
    private fun readBrushes(cur: AbrCursor): List<AbrBrush> {
        val descriptorVersion = cur.u32()
        if (descriptorVersion < 4L) {
            throw BrushException(
                "descriptor version $descriptorVersion cannot be read; this build reads 4 and up"
            )
        }
        val budget = NodeBudget()
        // The root is read by hand rather than through `readDescriptor`, because the `Brsh` list has
        // to be **deferred**: its entries are parsed one at a time so that one that will not parse
        // refuses itself and not the file. Reading it as an ordinary value would parse all of them
        // inside the root and a single bad brush would take the whole pack with it.
        if (1 > AbrImport.MAX_DESCRIPTOR_DEPTH) throw AbrCap("unreachable")
        budget.spend()
        readUnicode(cur)          // the root's own name
        readKey(cur)              // …and its class
        val itemCount = cur.u32()
        if (itemCount > AbrImport.MAX_LIST_ITEMS) {
            throw AbrCap("a descriptor carries at most ${AbrImport.MAX_LIST_ITEMS} items, this one claims $itemCount")
        }
        val items = ArrayList<AbrItem>(if (itemCount < 64L) itemCount.toInt() else 64)
        var raw: RawBrushList? = null
        repeat(itemCount.toInt()) {
            val key = readKey(cur)
            if (key == "Brsh" && raw == null) {
                raw = readBrushEntries(cur, budget)
            } else {
                items += AbrItem(key, readValue(cur, budget, 1))
            }
        }
        val list = raw ?: throw BrushException("the brush descriptor has no \"Brsh\" list")
        return toBrushes(list, resolve(AbrDescriptor("", "", items)))
    }

    /** The `Brsh` list's entries as values, with the first entry that would not parse, if any. */
    private class RawBrushList(val count: Int, val values: List<AbrValue>, val failedAt: Int?, val failedReason: String?)

    /**
     * The `Brsh` list: `VlLs`, a count, then that many entries read straight off the section
     * cursor — there is no byte length to seek past in a real file.
     *
     * The first entry that will not parse stops the read: the cursor is then at an unknown place
     * inside the entry, so the entries after it cannot be found without reading it. Where they are
     * is said in [toBrushes], not guessed here.
     */
    private fun readBrushEntries(cur: AbrCursor, budget: NodeBudget): RawBrushList {
        val type = cur.ascii(4)
        if (type != "VlLs") {
            throw BrushException("\"Brsh\" is a \"$type\", not a list of brushes")
        }
        val count = cur.u32()
        if (count > AbrImport.MAX_BRUSHES) {
            throw AbrCap("a .abr carries at most ${AbrImport.MAX_BRUSHES} brushes, this one claims $count")
        }
        val values = ArrayList<AbrValue>(if (count < 64L) count.toInt() else 64)
        var failedAt: Int? = null
        var failedReason: String? = null
        for (index in 0 until count.toInt()) {
            try {
                values += readValue(cur, budget, 2)
            } catch (e: BrushException) {
                failedAt = index
                failedReason = e.message
                break
            }
        }
        return RawBrushList(count.toInt(), values, failedAt, failedReason)
    }

    /**
     * The brushes themselves, one value at a time.
     *
     * A `prop` reference is resolved against [root] — the root's items, which is what a pointer in
     * this position means — and a pointer that does not resolve refuses the brush with a sentence
     * rather than handing the caller a null it would read as "this brush has no tip". The first
     * refusal, parse or resolve, refuses every entry after it too: without a length there is no way
     * to find them, and the message says so outright.
     */
    private fun toBrushes(raw: RawBrushList, root: AbrDescriptor): List<AbrBrush> {
        val out = ArrayList<AbrBrush>(raw.count)
        var failedAt = raw.failedAt
        var failedReason = raw.failedReason
        for (index in 0 until raw.count) {
            if (failedAt != null && index > failedAt) {
                out += AbrBrush.Unreadable(
                    "brush ${index + 1} cannot be read: it comes after brush ${failedAt + 1}, " +
                        "which cannot be read ($failedReason), and the brushes after it " +
                        "cannot be found without reading it"
                )
                continue
            }
            if (failedAt != null && index == failedAt) {
                // The entry the read stopped on. Anything before it parsed and is resolved below;
                // this is the one failure, said once.
                out += AbrBrush.Unreadable("brush ${index + 1} cannot be read: $failedReason")
                continue
            }
            if (index >= raw.values.size) {
                // Unreachable: the read stops only on a failure, which sets failedAt. Refused
                // anyway, so a file that ends mid-list still says where rather than indexing air.
                failedAt = index
                failedReason = "the file ends before it"
                out += AbrBrush.Unreadable("brush ${index + 1} cannot be read: $failedReason")
                continue
            }
            val descriptor: AbrDescriptor = try {
                when (val value = raw.values[index]) {
                    is AbrValue.Desc -> value.descriptor
                    is AbrValue.Ref -> (root.items.getOrNull(value.index)?.value as? AbrValue.Desc)
                        ?.descriptor
                        ?: throw BrushException(
                            "the list's entry ${index + 1} points at item ${value.index + 1} of the " +
                                "descriptor, which is not a brush"
                        )
                    else -> throw BrushException("the list's entry ${index + 1} is a ${value.javaClass.simpleName}, not a brush")
                }
            } catch (e: BrushException) {
                failedAt = index
                failedReason = e.message
                out += AbrBrush.Unreadable("brush ${index + 1} cannot be read: $failedReason")
                continue
            }
            out += AbrBrush.Read(descriptor)
        }
        return out
    }

    /** Reads one Action Descriptor, at [depth]. Every node and every key string is a budget. */
    private fun readDescriptor(cur: AbrCursor, budget: NodeBudget, depth: Int): AbrDescriptor {
        if (depth > AbrImport.MAX_DESCRIPTOR_DEPTH) {
            throw AbrCap("a descriptor nests deeper than ${AbrImport.MAX_DESCRIPTOR_DEPTH}")
        }
        budget.spend()
        val name = readUnicode(cur)
        val classId = readKey(cur)
        val count = cur.u32()
        if (count > AbrImport.MAX_LIST_ITEMS) {
            throw AbrCap("a descriptor carries at most ${AbrImport.MAX_LIST_ITEMS} items, this one claims $count")
        }
        val items = ArrayList<AbrItem>(if (count < 64L) count.toInt() else 64)
        repeat(count.toInt()) {
            val key = readKey(cur)
            items += AbrItem(key, readValue(cur, budget, depth))
        }
        return resolve(AbrDescriptor(name, classId, items))
    }

    /**
     * Replace every `'obj '`/`'prop'` reference with the item it points at.
     *
     * The index is into **this descriptor's own** item list (that is what a `prop` reference means),
     * and a reference that points outside it, or at another reference that points outside it, is a
     * refusal: a cycle would otherwise be walked forever and a missing item would be read as null.
     */
    private fun resolve(descriptor: AbrDescriptor): AbrDescriptor {
        var changed = false
        val items = ArrayList<AbrItem>(descriptor.items.size)
        for (item in descriptor.items) {
            val value = item.value
            if (value !is AbrValue.Ref) {
                items += item
                continue
            }
            val target = descriptor.items.getOrNull(value.index)
                ?: throw BrushException(
                    "descriptor reference points at item ${value.index + 1} of ${descriptor.items.size}"
                )
            items += AbrItem(item.key, target.value)
            changed = true
        }
        return if (changed) resolve(AbrDescriptor(descriptor.name, descriptor.classId, items)) else descriptor
    }

    private fun readValue(cur: AbrCursor, budget: NodeBudget, depth: Int): AbrValue {
        budget.spend()
        val type = cur.ascii(4)
        return when (type) {
            "Objc" -> AbrValue.Desc(readDescriptor(cur, budget, depth + 1))
            "obj " -> readReference(cur, budget, depth)
            "VlLs" -> {
                val count = cur.u32()
                if (count > AbrImport.MAX_LIST_ITEMS) {
                    throw AbrCap("a list carries at most ${AbrImport.MAX_LIST_ITEMS} items, this one claims $count")
                }
                // A real list is the count and then `count` self-describing values, each carrying
                // its own type tag — ag-psd reads exactly this, with no byte length in between. The
                // section cursor bounds every read; the count cap and the node budget are the bomb
                // defence and stay.
                val values = ArrayList<AbrValue>(if (count < 64L) count.toInt() else 64)
                repeat(count.toInt()) { values += readValue(cur, budget, depth) }
                AbrValue.Items(values)
            }
            "doub" -> AbrValue.Num(cur.f64())
            "UntF" -> { cur.ascii(4); AbrValue.Num(cur.f64()) }   // unit key, then the number
            "long" -> AbrValue.Num(cur.u32().toDouble())
            "comp" -> AbrValue.Num(cur.u64().toDouble())
            "bool" -> AbrValue.Num(if (cur.u8() != 0) 1.0 else 0.0)
            "TEXT" -> AbrValue.Text(readUnicode(cur))
            "alis" -> AbrValue.Text(readText(cur))
            "enum" -> AbrValue.Enum(readKey(cur), readKey(cur))
            "type" -> AbrValue.Text(readKey(cur))
            "tdta" -> AbrValue.Blob(readBlob(cur))
            "GlbO" -> AbrValue.Blob(readBlob(cur))
            "ObAr" -> AbrValue.Blob(readBlob(cur))
            else -> throw BrushException("descriptor type \"$type\" is not read by this build")
        }
    }

    /**
     * An `'obj '` value: a pointer to something rather than the thing. Every variant the Action
     * Descriptor format defines is here; an unknown one is a refusal rather than a guess.
     */
    private fun readReference(cur: AbrCursor, budget: NodeBudget, depth: Int): AbrValue {
        val kind = cur.ascii(4)
        return when (kind) {
            "obj " -> AbrValue.Desc(readDescriptor(cur, budget, depth + 1))
            "prop" -> AbrValue.Ref(cur.u32().toInt())
            "name", "rele" -> AbrValue.Text(readText(cur))
            "nameFromClass" -> AbrValue.Text(readKey(cur))
            "Idnt" -> { cur.u32(); readKey(cur); readValue(cur, budget, depth) }
            "Enmr" -> { readKey(cur); readKey(cur); readValue(cur, budget, depth) }
            "indx" -> { cur.u32(); cur.u32(); readKey(cur); readValue(cur, budget, depth) }
            else -> throw BrushException("descriptor reference \"$kind\" is not read by this build")
        }
    }

    /**
     * A key or a class id: a `u32` byte count, and **a count of zero means the next four bytes are
     * the key** (which is how Photoshop writes `Brsh`, `Dmtr` and `H   `). Both forms are read; the
     * count is a budget, because it is the only thing standing between a file and a four-byte read.
     */
    fun readKey(cur: AbrCursor): String {
        val length = cur.u32()
        if (length == 0L) return cur.ascii(4)
        if (length > AbrImport.MAX_STRING_BYTES) {
            throw AbrCap("a key is at most ${AbrImport.MAX_STRING_BYTES} bytes, this one claims $length")
        }
        return cur.ascii(length.toInt())
    }

    /**
     * A Unicode string: a `u32` **character** count, then that many big-endian 16-bit units.
     *
     * Real files count a trailing NUL terminator in the length ("Soft Round" is stored as 11
     * units, the name and a NUL — JB-8.01b), while the oracle the acceptance tests read was parsed
     * without it — so trailing NULs are not part of the string. Interior ones are kept: dropping
     * bytes from the middle of a name would be a different name, not a cleaner one.
     */
    fun readUnicode(cur: AbrCursor): String {
        val chars = cur.u32()
        if (chars * 2L > AbrImport.MAX_STRING_BYTES) {
            throw AbrCap("a string is at most ${AbrImport.MAX_STRING_BYTES} bytes, this one claims ${chars * 2L}")
        }
        val sb = StringBuilder(chars.toInt())
        repeat(chars.toInt()) { sb.append(cur.u16().toChar()) }
        while (sb.isNotEmpty() && sb.last() == '\u0000') sb.deleteCharAt(sb.length - 1)
        return sb.toString()
    }

    /** A length-prefixed run of bytes, read as text. The length is a budget. */
    private fun readText(cur: AbrCursor): String {
        val length = cur.u32()
        if (length > AbrImport.MAX_STRING_BYTES) {
            throw AbrCap("a string is at most ${AbrImport.MAX_STRING_BYTES} bytes, this one claims $length")
        }
        return cur.ascii(length.toInt())
    }

    /** A length-prefixed run of raw bytes. The length is a budget. */
    private fun readBlob(cur: AbrCursor): ByteArray {
        val length = cur.u32()
        if (length > AbrImport.MAX_SECTION_BYTES) {
            throw AbrCap("a blob is at most ${AbrImport.MAX_SECTION_BYTES} bytes, this one claims $length")
        }
        if (length > cur.remaining().toLong()) {
            throw BrushException("a blob claims $length bytes, only ${cur.remaining()} are left: the file is truncated")
        }
        return cur.bytes(length.toInt())
    }

    fun hexOf(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    /**
     * The one id comparison. A `samp` entry's Pascal UUID and the `sampledData` that points at it
     * are the same string written twice, sometimes wrapped in braces and sometimes not — so both
     * sides are lower-cased and every brace and dash is dropped before they are compared. This
     * matches ids that differ only in punctuation; it cannot turn one id into another.
     */
    fun normaliseId(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    const val COMPRESSION_RAW = 0
    const val COMPRESSION_PACKBITS = 1
}

/** A budget exception that is always a *file*-level fault, so a per-brush catch must not swallow it. */
internal class AbrCap(message: String) : Exception(message)

/** One node of the descriptor tree, counted across the whole file. */
private class NodeBudget {
    private var nodes = 0
    fun spend() {
        nodes++
        if (nodes > AbrImport.MAX_DESCRIPTOR_NODES) {
            throw AbrCap("the descriptor tree has more than ${AbrImport.MAX_DESCRIPTOR_NODES} entries")
        }
    }
}

// ---- the cursor -------------------------------------------------------------------------------------

/**
 * A big-endian cursor over a byte range.
 *
 * **Big-endian, deliberately and not by accident.** The other reader in this package, `ByteReader`,
 * is little-endian and throws `StrokeCodecException`; a builder who reaches for it gets a file that
 * parses cleanly and means something else. Every read here is big-endian, which is what an `.abr` is.
 *
 * Every read is bounds-checked before it happens, so a truncated file produces a sentence that says
 * so rather than an index exception, and no read is ever partial.
 */
internal class AbrCursor(
    val data: ByteArray,
    val start: Int,
    val end: Int,
    val what: String,
) {
    var at: Int = start

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

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        at += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        at += 8
        return v
    }

    /** IEEE 754 double, big-endian: the bits are assembled the same way and reinterpreted. */
    fun f64(): Double = Double.fromBits(u64())

    fun bytes(n: Int): ByteArray {
        need(n)
        val b = data.copyOfRange(at, at + n)
        at += n
        return b
    }

    /** Copy [n] bytes into [out] at [outAt]. Bounds-checked on both sides before anything moves. */
    fun copyInto(out: ByteArray, outAt: Long, n: Long) {
        if (n < 0 || n > Int.MAX_VALUE || outAt < 0 || outAt + n > out.size) {
            throw BrushException("a $n-byte row does not fit the $n px tip it belongs to")
        }
        need(n.toInt())
        data.copyInto(out, outAt.toInt(), at, at + n.toInt())
        at += n.toInt()
    }

    fun skip(n: Long) {
        if (n < 0 || n > end - at.toLong()) {
            throw BrushException("$what is truncated: $n more bytes were needed at offset $at, ${remaining()} are left")
        }
        at += n.toInt()
    }

    fun seek(position: Int) {
        if (position < start || position > end) {
            throw BrushException("$what: offset $position is outside the $start..$end this reader was given")
        }
        at = position
    }

    /** A cursor over `[at, position)` of this one, and this one is left at [position]. */
    fun region(position: Int): AbrCursor {
        val r = AbrCursor(data, at, position, what)
        at = position
        return r
    }

    /**
     * `8BIM` sections are padded to a four-byte boundary, and the last section in a file may be
     * unpadded — so the padding is taken only when the bytes are actually there.
     */
    fun align4() {
        val pad = (4 - (at and 3)) and 3
        if (pad <= remaining()) skip(pad.toLong())
    }

    fun ascii(n: Int): String {
        val b = bytes(n)
        val sb = StringBuilder(n)
        for (x in b) sb.append((x.toInt() and 0xFF).toChar())
        return sb.toString()
    }
}
