package cc.joycreator.joybrush.android.board

import android.app.Activity
import android.graphics.Matrix
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h640dp-mdpi")
class BoardBackdropTest {
    @Test fun backdropMappingIncludesDistinctAttachedWindowOrigins() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun root(x: Int,y: Int) = object : FrameLayout(activity) {
            override fun getLocationOnScreen(location: IntArray) { location[0]=x; location[1]=y }
        }
        fun attachedView() = object : View(activity) { override fun isAttachedToWindow() = true }
        val sourceRoot=root(100,200)
        val dialogRoot=root(500,700)
        sourceRoot.layout(17,23,217,323); dialogRoot.layout(-11,4,389,504)
        val source=attachedView(); val target=attachedView()
        sourceRoot.addView(source); dialogRoot.addView(target)
        source.layout(10,15,210,315); target.layout(20,30,420,530)
        val points=floatArrayOf(0f,0f,100f,150f)
        BoardBackdrop.bitmapToView(source,target,100,150)!!.mapPoints(points)
        assertArrayEquals(floatArrayOf(-410f,-515f,-210f,-215f),points,.002f)
    }
    @Test fun captureBoundsAreCheckedBeforeExtremeDimensionsCanAllocate() {
        assertEquals(128 to 264, BoardBackdrop.captureSize(1080, 2220))
        assertEquals(128 to 128, BoardBackdrop.captureSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(BoardBackdrop.captureSize(1, Int.MAX_VALUE))
        assertNull(BoardBackdrop.captureSize(128, 513))
        assertNull(BoardBackdrop.captureSize(0, 200))
        assertNull(BoardBackdrop.captureSize(200, -1))
        assertEquals(128 to 512, BoardBackdrop.captureSize(128, 512))
    }

    @Test fun blurPreservesUniformColourAndOpaqueEdgesAtSmallSizes() {
        for (size in listOf(1 to 1, 1 to 7, 7 to 1, 9 to 8)) {
            val original = 0xff7c468a.toInt()
            val pixels = IntArray(size.first * size.second) { original }
            BoardBackdrop.blurPixels(pixels, size.first, size.second)
            assertTrue(pixels.all { it == original })
        }
    }

    @Test fun blurSpreadsArtWithoutChangingAlphaOrWrappingOppositeEdges() {
        val pixels = IntArray(81) { 0xff000000.toInt() }
        pixels[0] = 0xffffffff.toInt()
        BoardBackdrop.blurPixels(pixels, 9, 9, radius = 1)
        assertEquals(113, pixels[0] and 255)
        assertEquals(56, pixels[1] and 255)
        assertEquals(28, pixels[10] and 255)
        assertEquals(0, pixels[8] and 255)
        assertTrue(pixels.all { it ushr 24 == 255 })
        assertThrows(IllegalArgumentException::class.java) { BoardBackdrop.blurPixels(IntArray(2), 1, 1) }
    }

    @Test fun backdropMappingCancelsOversizedRotatedOverlayAndNativeAncestors() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        parent.layout(0, 0, 1080, 2220)
        parent.pivotX = 300f; parent.pivotY = 400f; parent.rotation = 7f; parent.scaleX = .9f
        val surface = View(activity)
        val overlay = View(activity)
        parent.addView(surface); parent.addView(overlay)
        surface.layout(0, 0, 1080, 2220)
        overlay.layout(-3300, -3300, 4380, 5520)
        overlay.pivotX = 3450f; overlay.pivotY = 3490f; overlay.rotation = 45f
        val transform = BoardBackdrop.bitmapToView(surface, overlay, 128, 264)!!
        val mapped = floatArrayOf(0f, 0f, 64f, 132f, 128f, 264f)
        transform.mapPoints(mapped)
        val targetGlobal = Matrix().also { overlay.transformMatrixToGlobal(it) }
        targetGlobal.mapPoints(mapped)
        val expected = floatArrayOf(0f, 0f, 540f, 1110f, 1080f, 2220f)
        Matrix().also { surface.transformMatrixToGlobal(it) }.mapPoints(expected)
        expected.indices.forEach { assertEquals(expected[it], mapped[it], .002f) }
        overlay.scaleX = 0f
        assertNull(BoardBackdrop.bitmapToView(surface, overlay, 128, 264))
    }
}
