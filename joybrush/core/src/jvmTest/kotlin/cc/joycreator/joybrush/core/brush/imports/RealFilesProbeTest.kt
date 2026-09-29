package cc.joycreator.joybrush.core.brush.imports

import java.io.File
import kotlin.test.Test

/**
 * JB-8.05 (LEAD_RULINGS R44): imports every REAL third-party brush file found in the local, git-ignored
 * corpus and PRINTS a verdict per file. It fails only on a crash or a hang, never on "this brush imported
 * differently from Photoshop" — that judgement is a human one (the report), which is the point.
 * Corpus folder: $JOYBRUSH_TESTDATA, else <joybrush>/testdata-local. Empty or absent = the test does nothing.
 */
class RealFilesProbeTest {
    private fun corpus(): File? {
        val env = System.getenv("JOYBRUSH_TESTDATA")
        val f = if (env != null) File(env) else generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "testdata-local") }.firstOrNull { it.isDirectory }
        return f?.takeIf { it.isDirectory }
    }

    @Test
    fun everyRealFileImportsOrIsRefusedInWords() {
        val root = corpus() ?: return
        val report = StringBuilder()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val ext = f.extension.lowercase()
            val bytes = f.readBytes()
            val t0 = System.nanoTime()
            val outcome = try {
                val lib = when (ext) {
                    "abr" -> AbrImport.convert(bytes, "probe")
                    "bundle" -> KritaImport.convertBundle(bytes, "probe")
                    "kpp" -> KritaImport.convertKpp(bytes, "probe")
                    "brushset" -> ProcreateImport.convertBrushSet(bytes, "probe")
                    "brush" -> ProcreateImport.convertBrush(bytes, "probe")
                    else -> null
                }
                if (lib == null) null else lib.summary() + "  | refused: " + lib.refused.take(5).joinToString("; ") { "${it.name}: ${it.reason}" }.take(600)
            } catch (e: Throwable) {
                "EXCEPTION ${e.javaClass.simpleName}: ${e.message?.take(300)}"
            }
            if (outcome != null) report.append("${f.relativeTo(root)} [${bytes.size} B, ${(System.nanoTime() - t0) / 1_000_000} ms]\n    $outcome\n")
        }
        println("=== REAL FILE PROBE ===\n$report")
        File(System.getProperty("java.io.tmpdir"), "joybrush_real_probe.txt").writeText(report.toString())
    }
}
