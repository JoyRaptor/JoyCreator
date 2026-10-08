package cc.joycreator.joybrush.core.bench

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic recordings, not captured artist work. Prefixes share the same strokes at every count. */
object R51Fixtures {
    const val FRAME_SIZE = 512
    const val SAMPLES_PER_STROKE = 33
    val pen = BrushPreset(id = "r51-pen", name = "Bench pen", size = Param(3f),
        sizeJitter = 0f, smoothing = 0.2f)
    // A procedural dry stamp pencil, not the realistic media pencil or a paper-grain benchmark.
    val pencil = BrushPreset(id = "r51-pencil", name = "Bench procedural pencil",
        size = Param(7f), flow = Param(0.25f), opacity = Param(0.65f),
        tip = TipSpec(hardness = Param(0.55f)), spacing = 0.12f,
        accumulate = "buildup", smoothing = 0.2f)
    val paint = BrushPreset(id = "r51-paint", name = "Bench overlapping paint stamps",
        size = Param(32f), flow = Param(0.35f), opacity = Param(0.6f),
        spacing = 0.12f, tip = TipSpec(hardness = Param(0.7f)), smoothing = 0.2f)

    fun lines(count: Int, brush: BrushPreset = pen): List<StrokeRecord> = strokes(count, brush, false)
    fun painted(count: Int): List<StrokeRecord> = strokes(count, paint, true)

    private fun strokes(count: Int, brush: BrushPreset, painted: Boolean): List<StrokeRecord> {
        require(count >= 0)
        return List(count) { i ->
            // Integer arithmetic fixes placement without a platform random source. Curves overlap
            // across tile borders; varying pressure and finite tilt mimic recorded pen channels.
            val cx = 80.0 + ((i * 137L + 19) % 352)
            val cy = 80.0 + ((i * 211L + 47) % 352)
            val phase = (i % 23) * PI / 11.5
            val rx = if (painted) 65.0 else 22.0 + i % 51
            val ry = if (painted) 37.0 else 12.0 + i % 37
            val samples = List(SAMPLES_PER_STROKE) { j ->
                val t = j.toDouble() / (SAMPLES_PER_STROKE - 1)
                val a = phase + t * PI * 1.7
                PenSample((cx + rx * cos(a)).toFloat(),
                    (cy + ry * sin(a) + 3 * sin(t * PI * 4 + phase)).toFloat(),
                    i * 400.0 + j * 8.0,
                    pressure = (0.3 + 0.65 * sin(PI * t)).toFloat(),
                    tilt = (0.25 + 0.3 * t).toFloat(), azimuth = phase.toFloat())
            }
            val color = if (painted) intArrayOf(0xffa64028.toInt(), 0xff287ca6.toInt(),
                0xffd5a83d.toInt(), 0xff634994.toInt())[i % 4] else 0xff182129.toInt()
            StrokeRecord("r51-$i", brush.id, 0x51L + i, brush.smoothing, 1f, samples, color)
        }
    }

    /** Dense 2048px ink-like hatching: connected white channels and many small black islands.
     * Walls are three pixels wide; each horizontal hatch has a four-pixel break. Seed (8,8).
     * Gap closing therefore has actual work; trace input comes from FloodFill, not a fake mask.
     */
    fun denseLineArt(size: Int = 2048): ByteArray {
        require(size >= 32)
        return ByteArray(size * size * 4).also { rgba ->
            for (y in 0 until size) for (x in 0 until size) {
                val border = x < 3 || y < 3 || x >= size - 3 || y >= size - 3
                val hatch = y % 24 < 3 && x % 48 >= 4
                val island = x % 48 in 20..22 && y % 24 in 8..16
                val v = if (border || hatch || island) 0 else 255
                val p = (y * size + x) * 4
                rgba[p] = v.toByte(); rgba[p + 1] = v.toByte(); rgba[p + 2] = v.toByte()
                rgba[p + 3] = 255.toByte()
            }
        }
    }
}
