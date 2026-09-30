package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushValidate
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JB-8.04b, from the JDK's side: a `.bundle` and a compressed `preset` chunk written by **`java.util.zip`**,
 * which is the only oracle worth having and the only fixture `commonTest` is not allowed to build.
 *
 * **Why `commonTest` cannot do this and this file must.** `KritaImportTest` writes its zips entry by entry,
 * because a hand-rolled writer is the honest way to test a reader and `java.util.zip` may not appear in
 * `commonTest`. But a hand-rolled writer cannot produce a **Huffman-coded** stream — `bundleOf` writes a
 * stored DEFLATE block (`BFINAL = 1`, `BTYPE = 00`), which exercises every offset and every refusal and
 * **none of the entropy coder**. So the plumbing is proved there and the *stream* is proved here: a real
 * `Deflater`, a real `ZipOutputStream`, and a real zlib-wrapped `zTXt`, read back through the same
 * `inflateRaw` the zip readers use.
 *
 * **Two implementations, one JVM:** the JDK wrote these bytes and the JDK read them back. That proves the
 * *container and the stream* agree with this importer's reader; it does **not** prove that Joy Brush agrees
 * with Krita, and no real `.bundle` has ever been read in this project (R44 item 4). The last test here is a
 * grep over the two source files rather than a green tick, and it is the only assertion in this row that
 * catches prose that contradicts the code.
 */
class KritaBundleInflateTest {

    // ---- 22. the bundle the JDK itself wrote ------------------------------------------------------------

    /**
     * The test that closes this row: a `.bundle` written by **`java.util.zip.ZipOutputStream`** at
     * [Deflater.BEST_COMPRESSION], so every method-8 entry carries a real Huffman-coded stream and the sizes
     * in the central directory are the ones the JDK computed.
     *
     * Asserted: all three brushes, **in entry-name order**, by name; every one clean under
     * [BrushValidate.validate]; and the licence and author **from the compressed `meta.xml`** and never
     * `"CC0"` — which is the whole of Decision 14 and R8's attribution obligation, now tested on an entry
     * whose bytes had to be inflated to be read at all. Before this row a deflated `meta.xml` produced
     * `license = "unknown"` **with no warning**, because `readBundleMeta` swallows its own failure.
     *
     * **The retry is a fixture guard, and it is not a retry of the reader.** `looksLikeZlib` is a *test*, not a
     * requirement, and it has a documented false-positive rate of about 1 in 496 — so a payload that happens
     * to begin with a byte pair it accepts gets four bytes stripped off a stream that has no wrapper, and
     * this fixture would refuse an entry for a reason that has nothing to do with what it is testing. The
     * loop changes the *fixture's bytes* (a salt in every brush's name) until no entry payload starts with
     * such a pair; the writer is untouched and stays the JDK's. [payloadOffsets] is where the offsets come
     * from, and it walks the central directory rather than guessing.
     */
    @Test
    fun aBundleTheJdkItselfWroteConvertsWholeAndItsMetaXmlStillSuppliesTheLicence() {
        val zip = bundleWithoutAFalsePositiveWrapper(::jdkBundle)
        val library = KritaImport.convertBundle(zip, "probe")
        assertEquals(0, library.refused.size, "${library.refused.map { it.name to it.reason }}")
        // Entry-name order: "brushes/ink default.kpp" < "brushes/ink splatter.kpp" <
        // "paintoppresets/flat blitter.kpp". The count is 3, not "more than zero".
        assertEquals(
            listOf("Ink Default", "Ink Splatter", "Flat Blitter"),
            library.brushes.map { it.preset.name },
        )
        for (result in library.brushes) {
            val p = result.preset
            assertEquals("stamp", p.engine, p.name)
            assertEquals("CC-BY 4.0", p.license, "${p.name}: a compressed meta.xml still supplies it")
            assertEquals("David Revoy", p.author, p.name)
            assertEquals(brushId("probe", p.name), p.id, p.name)
            assertEquals(emptyList(), BrushValidate.validate(p), "${p.name}: ${result.warnings}")
        }
        assertTrue(library.summary().isNotBlank())
    }

    /**
     * A real Huffman-coded **pattern**, read back and stored byte for byte.
     *
     * The stored-block fixtures in `KritaImportTest` prove the offsets and the refusals; only a stream with
     * a Huffman table in it proves the *stream*, because a stored block contains no codes at all.
     */
    @Test
    fun aRealHuffmanDeflatedPatternBecomesTheGrainByteForByte() {
        val pattern = patternPng(48, 24)
        val zip = bundleWithoutAFalsePositiveWrapper { salt -> jdkGrainBundle(pattern, salt) }
        val library = KritaImport.convertBundle(zip, "probe")
        assertEquals(0, library.refused.size, "${library.refused.map { it.name to it.reason }}")
        val p = library.brushes.single().preset
        assertEquals("image", p.paperGrain.source, "a real Huffman entry is an image grain, not a name")
        assertEquals("grain.png", p.paperGrain.image)
        assertTrue(p.paperGrain.enabled)
        assertContentEquals(
            pattern,
            decodeBase64(assertNotNull(p.extensions[KritaImport.GRAIN_IMAGE_KEY])),
            "the inflated pattern's bytes come back exactly",
        )
        assertTrue(
            library.brushes.single().warnings.any { it.contains(TEXTURE_NOT_DRAWN) },
            "${library.brushes.single().warnings}",
        )
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    // ---- 24. a real zlib-wrapped zTXt -------------------------------------------------------------------

    /**
     * A `zTXt` `preset` compressed by **`DeflaterOutputStream`**, i.e. a real **zlib** stream (RFC 1950: the
     * two-byte header *and* the four-byte Adler-32) — which is what the PNG specification says a `zTXt`
     * holds, and what the wrapper tolerance in the zip reader exists for.
     *
     * **This is a different bug from the zip one and a builder will not think of it from the zip case:** a
     * `zTXt` path that forgot the wrapper entirely would read two bytes of header and four bytes of trailer
     * as DEFLATE, and fail on a chunk that is perfectly correct. Every real `zTXt` this spec has ever seen
     * described is wrapped.
     *
     * **The accent is in two places on purpose.** The `Title` `tEXt` chunk is **Latin-1** by the PNG
     * specification, and the `paintop`'s own `name` sits **inside the inflated text**, so this asserts that
     * Latin-1 survives the whole path — signature, chunk walk, inflate, decode, XML parse, name. Written in
     * ASCII it would pass against a reader that decoded every text chunk as UTF-8.
     */
    @Test
    fun aRealCompressedPresetTextChunkInflatesAndALatin1NameSurvivesIt() {
        val xml = """<paintop id="Pixel" name="Café">$AUTO_MASK<spacing mode="space" value="0.3"/></paintop>"""
        val bytes = png(
            ihdr(8, 8),
            textChunk("version", "0.5"),
            // keyword, NUL, compression method 0, then a **real zlib stream**. The payload is written as
            // Latin-1 bytes because that is what a `zTXt` holds — `zlib` would otherwise compress UTF-8's
            // two-byte `é`, and the Latin-1 decode would hand the parser `CafÃ©`.
            zTxtChunk("preset", zlib(latin1(xml))),
            // `tEXt` is Latin-1 too, so the name's `é` is the single byte 0xE9.
            textChunkBytes("Title", latin1("Café Pin")),
            chunkBytes("IEND", ByteArray(0)),
        )
        val library = KritaImport.convertKpp(bytes, "kpp")
        assertEquals(0, library.refused.size, "${library.refused}")
        val p = library.brushes.single().preset
        assertEquals("Café Pin", p.name, "the Latin-1 name is byte-exact, not decoded as UTF-8")
        assertEquals("Café", p.extensions["krita.paintop.name"], "and so is the accent inside the inflated text")
        assertEquals("stamp", p.engine)
        assertEquals(emptyList(), BrushValidate.validate(p), "${library.brushes.single().warnings}")
    }

    // ---- 25. the wrapper probe's false positive, made real ---------------------------------------------

    /**
     * [looksLikeZlib] is documented as "a test, not a requirement", with a false-positive rate of about 1 in
     * 496 — and a **true** negative on a real zlib-wrapped entry is the other direction. This makes the false
     * positive real rather than plausible: it searches a genuine `Deflater(6, /* nowrap = */ true)` stream
     * for the first offset whose two bytes the probe accepts, and then feeds the stream **shifted to that
     * offset** as a method-8 entry, so the reader strips four bytes off a stream that never had a wrapper.
     *
     * **The assertion is the one the reader's own KDoc promises:** either the correct bytes, or a
     * `BrushException` with a sentence — **and explicitly not a short array**. "Not a short array" is not
     * directly observable through `convertBundle`, so it is asserted as what *is* observable: **exactly one
     * outcome, and the refusal names `deflate`.** A reader that inflated the shifted stream into a
     * truncated `.kpp` and handed it on would fail one step later, on the PNG signature — no arithmetic
     * anywhere in the library, which is precisely the "returns something short" shape. So the assertion is
     * positive ("the inflater said so") *and* negative ("the PNG reader never got a look in"), and neither
     * half alone would do.
     *
     * The pair is **searched for, not written down**, over genuine `Deflater` output: see
     * [aFalsePositiveWrapperOrFail] for why the search widens the fixture instead of truncating, and for
     * what a "none found" result means. And the payload is stored **byte for byte** by
     * [bundleOfOneDeflatedEntry], because `ZipOutputStream` would re-deflate it and the whole fixture would
     * evaporate.
     */
    @Test
    fun aFalseZlibHeaderOnRealDeflateDataIsARefusalAndNotAShortRead() {
        val (stream, at) = aFalsePositiveWrapperOrFail()
        // The shifted stream: byte `at` becomes the entry's first byte, so the probe sees a "header" it
        // should not, and four bytes are stripped off a stream that never had a wrapper.
        val shifted = stream.copyOfRange(at, stream.size)
        val zip = bundleOfOneDeflatedEntry("brushes/Ink Default.kpp", shifted)
        val library = KritaImport.convertBundle(zip, "probe")
        assertEquals(
            1,
            library.brushes.size + library.refused.size,
            "one outcome and only one: ${library.brushes.map { it.preset.name }} / " +
                "${library.refused.map { it.name to it.reason }}",
        )
        if (library.refused.isNotEmpty()) {
            val why = library.refused.single().reason
            assertTrue(why.isNotBlank(), "the refusal has no sentence")
            // **The refusal must come from the inflater**, about the stream it was handed.
            assertTrue(why.contains("deflate"), "the refusal must name the stream: $why")
            // …and it must not be the *downstream* tell. A reader that inflated the shifted stream into a
            // truncated `.kpp` and handed it on would fail one step later, on the PNG signature, with no
            // arithmetic anywhere in the library — which is exactly the "returns something short" shape
            // this test exists to rule out.
            assertFalse(
                why.contains("PNG signature"),
                "a short read was handed on and refused later, which is the failure: $why",
            )
        }
    }

    /**
     * How many bytes of genuine DEFLATE the search may chew through before it gives up.
     *
     * A `val` and not a `const val`, because **`const` is only legal on a top-level, object or companion
     * property** — a plain class member with `const` is a compile error, and this is a class member.
     *
     * 256 KiB is about **528 expected hits** at 1 in 496, so reaching the budget is not bad luck; it is a
     * fact about the probe or the JDK's deflater, and the message says so.
     */
    private val searchBudgetBytes = 256 * 1024

    /**
     * The first offset in a genuine `Deflater(6, /* nowrap = */ true)` stream whose two bytes
     * [looksLikeZlib] accepts, as `(stream, offset)`.
     *
     * **Every position of every stream is searched, and the loop widens the *fixture* rather than
     * truncating the search.** That is the whole reason this cannot be flaky: the spec's "a 256-iteration
     * loop" has roughly a 40 % chance of finding a pair in any one stream and would fail about half the
     * time, whereas here the search keeps going over new genuine inputs until one carries an accepted pair.
     * **So "no pair found" is never a legitimate pass** — it can only mean the budget was exhausted, which
     * is either "the probe accepts nothing at all" (a bug the probe's own test would also catch) or "this
     * JDK's deflater is not producing the bytes it did". Either way it is **red**, never green, and the
     * message carries the byte count and the expected hit count so the cause is diagnosable without a
     * re-run.
     */
    private fun aFalsePositiveWrapperOrFail(): Pair<ByteArray, Int> {
        var searched = 0
        var salt = 0
        while (searched < searchBudgetBytes) {
            for (stream in genuineRawStreams(salt)) {
                for (at in 0 until stream.size - 1) {
                    if (looksLikeZlib(stream, at)) return stream to at
                }
                searched += stream.size
            }
            salt++
        }
        error(
            "no false-positive pair in $searched bytes of genuine DEFLATE; at 1 in 496 that is " +
                "${searched / 496} expected hits, so either the probe accepts nothing or this JDK's " +
                "deflater is not producing the bytes it did"
        )
    }

    /**
     * One round of genuine raw DEFLATE streams, each over a **different** input — the salt goes into a
     * brush name and a pattern's dimensions, which changes the compressed bytes. So a second round is a
     * genuinely new sample and widening the corpus really widens the search; a constant corpus would make
     * the loop pointless.
     */
    private fun genuineRawStreams(salt: Int): List<ByteArray> {
        val kppBytes = kpp(pixel("round $salt"))
        val pattern = patternPng(32 + salt % 64, 24)
        return listOf(
            rawDeflate(kppBytes, 6),
            rawDeflate(kppBytes, 9),
            rawDeflate(kppBytes, 1),
            rawDeflate(pattern, 6),
            rawDeflate(pattern, 9),
            rawDeflate(kppBytes + pattern, 6),
            rawDeflate(repeatOf("a brush with a long repeated body $salt ", 400).toByteArray(), 9),
        )
    }

    /**
     * A `.bundle` with **one** method-8 entry whose payload is exactly [payload], byte for byte.
     *
     * **Hand-written, and this is the one fixture where that is required rather than merely preferred.**
     * `ZipOutputStream` re-deflates whatever it is handed, so it cannot store a stream the test has already
     * chosen — and this test's entire subject is a stream that begins with a *specific* two-byte pair. Tests
     * 22 and 23 use the JDK's writer because an independent writer is the oracle worth having; this one
     * needs an **exact payload** instead, and those are different requirements, so conflating them would
     * have made this test vacuous while it looked like it was testing something.
     *
     * Every length is written consistently, so the archive is self-describing and `KritaZip` reads it as it
     * would read the JDK's. The declared uncompressed size is the payload's own length: the true one is
     * unknowable for a stream cut out of the middle of another, it is **not believed by any reader here**,
     * and the ratio it produces is 1:1, so no bomb budget is involved. The CRC is zero because no reader
     * in this project verifies one (Decision 12) and a test that invented a wrong CRC would be testing the
     * wrong thing.
     */
    private fun bundleOfOneDeflatedEntry(name: String, payload: ByteArray): ByteArray {
        val nameBytes = name.toByteArray()
        val out = ArrayList<Byte>()
        for (b in le32(0x04034b50L)) out.add(b)                 // local file header signature
        for (b in le16(20)) out.add(b)                          // version needed
        for (b in le16(0)) out.add(b)                           // flags
        for (b in le16(8)) out.add(b)                           // method: deflate
        for (b in le16(0)) out.add(b)                           // mod time
        for (b in le16(0)) out.add(b)                           // mod date
        for (b in le32(0)) out.add(b)                           // crc: not verified by any reader here
        for (b in le32(payload.size.toLong())) out.add(b)       // compressed size
        for (b in le32(payload.size.toLong())) out.add(b)       // declared uncompressed size
        for (b in le16(nameBytes.size)) out.add(b)              // name length
        for (b in le16(0)) out.add(b)                           // extra length
        for (b in nameBytes) out.add(b)
        for (b in payload) out.add(b)

        val directoryOffset = out.size
        for (b in le32(0x02014b50L)) out.add(b)                 // central directory signature
        for (b in le16(20)) out.add(b)                          // version made by
        for (b in le16(20)) out.add(b)                          // version needed
        for (b in le16(0)) out.add(b)                           // flags
        for (b in le16(8)) out.add(b)                           // method: deflate
        for (b in le16(0)) out.add(b)                           // mod time
        for (b in le16(0)) out.add(b)                           // mod date
        for (b in le32(0)) out.add(b)                           // crc
        for (b in le32(payload.size.toLong())) out.add(b)       // compressed size
        for (b in le32(payload.size.toLong())) out.add(b)       // uncompressed size
        for (b in le16(nameBytes.size)) out.add(b)              // name length
        for (b in le16(0)) out.add(b)                           // extra length
        for (b in le16(0)) out.add(b)                           // comment length
        for (b in le16(0)) out.add(b)                           // disk number start
        for (b in le16(0)) out.add(b)                           // internal attributes
        for (b in le32(0)) out.add(b)                           // external attributes
        for (b in le32(0)) out.add(b)                           // local header offset: 0
        for (b in nameBytes) out.add(b)

        val directorySize = out.size - directoryOffset
        for (b in le32(0x06054b50L)) out.add(b)                 // end-of-directory signature
        for (b in le16(0)) out.add(b)                           // this disk
        for (b in le16(0)) out.add(b)                           // disk with the directory
        for (b in le16(1)) out.add(b)                           // entries on this disk
        for (b in le16(1)) out.add(b)                           // entries in total
        for (b in le32(directorySize.toLong())) out.add(b)
        for (b in le32(directoryOffset.toLong())) out.add(b)
        for (b in le16(0)) out.add(b)                           // comment length
        return out.toByteArray()
    }

    // ---- 26. the prose must agree with the code --------------------------------------------------------

    /**
     * **Three needles, each chosen for a reason**, read from the two source files rather than asserted about
     * behaviour — because the failure this guards is a *comment* that survives the code it no longer
     * describes, and no behavioural test can see one.
     *
     *  - `"no inflater"` and `"does not inflate a PNG text chunk"` are the two prose claims R44 item 2
     *    reversed. Before this row the first was in `KritaZip.readStored` and the second in
     *    `readKppFile`, and a reader who believed either would write a check that refuses every real file.
     *  - `"readStored"` is the old function's name, so a call site left behind by a partial rename is caught
     *    by a test rather than by a reviewer noticing a compile failure in a build nobody has run.
     *
     * This is the technique D.02a's `noCallSiteInTheAppChanged` used. The module root is found the way
     * `RealFilesProbeTest` finds its corpus — walking up from `File("").absoluteFile`, which a Gradle test
     * JVM sets to the module directory — and the same `generateSequence(…) { it.parentFile }` idiom is
     * pointed at `src/commonMain` rather than at `testdata-local`.
     */
    @Test
    fun noSourceFileStillSaysThisBuildHasNoInflater() {
        val needles = listOf("no inflater", "does not inflate a PNG text chunk", "readStored")
        var checked = 0
        for (name in listOf("KritaImport.kt", "PngChunks.kt")) {
            val file = sourceFile(name)
            val lines = file.readLines()
            checked++
            for (needle in needles) {
                val hit = lines.indexOfFirst { it.contains(needle) }
                assertTrue(hit < 0, "$name:${hit + 1} still says \"$needle\": ${if (hit < 0) "" else lines[hit]}")
            }
        }
        assertEquals(2, checked, "both files were read")
    }

    // ---- the JDK's zip writer -------------------------------------------------------------------------

    /** A `.bundle` the JDK wrote: a compressed `meta.xml` and three compressed `.kpp` files. */
    private fun jdkBundle(salt: Int): ByteArray = jdkBundleOf(
        "meta.xml" to META.toByteArray(),
        "brushes/Ink Default.kpp" to kpp(pixel("default $salt")),
        "brushes/Ink Splatter.kpp" to kpp(pixel("splatter $salt")),
        "paintoppresets/Flat Blitter.kpp" to kpp(pixel("blitter $salt")),
    )

    /** A bundle whose one pattern is a real Huffman-coded method-8 entry and whose brush names it. */
    private fun jdkGrainBundle(pattern: ByteArray, salt: Int): ByteArray = jdkBundleOf(
        "brushes/grain.kpp" to kpp(
            """<paintop id="Pixel" name="grain $salt">$AUTO_MASK""" +
                """<spacing mode="space" value="0.3"/>""" +
                """<texture enabled="true" pattern="grain.png" blendmode="Subtract" depth="0.4"/></paintop>"""
        ),
        "patterns/grain.png" to pattern,
    )

    /**
     * A zip written by **`java.util.zip.ZipOutputStream`** at [Deflater.BEST_COMPRESSION]: every entry is a
     * real method-8 entry with a real Huffman-coded payload and real sizes.
     *
     * `setLevel` is set once on the stream and every entry is `DEFLATED`, so this is the archive a real
     * writer produces — a **second implementation of the container**, which is the whole reason this file
     * exists next to the hand-written one.
     */
    private fun jdkBundleOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).apply { setLevel(Deflater.BEST_COMPRESSION) }.use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /**
     * Every entry payload's first byte offset, from the **central directory** — the same PKWARE layout the
     * reader walks, written out here so the fixture guard does not depend on the code under test to find a
     * byte in its own input.
     *
     * The payload starts after the local header's *own* name and extra fields, whose lengths need not be the
     * central directory's, which is the reason the reader reads them at all.
     */
    private fun payloadOffsets(zip: ByteArray): List<Int> {
        var end = -1
        val earliest = maxOf(0, zip.size - (22 + 0xFFFF))
        for (i in zip.size - 22 downTo earliest) {
            if (le32(zip, i) == 0x06054b50L) {
                end = i
                break
            }
        }
        check(end >= 0) { "no end-of-directory record in ${zip.size} bytes" }
        val total = le16(zip, end + 10)
        val out = ArrayList<Int>(total)
        var at = le32(zip, end + 16).toInt()
        for (n in 0 until total) {
            check(le32(zip, at) == 0x02014b50L) { "directory entry ${n + 1} has no signature" }
            val nameLength = le16(zip, at + 28)
            val extraLength = le16(zip, at + 30)
            val commentLength = le16(zip, at + 32)
            val local = le32(zip, at + 42).toInt()
            check(le32(zip, local) == 0x04034b50L) { "local header at $local has no signature" }
            out += local + 30 + le16(zip, local + 26) + le16(zip, local + 28)
            at += 46 + nameLength + extraLength + commentLength
        }
        return out
    }

    /**
     * The offset of the first entry payload whose first two bytes [looksLikeZlib] accepts, or −1.
 *
     * The wrapper probe is a *test*, not a requirement, and it has a documented false-positive rate of about
     * 1 in 496 — so a payload that happens to begin with such a pair gets four bytes stripped off a stream
     * that never had a wrapper, and a fixture would be refused for a reason that has nothing to do with what
     * it is testing. The guard is on the **fixture's bytes**, never on the reader: a fixture whose payloads
     * are all safe needs no retry, and one that is not gets a different salt and is rebuilt.
     */
    private fun falsePositiveWrapperAt(zip: ByteArray): Int {
        for (at in payloadOffsets(zip)) {
            if (looksLikeZlib(zip, at)) return at
        }
        return -1
    }

    /** 32 salts is far more than the 1-in-496 rate needs; passing this loop is itself a bug in the probe. */
    private fun bundleWithoutAFalsePositiveWrapper(build: (Int) -> ByteArray): ByteArray {
        var salt = 0
        while (true) {
            val zip = build(salt)
            if (falsePositiveWrapperAt(zip) < 0) return zip
            salt++
            check(salt < 32) { "32 fixtures and no entry payload began with a non-zlib pair; the probe is wrong" }
        }
    }

    /**
     * The two **encoders**, little-endian, which is the only byte order a zip header is ever in.
     *
     * Distinct from the [le16]/[le32] readers below, which take a buffer and an offset: these take
     * a value and return the bytes to append. `hand-written container, not merely preferred` is
     * exactly why they are here — a hand-written header has to be able to WRITE one.
     */
    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFFL).toByte(),
        ((v ushr 8) and 0xFFL).toByte(),
        ((v ushr 16) and 0xFFL).toByte(),
        ((v ushr 24) and 0xFFL).toByte(),
    )

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    // ---- DEFLATE, two ways ----------------------------------------------------------------------------

    /** A **raw** DEFLATE stream (RFC 1951): what a zip entry's method 8 really is. */
    private fun rawDeflate(input: ByteArray, level: Int): ByteArray {
        val d = Deflater(level, /* nowrap = */ true)
        d.setInput(input)
        d.finish()
        val out = ArrayList<Byte>(input.size / 2 + 16)
        val chunk = ByteArray(16 * 1024)
        while (!d.finished()) {
            val n = d.deflate(chunk)
            if (n <= 0) break
            for (i in 0 until n) out.add(chunk[i])
        }
        d.end()
        return out.toByteArray()
    }

    /**
     * A **zlib** stream (RFC 1950): the two-byte header, the DEFLATE data, and the trailing Adler-32 — which
     * is what a PNG `zTXt` holds.
     *
     * `DeflaterOutputStream` rather than a bare `Deflater` with `nowrap = false`, because `finished()`
     * reports the *deflate* part done before the checksum is emitted: a hand-rolled loop stops four bytes
     * early and produces a zlib header wrapped around a truncated stream.
     */
    private fun zlib(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { it.write(bytes); it.finish() }
        return bos.toByteArray()
    }

    // ---- PNG bytes ------------------------------------------------------------------------------------

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val AUTO_MASK =
        """<brush_definition type="auto_mask" name="start" active="true">""" +
            """<cell row="0" col="0" value="1.0;0.6"/></brush_definition>"""

    private val PIXEL =
        """<paintop id="Pixel">$AUTO_MASK<spacing mode="space" value="0.3"/></paintop>"""

    private val META =
        """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
            """<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0">""" + "\n" +
            """  <office:meta>""" + "\n" +
            """    <dc:creator>David Revoy</dc:creator>""" + "\n" +
            """    <dc:rights>CC-BY 4.0</dc:rights>""" + "\n" +
            """  </office:meta>""" + "\n" +
            """</office:document-meta>"""

    /** Latin-1: the character's code point **is** the byte, which is the PNG rule for `tEXt` and `zTXt`. */
    private fun latin1(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            require(c.code < 256) { "'$c' is not Latin-1, so it cannot be a `tEXt` byte" }
            out[i] = c.code.toByte()
        }
        return out
    }

    private fun pixel(name: String): String =
        """<paintop id="Pixel" name="$name">$AUTO_MASK<spacing mode="space" value="0.3"/></paintop>"""

    private fun kpp(xml: String): ByteArray = png(
        ihdr(8, 8),
        textChunk("version", "0.5"),
        textChunk("preset", xml),
        chunkBytes("IEND", ByteArray(0)),
    )

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArray(SIGNATURE.size + chunks.sumOf { it.size })
        SIGNATURE.copyInto(out, 0)
        var at = SIGNATURE.size
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val data = ByteArray(13)
        data[0] = (width shr 24).toByte()
        data[1] = (width shr 16).toByte()
        data[2] = (width shr 8).toByte()
        data[3] = width.toByte()
        data[4] = (height shr 24).toByte()
        data[5] = (height shr 16).toByte()
        data[6] = (height shr 8).toByte()
        data[7] = height.toByte()
        data[8] = 8
        data[9] = 6
        return chunkBytes("IHDR", data)
    }

    private fun textChunk(keyword: String, body: String): ByteArray =
        textChunkBytes(keyword, body.toByteArray())

    /** `tEXt`: the keyword, a NUL, then Latin-1 bytes. */
    private fun textChunkBytes(keyword: String, body: ByteArray): ByteArray {
        val data = ByteArray(keyword.length + 1 + body.size)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0
        body.copyInto(data, keyword.length + 1)
        return chunkBytes("tEXt", data)
    }

    /** `zTXt`: the keyword, a NUL, the compression method `0`, then the zlib stream. */
    private fun zTxtChunk(keyword: String, stream: ByteArray): ByteArray {
        val data = ByteArray(keyword.length + 2 + stream.size)
        for (i in keyword.indices) data[i] = keyword[i].code.toByte()
        data[keyword.length] = 0
        data[keyword.length + 1] = 0
        stream.copyInto(data, keyword.length + 2)
        return chunkBytes("zTXt", data)
    }

    /** 4 of length, 4 of type, the payload, 4 of CRC — with a real CRC, because [CRC32] is right here. */
    private fun chunkBytes(type: String, data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(type.toByteArray())
        crc.update(data)
        val out = ByteArray(12 + data.size)
        writeBe32(out, 0, data.size.toLong())
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        data.copyInto(out, 8)
        writeBe32(out, 8 + data.size, crc.value)
        return out
    }

    private fun writeBe32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v shr 24).toByte()
        b[at + 1] = (v shr 16).toByte()
        b[at + 2] = (v shr 8).toByte()
        b[at + 3] = v.toByte()
    }

    /** A real PNG carrying an `IHDR` and no pixels: a header read, not a decode. */
    private fun patternPng(width: Int, height: Int): ByteArray =
        png(ihdr(width, height), chunkBytes("IEND", ByteArray(0)))

    private fun repeatOf(s: String, times: Int): String {
        val sb = StringBuilder(times * s.length)
        for (i in 0 until times) sb.append(s)
        return sb.toString()
    }

    // ---- base64 and the module root -------------------------------------------------------------------

    /**
     * RFC 4648 §4, written independently of the importer's encoder so the round trip is an oracle and not a
     * tautology: three bytes are 24 bits and four characters take six each, so the four indices are
     * `(n >> 18) & 63`, `(n >> 12) & 63`, `(n >> 6) & 63` and `n & 63`; `=` ends the stream.
     */
    private fun decodeBase64(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=') break
            val v = BASE64.indexOf(c)
            check(v >= 0) { "not base64: '$c'" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /**
     * One of the two source files this row rewrote, located the way `RealFilesProbeTest` locates its corpus:
     * walk up from the working directory until one level has a `src/commonMain` under it. A Gradle test JVM's
     * working directory is the module directory, so the first hit is `:core` — but the walk does not rely on
     * that, and neither does the assertion.
     */
    private fun sourceFile(name: String): File {
        val commonMain = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/commonMain") }
            .firstOrNull { it.isDirectory }
            ?: error("no src/commonMain above ${File("").absoluteFile}")
        val file = File(commonMain, "kotlin/cc/joycreator/joybrush/core/brush/imports/$name")
        assertTrue(file.isFile, "${file.absolutePath} is not a file")
        return file
    }

    /** RFC 4648 §4's standard alphabet, `A–Z a–z 0–9 + /`. */
    private val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}