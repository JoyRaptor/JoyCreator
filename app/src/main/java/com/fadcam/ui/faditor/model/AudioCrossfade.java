package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * A cross-fade between two ADJACENT AUDIO LANES — the "pill" of SPEC_AUDIO_UX_V1 §5.
 *
 * <p>JoyRaptor's design, in his words: <em>"a handsome looking little unit that can shade between
 * one and the other … a little long pill between the lanes that might cover a few pixels over
 * each lane … little direction arrows diagonally pointing that the sound is going from this
 * thing to that … I could grab the middle of it to slide it forward and backward on the
 * timeline, and I could grab its edges to change its starting and endpoints."</em></p>
 *
 * <p><b>Why this is a first-class object and not a pair of envelopes.</b> The fade it describes
 * is a RELATIONSHIP between two lanes, and §1's placement law puts a relationship on the
 * timeline between the things it relates, not inside either one. Storing it as "some keyframes
 * on A and some more on B" would mean nothing on screen to grab, nothing to slide, and no way
 * to tell a cross-fade apart from two coincidental fades that happen to overlap.</p>
 *
 * <p><b>Direction is which lane WINS.</b> {@link #toLaneAbove} true means the sound is handing
 * off to the lane above this one: the lane below is the loser and darkens after the crossover,
 * the lane above is the winner and darkens before it. §5.1 — the picture has to be true of what
 * you will hear, so "everything after is darkened" is deliberately NOT what this draws.</p>
 *
 * <p><b>Times are ABSOLUTE timeline ms</b>, not clip-local. A cross-fade outlives any particular
 * clip under it: trimming or replacing either side must not silently move the fade, and the user
 * positions it against the timeline they can see. This is the same choice a PiP's
 * {@code overlayTransform} keyframes make and for the same reason.</p>
 *
 * <p><b>Not yet audible.</b> The export path cannot render this until row {@code A8} lands —
 * {@code buildAudioSequence} currently flattens every audio clip into ONE sequential sequence,
 * so two overlapping lane clips cannot both sound at all. This class and its UI are the
 * authoring half; the row that makes it audible is tracked separately, and per §0 rule 6 the
 * UI must say so rather than implying a silent success.</p>
 */
public class AudioCrossfade {

    /** Shortest fade that is a fade rather than a click. Mirrors the pill's minimum grab width. */
    public static final long MIN_DURATION_MS = 50L;

    @NonNull private final String id;

    /** Lane id the pill sits UNDER — the seam is between this lane and the one above it. */
    @NonNull private String lowerLaneId;

    /** Start on the project timeline (absolute ms). */
    private long startMs;

    /** End on the project timeline (absolute ms). Always {@code > startMs}. */
    private long endMs;

    /**
     * true  = sound hands off UP   (lower lane fades out, upper fades in)
     * false = sound hands off DOWN (upper lane fades out, lower fades in)
     */
    private boolean toLaneAbove = true;

    /**
     * The user-assignable colour (0xRRGGBB, no alpha). §5: the pill and BOTH shaded tape regions
     * share it, so on a busy timeline you can see at a glance which fade owns which shading.
     */
    private int colorRgb = 0x35F6BF;

    public AudioCrossfade(@NonNull String lowerLaneId, long startMs, long endMs) {
        this(UUID.randomUUID().toString(), lowerLaneId, startMs, endMs);
    }

    public AudioCrossfade(@NonNull String id, @NonNull String lowerLaneId,
                          long startMs, long endMs) {
        this.id = id;
        this.lowerLaneId = lowerLaneId;
        this.startMs = Math.max(0, startMs);
        this.endMs = Math.max(this.startMs + MIN_DURATION_MS, endMs);
    }

    /** Copy constructor — used by undo snapshots, which must not alias the live object. */
    public AudioCrossfade(@NonNull AudioCrossfade other) {
        this.id = other.id;
        this.lowerLaneId = other.lowerLaneId;
        this.startMs = other.startMs;
        this.endMs = other.endMs;
        this.toLaneAbove = other.toLaneAbove;
        this.colorRgb = other.colorRgb;
    }

    @NonNull public String getId() { return id; }

    @NonNull public String getLowerLaneId() { return lowerLaneId; }
    public void setLowerLaneId(@NonNull String v) { this.lowerLaneId = v; }

    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public long getDurationMs() { return endMs - startMs; }

    public boolean isToLaneAbove() { return toLaneAbove; }
    public void setToLaneAbove(boolean v) { this.toLaneAbove = v; }

    public int getColorRgb() { return colorRgb; }
    public void setColorRgb(int rgb) { this.colorRgb = rgb & 0xF4F4F5; }

    /** Flip which lane the sound hands off to. One tap on the pill's arrow (§5.4). */
    public void flipDirection() { this.toLaneAbove = !this.toLaneAbove; }

    /**
     * Slide the whole fade without changing its length — the middle-drag of §5.
     * Clamped at zero so a drag off the left edge shortens nothing.
     */
    public void moveTo(long newStartMs) {
        long dur = getDurationMs();
        this.startMs = Math.max(0, newStartMs);
        this.endMs = this.startMs + dur;
    }

    /**
     * Drag one edge — the resize of §5. The moving edge can never cross the fixed one:
     * it stops {@link #MIN_DURATION_MS} short, which is also why a resize can never
     * invert the fade or collapse it into a click.
     */
    public void setEdge(boolean leftEdge, long ms) {
        if (leftEdge) {
            this.startMs = Math.max(0, Math.min(ms, endMs - MIN_DURATION_MS));
        } else {
            this.endMs = Math.max(startMs + MIN_DURATION_MS, ms);
        }
    }

    /** True when {@code timelineMs} falls inside the fade. */
    public boolean contains(long timelineMs) {
        return timelineMs >= startMs && timelineMs <= endMs;
    }

    /**
     * Progress 0..1 across the fade at an absolute timeline position: 0 before it, 1 after it.
     * The gain the WINNING lane should reach; the loser is {@code 1 - this}. Linear, matching
     * {@link VolumeEnvelope}'s interpolation so a cross-fade and a hand-drawn envelope cannot
     * curve differently.
     */
    public float progressAt(long timelineMs) {
        if (timelineMs <= startMs) return 0f;
        if (timelineMs >= endMs) return 1f;
        long span = getDurationMs();
        return span <= 0 ? 1f : (timelineMs - startMs) / (float) span;
    }
}
