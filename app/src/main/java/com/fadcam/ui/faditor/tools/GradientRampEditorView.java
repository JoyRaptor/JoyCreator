package com.fadcam.ui.faditor.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.GradientRamp;

/**
 * THE standard gradient ramp editor, app-wide (JoyRaptor, 2026-08-08): colour stops as circles along
 * the bottom of the bar, opacity stops as squares along the top with bias diamonds between them,
 * mirror/flip/solid-bands checkboxes, and a live gradient-bar preview that never lies about what
 * the shader will draw — {@link GradientRamp#sampleColor} and {@link GradientRamp#sampleAlpha}
 * are the SAME segment math {@code FxCompiler}'s {@code fxGradColor}/{@code fxGradAlpha} run, so
 * this bar is not an approximation of the render, it IS the render's own formula evaluated on
 * the CPU.
 *
 * <p><b>Gestures, exactly as specified:</b></p>
 * <ul>
 *   <li>Tap the bar → add a colour stop there, interpolated from the ramp already showing.</li>
 *   <li>Tap a colour circle → {@link ColorPickerDialog}, live.</li>
 *   <li>Drag a colour circle or an opacity square horizontally → reposition, with snapping to
 *       0%/50%/100% and to any OTHER stop already on that track.</li>
 *   <li>Drag a colour circle or an opacity square vertically off its row → delete it (below the
 *       two-stop floor, {@link GradientRamp} itself refuses and the marker snaps back).</li>
 *   <li>Drag a bias diamond horizontally → rebias the segment; it stays exactly between its two
 *       stops because its drawn position is always {@code lerp(left, right, biasToNext)} —
 *       "keeps its ratio" is a property of how it is DRAWN, not something tracked separately.</li>
 * </ul>
 *
 * <p><b>No Set/Cancel in here.</b> Every gesture calls {@link OnLiveChange} immediately, the same
 * contract {@link ColorPickerDialog}'s {@code OnLive} makes: whatever hosts this widget owns the
 * commit/undo boundary, exactly as {@code FxPanel} does by wrapping one editing session — from
 * the dialog opening to it closing — in one {@code structural()} step.</p>
 */
public final class GradientRampEditorView extends LinearLayout {

    public interface OnLiveChange { void onChange(@NonNull GradientRamp ramp); }

    private static final float SNAP_DP = 7f;
    /** Vertical drag (dp) past the stop's row before the stop is marked for deletion on release.
     *  60dp ≈ a deliberate, full-finger pull — JoyRaptor (2026-08-08): "polling swatches off is a
     *  little sensitive… takes a little bit more vertical movement to delete". */
    private static final float DELETE_DP = 60f;

    @NonNull private GradientRamp ramp = GradientRamp.defaultRamp();
    @Nullable private OnLiveChange listener;

    private final RampBar bar;
    private final CheckBox mirrorBox, flipBox, solidBox;

    public GradientRampEditorView(@NonNull Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        float d = ctx.getResources().getDisplayMetrics().density;

        bar = new RampBar(ctx);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(96 * d)));

        LinearLayout checks = new LinearLayout(ctx);
        checks.setOrientation(HORIZONTAL);
        checks.setGravity(Gravity.CENTER_VERTICAL);
        checks.setPadding(0, Math.round(4 * d), 0, 0);
        mirrorBox = checkRow(ctx, checks, "Mirror", d);
        flipBox = checkRow(ctx, checks, "Flip", d);
        solidBox = checkRow(ctx, checks, "Solid bands", d);
        addView(checks);

        mirrorBox.setOnCheckedChangeListener((v, on) -> { ramp.mirror = on; fire(); });
        flipBox.setOnCheckedChangeListener((v, on) -> { ramp.flip = on; fire(); });
        solidBox.setOnCheckedChangeListener((v, on) -> { ramp.solidBands = on; fire(); });
    }

    @NonNull
    private static CheckBox checkRow(@NonNull Context ctx, @NonNull LinearLayout row,
                                     @NonNull String label, float d) {
        CheckBox box = new CheckBox(ctx);
        box.setText(label);
        box.setTextColor(0xFFC4C4CE);
        box.setTextSize(11.5f);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(10 * d);
        row.addView(box, lp);
        return box;
    }

    public void setRamp(@NonNull GradientRamp r) {
        ramp = r;
        mirrorBox.setChecked(r.mirror);
        flipBox.setChecked(r.flip);
        solidBox.setChecked(r.solidBands);
        bar.invalidate();
    }

    @NonNull
    public GradientRamp getRamp() { return ramp; }

    public void setOnLiveChangeListener(@Nullable OnLiveChange l) { listener = l; }

    private void fire() {
        if (listener != null) listener.onChange(ramp);
        bar.invalidate();
    }

    // ── The bar: gradient preview + both stop tracks, self-contained touch handling ───────────

    private final class RampBar extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barRect = new RectF();
        private final float d;

        private static final int MODE_NONE = 0, MODE_COLOR = 1, MODE_OPACITY = 2, MODE_BIAS = 3;
        private int dragMode = MODE_NONE;
        private int dragIndex = -1;
        private float dragDy;
        // W5-1: the stop's position at drag start, so a REFUSED delete (the two-stop floor) can
        // restore it. The class doc promises "the marker snaps back" but the code never did —
        // drag a stop of a 2-stop ramp off the bar and it stayed wherever the finger left it.
        private float dragStartPos = -1f;
        // W5-1 design review: the tap-vs-drag decision needs the finger's TOTAL travel from its
        // down-point, not a dy-from-the-row check. The old `|dragDy| < 3dp` gate meant any
        // ordinary 4-7dp vertical jitter while tapping a colour stop silently turned the tap
        // into a drag — the stop nudged a pixel and the colour picker never opened. Touch slop
        // is the OS's own "you meant to tap" yardstick; use it.
        private float downX, downY;
        private float touchSlop;

        RampBar(@NonNull Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            touchSlop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.4f * d);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        // Row geometry, top to bottom.
        private float opacityY() { return 20f * d; }
        private float barTop() { return 34f * d; }
        private float barBottom() { return 64f * d; }
        private float colorY() { return 80f * d; }
        private float stopR() { return 9f * d; }
        private float diamondR() { return 6f * d; }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = stopR() + 3f * d;
            barRect.set(pad, barTop(), Math.max(pad + 1, w - pad), barBottom());
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            // Checkerboard under the bar, so a transparent stretch of the opacity ramp reads as
            // transparent rather than as an unexplained dark band.
            drawCheckerboard(c);

            int n = 64;
            int[] colors = new int[n + 1];
            float[] pos = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                int rgb = ramp.sampleColor(t);
                int a = Math.round(ramp.sampleAlpha(t) * 255f);
                colors[i] = (a << 24) | (rgb & 0xF4F4F5);
                pos[i] = t;
            }
            barPaint.setShader(new LinearGradient(barRect.left, 0, barRect.right, 0,
                    colors, pos, Shader.TileMode.CLAMP));
            float r = 6f * d;
            c.drawRoundRect(barRect, r, r, barPaint);
            strokePaint.setColor(0x55FFFFFF);
            c.drawRoundRect(barRect, r, r, strokePaint);

            // Opacity track: squares on the stops, diamonds biased between them.
            for (int i = 0; i < ramp.opacityStops.size() - 1; i++) {
                GradientRamp.OpacityStop a = ramp.opacityStops.get(i);
                GradientRamp.OpacityStop b = ramp.opacityStops.get(i + 1);
                float ax = xOf(a.pos), bx = xOf(b.pos);
                float dx = ax + (bx - ax) * clamp01(a.biasToNext);
                drawDiamond(c, dx, opacityY(), diamondR(),
                        dragMode == MODE_BIAS && dragIndex == i);
            }
            for (int i = 0; i < ramp.opacityStops.size(); i++) {
                GradientRamp.OpacityStop s = ramp.opacityStops.get(i);
                float x = xOf(s.pos);
                float y = opacityY() + (dragMode == MODE_OPACITY && dragIndex == i ? dragDy : 0f);
                boolean marked = dragMode == MODE_OPACITY && dragIndex == i
                        && Math.abs(dragDy) > DELETE_DP * d;
                int gray = Math.round(s.alpha * 255f);
                fillPaint.setColor(0xFF000000 | (gray << 16) | (gray << 8) | gray);
                fillPaint.setAlpha(marked ? 90 : 255);
                float half = stopR() * 0.72f;
                RectF sq = new RectF(x - half, y - half, x + half, y + half);
                c.save();
                c.rotate(45f, x, y);
                c.drawRect(new RectF(x - half * 0.72f, y - half * 0.72f,
                        x + half * 0.72f, y + half * 0.72f), fillPaint);
                c.restore();
                strokePaint.setColor(0xFFF4F4F5);
                strokePaint.setAlpha(marked ? 90 : 255);
                c.save();
                c.rotate(45f, x, y);
                c.drawRect(new RectF(x - half * 0.72f, y - half * 0.72f,
                        x + half * 0.72f, y + half * 0.72f), strokePaint);
                c.restore();
            }

            // Colour track: circles.
            for (int i = 0; i < ramp.colorStops.size(); i++) {
                GradientRamp.ColorStop s = ramp.colorStops.get(i);
                float x = xOf(s.pos);
                float y = colorY() + (dragMode == MODE_COLOR && dragIndex == i ? dragDy : 0f);
                boolean marked = dragMode == MODE_COLOR && dragIndex == i
                        && Math.abs(dragDy) > DELETE_DP * d;
                fillPaint.setColor(0xFF000000 | (s.color & 0xF4F4F5));
                fillPaint.setAlpha(marked ? 90 : 255);
                c.drawCircle(x, y, stopR(), fillPaint);
                strokePaint.setColor(0xFFF4F4F5);
                strokePaint.setAlpha(marked ? 90 : 255);
                c.drawCircle(x, y, stopR(), strokePaint);
            }
        }

        private void drawDiamond(@NonNull Canvas c, float cx, float cy, float rr, boolean hot) {
            Path p = new Path();
            p.moveTo(cx, cy - rr);
            p.lineTo(cx + rr, cy);
            p.lineTo(cx, cy + rr);
            p.lineTo(cx - rr, cy);
            p.close();
            fillPaint.setColor(hot ? 0xFFFBBF24 : 0xFF8A8A94);
            fillPaint.setAlpha(255);
            c.drawPath(p, fillPaint);
            strokePaint.setColor(0xFF16161B);
            strokePaint.setAlpha(255);
            c.drawPath(p, strokePaint);
        }

        private void drawCheckerboard(@NonNull Canvas c) {
            float cell = 6f * d;
            checkerPaint.setColor(0xFF33333C);
            c.save();
            c.clipRect(barRect);
            c.drawColor(0xFF2C2C35);
            boolean toggle = false;
            for (float x = barRect.left; x < barRect.right; x += cell) {
                toggle = !toggle;
                boolean rowToggle = toggle;
                for (float y = barRect.top; y < barRect.bottom; y += cell) {
                    if (rowToggle) c.drawRect(x, y, x + cell, y + cell, checkerPaint);
                    rowToggle = !rowToggle;
                }
            }
            c.restore();
        }

        private float xOf(float pos) { return barRect.left + pos * barRect.width(); }

        private float posOf(float x) {
            return clamp01((x - barRect.left) / Math.max(1f, barRect.width()));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(96 * d));
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent ev) {
            float x = ev.getX(), y = ev.getY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragDy = 0f;
                    downX = x; downY = y;
                    int bi = hitBias(x, y);
                    if (bi >= 0) { dragMode = MODE_BIAS; dragIndex = bi; dragStartPos = -1f; return true; }
                    int oi = hitOpacity(x, y);
                    if (oi >= 0) {
                        dragMode = MODE_OPACITY; dragIndex = oi;
                        dragStartPos = ramp.opacityStops.get(oi).pos;
                        return true;
                    }
                    int ci = hitColor(x, y);
                    if (ci >= 0) {
                        dragMode = MODE_COLOR; dragIndex = ci;
                        dragStartPos = ramp.colorStops.get(ci).pos;
                        return true;
                    }
                    if (barRect.contains(x, Math.max(barRect.top, Math.min(barRect.bottom, y)))) {
                        // TAP THE BAR → add a stop there, interpolated from what is showing.
                        float t = posOf(x);
                        if (ramp.addColorStop(t, ramp.sampleColor(t))) fire();
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragMode == MODE_NONE) return false;
                    if (dragMode == MODE_BIAS) {
                        GradientRamp.OpacityStop a = ramp.opacityStops.get(dragIndex);
                        GradientRamp.OpacityStop b = ramp.opacityStops.get(dragIndex + 1);
                        float ax = xOf(a.pos), bx = xOf(b.pos);
                        float f = (bx - ax) != 0 ? (x - ax) / (bx - ax) : 0.5f;
                        a.biasToNext = clamp01(f);
                        fire();
                        return true;
                    }
                    dragDy = y - (dragMode == MODE_COLOR ? colorY() : opacityY());
                    float t = snap(posOf(x), dragMode);
                    if (dragMode == MODE_COLOR) ramp.moveColorStop(dragIndex, t);
                    else ramp.moveOpacityStop(dragIndex, t);
                    fire();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // W5-1: a colour-stop TAP is a release whose finger barely moved — measured
                    // as total travel from the DOWN point (touch slop), not the stop's row. The
                    // old gate only compared |dragDy| against 3dp, so a 4-7dp jitter that stayed
                    // within the hit circle was still "a drag" and the picker never opened.
                    boolean tappedColor = dragMode == MODE_COLOR
                            && ev.getActionMasked() == MotionEvent.ACTION_UP
                            && Math.abs(x - downX) < touchSlop
                            && Math.abs(y - downY) < touchSlop;
                    if (dragMode == MODE_COLOR && Math.abs(dragDy) > DELETE_DP * d) {
                        // Two-stop floor: GradientRamp refuses the delete — the marker must
                        // SNAP BACK to where the finger picked it up, not stay where it was
                        // dropped (the class doc promises this; it never happened).
                        if (!ramp.removeColorStop(dragIndex) && dragStartPos >= 0f) {
                            ramp.moveColorStop(dragIndex, dragStartPos);
                        }
                    } else if (dragMode == MODE_OPACITY && Math.abs(dragDy) > DELETE_DP * d) {
                        if (!ramp.removeOpacityStop(dragIndex) && dragStartPos >= 0f) {
                            ramp.moveOpacityStop(dragIndex, dragStartPos);
                        }
                    } else if (tappedColor) {
                        openPicker(dragIndex);
                    }
                    boolean changed = dragMode != MODE_NONE;
                    dragMode = MODE_NONE;
                    dragIndex = -1;
                    dragDy = 0f;
                    dragStartPos = -1f;
                    if (changed) fire();
                    return true;
                }
                default:
                    return false;
            }
        }

        private float snap(float t, int mode) {
            float bestD = SNAP_DP * d / Math.max(1f, barRect.width());
            float best = t;
            float[] anchors = {0f, 0.5f, 1f};
            for (float a : anchors) if (Math.abs(t - a) < bestD) { bestD = Math.abs(t - a); best = a; }
            java.util.List<?> stops = mode == MODE_COLOR ? ramp.colorStops : ramp.opacityStops;
            for (int i = 0; i < stops.size(); i++) {
                if (i == dragIndex) continue;
                float p = mode == MODE_COLOR
                        ? ramp.colorStops.get(i).pos : ramp.opacityStops.get(i).pos;
                if (Math.abs(t - p) < bestD) { bestD = Math.abs(t - p); best = p; }
            }
            return best;
        }

        private void openPicker(int index) {
            if (index < 0 || index >= ramp.colorStops.size()) return;
            GradientRamp.ColorStop stop = ramp.colorStops.get(index);
            int initial = 0xFF000000 | (stop.color & 0xF4F4F5);
            ColorPickerDialog.show(getContext(), "Stop color", initial, false,
                    live -> {
                        if (live != null) { stop.color = live; fire(); }
                    },
                    picked -> {
                        if (picked != null) { stop.color = picked; fire(); }
                    });
        }

        private int hitColor(float x, float y) {
            float touchR = stopR() * 1.6f;
            for (int i = 0; i < ramp.colorStops.size(); i++) {
                float sx = xOf(ramp.colorStops.get(i).pos);
                if (dist(x, y, sx, colorY()) <= touchR) return i;
            }
            return -1;
        }

        private int hitOpacity(float x, float y) {
            float touchR = stopR() * 1.6f;
            for (int i = 0; i < ramp.opacityStops.size(); i++) {
                float sx = xOf(ramp.opacityStops.get(i).pos);
                if (dist(x, y, sx, opacityY()) <= touchR) return i;
            }
            return -1;
        }

        private int hitBias(float x, float y) {
            float touchR = diamondR() * 1.8f;
            for (int i = 0; i < ramp.opacityStops.size() - 1; i++) {
                GradientRamp.OpacityStop a = ramp.opacityStops.get(i);
                GradientRamp.OpacityStop b = ramp.opacityStops.get(i + 1);
                float ax = xOf(a.pos), bx = xOf(b.pos);
                float dx = ax + (bx - ax) * clamp01(a.biasToNext);
                if (dist(x, y, dx, opacityY()) <= touchR) return i;
            }
            return -1;
        }

        private float dist(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2, dy = y1 - y2;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }
}
