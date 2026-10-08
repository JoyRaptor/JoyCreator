package cc.joycreator.joybrush.core.bench

import cc.joycreator.joybrush.core.vector.InkTiles
import java.io.File
import kotlin.test.*

class R51MeasurementTest {
    @Test fun frameRunnerUsesLandedRendererAndProducesPremultipliedTiles() {
        val records = R51Fixtures.lines(1)
        val prepared = InkTiles.prepare(records) { R51Fixtures.pen }
        val tiles = R51Measurement.renderFrame(prepared)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.size == 256 * 256 * 4 })
        for (tile in tiles) for (i in tile.indices step 4) {
            val a = tile[i + 3].toInt() and 255
            assertTrue((0..2).all { (tile[i + it].toInt() and 255) <= a })
        }
        assertEquals(0, R51Measurement.renderFrame(InkTiles.prepare(emptyList()) { null }).size)
    }

    @Test fun optInDesktopMeasurements() {
        // Keep normal CI fast. An explicit environment path opts in and receives progress/results.
        val path = System.getenv("JOYBRUSH_R51_REPORT") ?: return
        R51Measurement.run(File(path))
        assertTrue(File(path).readText().endsWith("COMPLETE\n"))
    }
}
