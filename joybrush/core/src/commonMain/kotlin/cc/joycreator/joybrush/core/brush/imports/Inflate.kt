package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException

/**
 * Inflate a **raw DEFLATE stream (RFC 1951)** — the payload of one zip entry, with no zlib and no
 * gzip wrapper around it. **A zip's method 8 is raw DEFLATE, not zlib** (PKWARE APPNOTE 4.4.5), which is
 * a correction of an earlier claim in JB-8.02's spec and was found by building a fixture, not by
 * reading. The wrapper is therefore **tolerated, not required**: a caller that sees a zlib header strips
 * both of its ends and hands this function the raw stream. Two callers do this — `ProcreateImport` for a
 * zip entry and `PngChunks` for a `zTXt`, whose PNG specification *does* say zlib — and the probe is
 * `ImportSupport.looksLikeZlib`, one of them.
 *
 * **The wrapper's four-byte Adler-32 trailer is removed by the caller and is NOT verified.** That is the
 * landed `ProcreateImport` decision and this row copies it; see its Questions for the Lead.
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
