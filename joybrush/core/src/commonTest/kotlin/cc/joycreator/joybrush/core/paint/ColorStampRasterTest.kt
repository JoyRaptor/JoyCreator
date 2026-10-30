package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.brush.ColorDab
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.vector.InkRaster
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Colour moments preserve the existing wash mask, including falling per-dab opacity caps. */
class ColorStampRasterTest {
    private val side = 32
    private val fixed = 0xff336699.toInt()
    private val tip = TipShape(hardness = .63f)
    private val red = ColorDab(1f, 0f, 0f)
    private val blue = ColorDab(0f, 0f, 1f)

    @Test fun varyingCapsUseTheCoverageMaskRatherThanColorBufferAlpha() {
        val dabs = listOf(
            Dab(16.5f, 16.5f, 8f, flow = .8f, cap = .9f, color = red),
            Dab(16.5f, 16.5f, 8f, flow = .5f, cap = .15f, color = blue),
        )
        val expectedMask = .15f * .5f + (.9f * .8f) * (1f - .5f)
        val wrongAlpha = .15f * .5f + (.9f * .8f) * (1f - .15f * .5f)
        assertTrue(abs(expectedMask - wrongAlpha) > .3f, "falling caps expose using moment alpha")
        for (mode in Accumulate.entries) {
            val k = if (mode == Accumulate.BUILD_UP) .37f else 1f
            val canvas = reference(dabs, mode = mode, opacity = .37f)
            val p = canvas.pixel("ink", 16, 16)
            assertEquals(expectedMask * k, p[3])
            assertEquals((1f * .9f * .8f * (1f - .5f)) * k, p[0])
            assertEquals((1f * .15f * .5f) * k, p[2])
            assertEquals(0f, p[1])
            assertTrue(p[0] <= p[3] && p[2] <= p[3], "premultiplied channels stay below mask alpha")
            assertContentEquals(tile(canvas), raster(dabs, mode, .37f))
        }
    }

    @Test fun fixedColorKeepsTheLegacyFloatArithmeticForBothAccumulateModes() {
        val dabs = mixed().map { it.copy(color = null) }
        for (mode in Accumulate.entries) {
            val actual = raster(dabs, mode, .47f)
            val reference = tile(reference(dabs, mode = mode, opacity = .47f))
            assertContentEquals(reference, actual)
            val k = if (mode == Accumulate.BUILD_UP) .47f else 1f
            for (y in 0 until side) for (x in 0 until side) {
                var mask = 0f
                for (d in dabs) {
                    val cov = TipMath.coverage(x + .5f - d.x, y + .5f - d.y, d.radius, d.angle, tip) * d.flow
                    if (cov > 0f) mask = d.cap * cov + mask * (1f - cov)
                }
                val at = (y * side + x) * 4
                val a = mask * k
                assertEquals(a, actual[at + 3])
                assertEquals((0x33 / 255f) * a, actual[at])
                assertEquals((0x66 / 255f) * a, actual[at + 1])
                assertEquals((0x99 / 255f) * a, actual[at + 2])
            }
        }
    }

    @Test fun nullableColorsAndFirstColoredDabInALaterBatchKeepStrokeOrder() {
        val dabs = mixed()
        val together = tile(reference(dabs))
        val split = tile(reference(dabs, batches = listOf(dabs.take(2), dabs.drop(2).take(1), dabs.drop(3))))
        assertContentEquals(together, split, "a later first colour seeds the fixed prefix once")
        assertContentEquals(together, raster(dabs))
        val reversed = raster(dabs.reversed())
        assertTrue(together.indices.any { it % 4 != 3 && abs(together[it] - reversed[it]) > .05f },
            "the fixture sees reversed dab order")
        val fixedOnly = raster(dabs.map { it.copy(color = null) })
        assertTrue(together.indices.any { it % 4 != 3 && abs(together[it] - fixedOnly[it]) > .05f },
            "dropping colour moments must change the fixture")
        for (i in together.indices.step(4)) assertEquals(fixedOnly[i + 3], together[i + 3], "colour does not change coverage")
    }

    @Test fun referenceAndInkRasterAgreeOverAPaintedBackdropForEverySupportedOperator() {
        val dabs = mixed()
        val backgroundDab = Dab(16f, 16f, 50f, flow = .65f, cap = .8f)
        for (mode in Accumulate.entries) for ((strokeBlend, blendMode) in listOf(
            StrokeBlend.NORMAL to BlendMode.NORMAL, StrokeBlend.ERASE to BlendMode.ERASE_BELOW,
            StrokeBlend.MULTIPLY to BlendMode.MULTIPLY, StrokeBlend.SCREEN to BlendMode.SCREEN,
        )) {
            val canvas = RefCanvas(side)
            canvas.beginStroke("ink", .25f, .6f, .4f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, TipShape(hardness = 1f))
            canvas.addDabs(listOf(backgroundDab)); canvas.endStroke()
            val before = tile(canvas).copyOf()
            canvas.beginStroke("ink", 0x33 / 255f, 0x66 / 255f, 0x99 / 255f, .47f, mode, strokeBlend, tip)
            canvas.addDabs(dabs); canvas.endStroke()
            val actual = tile(canvas)
            val source = raster(dabs, mode, .47f)
            val s = FloatArray(4); val d = FloatArray(4); val out = FloatArray(4)
            for (at in actual.indices.step(4)) {
                for (c in 0..3) { s[c] = source[at + c]; d[c] = before[at + c] }
                Blend.apply(blendMode, s, d, out)
                for (c in 0..3) assertEquals(out[c], actual[at + c], 3e-7f, "$mode/$strokeBlend at $at channel $c")
            }
        }
    }

    @Test fun recoloringDabsChangesRgbAndPreservesAlphaIncludingRecordOpacity() {
        val original = mixed()
        val recolored = original.map { d -> d.copy(color = d.color?.let { ColorDab(it.b, 1f - it.g, it.r) }) }
        for (mode in Accumulate.entries) {
            val a = raster(original, mode, .47f, 0x80336699.toInt())
            val b = raster(recolored, mode, .47f, 0x80336699.toInt())
            val opaque = raster(original, mode, .47f)
            assertTrue(a.indices.any { it % 4 != 3 && abs(a[it] - b[it]) > .02f })
            for (i in a.indices.step(4)) {
                assertEquals(a[i + 3], b[i + 3])
                // Record alpha is part of k, so all four channels scale together.
                for (c in 0..3) assertEquals(opaque[i + c] * (128f / 255f), a[i + c], 6e-8f)
            }
        }
    }

    @Test fun cancelAndTheFollowingFixedStrokeDoNotReuseColorMoments() {
        val canvas = RefCanvas(side)
        canvas.beginStroke("ink", .2f, .4f, .6f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, tip)
        canvas.addDabs(mixed()); canvas.cancelStroke()
        assertTrue(canvas.tiles("ink").isEmpty())
        val fixedDabs = mixed().map { it.copy(color = null) }
        canvas.beginStroke("ink", .2f, .4f, .6f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, tip)
        canvas.addDabs(fixedDabs); canvas.endStroke()
        assertContentEquals(raster(fixedDabs), tile(canvas))
        val before = tile(canvas).copyOf()
        canvas.beginStroke("ink", .2f, .4f, .6f, 1f, Accumulate.WASH, StrokeBlend.NORMAL, tip)
        canvas.addDabs(mixed()); canvas.endStroke()
        assertTrue(canvas.undoStep()); assertContentEquals(before, tile(canvas))
        assertTrue(canvas.redoStep())
        assertTrue(tile(canvas).indices.any { abs(tile(canvas)[it] - before[it]) > .02f })
    }

    private fun mixed() = listOf(
        Dab(12.5f, 15.5f, 8f, flow = .73f, cap = .8f),
        Dab(16.5f, 15.5f, 8f, flow = .54f, cap = .6f),
        Dab(18.5f, 16.5f, 8f, flow = .62f, cap = .2f, color = red),
        Dab(15.5f, 18.5f, 8f, flow = .47f, cap = .7f, color = blue),
        Dab(17.5f, 16.5f, 8f, flow = .39f, cap = .4f),
    )

    private fun raster(dabs: List<Dab>, mode: Accumulate = Accumulate.WASH, opacity: Float = 1f,
                       argb: Int = fixed) = assertNotNull(
        InkRaster.premultiplied(dabs, tip, mode, opacity, argb, 0, 0, side, side, 1f))

    private fun reference(dabs: List<Dab>, mode: Accumulate = Accumulate.WASH, opacity: Float = 1f,
                          batches: List<List<Dab>> = listOf(dabs)): RefCanvas = RefCanvas(side).also {
        it.beginStroke("ink", 0x33 / 255f, 0x66 / 255f, 0x99 / 255f, opacity, mode, StrokeBlend.NORMAL, tip)
        for (batch in batches) it.addDabs(batch)
        it.endStroke()
    }

    private fun tile(canvas: RefCanvas) = assertNotNull(canvas.tiles("ink")[Tiles.key(0, 0)])
}
