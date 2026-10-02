package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushValidate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JB-8.01b: the `.abr` reader against REAL files.
 *
 * For every testdata-local abr folder with a src.abr present, `AbrImport.convert` must NOT throw, and against
 * that folder's `data.json` — ag-psd's own parse of the same file, an independent oracle:
 *  - imported brushes plus refused brushes account for every `brushes` entry of `data.json`;
 *  - each brush keeps its name; a `computed` shape arrives as a computed tip and a `sampled` shape
 *    as a tip carrying an image, with the `samples` entry of that id decoding to its `w × h`;
 *  - spacing agrees as a fraction (the oracle says 1.0 = 100 %), and computed size and hardness agree.
 *
 * When the corpus is absent the tests skip with a printed reason, never passing silently.
 * Every verdict line is printed and appended to `%TEMP%/joybrush_abr_real.txt`.
 */
class AbrRealFilesTest {

    private fun corpusRoot(): File? {
        val env = System.getenv("JOYBRUSH_TESTDATA")
        if (env != null) return File(env).takeIf { it.isDirectory }
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "testdata-local") }.firstOrNull { it.isDirectory }
    }

    @Test
    fun simpleImportsAgainstItsOracle() {
        checkFile("simple")
    }

    @Test
    fun tiltImportsAgainstItsOracle() {
        checkFile("tilt")
    }

    @Test
    fun specialImportsAgainstItsOracle() {
        checkFile("special")
    }

    @Test
    fun sampleAndPatternImportsAgainstItsOracle() {
        checkFile("sample-and-pattern")
    }

    private fun checkFile(dirName: String) {
        val root = corpusRoot()
        val reason = "AbrRealFilesTest/$dirName: SKIPPED — no testdata-local/abr/$dirName/src.abr + data.json " +
            "(corpus root ${root?.path ?: "absent"}; set JOYBRUSH_TESTDATA)"
        val dir = root?.let { File(it, "abr/$dirName") }
        val abr = dir?.let { File(it, "src.abr") }?.takeIf { it.isFile }
        val oracle = dir?.let { File(it, "data.json") }?.takeIf { it.isFile }
        if (abr == null || oracle == null) {
            println(reason)
        }
        // kotlin.test has no assumption API on this toolchain, so JUnit's own — already the runner
        // behind kotlin.test here, no new dependency — with the skip reason as the message.
        org.junit.Assume.assumeTrue(reason, abr != null && oracle != null)

        val bytes = abr!!.readBytes()
        val data = kotlinx.serialization.json.Json.parseToJsonElement(oracle!!.readText()).jsonObject
        val expected = data["brushes"]!!.jsonArray
        val samples = data["samples"]!!.jsonArray

        val library = try {
            AbrImport.convert(bytes, "abr")
        } catch (e: Throwable) {
            fail("$dirName: convert threw ${e.javaClass.simpleName}: ${e.message}")
        }
        // Reader level: nothing is ever dropped, only reported.
        val read = try {
            AbrReader.read(bytes)
        } catch (e: Throwable) {
            fail("$dirName: read threw ${e.javaClass.simpleName}: ${e.message}")
        }
        assertEquals(expected.size, read.brushes.size, "$dirName: the reader must report every oracle brush")
        assertEquals(
            expected.size, library.brushes.size + library.refused.size,
            "$dirName: imported + refused must account for every oracle brush",
        )

        // Every oracle sample must be in the file with its bounds and no error of its own.
        for (s in samples) {
            val o = s.jsonObject
            val bounds = o["bounds"]!!.jsonObject
            val w = bounds["w"]!!.jsonPrimitive.double.toInt()
            val h = bounds["h"]!!.jsonPrimitive.double.toInt()
            val id = o["id"]!!.jsonPrimitive.content
            val tip = read.tipsById[AbrReader.normaliseId(id)]
                ?: fail("$dirName: sample $id is not in the file")
            assertNull(tip.error, "$dirName: sample $id: ${tip.error}")
            assertEquals(w.toLong(), tip.width, "$dirName: sample $id width")
            assertEquals(h.toLong(), tip.height, "$dirName: sample $id height")
        }

        val refusedByIndex = library.refused.associateBy { it.index }
        val converted = library.brushes.iterator()
        val details = ArrayList<String>()
        for (i in expected.indices) {
            val brush = expected[i].jsonObject
            val expName = brush["name"]!!.jsonPrimitive.content
            val shape = brush["shape"]!!.jsonObject
            val type = shape["type"]!!.jsonPrimitive.content
            val refused = refusedByIndex[i]
            if (refused != null) {
                assertTrue(refused.name.isNotBlank(), "$dirName brush ${i + 1}: refusal has no name")
                assertTrue(refused.reason.isNotBlank(), "$dirName brush ${i + 1} ($expName): refusal has no reason")
                assertEquals(expName, refused.name, "$dirName brush ${i + 1}: refused under the wrong name")
                details += "    refused #$i $expName [$type]: ${refused.reason.take(200)}"
                continue
            }
            val result = if (converted.hasNext()) converted.next()
            else fail("$dirName brush ${i + 1} ($expName): neither converted nor refused")
            val preset = result.preset
            assertEquals(expName, preset.name, "$dirName brush ${i + 1}")
            assertEquals(emptyList(), BrushValidate.validate(preset), "$dirName $expName: ${result.warnings}")
            when (type) {
                "computed" -> {
                    assertNull(preset.extensions["abr.tipImage"], "$dirName $expName: a computed tip stores no image")
                    val size = shape["size"]!!.jsonPrimitive.double.toFloat()
                    val hardness = shape["hardness"]!!.jsonPrimitive.double.toFloat()
                    assertEquals(size, preset.size.base, 1e-4f, "$dirName $expName size")
                    assertEquals(hardness, preset.tip.hardness.base, 1e-4f, "$dirName $expName hardness")
                    val spacing = shape["spacing"]!!.jsonPrimitive.double.toFloat()
                    assertEquals(spacing, preset.spacing, 1e-6f, "$dirName $expName spacing")
                    details += "    imported #$i $expName [computed size=$size hardness=$hardness spacing=$spacing]"
                }
                "sampled" -> {
                    val sampledData = shape["sampledData"]!!.jsonPrimitive.content
                    val tip = read.tipsById[AbrReader.normaliseId(sampledData)]
                        ?: fail("$dirName $expName: sampledData $sampledData is not in the file")
                    val sample = samples.firstOrNull {
                        AbrReader.normaliseId(it.jsonObject["id"]!!.jsonPrimitive.content) ==
                            AbrReader.normaliseId(sampledData)
                    }?.jsonObject?.get("bounds")?.jsonObject
                        ?: fail("$dirName $expName: no oracle sample for $sampledData")
                    val w = sample["w"]!!.jsonPrimitive.double.toInt()
                    val h = sample["h"]!!.jsonPrimitive.double.toInt()
                    // The oracle carries bounds but no alpha bytes, so sizes only.
                    assertEquals((w * h).toLong(), read.grayBytes(tip).size.toLong(), "$dirName $expName decoded size")
                    val image = preset.tip.image
                    assertTrue(image == "tip.packbits" || image == "tip.raw", "$dirName $expName: tip.image=$image")
                    assertEquals(w.toFloat(), preset.size.base, 1e-4f, "$dirName $expName size")
                    val spacing = shape["spacing"]!!.jsonPrimitive.double.toFloat()
                    assertEquals(spacing, preset.spacing, 1e-6f, "$dirName $expName spacing")
                    details += "    imported #$i $expName [sampled ${w}x$h image=$image spacing=$spacing]"
                }
                else -> {
                    // `dynamic` (bristle) and `tips` (erodible/airbrush) have no Joy Brush engine:
                    // refused with a sentence is the correct import, and a converted one keeps its name.
                    details += "    brush #$i $expName [$type]: converted"
                }
            }
        }

        val verdict = "$dirName: ${expected.size}/${expected.size} accounted " +
            "(${library.brushes.size} imported, ${library.refused.size} refused) — OK"
        recordVerdict(verdict)
        for (d in details) recordVerdict(d)
    }

    companion object {
        private var headerWritten = false

        @Synchronized
        private fun recordVerdict(line: String) {
            println(line)
            val out = File(System.getProperty("java.io.tmpdir"), "joybrush_abr_real.txt")
            if (!headerWritten) {
                out.writeText("")
                headerWritten = true
            }
            out.appendText(line + "\n")
        }
    }
}
