package cc.joycreator.joybrush.androidkit.select

import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.tools.CanvasTool
import cc.joycreator.joybrush.core.select.StrokeEraseHit
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.view.ViewTransform

/**
 * The whole-line eraser (Concepts' stroke eraser, owner 11-27): with the "Whole lines" eraser in the hand, every editable
 * line the stroke touches on the active layer goes, the whole line, and the stroke is ONE undo step (owner: one press, one
 * step). Touched lines vanish as the eraser crosses them (lifted and shown empty, the same live preview Select's box
 * uses) and are deleted on lift through the same one-step delete Select's Delete uses. Paint (pixels) is not touched:
 * erasing part of a paint stroke is the ordinary eraser. Splitting a line part-way is later (engine work).
 *
 * It sits on the canvas in front of Select's tool ([next]), which stays on the canvas for good: anything this eraser does
 * not take (the pen's side button lasso, hover, any contact while another tool is in hand) goes straight on to [next].
 * Main thread only.
 */
class StrokeEraserTool(
    private val host: () -> SelectionHost?,
    /** True while the whole-line eraser is the tool in the hand. */
    private val inHand: () -> Boolean,
    /** The eraser's radius in document px (half its size). */
    private val radiusDoc: () -> Float,
    /** The tool behind this one (Select's), offered everything this one does not take. */
    private val next: () -> CanvasTool?,
    /** A finger erases only while fingers still draw (no pen seen yet); after that one finger pans, as with a brush. */
    private val fingersErase: () -> Boolean = { false },
    /** Said once when a stroke finds no lines to erase on this layer. */
    private val say: (String) -> Unit = {},
) : CanvasTool {

    private var owning = false
    private var delegated: CanvasTool? = null
    private var pointerId = -1
    private var lastX = 0.0
    private var lastY = 0.0
    private var lines: List<SelectionHost.Line> = emptyList()
    private val hit = LinkedHashSet<LineAddress>()
    private var lift: LiftedLines? = null
    private var toldNoLines = false

    override fun down(ev: MotionEvent, pointerIndex: Int, view: ViewTransform): Boolean {
        val pen = ev.getToolType(pointerIndex).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }
        val button = pen && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val h = host()
        if (!inHand() || button || h == null || (!pen && !fingersErase())) {
            val n = next()
            if (n != null && n.down(ev, pointerIndex, view)) { delegated = n; return true }
            return false
        }
        lines = h.activeLines().filter { !it.locked && it.record.capturedPhysicalSource == null }
        if (lines.isEmpty() && !toldNoLines) {
            toldNoLines = true
            say("This eraser takes away whole lines, and this layer has none. The ordinary eraser rubs out paint.")
        }
        owning = true
        hit.clear()
        pointerId = ev.getPointerId(pointerIndex)
        val (x, y) = view.screenToDoc(ev.getX(pointerIndex), ev.getY(pointerIndex))
        lastX = x.toDouble(); lastY = y.toDouble()
        sweep(lastX, lastY, view)
        return true
    }

    override fun event(ev: MotionEvent, view: ViewTransform): Boolean {
        delegated?.let { d ->
            val keep = d.event(ev, view)
            if (!keep || ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) delegated = null
            return keep
        }
        if (!owning) return false
        val i = ev.findPointerIndex(pointerId)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (i >= 0) {
                for (k in 0 until ev.historySize) {
                    val (x, y) = view.screenToDoc(ev.getHistoricalX(i, k), ev.getHistoricalY(i, k))
                    sweep(x.toDouble(), y.toDouble(), view)
                }
                val (x, y) = view.screenToDoc(ev.getX(i), ev.getY(i))
                sweep(x.toDouble(), y.toDouble(), view)
            }
            MotionEvent.ACTION_UP -> finish()
            MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == pointerId) finish()
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    /** The eraser moved to ([x], [y]): every line its disc crossed on the way joins the stroke's set. */
    private fun sweep(x: Double, y: Double, view: ViewTransform) {
        val zoom = if (view.zoom.isFinite() && view.zoom > 0f) view.zoom.toDouble() else 1.0
        // Never thinner than a few screen px, so a tiny eraser zoomed out still catches what it visibly crosses.
        val r = maxOf(radiusDoc().toDouble().takeIf { it.isFinite() } ?: 0.0, MIN_REACH_SCREEN_PX / zoom)
        val candidates = lines.filter { it.address !in hit }
        val touched = StrokeEraseHit.touched(candidates.map { it.geometry }, lastX, lastY, x, y, r)
        lastX = x; lastY = y
        if (touched.isEmpty()) return
        touched.forEach { hit.add(candidates[it].address) }
        showGone()
    }

    /** The touched lines leave the picture now: lifted, and their lifted look drawn empty. Nothing is written yet. */
    private fun showGone() {
        val h = host() ?: return
        lift?.release()
        lift = h.liftLines(hit.toSet())
        lift?.preview(emptyList())
    }

    private fun finish() {
        owning = false
        val gone = hit.toSet()
        hit.clear()
        // Delete first, then let go of the lift, in the order Select's Delete uses (the lines never flash back).
        if (gone.isNotEmpty()) host()?.deleteLines(gone)
        lift?.release(); lift = null
        lines = emptyList()
    }

    override fun cancel() {
        delegated?.cancel(); delegated = null
        if (!owning) return
        owning = false
        hit.clear()
        lift?.release(); lift = null   // a cancelled stroke erases nothing: the lines come back
        lines = emptyList()
    }

    override fun hover(ev: MotionEvent, view: ViewTransform) {
        if (!inHand()) next()?.hover(ev, view)
    }

    /** What the current stroke has touched so far (tests). */
    internal fun touchedSoFar(): Set<LineAddress> = hit.toSet()

    private companion object {
        const val MIN_REACH_SCREEN_PX = 4.0
    }
}
