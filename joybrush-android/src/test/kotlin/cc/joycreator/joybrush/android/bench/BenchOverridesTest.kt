package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.brush.*
import org.junit.Assert.*
import org.junit.Test

class BenchOverridesTest {
    @Test fun explicitSizeUsesTheRealJobRecipeWithoutChangingItsOtherControls() {
        val curve = InputCurve(BrushInput.pressure,listOf(listOf(0f,0f),listOf(1f,1f)))
        val preset = BrushPreset(id="soft",name="Soft",size=Param(256f),flow=Param(.08f,listOf(curve)),spacing=.15f)
        val resolved=BenchBrushOverride.resolve(preset,2000f)
        assertEquals(2000f,resolved.size.base,0f)
        assertEquals(preset,resolved.copy(size=preset.size))
        assertEquals(256f,preset.size.base,0f)
        assertSame(preset,BenchBrushOverride.resolve(preset,null))
    }
    @Test fun malformedOrUnimplementedOverridesRefuseInsteadOfBenchmarkingAnotherSize() {
        for (size in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,4097f)) {
            try {BenchBrushOverride.resolve(BrushPreset(id="p",name="P",size=Param(256f)),size);fail("Bad size accepted")}
            catch(expected:IllegalArgumentException) {}
        }
        try {BenchBrushOverride.resolve(BrushPreset(id="f",name="F",size=Param(256f),engine=ENGINE_FILL),2000f);fail("Ignored override accepted")}
        catch(expected:IllegalArgumentException) {assertTrue(expected.message!!.contains("stamp"))}
    }
    @Test fun aUnibrushNameFindsThatModeAndEveryOtherNameIsLeftToTheOldLookup() {
        val modes=UnibrushModes.presets()
        assertEquals(11,modes.size)
        for (mode in modes) assertEquals(mode,BenchUnibrush.resolve("unibrush:"+mode.id.removePrefix("unibrush.")))
        assertEquals("unibrush.texture",BenchUnibrush.resolve("Unibrush:TEXTURE")!!.id)
        for (other in listOf("ink","soft","unibrush.soft","unibrush","media:wet:Round","media:dry:Proto")) assertNull(other,BenchUnibrush.resolve(other))
    }
    @Test fun anUnknownUnibrushSlugRefusesAndNamesEveryValidOne() {
        for (bad in listOf("unibrush:nope","unibrush:","unibrush:soft2")) {
            try {BenchUnibrush.resolve(bad);fail("Accepted $bad")}
            catch(expected:IllegalArgumentException) {
                for (slug in listOf("soft","hard","calligraphy","taper","fade","texture","round","chisel","living","dry","sparkle"))
                    assertTrue("$bad: ${expected.message}",expected.message!!.contains("unibrush:$slug"))
            }
        }
    }
    @Test fun refusalLeaseForwardsAndRestoresExactlyTheCallbackItBorrowed() {
        val forwarded=ArrayList<String>()
        val prior:(String)->Unit={forwarded+=it}
        var callback:((String)->Unit)?=prior
        val observation=BenchRefusalObservation({callback},{callback=it})
        callback!!.invoke("No resources")
        assertEquals(listOf("No resources"),observation.snapshot())
        assertEquals(listOf("No resources"),forwarded)
        observation.close();observation.close()
        assertSame(prior,callback)
    }
    @Test fun aLaterOwnerSurvivesAndMakesTheTimingObservationInconclusive() {
        var callback:((String)->Unit)?=null
        val observation=BenchRefusalObservation({callback},{callback=it})
        val replacement:(String)->Unit={}
        callback=replacement
        observation.close();observation.close()
        assertSame(replacement,callback)
        assertEquals(1,observation.snapshot().size)
        assertTrue(observation.snapshot().single().contains("owner changed"))
    }
}
