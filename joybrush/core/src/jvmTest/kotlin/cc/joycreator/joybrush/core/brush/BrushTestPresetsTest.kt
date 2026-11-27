package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's two experimental brushes, forked from the Pencil so that a judgement about one is never
 * a judgement about the other.
 *
 * Owner direction, verbatim: *"clone this pencil and give it a different rename. like brush-test-01 …
 * and essentially fork it. So do what you were going to do before to make it an excellent pencil, and
 * then take the previous version and see how much you can push it to be like a watercolor … We can
 * save them out and then later clean up the ones that we don't like once we have the ability to
 * curate everything that we've built."*
 *
 * **Brush Test 01** is the Pencil with three corrections, each traceable to the owner's device pass:
 *
 *  1. *"the angle needs to determine the size mostly. And the pressure needs to determine the
 *     darkness and fill in of the grain"* — so pressure is a ~3x width axis and the angle is primary.
 *     The shipped Pencil had these almost exactly swapped, at 32x for tilt and 1.75x for pressure.
 *  2. *"the soft edge is still kind of a hard edge. It needs to be much more a fade"* — hardness FALLS
 *     with pressure, which is `JB-6.03`'s already-ruled tuning (`hardnessLight 0.9 / hardnessFull
 *     0.15`) and the exact opposite of what shipped.
 *  3. *"the hard edge is less affected or not affected at all by the bump of the canvas"* — the paper
 *     tooth belongs to the LEAN, because leaning is what exposes the lead's own grading, while
 *     upright must stay coherent enough to be a clean hairline.
 *
 * **Brush Test 02** is 01's geometry with one theme changed: wet. It exists to answer *"how much can
 * this be pushed toward a watercolour"* — and the honest answer, recorded in
 * `tasks/joybrush/specs/BRUSH_DEFECTS_20261002.md`, is **not far**, because edge darkening, bloom and
 * wet-lite flow are JB-6.01 and JB-6.02 and there is no code for any of them. What 02 can show is a
 * wet, granulating, soft-edged wash.
 *
 * These are experiments, not deliverables. They are on the shelf so the owner can hold them, and they
 * are free to delete.
 */
class BrushTestPresetsTest {
    private val halfTilt = (PI / 4).toFloat()

    private fun preset(folder: String): BrushPreset {
        val p = BrushJson.decode(File(joybrushRoot(), "brushes/$folder/brush.json").readText())
        assertEquals(emptyList(), BrushValidate.validate(p), folder)
        return p
    }

    private fun look(p: BrushPreset, pressure: Float, tilt: Float) =
        BrushDabber(p, 17L).look(PenSample(0f, 0f, pressure = pressure, tilt = tilt, azimuth = 0f, timeMs = 0.0), 0f, 0)

    /** Mean fraction of the paper height field that takes paint — how much tooth survives. */
    private fun tooth(p: BrushPreset, depth: Float): Float {
        var total = 0f
        for (step in 0..100) total += GrainMath.heightCoverage(step / 100f, depth, p.paperGrain.edge)
        return total / 101f
    }

    // ── both are real, distinct, on the shelf, and forks rather than replacements ────────────

    @Test fun bothExperimentsAreOnTheShelfOnceWithTheirOwnIdentity() {
        val entries = File(joybrushRoot(), "brushes/index.txt").readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val one = preset("brushtest01")
        val two = preset("brushtest02")
        val pencil = preset("pencil")
        assertEquals(1, entries.count { it == "brushtest01" }, "Brush Test 01 is on the shelf once")
        assertEquals(1, entries.count { it == "brushtest02" }, "Brush Test 02 is on the shelf once")
        assertTrue(one.id != two.id && one.id != pencil.id && two.id != pencil.id,
            "the experiments are forks, so no two may share an identity")
        assertEquals(3, listOf(one, two, pencil).map { it.id }.toSet().size, "three distinct identities")
    }

    @Test fun bothExperimentsSpeakTheContactDialectSoTheyBehaveLikeThePencil() {
        for (folder in listOf("brushtest01", "brushtest02")) {
            val p = preset(folder)
            assertTrue(p.version >= VERSION_CONTACT, "$folder must be a contact brush, got v${p.version}")
            assertEquals("stamp", p.engine, "$folder")
            assertTrue(p.tip.anchorDynamics != null && p.tip.aspectDynamics != null,
                "$folder keeps the leaned side contact; without it they are just a marker")
            assertTrue(p.paperGrain.enabled, "$folder reads the document paper")
        }
    }

    // ── 01: the corrected graphite pencil ─────────────────────────────────────────────────────

    @Test fun brushTestOneHasTheAngleAsItsPrimaryWidthAxis() {
        val p = preset("brushtest01")
        val uprightBold = look(p, 1f, 0f).radius * 2f
        val leanedFirm = look(p, 1f, halfTilt).radius * 2f
        assertTrue(leanedFirm > uprightBold * 4f,
            "the angle must be what makes a shading mark: leaned=$leanedFirm upright=$uprightBold")
    }

    @Test fun brushTestOneWidensByAboutThreeTimesUnderPressureAndNoMore() {
        val p = preset("brushtest01")
        val ratio = look(p, 1f, 0f).radius * 2f / (look(p, 0.05f, 0f).radius * 2f)
        assertTrue(ratio in 2.4f..4.0f,
            "the owner's size law is 'a one millimetre dot might go to three', got $ratio")
    }

    @Test fun brushTestOneIsCrispAtTheTipAndFadesAtTheBelly() {
        val p = preset("brushtest01")
        val light = look(p, 0.08f, 0f).hardness
        val firm = look(p, 1f, 0f).hardness
        assertTrue(light > firm, "hardness must FALL with pressure: light=$light firm=$firm")
        assertTrue(light >= 0.75f, "a feather touch stays crisp, got $light")
        assertTrue(firm <= 0.15f, "a laid-down belly is a real fade, got $firm")
    }

    @Test fun brushTestOneKeepsToothOnTheHardEdgeAndGivesTheLeanItsOwn() {
        val p = preset("brushtest01")
        // "the hard edge is less affected or not affected at all by the bump of the canvas"
        val firmUpright = tooth(p, look(p, 1f, 0f).paperDepth)
        assertTrue(firmUpright in 0.6f..0.9f,
            "the hard edge must still show the paper: $firmUpright survives, wanted 0.6-0.9")
        // An upright hairline has to be coherent, not speckled dust.
        assertTrue(tooth(p, look(p, 0.08f, 0f).paperDepth) >= 0.7f, "an upright hairline must be clean")
        // And leaning is what exposes the lead's own grading.
        val leaned = tooth(p, look(p, 0.9f, halfTilt).paperDepth)
        assertTrue(leaned < firmUpright - 0.2f,
            "a leaned mark must be visibly grainier than a firm upright one: $leaned against $firmUpright")
    }

    @Test fun brushTestOneKeepsTheNearEdgeFirmerThanTheFarBody() {
        // The mechanism the Pencil already had and must not lose: `tiltGradient` aims a plane along
        // the lean, so the side nearest the tip takes more paint than the trailing body.
        val p = preset("brushtest01")
        // The mechanism the Pencil already had and must not lose: `tiltGradient` aims a plane along
        // the lean, so the side nearest the tip takes more paint than the trailing body.
        // `DabLook` carries no tilt of its own — the tilt is the input we just evaluated — so the
        // amount is taken from the pose rather than read back off the dab.
        val amount = GrainMath.tiltAmount(halfTilt)
        val d = look(p, 0.25f, halfTilt)
        fun level(x: Float) = GrainMath.grainLevel(d.paperDepth, 1f, x, 0f, 1f, 0f, amount,
            p.paperGrain.tiltGradient, p.paperGrain.radial)
        assertTrue(level(-d.anchor + 0.55f) > level(-d.anchor - 0.55f),
            "near the tip must take more paint than the far body")
    }

    // ── 02: the same instrument, pushed wet ──────────────────────────────────────────────────

    @Test fun brushTestTwoIsBrushTestOneWithOneThemeChanged() {
        val one = preset("brushtest01")
        val two = preset("brushtest02")
        // Identical contact geometry, so the only variables are wetness and how far over it reaches.
        assertEquals(one.tip.aspectDynamics, two.tip.aspectDynamics, "same leaned shape")
        assertEquals(one.tip.anchorDynamics, two.tip.anchorDynamics, "same anchor")
        assertEquals(one.tip.angle, two.tip.angle, "same lean-following angle")
        assertEquals(one.tip.corner, two.tip.corner, "same tip roundness")
        assertEquals(one.tip.minPx, two.tip.minPx, "same hairline floor")
        assertEquals(one.size.base, two.size.base, "same size base")
        // The pressure law is deliberately SHARED: both obey the owner's ~3x rule, so 02 is an A/B of
        // 01 on wetness and not on how hard you press.
        val pressureLaw = { p: BrushPreset -> p.size.inputs.first { it.input == BrushInput.pressure }.curve }
        assertEquals(pressureLaw(one), pressureLaw(two), "same pressure width law")
        // These two differ on purpose, and saying so is the point of an experiment file.
        assertTrue(two.paper.wet > 0.5f, "02 must actually be wet, got wet=${two.paper.wet}")
        assertTrue(one.paper.wet < 0.1f, "01 is graphite, not a wash")
    }

    @Test fun brushTestTwoIsSofterAndGrainierThanTheGraphiteOne() {
        val one = preset("brushtest01")
        val two = preset("brushtest02")
        // A wash has no hard edge anywhere, so its hardness sits below 01's at every pressure.
        for (pressure in listOf(0.1f, 0.5f, 1f)) {
            assertTrue(
                look(two, pressure, 0f).hardness < look(one, pressure, 0f).hardness,
                "at pressure $pressure a wash must be softer than graphite",
            )
        }
        // Granulation: pigment settling into the tooth, so LESS of the field takes paint.
        val oneTooth = tooth(one, look(one, 0.9f, 0f).paperDepth)
        val twoTooth = tooth(two, look(two, 0.9f, 0f).paperDepth)
        assertTrue(twoTooth < oneTooth, "02 must granulate more than 01: $twoTooth against $oneTooth")
        // And it carries more water, which is what "wet" means here.
        assertTrue(two.flow.base > one.flow.base, "02 must carry more fluid: ${two.flow.base} against ${one.flow.base}")
    }

    @Test fun brushTestTwoStillHoldsTheShapeContractSoTheComparisonIsHonest() {
        // If 02 lost the leaned side contact it would not be an A/B of 01 at all, it would be a
        // different instrument wearing the same name.
        val two = preset("brushtest02")
        val side = look(two, 0.25f, halfTilt)
        assertTrue(side.radius * 2f >= 45f, "02 must still expose a real side at 45 degrees, got ${side.radius * 2f}")
        assertTrue(side.aspect < -0.7f, "and it must stay narrow across its long axis")
        assertTrue(side.anchor >= 0.8f, "and it must still grow away from the point")
    }

    // ── the honest limit of 02 ────────────────────────────────────────────────────────────────

    @Test fun brushTestTwoCannotYetReadAsWatercolourAndTheTestSaysWhy() {
        // Recorded rather than asserted-as-fixed: there is no edge darkening, no bloom and no
        // wet-lite flow anywhere in joybrush. Those are JB-6.01 and JB-6.02 and every row in Phase 6
        // is still an outline. So 02 is a wet granulating wash and the thing that makes watercolour
        // recognisable — where the wet edge sits and how it darkens as it dries — is NOT in this file
        // and cannot be, because the code it would need does not exist.
        val two = preset("brushtest02")
        assertTrue(two.paper.wet > 0.5f, "the wet response is what 02 can actually exercise")
        // A brush file cannot ask for bleed or bloom, because no such field exists. This is the
        // tripwire: if one is ever added to `BrushPreset` it appears in the data class's toString and
        // this fails, at which point JB-6.01 has landed and "02 is the whole watercolour story" is no
        // longer true and the file needs rewriting rather than retiring.
        val shape = two.toString().lowercase()
        assertTrue("bleed" !in shape && "bloom" !in shape,
            "the brush format grew a bleed/bloom field, so Phase 6 has landed and 02 is no longer the " +
                "whole watercolour story — revisit this file")
    }

    // ── 03: the watercolour attempt ──────────────────────────────────────────────────────────

    @Test fun brushTestThreeIsOnTheShelfOnceAndIsTheWetOne() {
        val entries = File(joybrushRoot(), "brushes/index.txt").readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val three = preset("brushtest03")
        assertEquals(1, entries.count { it == "brushtest03" }, "Brush Test 03 is on the shelf once")
        assertEquals(4, listOf(preset("brushtest01").id, preset("brushtest02").id, three.id, preset("pencil").id)
            .toSet().size, "four distinct identities")
        assertTrue(three.paper.wet > 0.85f, "03 must be as wet as the format allows, got ${three.paper.wet}")
    }

    @Test fun brushTestThreeUsesEveryLeverThatActuallyExistsForAWash() {
        val three = preset("brushtest03")
        // "buildup" is the one that pools: every dab writes into a stroke buffer at full and the
        // opacity curve is applied once at commit (`BrushDabber`, `GlPaintEngine.u_strokeScale`), which
        // is how a wash loads up and then settles rather than drying where it landed.
        assertEquals("buildup", three.accumulate, "03 must pool and tone at commit")
        assertTrue(three.flow.base > 0.7f, "and carry real fluid, got ${three.flow.base}")
        assertTrue(three.spacing >= 0.05f, "wide spacing so consecutive dabs overlap into a spread")
        // A wash has no edge at all, so hardness must reach near zero at full pressure.
        assertTrue(look(three, 1f, 0f).hardness <= 0.06f, "no hard edge anywhere, got ${look(three, 1f, 0f).hardness}")
        // And pigment settles, so granulation: much less of the paper takes paint than graphite leaves.
        val graphite = preset("brushtest01")
        assertTrue(tooth(three, look(three, 1f, 0f).paperDepth) < tooth(graphite, look(graphite, 1f, 0f).paperDepth),
            "03 must granulate more than the graphite brush")
    }

    @Test fun brushTestThreeCannotBleedPastItsOwnEdgeAndSaysSo() {
        // The owner's brief: "try to turn it into a nice watercolor brush with bleed". The wet, the
        // pooling and the granulation are reachable from a file. BLEED is not: it means pigment
        // travelling into damp paper beyond the mark and leaving a darker rim as it dries, which is
        // commit-time behaviour — JB-6.01 edge darkening and JB-6.02 wet-lite flow. There is no code
        // for either, no field in the format that asks for it, and every Phase 6 row is an outline.
        // So 03 can be a wet granulating wash that spreads ALONG a stroke and nothing more.
        val three = preset("brushtest03")
        val shape = three.toString().lowercase()
        assertTrue("bleed" !in shape && "bloom" !in shape,
            "the format grew a bleed/bloom field: JB-6.01 has landed and this file is now wrong")
    }

    // ── the shipped Pencil is the CONTROL, and must stay untouched ───────────────────────────

    @Test fun theShippedPencilIsDeliberatelyTheUncorrectedControl() {
        // The owner asked for the Pencil to be forked so the experiment costs nothing, so
        // `brushes/pencil/brush.json` was reverted to exactly what shipped. These assertions pin it
        // there ON PURPOSE: they will fail the moment someone helpfully "fixes" it, which is the
        // signal to leave it alone — the three experiments are only meaningful next to the original.
        val p = preset("pencil")
        val hardness = p.tip.hardness.inputs.first { it.input == BrushInput.pressure }.curve
        assertTrue(hardness.last()[1] > hardness.first()[1],
            "the shipped Pencil still FIRSMS with pressure; if this fails someone corrected it and " +
                "the control is gone: $hardness")
        val pressure = p.size.inputs.first { it.input == BrushInput.pressure }.curve
        assertTrue(pressure.last()[1] < 1.5f,
            "the shipped Pencil still has almost no pressure width axis; if this fails someone " +
                "corrected it and the control is gone: $pressure")
    }
}