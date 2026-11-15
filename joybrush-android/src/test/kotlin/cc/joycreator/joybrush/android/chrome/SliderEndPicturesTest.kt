package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import cc.joycreator.joybrush.android.select.testBrush
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** BRUSH-UI section K.3: slider-end pictures are drawn one at a time, and a newer ask for the same end replaces a waiting one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SliderEndPicturesTest {
    @Test fun oneAtATimeAndNewestWins() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val host = FrameLayout(activity); activity.setContentView(host)
        val views = (0 until 3).map { ImageView(activity).also { v -> host.addView(v, FrameLayout.LayoutParams(40, 20)) } }
        host.measure(0, 0); host.layout(0, 0, 400, 400); views.forEach { it.layout(0, 0, 40, 20) }
        val asked = ArrayList<String>()
        val pending = ArrayList<(Bitmap) -> Unit>()
        val parts = ArrayList<cc.joycreator.joybrush.core.brush.TestStroke.Part>()
        val q = SliderEndPictures { p, _, _, part, ready -> asked += p.name; parts += part; pending += ready; {} }
        q.ask(views[0], testBrush("a", name = "A"))
        q.ask(views[1], testBrush("b", name = "B"))
        q.ask(views[1], testBrush("b2", name = "B2"))   // replaces the waiting B
        q.ask(views[2], testBrush("c", name = "C"), cc.joycreator.joybrush.core.brush.TestStroke.Part.DAB)
        assertEquals(listOf("A"), asked)                 // only one in flight
        pending.removeAt(0)(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("A", "B2"), asked)
        pending.removeAt(0)(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("A", "B2", "C"), asked)
        assertEquals(cc.joycreator.joybrush.core.brush.TestStroke.Part.DAB, parts.last())
    }
}
