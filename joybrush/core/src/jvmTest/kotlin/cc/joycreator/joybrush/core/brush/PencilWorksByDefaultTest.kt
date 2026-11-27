package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pencil's standing contract after the owner's device verdict of 2026-10-02: **one preset, no
 * sliders touched**, must span the reference behaviour on its own.
 *
 * The reference is a single Infinite Painter pencil preset with nothing adjusted, producing crisp
 * tapering hairlines and a clean pressure ladder upright, and broad soft heavily-grained shading with
 * feathered edges when leaned. The owner reported ours as "a semi decent marker ish and a terrible
 * pencil".
 *
 * These tests pin the three things that made it a marker, and they pin them as *directions and
 * orderings*, never as absolute taste:
 *
 *  1. pressure alone must move the width upright, or pressure only moves darkness, which IS a marker;
 *  2. an upright light mark must be coherent, not speckled dust;
 *  3. a leaned broad mark must still show tooth at full pressure, and must be toothier than the
 *     upright mark at the same pressure — the tooth belongs to the lean, not to the pressure.
 *
 * They make no claim that the numbers are the owner's final numbers. See
 * `tasks/joybrush/specs/PENCIL_WORKS_BY_DEFAULT_20261002.md`.
 */
class PencilWorksByDefaultTest {
    private val halfTilt = (PI / 4).toFloat()

    private fun pencil(): BrushPreset {
        // Brush Test 01, NOT the shipped `pencil`. The owner asked for the Pencil to be forked so a
        // judgement about one is never a judgement about the other, and then the shipped
        // `brushes/pencil/brush.json` was reverted to exactly what it was before these corrections.
        // So this contract belongs to the fork alone: the shipped Pencil still carries the inverted
        // hardness and the swapped size axes, deliberately, as the control to compare against.
        val p = BrushJson.decode(File(joybrushRoot(), "brushes/brushtest01/brush.json").readText())
        assertEquals(emptyList(), BrushValidate.validate(p), "Brush Test 01 must validate")
        return p
    }

    private fun dabber(p: BrushPreset) = BrushDabber(p, 17L)

    private fun look(p: BrushPreset, pressure: Float, tilt: Float) =
        dabber(p).look(PenSample(0f, 0f, pressure = pressure, tilt = tilt, azimuth = 0f, timeMs = 0.0), 0f, 0)

    /**
     * The mean fraction of the paper's height field that takes paint, for one dab. This is the
     * quantity `depth` actually controls: `jb_heightCoverage` computes `threshold = 1 - level`, so a
     * high level paints more of the field and a low level starves it. Integrating over a uniform
     * height distribution stands in for the texture, and at this brush's crisp `edge` it comes out
     * very close to the level itself — which is why the thresholds below read as plain fractions.
     */
    private fun toothSurvives(p: BrushPreset, depth: Float): Float {
        var total = 0f
        for (step in 0..100) total += GrainMath.heightCoverage(step / 100f, depth, p.paperGrain.edge)
        return total / 101f
    }

    // ------------------------------------------------------------------------------------------
    // 1. Pressure must be a real width driver upright. This is the marker test.
    // ------------------------------------------------------------------------------------------

    @Test fun uprightPressureAloneSpansAHairlineToABoldLineAndTheAngleIsTheBiggerAxis() {
        val p = pencil()
        val hairline = look(p, 0.05f, 0f).radius * 2f
        val bold = look(p, 1f, 0f).radius * 2f
        val ratio = bold / hairline

        // CORRECTED 2026-10-02 after the owner's device pass, which overrode my earlier reading.
        // I had widened the upright pressure ladder to 9x because a low ratio "is a marker". The
        // owner's physical model is the opposite: "the angle needs to determine the size mostly.
        // And the pressure needs to determine the darkness and fill in of the grain", and the size
        // boost from pressing is small — "a dot that is one millimeter might, pressing hard, go to
        // three millimeters", because pressing harder flattens the paper tooth and puts more of the
        // lead in contact rather than spreading it. So the span is about 3x, not 9x, and the angle
        // has to be the dominant width axis or the pencil cannot reach a shading mark at all.
        assertTrue(
            ratio in 2.4f..4.0f,
            "upright pressure must widen the mark by about 3x, not by nothing and not by an order " +
                "of magnitude; hairline=$hairline bold=$bold ratio=$ratio",
        )

        // The angle is the primary axis. At a real drawing speed the leaned mark must be far wider
        // than anything pressure alone can reach.
        val leanedFirm = look(p, 1f, halfTilt).radius * 2f
        assertTrue(
            leanedFirm > bold * 4f,
            "the angle is what makes a shading mark: leaned firm=$leanedFirm against upright " +
                "bold=$bold, which is only a factor of ${leanedFirm / bold}",
        )

        // And no part of the pressure range may be dead.
        var previous = 0f
        for (step in 0..20) {
            val d = look(p, step / 20f, 0f).radius * 2f
            assertTrue(d >= previous, "upright width must not shrink as pressure rises (step $step: $d < $previous)")
            previous = d
        }
    }

    @Test fun theHardEdgeStillShowsThePaperToothAndTheSoftEdgeIsALongFade() {
        val p = pencil()
        // "the hard edge is less affected or not affected at all by the bump of the canvas" — at firm
        // pressure the mark was solid, because the depth curve saturated near 1 and a threshold near
        // zero lets every texel through. The tooth has to survive the top of the pressure range or
        // the tip of the pencil is a marker again.
        val firmUpright = toothSurvives(p, look(p, 1f, 0f).paperDepth)
        assertTrue(
            firmUpright <= 0.9f,
            "firm pressure must not erase the paper entirely; $firmUpright of it survives, so the " +
                "hard edge is smooth and reads as a marker.",
        )
        assertTrue(
            firmUpright >= 0.6f,
            "but the hard edge must still show the canvas bump; only $firmUpright survives, so it is " +
                "scratchy rather than toothy.",
        )
        // "the soft edge is still kind of a hard edge. It needs to be much more a fade."
        assertTrue(
            look(p, 1f, 0f).hardness <= 0.15f,
            "full pressure must reach a genuinely soft edge, got ${look(p, 1f, 0f).hardness}",
        )
        // And the fade has to be a real ramp across the footprint, not a step just inside the rim.
        // `DabLook` has no footprint of its own, so the shape is rebuilt from the preset with the
        // hardness this very pressure produced — which is the number under test.
        val firm = look(p, 1f, 0f)
        val shape = TipShape(p.tip.aspect, p.tip.corner, p.tip.taper, firm.hardness, p.tip.minPx)
        fun at(fraction: Float) = TipMath.coverage(firm.radius * fraction, 0f, firm.radius, firm.angle, shape)
        val mid = at(0.55f)
        val rim = at(0.9f)
        assertTrue(mid in 0.15f..0.95f, "the soft edge must be mid-ramp well inside the rim, got $mid at 0.55r")
        assertTrue(rim < mid, "and must keep falling toward the rim: $rim at 0.9r against $mid at 0.55r")
    }

    @Test fun uprightStillHoldsAFinePointAtLightPressure() {
        val p = pencil()
        // The pre-existing upright detail bound, kept so the ladder above cannot be bought by
        // making the whole brush fat.
        assertTrue(look(p, 0.25f, 0f).radius * 2f <= 5f, "upright detail diameter")
    }

    // ------------------------------------------------------------------------------------------
    // 2. An upright light mark must be a coherent line, not speckled dust.
    // ------------------------------------------------------------------------------------------

    @Test fun uprightLightPressureIsACoherentLineNotSpeckledDust() {
        val p = pencil()
        val light = look(p, 0.08f, 0f)
        val survives = toothSurvives(p, light.paperDepth)
        assertTrue(
            survives >= 0.7f,
            "an upright hairline must be coherent: only $survives of the paper took paint, so the " +
                "light end is speckled dust instead of a clean line.",
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. A leaned broad mark must keep its tooth at full pressure, and be toothier than upright.
    // ------------------------------------------------------------------------------------------

    @Test fun aLeanedBroadStrokeKeepsVisibleToothAtFullPressure() {
        val p = pencil()
        val firm = look(p, 1f, halfTilt)
        val survives = toothSurvives(p, firm.paperDepth)
        assertTrue(
            survives <= 0.65f,
            "a broad leaned mark must still show paper at full pressure; $survives of it survives, " +
                "so firm pressure fills the pits and the stroke is a solid bar.",
        )
    }

    @Test fun theToothBelongsToTheLeanNotToThePressure() {
        val p = pencil()
        // Same pressure, two poses. This is the reference's whole trick: the thin upright ladder is
        // clean and the broad shaded strokes are grainy, and tilt is what separates them.
        for (pressure in listOf(0.15f, 0.45f, 0.9f)) {
            val upright = toothSurvives(p, look(p, pressure, 0f).paperDepth)
            val leaned = toothSurvives(p, look(p, pressure, halfTilt).paperDepth)
            assertTrue(
                leaned < upright - 0.15f,
                "at pressure $pressure the leaned mark must be visibly toothier than the upright one; " +
                    "upright=$upright leaned=$leaned",
            )
        }
    }

    @Test fun pressureStillEngagesThePaperMoreWithinALeanStroke() {
        val p = pencil()
        val soft = look(p, 0.08f, halfTilt).paperDepth
        val firm = look(p, 0.9f, halfTilt).paperDepth
        assertTrue(firm > soft, "paper engagement must still rise with pressure inside one leaned stroke")
    }

    // ------------------------------------------------------------------------------------------
    // 4. Hardness runs the OTHER way. A light touch is a crisp point; a leaned belly is soft.
    // ------------------------------------------------------------------------------------------

    @Test fun pressureSoftensTheEdgeInsteadOfFirmingIt() {
        val p = pencil()
        val light = look(p, 0.08f, 0f).hardness
        val firm = look(p, 1f, 0f).hardness
        assertTrue(
            light > firm,
            "a hairline must be a HARD edge and a laid-down belly a SOFT one, so hardness must FALL " +
                "with pressure; light=$light firm=$firm. JB-6.03 ruled hardnessLight 0.9 / " +
                "hardnessFull 0.15 and the shipped file ran the other way.",
        )
        assertTrue(light >= 0.75f, "a feather touch must still be crisp, got $light")
        assertTrue(firm <= 0.3f, "full pressure must reach a soft edge, got $firm")
    }

    // ------------------------------------------------------------------------------------------
    // 5. A finger has no tilt. It must get the upright detail, not a starved or NaN pencil.
    // ------------------------------------------------------------------------------------------

    @Test fun aPenWithNoTiltSensorStillGetsACoherentPencil() {
        val p = pencil()
        val d = look(p, 0.25f, Float.NaN)
        assertTrue(
            listOf(d.radius, d.hardness, d.paperDepth, d.cap, d.flow).all { it.isFinite() },
            "every live number must stay finite on a finger",
        )
        assertTrue(d.radius * 2f <= 5f, "a finger must get the upright point, not a shading blob")
        // The lean curve is skipped when tilt is NaN, so the base has to carry the upright look on
        // its own. This is the assertion that keeps a NaN-safe base honest.
        assertTrue(
            toothSurvives(p, d.paperDepth) >= 0.7f,
            "with no tilt to lean on, a finger must still draw a coherent line; depth=${d.paperDepth}",
        )
    }
}