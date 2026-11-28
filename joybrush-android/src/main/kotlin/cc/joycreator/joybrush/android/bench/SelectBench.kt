package cc.joycreator.joybrush.android.bench

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import cc.joycreator.joybrush.android.select.SelectBarView
import cc.joycreator.joybrush.android.select.SelectCountView
import cc.joycreator.joybrush.android.select.SelectRowView
import cc.joycreator.joybrush.android.chrome.ToolStripView
import cc.joycreator.joybrush.androidkit.BrushLibrary
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.stroke.LineAddress
import com.fadcam.ui.faditor.transform.TransformOverlayView
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Select tool's phone acceptance, unattended (reviews/SELECT_LINES_GAPS_20261023.md §4). Started like every bench:
 *
 *     adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench select
 *
 * A fresh unsaved drawing, five ink lines drawn by a synthetic S Pen, then each step does what a person would (pen taps,
 * loops and drags; finger taps on the real strip, row and box) and checks the DRAWING, not the pixels: which lines are
 * selected, where their points went, how many undo steps were written. One JSON line per step on logcat tag
 * [BenchRunner.TAG] (`{"selectBench":…,"pass":…}`); a `selectBenchShot` JSON event asks the host script for a screenshot and the
 * bench holds that state until a matching cache ACK, with a bounded asynchronous timeout. Every event carries the
 * host's unique run ID and sequence. A failed step says why.
 * Never saves. Use ui-scripts/select_bench.sh to bind source/APK/device evidence.
 */
class SelectBench(private val canvas: JbCanvasView, private val runId: String, private val finished: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val g = SelectGestures(canvas)
    /** Select's tool: the canvas slot holds the whole-line eraser in front of it (11-27), so look behind that. */
    private val tool get() = (canvas.tool.let { (it as? cc.joycreator.joybrush.androidkit.select.StrokeEraserTool)?.behind ?: it }) as? SelectTool
    private fun activeLines() = tool?.benchActiveLines().orEmpty()
    private val editableBefore = canvas.editableLines
    private val restoreClipboard = cc.joycreator.joybrush.android.select.LineSelectionClipboard.preserveForBench()
    private val steps = ArrayDeque<Pair<String, (() -> Unit) -> Unit>>()
    private var passed = 0
    private var failed = 0
    private var event = 0
    private var active = true
    private var shotGate: SelectBenchShotGate? = null
    /**
     * `--ez jb_no_shots true` (the cloud emulator): skip the screenshot protocol. Each step still runs and reports;
     * the held-state screenshot requests and their ACK wait are left out, so a slow software GPU that cannot present a
     * held state in time no longer ends the run at the first screenshot (11-27: swapList). Real-phone acceptance keeps
     * the shots (the default).
     */
    private val noShots: Boolean = (canvas.context as? android.app.Activity)?.intent?.getBooleanExtra("jb_no_shots", false) == true
    /** A captured predicate belongs to this exact bench session and exposes no mutable bench state. */
    internal val acceptsCopyResult: () -> Boolean = { active }

    /** Lifecycle interruption is not successful terminal evidence. Safe to call again after ordinary completion. */
    fun abort(): Boolean {
        if (!active) return false
        cleanup()
        return true
    }

    private fun cleanup() {
        if (!active) return
        active = false
        ui.removeCallbacksAndMessages(null)
        shotGate?.cancel()
        try { g.cancel() } finally {
            restoreClipboard()
            canvas.editableLines = editableBefore
        }
    }
    /** The five lines' addresses in drawing order, L1..L5. */
    private var ids: List<LineAddress> = emptyList()
    private val w get() = canvas.width.toFloat()
    private val h get() = canvas.height.toFloat()

    fun start() {
        if (!active) return
        if (!runId.matches(Regex("[0-9a-f]{32}"))) {
            Log.w(TAG, "Select acceptance requires a unique jb_select_run_id"); cleanup(); finished(); return
        }
        if (tool == null) {
            report("setup", false, JSONObject().put("why", "the canvas has no Select tool"))
            done(); return
        }
        step("setup") { next ->
            canvas.benchFreshDrawing {
                if (!active) return@benchFreshDrawing
                canvas.editableLines = true
                val ink = BrushLibrary.builtIn().firstOrNull { it.id == "joybrush.ink" }
                    ?: BrushLibrary.builtIn().firstOrNull { it.id == "ink" }
                if (ink != null) canvas.preset = ink
                canvas.colorArgb = 0xFF141414.toInt()
                report("setup", ink != null && !(tool?.selectToolInHand ?: true),
                    JSONObject().put("brushInHand", !(tool?.selectToolInHand ?: true)).put("brushId", ink?.id))
                next()
            }
        }
        step("draw") { next ->
            val lines = listOf(
                pts(0.15f, 0.30f, 0.85f, 0.30f), pts(0.15f, 0.40f, 0.85f, 0.40f), pts(0.50f, 0.22f, 0.50f, 0.55f),
                pts(0.20f, 0.65f, 0.45f, 0.75f),
                // L5 is an S: its ends sit on the box's sides, not its corners (where the corner handles hide tip dots).
                listOf(0.55f to 0.66f, 0.62f to 0.62f, 0.70f to 0.78f, 0.80f to 0.74f).map { (x, y) -> SelectGestures.P(x * w, y * h) })
            fun draw(i: Int) {
                if (i == lines.size) {
                    // The committed lines reach the list a little after the last stroke ends: on a slow device (the cloud
                    // emulator) that is well over the old fixed 600 ms, so wait for all five, up to 10 s, then judge.
                    val started = android.os.SystemClock.uptimeMillis()
                    fun settle() {
                        if (!active) return
                        val got = activeLines()
                        if (got.size < 5 && android.os.SystemClock.uptimeMillis() - started < 10_000L) { ui.postDelayed({ settle() }, 250); return }
                        ids = got.map { it.address }
                        undoDepth { d -> report("draw", got.size == 5, JSONObject().put("lines", got.size).put("undoDepth", d)); shot("01_drawn"); hold(next) }
                    }
                    ui.postDelayed({ settle() }, 600); return
                }
                g.stroke(lines[i], 420, pen = true, settleMs = 250) { draw(i + 1) }
            }
            draw(0)
        }
        step("chooseSelect") { next ->
            val b = g.labelled("Select —")
            if (b == null) { report("chooseSelect", false, JSONObject().put("why", "no shown strip button labelled Select")); next(); return@step }
            g.tapView(b) { report("chooseSelect", tool?.selectToolInHand == true, JSONObject()); next() }
        }
        step("tapPick") { next ->
            val (x, y) = screenMid(3)
            g.tap(x, y, pen = true, settleMs = 700) {
                val sel = lines()
                val row = g.find<SelectRowView>().firstOrNull()
                val box = g.find<TransformOverlayView>().firstOrNull()
                report("tapPick", sel == setOf(ids.getOrNull(3)) && clearOfChrome(row), JSONObject().put("selected", sel.size)
                    .put("rowClearOfChrome", clearOfChrome(row))
                    .put("isL4", sel == setOf(ids.getOrNull(3))).put("rowShown", row != null && row.alpha > 0.9f)
                    .put("rowAboveBox", row != null && box != null && rowAbove(row)).put("boxShown", box != null))
                shot("02_tap_pick"); hold(next)
            }
        }
        step("cycle") { next ->
            val (x, y) = screen(0.50f * w, 0.30f * h)
            g.tap(x, y, pen = true) {
                val first = lines()
                g.tap(x, y, pen = true, settleMs = 700) {
                    val second = lines()
                    val pair = setOf(ids.getOrNull(0), ids.getOrNull(2))
                    val count = g.find<SelectCountView>().firstOrNull()
                    report("cycle", first.size == 1 && second.size == 1 && first != second && (first + second) == pair,
                        JSONObject().put("first", first.size).put("second", second.size).put("changed", first != second)
                            .put("countShown", count != null))
                    shot("03_cycle_count"); hold(next)
                }
            }
        }
        step("tapEmptyEnds") { next ->
            val (x, y) = screen(0.50f * w, 0.88f * h)
            g.tap(x, y, pen = true) { report("tapEmptyEnds", tool?.selection == null, JSONObject()); next() }
        }
        step("lasso") { next ->
            val loop = ellipse(0.50f * w, 0.70f * h, 0.36f * w, 0.10f * h)
            g.stroke(loop, 700, pen = true, settleMs = 700) {
                val sel = lines()
                val bar = g.find<SelectBarView>().firstOrNull()
                report("lasso", sel == setOfNotNull(ids.getOrNull(3), ids.getOrNull(4)) && clearOfChrome(bar),
                    JSONObject().put("selected", sel.size).put("barShown", bar != null).put("barClearOfChrome", clearOfChrome(bar)))
                shot("04_lasso_bar"); hold(next)
            }
        }
        step("dragMove") { next ->
            val before = centres()
            val (cx, cy) = screenCentreOfSelection() ?: run { report("dragMove", false, JSONObject().put("why", "no selection")); next(); return@step }
            undoDepth { d0 ->
                g.stroke(listOf(SelectGestures.P(cx, cy), SelectGestures.P(cx, cy + 0.08f * h)), 500, pen = true, settleMs = 700) {
                    val after = centres()
                    val want = docDelta(0f, 0.08f * h)
                    undoDepth { d1 ->
                        val moved = sel(before, after).all { (b, a) -> abs(a.second - b.second - want.second) < 3f && abs(a.first - b.first - want.first) < 3f }
                        report("dragMove", moved && d1 == d0 + 1, JSONObject().put("moved", moved).put("steps", d1 - d0))
                        shot("05_moved"); hold(next)
                    }
                }
            }
        }
        step("undoMove") { next ->
            val before = centres()
            val wasSelected = lines()
            canvas.undo()
            ui.postDelayed({
                val after = centres()
                val back = ids.all { id -> val b = before[id]; val a = after[id]; b != null && a != null } &&
                    wasSelected.all { id -> abs((after[id]?.second ?: 0f) - (before[id]?.second ?: 0f)) > 3f }
                // D20 (gap G5, fixed): undo keeps the selection, so losing it now fails the step (11-27).
                val kept = lines() == wasSelected
                report("undoMove", back && kept, JSONObject().put("linesBack", back).put("selectionKeptD20", kept))
                next()
            }, 700)
        }
        step("rotate") { next ->
            val (x, y) = screenMid(3)
            g.tap(x, y, pen = true, settleMs = 700) {
                val box = g.find<TransformOverlayView>().firstOrNull()
                val knob = FloatArray(2)
                if (box == null || !box.rotateHandlePosition(knob)) { report("rotate", false, JSONObject().put("why", "no box or knob")); next(); return@tap }
                val (kx, ky) = fromView(box, knob[0], knob[1])
                val (cx, cy) = screenCentreOfSelection() ?: (kx to ky)
                val a0 = angle(ids[3])
                val r = hypot(kx - cx, ky - cy)
                val start = atan2(ky - cy, kx - cx)
                val arc = List(13) { i -> val t = start + Math.toRadians(90.0).toFloat() * i / 12f
                    SelectGestures.P(cx + r * kotlin.math.cos(t), cy + r * kotlin.math.sin(t)) }
                undoDepth { d0 ->
                    g.stroke(arc, 700, pen = true, settleMs = 700) {
                        val turned = norm(angle(ids[3]) - a0)
                        undoDepth { d1 ->
                            report("rotate", abs(abs(turned) - 90f) < 8f && d1 == d0 + 1,
                                JSONObject().put("turnedDeg", turned).put("knobBelowBox", ky > cy).put("steps", d1 - d0))
                            shot("06_rotated"); hold(next)
                        }
                    }
                }
            }
        }
        step("pinchAnywhere") { next ->
            val len0 = length(ids[3])
            undoDepth { d0 ->
                // P8 B: two fingers ANYWHERE scale the selection, here well away from the box.
                g.pinch(0.5f * w, 0.20f * h, 0.20f * w, 0.30f * w, 600) {
                    val k = length(ids[3]) / len0
                    undoDepth { d1 ->
                        report("pinchAnywhere", abs(k - 1.5f) < 0.15f && d1 == d0 + 1, JSONObject().put("scale", k).put("steps", d1 - d0))
                        next()
                    }
                }
            }
        }
        rowAction("delete", "Delete") { before, after -> after.size == before.size - 1 && ids[3] !in after }
        rowAction("duplicate", "Duplicate") { before, after -> after.size == before.size + 1 && lines().none { it in before } }
        rowAction("flipH", "Flip horizontal") { _, _ -> true }
        step("swapList") { next ->
            // D25: the row's Brush opens the swap list, drawing the selected line's colour on the drawing's paper.
            val (x, y) = screenMid(3)
            g.tap(x, y, pen = true, settleMs = 600) {
                val b = g.labelled("Swap the selected lines")
                if (b == null) { report("swapList", false, JSONObject().put("why", "no Brush button in the row")); next(); return@tap }
                g.tapView(b, settleMs = 1500) {
                    val drawer = g.find<cc.joycreator.joybrush.android.chrome.BrushDrawerView>().firstOrNull()
                    val footer = drawer != null && g.all(drawer).any { (it as? android.widget.TextView)?.text?.startsWith("Only brushes that keep") == true }
                    report("swapList", drawer != null && footer, JSONObject().put("drawerShown", drawer != null).put("footer", footer))
                    shot("09b_swap_list")
                    // The sheet is modal: a tap anywhere outside it closes it and goes no further.
                    hold { g.tap(0.5f * w, 0.12f * h, pen = false, settleMs = 500) { next() } }
                }
            }
        }
        step("copyPaste") { next ->
            val source = ids[3]
            val before = all()
            val recipe = record(source)
            val (x, y) = screenMid(3)
            g.tap(x, y, pen = true, settleMs = 600) {
                undoDepth { d0 ->
                    moreAction("copyPaste", "Copy the selected lines", next) {
                        undoDepth { copiedDepth ->
                            settingsAction("copyPaste", "Paste the copied editable lines", next) {
                                undoDepth { pastedDepth ->
                                    val added = all() - before
                                    val pasted = added.singleOrNull()?.let { record(it) }
                                    report("copyPaste", recipe != null && pasted != null && added.size == 1 &&
                                        pasted.samples == recipe.samples && pasted.colorArgb == recipe.colorArgb &&
                                        pasted.id != recipe.id && copiedDepth == d0 && pastedDepth == d0 + 1 && lines() == added,
                                        JSONObject().put("added", added.size).put("copySteps", copiedDepth - d0)
                                            .put("pasteSteps", pastedDepth - copiedDepth))
                                    shot("09_copy_pasted")
                                    hold { if (pastedDepth > d0) canvas.undo(); ui.postDelayed(next, 650) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 11-27: Cut is ONE step; Undo brings the line back SELECTED (D20); Redo takes it away again.
        step("cutUndoRedo") { next ->
            val source = ids[2]
            val (x, y) = screenMid(2)
            g.tap(x, y, pen = true, settleMs = 600) {
                undoDepth { d0 ->
                    moreAction("cutUndoRedo", "Cut: copy the selected lines", next) {
                        // The copy runs on the GL thread first; give it time before reading the drawing.
                        ui.postDelayed({
                            undoDepth { d1 ->
                                val gone = source !in all()
                                canvas.undo()
                                // Undo and Redo reach the line list a little after the call: a slow device (the cloud emulator)
                                // needs well over the old fixed 700 ms, so wait for the change, up to 8 s, then judge.
                                waitFor(700, 8_000, { source in all() }) {
                                    val back = source in all()
                                    val reselected = lines() == setOf(source)
                                    canvas.redo()
                                    waitFor(700, 8_000, { source !in all() }) {
                                        val goneAgain = source !in all()
                                        report("cutUndoRedo", gone && d1 == d0 + 1 && back && reselected && goneAgain &&
                                            cc.joycreator.joybrush.android.select.LineSelectionClipboard.hasLines,
                                            JSONObject().put("cutSteps", d1 - d0).put("gone", gone).put("undoBack", back)
                                                .put("undoReselects", reselected).put("redoGone", goneAgain))
                                        shot("09b_cut")
                                        canvas.undo()   // put the line back for the steps that follow
                                        ui.postDelayed(next, 650)
                                    }
                                }
                            }
                        }, 900)
                    }
                }
            }
        }
        step("doneEnds") { next ->
            val (x, y) = screenMid(1)
            g.tap(x, y, pen = true, settleMs = 600) {
                val done = g.labelled("Done: let go of the selection")
                if (done == null || lines().isEmpty()) {
                    report("doneEnds", false, JSONObject().put("why", if (done == null) "no Done chip" else "nothing selected")); next(); return@tap
                }
                undoDepth { d0 ->
                    g.tapView(done, settleMs = 600) {
                        undoDepth { d1 ->
                            report("doneEnds", tool?.selection == null && d1 == d0,
                                JSONObject().put("ended", tool?.selection == null).put("steps", d1 - d0))
                            next()
                        }
                    }
                }
            }
        }
        step("group") { next ->
            settingsAction("group", "Select every editable line", next) {
                val selected = lines()
                undoDepth { d0 ->
                    moreAction("group", "Move and select these lines together", next) {
                        undoDepth { d1 ->
                            val groups = selected.map { record(it)?.groupId }
                            report("group", selected.size == 5 && groups.all { it != null } &&
                                groups.distinct().size == 1 && d1 == d0 + 1,
                                JSONObject().put("selected", selected.size).put("steps", d1 - d0))
                            shot("10_grouped")
                            hold { if (d1 > d0) canvas.undo(); ui.postDelayed(next, 650) }
                        }
                    }
                }
            }
        }
        step("lock") { next ->
            val (x, y) = screenMid(3)
            // A clear tap ends the previous multi-selection before choosing L4 alone.
            g.tap(.05f * w, .9f * h, pen = true) {
                g.tap(x, y, pen = true, settleMs = 600) {
                    undoDepth { d0 ->
                        moreAction("lock", "Keep these lines from being selected", next) {
                            undoDepth { d1 ->
                                report("lock", record(ids[3])?.locked == true && d1 == d0 + 1,
                                    JSONObject().put("locked", record(ids[3])?.locked).put("steps", d1 - d0))
                                shot("11_locked")
                                hold { if (d1 > d0) canvas.undo(); ui.postDelayed(next, 650) }
                            }
                        }
                    }
                }
            }
        }
        step("tipDots") { next ->
            val l = activeLines().firstOrNull { it.address == ids.getOrNull(4) }
            val s = tool?.viewTransform
            if (l == null || s == null) { report("tipDots", false, JSONObject().put("why", "no L5")); next(); return@step }
            g.tap(screenMid(4).first, screenMid(4).second, pen = true, settleMs = 500) {
                val (ex, ey) = s.docToScreen(l.geometry.xs.last().toFloat(), l.geometry.ys.last().toFloat())
                g.hover(listOf(SelectGestures.P(ex + 40f, ey + 40f), SelectGestures.P(ex + 4f, ey + 4f)), 300) {
                    val dots = tool?.tipDots().orEmpty().size
                    report("tipDots", dots > 0, JSONObject().put("dots", dots))
                    shot("07_tip_dots"); hold { g.hoverExit(SelectGestures.P(ex, ey)); next() }
                }
            }
        }
        step("nudge") { next ->
            val b = g.labelled("Push or pull")
            if (b == null) { report("nudge", false, JSONObject().put("why", "no Nudge button")); next(); return@step }
            val before = centres()[ids[4]]
            g.tapView(b) {
                val on = tool?.nudging == true
                val (mx, my) = screenMid(4)
                g.stroke(listOf(SelectGestures.P(mx, my + 30f), SelectGestures.P(mx, my - 40f)), 400, pen = true, settleMs = 700) {
                    val after = centres()[ids[4]]
                    val bent = before != null && after != null && abs(after.second - before.second) > 1f
                    report("nudge", on && bent, JSONObject().put("nudging", on).put("bent", bent))
                    shot("08_nudged"); hold(next)
                }
            }
        }
        step("fingerPansWithBrush") { next ->
            // K18 / P10 B: once the pen has been seen, one finger PANS while another tool is in hand (with the Select
            // tool in hand a finger picks and loops like the pen, D1.1, as on the owner's page).
            fun fail(why: String) { report("fingerPansWithBrush", false, JSONObject().put("precondition", why)); next() }
            fun panWithBrush() {
                val before = all()
                val (x0, _) = canvas.view.docToScreen(0f, 0f)
                undoDepth { d0 ->
                    // Plain canvas beyond the left tool strip; preserve the original .27w pan distance.
                    g.stroke(listOf(SelectGestures.P(0.18f * w, 0.50f * h), SelectGestures.P(0.45f * w, 0.50f * h)), 300, pen = false, settleMs = 600) {
                        val (x1, _) = canvas.view.docToScreen(0f, 0f)
                        undoDepth { d1 ->
                            val drew = d1 != d0 || all() != before
                            report("fingerPansWithBrush", abs(x1 - x0) > 20f && !drew && tool?.selectToolInHand == false,
                                JSONObject().put("panPx", x1 - x0).put("drew", drew).put("brushInHand", tool?.selectToolInHand == false))
                            next()
                        }
                    }
                }
            }
            fun takeBrush() {
                val strip = g.find<ToolStripView>().singleOrNull()
                val brush = strip?.let { g.labelled("Brush —", it) }
                if (brush == null) { fail("no uniquely scoped ToolStrip Brush button"); return }
                g.tapView(brush) {
                    if (tool?.selectToolInHand != false) fail("ToolStrip tap did not take Brush")
                    else panWithBrush()
                }
            }
            fun clearSelection() {
                // Same visible empty-paper point already exercised by tapEmptyEnds; no selected line footprint here.
                g.tap(.50f * w, .88f * h, pen = true, settleMs = 600) {
                    if (tool?.selection != null) fail("empty-paper tap did not clear selection") else takeBrush()
                }
            }
            if (tool?.nudging == true) {
                val bar = g.find<SelectBarView>().singleOrNull()
                val backToBox = bar?.let { g.labelled("Back to moving and resizing the selection", it) }
                if (backToBox == null) { fail("no uniquely scoped Nudge return-to-box control"); return@step }
                g.tapView(backToBox) {
                    if (tool?.nudging != false) fail("Nudge remained active") else clearSelection()
                }
            } else clearSelection()
        }
        // Concepts parity 4a (11-28): L2 cut in its middle through the real host, as the Part of a line eraser does. L3,
        // drawn after L2, crosses L2's right-hand piece: that piece must stay under L3, so every pixel away from the cut is
        // the same; one edit is one undo step; Undo gives back the drawing exactly, Redo the cut exactly.
        step("partCut") { next ->
            fun fail(why: String) { report("partCut", false, JSONObject().put("why", why)); next() }
            val target = ids.getOrNull(1) ?: return@step fail("no L2")
            val rec = record(target) ?: return@step fail("L2 is not in the drawing")
            val brush = canvas.lineSnapshot.brush(rec.brushId) ?: return@step fail("L2's brush is not in the drawing")
            val xs = rec.samples.map { it.x }; val ys = rec.samples.map { it.y }
            val cutX = xs.min() + 0.3f * (xs.max() - xs.min())
            val r = 6.0
            val path = cc.joycreator.joybrush.core.vector.EraserPath(doubleArrayOf(cutX.toDouble(), cutX.toDouble()),
                doubleArrayOf(ys.min() - 40.0, ys.max() + 40.0), r)
            val out = cc.joycreator.joybrush.core.vector.InkErase.run(target.celId ?: target.layerId, listOf(rec), path,
                cc.joycreator.joybrush.core.vector.InkEraseMode.PARTIAL) { brush }
            val pieces = (out as? cc.joycreator.joybrush.core.vector.InkEraseOutcome.Erased)?.records.orEmpty()
            if (pieces.size != 2) return@step fail("the eraser left ${pieces.size} pieces, not 2")
            val notes = ArrayList<String>()
            val host = cc.joycreator.joybrush.android.select.CanvasSelectionHost(cc.joycreator.joybrush.android.select.JbHostCanvas(canvas),
                object : cc.joycreator.joybrush.android.select.HostUi {
                    override fun say(text: String, action: String?, onAction: (() -> Unit)?) { notes += text }
                    override fun penButtonTap(x: Float, y: Float) {}
                    override fun overlaysChanged() {}
                    override fun editRefused(message: String) { notes += message }
                    override fun log(note: String) {}
                })
            // Away from the cut means more than the eraser and the line's own reach from it.
            val band = r + 48.0
            digest(cutX.toDouble(), band) { before ->
                if (before == null) return@digest fail("no picture before the cut")
                undoDepth { d0 ->
                    val linesBefore = activeLines().size
                    if (!host.eraseParts(emptyList(), emptySet(), listOf(cc.joycreator.joybrush.androidkit.lines.LineCutPlan.Cut(target, pieces))))
                        return@undoDepth fail("the host refused the cut: $notes")
                    waitFor(300, 10_000, { activeLines().size == linesBefore + 1 }) {
                        digest(cutX.toDouble(), band) { cut ->
                            undoDepth { d1 ->
                                val l2 = activeLines().filter { it.address.recordId == rec.id || it.address.recordId.startsWith(rec.id + "~") }
                                val l3 = ids.getOrNull(2)?.let { record(it) }
                                val second = l2.mapNotNull { record(it.address) }.firstOrNull { it.id != rec.id }
                                val ordered = second != null && l3 != null && second.seq < l3.seq && second.seq == (record(target)?.seq ?: -1L) + 1L
                                canvas.undo()
                                waitFor(300, 10_000, { activeLines().size == linesBefore }) {
                                    digest(cutX.toDouble(), band) { undone ->
                                        canvas.redo()
                                        waitFor(300, 10_000, { activeLines().size == linesBefore + 1 }) {
                                            digest(cutX.toDouble(), band) { redone ->
                                                val awayEqual = cut != null && before.away == cut.away
                                                val undoExact = undone != null && undone.all == before.all
                                                val redoExact = redone != null && cut != null && redone.all == cut.all
                                                report("partCut", l2.size == 2 && d1 == d0 + 1 && ordered && awayEqual && undoExact && redoExact,
                                                    JSONObject().put("pieces", l2.size).put("undoSteps", d1 - d0).put("underL3", ordered)
                                                        .put("awayFromTheCutUnchanged", awayEqual).put("undoExact", undoExact).put("redoExact", redoExact)
                                                        .put("notes", notes.joinToString(" | ")))
                                                next()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        step("end") { next ->
            emit(JSONObject().put("selectBench", "summary").put("passed", passed).put("failed", failed))
            canvas.editableLines = editableBefore
            next()
        }
        ui.postDelayed({ run() }, 1500)
    }

    // ── steps ──

    private fun record(id: LineAddress) = canvas.lineSnapshot.planes[
        cc.joycreator.joybrush.androidkit.lines.LinePlane(id.layerId, id.celId)]?.firstOrNull { it.id == id.recordId }

    private fun moreAction(name: String, label: String, failed: () -> Unit, done: () -> Unit) {
        val more = g.labelled("More actions for the selected lines")
        if (more == null) { report(name, false, JSONObject().put("why", "More action unavailable")); failed(); return }
        g.tapView(more) { tapAction(name, label, failed, done) }
    }

    private fun settingsAction(name: String, label: String, failed: () -> Unit, done: () -> Unit) {
        val select = g.labelled("Select —")
        if (select == null || tool?.selectToolInHand != true) {
            report(name, false, JSONObject().put("why", "Select settings unavailable")); failed(); return
        }
        g.tapView(select) { tapAction(name, label, failed, done) }
    }

    private fun tapAction(name: String, label: String, failed: () -> Unit, done: () -> Unit) {
        val action = g.labelled(label)
        if (action == null) { report(name, false, JSONObject().put("why", "No shown action: $label")); failed(); return }
        g.tapView(action, settleMs = 900, done = done)
    }

    private fun step(name: String, body: (() -> Unit) -> Unit) { steps.addLast(name to body) }

    private fun run() {
        if (!active) return
        val (name, body) = steps.removeFirstOrNull() ?: run { done(); return }
        if (ids.size < 5 && name !in setOf("setup", "draw", "end")) {
            report(name, false, JSONObject().put("why", "the five lines were not drawn as editable lines")); ui.post { run() }; return
        }
        try { body { ui.post { run() } } } catch (e: Exception) {
            report(name, false, JSONObject().put("threw", "${e.javaClass.simpleName}: ${e.message}")); ui.post { run() }
        }
    }

    /** Select L4 again if needed, tap the row's button labelled [label], check the line set before → after, then Undo. */
    private fun rowAction(name: String, label: String, ok: (Set<LineAddress>, Set<LineAddress>) -> Boolean) = step(name) { next ->
        val (x, y) = screenMid(3)
        val pick = { then: () -> Unit -> if (lines() == setOf(ids[3])) then() else g.tap(x, y, pen = true, settleMs = 600) { then() } }
        pick picked@{
            val row = g.find<SelectRowView>().firstOrNull()
            val b = row?.let { g.labelled(label, it) }
            if (b == null) { report(name, false, JSONObject().put("why", "no row button '$label'")); next(); return@picked }
            val before = all()
            undoDepth { d0 ->
                g.tapView(b, settleMs = 700) {
                    val after = all()
                    undoDepth { d1 ->
                        report(name, ok(before, after) && d1 == d0 + 1, JSONObject().put("before", before.size).put("after", after.size).put("steps", d1 - d0))
                        canvas.undo()
                        ui.postDelayed({ next() }, 600)
                    }
                }
            }
        }
    }

    private fun report(step: String, pass: Boolean, detail: JSONObject) {
        if (pass) passed++ else failed++
        emit(detail.put("selectBench", step).put("pass", pass))
    }

    private fun emit(row: JSONObject) { if (active) Log.i(TAG, row.put("runId", runId).put("event", event++).toString()) }
    private fun shot(name: String) {
        if (!active || noShots) return
        val gate = shotGate ?: SelectBenchShotGate(canvas.context.cacheDir, runId, ui).also { shotGate = it }
        if (!gate.expect(event)) { screenshotFailed("invalid or stale screenshot expectation"); return }
        // Queue behind current GL metadata work, then two UI vsyncs before advertising the stable held state.
        // Final visual review still verifies that the presented GL surface matches the expected state.
        canvas.benchState(readPixels = false) { _, _ ->
            if (active) canvas.rootView.postOnAnimation {
                if (active) canvas.rootView.postOnAnimation {
                    if (active) emit(JSONObject().put("selectBenchShot", "$name.png").put("shotProtocol", 1))
                }
            }
        }
    }
    private fun done() {
        if (!active) return
        // Emit terminal evidence only after local cleanup succeeds, and before Activity.finish triggers teardown.
        val terminal = JSONObject().put("selectBenchDone", true).put("runId", runId).put("event", event++)
        cleanup()
        Log.i(TAG, terminal.toString())
        finished()
    }
    private fun hold(next: () -> Unit) {
        if (!active) return
        if (noShots) { next(); return }
        val gate = shotGate ?: run { screenshotFailed("no screenshot expectation"); return }
        gate.await(onReady = { if (active) next() }, onTimeout = { screenshotFailed("screenshot ACK timed out or could not be consumed") })
    }

    private fun screenshotFailed(why: String) {
        if (!active) return
        report("screenshotAck", false, JSONObject().put("why", why))
        steps.clear()
        done() // Resolved cleanup, but missing/failed reports make host validation fail.
    }

    /** Wait at least [minMs], then until [cond] holds or [maxMs] has passed in all, then run [then]. */
    /** What the drawing holds ([all]: every tile, look, slab and line, by name) and its pictures with the band [half] doc px
     * either side of x [cutX] blanked ([away]), for partCut. */
    private class Digest(val all: Map<String, Long>, val away: Map<String, Long>)

    private fun digest(cutX: Double, half: Double, then: (Digest?) -> Unit) {
        canvas.snapshot({ c ->
            if (!active) return@snapshot
            fun crc(b: ByteArray) = java.util.zip.CRC32().also { it.update(b) }.value
            val size = cc.joycreator.joybrush.core.paint.Tiles.SIZE
            val all = HashMap<String, Long>(); val away = HashMap<String, Long>()
            fun picture(kind: String, key: Any, name: String, bytes: ByteArray) {
                all[kind + "|" + key] = crc(bytes)
                val tx = name.substringBefore('_').toIntOrNull() ?: return
                val x0 = tx * size
                val copy = bytes.copyOf()
                for (y in 0 until size) for (x in 0 until size) {
                    if (kotlin.math.abs(x0 + x + 0.5 - cutX) <= half) { val i = (y * size + x) * 4; copy[i] = 0; copy[i + 1] = 0; copy[i + 2] = 0; copy[i + 3] = 0 }
                }
                away[kind + "|" + key] = crc(copy)
            }
            for ((k, b) in c.tiles) picture("tile", k, k.third, b)
            for ((k, b) in c.looks) picture("look", k, k.third, b)
            for ((k, b) in c.slabs) all["slab|" + k] = crc(b)
            for ((k, l) in c.strokes) all["lines|" + k] = crc(cc.joycreator.joybrush.core.stroke.StrokeCodec.encodeAll(l))
            then(Digest(all, away))
        }, { then(null) }, keepWet = true)
    }

    private fun waitFor(minMs: Long, maxMs: Long, cond: () -> Boolean, then: () -> Unit) {
        val started = android.os.SystemClock.uptimeMillis()
        fun poll() {
            if (!active) return
            if (cond() || android.os.SystemClock.uptimeMillis() - started >= maxMs) { then(); return }
            ui.postDelayed({ poll() }, 150L)
        }
        ui.postDelayed({ poll() }, minMs)
    }

    private fun undoDepth(then: (Int) -> Unit) = canvas.benchState(readPixels = false) { s, _ -> if (active) then(s?.undoDepth ?: -1) }

    // ── the drawing ──

    private fun lines(): Set<LineAddress> = (tool?.selection as? SelectionState.Lines)?.ids.orEmpty()
    private fun all(): Set<LineAddress> = activeLines().map { it.address }.toSet()
    private fun line(id: LineAddress) = activeLines().firstOrNull { it.address == id }

    /** Each line's mean point, document px. */
    private fun centres(): Map<LineAddress, Pair<Float, Float>> = activeLines().associate { l ->
        l.address to (l.geometry.xs.average().toFloat() to l.geometry.ys.average().toFloat()) }

    private fun sel(before: Map<LineAddress, Pair<Float, Float>>, after: Map<LineAddress, Pair<Float, Float>>) =
        lines().mapNotNull { id -> before[id]?.let { b -> after[id]?.let { a -> b to a } } }

    private fun angle(id: LineAddress): Float {
        val g = line(id)?.geometry ?: return 0f
        return Math.toDegrees(atan2(g.ys.last() - g.ys.first(), g.xs.last() - g.xs.first())).toFloat()
    }

    private fun length(id: LineAddress): Float {
        val g = line(id)?.geometry ?: return 0f
        return hypot(g.xs.last() - g.xs.first(), g.ys.last() - g.ys.first()).toFloat()
    }

    private fun norm(d: Float): Float { var a = d; while (a > 180f) a -= 360f; while (a < -180f) a += 360f; return a }

    // ── screen ↔ document ──

    private fun pts(x0: Float, y0: Float, x1: Float, y1: Float) =
        listOf(SelectGestures.P(x0 * w, y0 * h), SelectGestures.P((x0 + x1) / 2 * w, (y0 + y1) / 2 * h + 2f), SelectGestures.P(x1 * w, y1 * h))

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
        List(41) { i -> val t = 2 * Math.PI * i / 40; SelectGestures.P(cx + rx * kotlin.math.cos(t).toFloat(), cy + ry * kotlin.math.sin(t).toFloat()) }

    private fun screen(x: Float, y: Float) = x to y

    /** The middle sample of line [i] (L1 = 0), canvas px. */
    private fun screenMid(i: Int): Pair<Float, Float> {
        val g = ids.getOrNull(i)?.let { line(it) }?.geometry ?: return (0.5f * w) to (0.5f * h)
        val k = g.xs.size / 2
        return tool!!.viewTransform.docToScreen(g.xs[k].toFloat(), g.ys[k].toFloat())
    }

    private fun screenCentreOfSelection(): Pair<Float, Float>? {
        val q = tool?.boxDoc ?: return null
        val v = tool!!.viewTransform
        val (x, y) = v.docToScreen((q[0] + q[2] + q[4] + q[6]) / 4f, (q[1] + q[3] + q[5] + q[7]) / 4f)
        return x to y
    }

    private fun docDelta(dx: Float, dy: Float): Pair<Float, Float> {
        val v = tool!!.viewTransform
        val (ax, ay) = v.screenToDoc(0f, 0f); val (bx, by) = v.screenToDoc(dx, dy)
        return (bx - ax) to (by - ay)
    }

    /** A point in [v]'s px, in the canvas's px. */
    private fun fromView(v: View, x: Float, y: Float): Pair<Float, Float> {
        val a = IntArray(2); v.getLocationInWindow(a); val c = IntArray(2); canvas.getLocationInWindow(c)
        return (x + a[0] - c[0]) to (y + a[1] - c[1])
    }

    /** The row or bar covers none of Joy Brush's own controls (strip, top bar, layer column, round button). */
    private fun clearOfChrome(v: View?): Boolean {
        if (v == null) return false
        val root = canvas.rootView as? android.view.ViewGroup ?: return true
        val mine = g.all(v).toSet() + g.find<SelectRowView>() + g.find<SelectBarView>() + g.find<SelectCountView>() +
            g.find<TransformOverlayView>() + canvas
        val a = IntArray(2).also { v.getLocationInWindow(it) }; val o = IntArray(2).also { root.getLocationInWindow(it) }
        val r = android.graphics.RectF((a[0] - o[0]).toFloat(), (a[1] - o[1]).toFloat(), (a[0] - o[0] + v.width).toFloat(), (a[1] - o[1] + v.height).toFloat())
        val chrome = cc.joycreator.joybrush.android.select.SelectPlacement.chromeRects(root, mine, 12f * canvas.resources.displayMetrics.density)
        return chrome.none { android.graphics.RectF.intersects(it, r) }
    }

    /** K1: the actions row sits ABOVE the selection. */
    private fun rowAbove(row: View): Boolean {
        val (_, cy) = screenCentreOfSelection() ?: return false
        val (_, ry) = fromView(row, 0f, row.height.toFloat())
        return ry <= cy
    }

    companion object {
        const val TAG = BenchRunner.TAG
    }
}
