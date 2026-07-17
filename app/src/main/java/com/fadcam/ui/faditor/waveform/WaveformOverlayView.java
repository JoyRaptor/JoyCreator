package com.fadcam.ui.faditor.waveform;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.WaveformData;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.model.WaveformStyle;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Editor preview layer for placed waveform/spectrum visualizers. Sized to the canvas (like the
 * player view) so an overlay's normalized placement maps straight to pixels. Driven by the
 * playhead — {@link #setPlayheadMs(long)} re-renders each active overlay at its current local
 * time via {@link WaveformStyleRenderer}, so it animates with playback and scrubbing.
 */
public class WaveformOverlayView extends View {

    private final WaveformStyleRenderer renderer = new WaveformStyleRenderer();
    private final Map<String, WaveformStyle> styles = new HashMap<>();
    private final float density;

    private List<WaveformOverlayInstance> overlays = Collections.emptyList();
    private Map<String, WaveformData> dataBySource = Collections.emptyMap();
    private final Map<String, Float> progressBySource = new HashMap<>();
    private long playheadMs = 0;

    /** Callbacks to the host so changes persist / overlays can be removed. */
    public interface OnChangeListener {
        void onWaveformChanged();
        void onWaveformDeleted(@NonNull WaveformOverlayInstance overlay);
        /** Re-tapping an already-selected visualizer — open the style chooser. */
        default void onWaveformTapped(@NonNull WaveformOverlayInstance overlay) {}
        /**
         * Hold on a visualizer — open its object menu (gesture contract §4.5;
         * delete lives inside it). Default falls back to the legacy instant
         * delete so other hosts keep working unchanged.
         */
        default void onWaveformLongPressed(@NonNull WaveformOverlayInstance overlay) {
            onWaveformDeleted(overlay);
        }
    }

    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private OnChangeListener changeListener;
    @Nullable private WaveformOverlayInstance selected;
    private int dragMode = 0; // 0 none, 1 move, 2 pinch-resize, 3 handle-resize
    private float downX, downY, startCenterX, startCenterY, startW, startH, startDist;
    private boolean movedSinceDown;
    private boolean reTapSelected; // the down hit was already the selected overlay
    @Nullable private Runnable longPressRunnable;
    // Handle resize state: which handle (1..8) + the fixed bounding-box edges at touch start.
    private int resizeHandle = 0; // 1=TL 2=TR 3=BL 4=BR 5=T 6=B 7=L 8=R
    private float resizeStartLeft, resizeStartTop, resizeStartRight, resizeStartBottom;

    public WaveformOverlayView(Context context) {
        this(context, null);
    }

    public WaveformOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.density = getResources().getDisplayMetrics().density;
        for (WaveformStyle s : WaveformStyleIO.loadBuiltins(context)) {
            styles.put(s.id, s);
        }
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setColor(0xFF2196F3);
        selectionPaint.setStrokeWidth(2f * density);
        selectionPaint.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFF2196F3);
        // Software layer: makes drawBitmap copy immediately (so the reused render bitmap is
        // safe across multiple overlays) and lets the glow BlurMaskFilter composite correctly.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setOnChangeListener(@Nullable OnChangeListener l) {
        this.changeListener = l;
    }

    /** Add/replace a user style not in the built-in set (from the Studio). */
    public void putStyle(@NonNull WaveformStyle style) {
        styles.put(style.id, style);
        invalidate();
    }

    public void setOverlays(@NonNull List<WaveformOverlayInstance> overlays) {
        this.overlays = overlays;
        invalidate();
    }

    /** Map of audioSourceRef → extracted data. */
    public void setData(@NonNull Map<String, WaveformData> dataBySource) {
        this.dataBySource = dataBySource;
        invalidate();
    }

    /** Report extraction progress (0..1) for a source so its placeholder fills as it decodes. */
    public void setExtractionProgress(@NonNull String sourceRef, float fraction) {
        progressBySource.put(sourceRef, fraction);
        invalidate();
    }

    public void setPlayheadMs(long ms) {
        this.playheadMs = ms;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || overlays.isEmpty()) return;
        for (WaveformOverlayInstance o : overlays) {
            if (playheadMs < o.getStartMs() || playheadMs > o.getEndMs()) continue;
            WaveformStyle style = styles.get(o.getStyleId());
            if (style == null) continue;
            style = o.applyOverrides(style);
            WaveformData data = o.getAudioSourceRef() != null
                    ? dataBySource.get(o.getAudioSourceRef()) : null;

            int rw = Math.max(1, (int) (o.getWidthFraction() * w));
            int rh = Math.max(1, (int) (o.getHeightFraction() * h));
            // Placeholder until the audio finishes extracting, then the live render.
            // renderReusable returns a shared, reused bitmap (no per-frame allocation);
            // it's drawn to the canvas immediately, so the next overlay can reuse it.
            Bitmap bmp;
            if (data == null) {
                Float prog = o.getAudioSourceRef() != null
                        ? progressBySource.get(o.getAudioSourceRef()) : null;
                bmp = renderer.renderPlaceholderReusable(style, rw, rh, density,
                        prog != null ? prog : -1f);
            } else {
                bmp = renderer.renderReusable(data, style, rw, rh,
                        o.mapToSourceMs(playheadMs), density,
                        o.getJustify(), o.getDataMode(), o.isHorizontalMirror(),
                        o.getCenterMode(), o.getRenderMode(), o.getRadialRingSize(),
                        o.getFrequencyRangeLowHz(), o.getFrequencyRangeHighHz(),
                        o.getBandCountOverride());
            }
            float cx = o.getCenterX() * w;
            float cy = o.getCenterY() * h;
            canvas.save();
            if (o.getRotationDeg() != 0f) canvas.rotate(o.getRotationDeg(), cx, cy);
            canvas.drawBitmap(bmp, cx - rw / 2f, cy - rh / 2f, null);
            if (o == selected) {
                float left = cx - rw / 2f, top = cy - rh / 2f;
                float right = cx + rw / 2f, bottom = cy + rh / 2f;
                canvas.drawRect(left, top, right, bottom, selectionPaint);
                // Draw corner + edge-midpoint handle dots so the user knows they can drag to resize.
                float hs = 6f * density; // visual handle radius
                float midX = (left + right) / 2f, midY = (top + bottom) / 2f;
                float[][] hp = {
                        {left, top}, {right, top}, {left, bottom}, {right, bottom}, // corners
                        {midX, top}, {midX, bottom}, {left, midY}, {right, midY}  // edge midpoints
                };
                for (float[] p : hp) canvas.drawCircle(p[0], p[1], hs, handlePaint);
            }
            canvas.restore();
        }
    }

    // ── Touch: select / move / pinch-resize / long-press delete ──────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float tx = e.getX(), ty = e.getY();
                // First: if an overlay is already selected, check for a resize-handle hit.
                if (selected != null && playheadMs >= selected.getStartMs() && playheadMs <= selected.getEndMs()) {
                    int handle = hitTestHandle(tx, ty, w, h, selected);
                    if (handle > 0) {
                        dragMode = 3;
                        resizeHandle = handle;
                        movedSinceDown = false;
                        float cx = selected.getCenterX() * w, cy = selected.getCenterY() * h;
                        float rw = selected.getWidthFraction() * w, rh = selected.getHeightFraction() * h;
                        resizeStartLeft = cx - rw / 2f;
                        resizeStartTop = cy - rh / 2f;
                        resizeStartRight = cx + rw / 2f;
                        resizeStartBottom = cy + rh / 2f;
                        cancelPendingLongPress();
                        return true;
                    }
                }
                WaveformOverlayInstance hit = hitTest(tx, ty, w, h);
                if (hit == null) {
                    if (selected != null) { selected = null; invalidate(); }
                    return false; // let the touch fall through to the views below
                }
                reTapSelected = (hit == selected);
                selected = hit;
                dragMode = 1;
                movedSinceDown = false;
                downX = tx;
                downY = ty;
                startCenterX = hit.getCenterX();
                startCenterY = hit.getCenterY();
                scheduleLongPress(hit);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (selected != null && e.getPointerCount() >= 2) {
                    cancelPendingLongPress();
                    dragMode = 2;
                    startDist = spacing(e);
                    startW = selected.getWidthFraction();
                    startH = selected.getHeightFraction();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected == null) return false;
                if (dragMode == 3) {
                    doHandleResize(e.getX(), e.getY(), w, h);
                    movedSinceDown = true;
                } else if (dragMode == 2 && e.getPointerCount() >= 2) {
                    float d = spacing(e);
                    float s = startDist > 0 ? d / startDist : 1f;
                    selected.setSize(startW * s, startH * s);
                    movedSinceDown = true;
                } else                 if (dragMode == 1) {
                    float dx = (e.getX() - downX) / w;
                    float dy = (e.getY() - downY) / h;
                    if (Math.abs(e.getX() - downX) > 8 || Math.abs(e.getY() - downY) > 8) {
                        movedSinceDown = true;
                        cancelPendingLongPress();
                    }
                    selected.setCenter(startCenterX + dx, startCenterY + dy);
                }
                clampToCanvas(selected);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                dragMode = 0; // require a fresh press to continue (avoids post-pinch jump)
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancelPendingLongPress();
                if (selected != null && changeListener != null) {
                    if (movedSinceDown) {
                        changeListener.onWaveformChanged();
                    } else if (reTapSelected && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        // Clean re-tap on an already-selected visualizer → open the style chooser.
                        changeListener.onWaveformTapped(selected);
                    }
                }
                dragMode = 0;
                return true;
            }
            default:
                return selected != null;
        }
    }

    private void scheduleLongPress(@NonNull WaveformOverlayInstance target) {
        cancelPendingLongPress();
        longPressRunnable = () -> {
            if (!movedSinceDown && selected == target && changeListener != null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                changeListener.onWaveformLongPressed(target);
            }
        };
        handler.postDelayed(longPressRunnable, 500);
    }

    private void cancelPendingLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private static float spacing(@NonNull MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    @Nullable
    private WaveformOverlayInstance hitTest(float x, float y, int w, int h) {
        for (int i = overlays.size() - 1; i >= 0; i--) {
            WaveformOverlayInstance o = overlays.get(i);
            if (playheadMs < o.getStartMs() || playheadMs > o.getEndMs()) continue;
            float cx = o.getCenterX() * w, cy = o.getCenterY() * h;
            float rw = o.getWidthFraction() * w, rh = o.getHeightFraction() * h;
            if (x >= cx - rw / 2f && x <= cx + rw / 2f && y >= cy - rh / 2f && y <= cy + rh / 2f) {
                return o;
            }
        }
        return null;
    }

    /** Hit-test the 8 resize handles of the selected overlay. Returns 1..8 or 0 if no hit. */
    private int hitTestHandle(float x, float y, int w, int h, @NonNull WaveformOverlayInstance o) {
        float cx = o.getCenterX() * w, cy = o.getCenterY() * h;
        float rw = o.getWidthFraction() * w, rh = o.getHeightFraction() * h;
        float left = cx - rw / 2f, top = cy - rh / 2f;
        float right = cx + rw / 2f, bottom = cy + rh / 2f;
        float midX = (left + right) / 2f, midY = (top + bottom) / 2f;
        float tol = 20f * density; // touch target radius
        float[][] hp = {
            {left, top}, {right, top}, {left, bottom}, {right, bottom}, // 1..4 corners
            {midX, top}, {midX, bottom}, {left, midY}, {right, midY}     // 5..8 edges
        };
        for (int i = 0; i < hp.length; i++) {
            if (Math.abs(x - hp[i][0]) <= tol && Math.abs(y - hp[i][1]) <= tol) return i + 1;
        }
        return 0;
    }

    /** Resize the selected overlay based on which handle is being dragged + current touch. */
    private void doHandleResize(float tx, float ty, int w, int h) {
        float left = resizeStartLeft, top = resizeStartTop;
        float right = resizeStartRight, bottom = resizeStartBottom;
        switch (resizeHandle) {
            case 1: left = tx; top = ty; break;                          // TL
            case 2: right = tx; top = ty; break;                          // TR
            case 3: left = tx; bottom = ty; break;                        // BL
            case 4: right = tx; bottom = ty; break;                        // BR
            case 5: top = ty; break;                                      // T
            case 6: bottom = ty; break;                                    // B
            case 7: left = tx; break;                                     // L
            case 8: right = tx; break;                                     // R
            default: return;
        }
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(w, right);
        bottom = Math.min(h, bottom);
        if (right <= left) right = left + 4 * density;
        if (bottom <= top) bottom = top + 4 * density;
        float newW = (right - left) / w;
        float newH = (bottom - top) / h;
        selected.setSize(newW, newH);
        selected.setCenter((left + right) / 2f / w, (top + bottom) / 2f / h);
    }

    private void clampToCanvas(@NonNull WaveformOverlayInstance o) {
        float hw = o.getWidthFraction() / 2f;
        float hh = o.getHeightFraction() / 2f;
        float cx = Math.max(hw, Math.min(1f - hw, o.getCenterX()));
        float cy = Math.max(hh, Math.min(1f - hh, o.getCenterY()));
        o.setCenter(cx, cy);
    }
}
