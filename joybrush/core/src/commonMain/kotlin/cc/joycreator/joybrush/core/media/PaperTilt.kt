package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which way the paper runs downhill, and how steeply (M5.6, owner 2026-10-11): a direction in DOCUMENT space (y DOWN, as
 * the app's documents are) whose length is sin(tilt angle), at most 1. [FLAT] is a level table. This is the engine's
 * `slope`; the PC lab's `wet.js` `tiltSlope` is the same number in a y-UP document.
 */
data class PaperSlope(val x: Float, val y: Float) {
    companion object {
        val FLAT = PaperSlope(0f, 0f)
    }
}

/**
 * The phone's gravity reading, made into the paper's slope. One instance per sensor session; feed it every reading.
 *
 * The chain (MEDIA_ENGINE_PLAN M5.6, ported from the lab's `hookPhoneTilt`, joybrush/lab/media/js/main.js 571-610):
 * 1. Gravity's in-plane part, as a fraction of the whole (÷ |g|, so it is sin of the tilt, whatever units the sensor uses).
 *    Android reports the push that holds the phone up, so downhill is its negative.
 * 2. Minus the reading at switch-on: the pose held when the person enabled it is "flat", so drawing at a comfortable
 *    angle stays level and only tipping the phone runs the water. Taken in the DEVICE frame, before any turning, so
 *    the reference survives the screen rotating mid-session (the same answer as the spec's order while it does not).
 * 3. Turned by the display rotation into screen axes (x right, y down), then by the canvas view's own rotation into
 *    document axes: tipping the phone toward the floor sends the paint toward the floor however the page is turned.
 * 4. Low-pass 0.25 per reading, dead zone 0.04, magnitude capped at 1 (upright).
 */
class PaperTilt(private val smoothing: Float = SMOOTHING, private val deadZone: Float = DEAD_ZONE) {

    private var haveRef = false
    private var refX = 0f
    private var refY = 0f
    private var smoothX = 0f
    private var smoothY = 0f

    /** The slope after the last reading. */
    var slope: PaperSlope = PaperSlope.FLAT
        private set

    /** Forget the reference pose: the next reading is level. Call it when the sensor is switched on or re-registered. */
    fun reset() {
        haveRef = false
        smoothX = 0f
        smoothY = 0f
        slope = PaperSlope.FLAT
    }

    /**
     * One gravity reading (device axes: x right, y up the screen, z out of it, for the device's natural orientation).
     * [displayQuarterTurns] is `Surface.ROTATION_*` (0, 1, 2, 3 = 0, 90, 180, 270 degrees anticlockwise from natural).
     * [canvasRotationRad] is the canvas view's rotation (+ is clockwise on screen). Returns the new slope.
     */
    fun feed(gx: Float, gy: Float, gz: Float, displayQuarterTurns: Int, canvasRotationRad: Float): PaperSlope {
        val mag = sqrt(gx * gx + gy * gy + gz * gz)
        if (!mag.isFinite() || mag < MIN_GRAVITY) return slope
        val dx = -gx / mag
        val dy = -gy / mag
        if (!haveRef) { haveRef = true; refX = dx; refY = dy }
        // Device frame, y up -> screen frame (still y up, turned by the display) -> y down.
        val (rx, ry) = rotateByDisplay(dx - refX, dy - refY, displayQuarterTurns)
        val (tx, ty) = screenToDoc(rx, -ry, canvasRotationRad)
        smoothX += smoothing * (tx - smoothX)
        smoothY += smoothing * (ty - smoothY)
        val m = hypot(smoothX, smoothY)
        slope = if (m > deadZone) {
            val k = min(1f, m - deadZone) / m
            PaperSlope(smoothX * k, smoothY * k)
        } else PaperSlope.FLAT
        return slope
    }

    companion object {
        /** Low-pass: the share of the gap closed per reading (lab main.js 585). */
        const val SMOOTHING = 0.25f

        /** A steady hand is level: no slope below this (lab main.js 588). */
        const val DEAD_ZONE = 0.04f

        private const val MIN_GRAVITY = 1e-3f

        /** The pad's curve: half way out is about 32 degrees, three quarters about 58 (lab main.js 549). */
        private const val PAD_CURVE = 1.5f

        /** A puck nearer the middle than this (of the radius) points nowhere (lab main.js 550). */
        private const val PAD_CENTRE = 0.02f

        /** Device (x, y up) turned by the display's anticlockwise quarter turns, into screen axes (x right, y up). */
        fun rotateByDisplay(x: Float, y: Float, quarterTurns: Int): Pair<Float, Float> = when (((quarterTurns % 4) + 4) % 4) {
            1 -> Pair(-y, x)
            2 -> Pair(-x, -y)
            3 -> Pair(y, -x)
            else -> Pair(x, y)
        }

        /**
         * A direction on the SCREEN (x right, y down) in the document's axes for a page turned by [canvasRotationRad]
         * (+ clockwise): the linear part of `ViewTransform.screenToDoc`. Length is kept.
         */
        fun screenToDoc(x: Float, y: Float, canvasRotationRad: Float): Pair<Float, Float> {
            val c = cos(canvasRotationRad.toDouble()).toFloat()
            val s = sin(canvasRotationRad.toDouble()).toFloat()
            return Pair(c * x + s * y, -s * x + c * y)
        }

        /**
         * The tilt pad: the puck is [dx], [dy] (screen px, y down) from the middle of a pad whose travel is [radius].
         * Distance is steepness (angle = 90 degrees x (distance / radius)^1.5, so fine near level, upright at the rim;
         * lab main.js 549), direction is downhill. Past the rim is clamped to it. The answer is in SCREEN axes (y down) —
         * a puck pulled toward the bottom of the screen sends the paint to the bottom — so turn it with [screenToDoc]
         * for a rotated page. The lab flips y here because its document is y up; the app's is y down, so nothing flips.
         */
        fun padToSlope(dx: Float, dy: Float, radius: Float): PaperSlope {
            if (!radius.isFinite() || radius <= 0f || !dx.isFinite() || !dy.isFinite()) return PaperSlope.FLAT
            val len = hypot(dx, dy)
            val amount = min(1f, len / radius)
            if (amount <= PAD_CENTRE) return PaperSlope.FLAT
            val degrees = 90f * amount.pow(PAD_CURVE)
            val s = sin(degrees.toDouble() * PI / 180.0).toFloat()
            return PaperSlope(dx / len * s, dy / len * s)
        }

        /** Both ways the tilt can be set, as the document slope: the pad wins whenever the puck is off the middle. */
        fun combine(pad: PaperSlope, phone: PaperSlope): PaperSlope =
            if (max(kotlin.math.abs(pad.x), kotlin.math.abs(pad.y)) > 0f) pad else phone
    }
}
