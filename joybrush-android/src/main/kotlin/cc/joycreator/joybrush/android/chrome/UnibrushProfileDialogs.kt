package cc.joycreator.joybrush.android.chrome

import android.app.Dialog
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import cc.joycreator.joybrush.core.brush.TipProfileSpec

/** DRAFT — not compiled/tested. Supplies UnibrushSettingsView.editProfilePoints without a stub. */
object UnibrushProfileDialogs {
    /** Requires the host Activity's themed ChromeKit context. Caller may retain/close the Dialog. */
    fun show(kit: ChromeKit, profile: TipProfileSpec, result: (TipProfileSpec?) -> Unit): Dialog {
        val dialog = Dialog(kit.context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var returned = false
        fun complete(value: TipProfileSpec?) {
            if (returned) return
            returned = true
            try { result(value) } catch (e: Exception) { returned = false; throw e }
            dialog.dismiss()
        }
        val editor = TipProfilePointEditorView(kit, profile,
            onDone = { complete(it) }, onCancel = { complete(null) })
        dialog.setContentView(editor)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { editor.cancelEditing() }
        dialog.setOnDismissListener { if (!returned) editor.cancelEditing() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }
}
