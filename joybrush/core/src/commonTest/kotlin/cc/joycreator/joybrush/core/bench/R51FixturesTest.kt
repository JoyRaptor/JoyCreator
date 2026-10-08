package cc.joycreator.joybrush.core.bench

import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.vector.InkReplay
import cc.joycreator.joybrush.core.fill.FloodFill
import cc.joycreator.joybrush.core.fill.FillOptions
import kotlin.test.*

class R51FixturesTest {
    @Test fun prefixesAndSensorSamplesAreReproducible() {
        val small = R51Fixtures.lines(50)
        assertEquals(small, R51Fixtures.lines(500).take(50))
        assertEquals(50, small.map { it.id }.toSet().size)
        assertEquals(1650, small.sumOf { it.samples.size })
        assertTrue(small.flatMap { it.samples }.all { it.isPlaceable && !it.predicted })
        assertTrue(small.flatMap { it.samples }.map { it.pressure }.distinct().size > 10)
    }

    @Test fun fixturesUseActualCodecAndReplayableBrushes() {
        for (brush in listOf(R51Fixtures.pen, R51Fixtures.pencil, R51Fixtures.paint)) {
            val record = R51Fixtures.lines(1, brush).single()
            assertNull(InkReplay.refusal(record, brush))
            assertTrue(InkReplay.dabs(record, brush).isNotEmpty())
            val bytes = StrokeCodec.encode(record)
            assertContentEquals(bytes, StrokeCodec.encode(StrokeCodec.decode(bytes)))
        }
    }

    @Test fun paintedFramesOverlapAndCarryDifferentColors() {
        val frame = R51Fixtures.painted(500)
        assertEquals(4, frame.map { it.colorArgb }.toSet().size)
        assertTrue(frame.all { it.brushId == R51Fixtures.paint.id })
        assertTrue(frame.flatMap { it.samples }.all {
            it.x in 0f..512f && it.y in 0f..512f
        })
    }

    @Test fun denseReferenceHasOpaqueWallsWhiteChannelsAndDeterministicBytes() {
        val ref = R51Fixtures.denseLineArt(96)
        assertContentEquals(ref, R51Fixtures.denseLineArt(96))
        assertTrue((3 until ref.size step 4).all { (ref[it].toInt() and 255) == 255 })
        assertEquals(255, ref[(8 * 96 + 8) * 4].toInt() and 255)
        assertEquals(0, ref[0].toInt())
        assertTrue((0 until ref.size step 4).count { ref[it] == 0.toByte() } > 96)
    }

    @Test fun denseFillActuallyExercisesALargeConnectedRegionWithHoles() {
        val ref = R51Fixtures.denseLineArt(96)
        val region = FloodFill.fill(96, 96, ref, 8, 8,
            FillOptions(tolerance = 0f, gapClosePx = 1, grow = 0))
        val covered = region.count { it != 0.toByte() }
        assertTrue(covered > 96 * 96 / 2, "covered=$covered")
        assertEquals(0, region[10 * 96 + 21].toInt(), "black islands remain holes")
        assertEquals(0, region[0].toInt(), "outer wall excludes background")
    }
}
