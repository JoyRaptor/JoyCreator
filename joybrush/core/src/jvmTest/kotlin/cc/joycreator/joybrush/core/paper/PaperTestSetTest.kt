package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.brush.joybrushRoot
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.grain.GrainMath
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Reads shipped pixels, rather than testing a duplicate inline catalogue. */
class PaperTestSetTest {
    private val dir = File(joybrushRoot(), "assets/paper")
    private val catalogue get() = PaperCatalogues.parse(File(dir, "catalogue.json").readText())
    private val ids = listOf("canvas_linen", "canvas_cotton_duck", "canvas_jute", "pulp_factory", "pulp_handmade")
    /** A surface as the app holds it: a height-only file (packed = false) is expanded by SurfaceMaps, as on device. */
    private fun texture(s: SurfaceEntry): PaperTexture {
        val t = texture(s.file)
        return PaperTexture(t.w, t.h, SurfaceMaps.expand(s, t.rgba, t.w, t.h))
    }
    private fun texture(file: String): PaperTexture {
        val img = ImageIO.read(File(dir, file))
        val bytes = ByteArray(img.width * img.height * 4)
        // A grey PNG (a height-only surface) is read from its raster: ImageIO's getRGB treats grey as LINEAR and
        // brightens it on the way to sRGB, which Android does not do.
        if (img.type == java.awt.image.BufferedImage.TYPE_BYTE_GRAY) {
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val v = img.raster.getSample(x, y, 0).toByte(); val i = (y * img.width + x) * 4
                bytes[i] = v; bytes[i+1] = v; bytes[i+2] = v; bytes[i+3] = -1
            }
            return PaperTexture(img.width, img.height, bytes)
        }
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val argb = img.getRGB(x, y); val i = (y * img.width + x) * 4
            bytes[i] = (argb ushr 16).toByte(); bytes[i+1] = (argb ushr 8).toByte()
            bytes[i+2] = argb.toByte(); bytes[i+3] = (argb ushr 24).toByte()
        }
        return PaperTexture(img.width, img.height, bytes)
    }
    @Test fun contactSheetShowsActualRendererAtQuarterNormalAndFourTimesScale() {
        val c = catalogue
        val rows = ids + "pulp_artisan"
        val sheet = java.awt.image.BufferedImage(432, rows.size * 152, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height)
        for ((row, id) in rows.withIndex()) {
            val s = assertNotNull(PaperCatalogues.surface(c, id)); val tex = texture(s)
            val lookId = if (id == "pulp_artisan") "off_white" else id
            val look = assertNotNull(PaperCatalogues.look(c, lookId))
            for ((col, scale) in listOf(.25f, 1f, 4f).withIndex()) {
                val paper = PaperState.resolve(cc.joycreator.joybrush.core.doc.Paper(
                    lookId = lookId, textureId = id, textureScale = scale), c)
                val rgba = PaperRaster.render(paper, look.file?.let(::texture), tex, RectPx(0, 0, 128, 128))
                for (y in 0 until 128) for (x in 0 until 128) {
                    val i = (y*128+x)*4
                    sheet.setRGB(col*144+x, row*152+y, ((rgba[i].toInt() and 255) shl 16) or
                        ((rgba[i+1].toInt() and 255) shl 8) or (rgba[i+2].toInt() and 255))
                }
                g.color = java.awt.Color.BLACK
                g.drawString("${look.name} ${scale}x", col*144+2, row*152+143)
            }
        }
        g.dispose()
        val output = File(dir.parentFile.parentFile, "tools/paper/out/contact.png")
        output.parentFile.mkdirs(); assertTrue(ImageIO.write(sheet, "png", output))
    }

    @Test fun allFiveSurfaceFilesRebuildExactlyFromTheirPhysicalHeights() {
        for (id in ids) {
            val s = assertNotNull(PaperCatalogues.surface(catalogue, id))
            // The file's height (B of a packed file; the grey of a height-only one, packed = false since 2026-10-06).
            val file = texture(s.file)
            val h = ByteArray(s.size*s.size) { i -> file.rgba[i*4 + 2] }
            val actual = texture(s)
            val rebuilt = SurfaceMaps.pack(h, s.size, s.size, s.slopeRange)
            // What the app holds is exactly the packing of that height; for a packed file ImageIO must also preserve
            // alpha = height squared, including semi-transparent texels.
            assertContentEquals(actual.rgba, rebuilt, id)
            assertEquals(s.size, actual.w)
            assertEquals(!id.startsWith("canvas_"), s.rotatable, "weave orientation: $id")
        }
    }
    @Test fun eachMaterialProducesDistinctDryBrushCoverageAndLitSelectorPixels() {
        val coverage = mutableListOf<List<Float>>()
        val previews = mutableListOf<ByteArray>()
        for (id in ids) {
            val c = catalogue
            val s = assertNotNull(PaperCatalogues.surface(c, id))
            val tex = texture(s)
            val out = FloatArray(4)
            val samples = (0 until 1024).map { i ->
                HexTile.sampleSurface(tex, (i % 32) * 7.37 / s.texelPx,
                    (i / 32) * 9.13 / s.texelPx, s.hexTexels.toDouble(), s.rotatable, s.slopeRange, out)
                GrainMath.heightCoverage(out[2], .5f, .25f)
            }
            assertTrue(samples.max() - samples.min() > .15f, "$id must affect deposition, not be flat")
            coverage.add(samples)
            val cache = PaperPreviews { p, rect -> PaperRaster.render(p, p.look?.file?.let(::texture), tex, rect) }
            val look = assertNotNull(PaperCatalogues.look(c, id))
            assertEquals(s.id, look.defaultSurface)
            val pixels = cache.background(look, c, 64)
            val red = pixels.filterIndexed { i, _ -> i % 4 == 0 }.map { it.toInt() and 255 }
            assertTrue(red.max() - red.min() > 2, "$id relief must be visible in the preview")
            previews.add(pixels)
        }
        for (i in ids.indices) for (j in 0 until i) {
            assertNotEquals(coverage[i], coverage[j], "${ids[i]} versus ${ids[j]} brush response")
            assertFalse(previews[i].contentEquals(previews[j]), "selector swatches must differ")
        }
    }
}
