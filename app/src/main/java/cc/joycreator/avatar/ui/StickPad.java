package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import cc.joycreator.avatar.perform.Stick;

import com.fadcam.ui.faditor.Studio;

/**
 * The joystick's pad (132 dp, the head pad's size): push toward an edge and the body goes that way, faster the further out;
 * let go and it stops. A quick tap or flick is a gesture instead. The small button in the corner opens the gestures. All the
 * thinking is in {@link Stick}; this is its face and its touch.
 */
public final class StickPad extends View {

    public interface Listener {
        /** The gestures button in the pad's corner. */
        void openGestures();
    }

    private final Stick stick;
    private final Listener listener;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private int pointer = -1;
    private Stick.Touch touch;
    private boolean gearDown;

    public StickPad(Context c, Stick stick, Listener l) {
        super(c);
        this.stick = stick;
        this.listener = l;
        dp = c.getResources().getDisplayMetrics().density;
        fill.setColor(0xFF18181B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * dp);
        text.setFakeBoldText(true);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Math.round(132 * dp);
        setMeasuredDimension(s, s);
    }

    private RectF gear() {
        float s = 28 * dp, w = getWidth();
        return new RectF(w - s - 4 * dp, 4 * dp, w - 4 * dp, 4 * dp + s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), m = w / 2, r = w * 0.4f, rd = 14 * dp;
        c.drawRoundRect(new RectF(0, 0, w, w), rd, rd, fill);
        ring.setStrokeWidth(Math.max(1f, dp));
        ring.setColor(0xFF2C2C35);
        c.drawCircle(m, m, r, ring);
        c.drawCircle(m, m, r * 0.45f, ring);
        ring.setColor(0xFF71717A);
        ring.setStrokeWidth(1.6f * dp);
        float[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (float[] d : dirs) {
            float tx = m + d[0] * (r + 9 * dp), ty = m + d[1] * (r + 9 * dp), px = -d[1], py = d[0];
            c.drawLine(tx - d[0] * 5 * dp + px * 4 * dp, ty - d[1] * 5 * dp + py * 4 * dp, tx, ty, ring);
            c.drawLine(tx, ty, tx - d[0] * 5 * dp - px * 4 * dp, ty - d[1] * 5 * dp - py * 4 * dp, ring);
        }
        if (stick.depthOn()) floor(c, m, r, stick.phase(), stick.angle());      // in perspective: a thin floor under the knob
        boolean on = stick.pressed();
        float kx = m + (float) stick.knobX() * r, ky = m + (float) stick.knobY() * r;
        if (on) {
            ring.setColor(0x73000000 | (Studio.LIVE & 0xFFFFFF));
            ring.setStrokeWidth(3 * dp);
            c.drawLine(m, m, kx, ky, ring);
        }
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(on ? Studio.LIVE : 0xFF1C1C22);
        c.drawCircle(kx, ky, 13 * dp, knob);
        knob.setStyle(Paint.Style.STROKE);
        knob.setStrokeWidth(2 * dp);
        knob.setColor(Studio.LIVE);
        c.drawCircle(kx, ky, 13 * dp, knob);
        // the gestures button: sliders
        RectF g = gear();
        knob.setStrokeWidth(1.7f * dp);
        knob.setStrokeCap(Paint.Cap.ROUND);
        knob.setColor(gearDown ? 0xFFE4E4E7 : 0xFF71717A);
        float gx = g.centerX(), gy = g.centerY(), u = 17 * dp / 24f;
        c.drawLine(gx - 8 * u, gy - 5 * u, gx + 1 * u, gy - 5 * u, knob);
        c.drawLine(gx + 5 * u, gy - 5 * u, gx + 8 * u, gy - 5 * u, knob);
        c.drawLine(gx - 8 * u, gy + 5 * u, gx - 5 * u, gy + 5 * u, knob);
        c.drawLine(gx - 1 * u, gy + 5 * u, gx + 8 * u, gy + 5 * u, knob);
        c.drawLine(gx + 3 * u, gy - 7 * u, gx + 3 * u, gy - 3 * u, knob);
        c.drawLine(gx - 3 * u, gy + 3 * u, gx - 3 * u, gy + 7 * u, knob);
        String fl = stick.flash(System.currentTimeMillis());
        if (fl != null) {                                                // what a gesture just did, for a moment
            float tw = text.measureText(fl) + 12 * dp;
            fill.setColor(0xBF09090B);
            c.drawRect(m - tw / 2, w - 24 * dp, m + tw / 2, w - 8 * dp, fill);
            fill.setColor(0xFF18181B);
            text.setColor(Studio.LIVE);
            c.drawText(fl, m, w - 12 * dp, text);
        }
    }

    /**
     * The floor, so you can see the pad walks in depth: a square plane tilted to the camera's angle (a flat square grid overhead,
     * lines running away to a point when low), fading toward the pad's edge, drifting as the character walks in or out.
     */
    private void floor(Canvas g, float m, float r, double phase, double deg) {
        double th = Math.toRadians(Math.max(0, Math.min(75, deg)));
        float R = r * 1.25f, D = R * 2.6f, cs = (float) Math.cos(th), sn = (float) Math.sin(th);
        int rgb = Studio.ARMED & 0xFFFFFF;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.9f * dp);
        p.setShader(new android.graphics.RadialGradient(m, m, r * 1.12f, new int[]{0x8C000000 | rgb, 0x47000000 | rgb, rgb}, new float[]{0.13f, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        android.graphics.Path path = new android.graphics.Path();
        int L = 8, n = 6;
        for (int i = 0; i <= L; i++) {
            float u = -1 + 2f * i / L;
            float[] a = floorAt(u, -1, m, r, R, D, cs, sn), b = floorAt(u, 1, m, r, R, D, cs, sn);
            path.moveTo(a[0], a[1]);
            path.lineTo(b[0], b[1]);
        }
        for (int i = 0; i <= n; i++) {                                    // across, drifting toward you as it comes nearer
            double v = 1 - 2 * (((i + phase) % (n + 1)) / n);
            if (v < -1 || v > 1) continue;
            float[] a = floorAt(-1, (float) v, m, r, R, D, cs, sn), b = floorAt(1, (float) v, m, r, R, D, cs, sn);
            path.moveTo(a[0], a[1]);
            path.lineTo(b[0], b[1]);
        }
        g.drawPath(path, p);
    }

    private static float[] floorAt(float u, float v, float m, float r, float R, float D, float cs, float sn) {
        float X = u * R, Y = v * R * cs, Z = v * R * sn, f = D / (D + Z);
        return new float[]{m + X * f, m + r * 0.08f - Y * f};
    }

    /** Called each frame, so a flash shows and fades while no finger is on the pad. */
    public void tick() {
        if (stick.pressed() || stick.flash(System.currentTimeMillis()) != null) invalidate();
    }

    private double padX(MotionEvent e, int i) {
        return e.getX(i) / getWidth() * 2 - 1;
    }

    private double padY(MotionEvent e, int i) {
        return e.getY(i) / getWidth() * 2 - 1;
    }

    private void knobAt(MotionEvent e, int i) {
        double x = padX(e, i), y = padY(e, i), d = Math.hypot(x, y) / 0.8;
        if (d > 1) {
            x /= d;
            y /= d;
        }
        stick.knob(x / 0.8, y / 0.8);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if (gear().contains(e.getX(), e.getY())) {
                    gearDown = true;
                    invalidate();
                    return true;
                }
                pointer = e.getPointerId(0);
                stick.press();
                touch = stick.begin(System.currentTimeMillis(), padX(e, 0), padY(e, 0));
                knobAt(e, 0);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pointer);
                if (pointer >= 0 && i >= 0) {
                    knobAt(e, i);
                    touch.move(padX(e, i), padY(e, i));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gearDown) {
                    gearDown = false;
                    invalidate();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && gear().contains(e.getX(), e.getY()) && listener != null) listener.openGestures();
                    return true;
                }
                if (pointer >= 0) {
                    Stick.Touch t = touch;
                    pointer = -1;
                    touch = null;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) stick.lift(t, System.currentTimeMillis());
                    else stick.release();
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }
}
