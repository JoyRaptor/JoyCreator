package cc.joycreator.joybrush.core.brush.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared DEFLATE budget JB-8.04b promoted out of `ProcreateImport` into this file, and the one
 * `maxOut` formula and the one zlib probe that go with them.
 *
 * **No `java.*` here**, for the same reason `KritaImportTest` has none: this is `commonTest`, and a probe
 * written by the same library it checks proves only that the two agree. Nothing in this file builds a
 * stream; it checks the *arithmetic* two importers now share, and the arithmetic is where a security bound
 * can quietly change.
 *
 * **Why these four cases and not "the tests pass".** The two constants are a **move** (Decision 1), and a
 * move has no behaviour to test — so what can go wrong is a value, and case 1 is deliberately the only
 * place in this row that retypes one. Cases 2 and 3 pin the two facts a later row could break without
 * anyone noticing: the compressed-chunk path cannot be loosened past the uncompressed one, and a computed
 * budget cannot wrap. Case 4 is the probe both zip readers and the `zTXt` reader use, and its false
 * positive is the interesting direction, so every expected value carries its arithmetic below.
 */
class ImportSupportTest {

    /**
     * **The one test in this row that retypes a number on purpose**, and its whole job is to catch a value
     * that changed during the move.
     *
     * **The derivations, because R9 says an expected value changes only with its derivation written down.**
     *  - `MAX_INFLATED_BYTES = 64L * 1024 * 1024 = 64 × 1 048 576 = 67 108 864`. Nothing chose that
     *    number here; `ProcreateImport` chose it and this row only relocated the declaration.
     *  - `MAX_INFLATE_RATIO = 200:1`, **derived and not remembered**: DEFLATE reaches about 1 038:1 on a
     *    run of zeroes, so 200:1 is far above every real brush bitmap (the worst real case is a large,
     *    very flat PNG tip) and far below what a bomb needs.
     *
     * **Non-vacuity:** the assertion is on the *value*, not on the identifier, so a copy that kept the old
     * declaration would pass here — which is exactly why `ProcreateImportTest` has to move too, and a
     * leftover `ProcreateImport.MAX_INFLATE_RATIO` is a compile error rather than a silent second number.
     */
    @Test
    fun theInflateBudgetsAreTheOnesThatMoved() {
        assertEquals(200L, MAX_INFLATE_RATIO)
        assertEquals(67_108_864L, MAX_INFLATED_BYTES)
        // The ceiling restated as MiB and as a whole number of bytes, so a wrong *multiple* of the same
        // shape cannot hide behind `67_108_864`.
        assertEquals(64L, MAX_INFLATED_BYTES / (1024 * 1024), "64 MiB")
        assertEquals(0L, MAX_INFLATED_BYTES % 1024, "a whole number of bytes, so no MiB is lost to a typo")
    }

    /**
     * **The ceiling is not what bounds the compressed-chunk path — this file's own string cap is.** A `zTXt`
     * states no uncompressed size, so the ratio budget has nothing to divide by (a fact about the PNG
     * format), and the only bound is the output ceiling [readPngCompressedText] asks for.
     *
     * Both halves matter. `MAX_PNG_STRING_BYTES < MAX_INFLATED_BYTES` is `8 388 608 < 67 108 864`, and it is
     * the property that stops a later row from raising the string cap past the shared ceiling or lowering
     * the ceiling under it — either of which would quietly make the `zTXt` path looser than the
     * uncompressed text it sits beside. The second assertion is what actually pins the *behaviour*:
     * asking for the string cap hands back exactly the string cap.
     */
    @Test
    fun aCompressedTextChunkCanNeverAskForMoreThanAnUncompressedOneMayBe() {
        assertEquals(8 * 1024 * 1024, MAX_PNG_STRING_BYTES, "8 MiB")
        assertTrue(
            MAX_PNG_STRING_BYTES < MAX_INFLATED_BYTES,
            "the string cap must be the tighter of the two: ${MAX_PNG_STRING_BYTES} < ${MAX_INFLATED_BYTES}",
        )
        // `inflateMaxOut` takes a `Long` (Kotlin does not widen), hence the `.toLong()`; the answer is `Int`.
        assertEquals(MAX_PNG_STRING_BYTES, inflateMaxOut(MAX_PNG_STRING_BYTES.toLong()))
    }

    /**
     * The formula's three interesting inputs, and the third is the one a file can reach.
     *
     * **`inflateMaxOut(Long.MAX_VALUE)` is the arithmetic a hostile central directory cannot state but a
     * *computed* budget can produce.** `Long.MAX_VALUE.toInt()` is `-1`, and a `maxOut` of `-1` makes the
     * `actual` throw "maxOut -1 is negative" — a refusal that reads like a bug rather than like a bomb.
     * The assertion is on the **clamped value**, not on the absence of an exception, because "it threw"
     * would be satisfied by the bug this exists to catch.
     */
    @Test
    fun theMaxOutFormulaNeverAsksForMoreThanTheCeilingAndNeverGoesNegative() {
        assertEquals(0, inflateMaxOut(0L))
        assertEquals(MAX_INFLATED_BYTES.toInt(), inflateMaxOut(MAX_INFLATED_BYTES))
        assertEquals(67_108_864, inflateMaxOut(Long.MAX_VALUE), "clamped to 64 MiB, and positive")
        // Just past the ceiling, and just below it: `>` and not `>=`, so a file that expands to exactly the
        // ceiling is legal.
        assertEquals(MAX_INFLATED_BYTES.toInt(), inflateMaxOut(MAX_INFLATED_BYTES + 1))
        assertEquals(MAX_INFLATED_BYTES.toInt() - 1, inflateMaxOut(MAX_INFLATED_BYTES - 1))
        // A negative budget is a mistake in a *caller*, and it is floored rather than propagated.
        assertEquals(0, inflateMaxOut(-1L))
    }

    /**
     * The probe both zip readers and [readPngCompressedText] use, with every expected value's arithmetic in
     * the line that asserts it.
     *
     * **A false positive costs a DEFLATE stream and a false negative costs a real brush**, so both
     * directions are pinned. The rate that matters is `1 in 16` for `CMF`'s low nibble times `1 in 31` for
     * the multiple of 31, i.e. about **1 in 496** — which is why a real entry occasionally trips it, and
     * why `KritaBundleInflateTest` makes that happen on purpose rather than hoping it does not.
     *
     * **`toByte()` on every literal above 0x7F, and that is not decoration.** `byteArrayOf` takes
     * `vararg Byte: Byte` and **Kotlin does not narrow a hex literal for you**: `0x9C` is the `Int` 156 and
     * does not fit. The reader inside [looksLikeZlib] masks with `and 0xFF` anyway, so what matters is that
     * the *fixture* stores the byte `0x9C` and not the `Int` 156.
     */
    @Test
    fun aZlibHeaderIsRecognisedAndOtherThingsAreNot() {
        // `0x78 & 0x0F == 8`, and `0x78 * 256 + 0x9C = 30 876 = 31 × 996`, so the pair is accepted.
        assertTrue(looksLikeZlib(byteArrayOf(0x78, 0x9C.toByte()), 0), "the canonical zlib header")
        // 30 877 = 31 × 996 + 1: one higher and the pair is not a multiple of 31, so it is not zlib.
        assertFalse(looksLikeZlib(byteArrayOf(0x78, 0x9D.toByte()), 0), "one byte higher breaks 31")
        // A one-byte array cannot hold a two-byte header: `at + 2 > data.size`, so the answer is false
        // rather than an index exception.
        assertFalse(looksLikeZlib(byteArrayOf(0x78), 0), "there is no second byte to read")
        assertFalse(looksLikeZlib(ByteArray(0), 0))
        // A stored DEFLATE block's first byte is `0x01` (BFINAL = 1, BTYPE = 00): `0x01 & 0x0F == 1`, which
        // is not 8, so the ordinary case is not mistaken for a wrapper.
        assertFalse(looksLikeZlib(byteArrayOf(0x01, 0x05, 0x00, 0xFA.toByte(), 0xFF.toByte()), 0))
        // `at` is honoured, so a probe at a payload offset sees that offset's two bytes and no other.
        assertTrue(looksLikeZlib(byteArrayOf(0x78, 0x9C.toByte(), 0x9D.toByte()), 0), "the header at offset 0")
        assertFalse(
            looksLikeZlib(byteArrayOf(0x78, 0x9C.toByte(), 0x9D.toByte()), 1),
            "at offset 1 the pair is 0x9C, 0x9D",
        )
        assertTrue(looksLikeZlib(byteArrayOf(0x9D.toByte(), 0x78, 0x9C.toByte()), 1), "the header at offset 1")
        // `CINFO` is the high nibble and the test does not look at it: `0x48 & 0x0F == 8` and
        // `0x48 * 256 + 0x0D = 18 445 = 31 × 595`, so a 4 KiB-window `CMF` is accepted as readily as 0x78.
        assertTrue(looksLikeZlib(byteArrayOf(0x48, 0x0D), 0), "CMF 0x48 is also deflate")
    }
}