package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The classic hue RING with a saturation/brightness TRIANGLE inside it.
 *
 * <p>Chosen over a square gradient because the triangle shares its geometry with the ring: the
 * hue vertex sits on the ring at the current angle, so turning the ring turns the triangle with
 * it and the two read as one instrument rather than two controls that happen to be stacked.</p>
 *
 * <p>Drawn, not composed from drawables, for one reason: a hue sweep and a two-axis interpolation
 * are pixel operations, and expressing them as nested views would mean either a bitmap per hue or
 * a stack of overlapping gradients that never quite line up at the vertices.</p>
 *
 * <p>Emits H/S/B in the ranges the rest of the picker speaks — hue 0..360, the other two 0..1 —
 * so nothing has to convert at the boundary.</p>
 */
public class ColorWheelView extends View {

    public interface OnColorChanged {
        /** Live during a drag. {@code h} 0..360, {@code s} and {@code b} 0..1. */
        void onColor(float h, float s, float b);
    }

    private static final float RING_THICKNESS_FRACTION = 0.16f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final float density;

    private float hue = 0f, sat = 1f, bri = 1f;
    @Nullable private OnColorChanged listener;
    /** Which instrument the in-flight gesture grabbed; a drag must not jump between them. */
    private boolean draggingRing, draggingTriangle;

    public ColorWheelView(@NonNull Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        markerStroke.setStyle(Paint.Style.STROKE);
        markerStroke.setStrokeWidth(2f * density);
        markerStroke.setColor(0xFF1F1F26);
        markerFill.setStyle(Paint.Style.FILL);
    }

    public void setListener(@Nullable OnColorChanged l) { listener = l; }

    /** Adopt an external colour without emitting — used when a swatch or the hex field wins. */
    public void setHsb(float h, float s, float b) {
        hue = h; sat = s; bri = b;
        invalidate();
    }

    public float hue() { return hue; }
    public float sat() { return sat; }
    public float bri() { return bri; }

    // ── Geometry ─────────────────────────────────────────────────────────────────────────

    private float radius() { return Math.min(getWidth(), getHeight()) / 2f - 2f * density; }
    private float ringWidth() { return radius() * RING_THICKNESS_FRACTION; }
    private float innerRadius() { return radius() - ringWidth(); }

    /**
     * The triangle's three corners: HUE at the current angle, WHITE 120° on, BLACK 120° further.
     * Written into {@code out} as x,y pairs.
     */
    private void corners(@NonNull float[] out) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = innerRadius() - 3f * density;
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(hue + i * 120f - 90f);
            out[i * 2] = cx + (float) Math.cos(a) * r;
            out[i * 2 + 1] = cy + (float) Math.sin(a) * r;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;

        // — hue ring —
        int[] sweep = new int[13];
        for (int i = 0; i < sweep.length; i++) {
            sweep[i] = Color.HSVToColor(new float[]{(i * 30f) % 360f, 1f, 1f});
        }
        ringPaint.setShader(new SweepGradient(cx, cy, sweep, null));
        ringPaint.setStrokeWidth(ringWidth());
        float rMid = radius() - ringWidth() / 2f;
        canvas.save();
        // -90 so hue 0 sits at the top, matching where the triangle's hue vertex is placed.
        canvas.rotate(-90f, cx, cy);
        canvas.drawCircle(cx, cy, rMid, ringPaint);
        canvas.restore();

        // — hue marker on the ring —
        double ha = Math.toRadians(hue - 90f);
        float hx = cx + (float) Math.cos(ha) * rMid;
        float hy = cy + (float) Math.sin(ha) * rMid;
        markerFill.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
        canvas.drawCircle(hx, hy, ringWidth() * 0.42f, markerFill);
        canvas.drawCircle(hx, hy, ringWidth() * 0.42f, markerStroke);

        // — saturation/brightness triangle —
        float[] c = new float[6];
        corners(c);
        triangle.reset();
        triangle.moveTo(c[0], c[1]);
        triangle.lineTo(c[2], c[3]);
        triangle.lineTo(c[4], c[5]);
        triangle.close();
        canvas.save();
        canvas.clipPath(triangle);
        // Two passes rather than a mesh: a linear ramp from the hue vertex to white, then a
        // radial black wash anchored on the black vertex. Cheap, and visually identical to the
        // barycentric interpolation at the sizes this is ever drawn.
        trianglePaint.setShader(new android.graphics.LinearGradient(
                c[0], c[1], c[2], c[3],
                Color.HSVToColor(new float[]{hue, 1f, 1f}), Color.WHITE, Shader.TileMode.CLAMP));
        canvas.drawPath(triangle, trianglePaint);
        float span = (float) Math.hypot(c[0] - c[4], c[1] - c[5]);
        shadePaint.setShader(new RadialGradient(c[4], c[5], Math.max(1f, span),
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawPath(triangle, shadePaint);
        canvas.restore();

        // — s/b marker —
        float[] p = pointFor(sat, bri);
        markerFill.setColor(Color.HSVToColor(new float[]{hue, sat, bri}));
        canvas.drawCircle(p[0], p[1], 7f * density, markerFill);
        canvas.drawCircle(p[0], p[1], 7f * density, markerStroke);
    }

    /** Barycentric: hue corner weighted by s*b, white by (1-s)*b, black by (1-b). */
    @NonNull
    private float[] pointFor(float s, float b) {
        float[] c = new float[6];
        corners(c);
        float wHue = s * b, wWhite = (1f - s) * b, wBlack = 1f - b;
        float sum = Math.max(0.0001f, wHue + wWhite + wBlack);
        return new float[]{
                (c[0] * wHue + c[2] * wWhite + c[4] * wBlack) / sum,
                (c[1] * wHue + c[3] * wWhite + c[5] * wBlack) / sum};
    }

    /** Inverse of {@link #pointFor} — solve the barycentric weights, then read s and b off them. */
    private void pickFromTriangle(float x, float y) {
        float[] c = new float[6];
        corners(c);
        float x1 = c[0], y1 = c[1], x2 = c[2], y2 = c[3], x3 = c[4], y3 = c[5];
        float det = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3);
        if (Math.abs(det) < 0.0001f) return;
        float l1 = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / det;
        float l2 = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / det;
        float l3 = 1f - l1 - l2;
        // Clamp INTO the triangle rather than rejecting the touch: a finger that slips a pixel
        // past an edge should pin the value at that edge, not freeze the drag.
        l1 = Math.max(0f, l1); l2 = Math.max(0f, l2); l3 = Math.max(0f, l3);
        float sum = Math.max(0.0001f, l1 + l2 + l3);
        l1 /= sum; l2 /= sum; l3 /= sum;
        bri = Math.max(0f, Math.min(1f, l1 + l2));
        sat = bri <= 0.0001f ? sat : Math.max(0f, Math.min(1f, l1 / bri));
        emit();
    }

    private void emit() {
        invalidate();
        if (listener != null) listener.onColor(hue, sat, bri);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float d = (float) Math.hypot(x - cx, y - cy);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Whichever instrument the finger STARTED on owns the whole gesture. Deciding
                // per-move instead would let a drag off the ring's inner edge silently become a
                // saturation drag, which feels like the control fighting you.
                draggingRing = d >= innerRadius() - 6f * density && d <= radius() + 8f * density;
                draggingTriangle = !draggingRing;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (draggingRing) {
                    hue = (float) ((Math.toDegrees(Math.atan2(y - cy, x - cx)) + 90f + 360f) % 360f);
                    emit();
                } else if (draggingTriangle) {
                    pickFromTriangle(x, y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingRing = draggingTriangle = false;
                performClick();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // Always square: the ring is a circle, and a non-square box would put the triangle's
        // vertices somewhere the hue marker is not.
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int side = Math.max(1, Math.min(w, h == 0 ? w : h));
        setMeasuredDimension(side, side);
    }
}
