package cc.joycreator.joybrush.core.brush.imports

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * **JB-8.01b — the `.abr` reader against real Photoshop files.**
 *
 * The reader this row fixes was written from a spec that guessed, tested against fixtures built from
 * the same guess, and reviewed by models that could not see a real file. Every real `.abr` it was
 * pointed at failed, and the first thing it read — a `u32` byte length after a list's count — is a
 * field that does not exist. This test is the other half of the row: for every real `.abr` in the
 * local, git-ignored corpus it imports the file and checks the result against **ag-psd's own parse of
 * the same bytes**, which each file carries beside it as `data.json`. That is an independent oracle —
 * a different implementation, the same file, no knowledge of this build — and it is the only thing in
 * the project that can say whether this reader reads a real `.abr`.
 *
 * The corpus is never committed (R8 / blueprint §5: importing what the owner owns is fine,
 * redistributing it is not). Point `JOYBRUSH_TESTDATA` at the folder that holds it, or let it default
 * to `<joybrush>/testdata-local`; `core/build.gradle.kts` declares it as a `jvmTest` input, so adding
 * or changing a file re-runs this (R44 item 1).
 *
 * **This test skips loudly, never silently.** With no corpus it is `assumeTrue` with the reason
 * printed first, so the run reports "skipped" rather than "passed". A test that is green because it
 * did nothing is worse than no test at all.
 */
class AbrRealFilesTest {

    /** Shared across the test instances of one run, which is what makes one verdict file possible. */
    private companion object {
        val verdicts = StringBuilder()
    }

    // ---- the corpus ---------------------------------------------------------------------------------

    /**
     * The `<name>` folders that hold a `src.abr`, sorted. Returns null after printing the reason when
     * there is nothing to test, which the callers turn into a skip.
     */
    private fun corpusOrNull(): List<File>? {
        val env = System.getenv("JOYBRUSH_TESTDATA")
        val root = if (env != null) {
            File(env)
        } else {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "testdata-local") }.firstOrNull { it.isDirectory }
        }
        if (root == null) {
            println("abr real files: JOYBRUSH_TESTDATA is unset and no testdata-local/ was found — SKIPPING")
            return null
        }
        val abr = File(root, "abr")
        val dirs = abr.listFiles()?.filter { File(it, "src.abr").isFile }?.sortedBy { it.name }.orEmpty()
        if (dirs.isEmpty()) {
            println("abr real files: ${abr.absolutePath} holds no <name>/src.abr — SKIPPING")
            return null
        }
        println("abr real files: ${abr.absolutePath} holds ${dirs.size} file(s): ${dirs.joinToString { it.name }}")
        return dirs
    }

    private fun corpus(): List<File> {
        val dirs = corpusOrNull()
        assumeTrue(
            dirs != null,
            "no real .abr corpus: set JOYBRUSH_TESTDATA, or put <name>/src.abr in joybrush/testdata-local/abr/",
        )
        return dirs!!
    }

    // ---- A. every real file imports or is refused in words, and agrees with the oracle ---------------

    /**
     * The whole of acceptance A, one real file at a time.
     *
     * For each `<name>/src.abr` beside its `data.json`:
     *  1. `AbrImport.convert` does not throw. A fault in the *file* is a refusal with a sentence, and
     *     a crash is a test failure naming the file.
     *  2. **Every** brush is accounted for: converted + refused = `brushes.length` in the oracle.
     *     Nothing may quietly vanish, and nothing may appear that is not in the file.
     *  3. Each brush carries the name ag-psd read, whether it converted or was refused.
     *  4. A brush ag-psd calls `computed` came out with a procedural tip and the tip's own `size`,
     *     `hardness` and `spacing`; one it calls `sampled` came out carrying its bitmap and the stored
     *     bytes R40 asks for.
     *
     * Every expected number is ag-psd's own `parsePercent` / `parseUnitsToNumber` of a value in the
     * file, read from `data.json` — never a number observed from this build. Where the two scales
     * differ the comment says by how much: ag-psd's `spacing` is a fraction and so is
     * `BrushPreset.spacing`, while its `size` is `Dmtr` in pixels, which is what `size.base` is.
     */
    @Test
    fun everyRealAbrImportsOrIsRefusedInWordsAndAgreesWithAgsPsd() {
        for (dir in corpus()) {
            val name = dir.name
            val bytes = File(dir, "src.abr").readBytes()
            val oracle = File(dir, "data.json")
            assumeTrue(
                oracle.isFile,
                "$name has no data.json beside src.abr, so there is no oracle to check it against — SKIPPING it",
            )
            val expected = oracle.readOracle()

            val library = try {
                AbrImport.convert(bytes, "abr-real")
            } catch (e: Throwable) {
                verdict("$name: EXCEPTION ${e.javaClass.simpleName}: ${e.message?.take(400)}")
                throw AssertionError("$name could not be imported at all: ${e.message}", e)
            }

            assertEquals(
                expected.brushes.size,
                library.brushes.size + library.refused.size,
                "$name: ${library.brushes.size} converted + ${library.refused.size} refused != " +
                    "${expected.brushes.size} brushes in data.json",
            )

            val converted = library.brushes.associateBy { it.preset.name }
            val refused = library.refused.associateBy { it.name }

            for ((index, oracleBrush) in expected.brushes.withIndex()) {
                val where = "$name brush ${index + 1} \"${oracleBrush.name}\""
                val result = converted[oracleBrush.name]
                if (result == null) {
                    // Refusing a brush is allowed — a bristle tip or an erodible one has no Joy Brush
                    // equivalent, and dropping it is the correct answer. Refusing it *silently*, or
                    // refusing a brush ag-psd read under a different name, is not.
                    val refusal = refused[oracleBrush.name]
                    assertNotNull(
                        refusal,
                        "$where is in neither the converted nor the refused list: converted " +
                            "${converted.keys}, refused ${refused.keys}",
                    )
                    assertTrue(refusal!!.reason.isNotBlank(), "$where was refused with no reason at all")
                    verdict("$name [$index] refused \"${oracleBrush.name}\": ${refusal.reason}")
                    continue
                }

                val preset = result.preset
                assertEquals(oracleBrush.name, preset.name, where)
                val shape = oracleBrush.shape
                // `size`: ag-psd reads `Dmtr` with `parseUnitsToNumber(…, 'Pixels')`, and `size.base`
                // is the same number in pixels. 1e-3 px is a float, not a judgement: `Dmtr` is stored
                // as a `UntF` double and 9.0, 25.0, 30.0, 36.0, 71.0 and 112.0 all land exactly in a
                // Float.
                assertEquals(shape.size, preset.size.base.toDouble(), 1e-3, "$where: ag-psd's Dmtr")
                // `spacing`: ag-psd's `parsePercent(Spcn)` and the importer's `Spcn / 100` are the
                // same division of the same file value, on the same 0..1 fraction scale.
                assertEquals(shape.spacing, preset.spacing.toDouble(), 1e-4, "$where: ag-psd's Spcn / 100")

                when (shape.type) {
                    "computed" -> {
                        assertEquals("procedural", preset.tip.source, "$where: a computed tip carries no bitmap")
                        // `hardness`: ag-psd's `parsePercent(Hrdn)`, the importer's `Hrdn / 100`.
                        assertEquals(
                            present(shape.hardness, "hardness", where),
                            preset.tip.hardness.base.toDouble(),
                            1e-4,
                            "$where: ag-psd's Hrdn / 100",
                        )
                        verdict(
                            "$name [$index] imported \"${oracleBrush.name}\": computed, size ${preset.size.base}, " +
                                "hardness ${preset.tip.hardness.base}, spacing ${preset.spacing}",
                        )
                    }
                    "sampled" -> {
                        assertEquals("image", preset.tip.source, "$where: a sampled tip must carry its bitmap")
                        // Rule 23: an image source has to name an image.
                        assertTrue(!preset.tip.image.isNullOrBlank(), "$where: image source with no image path")
                        // R40: the file's own bytes are kept, and the name says which encoding they are.
                        assertNotNull(preset.extensions["abr.tipImage"], "$where: R40's stored tip bytes")
                        // The tip's diameter: ag-psd's `Dmtr`, and the bitmap is that wide too — the
                        // second test below checks the bitmap against the `samples` bounds directly.
                        verdict(
                            "$name [$index] imported \"${oracleBrush.name}\": sampled, Dmtr ${preset.size.base}, " +
                                "image ${preset.tip.image}, ${preset.extensions["abr.tipImage"]!!.length} base64 chars, " +
                                "hardness ${preset.tip.hardness.base} (R40's 0.25 + 0.7 x mean stand-in)",
                        )
                    }
                    // ag-psd's `tips` shape is both erodible and airbrush; this build refuses the erodible
                    // one and maps the airbrush one to a soft round dab, and `dtipsHardness` is the
                    // hardness both read.
                    "tips" -> {
                        assertEquals(
                            present(shape.tipsHardness, "tipsHardness", where),
                            preset.tip.hardness.base.toDouble(),
                            1e-4,
                            "$where: ag-psd's dtipsHardness / 100",
                        )
                        verdict(
                            "$name [$index] imported \"${oracleBrush.name}\": tips (airbrush), size ${preset.size.base}, " +
                                "hardness ${preset.tip.hardness.base}, spacing ${preset.spacing}",
                        )
                    }
                    else -> verdict("$name [$index] imported \"${oracleBrush.name}\": shape \"${shape.type}\", not checked here")
                }
            }
        }
    }

    /**
     * The sampled tips decode to the size ag-psd's `samples` entry says, and to nothing else.
     *
     * This reads [AbrReader] directly rather than the importer, because the claim is about the *bytes*:
     * `w` is `right - left` and `h` is `bottom - top`, both read as signed 32-bit numbers in the file's
     * own `top, left, bottom, right` order, and decoding must produce exactly `w × h` gray samples —
     * one byte per pixel, whatever the stored depth.
     *
     * The oracle's `samples` entries carry `id` and `bounds` but **no alpha**, so the alpha sum cannot be
     * compared against anything today; that half is printed as a reason rather than quietly skipped.
     */
    @Test
    fun everySampledTipDecodesToTheSizeTheOracleGives() {
        var tips = 0
        for (dir in corpus()) {
            val name = dir.name
            val oracle = File(dir, "data.json")
            assumeTrue(
                oracle.isFile,
                "$name has no data.json beside src.abr, so there is no oracle to check it against — SKIPPING it",
            )
            val samples = oracle.readOracle().samples
            if (samples.isEmpty()) {
                verdict("$name: data.json carries no samples, so this file has no bitmap tip to decode")
                continue
            }
            val file = AbrReader.read(File(dir, "src.abr").readBytes())
            for (sample in samples) {
                val where = "$name tip \"${sample.id}\""
                val tip = file.tipsById[AbrReader.normaliseId(sample.id)]
                assertNotNull(tip, "$where is not in the file's tips")
                assertEquals(null, tip!!.error, "$where cannot be read: ${tip.error}")
                assertEquals(sample.w.toLong(), tip.width, "$where: width is right - left")
                assertEquals(sample.h.toLong(), tip.height, "$where: height is bottom - top")
                val gray = file.grayBytes(tip)
                assertEquals(sample.w.toLong() * sample.h.toLong(), gray.size.toLong(), "$where: decoded sample count")
                verdict(
                    "$name: tip \"${sample.id}\" decoded to ${tip.width}x${tip.height} = ${gray.size} samples, " +
                        "depth ${tip.depth}, compression ${tip.compression}; data.json carries no alpha to compare",
                )
                tips++
            }
        }
        assertTrue(tips > 0, "no sampled tip was checked in any real file")
    }

    // ---- the verdict file ---------------------------------------------------------------------------

    private fun verdict(line: String) {
        verdicts.appendLine(line)
        println(line)
    }

    /**
     * One file for the whole run, and only the first test of the run truncates it — JUnit makes a new
     * instance per test but the builder is shared, so "the builder is empty" is exactly "nothing of
     * this run has been recorded yet".
     */
    @BeforeTest
    fun startVerdictFile() {
        if (verdicts.isNotEmpty()) return
        File(System.getProperty("java.io.tmpdir"), "joybrush_abr_real.txt").writeText("")
    }

    /** Written whether the assertions passed or failed, so a failed run still says what it saw. */
    @AfterTest
    fun writeVerdicts() {
        if (verdicts.isEmpty()) return
        val out = File(System.getProperty("java.io.tmpdir"), "joybrush_abr_real.txt")
        out.appendText(verdicts.toString())
        println("wrote ${verdicts.length()} verdict lines so far to ${out.absolutePath}")
    }

    // ---- ag-psd's `data.json` ----------------------------------------------------------------------

    private class Oracle(
        val brushes: List<OracleBrush>,
        val samples: List<OracleSample>,
    )

    private class OracleBrush(val name: String, val shape: OracleShape)

    /**
     * What ag-psd's `parseBrushShape` recorded. [hardness] is only on a `computed` shape (it is
     * `parsePercent(Hrdn)`), and [tipsHardness] only on a `tips` shape (`parsePercent(dtipsHardness)`);
     * a missing one is a missing one, and the test that needs it says which key it wanted.
     */
    private class OracleShape(
        val type: String,
        val size: Double,
        val spacing: Double,
        val hardness: Double?,
        val tipsHardness: Double?,
    )

    private class OracleSample(val id: String, val w: Int, val h: Int)

    /**
     * `data.json`, read with the JSON library this module already depends on rather than with a
     * hand-written scan: a hand-rolled scanner that fails to find `patterns[0].name` cannot tell the
     * difference between "no patterns" and "my parser is wrong", and this file has both a `patterns`
     * array and a `samples` array with their own `id` and `name` keys in front of `brushes`.
     *
     * A key that must be there and is not throws, naming the file — so an oracle whose shape changed
     * fails loudly instead of being read as a default.
     */
    private fun File.readOracle(): Oracle {
        val root = Json.parseToJsonElement(readText()).jsonObject
        val brushes = root.array("brushes").map { element ->
            val brush = element.jsonObject
            val shape = brush["shape"]?.jsonObject
                ?: error("${name}: a brush has no \"shape\" object")
            OracleBrush(
                brush.string("name"),
                OracleShape(
                    type = shape.string("type"),
                    size = shape.number("size"),
                    spacing = shape.number("spacing"),
                    hardness = shape.numberOrNull("hardness"),
                    tipsHardness = shape.numberOrNull("tipsHardness"),
                ),
            )
        }
        val samples = root.array("samples").map { element ->
            val sample = element.jsonObject
            val bounds = sample["bounds"]?.jsonObject ?: error("${this.name}: a sample has no \"bounds\"")
            OracleSample(sample.string("id"), bounds.int("w"), bounds.int("h"))
        }
        return Oracle(brushes, samples)
    }

    private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: emptyList()

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("the oracle has no \"$key\"")

    private fun JsonObject.number(key: String): Double = numberOrNull(key) ?: error("the oracle has no \"$key\"")

    private fun JsonObject.numberOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.int ?: error("the oracle has no integer \"$key\"")

    /**
     * A number the oracle must carry. A missing one is a failure that says which key was wanted, so an
     * oracle whose shape changed is a red test and not a default of zero that happens to agree.
     */
    private fun present(value: Double?, key: String, where: String): Double {
        assertNotNull(value, "$where: the oracle's shape carries no \"$key\"")
        return value!!
    }
}