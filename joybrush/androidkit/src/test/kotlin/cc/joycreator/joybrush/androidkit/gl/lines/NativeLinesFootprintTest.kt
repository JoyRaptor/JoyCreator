package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.floor
import kotlin.test.*

/**
 * Editable lines v1.2 H6: the CPU footprint comes from the SAMPLES (each box widened by R = 2 * size * widthScale + scatter
 * reach + 16 px), never from replaying the dabs. It must still be a SUPERSET of every tile the replayed dabs can reach.
 */
class NativeLinesFootprintTest {
    private fun stroke(widthScale: Float = 1f, points: List<Pair<Float, Float>> = listOf(300f to 40f, 360f to 90f, 420f to 150f, 470f to 260f)) =
        StrokeRecord("s", "pen", 12345L, 0.3f, 1f,
            points.mapIndexed { i, (x, y) -> PenSample(x, y, 16.0 * i, pressure = 0.4f + 0.2f * i) },
            widthScale = widthScale, seq = 1)

    /** The oracle: every tile the replayed dabs reach, by their full tip extent, padded by the old 8 px. */
    private fun replayTiles(record: StrokeRecord, preset: BrushPreset): Set<Long> {
        val size = Tiles.SIZE.toFloat()
        val out = HashSet<Long>()
        for (d in InkReplay.replay(record, preset).dabs) {
            val r = TipMath.extent(d.radius, d.anchor) + 8f
            for (ty in floor((d.y - r) / size).toInt()..floor((d.y + r) / size).toInt())
                for (tx in floor((d.x - r) / size).toInt()..floor((d.x + r) / size).toInt()) out.add(Tiles.key(tx, ty))
        }
        return out
    }

    private fun pen(size: Float, scatter: ScatterSpec = ScatterSpec(), jitter: Float = 0f) =
        BrushPreset(id = "pen", name = "Pen", size = Param(size), scatter = scatter, sizeJitter = jitter)

    @Test fun theSampleFootprintCoversEveryTileTheReplayedDabsReach() {
        for (size in listOf(2f, 8f, 40f, 160f)) for (scale in listOf(0.5f, 1f, 3f)) {
            val preset = pen(size, jitter = 0.3f)
            val record = stroke(widthScale = scale)
            val truth = replayTiles(record, preset)
            val cpu = NativeLines.cpuFootprint(record, preset)
            assertTrue(cpu.containsAll(truth), "size $size x $scale: missing ${truth - cpu}")
        }
    }

    @Test fun scatterWidensTheReachAndStillCoversTheCopies() {
        val preset = pen(20f, ScatterSpec(amount = Param(1.5f), count = 4, bothAxes = true))
        val record = stroke()
        val truth = replayTiles(record, preset)
        val cpu = NativeLines.cpuFootprint(record, preset)
        assertTrue(cpu.containsAll(truth), "scatter copies are inside: missing ${truth - cpu}")
        assertTrue(NativeLines.reachPx(record, preset) > NativeLines.reachPx(record, pen(20f)), "scatter adds reach")
    }

    @Test fun theReachIsTwiceTheSizeScaledByTheWidthPlusSixteen() {
        assertEquals(2f * 10f + 16f, NativeLines.reachPx(stroke(), pen(10f)))
        assertEquals(2f * 10f * 2.5f + 16f, NativeLines.reachPx(stroke(widthScale = 2.5f), pen(10f)))
        assertEquals(2f * 10f * 2.5f + 16f, NativeLines.reachPx(stroke(widthScale = -2.5f), pen(10f)), "only the size of a scale counts")
        assertEquals(2f * 10f + 16f, NativeLines.reachPx(stroke(widthScale = Float.NaN), pen(10f)), "a broken scale is read as 1")
    }

    @Test fun aWatercolourBrushReachesAsFarAsItsWashAndPools() {
        val wc = WatercolourSpec(size = 300.0, scatter = 0.36, gravityWidth = 36.0, edgeWidth = 3.0)
        val preset = BrushPreset(id = "wc", name = "Wash", engine = ENGINE_WATERCOLOUR, size = Param(40f), watercolour = wc)
        val r = NativeLines.reachPx(stroke(), preset)
        assertTrue(r >= 2f * 300f + 36f + 3f + 16f, "reach $r covers the wash size, its pooling and the pad")
    }

    @Test fun aLineIsOnTheTilesAroundItsSamplesAndNotFarFromThem() {
        val record = stroke(points = listOf(10f to 10f, 20f to 12f))
        val tiles = NativeLines.cpuFootprint(record, pen(8f))
        assertTrue(Tiles.key(0, 0) in tiles)
        assertTrue(Tiles.key(-1, -1) in tiles, "a pad past the origin reaches the neighbour")
        assertFalse(Tiles.key(3, 3) in tiles)
        assertTrue(tiles.size <= 9)
    }

    @Test fun nothingToDrawOrNoBrushIsEmptyAndBrokenSamplesAreSkipped() {
        assertTrue(NativeLines.cpuFootprint(stroke(), null).isEmpty())
        assertTrue(NativeLines.cpuFootprint(stroke().copy(samples = emptyList()), pen(8f)).isEmpty())
        val broken = stroke().copy(samples = listOf(PenSample(Float.NaN, 5f, 0.0), PenSample(100f, 100f, 8.0)))
        assertEquals(setOf(Tiles.key(0, 0)), NativeLines.cpuFootprint(broken, pen(8f)))
    }

    @Test fun aLongStrokeCostsOnePassOverItsSamplesAndNoReplay() {
        val points = (0 until 5000).map { i -> (i * 3f) to (100f + 50f * kotlin.math.sin(i / 40f)) }
        val record = stroke(points = points)
        val started = System.nanoTime()
        val tiles = NativeLines.cpuFootprint(record, pen(12f))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(tiles.size in 50..200, "tiles ${tiles.size}")
        assertTrue(ms < 500, "took $ms ms")
    }

    @Test fun theStoreUsesTheSampleFootprintByDefault() {
        val lines = NativeLines()
        lines.addBrushes(mapOf("pen" to pen(8f)))
        val plane = LinePlane("paint", null)
        lines.put(plane, "s", stroke(points = listOf(300f to 40f, 330f to 40f)))
        assertTrue(lines.isCrossed(plane, Tiles.key(1, 0)))
        assertEquals(lines.footprint(plane, "s"), NativeLines.cpuFootprint(stroke(points = listOf(300f to 40f, 330f to 40f)), pen(8f)))
    }

    @Test fun aFastSparseStrokeCoversTheTilesBetweenItsReports() {
        // Two reports 3 tiles apart with a thin pen: the straight path crosses (1,0), (1,1) and (2,1) between them, which
        // no box round either report reaches; the replay draws there, so the footprint must hold them.
        val record = stroke(points = listOf(200f to 200f, 950f to 500f))
        val preset = pen(4f)
        val tiles = NativeLines.cpuFootprint(record, preset)
        for (t in replayTiles(record, preset)) assertTrue(t in tiles, "tile ${Tiles.tx(t)},${Tiles.ty(t)} the replay reaches")
        assertTrue(Tiles.key(1, 0) in tiles && Tiles.key(2, 1) in tiles)
        assertFalse(Tiles.key(0, 3) in tiles, "nowhere near the path")
    }
}
