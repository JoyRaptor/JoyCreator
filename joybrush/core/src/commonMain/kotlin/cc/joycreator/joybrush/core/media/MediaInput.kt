package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * The app's pen samples as the media strokes read them (lab main.js `feed`). Input calibration, not physics: the pen's
 * reach ([REACH_DEG], the owner's S Pen tops out at 71°) counts as lying on its side, so tilt runs the stick's whole
 * range. A pen or finger with no tilt sensor is upright and leans the lab's default way.
 */
object MediaInput {
    const val REACH_DEG = 68.0
    /** The lean of a pen that does not report one (lab: −60°). */
    const val DEFAULT_AZ = -60.0 * PI / 180

    fun sample(s: PenSample, reachDeg: Double = REACH_DEG): MediaSample {
        val reach = max(20.0, reachDeg) * PI / 180
        val tilt = if (s.hasTilt) min(1.0, s.tilt / reach) * (PI / 2) else 0.0
        val az = if (s.hasAzimuth) s.azimuth.toDouble() else DEFAULT_AZ
        val p = if (s.pressure.isNaN()) 1.0 else s.pressure.toDouble().coerceIn(0.0, 1.0)
        return MediaSample(s.x.toDouble(), s.y.toDouble(), p, tilt, az, s.timeMs)
    }
}
