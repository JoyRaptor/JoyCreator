package cc.joycreator.joybrush.android

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.BrushSettingsView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.android.select.BrushModeBadge
import cc.joycreator.joybrush.android.select.testBrush
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.brush.BrushPreset
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Activity binding coverage through openSettings; preview surfaces are not rendered or used as EGL evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrushLineActivityBindingTest {
    private data class Fixture(
        val activity: JoyBrushActivity,
        val root: FrameLayout,
        val canvas: JbCanvasView,
        val popovers: Popovers,
        val prefs: SharedPreferences,
    )

    private val fixtures = ArrayList<Fixture>()

    @After fun cleanUp() {
        fixtures.forEach { fixture ->
            val surfaces = (descendants(fixture.root).filterIsInstance<JbCanvasView>() + fixture.canvas).distinct()
            fixture.popovers.close()
            surfaces.forEach { it.onPause() }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun fixture(brush: BrushPreset, initialMode: Boolean): Fixture {
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        val prefs = activity.getSharedPreferences("joybrush_chrome", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit().putBoolean(PER_BRUSH_PREFIX + brush.id, initialMode).commit()
        val canvas = JbCanvasView(activity)
        canvas.preset = brush
        canvas.editableLines = initialMode
        val root = FrameLayout(activity)
        root.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        layout(root)
        val kit = ChromeKit(activity)
        val popovers = Popovers(kit, root)
        setField(activity, "canvas", canvas)
        setField(activity, "kit", kit)
        setField(activity, "popovers", popovers)
        setField(activity, "prefs", prefs)
        return Fixture(activity, root, canvas, popovers, prefs).also { fixtures += it }
    }

    private fun layout(root: FrameLayout) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 500, 800)
    }

    private fun openSettings(fixture: Fixture, brush: BrushPreset): BrushSettingsView {
        JoyBrushActivity::class.java.getDeclaredMethod("openSettings", BrushPreset::class.java)
            .also { it.isAccessible = true }.invoke(fixture.activity, brush)
        layout(fixture.root)
        return descendants(fixture.root).filterIsInstance<BrushSettingsView>().single()
    }

    private fun setField(target: Any, name: String, value: Any) {
        JoyBrushActivity::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
    }

    @Test fun pixelsAlwaysBrushIgnoresRawOnFlagAndShowsD22WithoutSwitch() {
        val pixelsBrush = testBrush("media-raw-on", name = "Media", engine = "media")
        val fixture = fixture(pixelsBrush, initialMode = false)
        assertTrue(fixture.prefs.edit().putBoolean(PER_BRUSH_PREFIX + pixelsBrush.id, true).commit())

        val sheet = openSettings(fixture, pixelsBrush)

        assertTrue(descendants(sheet).filterIsInstance<Switch>().isEmpty())
        assertEquals(1, descendants(sheet).filterIsInstance<TextView>()
            .count { it.text.toString() == BrushModeBadge.PIXELS_LABEL })
        assertTrue("raw stale ON data must not enable a pixels-always brush",
            !BrushLinePreferences(fixture.prefs).enabled(pixelsBrush))
        assertFalse(fixture.canvas.editableLines)
    }

    @Test fun eligibleSwitchWritesItsBrushKeyAndUpdatesTheCanvasMode() {
        val brush = testBrush("activity-switchable", name = "Switchable ink")
        val fixture = fixture(brush, initialMode = false)
        val sheet = openSettings(fixture, brush)
        val switch = descendants(sheet).filterIsInstance<Switch>().single()

        assertFalse(switch.isChecked)
        switch.performClick()

        assertTrue(switch.isChecked)
        assertTrue(fixture.canvas.editableLines)
        assertTrue(fixture.prefs.getBoolean(PER_BRUSH_PREFIX + brush.id, false))
        assertFalse("the legacy global preference must not be written", fixture.prefs.contains(LEGACY_GLOBAL_KEY))
    }

    @Test fun staleSheetCallbackCannotWriteOrChangeTheNewBrushMode() {
        val oldBrush = testBrush("old-sheet-brush", name = "Old brush")
        val newBrush = testBrush("new-active-brush", name = "New brush")
        val fixture = fixture(oldBrush, initialMode = false)
        fixture.prefs.edit().putBoolean(PER_BRUSH_PREFIX + newBrush.id, false).commit()
        val sheet = openSettings(fixture, oldBrush)
        val switch = descendants(sheet).filterIsInstance<Switch>().single()
        assertFalse(switch.isChecked)

        fixture.canvas.preset = newBrush
        fixture.canvas.editableLines = false
        switch.performClick()

        assertTrue("the stale sheet callback must not change the current canvas mode", !fixture.canvas.editableLines)
        assertFalse("the old brush flag stays as it was", fixture.prefs.getBoolean(PER_BRUSH_PREFIX + oldBrush.id, true))
        assertFalse("the new brush's preference is not accidentally overwritten", fixture.prefs.getBoolean(PER_BRUSH_PREFIX + newBrush.id, true))
        assertFalse(fixture.prefs.contains(LEGACY_GLOBAL_KEY))
    }

    private companion object {
        const val PER_BRUSH_PREFIX = "joybrush.brushMakesLines."
        const val LEGACY_GLOBAL_KEY = "joybrush.editableLines"
    }
}
