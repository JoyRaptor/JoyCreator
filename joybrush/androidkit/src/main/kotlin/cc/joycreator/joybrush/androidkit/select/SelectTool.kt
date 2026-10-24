package cc.joycreator.joybrush.androidkit.select

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.tools.CanvasTool
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineDensify
import cc.joycreator.joybrush.core.select.LineLasso
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.select.SelectRouter
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.select.SelectionLine
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.StrokePicker
import kotlin.math.hypot

/**
 * JB-5.20e — the Select tool on the canvas ([CanvasTool]). It turns MotionEvents into [SelectRouter] calls, the
 * router's intents into [SelectionState] changes and [SelectionHost] edits, and keeps the model the overlays draw:
 * the loop being drawn, the glow, the crosshair, the nudge ring, the tip dots. The Studio box (`TransformOverlayView`)
 * owns the handles and the body; it reaches this tool through [beginBox] / [boxTo] / [commitBox] (the box host in
 * joybrush-android). Main thread.
 *
 * Every edit is ONE undo step through the host; picking, cycling, previews and chips are never steps (spec D20).
 */
class SelectTool(
    /** Read by the debug Select bench (bench/SelectBench) to check the drawing; everything else goes through the tool. */
    val host: SelectionHost,
    /** Screen px per dp. */
    private val density: Float,
    /** JbCanvasView.penHasBeenSeen. */
    private val penSeen: () -> Boolean,
) : CanvasTool {

    /** What the Select UI listens to (the controller in joybrush-android). */
    interface Listener {
        /** The selection changed, or ended ([selection] is null). */
        fun selectionChanged()
        /** Something only the overlay draws changed (loop, glow, crosshair, ring, tips). */
        fun overlayChanged()
    }

    var listener: Listener? = null

    // ── settings, from the owner's §K picks and Settings (the controller sets them) ──

    /** The Select tool is in the hand. False while a brush is chosen and a selection lives (the pen-button lasso). */
    var selectToolInHand = true
    /** K18: one finger after the pen selects (default: it pans). */
    var fingerSelects = false
    /** P4 B: a still finger lifts the crosshair. */
    var crosshair = true
    /** P6 A: the ＋ chip. */
    var addArmed = false
    /** D13: nudging instead of moving the box. */
    var nudging = false
    /** D13 / P7: the nudge ring's radius on screen, in dp (the same on screen at any zoom). */
    var nudgeRadiusDp = 48f
    /** D10.6: keep line weights when the box scales (Concepts' Stretch). */
    var keepWeights = false

    // ── the model the overlays draw (screen px) ──

    /** The loop being drawn, x, y pairs; null when none. */
    var lassoScreen: FloatArray? = null
        private set
    /** Lines that glow: the pen's hover target, the loop's preview, the crosshair's target. */
    var glowIds: List<LineAddress> = emptyList()
        private set
    /** Where the crosshair is; null when it is not up. */
    var crosshairAt: FloatArray? = null
        private set
    /** Where the pen hovers (for the nudge ring and the tip dots, K16); null when it is not hovering. */
    var hoverAt: FloatArray? = null
        private set
    /** Where the nudge ring is while a nudge drags (screen px); null otherwise (then it follows [hoverAt]). */
    var ringAt: FloatArray? = null
        private set

    /** The canvas's view, as of the last event (the overlays draw through it). */
    val viewTransform: cc.joycreator.joybrush.core.view.ViewTransform get() = view

    /** The glowing lines in screen px: each a polyline (x, y pairs) and its half-width in px. */
    fun glowScreen(): List<Pair<FloatArray, Float>> {
        if (glowIds.isEmpty()) return emptyList()
        val out = ArrayList<Pair<FloatArray, Float>>(glowIds.size)
        for (l in lines) if (l.address in glowIds) {
            val g = l.geometry
            val pts = FloatArray(g.xs.size * 2)
            var half = 0.0
            for (i in g.xs.indices) {
                val (x, y) = view.docToScreen(g.xs[i].toFloat(), g.ys[i].toFloat())
                pts[i * 2] = x; pts[i * 2 + 1] = y
                half = maxOf(half, g.halfWidths[i])
            }
            out.add(pts to (half * view.zoom).toFloat())
        }
        return out
    }

    /** A paint selection's loop in screen px, following the box while it moves (the marching ants); null otherwise. */
    fun loopScreen(): FloatArray? {
        val s = state.current as? SelectionState.Pixels ?: return null
        val h = quad0?.let { q -> boxDoc?.let { homography(q, it) } }
        val out = FloatArray(s.loop.size * 2)
        for ((i, p) in s.loop.withIndex()) {
            val m = h?.apply(p) ?: p
            val (x, y) = view.docToScreen(m.x.toFloat(), m.y.toFloat())
            out[i * 2] = x; out[i * 2 + 1] = y
        }
        return out
    }

    val state = SelectionState(items = object : SelectionState.Items {
        override fun locked(id: LineAddress) = lineById(id)?.locked ?: false
        override fun group(id: LineAddress) = lineById(id)?.groupId
    })

    /** The selection the box works on, or null. */
    val selection: SelectionState.Selection? get() = state.current

    /** The ordinal beside the last tap/crosshair pick; ids remain an internal, current-contract snapshot. */
    data class CountPick(val at: SelectionState.Pick, val index: Int, val total: Int)
    internal data class CountStamp(val address: LineAddress, val locked: Boolean,
                                   val groupId: String?, val record: StrokeRecord)
    class CountSession internal constructor(
        val pick: CountPick,
        internal val order: List<LineAddress>,
        internal val base: Set<LineAddress>,
        internal val stamp: List<CountStamp>,
    )
    var countPick: CountPick? = null
        private set
    private var countOrder: List<LineAddress> = emptyList()
    private var countSession: CountSession? = null

    /** The box's four corners in DOCUMENT px (top-left, top-right, bottom-right, bottom-left), or null. */
    var boxDoc: FloatArray? = null
        private set

    private var view = cc.joycreator.joybrush.core.view.ViewTransform()
    private val picker = StrokePicker()
    private var lines: List<SelectionHost.Line> = emptyList()
    private var liftedLines: LiftedLines? = null
    private var liftedPixels: LiftedPixels? = null
    private var mask: SelectionMask? = null
    private var startRecords: List<AddressedStroke> = emptyList()
    /** A weight drag starts from current records, never a preceding box/bend gesture. */
    private var weightRecords: List<AddressedStroke>? = null
    /** The selected lines as they were when the selection was made (the ring's Reset). */
    private var selectionStart: List<AddressedStroke> = emptyList()
    private var lastPreviewAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { router.tick() }

    private val routerEnv = object : SelectRouter.Env {
        override val density get() = this@SelectTool.density
        override val selectToolInHand get() = this@SelectTool.selectToolInHand
        override val selection get() = when (state.current) {
            is SelectionState.Lines -> SelectRouter.Selection.LINES
            is SelectionState.Pixels -> SelectRouter.Selection.PIXELS
            null -> SelectRouter.Selection.NONE
        }
        override val nudging get() = this@SelectTool.nudging
        override val penSeen get() = this@SelectTool.penSeen()
        override val fingerSelects get() = this@SelectTool.fingerSelects
        override val crosshair get() = this@SelectTool.crosshair
        override val addArmed get() = this@SelectTool.addArmed
        override fun onTip(x: Float, y: Float) = tipAt(x, y) != null
    }

    private val router = SelectRouter(routerEnv, Intents(), SystemClock::uptimeMillis)

    // ── CanvasTool ──

    override fun down(ev: MotionEvent, pointerIndex: Int, view: cc.joycreator.joybrush.core.view.ViewTransform): Boolean {
        this.view = view
        val id = ev.getPointerId(pointerIndex)
        val kind = kindOf(ev, pointerIndex)
        if (router.busy) return router.otherDown(id, kind)
        lines = visibleLines()
        val button = kind == SelectRouter.Kind.PEN && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val owned = router.down(id, ev.getX(pointerIndex), ev.getY(pointerIndex), kind, button)
        if (owned && kind == SelectRouter.Kind.FINGER && crosshair) handler.postDelayed(tick, SelectRouter.CROSSHAIR_HOLD_MS)
        return owned
    }

    override fun event(ev: MotionEvent, view: cc.joycreator.joybrush.core.view.ViewTransform): Boolean {
        this.view = view
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) {
                if (!router.move(ev.getPointerId(i), ev.getX(i), ev.getY(i))) { handler.removeCallbacks(tick); return false }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                if (!router.otherDown(ev.getPointerId(i), kindOf(ev, i)) && !router.busy) { handler.removeCallbacks(tick); return false }
            }
            MotionEvent.ACTION_POINTER_UP -> { val i = ev.actionIndex; router.up(ev.getPointerId(i), ev.getX(i), ev.getY(i)) }
            MotionEvent.ACTION_UP -> { handler.removeCallbacks(tick); router.up(ev.getPointerId(0), ev.x, ev.y) }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(tick); router.cancel() }
        }
        return true
    }

    override fun cancel() { handler.removeCallbacks(tick); router.cancel() }

    override fun hover(ev: MotionEvent, view: cc.joycreator.joybrush.core.view.ViewTransform) {
        this.view = view
        if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT) { hoverAt = null; glowIds = emptyList(); listener?.overlayChanged(); return }
        hoverAt = floatArrayOf(ev.x, ev.y)
        glowIds = if (selectToolInHand && !nudging) hoverTarget(ev.x, ev.y) else emptyList()
        listener?.overlayChanged()
    }

    // ── the box (driven by the box host) ──

    private var quad0: FloatArray? = null

    /** A box gesture starts: one snapshot of what it moves. */
    fun beginBox() {
        countSession = null
        cancelWeight()
        quad0 = if (canTransform) boxDoc?.copyOf() else null
        startRecords = selectedRecords()
    }

    /** The box is now [quadDoc] (document px). Live: the lifted preview follows; nothing is written yet. */
    fun boxTo(quadDoc: FloatArray): Boolean {
        if (!canTransform || quad0 == null) return false
        val h = homographyTo(quadDoc) ?: return false
        boxDoc = quadDoc.copyOf()
        liftedLines?.setTransform(h)
        liftedPixels?.setTransform(h)
        host.invalidate()
        return true
    }

    /** The box gesture ended: ONE step. False (and the box goes back) when the move cannot be made. */
    fun commitBox(): Boolean {
        val q0 = quad0 ?: return false
        val q = boxDoc
        val h = q?.let { homography(q0, it) }
        if (h == null) { cancelBox(); return false }
        quad0 = null
        val ok = when (state.current) {
            is SelectionState.Lines -> {
                val weight = if (keepWeights) LineTransform.Weight.KEEP else LineTransform.Weight.SCALE
                val after = startRecords.map { entry ->
                    LineTransform.apply(entry.record, h, weight)?.let { AddressedStroke(entry.address, it) }
                }
                if (after.any { it == null }) {
                    host.toast("This move is too large to make.")
                    false
                } else commitLines(after.filterNotNull())
            }
            is SelectionState.Pixels -> liftedPixels?.commit() ?: false
            null -> false
        }
        startRecords = emptyList()
        if (ok) {
            clearCount()
            state.moved(h)
            val pixels = state.current as? SelectionState.Pixels
            if (pixels != null) {
                mask = SelectionMask.polygon(pixels.loop)
                host.paintClip = mask
            }
        } else revertBox(q0)
        listener?.selectionChanged()
        return ok
    }

    fun cancelBox() {
        quad0?.let { revertBox(it) }
        quad0 = null
        startRecords = emptyList()
        cancelWeight()
        listener?.overlayChanged()
    }

    /** The ring's Reset: the selected lines back to how they were when the selection was made, ONE step. */
    fun resetBox(): Boolean {
        if (state.current !is SelectionState.Lines || selectionStart.isEmpty()) return false
        cancelWeight()
        val ok = commitLines(selectionStart)
        refitBox()
        listener?.selectionChanged()
        return ok
    }

    private fun revertBox(q0: FloatArray) {
        boxDoc = q0.copyOf()
        liftedLines?.setTransform(Homography.IDENTITY)
        liftedPixels?.setTransform(Homography.IDENTITY)
        host.invalidate()
    }

    // ── the row's actions (each ONE step) ──

    fun recolour(argb: Int): Boolean = editRecords { StrokeEdit.recolor(it, argb) }

    fun rebrush(brushId: String): Boolean = editRecords { StrokeEdit.rebrush(it, brushId) }

    /** No Fill control is offered when the engine refused this lift. */
    val canFill: Boolean get() = state.current is SelectionState.Pixels && liftedPixels != null

    /** Lines can commit without a preview; paint must have an actual lift before it can move. */
    val canTransform: Boolean get() = when (state.current) {
        is SelectionState.Lines -> selectedRecords().isNotEmpty()
        is SelectionState.Pixels -> liftedPixels != null
        null -> false
    }

    /** Fill the lifted paint through its coverage mask, ONE step, keeping the selection and paint clip. */
    fun fill(argb: Int): Boolean {
        if (state.current !is SelectionState.Pixels) return false
        val pixels = liftedPixels ?: return false
        val changed = pixels.fill(argb)
        if (changed) {
            host.invalidate()
            listener?.overlayChanged()
        }
        return changed
    }

    /** Live while the weight chip is dragged; [done] writes the one step. */
    fun reweight(factor: Float, done: Boolean): Boolean {
        if (state.current !is SelectionState.Lines) return false
        val before = weightRecords ?: selectedRecords().also { weightRecords = it }
        if (before.isEmpty()) { weightRecords = null; return false }
        val after = before.map { it.copy(record = StrokeEdit.reweight(it.record, factor)) }
        if (!done) { liftedLines?.preview(after); host.invalidate(); return true }
        weightRecords = null
        val ok = commitLines(after)
        if (!ok) { liftedLines?.preview(before); host.invalidate() }
        return ok
    }

    private fun cancelWeight() {
        val before = weightRecords ?: return
        weightRecords = null
        liftedLines?.preview(before)
        host.invalidate()
    }

    fun deleteSelection(): Boolean {
        cancelWeight()
        val ok = when (val s = state.current) {
            is SelectionState.Lines -> if (selectionSourcesAvailable(s.ids)) host.deleteLines(s.ids) else false
            is SelectionState.Pixels -> liftedPixels?.clear() ?: false
            null -> false
        }
        if (ok) end()
        return ok
    }

    /** Duplicate: copies 16 dp right and down, on top, and the copy becomes the selection. */
    fun duplicate(newId: () -> String): Boolean {
        val s = state.current as? SelectionState.Lines ?: return false
        val o = 16f * density / view.zoom
        val copies = selectedRecords().map { entry ->
            val r = entry.record
            val record = r.copy(id = newId(), seq = 0L, samples = r.samples.map { it.copy(x = it.x + o, y = it.y + o) })
            AddressedStroke(entry.address.copy(recordId = record.id), record)
        }
        if (!host.addLines(copies)) return false
        lines = visibleLines()
        releaseLift()
        state.clear()
        copies.forEach { state.choose(it.address, adding = true, at = null, lines = geometries(lines)) }
        if (state.current == null && copies.isNotEmpty()) state.choose(copies[0].address, adding = false, at = null, lines = geometries(lines))
        onSelectionChanged()
        return s.ids.isNotEmpty()
    }

    fun flip(horizontal: Boolean): Boolean {
        if (!canTransform) return false
        val q = boxDoc ?: return false
        beginBox()
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val m = q.copyOf()
        for (i in 0 until 4) if (horizontal) m[i * 2] = 2 * cx - q[i * 2] else m[i * 2 + 1] = 2 * cy - q[i * 2 + 1]
        if (!boxTo(m)) { cancelBox(); return false }
        val ok = commitBox()
        // The box keeps its corner order on screen (top-left stays top-left).
        if (ok) boxDoc?.let { b -> boxDoc = if (horizontal) floatArrayOf(b[2], b[3], b[0], b[1], b[6], b[7], b[4], b[5]) else floatArrayOf(b[6], b[7], b[4], b[5], b[2], b[3], b[0], b[1]) }
        return ok
    }

    fun rasterizeDown(): Boolean {
        val s = state.current as? SelectionState.Lines ?: return false
        if (!selectionSourcesAvailable(s.ids)) return false
        val ok = host.bakeLines(s.ids)
        if (ok) end()
        return ok
    }

    /** Re-read the kept loop with new chips (D9, K13). A sentence when it cannot apply. */
    fun reread(reading: SelectionState.Reading): String? {
        lines = visibleLines()
        val said = state.reread(reading, geometries(activeLines()), geometries(lines))
        if (said == null) onSelectionChanged() else host.toast(said)
        return said
    }

    /** Step the selection to candidate [i] of the tap's order (the count scrubber, K11), adding when ＋ is on (K12). */
    fun chooseFromOrder(order: List<LineAddress>, i: Int, adding: Boolean, at: SelectionState.Pick) {
        if (i !in order.indices) return
        lines = visibleLines()
        val active = geometries(activeLines())
        if (active.none { it.address == order[i] }) return
        state.choose(order[i], adding, at, active)
        onSelectionChanged(countContext(order, i, at), order)
    }

    /** Capture once per count gesture. A changed drawing/rank must be picked again, never silently reinterpreted. */
    fun beginCountScrub(adding: Boolean, view: cc.joycreator.joybrush.core.view.ViewTransform = this.view): CountSession? {
        val pick = countPick ?: return null
        if (state.current !is SelectionState.Lines || nudging || quad0 != null) return null
        this.view = view
        lines = visibleLines()
        if (rankFor(pick.at) != countOrder) { staleCount(); return null }
        cancelWeight()
        val base = if (adding) state.countBaseWithout(countOrder[pick.index], geometries(activeLines())) else emptySet()
        return CountSession(pick, countOrder.toList(), base.toSet(), countStamp()).also { countSession = it }
    }

    /** One live ordinal choice from a fixed base: passing another candidate does not accumulate selections. */
    fun scrubCount(session: CountSession, index: Int): Boolean {
        if (countSession !== session || index !in session.order.indices || state.current !is SelectionState.Lines ||
            nudging || quad0 != null) return false
        lines = visibleLines()
        if (rankFor(session.pick.at) != session.order || countStamp() != session.stamp) { staleCount(); return false }
        if (countPick?.index == index) return true
        val before = state.current
        if (!state.chooseFromBase(session.order[index], session.base, session.pick.at, geometries(activeLines()))) {
            staleCount(); return false
        }
        val pick = session.pick.copy(index = index)
        if (before != state.current) onSelectionChanged(pick, session.order, session)
        else { countPick = pick; listener?.selectionChanged() }
        return true
    }

    /** Cancellation keeps the last chosen line, as in the approved count mock; it never cycles once more. */
    fun endCountScrub(session: CountSession) { if (countSession === session) countSession = null }

    private fun countContext(order: List<LineAddress>, index: Int, at: SelectionState.Pick): CountPick? =
        if (order.size > 1 && index in order.indices && state.current is SelectionState.Lines)
            CountPick(at, index, order.size) else null

    private fun clearCount() { countPick = null; countOrder = emptyList(); countSession = null }

    private fun staleCount() {
        clearCount()
        host.toast("Those lines changed. Tap again to choose.")
        listener?.selectionChanged()
    }

    private fun rankFor(at: SelectionState.Pick): List<LineAddress> {
        val pickable = activeLines().filter { !it.locked || state.pickLocked }
        return picker.rankIndices(pickable.map { it.geometry }, at.x, at.y, view.zoom.toDouble()).map { pickable[it].address }
    }

    private fun countStamp(): List<CountStamp> = activeLines().map {
        CountStamp(it.address, it.locked, it.groupId, it.record)
    }

    /** The selection ends: lifts go back, the clip and the fade are cleared. */
    fun end() {
        state.clear()
        onSelectionChanged()
    }

    // ── the router's intents ──

    private inner class Intents : SelectRouter.Sink {
        override fun tap(x: Float, y: Float, adding: Boolean) {
            lines = visibleLines()
            val (dx, dy) = view.screenToDoc(x, y)
            val order = rankAt(x, y)
            val changed = state.tap(dx.toDouble(), dy.toDouble(), view.zoom.toDouble(), geometries(activeLines()), adding) { px, py -> insideBox(px, py) }
            if (changed) {
                val s = state.current as? SelectionState.Lines
                val index = if (!adding && s?.ids?.size == 1) order.indexOf(s.ids.first()) else 0
                val at = s?.pick ?: SelectionState.Pick(dx.toDouble(), dy.toDouble())
                onSelectionChanged(countContext(order, index, at), order)
            }
        }

        override fun lassoMoved(points: FloatArray) {
            lassoScreen = points
            val now = SystemClock.uptimeMillis()
            if (now - lastPreviewAt >= PREVIEW_MS && points.size >= 6) {
                lastPreviewAt = now
                val candidates = activeLines()
                glowIds = LineLasso.takeIndices(candidates.map { it.geometry }, toDoc(points), LineLasso.Reach.WHOLE)
                    .map { candidates[it].address }
            }
            listener?.overlayChanged()
        }

        override fun lassoEnded(points: FloatArray, adding: Boolean) {
            lassoScreen = null
            glowIds = emptyList()
            if (state.loop(toDoc(points), geometries(activeLines()), geometries(lines), SelectionState.Reading(), adding)) onSelectionChanged()
            else listener?.overlayChanged()
        }

        override fun crosshairMoved(x: Float, y: Float) {
            crosshairAt = floatArrayOf(x, y)
            glowIds = rankAt(x, y).take(1)
            listener?.overlayChanged()
        }

        override fun crosshairEnded(x: Float, y: Float, adding: Boolean) {
            crosshairAt = null
            glowIds = emptyList()
            val order = rankAt(x, y)
            if (order.isNotEmpty()) {
                val (dx, dy) = view.screenToDoc(x, y)
                state.choose(order[0], adding, SelectionState.Pick(dx.toDouble(), dy.toDouble()), geometries(activeLines()))
                onSelectionChanged(countContext(order, 0, SelectionState.Pick(dx.toDouble(), dy.toDouble())), order)
            } else listener?.overlayChanged()
        }

        override fun penButtonTap(x: Float, y: Float) = host.penButtonTap(x, y)

        override fun endSelection() = end()

        override fun nudge(phase: SelectRouter.Phase, x: Float, y: Float) = bend(phase, x, y, tip = null)

        override fun tip(phase: SelectRouter.Phase, x: Float, y: Float) = bend(phase, x, y, tip = if (phase == SelectRouter.Phase.START) tipAt(x, y) else tipGrab)

        override fun cancelled() {
            lassoScreen = null; crosshairAt = null; glowIds = emptyList()
            if (bendGrab != null) { liftedLines?.preview(startRecords); bendGrab = null; host.invalidate() }
            startRecords = emptyList(); tipGrab = null; ringAt = null
            listener?.overlayChanged()
        }
    }

    // ── nudge and tips (D13, D14, K15, K16) ──

    private class Tip(val id: LineAddress, val end: Boolean)
    private var tipGrab: Tip? = null
    private var bendGrab: FloatArray? = null

    private fun bend(phase: SelectRouter.Phase, x: Float, y: Float, tip: Tip?) {
        val (dx, dy) = view.screenToDoc(x, y)
        when (phase) {
            SelectRouter.Phase.START -> {
                cancelWeight()
                startRecords = selectedRecords()
                tipGrab = tip
                bendGrab = if (tip != null) tipPoint(tip) else floatArrayOf(dx, dy)
                if (tip == null) ringAt = floatArrayOf(x, y)
            }
            SelectRouter.Phase.MOVE -> {
                if (tipGrab == null) ringAt = floatArrayOf(x, y)
                liftedLines?.preview(bent(dx, dy)); host.invalidate(); listener?.overlayChanged()
            }
            SelectRouter.Phase.END -> {
                ringAt = null
                val gap = 2f * density / view.zoom
                commitLines(bent(dx, dy).mapIndexed { i, entry ->
                    if (entry == startRecords[i]) entry
                    else entry.copy(record = LineDensify.densify(entry.record, gap))
                })
                bendGrab = null; tipGrab = null; startRecords = emptyList()
                refitBox()
            }
        }
    }

    private fun bent(dx: Float, dy: Float): List<AddressedStroke> {
        val g = bendGrab ?: return startRecords
        val t = tipGrab
        return startRecords.map { entry ->
            val r = entry.record
            if (t != null && entry.address != t.id) return@map entry
            val radius = if (t != null) maxOf(0.35f * arcLength(r), 24f * density / view.zoom) else nudgeRadiusDp * density / view.zoom
            // D13: only the selected lines the ring reaches bend; the rest stay exactly as they are.
            if (t == null && r.samples.none { hypot(it.x - g[0], it.y - g[1]) <= radius }) return@map entry
            entry.copy(record = StrokeEdit.reshape(r, g[0], g[1], dx - g[0], dy - g[1], radius))
        }
    }

    // ── what changes when the selection does ──

    private fun onSelectionChanged(pick: CountPick? = null, order: List<LineAddress> = emptyList(), session: CountSession? = null) {
        countPick = if (state.current is SelectionState.Lines) pick else null
        countOrder = if (countPick != null) order.toList() else emptyList()
        countSession = if (countPick != null) session else null
        weightRecords = null
        startRecords = emptyList()
        quad0 = null
        bendGrab = null; tipGrab = null; ringAt = null
        releaseLift()
        when (val s = state.current) {
            is SelectionState.Lines -> liftedLines = host.liftLines(s.ids)
            is SelectionState.Pixels -> {
                val m = SelectionMask.polygon(s.loop)
                mask = m
                liftedPixels = host.liftPixels(m, keepSource = false)
                host.paintClip = m
                if (liftedPixels == null) host.toast("Paint can’t be moved or edited yet. Only lines can.") // honest until paintClip/liftPixels land (G9, G10)
            }
            null -> {}
        }
        lines = visibleLines()
        selectionStart = selectedRecords()
        refitBox()
        listener?.selectionChanged()
    }

    private fun releaseLift() {
        liftedLines?.release(); liftedLines = null
        liftedPixels?.release(); liftedPixels = null
        host.paintClip = null
        mask = null
    }

    private fun refitBox() {
        boxDoc = when (val s = state.current) {
            is SelectionState.Lines -> {
                var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                for (l in lines) if (l.address in s.ids) for (i in l.geometry.xs.indices) {
                    val h = l.geometry.halfWidths[i]
                    x0 = minOf(x0, l.geometry.xs[i] - h); x1 = maxOf(x1, l.geometry.xs[i] + h)
                    y0 = minOf(y0, l.geometry.ys[i] - h); y1 = maxOf(y1, l.geometry.ys[i] + h)
                }
                if (x0 > x1) null else rect(x0, y0, x1, y1)
            }
            is SelectionState.Pixels -> {
                var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                for (p in s.loop) { x0 = minOf(x0, p.x); x1 = maxOf(x1, p.x); y0 = minOf(y0, p.y); y1 = maxOf(y1, p.y) }
                rect(x0, y0, x1, y1)
            }
            null -> null
        }
    }

    /** A box at least [MIN_BOX_DP] on screen in each direction, so its corners can be told apart. */
    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double): FloatArray {
        val min = MIN_BOX_DP * density / view.zoom
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val hw = maxOf((x1 - x0) / 2, min / 2.0); val hh = maxOf((y1 - y0) / 2, min / 2.0)
        return floatArrayOf((cx - hw).toFloat(), (cy - hh).toFloat(), (cx + hw).toFloat(), (cy - hh).toFloat(),
            (cx + hw).toFloat(), (cy + hh).toFloat(), (cx - hw).toFloat(), (cy + hh).toFloat())
    }

    // ── helpers ──

    private fun commitLines(after: List<AddressedStroke>): Boolean {
        val l = liftedLines
        val ok = if (l != null) l.commit(after) else host.replaceLines(after)
        lines = visibleLines()
        host.invalidate()
        return ok
    }

    private fun editRecords(edit: (StrokeRecord) -> StrokeRecord): Boolean {
        if (state.current !is SelectionState.Lines) return false
        cancelWeight()
        return commitLines(selectedRecords().map { it.copy(record = edit(it.record)) })
    }

    private fun selectedRecords(): List<AddressedStroke> {
        val s = state.current as? SelectionState.Lines ?: return emptyList()
        return lines.filter { it.address in s.ids }.map { AddressedStroke(it.address, it.record) }
    }

    // Raw centreline geometry cannot describe a clipped/placed source's visible pixels.
    private fun visibleLines(): List<SelectionHost.Line> = host.visibleLines().filter { it.record.capturedPhysicalSource == null }
    private fun activeLines(): List<SelectionHost.Line> = host.activeLines().filter { it.record.capturedPhysicalSource == null }

    private fun selectionSourcesAvailable(ids: Set<LineAddress>): Boolean {
        val current = host.visibleLines().filter { it.record.capturedPhysicalSource == null }.map { it.address }.toSet()
        return ids.all { it in current }
    }

    private fun geometries(ls: List<SelectionHost.Line>): List<SelectionLine> = ls.map { SelectionLine(it.address, it.geometry) }

    private fun lineById(id: LineAddress): SelectionHost.Line? = lines.firstOrNull { it.address == id }

    private fun toDoc(points: FloatArray): List<Pt> = (0 until points.size / 2).map {
        val (x, y) = view.screenToDoc(points[it * 2], points[it * 2 + 1]); Pt(x.toDouble(), y.toDouble())
    }

    private fun rankAt(x: Float, y: Float): List<LineAddress> {
        val (dx, dy) = view.screenToDoc(x, y)
        val pickable = activeLines().filter { !it.locked || state.pickLocked }
        return picker.rankIndices(pickable.map { it.geometry }, dx.toDouble(), dy.toDouble(), view.zoom.toDouble()).map { pickable[it].address }
    }

    /** D5: the line a tap would take, or the NEXT one when hovering the spot just picked. */
    private fun hoverTarget(x: Float, y: Float): List<LineAddress> {
        val order = rankAt(x, y)
        if (order.isEmpty()) return emptyList()
        val s = state.current as? SelectionState.Lines
        val pick = s?.pick
        if (s != null && pick != null && s.ids.size == 1 && order.size > 1) {
            val (px, py) = view.docToScreen(pick.x.toFloat(), pick.y.toFloat())
            if (hypot(px - x, py - y) <= SelectionState.CYCLE_REACH_SCREEN_PX * density) {
                val at = order.indexOf(s.ids.first())
                return listOf(order[(at + 1).mod(order.size)])
            }
        }
        return if (s != null && order[0] in s.ids && !addArmed) emptyList() else order.take(1)
    }

    private fun insideBox(dx: Double, dy: Double): Boolean {
        val q = boxDoc ?: return false
        val poly = (0 until 4).map { Pt(q[it * 2].toDouble(), q[it * 2 + 1].toDouble()) }
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            if ((poly[i].y > dy) != (poly[j].y > dy) && dx < (poly[j].x - poly[i].x) * (dy - poly[i].y) / (poly[j].y - poly[i].y) + poly[i].x) inside = !inside
            j = i
        }
        return inside
    }

    /** K16: with a pen seen, a tip's dot is there only while the pen hovers near it; with fingers only, always (1–6 lines). */
    private fun tipAt(x: Float, y: Float): Tip? {
        val s = state.current as? SelectionState.Lines ?: return null
        if (s.ids.size > 6 || nudging) return null
        val reach = TIP_REACH_DP * density
        for (l in lines) if (l.address in s.ids) for (end in listOf(false, true)) {
            val p = tipPoint(Tip(l.address, end)) ?: continue
            val (sx, sy) = view.docToScreen(p[0], p[1])
            if (hypot(sx - x, sy - y) <= reach) return Tip(l.address, end)
        }
        return null
    }

    /** The tip dots the overlay draws, in screen px (K16). */
    fun tipDots(): List<FloatArray> {
        val s = state.current as? SelectionState.Lines ?: return emptyList()
        if (s.ids.size > 6 || nudging) return emptyList()
        val out = ArrayList<FloatArray>()
        val hv = hoverAt
        for (l in lines) if (l.address in s.ids) for (end in listOf(false, true)) {
            val p = tipPoint(Tip(l.address, end)) ?: continue
            val (sx, sy) = view.docToScreen(p[0], p[1])
            if (penSeen() && (hv == null || hypot(hv[0] - sx, hv[1] - sy) > TIP_SHOW_DP * density)) continue
            if (nearCorner(sx, sy)) continue
            out.add(floatArrayOf(sx, sy))
        }
        return out
    }

    private fun nearCorner(sx: Float, sy: Float): Boolean {
        val q = boxDoc ?: return false
        for (i in 0 until 4) {
            val (cx, cy) = view.docToScreen(q[i * 2], q[i * 2 + 1])
            if (hypot(cx - sx, cy - sy) < TIP_CORNER_DP * density) return true
        }
        return false
    }

    private fun tipPoint(t: Tip): FloatArray? {
        val r = lineById(t.id)?.record ?: return null
        if (r.samples.isEmpty()) return null
        val p = if (t.end) r.samples.last() else r.samples.first()
        return floatArrayOf(p.x, p.y)
    }

    private fun arcLength(r: StrokeRecord): Float {
        var a = 0f
        for (i in 1 until r.samples.size) a += hypot(r.samples[i].x - r.samples[i - 1].x, r.samples[i].y - r.samples[i - 1].y)
        return a
    }

    private fun homographyTo(q: FloatArray): Homography? = quad0?.let { homography(it, q) } ?: boxDoc?.let { homography(it, q) }

    private fun homography(a: FloatArray, b: FloatArray): Homography? =
        Homography.fromQuads((0 until 4).map { Pt(a[it * 2].toDouble(), a[it * 2 + 1].toDouble()) }, (0 until 4).map { Pt(b[it * 2].toDouble(), b[it * 2 + 1].toDouble()) })

    private fun kindOf(ev: MotionEvent, i: Int): SelectRouter.Kind {
        val t = ev.getToolType(i)
        return if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) SelectRouter.Kind.PEN else SelectRouter.Kind.FINGER
    }

    companion object {
        /** How often the loop's preview of what it will take is recomputed (D8). */
        const val PREVIEW_MS = 50L
        /** The box is never smaller than this on screen, so its corners can be told apart. */
        const val MIN_BOX_DP = 28f
        /** A tip dot answers a touch this close (K16). */
        const val TIP_REACH_DP = 16f
        /** With a pen, a tip's dot shows while the pen hovers this close (K16). */
        const val TIP_SHOW_DP = 24f
        /** A tip dot never draws this close to a box corner; the corner wins there (K16). */
        const val TIP_CORNER_DP = 16f
    }
}
