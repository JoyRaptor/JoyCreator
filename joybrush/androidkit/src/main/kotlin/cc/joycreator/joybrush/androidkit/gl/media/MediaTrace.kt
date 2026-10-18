package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import android.os.Build
import android.os.Trace

/**
 * Bench only (the debug stroke replay, FrameStats): while [timer] is set, every [mediaTrace] section is also TIMED,
 * finished on the GPU before and after (glFinish brackets: the whole cost of its work, serialised, pessimistic but
 * honest), and reported by name without the "JB.media." prefix. Null (always, outside a bench recording) = the code
 * below, unchanged. GL thread only.
 */
internal object MediaPhases {
    @JvmField var timer: ((String, Double) -> Unit)? = null
}

/**
 * CPU command-submission markers (Systrace/Perfetto sections), and the bench's phase timer ([MediaPhases]).
 *
 * Without the timer: no GPU timing and no CPU frame-budget claims: each section only brackets the GL calls that submit
 * work, it does not measure how long the GPU takes. Disabled path is identical to calling [block] directly (early
 * returns, argument order, GL state and numeric work unchanged). Sections are always balanced via try/finally, so
 * exceptions still end the section.
 */
internal inline fun <T> mediaTrace(name: String, block: () -> T): T {
    val timer = MediaPhases.timer
    if (timer != null) {
        GLES30.glFinish()
        val t0 = System.nanoTime()
        try {
            return traced(name, block)
        } finally {
            GLES30.glFinish()
            timer(name.removePrefix("JB.media."), (System.nanoTime() - t0) / 1e6)
        }
    }
    return traced(name, block)
}

@PublishedApi internal inline fun <T> traced(name: String, block: () -> T): T {
    if (Build.VERSION.SDK_INT < 29 || !Trace.isEnabled()) {
        return block()
    }
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
