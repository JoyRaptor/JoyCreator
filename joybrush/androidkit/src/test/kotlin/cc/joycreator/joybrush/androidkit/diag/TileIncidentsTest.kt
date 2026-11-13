package cc.joycreator.joybrush.androidkit.diag

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileIncidentsTest {
    private fun withFile(block: (File) -> Unit) {
        val f = File.createTempFile("jb-incidents", ".log")
        val hadFile = TileIncidents.file; val hadContext = TileIncidents.context
        try {
            f.delete()
            TileIncidents.file = f
            block(f)
        } finally {
            TileIncidents.file = hadFile; TileIncidents.context = hadContext
            f.delete()
        }
    }

    @Test fun anIncidentIsKeptWithWhenItHappenedAndWhatTheEngineHeld() = withFile { f ->
        TileIncidents.context = { "paint room 12 of 315 MB" }
        val before = TileIncidents.count
        TileIncidents.e("layer a: tile texture 7 lost\n(framebuffer status 36054)", IllegalStateException("no memory"))
        TileIncidents.e("a second one")
        val lines = f.readLines()
        assertEquals(2, lines.size, "one line an incident, whatever the message held")
        assertEquals(before + 2, TileIncidents.count)
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\| ").containsMatchIn(lines[0]), lines[0])
        assertTrue("tile texture 7 lost (framebuffer status 36054)" in lines[0])
        assertTrue("paint room 12 of 315 MB" in lines[0] && "IllegalStateException: no memory" in lines[0], lines[0])
        assertTrue(" < TileIncidentsTest." in lines[0], "the first frames of where it was thrown: ${lines[0]}")
    }

    @Test fun aContextThatFailsDoesNotLoseTheIncident() = withFile { f ->
        TileIncidents.context = { error("the engine is gone") }
        TileIncidents.e("texture 9 retired twice")
        val line = f.readLines().single()
        assertTrue("texture 9 retired twice" in line && "context failed" in line, line)
    }

    @Test fun theFileStaysSmallAndKeepsWholeLinesOfTheNewest() = withFile { f ->
        TileIncidents.context = null
        val filler = "x".repeat(1000)
        repeat(600) { TileIncidents.e("incident $it $filler") }
        assertTrue(f.length() < 400 * 1024, "past 256 KB the older half goes: ${f.length()} bytes")
        val lines = f.readLines()
        assertTrue(lines.last().contains("incident 599 "), "the newest is kept")
        assertTrue(lines.all { Regex("^\\d{4}-").containsMatchIn(it) }, "no line is cut in two")
    }

    @Test fun withNowhereToKeepThemNothingIsWrittenAndNothingThrows() {
        val had = TileIncidents.file
        try {
            TileIncidents.file = null
            TileIncidents.e("nowhere to go", RuntimeException("x"))
        } finally { TileIncidents.file = had }
    }
}
