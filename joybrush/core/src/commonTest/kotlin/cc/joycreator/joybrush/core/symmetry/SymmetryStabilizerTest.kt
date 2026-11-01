package cc.joycreator.joybrush.core.symmetry

import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.input.StabilizerSpec
import cc.joycreator.joybrush.core.input.StrokeStabilizer
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A symmetry lane smooths the way its recording replays, or editing the line later changes its shape. */
class SymmetryStabilizerTest {
    private val tip = HardcodedTip(sizePx = 20f, minSizeFraction = 0.2f, flow = 1f, spacing = 0.1f, cap = 1f, followDirection = false)
    private val samples = (0..60).map { i -> PenSample(i * 6f, 40f * sin(i * 0.4f), i * 8.0, pressure = 0.7f) }

    private fun released(spec: StabilizerSpec?): List<PenSample> {
        val guide = Guide.Symmetry(Pt(0.0, -500.0), Pt(0.0, 500.0), count = 2)
        val lane = SymmetryLanes.build(guide, LaneBrush.Hardcoded(tip), SymmetryFamily.STAMP, 42L, 0.35f, 1f, spec).lanes[0]
        return samples.flatMap { lane.feed(it) }
    }

    @Test fun aLaneOfABrushFileSmoothsAsItsRecordingReplays() {
        val brush = cc.joycreator.joybrush.core.brush.BrushJson.decode(
            """
            {
              "format": "joybrush.brush", "version": 5, "id": "test.lane", "name": "Lane", "engine": "stamp",
              "tip": { "corner": 2 }, "size": { "base": 20 }, "flow": { "base": 1 }, "opacity": { "base": 1 },
              "spacing": 0.1, "accumulate": "wash", "blend": "normal", "smoothing": 0.35, "license": "CC0"
            }
            """.trimIndent() + "\n",
        )
        val spec = StabilizerSpec(StabilizerMode.movingAverage, strength = 1f, windowSize = 8)
        val guide = Guide.Symmetry(Pt(0.0, -500.0), Pt(0.0, 500.0), count = 2)
        fun run(s: StabilizerSpec?): List<PenSample> {
            val lane = SymmetryLanes.build(guide, LaneBrush.File(brush), SymmetryFamily.STAMP, 42L, 0.35f, 1f, s).lanes[0]
            return samples.flatMap { lane.feed(it) }
        }
        val replay = StrokeStabilizer(spec, 0.35f, 1f)
        val want = samples.flatMap { replay.add(brush.response?.apply(it) ?: it) }
        assertEquals(want, run(spec))
        assertNotEquals(run(null), run(spec), "the stabilizer changes the path, so it has to be the same one both ways")
    }

    @Test fun withNoneChosenALaneSmoothsAsItAlwaysDid() {
        val replay = StrokeStabilizer(null, 0.35f, 1f)
        assertEquals(samples.flatMap { replay.add(it) }, released(null))
    }
}
