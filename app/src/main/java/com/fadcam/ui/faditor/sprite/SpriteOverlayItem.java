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

    // §4.5 per-OBJECT visibility/lock (LANE_BADGES spec, built 2026-07-19) — see
    // TextOverlayItem's twin fields. Tolerant storage: absent = false.
    private boolean hidden;
    private boolean locked;

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

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

    // ── Avatar performance (bake-to-keyframes, PLAN_AVATAR_STUDIO §MINED) ──
    // Both additive + tolerant-read: a plain sprite carries neither; an
    // inserted avatar carries the rig linkage; a RECORDED performance adds the
    // track and upgrades rendering from the static neutral PNG to a live
    // puppet replayed through PuppetPoseResolver (webcam never re-runs).

    /** Rig this item puppets ({@code FaditorProject.avatarRigs} id), or null. */
    @Nullable private String avatarRigId;

    /** Recorded performance (item-local ms), or null when none recorded yet. */
    @Nullable private com.fadcam.ui.faditor.avatar.AvatarParamTrack avatarTrack;

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

    /**
     * Mirrors {@code TextOverlayItem.setTimeRange} exactly, INCLUDING its
     * degenerate-range guard (review gate 2026-07-03): an end ≤ start would make
     * the sprite invisible at every time forever (isVisibleAt can never pass), so
     * it coerces to open-ended instead — same protection text overlays get from
     * the generic trim/drag gesture path and from hand-edited/AI-authored JSON.
     */
    public void setTimeRange(long startMs, long endMs) {
        this.startMs = Math.max(0, startMs);
        this.endMs = (endMs <= this.startMs) ? Long.MAX_VALUE : endMs;
    }

    @Nullable public String getLayerId() { return layerId; }
    public void setLayerId(@Nullable String layerId) { this.layerId = layerId; }

    @NonNull public String getEndBehavior() { return endBehavior; }
    public void setEndBehavior(@NonNull String endBehavior) { this.endBehavior = endBehavior; }

    @NonNull public FrameTrack getFrameTrack() { return frameTrack; }
    @NonNull public KeyframeSet getKeyframes() { return keyframes; }

    @Nullable public String getAvatarRigId() { return avatarRigId; }
    public void setAvatarRigId(@Nullable String rigId) { this.avatarRigId = rigId; }

    @Nullable public com.fadcam.ui.faditor.avatar.AvatarParamTrack getAvatarTrack() {
        return avatarTrack;
    }

    public void setAvatarTrack(@Nullable com.fadcam.ui.faditor.avatar.AvatarParamTrack t) {
        this.avatarTrack = t;
    }

    /** True when this item should render as a LIVE puppet (rig + recorded track). */
    public boolean hasAvatarPerformance() {
        return avatarRigId != null && avatarTrack != null && !avatarTrack.isEmpty();
    }

    /**
     * True when {@code timelineMs} falls inside this item's visible range.
     * End-INCLUSIVE, matching {@code TextOverlayItem.isVisibleAt} exactly (review
     * gate 2026-07-03: the two mirrored overlay families must agree at boundaries).
     */
    public boolean isVisibleAt(long timelineMs) {
        return timelineMs >= startMs && timelineMs <= endMs;
    }

    /** Convert an absolute timeline time to this item's local time base. */
    public long toLocalMs(long timelineMs) {
        return timelineMs - startMs;
    }

    // ── Animated transform evaluation (S4; mirrors TextOverlayItem exactly:
    //    item-LOCAL keyframe time base, static field as the fallback) ──────

    /** "Armed" = has at least one keyframe (After-Effects stopwatch semantics). */
    public boolean isArmed() {
        return !keyframes.isEmpty();
    }

    private long localTime(long timelineMs) {
        return Math.max(0, timelineMs - startMs);
    }

    public float animatedCenterX(long timelineMs) {
        return keyframes.valueAt(KeyframeSet.X, localTime(timelineMs), centerX);
    }

    public float animatedCenterY(long timelineMs) {
        return keyframes.valueAt(KeyframeSet.Y, localTime(timelineMs), centerY);
    }

    /** Animated size fraction (the scale track stores the absolute fraction). */
    public float animatedSizeFraction(long timelineMs) {
        return keyframes.valueAt(KeyframeSet.SCALE, localTime(timelineMs), sizeFraction);
    }

    public float animatedRotation(long timelineMs) {
        return keyframes.valueAt(KeyframeSet.ROTATION, localTime(timelineMs), rotationDeg);
    }

    public float animatedOpacity(long timelineMs) {
        return keyframes.valueAt(KeyframeSet.OPACITY, localTime(timelineMs), opacity);
    }

    /** Record the current static transform as a keyframe at the given timeline
     *  time on every transform track (TextOverlayItem.addKeyframeAt semantics). */
    public void addKeyframeAt(long timelineMs) {
        long t = localTime(timelineMs);
        com.fadcam.ui.faditor.keyframe.Easing ease =
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        keyframes.getOrCreate(KeyframeSet.X).put(t, centerX, ease);
        keyframes.getOrCreate(KeyframeSet.Y).put(t, centerY, ease);
        keyframes.getOrCreate(KeyframeSet.SCALE).put(t, sizeFraction, ease);
        keyframes.getOrCreate(KeyframeSet.ROTATION).put(t, rotationDeg, ease);
        keyframes.getOrCreate(KeyframeSet.OPACITY).put(t, opacity, ease);
    }

    /**
     * G2 (gesture contract §2): keyframe-aware single-property write — mirrors
     * {@code TextOverlayItem.addPropertyKeyframeAt} exactly (anchor the shared
     * X/Y/SCALE pose tracks at this time, then write the one property; values
     * clamped like the static setters).
     */
    public void addPropertyKeyframeAt(@NonNull String property, long timelineMs, float value) {
        long t = localTime(timelineMs);
        com.fadcam.ui.faditor.keyframe.Easing ease =
                com.fadcam.ui.faditor.keyframe.Easing.EASE_IN_OUT;
        keyframes.getOrCreate(KeyframeSet.X).put(t, centerX, ease);
        keyframes.getOrCreate(KeyframeSet.Y).put(t, centerY, ease);
        keyframes.getOrCreate(KeyframeSet.SCALE).put(t, sizeFraction, ease);
        float v = value;
        switch (property) {
            case KeyframeSet.OPACITY:
                v = Math.max(0f, Math.min(1f, value));
                break;
            case KeyframeSet.SCALE:
                v = Math.max(0.01f, value);
                break;
            default:
                break; // x/y/rotation are unclamped, like the static setters
        }
        keyframes.getOrCreate(property).put(t, v, ease);
    }

    // ── Undo snapshot (one undo step per preview gesture, house rule) ─────

    /** Immutable static-transform + keyframe snapshot for gesture undo. */
    public static class TransformSnapshot {
        public final float centerX, centerY, sizeFraction, rotationDeg, opacity;
        public final boolean flipH, flipV;
        @NonNull public final KeyframeSet keyframes;

        TransformSnapshot(@NonNull SpriteOverlayItem o) {
            this.centerX = o.centerX;
            this.centerY = o.centerY;
            this.sizeFraction = o.sizeFraction;
            this.rotationDeg = o.rotationDeg;
            this.opacity = o.opacity;
            this.flipH = o.flipH;
            this.flipV = o.flipV;
            this.keyframes = o.keyframes.copy();
        }

        public boolean matches(@NonNull TransformSnapshot other) {
            return centerX == other.centerX && centerY == other.centerY
                    && sizeFraction == other.sizeFraction
                    && rotationDeg == other.rotationDeg && opacity == other.opacity
                    && flipH == other.flipH && flipV == other.flipV
                    && keyframesEqual(keyframes, other.keyframes);
        }

        /** Structural equality, mirroring TextOverlayItem.TransformSnapshot. */
        private static boolean keyframesEqual(@NonNull KeyframeSet a, @NonNull KeyframeSet b) {
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> ta = trackList(a);
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> tb = trackList(b);
            if (ta.size() != tb.size()) return false;
            for (int i = 0; i < ta.size(); i++) {
                com.fadcam.ui.faditor.keyframe.KeyframeTrack x = ta.get(i), y = tb.get(i);
                if (!x.property.equals(y.property)) return false;
                if (x.keyframes.size() != y.keyframes.size()) return false;
                for (int j = 0; j < x.keyframes.size(); j++) {
                    com.fadcam.ui.faditor.keyframe.Keyframe kx = x.keyframes.get(j);
                    com.fadcam.ui.faditor.keyframe.Keyframe ky = y.keyframes.get(j);
                    if (kx.timeMs != ky.timeMs || kx.value != ky.value
                            || kx.easing != ky.easing) return false;
                }
            }
            return true;
        }

        private static java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> trackList(
                @NonNull KeyframeSet s) {
            java.util.List<com.fadcam.ui.faditor.keyframe.KeyframeTrack> out =
                    new java.util.ArrayList<>();
            for (com.fadcam.ui.faditor.keyframe.KeyframeTrack t : s.tracks()) out.add(t);
            return out;
        }
    }

    @NonNull
    public TransformSnapshot snapshotTransform() {
        return new TransformSnapshot(this);
    }

    public void restoreTransform(@NonNull TransformSnapshot s) {
        this.centerX = s.centerX;
        this.centerY = s.centerY;
        this.sizeFraction = s.sizeFraction;
        this.rotationDeg = s.rotationDeg;
        this.opacity = s.opacity;
        this.flipH = s.flipH;
        this.flipV = s.flipV;
        this.keyframes.copyFrom(s.keyframes);
    }
}
