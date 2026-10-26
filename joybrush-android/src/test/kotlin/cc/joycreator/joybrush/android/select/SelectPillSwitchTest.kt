package cc.joycreator.joybrush.android.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import cc.joycreator.joybrush.android.chrome.ChromeKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectPillSwitchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun render(switch: SelectPillSwitch): Pair<Bitmap, Rect> {
        switch.measure(
            View.MeasureSpec.makeMeasureSpec(kit.dpi(240f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(kit.dpi(56f), View.MeasureSpec.EXACTLY),
        )
        switch.layout(0, 0, switch.measuredWidth, switch.measuredHeight)
        val image = Bitmap.createBitmap(switch.width, switch.height, Bitmap.Config.ARGB_8888)
        switch.draw(Canvas(image))
        val bounds = Rect(switch.trackDrawable!!.bounds)
        assertFalse("native Switch must lay out the actual track", bounds.isEmpty)
        return image to bounds
    }

    private val kit by lazy { ChromeKit(context) }

    @Test fun checkedStateSelectsGradientAndPunchesOnOutOfThePill() {
        val switch = SelectPillSwitch(kit).apply { text = "Editable lines"; isChecked = true }
        assertTrue(switch.trackDrawable!!.state.contains(android.R.attr.state_checked))
        val (image, bounds) = render(switch)

        assertEquals(Color.TRANSPARENT, image.getPixel(bounds.left, bounds.top))
        assertEquals(Color.TRANSPARENT, image.getPixel(bounds.right - 1, bounds.top))
        val leftInterior = image.getPixel(bounds.left + kit.dpi(8f), bounds.centerY() - kit.dpi(8f))
        val rightInterior = image.getPixel(bounds.right - kit.dpi(8f), bounds.centerY() + kit.dpi(8f))
        assertNotEquals("diagonal SelectAccent shader must vary across the pill", leftInterior, rightInterior)
        assertNotEquals("checked interior must use the selected palette", kit.ink(0.1f), leftInterior)

        val minOnAlpha = (bounds.centerX() - kit.dpi(12f)..bounds.centerX() + kit.dpi(12f)).minOf { x ->
            (bounds.centerY() - kit.dpi(8f)..bounds.centerY() + kit.dpi(8f)).minOf { y ->
                Color.alpha(image.getPixel(x, y))
            }
        }
        assertTrue("On text region must contain a low-alpha DST_OUT cutout", minOnAlpha < 64)
    }

    @Test fun offStateKeepsReadableInkAndNativeSwitchCanToggleBack() {
        val switch = SelectPillSwitch(kit).apply { text = "Editable lines" }
        val (offImage, offBounds) = render(switch)
        assertFalse(switch.isChecked)
        assertFalse(switch.trackDrawable!!.state.contains(android.R.attr.state_checked))
        val neutral = offImage.getPixel(offBounds.left + kit.dpi(8f), offBounds.centerY())
        val expectedNeutral = kit.ink(0.1f)
        assertEquals(Color.alpha(expectedNeutral), Color.alpha(neutral))
        // Low-alpha ARGB8888 stores premultiplied bytes; unpremultiplication can round a channel by a few units.
        assertTrue(kotlin.math.abs(Color.red(neutral) - Color.red(expectedNeutral)) <= 6)
        assertTrue(kotlin.math.abs(Color.green(neutral) - Color.green(expectedNeutral)) <= 6)
        assertTrue(kotlin.math.abs(Color.blue(neutral) - Color.blue(expectedNeutral)) <= 6)
        val maxOffAlpha = (offBounds.centerX() - kit.dpi(12f)..offBounds.centerX() + kit.dpi(12f)).maxOf { x ->
            (offBounds.centerY() - kit.dpi(8f)..offBounds.centerY() + kit.dpi(8f)).maxOf { y ->
                Color.alpha(offImage.getPixel(x, y))
            }
        }
        assertTrue("Off word must remain readable above its translucent neutral fill", maxOffAlpha > 192)

        var callbacks = 0
        switch.setOnCheckedChangeListener { _, _ -> callbacks++ }
        switch.performClick()
        assertTrue(switch.isChecked)
        assertTrue(switch.trackDrawable!!.state.contains(android.R.attr.state_checked))
        switch.performClick()
        assertFalse(switch.isChecked)
        assertFalse(switch.trackDrawable!!.state.contains(android.R.attr.state_checked))
        assertEquals("native Switch dispatches both state transitions", 2, callbacks)
    }

    @Test fun nativeSwitchLabelAndAccessibilityReflectCheckedState() {
        // Positive control first: stock and styled Switch use the same detached-view fixture.
        assertNativeAccessibilityAndCallbacks(Switch(context).apply {
            text = "Editable lines"
            textOn = "On"
            textOff = "Off"
            showText = false
        })
        val switch = SelectPillSwitch(kit).apply { text = "Editable lines" }

        assertEquals("Editable lines", switch.text.toString())
        assertFalse(switch.showText)
        assertEquals("On", switch.textOn.toString())
        assertEquals("Off", switch.textOff.toString())
        assertTrue("track has the approved 56 dp minimum", switch.switchMinWidth >= kit.dpi(56f))
        assertFalse("pill track must not be split around the absent thumb", switch.splitTrack)
        assertNativeAccessibilityAndCallbacks(switch)
    }

    private fun assertNativeAccessibilityAndCallbacks(switch: Switch) {
        var callbacks = 0
        switch.setOnCheckedChangeListener { _, _ -> callbacks++ }
        assertEquals(0, callbacks)
        assertAccessibilityState(switch, checked = false)
        switch.isChecked = true
        assertAccessibilityState(switch, checked = true)
        assertEquals("native CompoundButton listener reports a genuine checked transition", 1, callbacks)
        switch.isChecked = false
        assertAccessibilityState(switch, checked = false)
        assertEquals("native CompoundButton listener reports the return transition", 2, callbacks)
    }

    @Suppress("DEPRECATION")
    private fun assertAccessibilityState(switch: Switch, checked: Boolean) {
        // Explicit initialization avoids Robolectric's uninitialized node for a detached View.
        val node = AccessibilityNodeInfo.obtain()
        try {
            switch.onInitializeAccessibilityNodeInfo(node)
            assertEquals(Switch::class.java.name, node.className)
            assertTrue(node.isCheckable)
            assertEquals(checked, node.isChecked)
            // Native Switch may append its On/Off accessibility text to the visible label.
            assertTrue(node.text.toString().contains("Editable lines"))
        } finally {
            node.recycle()
        }
    }

    @Test fun drawableAlphaAppliesToOffAndCheckedTrackWithoutFillingTheCutout() {
        val switch = SelectPillSwitch(kit).apply { text = "Editable lines" }
        val track = switch.trackDrawable!!
        track.alpha = 128

        val (offImage, offBounds) = render(switch)
        val expectedOffAlpha = (Color.alpha(kit.ink(0.1f)) * 128 + 127) / 255
        assertEquals(expectedOffAlpha, Color.alpha(offImage.getPixel(offBounds.left + kit.dpi(8f), offBounds.centerY())))

        switch.isChecked = true
        val (onImage, onBounds) = render(switch)
        assertTrue("checked gradient is composited with drawable alpha", Color.alpha(
            onImage.getPixel(onBounds.left + kit.dpi(8f), onBounds.centerY() - kit.dpi(8f))) in 120..136)
        val minOnAlpha = (onBounds.centerX() - kit.dpi(12f)..onBounds.centerX() + kit.dpi(12f)).minOf { x ->
            (onBounds.centerY() - kit.dpi(8f)..onBounds.centerY() + kit.dpi(8f)).minOf { y ->
                Color.alpha(onImage.getPixel(x, y))
            }
        }
        assertTrue("DST_OUT cutout remains transparent under drawable alpha", minOnAlpha < 64)
    }
}
