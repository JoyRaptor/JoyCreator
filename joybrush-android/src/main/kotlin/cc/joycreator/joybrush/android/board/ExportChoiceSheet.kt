package cc.joycreator.joybrush.android.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
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
    }
    var host: Host = object : Host {}
    private val chrome = BoardChromeView(context)
    private val measure = Paint()
    private var shown: BoardExportLayout.Input? = null
    private var gestureSnapshot: BoardExportLayout.Input? = null
    private var blockingGesture = false

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
        val budgetWidth = measure.measureText("frames ${input.identity.frameIds.size} × layers ${input.layerCount}")
        val percentWidth = measure.measureText("${(input.memoryFraction*100).roundToInt()}%")
        val next = input.copy(frameIds = input.identity.frameIds, formatTextWidthsPx = formatWidths,
            headerTextWidthPx = headerWidth, budgetTextWidthPx = budgetWidth, percentTextWidthPx = percentWidth)
        if (shown?.boardId != next.boardId || shown?.identity?.frameIds != next.identity.frameIds) chrome.stopInteractions()
        shown = next
        chrome.submit(BoardExportLayout.layout(next))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureSnapshot = shown
        val childHandled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) gestureSnapshot = null
        if (childHandled) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blockingGesture = shown?.let {
            BoardExportLayout.contains(it, BoardChromeLayout.Point(event.x, event.y))
        } == true
        val handled = blockingGesture
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) blockingGesture = false
        return handled
    }

    fun stopInteractions() { blockingGesture = false; gestureSnapshot = null; chrome.stopInteractions() }
    override fun onDetachedFromWindow() { stopInteractions(); super.onDetachedFromWindow() }
}
