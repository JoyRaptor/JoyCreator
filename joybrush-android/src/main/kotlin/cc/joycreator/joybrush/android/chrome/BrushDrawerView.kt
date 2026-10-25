package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import cc.joycreator.joybrush.androidkit.preview.BrushPreviewStyle
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.chrome.BrushShelf
import cc.joycreator.joybrush.core.chrome.SampleStroke
import kotlin.math.roundToInt

/**
 * The brush drawer (JB-2.01): kinds down the left, and on the right a sample stroke of every brush on that shelf. Only
 * shelves that hold a brush are listed. Tapping a stroke picks the brush; the screen closes the drawer (0 ms: a brush pick
 * is a hundred-a-day act).
 *
 * Each row shows, once it has one, a picture drawn by the brush's OWN engine ([previews]; kept per brush version, so an open
 * is instant after the first). Until it arrives a row shows [sample], a stand-in laid from the brush's dabs as soft discs: it
 * knows the size, the spacing and the softness of a stamp brush and nothing else (no grain, no tuft streaks, no paper, no
 * smudge, no media), so it is a placeholder for a moment and never the answer. Only rows that are on screen ask.
 */
@SuppressLint("ViewConstructor")
class BrushDrawerView(
    private val kit: ChromeKit,
    library: List<BrushPreset>,
    startKind: BrushShelf.Kind,
    private val currentId: String?,
    private val onPick: (BrushPreset) -> Unit,
    /** Long-press on a brush: its advanced settings (owner, 2026-09-30). Null keeps the drawer tap-only. */
    private val onSettings: ((BrushPreset) -> Unit)? = null,
    /** Where each row's real picture comes from. Null keeps the stand-in only (a screen with no canvas to draw on). */
    private val previews: PreviewSource<Bitmap>? = null,
    /**
     * Select's swap list (JB-5.20e D25): the pictures draw the SELECTED lines' colour on the drawing's paper ([Swap.paper]
     * behind each picture), every brush the selection uses wears the ring, and a quiet footer says why some are missing.
     * Null keeps the ordinary drawer.
     */
    private val swap: Swap? = null,
) : LinearLayout(kit.context) {

    /** What the swap list draws with: the selected lines' [ink], the drawing's [paper], the brushes they [use] now. */
    class Swap(val ink: Int, val paper: Int, val use: (BrushPreset) -> Boolean, val footer: String)

    private val shelves = BrushShelf.shelves(library)
    private val kinds = LinearLayout(context).apply { orientation = VERTICAL }
    private val strokes = LinearLayout(context).apply { orientation = VERTICAL }
    private val kindViews = HashMap<BrushShelf.Kind, TextView>()
    private var shown: BrushShelf.Kind = shelves.firstOrNull { it.first == startKind }?.first ?: BrushShelf.Kind.ALL
    private val strokeScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val maxH = (resources.displayMetrics.heightPixels * 0.45f).roundToInt()

    /** The look the pictures are drawn in: the drawer ink, and the eraser strip tinted with it. */
    private val previewStyle = swap?.let {
        val ink = it.ink or (0xFF shl 24)
        // mediaInk too: the watercolour line and the media brushes draw with it (the Note 9 swap list showed a white wash).
        BrushPreviewStyle(ink = ink, strip = ColorUtils.setAlphaComponent(it.ink, 0x59), mediaInk = ink)
    }
        ?: BrushPreviewStyle(ink = kit.p.drawerInk, strip = ColorUtils.setAlphaComponent(kit.p.drawerInk, 0x59))

    /** One row picture: asked for once, when it first comes near the screen. [shelf] says which showing of the shelf it belongs to. */
    private class Slot(val preset: BrushPreset, val img: ImageView, val shelf: Int) {
        var laidOut = false
        var asked = false
    }

    private val slots = ArrayList<Slot>()
    private val asks = ArrayList<PreviewCancel>()
    private var shelfShowing = 0

    init {
        orientation = HORIZONTAL
        val kindScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(kinds) }
        strokeScroll.addView(strokes)
        strokeScroll.setOnScrollChangeListener { _, _, _, _, _ -> askVisible() }
        addView(kindScroll, LayoutParams(kit.dpi(104f), LayoutParams.WRAP_CONTENT))
        addView(strokeScroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = kit.dpi(6f) })
        for ((kind, _) in shelves) {
            val t = TextView(context).apply {
                text = nameOf(kind)
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dpi(10f), 0, kit.dpi(6f), 0)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { showShelf(kind) }
            }
            kit.label(t, "${nameOf(kind)} brushes")
            kinds.addView(t, LayoutParams(LayoutParams.MATCH_PARENT, kit.dpi(ChromeKit.TOUCH_DP)))
            kindViews[kind] = t
        }
        // Tall shelves scroll rather than push the drawer off the screen.
        kindScroll.layoutParams.height = LayoutParams.WRAP_CONTENT
        doOnLayout {
            if (height > maxH) layoutParams = layoutParams.apply { height = maxH }
        }
        showShelf(shown)
    }

    private fun showShelf(kind: BrushShelf.Kind) {
        shown = kind
        for ((k, v) in kindViews) {
            val on = k == kind
            v.setTextColor(if (on) kit.p.drawerInk else kit.p.drawerDim)
            v.background = if (on) ring(10f) else null
        }
        callOffAsks()
        shelfShowing++
        strokes.removeAllViews()
        val list = shelves.firstOrNull { it.first == kind }?.second ?: return
        for (p in list) strokes.addView(row(p), LayoutParams(LayoutParams.MATCH_PARENT, kit.dpi(ROW_DP)).apply { bottomMargin = kit.dpi(2f) })
        swap?.let { s ->
            strokes.addView(TextView(context).apply {
                text = s.footer
                textSize = 10.5f
                setTextColor(kit.p.drawerDim)
                setPadding(kit.dpi(8f), kit.dpi(6f), kit.dpi(8f), kit.dpi(4f))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** The pictures of the rows that are on screen (and one row beyond), asked for from the top. */
    private fun askVisible() {
        val source = previews ?: return
        val viewport = minOf(strokeScroll.height, maxH)
        if (viewport <= 0) return
        val reach = kit.dpi(ROW_DP)
        val top = strokeScroll.scrollY - reach
        val bottom = strokeScroll.scrollY + viewport + reach
        for (slot in slots) {
            if (slot.asked || !slot.laidOut || slot.shelf != shelfShowing) continue
            val row = slot.img.parent as? View ?: continue
            if (row.bottom < top || row.top > bottom) continue
            slot.asked = true
            asks += source.request(slot.preset, slot.img.width, slot.img.height, previewStyle) { bitmap ->
                // A shelf that has been swapped out, or a drawer that has closed, takes no picture.
                if (slot.shelf == shelfShowing && slot.img.isAttachedToWindow) slot.img.setImageBitmap(bitmap)
            }
        }
    }

    private fun callOffAsks() {
        for (ask in asks) ask.cancel()
        asks.clear()
        slots.clear()
    }

    override fun onDetachedFromWindow() {
        callOffAsks()
        super.onDetachedFromWindow()
    }

    /** One brush: its sample stroke, its name in the corner, the cyan ring if it is the one in the hand. */
    private fun row(p: BrushPreset): View {
        val box = FrameLayout(context)
        // The name is a small label above the stroke, never over it: seen on the Note 9, a name in the corner hid the
        // eraser's own stroke.
        val name = TextView(context).apply {
            text = p.name
            textSize = 10f
            setTextColor(kit.p.drawerDim)
            setSingleLine()
        }
        box.addView(name, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP).apply { marginStart = kit.dpi(8f); topMargin = kit.dpi(2f) })
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            // A pencil, watercolour or oil picture keeps its paper: a soft corner, so a sheet of paper reads as a swatch.
            val radius = kit.dp(6f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            // The swap list shows the line on its own paper, so a dark line reads on the dark drawer (D25).
            swap?.let { setBackgroundColor(it.paper or (0xFF shl 24)) }
        }
        box.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = kit.dpi(LABEL_DP)
        })
        if (p.id == currentId || swap?.use?.invoke(p) == true) box.background = ring(10f)
        kit.label(box, if (onSettings != null) "${p.name}. Hold for its settings" else p.name)
        box.setOnClickListener { onPick(p) }
        onSettings?.let { open -> box.setOnLongClickListener { open(p); true } }
        // The stand-in needs the row's real width; it is drawn once the row is laid out, and the real picture is asked for.
        val slot = Slot(p, img, shelfShowing)
        slots += slot
        img.doOnLayout {
            if (img.width > 0 && img.height > 0 && !slot.laidOut) {
                slot.laidOut = true
                img.setImageBitmap(sample(p, img.width, img.height))
                askVisible()
            }
        }
        return box
    }

    /**
     * The stand-in: the dabs as soft discs, laid into a stroke layer at their flow and then the layer at the brush's opacity,
     * so a wash brush does not darken where its dabs overlap. Grain, paper, smudging and media are not drawn. Shown only until
     * the engine's own picture arrives.
     */
    private fun sample(p: BrushPreset, w: Int, h: Int): Bitmap {
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val dabs = SampleStroke.dabs(p, w.toFloat(), h.toFloat(), displaySize = h * 0.55f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val soft = 1f - p.tip.hardness.base.coerceIn(0f, 1f)
        for (d in dabs) {
            val a = (d.flow.coerceIn(0f, 1f) * 255).roundToInt()
            if (a <= 0) continue
            paint.color = ColorUtils.setAlphaComponent(kit.p.drawerInk, a)
            val blur = d.radius * soft
            paint.maskFilter = if (blur >= 0.75f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            lc.drawCircle(d.x, d.y, (d.radius - blur / 2f).coerceAtLeast(0.5f), paint)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val layerPaint = Paint().apply { alpha = (p.opacity.base.coerceIn(0.05f, 1f) * 255).roundToInt() }
        oc.drawBitmap(layer, 0f, 0f, layerPaint)
        layer.recycle()
        return out
    }

    private fun ring(radiusDp: Float) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = kit.dp(radiusDp)
        setColor(kit.ink(0.10f))
        setStroke(kit.dpi(1.5f), kit.p.stateSelected)
    }

    companion object {
        const val ROW_DP = 52f

        /** The name's band above the stroke. */
        const val LABEL_DP = 15f

        fun nameOf(k: BrushShelf.Kind): String = when (k) {
            BrushShelf.Kind.ALL -> "All"
            BrushShelf.Kind.PENCILS -> "Pencils"
            BrushShelf.Kind.INKS -> "Inks & pens"
            BrushShelf.Kind.MARKERS -> "Markers"
            BrushShelf.Kind.PAINT -> "Paint"
            BrushShelf.Kind.WATERCOLOUR -> "Watercolour"
            BrushShelf.Kind.OILS -> "Oils"
            BrushShelf.Kind.AIRBRUSH -> "Airbrush"
            BrushShelf.Kind.SMUDGE -> "Smudge"
            BrushShelf.Kind.FILL -> "Fill"
            BrushShelf.Kind.ERASERS -> "Erasers"
            BrushShelf.Kind.IMPORTED -> "Imported"
        }
    }
}
