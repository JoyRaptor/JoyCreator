package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.brush.MediaPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Just-works sweep: a packaged brush that cannot load is counted and said, not just dropped with a log line. */
class BrushLibraryTest {
    private val good = BrushLibraryTest::class.java.getResourceAsStream("/joybrush/brushes/ink/brush.json")!!
        .bufferedReader().use { it.readText() }

    private fun load(files: Map<String, String?>, warnings: MutableList<String> = mutableListOf()) =
        BrushLibrary.load(files.keys.toList(), { path -> files[path.removeSuffix("/brush.json")] }, warnings::add)

    @Test fun everyShippedBrushThatIsListedLoadsAndNoneAreSkipped() {
        val names = BrushLibraryTest::class.java.getResourceAsStream("/joybrush/brushes/index.txt")!!.bufferedReader().use { it.readText() }
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val warnings = mutableListOf<String>()
        val loaded = BrushLibrary.load(names, { path ->
            BrushLibraryTest::class.java.getResourceAsStream("/joybrush/brushes/$path")?.bufferedReader()?.use { it.readText() }
        }, warnings::add)
        assertEquals(emptyList(), loaded.skipped, "a shipped brush that no longer loads must fail this test, not vanish from the drawer")
        assertEquals(emptyList(), warnings)
        assertEquals(names.size + MediaPresets.ALL.size, loaded.brushes.size)
    }

    @Test fun aBrushThatWillNotDecodeOrValidateOrIsMissingIsCountedLoggedAndTheRestStillLoad() {
        val warnings = mutableListOf<String>()
        val fromTheFuture = good.replace("\"version\": 6", "\"version\": 999")
        assertTrue(fromTheFuture != good, "the fixture really is changed")
        val loaded = load(linkedMapOf("ink" to good, "garbled" to "{ not json", "nofile" to null, "future" to fromTheFuture), warnings)
        assertEquals(listOf("garbled", "nofile", "future"), loaded.skipped)
        assertEquals(listOf("joybrush.ink"), loaded.brushes.map { it.id }.filter { it == "joybrush.ink" })
        assertTrue(warnings.any { it.startsWith("brush garbled will not decode") })
        assertTrue(warnings.any { it == "brush nofile has no packaged brush.json" })
        assertTrue(warnings.any { it.startsWith("brush future is not usable") })
        val summary = warnings.last()
        assertTrue(summary.startsWith("built-in brushes could not be loaded") && "(3)" in summary && "garbled" in summary && "nofile" in summary, summary)
        assertEquals(1, warnings.count { it.contains("could not be loaded") }, "the count is logged once, not per brush")
        assertTrue(MediaPresets.ALL.all { m -> loaded.brushes.any { it.id == m.id } }, "media presets are still appended")
    }

    @Test fun theSentenceNamesHowManyAndIsAbsentWhenNothingIsLost() {
        assertNull(BrushLibrary.sentenceFor(0))
        assertEquals("1 brush could not be loaded.", BrushLibrary.sentenceFor(1))
        assertEquals("3 brushes could not be loaded.", BrushLibrary.sentenceFor(3))
    }
}
