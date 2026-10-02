package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paper.PaperTexture
import cc.joycreator.joybrush.core.paper.PaperState
import kotlin.test.*

class PaperResourcesTest {
    @Test fun flatBaseAndTintNeedNoBitmapDecoder() {
        val loaded = PaperResources.load(Paper("#123456", tint = "#204060")) { error("flat paper has no texture") }
        assertTrue(loaded.warnings.isEmpty())
        assertContentEquals(byteArrayOf(32, 64, 96, -1), loaded.render(RectPx(-2, 3, 1, 1)))
    }
    @Test fun missingSurfaceFallsBackToFlatBaseAndReportsTheFilename() {
        val loaded = PaperResources.load(Paper("#123456", textureId = "pulp_artisan")) { null }
        assertTrue(loaded.warnings.single().contains("surface_pulp_artisan.png"))
        assertNull(loaded.surface)
        assertFalse(loaded.paper.light)
        assertContentEquals(byteArrayOf(18, 52, 86, -1), loaded.render(RectPx(0, 0, 1, 1)))
    }
    @Test fun packagedCatalogueResolvesItsSurfaceFileAndKeepsRgbaData() {
        val pixels = byteArrayOf(-128, -128, 40, 6)
        val loaded = PaperResources.load(Paper("#123456", textureId = "pulp_artisan", light = false)) {
            assertEquals("surface_pulp_artisan.png", it); PaperTexture(1, 1, pixels)
        }
        assertTrue(loaded.warnings.isEmpty())
        assertNotNull(loaded.surface)
        assertContentEquals(pixels, loaded.surface.rgba)
    }
    @Test fun sliderChangesReuseImagesAndApplyNewSettings() {
        val request = PaperState.resolve(Paper(textureId = "pulp_artisan"), PaperResources.catalogue)
        var decodes = 0
        val texture = PaperTexture(1, 1, byteArrayOf(-128, -128, 40, 6))
        val first = PaperResources.update(request, null, null) { decodes++; texture }
        val changed = request.copy(show = .2f, bite = .7f, scale = 3f, baseArgb = 0xff123456.toInt(), tintSet = true)
        val second = PaperResources.update(changed, request, first) { error("slider decoded again") }
        assertEquals(1, decodes)
        assertSame(texture, second.surface)
        assertEquals(changed, second.paper)
        val smooth = PaperResources.update(changed.copy(surface = null), changed, second) { error("smooth needs no image") }
        assertNull(smooth.surface)
        assertTrue(smooth.warnings.isEmpty())
    }
    @Test fun failedMaterialIsNotRetriedOnEverySliderChange() {
        val request = PaperState.resolve(Paper(textureId = "pulp_artisan"), PaperResources.catalogue)
        val first = PaperResources.update(request, null, null) { null }
        val changed = request.copy(show = .3f, baseArgb = 0xff123456.toInt())
        val second = PaperResources.update(changed, request, first) { error("failed material retried") }
        assertEquals(first.warnings, second.warnings)
        assertNull(second.paper.surface)
        assertFalse(second.paper.light)
        assertEquals(changed.baseArgb, second.paper.baseArgb)
    }
}
