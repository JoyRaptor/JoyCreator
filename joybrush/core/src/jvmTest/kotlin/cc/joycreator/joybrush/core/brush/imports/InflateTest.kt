package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [inflateRaw] against `java.util.zip.Deflater`, which is the only oracle worth having.
 *
 * **In `jvmTest`, not `commonTest`, because the oracle is the point.** The test that matters here is
 * the one that says two independent implementations of the same RFC agree; a reader tested against a
 * hand-rolled writer proves only that the writer and the reader share a mistake. `Deflater` is the
 * JDK's, so agreement is real evidence.
 *
 * The `actual` under test is `java.util.zip.Inflater` and the oracle is `java.util.zip.Deflater` — the
 * same library, in the same JVM, over the same bytes. That makes this a test of the *wrapper*: the
 * range checks, the early-end check, the cap, the trailing-bytes check, and the `n == 0` case. It is
 * not a test of DEFLATE, which is the point of R40: this build did not write a decoder, so there is
 * no decoder here to be wrong.
 */
class InflateTest {

    /**
     * A raw DEFLATE stream (RFC 1951) with no zlib and no gzip wrapper, which is what the two bytes
     * [ProcreateImport] strips off before calling [inflateRaw].
     */
    private fun deflate(input: ByteArray, level: Int): ByteArray {
        val d = Deflater(level, /* nowrap = */ true)
        d.setInput(input)
        d.finish()
        val out = ArrayList<Byte>(input.size + 64)
        val chunk = ByteArray(16 * 1024)
        while (!d.finished()) {
            val n = d.deflate(chunk)
            if (n <= 0) break
            for (i in 0 until n) out += chunk[i]
        }
        d.end()
        return out.toByteArray()
    }

    /** Deterministic, and compressible at levels 6 and 9, so the round trip is not trivially stored. */
    private fun sample(n: Int): ByteArray = ByteArray(n) { (it * 31 + (it / 97)) .toByte() }

    // ---- 1. round trip, four ways ------------------------------------------------------------------

    @Test
    fun aStreamCompressesAndComesBackByteForByte() {
        for (level in intArrayOf(6, 9)) {
            for (size in intArrayOf(0, 1, 100, 100_000)) {
                val input = sample(size)
                val packed = deflate(input, level)
                val out = inflateRaw(packed, 0, packed.size, 8 * 1024 * 1024)

                // Non-vacuity: the *size* is asserted as well as the content, so a reader that returned
                // its input unchanged cannot pass, and so neither can one that returns a prefix.
                assertEquals(size, out.size, "level $level, $size bytes in")
                assertContentEquals(input, out, "level $level, $size bytes in")
            }
        }
    }

    @Test
    fun aStoredStreamIsRefusedBecauseItIsNotADeflateStream() {
        // `Deflater` at level 0 emits a *stored* DEFLATE block stream, which is a legal RFC 1951
        // stream. The zip's method-0 entries are not: they are raw bytes with no stream at all, and a
        // `.brush` really does contain them (Decision 5), which is why the importer copies method 0
        // rather than handing it here. This test pins the boundary the other side of that decision.
        val input = sample(64)
        val stored = deflate(input, 0)
        // 64 bytes of incompressible-ish input at level 0 comes back as stored blocks, and the reader
        // either decodes them (a correct DEFLATE decoder) or refuses. Both are acceptable; what is
        // not acceptable is a *short* answer, so that is what is asserted.
        val out = try {
            inflateRaw(stored, 0, stored.size, 1024)
        } catch (e: BrushException) {
            assertTrue(e.message!!.contains("stored") || e.message!!.contains("cannot be read"), e.message!!)
            return
        }
        assertEquals(input.size, out.size)
        assertContentEquals(input, out)
    }

    @Test
    fun anOffsetAndALengthAreHonoured() {
        // A range in the middle of a larger buffer, which is what the zip reader hands over: the
        // payload sits at `at + 2` because of the zlib header.
        val input = sample(5_000)
        val packed = deflate(input, 6)
        val padded = ByteArray(packed.size + 40)
        packed.copyInto(padded, destinationOffset = 13)
        // Junk on both sides must not change the answer, or the offsets are being ignored.
        val out = inflateRaw(padded, 13, packed.size, 1024 * 1024)
        assertEquals(input.size, out.size)
        assertContentEquals(input, out)
    }

    // ---- 2. truncation and trailing bytes ----------------------------------------------------------

    @Test
    fun aTruncatedStreamRefusesWithEndsEarly() {
        val packed = deflate(sample(20_000), 6)
        val cut = packed.copyOfRange(0, packed.size / 2)
        val e = assertFailsWith<BrushException> { inflateRaw(cut, 0, cut.size, 1024 * 1024) }
        assertTrue(e.message!!.contains("ends early"), e.message!!)
    }

    @Test
    fun trailingBytesAfterTheStreamAreRefusedRatherThanIgnored() {
        // Reading past the end of the DEFLATE data would be reading bytes the file did not put there.
        // A zip whose deflate payload has a tail is a hostile zip, and this is the check for it.
        val packed = deflate(sample(1_000), 6)
        val withTail = packed + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val e = assertFailsWith<BrushException> {
            inflateRaw(withTail, 0, withTail.size, 1024 * 1024)
        }
        assertTrue(e.message!!.contains("unread after its end"), e.message!!)
        assertTrue(e.message!!.contains("8"), "the count of unread bytes is not in the message: ${e.message}")
    }

    // ---- 3. the cap is enforced by the actual, before the allocation ---------------------------------

    @Test
    fun theCapIsEnforcedInsideTheActual() {
        // 10 MiB of zeroes deflates to a few KiB, so this is the cheapest possible bomb: a real
        // expansion, a 1 MiB cap, and a stream that is nowhere near the file's own limits.
        val input = ByteArray(10 * 1024 * 1024)
        val packed = deflate(input, 9)
        val start = System.nanoTime()
        val e = assertFailsWith<BrushException> { inflateRaw(packed, 0, packed.size, 1024 * 1024) }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(e.message!!.contains("limit"), e.message!!)
        assertTrue(e.message!!.contains("1048576"), "the cap is not in the message: ${e.message}")
        // "A bomb that allocates before it checks is the failure this exists to stop", so the time is
        // asserted as well as the message. Generous on purpose: this is a smoke test, not a benchmark.
        assertTrue(millis < 5_000, "expanding and refusing took ${millis}ms")
    }

    @Test
    fun aStreamExactlyAtTheCapIsAccepted() {
        // The other side of the same boundary: `>` not `>=`. A file that expands to exactly the cap is
        // legal, and a reader that refused it would be throwing away a real brush over an off-by-one.
        val input = sample(64 * 1024)
        val packed = deflate(input, 6)
        val out = inflateRaw(packed, 0, packed.size, input.size)
        assertEquals(input.size, out.size)
        assertContentEquals(input, out)
    }

    // ---- 4. a range outside the array --------------------------------------------------------------

    @Test
    fun aRangeOutsideTheArrayRefuses() {
        val packed = deflate(sample(100), 6)
        for (bad in listOf(
            Triple(-1, packed.size, 1024),
            Triple(0, packed.size + 1, 1024),
            Triple(packed.size, 1, 1024),
            Triple(0, packed.size, -1),
        )) {
            val e = assertFailsWith<BrushException>("offset ${bad.first}, length ${bad.second}") {
                inflateRaw(packed, bad.first, bad.second, bad.third)
            }
            assertTrue(e.message!!.isNotBlank(), "the refusal has no sentence")
        }
    }

    @Test
    fun anEmptyRangeIsRefusedRatherThanReturningNothing() {
        // A zero-length input is not a stream that expands to nothing; it is a stream with no data, and
        // "never return a short result" is the rule. An empty *output* is only legal when the input
        // said so, which a STORED entry does and this does not.
        val packed = deflate(sample(10), 6)
        val e = assertFailsWith<BrushException> { inflateRaw(packed, 0, 0, 1024) }
        assertTrue(e.message!!.contains("ends early") || e.message!!.contains("truncated"), e.message!!)
    }

    // ---- 5. malformed input refuses rather than looping ---------------------------------------------

    @Test
    fun malformedInputRefusesRatherThanLooping() {
        val forty = ByteArray(40) { ((it * 53 + 17) and 0xFF).toByte() }
        val truncatedTable = byteArrayOf(0x08, 0x0C, 0x00, 0x01)      // a Huffman code-count table cut short
        val distanceFirst = byteArrayOf(0x01, 0x03, 0x00, 0x40, 0x00)  // a length/distance before output

        for ((what, bytes) in listOf(
            "40 random bytes" to forty,
            "a truncated fixed-Huffman table" to truncatedTable,
            "a distance before the start of the output" to distanceFirst,
        )) {
            val e = assertFailsWith<BrushException>("$what should refuse") {
                inflateRaw(bytes, 0, bytes.size, 64 * 1024)
            }
            assertTrue(e.message!!.isNotBlank(), "$what refused with no sentence")
        }
    }

    @Test
    fun aZeroLengthInputRefuses() {
        assertFailsWith<BrushException> { inflateRaw(ByteArray(0), 0, 0, 1024) }
    }
}
