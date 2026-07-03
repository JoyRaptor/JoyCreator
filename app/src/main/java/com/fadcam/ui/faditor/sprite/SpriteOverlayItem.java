package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.KeyframeSet;

import java.util.UUID;

/**
 * A PLACED sprite instance on the timeline ({@code Timeline.spriteOverlays[]}),
 * referencing a {@link SpriteSheet} by id. Mirrors {@code model/TextOverlayItem}'s
 * shape deliberately (PLAN_SPRITE_ANIMATION §Data model + 2026-07-03 amendment):
 * normalized center/size, degree rotation, opacity, a timeline time range with an
 * open-ended default end, an eased {@link KeyframeSet} for whole-unit transform
 * animation (item-LOCAL times), and a {@code layerId} for Track membership — so it
 * rides the schema-v8 Track/TimedItem system natively (TrackKind.SPRITE) and
 * inherits the whole 2026-07-03 gesture contract.
 *
 * <p>What the sprite SHOWS over time lives in the discrete {@link FrameTrack}
 * (step/hold, item-local times); {@link SpriteFrameResolver} is the only code
 * allowed to turn (sheet, item, time) into a cell index.</p>
 */
public class SpriteOverlayItem {

    @NonNull private final String id;
    @NonNull private String sheetId;

    // ── Placement (normalized to the video canvas, like TextOverlayItem) ──
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    /** Rendered cell height as a fraction of canvas height (width follows cell aspect). */
    private float sizeFraction = 0.25f;
    private float rotationDeg = 0f;
    private float opacity = 1f;
    private boolean flipH = false;
    private boolean flipV = false;

    // ── Timeline range (absolute ms; MAX_VALUE end = "rest of the timeline") ──
    private long startMs = 0;
    private long endMs = Long.MAX_VALUE;

    /** Track membership (M10 layer routing); null = the default "sprite" track. */
    @Nullable private String layerId;

    /** "hold" (default) | "loop" | "pingpong" — what happens after the last frame entry. */
    @NonNull private String endBehavior = "hold";

    /** Discrete which-cell-when track (item-local times). Never null. */
    @NonNull private final FrameTrack frameTrack = new FrameTrack();

    /** Eased whole-unit transform animation (x/y/scale/rotation/opacity), item-local times. */
    @NonNull private final KeyframeSet keyframes = new KeyframeSet();

    public SpriteOverlayItem(@NonNull String id, @NonNull String sheetId) {
        this.id = id;
        this.sheetId = sheetId;
    }

    public static SpriteOverlayItem create(@NonNull String sheetId) {
        return new SpriteOverlayItem(UUID.randomUUID().toString(), sheetId);
    }

    // ── Accessors ────────────────────────────────────────────────────────

    @NonNull public String getId() { return id; }
    @NonNull public String getSheetId() { return sheetId; }
    public void setSheetId(@NonNull String sheetId) { this.sheetId = sheetId; }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public void setCenter(float x, float y) { this.centerX = x; this.centerY = y; }
    public float getSizeFraction() { return sizeFraction; }
    public void setSizeFraction(float f) { this.sizeFraction = Math.max(0.01f, f); }
    public float getRotationDeg() { return rotationDeg; }
    public void setRotationDeg(float deg) { this.rotationDeg = deg; }
    public float getOpacity() { return opacity; }
    public void setOpacity(float o) { this.opacity = Math.max(0f, Math.min(1f, o)); }
    public boolean isFlipH() { return flipH; }
    public void setFlipH(boolean flipH) { this.flipH = flipH; }
    public boolean isFlipV() { return flipV; }
    public void setFlipV(boolean flipV) { this.flipV = flipV; }

    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }

    /** Mirrors {@code TextOverlayItem.setTimeRange} (start clamped ≥ 0; end may be MAX_VALUE). */
    public void setTimeRange(long startMs, long endMs) {
        this.startMs = Math.max(0, startMs);
        this.endMs = endMs;
    }

    @Nullable public String getLayerId() { return layerId; }
    public void setLayerId(@Nullable String layerId) { this.layerId = layerId; }

    @NonNull public String getEndBehavior() { return endBehavior; }
    public void setEndBehavior(@NonNull String endBehavior) { this.endBehavior = endBehavior; }

    @NonNull public FrameTrack getFrameTrack() { return frameTrack; }
    @NonNull public KeyframeSet getKeyframes() { return keyframes; }

    /** True when {@code timelineMs} falls inside this item's visible range. */
    public boolean isVisibleAt(long timelineMs) {
        return timelineMs >= startMs && (endMs == Long.MAX_VALUE || timelineMs < endMs);
    }

    /** Convert an absolute timeline time to this item's local time base. */
    public long toLocalMs(long timelineMs) {
        return timelineMs - startMs;
    }
}
