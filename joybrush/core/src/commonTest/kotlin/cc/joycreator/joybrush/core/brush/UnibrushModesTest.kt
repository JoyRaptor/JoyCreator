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
            "chisel" to "Chisel", "living" to "Living", "dry" to "Dry", "sparkle" to "Sparkle",
            // The 2026-11-13 brush set: appended, so the eleven above keep their order.
            "chalk" to "Chalk", "charcoal" to "Charcoal", "technical" to "Technical",
            "airbrush" to "Airbrush", "pastel" to "Pastel", "ribbon" to "Ribbon", "rainbow" to "Rainbow",
            "foliage" to "Foliage", "stipple" to "Stipple", "dashes" to "Dashes", "hatch" to "Hatch")
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
        assertEquals(setOf(UnibrushModes.TEXTURE_PATH,UnibrushModes.DRY_PATH,UnibrushModes.SPARKLE_PATH)+
            UnibrushModes.FOLIAGE_PATHS+UnibrushModes.HATCH_PATHS,allPaths)
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

    @Test fun theBrushSetAddsElevenRecipesAfterTheFirstElevenAndNoTwoAreTheSameBrush() {
        val entries=UnibrushModes.presets()
        assertEquals(22,entries.size)
        assertEquals(listOf("soft","hard","calligraphy","taper","fade","texture","round","chisel","living","dry","sparkle"),
            entries.take(11).map { it.id.removePrefix("unibrush.") })
        // Two rows that differ only by their label would be one brush listed twice.
        assertEquals(entries.size,entries.map { it.copy(id="x",name="X") }.toSet().size)
        assertEquals(entries.size,entries.map { it.name }.toSet().size)
    }

    @Test fun theBrushSetEssentialsAreTheFamiliarToolsTheyAreNamedFor() {
        val modes=modes()
        for (name in listOf("chalk","charcoal")) {
            val p=modes.getValue(name)
            assertTrue(p.paperGrain.enabled && p.paper.influence>=.5f,name)
            // A light touch leaves more of the paper showing than a firm one.
            assertTrue(Dynamics.eval(p.paperGrain.depth,inputs(.1f))>Dynamics.eval(p.paperGrain.depth,inputs(1f)),name)
            assertTrue(Dynamics.eval(p.flow,inputs(.1f))<Dynamics.eval(p.flow,inputs(1f)),name)
            assertEquals(Dynamics.eval(p.size,inputs(.1f)),Dynamics.eval(p.size,inputs(1f)),name)
            assertTrue(Dynamics.eval(p.size,inputs(1f,(PI*.5).toFloat()))>Dynamics.eval(p.size,inputs(1f,0f))*1.5f,name)
        }
        val chalk=modes.getValue("chalk");val charcoal=modes.getValue("charcoal")
        assertEquals(TipProfile.hard,chalk.tip.profile!!.kind);assertTrue(chalk.tip.quad!!.a.roundness<.5f)
        assertTrue(charcoal.tip.hardness.base<.5f)
        assertEquals(FadeFrame.penAzimuth,charcoal.tip.fade!!.relativeTo)
        assertTrue(Dynamics.eval(charcoal.tip.fade!!.amount,inputs(1f,0f))<.01f)
        assertTrue(Dynamics.eval(charcoal.tip.fade!!.amount,inputs(1f,(PI*.5).toFloat()))>.5f)

        val technical=modes.getValue("technical")
        assertTrue(technical.size.inputs.isEmpty() && technical.flow.inputs.isEmpty() && technical.opacity.inputs.isEmpty())
        assertEquals(TipProfile.hard,technical.tip.profile!!.kind);assertEquals(1f,technical.tip.hardness.base)
        assertTrue(technical.size.base<=6f && technical.spacing<=.04f)
        assertEquals(StabilizerMode.pulledString,technical.stabilizer!!.mode)
        assertTrue(technical.stabilizer!!.strength>modes.getValue("hard").stabilizer!!.strength)

        val airbrush=modes.getValue("airbrush");val soft=modes.getValue("soft")
        assertEquals(TipProfile.gaussian,airbrush.tip.profile!!.kind);assertEquals("buildup",airbrush.accumulate)
        assertTrue(airbrush.size.base<soft.size.base*.25f && airbrush.flow.base<=.1f)
        assertTrue(Dynamics.eval(airbrush.flow,inputs(.1f))<Dynamics.eval(airbrush.flow,inputs(1f)))

        val pastel=modes.getValue("pastel")
        assertEquals(TipAngleSource.stroke,pastel.tip.angleSource)
        assertTrue(pastel.tip.aspect>0f && pastel.tip.quad!!.a.roundness in .1f..(.6f))
        assertTrue(pastel.size.base>=48f && pastel.flow.inputs.isEmpty() && pastel.flow.base>=.9f)
        assertTrue(pastel.tipTexture.enabled && pastel.paperGrain.enabled)
    }

    @Test fun theBrushSetShowpiecesEachUseTheEngineFeatureTheyAreThereToShow() {
        val modes=modes()
        val ribbon=modes.getValue("ribbon")
        assertEquals(TipAngleSource.penAzimuth,ribbon.tip.angleSource)
        val quad=ribbon.tip.quad!!;val other=assertNotNull(quad.b)
        assertNotEquals(quad.a.points,other.points)
        assertTrue(Dynamics.eval(quad.morph,inputs(0f))<.01f);assertTrue(Dynamics.eval(quad.morph,inputs(1f))>.99f)
        // Every shape on the way from A to B is a real, filled tip.
        for (step in 0..10) assertFalse(QuadMath.prepare(TipQuads.interpolate(quad.a,other,step/10f)).empty,"morph $step")
        assertTrue(TipQuads.aspect(quad.a,other,0f,ribbon.tip.aspect)!!>TipQuads.aspect(quad.a,other,1f,ribbon.tip.aspect)!!)
        assertEquals(FadeFrame.penAzimuth,ribbon.tip.fade!!.relativeTo);assertTrue(ribbon.tip.fade!!.amount.base>.4f)

        val rainbow=modes.getValue("rainbow").color.dynamics!!
        assertEquals(ColorMode.perStrokeDrift,rainbow.mode)
        assertTrue(abs(rainbow.pressureHueDegrees)>=90f && rainbow.hueRangeDegrees>=90f)
        assertTrue(rainbow.hueRangeDegrees>modes.getValue("living").color.dynamics!!.hueRangeDegrees*4f)

        val foliage=modes.getValue("foliage")
        assertEquals(UnibrushModes.FOLIAGE_PATHS,foliage.tip.sampled!!.images)
        assertEquals(4,foliage.tip.sampled!!.images.toSet().size)
        assertEquals(SamplePick.random,foliage.tip.sampled!!.pick)
        assertTrue(foliage.scatter.count>1 && foliage.scatter.amount.base>0f && foliage.angleJitter>0f)
        assertEquals(ColorMode.perDab,foliage.color.dynamics!!.mode);assertTrue(foliage.color.dynamics!!.isActive)

        val stipple=modes.getValue("stipple")
        assertTrue(stipple.spacing>1f && stipple.scatter.amount.base>0f)
        assertEquals(TipProfile.hard,stipple.tip.profile!!.kind);assertEquals(1f,stipple.tip.quad!!.a.roundness)
        assertTrue(Dynamics.eval(stipple.size,inputs(.1f))<Dynamics.eval(stipple.size,inputs(1f)))

        val dashes=modes.getValue("dashes")
        val line=assertNotNull(dashes.line)
        assertTrue(line.dashOn>0f && line.dashOff>0f && line.screenWidthPx==0f)
        assertTrue(cc.joycreator.joybrush.core.vector.LiftShape.wanted(dashes))
        assertTrue(dashes.size.inputs.isEmpty() && dashes.flow.inputs.isEmpty())

        val hatch=modes.getValue("hatch")
        assertEquals(UnibrushModes.HATCH_PATHS,hatch.tip.sampled!!.images)
        assertEquals(SamplePick.byPressure,hatch.tip.sampled!!.pick)
        assertEquals(TipAngleSource.stroke,hatch.tip.angleSource)
        // Constant size and an un-jittered half-tip step are what let one dab's lines meet the next dab's.
        assertTrue(hatch.size.inputs.isEmpty() && hatch.sizeJitter==0f && hatch.tip.sampled!!.spacingJitter==0f)
        assertEquals(.5f,hatch.spacing)
    }

    @Test fun leafAndHatchArtworkAreDistinctAndTheHatchSheetsGetDenserWithPressure() {
        fun cover(path:String)=UnibrushModes.imageMask(path)!!.bytes().sumOf { it.toInt() and 255 }/255.0/(64*64)
        val leaves=UnibrushModes.FOLIAGE_PATHS.map { UnibrushModes.imageMask(it)!! }
        assertEquals(4,leaves.map { it.bytes().toList() }.toSet().size)
        for (path in UnibrushModes.FOLIAGE_PATHS) {
            assertTrue(cover(path) in .05..(.5),path)
            val mask=UnibrushModes.imageMask(path)!!;val bytes=mask.bytes()
            // No leaf is cut off by the edge of its image.
            for (i in 0 until 64) for (edge in listOf(i,63*64+i,i*64,i*64+63)) assertEquals(0,bytes[edge].toInt(),"$path $edge")
        }
        val (open,close,cross)=UnibrushModes.HATCH_PATHS.map(::cover)
        assertTrue(open>.04 && open<close && close<cross && cross<.5,"$open $close $cross")
        val sheets=UnibrushModes.HATCH_PATHS.map { UnibrushModes.imageMask(it)!! }
        for (sheet in sheets) {
            assertTrue(sheet.sample(0f,0f)>.5f)          // a line runs through the middle of every sheet
            assertTrue(sheet.sample(.25f,0f)<.05f)       // and the paper shows between lines
            // Whole lines one tip-half apart: the pattern a dab lays is the one the next dab continues.
            val bytes=sheet.bytes()
            for (y in 12..51) for (x in 0 until 32) assertEquals(bytes[y*64+x],bytes[y*64+x+32],"$x,$y")
        }
        assertTrue(sheets[0].sample(.5f,0f)<.05f && sheets[1].sample(.5f,0f)>.5f)
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
