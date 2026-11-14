package cc.joycreator.joybrush.android.chrome

import cc.joycreator.joybrush.core.brush.TipProfile
import cc.joycreator.joybrush.core.brush.TipProfileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BRUSH-UI item 4: the tip cross-section follows the shader's rules for each profile and the Softness slider. */
class TipSectionViewTest {
    private fun cov(kind: TipProfile?, hardness: Float, d: Float) =
        TipSectionView.coverageOf(kind?.let { TipProfileSpec(it) }, hardness, d)

    @Test fun hardKeepsAHardRimWhateverTheSoftness() {
        assertEquals(1f, cov(TipProfile.hard, .1f, .5f), 1e-4f)
        assertEquals(1f, cov(TipProfile.hard, .9f, .5f), 1e-4f)
        assertEquals(0f, cov(TipProfile.hard, .1f, 1f), 1e-4f)
    }

    @Test fun smoothAndLegacySoftenFromHardnessToTheRim() {
        for (kind in listOf(null, TipProfile.smooth)) {
            assertEquals(1f, cov(kind, .8f, .5f), 1e-4f)
            assertTrue(cov(kind, .2f, .5f) < 1f)
            assertTrue(cov(kind, .2f, .5f) < cov(kind, .6f, .5f))
        }
    }

    @Test fun linearAndGaussianFallOffAfterHardness() {
        assertEquals(1f, cov(TipProfile.linear, .5f, .4f), 1e-4f)
        assertEquals(.5f, cov(TipProfile.linear, .5f, .75f), 1e-4f)
        assertTrue(cov(TipProfile.gaussian, 0f, .5f) in .0f..1f)
    }
}
