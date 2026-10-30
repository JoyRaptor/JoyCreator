package cc.joycreator.joybrush.android.chrome

import org.junit.Assert.assertFalse
import org.junit.Test

class UnibrushActivationTest {
    // Unibrush stays hidden from the owner until the finish plan is done. The switch is flipped deliberately at
    // switch-on (step U12 of UNIBRUSH_FINISH_20261023), and this test changes with it. The debug bench reaches the
    // modes without it (`unibrush:<slug>`).
    @Test fun unibrushIsHiddenFromTheOwnerUntilSwitchOn() {
        assertFalse(UnibrushActivation.SHIPPING_ENABLED)
    }
}
