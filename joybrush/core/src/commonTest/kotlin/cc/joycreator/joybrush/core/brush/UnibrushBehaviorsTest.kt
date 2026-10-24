package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UnibrushBehaviorsTest {
    private fun brush() = BrushPreset(id = "unibrush", name = "Unibrush", size = Param(40f), flow = Param(.8f))
    private fun sample(pressure: Float = 1f, tilt: Float = 0f) = PenSample(0f, 0f, 0.0, pressure, tilt, .9f, -.6f)

    @Test fun pressureChipsChangeActualWidthAndInk() {
        for (mode in PressureBehavior.entries) {
            val p = BrushBehaviors.pressure(brush(), mode)
            val soft = BrushDabber(p, 1).look(sample(.25f), 0f, 0)
            val firm = BrushDabber(p, 1).look(sample(1f), 0f, 0)
            val width = mode == PressureBehavior.width || mode == PressureBehavior.both
            val ink = mode == PressureBehavior.ink || mode == PressureBehavior.both
            assertEquals(if (width) 5f else 20f, soft.radius, "$mode width")
            assertEquals(20f, firm.radius)
            assertEquals(if (ink) .2f else .8f, soft.flow, "$mode ink")
            assertEquals(.8f, firm.flow)
            assertEquals(p, BrushBehaviors.pressure(p, mode), "selection must be idempotent")
            assertTrue(BrushValidate.validate(p).isEmpty())
        }
    }

    @Test fun pressureWiringPreservesOtherCurvesAndAdditiveBases() {
        val other = InputCurve(BrushInput.speed, listOf(listOf(0f, 0f), listOf(1f, 2f)))
        val p = brush().copy(size = Param(40f, listOf(other), "add"))
        val wired = BrushBehaviors.pressure(p, PressureBehavior.width)
        assertEquals(other, wired.size.inputs.first())
        assertEquals(5f, BrushDabber(wired, 1).look(sample(.25f), 0f, 0).radius)
        val none = BrushBehaviors.pressure(wired, PressureBehavior.none)
        assertEquals(p, none)
    }

    @Test fun tiltChipsProduceRealRotationFlatteningAndSize() {
        val angled = BrushBehaviors.tilt(brush(), TiltBehavior.angle)
        assertEquals(.9f, BrushDabber(angled, 1).look(sample(), 0f, 0).angle, .00001f)
        val flattened = BrushBehaviors.tilt(brush(), TiltBehavior.flatten)
        assertEquals(0f, BrushDabber(flattened, 1).look(sample(), 0f, 0).aspect)
        assertEquals(.85f, BrushDabber(flattened, 1).look(sample(tilt = (PI / 2).toFloat()), 0f, 0).aspect)
        val bigger = BrushBehaviors.tilt(brush(), TiltBehavior.size)
        assertEquals(40f, BrushDabber(bigger, 1).look(sample(tilt = (PI / 2).toFloat()), 0f, 0).radius)
        assertEquals(20f, BrushDabber(bigger, 1).look(sample(tilt = Float.NaN), 0f, 0).radius)
        for (p in listOf(angled, flattened, bigger)) assertTrue(BrushValidate.validate(p).isEmpty())
        for (mode in TiltBehavior.entries) {
            val p = BrushBehaviors.tilt(brush(), mode)
            assertEquals(p, BrushBehaviors.tilt(p, mode))
            val cleared = BrushBehaviors.tilt(p, TiltBehavior.none)
            val upright = BrushDabber(cleared, 1).look(sample(), 0f, 0)
            val leaned = BrushDabber(cleared, 1).look(sample(tilt = (PI / 2).toFloat()), 0f, 0)
            assertEquals(upright.radius, leaned.radius)
            assertEquals(upright.aspect, leaned.aspect)
            assertEquals(upright.angle, leaned.angle)
        }
    }

    @Test fun flattenPreservesAdditivePressureAndAlwaysMakesTheTipThinner() {
        val pressure = InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, .1f)))
        val p = brush().copy(tip = TipSpec(aspectDynamics = Param(.1f, listOf(pressure), "add")))
        val changed = BrushBehaviors.tilt(p, TiltBehavior.flatten)
        assertEquals(pressure, changed.tip.aspectDynamics!!.inputs.first())
        assertEquals(.2f, BrushDabber(changed, 1).look(sample(), 0f, 0).aspect, .00001f)
        for (base in listOf(-.95f, .95f)) {
            val thin = BrushBehaviors.tilt(brush().copy(tip = TipSpec(aspect = base)), TiltBehavior.flatten)
            val leaned = BrushDabber(thin, 1).look(sample(tilt = (PI / 2).toFloat()), 0f, 0).aspect
            assertTrue(kotlin.math.abs(leaned) > kotlin.math.abs(base))
            assertTrue(leaned * base > 0f)
        }
    }

    @Test fun customRecipesAreRefusedInsteadOfInstallingDeadChipsOrErasingOtherInputs() {
        val speed = InputCurve(BrushInput.speed, listOf(listOf(0f, 0f), listOf(1f, .8f)))
        val p = brush().copy(flow = Param(0f, listOf(speed), "add"))
        assertFailsWith<BrushException> { BrushBehaviors.pressure(p, PressureBehavior.ink) }
        val customAspect = brush().copy(tip = TipSpec(aspectDynamics = Param(.4f, listOf(speed))))
        assertFailsWith<BrushException> { BrushBehaviors.tilt(customAspect, TiltBehavior.flatten) }
        val crossing = InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, -.2f)))
        val changesAxis = brush().copy(tip = TipSpec(aspectDynamics = Param(.1f, listOf(crossing), "add")))
        assertFailsWith<BrushException> { BrushBehaviors.tilt(changesAxis, TiltBehavior.flatten) }
    }

    @Test fun additivePressureOnlyBrushDoesNotDisappearWhenChoosingAChip() {
        val pressure = InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, .8f)))
        val p = brush().copy(flow = Param(0f, listOf(pressure), "add"))
        for (mode in PressureBehavior.entries) {
            val wired = BrushBehaviors.pressure(p, mode)
            val ink = mode == PressureBehavior.ink || mode == PressureBehavior.both
            val soft = BrushDabber(wired, 1).look(sample(.25f), 0f, 0)
            assertEquals(if (ink) .2f else .8f, soft.flow, .00001f)
            assertEquals(.8f, BrushDabber(wired, 1).look(sample(), 0f, 0).flow, .00001f)
            assertEquals(wired, BrushBehaviors.pressure(wired, mode))
        }
    }

    @Test fun fullPressureSizeCannotMakeAnInvalidPresetOrChangeAnAdvancedCurve() {
        val pressure = InputCurve(BrushInput.pressure, listOf(listOf(0f, 0f), listOf(1f, 2f)))
        val huge = brush().copy(size = Param(4096f, listOf(pressure)))
        val wired = BrushBehaviors.pressure(huge, PressureBehavior.none)
        assertEquals(4096f, wired.size.base)
        assertTrue(BrushValidate.validate(wired).isEmpty())
        val speed = InputCurve(BrushInput.speed, listOf(listOf(0f, .5f), listOf(1f, .5f)))
        assertFailsWith<BrushException> {
            BrushBehaviors.pressure(huge.copy(size = huge.size.copy(inputs = huge.size.inputs + speed)), PressureBehavior.none)
        }
    }

    @Test fun explicitRotationAddsOffsetAndDoesNotChangeTheRandomStream() {
        val p = brush().copy(sizeJitter = .3f, angleJitter = 0f, tip = TipSpec(angle = Param(30f)))
        val fixed = BrushDabber(BrushBehaviors.rotation(p, TipAngleSource.fixed), 51)
        val lean = BrushDabber(BrushBehaviors.rotation(p, TipAngleSource.penAzimuth), 51)
        val barrel = BrushDabber(BrushBehaviors.rotation(p, TipAngleSource.penBarrel), 51)
        repeat(10) { i ->
            val s = sample().copy(x = i * 10f)
            val a = fixed.look(s, i * 10f, i)
            val b = lean.look(s, i * 10f, i)
            val c = barrel.look(s, i * 10f, i)
            assertEquals(a.radius, b.radius)
            assertEquals(a.radius, c.radius)
            assertEquals((PI / 6).toFloat(), a.angle, .00001f)
            assertEquals(a.angle + .9f, b.angle, .00001f)
            assertEquals(a.angle - .6f, c.angle, .00001f)
        }
    }

    @Test fun oldJsonKeepsLegacySemanticsAndNewSourcesRequireTheirVersion() {
        val old = brush().copy(version = 1, tip = TipSpec(followDirection = true))
        val json = BrushJson.encode(old)
        assertFalse(json.contains("angleSource"), "do not add a null extension to old files")
        assertEquals(old, BrushJson.decodeChecked(json))
        for (source in TipAngleSource.entries) {
            val current = BrushBehaviors.rotation(old, source)
            val text = BrushJson.encode(current)
            assertEquals(current, BrushJson.decodeChecked(text))
            val forged = text.replace("\"version\": 12", "\"version\": 11")
            assertFailsWith<BrushException> { BrushJson.decodeChecked(forged) }
            assertFailsWith<BrushException> { BrushJson.decode(text.replace("\"angleSource\": \"${source.name}\"", "\"angleSource\": \"future\"")) }
        }
    }

    @Test fun everyRotationAndBehaviorReplaysTheLiveStreamAfterCodecRoundtrip() {
        val samples = (0..45).map { i -> PenSample(i * 3f, (i % 11) * 2f, i * 8.0,
            pressure = .15f + (i % 9) / 10f, tilt = i / 40f, azimuth = 3f - i * .14f, barrel = -.8f + i * .1f) }
        val record = StrokeRecord("line", "unibrush", 47L, .35f, 1.5f, samples)
        val restored = StrokeCodec.decodeAll(StrokeCodec.encodeAll(listOf(record))).single()
        for (source in TipAngleSource.entries) for (pressure in PressureBehavior.entries) for (tilt in TiltBehavior.entries) {
            val p = BrushBehaviors.rotation(BrushBehaviors.tilt(BrushBehaviors.pressure(brush(), pressure), tilt), source)
                .copy(sizeJitter = .2f, angleJitter = 13f, response = ResponseSpec(
                    pressure = listOf(.2f, .05f, .7f, .9f), tilt = listOf(.3f, .1f, .8f, .7f)))
            val dabber = BrushDabber(p, record.seed)
            val placer = DabPlacer(dabber.spacing, dabber::look)
            val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
            val live = ArrayList<Dab>()
            for (s in samples) live.addAll(placer.add(smoother.add(p.response.apply(s))))
            live.addAll(placer.add(smoother.finish()))
            assertTrue(live.size > 30)
            assertEquals(live, InkReplay.dabs(restored, p), "$source/$pressure/$tilt")
            assertNotEquals(live, InkReplay.dabs(restored.copy(seed = 48L), p), "seed control must be non-vacuous")
        }
    }
}
