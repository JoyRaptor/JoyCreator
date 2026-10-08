package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.fadcam.ui.faditor.sprite.FilmStrip
import com.fadcam.ui.faditor.sprite.WeightedScrubBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedFilmStripTest {
    private fun context() = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun layout(view: View, width: Int = 200, height: Int = 80) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun solidDefaultsAndOutlinePaddingStayCompatible() {
        val context = context()
        val solid = FilmStrip(context)
        val outline = FilmStrip(context, FilmStrip.Style.OUTLINE)
        assertEquals(3, solid.childCount)
        assertEquals(1, outline.childCount)
        assertTrue(solid.frames().paddingLeft > 0)
        assertEquals(0, outline.frames().paddingLeft)
        val child = View(context)
        solid.frames().addView(child, android.widget.LinearLayout.LayoutParams(44, 26))
        layout(solid)
        assertEquals(44, child.width)
        assertSame(solid.frames(), child.parent)
    }

    @Test fun overlayKeepsExplicitGapBoundsAndDrawsStockAndDropThroughSharedViews() {
        val context = context(); val strip = FilmStrip(context, FilmStrip.Style.OUTLINE)
        val left = View(context); val right = View(context)
        var stock = 0; val gaps = mutableListOf<Int>()
        strip.setOverlay(object : FilmStrip.Overlay {
            override fun bounds(child: View) = if (child === left) RectF(8f, 7f, 48f, 33f)
                else RectF(52f, 7f, 136f, 33f)
            override fun drawStock(canvas: Canvas) { stock++ }
            override fun drawDrop(canvas: Canvas, index: Int) { gaps += index }
        })
        strip.frames().addView(left); strip.frames().addView(right)
        strip.frames().setDropColor(android.graphics.Color.CYAN)
        strip.frames().setDropAt(1)
        layout(strip)
        assertEquals(8, left.left); assertEquals(40, left.width)
        assertEquals(52, right.left); assertEquals(84, right.width)
        strip.draw(Canvas(Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)))
        assertEquals(1, stock); assertEquals(listOf(1), gaps)
        strip.frames().setDropAt(-1)
        strip.draw(Canvas(Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)))
        assertEquals(listOf(1), gaps)
    }

    @Test fun weightedRendererAndDefaultTouchShareHoldBoundaries() {
        val context = context(); var current = 0
        val events = mutableListOf<Pair<Int, Boolean>>()
        val holds = listOf(1, 2, 1)
        val bar = WeightedScrubBar(context, object : WeightedScrubBar.Model {
            override fun size() = holds.size
            override fun holdAt(index: Int) = holds[index]
            override fun currentIndex() = current
            override fun onScrub(index: Int, finished: Boolean) { current = index; events += index to finished }
        }, android.graphics.Color.MAGENTA, android.graphics.Color.WHITE)
        layout(bar, 176, 20)
        assertEquals(0, bar.frameAt(-100f, 8f, 44f))
        assertEquals(1, bar.frameAt(52f, 8f, 44f))
        assertEquals(1, bar.frameAt(139f, 8f, 44f))
        assertEquals(2, bar.frameAt(140f, 8f, 44f))
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(0, 10, action, x, 10f, 0)
            try { assertTrue(bar.onTouchEvent(event)) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 44f)
        touch(MotionEvent.ACTION_UP, 44f)
        assertEquals(listOf(1 to false, 1 to true), events)
        val drawn = mutableListOf<Pair<Int, Boolean>>()
        bar.setPresentation { _, index, now -> drawn += index to now }
        bar.draw(Canvas(Bitmap.createBitmap(176, 20, Bitmap.Config.ARGB_8888)))
        assertEquals(listOf(0 to false, 1 to true, 2 to false), drawn)
    }
}
