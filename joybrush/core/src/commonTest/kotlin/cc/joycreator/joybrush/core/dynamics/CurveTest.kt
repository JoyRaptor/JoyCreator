package cc.joycreator.joybrush.core.dynamics

import kotlin.test.Test
import kotlin.test.assertEquals

class CurveTest {

    @Test
    fun identityPassesValuesThrough() {
        for (i in 0..20) {
            val x = i / 20f
            assertEquals(x, Curve.IDENTITY.eval(x), 1e-5f)
        }
    }

    @Test
    fun interpolatesBetweenPointsAndClampsOutside() {
        val c = Curve(listOf(0.2f to 0f, 0.6f to 1f))
        assertEquals(0f, c.eval(0f))
        assertEquals(0.5f, c.eval(0.4f), 0.01f)
        assertEquals(1f, c.eval(1f))
        assertEquals(1f, c.eval(7f))
        assertEquals(0f, c.eval(-3f))
    }

    @Test
    fun unsortedPointsAreSorted() {
        val c = Curve(listOf(1f to 1f, 0f to 0.5f))
        assertEquals(0.75f, c.eval(0.5f), 0.01f)
    }

    @Test
    fun nanInputIsTreatedAsZero() {
        assertEquals(0.25f, Curve(listOf(0f to 0.25f, 1f to 1f)).eval(Float.NaN))
    }
}
