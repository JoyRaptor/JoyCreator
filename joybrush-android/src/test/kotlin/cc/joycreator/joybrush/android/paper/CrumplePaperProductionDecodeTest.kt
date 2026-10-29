package cc.joycreator.joybrush.android.paper

import android.graphics.Bitmap
import cc.joycreator.joybrush.androidkit.gl.GrainTextures
import cc.joycreator.joybrush.core.paper.PaperCatalogue
import cc.joycreator.joybrush.core.paper.PaperCatalogues
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrumplePaperProductionDecodeTest {
    private val fixture by lazy {
        val input = javaClass.getResourceAsStream("/crumple-paper/decode-expectations.json")
            ?: error("Missing crumple-paper decode expectations")
        input.use { Json.parseToJsonElement(it.reader().readText()).jsonObject }
    }

    private fun paperResource(path: String): ByteArray =
        (GrainTextures::class.java.getResourceAsStream("/joybrush/assets/paper/$path")
            ?: error("Missing packaged paper resource $path")).use { it.readBytes() }

    private fun productionCatalogue(): PaperCatalogue =
        PaperCatalogues.parse(paperResource("catalogue.json").decodeToString())

    private fun expectedRawHash(file: String): String = fixture["files"]!!.jsonArray
        .first { it.jsonObject["file"]!!.jsonPrimitive.content == file }
        .jsonObject["rawRgbaSha256"]!!.jsonPrimitive.content

    private fun decodedRgba(file: String): Pair<Bitmap, ByteArray> {
        val bitmap = GrainTextures.loadPackagedBitmap("paper", file)
            ?: error("Production BitmapFactory loader could not decode $file")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap to GrainTextures.rgbaBytes(pixels)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun productionCatalogueKeepsBaselineAndAppendsTheThreeMappedVariants() {
        val additions = Json.decodeFromJsonElement<PaperCatalogue>(fixture["additions"]!!)
        val catalogue = productionCatalogue()
        assertTrue(PaperCatalogues.problems(catalogue).toString(), PaperCatalogues.problems(catalogue).isEmpty())

        val expectedSurfaceIds = fixture["baselineSurfaceIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val expectedLookIds = fixture["baselineLookIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(catalogue.surfaces.map { it.id }.containsAll(expectedSurfaceIds))
        assertTrue(catalogue.looks.map { it.id }.containsAll(expectedLookIds))
        assertEquals(additions.surfaces.map { it.id }.toSet(), catalogue.surfaces.map { it.id }.toSet() - expectedSurfaceIds)
        assertEquals(additions.looks.map { it.id }.toSet(), catalogue.looks.map { it.id }.toSet() - expectedLookIds)

        for (surface in additions.surfaces) {
            val actual = catalogue.surfaces.single { it.id == surface.id }
            assertEquals(surface, actual)
            val look = catalogue.looks.single { it.id == surface.id }
            assertEquals(surface.id, look.defaultSurface)
            assertEquals(surface.texelPx, look.texelPx)
            assertEquals(surface.hexTexels, look.hexTexels)
            assertEquals(surface.rotatable, look.rotatable)
        }
    }

    @Test fun bitmapFactoryReturnsExpectedPngChannelsAndOpaqueSizedJpegLooks() {
        for (spec in fixture["files"]!!.jsonArray.map { it.jsonObject }) {
            val file = spec["file"]!!.jsonPrimitive.content
            val (bitmap, rgba) = decodedRgba(file)
            try {
                assertEquals(file, spec["width"]!!.jsonPrimitive.content.toInt(), bitmap.width)
                assertEquals(file, spec["height"]!!.jsonPrimitive.content.toInt(), bitmap.height)
                assertFalse("$file must be decoded without premultiplication", bitmap.isPremultiplied)
                if (spec["mode"]!!.jsonPrimitive.content == "RGB") {
                    val nonOpaquePixel = (3 until rgba.size step 4)
                        .firstOrNull { (rgba[it].toInt() and 0xff) != 255 }
                    assertEquals("$file must decode as opaque", null, nonOpaquePixel)
                } else {
                    assertEquals(file, expectedRawHash(file), sha256(rgba))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun packedSurfaceProfilesPassThroughWithHeightAndIndependentAlphaIntact() {
        val catalogue = productionCatalogue()
        for (entry in catalogue.surfaces.filter { it.id.startsWith("eval_expanded_firm") && it.packed }) {
            val (bitmap, rgba) = decodedRgba(entry.file)
            try {
                assertEquals(entry.size, bitmap.width)
                assertEquals(entry.size, bitmap.height)
                assertEquals(expectedRawHash(entry.file), sha256(rgba))
                val expanded = GrainTextures.expandSurface(entry.file, rgba, bitmap.width, bitmap.height)
                assertTrue("${entry.file} packed RGBA must pass through unchanged", rgba.contentEquals(expanded))
                assertTrue("${entry.file} fixture must include semitransparent alpha data",
                    (3 until expanded.size step 4).any { (expanded[it].toInt() and 0xff) in 1..254 })
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun masterHeightExpandsToBHeightAndPerTexelHeightSquaredAlpha() {
        val entry = productionCatalogue().surfaces.single { it.id == "eval_expanded_firm" }
        assertFalse(entry.packed)
        val (bitmap, rgba) = decodedRgba(entry.file)
        try {
            assertEquals(entry.size, bitmap.width)
            assertEquals(entry.size, bitmap.height)
            assertEquals(expectedRawHash(entry.file), sha256(rgba))
            val packed = GrainTextures.expandSurface(entry.file, rgba, bitmap.width, bitmap.height)
            var mismatch = -1
            for (pixel in rgba.indices step 4) {
                val h = rgba[pixel].toInt() and 0xff
                if (h != (packed[pixel + 2].toInt() and 0xff)) {
                    mismatch = pixel / 4
                    break
                }
                val expectedH2 = kotlin.math.round(255.0 * h / 255.0 * h / 255.0).toInt()
                if (expectedH2 != (packed[pixel + 3].toInt() and 0xff)) {
                    mismatch = pixel / 4
                    break
                }
            }
            assertEquals("B=height and A=height² must hold at every master texel", -1, mismatch)
        } finally {
            bitmap.recycle()
        }
    }

}
