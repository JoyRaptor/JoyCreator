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

    /**
     * Exact-zero encoding: 127 is flat, and the two rails −[slopeRange] and +[slopeRange] are 0 and 254.
     * Byte 255 is never written, so every byte this returns is one [decodeSlope] can name.
     *
     * A non-finite [s] is refused by name. `coerceIn` lets a NaN through, because both of its
     * comparisons are false; `round` hands the NaN back; and `Double.toInt()` narrows NaN to 0 (JLS
     * 5.1.3). Byte 0 is the −[slopeRange] rail, so before the guard one NaN in a hand-built slope array
     * packed as a texel tilted as hard downhill as the format can express, with nothing thrown and
     * nothing logged. [defaultSlopeRange] does not always catch it first either: its 99.9th-percentile
     * index sits well below the last element, so a NaN that sorts to the end is never looked at.
     * [pack] cannot reach this path at all — its heights come from bytes, so its slopes are differences
     * of numbers in 0..1 and are finite by construction.
     */
    fun encodeSlope(s: Float, slopeRange: Float): Int {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        require(s.isFinite()) { "a slope must be a finite number, was $s" }
        return encodeDouble(s.toDouble(), slopeRange.toString().toDouble())
    }

    private fun encodeDouble(s: Double, range: Double): Int =
        round(127.0 + 127.0 * (s / range).coerceIn(-1.0, 1.0)).toInt()

    /**
     * Inverse of [encodeSlope], with no offset and no half-byte bias.
     *
     * `encodeSlope(k·[slopeRange]/127, slopeRange)` is exactly `127 + k` for every integer k in
     * −127..127, because the encode is `round(127 + 127·clamp(k/127))` and k is already inside the
     * clamp. So byte `127 + k` names the slope `k·slopeRange/127`, which puts byte 127 at exactly 0,
     * byte 126 at −`slopeRange`/127 and byte 128 at +`slopeRange`/127 — the two encode steps either
     * side of flat, not a bias in one direction. Byte 255 is never written; read one and it decodes to
     * 128·`slopeRange`/127, past the rail.
     */
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
     *
     * A is the second moment of the height inside one texel, carried in a single byte, and the
     * quantisation is the whole of what it measures. Writing `u = b²/255` for the height byte b:
     * ```
     *   A = round(u),  B² = b²/65025 = u/255,  so  A − B² = (round(u) − u)/255 = e/255, |e| ≤ 1/2
     * ```
     * Nothing about the surface's roughness survives that division by 255. Put `r = b² mod 255`, and
     * `e` is `−r/255` when `r ≤ 127` and `(255 − r)/255` above it, so **`A − B²` is quantisation error
     * and not a local variance**: it lies in ±127/65025 (1.9531e-3), its SIGN is decided by `b² mod 255`
     * rather than by the paper, and on the shipped `assets/paper/surface_pulp_artisan.png` it is negative
     * on 155902 of its 262144 texels, minimum −1.8608e-3 (which is −121/65025, the height byte 11). A
     * consumer that wants roughness must take `max(A − B², 0)`; `sqrt(A − B²)` is NaN on those texels.
     *
     * The same byte budget leaves A with no signal over the twenty darkest height bytes: b ≤ 11 gives
     * A = 0 and 12 ≤ b ≤ 19 gives A = 1, because `255·(b/255)²` is still under 1.5 for b ≤ 19. A is
     * therefore not a "this texel is dark" flag — one value, 0, means both a black texel and a rounded
     * one — and on the shipped surface all 22 of the texels with B ≤ 11 sit in the A = 0 half of it.
     *
     * JB-9.01 Decision 3 and JB-9.02 Decision 2 both promised a real variance here. The BYTES are
     * unchanged: they are what `joybrush/tools/paper/pack.py` writes (`np.round(255 * h * h)`) and what
     * `SurfaceAssetTest` (jvmTest) checks against the shipped file. Only the claim about what A means is
     * corrected.
     */
    fun pack(heightBytes: ByteArray, w: Int, h: Int, slopeRange: Float): ByteArray {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        val height = DoubleArray(heightBytes.size) { heightBytes[it].toInt().and(0xFF) / 255.0 }
        val (dx, dy) = slopesDouble(height, w, h)
        val out = ByteArray(w * h * 4)
        // The Float→Double widening of slopeRange, done once. `Float.toString().toDouble()` is the
        // widening that matches pack.py's float64 for every Float value, and it was being rebuilt from
        // a string twice per texel.
        val range = slopeRange.toString().toDouble()
        for (i in heightBytes.indices) {
            out[i * 4] = encodeDouble(dx[i], range).toByte()
            out[i * 4 + 1] = encodeDouble(dy[i], range).toByte()
            out[i * 4 + 2] = heightBytes[i]
            out[i * 4 + 3] = round(255.0 * height[i] * height[i]).toInt().toByte()
        }
        return out
    }
}
