package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.doc.RectPx
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Generated parity input contains the actual CPU output, never a separately reimplemented oracle. */
class PaperRasterFixtureTest {
    @Test fun writesLargeCoordinateSmoothFixture() {
        val size = 64
        val lookBytes = ByteArray(size * size * 4) { i ->
            if (i % 4 == 3) 255.toByte() else {
                val x = (i / 4) % size; val y = (i / 4) / size
                (186 + 3 * kotlin.math.sin(x * 2.0 * kotlin.math.PI / size) +
                    3 * kotlin.math.cos(y * 2.0 * kotlin.math.PI / size)).toInt().toByte()
            }
        }
        val look = LookEntry("large_fixture", "Large fixture", "#BABABA", "look.png", "#BABABA", 2f, 32f, true)
        val p = ResolvedPaper(null, look, 0xFFBABABA.toInt(), 1f, 1f, 1f, false, false)
        val expected = PaperRaster.render(p, PaperTexture(size, size, lookBytes), null, RectPx(10000000, -10000000, 64, 64))
        assertEquals(64 * 64 * 4, expected.size)
        fun numbers(bytes: ByteArray) = bytes.joinToString(",", "[", "]") { (it.toInt() and 255).toString() }
        val json = """{"width":64,"height":64,"origin":[10000000,-10000000],"coordinateUnits":"top-left doc px; pixel centres +0.5","base":[186,186,186],"scale":1,"show":1,"light":false,"tintSet":false,"look":{"size":64,"texelPx":2,"hexTexels":32,"rotatable":true,"mean":[186,186,186],"rgba":${numbers(lookBytes)}},"surface":null,"expected":${numbers(expected)}}"""
        val core = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory }
        File(core, "build/paper-raster-large-fixture.json").apply { parentFile.mkdirs(); writeText(json) }
    }

    @Test fun writesScreenParityFixtureAtDocumentPixelCentres() {
        val size = 64
        val lookBytes = ByteArray(size * size * 4) { i ->
            if (i % 4 == 3) 255.toByte() else (150 + (i / 4 * 17 + i % 4 * 13) % 80).toByte()
        }
        val surfaceBytes = ByteArray(size * size * 4) { i ->
            when (i % 4) { 0 -> (115 + (i / 4 * 7) % 25).toByte()
                1 -> (115 + (i / 4 * 11) % 25).toByte()
                2 -> (70 + (i / 4 * 31) % 150).toByte(); else -> 127.toByte() }
        }
        val look = LookEntry("fixture", "Fixture", "#DAC6A2", "look.png", "#BABABA", 2f, 32f, true)
        val surface = SurfaceEntry("fixture", "Fixture", "surface.png", size, 2f, 0.099f, 32f, true, 1f)
        val p = ResolvedPaper(surface, look, 0xFFDAC6A2.toInt(), 1f, 0.8f, 1f, true, true)
        val expected = PaperRaster.render(p, PaperTexture(size, size, lookBytes), PaperTexture(size, size, surfaceBytes), RectPx(0, 0, 256, 256))
        assertEquals(256 * 256 * 4, expected.size)
        fun numbers(bytes: ByteArray) = bytes.joinToString(",", "[", "]") { (it.toInt() and 255).toString() }
        val json = """{"width":256,"height":256,"origin":[0,0],"coordinateUnits":"top-left doc px; pixel centres +0.5","base":[218,198,162],"scale":1,"show":0.8,"light":true,"tintSet":true,"look":{"size":64,"texelPx":2,"hexTexels":32,"rotatable":true,"mean":[186,186,186],"rgba":${numbers(lookBytes)}},"surface":{"size":64,"texelPx":2,"hexTexels":32,"rotatable":true,"slopeRange":0.099,"relief":1,"rgba":${numbers(surfaceBytes)}},"expected":${numbers(expected)}}"""
        val core = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/commonMain/kotlin/cc/joycreator/joybrush/core").isDirectory }
        File(core, "build/paper-raster-fixture.json").apply { parentFile.mkdirs(); writeText(json) }
    }
}