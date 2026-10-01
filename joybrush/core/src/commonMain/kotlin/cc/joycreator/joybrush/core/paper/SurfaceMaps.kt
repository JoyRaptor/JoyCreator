package cc.joycreator.joybrush.core.paper

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Surface maps: a height map becomes slopes, slopes become the GPU texture layout (JB-9.01).
 *
 * Every paper surface, and every texture an imported brush brings, is a HEIGHT map. What a dry brush
 * needs from it is not the height but the **slopes** — which way each spot faces — so paint can catch
 * the faces that meet the stroke (R10 §5). That conversion lives here, once, for every caller: the
 * paper packer, the importer, the catalogue's own tool, the CPU sampler and the shader all read
 * slopes that came out of this one function.
 *
 * This is the Kotlin twin of `joybrush/tools/paper/pack.py`, and the shipped
 * `assets/paper/surface_pulp_artisan.png` is the golden. Any change here that moves a weight, a sign,
 * a divisor or an encoding must land in pack.py in the same commit, the way `TipMath` follows the
 * shader; `SurfaceAssetTest` (jvmTest) is what catches the day someone forgets.
 */
object SurfaceMaps {

    /** Divisor that makes a ramp `h = k·x` report a slope of exactly k (Scharr weights 3,10,3 over 2 texels). */
    const val SCHARR_NORM = 32f

    /**
     * Slopes of a TILEABLE height map (wraps at every edge: the left neighbour of `x = 0` is `x = w-1`).
     *
     * `height`: `w*h` values 0..1, row-major, row 0 = top. Returns `(dx, dy)` in height units per texel:
     * ```
     * dx = (3·(H[y-1][x+1] − H[y-1][x-1]) + 10·(H[y][x+1] − H[y][x-1]) + 3·(H[y+1][x+1] − H[y+1][x-1])) / 32
     * dy = (3·(H[y+1][x-1] − H[y-1][x-1]) + 10·(H[y+1][x] − H[y-1][x]) + 3·(H[y+1][x+1] − H[y-1][x+1])) / 32
     * ```
     * (y grows DOWN, so dy > 0 means the ground rises toward the bottom of the picture.)
     */
    fun slopes(height: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val (dx, dy) = slopesDouble(DoubleArray(height.size) { height[it].toDouble() }, w, h)
        return FloatArray(dx.size) { dx[it].toFloat() } to FloatArray(dy.size) { dy[it].toFloat() }
    }

    private fun slopesDouble(height: DoubleArray, w: Int, h: Int): Pair<DoubleArray, DoubleArray> {
        require(w >= 3 && h >= 3) { "a surface needs at least 3x3 to have interior texels, got ${w}x$h" }
        require(height.size == w * h) { "height has ${height.size} values, but ${w}x$h needs ${w * h}" }
        val dx = DoubleArray(w * h)
        val dy = DoubleArray(w * h)
        for (y in 0 until h) {
            val rowUp = ((y - 1 + h) % h) * w
            val row = y * w
            val rowDown = ((y + 1) % h) * w
            for (x in 0 until w) {
                val left = (x - 1 + w) % w
                val right = (x + 1) % w
                val i = row + x
                dx[i] = (3.0 * (height[rowUp + right] - height[rowUp + left]) +
                    10.0 * (height[row + right] - height[row + left]) +
                    3.0 * (height[rowDown + right] - height[rowDown + left])) / 32.0
                dy[i] = (3.0 * (height[rowDown + left] - height[rowUp + left]) +
                    10.0 * (height[rowDown + x] - height[rowUp + x]) +
                    3.0 * (height[rowDown + right] - height[rowUp + right])) / 32.0
            }
        }
        return dx to dy
    }

    /**
     * The 99.9th percentile of `|dx| ∪ |dy|`, rounded UP to a multiple of 0.001 (pack.py's default).
     *
     * The percentile, not the maximum: one torn texel or one speck of dirt in a scan would otherwise
     * set the range for the whole surface and flatten the other 99.9% of it into two or three bytes.
     * Rounded UP so every slope still fits a byte — a value rounded to nearest could land a hair
     * outside ±range and clamp.
     */
    fun defaultSlopeRange(dx: FloatArray, dy: FloatArray): Float {
        val n = dx.size + dy.size
        require(n > 0) { "there are no slopes to take a range from" }
        val magnitudes = FloatArray(n) { i -> abs(if (i < dx.size) dx[i] else dy[i - dx.size]) }
        magnitudes.sort()
        // NumPy's default linear interpolation, so a range from this and a range from pack.py agree.
        val pos = 0.999 * (n - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, n - 1)
        val percentile = (magnitudes[lo] + (pos - lo) * (magnitudes[hi] - magnitudes[lo])).toFloat()
        return (ceil(percentile * 1000f) / 1000f).coerceAtLeast(0.001f)
    }

    /** Exact-zero encoding: 127 is flat; -range and +range are 0 and 254. */
    fun encodeSlope(s: Float, slopeRange: Float): Int {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        return encodeDouble(s.toDouble(), slopeRange.toString().toDouble())
    }

    private fun encodeDouble(s: Double, range: Double): Int =
        round(127.0 + 127.0 * (s / range).coerceIn(-1.0, 1.0)).toInt()

    /** Inverse, including the exact zero at byte 127. */
    fun decodeSlope(b: Int, slopeRange: Float): Float {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        return (b - 127) / 127f * slopeRange
    }

    /** Filter first, decode linearly. Only two Float ulps around encoded zero are exact-zero aliases. */
    fun decodeFilteredSlope(v: Float, slopeRange: Float): Float {
        val delta = v * 255f - 127f
        return if (abs(delta) <= 1f / 65536f) 0f else delta / 127f * slopeRange
    }

    /**
     * The GPU surface layout, RGBA8 row-major: R = encodeSlope(dx), G = encodeSlope(dy), B = the height
     * byte as given, A = `round(255·h²)` where h = heightByte/255. Input is greyscale BYTES (what a PNG
     * holds), so B round-trips exactly.
     *
     * The height is NOT normalised (min→0, max→1) here: B has to round-trip, so a normalised B would
     * be a different paper. Slopes are stored, not unit normals, because slopes add and filter
     * linearly (Mikkelsen 2020/2022); a normal is `normalize(−dx, −dy, 1)` wherever lighting needs one.
     * A = h² gives the mips the surface's roughness for free, since variance = A − B².
     */
    fun pack(heightBytes: ByteArray, w: Int, h: Int, slopeRange: Float): ByteArray {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        val height = DoubleArray(heightBytes.size) { heightBytes[it].toInt().and(0xFF) / 255.0 }
        val (dx, dy) = slopesDouble(height, w, h)
        val out = ByteArray(w * h * 4)
        for (i in heightBytes.indices) {
            val b = heightBytes[i].toInt().and(0xFF)
            out[i * 4] = encodeDouble(dx[i], slopeRange.toString().toDouble()).toByte()
            out[i * 4 + 1] = encodeDouble(dy[i], slopeRange.toString().toDouble()).toByte()
            out[i * 4 + 2] = heightBytes[i]
            out[i * 4 + 3] = round(255.0 * height[i] * height[i]).toInt().toByte()
        }
        return out
    }
}
