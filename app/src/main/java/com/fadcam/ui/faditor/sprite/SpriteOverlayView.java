package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * S4 (PLAN_SPRITE_ANIMATION): transparent preview layer that renders placed
 * sprites over the video — resolver-driven cell choice ({@link
 * SpriteFrameResolver} is the ONLY cell authority), eased whole-unit transforms
 * via each item's KeyframeSet, drag-to-move / pinch-to-scale with
 * auto-keyframe-when-armed and center/peer snapping (all mirroring
 * {@code TextOverlayLayer}'s contract so the two overlay families feel
 * identical). Z-order: this view sits ABOVE the video surfaces and BELOW the
 * text-overlay + caption layers in the preview stack, matching the export
 * draw order rule (sprites below captions).
 *
 * <p>Pure Canvas blits (a sheet cell can't be an ImageView — sub-rect), one
 * shared decoded bitmap per sheet via {@link SpriteSheetRenderer} instances the
 * callback owns/recycles. A sheet that fails to load draws the MISSING
 * placeholder (S7 rule: never black, never crash). Empty areas pass touches
 * through to the player.</p>
 */
public class SpriteOverlayView extends View {

    public interface Callback {
        /** Pixel rect of the visible video content inside this layer's bounds. */
        @NonNull RectF getVideoContentRect();
        /** Sheet definition for an item (null = unknown id). */
        @Nullable SpriteSheet lookupSheet(@NonNull String sheetId);
        /** Shared decoded renderer for a sheet (null = missing art). */
        @Nullable SpriteSheetRenderer lookupRenderer(@NonNull String sheetId);
        /** A sprite's transform changed — persist it. */
        void onSpriteChanged();

        /** Double-tap on a sprite/avatar → open its advanced object menu (JoyRaptor 2026-07-16). */
        default void onSpriteDoubleTapped(@NonNull SpriteOverlayItem item) { }
        /** A drag/pinch gesture finished; record ONE undo step from the snapshot. */
        default void onSpriteManipulated(@NonNull SpriteOverlayItem item,
                                         @NonNull SpriteOverlayItem.TransformSnapshot before) { }
        /** Rig for a placed avatar item's replay render (null = unknown id). */
        @Nullable
        default com.fadcam.ui.faditor.avatar.AvatarRig lookupAvatarRig(@NonNull String rigId) {
            return null;
        }
    }

    private static final float SNAP_THRESHOLD = 0.045f; // TextOverlayLayer parity
    private static final long TIME_SNAP_MS = 250L;

    private final List<SpriteOverlayItem> items = new ArrayList<>();
    @Nullable private Callback callback;
    private long currentTimeMs = 0;
    @Nullable private SpriteOverlayItem manipulating;
    private boolean snapEnabled = true;

    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint missingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missingText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF workRect = new RectF();

    private final ScaleGestureDetector scaleDetector;
    private float downRawX, downRawY, startCenterX, startCenterY;
    private boolean moved;
    @Nullable private SpriteOverlayItem.TransformSnapshot beforeGesture;
    /** Double-tap pairing state (advanced object menu). */
    @Nullable private SpriteOverlayItem lastTapItem;
    private long lastTapUpMs;

    public SpriteOverlayView(Context ctx) { this(ctx, null); }

    public SpriteOverlayView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        missingPaint.setStyle(Paint.Style.STROKE);
        missingPaint.setStrokeWidth(3f);
        missingPaint.setColor(0xFFE040FB);
        missingText.setColor(0xFFE040FB);
        missingText.setTextSize(24f);
        missingText.setTextAlign(Paint.Align.CENTER);
        scaleDetector = new ScaleGestureDetector(ctx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        if (manipulating != null) {
                            manipulating.setSizeFraction(
                                    manipulating.getSizeFraction() * d.getScaleFactor());
                            invalidate();
                        }
                        return true;
                    }
                });
    }

    public void setSnapEnabled(boolean enabled) { this.snapEnabled = enabled; }

    // ── Avatar replay (bake-to-keyframes): one puppet per performing item ──
    // Keyed by item id; a null value caches "no rig" so a broken linkage
    // doesn't retry every frame. Pruned in setData, released on detach.
    private final java.util.Map<String, com.fadcam.ui.faditor.avatar.AvatarItemPuppet>
            puppets = new java.util.HashMap<>();

    public void setData(@NonNull List<SpriteOverlayItem> items, @NonNull Callback cb) {
        this.items.clear();
        this.items.addAll(items);
        this.callback = cb;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (SpriteOverlayItem o : items) live.add(o.getId());
        java.util.Iterator<java.util.Map.Entry<String,
                com.fadcam.ui.faditor.avatar.AvatarItemPuppet>> it =
                puppets.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, com.fadcam.ui.faditor.avatar.AvatarItemPuppet> e =
                    it.next();
            if (!live.contains(e.getKey())) {
                if (e.getValue() != null) e.getValue().release();
                it.remove();
            }
        }
        invalidate();
    }

    /** Lazy per-item replay puppet; null when the item isn't performing or the
     *  rig can't be found (fall back to the static sprite path). */
    @Nullable
    private com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppetFor(
            @NonNull SpriteOverlayItem o) {
        if (callback == null || !o.hasAvatarPerformance()) return null;
        if (puppets.containsKey(o.getId())) return puppets.get(o.getId());
        com.fadcam.ui.faditor.avatar.AvatarItemPuppet p =
                com.fadcam.ui.faditor.avatar.AvatarItemPuppet.forItem(
                        getContext(), o,
                        o.getAvatarRigId() != null
                                ? callback.lookupAvatarRig(o.getAvatarRigId()) : null,
                        id -> callback.lookupRenderer(id),
                        id -> callback.lookupSheet(id));
        puppets.put(o.getId(), p);
        return p;
    }

    /** Drive time-ranges, frame resolution, and keyframed transforms. */
    public void setPlayheadMs(long timelineMs) {
        this.currentTimeMs = timelineMs;
        invalidate();
    }

    public boolean isEmpty() { return items.isEmpty(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (callback == null || items.isEmpty()) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        // List order = z order (newest on top), per the plan's S1 decision.
        for (SpriteOverlayItem o : items) {
            if (!o.isVisibleAt(currentTimeMs)) continue;
            drawSprite(canvas, o, r);
        }
    }

    private void drawSprite(@NonNull Canvas canvas, @NonNull SpriteOverlayItem o,
                            @NonNull RectF r) {
        SpriteSheet sheet = callback.lookupSheet(o.getSheetId());
        SpriteSheetRenderer renderer = sheet != null
                ? callback.lookupRenderer(o.getSheetId()) : null;

        boolean live = o == manipulating;
        float cx = r.left + (live ? o.getCenterX() : o.animatedCenterX(currentTimeMs)) * r.width();
        float cy = r.top + (live ? o.getCenterY() : o.animatedCenterY(currentTimeMs)) * r.height();
        float sizeFraction = live ? o.getSizeFraction() : o.animatedSizeFraction(currentTimeMs);
        float rot = live ? o.getRotationDeg() : o.animatedRotation(currentTimeMs);
        float alpha = live ? 1f
                : Math.max(0f, Math.min(1f, o.animatedOpacity(currentTimeMs)));

        float h = sizeFraction * r.height();
        float aspect = renderer != null ? renderer.cellAspect() : 1f;
        float w = h * (aspect > 0 ? aspect : 1f);
        workRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);

        canvas.save();
        canvas.rotate(rot, cx, cy);
        if (o.isFlipH() || o.isFlipV()) {
            canvas.scale(o.isFlipH() ? -1f : 1f, o.isFlipV() ? -1f : 1f, cx, cy);
        }
        com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppet = puppetFor(o);
        if (puppet != null && o.getAvatarTrack() != null) {
            // Live puppet replay (bake-to-keyframes): resolver-driven, replaces
            // the static neutral cell. Rotate/flip above apply; workRect is the
            // same box the neutral PNG occupied, so geometry is unchanged.
            puppet.draw(canvas, workRect, o.getAvatarTrack(),
                    Math.max(0, o.toLocalMs(currentTimeMs)), alpha);
        } else if (renderer != null && sheet != null) {
            int cell = SpriteFrameResolver.resolveCellAt(sheet, o, currentTimeMs);
            if (cell != SpriteFrameResolver.NO_CELL) {
                drawPaint.setAlpha(Math.round(alpha * 255));
                renderer.drawCell(canvas, cell, workRect, drawPaint);
            }
            // NO_CELL with an empty frame track = a just-placed sprite; S3's
            // palette drops the first entry. Draw nothing (not even MISSING).
        } else {
            canvas.drawRect(workRect, missingPaint);
            canvas.drawText("sprite?", workRect.centerX(),
                    workRect.centerY() + missingText.getTextSize() / 3f, missingText);
        }
        canvas.restore();
    }

    // ── Gestures (TextOverlayLayer contract: drag move, pinch scale,
    //    auto-keyframe when armed, empty areas pass through) ──────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (callback == null) return false;
        if (manipulating != null) scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                SpriteOverlayItem hit = hitTest(e.getX(), e.getY());
                if (hit == null) return false; // pass through to the player
                manipulating = hit;
                beforeGesture = hit.snapshotTransform();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startCenterX = hit.getCenterX();
                startCenterY = hit.getCenterY();
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (manipulating == null) return false;
                if (scaleDetector.isInProgress()) { moved = true; return true; }
                RectF r = callback.getVideoContentRect();
                if (r.width() <= 0 || r.height() <= 0) return true;
                float dx = (e.getRawX() - downRawX) / r.width();
                float dy = (e.getRawY() - downRawY) / r.height();
                if (Math.abs(e.getRawX() - downRawX) > 8
                        || Math.abs(e.getRawY() - downRawY) > 8) {
                    moved = true;
                }
                float nx = startCenterX + dx, ny = startCenterY + dy;
                if (snapEnabled) {
                    nx = snap(nx, true);
                    ny = snap(ny, false);
                }
                manipulating.setCenter(clamp(nx), clamp(ny));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                SpriteOverlayItem o = manipulating;
                manipulating = null;
                // Double-tap (no drag) → advanced object menu (JoyRaptor 2026-07-16).
                if (o != null && !moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (o == lastTapItem && now - lastTapUpMs <= 320) {
                        lastTapItem = null;
                        beforeGesture = null;
                        callback.onSpriteDoubleTapped(o);
                        return true;
                    }
                    lastTapItem = o;
                    lastTapUpMs = now;
                }
                if (o != null && moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (o.isArmed()) {
                        long t = snapEnabled ? snapTimeMs(currentTimeMs) : currentTimeMs;
                        if (t != currentTimeMs) currentTimeMs = t;
                        o.addKeyframeAt(currentTimeMs);
                    }
                    if (beforeGesture != null) callback.onSpriteManipulated(o, beforeGesture);
                    callback.onSpriteChanged();
                }
                beforeGesture = null;
                invalidate();
                return o != null;
            }
        }
        return false;
    }

    /** Topmost visible sprite whose (unrotated) bounds contain the point. */
    @Nullable
    private SpriteOverlayItem hitTest(float x, float y) {
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return null;
        for (int i = items.size() - 1; i >= 0; i--) { // list order = z, top first
            SpriteOverlayItem o = items.get(i);
            if (!o.isVisibleAt(currentTimeMs)) continue;
            SpriteSheetRenderer renderer = callback.lookupRenderer(o.getSheetId());
            float cx = r.left + o.animatedCenterX(currentTimeMs) * r.width();
            float cy = r.top + o.animatedCenterY(currentTimeMs) * r.height();
            float h = o.animatedSizeFraction(currentTimeMs) * r.height();
            float aspect = renderer != null ? renderer.cellAspect() : 1f;
            float w = h * (aspect > 0 ? aspect : 1f);
            // Generous slop so small sprites stay grabbable (finger-sized min).
            float minHalf = 24f * getResources().getDisplayMetrics().density / 2f;
            float hw = Math.max(w / 2f, minHalf), hh = Math.max(h / 2f, minHalf);
            if (x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh) return o;
        }
        return null;
    }

    private float snap(float v, boolean isX) {
        float best = v;
        float bestDistance = SNAP_THRESHOLD;
        float d = Math.abs(v - 0.5f);
        if (d < bestDistance) { bestDistance = d; best = 0.5f; }
        for (SpriteOverlayItem other : items) {
            if (other == manipulating) continue;
            float target = isX ? other.getCenterX() : other.getCenterY();
            d = Math.abs(v - target);
            if (d < bestDistance) { bestDistance = d; best = target; }
        }
        return best;
    }

    private long snapTimeMs(long timeMs) {
        long best = timeMs;
        long bestDistance = TIME_SNAP_MS;
        for (SpriteOverlayItem other : items) {
            if (other == manipulating) continue;
            long d = Math.abs(timeMs - other.getStartMs());
            if (d < bestDistance) { bestDistance = d; best = other.getStartMs(); }
            if (other.getEndMs() != Long.MAX_VALUE) {
                d = Math.abs(timeMs - other.getEndMs());
                if (d < bestDistance) { bestDistance = d; best = other.getEndMs(); }
            }
        }
        return best;
    }

    private float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Drop an item's cached replay puppet (e.g. after a re-record replaced
     *  its track) so the next draw rebuilds with fresh replay state. */
    public void resetAvatarPuppet(@NonNull String itemId) {
        com.fadcam.ui.faditor.avatar.AvatarItemPuppet p = puppets.remove(itemId);
        if (p != null) p.release();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (com.fadcam.ui.faditor.avatar.AvatarItemPuppet p : puppets.values()) {
            if (p != null) p.release();
        }
        puppets.clear();
    }
}
