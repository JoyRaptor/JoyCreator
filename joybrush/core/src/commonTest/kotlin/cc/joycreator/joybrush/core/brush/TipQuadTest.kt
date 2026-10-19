package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.*
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.PI
import kotlin.test.*

class TipQuadTest {
    private val a = QuadShapeSpec(profile = TipProfileSpec(TipProfile.gaussian))
    private val b = QuadShapeSpec(points = listOf(QuadPoint(-.8f, -.5f), QuadPoint(.2f, -1f),
        QuadPoint(1f, .6f), QuadPoint(-.9f, 1f)), roundness = .1f, aspect = .7f,
        profile = TipProfileSpec(TipProfile.hard))
    private fun brush(q: TipQuadSpec = TipQuadSpec(a, b)) = BrushPreset(id = "quad", name = "Quad", size = Param(26f),
        tip = TipSpec(quad = q, angleSource = TipAngleSource.stroke), sizeJitter = .3f, angleJitter = 20f,
        response = ResponseSpec(pressure = listOf(.15f, .35f, .8f, .9f)))
    private fun drive(input: BrushInput) = Param(0f, listOf(InputCurve(input, listOf(listOf(0f, 0f), listOf(1f, 1f)))), "add")

    @Test fun morphIncludesEveryEndpointParameterWithoutChangingRandomDraws() {
        val q = TipQuadSpec(a, b, drive(BrushInput.pressure))
        for (t in listOf(0f, .25f, .5f, .75f, 1f)) {
            val sample = PenSample(2f, 3f, 0.0, t)
            val dab = BrushDabber(brush(q), 17).look(sample, 0f, 0)
            val legacy = BrushDabber(brush(q).copy(tip = brush(q).tip.copy(quad = null)), 17).look(sample, 0f, 0)
            assertEquals(TipQuads.interpolate(a, b, t), dab.quad)
            assertEquals(t, dab.profileMix, .00001f)
            assertEquals(.7f * t, dab.aspect, .00001f)
            assertSame(a.profile, dab.profile); assertSame(b.profile, dab.profileB)
            assertEquals(legacy.radius, dab.radius); assertEquals(legacy.angle, dab.angle)
        }
        assertEquals(TipQuads.interpolate(a, null, 1f), TipQuads.interpolate(a, b, 0f))
        assertEquals(TipQuads.interpolate(b, null, 0f), TipQuads.interpolate(a, b, 1f))
    }

    @Test fun tiltMorphUsesAForMissingSensorAndSpeedMorphUsesFilteredMotion() {
        val tilted = brush(TipQuadSpec(a, b, drive(BrushInput.tilt)))
        val finger = BrushDabber(tilted, 1).look(PenSample(0f, 0f, 0.0), 0f, 0)
        assertEquals(0f, finger.profileMix)
        val half = BrushDabber(tilted, 1).look(PenSample(0f, 0f, 0.0, tilt = (PI / 4).toFloat()), 0f, 0)
        assertEquals(.5f, half.profileMix, .00001f)
        val sped = BrushDabber(brush(TipQuadSpec(a, b, drive(BrushInput.speed))), 1)
        assertEquals(0f, sped.look(PenSample(0f, 0f, 0.0), 0f, 0).profileMix)
        assertTrue(sped.look(PenSample(100f, 0f, 25.0), 100f, 1).profileMix > 0f)
    }

    @Test fun aspectInheritanceAndProfileBlendAreContinuousAndBounded() {
        val inherited = a.copy(aspect = null)
        assertNull(TipQuads.aspect(inherited, inherited, .5f, .2f))
        assertEquals(.45f, TipQuads.aspect(inherited, b, .5f, .2f)!!, .00001f)
        val shape = TipQuads.interpolate(a, b, .5f)
        val first = TipShape(quad = shape, profile = a.profile, hardness = 0f)
        val second = first.copy(profile = b.profile)
        for (x in -20..20) for (y in -20..20) {
            val ca = TipMath.coverage(x + .2f, y + .3f, 20f, .4f, first)
            val cb = TipMath.coverage(x + .2f, y + .3f, 20f, .4f, second)
            val mixed = TipMath.coverage(x + .2f, y + .3f, 20f, .4f, first.copy(profileB = b.profile, profileMix = .35f))
            assertEquals(ca + (cb - ca) * .35f, mixed, .000001f)
            assertTrue(mixed.isFinite() && mixed in 0f..1f)
        }
    }

    @Test fun quadSchemaIsVersionedAndRejectsMalformedShapesAndCurves() {
        val p = brush().copy(version = 1)
        val encoded = BrushJson.encode(p)
        assertTrue(encoded.contains("\"version\": 14"))
        assertEquals(14, BrushJson.decodeChecked(encoded).version)
        assertFailsWith<BrushException> { BrushJson.decode(encoded.replace("\"version\": 14", "\"version\": 13")) }
        val bad = listOf(a.copy(points = emptyList()), a.copy(points = a.points + QuadPoint(0f, 0f)),
            a.copy(points = a.points.toMutableList().also { it[1] = QuadPoint(Float.NaN, 0f) }),
            a.copy(roundness = 1.01f), a.copy(aspect = Float.POSITIVE_INFINITY),
            a.copy(profile = TipProfileSpec(TipProfile.custom, listOf(listOf(0f, 1f)))))
        for (shape in bad) assertTrue(BrushValidate.validate(brush(TipQuadSpec(shape))).isNotEmpty())
        for (param in listOf(Param(Float.NaN), Param(2f), Param(0f, combine = "bad"),
            Param(0f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, Float.NaN)))))))
            assertTrue(BrushValidate.validate(brush(TipQuadSpec(a, b, param))).isNotEmpty())
        assertFalse(BrushJson.encode(p.copy(tip = TipSpec())).contains("\"quad\":"), "old tips keep the exact legacy representation")
    }

    @Test fun rawSamplesCodecAndBrushCodecReproduceEveryMorphedDab() {
        val samples = (0..32).map { i -> PenSample(i * 5f, (i % 5) * 3f, i * 8.0,
            (i % 9) / 8f, (i % 7) * .2f, i * .15f) }
        val record = StrokeRecord("quad-line", "quad", 123L, .25f, 1f, samples)
        val restored = StrokeCodec.decodeAll(StrokeCodec.encodeAll(listOf(record))).single()
        for (input in listOf(BrushInput.pressure, BrushInput.tilt, BrushInput.speed)) {
            val p = brush(TipQuadSpec(a, b, drive(input))).copy(scatter = ScatterSpec(amount = Param(.15f), count = 2))
            val dabber = BrushDabber(p, record.seed)
            val placer = DabPlacer(dabber.spacing, dabber::look)
            val smoother = StrokeSmoother(record.smoothing, record.screenPerDoc)
            val live = ArrayList<Dab>()
            for (s in samples) live += placer.add(smoother.add(p.response.apply(s)))
            live += placer.add(smoother.finish())
            // Scatter owns a separate seeded stream; compare via replay after direct pipeline first.
            val plain = p.copy(scatter = ScatterSpec())
            assertEquals(live, InkReplay.dabs(restored, BrushJson.decodeChecked(BrushJson.encode(plain))))
            val replay = InkReplay.dabs(restored, BrushJson.decodeChecked(BrushJson.encode(p)))
            assertTrue(replay.size > live.size && replay.all { it.quad != null })
            assertTrue(replay.map { it.profileMix }.toSet().size > 5)
            assertEquals(replay, InkReplay.dabs(restored, p))
            assertNotEquals(replay, InkReplay.dabs(restored.copy(seed = 124L), p))
            for (d in replay.take(10)) {
                assertSame(d.quad, d.copy(x = d.x + 100f, radius = d.radius * 2f).quad)
                assertEquals(d.quad, d.tipShape(TipShape()).quad)
            }
        }
    }
}
