package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.input.*
import java.util.Locale

enum class UnibrushAssetSlot { sampledTips, dualTip, tipTexture }
data class UnibrushAssetRequest(val slot: UnibrushAssetSlot, val currentPaths: List<String>)

/**
 * DRAFT — uncompiled/untested. Host owns real rendering, frozen image bytes, persistence,
 * naming, publication and the opening long-press gesture. This sheet owns only valid settings.
 * Asset callback returns fully prepared SAFE PNG paths, or null for cancellation; no fake assets.
 */
@SuppressLint("ViewConstructor")
class UnibrushSettingsView(
    private val kit: ChromeKit,
    initial: BrushPreset,
    initialPreference: StabilizerPreference,
    private val preview: View,
    private val onChange: (recipe: BrushPreset, done: Boolean) -> Unit,
    private val onPreferenceChange: (StabilizerPreference) -> Unit,
    private val onNewBrush: (BrushPreset) -> Unit,
    private val onDone: () -> Unit,
    private val onPreviewInput: (pressure: Float, tiltRadians: Float) -> Unit,
    private val chooseAssets: ((UnibrushAssetRequest, (List<String>?) -> Unit) -> Unit)? = null,
    private val editProfilePoints: ((TipProfileSpec, (TipProfileSpec?) -> Unit) -> Unit)? = null,
    private val onChooseSecondaryColour: (() -> Unit)? = null,
) : LinearLayout(kit.context) {
    private enum class Page { Quick, Tip, All }
    private var model = initial
    private var preference = initialPreference
    private var page = Page.Quick
    private var endpointB = false
    private var closed = false
    private var finishing = false
    private var assetRequest = 0L
    private var initialValid = false
    private val rows = ArrayList<UnibrushSheetSlider>()
    private val editors = ArrayList<QuadShapeEditorView>()
    private val curves = ArrayList<UnibrushBezierEditorView>()
    private val refreshers = ArrayList<() -> Unit>()
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val error = TextView(context).apply {
        setTextColor(kit.p.drawerInk); textSize = 12f; visibility = GONE
        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        kit.label(this, "Settings could not be applied. The previous valid brush is kept")
    }
    private val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val pen = PenReadingView(kit)
    private val input = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val scroll = ScrollView(context)

    init {
        orientation = VERTICAL
        setPadding(kit.dpi(8f), kit.dpi(4f), kit.dpi(8f), kit.dpi(4f))
        kit.surface(this, 12f)
        val heading = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("Unibrush", 16f), LayoutParams(0, WRAP, 1f))
        heading.addView(chip("Done", "Finish the current edit and close settings") { close() })
        addView(heading, LayoutParams(MATCH, WRAP))
        require(preview.parent == null) { "Supply an unattached real-engine preview View" }
        kit.label(preview, "Live brush stroke drawn by the painting engine")
        addView(preview, LayoutParams(MATCH, kit.dpi(84f)))
        input.addView(pen, LayoutParams(kit.dpi(64f), kit.dpi(92f)))
        input.addView(UnibrushPreviewPad(kit) { pressure, tilt ->
            pen.show(pressure, tilt, 0f)
            try { onPreviewInput(pressure, tilt) } catch (e: Exception) { showError(e.message ?: "The preview could not be updated") }
        }, LayoutParams(0, kit.dpi(92f), 1f).apply { marginStart = kit.dpi(8f) })
        addView(tabRow, LayoutParams(MATCH, WRAP))
        addView(error, LayoutParams(MATCH, WRAP))
        scroll.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        val footer = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        footer.addView(chip("New brush", "Save these settings as a new mode. The next screen chooses its name") {
            finishEditing()
            if (initialValid) try { onNewBrush(snapshot(model)) } catch (e: Exception) { showError(e.message ?: "The brush could not be saved") }
        })
        addView(footer, LayoutParams(MATCH, WRAP))
        try {
            if (initial.engine != "stamp") throw BrushException("These settings belong to a Unibrush stamp mode")
            model = BrushJson.decodeChecked(BrushJson.encode(initial))
            require(preference.mine.problems().isEmpty()) { preference.mine.problems().joinToString("; ") }
            initialValid = true
        } catch (e: Exception) { showError(e.message ?: "This brush cannot be edited") }
        buildTabs(); rebuild()
    }

    fun currentRecipe(): BrushPreset = snapshot(model)
    fun currentPreference(): StabilizerPreference = preference
    fun showReading(pressure: Float, tiltRadians: Float, orientationRadians: Float) {
        pen.show(pressure, tiltRadians, orientationRadians)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: resources.displayMetrics.heightPixels
        val cap = minOf(available, (resources.displayMetrics.heightPixels * .70f).toInt())
        preview.layoutParams.height = kit.dpi(if (cap < kit.dpi(400f)) 44f else 84f)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY))
    }

    /** Commit child drags before a structural change. No slider View is rebuilt mid-drag. */
    fun finishEditing() {
        if (finishing) return
        finishing = true
        try {
            rows.toList().forEach { it.finishEditing() }
            editors.toList().forEach { it.finishEditing() }
            curves.toList().forEach { it.finishEditing() }
        } finally { finishing = false }
    }

    private fun close() {
        if (closed) return
        finishEditing(); closed = true; assetRequest++
        onDone()
    }

    override fun onDetachedFromWindow() {
        finishEditing(); closed = true; assetRequest++
        super.onDetachedFromWindow()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        for (p in Page.entries) tabRow.addView(chip(p.name, "Show ${p.name.lowercase()} brush settings") {
            if (page != p) { finishEditing(); page = p; buildTabs(); rebuild(resetScroll = true) }
        }.apply { isSelected = page == p }, LayoutParams(0, kit.dpi(44f), 1f))
    }

    private fun rebuild(resetScroll: Boolean = false) {
        assetRequest++ // A/B, tab and optional-panel changes invalidate callbacks bound to the old panel.
        val oldScroll = if (resetScroll) 0 else scroll.scrollY
        body.removeAllViews(); rows.clear(); editors.clear(); curves.clear(); refreshers.clear()
        body.addView(input, LayoutParams(MATCH, kit.dpi(92f)))
        if (!initialValid) { info("The brush has not changed. Close this sheet and choose a valid Unibrush mode."); return }
        when (page) {
            Page.Quick -> { quick(); behaviors(); colorLife() }
            Page.Tip -> { shapeControls(); profileControls("Main tip profile", { it.tip.profile }, { p, s -> p.copy(tip = p.tip.copy(profile = s)) }); fadeControls() }
            Page.All -> {
                quick(); shapeControls(); profileControls("Main tip profile", { it.tip.profile }, { p, s -> p.copy(tip = p.tip.copy(profile = s)) })
                fadeControls(); behaviors(); dynamics(); colorControls(); sampledControls(); dualControls()
                grainControls(tip = true); grainControls(tip = false); endsControls(); stabilizerControls()
            }
        }
        scroll.post { if (!closed) scroll.scrollTo(0, oldScroll) }
    }

    private fun edit(done: Boolean, refresh: Boolean = true, transform: (BrushPreset) -> BrushPreset): Boolean {
        if (closed || !initialValid) return false
        val previous = model
        val next = try {
            val candidate = BrushRecipeEdits.withRequiredVersion(transform(previous))
            val errors = BrushValidate.validate(candidate)
            if (errors.isNotEmpty()) throw BrushException(errors.joinToString("; "))
            // Host refuses before publication if frozen resources/preview preparation fail.
            onChange(candidate, done)
            candidate
        } catch (e: Exception) {
            showError(e.message ?: "These settings could not be applied")
            refreshControls(forceThumb = true)
            return false
        }
        model = next; error.visibility = GONE
        // Publication succeeded. A UI refresh can never roll the model back behind the host.
        if (refresh) refreshControls()
        return true
    }

    private fun refreshControls(forceThumb: Boolean = false) {
        refreshers.toList().forEach { it() }; rows.toList().forEach { it.refresh(forceThumb) }
    }

    private fun structural(transform: (BrushPreset) -> BrushPreset) {
        finishEditing()
        if (edit(true, refresh = false, transform = transform)) rebuild()
    }
    private fun showError(message: String) { error.text = message; kit.label(error, message); error.visibility = VISIBLE }
    private fun snapshot(p: BrushPreset) = BrushJson.decode(BrushJson.encode(p))
    private fun label(value: String, sp: Float = 13f) = TextView(context).apply {
        text = value; textSize = sp; setTextColor(kit.p.drawerInk); kit.label(this, value)
    }
    private fun section(title: String) { body.addView(label(title, 14f), LayoutParams(MATCH, WRAP).apply { topMargin = kit.dpi(12f) }) }
    private fun info(message: String) { body.addView(label(message, 11f), LayoutParams(MATCH, WRAP)) }
    private fun chip(caption: String, description: String, action: () -> Unit) = UnibrushSheetChip(kit, caption, description, action)

    private fun <T> choices(title: String, values: List<T>, caption: (T) -> String = { it.toString() },
                            selected: () -> T?, choose: (T) -> Unit) {
        body.addView(label(title), LayoutParams(MATCH, WRAP))
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        val buttons = values.map { value ->
            chip(caption(value), "$title: ${caption(value)}") { choose(value) }.also { line.addView(it) }
        }
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; addView(line, ViewGroup.LayoutParams(WRAP, kit.dpi(44f)))
        }
        body.addView(horizontal, LayoutParams(MATCH, kit.dpi(44f)))
        val refresh = { buttons.forEachIndexed { i, button -> button.isSelected = selected() == values[i]; button.invalidate() } }
        refreshers += refresh; refresh()
    }
    private fun toggle(title: String, read: () -> Boolean, write: (Boolean) -> Unit) =
        choices(title, listOf(false, true), { if (it) "On" else "Off" }, read) { value -> if (value != read()) write(value) }

    private fun number(title: String, low: Float, high: Float, read: (BrushPreset) -> Float,
                       write: (BrushPreset, Float) -> BrushPreset, logarithmic: Boolean = false,
                       integer: Boolean = false, power: Float = 1f, format: (Float) -> String = ::numberText) {
        val row = UnibrushSheetSlider(kit, title, "Adjust $title", low, high, { read(model) },
            { value, done -> edit(done) { write(it, value) } }, format, logarithmic, integer, power)
        rows += row; body.addView(row, LayoutParams(MATCH, WRAP))
    }

    private fun quick() {
        section("Quick")
        choices("Shape ${if (endpointB) "B" else "A"}", listOf("Circle", "Square", "Trapezoid"), selected = {
            val s = shapeOf(model)
            when {
                model.tip.quad == null -> null
                s.points == QuadShapeSpec().points && s.roundness == 1f -> "Circle"
                s.points == QuadShapeSpec().points && s.roundness == 0f -> "Square"
                s.points == listOf(QuadPoint(-.6f, -1f), QuadPoint(.6f, -1f), QuadPoint(1f, 1f), QuadPoint(-1f, 1f)) && s.roundness == 0f -> "Trapezoid"
                else -> null
            }
        }) { choice ->
            structural { p ->
                val previous = shapeOf(p)
                val shape = when (choice) {
                    "Circle" -> previous.copy(points = QuadShapeSpec().points, roundness = 1f)
                    "Square" -> previous.copy(points = QuadShapeSpec().points, roundness = 0f)
                    else -> previous.copy(points = listOf(QuadPoint(-.6f, -1f), QuadPoint(.6f, -1f), QuadPoint(1f, 1f), QuadPoint(-1f, 1f)), roundness = 0f)
                }
                putShape(p, shape)
            }
        }
        number("Size", .25f, 4096f, { it.size.base }, { p, v -> p.copy(size = p.size.copy(base = v)) }, logarithmic = true)
        number("Softness", 0f, 1f, { 1f - it.tip.hardness.base }, { p, v -> p.copy(tip = p.tip.copy(hardness = p.tip.hardness.copy(base = 1f - v))) }, format = ::percent)
        number("Opacity", 0f, 1f, { it.opacity.base }, { p, v -> p.copy(opacity = p.opacity.copy(base = v)) }, format = ::percent)
        number("Ink per dab", 0f, 1f, { it.flow.base }, { p, v -> p.copy(flow = p.flow.copy(base = v)) }, format = ::percent)
        number("Spacing", .005f, 5f, { it.spacing }, { p, v -> p.copy(spacing = v) }, logarithmic = true)
        choices("Builds up", listOf("wash", "buildup"), { if (it == "wash") "Wash" else "Build up" }, { model.accumulate }) { value -> edit(true) { it.copy(accumulate = value) } }
    }

    private fun behaviors() {
        section("Pen behavior")
        choices("Pressure changes", PressureBehavior.entries, { it.name }, {
            val width = model.size.inputs.any { it.input == BrushInput.pressure }
            val ink = model.flow.inputs.any { it.input == BrushInput.pressure }
            when { width && ink -> PressureBehavior.both; width -> PressureBehavior.width; ink -> PressureBehavior.ink; else -> PressureBehavior.none }
        }) { value -> edit(true) { BrushBehaviors.pressure(it, value) } }
        choices("Tilt changes", TiltBehavior.entries, { readable(it.name) }, { tiltChoice(model) }) { value -> structural { BrushBehaviors.tilt(it, value) } }
        choices("Turns with", TipAngleSource.entries, { readable(it.name) }, { model.tip.angleSource }) { value -> edit(true) { BrushBehaviors.rotation(it, value) } }
        if (model.tip.angleSource == null) info("The saved rotation behavior stays in use until you choose a source.")
        number("Facing offset", -180f, 180f, { it.tip.angle.base }, { p, v -> p.copy(tip = p.tip.copy(angle = p.tip.angle.copy(base = v))) }, format = { "${numberText(it)}°" })
    }

    private fun shapeOf(p: BrushPreset): QuadShapeSpec = if (endpointB) p.tip.quad?.b ?: p.tip.quad?.a ?: QuadShapeSpec() else p.tip.quad?.a ?: QuadShapeSpec()
    private fun putShape(p: BrushPreset, shape: QuadShapeSpec): BrushPreset {
        val q = p.tip.quad ?: TipQuadSpec()
        return p.copy(tip = p.tip.copy(quad = if (endpointB) q.copy(b = shape) else q.copy(a = shape)))
    }
    private fun shapeControls() {
        section("Tip")
        toggle("Four-point shape", { model.tip.quad != null }) { value -> finishEditing(); endpointB = false; structural { p ->
            p.copy(tip = p.tip.copy(quad = if (value) TipQuadSpec() else null))
        } }
        val quad = model.tip.quad
        if (quad == null) { info("The saved tip stays unchanged. Turn on four points to start shaping it."); return }
        choices("Shape endpoint", listOf(false, true), { if (it) "B" else "A" }, { endpointB }) { b ->
            finishEditing()
            if (b && model.tip.quad?.b == null && !edit(true) { p -> p.copy(tip = p.tip.copy(quad = p.tip.quad!!.copy(b = p.tip.quad!!.a))) }) return@choices
            endpointB = b; rebuild()
        }
        body.addView(chip("Remove B", "Remove the second shape and keep shape A") {
            finishEditing(); endpointB = false; structural { p -> p.copy(tip = p.tip.copy(quad = p.tip.quad!!.copy(b = null))) }
        })
        lateinit var editor: QuadShapeEditorView
        editor = QuadShapeEditorView(kit, shapeOf(model), model.tip.angle.base) { shape, angle, done ->
            if (!edit(done) { p -> putShape(p, shape).let { q -> q.copy(tip = q.tip.copy(angle = q.tip.angle.copy(base = angle))) } })
                editor.setShape(shapeOf(model), model.tip.angle.base)
        }
        editors += editor; body.addView(editor, LayoutParams(MATCH, WRAP))
        refreshers += { editor.setShape(shapeOf(model), model.tip.angle.base) }
        number("Corner rounding", 0f, 1f, { shapeOf(it).roundness }, { p, v -> putShape(p, shapeOf(p).copy(roundness = v)) }, format = ::percent)
        number("Shape thinness", -1f, 1f, { shapeOf(it).aspect ?: it.tip.aspect }, { p, v -> putShape(p, shapeOf(p).copy(aspect = v)) })
        body.addView(chip("Inherit thinness", "Use the main tip's thinness for this shape") { edit(true) { p -> putShape(p, shapeOf(p).copy(aspect = null)) } })
        profileControls("Shape ${if (endpointB) "B" else "A"} profile", { shapeOf(it).profile }, { p, v -> putShape(p, shapeOf(p).copy(profile = v)) })
        choices("Morph changes with", listOf("Fixed", "Pressure", "Tilt", "Speed"), selected = {
            val inputs = model.tip.quad!!.morph.inputs
            if (inputs.isEmpty()) "Fixed" else when (inputs.singleOrNull()?.input) {
                BrushInput.pressure -> "Pressure"; BrushInput.tilt -> "Tilt"; BrushInput.speed -> "Speed"; else -> null
            }
        }) { choice -> edit(true) { p ->
            val source = when (choice) { "Pressure" -> BrushInput.pressure; "Tilt" -> BrushInput.tilt; "Speed" -> BrushInput.speed; else -> null }
            val morph = if (source == null) Param(p.tip.quad!!.morph.base) else Param(0f,
                listOf(InputCurve(source, listOf(listOf(0f, 0f), listOf(1f, 1f)))), "add")
            p.copy(tip = p.tip.copy(quad = p.tip.quad!!.copy(morph = morph)))
        } }
        if (quad.morph.inputs.size > 1 || quad.morph.inputs.any { it.input !in listOf(BrushInput.pressure, BrushInput.tilt, BrushInput.speed) })
            info("This mode uses custom morph inputs. They stay intact until you choose a source above.")
        number("Morph A to B", 0f, 1f, { it.tip.quad!!.morph.base }, { p, v -> p.copy(tip = p.tip.copy(quad = p.tip.quad!!.copy(morph = p.tip.quad!!.morph.copy(base = v)))) }, format = ::percent)
    }

    private fun profileControls(title: String, read: (BrushPreset) -> TipProfileSpec?, write: (BrushPreset, TipProfileSpec?) -> BrushPreset) {
        choices(title, listOf<TipProfile?>(null) + TipProfile.entries, { it?.name ?: "Inherit" }, { read(model)?.kind }) { value ->
            structural { p -> write(p, value?.let { (read(p) ?: TipProfileSpec()).copy(kind = it) }) }
        }
        if (read(model)?.kind != TipProfile.custom) return
        info("The current custom falloff stays intact. Drag these handles to replace it with a Bezier falloff.")
        lateinit var curve: UnibrushBezierEditorView
        var accepted = ResponseCurve.LINEAR
        var beforeGesture: TipProfileSpec? = null
        curve = UnibrushBezierEditorView(kit, "Falloff", accepted) { handles, done, cancelled ->
            if (!done && beforeGesture == null) beforeGesture = read(model)
            val replacement = if (cancelled) beforeGesture ?: read(model)!! else {
                val points = (0..32).map { i -> val x = i / 32f; listOf(x, if (i == 32) 0f else 1f - ResponseCurve.eval(handles, x)) }
                TipProfileSpec(TipProfile.custom, points)
            }
            if (edit(done) { p -> write(p, replacement) }) accepted = handles.toList()
            else curve.setHandles(accepted)
            if (done) beforeGesture = null
        }
        curves += curve; body.addView(curve, LayoutParams(MATCH, WRAP))
        editProfilePoints?.let { launch -> body.addView(chip("Edit curve points", "Edit every point of the saved radial falloff") {
            finishEditing(); val ticket = ++assetRequest
            try { launch(read(model)!!) { result -> post {
                if (!closed && ticket == assetRequest && result != null) structural { p -> write(p, result.copy(kind = TipProfile.custom)) }
            } } } catch (e: Exception) { showError(e.message ?: "The curve editor could not be opened") }
        }) }
        if (editProfilePoints == null) info("Individual point editing is unavailable here. Use the Bezier handles to replace this falloff.")
    }

    private fun fadeControls() {
        section("Directional softness")
        toggle("Directional fade", { model.tip.fade != null }) { on -> structural { p -> p.copy(tip = p.tip.copy(fade = if (on) TipFadeSpec() else null)) } }
        if (model.tip.fade == null) return
        number("Fade amount", 0f, 1f, { it.tip.fade!!.amount.base }, { p, v -> p.copy(tip = p.tip.copy(fade = p.tip.fade!!.copy(amount = p.tip.fade!!.amount.copy(base = v)))) }, format = ::percent)
        number("Fade direction", -180f, 180f, { it.tip.fade!!.angle }, { p, v -> p.copy(tip = p.tip.copy(fade = p.tip.fade!!.copy(angle = v))) }, format = { "${numberText(it)}°" })
        choices("Fade follows", FadeFrame.entries, { readable(it.name) }, { model.tip.fade!!.relativeTo }) { frame -> edit(true) { p -> p.copy(tip = p.tip.copy(fade = p.tip.fade!!.copy(relativeTo = frame))) } }
    }

    private fun dynamics() {
        section("Dynamics")
        number("Main thinness", -1f, 1f, { it.tip.aspect }, { p, v -> p.copy(tip = p.tip.copy(aspect = v)) })
        number("Tip taper", 0f, 1f, { it.tip.taper }, { p, v -> p.copy(tip = p.tip.copy(taper = v)) }, format = ::percent)
        number("Minimum width", .25f, 16f, { it.tip.minPx }, { p, v -> p.copy(tip = p.tip.copy(minPx = v)) })
        number("Size jitter", 0f, 1f, { it.sizeJitter }, { p, v -> p.copy(sizeJitter = v) }, format = ::percent)
        number("Angle jitter", 0f, 360f, { it.angleJitter }, { p, v -> p.copy(angleJitter = v) }, format = { "${numberText(it)}°" })
        number("Scatter", 0f, 4f, { it.scatter.amount.base }, { p, v -> p.copy(scatter = p.scatter.copy(amount = p.scatter.amount.copy(base = v))) })
        number("Scatter copies", 1f, 16f, { it.scatter.count.toFloat() }, { p, v -> p.copy(scatter = p.scatter.copy(count = v.toInt())) }, integer = true)
        number("Copy jitter", 0f, 1f, { it.scatter.countJitter }, { p, v -> p.copy(scatter = p.scatter.copy(countJitter = v)) }, format = ::percent)
        toggle("Scatter both axes", { model.scatter.bothAxes }) { value -> edit(true) { it.copy(scatter = it.scatter.copy(bothAxes = value)) } }
        responseCurve("Pressure response", { it.response.pressure }, { p, h -> p.copy(response = p.response.copy(pressure = h)) })
        responseCurve("Tilt response", { it.response.tilt }, { p, h -> p.copy(response = p.response.copy(tilt = h)) })
    }
    private fun responseCurve(title: String, read: (BrushPreset) -> List<Float>, write: (BrushPreset, List<Float>) -> BrushPreset) {
        lateinit var curve: UnibrushBezierEditorView
        curve = UnibrushBezierEditorView(kit, title, read(model)) { h, done, _ -> if (!edit(done) { write(it, h) }) curve.setHandles(read(model)) }
        curves += curve; body.addView(curve, LayoutParams(MATCH, WRAP))
    }

    private fun secondaryColourControl() {
        val choose = onChooseSecondaryColour ?: return
        body.addView(chip("Second colour", "Choose the background colour used by Secondary colour mix") {
            finishEditing()
            if (!closed) try { choose() } catch (error: Exception) { showError(error.message ?: "The second colour could not be chosen") }
        })
    }
    private fun colorLife() {
        section("Colour life"); secondaryColourControl()
        number("Colour life", 0f, 1f, { maxOf((it.color.dynamics?.hueRangeDegrees ?: 0f) / 30f, (it.color.dynamics?.saturationRange ?: 0f) / .2f) }, { p, v ->
            val c = p.color.dynamics ?: ColorDynamicsSpec()
            p.copy(color = p.color.copy(dynamics = c.copy(hueRangeDegrees = v * 30f, saturationRange = v * .2f)))
        }, format = ::percent)
    }
    private fun colorControls() {
        section("Colour"); secondaryColourControl()
        toggle("Colour dynamics", { model.color.dynamics != null }) { on -> structural { p -> p.copy(color = p.color.copy(dynamics = if (on) ColorDynamicsSpec() else null)) } }
        if (model.color.dynamics == null) return
        choices("Colour timing", ColorMode.entries, { readable(it.name) }, { model.color.dynamics!!.mode }) { v -> edit(true) { p -> p.copy(color = p.color.copy(dynamics = p.color.dynamics!!.copy(mode = v))) } }
        fun colorNumber(title: String, lo: Float, hi: Float, get: (ColorDynamicsSpec) -> Float, set: (ColorDynamicsSpec, Float) -> ColorDynamicsSpec) =
            number(title, lo, hi, { get(it.color.dynamics!!) }, { p, v -> p.copy(color = p.color.copy(dynamics = set(p.color.dynamics!!, v))) })
        colorNumber("Hue range", 0f, 180f, { it.hueRangeDegrees }, { c, v -> c.copy(hueRangeDegrees = v) })
        colorNumber("Saturation range", 0f, 1f, { it.saturationRange }, { c, v -> c.copy(saturationRange = v) })
        colorNumber("Value range", 0f, 1f, { it.valueRange }, { c, v -> c.copy(valueRange = v) })
        colorNumber("Secondary colour mix", 0f, 1f, { it.secondaryMix }, { c, v -> c.copy(secondaryMix = v) })
        colorNumber("Pressure hue", -180f, 180f, { it.pressureHueDegrees }, { c, v -> c.copy(pressureHueDegrees = v) })
        colorNumber("Colour drift distance", 1f, 4096f, { it.driftDistancePx }, { c, v -> c.copy(driftDistancePx = v) })
    }

    private fun request(slot: UnibrushAssetSlot, paths: List<String>, accept: (BrushPreset, List<String>) -> BrushPreset) {
        finishEditing()
        val picker = chooseAssets ?: run { showError("Image importing is unavailable here. Existing images are kept."); return }
        val ticket = ++assetRequest
        try { picker(UnibrushAssetRequest(slot, paths.toList())) { result -> post {
            if (!closed && ticket == assetRequest && result != null) structural { p -> accept(p, result.toList()) }
        } } } catch (e: Exception) { showError(e.message ?: "The images could not be loaded") }
    }
    private fun sampledControls() {
        section("Sample images")
        body.addView(chip("Choose PNGs", "Choose one to sixteen ordered PNG tip images") { request(UnibrushAssetSlot.sampledTips, model.tip.sampled?.images ?: emptyList()) { p, paths ->
            if (paths.isEmpty()) throw BrushException("Choose at least one PNG tip")
            p.copy(tip = p.tip.copy(source = "image", image = paths.first(), sampled = (p.tip.sampled ?: SampledTipSpec(paths)).copy(images = paths)))
        } })
        val sampled = model.tip.sampled ?: return
        info("${sampled.images.size} images: ${sampled.images.joinToString { it.substringAfterLast('/') }}")
        body.addView(chip("Use procedural tip", "Remove sampled selection and return to the procedural tip") { structural { p -> p.copy(tip = p.tip.copy(source = "procedural", image = null, sampled = null)) } })
        choices("Image selection", SamplePick.entries, { readable(it.name) }, { model.tip.sampled!!.pick }) { pick -> edit(true) { p -> p.copy(tip = p.tip.copy(sampled = p.tip.sampled!!.copy(pick = pick))) } }
        choices("Image coverage", SampleCoverage.entries, { it.name }, { model.tip.sampled!!.coverage }) { coverage -> edit(true) { p -> p.copy(tip = p.tip.copy(sampled = p.tip.sampled!!.copy(coverage = coverage))) } }
        toggle("Invert image", { model.tip.sampled!!.invert }) { v -> edit(true) { p -> p.copy(tip = p.tip.copy(sampled = p.tip.sampled!!.copy(invert = v))) } }
        number("Image angle jitter", 0f, 360f, { it.tip.sampled!!.rotateJitterDegrees }, { p, v -> p.copy(tip = p.tip.copy(sampled = p.tip.sampled!!.copy(rotateJitterDegrees = v))) })
        number("Image spacing jitter", 0f, 1f, { it.tip.sampled!!.spacingJitter }, { p, v -> p.copy(tip = p.tip.copy(sampled = p.tip.sampled!!.copy(spacingJitter = v))) }, format = ::percent)
    }

    private fun dualControls() {
        section("Dual tip")
        toggle("Secondary tip", { model.tip.dual != null }) { on -> structural { p -> p.copy(tip = p.tip.copy(dual = if (on) DualTipSpec() else null)) } }
        if (model.tip.dual == null) return
        choices("Secondary source", listOf("Shape", "Image"), selected = { if (model.tip.dual!!.image == null) "Shape" else "Image" }) { source ->
            if (source == "Shape") {
                if (model.tip.dual!!.shape == null) structural { p -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(shape = QuadShapeSpec(), image = null))) }
            }
            else request(UnibrushAssetSlot.dualTip, listOfNotNull(model.tip.dual!!.image?.image)) { p, paths ->
                if (paths.size != 1) throw BrushException("A secondary tip needs exactly one PNG")
                p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(shape = null, image = DualImageSpec(paths.single()))))
            }
        }
        if (model.tip.dual!!.shape != null) {
            lateinit var editor: QuadShapeEditorView
            editor = QuadShapeEditorView(kit, model.tip.dual!!.shape!!, 0f, facingEnabled = false) { shape, _, done ->
                if (!edit(done) { p -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(shape = shape.copy(profile = null)))) })
                    editor.setShape(model.tip.dual!!.shape!!, 0f)
            }
            editors += editor; body.addView(editor, LayoutParams(MATCH, WRAP))
            refreshers += { editor.setShape(model.tip.dual!!.shape!!, 0f) }
            number("Secondary rounding", 0f, 1f, { it.tip.dual!!.shape!!.roundness }, { p, v -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(shape = p.tip.dual!!.shape!!.copy(roundness = v)))) }, format = ::percent)
            number("Secondary thinness", -1f, 1f, { it.tip.dual!!.shape!!.aspect ?: 0f }, { p, v -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(shape = p.tip.dual!!.shape!!.copy(aspect = v)))) })
        } else {
            choices("Secondary image coverage", SampleCoverage.entries, { it.name }, { model.tip.dual!!.image!!.coverage }) { v -> edit(true) { p -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(image = p.tip.dual!!.image!!.copy(coverage = v)))) } }
            toggle("Invert secondary image", { model.tip.dual!!.image!!.invert }) { v -> edit(true) { p -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(image = p.tip.dual!!.image!!.copy(invert = v)))) } }
        }
        choices("Dual mask operation", DualMode.entries, { it.name }, { model.tip.dual!!.mode }) { v -> edit(true) { p -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(mode = v))) } }
        number("Secondary scale", .05f, 2f, { it.tip.dual!!.scale }, { p, v -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(scale = v))) })
        number("Secondary spacing", .125f, 4f, { it.tip.dual!!.spacing }, { p, v -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(spacing = v))) })
        number("Secondary scatter", 0f, 1f, { it.tip.dual!!.scatter }, { p, v -> p.copy(tip = p.tip.copy(dual = p.tip.dual!!.copy(scatter = v))) }, format = ::percent)
    }

    private fun grainControls(tip: Boolean) {
        section(if (tip) "Tip texture" else "Stroke texture")
        fun get(p: BrushPreset) = if (tip) p.tipTexture else p.paperGrain
        fun put(p: BrushPreset, g: GrainSpec) = if (tip) p.copy(tipTexture = g) else p.copy(paperGrain = g)
        toggle("${if (tip) "Tip" else "Stroke"} texture", { get(model).enabled }) { v -> structural { p ->
            val q = put(p, get(p).copy(enabled = v))
            // With Paper at 0 a stroke texture shows nothing: switched on, it starts at half, catching the tooth by direction.
            if (!tip && v && q.paper.influence == 0f) q.copy(paper = q.paper.copy(influence = .5f, directional = maxOf(q.paper.directional, .6f))) else q
        } }
        if (!get(model).enabled) return
        if (tip) {
            choices("Texture source", listOf("cloud", "image"), { if (it == "cloud") "Cloud" else "Image" }, { get(model).source }) { source ->
                if (source == "cloud") edit(true) { p -> put(p, get(p).copy(source = source)) }
                else request(UnibrushAssetSlot.tipTexture, listOfNotNull(get(model).image)) { p, paths ->
                    if (paths.size != 1) throw BrushException("Tip texture needs exactly one PNG")
                    put(p, get(p).copy(source = "image", image = paths.single()))
                }
            }
            number("Texture scale", .05f, 64f, { get(it).scale }, { p, v -> put(p, get(p).copy(scale = v)) }, logarithmic = true)
        } else info("The document paper supplies the stroke texture image and scale.")
        number("Texture depth", 0f, 1f, { get(it).depth.base }, { p, v -> put(p, get(p).copy(depth = get(p).depth.copy(base = v))) }, format = ::percent)
        number("Texture edge", 0f, 1f, { get(it).edge }, { p, v -> put(p, get(p).copy(edge = v)) }, format = ::percent)
        number("Texture tilt gradient", -4f, 4f, { get(it).tiltGradient }, { p, v -> put(p, get(p).copy(tiltGradient = v)) })
        number("Texture radial gradient", 0f, 4f, { get(it).radial }, { p, v -> put(p, get(p).copy(radial = v)) })
        if (tip) return
        // How the stroke meets the document's paper (the brush sheet's Paper / Direction / Wet, same words and range).
        fun paper(title: String, read: (PaperResponse) -> Float, write: (PaperResponse, Float) -> PaperResponse) =
            number(title, 0f, 1f, { read(it.paper) }, { p, v -> p.copy(paper = write(p.paper, v)) }, format = ::percent)
        paper("Paper", { it.influence }) { r, v -> r.copy(influence = v) }
        paper("Direction", { it.directional }) { r, v -> r.copy(directional = v) }
        paper("Wet", { it.wet }) { r, v -> r.copy(wet = v) }
    }

    private fun endsControls() {
        section("Stroke ends")
        toggle("Stroke-end shaping", { model.line != null }) { on -> structural { it.copy(line = if (on) LineStyleSpec(taperOut = 3f) else null) } }
        if (model.line == null) return
        fun lineNumber(title: String, lo: Float, hi: Float, get: (LineStyleSpec) -> Float, put: (LineStyleSpec, Float) -> LineStyleSpec, power: Float = 1f) =
            number(title, lo, hi, { get(it.line!!) }, { p, v -> p.copy(line = put(p.line!!, v)) }, power = power)
        lineNumber("Start taper · diameters", 0f, 1000f, { it.taperIn }, { l, v -> l.copy(taperIn = v) }, power = 3f)
        lineNumber("End taper · diameters", 0f, 1000f, { it.taperOut }, { l, v -> l.copy(taperOut = v) }, power = 3f)
        lineNumber("End width", 0f, 1f, { it.taperTip }, { l, v -> l.copy(taperTip = v) })
        lineNumber("Taper shape", .25f, 4f, { it.taperShape }, { l, v -> l.copy(taperShape = v) })
        lineNumber("Dash length", 0f, 1000f, { it.dashOn }, { l, v -> l.copy(dashOn = v) }, power = 3f)
        lineNumber("Dash gap", 0f, 1000f, { it.dashOff }, { l, v -> l.copy(dashOff = v) }, power = 3f)
    }

    private fun stabilizerControls() {
        section("Stabilizer")
        choices("Whose settings", StabilizerPolicy.entries, { if (it == StabilizerPolicy.brushSuggestion) "Each brush" else "Always mine" }, { preference.policy }) { value ->
            finishEditing(); changePreference(preference.copy(policy = value)); rebuild()
        }
        if (preference.policy == StabilizerPolicy.brushSuggestion) info("Editing this mode's suggestion. Actual settings freeze when the next stroke starts.")
        else info("Editing your canvas stabilizer. This overrides each brush's suggestion.")
        fun current(): StabilizerSpec = if (preference.policy == StabilizerPolicy.alwaysMine) preference.mine
            else model.stabilizer ?: StabilizerSpec(strength = model.smoothing)
        fun update(done: Boolean, transform: (StabilizerSpec) -> StabilizerSpec): Boolean {
            val next = transform(current())
            return if (preference.policy == StabilizerPolicy.brushSuggestion) edit(done) { it.copy(stabilizer = next) }
            else changePreference(preference.copy(mine = next))
        }
        choices("Mode", StabilizerMode.entries, { readable(it.name) }, { current().mode }) { value -> update(true) { it.copy(mode = value) } }
        val explanation = label("", 11f)
        body.addView(explanation, LayoutParams(MATCH, WRAP))
        val explain = {
            explanation.text = when (current().mode) {
                StabilizerMode.off -> "The stroke follows your pen directly."
                StabilizerMode.smooth -> "Smooth bends while keeping the stroke responsive."
                StabilizerMode.pulledString -> "The tip trails behind a string; the stroke ends where the tip reaches."
                StabilizerMode.movingAverage -> "Average the recent pen positions over the sample window."
                StabilizerMode.catchUpOnLift -> "The tip trails behind a string, then reaches your exact lift point when you lift."
            }
            kit.label(explanation, explanation.text.toString())
        }
        refreshers += explain; explain()
        fun setting(title: String, lo: Float, hi: Float, get: (StabilizerSpec) -> Float, put: (StabilizerSpec, Float) -> StabilizerSpec, integer: Boolean = false) {
            val row = UnibrushSheetSlider(kit, title, "Adjust $title", lo, hi, { get(current()) },
                { v, done -> update(done) { put(it, v) } }, ::numberText, integer = integer)
            rows += row; body.addView(row, LayoutParams(MATCH, WRAP))
        }
        setting("Strength", 0f, 1f, { it.strength }, { s, v -> s.copy(strength = v) })
        setting("String radius · screen px", 0f, 512f, { it.radiusPx }, { s, v -> s.copy(radiusPx = v) })
        setting("Moving-average window", 1f, 256f, { it.windowSize.toFloat() }, { s, v -> s.copy(windowSize = v.toInt()) }, integer = true)
        if (preference.policy == StabilizerPolicy.brushSuggestion) body.addView(chip("Use saved smoothing", "Remove this mode's explicit suggestion and use its saved smooth amount") { edit(true) { it.copy(stabilizer = null) } })
    }
    private fun changePreference(next: StabilizerPreference): Boolean {
        try {
            require(next.mine.problems().isEmpty()) { next.mine.problems().joinToString("; ") }
            onPreferenceChange(next)
        } catch (e: Exception) {
            showError(e.message ?: "The stabilizer settings could not be applied"); refreshControls(forceThumb = true); return false
        }
        preference = next; error.visibility = GONE; refreshControls(); return true
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        fun percent(value: Float) = "${Math.round(value * 100)}%"
        fun numberText(value: Float) = String.format(Locale.US, if (value == value.toInt().toFloat()) "%.0f" else "%.2f", value)
        fun readable(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
        fun tiltChoice(p: BrushPreset): TiltBehavior = when {
            p.tip.angleSource == TipAngleSource.penAzimuth -> TiltBehavior.angle
            p.tip.fade?.amount?.inputs?.any { it.input == BrushInput.tilt } == true -> TiltBehavior.sideFade
            p.tip.aspectDynamics?.inputs?.any { it.input == BrushInput.tilt } == true -> TiltBehavior.flatten
            p.size.inputs.any { it.input == BrushInput.tilt } -> TiltBehavior.size
            else -> TiltBehavior.none
        }
    }
}
