package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Concepts parity item 5 (11-27): a layer that holds editable lines says so in the layer column. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerColumnLinesTagTest {
    private class Host : LayerColumnView.Host {
        override fun addLayer() {}
        override fun selectLayer(id: String) {}
        override fun openLayer(id: String, anchor: View) {}
        override fun moveLayer(id: String, toIndex: Int) {}
        override fun maskTapped(id: String) {}
    }

    private fun column() = LayerColumnView(ChromeKit(RuntimeEnvironment.getApplication()), Host()).also { c ->
        c.layoutParams = FrameLayout.LayoutParams(c.widthPx, 400)
        c.show(LayerStack(listOf(LayerState("paint", "Paint"), LayerState("ink", "Lines")), "paint"), 16)
        c.measure(View.MeasureSpec.makeMeasureSpec(c.widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
    }

    private fun picture(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    @Test fun aLayerWithLinesWearsTheMarkAndOneWithoutDoesNot() {
        val c = column()
        val ink = c.cellFor("ink")!!
        val paint = c.cellFor("paint")!!
        val inkBefore = picture(ink)
        val paintBefore = picture(paint)
        c.setLinedLayers(setOf("ink"))
        assertTrue(c.showsLines("ink"))
        assertFalse(c.showsLines("paint"))
        assertFalse("the lines layer's cell looks different", inkBefore.sameAs(picture(ink)))
        assertTrue("the paint layer's cell is as it was", paintBefore.sameAs(picture(paint)))
        // Its lines all erased: the mark goes again.
        c.setLinedLayers(emptySet())
        assertFalse(c.showsLines("ink"))
        assertTrue(inkBefore.sameAs(picture(ink)))
    }
}
