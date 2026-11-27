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

    /**
     * THE PHYSICAL CONTRACT (owner, Note 9 and Note 20): the S Pen reports AXIS_ORIENTATION pointing away from the
     * barrel. A barrel leaning toward +x (the right edge) arrives as "pointing left", -PI/2, and must read azimuth 0;
     * a barrel leaning toward the person (the bottom edge, +y down) arrives as "pointing up", 0, and must read +PI/2.
     * Before the flip both of these read exactly opposite, which is what "inverted x and y" looked like on the phone.
     */
    @Test
    fun aPenReadsTheDirectionItsBarrelLeans() {
        assertTrue(AxisMapping.STYLUS_FLIP)
        assertEquals(0f, AxisMapping.stylusAzimuth((-PI / 2).toFloat()), eps)                   // barrel → right
        assertEquals((PI / 2).toFloat(), AxisMapping.stylusAzimuth(0f), eps)                     // barrel → bottom
        assertEquals((-PI / 2).toFloat(), AxisMapping.stylusAzimuth(PI.toFloat()), eps)          // barrel → top
        // The canvas rotation is still removed after the flip: canvas turned 90° clockwise, barrel right on screen
        // leans "up" the document.
        assertEquals((-PI / 2).toFloat(), AxisMapping.stylusAzimuth((-PI / 2).toFloat(), (PI / 2).toFloat()), eps)
        // The pen dot gets the same turn, in Android's convention: barrel right = +PI/2.
        assertEquals((PI / 2).toFloat(), AxisMapping.stylusBarrelOrientation((-PI / 2).toFloat()), eps)
        assertTrue(AxisMapping.stylusAzimuth(Float.NaN).isNaN())
        assertTrue(AxisMapping.stylusBarrelOrientation(Float.NaN).isNaN())
    }

    @Test
    fun flipAndMissing() {
        assertEquals((PI / 2).toFloat(), AxisMapping.androidOrientationToAzimuth(0f, flip = true), eps)
        assertTrue(AxisMapping.androidOrientationToAzimuth(Float.NaN).isNaN())
    }
}
