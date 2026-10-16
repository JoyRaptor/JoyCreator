package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.input.Angles
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * The app's pen samples as the media strokes read them (lab main.js `feed`). Input calibration, not physics: the pen's
 * reach ([REACH_DEG], the owner's S Pen tops out at 71°) counts as lying on its side, so tilt runs the stick's whole
 * range. A pen or finger with no tilt sensor is upright and leans the lab's default way.
 *
 * The lean: the media strokes need the direction the BARREL leans (stick frame x points toward it). Android's
 * AXIS_ORIENTATION is the direction the TIP points (0 = the tip toward the top of the screen, the barrel toward the
 * person), and [PenSample.azimuth] carries it through [cc.joycreator.joybrush.core.input.AxisMapping] unturned, which
 * the stamp and tuft brushes were tuned against on the phone. So the media turn it half round here, and only here.
 * Chrome on the same Note 9 makes the same conversion for the lab and the pencil tuner, which lean correctly (the
 * owner, 2026-10-15: in the app the pencil looked like "a graphite spray airbrush"; the tuner "works great").
 */
object MediaInput {
    const val REACH_DEG = 68.0
    /** The lean of a pen that does not report one: the barrel toward the lower right, as the lab's −60° (y up). */
    const val DEFAULT_AZ = 60.0 * PI / 180

    fun sample(s: PenSample, reachDeg: Double = REACH_DEG): MediaSample {
        val reach = max(20.0, reachDeg) * PI / 180
        val tilt = if (s.hasTilt) min(1.0, s.tilt / reach) * (PI / 2) else 0.0
        val az = if (s.hasAzimuth) Angles.wrap(s.azimuth.toDouble() + PI) else DEFAULT_AZ
        val p = if (s.pressure.isNaN()) 1.0 else s.pressure.toDouble().coerceIn(0.0, 1.0)
        return MediaSample(s.x.toDouble(), s.y.toDouble(), p, tilt, az, s.timeMs)
    }
}
