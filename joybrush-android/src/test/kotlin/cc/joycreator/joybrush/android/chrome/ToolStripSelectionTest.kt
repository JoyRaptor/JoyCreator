package cc.joycreator.joybrush.android.chrome

import android.animation.ValueAnimator
import android.app.Activity
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.chrome.ToolSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ToolStripSelectionTest {
    private class Host : ToolStripView.Host {
        override fun toolTapped(slot: ToolSlot, anchor: View) {}
        override fun sizeNow() = 12f
        override fun opacityNow() = 1f
        override fun zoom() = 1f
        override fun sizeDragged(px: Float, done: Boolean) {}
        override fun opacityDragged(value: Float, done: Boolean) {}
        override fun sizeTapped(anchor: View) {}
        override fun opacityTapped(anchor: View) {}
        override fun stripDropped(cx: Float, cy: Float) {}
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun strip(): ToolStripView = ToolStripView(ChromeKit(RuntimeEnvironment.getApplication()), Host())

    private fun animator(strip: ToolStripView): ValueAnimator? {
        val field = ToolStripView::class.java.getDeclaredField("selectionPulse")
        field.isAccessible = true
        return field.get(strip) as? ValueAnimator
    }

    @Test fun restyleHighlightsTheThreeSelectionTargetsAndSurvivesToolRefreshes() {
        val strip = strip()
        strip.showTools(setOf(ToolSlot.BRUSH, ToolSlot.SMUDGE, ToolSlot.ERASER), ToolSlot.BRUSH)
        strip.showSelect(shown = true, chosen = false)
        val all = descendants(strip)
        val brush = all.first { it.contentDescription?.toString()?.startsWith("Brush") == true }
        val size = all.first { it.contentDescription?.toString()?.startsWith("Size") == true }
        val select = all.first { it.contentDescription?.toString()?.startsWith("Select") == true }

        strip.setSelectionRestyle(on = true, pulse = false)
        assertTrue(size.isActivated)
        assertTrue(strip.swatch.isActivated)
        assertTrue(brush.isActivated)
        assertTrue(brush.isSelected)
        assertTrue(size.contentDescription.toString().contains("Selected line weight"))
        assertTrue(brush.contentDescription.toString().contains("selected lines’ brush"))
        assertTrue(strip.swatch.contentDescription.toString().contains("selected lines’ colour"))
        assertTrue(select.contentDescription.toString().contains("Settings"))

        strip.showTools(setOf(ToolSlot.BRUSH, ToolSlot.SMUDGE, ToolSlot.ERASER), ToolSlot.ERASER)
        assertTrue(brush.isActivated)
        assertFalse(brush.isSelected)
        assertTrue(all.first { it.contentDescription?.toString()?.startsWith("Eraser") == true }.isSelected)
        strip.setSelectionRestyle(on = false, pulse = false)
        assertFalse(size.isActivated)
        assertFalse(strip.swatch.isActivated)
        assertFalse(brush.isActivated)
        assertTrue(size.contentDescription.toString().startsWith("Size"))
        assertTrue(brush.contentDescription.toString().contains("tap again for the brushes"))
    }

    @Test fun pulseIsFiniteRefreshDoesNotRestartAndOffCancelsIt() {
        val strip = strip()
        strip.setSelectionRestyle(on = true, pulse = true)
        val running = animator(strip)
        assertNotNull(running)
        assertEquals(1200L, running!!.duration)
        assertEquals(0, running.repeatCount)

        strip.setSelectionRestyle(on = true, pulse = false)
        assertSame(running, animator(strip))
        strip.setSelectionRestyle(on = false, pulse = false)
        assertNull(animator(strip))
        assertFalse(running.isStarted)
    }

    @Test fun pulseCompletesTwoRingCyclesWithoutChangingTheSwatch() {
        val strip = strip()
        strip.showValues(18f, 0xFF123456.toInt(), 0.6f)
        strip.setSelectionRestyle(on = true, pulse = true)
        val running = animator(strip)!!
        fun number(name: String): Float {
            val field = strip.swatch.javaClass.getDeclaredField(name)
            field.isAccessible = true
            return field.getFloat(strip.swatch)
        }
        for (time in listOf(300L, 900L)) {
            running.currentPlayTime = time
            assertEquals(1.15f, number("ringScale"), 0.001f)
            assertEquals(0.4f, number("ringAlpha"), 0.001f)
        }
        running.end()
        assertNull(animator(strip))
        assertEquals(1f, number("ringScale"), 0.001f)
        assertEquals(1f, number("ringAlpha"), 0.001f)
        assertTrue(strip.swatch.isActivated)
        val colour = strip.swatch.javaClass.getDeclaredField("argb").apply { isAccessible = true }
        assertEquals(0xFF123456.toInt(), colour.getInt(strip.swatch))
    }

    @Test fun reducedMotionKeepsSelectionHighlightedWithoutStartingPulse() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getString(resolver, key)
        try {
            Settings.Global.putFloat(resolver, key, 0f)
            val strip = strip()
            strip.setSelectionRestyle(on = true, pulse = true)
            assertTrue(strip.swatch.isActivated)
            assertNull(animator(strip))
        } finally { Settings.Global.putString(resolver, key, previous) }
    }

    @Test fun detachCancelsPulseAndClearsSelectionHighlights() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val strip = ToolStripView(ChromeKit(activity), Host())
        root.addView(strip)
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        strip.setSelectionRestyle(on = true, pulse = true)
        val brush = descendants(strip).first { it.contentDescription?.toString()?.startsWith("Brush") == true }
        assertTrue(brush.isActivated)

        activity.setContentView(FrameLayout(activity))
        assertFalse(brush.isActivated)
        assertNull(animator(strip))
    }
}
