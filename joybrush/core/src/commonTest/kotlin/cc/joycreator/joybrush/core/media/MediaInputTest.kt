package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.test.Test
import cc.joycreator.joybrush.core.input.AxisMapping
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaInputTest {
    @Test fun thePensReachCountsAsLyingFlat() {
        val flat = MediaInput.sample(PenSample(1f, 2f, 10.0, 0.5f, tilt = (68 * PI / 180).toFloat(), azimuth = 1f))
        assertEquals(PI / 2, flat.tilt, 1e-6)
        assertEquals(PI / 4, MediaInput.sample(PenSample(0f, 0f, 0.0, tilt = (34 * PI / 180).toFloat())).tilt, 1e-6)
        assertEquals(PI / 2, MediaInput.sample(PenSample(0f, 0f, 0.0, tilt = 1.5f)).tilt, 1e-9, "past the reach is still flat")
        assertEquals(1.0 + PI - 2 * PI, flat.az, 1e-6, "the barrel leans opposite the tip the pen reports")
        assertEquals(0.5, flat.p, 1e-6); assertEquals(10.0, flat.t)
    }

    @Test fun theStickLeansTheWayTheBarrelDoesNotTheTip() {
        // Android: orientation 0 = the tip points up the screen, so the barrel leans down toward the person (Chrome turns
        // the same reading into tiltY > 0, the barrel toward +y). The document's y grows down: the media lean is +π/2.
        val up = AxisMapping.androidOrientationToAzimuth(0f)
        assertEquals(PI / 2, MediaInput.sample(PenSample(0f, 0f, 0.0, tilt = 0.6f, azimuth = up)).az, 1e-6)
        // Orientation +π/2 = the tip points right, a left-hander's lean: the barrel goes left (π).
        val right = AxisMapping.androidOrientationToAzimuth((PI / 2).toFloat())
        assertEquals(PI, kotlin.math.abs(MediaInput.sample(PenSample(0f, 0f, 0.0, tilt = 0.6f, azimuth = right)).az), 1e-6)
    }

    @Test fun noTiltSensorIsUprightAndLeansTheLabsWay() {
        val s = MediaInput.sample(PenSample(0f, 0f, 0.0))
        assertEquals(0.0, s.tilt); assertEquals(MediaInput.DEFAULT_AZ, s.az); assertEquals(1.0, s.p)
        assertTrue(kotlin.math.cos(s.az) > 0 && kotlin.math.sin(s.az) > 0, "the barrel to the lower right, y down")
    }
}
