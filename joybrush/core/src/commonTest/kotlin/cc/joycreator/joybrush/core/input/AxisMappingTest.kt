package cc.joycreator.joybrush.core.input

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxisMappingTest {
    private val eps = 1e-5f

    @Test
    fun documentedAndroidDirections() {
        assertEquals((-PI / 2).toFloat(), AxisMapping.androidOrientationToAzimuth(0f), eps)           // up
        assertEquals(0f, AxisMapping.androidOrientationToAzimuth((PI / 2).toFloat()), eps)            // right
        assertEquals((PI / 2).toFloat(), AxisMapping.androidOrientationToAzimuth(PI.toFloat()), eps)  // down
    }

    @Test
    fun canvasRotationIsRemoved() {
        // Canvas turned 90° clockwise on screen: a pen leaning right on screen leans "up" in the document.
        val a = AxisMapping.androidOrientationToAzimuth((PI / 2).toFloat(), canvasRotation = (PI / 2).toFloat())
        assertEquals((-PI / 2).toFloat(), a, eps)
    }

    @Test
    fun flipAndMissing() {
        assertEquals((PI / 2).toFloat(), AxisMapping.androidOrientationToAzimuth(0f, flip = true), eps)
        assertTrue(AxisMapping.androidOrientationToAzimuth(Float.NaN).isNaN())
    }
}
