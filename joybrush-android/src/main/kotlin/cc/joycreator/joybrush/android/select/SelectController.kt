package cc.joycreator.joybrush.android.select

import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.select.SelectOverlayView
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.chrome.IconContrast
import cc.joycreator.joybrush.core.chrome.IconInk
import cc.joycreator.joybrush.core.select.SelectRouter
import cc.joycreator.joybrush.core.select.SelectionState
import com.fadcam.ui.faditor.transform.HandleModel
import com.fadcam.ui.faditor.transform.TransformOverlayView

/**
 * JB-5.20e — puts the Select tool on the screen and keeps its pieces in step (spec §K, the owner's locked design):
 * - the [tool] is the canvas's [JbCanvasView.tool] for good; [selectToolInHand] says whether the strip's Select is chosen
 *   (with a brush chosen, only the S Pen button selects, R42, and the pen paints, D12);
 * - the Studio box (`TransformOverlayView`, R16) in its clean style (K5/K6), the rotate handle below (K4), no pills, no
 *   loupe (the canvas is GL), two fingers anywhere (P8 B), with [SelectRouter.boxTakes] as its touch gate;
 * - the [SelectOverlayView] for the loop, glow, crosshair, nudge ring, tip dots and marching ants;
 * - the row ABOVE the selection and the bar at the bottom, both on the paper with icon ink by brightness (K2, K3);
 * - the count scrubber beside a tap in dense lines (K11).
 *
 * The activity creates one and calls [attach]; the few lines that need are the Lead's (spec §H).
 */
class SelectController(
    private val kit: ChromeKit,
    /** The FrameLayout that holds the canvas: the box and the overlay go directly above it (so a declined contact reaches it). */
    private val root: FrameLayout,
    private val canvas: JbCanvasView,
    private val host: SelectionHost,
    private val popovers: Popovers,
    private val app: App,
) {
    /** What only the activity knows or owns. */
    interface App {
        /** The paper's colour (K3: the row and bar wear it). */
        fun paperArgb(): Int
        /** The strip's colour picker, opened for the selection; [onPicked] recolours (ONE step). */
        fun openColour(anchor: View, current: Int, onPicked: (Int) -> Unit)
        /** The line brushes only (a pixel brush cannot take a line); [onPicked] re-brushes (ONE step). */
        fun openLineBrushes(anchor: View, onPicked: (String) -> Unit)
        /** The icon a brush shows on the row's brush chip. */
        fun brushIcon(brushId: String): JbIcon
        /** A fresh line id (Duplicate). */
        fun newLineId(): String
        /** Copy selected lines and their frozen brushes into the in-app session clipboard. */
        fun copySelection(selection: SelectionState.Selection)
        /** K8/K22: strip width, colour and brush affect selected lines; pulse their selection rings briefly on entry. */
        fun restyleRing(on: Boolean, pulse: Boolean)
        /** K18/Q2: the shelf choice, persisted by the host. */
        fun paintMakesLines(): Boolean
        fun setPaintMakesLines(on: Boolean)
        /** Enable the explanatory caption only after the host implements per-brush priority (L3). */
        fun perBrushLinesOverrideShelf(): Boolean = false
        /** Accepted resting finger across canvas/chrome while the pen owns the count; exclude rejected palms. */
        fun hasRestingThumbForCount(): Boolean
    }

    /** The owner's picks and Settings (spec §K). */
    data class Picks(
        val chipsInRow: Boolean = true,       // P3 B (false = C, the strip only)
        val crosshair: Boolean = true,        // P4 B
        val chipsReRead: Boolean = true,      // P5 A
        val plusChip: Boolean = true,         // P6 A
        val fadeRest: Float = 0.66f,          // P2 A
        val fingerSelects: Boolean = false,   // K18 (P10 B default)
        val pickLocked: Boolean = false,
    )

    private val preferences = SelectPreferences(kit.context)
    var picks = Picks(
        fingerSelects = preferences.fingerSelects,
        pickLocked = preferences.pickLocked,
        fadeRest = if (preferences.dimRest) 0.66f else 0f,
    )
        set(value) { field = value; applyPicks(); refresh() }

    private val toast = SelectToast(kit, root, app::paperArgb, canvas::sampleScreen)
    private val toolHost = object : SelectionHost by host {
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) = toast.show(text, action, onAction)
    }
    val tool = SelectTool(toolHost, kit.dp(1f), { canvas.penHasBeenSeen })
    private val overlay = SelectOverlayView(kit.context, tool)
    private val box = TransformOverlayView(kit.context)
    private val boxHost = SelectBoxHost(tool, { canvas.view }, { RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()) })
    private val row = SelectRowView(kit, RowActions())
    private val bar = SelectBarView(kit, BarChips())
    private val count = SelectCountView(kit, CountChoices())
    private var countSession: SelectTool.CountSession? = null
    private var rowInk: IconInk? = null
    private var barInk: IconInk? = null
    private var countInk: IconInk? = null
    private var inkGeneration = 0L
    private var boxMoving = false
    private var ringWasOn = false
    private val colourDrag = SelectColourDrag(kit.dp(1f), move = { rawX, rawY, finger ->
        val at = IntArray(2)
        canvas.getLocationOnScreen(at)
        canvas.dragEyedropMove(rawX - at[0], rawY - at[1], finger)
    }, end = canvas::dragEyedropEnd)

    /** Whether the strip's Select tool is the one in the hand. */
    var selectToolInHand: Boolean
        get() = tool.selectToolInHand
        set(value) { tool.selectToolInHand = value; refresh() }

    fun attach() {
        tool.updateView(canvas.view)
        val at = root.indexOfChild(canvas) + 1
        val match = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay, at, match)
        root.addView(box, at + 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        box.setHost(boxHost, HandleModel())
        box.setCleanStyle(true)
        box.setRotateKnobBelow(true)
        box.setBendPillVisible(false)
        box.setLoupeEnabled(false)
        box.setPinchAnywhere(true)
        box.setTouchGate { e, kind -> gate(e, kind) }
        box.visibility = View.GONE
        boxHost.onGesture = { moving ->
            inkGeneration++
            countInk = null
            boxMoving = moving
            if (moving) { row.fadeOut(); count.visibility = View.GONE }
            else { place(); readInk(); row.fadeIn() }
        }
        root.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, kit.dpi(44f)))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, kit.dpi(40f)))
        root.addView(count, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, kit.dpi(44f)))
        row.visibility = View.GONE; bar.visibility = View.GONE; count.visibility = View.GONE
        tool.listener = object : SelectTool.Listener {
            override fun selectionChanged() = refresh()
            override fun overlayChanged() { overlay.invalidate() }
        }
        canvas.tool = tool
        applyPicks()
    }

    fun detach() {
        inkGeneration++
        tool.end()
        toast.dispose()
        if (canvas.tool === tool) canvas.tool = null
        listOf<View>(overlay, box, row, bar, count).forEach { root.removeView(it) }
    }

    /** The paper changed: the row, bar and box ink follow. */
    fun paperChanged() { rowInk = null; barInk = null; countInk = null; refresh() }

    /** Chain from canvas.onViewMoved alongside existing chrome sampling; keep picks anchored to the drawing. */
    fun viewMoved() {
        tool.updateView(canvas.view)
        inkGeneration++
        box.refresh()
        overlay.invalidate()
        place()
        readInk()
    }

    /** The activity's SelectionHost.toast forwards notes here, including optional Undo actions. */
    fun showToast(text: String, action: String? = null, onAction: (() -> Unit)? = null) = toast.show(text, action, onAction)

    /** The strip's pulsing width control opens the same selection-weight slider as the floating row. */
    fun showWeightSlider(anchor: View) = RowActions().weightSlider(anchor)

    /** Cancel an unfinished preview before Undo/Redo, retaining the selected line identities. */
    fun beforeHistoryChange() { tool.cancel(); tool.cancelBox() }

    /** K18: the strip calls this when Select is tapped while it is already in the hand. */
    fun showSettings(anchor: View) {
        tool.updateView(canvas.view)
        preferences.observeLockedLines(host.visibleLines().any { it.locked })
        val settings = SelectSettingsView(kit, picks.fingerSelects, app.paintMakesLines(),
            onFingerSelects = { on -> preferences.fingerSelects = on; picks = picks.copy(fingerSelects = on) },
            onPaintMakesLines = app::setPaintMakesLines,
            pickLocked = picks.pickLocked,
            dimRest = picks.fadeRest > 0f,
            canPasteLines = LineSelectionClipboard.hasLines,
            onSelectAllLines = { popovers.close(); tool.selectAllLines(); refresh() },
            onPasteLines = { popovers.close(); pasteLines() },
            onPickLocked = { on -> preferences.pickLocked = on; picks = picks.copy(pickLocked = on) },
            onDimRest = { on -> preferences.dimRest = on; picks = picks.copy(fadeRest = if (on) 0.66f else 0f) },
            showLockedOption = preferences.lockedOptionRevealed,
            perBrushLinesOverrideShelf = app.perBrushLinesOverrideShelf())
        showScrollableMenu(settings, anchor, Popovers.Side.BESIDE, widthDp = 260f)
    }

    private fun showScrollableMenu(content: View, anchor: View, side: Popovers.Side, widthDp: Float) {
        val scroll = SelectMenuScrollView(kit.context, content) { popovers.topInsetPx + kit.dpi(12f) }
        popovers.show(scroll, anchor, side, widthDp)
    }

    // ── the touch gate (D12, P8 B) ──

    private fun gate(e: MotionEvent, kind: HandleModel.Kind?): Boolean {
        val pen = e.getToolType(0).let { it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER }
        val button = pen && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val hit = when {
            kind == null -> SelectRouter.BoxHit.OFF
            kind == HandleModel.Kind.BODY -> if (tool.tipDots().any { kotlin.math.hypot(it[0] - e.x, it[1] - e.y) <= kit.dp(16f) }) SelectRouter.BoxHit.TIP else SelectRouter.BoxHit.BODY
            else -> SelectRouter.BoxHit.HANDLE
        }
        return SelectRouter.boxTakes(hit, if (pen) SelectRouter.Kind.PEN else SelectRouter.Kind.FINGER, button, tool.selectToolInHand)
    }

    // ── keeping everything in step ──

    private fun applyPicks() {
        tool.crosshair = picks.crosshair
        tool.fingerSelects = picks.fingerSelects
        tool.state.pickLocked = picks.pickLocked
    }

    private fun refresh() {
        inkGeneration++
        boxHost.settle()
        val s = tool.selection
        host.dimRest(if (s is SelectionState.Lines) picks.fadeRest else 0f)
        box.visibility = if (tool.canTransform && !tool.nudging) View.VISIBLE else View.GONE
        box.refresh()
        val paper = app.paperArgb()
        row.setPaper(paper); bar.setPaper(paper); count.setPaper(paper)
        if (countInk == null) count.ink = IconContrast.inkFor(intArrayOf(paper), null)
        if (s !is SelectionState.Lines || tool.countPick == null || tool.nudging || boxMoving)
            count.visibility = View.GONE
        when (s) {
            is SelectionState.Lines -> {
                val recs = host.visibleLines().filter { it.address in s.ids }
                preferences.observeLockedLines(recs.any { it.locked })
                val colours = recs.map { it.record.colorArgb }.distinct().take(2)
                val icon = recs.firstOrNull()?.let { app.brushIcon(it.record.brushId) } ?: JbIcon.BRUSH
                row.showLines(colours, icon, 1f, picks.chipsInRow)
            }
            is SelectionState.Pixels -> row.showPixels(canvas.strokeColor, canFill = tool.canFill, canEdit = tool.canTransform)
            null -> {}
        }
        row.visibility = if (s != null && !tool.nudging) View.VISIBLE else View.GONE
        if (row.visibility == View.VISIBLE) { row.alpha = 1f; row.isEnabled = true }
        bar.visibility = if (s != null) View.VISIBLE else View.GONE
        if (s != null) bar.show(barModel(s))
        val ringOn = s is SelectionState.Lines
        if (ringOn != ringWasOn) { app.restyleRing(ringOn, pulse = ringOn); ringWasOn = ringOn }
        overlay.invalidate()
        root.post { place(); readInk() }
    }

    private fun barModel(s: SelectionState.Selection): SelectBarView.Model {
        val lines = s is SelectionState.Lines
        val reading = (s as? SelectionState.Lines)?.reading ?: (s as? SelectionState.Pixels)?.reading
        val n = when (s) { is SelectionState.Lines -> s.ids.size; is SelectionState.Pixels -> s.linesInLoop }
        return SelectBarView.Model(
            lines = lines,
            count = n,
            fromLoop = picks.chipsReRead && reading != null,
            whole = reading?.reach != cc.joycreator.joybrush.core.select.LineLasso.Reach.TOUCHING,
            allLayers = reading?.allLayers == true,
            keepWeights = tool.keepWeights,
            addArmed = tool.addArmed,
            offerAdd = picks.plusChip,
            nudging = tool.nudging,
            ringDp = tool.nudgeRadiusDp,
        )
    }

    /** K2: the row 12 dp above the box; below it (past the rotate handle) only when there is no room above. */
    private fun place() {
        placeCount()
        val q = FloatArray(8)
        if (!boxHost.readQuad(q)) return
        val top = minOf(q[1], q[3], q[5], q[7])
        val bottom = maxOf(q[1], q[3], q[5], q[7])
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val rw = row.width.takeIf { it > 0 } ?: row.measuredWidth
        val rh = kit.dp(44f)
        var y = top - kit.dp(12f) - rh
        if (y < kit.dp(TOP_RESERVE_DP)) y = bottom + kit.dp(46f + 10f + 12f)
        y = y.coerceAtMost(root.height - kit.dp(60f) - rh)
        val minX = kit.dp(6f)
        row.x = (cx - rw / 2f).coerceIn(minX, maxOf(minX, root.width - rw - minX))
        row.y = y
        val bw = bar.width.takeIf { it > 0 } ?: bar.measuredWidth
        bar.x = (root.width - bw) / 2f
        bar.y = root.height - kit.dp(12f) - kit.dp(40f)
    }

    private fun placeCount() {
        val pick = tool.countPick
        if (tool.selection !is SelectionState.Lines || pick == null || pick.total < 2 || tool.nudging || boxMoving) {
            count.visibility = View.GONE
            return
        }
        count.show(pick.index, pick.total)
        if (countInk == null) count.ink = IconContrast.inkFor(intArrayOf(app.paperArgb()), null)
        count.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(kit.dpi(44f), View.MeasureSpec.EXACTLY))
        val (px, py) = canvas.view.docToScreen(pick.at.x.toFloat(), pick.at.y.toFloat())
        val margin = kit.dp(6f)
        count.x = (px + kit.dp(18f)).coerceIn(margin, maxOf(margin, root.width - count.measuredWidth - margin))
        val minY = kit.dp(TOP_RESERVE_DP + 4f)
        // The 22dp visible pill is centred in a 44dp contact target; keep both clear of the top/footer.
        count.y = (py - kit.dp(36f + 11f)).coerceIn(minY,
            maxOf(minY, root.height - kit.dp(58f) - count.measuredHeight))
    }

    /** K3: white or black icons by the brightness behind the row and the bar (IconContrast, the top bar's sampler). */
    private fun readInk() {
        val views = listOf<View>(row, bar, count).filter { it.visibility == View.VISIBLE && it.width > 0 }
        if (views.isEmpty()) return
        val generation = inkGeneration
        val bounds = views.map { RectF(it.x, it.y, it.x + it.width, it.y + it.height) }
        val pts = FloatArray(views.size * 18)
        var k = 0
        for (v in views) for (j in -1..1) for (i in -1..1) {
            pts[k++] = v.x + v.width / 2f + i * v.width / 3f
            pts[k++] = v.y + v.height / 2f + j * v.height / 3f
        }
        canvas.sampleScreen(pts) { colours ->
            if (generation != inkGeneration || colours.size < views.size * 9) return@sampleScreen
            for ((n, v) in views.withIndex()) {
                if (v.visibility != View.VISIBLE || bounds[n] != RectF(v.x, v.y, v.x + v.width, v.y + v.height)) continue
                val samples = IntArray(9) { colours[n * 9 + it] }
                if (v === row) { rowInk = IconContrast.inkFor(samples, rowInk); row.ink = rowInk!! }
                if (v === bar) { barInk = IconContrast.inkFor(samples, barInk); bar.ink = barInk!! }
                if (v === count) { countInk = IconContrast.inkFor(samples, countInk); count.ink = countInk!! }
            }
            box.setGuideInk(if ((rowInk ?: IconInk.LIGHT) == IconInk.DARK) 0xFF121214.toInt() else 0xFFF7F7F9.toInt())
        }
    }

    private inner class CountChoices : SelectCountView.Callbacks {
        override fun begin(pen: Boolean): SelectCountView.Model? {
            countSession?.let(tool::endCountScrub)
            countSession = tool.beginCountScrub(tool.addArmed || (pen && app.hasRestingThumbForCount()), canvas.view)
            return countSession?.pick?.let { SelectCountView.Model(it.index, it.total) }
        }
        override fun choose(index: Int): Boolean = countSession?.let { tool.scrubCount(it, index) } ?: false
        override fun end() {
            countSession?.let(tool::endCountScrub)
            countSession = null
        }
    }

    // ── the row ──

    private inner class RowActions : SelectRowView.Actions {
        override fun colourTouch(view: View, event: MotionEvent): Boolean = colourDrag.onTouch(view, event)
        override fun colour(anchor: View) {
            app.openColour(anchor, canvas.strokeColor) { argb -> tool.recolour(argb); refresh() }
        }
        override fun brush(anchor: View) { app.openLineBrushes(anchor) { id -> tool.rebrush(id); refresh() } }
        override fun weight(factor: Float, done: Boolean) { tool.reweight(factor, done); if (done) refresh() }
        override fun weightSlider(anchor: View) {
            val seek = SeekBar(kit.context).apply { max = 200; progress = 100 }
            var last = 1f
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    last = Math.pow(2.0, (p - 100) / 50.0).toFloat(); tool.reweight(last, done = false); row.showWeight(last)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { tool.reweight(last, done = true) }
            })
            kit.label(seek, "Line weight")
            popovers.show(seek, anchor, Popovers.Side.BELOW, widthDp = 220f)
        }
        override fun nudge() { tool.nudging = true; refresh() }
        override fun duplicate() { tool.duplicate { app.newLineId() }; refresh() }
        override fun delete() { tool.deleteSelection(); refresh() }
        override fun flip(horizontal: Boolean) { tool.flip(horizontal); refresh() }
        override fun more(anchor: View) = showMore(anchor)
        override fun fill() { tool.fill(canvas.strokeColor) }
    }

    private fun showMore(anchor: View) {
        val s = tool.selection ?: return
        val list = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        fun item(icon: JbIcon?, text: String, label: String, run: () -> Unit) {
            val t = TextView(kit.context).apply {
                this.text = text
                textSize = 14f
                setPadding(kit.dpi(10f), 0, kit.dpi(10f), 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = kit.dpi(40f)
                setTextColor(kit.p.drawerInk)
                val d = icon?.drawable(kit.p.drawerInk)
                d?.setBounds(0, 0, kit.dpi(20f), kit.dpi(20f))
                setCompoundDrawables(d, null, null, null)
                compoundDrawablePadding = if (d == null) 0 else kit.dpi(10f)
                setOnClickListener { popovers.close(); run() }
            }
            kit.label(t, label)
            list.addView(t)
        }
        if (s is SelectionState.Lines) item(JbIcon.PAPERCLIP, "Copy", "Copy the selected lines for pasting in this drawing or another") { app.copySelection(s) }
        if (LineSelectionClipboard.hasLines) item(JbIcon.PAPERCLIP, "Paste", "Paste the copied editable lines onto this layer") { pasteLines() }
        if (host.canAdjustColour) item(JbIcon.ADJUST, "Adjust colour", "Adjust colour: hue, saturation and brightness") { showAdjust(anchor) }
        if (s is SelectionState.Lines) {
            item(JbIcon.RASTER, "Rasterize Down", "Turn the selected lines into pixels. The picture stays the same; they stop being editable") { tool.rasterizeDown(); refresh() }
        }
        if (s is SelectionState.Lines) {
            val selected = host.visibleLines().filter { it.address in s.ids }
            val locked = selected.isNotEmpty() && selected.all { it.record.locked }
            item(JbIcon.LOCK, if (locked) "Unlock" else "Lock", if (locked) "Let these lines be selected and edited" else "Keep these lines from being selected") {
                tool.lockSelection(!locked); refresh()
            }
            val group = selected.map { it.record.groupId }.distinct().singleOrNull()
            val oneGroup = group != null && selected.all { it.record.groupId == group }
            item(null, if (oneGroup) "Ungroup" else "Group", if (oneGroup) "Let these lines move separately" else "Move and select these lines together") {
                val id = if (oneGroup) null else CanvasSelectionHost.randomId("group")
                tool.groupSelection(id); refresh()
            }
            item(null, "Invert", "Select the other unlocked lines on this layer") { tool.invertSelection(); refresh() }
        }
        showScrollableMenu(list, anchor, Popovers.Side.BELOW, widthDp = 220f)
    }

    /** Paste records into the current editable plane, assigning fresh identities and importing their frozen brushes. */
    private fun pasteLines() {
        tool.updateView(canvas.view)
        val copied = LineSelectionClipboard.snapshot()
        if (copied.isEmpty()) return
        if (host.activeLinePlane() == null) { host.toast("Choose an editable layer before pasting lines."); return }
        val existing = host.visibleLines().mapTo(HashSet()) { it.record.id }
        val ids = ArrayList<String>(copied.size)
        repeat(copied.size) {
            var id: String? = null
            repeat(64) { if (id == null) app.newLineId().takeIf { it !in existing && it !in ids }?.let { found -> id = found } }
            if (id == null) { host.toast("Those lines could not be pasted."); return }
            ids += id!!
        }
        val brushIds = HashMap<cc.joycreator.joybrush.core.brush.BrushPreset, String>()
        val destinationBrushIds = ArrayList<String>(copied.size)
        val points = copied.flatMap { it.record.samples }
        val x0 = points.minOfOrNull { it.x } ?: 0f
        val x1 = points.maxOfOrNull { it.x } ?: 0f
        val y0 = points.minOfOrNull { it.y } ?: 0f
        val y1 = points.maxOfOrNull { it.y } ?: 0f
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val (screenX, screenY) = tool.viewTransform.docToScreen(cx, cy)
        val (viewX, viewY) = if (screenX in 0f..canvas.width.toFloat() && screenY in 0f..canvas.height.toFloat())
            cx to cy else tool.viewTransform.screenToDoc(canvas.width / 2f, canvas.height / 2f)
        val dx = viewX - cx
        val dy = viewY - cy
        val plane = host.linePlaneAt(viewX, viewY)
            ?: run { host.toast("Choose an editable layer before pasting lines."); return }
        for (i in copied.indices) {
            val entry = copied[i]
            val preset = entry.brush ?: host.brushPreset(entry.record.brushId)
                ?: run { host.toast("A copied line's brush is missing."); return }
            val brushId = brushIds.getOrPut(preset) {
                host.importBrush(preset) ?: run { host.toast("That line's brush can't be brought into this drawing."); return }
            }
            destinationBrushIds += brushId
        }
        val destination = try { LineSelectionClipboard.paste(copied, plane, ids, destinationBrushIds, dx, dy) }
        catch (_: IllegalArgumentException) { host.toast("Those lines are too far from this view to paste."); return }
        if (!tool.addAndSelect(destination)) host.toast("Those lines could not be pasted.") else refresh()
    }

    /** K17: hue, saturation and brightness, live, ONE step on release. */
    private fun showAdjust(anchor: View) {
        val list = LinearLayout(kit.context).apply { orientation = LinearLayout.VERTICAL }
        val values = floatArrayOf(0f, 1f, 1f)
        val epoch = host.selectionEpoch
        var owner = tool.selection
        var previewing = false
        val controls = ArrayList<Pair<SeekBar, Int>>()
        fun current() = host.selectionEpoch == epoch && tool.selection === owner
        fun neutralValues() { values[0] = 0f; values[1] = 1f; values[2] = 1f }
        fun resetControls() {
            neutralValues()
            controls.forEach { (seek, neutral) -> seek.progress = neutral }
        }
        fun cancelPreview() {
            if (previewing && current()) host.adjustColour(0f, 1f, 1f, done = true)
            previewing = false
            resetControls()
        }
        fun slider(name: String, max: Int, start: Int, map: (Int) -> Float, i: Int) {
            val label = TextView(kit.context).apply { text = name; textSize = 12f; setTextColor(kit.p.drawerInk) }
            val seek = SeekBar(kit.context).apply { this.max = max; progress = start }
            controls += seek to start
            seek.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelPreview()
                false // SeekBar still receives the terminal event; its stop callback must see no active preview.
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || !current()) return
                    previewing = true
                    neutralValues()
                    values[i] = map(p)
                    host.adjustColour(values[0], values[1], values[2], done = false)
                }
                override fun onStartTrackingTouch(s: SeekBar) {
                    cancelPreview()
                    previewing = current()
                }
                override fun onStopTrackingTouch(s: SeekBar) {
                    if (previewing && current()) {
                        host.adjustColour(values[0], values[1], values[2], done = true)
                        previewing = false
                        tool.reconcileLineSelection()
                        owner = tool.selection
                        resetControls()
                    } else cancelPreview()
                }
            })
            kit.label(seek, name)
            list.addView(label); list.addView(seek)
        }
        slider("Hue", 360, 180, { (it - 180).toFloat() }, 0)
        slider("Saturation", 200, 100, { it / 100f }, 1)
        slider("Brightness", 200, 100, { it / 100f }, 2)
        popovers.show(list, anchor, Popovers.Side.BELOW, widthDp = 240f, onClosed = ::cancelPreview)
    }

    // ── the bar ──

    private inner class BarChips : SelectBarView.Chips {
        private fun reading(): SelectionState.Reading =
            (tool.selection as? SelectionState.Lines)?.reading ?: (tool.selection as? SelectionState.Pixels)?.reading ?: SelectionState.Reading()

        // K13: a line filter on paint means lines; the core falls back to Touching when none is whole.
        override fun take(lines: Boolean) { tool.reread(reading().copy(take = if (lines) SelectionState.Take.LINES else SelectionState.Take.PIXELS, allLayers = if (lines) reading().allLayers else false)) }
        override fun whole(whole: Boolean) { tool.reread(reading().copy(take = SelectionState.Take.LINES, reach = if (whole) cc.joycreator.joybrush.core.select.LineLasso.Reach.WHOLE else cc.joycreator.joybrush.core.select.LineLasso.Reach.TOUCHING)) }
        override fun allLayers(all: Boolean) { tool.reread(reading().copy(take = SelectionState.Take.LINES, allLayers = all)) }
        override fun keepWeights(keep: Boolean) { tool.keepWeights = keep; refresh() }
        override fun add(on: Boolean) { tool.addArmed = on; refresh() }
        override fun ringSize(dp: Float) { tool.nudgeRadiusDp = dp; overlay.invalidate() }
        override fun backToBox() { tool.nudging = false; refresh() }
    }

    private companion object {
        /** The top bar's reserve (JoyBrushActivity.TOP_RESERVE_DP): the row never sits under it. */
        const val TOP_RESERVE_DP = 54f
    }
}
