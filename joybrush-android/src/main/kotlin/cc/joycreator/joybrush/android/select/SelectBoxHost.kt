package cc.joycreator.joybrush.android.select

import android.graphics.RectF
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.core.view.ViewTransform
import com.fadcam.ui.faditor.transform.TransformOverlayView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * JB-5.20e D10 — the Studio's box (`TransformOverlayView`, R16: one box, improved once) driving the Select tool. The box
 * speaks SCREEN quads; the tool keeps the selection's box in DOCUMENT px. This host converts between them through the
 * canvas's [view] and hands every gesture to the tool: [beginGesture] snapshots, [writeQuad] previews (the lifted
 * texture follows, nothing is written), [commitGesture] writes ONE undo step.
 */
class SelectBoxHost(
    private val tool: SelectTool,
    /** The canvas's live view transform (pan, zoom, rotation). */
    private val view: () -> ViewTransform,
    /** The canvas's bounds in the box's coordinates, for the box's snapping. */
    private val bounds: () -> RectF,
) : TransformOverlayView.Host {

    private val scratch = FloatArray(8)
    private var gestureQuad: FloatArray? = null

    /**
     * Told when the box starts MOVING (true) and when the move is written (false): the row fades out and back (K1). Not
     * on touch-down: the box begins a gesture on every down, and a tap on a corner (a pin) never moves or commits.
     */
    var onGesture: ((Boolean) -> Unit)? = null
    private var moving = false

    override fun readQuad(outQuad8: FloatArray): Boolean {
        val q = tool.boxDoc ?: return false
        val v = view()
        for (i in 0 until 4) {
            val (x, y) = v.docToScreen(q[i * 2], q[i * 2 + 1])
            outQuad8[i * 2] = x; outQuad8[i * 2 + 1] = y
        }
        return true
    }

    override fun readPivot(outXY: FloatArray) {
        if (!readQuad(scratch)) return
        outXY[0] = (scratch[0] + scratch[2] + scratch[4] + scratch[6]) / 4f
        outXY[1] = (scratch[1] + scratch[3] + scratch[5] + scratch[7]) / 4f
    }

    override fun readFoldPivot(outXY: FloatArray) = readPivot(outXY)

    override fun beginGesture() {
        gestureQuad = FloatArray(8).takeIf { readQuad(it) }
        tool.beginBox()
    }

    /** A pinch can absorb a moved drag without taking a second undo snapshot. */
    override fun rebaseGesture() {
        gestureQuad = FloatArray(8).takeIf { readQuad(it) }
    }

    override fun videoRect(): RectF = bounds()

    override fun writeQuad(quad8: FloatArray): Boolean {
        tapAt = null
        val v = view()
        val doc = FloatArray(8)
        for (i in 0 until 4) {
            val (x, y) = v.screenToDoc(quad8[i * 2], quad8[i * 2 + 1])
            doc[i * 2] = x; doc[i * 2 + 1] = y
        }
        val ok = tool.boxTo(doc)
        if (ok && !moving) { moving = true; onGesture?.invoke(true) }
        return ok
    }

    override fun writeTranslate(dxPx: Float, dyPx: Float) {
        val q = gestureQuad ?: return
        val next = q.copyOf()
        for (i in 0 until 4) { next[i * 2] += dxPx; next[i * 2 + 1] += dyPx }
        writeQuad(next)
    }

    override fun writeSimilarity(factor: Float, deltaDeg: Float, cxPx: Float, cyPx: Float) {
        val q = gestureQuad ?: return
        val oldCx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val oldCy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val next = q.copyOf()
        val a = Math.toRadians(deltaDeg.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        for (i in 0 until 4) {
            val dx = q[i * 2] - oldCx; val dy = q[i * 2 + 1] - oldCy
            next[i * 2] = cxPx + (dx * c - dy * s) * factor
            next[i * 2 + 1] = cyPx + (dx * s + dy * c) * factor
        }
        writeQuad(next)
    }

    override fun writeRotation(deg: Float) {
        val q = gestureQuad ?: return
        val now = Math.toDegrees(atan2((q[3] - q[1]).toDouble(), (q[2] - q[0]).toDouble())).toFloat()
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        writeSimilarity(1f, deg - now, cx, cy)
    }

    override fun commitGesture(what: String) {
        tool.commitBox()
        gestureQuad = null
        moving = false
        onGesture?.invoke(false)
    }

    override fun cancelGesture() {
        tool.cancelBox()
        gestureQuad = null
        settle()
        val t = tapAt; tapAt = null
        if (t != null && android.os.SystemClock.uptimeMillis() - tapTime < TAP_MS) tool.tapFromBox(t[0], t[1])
    }

    /** Canvas px of a single contact the box took on its body, or null: if it lifts without moving, it was a tap. */
    private var tapAt: FloatArray? = null
    private var tapTime = 0L

    /** The controller's touch gate saw the box take a body contact ([single] = the only finger or pen on the screen). */
    fun noteBodyContact(x: Float, y: Float, single: Boolean) {
        tapAt = if (single) floatArrayOf(x, y) else null
        tapTime = android.os.SystemClock.uptimeMillis()
    }

    private companion object { const val TAP_MS = 300L }

    /** The controller calls this when a selection changes or ends, so a move that was taken away never leaves the row hidden. */
    fun settle() { gestureQuad = null; if (moving) { moving = false; onGesture?.invoke(false) } }

    override fun currentRotationDeg(): Float {
        if (!readQuad(scratch)) return 0f
        return Math.toDegrees(atan2((scratch[3] - scratch[1]).toDouble(), (scratch[2] - scratch[0]).toDouble())).toFloat()
    }

    /** The ring's Reset: the selection back to how it was when it was made, ONE step. */
    override fun resetObjectGeometry() { tool.resetBox() }

    override fun flip(horizontal: Boolean) { tool.flip(horizontal) }
}
