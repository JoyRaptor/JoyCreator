package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException

/**
 * Inflate a **raw DEFLATE stream (RFC 1951)** — the payload of one zip entry, with no zlib and no
 * gzip wrapper around it. A `.brush` entry carries the zlib two-byte header; `ProcreateImport` reads
 * and checks that header itself and hands this function the bytes after it (Decision 5).
 *
 * **`expect`/`actual` rather than a decoder in `commonMain`, by ruling.** LEAD_RULINGS R40: "no
 * hand-written Inflate — `expect/actual` with the JVM using `java.util.zip` (and a zlib-backed
 * `actual` on iOS later)". A DEFLATE decoder is a Huffman table walker with three interacting state
 * machines, and a bug in any of them is a silently wrong image rather than an exception, which is
 * the one outcome a brush importer must not have. `java.util.zip.Inflater` is a decade old and
 * fuzzed; the iOS target gets `import Compression` in one function. The whole reason for the
 * `expect` is that the *only* thing written by hand is the signature.
 *
 * @throws BrushException if the range is outside [deflate], if the stream is malformed or ends early,
 *   if the output would pass [maxOut], or if any input byte is left unread after the end of the
 *   stream. Never return a short result: a truncated image is a file that is not what it says.
 */
internal expect fun inflateRaw(deflate: ByteArray, offset: Int, length: Int, maxOut: Int): ByteArray
