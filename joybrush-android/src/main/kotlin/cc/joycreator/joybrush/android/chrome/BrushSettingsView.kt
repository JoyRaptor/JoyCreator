package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.BrushKnobs
import cc.joycreator.joybrush.core.chrome.BrushTuning

/**
 * A brush's advanced settings (owner, 2026-09-30): opened by holding a brush in the drawer, or from the ⋯ menu. A live
 * [preview] on top — the brush's own strokes, redrawn as a slider moves — and one slider per thing the brush can do
 * ([BrushKnobs]). The sheet does NOT catch the canvas, so a test stroke on the drawing is always one move away.
 *
 * [base] is the brush as it ships; [start] the saved slider positions. [onChange] hears every move with the whole set of
 * positions (`done` when the finger lifts), [onReset] forgets them, [onTest] draws the test sheet on the drawing, [onDone]
 * closes the sheet. The sliders look like the rest of the chrome's (JoyBrushActivity.tintSlider), and every control
 * carries its hover label.
 */
@SuppressLint("ViewConstructor")
class BrushSettingsView(
    private val kit: ChromeKit,
    private val base: BrushPreset,
    start: Map<String, Float>,
    preview: View,
    private val onChange: (positions: Map<String, Float>, done: Boolean) -> Unit,
    private val onReset: () -> Unit,
    private val onTest: () -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(kit.context) {

    private var positions = start
    private val rows = ArrayList<Triple<BrushKnobs.Knob, SeekBar, TextView>>()
    private val penDot = PenReadingView(kit)
    private val pressureCurve = CurveEditorView(kit, "Pressure", tuned().response.pressure) { h, done -> curveMoved("pressure", h, done) }
    private val tiltCurve = CurveEditorView(kit, "Tilt", tuned().response.tilt) { h, done -> curveMoved("tilt", h, done) }

    /**
     * What the pen reads now, from the drawing or the preview: the pen dot, and the markers on the two curves. [tilt] in
     * radians from upright, [orientation] as Android's AXIS_ORIENTATION.
     */
    fun showReading(pressure: Float, tilt: Float, orientation: Float) {
        penDot.show(pressure, tilt, orientation)
        pressureCurve.showReading(if (pressure > 0f) pressure else Float.NaN)
        tiltCurve.showReading(if (tilt.isFinite()) tilt / (Math.PI.toFloat() / 2f) else Float.NaN)
    }

    private fun curveMoved(curve: String, handles: List<Float>, done: Boolean) {
        val keys = BrushKnobs.curveKeys(curve)
        positions = positions + keys.zip(handles)
        onChange(positions, done)
    }

    /** The brush as the sliders have it now. */
    private fun tuned(): BrushPreset = BrushTuning.apply(base, mapOf(base.id to positions))

    init {
        orientation = VERTICAL

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(base.name, 14f), LayoutParams(0, WRAP, 1f))
        head.addView(button("Reset", "Put every slider back to how this brush ships") {
            positions = emptyMap()
            onReset()
            refresh()
        })
        head.addView(button("Test", "Draw a sheet of test strokes on the drawing with this brush. One undo removes them") { onTest() })
        head.addView(button("Done", "Close the brush settings") { onDone() })
        addView(head, LayoutParams(MATCH, WRAP).apply { setMargins(kit.dpi(6f), 0, 0, kit.dpi(2f)) })

        kit.label(preview, "Live preview of ${base.name}: the same strokes, redrawn as you move a slider. You can draw on it too")
        addView(preview, LayoutParams(MATCH, kit.dpi(PREVIEW_DP)).apply { setMargins(kit.dpi(4f), 0, kit.dpi(4f), kit.dpi(4f)) })

        val list = LinearLayout(context).apply { orientation = VERTICAL }
        // How the brush hears the pen: what it reads now, and the two curves that shape it.
        val hearing = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP }
        hearing.addView(penDot, LayoutParams(0, WRAP, 0.8f))
        hearing.addView(pressureCurve, LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dpi(6f) })
        hearing.addView(tiltCurve, LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dpi(6f) })
        list.addView(hearing, LayoutParams(MATCH, WRAP).apply { setMargins(kit.dpi(4f), 0, kit.dpi(4f), kit.dpi(6f)) })
        for (knob in BrushKnobs.forBrush(base)) if (knob.slider) list.addView(row(knob))
        val scroll = ScrollView(context).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) }
        // Preview + sliders take at most ~55% of the screen, so the drawing above stays free for test strokes.
        val maxH = (context.resources.displayMetrics.heightPixels * 0.55f).toInt() - kit.dpi(PREVIEW_DP + 48f)
        addView(object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(scroll, LayoutParams(MATCH, WRAP)) }, LayoutParams(MATCH, WRAP))
    }

    private fun refresh() {
        val p = tuned()
        pressureCurve.setHandles(p.response.pressure)
        tiltCurve.setHandles(p.response.tilt)
        for ((knob, seek, value) in rows) {
            seek.progress = Math.round(knob.get(p) * STEPS)
            value.text = knob.show(p)
        }
    }

    private fun row(knob: BrushKnobs.Knob): LinearLayout {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        val head = LinearLayout(context).apply { orientation = HORIZONTAL }
        val now = tuned()
        val name = text(knob.label, 13f)
        val value = text(knob.show(now), 12f).apply { typeface = Typeface.MONOSPACE }
        kit.label(name, knob.hint)
        head.addView(name, LayoutParams(0, WRAP, 1f))
        head.addView(value, LayoutParams(WRAP, WRAP))
        val seek = SeekBar(context).apply {
            tint(this)
            max = STEPS
            progress = Math.round(knob.get(now) * STEPS)
            kit.label(this, "${knob.label}: ${knob.hint}")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    positions = positions + (knob.key to progress / STEPS.toFloat())
                    value.text = knob.show(tuned())
                    onChange(positions, false)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    // nothing to do
                }

                override fun onStopTrackingTouch(bar: SeekBar) = onChange(positions, true)
            })
        }
        rows.add(Triple(knob, seek, value))
        box.addView(head, LayoutParams(MATCH, WRAP).apply { setMargins(kit.dpi(6f), kit.dpi(2f), kit.dpi(6f), 0) })
        box.addView(seek, LayoutParams(MATCH, kit.dpi(34f)))
        return box
    }

    private fun text(s: String, sp: Float): TextView = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(kit.p.drawerInk)
    }

    private fun button(s: String, label: String, action: () -> Unit): TextView = text(s, 13f).apply {
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

    /** The chrome's slider: a drawer-ink thumb on a faint track, never the platform's accent. */
    private fun tint(s: SeekBar) {
        val ink = ColorStateList.valueOf(kit.p.drawerInk)
        s.thumbTintList = ink
        s.progressTintList = ink
        s.progressBackgroundTintList = ColorStateList.valueOf(kit.ink(0.25f))
    }

    companion object {
        /** The live preview's height, dp. */
        const val PREVIEW_DP = 130f
        private const val STEPS = 100
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
