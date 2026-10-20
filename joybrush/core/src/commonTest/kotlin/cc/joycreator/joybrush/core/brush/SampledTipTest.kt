package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SampledTipTest {
    private val images = (0..7).map { "sample-$it.png" }
    private fun sample(x: Float, y: Float, pressure: Float = 1f) = PenSample(x, y, 0.0, pressure)

    @Test fun selectionModesHaveExplicitEndpointsAndDirectionBinCenters() {
        val sequence = SampledTipPicker(SampledTipSpec(images), 17)
        assertEquals((0..23).map { it % 8 }, (0..23).map { sequence.next(sample(it.toFloat(), 0f), it.toFloat(), it).imageIndex })
        val pressure = SampledTipPicker(SampledTipSpec(images, SamplePick.byPressure), 17)
        assertEquals(listOf(0, 0, 1, 4, 7, 7, 0), listOf(-1f, 0f, .125f, .5f, 1f, 2f, Float.NaN)
            .mapIndexed { i, p -> pressure.next(sample(i.toFloat(), 0f, p), i.toFloat(), i).imageIndex })
        val direction = SampledTipPicker(SampledTipSpec(images, SamplePick.byDirection), 17)
        val path = listOf(sample(0f, 0f), sample(1f, 0f), sample(1f, 1f), sample(0f, 1f), sample(0f, 0f), sample(0f, 0f))
        assertEquals(listOf(0, 0, 2, 4, 6, 6), path.mapIndexed { i, s -> direction.next(s, i.toFloat(), i).imageIndex })
    }

    @Test fun imageDirectionIgnoresPenLeanAndBarrelAndHoldsAtRest() {
        val recipe = SampledTipSpec(images, SamplePick.byDirection)
        val a = SampledTipPicker(recipe, 17); val b = SampledTipPicker(recipe, 17)
        val path = listOf(sample(0f, 0f), sample(5f, 0f), sample(5f, 5f), sample(5f, 5f), sample(0f, 5f))
        val expected = path.mapIndexed { i, s -> a.next(s, i.toFloat(), i) }
        val actual = path.mapIndexed { i, s -> b.next(s.copy(tilt = 1f, azimuth = (PI / 3).toFloat(), barrel = -2f), i.toFloat(), i) }
        assertEquals(expected, actual)
        assertEquals(actual[2].imageIndex, actual[3].imageIndex)
    }

    @Test fun independentIndexedStreamsSurviveBatchingAndUnrelatedSettings() {
        val recipe = SampledTipSpec(images, SamplePick.random, rotateJitterDegrees = 40f, spacingJitter = .6f)
        val path = (0 until 100).map { sample(it.toFloat(), (it % 9).toFloat(), it / 100f) }
        fun run(preset: SampledTipSpec, seed: Long, splits: List<Int>): List<SampledDab> {
            val picker = SampledTipPicker(preset, seed); var index = 0
            return splits.flatMap { count -> (0 until count).map {
                val i = index++; picker.next(path[i], i.toFloat(), i)
            } }
        }
        val whole = run(recipe, 19, listOf(100))
        assertEquals(whole, run(recipe, 19, listOf(1, 11, 3, 25, 60)))
        val noRotation = run(recipe.copy(rotateJitterDegrees = 0f), 19, listOf(100))
        val noSpacing = run(recipe.copy(spacingJitter = 0f), 19, listOf(100))
        assertEquals(whole.map { it.imageIndex }, noRotation.map { it.imageIndex })
        assertEquals(whole.map { it.spacingMultiplier }, noRotation.map { it.spacingMultiplier })
        assertEquals(whole.map { it.imageIndex }, noSpacing.map { it.imageIndex })
        assertEquals(whole.map { it.rotationRadians }, noSpacing.map { it.rotationRadians })
        val changedSeed = run(recipe, 20, listOf(100))
        assertTrue(whole.indices.count { whole[it].imageIndex != changedSeed[it].imageIndex } > 40)
        assertTrue(whole.indices.count { whole[it].rotationRadians != changedSeed[it].rotationRadians } > 90)
        assertTrue(whole.all { it.spacingMultiplier in .4f..1.6f && abs(it.rotationRadians) <= (40 * PI / 180).toFloat() })
    }

    @Test fun sourcePathsRangesAndRecipeOwnershipAreChecked() {
        for (bad in listOf("../tip.png", "./tip.png", "folder./tip.png", "/tip.png", "C:/tip.png", "tip\\mask.png", "sub//tip.png", "tip.raw", "tip..png"))
            assertTrue(SampledTips.problems(SampledTipSpec(listOf(bad))).isNotEmpty(), bad)
        assertTrue(SampledTips.problems(SampledTipSpec(emptyList())).isNotEmpty())
        assertTrue(SampledTips.problems(SampledTipSpec(List(17) { "tip.png" })).isNotEmpty())
        assertTrue(SampledTips.problems(SampledTipSpec(listOf("tip.png"), rotateJitterDegrees = Float.NaN)).isNotEmpty())
        assertTrue(SampledTips.problems(SampledTipSpec(listOf("tip.png"), spacingJitter = 1.1f)).isNotEmpty())
        assertTrue(SampledTips.problems(SampledTipSpec(listOf("folder/TIP.PNG", "folder/TIP.PNG"))).isEmpty())
        val mutable = images.toMutableList()
        val picker = SampledTipPicker(SampledTipSpec(mutable), 17)
        mutable.clear()
        assertEquals(7, picker.next(sample(0f, 0f), 0f, 7).imageIndex)
        assertFailsWith<IllegalArgumentException> { picker.next(sample(0f, 0f), 0f, -1) }
    }
}

class ImageMaskTest {
    @Test fun alphaAndLuminanceHaveDifferentExplicitInversionContracts() {
        val pixels = intArrayOf(0x80000000.toInt(), 0x80ffffff.toInt(), 0x00ffffff, 0xffff0000.toInt())
        fun bytes(mode: SampleCoverage, invert: Boolean) = ImageMask.fromArgb(2, 2, pixels, mode, invert).bytes()
        assertContentEquals(byteArrayOf(128.toByte(), 128.toByte(), 0, 255.toByte()), bytes(SampleCoverage.alpha, false))
        assertContentEquals(byteArrayOf(127, 127, 255.toByte(), 0), bytes(SampleCoverage.alpha, true))
        assertContentEquals(byteArrayOf(128.toByte(), 0, 0, 201.toByte()), bytes(SampleCoverage.luminance, false))
        // Transparent white stays transparent when luminance is inverted: A*Y, not 1-A*(1-Y).
        assertContentEquals(byteArrayOf(0, 128.toByte(), 0, 54), bytes(SampleCoverage.luminance, true))
        val inverted = ImageMask.fromArgb(2, 2, pixels, SampleCoverage.alpha, true)
        assertEquals(0f, inverted.sample(2f, 0f)); assertEquals(0f, inverted.sample(0f, -2f))
    }

    @Test fun bilinearInterpolationUsesTransparentOutsideTexelsAndNativeAspect() {
        val wide = ImageMask.fromR8(4, 2, ByteArray(8) { 255.toByte() })
        assertEquals(1f, wide.sample(0f, 0f))
        assertEquals(1f, wide.sample(0f, .25f))
        assertEquals(.5f, wide.sample(0f, .5f))
        assertEquals(0f, wide.sample(0f, .75f))
        assertEquals(.5f, wide.sample(1f, 0f))
        assertEquals(.25f, wide.sample(1.125f, 0f))
        assertEquals(0f, wide.sample(1.25f, 0f))
        assertEquals(1.25f, wide.supportHalfWidth); assertEquals(.75f, wide.supportHalfHeight)
        val tall = ImageMask.fromR8(2, 4, ByteArray(8) { 255.toByte() })
        assertEquals(.5f, tall.sample(.5f, 0f)); assertEquals(1f, tall.sample(0f, .5f))
        val tiny = ImageMask.fromR8(1, 1, byteArrayOf(255.toByte()))
        assertEquals(2f, tiny.supportHalfWidth)
        assertEquals(1f, tiny.sample(0f, 0f)); assertEquals(.5f, tiny.sample(1f, 0f))
        assertEquals(.25f, tiny.sample(1f, 1f)); assertEquals(0f, tiny.sample(2f, 0f))
        assertEquals(0f, tiny.sample(Float.NaN, 0f)); assertEquals(0f, tiny.sample(Float.POSITIVE_INFINITY, 0f))
    }

    @Test fun r8AndArgbStorageCannotBeChangedThroughCallerArrays() {
        val source = byteArrayOf(255.toByte(), 0, 0, 255.toByte())
        val mask = ImageMask.fromR8(2, 2, source)
        source.fill(0); val returned = mask.bytes(); returned.fill(0)
        assertEquals(.5f, mask.sample(0f, 0f))
        assertEquals(1f, mask.sample(-.5f, -.5f))
        val argb = intArrayOf(0xffffffff.toInt())
        val other = ImageMask.fromArgb(1, 1, argb, SampleCoverage.alpha)
        argb[0] = 0
        assertEquals(1f, other.sample(0f, 0f))
        assertFailsWith<BrushException> { ImageMask.fromR8(2049, 1, ByteArray(2049)) }
        assertFailsWith<BrushException> { ImageMask.fromR8(0, 1, byteArrayOf()) }
        assertFailsWith<BrushException> { ImageMask.fromR8(2, 2, byteArrayOf(0)) }
    }

    @Test fun variableRectAtlasMatchesEverySourceAndItsBordersWithoutNeighborLeakage() {
        val masks = listOf(
            ImageMask.fromR8(4, 2, byteArrayOf(0, 64, 128.toByte(), 255.toByte(), 255.toByte(), 128.toByte(), 64, 0)),
            ImageMask.fromR8(1, 3, byteArrayOf(255.toByte(), 0, 255.toByte())),
            ImageMask.fromR8(1, 1, byteArrayOf(255.toByte())),
            ImageMask.fromR8(2, 2, ByteArray(4)),
        )
        val atlas = SampleMaskAtlas.pack(masks, maxTextureSize = 12)
        val again = SampleMaskAtlas.pack(masks, maxTextureSize = 12)
        assertEquals(atlas.width, again.width); assertEquals(atlas.height, again.height)
        assertEquals(atlas.rects(), again.rects()); assertContentEquals(atlas.bytes(), again.bytes())
        assertTrue(atlas.width <= 12 && atlas.height <= 12)
        for (i in masks.indices) for (yi in -24..24) for (xi in -24..24) {
            val x = xi / 8f; val y = yi / 8f
            assertEquals(masks[i].sample(x, y), atlas.sample(i, x, y), 2e-7f, "source $i at $x,$y")
        }
        val pixels = atlas.bytes()
        for (i in masks.indices) {
            val rect = atlas.rect(i)
            for (x in rect.x - 1..rect.x + rect.imageWidth) {
                assertEquals(0, pixels[(rect.y - 1) * atlas.width + x].toInt())
                assertEquals(0, pixels[(rect.y + rect.imageHeight) * atlas.width + x].toInt())
            }
            assertEquals(rect.imageWidth.toFloat() / atlas.width, rect.u1 - rect.u0, 1e-7f)
            assertEquals(rect.imageHeight.toFloat() / atlas.height, rect.v1 - rect.v0, 1e-7f)
        }
        pixels.fill(255.toByte())
        assertEquals(0f, atlas.sample(3, 0f, 0f), "exported atlas bytes cannot change its ownership")
        assertEquals(0f, atlas.sample(0, 30f, 0f), "far outside cannot reach another cell")
    }

    @Test fun completeSetsAreRefusedAtBudgetsRatherThanRescaledOrTruncated() {
        val a = ImageMask.fromR8(3, 3, ByteArray(9))
        assertFailsWith<BrushException> { SampleMaskAtlas.pack(listOf(a), maxTextureSize = 4) }
        assertFailsWith<BrushException> { SampleMaskAtlas.pack(listOf(a, a), 8, maxSourcePixels = 17) }
        assertFailsWith<BrushException> { SampleMaskAtlas.pack(listOf(a, a), 8, maxAtlasPixels = 24) }
        assertFailsWith<BrushException> { SampleMaskAtlas.pack(List(17) { a }, 64) }
        assertFailsWith<BrushException> { SampleMaskAtlas.pack(emptyList(), 64) }
        val accepted = SampleMaskAtlas.pack(listOf(a), 5, maxSourcePixels = 9, maxAtlasPixels = 25)
        assertEquals(5, accepted.width); assertEquals(5, accepted.height); assertEquals(1, accepted.size)
        assertEquals(3, accepted.rect(0).imageWidth); assertEquals(3, accepted.rect(0).imageHeight)
    }
}
