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
enum class JbIcon(val path: String) {
    BRUSH("M18.3 2.6L21.4 5.7 12.2 14.9 9.1 11.8ZM8.3 12.9L11.1 15.7C10.6 18.6 8.4 20.9 3 21 3.6 17.4 4.3 13.6 8.3 12.9Z"),
    SMUDGE("M4 17C4 12 9 9 14 8 17 7.5 20 5 20 5 20 5 21 11 17 15 14 18 9 19.5 6.5 19.5 5 19.5 4 18.5 4 17Z"),
    ERASER("M14.6 3.4L21 9.8 11.8 19H7L3.4 15.4ZM13.5 19H21V21H11.5Z"),
    HOME("M12 3.2L21 11H18.5V20.5H14V15H10V20.5H5.5V11H3Z"),
    UNDO("M2.5 8.5L9 3V14ZM8 7H14.5A6.75 6.75 0 0 1 14.5 20.5H11V17.5H14.5A3.75 3.75 0 0 0 14.5 10H8Z"),
    REDO("M21.5 8.5L15 3V14ZM16 7H9.5A6.75 6.75 0 0 0 9.5 20.5H13V17.5H9.5A3.75 3.75 0 0 1 9.5 10H16Z"),
    PIN("M8 3H16V5H15V10L18 13V15H13V20.5L12 22 11 20.5V15H6V13L9 10V5H8Z"),
    MORE("M5 10A2 2 0 1 0 5 14 2 2 0 1 0 5 10ZM12 10A2 2 0 1 0 12 14 2 2 0 1 0 12 10ZM19 10A2 2 0 1 0 19 14 2 2 0 1 0 19 10Z"),
    LAYERS("M12 2.5L22 8 12 13.5 2 8ZM4.3 11.7L12 15.9 19.7 11.7 22 13 12 18.5 2 13Z"),
    GUIDES("M3 21V3L21 21ZM7 17H13.5L7 10.5Z");

    private var parsed: Path? = null

    /** The parsed path, on the 24-unit grid. Parsed once. */
    fun path(): Path = parsed ?: PathParser.createPathFromPathData(path).also { parsed = it }

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
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(scaled, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = paint.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
