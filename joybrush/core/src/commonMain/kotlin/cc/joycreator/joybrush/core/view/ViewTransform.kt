package cc.joycreator.joybrush.core.view

import cc.joycreator.joybrush.core.input.Angles
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * Where the document is on the screen: zoom, rotation and pan. JB-2.02.
 *
 * One rule, and every other function here follows from it:
 *
 *     screen = translate(panX, panY) · rotate(rotation) · scale(zoom) · document
 *
 * Screen px have y growing DOWN, so a positive [rotation] turns the page clockwise — the way two
 * fingers turn it. Document px are the px the tile engine stores (256² tiles), so a dab at
 * (10, 10) is always at (10, 10) in the document whatever the view is doing to it.
 *
 * Pure maths and platform-neutral on purpose: the Android shell, the PC Brush Lab and a future
 * iOS shell all turn the page through this class, so "zoomed to 200%" means one thing everywhere
 * and the gesture maths is unit-testable on a computer with no phone in sight.
 *
 * Every number written here is guarded. A non-finite value is IGNORED (the old one stands) and
 * [zoom] is clamped, because one bad float arriving from a gesture must not be able to make the
 * canvas permanently unzoomable or draw nothing at all — the same rule the engine follows for a
 * brush with an infinite size (LEAD_RULINGS R1).
 */
class ViewTransform {

    /** Screen px per document px. Every write is clamped to [MIN_ZOOM]..[MAX_ZOOM]. */
    var zoom: Float = 1f
        set(v) { field = if (v.isFinite()) v.coerceIn(MIN_ZOOM, MAX_ZOOM) else field }

    /**
     * How far the page is turned, radians; + is clockwise on screen. Wrapped into -PI..PI on every
     * write, so a long session never drifts towards float noise.
     */
    var rotation: Float = 0f
        set(v) { field = if (v.isFinite()) Angles.wrap(v.toDouble()).toFloat() else field }

    /** Screen position of document (0, 0), px. */
    var panX: Float = 0f
        set(v) { field = if (v.isFinite()) v else field }

    /** Screen position of document (0, 0), px. */
    var panY: Float = 0f
        set(v) { field = if (v.isFinite()) v else field }

    /** Document px → screen px. */
    fun docToScreen(x: Float, y: Float): Pair<Float, Float> {
        val c = cos(rotation.toDouble())
        val s = sin(rotation.toDouble())
        val z = zoom.toDouble()
        val dx = x.toDouble()
        val dy = y.toDouble()
        return Pair(
            (panX.toDouble() + z * (c * dx - s * dy)).toFloat(),
            (panY.toDouble() + z * (s * dx + c * dy)).toFloat(),
        )
    }

    /** Screen px → document px: the exact inverse of [docToScreen]. */
    fun screenToDoc(x: Float, y: Float): Pair<Float, Float> {
        val c = cos(rotation.toDouble())
        val s = sin(rotation.toDouble())
        val u = (x.toDouble() - panX) / zoom
        val v = (y.toDouble() - panY) / zoom
        return Pair((c * u + s * v).toFloat(), (-s * u + c * v).toFloat())
    }

    /**
     * Document px → clip space for a w×h viewport, as the COLUMN-MAJOR 3×3 the GL engine wants
     * (`glUniformMatrix3fv`, transpose = false). A viewport that has no size yet (0, or a surface
     * that has not been laid out) is read as 1 px rather than allowed to fill the matrix with
     * infinities — nothing sensible can be drawn then anyway, and a finite matrix cannot poison
     * the GL state.
     *
     * Clip space is y UP (-1..1) and screen is y down, so the y row flips: that flip is the only
     * difference from the plain view matrix, and getting it wrong draws the page upside down.
     */
    fun docToClip(w: Int, h: Int): FloatArray {
        val vw = if (w > 0) w else 1
        val vh = if (h > 0) h else 1
        val c = cos(rotation.toDouble()).toFloat()
        val s = sin(rotation.toDouble()).toFloat()
        val m00 = 2f * zoom * c / vw
        val m10 = -2f * zoom * s / vh
        val m01 = -2f * zoom * s / vw
        val m11 = -2f * zoom * c / vh
        val m02 = 2f * panX / vw - 1f
        val m12 = 1f - 2f * panY / vh
        return floatArrayOf(m00, m10, 0f, m01, m11, 0f, m02, m12, 1f)
    }

    /**
     * One step of a two-finger gesture. The document point that was under the old centroid is
     * scaled by [scaleFactor] and turned by [dRotation] ABOUT ITSELF and lands under the new
     * centroid — so whatever was between the fingers stays between the fingers, which is the whole
     * point of a pinch. Both centroids are SCREEN px.
     *
     * A step carrying a non-finite or non-positive [scaleFactor] is ignored rather than allowed to
     * scale the canvas to nothing.
     */
    fun applyPinch(oldCx: Float, oldCy: Float, newCx: Float, newCy: Float, scaleFactor: Float, dRotation: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        val held = screenToDoc(oldCx, oldCy)
        if (dRotation.isFinite() && dRotation != 0f) rotation += dRotation
        zoom *= scaleFactor
        val now = docToScreen(held.first, held.second)
        panX += newCx - now.first
        panY += newCy - now.second
    }

    /**
     * The pinch is over. If the page is within [SNAP_DEGREES] of a right angle it squares up, with
     * the document point that was under (cx, cy) — the last centroid — left exactly where it is,
     * so the page does not jump out from under the fingers as it turns. Anything further out is
     * left alone: a page deliberately turned to 20° stays at 20°.
     */
    fun snapRotation(cx: Float, cy: Float) {
        val step = PI / 2.0
        val target = Angles.wrap(round(rotation / step) * step)
        val delta = target - rotation
        if (abs(delta) > SNAP_TOLERANCE) return
        if (delta == 0.0) return
        val held = screenToDoc(cx, cy)
        rotation = target.toFloat()
        val now = docToScreen(held.first, held.second)
        panX += cx - now.first
        panY += cy - now.second
    }

    /**
     * Puts a document rectangle in the middle of a w×h view, upright ([rotation] = 0) and with
     * [FIT_MARGIN] of the view left empty on every side — "quick pinch fits the board" (blueprint
     * §3.5) lands here. A rectangle with no width or no height only constrains the other axis, and
     * the axis that has room to spare is split evenly above and below.
     */
    fun fit(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int) {
        rotation = 0f
        val usableW = w * (1f - 2f * FIT_MARGIN)
        val usableH = h * (1f - 2f * FIT_MARGIN)
        val dw = right - left
        val dh = bottom - top
        val byWidth = if (dw > 0f && dw.isFinite()) usableW / dw else Float.MAX_VALUE
        val byHeight = if (dh > 0f && dh.isFinite()) usableH / dh else Float.MAX_VALUE
        zoom = minOf(byWidth, byHeight)
        panX = w * 0.5f - zoom * (left + right) * 0.5f
        panY = h * 0.5f - zoom * (top + bottom) * 0.5f
    }

    companion object {
        /** Furthest out: one document px is a twentieth of a screen px. */
        const val MIN_ZOOM = 0.05f

        /** Furthest in: 64 screen px per document px, so a single 256² tile is comfortable to work on. */
        const val MAX_ZOOM = 64f

        /** How close to a right angle the page must be before it squares up, in degrees. */
        const val SNAP_DEGREES = 7f

        /** Empty space left around a fitted rectangle, as a fraction of the view, on EACH side. */
        const val FIT_MARGIN = 0.05f

        /** [SNAP_DEGREES] in radians — the window [snapRotation] uses. */
        val SNAP_TOLERANCE: Float = (SNAP_DEGREES.toDouble() * PI / 180.0).toFloat()
    }
}
