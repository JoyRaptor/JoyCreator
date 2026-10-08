package cc.joycreator.joybrush.android.board

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.ChromeKit
import com.fadcam.ui.faditor.tools.DrawerFill
import com.fadcam.ui.type.Type
import java.util.function.Consumer
import kotlin.math.min

/** Board forms share the app's live see-through drawer fill and retain invalid input for correction. */
class BoardPanels(private val context: Context) {
    private val kit = ChromeKit(context)
    private var current: Dialog? = null

    fun close() { current?.dismiss(); current = null }

    fun menu(title: String, labels: List<String>, choose: (Int) -> Unit) {
        val (dialog, panel, content) = panel(title)
        labels.forEachIndexed { index, label ->
            content.addView(action(label) { dialog.dismiss(); choose(index) }, row())
        }
        panel.addView(action("Cancel") { dialog.cancel() }, row())
        show(dialog)
    }

    fun confirm(title: String, message: String, action: String, done: () -> Unit) {
        val (dialog, panel, content) = panel(title)
        content.addView(label(message, 14f).apply { setPadding(0, kit.dpi(8f), 0, kit.dpi(12f)) })
        actions(panel, dialog, action) { dialog.dismiss(); done() }
        show(dialog)
    }

    fun form(title: String, fields: List<Pair<String, String>>, done: (List<String>) -> Unit) {
        formValidated(title,fields,{ null },done)
    }

    fun formValidated(title: String,fields: List<Pair<String,String>>,validate: (List<String>) -> String?,done: (List<String>) -> Unit) {
        val (dialog, panel, content) = panel(title)
        val inputs = fields.map { (name, initial) ->
            content.addView(label(name, 12f).apply { setPadding(0, kit.dpi(8f), 0, 0) })
            EditText(context).apply {
                contentDescription = name
                hint = name
                setSingleLine()
                setSelectAllOnFocus(true)
                setTextColor(kit.p.drawerInk)
                setHintTextColor(kit.ink(.6f))
                textSize = 16f
                typeface = if(name == "Name") Type.body(context,500) else Type.mono(context,500)
                inputType = if (name == "Name") InputType.TYPE_CLASS_TEXT else
                    InputType.TYPE_CLASS_NUMBER or if (name == "X" || name == "Y") InputType.TYPE_NUMBER_FLAG_SIGNED else 0
                backgroundTintList = android.content.res.ColorStateList.valueOf(kit.ink(.7f))
                minimumHeight = kit.dpi(44f)
                setText(initial)
                content.addView(this, row())
            }
        }
        actions(panel, dialog, "Apply") {
            val values = inputs.map { it.text.toString().trim() }
            var firstInvalid: EditText? = null
            fields.forEachIndexed { index, (name, _) ->
                val value = values[index]
                val number = value.toIntOrNull()
                val error = when {
                    name == "Name" && value.isEmpty() -> "Enter a name"
                    name == "Name" -> null
                    number == null -> "Enter a whole number"
                    name != "X" && name != "Y" && number <= 0 -> "Enter a number greater than zero"
                    else -> null
                }
                inputs[index].error = error
                if (error != null && firstInvalid == null) firstInvalid = inputs[index]
            }
            val customError=if(firstInvalid == null) validate(values) else null
            if (firstInvalid != null) {
                firstInvalid!!.requestFocus()
            } else if(customError != null) {
                inputs.firstOrNull()?.let { it.error=customError; it.requestFocus() }
            } else {
                dialog.dismiss()
                done(values)
            }
        }
        show(dialog)
    }

    private data class Content(val dialog: Dialog, val panel: LinearLayout, val content: LinearLayout)

    private fun panel(title: String): Content {
        close()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dpi(14f), kit.dpi(10f), kit.dpi(14f), kit.dpi(10f))
            kit.surface(this, 20f)
            if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = title
        }
        panel.addView(label(title, 18f).apply {
            typeface = Type.display(context, 800)
            setPadding(0, 0, 0, kit.dpi(8f))
        })
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // AdjustResize supplies the keyboard-reduced height; also bound long menus
                // before the window first measures itself. The actions remain outside the scroll.
                val limit = (resources.displayMetrics.heightPixels - kit.dpi(180f)).coerceAtLeast(kit.dpi(80f))
                val supplied = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                    else min(limit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(supplied, MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; addView(content) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(panel)
        current = dialog
        dialog.setOnDismissListener { if (current === dialog) current = null }
        return Content(dialog, panel, content)
    }

    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(kit.p.drawerInk)
        typeface = Type.body(context, 500)
    }

    private fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun action(text: String, run: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(kit.p.drawerInk)
        textSize = 14f
        typeface = Type.body(context, 600)
        minimumHeight = kit.dpi(44f)
        minHeight = kit.dpi(44f)
        background = GradientDrawable().apply { cornerRadius = kit.dp(12f); setColor(kit.ink(.1f)) }
        kit.label(this, text)
        setOnClickListener { run() }
    }

    private fun actions(panel: LinearLayout, dialog: Dialog, positive: String, run: () -> Unit) {
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dpi(10f), 0, 0) }
        buttons.addView(action("Cancel") { dialog.cancel() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(action(positive, run), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = kit.dpi(8f) })
        panel.addView(buttons)
    }

    private fun show(dialog: Dialog) {
        dialog.show()
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout(min(kit.dpi(380f), context.resources.displayMetrics.widthPixels - kit.dpi(32f)), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (Build.VERSION.SDK_INT >= 31) installFrost(dialog, window)
    }

    @android.annotation.TargetApi(31)
    private fun installFrost(dialog: Dialog, window: Window) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = context.getSharedPreferences("studio_drawer", Context.MODE_PRIVATE)
        val update = { window.setBackgroundBlurRadius(if (manager.isCrossWindowBlurEnabled && DrawerFill.frostOn(context)) kit.dpi(20f) else 0) }
        val blur = Consumer<Boolean> { update() }
        val preferences = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> window.decorView.post { update() } }
        manager.addCrossWindowBlurEnabledListener(blur)
        prefs.registerOnSharedPreferenceChangeListener(preferences)
        update()
        dialog.setOnDismissListener {
            manager.removeCrossWindowBlurEnabledListener(blur)
            prefs.unregisterOnSharedPreferenceChangeListener(preferences)
            if (current === dialog) current = null
        }
    }
}
