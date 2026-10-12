package cc.joycreator.joybrush.android.select

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.core.chrome.IconContrast
import cc.joycreator.joybrush.core.chrome.IconInk

/** A brief paper-coloured note above the selection bar, with an optional one-shot action. */
class SelectToast(
    private val kit: ChromeKit,
    private val root: FrameLayout,
    private val paperArgb: () -> Int,
    private val sampleScreen: ((FloatArray, (IntArray) -> Unit) -> Unit)? = null,
) {
    private var generation = 0
    private var current: Surface? = null
    private var timeout: Runnable? = null
    private val surfaces = LinkedHashSet<Surface>()

    /** Show a note. The optional action is labelled, remains readable beside wrapped text, and runs once. */
    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        dismissCurrent(incrementGeneration = false)
        val token = ++generation
        val surface = Surface(kit).also {
            val rootWidth = root.width.takeIf { width -> width > 0 } ?: root.resources.displayMetrics.widthPixels
            it.configure(text, action, paperArgb(), rootWidth)
        }
        current = surface
        val outer = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = kit.dpi(BOTTOM_OFFSET_DP)
        }
        root.addView(surface, outer)
        surfaces += surface
        surface.alpha = 0f
        surface.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        surface.setAction(action, onAction) { if (generation == token && current === surface) dismiss() }
        surface.doOnLayout { sampleInk(surface, token) }

        val delay = timeoutMillis(!action.isNullOrBlank() && onAction != null)
        val end = Runnable { if (generation == token && current === surface) dismiss() }
        timeout = end
        surface.postDelayed(end, delay)
    }

    fun dismiss() {
        dismissCurrent(incrementGeneration = true)
    }

    /** Remove callbacks and the surface so an old sample or timeout cannot affect the next note. */
    fun dispose() {
        generation++
        timeout?.let { current?.removeCallbacks(it) }
        timeout = null
        surfaces.toList().forEach { view ->
            view.deactivate()
            view.animate().cancel()
            (view.parent as? FrameLayout)?.removeView(view)
        }
        surfaces.clear()
        current = null
    }

    private fun dismissCurrent(incrementGeneration: Boolean) {
        if (incrementGeneration) generation++
        timeout?.let { current?.removeCallbacks(it) }
        timeout = null
        current?.let { view ->
            view.deactivate()
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
                surfaces.remove(view)
                (view.parent as? FrameLayout)?.removeView(view)
                if (current === view) {
                    current = null
                }
            }.start()
        }
    }

    private fun sampleInk(surface: Surface, token: Int) {
        val sample = sampleScreen ?: return
        if (generation != token || current !== surface || surface.parent !== root) return
        val xs = FloatArray(3)
        val ys = FloatArray(3)
        val insetX = kit.dp(12f)
        val insetY = kit.dp(8f)
        for (i in 0..2) {
            xs[i] = surface.x + insetX + (surface.width - insetX * 2f) * i / 2f
            ys[i] = surface.y + surface.height / 2f + if (i == 1) insetY else -insetY
        }
        val points = FloatArray(6)
        for (i in 0..2) { points[i * 2] = xs[i]; points[i * 2 + 1] = ys[i] }
        sample(points) { colours ->
            if (generation == token && current === surface && surface.parent === root && colours.isNotEmpty()) {
                surface.setInk(IconContrast.inkFor(colours, surface.ink))
            }
        }
    }

    private fun timeoutMillis(hasAction: Boolean): Long {
        val base = if (hasAction) ACTION_TIMEOUT_MS else NOTE_TIMEOUT_MS
        val manager = kit.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return if (Build.VERSION.SDK_INT >= 29 && manager != null) {
            manager.getRecommendedTimeoutMillis(base.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong()
        } else base
    }

    private class Surface(private val kit: ChromeKit) : LinearLayout(kit.context) {
        private val message = TextView(kit.context)
        private val actionView = TextView(kit.context)
        private val backing = GradientDrawable()
        private var actionRun: (() -> Unit)? = null
        var ink: IconInk = IconInk.LIGHT
            private set
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
            background = backing
            elevation = kit.dp(3f)
            isClickable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            message.apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setLineSpacing(0f, 1f)
                includeFontPadding = true
            }
            actionView.apply {
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(40f)
                minHeight = dp(40f)
                setPadding(dp(8f), 0, dp(8f), 0)
                isClickable = true
                isFocusable = true
                visibility = View.GONE
            }
            addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(actionView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4f) })
        }

        fun configure(text: String, action: String?, paper: Int, rootWidth: Int) {
            message.text = text
            message.contentDescription = text
            kit.label(this, text)
            actionView.text = action.orEmpty()
            actionView.visibility = if (action.isNullOrBlank()) View.GONE else View.VISIBLE
            val maxWidth = (rootWidth - dp(HORIZONTAL_MARGIN_DP * 2)).coerceAtLeast(dp(40f))
            if (action.isNullOrBlank()) {
                message.maxWidth = (maxWidth - dp(18f)).coerceAtLeast(1)
            } else {
                actionView.maxWidth = minOf(dp(MAX_ACTION_WIDTH_DP), (maxWidth - dp(62f)).coerceAtLeast(dp(40f)))
                message.maxWidth = (maxWidth - dp(22f) - actionView.maxWidth).coerceAtLeast(1)
            }
            backing.cornerRadius = kit.dp(22f)
            backing.setColor(ColorUtils.setAlphaComponent(paper, PAPER_ALPHA))
            setInk(IconContrast.inkFor(intArrayOf(paper), null))
        }

        fun setInk(value: IconInk) {
            ink = value
            val colour = if (value == IconInk.DARK) DARK_INK else LIGHT_INK
            message.setTextColor(colour)
            actionView.setTextColor(colour)
            // Like the row, a paper backing without a perimeter blends into a blank canvas.
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            backing.cornerRadius = h / 2f
        }

        fun setAction(action: String?, callback: (() -> Unit)?, dismiss: () -> Unit) {
            if (action.isNullOrBlank() || callback == null) {
                actionView.visibility = View.GONE
                return
            }
            actionView.visibility = View.VISIBLE
            actionView.text = action
            kit.label(actionView, action)
            actionView.isEnabled = true
            actionView.alpha = 1f
            actionRun = callback
            actionView.setOnClickListener {
                val run = actionRun ?: return@setOnClickListener
                actionView.isEnabled = false
                actionView.isClickable = false
                actionRun = null
                try { run() } finally { dismiss() }
            }
        }

        fun deactivate() {
            actionRun = null
            actionView.isEnabled = false
            actionView.isClickable = false
            actionView.setOnClickListener(null)
        }

        private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

        companion object {
            private const val PAPER_ALPHA = 0xE0
            private const val DARK_INK = 0xFF121214.toInt()
            private const val LIGHT_INK = 0xFFF7F7F9.toInt()
        }
    }

    private companion object {
        const val HORIZONTAL_MARGIN_DP = 12f
        const val BOTTOM_OFFSET_DP = 64f
        const val MAX_ACTION_WIDTH_DP = 96f
        const val NOTE_TIMEOUT_MS = 2200L
        const val ACTION_TIMEOUT_MS = 4200L
        const val FADE_IN_MS = 140L
        const val FADE_OUT_MS = 180L
    }
}
