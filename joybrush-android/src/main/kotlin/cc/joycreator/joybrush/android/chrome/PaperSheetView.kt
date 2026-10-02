package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.paper.PaperCatalogue
import cc.joycreator.joybrush.core.paper.PaperPreviews
import cc.joycreator.joybrush.core.paper.PaperState
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import kotlin.math.roundToInt

/** Live, non-modal paper controls. The host owns preview work, the document, and one undo per visit. */
@SuppressLint("ViewConstructor")
class PaperSheetView(
    private val kit: ChromeKit,
    start: Paper,
    private val catalogue: PaperCatalogue,
    private val host: Host,
) : LinearLayout(kit.context) {
    interface Host {
        fun apply(paper: Paper)
        /** Null cancels the shared picker; clearing Tint is the well's long press. */
        fun pickColour(tint: Boolean, current: Int, onPicked: (Int?) -> Unit)
        fun close()
    }

    /** Immutable worker input. Equality includes every visible setting and the crop size. */
    data class PreviewRequest(val key: String, val paper: ResolvedPaper, val size: Int)

    private var current = start
    private var refreshing = false
    private val circles = linkedMapOf<String, Circle>()
    private val sliders = arrayListOf<Slider>()
    private val requests = linkedMapOf<String, PreviewRequest>()
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val tintWell = Circle("Tint", "Tint paper; hold to restore the paper's own colours")
    private val light = toggle("Light", "Light the paper relief from the upper left") { change(current.copy(light = it)) }
    private val export = toggle("Include in export", "Include the visible paper in exported images") {
        change(current.copy(includeInExport = it))
    }

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Paper", 14f), LayoutParams(0, WRAP, 1f))
        head.addView(text("Done", 13f).apply {
            gravity = Gravity.CENTER
            minHeight = kit.dpi(ChromeKit.TOUCH_DP)
            setPadding(kit.dpi(12f), 0, kit.dpi(12f), 0)
            kit.label(this, "Close the Paper sheet")
            setOnClickListener { host.close() }
        }, LayoutParams(WRAP, WRAP))
        addView(head, LayoutParams(MATCH, WRAP))

        val backgrounds = choices("Background")
        for (look in catalogue.looks) addChoice(backgrounds, "look:${look.id}", look.name, "Use ${look.name} paper and its default surface") {
            change(current.copy(lookId = look.id, textureId = look.defaultSurface, tint = null,
                light = null, screenTransparent = false))
        }
        addChoice(backgrounds, "colour", "Colour", "Choose a custom paper background colour") { pick(false) }
        addChoice(backgrounds, "none", "None", "Transparent background; exclude paper from export") {
            change(current.copy(screenTransparent = true, includeInExport = false))
        }

        val surfaces = choices("Surface")
        for (surface in catalogue.surfaces) addChoice(surfaces, "surface:${surface.id}", surface.name, "Feel ${surface.name} under the brush") {
            change(current.copy(textureId = surface.id))
        }
        addChoice(surfaces, "smooth", "Smooth", "Smooth paper; no surface tooth") { change(current.copy(textureId = null)) }

        val tintRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tintRow.addView(text("Tint", 13f), LayoutParams(0, WRAP, 1f))
        tintWell.setOnClickListener { pick(true) }
        tintWell.setOnLongClickListener { change(current.copy(tint = null)); true }
        tintRow.addView(tintWell, LayoutParams(kit.dpi(48f), kit.dpi(44f)))
        list.addView(tintRow, LayoutParams(MATCH, WRAP))
        slider("Show", "Visibility of the paper look and relief", 0, 100,
            { (it.show * 100).roundToInt() }, { current.copy(show = it / 100f) })
        slider("Bite", "How strongly the brush feels the paper surface", 0, 100,
            { (it.bite * 100).roundToInt() }, { current.copy(bite = it / 100f) })
        slider("Scale", "Paper texture scale from 25 to 400 percent", 25, 400,
            { (PaperState.resolve(it, catalogue).scale * 100).roundToInt() }, { current.copy(textureScale = it / 100f) })
        val toggles = LinearLayout(context).apply { orientation = HORIZONTAL }
        toggles.addView(light, LayoutParams(0, WRAP, 0.8f))
        toggles.addView(export, LayoutParams(0, WRAP, 1.5f))
        list.addView(toggles, LayoutParams(MATCH, WRAP))
        // Retain at least half the canvas for test strokes, as the brush Tune sheet does.
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (context.resources.displayMetrics.heightPixels * 0.55f).toInt() - kit.dpi(56f)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap.coerceAtLeast(1), MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; addView(list) }
        addView(scroll, LayoutParams(MATCH, WRAP))
        refresh(start)
    }

    /** Rebind after undo or a picker without reporting a second document edit. */
    fun refresh(paper: Paper) {
        current = paper
        val resolved = PaperState.resolve(paper, catalogue)
        refreshing = true
        for (slider in sliders) {
            val value = slider.read(paper).coerceIn(slider.min, slider.max)
            slider.seek.progress = value - slider.min
            slider.value.text = "$value%"
        }
        light.isChecked = resolved.light
        export.isChecked = !paper.screenTransparent && paper.includeInExport
        export.isEnabled = !paper.screenTransparent
        export.alpha = if (export.isEnabled) 1f else 0.4f
        refreshing = false
        tintWell.fill = resolved.baseArgb
        tintWell.chosen = paper.tint != null
        tintWell.invalidate()
        requests.clear()
        for (look in catalogue.looks) request("look:${look.id}", PaperState.resolve(
            Paper(lookId = look.id, textureId = look.defaultSurface), catalogue))
        request("colour", PaperState.resolve(PaperPreviews.customColour(paper, paper.color), catalogue))
        request("none", resolved.copy(screenTransparent = true))
        for (surface in catalogue.surfaces) request("surface:${surface.id}",
            ResolvedPaper(surface, null, 0xFFD8D8D8.toInt(), 1f, 1f, 1f, light = true))
        request("smooth", ResolvedPaper(null, null, 0xFFD8D8D8.toInt(), 1f, 1f, 1f, light = true))
        for ((key, circle) in circles) {
            circle.chosen = when {
                key.startsWith("look:") -> !paper.screenTransparent && key == "look:${paper.lookId}"
                key == "colour" -> !paper.screenTransparent && paper.lookId == null
                key == "none" -> paper.screenTransparent
                key == "smooth" -> paper.textureId == null
                else -> key == "surface:${paper.textureId}"
            }
            circle.invalidate()
        }
    }

    /** Request only missing crops; unchanged catalogue circles retain their accepted bitmaps. */
    fun previewRequests(): List<PreviewRequest> = requests.values.filter { circles[it.key]?.bitmap == null }

    /** Call on the UI thread. A late worker completion cannot overwrite a newer request. */
    fun setPreview(request: PreviewRequest, bitmap: Bitmap?): Boolean {
        if (requests[request.key] != request) return false
        val circle = circles[request.key] ?: return false
        circle.bitmap = bitmap
        circle.invalidate()
        return true
    }

    private fun request(key: String, resolved: ResolvedPaper) {
        val circle = circles.getValue(key)
        val next = PreviewRequest(key, resolved, kit.dpi(40f).coerceIn(1, PaperPreviews.MAX_SIZE))
        if (circle.request != next) circle.bitmap = null
        circle.request = next
        circle.fill = resolved.baseArgb
        circle.checker = resolved.screenTransparent
        requests[key] = next
    }

    private fun change(paper: Paper) {
        if (paper == current) return
        refresh(paper)
        host.apply(paper)
    }

    private fun pick(tint: Boolean) {
        val colour = if (tint) PaperState.resolve(current, catalogue).baseArgb
            else PaperState.resolve(current.copy(tint = null), catalogue).baseArgb
        host.pickColour(tint, colour) { picked ->
            if (picked != null) {
                val hex = "#%06X".format(picked and 0xFFFFFF)
                change(if (tint) current.copy(tint = hex) else PaperPreviews.customColour(current, hex))
            }
        }
    }

    private fun choices(title: String): LinearLayout {
        list.addView(text(title, 13f), LayoutParams(MATCH, WRAP))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        list.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LayoutParams(MATCH, WRAP))
        return row
    }

    private fun addChoice(row: LinearLayout, key: String, name: String, label: String, action: () -> Unit) {
        val item = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val circle = Circle(name, label).apply { setOnClickListener { action() } }
        circles[key] = circle
        item.addView(circle, LayoutParams(kit.dpi(52f), kit.dpi(48f)))
        item.addView(text(name, 10f).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            kit.label(this, label)
            setOnClickListener { action() }
        }, LayoutParams(MATCH, kit.dpi(28f)))
        row.addView(item, LayoutParams(kit.dpi(66f), WRAP))
    }

    private data class Slider(val seek: SeekBar, val value: TextView, val min: Int, val max: Int, val read: (Paper) -> Int)

    private fun slider(name: String, hint: String, min: Int, max: Int, read: (Paper) -> Int, write: (Int) -> Paper) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(name, 13f), LayoutParams(kit.dpi(44f), WRAP))
        val value = text("", 12f).apply { gravity = Gravity.END }
        val seek = SeekBar(context).apply {
            this.max = max - min
            thumbTintList = ColorStateList.valueOf(kit.p.drawerInk)
            progressTintList = thumbTintList
            progressBackgroundTintList = ColorStateList.valueOf(kit.ink(0.25f))
            kit.label(this, "$name: $hint")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !refreshing) change(write(progress + min))
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        sliders += Slider(seek, value, min, max, read)
        row.addView(seek, LayoutParams(0, kit.dpi(40f), 1f))
        row.addView(value, LayoutParams(kit.dpi(44f), WRAP))
        list.addView(row, LayoutParams(MATCH, WRAP))
    }

    private fun toggle(name: String, hint: String, action: (Boolean) -> Unit): CheckBox = CheckBox(context).apply {
        text = name
        textSize = 12f
        setTextColor(kit.p.drawerInk)
        buttonTintList = ColorStateList.valueOf(kit.p.drawerInk)
        minHeight = kit.dpi(ChromeKit.TOUCH_DP)
        kit.label(this, hint)
        setOnCheckedChangeListener { _, checked -> if (!refreshing) action(checked) }
    }

    private fun text(value: String, size: Float): TextView = TextView(context).apply {
        text = value; textSize = size; setTextColor(kit.p.drawerInk)
    }

    private inner class Circle(name: String, label: String) : View(context) {
        var bitmap: Bitmap? = null
        var request: PreviewRequest? = null
        var fill: Int = kit.p.drawerDim
        var checker = false
        var chosen = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()
        private val box = RectF()

        init { isClickable = true; isFocusable = true; kit.label(this, "$name: $label") }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val radius = minOf(width, height) / 2f - kit.dp(4f)
            box.set(cx - radius, cy - radius, cx + radius, cy + radius)
            clip.reset(); clip.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            paint.style = Paint.Style.FILL; paint.color = fill
            canvas.drawRect(box, paint)
            if (checker) {
                val step = kit.dp(6f)
                var y = box.top; var row = 0
                while (y < box.bottom) {
                    var x = box.left; var col = 0
                    while (x < box.right) {
                        paint.color = if ((row + col) % 2 == 0) 0xFFCCCCCC.toInt() else 0xFFE6E6E6.toInt()
                        canvas.drawRect(x, y, x + step, y + step, paint); x += step; col++
                    }
                    y += step; row++
                }
            }
            bitmap?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it, null, box, paint) }
            canvas.restore()
            if (chosen) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = kit.dp(1.5f); paint.color = kit.p.stateSelected
                canvas.drawCircle(cx, cy, radius + kit.dp(2f), paint)
            }
        }
    }

    companion object {
        const val MAX_WIDTH_DP = 548f
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
