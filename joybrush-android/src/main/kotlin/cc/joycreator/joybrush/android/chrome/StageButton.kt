package cc.joycreator.joybrush.android.chrome

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.TextView

/**
 * The test-stroke stage's own small buttons (Reset, Clear, Test): plain words in the drawer's ink, a soft tint only while
 * pressed. One look for every brush editor (BRUSH-UI §L 1), so both editors build them here.
 */
object StageButton {
    fun make(kit: ChromeKit, words: String, label: String, action: () -> Unit): TextView = TextView(kit.context).apply {
        text = words
        textSize = 13f
        setTextColor(kit.p.drawerInk)
        gravity = Gravity.CENTER
        minHeight = kit.dpi(ChromeKit.TOUCH_DP)
        setPadding(kit.dpi(12f), 0, kit.dpi(12f), 0)
        kit.label(this, label)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                cornerRadius = kit.dp(10f)
                setColor(kit.ink(0.2f))
            })
        }
        setOnClickListener { action() }
    }
}
