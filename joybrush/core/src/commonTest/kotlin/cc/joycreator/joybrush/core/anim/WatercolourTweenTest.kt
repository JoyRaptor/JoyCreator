package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatercolourTweenTest {
    private fun watercolour(id: String = "ordinary-ink-looking-id", spec: WatercolourSpec = WatercolourSpec()) =
        BrushPreset(
            id = id,
            name = "Frozen wash",
            size = Param(32f),
            engine = ENGINE_WATERCOLOUR,
            watercolour = spec,
            blend = BLEND_MULTIPLY,
        )

    private fun ink(id: String = "watercolour-looking-id") =
        BrushPreset(id = id, name = "Frozen ink", size = Param(16f))

    private fun record(brushId: String, washFrame: StrokeWashFrame? = null) = StrokeRecord(
        id = "line",
        brushId = brushId,
        seed = 47L,
        smoothing = 0.3f,
        screenPerDoc = 1.5f,
        samples = listOf(
            PenSample(10f, 20f, 0.0, pressure = 0.2f, tilt = 0.1f, azimuth = 0.3f, barrel = 0.2f),
            PenSample(20f, 25f, 8.0, pressure = 0.7f, tilt = 0.4f, azimuth = 0.6f, barrel = 0.4f),
        ),
        colorArgb = 0xFFCC2200.toInt(),
        widthScale = 1.25f,
        seq = 9L,
        washFrame = washFrame,
    )

    private fun translated(record: StrokeRecord, dx: Double, dy: Double) =
        assertNotNull(LineTransform.apply(record, Homography.translate(dx, dy), LineTransform.Weight.KEEP))

    private fun interpolate(a: StrokeRecord, b: StrokeRecord, t: Float): StrokeRecord =
        WatercolourTween.betweenForReplay(listOf(a), listOf(b), t) { id ->
            if (id == a.brushId) watercolour(a.brushId) else null
        }.single()

    @Test
    fun pureTranslationCarriesGeometryAndWashAndKeepsLegacyNullEndpointExact() {
        val preset = watercolour()
        val a = record(preset.id, washFrame = null)
        val transformed = translated(a, 12.0, -6.0)
        // These four controls are deliberately allowed to differ at the destination.
        val b = transformed.copy(
            colorArgb = 0xFF0033CC.toInt(),
            widthScale = 2.75f,
            locked = true,
            groupId = "pair",
        )

        val atStart = interpolate(a, b, 0f)
        val atEnd = interpolate(a, b, 1f)
        val middle = interpolate(a, b, 0.5f)
        val ordinaryMiddle = Tween.between(listOf(a), listOf(b), 0.5f).single()
        val movedMiddle = translated(a, 6.0, -3.0)
        val expectedMiddle = movedMiddle.copy(
            colorArgb = ordinaryMiddle.colorArgb,
            widthScale = ordinaryMiddle.widthScale,
        )

        assertEquals(a, atStart, "the legacy null wash frame is exact at t=0")
        assertEquals(b, atEnd, "all destination fields are exact at t=1")
        assertEquals(expectedMiddle, middle)
        assertNull(atStart.washFrame)
        assertNotNull(atEnd.washFrame)
        val sourcePoint = Pt(a.samples.first().x.toDouble(), a.samples.first().y.toDouble())
        val destinationPoint = Pt(b.samples.first().x.toDouble(), b.samples.first().y.toDouble())
        val middlePoint = Pt(middle.samples.first().x.toDouble(), middle.samples.first().y.toDouble())
        assertWashLocalEquals(StrokeWashFrame.IDENTITY.localOf(sourcePoint), atEnd.washFrame!!.localOf(destinationPoint))
        assertWashLocalEquals(StrokeWashFrame.IDENTITY.localOf(sourcePoint), middle.washFrame!!.localOf(middlePoint))
        assertEquals(a.locked, middle.locked)
        assertEquals(a.groupId, middle.groupId)
    }

    @Test
    fun explicitWashFrameTranslationMatchesLineTransformComposition() {
        val preset = watercolour()
        val initialFrame = assertNotNull(StrokeWashFrame.from(Homography.translate(-7.0, 4.0)))
        val a = record(preset.id, initialFrame)
        val full = Homography.translate(3.5, 11.0)
        val b = assertNotNull(LineTransform.apply(a, full, LineTransform.Weight.KEEP))
        val t = 0.25f
        val actual = interpolate(a, b, t)
        val expected = assertNotNull(
            LineTransform.apply(a, Homography.translate(3.5 * t, 11.0 * t), LineTransform.Weight.KEEP),
        )

        assertEquals(expected, actual)
        val beforePoint = Pt(a.samples.last().x.toDouble(), a.samples.last().y.toDouble())
        val afterPoint = Pt(actual.samples.last().x.toDouble(), actual.samples.last().y.toDouble())
        assertWashLocalEquals(a.washFrame!!.localOf(beforePoint), actual.washFrame!!.localOf(afterPoint))
    }

    @Test
    fun onlyPureTranslationsAndMatchingRecipesAreAccepted() {
        val preset = watercolour()
        val a = record(preset.id)
        val rejectedTransforms = listOf(
            Homography.rotate(Math.PI, Pt(0.0, 0.0)),
            Homography.scale(2.0, 2.0, Pt(0.0, 0.0)),
            Homography(doubleArrayOf(1.0, 0.25, 4.0, 0.0, 1.0, -2.0, 0.0, 0.0, 1.0)),
            Homography(doubleArrayOf(1.0, 0.0, 4.0, 0.0, 1.0, -2.0, 0.001, 0.0, 1.0)),
        )
        for (transform in rejectedTransforms) {
            val endpoint = assertNotNull(LineTransform.apply(a, transform, LineTransform.Weight.KEEP))
            assertFailsWith<DocException> { interpolate(a, endpoint, 0.5f) }
        }

        val translated = translated(a, 8.0, 5.0)
        assertFailsWith<DocException> { interpolate(a, translated.copy(seed = translated.seed + 1L), 0.5f) }
        val alteredSample = translated.copy(samples = translated.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(x = sample.x + 0.25f) else sample
        })
        assertFailsWith<DocException> { interpolate(a, alteredSample, 0.5f) }
        val otherDefinition = watercolour("other-wash", WatercolourSpec(dryness = 0.7))
        val otherBrushEndpoint = translated.copy(brushId = otherDefinition.id)
        assertFailsWith<DocException> {
            WatercolourTween.betweenForReplay(listOf(a), listOf(otherBrushEndpoint), 0.5f) { id ->
                when (id) {
                    preset.id -> preset
                    otherDefinition.id -> otherDefinition
                    else -> null
                }
            }
        }
    }

    @Test
    fun frozenLookupUsesActualPresetEngineAndRejectsMissingOrMismatchedDefinitions() {
        val actualWash = watercolour(id = "brush-without-wash-in-name")
        val a = record(actualWash.id)
        val b = translated(a, 5.0, 2.0)
        val byNameOnly = ink(id = "looks-like-watercolour")
        val inkA = record(byNameOnly.id)
        val inkB = translated(inkA, 5.0, 2.0)

        // The preset's engine, not a brush-id spelling heuristic, selects the special rule.
        assertFailsWith<DocException> {
            WatercolourTween.betweenForReplay(listOf(a), listOf(b), 0.5f) { null }
        }
        assertFailsWith<DocException> {
            WatercolourTween.betweenForReplay(listOf(a), listOf(b), 0.5f) { actualWash.copy(id = "wrong-id") }
        }
        val inconsistent = watercolour(actualWash.id, WatercolourSpec(dryness = 0.25))
        var lookups = 0
        assertFailsWith<DocException> {
            WatercolourTween.betweenForReplay(listOf(a), listOf(b), 0.5f) {
                if (lookups++ == 0) actualWash else inconsistent
            }
        }
        assertEquals(
            Tween.between(listOf(inkA), listOf(inkB), 0.5f),
            WatercolourTween.betweenForReplay(listOf(inkA), listOf(inkB), 0.5f) { byNameOnly },
            "an Ink brush whose id says watercolour keeps the existing Tween behavior",
        )

        val crossEngine = b.copy(brushId = byNameOnly.id)
        assertFailsWith<DocException> {
            WatercolourTween.betweenForReplay(listOf(a), listOf(crossEngine), 0.5f) { id ->
                if (id == actualWash.id) actualWash else byNameOnly
            }
        }
    }

    @Test
    fun genericInkKeepsLegacyTweenMathEvenWhenItsWashFrameDiffers() {
        val preset = ink(id = "watercolour")
        val a = record(preset.id, washFrame = null)
        val transformed = translated(a, 6.0, 3.0)
        val b = transformed.copy(colorArgb = 0xFF229944.toInt(), widthScale = 1.75f)
        val expected = Tween.between(listOf(a), listOf(b), 0.4f)
        val actual = WatercolourTween.betweenForReplay(listOf(a), listOf(b), 0.4f) { preset }

        assertEquals(expected, actual)
        assertNull(actual.single().washFrame, "generic Ink keeps Tween.between's original record-field policy")

        val sourceBrush = ink("ink-source")
        val targetBrush = ink("ink-target")
        val distinctA = record(sourceBrush.id)
        val distinctB = translated(distinctA, -4.0, 9.0).copy(brushId = targetBrush.id)
        val lookup = mapOf(sourceBrush.id to sourceBrush, targetBrush.id to targetBrush)
        assertEquals(
            Tween.between(listOf(distinctA), listOf(distinctB), 0.6f),
            WatercolourTween.betweenForReplay(listOf(distinctA), listOf(distinctB), 0.6f) { lookup[it] },
            "two distinct frozen Ink presets still use ordinary Tween math",
        )
    }

    @Test
    fun invalidFractionIsExplicitAndCapturedRefusalRemainsUnchanged() {
        val preset = watercolour()
        val a = record(preset.id)
        val b = translated(a, 2.0, 3.0)
        val lookup: (String) -> BrushPreset? = { if (it == preset.id) preset else null }
        for (t in listOf(Float.NaN, -0.01f, 1.01f)) {
            assertFailsWith<DocException> {
                WatercolourTween.betweenForReplay(listOf(a), listOf(b), t, lookup)
            }
        }
        val captured = a.copy(capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain()))
        val capturedTarget = translated(captured, 2.0, 3.0)
        assertFailsWith<IllegalArgumentException> {
            WatercolourTween.betweenForReplay(listOf(captured), listOf(capturedTarget), 0.5f, lookup)
        }
    }

    @Test
    fun translationPreservesUnknownSensorNanPayloadAndRejectsPayloadChanges() {
        val preset = watercolour()
        val unknownTilt = Float.fromBits(0x7fc00011)
        val source = record(preset.id)
        val a = source.copy(samples = source.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(tilt = unknownTilt) else sample
        })
        val b = translated(a, 7.0, -3.0)
        val middle = interpolate(a, b, 0.5f)

        assertEquals(0x7fc00011, middle.samples.first().tilt.toRawBits())
        val differentPayload = b.copy(samples = b.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(tilt = Float.fromBits(0x7fc00022)) else sample
        })
        assertFailsWith<DocException> { interpolate(a, differentPayload, 0.5f) }
    }

    private fun assertWashLocalEquals(want: Pt, got: Pt) {
        assertTrue(abs(want.x - got.x) < 1e-5, "wash-local x: expected ${want.x}, got ${got.x}")
        assertTrue(abs(want.y - got.y) < 1e-5, "wash-local y: expected ${want.y}, got ${got.y}")
    }
}
