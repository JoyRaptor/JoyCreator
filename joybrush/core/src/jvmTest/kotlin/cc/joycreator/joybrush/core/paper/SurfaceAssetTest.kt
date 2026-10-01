package cc.joycreator.joybrush.core.paper

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE cross-check between the Kotlin twin and the Python original (JB-9.01).
 *
 * `joybrush/tools/paper/pack.py` is what actually produced the shipped
 * `assets/paper/surface_pulp_artisan.png`. This test reads that file, takes its B channel (the
 * height) back out, and asks `SurfaceMaps.pack` to rebuild the whole texture. If the twin has drifted
 * from the tool in a weight, a sign, a divisor, an encoding rule or the wrap, the rebuilt RGBA stops
 * matching the bytes on disk — which is the only place that failure is visible, since the golden is
 * a 512² image and no unit test asserts against it.
 *
 * It lives in `jvmTest`, not `commonTest`, because it opens a file through `javax.imageio` (the same
 * reason the grain tests are jvmTest).
 */
class SurfaceAssetTest {

    /** The joybrush module root: the nearest ancestor holding `assets/paper/`. */
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "assets/paper/surface_pulp_artisan.png").isFile || File(it, "joybrush/assets/paper/surface_pulp_artisan.png").isFile }
        .let { if (File(it, "assets/paper").isDirectory) it else File(it, "joybrush") }

    /**
     * Rebuilding the shipped artisan surface with the catalogue's own `slopeRange` must reproduce the
     * file byte for byte, to within ±1 per channel.
     *
     * ±1, and not 0, because the two implementations do their last rounding at different
     * precisions: pack.py rounds in NumPy float64, this in Kotlin Float. A channel one step out is
     * still a match; anything larger is a real disagreement and fails.
     *
     * The count of differing channels is reported, because "it passed" and "it needed the tolerance
     * on 40% of the texels" are very different states of the world and a reader should be able to
     * tell them apart.
     */
    @Test
    fun theShippedArtisanSurfaceIsWhatThisKotlinTwinPacks() {
        val img = ImageIO.read(File(root(), "assets/paper/surface_pulp_artisan.png"))
        val w = img.width
        val h = img.height
        assertTrue(w >= 3 && h >= 3, "the shipped surface must be at least 3x3, is ${w}x$h")

        val rgba = ByteArray(w * h * 4)
        val heightBytes = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val argb = img.getRGB(x, y)
            val i = (y * w + x) * 4
            rgba[i] = ((argb ushr 16) and 0xFF).toByte()
            rgba[i + 1] = ((argb ushr 8) and 0xFF).toByte()
            rgba[i + 2] = (argb and 0xFF).toByte()
            rgba[i + 3] = ((argb ushr 24) and 0xFF).toByte()
            heightBytes[y * w + x] = rgba[i + 2]
        }

        val packed = SurfaceMaps.pack(heightBytes, w, h, SLOPE_RANGE)
        var differing = 0
        var worst = 0
        for (i in packed.indices) {
            val d = abs((packed[i].toInt() and 0xFF) - (rgba[i].toInt() and 0xFF))
            if (d > 0) differing++
            if (d > worst) worst = d
        }
        println(
            "JB-9.01 SurfaceAssetTest: ${w}x$h slopeRange=$SLOPE_RANGE, " +
                "$differing of ${packed.size} channels differ from the shipped file by ±1 or more; worst $worst",
        )
        assertTrue(
            worst <= 1,
            "pack() drifted from the shipped file: worst channel difference is $worst over " +
                "$differing of ${packed.size} channels. The twin must match pack.py, not merely resemble it.",
        )
    }

    private companion object {
        /** The catalogue's value for `pulp_artisan` (assets/paper/catalogue.json). */
        const val SLOPE_RANGE = 0.099f
    }
}
