package cc.joycreator.joybrush.android.board

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import com.fadcam.ui.faditor.tools.DrawerFill
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil
import kotlin.math.min

/** Frost copies only the painting surface. Glyphs and drawer opacity remain owned by chrome. */
class BoardBackdrop(private val context: Context, private val source: GLSurfaceView) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "JoyBrush-board-frost").apply { isDaemon = true } }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val prefs = context.getSharedPreferences("studio_drawer", Context.MODE_PRIVATE)
    private var snapshot: Bitmap? = null
    private var changed: (() -> Unit)? = null
    private var dirty = false
    private var busy = false
    private var scheduled = false
    private var closed = false
    private var epoch = 0L
    private var lastCapture = -CAPTURE_INTERVAL_MS
    private val capture = Runnable { scheduled = false; captureNow() }
    private val preferences = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "frost") main.post { if (!closed) refresh(changed ?: {}) }
    }
    private val attached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { refresh(changed ?: {}) }
        override fun onViewDetachedFromWindow(v: View) { deactivate() }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferences)
        source.addOnAttachStateChangeListener(attached)
    }

    /** Hosts call on visible glass/content/view changes. One outstanding read; at most four per second. */
    fun refresh(changed: () -> Unit) {
        if (closed) return
        this.changed = changed
        if (!enabled()) { deactivate(); return }
        dirty = true
        if (busy || scheduled) return
        val delay = (CAPTURE_INTERVAL_MS - (SystemClock.uptimeMillis() - lastCapture)).coerceAtLeast(0)
        if (delay == 0L) captureNow() else { scheduled = true; main.postDelayed(capture, delay) }
    }

    /** Called under the existing clipped glass element; never paints a fill over the backdrop. */
    fun draw(canvas: Canvas, view: View) {
        if (!enabled()) return
        val bitmap = snapshot?.takeUnless { it.isRecycled } ?: return
        val matrix = bitmapToView(source, view, bitmap.width, bitmap.height) ?: return
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    fun close() {
        if (closed) return
        closed = true
        deactivate()
        changed = null
        prefs.unregisterOnSharedPreferenceChangeListener(preferences)
        source.removeOnAttachStateChangeListener(attached)
        worker.shutdown()
    }

    private fun enabled() = !closed && Build.VERSION.SDK_INT >= 31 && DrawerFill.frostOn(context) &&
        source.isAttachedToWindow && source.isShown && source.windowVisibility == View.VISIBLE

    private fun deactivate() {
        epoch++
        dirty = false
        scheduled = false
        main.removeCallbacks(capture)
        val hadSnapshot = snapshot != null
        snapshot?.recycle()
        snapshot = null
        if (hadSnapshot) changed?.invoke()
        // A bitmap being copied/blurred is owned by that callback until it completes.
        // Leaving busy set prevents rapid toggles from allocating parallel captures.
    }

    private fun captureNow() {
        if (!enabled() || busy || !dirty) return
        val size = captureSize(source.width, source.height) ?: run { deactivate(); return }
        val bitmap = try { Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888) }
            catch (_: OutOfMemoryError) { dirty = false; return }
        dirty = false
        busy = true
        val request = epoch
        lastCapture = SystemClock.uptimeMillis()
        try {
            PixelCopy.request(source, bitmap, { status ->
                if (status != PixelCopy.SUCCESS || request != epoch || !enabled()) {
                    complete(bitmap, request, false)
                } else {
                    try {
                        worker.execute {
                            val success = try {
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                blurPixels(pixels, bitmap.width, bitmap.height)
                                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                true
                            } catch (_: RuntimeException) { false } catch (_: OutOfMemoryError) { false }
                            main.post { complete(bitmap, request, success) }
                        }
                    } catch (_: RejectedExecutionException) { complete(bitmap, request, false) }
                }
            }, main)
        } catch (_: IllegalArgumentException) { complete(bitmap, request, false) }
    }

    private fun complete(bitmap: Bitmap, request: Long, success: Boolean) {
        busy = false
        if (success && request == epoch && enabled()) {
            snapshot?.recycle()
            snapshot = bitmap
            changed?.invoke()
        } else bitmap.recycle()
        if (dirty && enabled()) refresh(changed ?: {})
    }

    internal companion object {
        const val MAX_PIXELS = 65_536
        const val CAPTURE_INTERVAL_MS = 250L

        fun captureSize(width: Int, height: Int): Pair<Int, Int>? {
            if (width <= 0 || height <= 0) return null
            val w = min(128, width)
            val h = ceil(height.toDouble() * w / width)
            if (!h.isFinite() || h < 1 || h > MAX_PIXELS.toDouble() / w) return null
            return w to h.toInt()
        }

        /** PixelCopy's small bitmap -> source local -> global -> overlay local, including every native ancestor. */
        fun bitmapToView(source: View, target: View, width: Int, height: Int): Matrix? {
            if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return null
            val sourceGlobal = screenMatrix(source)
            val targetGlobal = screenMatrix(target)
            val inverse = Matrix()
            if (!targetGlobal.invert(inverse)) return null
            val scaled = Matrix().apply { setScale(source.width.toFloat() / width, source.height.toFloat() / height) }
            val global = Matrix().apply { setConcat(sourceGlobal, scaled) }
            return Matrix().apply { setConcat(inverse, global) }
        }

        private fun screenMatrix(view: View): Matrix {
            val matrix = Matrix().also { view.transformMatrixToGlobal(it) }
            if (view.isAttachedToWindow) {
                // Native global matrices stop at ViewRoot. A Dialog and the painting surface
                // have separate window origins; correct once at the root, preserving subpixels
                // in every child transform rather than rounding each view's screen position.
                val root = view.rootView
                val origin = floatArrayOf(0f, 0f)
                Matrix().also { root.transformMatrixToGlobal(it) }.mapPoints(origin)
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                matrix.postTranslate(location[0] - origin[0], location[1] - origin[1])
            }
            return matrix
        }

        /** Two bounded box passes, clamping edges so Frost never introduces a black border. */
        fun blurPixels(pixels: IntArray, width: Int, height: Int, radius: Int = 4) {
            require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong() && pixels.size <= MAX_PIXELS)
            require(radius in 0..16)
            if (radius == 0) return
            val scratch = IntArray(pixels.size)
            val count = radius * 2 + 1
            fun pass(input: IntArray, output: IntArray, rows: Int, columns: Int, stride: Int, rowStride: Int) {
                for (row in 0 until rows) {
                    val base = row * rowStride
                    val sums = IntArray(4)
                    fun add(pixel: Int, sign: Int) {
                        sums[0] += (pixel ushr 24) * sign
                        sums[1] += ((pixel ushr 16) and 255) * sign
                        sums[2] += ((pixel ushr 8) and 255) * sign
                        sums[3] += (pixel and 255) * sign
                    }
                    for (offset in -radius..radius) add(input[base + offset.coerceIn(0, columns - 1) * stride], 1)
                    for (column in 0 until columns) {
                        output[base + column * stride] = (sums[0] / count shl 24) or (sums[1] / count shl 16) or
                            (sums[2] / count shl 8) or (sums[3] / count)
                        add(input[base + (column - radius).coerceIn(0, columns - 1) * stride], -1)
                        add(input[base + (column + radius + 1).coerceIn(0, columns - 1) * stride], 1)
                    }
                }
            }
            pass(pixels, scratch, height, width, 1, width)
            pass(scratch, pixels, width, height, width, 1)
        }
    }
}
