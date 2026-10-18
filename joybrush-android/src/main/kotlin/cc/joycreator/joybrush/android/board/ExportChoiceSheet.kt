package cc.joycreator.joybrush.android.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout
import cc.joycreator.joybrush.core.chrome.BoardChromeIdentity
import cc.joycreator.joybrush.core.chrome.BoardExportLayout
import com.fadcam.ui.type.Type
import kotlin.math.roundToInt

/** Reusable K6 presentation, available to Studio through its existing joybrush-android dependency.
 * Hosts supply validated choices/capabilities and implement export. No private Studio flow is copied. */
class ExportChoiceSheet(context: Context) : FrameLayout(context) {
    interface Host {
        fun scope(board: BoardChromeIdentity, scope: BoardExportLayout.Scope) {}
        fun format(board: BoardChromeIdentity, format: BoardExportLayout.Format) {}
        fun export(choice: BoardExportLayout.Choice) {}
        /** The existing shared Frost provider supplies the blurred backdrop, if enabled. */
        fun frost(canvas: Canvas, element: BoardChromeLayout.Element) {}
        /** The person tapped Export, a scope row or a format chip while it is greyed: [reason] says why. It stays greyed. */
        fun refused(reason: String) {}
    }
    var host: Host = object : Host {}
    private val chrome = BoardChromeView(context)
    private val measure = Paint()
    private var shown: BoardExportLayout.Input? = null
    private var gestureSnapshot: BoardExportLayout.Input? = null
    private var blockingGesture = false
    /** The displayed layout, so a tap on a greyed control can be told apart from a tap on the empty sheet. */
    private var shownLayout: BoardChromeLayout.Layout? = null
    private var greyedTap: String? = null

    init {
        addView(chrome, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chrome.host = object : BoardChromeView.Host {
            override fun action(id: String, held: Boolean) {
                if (held) return
                val snapshot = gestureSnapshot ?: shown ?: return
                BoardExportLayout.Scope.entries.firstOrNull { id == "export-scope-${it.name.lowercase()}" }?.let {
                    if (it in snapshot.availableScopes) host.scope(snapshot.identity, it)
                    return
                }
                BoardExportLayout.Format.entries.firstOrNull { id == "export-format-${it.name.lowercase()}" }?.let {
                    if (it in snapshot.availableFormats) host.format(snapshot.identity, it)
                    return
                }
                if (id == "export-go" && snapshot.canExport()) host.export(snapshot.choice())
            }
            override fun frost(canvas: Canvas, element: BoardChromeLayout.Element) = host.frost(canvas, element)
        }
    }

    fun invalidateBackdrop() { chrome.invalidate() }

    fun show(input: BoardExportLayout.Input) {
        // Intrinsic font measurement only. Core decides every position, padding and rectangle.
        measure.typeface = Type.body(context, 600)
        measure.textSize = 10.5f * resources.displayMetrics.scaledDensity
        val formatWidths = BoardExportLayout.Format.entries.associateWith { measure.measureText(it.label) }
        measure.typeface = Type.display(context, 800)
        measure.textSize = 14.5f * resources.displayMetrics.scaledDensity
        val headerWidth = measure.measureText("Export")
        measure.typeface = Type.mono(context, 500)
        measure.textSize = 8.5f * resources.displayMetrics.scaledDensity
        val budgetWidth = measure.measureText(budgetLabel(input))
        val percentWidth = measure.measureText("${(input.memoryFraction*100).roundToInt()}%")
        val next = input.copy(frameIds = input.identity.frameIds, formatTextWidthsPx = formatWidths,
            headerTextWidthPx = headerWidth, budgetTextWidthPx = budgetWidth, percentTextWidthPx = percentWidth)
        if (shown?.boardId != next.boardId || shown?.identity?.frameIds != next.identity.frameIds) chrome.stopInteractions()
        shown = next
        val layout = BoardExportLayout.layout(next)
        shownLayout = layout
        chrome.submit(layout)
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = "Export ${next.name}"
        // The controls are native Views, while their labels/radio marks are drawn by chrome.
        // Supply equivalent native semantics without adding another visible sheet or layout.
        for (index in 0 until chrome.childCount) {
            val target = chrome.getChildAt(index)
            val control = layout.controls.firstOrNull { it.tooltip == target.contentDescription?.toString() } ?: continue
            val scope = BoardExportLayout.Scope.entries.firstOrNull { control.id == "export-scope-${it.name.lowercase()}" }
            val format = BoardExportLayout.Format.entries.firstOrNull { control.id == "export-format-${it.name.lowercase()}" }
            val checked = scope == next.scope && scope != null || format == next.format && format != null
            val description = when {
                scope != null -> scopeDescription(next, scope)
                format != null -> format.label
                control.id == "export-budget" -> "${budgetLabel(next)}, ${(next.memoryFraction * 100).roundToInt()}% of memory budget"
                control.id == "export-go" -> "Export ${next.name}, ${scopeDescription(next, next.scope)}, ${next.format.label}, ${next.pixelWidth} by ${next.pixelHeight} pixels"
                else -> control.tooltip
            }
            target.isSelected = checked
            target.contentDescription = description
            target.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (scope != null || format != null) {
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = checked
                    } else if (control.id == "export-go") info.className = "android.widget.Button"
                }
            }
        }
    }

    private fun budgetLabel(input: BoardExportLayout.Input): String =
        (input.cellCount?.let { "cells $it" } ?: "frames ${input.identity.frameIds.size}") + " × layers ${input.layerCount}"

    private fun scopeDescription(input: BoardExportLayout.Input, scope: BoardExportLayout.Scope): String = when (scope) {
        BoardExportLayout.Scope.ANIMATION -> "Animation, all ${input.identity.frameIds.size} frames"
        BoardExportLayout.Scope.RANGE -> {
            val start = input.identity.frameIds.indexOf(input.rangeStartId)
            val end = input.identity.frameIds.indexOf(input.rangeEndId)
            if (start < 0 || end < 0) scope.label else "A range of frames, ${start + 1} through ${end + 1}"
        }
        BoardExportLayout.Scope.FRAME -> "This frame, ${input.identity.frameIds.indexOf(input.currentFrameId) + 1}"
        BoardExportLayout.Scope.BOARD -> "This board, ${input.name}"
        BoardExportLayout.Scope.ALL_IMAGES -> "All image boards"
    }

    /** The sentence for a press that lands on a greyed control, else null. Disabled controls take no press of their own. */
    private fun greyedReasonAt(event: MotionEvent): String? {
        val input = shown ?: return null
        val layout = shownLayout ?: return null
        val control = BoardChromeLayout.hit(layout, BoardChromeLayout.Point(event.x, event.y), enabledOnly = false)
        return if (control != null && !control.enabled) BoardExportLayout.whyGreyed(input, control.id) else null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { gestureSnapshot = shown; greyedTap = greyedReasonAt(event) }
        val childHandled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            // The lift must still be on the control: sliding off it is not a tap, as with an enabled one.
            val reason = greyedTap
            if (reason != null && greyedReasonAt(event) == reason) host.refused(reason)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { gestureSnapshot = null; greyedTap = null }
        if (childHandled) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blockingGesture = shown?.let {
            BoardExportLayout.contains(it, BoardChromeLayout.Point(event.x, event.y))
        } == true
        val handled = blockingGesture
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) blockingGesture = false
        return handled
    }

    fun stopInteractions() { blockingGesture = false; gestureSnapshot = null; greyedTap = null; chrome.stopInteractions() }
    override fun onDetachedFromWindow() { stopInteractions(); super.onDetachedFromWindow() }
}
