package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.model.Clip;

import java.util.ArrayList;
import java.util.List;

/**
 * Preview surface for floating IMAGE-kind {@link TimedItem}s (PLAN §3.2, M-COMP-1 scope
 * item 4 — "IMAGE item plumbing"). Read-only in M-COMP-1: there is no creation UI yet
 * (nothing can produce an IMAGE track), so this view exists purely so that once M10 lets
 * users author one, preview already renders it correctly. Deliberately has no gesture
 * handling (unlike {@link com.fadcam.ui.faditor.overlay.TextOverlayLayer}) — add it
 * alongside the eventual creation UI, not here.
 *
 * <p>Mirrors {@code TextOverlayLayer}'s position/scale/opacity math exactly (same
 * {@link KeyframeSet} evaluator, same normalised-center convention against the video
 * content rect) so a future image layer behaves identically to a text/PNG overlay.</p>
 */
public class LayerImageOverlayView extends FrameLayout {

    /** Supplies the pixel rect of the visible video content (same contract as TextOverlayLayer.Callback). */
    public interface RectProvider {
        @NonNull RectF getVideoContentRect();
    }

    private final List<TimedItem> items = new ArrayList<>();
    @Nullable private RectProvider rectProvider;
    private long currentTimeMs = 0;

    public LayerImageOverlayView(Context context) { super(context); }
    public LayerImageOverlayView(Context context, AttributeSet attrs) { super(context, attrs); }
    public LayerImageOverlayView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setRectProvider(@Nullable RectProvider provider) {
        this.rectProvider = provider;
    }

    /**
     * Replace the set of IMAGE items to render. Only items whose payload is a still-image
     * {@link Clip} (IMAGE track items per {@code TimedItem} payload discriminator) make
     * sense here; callers should pre-filter to a single (currently inert) IMAGE track's
     * items. Rebuilds child views.
     */
    public void setItems(@NonNull List<TimedItem> newItems) {
        items.clear();
        items.addAll(newItems);
        removeAllViews();
        for (TimedItem item : items) {
            Clip clip = item.getClip();
            if (clip == null) continue;
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            try {
                iv.setImageURI(clip.getSourceUri());
            } catch (Exception ignored) { }
            iv.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            iv.setTag(item);
            addView(iv);
        }
        setPlayheadMs(currentTimeMs);
    }

    /** Re-evaluate each item's time window + transform for the given absolute timeline time. */
    public void setPlayheadMs(long timelineMs) {
        currentTimeMs = timelineMs;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof TimedItem) {
                position(v, (TimedItem) tag);
            }
        }
    }

    private void position(@NonNull View view, @NonNull TimedItem item) {
        if (rectProvider == null) { view.setVisibility(GONE); return; }
        RectF r = rectProvider.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) { view.setVisibility(GONE); return; }

        Clip clip = item.getClip();
        if (clip == null) { view.setVisibility(GONE); return; }

        long start = item.getTimelineStartMs();
        long end = start + item.getDisplayDurationMs(Long.MAX_VALUE);
        boolean visible = currentTimeMs >= start && (end == Long.MAX_VALUE || currentTimeMs < end);
        if (!visible) { view.setVisibility(GONE); return; }
        view.setVisibility(VISIBLE);

        // Transform envelope: identity default, evaluated via the SAME KeyframeSet
        // evaluator TextOverlayItem uses (KeyframeSet#valueAt) — no new interpolation
        // code (PLAN §3.2 / final-report item 4).
        KeyframeSet transform = item.getTransform();
        long localMs = Math.max(0, currentTimeMs - start);
        float cx = 0.5f, cy = 0.5f, scale = 0.5f, rotation = 0f, opacity = 1f;
        if (transform != null) {
            cx = transform.valueAt(KeyframeSet.X, localMs, cx);
            cy = transform.valueAt(KeyframeSet.Y, localMs, cy);
            scale = transform.valueAt(KeyframeSet.SCALE, localMs, scale);
            rotation = transform.valueAt(KeyframeSet.ROTATION, localMs, rotation);
            opacity = transform.valueAt(KeyframeSet.OPACITY, localMs, opacity);
        }
        view.setAlpha(Math.max(0f, Math.min(1f, opacity)));

        float aspect = 1f;
        if (view instanceof ImageView) {
            android.graphics.drawable.Drawable d = ((ImageView) view).getDrawable();
            if (d != null && d.getIntrinsicHeight() > 0) {
                aspect = d.getIntrinsicWidth() / (float) d.getIntrinsicHeight();
            }
        }
        int h = Math.round(scale * r.height());
        int w = Math.round(h * aspect);

        float centerX = r.left + cx * r.width();
        float centerY = r.top + cy * r.height();

        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.width = Math.max(1, w);
        lp.height = Math.max(1, h);
        lp.leftMargin = Math.round(centerX - w / 2f);
        lp.topMargin = Math.round(centerY - h / 2f);
        view.setLayoutParams(lp);
        view.setRotation(rotation);

        // BlendMode: NORMAL only (alpha-over above, via setAlpha). Non-NORMAL blend on the
        // View layer is a later, verified step (PLAN Part 10 #4) — TODO wire
        // android.graphics.BlendMode / PorterDuff on this ImageView's Paint once verified
        // to match the export GlEffect blend closely enough (item.getBlendMode()).
    }
}
