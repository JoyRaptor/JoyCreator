package cc.joycreator.joybrush.android.library

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cc.joycreator.joybrush.android.JbColors
import com.fadcam.joyfolder.android.JoyFolderHome
import java.util.concurrent.Executors

/**
 * Every drawing, as pictures: open one, start a new one, rename or delete (to Trash) with a long press.
 *
 * Built in code in the studio's language (old black, zinc panels, the room's gradient only on the one "new" card) so it
 * needs no layouts or strings. Drawings put back from Trash, or found in the Joy Creator folder after a reinstall, are
 * brought in each time this opens ([DrawingLibrary.syncFromFolder]).
 */
class DrawingsActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var grid: GridLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GROUND
        window.navigationBarColor = GROUND
        val scroll = ScrollView(this).apply { setBackgroundColor(GROUND); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(col)
        val back = text("‹ Back", 15f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(10))
            setOnClickListener { finish() }
            tip(this, "Back")
        }
        col.addView(back)
        col.addView(text("Drawings", 26f, INK).apply { typeface = Typeface.DEFAULT_BOLD })
        status = text("", 13f, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) }
        col.addView(status)
        grid = GridLayout(this).apply { columnCount = 2 }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Bring in anything new in the folder (put back from Trash, copied in, a reinstall), then show it.
        if (JoyFolderHome.isChosen(this)) {
            val app = applicationContext
            JoyFolderHome.io().execute {
                val added = try { DrawingLibrary.syncFromFolder(app) } catch (e: Exception) { 0 }
                if (added > 0) runOnUiThread { if (!isFinishing) refresh() }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val all = DrawingLibrary.list(this)
        status.text = when (all.size) {
            0 -> "Nothing yet. Start one below."
            1 -> "1 drawing · kept in your Joy Creator folder"
            else -> "${all.size} drawings · kept in your Joy Creator folder"
        }
        grid.removeAllViews()
        grid.addView(newCard())
        for (d in all) grid.addView(card(d))
    }

    private fun cellParams(): GridLayout.LayoutParams {
        val w = (resources.displayMetrics.widthPixels - dp(16) * 2 - dp(10)) / 2
        return GridLayout.LayoutParams().apply {
            width = w
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(0, 0, if (grid.childCount % 2 == 0) dp(10) else 0, dp(12))
        }
    }

    private fun newCard(): View {
        val jb = JbColors.palette(this)
        val box = FrameLayout(this)
        box.layoutParams = cellParams()
        val tile = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(jb.roomStart, jb.roomEnd))
                .apply { cornerRadius = dp(12).toFloat() }
        }
        box.addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH()))
        val plus = text("+  New drawing", 15f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        tile.addView(plus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.setOnClickListener {
            val file = DrawingLibrary.create(this)
            if (file == null) toast("This phone has nowhere to keep a drawing.")
            else startActivity(DrawingLibrary.openIntent(this, file))
        }
        tip(box, "New drawing")
        return box
    }

    private fun card(d: DrawingLibrary.Drawing): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.layoutParams = cellParams()
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(PANEL); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
        }
        box.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH()))
        val path = d.thumbPath
        if (path != null) {
            io.execute {
                val bmp = try { BitmapFactory.decodeFile(path) } catch (t: Throwable) { null }
                if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
            }
        } else {
            image.setImageDrawable(null)
        }
        box.addView(text(d.name, 13f, INK).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        })
        val ago = if (d.modified > 0) DateUtils.getRelativeTimeSpanString(d.modified).toString() else ""
        box.addView(text(ago, 11f, MUTED).apply { setPadding(dp(2), dp(1), dp(2), 0) })
        box.setOnClickListener { startActivity(DrawingLibrary.openIntent(this, d.file)) }
        box.setOnLongClickListener { actions(d); true }
        box.contentDescription = "${d.name}, $ago. Long press to rename or delete."
        tip(box, d.name)
        return box
    }

    private fun actions(d: DrawingLibrary.Drawing) {
        android.app.AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which -> if (which == 0) rename(d) else delete(d) }
            .show()
    }

    private fun rename(d: DrawingLibrary.Drawing) {
        val input = EditText(this).apply { setText(d.name); setSelectAllOnFocus(true); setSingleLine(true) }
        val pad = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename drawing")
            .setView(pad)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString()
                val app = applicationContext
                JoyFolderHome.io().execute {
                    val ok = DrawingLibrary.rename(app, d.file, name)
                    runOnUiThread { if (!ok) toast("That name can't be used."); if (!isFinishing) refresh() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(d: DrawingLibrary.Drawing) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete “${d.name}”?")
            .setMessage("It goes to Trash. You can put it back from Settings → Storage Location → Recently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                val app = applicationContext
                JoyFolderHome.io().execute {
                    val problem = DrawingLibrary.delete(app, d.file)
                    runOnUiThread {
                        toast(if (problem == null) "Moved to Trash." else "Not deleted: $problem.")
                        if (!isFinishing) refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun thumbH(): Int = ((resources.displayMetrics.widthPixels - dp(42)) / 2 * 3) / 4

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun tip(v: View, label: String) {
        if (v.contentDescription == null) v.contentDescription = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.tooltipText = label
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val GROUND = 0xFF050507.toInt()
        const val PANEL = 0xFF1C1C22.toInt()
        const val INK = 0xFFE9E9EE.toInt()
        const val MUTED = 0xFFA1A1AC.toInt()
    }
}
