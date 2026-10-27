package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import cc.joycreator.joybrush.core.brush.TuftSpec
import cc.joycreator.joybrush.core.chrome.TuftKnobs

/**
 * "Tune this brush…" (R9): one slider per behaviour of a tuft brush, from [TuftKnobs.all]. It opens as a sheet that
 * does NOT catch the canvas, so the owner moves a slider, draws a test stroke, and moves it again without reopening
 * anything. [onChange] hears every move (`done` when the finger lifts); [onReset] puts the brush back to its file;
 * [onDone] closes the sheet.
 *
 * The sliders look like the rest of the chrome's (JoyBrushActivity.tintSlider): a drawer-ink thumb on a faint track,
 * and every control carries its hover label.
 */
@SuppressLint("ViewConstructor")
class TuftTuningView(
    private val kit: ChromeKit,
    brushName: String,
    start: TuftSpec,
    private val onChange: (TuftSpec, done: Boolean) -> Unit,
    private val onReset: () -> TuftSpec,
    private val onDone: () -> Unit,
    private val onTest: () -> Unit,
) : LinearLayout(kit.context) {

    private var spec = start
    private val rows = ArrayList<Pair<TuftKnobs.Knob, Pair<SeekBar, TextView>>>()

    init {
        orientation = VERTICAL

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Tune $brushName", 14f), LayoutParams(0, WRAP, 1f))
        head.addView(button("Reset", "Put every slider back to how this brush ships") {
            spec = onReset()
            for ((knob, views) in rows) {
                views.first.progress = Math.round(knob.get(spec) * STEPS)
                views.second.text = knob.show(spec)
            }
        })
        head.addView(button("Test", "Draw a sheet of test strokes with this brush. One undo removes them") { onTest() })
        head.addView(button("Done", "Close the brush tuning") { onDone() })
        addView(head, LayoutParams(MATCH, WRAP).apply { setMargins(kit.dpi(6f), 0, 0, kit.dpi(4f)) })

        val list = LinearLayout(context).apply { orientation = VERTICAL }
        for (knob in TuftKnobs.all) list.addView(row(knob))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        // At most ~45% of the screen, so the canvas above stays free to draw test strokes on.
        val maxH = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        addView(object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(scroll, LayoutParams(MATCH, WRAP)) }, LayoutParams(MATCH, WRAP))
    }

    private fun row(knob: TuftKnobs.Knob): LinearLayout {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        val head = LinearLayout(context).apply { orientation = HORIZONTAL }
        val name = text(knob.label, 13f)
        val value = text(knob.show(spec), 12f).apply { typeface = Typeface.MONOSPACE }
        kit.label(name, knob.hint)
        head.addView(name, LayoutParams(0, WRAP, 1f))
        head.addView(value, LayoutParams(WRAP, WRAP))
        val seek = SeekBar(context).apply {
            tint(this)
            max = STEPS
            progress = Math.round(knob.get(spec) * STEPS)
            kit.label(this, "${knob.label}: ${knob.hint}")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    spec = knob.set(spec, progress / STEPS.toFloat())
                    value.text = knob.show(spec)
                    onChange(spec, false)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    // nothing to do
                }

                override fun onStopTrackingTouch(bar: SeekBar) = onChange(spec, true)
            })
        }
        rows.add(knob to (seek to value))
        box.addView(head, LayoutParams(MATCH, WRAP).apply { setMargins(kit.dpi(6f), kit.dpi(2f), kit.dpi(6f), 0) })
        box.addView(seek, LayoutParams(MATCH, kit.dpi(36f)))
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

    private companion object {
        const val STEPS = 100
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
