package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.ImageMask
import cc.joycreator.joybrush.core.brush.SampleCoverage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The core calls an explicit PNG boundary; JVM ImageIO supplies independent real pixel fixtures. */
class MaterializedImportsTest {
    private class PngCodec : ImportedTipPngCodec {
        var encodedDimensions: ImportedPngDimensions? = null
        var encodedAlpha: ByteArray? = null
        var encodedBytes: ByteArray? = null
        override fun encodeAlpha(width: Int, height: Int, alpha: ByteArray): ByteArray {
            encodedDimensions = ImportedPngDimensions(width, height); encodedAlpha = alpha.copyOf()
            return png(width, height, IntArray(alpha.size) { ((alpha[it].toInt() and 255) shl 24) or 0xffffff })
                .also { encodedBytes = it }
        }
        override fun inspect(png: ByteArray): ImportedPngDimensions {
            val image = ImageIO.read(ByteArrayInputStream(png)) ?: throw BrushException("unreadable PNG fixture")
            return ImportedPngDimensions(image.width, image.height)
        }
    }

    @Test fun abrMaterializesOriginalDimensionsAnd16BitGrayWhileLegacyStaysOpaque() {
        val source = abr(2, 2, byteArrayOf(0, 255.toByte(), 64, 123, 128.toByte(), 45, 255.toByte(), 0), depth = 16)
        val old = AbrImport.convert(source, "pack").brushes.single()
        val codec = PngCodec()
        val converted = AbrImport.convertWithAssets(source, "pack", codec)
        assertTrue(converted.refused.isEmpty())
        val result = converted.brushes.single()
        assertEquals("tip.raw", old.preset.tip.image)
        assertNotNull(old.preset.extensions["abr.tipImage"])
        assertNull(old.preset.tip.sampled)
        assertEquals(listOf("tip.png"), assertNotNull(result.preset.tip.sampled).images)
        assertNull(result.preset.extensions["abr.tipImage"], "opaque old bytes are not relabeled as PNG")
        assertEquals("2", result.preset.extensions["abr.sampledSourceWidth"])
        assertEquals("2", result.preset.extensions["abr.sampledSourceHeight"])
        assertEquals("16", result.preset.extensions["abr.sampledSourceDepth"])
        assertEquals(ImportedPngDimensions(2, 2), codec.encodedDimensions)
        assertContentEquals(byteArrayOf(0, 64, 128.toByte(), 255.toByte()), codec.encodedAlpha)
        assertTrue(result.warnings.any { "16-bit" in it })
        val pixels = ImageIO.read(ByteArrayInputStream(result.assets.getValue("tip.png")))
        assertEquals(listOf(0, 64, 128, 255), (0..3).map { pixels.getRGB(it % 2, it / 2) ushr 24 })
        val saved = result.assets.getValue("tip.png")
        codec.encodedBytes!!.fill(0); result.assets.getValue("tip.png").fill(0)
        assertContentEquals(saved, result.assets.getValue("tip.png"), "encoder/export arrays cannot mutate frozen resources")
    }

    @Test fun packBitsTipsUseTheReaderPixelsAndComputedBrushesKeepExactOldResults() {
        // Two rows: table lengths 3,3, then literal runs [0,255] and [64,128].
        val payload = byteArrayOf(0, 3, 0, 3, 1, 0, 255.toByte(), 1, 64, 128.toByte())
        val sampled = abr(2, 2, payload, compression = 1)
        val codec = PngCodec(); val result = AbrImport.convertWithAssets(sampled, "pack", codec)
        assertTrue(result.refused.isEmpty())
        assertContentEquals(byteArrayOf(0, 255.toByte(), 64, 128.toByte()), codec.encodedAlpha)
        val computed = file(desc = descSection(listOf(brush("round", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20))))))))
        val old = AbrImport.convert(computed, "pack")
        val modern = AbrImport.convertWithAssets(computed, "pack", codec)
        assertEquals(old, modern.library())
        assertTrue(modern.brushes.single().assets.isEmpty())
    }

    @Test fun materializedTipDoesNotDisappearAtTheLegacyExtensionStorageCap() {
        var seed = 17L
        val alpha = ByteArray(512 * 512) { seed = seed * 6364136223846793005L + 1; (seed ushr 32).toByte() }
        val source = abr(512, 512, alpha)
        val old = AbrImport.convert(source, "pack").brushes.single()
        assertEquals("procedural", old.preset.tip.source)
        val modern = AbrImport.convertWithAssets(source, "pack", PngCodec())
        assertTrue(modern.refused.isEmpty())
        val tip = modern.brushes.single()
        assertNotNull(tip.preset.tip.sampled)
        val decoded = ImageIO.read(ByteArrayInputStream(tip.assets.getValue("tip.png")))
        assertEquals(512, decoded.width); assertEquals(512, decoded.height)
        assertTrue(tip.warnings.none { "tip stays procedural" in it || "bitmap tip:" in it })
    }

    @Test fun badEncoderDimensionsRefuseOnlyThatBrushAndNeverProducePartialAssets() {
        val base = PngCodec()
        val wrong = object : ImportedTipPngCodec by base {
            override fun encodeAlpha(width: Int, height: Int, alpha: ByteArray) = png(1, 1, intArrayOf(-1))
        }
        val result = AbrImport.convertWithAssets(abr(2, 2, ByteArray(4)), "pack", wrong)
        assertTrue(result.brushes.isEmpty()); assertEquals(1, result.refused.size)
        assertTrue("dimensions" in result.refused.single().reason)
    }

    @Test fun procreateUsesWhiteOpaqueBlackTransparentAndSupportsExplicitAlphaOverride() {
        val pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())
        val source = procreate(png(2, 1, pixels))
        val old = ProcreateImport.convertBrush(source, "pack").brushes.single()
        assertNull(old.preset.tip.sampled)
        val result = ProcreateImport.convertBrushWithAssets(source, "pack", PngCodec()).brushes.single()
        val recipe = assertNotNull(result.preset.tip.sampled)
        assertEquals(SampleCoverage.luminance, recipe.coverage); assertTrue(recipe.invert)
        val mask = ImageMask.fromArgb(2, 1, pixels, recipe.coverage, recipe.invert)
        assertEquals(0f, mask.sample(-.5f, 0f)); assertEquals(1f, mask.sample(.5f, 0f))
        val explicitAlpha = ProcreateImport.convertBrushWithAssets(source, "pack", PngCodec(), MaterializedImportOptions())
        assertEquals(SampleCoverage.alpha, explicitAlpha.brushes.single().preset.tip.sampled!!.coverage)
        assertContentEquals(png(2, 1, pixels), result.assets.getValue("tip.png"))
    }

    @Test fun sameNamedProcreateBrushesKeepTheirOwnAssetsInPackOrder() {
        val a = png(1, 1, intArrayOf(0xff000000.toInt())); val b = png(1, 1, intArrayOf(-1))
        val archive = plist("Same")
        val source = zip(linkedMapOf("a/Brush.archive" to archive, "a/Shape.png" to a,
            "b/Brush.archive" to archive, "b/Shape.png" to b))
        val result = ProcreateImport.convertBrushSetWithAssets(source, "pack", PngCodec())
        assertTrue(result.refused.isEmpty()); assertEquals(2, result.brushes.size)
        assertEquals(result.brushes[0].preset.id, result.brushes[1].preset.id, "same names exercise asset identity by result order")
        assertContentEquals(a, result.brushes[0].assets.getValue("tip.png"))
        assertContentEquals(b, result.brushes[1].assets.getValue("tip.png"))
    }

    @Test fun kritaEmbeddedPngMaterializesWithoutChangingTheOldExtensionResult() {
        val image = png(3, 2, IntArray(6) { if (it % 2 == 0) -1 else 0 })
        val source = kpp(image)
        val old = KritaImport.convertKpp(source, "pack").brushes.single()
        val result = KritaImport.convertKppWithAssets(source, "pack", PngCodec()).brushes.single()
        assertNotNull(old.preset.extensions[KritaImport.TIP_IMAGE_KEY]); assertNull(old.preset.tip.sampled)
        assertNotNull(result.preset.tip.sampled); assertNull(result.preset.extensions[KritaImport.TIP_IMAGE_KEY])
        assertEquals(SampleCoverage.alpha, result.preset.tip.sampled!!.coverage)
        assertContentEquals(image, result.assets.getValue("tip.png"))
        val bundle = KritaImport.convertBundleWithAssets(zip(linkedMapOf("paintoppresets/a.kpp" to source,
            "paintoppresets/b.kpp" to kpp(png(1, 1, intArrayOf(-1))))), "pack", PngCodec())
        assertTrue(bundle.refused.isEmpty()); assertEquals(2, bundle.brushes.size)
        assertContentEquals(image, bundle.brushes[0].assets.getValue("tip.png"))
    }

    @Test fun pngMaterializationRequiresRealPixelsAndTheSampledEngine() {
        val invalid = procreate(byteArrayOf(1, 2, 3), explicitSize = true)
        assertFailsWith<BrushException> { ProcreateImport.convertBrushWithAssets(invalid, "pack", PngCodec()) }
        val tooWide = procreate(png(2049, 1, IntArray(2049) { -1 }))
        assertFailsWith<BrushException> { ProcreateImport.convertBrushWithAssets(tooWide, "pack", PngCodec()) }
        val smudge = KritaImport.convertKppWithAssets(kpp(png(1, 1, intArrayOf(-1)), "ColorSmudge"), "pack", PngCodec())
        assertTrue(smudge.brushes.isEmpty()); assertEquals(1, smudge.refused.size)
        assertTrue("stamp" in smudge.refused.single().reason)
    }

    private fun abr(w: Int, h: Int, bytes: ByteArray, depth: Int = 8, compression: Int = 0): ByteArray = file(
        desc = descSection(listOf(brush("sampled", itemOf("Brsh", sampledTip("sample-id"))))),
        samp = sampSection(listOf(TipEntry("sample-id", bottom = h, right = w, depth = depth, compression = compression, payload = bytes))))

    private fun procreate(shape: ByteArray, explicitSize: Boolean = false): ByteArray = zip(linkedMapOf(
        "Brush.archive" to plist("Sample", explicitSize), "Shape.png" to shape))

    private fun plist(name: String, explicitSize: Boolean = false): ByteArray =
        ("<plist><dict><key>name</key><string>$name</string>" +
            (if (explicitSize) "<key>maxSize</key><real>10</real>" else "") + "</dict></plist>").toByteArray()

    private fun kpp(shape: ByteArray, engine: String = "Pixel"): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(shape)
        val xml = """<paintop id="$engine"><brush_definition type="mask" name="tip.png" embedded="true"><param type="base64" name="preset_image" value="$encoded"/></brush_definition><spacing mode="space" value="0.3"/></paintop>"""
        val wrapper = png(1, 1, intArrayOf(-1))
        return wrapper.copyOf(wrapper.size - 12) + chunk("tEXt", "preset\u0000$xml".toByteArray()) + wrapper.takeLast(12).toByteArray()
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((name, bytes) in entries) {
            val entry = ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong()
                compressedSize = size; crc = CRC32().apply { update(bytes) }.value; time = 0 }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun chunk(type: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        val name = type.toByteArray(); val crc = CRC32().apply { update(name); update(bytes) }
        DataOutputStream(out).use { it.writeInt(bytes.size); it.write(name); it.write(bytes); it.writeInt(crc.value.toInt()) }
    }.toByteArray()

    companion object {
        private fun png(w: Int, h: Int, pixels: IntArray): ByteArray {
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, w, h, pixels, 0, w)
            return ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
        }
    }
}
