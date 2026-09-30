package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout

/**
 * One panel open at a time, beside the thing that opened it (JB-2.01: "panels that open next to what you tapped", on a
 * phone and a tablet alike). The panel is the shared see-through surface; a tap anywhere outside closes it and is
 * CONSUMED, so closing a panel never also leaves a dot on the drawing. Opening takes 220 ms from 95% (visual language
 * §1.10, never from 0); closing is instant.
 */
class Popovers(private val kit: ChromeKit, private val host: FrameLayout) {

    /** Which side of the anchor the panel opens on. */
    enum class Side { BESIDE, BELOW, ABOVE }

    private var catcher: View? = null
    private var card: View? = null
    private var onClosed: (() -> Unit)? = null

    val isOpen: Boolean get() = card != null

    /** Panels never open above this (px from the host's top): the top bar lives there, and a see-through panel over it muddles both. */
    var topInsetPx = 0

    /**
     * Opens [content] beside [anchor] (on the side of it with more room when [side] is BESIDE). [widthDp] 0 means the
     * content's own width. Anything already open is closed first.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(content: View, anchor: View, side: Side = Side.BESIDE, widthDp: Float = 0f, onClosed: (() -> Unit)? = null) {
        close()
        this.onClosed = onClosed
        val c = View(host.context).apply {
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) close(); true }
        }
        host.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        catcher = c

        val frame = FrameLayout(host.context).apply {
            val pad = kit.dpi(8f)
            setPadding(pad, pad, pad, pad)
            kit.surface(this, 14f)
            elevation = kit.dp(6f)
            isClickable = true
        }
        frame.addView(content)
        val w = if (widthDp > 0f) kit.dpi(widthDp) else ViewGroup.LayoutParams.WRAP_CONTENT
        host.addView(frame, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        card = frame
        frame.visibility = View.INVISIBLE
        // Placed once it has a size: a panel measured before its first layout would land at 0 × 0.
        frame.doOnLayout { place(frame, anchor, side) }
    }

    /**
     * Opens [content] along the bottom of the screen, full width up to [maxWidthDp] (the brush drawer on a phone).
     *
     * [modal] false leaves the rest of the screen live: no touch-catcher, so the canvas still draws while the sheet is up
     * (R9: the brush tuning sheet, where a slider is moved and a test stroke drawn, over and over). Such a sheet closes
     * itself, or when another panel opens.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showSheet(content: View, maxWidthDp: Float, alignEnd: Boolean, onClosed: (() -> Unit)? = null, modal: Boolean = true) {
        close()
        this.onClosed = onClosed
        if (modal) {
            val c = View(host.context).apply {
                setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) close(); true }
            }
            host.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            catcher = c
        }
        val frame = FrameLayout(host.context).apply {
            val pad = kit.dpi(8f)
            setPadding(pad, pad, pad, pad)
            kit.surface(this, 16f)
            elevation = kit.dp(6f)
            isClickable = true
        }
        frame.addView(content)
        val avail = host.width - host.paddingLeft - host.paddingRight - kit.dpi(12f)
        val w = minOf(avail, kit.dpi(maxWidthDp))
        val lp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM or (if (alignEnd) android.view.Gravity.END else android.view.Gravity.START)
            val m = kit.dpi(6f)
            setMargins(m, m, m, m)
        }
        host.addView(frame, lp)
        card = frame
        grow(frame, 0.5f, 1f)
    }

    fun close() {
        val had = card != null
        catcher?.let { host.removeView(it) }
        card?.let { host.removeView(it) }
        catcher = null
        card = null
        if (had) {
            val done = onClosed
            onClosed = null
            done?.invoke()
        }
    }

    private fun place(frame: View, anchor: View, side: Side) {
        val a = Rect()
        anchor.getDrawingRect(a)
        try {
            host.offsetDescendantRectToMyCoords(anchor, a)
        } catch (e: IllegalArgumentException) {
            // The anchor left the screen between the tap and this layout (a list rebuilt under it). Open beside the
            // host's right edge instead: a panel in a slightly odd place beats the whole screen closing.
            a.set(host.width - host.paddingRight, host.height / 3, host.width - host.paddingRight, host.height / 3)
        }
        val gap = kit.dpi(6f)
        val minX = host.paddingLeft + gap
        val maxX = host.width - host.paddingRight - gap - frame.width
        val minY = host.paddingTop + gap + topInsetPx
        val maxY = host.height - host.paddingBottom - gap - frame.height
        var x: Int
        var y: Int
        var pivotX: Float
        var pivotY: Float
        when (side) {
            Side.BESIDE -> {
                val roomRight = host.width - a.right
                val right = roomRight >= a.left
                x = if (right) a.right + gap else a.left - gap - frame.width
                y = a.centerY() - frame.height / 2
                pivotX = if (right) 0f else 1f
                pivotY = 0.5f
            }
            Side.BELOW -> {
                x = a.centerX() - frame.width / 2
                y = a.bottom + gap
                pivotX = 0.5f; pivotY = 0f
            }
            Side.ABOVE -> {
                x = a.centerX() - frame.width / 2
                y = a.top - gap - frame.height
                pivotX = 0.5f; pivotY = 1f
            }
        }
        x = x.coerceIn(minX, maxOf(minX, maxX))
        y = y.coerceIn(minY, maxOf(minY, maxY))
        frame.x = x.toFloat()
        frame.y = y.toFloat()
        frame.visibility = View.VISIBLE
        grow(frame, pivotX, pivotY)
    }

    private fun grow(frame: View, px: Float, py: Float) {
        frame.doOnLayout {
            frame.pivotX = frame.width * px
            frame.pivotY = frame.height * py
            frame.scaleX = 0.95f; frame.scaleY = 0.95f; frame.alpha = 0f
            frame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(OPEN_MS).start()
        }
    }

    private companion object {
        const val OPEN_MS = 220L
    }
}
