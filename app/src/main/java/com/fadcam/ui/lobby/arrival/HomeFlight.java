package com.fadcam.ui.lobby.arrival;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Joybot's way home: when the chat closes he flies back into the button he came out of (owner, 11-15 midday: "not
 * just out"; "tapping X flies him straight back to where he started").
 *
 * <p>A plain view over the whole chat window, so the drawer can fold away under him and a floating card does not clip
 * him. He lifts off his seat with a squash, flies head first in a gentle arc with his thruster trailing, swings a
 * little toward us on the way, tumbles small into the button (his arrival's tumble, played backwards in spirit) and
 * leaves a pink ring where he went in. Drawn from his home pose and his tumble, the pictures the arrival flies with,
 * on the clock (he always animates).</p>
 */
final class HomeFlight extends View {

    private static final long LIFT_MS = 110L, RING_MS = 220L;
    private static final int TRAIL = 28;

    private final float d;
    private final Bitmap idle;
    private final int idleCols, idleRows;
    @Nullable private final Bitmap spin;
    private final int spinCols, spinRows, spinCount;
    private final RectF focus;
    /** dp of him per pixel of a cell (the arrival's {@link Show#cellScale}). */
    private final float cellDp;

    // his body centre and the button's centre, px on the screen (the window may change size under him: a card going
    // back to a full window so he can leave it)
    private float sx, sy, ex, ey, cx2, cy2;
    private long t0, fly;
    private int face = 1;
    @Nullable private Runnable done;
    private boolean running;

    private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final float[] trailX = new float[TRAIL], trailY = new float[TRAIL];
    private final long[] trailT = new long[TRAIL];
    private int trailNext;
    private final int[] at = new int[2];

    HomeFlight(@NonNull Context c, @NonNull Bitmap idle, int idleCols, int idleRows, @Nullable Bitmap spin, int spinCols,
               int spinRows, int spinCount, @NonNull RectF focus, float actorDp) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        this.idle = idle;
        this.idleCols = Math.max(1, idleCols);
        this.idleRows = Math.max(1, idleRows);
        this.spin = spin;
        this.spinCols = Math.max(1, spinCols);
        this.spinRows = Math.max(1, spinRows);
        this.spinCount = Math.max(1, spinCount);
        this.focus = focus;
        this.cellDp = Show.cellScale(idle.getWidth() / (float) this.idleCols, idle.getHeight() / (float) this.idleRows, focus, actorDp);
        glow.setStyle(Paint.Style.FILL);
        core.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** How long from lift-off to his going into the button, ms. */
    long flightMs() { return LIFT_MS + fly; }

    /**
     * Fly from his body centre ({@code fromX, fromY}) to the button's centre ({@code toX, toY}), screen px; {@code then}
     * runs once the ring where he went in has faded.
     */
    void go(float fromX, float fromY, float toX, float toY, @NonNull Runnable then) {
        sx = fromX;
        sy = fromY;
        ex = toX;
        ey = toY;
        float dist = (float) Math.hypot(ex - sx, ey - sy) / d;
        fly = Math.round(Flight.clamp(dist / 2.4f, 360f, 560f));
        face = ex < sx ? -1 : 1;
        // a gentle bow to the arc, away from the straight line on the outside of the turn
        float mx = (sx + ex) / 2f, my = (sy + ey) / 2f, nx = -(ey - sy), ny = ex - sx;
        float len = (float) Math.max(1.0, Math.hypot(nx, ny));
        float bow = .14f * (float) Math.hypot(ex - sx, ey - sy) * (ny > 0f ? -1f : 1f);  // bow upward
        cx2 = mx + nx / len * bow;
        cy2 = my + ny / len * bow;
        done = then;
        t0 = SystemClock.uptimeMillis();
        running = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!running) return;
        long t = SystemClock.uptimeMillis() - t0;
        getLocationOnScreen(at);
        canvas.save();
        canvas.translate(-at[0], -at[1]);                      // draw in screen px
        long end = LIFT_MS + fly;
        if (t < end) drawHim(canvas, t);
        drawTrail(canvas);
        if (t >= end) drawRing(canvas, t - end);
        canvas.restore();
        if (t < end + RING_MS) {
            postInvalidateOnAnimation();
        } else {
            running = false;
            Runnable r = done;
            done = null;
            if (r != null) post(r);
        }
    }

    private void drawHim(@NonNull Canvas canvas, long t) {
        float x, y, rot = 0f, sxq = 1f, syq = 1f, scale = 1f, alpha = 1f;
        boolean tumbling = false;
        int frame = 0;
        if (t < LIFT_MS) {
            // a squash down, then up off the seat
            float u = t / (float) LIFT_MS;
            float q = (float) Math.sin(Math.PI * Math.min(1f, u * 1.6f));
            sxq = 1f + .10f * q * (u < .6f ? 1f : -1f);
            syq = 1f - .10f * q * (u < .6f ? 1f : -1f);
            x = sx;
            y = sy - 8f * d * Flight.easeOut(u);
        } else {
            float k = Flight.clamp((t - LIFT_MS) / (float) fly, 0f, 1f);
            float u = Flight.easeIO(k);
            float a = 1f - u;
            float y0 = sy - 8f * d;
            x = a * a * sx + 2f * a * u * cx2 + u * u * ex;
            y = a * a * y0 + 2f * a * u * cy2 + u * u * ey;
            // head first along the way he is going (0 = upright), righting nothing: he dives into the button
            float tx = 2f * a * (cx2 - sx) + 2f * u * (ex - cx2), ty = 2f * a * (cy2 - y0) + 2f * u * (ey - cy2);
            float head = (float) Math.toDegrees(Math.atan2(ty, tx)) + 90f;
            while (head > 180f) head -= 360f;
            while (head < -180f) head += 360f;
            rot = head * Flight.smooth(k / .25f);
            float s = (float) Math.sin(Math.PI * k);
            sxq = 1f - .07f * s;
            syq = 1f + .10f * s;
            scale = (1f + .14f * s) * Flight.lerp(1f, .22f, Flight.smooth((k - .62f) / .38f));   // toward us, then into it
            alpha = 1f - Flight.smooth((k - .86f) / .14f);
            tumbling = spin != null && k > .62f;
            frame = (int) ((t - LIFT_MS - .62f * fly) / 55L);
            // his thruster, from his peg
            double r = Math.toRadians(rot);
            float peg = cellDp * (idle.getHeight() / (float) idleRows) * (focus.bottom - focus.centerY()) * d * scale;
            emit(x - (float) Math.sin(r) * peg, y + (float) Math.cos(r) * peg);
        }
        Bitmap b = tumbling ? spin : idle;
        int cols = tumbling ? spinCols : idleCols, rows = tumbling ? spinRows : idleRows;
        int i = tumbling ? Math.floorMod(frame, spinCount) : 0;
        int cw = b.getWidth() / cols, ch = b.getHeight() / rows;
        src.set((i % cols) * cw, (i / cols) * ch, (i % cols + 1) * cw, (i / cols + 1) * ch);
        float px = cellDp * d;                                 // screen px per cell px
        float left = -cw * focus.centerX() * px, top = -ch * focus.centerY() * px;
        dst.set(left, top, left + cw * px, top + ch * px);
        pic.setAlpha(Math.round(255 * alpha));
        int s = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rot);
        canvas.scale(sxq * scale * face, syq * scale);
        canvas.drawBitmap(b, src, dst, pic);
        canvas.restoreToCount(s);
    }

    private void emit(float x, float y) {
        trailX[trailNext] = x;
        trailY[trailNext] = y;
        trailT[trailNext] = SystemClock.uptimeMillis();
        trailNext = (trailNext + 1) % TRAIL;
    }

    /** His plasma behind him: white-hot puffs cooling to pink. */
    private void drawTrail(@NonNull Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < TRAIL; i++) {
            if (trailT[i] == 0L) continue;
            float age = (now - trailT[i]) / 280f;
            if (age >= 1f) { trailT[i] = 0L; continue; }
            float a = (1f - age) * (1f - age);
            glow.setColor(Color.argb(Math.round(255 * .30f * a), 255, 40, 190));
            canvas.drawCircle(trailX[i], trailY[i], (6f - 2f * age) * d, glow);
            core.setColor(Color.argb(Math.round(255 * a), 255, Math.round(150 + 90 * (1f - age)), 240));
            canvas.drawCircle(trailX[i], trailY[i], Math.max(.8f, 2.2f * (1f - age)) * d, core);
        }
    }

    /** A pink ring where he went into the button. */
    private void drawRing(@NonNull Canvas canvas, long t) {
        float u = Flight.clamp(t / (float) RING_MS, 0f, 1f);
        float r = (4f + 16f * Flight.easeOut(u)) * d, a = (1f - u) * (1f - u);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth((6f - 3f * u) * d);
        glow.setColor(Color.argb(Math.round(255 * .35f * a), 255, 40, 190));
        canvas.drawCircle(ex, ey, r, glow);
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeWidth(1.6f * d);
        core.setColor(Color.argb(Math.round(255 * a), 255, 190, 245));
        canvas.drawCircle(ex, ey, r, core);
        glow.setStyle(Paint.Style.FILL);
        core.setStyle(Paint.Style.FILL);
    }
}
