package com.fadcam.ui.faditor.model;

import android.net.Uri;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.Transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single audio clip on the audio track in the editor timeline.
 *
 * <p>Audio clips exist on a separate track from video segments and can be
 * independently positioned, trimmed, and dragged along the timeline.</p>
 *
 * <p>Key concepts:</p>
 * <ul>
 *   <li>{@link #sourceUri} – the audio file on disk (extracted AAC, or user-supplied).</li>
 *   <li>{@link #inPointMs} / {@link #outPointMs} – trim bounds within the audio source.</li>
 *   <li>{@link #offsetMs} – where this clip begins on the project timeline
 *       (0 = aligned with the very start of the video track).</li>
 *   <li>{@link #waveform} – downsampled amplitude array for timeline visualisation.</li>
 * </ul>
 */
public class AudioClip {

    // §4.5 per-OBJECT lock (LANE_BADGES spec, built 2026-07-19): locked = selectable but
    // never trims/moves/deletes. Audio has NO hidden twin — per-clip MUTE already is the
    // audible "eye". Tolerant storage: absent = false.
    private boolean locked;

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    @NonNull
    private final String id;

    /** URI of the audio source file. */
    @NonNull
    private Uri sourceUri;

    /** Total duration of the source audio file in milliseconds. */
    private long sourceDurationMs;

    /** Trim start within the source audio (ms). */
    private long inPointMs;

    /** Trim end within the source audio (ms). */
    private long outPointMs;

    /**
     * Offset from the beginning of the project timeline (ms).
     * Positive = the audio starts that many ms into the project.
     * Allows the user to drag the audio left/right to align with video.
     */
    private long offsetMs;

    /** Volume multiplier (0.0 – 2.0, default 1.0). */
    private float volumeLevel = 1.0f;

    /** Whether audio is muted. */
    private boolean muted = false;

    /**
     * Downsampled amplitude data for waveform visualisation.
     * Each value is 0–255 representing the peak amplitude for that sample window.
     * Null until waveform extraction has been performed.
     */
    private int[] waveform;

    /** User-visible label (e.g. "Extracted audio", "voice-note.mp3"). */
    @NonNull
    private String label = "Audio";

    /**
     * Persistent home for WHICH audio layer track this clip belongs to (M10; mirrors
     * {@link TextOverlayItem#getLayerId()}). {@code null} = the default/auto-migrated
     * single AUDIO track (id {@code "audio"}) — the only value any project saved
     * before M10 can have, so grouping every null/"audio"-layerId clip into ONE
     * track reproduces exactly today's single-AUDIO-track behavior.
     */
    @Nullable
    private String layerId;

    /**
     * Optional volume automation keyframes (the blue "rubber-band" envelope).
     * Each keyframe is a (clip-local time in ms, MULTIPLIER 0–1+) point: the stored
     * value scales this clip's {@link #volumeLevel}, so moving the volume slider AFTER a
     * fade is drawn rescales the whole envelope instead of stranding a stale peak (B1.Q,
     * JoyRaptor's ruling). Time is measured from the START of this clip on the timeline
     * (0 = clip start), independent of trim/offset so the envelope rides with the clip.
     * Sorted ascending by time. When non-empty, {@link #gainAtClipMs(long)} returns
     * {@code volumeLevel × interpolated multiplier} — the FINAL gain, identical to what
     * export's processor computes — so no caller outside the model needs to know the
     * envelope exists.
     *
     * <p><b>Legacy disk format:</b> projects saved before B1.Q stored ABSOLUTE gains here.
     * {@link #migrateLegacyAbsoluteEnvelope()} converts them at load; the on-disk marker is
     * ProjectStorage's {@code envMul} flag.</p>
     */
    @NonNull
    private final List<VolumeKeyframe> volumeKeyframes = new ArrayList<>();

    /**
     * Whether {@link #volumeKeyframes} hold multipliers over {@link #volumeLevel} (true,
     * post-B1.Q) or legacy ABSOLUTE gains (false — only ever true transiently while a
     * pre-B1.Q project is being loaded, before migration).
     */
    private boolean envelopeMultiplier = true;

    /** Struck-word cuts (same semantics as {@link Clip#getRemovedSpans()}): source-ms spans that decode as silence. */
    @NonNull
    private final List<long[]> removedSpans = new ArrayList<>();

    /**
     * C2.U: set while {@link #sourceUri} points at a BAKED render (denoise/loudnorm) rather
     * than the user's original file. Remembers where to revert to and which artifact to
     * delete on revert. Null = the source IS the original. Tolerant storage: absent = null.
     */
    @Nullable
    private String bakedFromUri;
    @Nullable
    private String bakedFromFile;

    // ── Transcripts (speech-to-text) ─────────────────────────────────

    @NonNull
    private final List<NamedTranscript> transcripts = new ArrayList<>();
    private int activeTranscriptIndex = -1;
    private boolean captionsEnabled = false;
    @NonNull
    private String captionStyleId = "pop";
    private float captionCenterX = 0.5f;
    private float captionCenterY = 0.82f;
    // Matches Clip's default (0.060) and the CaptionOverlayView preview default so audio
    // captions are not rendered 2x too large on export. Audio caption size is not persisted,
    // so this default IS the effective size unless changed in-session.
    private float captionSizeFraction = 0.060f;

    /** A single point on the audio volume envelope. */
    public static class VolumeKeyframe {
        /** Clip-local time in ms (0 = clip start on the timeline). */
        public long timeMs;
        /** Multiplier over the clip's volumeLevel (0–1 typical; B1.Q semantics). */
        public float volume;

        public VolumeKeyframe(long timeMs, float volume) {
            this.timeMs = timeMs;
            this.volume = volume;
        }
    }

    // ── Constructors ─────────────────────────────────────────────────

    /**
     * Create a new AudioClip.
     *
     * @param sourceUri      URI of the audio file
     * @param durationMs     total duration of the audio source
     */
    public AudioClip(@NonNull Uri sourceUri, long durationMs) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = sourceUri;
        this.sourceDurationMs = durationMs;
        this.inPointMs = 0;
        this.outPointMs = durationMs;
        this.offsetMs = 0;
    }

    /**
     * Copy constructor.
     */
    public AudioClip(@NonNull AudioClip other) {
        this.id = UUID.randomUUID().toString();
        this.sourceUri = other.sourceUri;
        this.sourceDurationMs = other.sourceDurationMs;
        this.inPointMs = other.inPointMs;
        this.outPointMs = other.outPointMs;
        this.offsetMs = other.offsetMs;
        this.volumeLevel = other.volumeLevel;
        this.muted = other.muted;
        this.label = other.label;
        // Layer-track membership must survive cloning (split creates both halves via
        // this constructor): dropping it re-lanes the copy onto the default AUDIO row.
        this.layerId = other.layerId;
        // Waveform data is shared (immutable int array after extraction)
        this.waveform = other.waveform;
        for (VolumeKeyframe kf : other.volumeKeyframes) {
            this.volumeKeyframes.add(new VolumeKeyframe(kf.timeMs, kf.volume));
        }
        this.envelopeMultiplier = other.envelopeMultiplier;
        // Copy transcripts
        for (NamedTranscript nt : other.transcripts) {
            this.transcripts.add(nt.copy());
        }
        this.activeTranscriptIndex = other.activeTranscriptIndex;
        this.captionsEnabled = other.captionsEnabled;
        this.captionStyleId = other.captionStyleId;
        this.captionCenterX = other.captionCenterX;
        this.captionCenterY = other.captionCenterY;
        this.captionSizeFraction = other.captionSizeFraction;
        for (long[] s : other.removedSpans) this.removedSpans.add(new long[]{s[0], s[1]});
        this.bakedFromUri = other.bakedFromUri;
        this.bakedFromFile = other.bakedFromFile;
    }

    // ── Getters ──────────────────────────────────────────────────────

    @NonNull
    public String getId() { return id; }

    @NonNull
    public Uri getSourceUri() { return sourceUri; }

    public long getSourceDurationMs() { return sourceDurationMs; }

    public long getInPointMs() { return inPointMs; }

    public long getOutPointMs() { return outPointMs; }

    public long getOffsetMs() { return offsetMs; }

    public float getVolumeLevel() { return volumeLevel; }

    public boolean isMuted() { return muted; }

    public int[] getWaveform() { return waveform; }

    @NonNull
    public String getLabel() { return label; }

    // ── Layer-track membership (M10) ────────────────────────────────────

    /** Stable id of the audio layer track this clip belongs to, or {@code null} for the default AUDIO track. */
    @Nullable
    public String getLayerId() { return layerId; }

    public void setLayerId(@Nullable String layerId) { this.layerId = layerId; }

    /**
     * Trimmed duration of this audio clip in milliseconds.
     */
    public long getTrimmedDurationMs() {
        return outPointMs - inPointMs;
    }

    /**
     * End position on the project timeline (offset + trimmed duration).
     */
    public long getEndOnTimelineMs() {
        return offsetMs + getTrimmedDurationMs();
    }

    // ── Setters ──────────────────────────────────────────────────────

    public void setSourceUri(@NonNull Uri uri) { this.sourceUri = uri; }

    public void setSourceDurationMs(long ms) { this.sourceDurationMs = ms; }

    public void setInPointMs(long ms) { this.inPointMs = Math.max(0, ms); }

    public void setOutPointMs(long ms) {
        this.outPointMs = Math.min(ms, sourceDurationMs);
    }

    public void setOffsetMs(long ms) { this.offsetMs = Math.max(0, ms); }

    public void setVolumeLevel(float level) {
        this.volumeLevel = Math.max(0f, Math.min(level, 2.0f));
    }

    public void setMuted(boolean muted) { this.muted = muted; }

    public void setWaveform(int[] waveform) { this.waveform = waveform; }

    public void setLabel(@NonNull String label) { this.label = label; }

    // ── Volume keyframes (automation envelope) ───────────────────────

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
        sortKeyframes();
    }

    /**
     * Add a keyframe at {@code timeMs} (clip-local), or update the gain of an
     * existing keyframe at (approximately) the same time. Keeps the list sorted.
     */
    public void addOrUpdateVolumeKeyframe(long timeMs, float volume) {
        timeMs = Math.max(0, timeMs);
        volume = Math.max(0f, Math.min(volume, 2.0f));
        for (VolumeKeyframe kf : volumeKeyframes) {
            if (Math.abs(kf.timeMs - timeMs) <= 40) { // ~1 frame tolerance
                kf.volume = volume;
                return;
            }
        }
        volumeKeyframes.add(new VolumeKeyframe(timeMs, volume));
        sortKeyframes();
    }

    public void clearVolumeKeyframes() { volumeKeyframes.clear(); }

    private void sortKeyframes() {
        Collections.sort(volumeKeyframes, Comparator.comparingLong(k -> k.timeMs));
    }

    /**
     * The FINAL gain at a given clip-local time (ms): {@code volumeLevel × interpolated
     * envelope multiplier} when keyframes exist, else the whole-clip {@link #volumeLevel}.
     * The multiply is ABSORBED here on purpose (B1.Q): preview ticks, the drawer's Level
     * row and the export processor all read final gains through this one method, so no
     * caller needs to know whether an envelope is armed. Muting is NOT applied — callers
     * handle mute. Fades keep storing 0f→1f; as multipliers they now correctly fade TO
     * the slider level instead of capping a boosted clip at 100%.
     */
    /**
     * Write an envelope keyframe from a FINAL GAIN — the number the user sees on a slider and
     * hears from the speaker (0..2) — converting into the MULTIPLIER space the store has held
     * since B1.Q ("fades stack", JoyRaptor 2026-08-23).
     *
     * <p>This is the write-side twin of {@code gainAtClipMs}, which absorbs the multiply on the
     * read side. Both conversions live in the model for the same reason: every caller outside
     * it thinks in final gain, and a caller that divides for itself is a second definition of
     * what a keyframe means — which is exactly how the preview and the file drift apart.</p>
     *
     * <p>A level at or near zero cannot be divided by. The clip is silent at any multiplier in
     * that state, so the raw value is stored rather than producing an infinity; this mirrors the
     * guard the load-time migration uses.</p>
     */
    public void addOrUpdateFinalGainKeyframe(long timeMs, float finalGain) {
        float lvl = getVolumeLevel();
        addOrUpdateVolumeKeyframe(timeMs, lvl > 0.0001f ? finalGain / lvl : finalGain);
    }

    public float gainAtClipMs(long clipMs) {
        if (volumeKeyframes.isEmpty()) return volumeLevel;
        return volumeLevel * multiplierAt(clipMs);
    }

    /** Raw multiplier interpolation over the stored keyframes (no volumeLevel applied). */
    private float multiplierAt(long clipMs) {
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

    // ── B1.Q legacy-envelope migration ───────────────────────────────

    /** Whether stored keyframes are multipliers over {@link #volumeLevel} (post-B1.Q). */
    public boolean isEnvelopeMultiplier() { return envelopeMultiplier; }

    public void setEnvelopeMultiplier(boolean multiplier) { this.envelopeMultiplier = multiplier; }

    /**
     * One-time migration of a PRE-B1.Q envelope: keys on disk were ABSOLUTE gains, so each
     * becomes {@code absolute / volumeLevel} to preserve the exact sound the user heard.
     *
     * <p>Guarded on {@code volumeLevel == 0}: any multiplier times zero is zero, so an
     * un-migratable silent clip stays silent either way — skipping the divide cannot change
     * what anyone hears. A no-op when the keyframes already hold multipliers.</p>
     */
    public void migrateLegacyAbsoluteEnvelope() {
        if (envelopeMultiplier) return;
        envelopeMultiplier = true;
        if (volumeLevel < 0.0001f) return;
        for (VolumeKeyframe kf : volumeKeyframes) {
            kf.volume = Math.max(0f, Math.min(kf.volume / volumeLevel, 2f));
        }
    }

    // ── Fades (SPEC_AUDIO_UX_V1 C1.U) ────────────────────────────────

    /**
     * THE single definition of a fade-in, shared by the Level-tab sliders and (as a
     * follow-up) {@code LayerGestureController.armFade}: the envelope STARTS with the pair
     * (0, 0f) → (fadeInMs, 1f), clip-local ms. Returns 0 when the envelope does not start
     * that way — including a hand-drawn envelope that merely begins low, which must not be
     * misread as a fade (then "set fade 0" would eat someone's curve).
     */
    public long getFadeInMs() {
        if (volumeKeyframes.size() < 2) return 0;
        VolumeKeyframe first = volumeKeyframes.get(0);
        if (first.timeMs != 0 || first.volume > 0f) return 0;
        return Math.max(0, volumeKeyframes.get(1).timeMs);
    }

    /** Mirror of {@link #getFadeInMs()} at the clip end: (dur−fadeOutMs, 1f) → (dur, 0f). */
    public long getFadeOutMs() {
        long dur = getTrimmedDurationMs();
        int n = volumeKeyframes.size();
        if (dur <= 0 || n < 2) return 0;
        VolumeKeyframe last = volumeKeyframes.get(n - 1);
        if (last.timeMs != dur || last.volume > 0f) return 0;
        return Math.max(0, dur - volumeKeyframes.get(n - 2).timeMs);
    }

    /**
     * Write (or clear, at 0) the fade-in, using EXACTLY the keyframe convention the fade
     * drag handle already writes, so the slider and the handle can never disagree.
     * Setting 0 removes the fade's own pair only — a hand-drawn envelope without a leading
     * (0, 0f) key reports 0 and passes through untouched.
     */
    public void setFadeInMs(long fadeMs) {
        long dur = getTrimmedDurationMs();
        if (dur <= 0) return;
        fadeMs = Math.max(0, Math.min(fadeMs, dur / 2));
        if (fadeMs <= 0) {
            long cur = getFadeInMs();
            if (cur > 0) removeVolumeKeysAtOrBefore(cur);
            return;
        }
        // Same region-clear the drag does, so re-dragging never stacks stale keys.
        removeVolumeKeysAtOrBefore(fadeMs);
        addOrUpdateVolumeKeyframe(0, 0f);
        addOrUpdateVolumeKeyframe(fadeMs, 1f);
    }

    /** Mirror of {@link #setFadeInMs(long)} at the clip end. */
    public void setFadeOutMs(long fadeMs) {
        long dur = getTrimmedDurationMs();
        if (dur <= 0) return;
        fadeMs = Math.max(0, Math.min(fadeMs, dur / 2));
        if (fadeMs <= 0) {
            long cur = getFadeOutMs();
            if (cur > 0) removeVolumeKeysAtOrAfter(dur - cur);
            return;
        }
        removeVolumeKeysAtOrAfter(dur - fadeMs);
        addOrUpdateVolumeKeyframe(dur - fadeMs, 1f);
        addOrUpdateVolumeKeyframe(dur, 0f);
    }

    private void removeVolumeKeysAtOrBefore(long timeMs) {
        volumeKeyframes.removeIf(kf -> kf.timeMs <= timeMs);
    }

    private void removeVolumeKeysAtOrAfter(long timeMs) {
        volumeKeyframes.removeIf(kf -> kf.timeMs >= timeMs);
    }

    // ── Transcripts ──────────────────────────────────────────────────

    @NonNull
    public List<NamedTranscript> getTranscripts() { return transcripts; }

    public int getActiveTranscriptIndex() { return activeTranscriptIndex; }

    public void setActiveTranscriptIndex(int index) {
        this.activeTranscriptIndex = (index >= 0 && index < transcripts.size()) ? index : -1;
    }

    @Nullable
    public NamedTranscript getActiveNamedTranscript() {
        return (activeTranscriptIndex >= 0 && activeTranscriptIndex < transcripts.size())
                ? transcripts.get(activeTranscriptIndex) : null;
    }

    @Nullable
    public Transcript getTranscript() {
        NamedTranscript nt = getActiveNamedTranscript();
        return nt == null ? null : nt.transcript;
    }

    @NonNull
    public NamedTranscript addTranscript(@NonNull NamedTranscript named) {
        transcripts.add(named);
        activeTranscriptIndex = transcripts.size() - 1;
        return named;
    }

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
        Transcript t = getTranscript();
        return t != null && !t.isEmpty();
    }

    public boolean isCaptionsEnabled() { return captionsEnabled; }

    public void setCaptionsEnabled(boolean enabled) { this.captionsEnabled = enabled; }

    @NonNull
    public String getCaptionStyleId() { return captionStyleId; }

    public void setCaptionStyleId(@NonNull String id) { this.captionStyleId = id; }

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

    public List<long[]> getRemovedSpans() { return removedSpans; }
    public boolean hasRemovedSpans() { return !removedSpans.isEmpty(); }
    public void setRemovedSpans(@NonNull List<long[]> spans) { removedSpans.clear(); removedSpans.addAll(spans); }

    // ── C2.U baked-source bookkeeping ────────────────────────────────

    /** The ORIGINAL source uri while {@link #getSourceUri()} points at a bake, else null. */
    @Nullable
    public String getBakedFromUri() { return bakedFromUri; }

    /** The baked artifact's path — deleted when the bake is reverted. */
    @Nullable
    public String getBakedFromFile() { return bakedFromFile; }

    public void setBakedFrom(@Nullable String originalUri, @Nullable String bakedFilePath) {
        this.bakedFromUri = originalUri;
        this.bakedFromFile = bakedFilePath;
    }

    public boolean isBakedSource() { return bakedFromUri != null; }

    // ── Utility ──────────────────────────────────────────────────────

    @NonNull
    @Override
    public String toString() {
        return "AudioClip{id=" + id
                + ", duration=" + sourceDurationMs
                + "ms, trim=[" + inPointMs + "–" + outPointMs + "]"
                + ", offset=" + offsetMs
                + "ms, label=" + label + "}";
    }
}
