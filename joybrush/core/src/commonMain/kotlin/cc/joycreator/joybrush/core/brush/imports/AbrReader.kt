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
 *
 * **What four real `.abr` files changed (JB-8.01b).** This reader was first written from a spec that
 * guessed, tested against files built from the same guess, and reviewed by models that could not see
 * a real file — so four parts of it were wrong and every real `.abr` failed to import. Each fix below
 * is against ag-psd's `readAbr` and against the four real files, and each one is a fact about the
 * bytes rather than a preference:
 *  - **A `VlLs` has no byte length.** It is `count:u32` and then `count` self-describing values, and
 *    the section's own end is the bound. The `byteLength` this reader used to read after the count was
 *    the first item's own type tag (`Objc`), which is why every real brush read as truncated.
 *  - **A `samp` entry begins with its own `u32` length**, padded up to a four-byte boundary; the entry
 *    ends there. This reader used to compute where the bounds were from a hard-coded offset counted
 *    from the entry's first byte (47 or 301), which is right for no `id` length at all.
 *  - **`depth` is an `i16` and `compression` a `u8`**, not two `u32`s. Reading eight bytes where a
 *    real file has three moved the pixel start five bytes past the truth.
 *  - **PackBits rows are counted in a table that comes FIRST**: `height` `u16` byte counts, then that
 *    many bytes per row. This reader read each row's count immediately before that row, which is the
 *    interleaved form and is what a `u32`-counted list looks like when it is not a list at all.
 *  - **A Unicode string is NUL-terminated and the terminator is counted**: every `Nm  ` in the four
 *    files is `"Soft Round\0"`, and ag-psd's own `data.json` says `"Soft Round"`. Trailing NULs are
 *    dropped on the way in; a name that carries one into an id is a folder name with a NUL in it.
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
     * The tip's gray samples, one byte per pixel, decoded here and thrown away by the caller.
     *
     * Not an image: no PNG, no inflate, no colour, no scaling. PackBits RLE is undone because
     * Decision 4's hardness stand-in is `0.25 + 0.7 × meanAlpha` and a mean is one pass over the
     * decoded samples. **One byte out per pixel, whatever the stored depth**: a 16-bit sample
     * contributes its high byte, which is ag-psd's own rule (`alpha[i] = readUint16() >> 8`) and is
     * the only one that makes a mean over a 16-bit tip mean the same thing as over an 8-bit one.
     *
     * For a PackBits tip the byte counts are **a table that comes first** — `height` `u16` counts,
     * then that many bytes per row — because that is how a real `samp` entry is laid out; reading
     * each row's count immediately in front of that row decodes the first row's tail as the second
     * row's header.
     */
    fun grayBytes(tip: AbrTip): ByteArray {
        tip.error?.let { throw BrushException("its sampled tip cannot be read: $it") }
        val bps = AbrReader.bytesPerSample(tip.depth.toLong())
        val width = tip.width
        val height = tip.height
        val storedRow = width * bps
        // `width * height` is already bounded by `MAX_TIP_PIXELS` in tipError, so this fits an Int.
        val out = ByteArray((width * height).toInt())
        val cur = AbrCursor(bytes, tip.dataStart, tip.dataEnd, "sampled tip \"${tip.id}\"")
        var written = 0L
        when (tip.compression) {
            AbrReader.COMPRESSION_RAW ->
                if (bps == 1L) {
                    repeat(height.toInt()) {
                        cur.copyInto(out, written, storedRow)
                        written += storedRow
                    }
                } else {
                    repeat(height.toInt()) {
                        for (i in 0 until width) out[(written + i).toInt()] = cur.u16().toByte()
                        written += width
                    }
                }
            AbrReader.COMPRESSION_PACKBITS -> {
                val counts = AbrReader.rowByteCounts(cur, height)
                for (row in 0 until height) {
                    val declared = counts[row.toInt()]
                    val from = cur.at
                    cur.skip(declared)
                    written += AbrReader.packBits(bytes, from, from + declared.toInt(), out, written, width)
                }
            }
            else -> throw BrushException("sampled tip \"${tip.id}\" uses compression ${tip.compression}")
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

    /** The only sub-versions, and the only two `samp` preambles. ag-psd refuses anything else. */
    private val SUB_VERSIONS = setOf(1, 2)

    private const val SIG_8BIM = "8BIM"

    /**
     * The bytes a `samp` entry carries between its Pascal id and its bounds: 10 for sub-version 1, 264
     * for sub-version 2 (ag-psd `abr.ts:546`). Counted **from the first byte after the id**, which is
     * the only place it can be counted from: it depends on nothing but the sub-version.
     */
    const val PREAMBLE_SUB1 = 10
    const val PREAMBLE_SUB2 = 264

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
        if (subVersion !in SUB_VERSIONS) {
            // ag-psd `abr.ts:527` refuses it, and the two sub-versions are the only two whose `samp`
            // preamble this build knows (10 or 264 bytes). Reading one as the other would put the
            // bounds of every tip 254 bytes off.
            throw BrushException(
                "a .abr of sub-version $subVersion cannot be read; this build reads 1 and 2"
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
                // `patt` and `phry` are both skipped by their own declared length, and that is the whole
                // of what is done with them. `patt` is a list of pattern records and a pattern's pixels
                // are an image Joy Brush cannot name yet (JB-1.05d), so nothing this build wants is in
                // there; `phry` is the preset group hierarchy, which is another descriptor and carries
                // no brush. A declared length is not trusted for anything that is *used*; skipping a
                // payload is the one use of it that cannot invent a number. JB-8.01b: an earlier build
                // parsed `patt` records against a guessed layout, which on the two real files that carry
                // a `patt` payload (`sample-and-pattern`, `special`) read a record length out of the
                // pattern's own pixels and refused the whole `.abr`.
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
     * The `samp` section: one entry per sampled tip, each **carrying its own length**.
     *
     * The layout is ag-psd's `abr.ts:536-582` and it was checked byte for byte against the two real
     * files that have tips (`testdata-local/abr/sample-and-pattern`, 112 × 105, and `…/special`,
     * 23 × 20):
     *
     *     u32 entryLength, rounded up to a multiple of 4      ← the entry ends here
     *     u8 idLength, idLength bytes of Pascal id
     *     10 bytes (sub-version 1) or 264 bytes (sub-version 2) of preamble
     *     i32 top, i32 left, i32 bottom, i32 right            ← **in that order**
     *     i16 depth, u8 compression
     *     pixels: raw `width × height × bytesPerSample` bytes when compression is 0, or
     *             `height` u16 row byte counts and then that many PackBits bytes per row when it is 1
     *     …then padding to `entryLength`
     *
     * The padding is real: both real entries end with 9 and 10 zero bytes behind their last row, and
     * `entryLength` is what says so. Nothing is guessed about what it holds.
     */
    private fun readTips(cur: AbrCursor, into: MutableList<AbrTip>, subVersion: Int) {
        val preamble = if (subVersion == 1) PREAMBLE_SUB1 else PREAMBLE_SUB2
        while (cur.remaining() >= 4) {
            val declared = cur.u32()
            // A zero length is a record that is not there. Anything else would leave the cursor where
            // it is and this loop would never end.
            if (declared == 0L) break
            var length = declared
            while ((length and 3L) != 0L) length++
            if (length > cur.remaining().toLong()) {
                throw BrushException(
                    "sampled tip claims $length bytes, only ${cur.remaining()} are left: the file is truncated"
                )
            }
            val entry = cur.region(cur.at + length.toInt())
            val idLength = entry.u8()
            if (idLength == 0 || idLength > entry.remaining() || idLength > AbrImport.MAX_STRING_BYTES) {
                // The entry after the last one is not there. Everything read so far is kept and every
                // brush that wanted a later tip is refused with "not in this file" — a shorter list
                // is a visible loss, a wrong list is not.
                break
            }
            val id = entry.ascii(idLength)
            entry.skip(preamble.toLong())
            val top = entry.i32().toLong()
            val left = entry.i32().toLong()
            val bottom = entry.i32().toLong()
            val right = entry.i32().toLong()
            val depth = entry.i16().toLong()
            val compression = entry.u8().toLong()
            val dataStart = entry.at
            // The pixel region is bounded by the entry's own length, and its own size is computed from
            // bounds that have been range-checked, so nothing below is an allocation a file chose freely.
            val error = tipError(top, left, bottom, right, depth, compression, entry.remaining())
            val dataEnd = pixelEnd(entry, top, left, bottom, right, depth, compression, error)
            into += AbrTip(id, top, left, bottom, right, depth.toInt(), compression.toInt(), dataStart, dataEnd, error)
            if (dataEnd > entry.end) {
                throw BrushException("sampled tip \"$id\" claims pixels past the end of the section: the file is truncated")
            }
            if (into.size > AbrImport.MAX_LIST_ITEMS) {
                throw AbrCap("a .abr carries at most ${AbrImport.MAX_LIST_ITEMS} sampled tips")
            }
        }
    }

    /**
     * Why this tip's own numbers cannot be used, or null. D10: the subtraction is in Long, and a
     * result that is zero, negative, over [AbrImport.MAX_TIP_DIM] or over `Int.MAX_VALUE` is refused
     * by name rather than becoming a negative width.
     *
     * [remaining] is what the entry has left after `depth` and `compression`, and it is checked only
     * against the cheapest thing the pixels need: the `height` u16 row-count table a PackBits entry
     * starts with. The rest of the walk is [pixelEnd]'s, because it is the only place that knows
     * where each row actually ends.
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
        if (compression == COMPRESSION_PACKBITS.toLong() && bps != 1L) {
            // Whether a 16-bit PackBits row counts its bytes or its samples is not recorded anywhere,
            // and no real file carries one — ag-psd refuses it too (`abr.ts:572`). Decoding it as if
            // it were 8 bit would produce a tip half as wide as the artist's and say nothing.
            return "its pixels are 16 bit and compressed with code 1, which this build does not decode"
        }
        if (compression == COMPRESSION_RAW.toLong()) {
            val stored = width * height * bps
            if (remaining.toLong() < stored) {
                return "it holds $remaining bytes of pixel data for $stored bytes"
            }
        } else {
            // A PackBits tip opens with `height` u16 row byte counts, all of them before any row.
            val table = height * 2L
            if (remaining.toLong() < table) {
                return "it holds $remaining bytes of pixel data for a $table-byte row-count table"
            }
        }
        return null
    }

    /** Where this tip's stored pixels end, or the entry end when the tip is already known bad. */
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
        val start = cur.at
        val total = if (compression == COMPRESSION_PACKBITS.toLong()) {
            // `height` u16 counts, then each row's own bytes. `rowByteCounts` has already bounds-checked
            // every count against what is left, so summing them cannot pass the entry end either.
            var at = start + 2L * height
            for (count in rowByteCounts(cur, height)) at += count
            at - start
        } else {
            width * height * bps
        }
        val end = start + total.toInt()
        if (end > cur.end) {
            throw BrushException("sampled tip claims $total bytes of pixels, the entry has ${cur.end - start}")
        }
        return end
    }

    /**
     * The PackBits row byte counts: `height` `u16` values read from [cur], each checked against what
     * is left behind it. A count the entry cannot honour is a refusal, not a row of zeros — and it is
     * checked here rather than at the point of use so that [pixelEnd] and `grayBytes` cannot disagree
     * about where a row begins.
     */
    fun rowByteCounts(cur: AbrCursor, height: Long): LongArray {
        val counts = LongArray(height.toInt())
        for (row in 0 until height) {
            if (cur.remaining() < 2) {
                throw BrushException("sampled tip row-count table is truncated after ${cur.remaining()} bytes")
            }
            val n = cur.u16().toLong()
            if (n > cur.remaining().toLong()) {
                throw BrushException("sampled tip pixel row ${row + 1} claims $n bytes, the entry ends first")
            }
            counts[row.toInt()] = n
        }
        return counts
    }

    /**
     * Stored bytes per sample, or 0 for a depth this build does not read.
     *
     * **8 and 16 only.** A 1-bit tip stores `ceil(width / 8)` bytes per row, and reading it as one byte
     * per pixel — which is what a `1L` here used to ask for — produces an image eight times too wide
     * that still decodes cleanly. ag-psd refuses it (`abr.ts:577`), and a wrong tip that imports is
     * the one outcome this build exists to prevent, so 1 bit is refused by name in [tipError].
     */
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
     * holds the presets (R4 §B.1).
     *
     * **A count is never believed and a length is never invented.** The `Brsh` list is `count:u32`
     * and then `count` self-describing values — there is no byte length after the count, and the
     * section's own end is the bound (ag-psd `descriptor.ts:488-499`, and the four real files, whose
     * first brush entry begins `Objc` where this reader used to expect a length).
     *
     * The list is read **one entry at a time** rather than through [readValue], so that an entry that
     * will not parse refuses itself and not the file. Entries in a list are self-delimiting only once
     * they have been parsed: there is no way to find where the next one starts, and inventing a place
     * is how a file turns into a different brush. So once one entry has failed, every entry after it
     * is refused with a sentence that says why, and the brushes before it still convert — which is the
     * whole of D1.
     *
     * The one thing a lost cursor does cost is the rest of the **root** descriptor's own items: the
     * section is one descriptor and there is no length to skip past the list with. Nothing is lost
     * that a brush needs — the root's items exist to resolve a `'obj '`/`'prop'` entry in the list,
     * and a brush that needs one is refused with its own sentence — but the fact is written down rather
     * than left for someone to find.
     */
    private fun readBrushes(cur: AbrCursor): List<AbrBrush> {
        val descriptorVersion = cur.u32()
        if (descriptorVersion < 4L) {
            throw BrushException(
                "descriptor version $descriptorVersion cannot be read; this build reads 4 and up"
            )
        }
        val budget = NodeBudget()
        if (1 > AbrImport.MAX_DESCRIPTOR_DEPTH) throw AbrCap("unreachable")
        budget.spend()
        readUnicode(cur)          // the root's own name
        readKey(cur)              // …and its class
        val itemCount = cur.u32()
        if (itemCount > AbrImport.MAX_LIST_ITEMS) {
            throw AbrCap("a descriptor carries at most ${AbrImport.MAX_LIST_ITEMS} items, this one claims $itemCount")
        }
        val items = ArrayList<AbrItem>(if (itemCount < 64L) itemCount.toInt() else 64)
        var brushList: BrushList? = null
        var pending = itemCount.toInt()
        while (pending > 0) {
            pending--
            val key = readKey(cur)
            if (key == "Brsh" && brushList == null) {
                val read = readBrushList(cur, budget)
                brushList = read
                // A list entry that would not parse has left the cursor somewhere meaningless, so the
                // root's own remaining items cannot be found either. Stop here rather than read noise.
                if (read.cursorLost) break
            } else {
                items += AbrItem(key, readValue(cur, budget, 1))
            }
        }
        val list = brushList ?: throw BrushException("the brush descriptor has no \"Brsh\" list")
        return list.toBrushes(rootOf(items))
    }

    /**
     * The root descriptor, for a `'obj '`/`'prop'` entry in the list to resolve against.
     *
     * A reference the root cannot satisfy — which is what a list that lost its cursor looks like —
     * resolves to a root with nothing in it rather than refusing the whole file. The brush that wanted
     * the pointer is then refused with its own sentence, naming the index it asked for, which is the
     * honest answer: one brush lost, not the pack.
     */
    private fun rootOf(items: List<AbrItem>): AbrDescriptor = try {
        resolve(AbrDescriptor("", "", items))
    } catch (e: BrushException) {
        AbrDescriptor("", "", emptyList())
    }

    /**
     * The `Brsh` list's entries, read but not yet resolved: [values] holds one raw value per entry and
     * a `null` from the first entry that would not parse onwards.
     *
     * The list's own type and count are read here rather than through [readValue] so that the count is
     * held against [AbrImport.MAX_BRUSHES] — the brush cap — rather than the much larger generic list
     * cap, and so that the first failure can be recorded and every entry after it refused at once.
     * [cursorLost] says whether the main cursor can still be trusted, which it cannot be once an entry
     * has failed to parse: a list entry's length is only known after it has been read.
     */
    private class BrushList(
        val values: List<AbrValue?>,
        val failureReason: String?,
        val cursorLost: Boolean,
    ) {
        /**
         * The brushes. [root] is the descriptor the list's own `'prop'` pointers resolve against.
         *
         * A `'prop'` entry that does not resolve refuses the brush with a sentence rather than handing
         * the caller a null it would read as "this brush has no tip". From the first failure onwards
         * every entry is refused with the same sentence, which is why the brushes after a failure are
         * not read: there is no length to skip them by.
         */
        fun toBrushes(root: AbrDescriptor): List<AbrBrush> {
            val out = ArrayList<AbrBrush>(values.size)
            var stop = false
            var reason = ""
            for ((index, value) in values.withIndex()) {
                if (stop) {
                    out += AbrBrush.Unreadable("brush ${index + 1} cannot be read: $reason")
                    continue
                }
                if (value == null) {
                    stop = true
                    reason = "${failureReason ?: "it cannot be read"}; " +
                        "the brushes after it cannot be found without reading it"
                    out += AbrBrush.Unreadable("brush ${index + 1} cannot be read: $reason")
                    continue
                }
                val descriptor: AbrDescriptor = try {
                    when (value) {
                        is AbrValue.Desc -> value.descriptor
                        is AbrValue.Ref ->
                            (root.items.getOrNull(value.index)?.value as? AbrValue.Desc)?.descriptor
                                ?: throw BrushException(
                                    "the list's entry ${index + 1} points at item ${value.index + 1} of the " +
                                        "descriptor, which is not a brush"
                                )
                        else -> throw BrushException(
                            "the list's entry ${index + 1} is a ${value.javaClass.simpleName}, not a brush"
                        )
                    }
                } catch (e: BrushException) {
                    stop = true
                    reason = "${e.message ?: "it cannot be read"}; " +
                        "the brushes after it cannot be found without reading it"
                    out += AbrBrush.Unreadable("brush ${index + 1} cannot be read: $reason")
                    continue
                }
                out += AbrBrush.Read(descriptor)
            }
            return out
        }
    }

    private fun readBrushList(cur: AbrCursor, budget: NodeBudget): BrushList {
        val type = cur.ascii(4)
        if (type != "VlLs") {
            throw BrushException("\"Brsh\" is a \"$type\", not a list of brushes")
        }
        val count = cur.u32()
        if (count > AbrImport.MAX_BRUSHES) {
            throw AbrCap("a .abr carries at most ${AbrImport.MAX_BRUSHES} brushes, this one claims $count")
        }
        val values = ArrayList<AbrValue?>(if (count < 64L) count.toInt() else 64)
        var stopped = false
        var reason: String? = null
        for (index in 0 until count.toInt()) {
            if (stopped) {
                values += null
                continue
            }
            values += try {
                readValue(cur, budget, 2)
            } catch (e: BrushException) {
                if (!stopped) {
                    stopped = true
                    reason = e.message
                }
                null
            }
        }
        return BrushList(values, reason, cursorLost = stopped)
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
            // `GlbO` is a "global object": the same descriptor structure under a different tag
            // (ag-psd `descriptor.ts:485-487`). Reading it as a length-prefixed blob — which this build
            // used to do — reads a `u32` length and that many bytes where a name, a class id and a
            // count are, and loses every item behind it in the same list.
            "GlbO" -> AbrValue.Desc(readDescriptor(cur, budget, depth + 1))
            "obj " -> readReference(cur, budget, depth)
            // **A list has no byte length.** It is `count:u32` and then `count` values, each carrying
            // its own four-character type (`ag-psd/descriptor.ts:488-499`). The section's own end is
            // the bound and `AbrCursor` refuses to read past it, so a count larger than the bytes
            // behind it runs out of bytes and says so instead of walking into the next section. The
            // `u32` this build used to read here as a length is the first item's own type tag, which
            // is `0x4f626a63` for `Objc` — about 1.3 GB — so every real list read as a cap or a
            // truncation.
            "VlLs" -> {
                val count = cur.u32()
                if (count > AbrImport.MAX_LIST_ITEMS) {
                    throw AbrCap("a list carries at most ${AbrImport.MAX_LIST_ITEMS} items, this one claims $count")
                }
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
            // `type` and `GlbC` are both a **class structure**: a Unicode name and then a class id
            // (`descriptor.ts:530-532`). Reading one as a bare class id left that name unconsumed, and
            // everything behind it in the same descriptor was read as noise. The two are joined by one
            // space so that the value is one readable string rather than a structure nothing downstream
            // needs.
            "type", "GlbC" -> AbrValue.Text(readUnicode(cur) + " " + readKey(cur))
            "tdta" -> AbrValue.Blob(readBlob(cur))
            // `ObAr` is not a length-prefixed blob: it is a version, a name, a class id, a count and
            // then a list of rational points (`descriptor.ts:541-562`). This build does not read one,
            // and refusing it is the honest answer where reading a blob was a guess that put the cursor
            // out by an unknown amount. None of the four real `.abr` files carries one.
            "ObAr" -> throw BrushException(
                "descriptor type \"ObAr\" is a rational-point array, which this build does not read"
            )
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
     * **Trailing NULs are dropped.** Photoshop writes these strings NUL-terminated and counts the
     * terminator: every `Nm  ` in the four real files is one character longer than the name it holds
     * (`"Soft Round\0"`, `"test2\0"`, `"Bobbys Brush 743\0"`, `"Pencil\0"`), the descriptor's own name
     * is a single NUL, and ag-psd's `data.json` — its own parse of the same bytes — says
     * `"Soft Round"`. So the terminator is part of the encoding and not part of the name, and an id
     * built from a name that still carries it is a folder name with a NUL in it.
     *
     * Only *trailing* NULs go: a NUL in the middle of a name is a character the artist put there, and
     * stopping at the first one would silently shorten a string.
     */
    fun readUnicode(cur: AbrCursor): String {
        // The one character that is encoding rather than text. Spelled out and not written as
        // a literal so that no editor, diff or copy can turn it into an invisible byte.
        val nul = '\u0000'

        val chars = cur.u32()
        if (chars * 2L > AbrImport.MAX_STRING_BYTES) {
            throw AbrCap("a string is at most ${AbrImport.MAX_STRING_BYTES} bytes, this one claims ${chars * 2L}")
        }
        val sb = StringBuilder(chars.toInt())
        repeat(chars.toInt()) { sb.append(cur.u16().toChar()) }
        var end = sb.length
        while (end > 0 && sb[end - 1] == nul) end--
        return if (end == sb.length) sb.toString() else sb.substring(0, end)
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
