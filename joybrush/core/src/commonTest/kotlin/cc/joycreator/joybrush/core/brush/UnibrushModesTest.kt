package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.svg.LineSvg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/** DRAFT — not run; install only after the D8 model/suggestion checkpoint. */
class UnibrushModesTest {
    private fun modes() = UnibrushModes.presets().associateBy { it.id.removePrefix("unibrush.") }
    private fun inputs(pressure: Float, tilt: Float = 0f) = DabInputs(pressure,tilt,
        Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN)

    @Test fun oneStampFamilyHasUniqueStableIdsAndOnlyItsModeWordOnEachRow() {
        val expected = linkedMapOf("soft" to "Soft", "hard" to "Hard", "calligraphy" to "Calligraphy",
            "taper" to "Taper", "fade" to "Fade", "texture" to "Texture", "round" to "Round",
            "chisel" to "Chisel", "living" to "Living", "dry" to "Dry", "sparkle" to "Sparkle")
        val entries = UnibrushModes.presets()
        assertEquals(expected.entries.map { "unibrush.${it.key}" to it.value },entries.map { it.id to it.name })
        assertEquals(entries.size,entries.map { it.id }.toSet().size)
        for (preset in entries) {
            assertEquals("stamp",preset.engine)
            assertEquals("builtin",preset.extensions["unibrush.family"])
            assertFalse(preset.name.contains("Unibrush",ignoreCase=true))
            assertTrue(preset.name.all { it.isLetter() },"row labels are mode words: ${preset.name}")
            assertEquals("native",preset.sourceFormat)
        }
        assertEquals(entries,UnibrushModes.presets(),"catalog construction consumes no mutable RNG")
    }

    @Test fun everyRecipeValidatesAndCarriesTheModernFourHandleShape() {
        for (preset in UnibrushModes.presets()) {
            assertEquals(emptyList(),BrushValidate.validate(preset),preset.id)
            val quad=assertNotNull(preset.tip.quad,preset.id)
            assertEquals(4,quad.a.points.size)
            assertTrue(quad.a.points.all { it.x in -1f..1f && it.y in -1f..1f })
            assertTrue(quad.a.roundness in 0f..1f)
            assertNotNull(preset.tip.profile)
            assertNotNull(preset.tip.angleSource)
            assertNotNull(preset.stabilizer)
            assertTrue(preset.stabilizer!!.problems().isEmpty())
            assertTrue(preset.line?.screenWidthPx?.let { it==0f } ?: true)
            assertEquals(preset,BrushJson.decodeChecked(BrushJson.encode(preset)))
        }
    }

    @Test fun pressureChipsAndStrongTaperHaveVisibleCorrectCurves() {
        val modes=modes()
        for (name in listOf("hard","calligraphy","taper","living","sparkle")) {
            val p=modes.getValue(name)
            assertTrue(Dynamics.eval(p.size,inputs(.1f))<Dynamics.eval(p.size,inputs(1f)),name)
            assertEquals(Dynamics.eval(p.flow,inputs(.1f)),Dynamics.eval(p.flow,inputs(1f)),name)
        }
        for (name in listOf("soft","fade","chisel","dry")) {
            val p=modes.getValue(name)
            assertEquals(Dynamics.eval(p.size,inputs(.1f)),Dynamics.eval(p.size,inputs(1f)),name)
            assertTrue(Dynamics.eval(p.flow,inputs(.1f))<Dynamics.eval(p.flow,inputs(1f)),name)
        }
        for (name in listOf("round","texture")) {
            val p=modes.getValue(name)
            assertTrue(Dynamics.eval(p.size,inputs(.1f))<Dynamics.eval(p.size,inputs(1f)),name)
            assertTrue(Dynamics.eval(p.flow,inputs(.1f))<Dynamics.eval(p.flow,inputs(1f)),name)
        }
        val taper=modes.getValue("taper")
        assertTrue(Dynamics.eval(taper.size,inputs(.5f))<taper.size.base*.5f)
        val ends=assertNotNull(taper.line)
        assertTrue(ends.taperIn>0f && ends.taperOut>0f)
        assertEquals(StabilizerMode.pulledString,taper.stabilizer!!.mode)
    }

    @Test fun sectionFourRecipesRemainConcreteEngineSettings() {
        val modes=modes()
        val soft=modes.getValue("soft")
        assertEquals(TipProfile.gaussian,soft.tip.profile!!.kind)
        assertEquals(0f,soft.tip.hardness.base);assertEquals("buildup",soft.accumulate)
        assertTrue(soft.flow.base<=.1f);assertEquals(.15f,soft.spacing)
        assertEquals(StabilizerMode.off,soft.stabilizer!!.mode)
        assertTrue(BrushValidate.validate(soft.copy(size=soft.size.copy(base=2000f))).isEmpty())
        val round=modes.getValue("round")
        assertEquals(TipProfile.smooth,round.tip.profile!!.kind);assertEquals(.85f,round.tip.hardness.base)
        val hard=modes.getValue("hard")
        assertEquals(TipProfile.hard,hard.tip.profile!!.kind);assertTrue(hard.size.base<=8f)
        assertEquals(StabilizerMode.pulledString,hard.stabilizer!!.mode)
        val calligraphy=modes.getValue("calligraphy")
        assertEquals(TipAngleSource.penAzimuth,calligraphy.tip.angleSource)
        assertEquals(0f,calligraphy.tip.quad!!.a.roundness)
        assertTrue(abs((1f-calligraphy.tip.aspect)-.15f)<.00001f)
        val aspect=assertNotNull(calligraphy.tip.aspectDynamics)
        assertTrue(abs(Dynamics.eval(aspect,inputs(1f,(PI*.5).toFloat())))>
            abs(Dynamics.eval(aspect,inputs(1f,0f))))
        val chisel=modes.getValue("chisel")
        assertEquals(.4f,chisel.tip.taper);assertEquals("wash",chisel.accumulate)
        assertEquals(FadeFrame.penAzimuth,chisel.tip.fade!!.relativeTo)
        val living=modes.getValue("living").color.dynamics!!
        assertEquals(ColorMode.perStrokeDrift,living.mode)
        assertEquals(18f,living.hueRangeDegrees);assertEquals(.1f,living.saturationRange)
        val dry=modes.getValue("dry")
        assertEquals(.12f,dry.spacing);assertNotNull(dry.tip.dual!!.image)
        val sparkle=modes.getValue("sparkle")
        assertEquals(listOf(UnibrushModes.SPARKLE_PATH),sparkle.tip.sampled!!.images)
        assertTrue(sparkle.angleJitter>0f && sparkle.scatter.count>1)
        assertTrue(sparkle.color.dynamics!!.isActive)
    }

    @Test fun exactRequiredPngClosuresHaveBoundedImmutableNonemptyR8Artwork() {
        val allPaths=HashSet<String>()
        for (preset in UnibrushModes.presets()) {
            val required=BrushAssetPaths.required(preset)
            val assets=UnibrushModes.imageMasks(preset)
            assertEquals(required.toSet(),assets.keys,preset.id)
            allPaths.addAll(required)
            var pixels=0L
            for ((path,mask) in assets) {
                assertTrue(SampledTips.safePath(path))
                assertTrue(mask.width in 1..2048 && mask.height in 1..2048)
                pixels+=mask.width.toLong()*mask.height
                val before=mask.bytes()
                assertTrue(before.any { (it.toInt()and 255)>0 },path)
                assertTrue(before.any { it==0.toByte() },path)
                before.fill(0)
                assertTrue(mask.bytes().any { (it.toInt()and 255)>0 },"outward arrays cannot alter $path")
                assertSame(mask,UnibrushModes.imageMask(path))
            }
            assertTrue(pixels<=SampleMaskLimits.MAX_TOTAL_SOURCE_PIXELS)
        }
        assertEquals(setOf(UnibrushModes.TEXTURE_PATH,UnibrushModes.DRY_PATH,UnibrushModes.SPARKLE_PATH),allPaths)
        assertNull(UnibrushModes.imageMask("missing.png"))
        val unknown=modes().getValue("hard").copy(tip=TipSpec(sampled=SampledTipSpec(listOf("missing.png"))))
        assertFailsWith<BrushException> { UnibrushModes.imageMasks(unknown) }
    }

    @Test fun sparkleArtworkHasFiveRealTipsAndFiveOpenValleys() {
        val mask=UnibrushModes.imageMask(UnibrushModes.SPARKLE_PATH)!!
        assertTrue(mask.sample(0f,0f)>.9f)
        for(i in 0 until 5) {
            val tip=-PI*.5+i*2*PI/5
            val valley=tip+PI/5
            assertTrue(mask.sample((cos(tip)*.75).toFloat(),(sin(tip)*.75).toFloat())>.1f,"tip$i")
            assertTrue(mask.sample((cos(valley)*.65).toFloat(),(sin(valley)*.65).toFloat())<.05f,"valley$i")
        }
    }

    @Test fun modesNeverPromiseAnOutlineSvgFallbackForImageOrSoftnessAppearance() {
        for(preset in UnibrushModes.presets()) {
            val record=StrokeRecord("s",preset.id,1L,0f,1f,
                listOf(PenSample(8f,8f,0.0),PenSample(20f,20f,10.0)))
            val result=LineSvg.export(64.0,64.0,listOf(record),{preset})
            assertEquals(1,result.skipped.size,preset.id)
            assertEquals("s",result.skipped.single().strokeId)
            assertTrue(result.skipped.single().reason.isNotBlank())
            assertTrue(result.approximated.isEmpty())
        }
    }
}
