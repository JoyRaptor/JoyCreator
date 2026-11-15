package cc.joycreator.joybrush.android.chrome

import android.graphics.Bitmap
import android.widget.ImageView
import cc.joycreator.joybrush.core.brush.BrushPreset

/**
 * BRUSH-UI section K.3: the small pictures at each end of a brush editor slider, the brush with that one setting pushed
 * to its end. Asked for ONE AT A TIME (pencil, watercolour and oil pictures run the media engine, and a sheet has many
 * sliders), newest settings first; a picture still waiting when the settings change again is replaced, never stacked.
 *
 * [render] draws one picture (the editor's host asks the shared preview cache for the test stroke's S-turns with no
 * stripes) and returns a cancel.
 */
class SliderEndPictures(
    private val render: (preset: BrushPreset, widthPx: Int, heightPx: Int, onReady: (Bitmap) -> Unit) -> (() -> Unit),
) {
    private class Job(val view: ImageView, val preset: BrushPreset)

    private val queue = ArrayDeque<Job>()
    private var running: (() -> Unit)? = null
    private var closed = false

    /** Asks for [view] to show [preset]; replaces any waiting job for the same view. */
    fun ask(view: ImageView, preset: BrushPreset) {
        if (closed) return
        queue.removeAll { it.view === view }
        queue.addLast(Job(view, preset))
        if (running == null) next()
    }

    private fun next() {
        if (closed) return
        val job = queue.removeFirstOrNull() ?: run { running = null; return }
        val w = job.view.width; val h = job.view.height
        if (w <= 0 || h <= 0) { next(); return }
        var done = false
        running = {}
        val cancel = try {
            render(job.preset, w, h) { bitmap ->
                if (done) return@render
                done = true
                if (!closed && job.view.isAttachedToWindow) job.view.setImageBitmap(bitmap)
                job.view.post { running = null; next() }
            }
        } catch (e: Exception) {
            android.util.Log.w("JoyBrushEnds", "no end picture", e); done = true; running = null
            job.view.post { next() }; return
        }
        if (!done) running = cancel
    }

    fun close() {
        closed = true
        queue.clear()
        running?.invoke(); running = null
    }
}
