package com.fadcam.ui.faditor.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fadcam.ui.faditor.effects.EffectStack;

/**
 * Represents a single video segment in the editor timeline.
 * Maps directly to a Media3 MediaItem with ClippingConfiguration.
 *
 * <p>Immutable-style: use setters sparingly; prefer creating new instances
 * or using the Builder when scaling to multi-clip editing.</p>
 */
public class Clip {

    @NonNull
    private final String id;

    @NonNull
    private Uri sourceUri;

    /** Start position within the source video (milliseconds). */
    private long inPointMs;

    /** End position within the source video (milliseconds). */
    private long outPointMs;

    /** Original (un-trimmed) duration of the source video (milliseconds). */
    private long sourceDurationMs;

    /** Playback speed multiplier (0.1 – 10.0, default 1.0). */
    private float speedMultiplier = 1.0f;

    /** Whether pitch is kept constant when speed changes (default true). */
    private boolean pitchCompensation = true;

    /** Whether audio is muted for this clip. */
    private boolean audioMuted = false;

    /** Volume level (0.0 = silence, 1.0 = original, 2.0 = 200%). */
    private float volumeLevel = 1.0f;

    /** Audio ducking amount: 0 = off, 0.3 = duck to 30% when voice detected. */
    private float duckAmount = 0f;

    /** Punch-in zoom: 1.0 = no zoom, 2.0 = 2x centered. */
    private float zoomLevel = 1.0f;
    private float zoomCenterX = 0.5f;
    private float zoomCenterY = 0.5f;

    /** Rotation in degrees: 0, 90, 180, 270. */
    private int rotationDegrees = 0;

    /** Whether the video is flipped horizontally (mirror). */
    private boolean flipHorizontal = false;

    /** Whether the video is flipped vertically. */
    private boolean flipVertical = false;

    /**
     * Crop aspect ratio preset key.
     * Values: "none", "16:9", "9:16", "4:3", "3:4", "1:1", "21:9", "custom"
     */
    @NonNull
    private String cropPreset = "none";

    /**
     * Custom crop bounds (normalised 0.0 – 1.0).
     * Only used when {@link #cropPreset} is "custom".
     */
    private float cropLeft = 0f;
    private float cropTop = 0f;
    private float cropRight = 1f;
    private float cropBottom = 1f;

    /**
     * Whether this clip is a still image (not a video).
     * Image clips have a fixed playback duration (e.g. 5 seconds)
     * and display as a single still frame during preview/export.
     */
    private boolean imageClip = false;

    /**
     * Source-time spans (ms, each {start,end}) removed by transcript editing.
     * These are non-destructive: the preview player skips them, the timeline
     * draws them as dark regions, and export bakes them out (the clip is split
     * into the kept ranges). Stored in source time so they survive trim/move.
     */
    @NonNull
    private final List<long[]> removedSpans = new ArrayList<>();

    /**
     * Detected silence ranges (source ms) shown as yellow "candidates" on the
     * timeline. Transient (re-detected each session): tapping one moves it into
     * {@link #removedSpans} (turns black / will be cut).
     */
    @NonNull
    private final transient List<long[]> silenceCandidates = new ArrayList<>();

    /**
     * The clip's transcript versions (words + struck state). A clip can hold
     * several — e.g. a fast Vosk pass for edit-by-text and a Whisper pass for
     * captions — and {@link #activeTranscriptIndex} selects the one in use.
     * Persisted so reopening a project never re-runs speech recognition.
     */
    @NonNull
    private final List<com.fadcam.ui.faditor.transcript.NamedTranscript> transcripts =
            new ArrayList<>();

    /** Index into {@link #transcripts} of the active version, or -1 if none. */
    private int activeTranscriptIndex = -1;

    /** Display name to show when this clip's source is missing (for relink). */
    @Nullable
    private String displayName;

    // ── Floating overlay-video placement (M-COMP-2, PLAN_LAYERS_V2 §3.3) ──

    /**
     * Non-null ⇒ this clip is a FLOATING overlay (PiP) living on that VIDEO/IMAGE
     * layer track, stored in {@code Timeline#overlayClips} — never in the master
     * {@code clips} list. {@code null} (the default) ⇒ an ordinary master clip;
     * every pre-existing serialization stays byte-identical.
     */
    @Nullable
    private String layerId;

    /**
     * Absolute timeline start (ms) of a floating overlay clip — the persisted home
     * of {@code TimedItem#timelineStartMs} for clip payloads (text/audio/sprite
     * payloads carry their own start; master clips derive theirs by summation).
     * Meaningless while {@link #layerId} is null.
     */
    private long overlayStartMs;

    /**
     * Free-transform envelope (X/Y/SCALE/ROTATION/OPACITY) for a floating overlay
     * clip — the persisted home of {@code TimedItem#transform}. Uses the same
     * {@code KeyframeSet} primitive text overlays animate with. Null = identity.
     */
    @Nullable
    private com.fadcam.ui.faditor.keyframe.KeyframeSet overlayTransform;

    /**
     * Compositing blend-mode NAME ({@code layers.BlendMode}) for a floating overlay
     * clip. Stored as a String so the model package doesn't depend on the layers
     * package; parse with {@code BlendMode.fromName}. Only NORMAL is honoured until
     * M-EXPORT-2 lands the blend {@code GlEffect}.
     */
    @NonNull
    private String overlayBlendMode = "NORMAL";

    /**
     * Per-item compositing spec — vector masks / chroma key / track matte
     * (FEEDBACK_20260702 §C, one additive family). Null (the default) = none;
     * pre-existing serialization stays byte-identical.
     */
    @Nullable
    private CompositingSpec compositing;

    /** Whether animated on-screen captions are enabled for this clip. */
    private boolean captionsEnabled = false;

    /** Caption style preset id (see {@code CaptionStyle.presets()}). */
    @NonNull
    private String captionStyleId = "pop";

    /** Caption block centre X in normalised video-content coords [0,1]. */
    private float captionCenterX = 0.5f;

    /** Caption block centre Y in normalised video-content coords [0,1]. */
    private float captionCenterY = 0.82f;

    /** Caption text height as a fraction of the video height. */
    private float captionSizeFraction = 0.060f;

    // ── Loop / Ping-pong ─────────────────────────────────────────────

    /** Loop mode: 0=OFF, 1=LOOP (forward repeat), 2=PING_PONG (alternate), 3=STILL (freeze first/last frame). */
    public static final int LOOP_MODE_OFF = 0;
    public static final int LOOP_MODE_NORMAL = 1;
    public static final int LOOP_MODE_PING_PONG = 2;
    public static final int LOOP_MODE_STILL = 3;

    /**
     * PING-PONG PARKED (user decision 2026-07-02). True-reverse ping-pong (L2, baked reversed
     * segments) is DORMANT: it was buggy on-device (a reversed playlist item that failed to decode
     * blacked out the whole shared gapless player and the black spread across every clip; edge-drag
     * resize also fought the trim path). While this flag is true the entire L2 pipeline stays
     * PARKED but INTACT — no code deleted — behind these gates:
     *   • the loop drawer's ping-pong chip is disabled ("coming soon");
     *   • any clip that ALREADY has {@code loopMode == PING_PONG} (from a project saved before
     *     parking) DEGRADES GRACEFULLY to a plain forward NORMAL-loop wrap in BOTH preview and
     *     export — never black, never crash (see {@code MasterPlaybackEngine} eligibility gate via
     *     the resolver returning null, and {@code ExportManager.buildLoopExtensionItem}'s reverse
     *     gate);
     *   • NO reverse bake is ever kicked (incl. from resize) while parked.
     * Flip to {@code false} to un-park the L2 ping-pong path exactly as it was at 9d9539c.
     *
     * <p>UNPARKED (2026-07-03, tasks/PLAN_PINGPONG_UNPARK): the blackout amplifier is fixed —
     * MasterPlaybackEngine now has an onPlayerError that poisons a failing reversed URI and degrades
     * that clip to forward reps ONLY (scoped, no whole-timeline blackout), the reversed segments now
     * bake single-codec HEVC (hevc_mediacodec, hardened-libx264 fallback) so the playlist stays hvc1
     * end-to-end (no HEVC→AVC decoder swap at the seam), and the bake-complete auto-promote is gated
     * on a rebuild generation so a stale promote can't revert a resize. All 4 seams below reactivate.
     */
    public static final boolean PING_PONG_PARKED = false;

    private int loopMode = LOOP_MODE_OFF;

    /** Loop extension before the clip start (ms), rendered semi-transparent. */
    private long loopBeforeMs = 0;

    /** Loop extension after the clip end (ms), rendered semi-transparent. */
    private long loopAfterMs = 0;

    /** Tracks playback direction toggle for ping-pong (transient, not persisted). */
    private transient boolean pingPongForward = true;

    public int getLoopMode() { return loopMode; }
    public void setLoopMode(int mode) {
        this.loopMode = (mode == LOOP_MODE_NORMAL || mode == LOOP_MODE_PING_PONG || mode == LOOP_MODE_STILL) ? mode : LOOP_MODE_OFF;
        if (loopMode == LOOP_MODE_OFF) {
            loopBeforeMs = 0;
            loopAfterMs = 0;
        }
    }

    public long getLoopBeforeMs() { return loopBeforeMs; }
    public void setLoopBeforeMs(long ms) { this.loopBeforeMs = Math.max(0, ms); }

    public long getLoopAfterMs() { return loopAfterMs; }
    public void setLoopAfterMs(long ms) { this.loopAfterMs = Math.max(0, ms); }

    public boolean hasLoopExtension() {
        return loopMode != LOOP_MODE_OFF && (loopBeforeMs > 0 || loopAfterMs > 0);
    }

    public boolean isLoopModeLooping() {
        return loopMode == LOOP_MODE_NORMAL || loopMode == LOOP_MODE_PING_PONG;
    }

    /**
     * Total visual duration including loop extensions.
     */
    public long getVisualDurationMs() {
        return getTrimmedDurationMs() + loopBeforeMs + loopAfterMs;
    }

    /**
     * Map a timeline-visual position (0-based from clip start on the timeline)
     * to the corresponding absolute source position. Used for thumbnail rendering
     * in loop regions, playback seeking, and export frame mapping.
     *
     * @param visualMs position on the timeline relative to clip start
     * @return absolute source ms, or -1 if out of range
     */
    public long mapToSourceMs(long visualMs) {
        long trimmed = getTrimmedDurationMs();
        if (trimmed <= 0) return inPointMs;
        long visualDuration = getVisualDurationMs();
        if (visualMs < 0 || visualMs >= visualDuration) return -1;

        long posInBase = visualMs - loopBeforeMs;

        // Inside the base clip region
        if (posInBase >= 0 && posInBase < trimmed) {
            return inPointMs + (long) (posInBase * speedMultiplier);
        }

        // In loop extension
        if (loopMode == LOOP_MODE_OFF) {
            return posInBase < 0 ? inPointMs : outPointMs - 1;
        }

        // Still mode: freeze first frame backwards, last frame forwards
        if (loopMode == LOOP_MODE_STILL) {
            return posInBase < 0 ? inPointMs : outPointMs - 1;
        }

        // Compute which repetition and offset within it
        long absPos = posInBase < 0 ? -posInBase - 1 : posInBase - trimmed;
        long rep = absPos / trimmed;
        long offsetInRep = absPos % trimmed;

        if (loopMode == LOOP_MODE_NORMAL) {
            // Always forward
            return inPointMs + (long) (offsetInRep * speedMultiplier);
        } else {
            // Ping-pong: alternate direction each repetition
            if (rep % 2 == 0) {
                return inPointMs + (long) (offsetInRep * speedMultiplier);
            } else {
                return outPointMs - (long) (offsetInRep * speedMultiplier) - 1;
            }
        }
    }

    /** Toggle the ping-pong direction (called when playback wraps). */
    public void togglePingPongDirection() { pingPongForward = !pingPongForward; }
    public boolean isPingPongForward() { return pingPongForward; }

    /**
     * Opacity automation keyframes (the visual "fade" envelope on the clip).
     * Each keyframe is a (clip-local time in ms, opacity 0–1) point. Time is
     * measured from the START of this clip on the timeline (0 = clip start),
     * independent of trim so the envelope rides with the clip. Sorted ascending
     * by time. When non-empty, the interpolated opacity (see
     * {@link #opacityAtClipMs(long)}) overrides the default 1.0 on both preview
     * and export.
     */
    @NonNull
    private final List<OpacityKeyframe> opacityKeyframes = new ArrayList<>();

    /** A single point on the clip opacity envelope. */
    public static class OpacityKeyframe {
        /** Clip-local time in ms (0 = clip start on the timeline). */
        public long timeMs;
        /** Opacity 0.0–1.0 (0 = invisible, 1 = fully visible). */
        public float opacity;

        public OpacityKeyframe(long timeMs, float opacity) {
            this.timeMs = timeMs;
            this.opacity = opacity;
        }
    }

    @NonNull
    private final List<CaptionStyleKeyframe> captionStyleKeyframes = new ArrayList<>();

    /** A single keyframe in the caption style animation track. */
    public static class CaptionStyleKeyframe {
        /** Clip-local time in ms (0 = clip start on the timeline). */
        public long timeMs;
        /** CaptionStyle id (e.g. "pop", "zoom", "hidden"). */
        @NonNull public String styleId;

        public CaptionStyleKeyframe(long timeMs, @NonNull String styleId) {
            this.timeMs = timeMs;
            this.styleId = styleId;
        }
    }

    /** A single point on the clip volume envelope (for video clips that carry audio). */
    public static class VolumeKeyframe {
        /** Clip-local time in ms (0 = clip start on the timeline). */
        public long timeMs;
        /** Volume/gain 0.0–2.0 (0 = silent, 1 = original, 2 = 200%). */
        public float volume;

        public VolumeKeyframe(long timeMs, float volume) {
            this.timeMs = timeMs;
            this.volume = volume;
        }
    }

    @NonNull
    private final List<VolumeKeyframe> volumeKeyframes = new ArrayList<>();

    private EffectStack effectStack = new EffectStack();

    /**
     * Present only when this clip is an AI-authored fullscreen slide. Carries the
     * authoring recipe (HTML + params); the rendered MP4 it points {@link #sourceUri}
     * at is a regenerable cache. Null for ordinary clips.
     */
    @Nullable
    private GeneratedSource generatedSource;

    /**
     * Create a new Clip from a video URI.
     *
     * @param sourceUri        URI of the source video file
     * @param sourceDurationMs total duration of the source video in ms
     */
    public Clip(@NonNull Uri sourceUri, long sourceDurationMs) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = sourceUri;
        this.inPointMs = 0;
        this.outPointMs = sourceDurationMs;
        this.sourceDurationMs = sourceDurationMs;
    }

    /**
     * Constructor for deserialization / cloning.
     */
    public Clip(@NonNull String id, @NonNull Uri sourceUri,
         long inPointMs, long outPointMs, long sourceDurationMs,
         float speedMultiplier, boolean audioMuted, float volumeLevel,
         int rotationDegrees, boolean flipHorizontal, boolean flipVertical,
         @NonNull String cropPreset,
         float cropLeft, float cropTop, float cropRight, float cropBottom) {
        this.id = id;
        this.sourceUri = sourceUri;
        this.inPointMs = inPointMs;
        this.outPointMs = outPointMs;
        this.sourceDurationMs = sourceDurationMs;
        this.speedMultiplier = speedMultiplier;
        this.audioMuted = audioMuted;
        this.volumeLevel = volumeLevel;
        this.rotationDegrees = rotationDegrees;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.cropPreset = cropPreset;
        this.cropLeft = cropLeft;
        this.cropTop = cropTop;
        this.cropRight = cropRight;
        this.cropBottom = cropBottom;
    }

    /**
     * Deep copy constructor. Creates a new clip with a fresh ID
     * but identical source, trim, and all effect settings.
     *
     * @param other the clip to copy
     */
    public Clip(@NonNull Clip other) {
        this(other, UUID.randomUUID().toString());
    }

    /**
     * Deep copy with an explicit id. Used when splitting a clip into two children
     * whose ids must be known to a follow-up operation (e.g. {@code REORDER_CLIPS}).
     *
     * @param other the clip to copy
     * @param newId the id to assign the copy
     */
    public Clip(@NonNull Clip other, @NonNull String newId) {
        this.id = newId;
        this.sourceUri = other.sourceUri;
        this.inPointMs = other.inPointMs;
        this.outPointMs = other.outPointMs;
        this.sourceDurationMs = other.sourceDurationMs;
        this.speedMultiplier = other.speedMultiplier;
        this.pitchCompensation = other.pitchCompensation;
        this.audioMuted = other.audioMuted;
        this.volumeLevel = other.volumeLevel;
        this.rotationDegrees = other.rotationDegrees;
        this.flipHorizontal = other.flipHorizontal;
        this.flipVertical = other.flipVertical;
        this.cropPreset = other.cropPreset;
        this.cropLeft = other.cropLeft;
        this.cropTop = other.cropTop;
        this.cropRight = other.cropRight;
        this.cropBottom = other.cropBottom;
        this.imageClip = other.imageClip;
        for (long[] s : other.removedSpans) {
            this.removedSpans.add(new long[]{s[0], s[1]});
        }
        for (long[] s : other.silenceCandidates) {
            this.silenceCandidates.add(new long[]{s[0], s[1]});
        }
        for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : other.transcripts) {
            this.transcripts.add(nt.copy());
        }
        this.activeTranscriptIndex = other.activeTranscriptIndex;
        this.displayName = other.displayName;
        this.captionsEnabled = other.captionsEnabled;
        this.captionStyleId = other.captionStyleId;
        this.captionCenterX = other.captionCenterX;
        this.captionCenterY = other.captionCenterY;
        this.captionSizeFraction = other.captionSizeFraction;
        this.effectStack = new EffectStack(other.effectStack);
        this.duckAmount = other.duckAmount;
        this.zoomLevel = other.zoomLevel;
        this.zoomCenterX = other.zoomCenterX;
        this.zoomCenterY = other.zoomCenterY;
        for (OpacityKeyframe kf : other.opacityKeyframes) {
            this.opacityKeyframes.add(new OpacityKeyframe(kf.timeMs, kf.opacity));
        }
        for (CaptionStyleKeyframe kf : other.captionStyleKeyframes) {
            this.captionStyleKeyframes.add(new CaptionStyleKeyframe(kf.timeMs, kf.styleId));
        }
        this.generatedSource = other.generatedSource != null
                ? other.generatedSource.copy() : null;
        this.loopMode = other.loopMode;
        this.loopBeforeMs = other.loopBeforeMs;
        this.loopAfterMs = other.loopAfterMs;
        // Floating overlay (PiP) identity/placement (M-COMP-2 review fix: a copy
        // of an overlay clip must stay an overlay clip; null/defaults for masters).
        this.layerId = other.layerId;
        this.overlayStartMs = other.overlayStartMs;
        this.overlayTransform = other.overlayTransform != null
                ? other.overlayTransform.copy() : null;
        this.overlayBlendMode = other.overlayBlendMode;
        this.compositing = other.compositing != null ? other.compositing.copy() : null;
    }

    /**
     * Returns a copy of this clip pointing at a NEW source URI but keeping every
     * edit (trim, speed, volume, crop, rotation, removed spans). Used to relink
     * a clip whose original source can no longer be accessed.
     */
    @NonNull
    public Clip relinked(@NonNull Uri newUri) {
        Clip c = new Clip(id, newUri, inPointMs, outPointMs, sourceDurationMs,
                speedMultiplier, audioMuted, volumeLevel, rotationDegrees,
                flipHorizontal, flipVertical, cropPreset,
                cropLeft, cropTop, cropRight, cropBottom);
        c.imageClip = imageClip;
        c.removedSpans.addAll(removedSpans);
        for (com.fadcam.ui.faditor.transcript.NamedTranscript nt : transcripts) {
            c.transcripts.add(nt.copy());
        }
        c.activeTranscriptIndex = activeTranscriptIndex;
        c.displayName = displayName;
        c.captionsEnabled = captionsEnabled;
        c.captionStyleId = captionStyleId;
        c.captionCenterX = captionCenterX;
        c.captionCenterY = captionCenterY;
        c.captionSizeFraction = captionSizeFraction;
        c.effectStack = new EffectStack(effectStack);
        c.pitchCompensation = pitchCompensation;
        c.duckAmount = duckAmount;
        c.zoomLevel = zoomLevel;
        c.zoomCenterX = zoomCenterX;
        c.zoomCenterY = zoomCenterY;
        for (OpacityKeyframe kf : opacityKeyframes) {
            c.opacityKeyframes.add(new OpacityKeyframe(kf.timeMs, kf.opacity));
        }
        for (CaptionStyleKeyframe kf : captionStyleKeyframes) {
            c.captionStyleKeyframes.add(new CaptionStyleKeyframe(kf.timeMs, kf.styleId));
        }
        c.generatedSource = generatedSource != null ? generatedSource.copy() : null;
        c.loopMode = loopMode;
        c.loopBeforeMs = loopBeforeMs;
        c.loopAfterMs = loopAfterMs;
        // Floating overlay (PiP) identity/placement — a relinked PiP must remain
        // a PiP on its layer (M-COMP-2 review fix).
        c.layerId = layerId;
        c.overlayStartMs = overlayStartMs;
        c.overlayTransform = overlayTransform != null ? overlayTransform.copy() : null;
        c.overlayBlendMode = overlayBlendMode;
        c.compositing = compositing != null ? compositing.copy() : null;
        return c;
    }

    // ── Getters ──────────────────────────────────────────────────────

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public Uri getSourceUri() {
        return sourceUri;
    }

    /**
     * Repoint a generated slide's source at a freshly baked render file.
     * Slides are the ONE case where a clip's source legitimately moves: the
     * render is a per-trim-state cache artifact, not user media. No-ops for
     * ordinary clips so nothing else can ever silently rewrite a source.
     */
    public void repointGeneratedSlideSource(@NonNull Uri uri) {
        if (generatedSource == null) return;
        this.sourceUri = uri;
    }

    public long getInPointMs() {
        return inPointMs;
    }

    public long getOutPointMs() {
        return outPointMs;
    }

    public long getSourceDurationMs() {
        return sourceDurationMs;
    }

    public float getSpeedMultiplier() {
        return speedMultiplier;
    }

    public boolean isPitchCompensationEnabled() {
        return pitchCompensation;
    }

    public void setPitchCompensationEnabled(boolean enabled) {
        this.pitchCompensation = enabled;
    }

    public boolean isAudioMuted() {
        return audioMuted;
    }

    public float getVolumeLevel() {
        return volumeLevel;
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public boolean isFlipHorizontal() {
        return flipHorizontal;
    }

    public boolean isFlipVertical() {
        return flipVertical;
    }

    @NonNull
    public String getCropPreset() {
        return cropPreset;
    }

    public float getCropLeft() { return cropLeft; }
    public float getCropTop() { return cropTop; }
    public float getCropRight() { return cropRight; }
    public float getCropBottom() { return cropBottom; }

    /**
     * Whether this clip represents a still image rather than a video.
     */
    public boolean isImageClip() {
        return imageClip;
    }

    /**
     * Source-time removed spans (each {startMs,endMs}), mutate-in-place list.
     * Skipped in preview, drawn dark on the timeline, baked out on export.
     */
    @NonNull
    public List<long[]> getRemovedSpans() {
        return removedSpans;
    }

    public boolean hasRemovedSpans() {
        return !removedSpans.isEmpty();
    }

    /**
     * Replace all removed spans (kept as the same list object so views holding
     * a reference stay valid). Spans are clamped to the trim and merged.
     */
    public void setRemovedSpans(@NonNull List<long[]> spans) {
        removedSpans.clear();
        removedSpans.addAll(spans);
    }

    @NonNull
    public List<long[]> getSilenceCandidates() {
        return silenceCandidates;
    }

    public void setSilenceCandidates(@NonNull List<long[]> spans) {
        silenceCandidates.clear();
        silenceCandidates.addAll(spans);
    }

    /**
     * Effective (post-edit) trimmed duration in ms: the trimmed length minus
     * removed spans, divided by speed. This is what the clip contributes to the
     * exported video.
     */
    public long getEffectiveDurationMs() {
        long removed = 0;
        for (long[] s : removedSpans) {
            long a = Math.max(inPointMs, s[0]);
            long b = Math.min(outPointMs, s[1]);
            if (b > a) removed += b - a;
        }
        long raw = (outPointMs - inPointMs) - removed;
        return (long) (Math.max(0, raw) / speedMultiplier);
    }

    /**
     * Trimmed duration in milliseconds (respects speed multiplier).
     */
    public long getTrimmedDurationMs() {
        long raw = outPointMs - inPointMs;
        return (long) (raw / speedMultiplier);
    }

    // ── Setters (for trim handle updates) ────────────────────────────

    /**
     * Set the rotation in degrees (clamped to 0, 90, 180, 270).
     *
     * @param degrees rotation angle; rounded to nearest 90°
     */
    public void setRotationDegrees(int degrees) {
        this.rotationDegrees = ((degrees % 360) + 360) % 360;
        // Snap to nearest 90
        this.rotationDegrees = (Math.round(this.rotationDegrees / 90f) * 90) % 360;
    }

    public void setFlipHorizontal(boolean flip) {
        this.flipHorizontal = flip;
    }

    public void setFlipVertical(boolean flip) {
        this.flipVertical = flip;
    }

    /**
     * Set the crop preset.
     *
     * @param preset one of "none", "16:9", "9:16", "4:3", "3:4", "1:1", "21:9", "custom"
     */
    public void setCropPreset(@NonNull String preset) {
        this.cropPreset = preset;
    }

    /**
     * Set custom crop bounds (normalised 0.0 – 1.0).
     * Automatically sets cropPreset to "custom".
     */
    public void setCustomCropBounds(float left, float top, float right, float bottom) {
        this.cropPreset = "custom";
        this.cropLeft = Math.max(0f, Math.min(left, 1f));
        this.cropTop = Math.max(0f, Math.min(top, 1f));
        this.cropRight = Math.max(0f, Math.min(right, 1f));
        this.cropBottom = Math.max(0f, Math.min(bottom, 1f));
    }

    /**
     * Set the in-point (start trim position).
     *
     * @param ms milliseconds from start of source; clamped to [0, outPointMs)
     */
    public void setInPointMs(long ms) {
        this.inPointMs = Math.max(0, Math.min(ms, outPointMs - 1));
    }

    /**
     * Set the out-point (end trim position).
     *
     * @param ms milliseconds from start of source; clamped to (inPointMs, sourceDurationMs]
     */
    public void setOutPointMs(long ms) {
        this.outPointMs = Math.max(inPointMs + 1, Math.min(ms, sourceDurationMs));
    }

    public void setSpeedMultiplier(float speed) {
        this.speedMultiplier = Math.max(0.1f, Math.min(speed, 10.0f));
    }

    public void setAudioMuted(boolean muted) {
        this.audioMuted = muted;
    }

    public void setVolumeLevel(float level) {
        this.volumeLevel = Math.max(0f, Math.min(level, 2.0f));
    }

    public float getDuckAmount() { return duckAmount; }
    public void setDuckAmount(float amount) {
        this.duckAmount = Math.max(0f, Math.min(amount, 1f));
    }

    public float getZoomLevel() { return zoomLevel; }
    public void setZoomLevel(float zoom) {
        this.zoomLevel = Math.max(1.0f, Math.min(zoom, 8.0f));
    }
    public float getZoomCenterX() { return zoomCenterX; }
    public float getZoomCenterY() { return zoomCenterY; }
    public void setZoomCenter(float x, float y) {
        this.zoomCenterX = Math.max(0f, Math.min(1f, x));
        this.zoomCenterY = Math.max(0f, Math.min(1f, y));
    }

    /**
     * Mark this clip as a still image clip (not video).
     *
     * @param imageClip true if this clip represents a still image
     */
    public void setImageClip(boolean imageClip) {
        this.imageClip = imageClip;
    }

    /**
     * Correct the source duration (e.g. after ExoPlayer reports the real duration
     * for fragmented MP4 files where MediaMetadataRetriever was inaccurate).
     *
     * @param ms corrected source duration in milliseconds
     */
    public void setSourceDurationMs(long ms) {
        this.sourceDurationMs = Math.max(1, ms);
    }

    @NonNull
    public EffectStack getEffectStack() {
        return effectStack;
    }

    /** AI-authored slide recipe, or null for an ordinary clip. */
    @Nullable
    public GeneratedSource getGeneratedSource() {
        return generatedSource;
    }

    public void setGeneratedSource(@Nullable GeneratedSource generatedSource) {
        this.generatedSource = generatedSource;
    }

    /** Whether this clip is an AI-authored animated slide. */
    public boolean isGeneratedSlide() {
        return generatedSource != null;
    }

    // ── Transcript & captions ────────────────────────────────────────

    /** All transcript versions for this clip (may be empty). */
    @NonNull
    public List<com.fadcam.ui.faditor.transcript.NamedTranscript> getTranscripts() {
        return transcripts;
    }

    public int getActiveTranscriptIndex() {
        return activeTranscriptIndex;
    }

    public void setActiveTranscriptIndex(int index) {
        this.activeTranscriptIndex = (index >= 0 && index < transcripts.size()) ? index : -1;
    }

    /** The active transcript version, or null if none. */
    @Nullable
    public com.fadcam.ui.faditor.transcript.NamedTranscript getActiveNamedTranscript() {
        return (activeTranscriptIndex >= 0 && activeTranscriptIndex < transcripts.size())
                ? transcripts.get(activeTranscriptIndex) : null;
    }

    /** The active transcript's words, or null if none — back-compat accessor. */
    @Nullable
    public com.fadcam.ui.faditor.transcript.Transcript getTranscript() {
        com.fadcam.ui.faditor.transcript.NamedTranscript nt = getActiveNamedTranscript();
        return nt == null ? null : nt.transcript;
    }

    /**
     * Add a transcript version and make it active. Returns the added version.
     */
    @NonNull
    public com.fadcam.ui.faditor.transcript.NamedTranscript addTranscript(
            @NonNull com.fadcam.ui.faditor.transcript.NamedTranscript named) {
        transcripts.add(named);
        activeTranscriptIndex = transcripts.size() - 1;
        return named;
    }

    /** Remove the version at index, fixing up the active selection. */
    public void removeTranscript(int index) {
        if (index < 0 || index >= transcripts.size()) return;
        transcripts.remove(index);
        if (transcripts.isEmpty()) {
            activeTranscriptIndex = -1;
        } else if (activeTranscriptIndex >= transcripts.size()) {
            activeTranscriptIndex = transcripts.size() - 1;
        }
    }

    public boolean hasTranscript() {
        com.fadcam.ui.faditor.transcript.Transcript t = getTranscript();
        return t != null && !t.isEmpty();
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(@Nullable String displayName) {
        this.displayName = displayName;
    }

    // ── Floating overlay-video accessors (M-COMP-2) ──────────────────

    /** @see #layerId */
    @Nullable
    public String getLayerId() {
        return layerId;
    }

    public void setLayerId(@Nullable String layerId) {
        this.layerId = layerId;
    }

    /** True when this clip is a floating overlay (PiP) rather than a master clip. */
    public boolean isOverlayClip() {
        return layerId != null;
    }

    /** @see #overlayStartMs */
    public long getOverlayStartMs() {
        return overlayStartMs;
    }

    public void setOverlayStartMs(long overlayStartMs) {
        this.overlayStartMs = Math.max(0, overlayStartMs);
    }

    /** @see #overlayTransform */
    @Nullable
    public com.fadcam.ui.faditor.keyframe.KeyframeSet getOverlayTransform() {
        return overlayTransform;
    }

    public void setOverlayTransform(
            @Nullable com.fadcam.ui.faditor.keyframe.KeyframeSet overlayTransform) {
        this.overlayTransform = overlayTransform;
    }

    /** @see #overlayBlendMode */
    @NonNull
    public String getOverlayBlendMode() {
        return overlayBlendMode;
    }

    /** @see #compositing */
    @Nullable
    public CompositingSpec getCompositing() {
        return compositing;
    }

    /** An empty spec normalizes to null so serialization stays omit-clean. */
    public void setCompositing(@Nullable CompositingSpec spec) {
        this.compositing = spec != null && spec.isEmpty() ? null : spec;
    }

    public void setOverlayBlendMode(@Nullable String overlayBlendMode) {
        this.overlayBlendMode = overlayBlendMode == null ? "NORMAL" : overlayBlendMode;
    }

    public boolean isCaptionsEnabled() {
        return captionsEnabled;
    }

    public void setCaptionsEnabled(boolean enabled) {
        this.captionsEnabled = enabled;
    }

    @NonNull
    public String getCaptionStyleId() {
        return captionStyleId;
    }

    public void setCaptionStyleId(@NonNull String id) {
        this.captionStyleId = id;
    }

    public float getCaptionCenterX() { return captionCenterX; }

    public float getCaptionCenterY() { return captionCenterY; }

    public void setCaptionCenter(float x, float y) {
        this.captionCenterX = Math.max(0f, Math.min(1f, x));
        this.captionCenterY = Math.max(0f, Math.min(1f, y));
    }

    public float getCaptionSizeFraction() { return captionSizeFraction; }

    public void setCaptionSizeFraction(float f) {
        this.captionSizeFraction = Math.max(0.02f, Math.min(0.6f, f));
    }

    // ── Caption style keyframes ───────────────────────────────────────

    @NonNull
    public List<CaptionStyleKeyframe> getCaptionStyleKeyframes() { return captionStyleKeyframes; }

    public boolean hasCaptionStyleKeyframes() { return !captionStyleKeyframes.isEmpty(); }

    /** Tolerance (ms) used to snap a playhead position onto an existing keyframe. */
    public static final long CAPTION_STYLE_KEYFRAME_TOLERANCE_MS = 50;

    public void addOrUpdateCaptionStyleKeyframe(long timeMs, @NonNull String styleId) {
        timeMs = Math.max(0, timeMs);
        for (CaptionStyleKeyframe kf : captionStyleKeyframes) {
            if (Math.abs(kf.timeMs - timeMs) <= CAPTION_STYLE_KEYFRAME_TOLERANCE_MS) {
                kf.styleId = styleId;
                // Keep the scalar base style in sync with keyframe[0] so that the span
                // BEFORE the first keyframe (and the fallback used once all keyframes are
                // removed) always reflects the most recently chosen style, not whatever
                // captionStyleId happened to be when keyframe mode was first armed.
                sortCaptionStyleKeyframes();
                if (!captionStyleKeyframes.isEmpty() && captionStyleKeyframes.get(0) == kf) {
                    captionStyleId = styleId;
                }
                return;
            }
        }
        captionStyleKeyframes.add(new CaptionStyleKeyframe(timeMs, styleId));
        sortCaptionStyleKeyframes();
        if (captionStyleKeyframes.get(0).timeMs == timeMs) {
            // New keyframe became (or already was) the earliest one — same sync as above.
            captionStyleId = styleId;
        }
    }

    /** Remove the keyframe nearest to timeMs (within tolerance). */
    public void removeCaptionStyleKeyframe(long timeMs) {
        for (int i = 0; i < captionStyleKeyframes.size(); i++) {
            if (Math.abs(captionStyleKeyframes.get(i).timeMs - timeMs) <= CAPTION_STYLE_KEYFRAME_TOLERANCE_MS) {
                boolean wasFirst = (i == 0);
                captionStyleKeyframes.remove(i);
                if (wasFirst && !captionStyleKeyframes.isEmpty()) {
                    // The removed keyframe was the earliest one — the next remaining
                    // keyframe's style now governs the span from clip-start up to its own
                    // time, so it should "extend back" rather than reverting to a stale
                    // base style (spec: caption-style-keyframe UX point 4).
                    captionStyleId = captionStyleKeyframes.get(0).styleId;
                }
                return;
            }
        }
    }

    /** Remove the keyframe at the given list index. */
    public void removeCaptionStyleKeyframeAt(int index) {
        if (index >= 0 && index < captionStyleKeyframes.size()) {
            captionStyleKeyframes.remove(index);
        }
    }

    public void clearCaptionStyleKeyframes() { captionStyleKeyframes.clear(); }

    /** Replace all caption-style keyframes (used by undo/redo). Deep-copies, clamps and sorts. */
    public void setCaptionStyleKeyframes(@NonNull List<CaptionStyleKeyframe> kfs) {
        captionStyleKeyframes.clear();
        for (CaptionStyleKeyframe kf : kfs) {
            captionStyleKeyframes.add(new CaptionStyleKeyframe(Math.max(0, kf.timeMs), kf.styleId));
        }
        sortCaptionStyleKeyframes();
        // Keep the scalar base style derived from keyframe[0] (see addOrUpdate/remove) so
        // undo/redo of a keyframe-list change is fully self-contained — restoring the list
        // alone reproduces the correct pre-first-keyframe span without a separate
        // captionStyleId snapshot in the undo action.
        if (!captionStyleKeyframes.isEmpty()) {
            captionStyleId = captionStyleKeyframes.get(0).styleId;
        }
    }

    private void sortCaptionStyleKeyframes() {
        java.util.Collections.sort(captionStyleKeyframes,
                java.util.Comparator.comparingLong(k -> k.timeMs));
    }

    /**
     * The effective caption style ID at a given clip-local time (ms).
     * Returns the clip's base {@link #captionStyleId} when there are no
     * keyframes, or when {@code clipMs} is before the first keyframe.
     * Otherwise flat-held from the preceding keyframe.
     */
    @NonNull
    public String captionStyleAtClipMs(long clipMs) {
        if (captionStyleKeyframes.isEmpty()) return captionStyleId;
        sortCaptionStyleKeyframes();
        if (clipMs < captionStyleKeyframes.get(0).timeMs) return captionStyleId;
        CaptionStyleKeyframe best = captionStyleKeyframes.get(0);
        for (CaptionStyleKeyframe kf : captionStyleKeyframes) {
            if (kf.timeMs <= clipMs) best = kf;
            else break;
        }
        return best.styleId;
    }

    // ── Volume keyframes (audio gain envelope for video clips) ───────

    /** The volume envelope keyframes (sorted ascending by time). */
    @NonNull
    public List<VolumeKeyframe> getVolumeKeyframes() { return volumeKeyframes; }

    public boolean hasVolumeKeyframes() { return !volumeKeyframes.isEmpty(); }

    /** Replace all keyframes (used on project load). Sorts and clamps. */
    public void setVolumeKeyframes(@NonNull List<VolumeKeyframe> kfs) {
        volumeKeyframes.clear();
        for (VolumeKeyframe kf : kfs) {
            volumeKeyframes.add(new VolumeKeyframe(
                    Math.max(0, kf.timeMs), Math.max(0f, Math.min(kf.volume, 2.0f))));
        }
        sortVolumeKeyframes();
    }

    /**
     * Add a keyframe at {@code timeMs} (clip-local), or update the volume of
     * an existing keyframe at (approximately) the same time.
     */
    public void addOrUpdateVolumeKeyframe(long timeMs, float volume) {
        timeMs = Math.max(0, timeMs);
        volume = Math.max(0f, Math.min(volume, 2.0f));
        for (VolumeKeyframe kf : volumeKeyframes) {
            if (Math.abs(kf.timeMs - timeMs) <= 60) {
                kf.volume = volume;
                sortVolumeKeyframes();
                return;
            }
        }
        volumeKeyframes.add(new VolumeKeyframe(timeMs, volume));
        sortVolumeKeyframes();
    }

    public void clearVolumeKeyframes() { volumeKeyframes.clear(); }

    private void sortVolumeKeyframes() {
        java.util.Collections.sort(volumeKeyframes,
                java.util.Comparator.comparingLong(k -> k.timeMs));
    }

    /**
     * The effective volume/gain at a given clip-local time (ms). When keyframes
     * exist, the gain is linearly interpolated between surrounding keyframes
     * (flat-held before the first / after the last). When none exist, returns
     * the clip's base {@link #volumeLevel}.
     */
    public float gainAtClipMs(long clipMs) {
        if (volumeKeyframes.isEmpty()) return volumeLevel;
        if (volumeKeyframes.size() == 1) return volumeKeyframes.get(0).volume;
        VolumeKeyframe first = volumeKeyframes.get(0);
        if (clipMs <= first.timeMs) return first.volume;
        VolumeKeyframe last = volumeKeyframes.get(volumeKeyframes.size() - 1);
        if (clipMs >= last.timeMs) return last.volume;
        for (int i = 0; i < volumeKeyframes.size() - 1; i++) {
            VolumeKeyframe a = volumeKeyframes.get(i);
            VolumeKeyframe b = volumeKeyframes.get(i + 1);
            if (clipMs >= a.timeMs && clipMs <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) return b.volume;
                float frac = (clipMs - a.timeMs) / (float) span;
                return a.volume + (b.volume - a.volume) * frac;
            }
        }
        return last.volume;
    }

    // ── Opacity keyframes (visual fade envelope) ─────────────────────

    /** The opacity envelope keyframes (sorted ascending by time). */
    @NonNull
    public List<OpacityKeyframe> getOpacityKeyframes() { return opacityKeyframes; }

    public boolean hasOpacityKeyframes() { return !opacityKeyframes.isEmpty(); }

    /** Replace all keyframes (used on project load). Sorts and clamps. */
    public void setOpacityKeyframes(@NonNull List<OpacityKeyframe> kfs) {
        opacityKeyframes.clear();
        for (OpacityKeyframe kf : kfs) {
            opacityKeyframes.add(new OpacityKeyframe(
                    Math.max(0, kf.timeMs), Math.max(0f, Math.min(kf.opacity, 1.0f))));
        }
        sortOpacityKeyframes();
    }

    /**
     * Add a keyframe at {@code timeMs} (clip-local), or update the opacity of
     * an existing keyframe at (approximately) the same time.
     */
    public void addOrUpdateOpacityKeyframe(long timeMs, float opacity) {
        timeMs = Math.max(0, timeMs);
        opacity = Math.max(0f, Math.min(opacity, 1.0f));
        for (OpacityKeyframe kf : opacityKeyframes) {
            if (Math.abs(kf.timeMs - timeMs) <= 40) {
                kf.opacity = opacity;
                return;
            }
        }
        opacityKeyframes.add(new OpacityKeyframe(timeMs, opacity));
        sortOpacityKeyframes();
    }

    public void clearOpacityKeyframes() { opacityKeyframes.clear(); }

    private void sortOpacityKeyframes() {
        java.util.Collections.sort(opacityKeyframes,
                java.util.Comparator.comparingLong(k -> k.timeMs));
    }

    /**
     * The effective opacity at a given clip-local time (ms). When keyframes
     * exist, the opacity is linearly interpolated between the surrounding
     * keyframes (flat-held before the first / after the last). When none
     * exist, returns 1.0 (fully visible).
     */
    public float opacityAtClipMs(long clipMs) {
        if (opacityKeyframes.isEmpty()) return 1.0f;
        if (opacityKeyframes.size() == 1) return opacityKeyframes.get(0).opacity;
        OpacityKeyframe first = opacityKeyframes.get(0);
        if (clipMs <= first.timeMs) return first.opacity;
        OpacityKeyframe last = opacityKeyframes.get(opacityKeyframes.size() - 1);
        if (clipMs >= last.timeMs) return last.opacity;
        for (int i = 0; i < opacityKeyframes.size() - 1; i++) {
            OpacityKeyframe a = opacityKeyframes.get(i);
            OpacityKeyframe b = opacityKeyframes.get(i + 1);
            if (clipMs >= a.timeMs && clipMs <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) return b.opacity;
                float frac = (clipMs - a.timeMs) / (float) span;
                return a.opacity + (b.opacity - a.opacity) * frac;
            }
        }
        return last.opacity;
    }

    @NonNull
    @Override
    public String toString() {
        return "Clip{id=" + id
                + ", in=" + inPointMs
                + ", out=" + outPointMs
                + ", speed=" + speedMultiplier
                + ", muted=" + audioMuted
                + ", rot=" + rotationDegrees
                + ", flipH=" + flipHorizontal
                + ", flipV=" + flipVertical
                + ", crop=" + cropPreset
                + ", loop=" + loopMode + "(" + loopBeforeMs + "+" + loopAfterMs + ")"
                + "}";
    }}
