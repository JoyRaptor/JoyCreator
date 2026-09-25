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
public class SpriteOverlayItem implements com.fadcam.ui.faditor.model.LinkPose {

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
    /** FADE_KNOBS §2.5: opacity fade durations (0 = none), stackable multiply over keyframes. */
    private long fadeInMs = 0;
    private long fadeOutMs = 0;
    private boolean flipH = false;
    private boolean flipV = false;

    // ── Timeline range (absolute ms; MAX_VALUE end = "rest of the timeline") ──
    private long startMs = 0;
    private long endMs = Long.MAX_VALUE;

    /** Track membership (M10 layer routing); null = the default "sprite" track. */
    @Nullable private String layerId;

    /** "hold" (default) | "loop" | "pingpong" — what happens after the last frame entry. */
    @NonNull private String endBehavior = "hold";

    /**
     * SPEC_IMAGE_SEQUENCE §6 — the object "continues" until something stops it.
     *
     * <p><b>This is the one idea in the spec flagged as dangerous, and the flag is deliberately
     * not the implementation.</b> The user asked for open-ended playback running <i>"until it hits
     * some other layer in that lane or the end of the project"</i>. Making a duration depend on
     * NEIGHBOURS means adding an unrelated object silently shortens this one, deleting one
     * silently extends it, and undo has to restore a length nobody authored — and this codebase
     * already has a ledger full of "the app moved my clip by itself" incidents that were
     * expensive to find.</p>
     *
     * <p>So the affordance is kept and the semantics are not: this is an INTENT flag, and
     * {@code endMs} is always a concrete resolved number. Whenever anything changes, the length
     * is recomputed and — if a neighbour cut it short — that is shown, never silent.</p>
     */
    private boolean continuesUntilBlocked;

    public boolean isContinuesUntilBlocked() { return continuesUntilBlocked; }
    public void setContinuesUntilBlocked(boolean v) { this.continuesUntilBlocked = v; }

    /**
     * True when the resolved end came from a NEIGHBOUR rather than from the sequence's own
     * length — i.e. the object was clipped. Transient (never serialised): it is a fact about the
     * current arrangement, and persisting it would let a stale "clipped" badge outlive the
     * neighbour that caused it.
     */
    private transient boolean clippedByNeighbour;

    public boolean isClippedByNeighbour() { return clippedByNeighbour; }
    public void setClippedByNeighbour(boolean v) { this.clippedByNeighbour = v; }

    /**
     * SPEC_IMAGE_SEQUENCE §2a — the first frame of the run this object shows.
     *
     * <p>ABSOLUTE resize "keeps each frame's resolved milliseconds and changes the frame COUNT",
     * and the spec is specific that <i>"trimming from the left vs the right decides WHICH five"</i>.
     * Trimming the right edge answers that by itself — the run simply ends early. Trimming the
     * LEFT has to drop frames off the FRONT, and this is where that lives.</p>
     *
     * <p>A non-destructive OFFSET rather than deleting entries from the preset, because the
     * preset is shared by every placement of the sheet and because dragging an edge back out
     * should bring the frames back — a drag that permanently destroys content is the one thing
     * {@code setOutPointMs} taught this project to be careful with (LEDGER, 2026-08-05).</p>
     *
     * <p>0 for every sequence that has never been left-trimmed in ABSOLUTE mode, which is what
     * keeps it free: {@link SpriteFrameResolver} adds a zero tick offset.</p>
     */
    private int sequenceStartFrame;

    public int getSequenceStartFrame() { return sequenceStartFrame; }
    public void setSequenceStartFrame(int f) { this.sequenceStartFrame = Math.max(0, f); }

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

    // ── SPEC Z slice 1: the sprite's own distortion ───────────────────────────────────────────
    //
    // JoyRaptor, 2026-09-13: "The distortion should be on the sprite itself — NOT the cells living
    // inside, which are transient. If I'm animating squash and stretch, bend to the head, I want
    // the mouth or facial expressions to follow underneath."
    //
    // So it lives HERE, on the sprite instance, and not on FrameTrack, not on a cell, and not on
    // the rig. The render order is: the FrameTrack picks a cell, the rig composes its parts, the
    // per-cell transform places them — and only then does this warp the composed raster. Authored
    // once, inherited by every cell and every pose.
    //
    // Shaped EXACTLY like TextOverlayItem's, down to the units (offsets are fractions of the
    // item's own untransformed size) and the accessor names, because two shapes is how two
    // behaviours start. If you are about to add a sprite-only convenience here, add it there too
    // or do not add it.
    private final float[] cornerPin =
            new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];

    @Nullable private com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec mesh;

    /**
     * A DEEP copy under a NEW id — what "duplicate this object" needs.
     *
     * <p>The {@link FrameTrack} and the {@link KeyframeSet} are both final and both copied
     * ENTRY BY ENTRY rather than shared. Sharing either would give two sprites one animation:
     * scrubbing a frame on the copy would move the original, which looks like the editor having
     * a mind of its own rather than like an aliased field.</p>
     *
     * <p>{@code layerId} is NOT copied — a duplicate belongs to whichever lane the caller puts
     * it on, and inheriting the original's lane is precisely what makes two objects overlap on
     * a row that forbids overlap.</p>
     *
     * <p>{@code clippedByNeighbour} is transient layout state, not content, and is deliberately
     * left at its default.</p>
     */
    @NonNull
    public SpriteOverlayItem copyWithNewId(@NonNull String newId) {
        SpriteOverlayItem c = new SpriteOverlayItem(newId, sheetId);
        c.hidden = hidden;
        c.locked = locked;
        c.centerX = centerX;
        c.centerY = centerY;
        c.sizeFraction = sizeFraction;
        c.rotationDeg = rotationDeg;
        c.opacity = opacity;
        c.fadeInMs = fadeInMs;
        c.fadeOutMs = fadeOutMs;
        c.flipH = flipH;
        c.flipV = flipV;
        c.startMs = startMs;
        c.endMs = endMs;
        c.endBehavior = endBehavior;
        c.continuesUntilBlocked = continuesUntilBlocked;
        c.sequenceStartFrame = sequenceStartFrame;
        c.avatarRigId = avatarRigId;
        for (FrameTrack.Key k : frameTrack.keys()) {
            FrameTrack.Key nk = k.presetId != null
                    ? FrameTrack.Key.ofPreset(k.timeMs, k.presetId)
                    : FrameTrack.Key.ofCell(k.timeMs, k.cellIndex);
            c.frameTrack.put(nk);
        }
        c.keyframes.copyFrom(keyframes);
        System.arraycopy(cornerPin, 0, c.cornerPin, 0,
                com.fadcam.ui.faditor.model.CornerPin.SIZE);
        // A copy gets its OWN bend, not a shared reference — aliasing one would make bending the
        // copy silently bend the original, the same trap the avatar track note below describes.
        c.mesh = mesh != null ? mesh.copy() : null;
        c.installMeshCurve();
        // The avatar param track has no copy() of its own; sharing it would alias a rig's pose
        // animation across two puppets. Dropped rather than shared -- a copy that quietly moves
        // with the original is worse than one that starts un-posed, and the rig id is kept so
        // the copy still knows what it is.
        c.avatarTrack = null;
        return c;
    }

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
    public long getFadeInMs() { return fadeInMs; }
    public long getFadeOutMs() { return fadeOutMs; }
    public void setFadeInMs(long ms) { this.fadeInMs = Math.max(0, ms); }
    public void setFadeOutMs(long ms) { this.fadeOutMs = Math.max(0, ms); }
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

    /**
     * Set the window with TRIM semantics: the transform keys stay put in PROJECT time.
     *
     * <p>Mirrors {@code TextOverlayItem.setTrimmedTimeRange} exactly, and for the same reason — a
     * sprite's keys use the same local base ({@link #localTime}), so moving the START moved every
     * key with it. Dragging the front edge is the user saying "show more of this", not "move the
     * animation"; moving the object along the timeline is the gesture that carries its keys, and
     * that one still goes through {@link #setTimeRange}.</p>
     *
     * <p>The frame CADENCE is a separate matter and stays with the caller: for a sequence the left
     * edge also decides which frames survive, which {@code applySequenceTrim} handles. This method
     * is only about where the transform animation sits in time.</p>
     */
    public void setTrimmedTimeRange(long startMs, long endMs) {
        long before = this.startMs;
        setTimeRange(startMs, endMs);
        long after = this.startMs;
        if (after != before) keyframes.shiftAll(before - after);
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
        float base = keyframes.valueAt(KeyframeSet.OPACITY, localTime(timelineMs), opacity);
        // FADE_KNOBS §2.5: fade handles multiply the base opacity (stackable — mirrors
        // TextOverlayItem.animatedOpacity; every preview + export site reads this).
        if (fadeInMs > 0 || fadeOutMs > 0) {
            long local = localTime(timelineMs);
            long dur = (endMs == Long.MAX_VALUE ? local + fadeOutMs + 1 : endMs - startMs);
            float factor;
            if (fadeInMs > 0 && local < fadeInMs) factor = (float) local / (float) fadeInMs;
            else if (fadeOutMs > 0 && local > dur - fadeOutMs) {
                long rem = dur - local; factor = Math.max(0f, (float) rem / (float) fadeOutMs);
            } else factor = 1f;
            return base * factor;
        }
        return base;
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
    // ── Corner pin + mesh: the same API TextOverlayItem exposes, deliberately ────────────────

    private static int pinIndex(int corner, int axis) {
        if (corner < 0 || corner > com.fadcam.ui.faditor.model.CornerPin.BL) return -1;
        if (axis != com.fadcam.ui.faditor.model.CornerPin.DX
                && axis != com.fadcam.ui.faditor.model.CornerPin.DY) return -1;
        return corner * 2 + axis;
    }

    /** One corner component's STATIC value. */
    public float getCornerPin(int corner, int axis) {
        int i = pinIndex(corner, axis);
        return i < 0 ? 0f : cornerPin[i];
    }

    /** Set one corner component, clamped to {@code CornerPin.MAX_OFFSET}. */
    public void setCornerPin(int corner, int axis, float value) {
        int i = pinIndex(corner, axis);
        if (i >= 0) cornerPin[i] = com.fadcam.ui.faditor.model.CornerPin.clamp(value);
    }

    /** Set all four corners at once from a packed array; shorter/null input is ignored. */
    public void setCornerPin(@Nullable float[] off8) {
        int n = com.fadcam.ui.faditor.model.CornerPin.SIZE;
        if (off8 == null || off8.length < n) return;
        for (int i = 0; i < n; i++) {
            cornerPin[i] = com.fadcam.ui.faditor.model.CornerPin.clamp(off8[i]);
        }
    }

    /** Copy the STATIC offsets into {@code out8}. */
    public void copyCornerPinInto(@NonNull float[] out8) {
        int n = com.fadcam.ui.faditor.model.CornerPin.SIZE;
        if (out8.length < n) return;
        System.arraycopy(cornerPin, 0, out8, 0, n);
    }

    /** Back to undistorted — the state every sprite starts in. */
    public void clearCornerPin() {
        java.util.Arrays.fill(cornerPin, 0f);
    }

    /**
     * Is this sprite distorted AT ALL — statically or by any keyframe?
     *
     * <p>The skip gate every render path checks first, and it reads the TRACKS rather than
     * sampling a time, for the same reason the image one does: the answer must not flicker between
     * frames of an animation, or the preview would swap how it draws the sprite mid-playback.
     */
    public boolean hasCornerPin() {
        if (!com.fadcam.ui.faditor.model.CornerPin.isFlat(cornerPin)) return true;
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < 2; a++) {
                com.fadcam.ui.faditor.keyframe.KeyframeTrack t =
                        keyframes.get(com.fadcam.ui.faditor.model.CornerPin.trackFor(c, a));
                if (t == null || t.isEmpty()) continue;
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : t.keyframes) {
                    if (Math.abs(k.value)
                            > com.fadcam.ui.faditor.model.CornerPin.EPSILON) return true;
                }
            }
        }
        return false;
    }

    /** The offsets at {@code timelineMs}, on this sprite's own local time base. */
    public void animatedCornerPin(long timelineMs, @NonNull float[] out8) {
        int n = com.fadcam.ui.faditor.model.CornerPin.SIZE;
        if (out8.length < n) return;
        long t = Math.max(0, timelineMs - startMs);
        for (int c = 0; c < 4; c++) {
            for (int a = 0; a < 2; a++) {
                int i = c * 2 + a;
                out8[i] = com.fadcam.ui.faditor.model.CornerPin.clamp(keyframes.valueAt(
                        com.fadcam.ui.faditor.model.CornerPin.trackFor(c, a), t, cornerPin[i]));
            }
        }
    }

    /**
     * The corner-pin matrix for this sprite at {@code timelineMs}, over the untransformed drawn
     * rect {@code (left, top, w, h)} in the CALLER's pixel space.
     *
     * <p>The ONE method the preview and the export both call, so the arithmetic has no second
     * transcription to drift from — the same discipline {@code TextOverlayItem.cornerPinMatrix}
     * already carries for images, and literally the same {@code CornerPin.buildMatrix} underneath.
     *
     * <p>Concat it INNERMOST, immediately around the cell draw and INSIDE the existing
     * rotate/flip. That placement is what makes the warp a property of the SPRITE rather than of
     * a cell: whatever is showing at that instant — a cell, a rig's composed parts — is drawn into
     * the same rect, so the pin distorts the composed result and every cell inherits it.
     *
     * @return true when {@code out} must be concat-ed; false when the sprite is undistorted at
     *         this time and the caller should draw exactly as it always did
     */
    public boolean cornerPinMatrix(@NonNull android.graphics.Matrix out, long timelineMs,
                                   float left, float top, float w, float h) {
        if (!hasCornerPin()) { out.reset(); return false; }
        float[] off = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
        animatedCornerPin(timelineMs, off);
        return com.fadcam.ui.faditor.model.CornerPin.buildMatrix(out, left, top, w, h, off);
    }

    /**
     * Must this sprite be drawn by GL rather than by the Canvas view?
     *
     * <p>JoyRaptor, 2026-09-13: <i>"We need sprites to be GL so that they interact with the other
     * layers properly for blending modes, masks, and adjustment layers. GL can work images just
     * fine, and the sprite is just an image."</i>
     *
     * <p>He is right about the consequence, and it is the reason this predicate exists rather than
     * a Canvas warp being enough. A sprite drawn on the Canvas is painted OVER the GL surface, so
     * it cannot be sampled by a blend above it, cannot be cut by a mask, and cannot be graded by
     * an adjustment layer — it sits outside the composite instead of inside it. Bending it on the
     * Canvas would have bought the warp and kept it locked out of everything else.
     *
     * <p>Mirrors {@code TextOverlayItem.wantsGlExport} in shape deliberately: same question, same
     * answer style, so the two families cannot drift into different ideas of "this one is GL". The
     * list is shorter only because a sprite has no blend, key or fx channel YET — when it gains
     * one, it is added here and everything downstream already works.
     *
     * <p>Checked BEFORE any GL object exists, so a project with no warped sprite compiles no
     * program and allocates no framebuffer.
     */
    public boolean wantsGl() {
        return hasMesh() || hasCornerPin();
    }

    /** True when a bend is authored. Checked before any GL object exists, so no bend costs zero. */
    public boolean hasMesh() {
        return mesh != null && mesh.hasWarp();
    }

    /** The bend spec, or null. Renderers copy it per-frame; never hand the live one out. */
    @Nullable
    public com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec getMesh() { return mesh; }

    /** Set/replace the bend (null clears). Installs the shared easing curve on its track. */
    public void setMesh(@Nullable com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec m) {
        this.mesh = m;
        installMeshCurve();
    }

    /** One easing authority for every warpable type — see {@code MeshCurves}. */
    public void installMeshCurve() {
        com.fadcam.ui.faditor.transform.mesh.MeshCurves.install(mesh);
    }

    /** Mesh time base is LOCAL, like every other animated property here. */
    public long meshLocalTime(long timelineMs) {
        return Math.max(0, timelineMs - startMs);
    }

    public static class TransformSnapshot {
        public final float centerX, centerY, sizeFraction, rotationDeg, opacity;
        public final boolean flipH, flipV;
        public final long fadeInMs, fadeOutMs;
        @NonNull public final KeyframeSet keyframes;
        /** SPEC Z: the distortion is part of the pose, so one undo returns it with everything else. */
        @NonNull public final float[] cornerPin;
        @Nullable public final com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec mesh;

        TransformSnapshot(@NonNull SpriteOverlayItem o) {
            this.cornerPin = new float[com.fadcam.ui.faditor.model.CornerPin.SIZE];
            System.arraycopy(o.cornerPin, 0, this.cornerPin, 0, this.cornerPin.length);
            // A snapshot holding the LIVE spec would move with the object it exists to restore.
            this.mesh = o.mesh != null ? o.mesh.copy() : null;
            this.centerX = o.centerX;
            this.centerY = o.centerY;
            this.sizeFraction = o.sizeFraction;
            this.rotationDeg = o.rotationDeg;
            this.opacity = o.opacity;
            this.flipH = o.flipH;
            this.flipV = o.flipV;
            this.fadeInMs = o.fadeInMs;
            this.fadeOutMs = o.fadeOutMs;
            this.keyframes = o.keyframes.copy();
        }

        public boolean matches(@NonNull TransformSnapshot other) {
            return centerX == other.centerX && centerY == other.centerY
                    && sizeFraction == other.sizeFraction
                    && rotationDeg == other.rotationDeg && opacity == other.opacity
                    && flipH == other.flipH && flipV == other.flipV
                    && fadeInMs == other.fadeInMs && fadeOutMs == other.fadeOutMs
                    && java.util.Arrays.equals(cornerPin, other.cornerPin)
                    && meshEqual(mesh, other.mesh)
                    && keyframesEqual(keyframes, other.keyframes);
        }

        /**
         * Two bends are equal when they serialise the same. Comparing specs field by field would
         * be a second definition of "same bend" that could disagree with the one the file format
         * already uses.
         */
        private static boolean meshEqual(
                @Nullable com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec a,
                @Nullable com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec b) {
            if (a == b) return true;
            boolean aWarp = a != null && a.hasWarp();
            boolean bWarp = b != null && b.hasWarp();
            if (!aWarp && !bWarp) return true;
            if (aWarp != bWarp) return false;
            try {
                com.google.gson.JsonObject ja = a.toJson();
                com.google.gson.JsonObject jb = b.toJson();
                return ja == null ? jb == null : ja.equals(jb);
            } catch (Exception ignored) {
                return false;   // cannot prove equal -> treat as changed, never as unchanged
            }
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
        this.fadeInMs = s.fadeInMs;
        this.fadeOutMs = s.fadeOutMs;
        this.keyframes.copyFrom(s.keyframes);
    }
}
