package cc.joycreator.joybrush.core.brush

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Brush version 9 (VECTOR_RENDER_DESIGN H6 and §3): the `line` section and the `multiply` / `screen` blends.
 *
 * The Lead's condition for this row: every brush file that exists today decodes EXACTLY as it did, with no `line` and its
 * old blend word, and survives a write-and-read unchanged. That is checked here on every shipped `brush.json`, so a
 * shipped brush cannot quietly start tapering or change version because of this section.
 */
class LineFormatTest {

    private fun shipped(): List<Pair<String, String>> {
        val root = File(joybrushRoot(), "brushes")
        val files = root.walkTopDown().filter { it.name == "brush.json" }.sortedBy { it.path }.toList()
        assertTrue(files.size >= 9, "the shipped brushes are found (found ${files.size})")
        return files.map { it.relativeTo(root).path to it.readText() }
    }

    @Test
    fun everyShippedBrushDecodesAsBeforeAndRoundTrips() {
        for ((name, json) in shipped()) {
            val p = BrushJson.decode(json)
            assertNull(p.line, "$name: an older brush has no line section")
            assertTrue(p.blend in setOf("normal", "erase", BLEND_BEHIND), "$name: blend word unchanged (${p.blend})")
            assertTrue(p.version < VERSION_LINES, "$name: shipped as version ${p.version}")
            val again = BrushJson.decode(BrushJson.encode(p))
            assertEquals(p, again, "$name: write then read gives the same brush")
            assertEquals(p.version, again.version, "$name: re-writing does not raise the version")
        }
    }

    @Test
    fun aLineBrushRoundTripsAsVersionNine() {
        val p = BrushPreset(id = "pen", name = "Pen", size = Param(6f), version = 1, blend = BLEND_MULTIPLY,
            line = LineStyleSpec(taperIn = 2f, taperOut = 4.5f, taperShape = 2f, dashOn = 3f, dashOff = 1.5f))
        val json = BrushJson.encode(p)
        assertTrue("\"line\"" in json && "\"taperOut\": 4.5" in json, json)
        val back = BrushJson.decode(json)
        assertEquals(VERSION_LINES, back.version, "written with the lowest version that can say it")
        assertEquals(p.copy(version = VERSION_LINES), back)
    }

    @Test
    fun anOlderVersionCannotUseTheNewWords() {
        // A version-8 build would draw these as a plain pen, which is the mistake the number exists to stop.
        val withLine = BrushJson.encode(BrushPreset(id = "a", name = "A", size = Param(4f), line = LineStyleSpec(taperIn = 1f)))
            .replace("\"version\": $VERSION_LINES", "\"version\": 8")
        assertTrue("line needs brush version $VERSION_LINES" in assertFailsWith<BrushException> { BrushJson.decode(withLine) }.message!!)
        val multiply = BrushJson.encode(BrushPreset(id = "b", name = "B", size = Param(4f), blend = BLEND_SCREEN))
            .replace("\"version\": $VERSION_LINES", "\"version\": 8")
        assertTrue("blend \"screen\" needs brush version" in assertFailsWith<BrushException> { BrushJson.decode(multiply) }.message!!)
    }

    @Test
    fun validationNamesEachBadLineNumberAndAGlazeOffAStampBrush() {
        val bad = BrushPreset(id = "c", name = "C", size = Param(4f),
            line = LineStyleSpec(taperIn = Float.NaN, taperTip = 2f, taperShape = 0f, dashOff = -1f, screenWidthPx = 100f))
        val problems = BrushValidate.validate(bad).joinToString("; ")
        for (field in listOf("line.taperIn", "line.taperTip", "line.taperShape", "line.dashOff", "line.screenWidthPx")) {
            assertTrue(field in problems, "$field is named: $problems")
        }
        val smudgeGlaze = BrushValidate.validate(BrushPreset(id = "d", name = "D", size = Param(4f), engine = ENGINE_SMUDGE,
            blend = BLEND_MULTIPLY)).joinToString("; ")
        assertTrue("works only on a stamp brush" in smudgeGlaze, smudgeGlaze)
        assertTrue(BrushValidate.validate(BrushPreset(id = "e", name = "E", size = Param(4f), blend = BLEND_MULTIPLY,
            line = LineStyleSpec(taperIn = 1f, taperOut = 2f))).isEmpty(), "a good line brush has no problems")
    }
}
