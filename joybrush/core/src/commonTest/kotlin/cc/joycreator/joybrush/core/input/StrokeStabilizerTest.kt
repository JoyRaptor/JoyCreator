package cc.joycreator.joybrush.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DRAFT — not compiled or tested. Parent must add codec/live-replay integration cases. */
class StrokeStabilizerTest {
    @Test fun persistedModeCodesAndWordsAreAppendOnly() {
        assertEquals(listOf("off" to 0,"smooth" to 1,"pulledString" to 2,"movingAverage" to 3,"catchUpOnLift" to 4),
            StabilizerMode.entries.map { it.name to it.code })
        assertEquals(listOf("brushSuggestion","alwaysMine"),StabilizerPolicy.entries.map { it.name })
    }

    private val path = (0..45).map { i -> PenSample(i * 3f, ((i % 4) - 2) * 2f, i * 7.0,
        pressure = (i % 10) / 10f, tilt = if (i % 4 == 0) Float.NaN else .2f,
        azimuth = 3f, barrel = -3f) }
    private fun bits(s: PenSample): List<Long> = listOf(s.x.toRawBits().toLong(), s.y.toRawBits().toLong(),
        s.timeMs.toRawBits(), s.pressure.toRawBits().toLong(), s.tilt.toRawBits().toLong(),
        s.azimuth.toRawBits().toLong(), s.barrel.toRawBits().toLong(), s.tool.ordinal.toLong())
    private fun replay(spec: StabilizerSpec?, samples: List<PenSample> = path, zoom: Float = 1f): List<PenSample> {
        val s = StrokeStabilizer(spec, .65f, zoom)
        return buildList { samples.forEach { addAll(s.add(it)) }; addAll(s.finish()) }
    }

    @Test fun nullRetainsExistingSmootherBitsAndExplicitSmoothMatches() {
        for (zoom in listOf(.5f, 1f, 4f)) {
            val old = StrokeSmoother(.65f, zoom)
            val expected = buildList { path.forEach { addAll(old.add(it)) }; addAll(old.finish()) }
            assertEquals(expected.map(::bits), replay(null, zoom = zoom).map(::bits))
            assertEquals(expected.map(::bits), replay(StabilizerSpec(strength = .65f), zoom = zoom).map(::bits))
        }
    }
    @Test fun sinkAndListAreIdenticalInEveryMode() {
        for (mode in StabilizerMode.entries) {
            val spec = StabilizerSpec(mode, .7f, 18f, 5)
            val s = StrokeStabilizer(spec, 0f)
            val streamed = buildList { path.chunked(7).forEach { chunk -> chunk.forEach { s.addTo(it, ::add) } }; s.finishTo(::add) }
            assertEquals(replay(spec).map(::bits), streamed.map(::bits), mode.name)
            assertTrue(s.finish().isEmpty())
            assertFailsWith<IllegalStateException> { s.add(path[0]) }
        }
    }
    @Test fun offPreservesAllRawBitsAndNewSpatialModesKeepChannels() {
        val odd = path + PenSample(-0f, 0f, 400.0, Float.fromBits(0x7fc00042), Float.fromBits(0x7fc00017))
        assertEquals(odd.map(::bits), replay(StabilizerSpec(StabilizerMode.off), odd).map(::bits))
        for (mode in listOf(StabilizerMode.pulledString, StabilizerMode.movingAverage, StabilizerMode.catchUpOnLift)) {
            val result = replay(StabilizerSpec(mode))
            assertEquals(path.size, result.take(path.size).size)
            path.indices.forEach { i -> assertEquals(bits(path[i]).drop(2), bits(result[i]).drop(2)) }
        }
    }
    @Test fun radiusUsesContactZoomAndCatchUpEndsExactlyAtLift() {
        val straight = listOf(PenSample(0f, 0f, 0.0), PenSample(100f, 0f, 20.0))
        val rope = StabilizerSpec(StabilizerMode.pulledString, 1f, 20f)
        assertEquals(80f, replay(rope, straight, 1f).last().x)
        assertEquals(90f, replay(rope, straight, 2f).last().x)
        val caught = replay(rope.copy(mode = StabilizerMode.catchUpOnLift), straight)
        assertEquals(3, caught.size)
        assertEquals(bits(straight.last()), bits(caught.last()))
    }
    @Test fun missingAndPredictedPointsCannotPoisonOrMoveTheEnd() {
        for (mode in StabilizerMode.entries) {
            val spec = StabilizerSpec(mode)
            val clean = replay(spec)
            val s = StrokeStabilizer(spec, 0f)
            val dirty = buildList {
                path.forEachIndexed { i, raw ->
                    if (i == 10) { addAll(s.add(raw.copy(x = Float.NaN))); addAll(s.add(raw.copy(predicted = true))) }
                    addAll(s.add(raw))
                }
                addAll(s.finish())
            }
            assertEquals(clean.map(::bits), dirty.map(::bits), mode.name)
            assertEquals(1, s.droppedSamples)
        }
    }
    @Test fun movingAverageIsBoundedAndStrengthZeroIsIdentity() {
        val samples = listOf(PenSample(0f, 0f, 0.0), PenSample(10f, 0f, 1.0), PenSample(20f, 0f, 2.0))
        val spec = StabilizerSpec(StabilizerMode.movingAverage, 1f, windowSize = 2)
        assertEquals(listOf(0f, 5f, 15f), replay(spec, samples).map { it.x })
        for (mode in StabilizerMode.entries.filter { it != StabilizerMode.smooth })
            assertEquals(samples.map(::bits), replay(spec.copy(mode = mode, strength = 0f), samples).map(::bits))
    }
    @Test fun userPolicyOverridesBrushSuggestionOnlyWhenSelected() {
        val suggested = StabilizerSpec(StabilizerMode.off)
        val mine = StabilizerSpec(StabilizerMode.pulledString, .9f)
        assertEquals(suggested, StabilizerPreference(mine = mine).resolve(suggested, .2f))
        assertEquals(mine, StabilizerPreference(StabilizerPolicy.alwaysMine, mine).resolve(suggested, .2f))
        assertEquals(.2f, StabilizerPreference().resolve(null, .2f).strength)
        assertEquals(StabilizerMode.smooth, StabilizerPreference().resolve(null, .2f).mode)
    }
    @Test fun newSettingsValidateBeforeAnyWindowAllocation() {
        for (bad in listOf(StabilizerSpec(strength = Float.NaN), StabilizerSpec(radiusPx = Float.POSITIVE_INFINITY),
            StabilizerSpec(windowSize = 0), StabilizerSpec(windowSize = Int.MAX_VALUE)))
            assertFailsWith<IllegalArgumentException> { StrokeStabilizer(bad, 0f) }
        val negativeZero = PenSample(-0f, -0f, 0.0)
        for (mode in StabilizerMode.entries.filter { it != StabilizerMode.smooth })
            assertEquals(listOf(bits(negativeZero)), replay(StabilizerSpec(mode, strength = 0f), listOf(negativeZero)).map(::bits))
    }
}
