package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The pinned reference picture (JB-2.01, owner decision 3): a picture floating over the drawing that one finger moves
 * and two fingers turn and scale. It is never painted on and never saved into the drawing or exported — it is a view,
 * not a layer. A touch that misses the picture falls through to the canvas.
 *
 * Where it sits is a [Matrix] from the picture's own pixels to this view's, kept by the screen between visits.
 */
@SuppressLint("ViewConstructor")
class ReferenceView(private val kit: ChromeKit) : View(kit.context) {

    var bitmap: Bitmap? = null
        private set
    val place = Matrix()

    /** Called when a move, turn or scale ends, so the screen can remember where the picture is. */
    var onMoved: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corners = FloatArray(8)
    private val inv = Matrix()
    private val pt = FloatArray(2)

    private var active = false
    private var p0 = -1
    private var p1 = -1
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Shows [b]. With [placement] null the picture is put in the default place, top-right at about a third of the width. */
    fun setPicture(b: Bitmap?, placement: FloatArray?) {
        bitmap = b
        if (b != null) {
            if (placement != null && placement.size == 9) place.setValues(placement) else post { putBack() }
        }
        invalidate()
    }

    /** "Put everything back": the default place and size. */
    fun putBack() {
        val b = bitmap ?: return
        if (width == 0) { post { putBack() }; return }
        val target = width * 0.34f
        val s = target / maxOf(b.width, b.height).toFloat()
        place.reset()
        place.postScale(s, s)
        place.postTranslate(width - b.width * s - kit.dp(14f), kit.dp(64f))
        invalidate()
        onMoved?.invoke()
    }

    fun placement(): FloatArray = FloatArray(9).also { place.getValues(it) }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        c.drawBitmap(b, place, paint)
        // A hairline frame and a soft dark edge, so a white reference still reads on white paper.
        corners[0] = 0f; corners[1] = 0f
        corners[2] = b.width.toFloat(); corners[3] = 0f
        corners[4] = b.width.toFloat(); corners[5] = b.height.toFloat()
        corners[6] = 0f; corners[7] = b.height.toFloat()
        place.mapPoints(corners)
        shadow.strokeWidth = kit.dp(3f)
        shadow.color = ColorUtils.setAlphaComponent(kit.p.ground, 70)
        edge.strokeWidth = kit.dp(1f)
        edge.color = kit.ink(0.45f)
        for (paintNow in arrayOf(shadow, edge)) {
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                c.drawLine(corners[i * 2], corners[i * 2 + 1], corners[j * 2], corners[j * 2 + 1], paintNow)
            }
        }
    }

    /**
     * The reference picture's colour at view point ([x], [y]), or null when the picture is hidden or not there. The canvas
     * cannot see this view (it is drawn above the GL surface), so the top icons ask it directly.
     */
    fun colourAt(x: Float, y: Float): Int? {
        val b = bitmap ?: return null
        if (visibility != VISIBLE || !place.invert(inv)) return null
        pt[0] = x; pt[1] = y
        inv.mapPoints(pt)
        val px = pt[0].toInt()
        val py = pt[1].toInt()
        if (px < 0 || py < 0 || px >= b.width || py >= b.height) return null
        val c = b.getPixel(px, py)
        // A mostly see-through pixel of a cut-out picture shows the drawing, not the picture.
        return if ((c ushr 24) < 128) null else c
    }

    private fun hits(x: Float, y: Float): Boolean {
        val b = bitmap ?: return false
        if (!place.invert(inv)) return false
        pt[0] = x; pt[1] = y
        inv.mapPoints(pt)
        return RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()).contains(pt[0], pt[1])
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hits(e.x, e.y)) return false
                active = true
                p0 = e.getPointerId(0); p1 = -1
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (active && p1 < 0) {
                p1 = e.getPointerId(e.actionIndex)
                startPair(e)
            }
            MotionEvent.ACTION_MOVE -> if (active) move(e)
            MotionEvent.ACTION_POINTER_UP -> if (active) {
                val gone = e.getPointerId(e.actionIndex)
                if (gone == p0 || gone == p1) {
                    // Keep going with whichever finger stays, from where it is now: no jump.
                    val keep = if (gone == p0) p1 else p0
                    p0 = keep; p1 = -1
                    val i = e.findPointerIndex(keep)
                    if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active) {
                active = false
                onMoved?.invoke()
            }
        }
        return active
    }

    private fun startPair(e: MotionEvent) {
        val a = e.findPointerIndex(p0)
        val b = e.findPointerIndex(p1)
        if (a < 0 || b < 0) return
        lastX = (e.getX(a) + e.getX(b)) / 2f
        lastY = (e.getY(a) + e.getY(b)) / 2f
        lastSpan = hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a))
        lastAngle = atan2(e.getY(b) - e.getY(a), e.getX(b) - e.getX(a))
    }

    private fun move(e: MotionEvent) {
        val a = e.findPointerIndex(p0)
        if (a < 0) return
        if (p1 < 0) {
            place.postTranslate(e.getX(a) - lastX, e.getY(a) - lastY)
            lastX = e.getX(a); lastY = e.getY(a)
        } else {
            val b = e.findPointerIndex(p1)
            if (b < 0) return
            val cx = (e.getX(a) + e.getX(b)) / 2f
            val cy = (e.getY(a) + e.getY(b)) / 2f
            val span = hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a))
            val angle = atan2(e.getY(b) - e.getY(a), e.getX(b) - e.getX(a))
            place.postTranslate(cx - lastX, cy - lastY)
            if (lastSpan > 1f && span > 1f) {
                val s = (span / lastSpan).coerceIn(0.5f, 2f)
                // Never smaller than a thumbnail, never bigger than four screens.
                val now = currentScale()
                val clamped = (now * s).coerceIn(kit.dp(48f) / maxSide(), 4f * maxOf(width, height) / maxSide()) / now
                place.postScale(clamped, clamped, cx, cy)
            }
            place.postRotate(Math.toDegrees((angle - lastAngle).toDouble()).toFloat(), cx, cy)
            lastX = cx; lastY = cy; lastSpan = span; lastAngle = angle
        }
        invalidate()
    }

    private fun maxSide(): Float = bitmap?.let { maxOf(it.width, it.height).toFloat() } ?: 1f

    private fun currentScale(): Float {
        val v = FloatArray(9)
        place.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]).coerceAtLeast(1e-6f)
    }
}
