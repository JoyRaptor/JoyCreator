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
 * Joy Brush's line icons (JB-2.01): one stroke weight, round ends, drawn on a 24-unit grid like the mockup's. Kept as
 * path data in code rather than 12 vector files, so an icon and the place it is used can be read together.
 */
enum class JbIcon(val path: String, val weight: Float = 1.7f) {
    BRUSH("M4 20c2 0 3-1 3-3 0-1.5 1-2.5 2.5-2.5L19 5l-1-1-9.5 9.5"),
    SMUDGE("M7 9c1-3 5-4 7-2 2 1 4 0 5 2s-1 4-3 4c-1 3-4 5-7 4-3 0-5-3-4-5 0-1 1-2 2-3z"),
    ERASER("M4 16l8-8 6 6-5 5H8zM8 19h11"),
    HOME("M4 11l8-7 8 7v9H4z"),
    UNDO("M9 14L4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11"),
    REDO("M15 14l5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13"),
    PIN("M15 3l6 6M18 6l-4.5 4.5L9 9.5l-2 2 5.5 5.5 2-2-1-4.5M9.75 14.25L4 20"),
    LAYERS("M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"),
    MORE("M5 12h0.01M12 12h0.01M19 12h0.01", 3.2f);

    private var parsed: Path? = null

    /** The parsed path, on the 24-unit grid. Parsed once. */
    fun path(): Path = parsed ?: PathParser.createPathFromPathData(path).also { parsed = it }

    /** A drawable of this icon in [argb], scaled to whatever bounds it is given. */
    fun drawable(argb: Int): Drawable = IconDrawable(this, argb)
}

private class IconDrawable(private val icon: JbIcon, argb: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = argb
    }
    private val scaled = Path()
    private val m = Matrix()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val s = minOf(bounds.width(), bounds.height()) / 24f
        m.setScale(s, s)
        m.postTranslate(bounds.left + (bounds.width() - 24f * s) / 2f, bounds.top + (bounds.height() - 24f * s) / 2f)
        icon.path().transform(m, scaled)
        paint.strokeWidth = icon.weight * s
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(scaled, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
