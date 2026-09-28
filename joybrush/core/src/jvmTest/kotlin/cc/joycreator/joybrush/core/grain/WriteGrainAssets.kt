package cc.joycreator.joybrush.core.grain

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grain textures the phone and the PC Brush Lab load, written as 8-bit grayscale PNGs. This
 * ALWAYS runs and overwrites, so the committed assets can never drift from the generator.
 */
class WriteGrainAssets {

    @Test
    fun writeCloudPngs() {
        val dir = File(joybrushRoot(), "assets" + File.separator + "grain")
        assertTrue(dir.isDirectory || dir.mkdirs(), "could not create ${dir.absolutePath}")
        write(dir, "cloud_256.png", CloudNoise.generate(256, 1L))
        write(dir, "cloud_512.png", CloudNoise.generate(512, 1L))
        write(
            dir,
            "cloud_fine_256.png",
            CloudNoise.generate(256, 2L, octaves = 3, baseCells = 16),
        )
    }

    private fun write(dir: File, name: String, heights: FloatArray) {
        val side = sqrt(heights.size.toDouble()).toInt()
        assertTrue(side * side == heights.size, "$name is not square (${heights.size} heights)")
        val image = BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY)
        val raster = image.raster
        for (y in 0 until side) for (x in 0 until side) {
            raster.setSample(x, y, 0, grey(heights[y * side + x]))
        }
        val file = File(dir, name)
        assertTrue(ImageIO.write(image, "png", file), "ImageIO refused to write $name")
        assertTrue(file.length() > 0L, "$name came out empty")

        val back = ImageIO.read(file)
        assertEquals(side, back.width, "$name width")
        assertEquals(side, back.height, "$name height")
        assertEquals(1, back.raster.numBands, "$name is not single-channel")
        // The bytes on disk must be the quantised heights, so a phone sampling the PNG sees what
        // the generator produced.
        for (y in 0 until side step 7) for (x in 0 until side step 7) {
            assertEquals(grey(heights[y * side + x]), back.raster.getSample(x, y, 0), "$name at ($x,$y)")
        }
    }

    private fun grey(height: Float): Int = (height * 255f).roundToInt().coerceIn(0, 255)

    /** The `joybrush/` folder: walk up from wherever the test task was started. */
    private fun joybrushRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (dir.name == "joybrush" && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "no joybrush/ folder with settings.gradle.kts above ${File("").absolutePath}",
        )
    }
}
