package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paper.PaperTexture
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
}
