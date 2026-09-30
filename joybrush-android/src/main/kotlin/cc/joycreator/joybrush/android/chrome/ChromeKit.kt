package cc.joycreator.joybrush.android.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import cc.joycreator.joybrush.android.JbColors
import cc.joycreator.joybrush.android.Palette
import com.fadcam.ui.faditor.tools.DrawerFill

/**
 * The look every piece of Joy Brush's chrome shares (JB-2.01, visual language §1–2): the see-through fill the Studio's
 * drawers use (one slider for the whole app), drawer ink, the cyan ring that means "this one", and one label that is the
 * hover label, the TalkBack name and the long-press tooltip at once. No colour is written here: they all come from
 * `jb_tokens.xml` through [JbColors].
 */
class ChromeKit(val context: Context) {

    val p: Palette = JbColors.palette(context)
    private val density = context.resources.displayMetrics.density

    fun dp(v: Float): Float = v * density
    fun dpi(v: Float): Int = Math.round(v * density)

    /** The drawer ink at a strength: a control's idle fill (10%), a ring (12%), a pressed wash (20%). */
    fun ink(alpha: Float): Int = ColorUtils.setAlphaComponent(p.drawerInk, Math.round(alpha * 255))

    /** A see-through chrome surface: the shared drawer fill, following the Studio's see-through slider live. */
    fun surface(view: View, radius: FloatArray) {
        view.background = GradientDrawable().apply { cornerRadii = radius }
        DrawerFill.follow(view)
    }

    fun surface(view: View, radiusDp: Float) = surface(view, FloatArray(8) { dp(radiusDp) })

    /**
     * The fill of the small, always-there controls — the strip and the top bar's chips. Near-solid panel, as the approved
     * mockup drew it: at the drawer's 50% these read as flat grey over white paper and look switched off. They cover a
     * sliver, not the picture, so the owner's see-through rule (for drawers and panels) is not what applies here.
     */
    fun chrome(): Int = ColorUtils.setAlphaComponent(p.panel, CHROME_ALPHA)

    /** A chrome surface: [chrome] with a hairline of the ground ramp's line, so it also reads on dark paint. */
    fun chromeSurface(view: View, radius: FloatArray) {
        view.background = GradientDrawable().apply {
            cornerRadii = radius
            setColor(chrome())
            setStroke(Math.max(1, dpi(0.75f)), p.line)
        }
    }

    /** Every tappable thing is labelled (visual language §2): hover label = TalkBack name = tooltip. */
    fun label(v: View, text: String) {
        v.contentDescription = text
        ViewCompat.setTooltipText(v, text)
    }

    /** The selected ring (a state colour is always a ring, never a fill): 1.5 dp of cyan around [r]. */
    fun drawSelected(c: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = ink(0.12f)
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = p.stateSelected
        c.drawCircle(cx, cy, r, paint)
    }

    companion object {
        /** The smallest touch target in the chrome, dp. */
        const val TOUCH_DP = 40f

        /** 90% (0.9 × 255), the mockup's `--chrome`. */
        const val CHROME_ALPHA = 230
    }
}
