package cc.joycreator.joybrush.core.grain

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.DabPlacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JB-1.05c: the pieces between a brush file and the shader — what a dab carries, what a stroke fixes. */
class GrainWiringTest {

    // The shipped pencil's paperGrain block (byte for byte the numbers of brushes/pencil/brush.json).
    private val pencil = BrushJson.decode(
        """{"format":"joybrush.brush","version":1,"id":"joybrush.pencil","name":"Pencil","engine":"stamp",
        "tip":{"corner":2,"aspect":0,"followDirection":false},
        "size":{"base":3,"inputs":[{"input":"pressure","curve":[[0,0.6],[1,1]]}]},
        "opacity":{"base":1,"inputs":[{"input":"pressure","curve":[[0,0.1],[1,0.9]]}]},
        "paperGrain":{"enabled":true,"source":"cloud","image":"cloud_fine_256.png","scale":10,
          "depth":{"base":1,"inputs":[{"input":"pressure","curve":[[0,0.2],[1,0.9]]}]},"edge":0.25,"tiltGradient":0.6},
        "smoothing":0.2,"license":"CC0"}""",
    )

    private fun sample(x: Float, t: Double, pressure: Float = 1f, tilt: Float = Float.NaN, az: Float = Float.NaN) =
        PenSample(x = x, y = 0f, timeMs = t, pressure = pressure, tilt = tilt, azimuth = az)

    @Test
    fun aDabCarriesTheTiltAndAzimuthOfTheSampleItFellOn() {
        val placer = DabPlacer(spacing = 0.5f, radiusOf = { 4f })
        // Dabs fall every 4 px (spacing 0.5 x diameter 8). The second dab is exactly half way between the two
        // samples at x = 0 and x = 8, so tilt 0.2 -> 0.6 gives 0.4 and azimuth 0 -> 1 gives 0.5.
        val dabs = placer.add(listOf(sample(0f, 0.0, tilt = 0.2f, az = 0f), sample(8f, 8.0, tilt = 0.6f, az = 1f)))
        assertEquals(0.2f, dabs[0].tilt, 1e-6f)
        assertEquals(0.4f, dabs[1].tilt, 1e-5f)
        assertEquals(0.5f, dabs[1].azimuth, 1e-5f)
    }

    @Test
    fun aFingerSampleGivesNaNTiltAndTheDabSaysSoRatherThanInventingOne() {
        val placer = DabPlacer(spacing = 0.5f, radiusOf = { 4f })
        val dabs = placer.add(listOf(sample(0f, 0.0), sample(8f, 8.0)))
        assertTrue(dabs.all { it.tilt.isNaN() && it.azimuth.isNaN() })
    }

    @Test
    fun aGrainDepthIsFixedAtTheFirstDabOfTheStroke() {
        val dabber = BrushDabber(pencil, seed = 1L)
        // Before any dab: the file's own base (1.0) — the shader is never handed an unset value.
        assertEquals(1f, dabber.strokeGrain.paper.depth)
        // First dab at pressure 0.5: the curve [[0,0.2],[1,0.9]] gives 0.2 + 0.5 * 0.7 = 0.55.
        dabber.look(sample(0f, 0.0, pressure = 0.5f), 0f, 0)
        assertEquals(0.55f, dabber.strokeGrain.paper.depth, 1e-6f)
        // A later dab at full pressure does NOT move it: one uniform for the whole stroke.
        dabber.look(sample(4f, 4.0, pressure = 1f), 4f, 1)
        assertEquals(0.55f, dabber.strokeGrain.paper.depth, 1e-6f)
    }

    @Test
    fun onlyTheGrainTheFileEnablesIsOnAndItsPitchIsSixtyFourOverScale() {
        val g = BrushDabber(pencil, 1L).strokeGrain
        assertFalse(g.tip.enabled, "the pencil has no tip texture")
        assertTrue(g.paper.enabled)
        assertEquals(6.4f, g.paper.pitchPx, 1e-6f) // 64 / 10
        assertEquals("cloud_fine_256.png", g.paper.asset)
        assertEquals(0.6f, g.paper.tiltGradient)
        assertEquals(0.25f, g.paper.edge)
    }
}
