package cc.joycreator.joybrush.android.handoff

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * FX-1 (Joybot carries your work between rooms): a picture of JoyBrush as it is on screen, taken as
 * the drawing leaves for another room, so the hand-off show can warp the real screen and not a stand-in.
 *
 * JoyBrush cannot see the app's show (the app builds on JoyBrush, not the other way round), so the
 * picture is left here and the show's first screen takes it ([take]). The painting is its own GL
 * surface under the screen's buttons and drawers, so both are copied and laid together.
 */
object RoomShot {

    /** [picture]: the whole window, scaled; [joybot]: his button, in window pixels; the window's own size in pixels. */
    class Shot(val picture: Bitmap, val joybot: Rect, val windowW: Int, val windowH: Int, internal val atMs: Long)

    /** As the app's own room pictures (`Handoff.PICTURE_LONG_SIDE`). */
    private const val LONG_SIDE = 2048f
    private const val WAIT_MS = 300L
    private const val FRESH_MS = 15_000L

    private var last: Shot? = null

    /** The app's switch (`Handoff.PREFS` / `KEY_SHOW`); off, or on a phone too old to copy a screen: no picture, no show. */
    fun wanted(c: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            c.applicationContext.getSharedPreferences("handoff", Context.MODE_PRIVATE).getBoolean("show", true)

    /** The picture just taken, once; null when there is none or it is stale. */
    @JvmStatic
    @Synchronized
    fun take(): Shot? {
        val s = last
        last = null
        if (s != null && SystemClock.uptimeMillis() - s.atMs > FRESH_MS) { s.picture.recycle(); return null }
        return s
    }

    @Synchronized
    private fun keep(s: Shot?) {
        last?.picture?.recycle()
        last = s
    }

    /**
     * Picture this screen, then run [then] on the main thread: always, and within [WAIT_MS], with or
     * without a picture. The hand-off itself never waits on the show.
     */
    fun capture(activity: Activity, painting: SurfaceView, joybotButton: View, then: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(shot: Shot?) {
            if (finished) { shot?.picture?.recycle(); return }
            finished = true
            keep(shot)
            then()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { finish(null); return }
        try {
            val decor = activity.window.decorView
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0 || painting.width <= 0 || painting.height <= 0 || !painting.holder.surface.isValid) { finish(null); return }
            val k = min(1f, LONG_SIDE / max(w, h))
            val chrome = Bitmap.createBitmap(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()), Bitmap.Config.ARGB_8888)
            val paint = Bitmap.createBitmap(max(1, (painting.width * k).roundToInt()), max(1, (painting.height * k).roundToInt()), Bitmap.Config.ARGB_8888)
            val at = IntArray(2)
            painting.getLocationInWindow(at)
            val bt = IntArray(2)
            joybotButton.getLocationInWindow(bt)
            val joy = Rect(bt[0], bt[1], bt[0] + joybotButton.width, bt[1] + joybotButton.height)
            var left = 2
            var ok = true
            val each = PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result != PixelCopy.SUCCESS) ok = false
                left--
                if (left == 0) {
                    if (!ok || finished) {
                        chrome.recycle(); paint.recycle()
                        finish(null)
                    } else {
                        val out = Bitmap.createBitmap(chrome.width, chrome.height, Bitmap.Config.ARGB_8888)
                        val c = Canvas(out)
                        c.drawColor(Color.BLACK)
                        val px = at[0] * k
                        val py = at[1] * k
                        // The window is clear where the painting shows through. A phone that copies that hole
                        // as solid black instead gets the painting laid over the window, not under it.
                        val mid = chrome.getPixel(
                            (px + paint.width / 2f).toInt().coerceIn(0, chrome.width - 1),
                            (py + paint.height / 2f).toInt().coerceIn(0, chrome.height - 1))
                        if (mid == Color.BLACK) {
                            c.drawBitmap(chrome, 0f, 0f, null)
                            c.drawBitmap(paint, px, py, null)
                        } else {
                            c.drawBitmap(paint, px, py, null)
                            c.drawBitmap(chrome, 0f, 0f, null)
                        }
                        chrome.recycle(); paint.recycle()
                        finish(Shot(out, joy, w, h, SystemClock.uptimeMillis()))
                    }
                }
            }
            PixelCopy.request(activity.window, chrome, each, main)
            PixelCopy.request(painting, paint, each, main)
            main.postDelayed({ finish(null) }, WAIT_MS)
        } catch (e: Throwable) {
            finish(null)
        }
    }
}
