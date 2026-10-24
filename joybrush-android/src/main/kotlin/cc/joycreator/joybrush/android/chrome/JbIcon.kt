package cc.joycreator.joybrush.android.chrome

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/**
 * Joy Brush's icons (JB-2.01): SOLID shapes on a 24-unit grid (owner, 2026-09-30: the icons along the top and every icon
 * that opens a menu are solid). Kept as path data in code rather than a dozen vector files, so an icon and the place it is
 * used can be read together. Drawn by the Joy Brush Lead for this app; not copied from an icon set.
 */
enum class JbIcon(val path: String, private val evenOdd: Boolean = false) {
    BRUSH("M18.3 2.6L21.4 5.7 12.2 14.9 9.1 11.8ZM8.3 12.9L11.1 15.7C10.6 18.6 8.4 20.9 3 21 3.6 17.4 4.3 13.6 8.3 12.9Z"),
    SMUDGE("M4 17C4 12 9 9 14 8 17 7.5 20 5 20 5 20 5 21 11 17 15 14 18 9 19.5 6.5 19.5 5 19.5 4 18.5 4 17Z"),
    ERASER("M14.6 3.4L21 9.8 11.8 19H7L3.4 15.4ZM13.5 19H21V21H11.5Z"),
    HOME("M12 3.2L21 11H18.5V20.5H14V15H10V20.5H5.5V11H3Z"),
    UNDO("M2.5 8.5L9 3V14ZM8 7H14.5A6.75 6.75 0 0 1 14.5 20.5H11V17.5H14.5A3.75 3.75 0 0 0 14.5 10H8Z"),
    REDO("M21.5 8.5L15 3V14ZM16 7H9.5A6.75 6.75 0 0 0 9.5 20.5H13V17.5H9.5A3.75 3.75 0 0 1 9.5 10H16Z"),
    PIN("M8 3H16V5H15V10L18 13V15H13V20.5L12 22 11 20.5V15H6V13L9 10V5H8Z"),
    MORE("M5 10A2 2 0 1 0 5 14 2 2 0 1 0 5 10ZM12 10A2 2 0 1 0 12 14 2 2 0 1 0 12 10ZM19 10A2 2 0 1 0 19 14 2 2 0 1 0 19 10Z"),
    LAYERS("M12 2.5L22 8 12 13.5 2 8ZM4.3 11.7L12 15.9 19.7 11.7 22 13 12 18.5 2 13Z"),
    GUIDES("M3 21V3L21 21ZM7 17H13.5L7 10.5Z"),
    /** Joybot: ask for help; a four-point spark. */
    JOYBOT("M12 2L14.6 9.4 22 12 14.6 14.6 12 22 9.4 14.6 2 12 9.4 9.4Z"),
    /** JB-5.20e: the Select tool, an arrow trailing a dotted loop (tap picks a line, a drag draws a loop). */
    SELECT("M3.5 2.5V17.6L7.6 13.8 10.3 20 13.1 18.8 10.4 12.7H15.9ZM17.54 15.72A1.15 1.15 0 1 0 19.84 15.72A1.15 1.15 0 1 0 17.54 15.72ZM19.37 18.15A1.15 1.15 0 1 0 21.67 18.15A1.15 1.15 0 1 0 19.37 18.15ZM18.69 21.12A1.15 1.15 0 1 0 20.99 21.12A1.15 1.15 0 1 0 18.69 21.12ZM15.98 22.50A1.15 1.15 0 1 0 18.28 22.50A1.15 1.15 0 1 0 15.98 22.50Z"),
    /** JB-5.20e: Duplicate: a copy on top, which becomes the selection. */
    DUPLICATE("M8 8H21V21H8ZM3 3H16V6H6V16H3Z"),
    /** JB-5.20e: Delete: a bin. */
    DELETE("M9 2.5H15V4.5H20.5V7H3.5V4.5H9ZM5.2 8.5H18.8L17.4 21.5H6.6Z"),
    /** JB-5.20e: Flip horizontal: two mirrored triangles. */
    FLIP_H("M10.6 4.5L3 19.5H10.6ZM13.4 4.5L21 19.5H13.4ZM11.4 2H12.6V22H11.4Z"),
    /** JB-5.20e: Flip vertical. */
    FLIP_V("M4.5 10.6L19.5 3V10.6ZM4.5 13.4L19.5 21V13.4ZM2 11.4H22V12.6H2Z"),
    /** JB-5.20e: Nudge: a bent line under a fingertip. */
    NUDGE("M2 17.2C6.2 17.2 7.4 9 12 9S17.8 17.2 22 17.2V19.6C16.6 19.6 15.7 11.4 12 11.4S7.4 19.6 2 19.6ZM9.7 4.6A2.3 2.3 0 1 0 14.3 4.6A2.3 2.3 0 1 0 9.7 4.6Z"),
    /** JB-5.20e: Copy: a paperclip, as in Concepts. */
    PAPERCLIP("M15.6 6.9V16.6A3.6 3.6 0 0 1 8.4 16.6V5.9A2.4 2.4 0 0 1 13.2 5.9V15.4A1.2 1.2 0 0 1 10.8 15.4V7.4H12.4V15.4H11.6V5.9A0.8 0.8 0 0 0 10 5.9V16.6A2 2 0 0 0 14 16.6V6.9Z"),
    /** JB-5.20e: Lock. */
    LOCK("M5.5 10.5H18.5V21.5H5.5ZM8 10.5V7.5A4 4 0 0 1 16 7.5V10.5H13.8V7.5A1.8 1.8 0 0 0 10.2 7.5V10.5Z"),
    /** JB-5.20e: Adjust colour: a half-filled disc. */
    ADJUST("M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM5.4 12A6.6 6.6 0 1 0 18.6 12A6.6 6.6 0 1 0 5.4 12ZM12 5.4A6.6 6.6 0 0 0 12 18.6Z", evenOdd = true),
    /** JB-5.20e: Rasterize Down: a line becoming pixels. */
    RASTER("M2.5 16.5C4.8 10.8 8.3 9.8 11.3 12.4L9.9 13.9C7.7 12.1 5.6 13 4.6 17.3ZM13 8.5H17V12.5H13ZM17 12.5H21V16.5H17ZM13 15H15.5V17.5H13ZM18.5 9H21V11.5H18.5Z"),
    /** JB-5.20e: Line weight: three bars, thin to thick. */
    WEIGHT("M4 5H20V6.2H4ZM4 10.4H20V12.8H4ZM4 16.4H20V20H4Z"),
    /** JB-5.20e: Add. */
    PLUS("M10.8 4.5H13.2V10.8H19.5V13.2H13.2V19.5H10.8V13.2H4.5V10.8H10.8Z"),
    /** JB-5.20e: Back to the box. */
    BOX("M6 5.4H18V6.6H6ZM6 17.4H18V18.6H6ZM5.4 6H6.6V18H5.4ZM17.4 6H18.6V18H17.4ZM3.6 6A2.4 2.4 0 1 0 8.4 6A2.4 2.4 0 1 0 3.6 6ZM15.6 6A2.4 2.4 0 1 0 20.4 6A2.4 2.4 0 1 0 15.6 6ZM15.6 18A2.4 2.4 0 1 0 20.4 18A2.4 2.4 0 1 0 15.6 18ZM3.6 18A2.4 2.4 0 1 0 8.4 18A2.4 2.4 0 1 0 3.6 18Z"),
    /** JB-5.20e: Only lines inside the loop: a loop around a whole line. */
    WHOLE("M12 4C6.5 4 2.5 7.6 2.5 12S6.5 20 12 20S21.5 16.4 21.5 12S17.5 4 12 4ZM12 5.6C16.6 5.6 19.9 8.5 19.9 12S16.6 18.4 12 18.4S4.1 15.5 4.1 12S7.4 5.6 12 5.6ZM7.5 13.6C9.5 10.4 13.4 10 16.6 12.4L15.6 13.8C13.4 12.1 10.6 12.3 9.1 14.6Z", evenOdd = true),
    /** JB-5.20e: Every line the loop touches: a line crossing the loop. */
    TOUCH("M10 5C5.7 5 2.5 8.1 2.5 12S5.7 19 10 19S17.5 15.9 17.5 12S14.3 5 10 5ZM10 6.6C13.4 6.6 15.9 9 15.9 12S13.4 17.4 10 17.4S4.1 15 4.1 12S6.6 6.6 10 6.6ZM6.5 13.6C10.5 10.6 15.8 10.5 22 12.4L21.5 14C15.9 12.3 11.2 12.4 7.9 14.8Z", evenOdd = true),
    /** JB-5.20e: This layer. */
    LAYER_ONE("M12 6.5L21 11L12 15.5L3 11Z"),
    /** JB-5.20e: Keep line weights when resizing. */
    KEEP_WEIGHTS("M2.5 4.5H10.5V12.5H2.5ZM3.9 5.9V11.1H9.1V5.9ZM10.5 8.5H21.5V19.5H10.5ZM11.9 9.9V18.1H20.1V9.9ZM4.8 10.4L8.4 6.8L9 7.4L5.4 11ZM12.7 17L19 10.7L19.6 11.3L13.3 17.6Z", evenOdd = true),
    /** JB-5.20e: Line weights grow and shrink with the box. */
    SCALE_WEIGHTS("M2.5 4.5H10.5V12.5H2.5ZM3.9 5.9V11.1H9.1V5.9ZM10.5 8.5H21.5V19.5H10.5ZM11.9 9.9V18.1H20.1V9.9ZM4.8 10.4L8.4 6.8L9 7.4L5.4 11ZM12.6 15.8L17.8 10.6L19.4 12.2L14.2 17.4Z", evenOdd = true);

    private var parsed: Path? = null

    /** The parsed path, on the 24-unit grid. Parsed once. */
    fun path(): Path = parsed ?: PathParser.createPathFromPathData(path).also {
        if (evenOdd) it.fillType = Path.FillType.EVEN_ODD // rings and frames: an inner shape is a hole
        parsed = it
    }

    /** A drawable of this icon in [argb], scaled to whatever bounds it is given. */
    fun drawable(argb: Int): IconDrawable = IconDrawable(this, argb)
}

/** A solid icon. [shadow] adds the drop shadow a white icon wears over the picture (0 = none). */
class IconDrawable internal constructor(private val icon: JbIcon, argb: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = argb
    }
    private val scaled = Path()
    private val m = Matrix()

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidateSelf() }

    /** A soft shadow under the icon: [radius] px of blur, [dy] px down, in [argb]. radius 0 removes it. */
    fun shadow(radius: Float, dy: Float, argb: Int) {
        if (radius <= 0f) paint.clearShadowLayer() else paint.setShadowLayer(radius, 0f, dy, argb)
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val s = minOf(bounds.width(), bounds.height()) / 24f
        m.setScale(s, s)
        m.postTranslate(bounds.left + (bounds.width() - 24f * s) / 2f, bounds.top + (bounds.height() - 24f * s) / 2f)
        icon.path().transform(m, scaled)
        scaled.fillType = icon.path().fillType
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(scaled, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = paint.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
