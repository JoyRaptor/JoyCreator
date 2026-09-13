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

        /**
         * Double-tap on a sprite/avatar → its TYPE editor (gesture grammar,
         * JoyRaptor 2026-07-17: tap = select, double-tap = type editor, hold =
         * general drawer — the 2026-07-16 drawer routing moved to onSpriteHeld).
         */
        default void onSpriteDoubleTapped(@NonNull SpriteOverlayItem item) { }
        /** Single tap (no drag) — select the sprite (timeline row + handles). */
        default void onSpriteTapped(@NonNull SpriteOverlayItem item) { }
        /** Hold (~long-press, no movement) — open the general properties drawer. */
        default void onSpriteHeld(@NonNull SpriteOverlayItem item) { }
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
    /** Scratch for the sprite corner pin — allocated once, never inside a draw. */
    private final android.graphics.Matrix pinMatrix = new android.graphics.Matrix();
    /** The bend, shared with the export — see SpriteMeshDraw. */
    private final SpriteMeshDraw meshDraw = new SpriteMeshDraw();

    /**
     * Sprites the GL composite is drawing this frame — this view must not paint them too.
     *
     * <p>Exactly {@code TextOverlayLayer.setGlOwnedImageIds}'s job, and named to match. A sprite
     * that has moved into the composite is drawn INSIDE it, where a blend above can sample it and
     * a mask can cut it; painting it here as well would double it and put the Canvas copy on top
     * of the very effects it moved to GL to receive.
     */
    @NonNull private java.util.Set<String> glOwnedIds = java.util.Collections.emptySet();

    /** Told every frame by the composite; cheap no-op when the set has not changed. */
    public void setGlOwnedIds(@NonNull java.util.Set<String> ids) {
        if (glOwnedIds.equals(ids)) return;
        glOwnedIds = new java.util.HashSet<>(ids);
        invalidate();
    }
    private float downRawX, downRawY, startCenterX, startCenterY;
    private boolean moved;
    @Nullable private SpriteOverlayItem.TransformSnapshot beforeGesture;
    /** Double-tap pairing state (type-editor express lane). */
    @Nullable private SpriteOverlayItem lastTapItem;
    private long lastTapUpMs;
    /** Hold (long-press → general drawer) state. */
    private boolean heldFired;
    private final Runnable holdRunnable = () -> {
        SpriteOverlayItem o = manipulating;
        if (o == null || callback == null) return;
        heldFired = true;
        manipulating = null;
        beforeGesture = null;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        callback.onSpriteHeld(o);
    };

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

    /** Z3: false = draw-only (the below-video instance). See SPEC_CROSSTYPE_Z. */
    private boolean interactive = true;

    /** Z3: make this instance draw-only, so it never competes for touch. */
    public void setInteractive(boolean value) { this.interactive = value; }

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

    /**
     * The sprite's REAL pixels, as a bitmap, for the GL composite to sample.
     *
     * <p>Fixes a regression this lane shipped on 2026-09-13 and the ZA lane caught:
     * {@code OverlayTextureCache.rasterizeSprite} is a placeholder that fills a solid blue
     * rectangle ("placeholder square; real sheet cell aspect would be sheet-dependent"). Harmless
     * while nothing used it — and then this lane routed GL-owned sprites through it, so a bent
     * sprite PREVIEWED as a blue box while the export rasterised real cells. Preview and export
     * disagreeing about the actual pixels is the worst version of the bug rule 7 exists to stop.
     *
     * <p>Rasterised by {@link #drawSpriteContent}, which is the same method that paints the sprite
     * on this Canvas — so the texture holds exactly what the Canvas path would have drawn, cell or
     * rig, and the two cannot diverge. The aspect comes from the sheet's own cell rather than the
     * placeholder's square guess.
     *
     * @return the bitmap, or null when the sheet is not loaded yet (the composite then omits the
     *         sprite for that frame rather than drawing a wrong one)
     */
    @Nullable
    public android.graphics.Bitmap rasterFor(@NonNull SpriteOverlayItem o, int frameW, int frameH) {
        if (frameW <= 0 || frameH <= 0) return null;
        SpriteSheet sheet = callback.lookupSheet(o.getSheetId());
        SpriteSheetRenderer renderer = sheet != null
                ? callback.lookupRenderer(o.getSheetId()) : null;
        if (renderer == null) return null;
        float hNorm = o.animatedSizeFraction(currentTimeMs);
        if (!(hNorm > 0f)) return null;
        // Cell aspect from the sheet, not a square guess.
        int cell = SpriteFrameResolver.resolveCellAt(sheet, o, currentTimeMs);
        float aspect = 1f;
        try {
            android.graphics.Rect cr = cell != SpriteFrameResolver.NO_CELL
                    ? renderer.cellRectBitmap(cell) : null;
            if (cr != null && cr.height() > 0) aspect = cr.width() / (float) cr.height();
        } catch (Exception ignored) { }
        if (!(aspect > 0f)) aspect = 1f;
        int hi = Math.max(1, Math.min(4096, Math.round(hNorm * frameH)));
        int wi = Math.max(1, Math.min(4096, Math.round(hi * aspect)));
        try {
            android.graphics.Bitmap bmp =
                    android.graphics.Bitmap.createBitmap(wi, hi, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            RectF into = new RectF(0f, 0f, wi, hi);
            com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppet = puppetFor(o);
            // Alpha 1: opacity is applied by the composite, not baked into the texture, or it
            // would be applied twice.
            drawSpriteContent(c, o, into, renderer, sheet, puppet, 1f);
            return bmp;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void drawSprite(@NonNull Canvas canvas, @NonNull SpriteOverlayItem o,
                            @NonNull RectF r) {
        // GL has this one — see setGlOwnedIds.
        if (glOwnedIds.contains(o.getId())) return;
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
        // SPEC Z slice 1 — the SPRITE's own corner pin, concat-ed INNERMOST: inside the rotate
        // and the flip, immediately around whatever draws next. That placement is the design, not
        // an implementation detail. The cell and the rig both draw into workRect, so distorting
        // here distorts the COMPOSED result — a bend authored on a head is inherited by every
        // cell and every rig pose, and the mouth follows underneath, which is the entire reason
        // the pin lives on the sprite and not on a cell.
        //
        // Same matrix the export builds, from the same method, so the two cannot transcribe it
        // differently. An undistorted sprite takes the byte-identical path it always did.
        if (o.cornerPinMatrix(pinMatrix, currentTimeMs,
                workRect.left, workRect.top, workRect.width(), workRect.height())) {
            canvas.concat(pinMatrix);
        }
        com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppet = puppetFor(o);
        // SPEC Z slice 1 — THE BEND. Everything the sprite shows is drawn into an offscreen and
        // that picture is warped, so a cell swap or a rig pose change cannot change the bend and
        // the mouth follows the head. Returns false and costs nothing when there is no bend.
        if (meshDraw.draw(canvas, o, currentTimeMs, workRect,
                (c, into) -> drawSpriteContent(c, o, into, renderer, sheet, puppet, alpha),
                drawPaint)) {
            canvas.restore();
            return;
        }
        drawSpriteContent(canvas, o, workRect, renderer, sheet, puppet, alpha);
        canvas.restore();
    }

    /**
     * What the sprite SHOWS at this instant — a rig's composed parts, a sheet cell, or the missing
     * placeholder. Split out so the bend can rasterise exactly this and warp the result, with no
     * second copy of the branches.
     */
    private void drawSpriteContent(@NonNull Canvas canvas, @NonNull SpriteOverlayItem o,
                                   @NonNull RectF into,
                                   @Nullable SpriteSheetRenderer renderer,
                                   @Nullable SpriteSheet sheet,
                                   @Nullable com.fadcam.ui.faditor.avatar.AvatarItemPuppet puppet,
                                   float alpha) {
        if (puppet != null && o.getAvatarTrack() != null) {
            // Live puppet replay (bake-to-keyframes): resolver-driven, replaces
            // the static neutral cell. Rotate/flip above apply; workRect is the
            // same box the neutral PNG occupied, so geometry is unchanged.
            puppet.draw(canvas, into, o.getAvatarTrack(),
                    Math.max(0, o.toLocalMs(currentTimeMs)), alpha);
        } else if (renderer != null && sheet != null) {
            int cell = SpriteFrameResolver.resolveCellAt(sheet, o, currentTimeMs);
            if (cell != SpriteFrameResolver.NO_CELL) {
                drawPaint.setAlpha(Math.round(alpha * 255));
                renderer.drawCell(canvas, cell, into, drawPaint);
            }
            // NO_CELL with an empty frame track = a just-placed sprite; S3's
            // palette drops the first entry. Draw nothing (not even MISSING).
        } else {
            canvas.drawRect(into, missingPaint);
            canvas.drawText("sprite?", into.centerX(),
                    into.centerY() + missingText.getTextSize() / 3f, missingText);
        }
    }

    // ── Gestures (TextOverlayLayer contract: drag move, pinch scale,
    //    auto-keyframe when armed, empty areas pass through) ──────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Z3: the below-video instance is draw-only — see SPEC_CROSSTYPE_Z's Z3 note.
        if (!interactive) return false;
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
                heldFired = false;
                postDelayed(holdRunnable,
                        android.view.ViewConfiguration.getLongPressTimeout());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                removeCallbacks(holdRunnable); // pinch incoming — not a hold
                return manipulating != null;
            }
            case MotionEvent.ACTION_MOVE: {
                if (heldFired) return true;
                if (manipulating == null) return false;
                if (scaleDetector.isInProgress()) {
                    moved = true;
                    removeCallbacks(holdRunnable);
                    return true;
                }
                RectF r = callback.getVideoContentRect();
                if (r.width() <= 0 || r.height() <= 0) return true;
                // Screen-pixel delta over a local-pixel rect: correct only while nothing above
                // is scaled, and player_container shrinks to clear a drawer.
                float ui = com.fadcam.ui.faditor.overlay.UiScale.of(this);
                float dx = (e.getRawX() - downRawX) / ui / r.width();
                float dy = (e.getRawY() - downRawY) / ui / r.height();
                if (Math.abs(e.getRawX() - downRawX) > 8
                        || Math.abs(e.getRawY() - downRawY) > 8) {
                    moved = true;
                    removeCallbacks(holdRunnable);
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
                removeCallbacks(holdRunnable);
                if (heldFired) { // drawer already opened; swallow the UP
                    heldFired = false;
                    return true;
                }
                SpriteOverlayItem o = manipulating;
                manipulating = null;
                // Tap = select; double-tap (no drag) = type editor (grammar 2026-07-17).
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
                    callback.onSpriteTapped(o);
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
