package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaInputTest {
    @Test fun thePensReachCountsAsLyingFlat() {
        val flat = MediaInput.sample(PenSample(1f, 2f, 10.0, 0.5f, tilt = (68 * PI / 180).toFloat(), azimuth = 1f))
        assertEquals(PI / 2, flat.tilt, 1e-6)
        assertEquals(PI / 4, MediaInput.sample(PenSample(0f, 0f, 0.0, tilt = (34 * PI / 180).toFloat())).tilt, 1e-6)
        assertEquals(PI / 2, MediaInput.sample(PenSample(0f, 0f, 0.0, tilt = 1.5f)).tilt, 1e-9, "past the reach is still flat")
        assertEquals(1.0, flat.az, 1e-9); assertEquals(0.5, flat.p, 1e-6); assertEquals(10.0, flat.t)
    }

    @Test fun noTiltSensorIsUprightAndLeansTheLabsWay() {
        val s = MediaInput.sample(PenSample(0f, 0f, 0.0))
        assertEquals(0.0, s.tilt); assertEquals(MediaInput.DEFAULT_AZ, s.az); assertEquals(1.0, s.p)
    }
}
