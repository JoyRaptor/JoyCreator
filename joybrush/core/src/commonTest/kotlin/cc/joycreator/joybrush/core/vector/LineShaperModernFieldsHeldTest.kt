package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.paint.Dab
import kotlin.test.*

/** Proposed tests for the held LineShaper preservation adapter; not canonical until Lead applies. */
class LineShaperModernFieldsHeldTest {
    @Test fun proceduralDensificationRetainsTheSharedProfilesAndInterpolatesQuadAndFade() {
        val a=TipQuads.interpolate(QuadShapeSpec(roundness=.2f),null,0f)
        val b=TipQuads.interpolate(QuadShapeSpec(roundness=.8f),null,0f)
        val profile=TipProfileSpec(TipProfile.gaussian)
        val profileB=TipProfileSpec(TipProfile.linear)
        val dabs=listOf(Dab(0f,0f,.5f,quad=a,profile=profile,profileB=profileB,profileMix=.2f,fadeAmount=.2f,fadeAngle=3f),
            Dab(8f,0f,.5f,quad=b,profile=profile,profileB=profileB,profileMix=.8f,fadeAmount=.8f,fadeAngle=-3f))
        val actual=LineShaper.densify(dabs,8f)
        assertTrue(actual.size>2); assertEquals(dabs.first(),actual.first());assertEquals(dabs.last(),actual.last())
        for(d in actual.drop(1).dropLast(1)) {
            assertSame(profile,d.profile);assertNotNull(d.quad)
            assertTrue(d.quad!!.roundness in .2f..0.8f)
            assertSame(profileB,d.profileB); assertTrue(d.profileMix in .2f..0.8f)
            assertTrue(d.fadeAmount in .2f..0.8f)
            assertTrue(kotlin.math.abs(d.fadeAngle)>3f) // shortest arc crosses PI, not zero
        }
    }

    @Test fun stochasticAndImageContactsNeverInventExtraDabs() {
        val image=SampledStamp(SampledTipSet(listOf("a.png"),listOf(ImageMask.fromR8(1,1,byteArrayOf(-1)))),0,1f)
        val dual=DualTips.bake(DualTipSpec(),1)
        for(dab in listOf(Dab(0f,0f,.5f,sampled=image),Dab(0f,0f,.5f,dual=dual),
            Dab(0f,0f,.5f,color=ColorDab(.1f,.2f,.3f)))) {
            val input=listOf(dab,dab.copy(x=8f))
            assertSame(input,LineShaper.densify(input,8f))
        }
    }
}
