package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.imports.ImportedPngDimensions
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaterializedBrushImportsTest {
    @Test fun realEncoderPreservesEveryAlphaPixelAndIsAcceptedByStrictPngInspection() {
        val codec = MaterializedBrushImports.pngCodec
        val bytes = codec.encodeAlpha(2, 2, byteArrayOf(0, 64, 128.toByte(), 255.toByte()))
        assertEquals(ImportedPngDimensions(2, 2), codec.inspect(bytes))
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(listOf(0, 64, 128, 255), (0..3).map { decoded.getRGB(it % 2, it / 2) ushr 24 })
        assertEquals(0xffffff, decoded.getRGB(1, 1) and 0xffffff)
    }

    @Test fun actualPngCorruptionAndOversizedSourcesCannotBeMaterialized() {
        val codec = MaterializedBrushImports.pngCodec
        val bytes = codec.encodeAlpha(1, 1, byteArrayOf(255.toByte()))
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        assertFailsWith<BrushException> { codec.inspect(bytes) }
        assertFailsWith<BrushException> { codec.encodeAlpha(2049, 1, ByteArray(2049)) }
        assertFailsWith<BrushException> { codec.encodeAlpha(2, 2, ByteArray(3)) }
    }
}
