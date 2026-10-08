package cc.joycreator.joybrush.android.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import cc.joycreator.joybrush.androidkit.tools.FillPenPreviewState
import cc.joycreator.joybrush.core.view.ViewTransform

/** A noninteractive shape preview over the GL surface. No stroke or document data is owned here. */
class FillPenPreview(context: Context, private val transform: ViewTransform) : View(context) {
    var state: FillPenPreviewState? = null
        set(value) { field=value; invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val shape=state ?: return
        if(shape.points.size < 3) return
        path.rewind(); path.fillType=Path.FillType.WINDING
        shape.points.forEachIndexed { i,p ->
            val (x,y)=transform.docToScreen(p.x.toFloat(),p.y.toFloat())
            if(i == 0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        path.close()
        paint.color=shape.argb; paint.alpha=(shape.opacity.coerceIn(0f,1f)*153).toInt()
        paint.style=Paint.Style.FILL; canvas.drawPath(path,paint)
        paint.alpha=(shape.opacity.coerceIn(0f,1f)*255).toInt()
        paint.style=Paint.Style.STROKE; paint.strokeWidth=resources.displayMetrics.density
        canvas.drawPath(path,paint)
    }
}
