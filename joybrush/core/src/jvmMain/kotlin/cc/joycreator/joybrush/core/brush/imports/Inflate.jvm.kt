package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The JVM half of [inflateRaw]: `java.util.zip.Inflater`, whole.
 *
 * This is the *only* file in the project that mentions `java.util.zip` for the Procreate importer,
 * and it exists as a separate source set precisely so that `commonMain` never has to. The alternative
 * ruling rejected was ~350 lines of Huffman bit-twiddling in `commonMain`; see [Inflate.kt] for why.
 *
 * Four checks, and each exists because its absence is a specific failure:
 *  - the range is inside the array, in `Long`, so `offset + length` cannot wrap;
 *  - `n == 0` before `finished()` is a stream that **ends early** — returning what is in the buffer
 *    would be a truncated image that validates clean, which is the whole point of refusing;
 *  - `total > maxOut` is checked *before* the bytes are written, so a bomb allocates at most one
 *    64 KiB chunk past the cap rather than the whole expansion;
 *  - `remaining != 0` is trailing garbage the stream never consumed. Reading past it would be reading
 *    bytes the file did not put there; a zip whose deflate payload has a tail is a hostile zip.
 */
internal actual fun inflateRaw(deflate: ByteArray, offset: Int, length: Int, maxOut: Int): ByteArray {
    if (offset < 0 || length < 0 || offset.toLong() + length > deflate.size) {
        throw BrushException("deflate range $offset..${offset + length} is outside ${deflate.size} bytes")
    }
    if (maxOut < 0) throw BrushException("maxOut $maxOut is negative")
    val inflater = Inflater(true)          // nowrap = true: the caller owns any zlib header
    try {
        inflater.setInput(deflate, offset, length)
        val out = ByteArrayOutputStream(minOf(maxOut, 64 * 1024).coerceAtLeast(64))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (!inflater.finished()) {
            val n = try {
                inflater.inflate(chunk)
            } catch (e: DataFormatException) {
                throw BrushException("deflate stream cannot be read: ${e.message}")
            }
            if (n == 0) {
                // `n == 0` is only a truncated stream if the inflater is **not** finished. An empty
                // DEFLATE stream is two bytes and legally expands to nothing, so the inherited code's
                // bare `if (n == 0) throw` refused a valid zero-byte zip entry — a file this build
                // would then be unable to open. `finished()` is the only thing that tells the two
                // apart, and it is set by the very call that returned zero.
                if (inflater.finished()) break
                throw BrushException("deflate stream ends early after $total bytes")
            }
            total += n
            if (total > maxOut) throw BrushException("deflate stream is over the $maxOut byte limit")
            out.write(chunk, 0, n)
        }
        if (inflater.remaining != 0) {
            throw BrushException("deflate stream has ${inflater.remaining} bytes unread after its end")
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
