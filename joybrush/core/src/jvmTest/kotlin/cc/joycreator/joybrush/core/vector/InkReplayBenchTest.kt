package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.bench.Bench
import cc.joycreator.joybrush.core.bench.BenchCase
import cc.joycreator.joybrush.core.bench.BenchOutcome
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.joybrushRoot
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * B1 of VECTOR_RENDER_DESIGN §6: how many dabs per second the CPU replay makes, per shipped line brush.
 *
 * The design's one real risk is rebuilding a dense tile: every line crossing it is replayed from its samples. This
 * measures that cost on the desktop JVM as a PROXY. The phone figure is expected to be several times slower and is
 * measured separately (B2/B3). Following [cc.joycreator.joybrush.core.bench.BenchTest]'s rule, no wall-clock figure is
 * asserted: timings are REPORTED. What is asserted is that each case really replays a full-length line (dab counts),
 * so a bench that silently timed an empty list cannot pass.
 */
class InkReplayBenchTest {

    private fun preset(folder: String): BrushPreset =
        BrushJson.decode(File(joybrushRoot(), "brushes/$folder/brush.json").readText())

    /**
     * An S-curve about 1,500 doc px long, drawn in 1.5 s at 240 samples/s: the "one line across an A5 page" case the
     * design's cost table uses. Pressure swells and fades; tilt and azimuth are present, as from an S Pen.
     */
    private fun sCurve(): StrokeRecord {
        val n = 360
        val samples = List(n + 1) { i ->
            val t = i.toFloat() / n
            PenSample(
                x = 100f + 1200f * t,
                y = 400f + 150f * sin(2.0 * PI * 1.5 * t).toFloat(),
                timeMs = 1500.0 * t,
                pressure = 0.25f + 0.65f * sin(PI * t).toFloat(),
                tilt = 0.6f,
                azimuth = 1.0f,
            )
        }
        return StrokeRecord("bench", "bench", seed = 0x5EEDL, smoothing = 0.35f, screenPerDoc = 1f, samples = samples)
    }

    private fun pathLength(r: StrokeRecord): Float =
        r.samples.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()

    @Test
    fun replayThroughputPerLineBrush() {
        val record = sCurve()
        val length = pathLength(record)
        assertTrue(length in 1300f..1800f, "the fixture is the 1,500 px line the cost table assumes, was $length")

        val lines = mutableListOf<String>()
        val outcomes = mutableListOf<BenchOutcome>()
        for (folder in listOf("ink", "pencil", "marker")) {
            val brush = preset(folder)
            val count = InkReplay.dabs(record, brush).size
            // Non-vacuous: a 1,500 px line at a spacing floor of 0.5 px is hundreds to thousands of dabs, never a handful.
            assertTrue(count > 200, "$folder replayed only $count dabs")
            val case = BenchCase("replay-$folder") { InkReplay.dabs(record, brush).size.toLong() }
            val m = Bench.measure(case, warmup = 20, repeats = 40, nowMs = { System.nanoTime() / 1_000L })
            // nowMs is fed MICROseconds so sub-millisecond medians are not rounded to 0; converted back below.
            val medianMs = Bench.median(m.samplesMs) / 1000.0
            val p95Ms = Bench.p95(m.samplesMs) / 1000.0
            outcomes.add(m)
            lines.add(
                "B1 replay %-7s dabs=%5d median=%.3f ms p95=%.3f ms → %.2f M dabs/s".format(
                    folder, count, medianMs, p95Ms, count / medianMs / 1000.0,
                ),
            )
        }

        // The shaping post-pass on the ink line: taper, dashes and densify at 8×, as the sharp levels would run it.
        val ink = preset("ink")
        val dabs = InkReplay.dabs(record, ink)
        val style = LineStyle(taperIn = 3f, taperOut = 5f, dashOn = 4f, dashOff = 2f)
        val shapeCase = BenchCase("shape-ink") {
            val shaped = LineShaper.dash(LineShaper.taper(dabs, style, ink.size.base), style, ink.size.base)
            LineShaper.densify(shaped, 8f).size.toLong()
        }
        val sm = Bench.measure(shapeCase, warmup = 20, repeats = 40, nowMs = { System.nanoTime() / 1_000L })
        lines.add(
            "B1 shape   ink     dabs=%5d median=%.3f ms (taper + dash + densify@8×)".format(
                dabs.size, Bench.median(sm.samplesMs) / 1000.0,
            ),
        )

        lines.forEach(::println)
        println("B1 note: desktop JVM, microsecond ticks, allocation included; the phone is expected to be several × slower.")
    }
}
