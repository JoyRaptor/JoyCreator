package cc.joycreator.joybrush.androidkit.gl.media

import android.os.Build
import android.os.Trace

/**
 * CPU command-submission markers only (Systrace/Perfetto sections).
 *
 * No GPU timing and no CPU frame-budget claims: each section only brackets the GL
 * calls that submit work, it does not measure how long the GPU takes.
 *
 * Disabled path is identical to calling [block] directly (early returns, argument
 * order, GL state and numeric work unchanged). Sections are always balanced via
 * try/finally, so exceptions still end the section.
 */
internal inline fun <T> mediaTrace(name: String, block: () -> T): T {
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
