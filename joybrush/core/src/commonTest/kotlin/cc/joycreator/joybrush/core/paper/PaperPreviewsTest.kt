package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperPreviewsTest {
    private val surface = SurfaceEntry("ridge", "Ridge", "ridge.png", 64, 1f, 0.1f, 16f, false)
    private val look = LookEntry("coloured", "Coloured", "#808080", "look.png", "#808080",
        texelPx = 1f, hexTexels = 16f, rotatable = false, defaultSurface = surface.id)
    private val catalogue = PaperCatalogue(surfaces = listOf(surface), looks = listOf(look))
    private val lookTexture = PaperTexture(64, 64, ByteArray(64 * 64 * 4) { i ->
        if (i % 4 == 3) 255.toByte() else ((i / 4 % 64) * 3 + 20).toByte()
    })
    private val surfaceTexture = PaperTexture(64, 64, ByteArray(64 * 64 * 4) { i ->
        when (i % 4) { 0 -> 254.toByte(); 1 -> 127.toByte(); 2 -> 128.toByte(); else -> 64.toByte() }
    })
    private val paper get() = PaperState.resolve(Paper(lookId = look.id, textureId = surface.id), catalogue)
    private fun render(p: ResolvedPaper, rect: RectPx) = PaperRaster.render(p,
        if (p.look?.file != null) lookTexture else null, if (p.surface != null) surfaceTexture else null, rect)
    private fun previews() = PaperPreviews(render = ::render)

    @Test fun previewIsTheActualExportCropAndCallerCannotCorruptTheCachedPixels() {
        val previews = previews()
        val expected = render(paper, RectPx(0, 0, 24, 12))
        val first = previews.crop(paper, 24, 12)
        assertContentEquals(expected, first)
        first.fill(0)
        assertContentEquals(expected, previews.crop(paper, 24, 12))
        assertTrue(expected.filterIndexed { i, _ -> i % 4 == 3 }.all { (it.toInt() and 255) == 255 })
    }

    @Test fun visibleControlsInvalidateTheLiveCrop() {
        val previews = previews()
        val original = previews.crop(paper, 32)
        for (changed in listOf(paper.copy(light = false), paper.copy(show = 0f), paper.copy(scale = 3f),
            paper.copy(tintSet = true, baseArgb = 0xFF306090.toInt()), paper.copy(surface = null))) {
            val actual = previews.crop(changed, 32)
            assertContentEquals(render(changed, RectPx(0, 0, 32, 32)), actual)
            assertFalse(original.contentEquals(actual), "visible change must not return the previous crop: $changed")
        }
        assertEquals(16 * 8 * 4, previews.crop(paper, 16, 8).size)
    }

    @Test fun backgroundUsesItsDefaultSurfaceAndSurfaceThumbnailIsIndependentOfTheLook() {
        val previews = previews()
        assertContentEquals(render(paper, RectPx(0, 0, 20, 20)), previews.background(look, catalogue, 20))
        val expectedSurface = ResolvedPaper(surface, null, 0xFFD8D8D8.toInt(), 1f, 1f, 1f, true)
        assertContentEquals(render(expectedSurface, RectPx(0, 0, 20, 20)), previews.surface(surface, 20))
        assertFalse(previews.surface(surface, 20).contentEquals(previews.surface(null, 20)))
        val black = LookEntry("black", "Black", "#000000", lightByDefault = false)
        val blackCatalogue = catalogue.copy(looks = catalogue.looks + black)
        assertTrue(previews.background(black, blackCatalogue, 20).filterIndexed { i, _ -> i % 4 != 3 }.all { it == 0.toByte() })
    }

    @Test fun repeatedlyUsedSwatchSurvivesEvictionAndClearReloadsIt() {
        var calls = 0
        val previews = PaperPreviews(maxEntries = 2) { p, rect -> calls++; render(p, rect) }
        val a = paper; val b = paper.copy(show = 0f); val c = paper.copy(light = false)
        previews.crop(a, 12); previews.crop(b, 12); previews.crop(a, 12)
        previews.crop(c, 12); previews.crop(a, 12)
        assertEquals(3, calls) // Touching A protected it; B, not A, was evicted.
        previews.crop(b, 12); assertEquals(4, calls)
        previews.clear(); previews.crop(b, 12); assertEquals(5, calls)
    }
}
