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
    private fun texture(s: SurfaceEntry): PaperTexture = texture(s.file)
    private fun texture(file: String): PaperTexture {
        val img = ImageIO.read(File(dir, file))
        val bytes = ByteArray(img.width * img.height * 4)
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
            val image = ImageIO.read(File(dir, s.file))
            val h = ByteArray(s.size*s.size) { i -> image.getRGB(i % s.size, i / s.size).toByte() }
            val actual = texture(s)
            val rebuilt = SurfaceMaps.pack(h, s.size, s.size, s.slopeRange)
            // ImageIO must preserve alpha = height squared, including semi-transparent texels.
            val expected = ByteArray(rebuilt.size)
            for (i in h.indices) {
                val argb = image.getRGB(i % s.size, i / s.size)
                expected[i*4]=(argb ushr 16).toByte(); expected[i*4+1]=(argb ushr 8).toByte()
                expected[i*4+2]=argb.toByte(); expected[i*4+3]=(argb ushr 24).toByte()
            }
            assertContentEquals(expected, rebuilt, id)
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
