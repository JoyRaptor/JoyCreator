package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException

// ---- this file's own budgets ------------------------------------------------------------------------------
//
// **They live here, not in the importer that calls it, and that is deliberate.** This file arrived
// ahead of its caller (KritaImport, JB-8.04) and used that importer's names for four caps, which made
// it a file that cannot compile until a row behind this one lands — the whole `:core` module was red
// because of it. A shared *reader* whose budgets belong to one *importer* is a cycle: the reader
// cannot be reused by JB-8.02 (which needs `readPngHeader` and has no `.kpp`) until JB-8.04 lands.
//
// So the caps are PngChunks' own, named for what they bound, and each carries its derivation. JB-8.04
// re-points them if it wants different numbers; it must not *delete* them, because a PNG walk with no
// cap is a file that can claim a 4 GiB chunk. Numbers are in the right order to reason about: the file
// is at most [MAX_PNG_BYTES], one chunk is at most [MAX_PNG_CHUNK_BYTES], and a string that ends up in
// a `brush.json` is at most [MAX_PNG_STRING_BYTES].

/** The whole file. 64 MiB, the same budget `AbrImport` gives an `.abr`; a different cap, same number. */
internal const val MAX_PNG_BYTES = 64L * 1024 * 1024

/** Chunks in one file. A photograph is five; a text-heavy file is hundreds. 4 096 is past both. */
internal const val MAX_PNG_CHUNKS = 4_096

/**
 * One chunk's declared data length. Half the file cap: a chunk over that is not an image part, it is
 * one blob wearing an `IDAT` label, and the walk would have to hold it in memory to reach the CRC.
 */
internal const val MAX_PNG_CHUNK_BYTES = 32L * 1024 * 1024

/**
 * One `tEXt`/`iTXt` text, in bytes. **8 MiB, and JB-8.04 re-pointed it from 64 Ki (2026-09-29).**
 *
 * The 64 Ki it was born with is `AbrImport`'s budget for a *string that ends up in a `brush.json`*, and
 * it is the right number for that. It is the wrong number for a Krita `.kpp`, and it is wrong by an
 * order of magnitude that decides whether this row works: a `.kpp`'s `preset` chunk is one text chunk
 * that carries **the whole paint-op settings XML**, and a preset with an embedded tip carries that tip
 * as a **base64 string inside the same chunk**. `ImportSupport.MAX_EXTENSION_BYTES` — the cap R40 puts on
 * what a brush may *keep* — is 256 Ki, and base64 is 4 characters per 3 bytes, so the smallest tip that
 * is worth storing at all is ~192 KiB of bytes, ~256 Ki of characters, and the chunk that holds it is
 * that plus the rest of the settings. At 64 KiB this reader would refuse **every `.kpp` with an embedded
 * tip** — precisely the case Decision 4 and the R40 tests exist for — and would refuse it as a malformed
 * PNG rather than as an import it declined.
 *
 * 8 MiB is this spec's Decision 7 `MAX_STRING_BYTES` and is ~32x the R40 cap, so a preset is either
 * readable or genuinely absurd. **Only JB-8.04 is affected:** `MAX_PNG_STRING_BYTES` has exactly two call
 * sites and both are in this file, and nothing in JB-8.02 or JB-8.03 reads a PNG text chunk at all
 * (`ProcreateImport` reads `readPngHeader` only, and a header has no text).
 */
internal const val MAX_PNG_STRING_BYTES = 8 * 1024 * 1024

/**
 * One text chunk out of a PNG. [compressed] is true for `zTXt` and for an `iTXt` that asks for it.
 *
 * [text] is **empty** whenever [compressed] is true, and that is the whole of the contract: this
 * reader never inflates (KritaImport Decision 5), so a reader that silently returned nothing would
 * make a compressed chunk indistinguishable from a file that has no such chunk at all. The caller
 * refuses it *in words* — "this chunk is compressed" — and the person who chose the file sees why.
 */
data class PngTextChunk(val keyword: String, val text: String, val compressed: Boolean)

/**
 * The only two numbers of a PNG's `IHDR` this build reads: the image's own width and height in px.
 *
 * Four big-endian bytes each, at **file** offsets 16..20 and 20..24 — 8 of signature, 4 of chunk
 * length and 4 of chunk type come first, which is why a header shorter than 24 bytes cannot have
 * them. This is a header read at a fixed offset and not a decode: there is no PNG *reader* in
 * `joybrush/` at all, only a writer (`PngWriter`, JB-2.14a), and Decision 6 keeps it that way.
 */
internal data class PngHeader(val width: Int, val height: Int)

/**
 * Every `tEXt` and `iTXt` chunk of a PNG, in file order.
 *
 * **Never decodes pixels and never inflates** (Decisions 5 and 6). A `zTXt` chunk, and an `iTXt` with
 * a non-zero compression flag, are returned with `compressed = true` and an **empty** `text` — the
 * caller refuses them in words, and a reader that silently returned nothing would make that
 * indistinguishable from a file that simply has no such chunk.
 *
 * What is read: the 8-byte signature, the chunk walk by declared length, and the `tEXt`/`zTXt`/
 * `iTXt` payloads. `tEXt` text is **Latin-1** and `iTXt` text is **UTF-8**, which is what the PNG
 * specification says each one is; an `iTXt` whose text is not valid UTF-8 is refused rather than
 * decoded as something it is not, because every guess here is a `preset` XML with a `&` in the wrong
 * place. Unknown chunk types are skipped by their own declared length, which is the one use of a
 * length a file states that cannot invent a number.
 *
 * @throws BrushException on a bad signature, a chunk length that overruns the file, a length that is
 *   negative read as a signed Int, or any budget overrun (Decision 7).
 */
fun readPngTextChunks(bytes: ByteArray): List<PngTextChunk> {
    val out = ArrayList<PngTextChunk>()
    walkPngChunks(bytes) { type, at, length ->
        when (type) {
            "tEXt" -> out += flatChunk(bytes, at, length, "tEXt")
            "zTXt" -> out += compressedChunk(bytes, at, length, "zTXt")
            "iTXt" -> out += internationalChunk(bytes, at, length)
            else -> Unit
        }
        true
    }
    return out
}

/**
 * The `IHDR` of a PNG, or null when the file has none.
 *
 * **This is the function JB-8.02 needs and the reason this file exists in the tree at all**: R40
 * settles `size.base` for a Procreate tip by reading the `Shape.png`'s own width out of its header —
 * "a header read, not a decode" — and this is that read.
 *
 * Null rather than a refusal, because a caller asking for a tip's dimensions has two different
 * questions to ask: "how big is this image" (null: it does not say) and "is this file sound"
 * (KritaImport throws long before it gets here). An `IHDR` that is **present** and unusable — a
 * payload too short to hold a width — is a refusal, because a file that has an `IHDR` and cannot
 * say how wide it is is not a PNG.
 */
internal fun readPngHeader(bytes: ByteArray): PngHeader? {
    var header: PngHeader? = null
    walkPngChunks(bytes) { type, at, length ->
        if (type != "IHDR") return@walkPngChunks true
        // Bytes 16..24 of the file are the width and the height. A header that ends before 24
        // cannot have them, whatever its `IHDR` claims its length is.
        if (length < IHDR_BYTES_NEEDED) {
            throw BrushException(
                "its IHDR is $length bytes, and a PNG needs 24 bytes before the width and the height: " +
                    "8 of signature, 4 of chunk length, 4 of chunk type and 8 of header"
            )
        }
        // `be32` reads a u32 as a `Long` so that 0xFFFFFFFF is 4 294 967 355 rather than −1. The two
        // truncations here are on numbers the walk has already bounded by MAX_PNG_CHUNK_BYTES, so
        // they cannot produce a negative width.
        header = PngHeader(be32(bytes, at).toInt(), be32(bytes, at + 4).toInt())
        false
    }
    return header
}

/** Width and height, four big-endian bytes each: `IHDR_BYTES_NEEDED` is 4 + 4. */
private const val IHDR_BYTES_NEEDED = 8

// ---- the walk ------------------------------------------------------------------------------------

/**
 * The 8-byte signature, exactly: PNG has no version field and no leading junk, so a file that does
 * not start with these eight bytes is not a PNG and saying so is cheaper than looking for a chunk
 * that happens to parse.
 */
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

/** 4 of chunk length + 4 of chunk type + 4 of CRC. The data sits between the type and the CRC. */
private const val CHUNK_HEADER = 12

/**
 * Call [visit] for every chunk of [bytes], in file order, until it returns false.
 *
 * **The length arithmetic is in `Long`, and the cap is checked before any addition** (Decision 8,
 * R19). PNG's chunk length is a `u32`, so a hostile file can set the high bit: read as a signed
 * 32-bit `Int` that is `-1`, and adding it to the position walks the reader backwards into a
 * header it then reads as a length. Here it is assembled as a `Long` (0xFFFFFFFF is 4 294 967 295,
 * a perfectly ordinary positive number), compared against `MAX_CHUNK_BYTES` first, and only then
 * added. The truncation to `Int` happens once, after both checks, on a number already known to be
 * inside this process.
 */
private fun walkPngChunks(bytes: ByteArray, visit: (type: String, dataAt: Int, length: Int) -> Boolean) {
    if (bytes.size > MAX_PNG_BYTES) {
        throw BrushException(
            "a PNG read by this build is at most $MAX_PNG_BYTES bytes, this one is ${bytes.size}"
        )
    }
    if (bytes.size < PNG_SIGNATURE.size) {
        throw BrushException("this PNG is ${bytes.size} bytes, too short to hold the 8-byte signature")
    }
    for (i in PNG_SIGNATURE.indices) {
        if (bytes[i] != PNG_SIGNATURE[i]) {
            throw BrushException("this file does not start with the 8-byte PNG signature")
        }
    }

    var at = PNG_SIGNATURE.size
    var chunks = 0
    while (at < bytes.size) {
        chunks++
        if (chunks > MAX_PNG_CHUNKS) {
            throw BrushException("a PNG carries at most $MAX_PNG_CHUNKS chunks, this one has more")
        }
        if (at + 8 > bytes.size) {
            throw BrushException(
                "chunk $chunks starts at byte $at, where only ${bytes.size - at} bytes are left: " +
                    "a chunk needs 8 for its length and its type"
            )
        }
        val type = ascii4(bytes, at + 4)
        val length = be32(bytes, at)
        if (length > MAX_PNG_CHUNK_BYTES) {
            throw BrushException(
                "chunk $chunks (\"$type\") declares $length bytes, at most $MAX_PNG_CHUNK_BYTES"
            )
        }
        val left = bytes.size - at - CHUNK_HEADER
        if (left < 0 || length > left.toLong()) {
            throw BrushException(
                "chunk $chunks (\"$type\") declares $length bytes, only ${maxOf(left, 0)} are left: " +
                    "the file is truncated"
            )
        }
        val dataAt = at + 8
        val dataLength = length.toInt()
        if (!visit(type, dataAt, dataLength)) return
        at = dataAt + dataLength + 4
    }
}

/** A big-endian `u32` as a `Long`: 0xFFFFFFFF is 4 294 967 295, never −1. */
private fun be32(bytes: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
    return v
}

private fun ascii4(bytes: ByteArray, at: Int): String {
    val sb = StringBuilder(4)
    for (i in 0 until 4) sb.append((bytes[at + i].toInt() and 0xFF).toChar())
    return sb.toString()
}

// ---- the text chunks ------------------------------------------------------------------------------

/** A PNG keyword is 1..79 bytes by the specification; anything else is a chunk that is not one. */
private const val MAX_KEYWORD_BYTES = 79

/**
 * The keyword of a text chunk: the bytes before the first NUL.
 *
 * A chunk with no NUL has no keyword, and a keyword longer than 79 bytes is not a PNG keyword. Both
 * are refusals, not skips: a text chunk is not something to step over, and a chunk whose layout this
 * reader cannot find the end of is exactly the chunk a malformed file uses to hide a length.
 */
private fun keywordOf(bytes: ByteArray, at: Int, length: Int, type: String): Int {
    val n = indexOfZero(bytes, at, length)
        ?: throw BrushException("its \"$type\" chunk has no keyword separator, so it cannot be read")
    if (n == 0) {
        throw BrushException("its \"$type\" chunk has an empty keyword, so it cannot be read")
    }
    if (n > MAX_KEYWORD_BYTES) {
        throw BrushException(
            "its \"$type\" chunk's keyword is $n bytes, at most $MAX_KEYWORD_BYTES in a PNG"
        )
    }
    return n
}

private fun indexOfZero(bytes: ByteArray, at: Int, length: Int): Int? {
    for (i in 0 until length) if (bytes[at + i].toInt() == 0) return i
    return null
}

private fun textStringOf(bytes: ByteArray, at: Int, length: Int, type: String): String {
    if (length > MAX_PNG_STRING_BYTES) {
        throw BrushException(
            "a PNG \"$type\" chunk is at most $MAX_PNG_STRING_BYTES bytes, this one is $length"
        )
    }
    return latin1(bytes, at, length)
}

/** `tEXt`: keyword, NUL, text in **Latin-1** — the PNG specification's own encoding for `tEXt`. */
private fun flatChunk(bytes: ByteArray, at: Int, length: Int, type: String): PngTextChunk {
    val keyword = keywordOf(bytes, at, length, type)
    return PngTextChunk(
        latin1(bytes, at, keyword),
        textStringOf(bytes, at + keyword + 1, length - keyword - 1, type),
        false,
    )
}

/**
 * `zTXt`: keyword, NUL, a compression-method byte, then a DEFLATE stream this build does not have.
 *
 * The keyword is read — the person needs to know *which* chunk is compressed — and the payload is
 * not touched. One byte of the method is read so that a chunk which is not even shaped like a `zTXt`
 * is refused rather than treated as one; no inflater is called and no budget is invented here
 * (Decision 5).
 */
private fun compressedChunk(bytes: ByteArray, at: Int, length: Int, type: String): PngTextChunk {
    val keyword = keywordOf(bytes, at, length, type)
    if (length - keyword - 1 < 1) {
        throw BrushException("its \"$type\" chunk is truncated before its compression method")
    }
    return PngTextChunk(latin1(bytes, at, keyword), "", true)
}

/**
 * `iTXt`: keyword, NUL, a compression flag, a compression method, a language tag, a NUL, a
 * translated keyword, a NUL, then the text in **UTF-8**.
 *
 * A non-zero compression flag is [compressed] with no text, for the same reason `zTXt` is: this
 * build has no inflater (Decision 5). The two NUL-terminated fields in front of the text are read
 * with a bound each, because a file can leave them unterminated and the text is then the whole
 * remainder.
 */
private fun internationalChunk(bytes: ByteArray, at: Int, length: Int): PngTextChunk {
    val keyword = keywordOf(bytes, at, length, "iTXt")
    val flag = at + keyword + 1
    if (flag + 2 > at + length) {
        throw BrushException("its \"iTXt\" chunk is truncated before its compression flags")
    }
    if (bytes[flag].toInt() and 0xFF != 0) {
        return PngTextChunk(latin1(bytes, at, keyword), "", true)
    }
    var cursor = flag + 2
    val language = indexOfZero(bytes, cursor, at + length - cursor) ?: -1
    if (language < 0) throw BrushException("its \"iTXt\" chunk's language tag is not terminated")
    cursor += language + 1
    val translated = indexOfZero(bytes, cursor, at + length - cursor) ?: -1
    if (translated < 0) throw BrushException("its \"iTXt\" chunk's translated keyword is not terminated")
    cursor += translated + 1
    val textLength = at + length - cursor
    if (textLength > MAX_PNG_STRING_BYTES) {
        throw BrushException(
            "a PNG \"iTXt\" chunk is at most $MAX_PNG_STRING_BYTES bytes, this one is $textLength"
        )
    }
    return PngTextChunk(latin1(bytes, at, keyword), decodeUtf8Strict(bytes, cursor, textLength, "iTXt"), false)
}

/** Latin-1: the byte *is* the code point. One byte becomes one char, which is the PNG rule. */
private fun latin1(bytes: ByteArray, at: Int, length: Int): String {
    val sb = StringBuilder(length)
    for (i in 0 until length) sb.append((bytes[at + i].toInt() and 0xFF).toChar())
    return sb.toString()
}

/**
 * UTF-8, strictly, and **shared**.
 *
 * `iTXt` is UTF-8 by the PNG specification and a plist's XML is UTF-8 by its own, and Latin-1 reading
 * of either is a `preset` or an archive with a byte-shifted `&` in it — so the encoding is told
 * apart by the format rather than guessed at, and a sequence that is not valid UTF-8 is refused in
 * words instead of decoded into something it is not. Every code point is checked for the two things
 * a naive decoder drops: an overlong encoding and a surrogate.
 *
 * **One decoder, two call sites** (this file's `iTXt` and `KeyedArchive`'s XML plist), because three
 * hand-written UTF-8 decoders in one package is three chances to disagree about what a valid
 * sequence is. [what] is the caller's own noun for the thing being read, so the message says
 * "chunk" in one place and "archive" in the other rather than saying both everywhere.
 */
internal fun decodeUtf8Strict(bytes: ByteArray, at: Int, length: Int, what: String): String {
    val sb = StringBuilder(length)
    var i = 0
    fun bad(at0: Int): Nothing = throw BrushException(
        "its $what is not valid UTF-8: byte $at0 of the text is 0x${(bytes[at + at0].toInt() and 0xFF).toString(16)}"
    )
    while (i < length) {
        val b0 = bytes[at + i].toInt() and 0xFF
        if (b0 < 0x80) {
            sb.append(b0.toChar())
            i++
            continue
        }
        val follow: Int
        var code: Int
        when {
            b0 and 0xE0 == 0xC0 -> { follow = 1; code = b0 and 0x1F }
            b0 and 0xF0 == 0xE0 -> { follow = 2; code = b0 and 0x0F }
            b0 and 0xF8 == 0xF0 -> { follow = 3; code = b0 and 0x07 }
            else -> bad(i)
        }
        if (i + follow >= length) bad(i)
        for (k in 1..follow) {
            val c = bytes[at + i + k].toInt() and 0xFF
            if (c and 0xC0 != 0x80) bad(i + k)
            code = (code shl 6) or (c and 0x3F)
        }
        // A code point that could have been written in fewer bytes is an overlong encoding, and a
        // surrogate is not a character: both are how a malformed file smuggles a different string
        // past a validator that only counts bytes.
        if (code < 0x80 || code in 0xD800..0xDFFF || code > 0x10FFFF) bad(i)
        sb.append(code.toChar())
        i += follow + 1
    }
    return sb.toString()
}
