package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.doc.BoardKind
import kotlin.test.*

class BoardChromePenFadeTest {
    private val input=BoardChromeLayout.Input(BoardChromeLayout.Rect(100f,100f,300f,250f),1f,BoardKind.CANVAS,selected=true)
    private fun alpha(input:BoardChromeLayout.Input)=BoardChromeLayout.layout(input).elements.first {it.id=="pill"}.alpha / BoardChromeLayout.CHROME_ALPHA

    @Test fun shortStrokeLiftsWithoutJumpAndReturnsIn300Ms() {
        val fade=BoardChromePenFade()
        assertEquals(1f,alpha(fade.apply(input.copy(penDown=true),1000)),.00001f)
        val before=alpha(fade.apply(input.copy(penDown=true),1030))
        assertEquals(.78f,before,.00001f)
        assertEquals(before,alpha(fade.apply(input,1030)),.00001f)
        assertEquals(.89f,alpha(fade.apply(input,1180)),.00001f)
        assertEquals(1f,alpha(fade.apply(input,1330)),.00001f)
    }
    @Test fun newStrokeInterruptsReturnWithoutJumpAndTakes120Ms() {
        val fade=BoardChromePenFade()
        fade.apply(input.copy(penDown=true),0)
        assertEquals(.12f,alpha(fade.apply(input.copy(penDown=true),120)),.00001f)
        fade.apply(input,120)
        val returning=alpha(fade.apply(input,270))
        assertEquals(.56f,returning,.00001f)
        assertEquals(returning,alpha(fade.apply(input.copy(penDown=true),270)),.00001f)
        assertEquals(.34f,alpha(fade.apply(input.copy(penDown=true),330)),.00001f)
        assertEquals(.12f,alpha(fade.apply(input.copy(penDown=true),390)),.00001f)
    }
    @Test fun rapidRepeatedTapsStayContinuousAndWithinLockedLimits() {
        val fade=BoardChromePenFade()
        var time=1000L
        repeat(100) {
            val before=alpha(fade.apply(input,time))
            assertEquals(before,alpha(fade.apply(input.copy(penDown=true),time)),.00001f)
            time+=10
            val drawn=alpha(fade.apply(input.copy(penDown=true),time))
            assertTrue(drawn in .11999f..1f)
            assertEquals(drawn,alpha(fade.apply(input,time)),.00001f)
            time+=10
        }
        assertEquals(1f,alpha(fade.apply(input,time+300)),.00001f)
    }
    @Test fun defaultInputRetainsLastShortStrokeDurationAtLift() {
        assertEquals(.78f,alpha(input.copy(penDownElapsedMs=30,penLiftElapsedMs=0)),.00001f)
    }
    @Test fun resetRestoresIdleVisibilityAndNewTimeOrigin() {
        val fade=BoardChromePenFade()
        fade.apply(input.copy(penDown=true),1000)
        fade.apply(input.copy(penDown=true),1120)
        fade.reset()
        assertEquals(1f,alpha(fade.apply(input,0)),.00001f)
    }
    @Test fun timeMustBeMonotonicAndOpacityMustBeValid() {
        val fade=BoardChromePenFade();fade.apply(input,100)
        assertFailsWith<IllegalArgumentException> {fade.apply(input,99)}
        assertFailsWith<IllegalArgumentException> {BoardChromeLayout.drawingAlpha(true,0,Float.NaN)}
        assertFailsWith<IllegalArgumentException> {BoardChromeLayout.drawingAlpha(false,0,.1f)}
    }
}
