package cc.joycreator.joybrush.android.chrome

import org.junit.Assert.assertTrue
import org.junit.Test

class UnibrushActivationTest {
    // The owner switched Unibrush on, 2026-10-31 (step U12 of UNIBRUSH_FINISH_20261023). The debug bench reaches the
    // modes without it (`unibrush:<slug>`).
    @Test fun unibrushIsSwitchedOn() {
        assertTrue(UnibrushActivation.SHIPPING_ENABLED)
    }
}
