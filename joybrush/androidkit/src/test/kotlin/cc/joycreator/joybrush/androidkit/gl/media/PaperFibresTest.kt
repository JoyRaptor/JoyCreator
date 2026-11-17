package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.MediaPaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paper fibres ([PaperFibres], owner 2026-11-17): what can be said without a graphics chip.
 * - Which papers bleed at all: the sheets sized as watercolour paper is keep the crisp edge they had.
 * - The switch off is the old water: everything the fibres added to the water's two passes and to the paper's bake lies
 *   inside `#ifdef JB_WET_FIBRES`, so a build without the define compiles none of it.
 * What it looks like is the Note 9's to say (tasks/jobs/ATLAS-SOLID_never_refuse.md, "11-17: paper fibres").
 */
class PaperFibresTest {
    private fun paper(absorbency: Double, sizing: Double) =
        MediaPaper(DoubleArray(256).also { it[128] = 1.0 }, toothMm = 0.05, compliance = 0.3, sizing = sizing, absorbency = absorbency)

    @Test fun unsizedPapersBleedAndSizedOnesDoNot() {
        // The catalogue's rows (joybrush/assets/paper/catalogue.json).
        assertEquals(0.84, PaperFibres.thirst(paper(0.9, 0.05)), 0.01)     // rice
        assertEquals(0.43, PaperFibres.thirst(paper(0.65, 0.25)), 0.01)    // sugarcane, thai
        assertEquals(0.36, PaperFibres.thirst(paper(0.6, 0.3)), 0.01)      // cardboard
        assertEquals(0.11, PaperFibres.thirst(paper(0.5, 0.6)), 0.01)      // drawing paper
        assertEquals(0.0, PaperFibres.thirst(paper(0.4, 0.8)))             // cold press
        assertEquals(0.0, PaperFibres.thirst(paper(0.35, 0.85)))           // hot press
        assertEquals(0.0, PaperFibres.thirst(paper(0.3, 0.85)))            // bristol
        assertEquals(0.0, PaperFibres.thirst(paper(0.0, 1.0)))             // chalk ground
        assertTrue(PaperFibres.thirst(paper(1.0, 0.0)) <= 1.0)
    }

    /** [text] as a build WITHOUT JB_WET_FIBRES compiles it: the fibre blocks out, their `#else` halves kept. */
    private fun plain(text: String): String {
        val out = StringBuilder()
        val open = ArrayList<String>()   // per open conditional: "fibre", "else" (the other half of a fibre block) or "other"
        for (line in text.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("#if") -> { open += if (t == "#ifdef JB_WET_FIBRES") "fibre" else "other"; if (open.last() == "other" && "fibre" !in open) out.appendLine(line) }
                t.startsWith("#else") && open.lastOrNull() in setOf("fibre", "else") -> open[open.size - 1] = "else"
                t.startsWith("#endif") && open.isNotEmpty() -> { val was = open.removeAt(open.size - 1); if (was == "other" && "fibre" !in open) out.appendLine(line) }
                "fibre" !in open -> out.appendLine(line)
            }
        }
        return out.toString()
    }

    @Test fun theSwitchOffCompilesNoneOfIt() {
        val shaders = ShaderLibrary()
        val own = Regex("""\b(u_bleed\w*|u_fibre\w*|u_pore|jb_film\w*|inRun|keepP|bled)\b""")
        for (name in listOf("media/jb_wet_flux.frag", "media/jb_wet_update.frag", "media/jb_media_bake.frag")) {
            val text = shaders.source(name)
            assertTrue(text.contains("#ifdef JB_WET_FIBRES"), "$name has no fibre build")
            val code = plain(text).lineSequence().map { it.substringBefore("//") }.joinToString("\n")
            val left = own.find(code)
            assertFalse(left != null, "$name: ${left?.value} is compiled with the switch off")
            // And the fibre build does use what the engine sends it.
            if (name != "media/jb_media_bake.frag") assertTrue(text.contains("uniform float u_bleed;"), "$name does not read the paper's pull")
        }
    }

    @Test fun everyTuningNumberTheEngineSendsIsReadByAPass() {
        val shaders = ShaderLibrary()
        val text = listOf("media/jb_wet_flux.frag", "media/jb_wet_update.frag", "media/jb_media_bake.frag").joinToString("\n") { shaders.source(it) }
        for (name in PaperFibres.tune.keys) {
            val uniform = if (name.startsWith("fibre")) "u_$name" else "u_bleed" + name.replaceFirstChar { it.uppercase() }
            assertTrue(Regex("""uniform\s+float\s+$uniform\s*;""").containsMatchIn(text), "no pass declares $uniform ($name)")
        }
    }
}
