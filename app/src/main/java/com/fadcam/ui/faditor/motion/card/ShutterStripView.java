package com.fadcam.ui.faditor.motion.card;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.ShutterStripModel;
import com.fadcam.ui.type.Type;

/**
 * The Motion Blur card's shutter strip (STU-7 spec §K.1, owner pick B3 with B1's frame labels).
 *
 * <p>Time runs left to right around the playhead; the bright bar is when the "camera" is
 * looking. Pull an end to widen or narrow it, drag the middle to slide it earlier or later.
 * Ends snap to the film stops and to centred / behind / ahead, with a tick of haptics. Pulled
 * past the last frame the strip zooms out as you go and the window becomes a long trail, up to
 * 32 frames back (§K.2); its bar fades toward the past by the card's Fade. All the geometry is
 * {@link ShutterStripModel}, tested on the PC; this class only draws and routes touches.</p>
 */
public final class ShutterStripView extends View {

    /** Told on every change while dragging ({@code done == false}) and once on release. */
    public interface Listener {
        void onShutter(@NonNull MotionShutter.Controls controls, boolean done);
    }

    private static final int MAX_BACK = MotionShutter.MAX_TRAIL_ANGLE / 360;

    private final float d;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playhead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelNow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    @NonNull private MotionShutter.Controls controls = MotionShutter.Controls.NATURAL;
    @Nullable private Listener listener;

    @NonNull private ShutterStripModel.View view = new ShutterStripModel.View(1, 1);
    @Nullable private ShutterStripModel.Grab grab;
    @Nullable private MotionShutter.Controls dragStart;
    private double downDeg;

    public ShutterStripView(@NonNull Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        track.setColor(0x2EFFFFFF);                       // TRACK, white 18%
        tick.setColor(0x59FFFFFF);
        tick.setStrokeWidth(Math.max(1f, d));
        playhead.setColor(Studio.DRAWER_INK);
        playhead.setStrokeWidth(1.5f * d);
        handle.setColor(Studio.DRAWER_INK);
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;
        for (Paint p : new Paint[]{label, labelNow}) {
            p.setTypeface(Type.mono(ctx, Type.REGULAR));
            p.setTextSize(9f * sp);
        }
        label.setColor(Studio.alpha(Studio.DRAWER_LABEL, 0xB3));
        labelNow.setColor(Studio.DRAWER_DIM);
        setMinimumHeight(Math.round(46 * d));
        setFocusable(true);
    }

    public void setListener(@Nullable Listener l) { listener = l; }

    public void setControls(@NonNull MotionShutter.Controls c) {
        controls = c;
        if (grab == null) view = ShutterStripModel.viewFor(c);
        invalidate();
    }

    @NonNull public MotionShutter.Controls controls() { return controls; }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w),
                resolveSize(Math.round(46 * d), h));
    }

    private float left() { return 8 * d; }
    private float right() { return getWidth() - 8 * d; }
    private float xOf(double deg) {
        return (float) (left() + view.fraction(deg) * (right() - left()));
    }
    private double degOf(float x) {
        return view.degrees((x - left()) / Math.max(1f, right() - left()));
    }

    @Override protected void onDraw(@NonNull Canvas c) {
        float mid = 17 * d;
        r.set(left(), mid - 2 * d, right(), mid + 2 * d);
        c.drawRoundRect(r, 2 * d, 2 * d, track);
        // A tick at every whole frame; past 8 frames, every other one, so they stay apart.
        int step = view.back() + view.ahead() > 16 ? 2 : 1;
        for (int f = -view.back(); f <= view.ahead(); f += 1) {
            if (f == 0 || f % step != 0) continue;
            float x = xOf(f * 360.0);
            c.drawLine(x, mid - 9 * d, x, mid + 9 * d, tick);
        }
        double open = controls.phase();
        double close = open + controls.angle();
        float x0 = xOf(open), x1 = xOf(close);
        if (x1 - x0 > 0.5f) {
            // The bar fades toward the past by Fade: a long trail visibly thins out behind.
            int oldEnd = Studio.alpha(Studio.GO, (int) Math.round(255 * controls.fadeWeight(0)));
            bar.setShader(new LinearGradient(x0, 0, Math.max(x0 + 1, x1), 0,
                    new int[]{oldEnd, Studio.GO, Studio.GO_END}, new float[]{0f, 0.75f, 1f},
                    Shader.TileMode.CLAMP));
            r.set(x0, mid - 9 * d, x1, mid + 9 * d);
            c.drawRoundRect(r, 9 * d, 9 * d, bar);
        }
        float x = xOf(0);
        c.drawLine(x, mid - 15 * d, x, mid + 15 * d, playhead);
        drawHandle(c, x0, mid);
        if (!controls.isLongTrail()) drawHandle(c, x1, mid);

        float base = getHeight() - 4 * d;
        Context ctx = getContext();
        String back = view.back() == 1 ? ctx.getString(R.string.studio_motion_strip_last)
                : ctx.getString(R.string.studio_motion_strip_back, view.back());
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText(back, left(), base, label);
        String now = ctx.getString(R.string.studio_motion_strip_now);
        if (view.ahead() == 0) {
            labelNow.setTextAlign(Paint.Align.RIGHT);
            c.drawText(now, right(), base, labelNow);
            return;
        }
        String ahead = view.ahead() == 1 ? ctx.getString(R.string.studio_motion_strip_next)
                : ctx.getString(R.string.studio_motion_strip_ahead, view.ahead());
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText(ahead, right(), base, label);
        labelNow.setTextAlign(Paint.Align.CENTER);
        c.drawText(now, x, base, labelNow);
    }

    private void drawHandle(@NonNull Canvas c, float x, float mid) {
        r.set(x - 6 * d, mid - 13 * d, x + 6 * d, mid + 13 * d);
        c.drawRoundRect(r, 6 * d, 6 * d, handle);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(@NonNull MotionEvent e) {
        if (!isEnabled()) return false;
        double deg = degOf(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // A thumb is about 28dp wide: that much either side of an end grabs the end.
                double handleDeg = 28 * d / Math.max(1f, right() - left())
                        * 360.0 * (view.back() + view.ahead());
                grab = ShutterStripModel.grab(controls, deg, handleDeg);
                dragStart = controls;
                downDeg = deg;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (grab == null || dragStart == null) return false;
                // Pulling the opening end at or past the left edge shows one more frame back,
                // so a long trail is reached by pulling on; the other edges stop at two frames.
                if (grab == ShutterStripModel.Grab.OPEN && e.getX() <= left() + 4 * d
                        && view.back() < MAX_BACK) {
                    view = new ShutterStripModel.View(view.back() + 1, view.ahead());
                    deg = degOf(e.getX());
                } else if (grab != ShutterStripModel.Grab.MIDDLE && view.back() < 2
                        && Math.abs(deg) > 360.0 * view.back()) {
                    view = new ShutterStripModel.View(2, Math.max(view.ahead(), 2));
                    deg = degOf(e.getX());
                }
                int frames = view.frames();
                MotionShutter.Controls next = switch (grab) {
                    case OPEN -> ShutterStripModel.dragOpen(controls, deg, frames);
                    case CLOSE -> ShutterStripModel.dragClose(controls, deg, frames);
                    case MIDDLE -> ShutterStripModel.slide(dragStart, deg - downDeg, frames);
                };
                if (!next.equals(controls)) {
                    boolean snapped = next.timing() != controls.timing()
                            || isStop(next.angle()) && next.angle() != controls.angle()
                            || next.isLongTrail() != controls.isLongTrail();
                    controls = next;
                    if (snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    if (listener != null) listener.onShutter(controls, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = grab != null;
                grab = null;
                dragStart = null;
                view = ShutterStripModel.viewFor(controls);
                invalidate();
                if (was && listener != null) listener.onShutter(controls, true);
                return true;
            }
            default:
                return true;
        }
    }

    private static boolean isStop(int angle) {
        for (int s : ShutterStripModel.ANGLE_STOPS) if (s == angle) return true;
        return false;
    }
}
