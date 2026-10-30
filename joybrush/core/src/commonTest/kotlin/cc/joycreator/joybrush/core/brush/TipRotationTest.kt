package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TipRotationTest {
    private fun sample(x: Float = 0f, y: Float = 0f, azimuth: Float = 1f, barrel: Float = -1f) =
        PenSample(x, y, 0.0, tilt = .8f, azimuth = azimuth, barrel = barrel)

    @Test fun eachSourceIgnoresTheOtherChannels() {
        assertEquals(0f, TipRotation(TipAngleSource.fixed).update(sample()))
        assertEquals(1f, TipRotation(TipAngleSource.penAzimuth).update(sample()))
        assertEquals(-1f, TipRotation(TipAngleSource.penBarrel).update(sample()))
        val stroke = TipRotation(TipAngleSource.stroke)
        assertEquals(0f, stroke.update(sample()))
        assertEquals((PI / 2).toFloat(), stroke.update(sample(y = 10f)), .00001f)
    }

    @Test fun smoothingUsesOnePointFiveMillimetersAndFreezesAtRest() {
        val rotation = TipRotation(TipAngleSource.penAzimuth)
        rotation.update(sample(azimuth = 0f))
        val actual = rotation.update(sample(x = 30f, azimuth = 1f))
        assertEquals((1.0 - exp(-1.0)).toFloat(), actual, .00001f)
        assertEquals(actual, rotation.update(sample(x = 30f, azimuth = -2f)))
    }

    @Test fun wrapsAcrossPiAndDoesNotStickAtReversal() {
        val rotation = TipRotation(TipAngleSource.penAzimuth)
        rotation.update(sample(azimuth = (PI - .1).toFloat()))
        val crossed = rotation.update(sample(x = 30f, azimuth = (-PI + .1).toFloat()))
        assertTrue(abs(crossed) > 3f, "must cross pi, not zero: $crossed")
        val stroke = TipRotation(TipAngleSource.stroke)
        stroke.update(sample())
        stroke.update(sample(x = 30f))
        val reversal = stroke.update(sample(x = 0f))
        assertTrue(abs(reversal) > 1f && abs(reversal) < PI, "reversal must turn: $reversal")
    }

    @Test fun unavailableAndInvalidSensorsHoldAValidHeading() {
        for (source in listOf(TipAngleSource.penAzimuth, TipAngleSource.penBarrel)) {
            val rotation = TipRotation(source)
            assertEquals(0f, rotation.update(sample(azimuth = Float.NaN, barrel = Float.NaN)))
            assertEquals(.8f, rotation.update(sample(x = 30f, azimuth = .8f, barrel = .8f)))
            for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertEquals(.8f, rotation.update(sample(x = 60f, azimuth = v, barrel = v)))
            }
        }
    }
}
