package cc.joycreator.joybrush.android.select

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** Bound the menu to the popover's usable height without shrinking its pills or text rows. */
internal class SelectMenuScrollView(
    context: Context,
    content: View,
    private val reservedHeight: () -> Int,
) : ScrollView(context) {
    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Surface padding is already deducted by the parent. Reserve the top bar and both placement gaps here.
        val parentHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
        val available = (parentHeight - reservedHeight().coerceAtLeast(0)).coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }
}
