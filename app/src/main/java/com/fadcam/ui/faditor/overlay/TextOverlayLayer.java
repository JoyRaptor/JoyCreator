package com.fadcam.ui.faditor.overlay;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent layer placed over the video preview that renders editable text
 * overlays. Each overlay is a {@link TextView} the user can drag to move, pinch
 * to scale, and tap to edit. Empty areas pass touches through to the player.
 */
public class TextOverlayLayer extends FrameLayout {

    public interface Callback {
        /** Pixel rect of the visible video content inside this layer's bounds. */
        @NonNull RectF getVideoContentRect();
        /** An overlay's position/size/text changed — persist it. */
        void onOverlayChanged();
        /** User tapped an overlay — open the editor for it. */
        void onEditRequested(@NonNull TextOverlayItem item);
        /**
         * A drag/pinch gesture on {@code item} finished, mutating its transform
         * (and possibly adding a keyframe). {@code before} is the snapshot taken
         * when the gesture started — record a single undo step from it. Default
         * no-op so existing callers need not implement it.
         */
        default void onOverlayManipulated(@NonNull TextOverlayItem item,
                                          @NonNull TextOverlayItem.TransformSnapshot before) { }
    }

    private final List<TextOverlayItem> overlays = new ArrayList<>();
    @Nullable private Callback callback;
    /** Current timeline time (ms) used to evaluate overlay time-ranges + keyframes. */
    private long currentTimeMs = 0;
    /** Overlay being actively dragged/scaled — shown at its static transform. */
    @Nullable private TextOverlayItem manipulating;
    private boolean snapEnabled = true;
    private static final float SNAP_THRESHOLD = 0.045f;
    private static final long TIME_SNAP_MS = 250L;

    public void setSnapEnabled(boolean enabled) {
        this.snapEnabled = enabled;
    }

    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    public TextOverlayLayer(Context context) { super(context); }
    public TextOverlayLayer(Context context, AttributeSet attrs) { super(context, attrs); }
    public TextOverlayLayer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setData(@NonNull List<TextOverlayItem> overlays, @NonNull Callback cb) {
        this.overlays.clear();
        this.overlays.addAll(overlays);
        this.callback = cb;
        rebuild();
    }

    /** Recreate all overlay views from the model (call after data or size changes). */
    public void rebuild() {
        removeAllViews();
        if (callback == null) return;
        for (TextOverlayItem o : overlays) {
            View v = createOverlayView(o);
            v.setTag(o);
            addView(v);
        }
    }

    /**
     * Update the timeline time and re-evaluate every overlay's time-range,
     * keyframed position/size/rotation, and opacity — without rebuilding views.
     */
    public void setPlayheadMs(long timelineMs) {
        currentTimeMs = timelineMs;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof TextOverlayItem) {
                position(v, (TextOverlayItem) tag);
            }
        }
    }

    private View createOverlayView(@NonNull TextOverlayItem o) {
        View view;
        if (o.isImage()) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            try {
                iv.setImageURI(Uri.parse(o.getImageUri()));
            } catch (Exception ignored) { }
            view = iv;
        } else {
            TextView tv = new TextView(getContext());
            tv.setText(o.getText());
            tv.setTextColor(o.getColorInt());
            tv.setTypeface(o.getTypeface());
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.getPaint().setStrokeWidth(o.getStrokeWidthPx());
            if (o.getStrokeWidthPx() > 0f && o.getStrokeColorInt() != android.graphics.Color.TRANSPARENT) {
                tv.getPaint().setStyle(android.graphics.Paint.Style.FILL);
                tv.getPaint().setColor(o.getStrokeColorInt());
            }
            tv.setShadowLayer(o.getShadowRadiusPx() > 0f ? o.getShadowRadiusPx() : 8f,
                    0f, 3f, o.getShadowColorInt());
            view = tv;
        }
        view.setLayoutParams(new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        attachGestures(view, o);
        // Position once the view has a measured size.
        final View fv = view;
        fv.post(() -> position(fv, o));
        return view;
    }

    private void position(@NonNull View view, @NonNull TextOverlayItem o) {
        if (callback == null) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;

        // Hide the overlay outside its time range.
        if (!o.isVisibleAt(currentTimeMs)) {
            view.setVisibility(GONE);
            return;
        }
        view.setVisibility(VISIBLE);
        // While the user is dragging/scaling this overlay, follow the finger
        // (static transform) rather than the keyframed value at the playhead.
        boolean live = o == manipulating;
        view.setAlpha(live ? 1f
                : Math.max(0f, Math.min(1f, o.animatedOpacity(currentTimeMs))));

        float sizeFraction = live ? o.getSizeFraction() : o.animatedSizeFraction(currentTimeMs);
        int w, h;
        if (o.isImage() && view instanceof ImageView) {
            // Height = fraction of video height; width derived from image aspect.
            float aspect = 1f;
            android.graphics.drawable.Drawable d = ((ImageView) view).getDrawable();
            if (d != null && d.getIntrinsicHeight() > 0) {
                aspect = d.getIntrinsicWidth() / (float) d.getIntrinsicHeight();
            }
            h = Math.round(sizeFraction * r.height());
            w = Math.round(h * aspect);
        } else {
            TextView tv = (TextView) view;
            float fontPx = sizeFraction * r.height();
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx);
            tv.setShadowLayer(o.getShadowRadiusPx() > 0f ? o.getShadowRadiusPx() : fontPx * 0.10f,
                    0f, fontPx * 0.04f, o.getShadowColorInt());
            tv.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            w = tv.getMeasuredWidth();
            h = tv.getMeasuredHeight();
        }

        float cx = r.left + (live ? o.getCenterX() : o.animatedCenterX(currentTimeMs)) * r.width();
        float cy = r.top + (live ? o.getCenterY() : o.animatedCenterY(currentTimeMs)) * r.height();

        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.width = Math.max(1, w);
        lp.height = Math.max(1, h);
        lp.leftMargin = Math.round(cx - w / 2f);
        lp.topMargin = Math.round(cy - h / 2f);
        view.setLayoutParams(lp);
        view.setRotation(live ? o.getRotationDeg() : o.animatedRotation(currentTimeMs));
    }

    private void attachGestures(@NonNull View tv, @NonNull TextOverlayItem o) {
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        o.setSizeFraction(o.getSizeFraction() * detector.getScaleFactor());
                        position(tv, o);
                        return true;
                    }
                });

        tv.setOnTouchListener(new OnTouchListener() {
            float downRawX, downRawY, startCenterX, startCenterY;
            boolean moved;
            @Nullable TextOverlayItem.TransformSnapshot beforeGesture;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                scaleDetector.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startCenterX = o.getCenterX();
                        startCenterY = o.getCenterY();
                        moved = false;
                        manipulating = o;
                        beforeGesture = o.snapshotTransform();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (scaleDetector.isInProgress() || callback == null) return true;
                        RectF r = callback.getVideoContentRect();
                        if (r.width() <= 0 || r.height() <= 0) return true;
                        float dx = (e.getRawX() - downRawX) / r.width();
                        float dy = (e.getRawY() - downRawY) / r.height();
                        if (Math.abs(e.getRawX() - downRawX) > 8
                                || Math.abs(e.getRawY() - downRawY) > 8) {
                            moved = true;
                        }
                        if (snapEnabled) {
                            float snappedX = snapX(startCenterX + dx);
                            float snappedY = snapY(startCenterY + dy);
                            o.setCenter(snappedX, snappedY);
                        } else {
                            o.setCenter(startCenterX + dx, startCenterY + dy);
                        }
                        position(tv, o);
                        return true;
                    case MotionEvent.ACTION_UP:
                        manipulating = null;
                        if (callback != null) {
                            if (!moved) {
                                callback.onEditRequested(o);
                            } else {
                                // Auto-keyframe: once an overlay is armed (has a
                                // keyframe), moving it at the playhead records a
                                // keyframe there (After Effects "stopwatch on"
                                // behaviour). Un-armed overlays just move.
                                if (o.isArmed()) {
                                    long snappedTime = snapEnabled ? snapTimeMs(currentTimeMs) : currentTimeMs;
                                    if (snappedTime != currentTimeMs) currentTimeMs = snappedTime;
                                    o.addKeyframeAt(currentTimeMs);
                                }
                                if (beforeGesture != null) {
                                    callback.onOverlayManipulated(o, beforeGesture);
                                }
                                callback.onOverlayChanged();
                            }
                        }
                        beforeGesture = null;
                        position(tv, o);
                        return true;
                }
                return false;
            }
        });
    }

    private float snapX(float x) {
        float best = clamp(x);
        float bestDistance = SNAP_THRESHOLD;
        float[] targets = new float[]{0.5f};
        for (TextOverlayItem other : overlays) {
            if (other != manipulating) targets = append(targets, other.getCenterX());
        }
        for (float target : targets) {
            float d = Math.abs(x - target);
            if (d < bestDistance) {
                bestDistance = d;
                best = target;
            }
        }
        return clamp(best);
    }

    private float snapY(float y) {
        float best = clamp(y);
        float bestDistance = SNAP_THRESHOLD;
        float[] targets = new float[]{0.5f};
        for (TextOverlayItem other : overlays) {
            if (other != manipulating) targets = append(targets, other.getCenterY());
        }
        for (float target : targets) {
            float d = Math.abs(y - target);
            if (d < bestDistance) {
                bestDistance = d;
                best = target;
            }
        }
        return clamp(best);
    }

    private long snapTimeMs(long timeMs) {
        long best = timeMs;
        long bestDistance = TIME_SNAP_MS;
        for (TextOverlayItem other : overlays) {
            if (other == manipulating) continue;
            best = snapToOneEdge(timeMs, best, bestDistance, other.getStartMs());
            if (other.getEndMs() != Long.MAX_VALUE) {
                best = snapToOneEdge(timeMs, best, bestDistance, other.getEndMs());
            }
        }
        return best;
    }

    private long snapToOneEdge(long timeMs, long best, long bestDistance, long edge) {
        long d = Math.abs(timeMs - edge);
        if (d < bestDistance) {
            bestDistance = d;
            best = edge;
        }
        return best;
    }

    private float[] append(float[] values, float value) {
        float[] out = java.util.Arrays.copyOf(values, values.length + 1);
        out[out.length - 1] = value;
        return out;
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
