package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cc.joycreator.joybrush.core.chrome.BrushKnobGroups.Group
import com.fadcam.ui.faditor.Studio

/**
 * BRUSH-UI section K.1 (owner's pick, 11-14): the brush editor's top bar is Studio's tabs drawer header. The brush's name
 * on the left, a scrolling row of round group buttons (an icon; the open one fills cyan and shows its name), then Done
 * and ✕ (cancel) at the far right. Sizes and colours are Studio's own (`ObjectDrawer` header: 12sp bold title capped at
 * 120dp, 32dp pills inset 4dp with 7dp corners, the drawer control fill, the "on" fill at 70% of the accent, ✕ 13sp in
 * the dim ink on a 40dp target). ObjectDrawer lives in the app module, which JoyBrush cannot reach, so the look is
 * rebuilt here from the shared Studio tokens rather than drawn anew.
 */
@SuppressLint("ViewConstructor")
class BrushEditorHeader(
    private val kit: ChromeKit,
    title: String,
    private val groups: List<Group>,
    private val onGroup: (Group) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) : LinearLayout(kit.context) {
    private val pills = LinkedHashMap<Group, LinearLayout>()
    var active: Group? = null
        set(value) { if (field != value) { field = value; paint() } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dpi(38f)
        addView(TextView(context).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Studio.DRAWER_INK)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            maxWidth = kit.dpi(120f)
            setPadding(kit.dpi(6f), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dpi(8f), 0, kit.dpi(4f), 0)
        }
        for (g in groups) {
            val pill = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER
                minimumWidth = kit.dpi(32f)
                addView(ImageView(context).apply { setImageDrawable(iconOf(g).drawable(Studio.DRAWER_DIM)) },
                    LayoutParams(kit.dpi(14f), kit.dpi(14f)))
                addView(TextView(context).apply {
                    text = g.label; textSize = 11f; setSingleLine(true)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                kit.label(this, "Go to ${g.label} settings")
                setOnClickListener { active = g; onGroup(g) }
            }
            row.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, kit.dpi(32f)).apply { if (pills.isNotEmpty()) leftMargin = kit.dpi(3f) })
            pills[g] = pill
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(kit.dpi(14f))
            addView(row)
        }, LayoutParams(0, kit.dpi(32f), 1f))
        addView(textButton("Done", "Keep these settings and close") { onDone() })
        addView(textButton("✕", "Cancel: put the brush back as it was and close", 13f) { onCancel() })
        active = groups.firstOrNull()
        paint()
    }

    private fun paint() {
        for ((g, pill) in pills) {
            val on = g == active
            val ink = if (on) Studio.ON_GO else Studio.DRAWER_DIM
            val icon = pill.getChildAt(0) as ImageView
            val label = pill.getChildAt(1) as TextView
            icon.setImageDrawable(iconOf(g).drawable(ink))
            label.visibility = if (on) VISIBLE else GONE
            label.setTextColor(ink)
            label.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            (label.layoutParams as LayoutParams).leftMargin = if (on) kit.dpi(5f) else 0
            val side = kit.dpi(if (on) 11f else 9f)
            pill.setPadding(side, 0, side, 0)
            pill.background = InsetDrawable(GradientDrawable().apply {
                setColor(if (on) Studio.alpha(Studio.ARMED, 0xB3) else Studio.alpha(Studio.DRAWER_INK, 0x1A))
                cornerRadius = kit.dp(7f)
            }, 0, kit.dpi(4f), 0, kit.dpi(4f))
            pill.isSelected = on
        }
    }

    private fun textButton(word: String, label: String, sp: Float = 12f, action: () -> Unit) = TextView(context).apply {
        text = word; textSize = sp; gravity = Gravity.CENTER
        setTextColor(Studio.DRAWER_DIM)
        minWidth = kit.dpi(40f); minHeight = kit.dpi(38f)
        setPadding(kit.dpi(6f), 0, kit.dpi(6f), 0)
        kit.label(this, label)
        setOnClickListener { action() }
    }

    companion object {
        fun iconOf(g: Group): JbIcon = when (g) {
            Group.SHAPE -> JbIcon.GROUP_SHAPE
            Group.INK -> JbIcon.GROUP_INK
            Group.PRESSURE -> JbIcon.GROUP_PRESSURE
            Group.SCATTER -> JbIcon.GROUP_SCATTER
            Group.COLOUR -> JbIcon.GROUP_COLOUR
            Group.WATER -> JbIcon.GROUP_WATER
            Group.ENDS -> JbIcon.GROUP_ENDS
            Group.PAPER -> JbIcon.GROUP_PAPER
            Group.FEEL -> JbIcon.GROUP_FEEL
        }
    }
}
