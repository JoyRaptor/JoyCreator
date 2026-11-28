package cc.joycreator.avatar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.fadcam.ui.faditor.Studio;

import java.util.ArrayList;
import java.util.List;

/**
 * The head pad (132 dp square): a drag turns the head, left and right across, up and down. The drawings of the head show
 * as faint dots where they sit on the pad; the finger is a solid cyan dot. A finger on it owns the head until it lifts
 * (many fingers: this one owns the head, others can hold a hand or the joystick).
 */
public final class HeadPad extends View {

    public interface Listener {
        /** A finger is on the pad at x, y (-1..1). */
        void head(double x, double y);

        /** The finger lifted. */
        void released();
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[]> angles = new ArrayList<>();
    private final float dp;
    private Listener listener;
    private float x, y;
    private boolean down;
    private int pointer = -1;

    public HeadPad(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        fill.setColor(Studio.PANEL);
        line.setColor(Studio.PRESSED);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, dp));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Where the head's drawings sit on the pad (-1..1 each way). */
    public void setAngles(List<float[]> at) {
        angles.clear();
        angles.addAll(at);
        invalidate();
    }

    /** Where the head is now (so the knob follows a take that is playing). */
    public void setHead(double hx, double hy) {
        if (down) return;
        x = (float) hx;
        y = (float) hy;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Math.round(132 * dp);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = 14 * dp;
        c.drawRoundRect(new RectF(0, 0, w, h), r, r, fill);
        c.drawLine(w / 2, 10 * dp, w / 2, h - 10 * dp, line);
        c.drawLine(10 * dp, h / 2, w - 10 * dp, h / 2, line);
        c.drawRoundRect(new RectF(dp, dp, w - dp, h - dp), r, r, line);
        dot.setColor(Studio.INK_FAINT);
        for (float[] a : angles) c.drawCircle(toPx(a[0], w), toPx(a[1], h), 3 * dp, dot);
        dot.setColor(Studio.ARMED);
        c.drawCircle(toPx(x, w), toPx(y, h), (down ? 9 : 7) * dp, dot);
    }

    private float toPx(float v, float size) {
        float pad = 12 * dp;
        return pad + (v + 1f) / 2f * (size - 2 * pad);
    }

    private float fromPx(float p, float size) {
        float pad = 12 * dp;
        return Math.max(-1f, Math.min(1f, (p - pad) / (size - 2 * pad) * 2f - 1f));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                pointer = e.getPointerId(0);
                down = true;
                move(e, 0);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pointer);
                if (down && i >= 0) move(e, i);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (down) {
                    down = false;
                    pointer = -1;
                    invalidate();
                    if (listener != null) listener.released();
                }
                return true;
            default:
                return true;
        }
    }

    private void move(MotionEvent e, int i) {
        x = fromPx(e.getX(i), getWidth());
        y = fromPx(e.getY(i), getHeight());
        invalidate();
        if (listener != null) listener.head(x, y);
    }
}
