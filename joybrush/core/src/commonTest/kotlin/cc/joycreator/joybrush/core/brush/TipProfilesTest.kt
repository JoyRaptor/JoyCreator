package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.*
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.PI
import kotlin.test.*

class TipProfilesTest {
    private fun brush() = BrushPreset(id = "profiles", name = "Unibrush", size = Param(30f),
        tip = TipSpec(hardness = Param(0f), profile = TipProfileSpec(TipProfile.gaussian)))

    @Test fun radialProfilesHaveFiniteSupportAndActuallyDiffer() {
        for (kind in TipProfile.entries) {
            val p = TipProfileSpec(kind)
            assertEquals(1f, TipProfiles.coverage(p, 0f))
            assertEquals(0f, TipProfiles.coverage(p, 1f))
            for (i in 0..100) assertTrue(TipProfiles.coverage(p, i / 100f) in 0f..1f)
        }
        assertTrue(TipProfiles.coverage(TipProfileSpec(TipProfile.gaussian), .5f) < .4f)
        assertEquals(.5f, TipProfiles.coverage(TipProfileSpec(TipProfile.linear), .5f))
        val custom = TipProfileSpec(TipProfile.custom, listOf(listOf(0f, 1f), listOf(.3f, .9f), listOf(1f, 0f)))
        assertTrue(TipProfiles.coverage(custom, .3f) > .89f)
        val samples = TipProfiles.samples(custom)
        samples.fill(0f)
        assertTrue(TipProfiles.coverage(custom, .3f) > .89f, "uploader must not mutate the replay cache")
    }

    @Test fun explicitSmoothMatchesLegacyAndZeroFadeIsAnExactNoOp() {
        for (aspect in listOf(0f, -.7f, .6f)) for (corner in listOf(2f, 6f)) {
            val old = TipShape(aspect = aspect, corner = corner, taper = .2f, hardness = .4f)
            for (y in -20..20) for (x in -20..20) {
                val legacy = TipMath.coverage(x + .5f, y + .5f, 18f, .6f, old)
                assertEquals(legacy, TipMath.coverage(x + .5f, y + .5f, 18f, .6f, old.copy(fadeAngle = 1.9f)))
                assertEquals(legacy, TipMath.coverage(x + .5f, y + .5f, 18f, .6f,
                    old.copy(profile = TipProfileSpec(TipProfile.smooth))), .000001f)
            }
        }
    }

    @Test fun canvasRelativeFadeKeepsItsAngleOnAThinRotatedNib() {
        val angle = (PI / 3).toFloat()
        val tip = TipShape(aspect = .7f, hardness = 1f, profile = TipProfileSpec(TipProfile.hard),
            fadeAmount = 1f, fadeAngle = -angle)
        val top = TipMath.coverage(0f, 1f, 30f, angle, tip)
        val bottom = TipMath.coverage(0f, -1f, 30f, angle, tip)
        assertEquals(top, bottom, .00001f, "horizontal canvas gradient must not acquire a vertical component")
        assertTrue(TipMath.coverage(-2f, 0f, 30f, angle, tip) > TipMath.coverage(2f, 0f, 30f, angle, tip))
    }

    @Test fun fadeFramesResolveAfterTipOffsetAndFollowPressureOrTilt() {
        val sample = PenSample(0f, 0f, 0.0, .25f, (PI / 4).toFloat(), .7f)
        val pressure = Param(1f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, 1f)))))
        for (frame in FadeFrame.entries) {
            val p = brush().copy(tip = brush().tip.copy(angle = Param(45f), fade = TipFadeSpec(pressure, 30f, frame)))
            val look = BrushDabber(p, 4).look(sample, 0f, 0)
            assertEquals(.25f, look.fadeAmount)
            val expected = (PI / 6).toFloat() + when (frame) {
                FadeFrame.tip -> 0f
                FadeFrame.canvas -> -look.angle
                FadeFrame.penAzimuth -> .7f - look.angle
            }
            assertEquals(expected, look.fadeAngle, .00001f)
        }
        val tilted = BrushBehaviors.tilt(brush(), TiltBehavior.sideFade)
        assertEquals(.5f, BrushDabber(tilted, 4).look(sample, 0f, 0).fadeAmount, .00001f)
        assertEquals(0f, BrushDabber(tilted, 4).look(sample.copy(tilt = Float.NaN, azimuth = Float.NaN), 0f, 0).fadeAmount,
            "a finger must not acquire full side shading from a missing tilt sensor")
        val none = BrushBehaviors.tilt(tilted, TiltBehavior.none)
        assertEquals(0f, BrushDabber(none, 4).look(sample, 0f, 0).fadeAmount)
    }

    @Test fun versionAndValidationRefuseLyingOrBrokenProfiles() {
        val p = brush().copy(version = 1)
        val json = BrushJson.encode(p)
        assertTrue(json.contains("\"version\": 13"))
        assertFailsWith<BrushException> { BrushJson.decode(json.replace("\"version\": 13", "\"version\": 12")) }
        assertEquals(listOf("hard", "linear", "smooth", "gaussian", "custom"), TipProfile.entries.map { it.name })
        assertEquals(listOf("tip", "canvas", "penAzimuth"), FadeFrame.entries.map { it.name })
        assertEquals(listOf(1, 2, 3, 4, 5), TipProfile.entries.map { it.code })
        val current = brush()
        for (bad in listOf(
            current.copy(engine = "smudge"),
            current.copy(tip = current.tip.copy(profile = TipProfileSpec(curve = listOf(listOf(0f, 1f))))),
            current.copy(tip = current.tip.copy(profile = TipProfileSpec(curve = listOf(listOf(0f, 1f), listOf(0f, 0f))))),
            current.copy(tip = current.tip.copy(fade = TipFadeSpec(Param(Float.NaN)))),
            current.copy(tip = current.tip.copy(fade = TipFadeSpec(angle = Float.POSITIVE_INFINITY))),
        )) assertTrue(BrushValidate.validate(bad).isNotEmpty())
    }

    @Test fun tiltChoicesPreserveOrRefuseCustomFadeInputs() {
        val pressure = InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, 1f)))
        val tilt = InputCurve(BrushInput.tilt, listOf(listOf(0f, 0f), listOf(1f, 1f)))
        val p = brush().copy(tip = brush().tip.copy(fade = TipFadeSpec(Param(1f, listOf(pressure, tilt)))))
        val none = BrushBehaviors.tilt(p, TiltBehavior.none)
        assertEquals(listOf(pressure), none.tip.fade!!.amount.inputs)
        assertEquals(.25f, BrushDabber(none, 1).look(PenSample(0f, 0f, 0.0, .25f), 0f, 0).fadeAmount)
        assertFailsWith<BrushException> { BrushBehaviors.tilt(p, TiltBehavior.sideFade) }
    }

    @Test fun rawReplayAndCopiesKeepEveryProfileAndFadeValue() {
        val samples = (0..36).map { i -> PenSample(i * 3f, (i % 7) * 2f, i * 9.0,
            .2f + (i % 8) * .1f, i / 30f, 2.8f - i * .15f) }
        val record = StrokeRecord("line", "profiles", 25, .3f, 1f, samples)
        val restored = StrokeCodec.decodeAll(StrokeCodec.encodeAll(listOf(record))).single()
        for (kind in TipProfile.entries) for (frame in FadeFrame.entries) {
            val profile = TipProfileSpec(kind)
            val p = BrushBehaviors.tilt(brush().copy(tip = brush().tip.copy(profile = profile)), TiltBehavior.sideFade)
                .let { it.copy(tip = it.tip.copy(fade = it.tip.fade!!.copy(relativeTo = frame))) }
            val dabber = BrushDabber(p, record.seed)
            val placer = DabPlacer(dabber.spacing, dabber::look)
            val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
            val live = ArrayList<Dab>()
            for (s in samples) live.addAll(placer.add(smoother.add(p.response.apply(s))))
            live.addAll(placer.add(smoother.finish()))
            val replayed = InkReplay.dabs(restored, BrushJson.decodeChecked(BrushJson.encode(p)))
            assertEquals(live, replayed)
            assertTrue(live.size > 40 && live.all { it.profile === profile })
            assertTrue(live.map { it.fadeAmount }.toSet().size > 5)
            for (d in live.take(4)) {
                val moved = d.copy(x = d.x + 5f, radius = d.radius * 2f)
                assertSame(profile, moved.profile)
                assertEquals(profile, moved.tipShape(TipShape()).profile)
            }
        }
    }
}
