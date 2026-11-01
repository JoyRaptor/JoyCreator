package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Which main-track clip sits at a timeline time, counting each clip the way the timeline draws it:
 * a looped or extended clip takes its whole visual length, not its trimmed pass. Past the end it
 * answers the last clip, as the editor's seek does.
 */
public final class SpineLookup {
    public final int index;
    @NonNull public final Clip clip;
    /** Where the clip starts on the timeline. */
    public final long startMs;
    /** The time inside the clip, from its visual start: loop repeats included, in timeline ms. */
    public final long visualMs;

    private SpineLookup(int index, @NonNull Clip clip, long startMs, long visualMs) {
        this.index = index;
        this.clip = clip;
        this.startMs = startMs;
        this.visualMs = visualMs;
    }

    /** The clip's length on the timeline. */
    public static long spanMs(@NonNull Clip clip) {
        return clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
    }

    @Nullable
    public static SpineLookup at(@NonNull Timeline timeline, long absoluteMs) {
        long abs = Math.max(0L, absoluteMs);
        long start = 0L;
        int count = timeline.getClipCount();
        for (int i = 0; i < count; i++) {
            Clip clip = timeline.getClip(i);
            if (clip == null) continue;
            long span = spanMs(clip);
            if (abs < start + span || i == count - 1) {
                return new SpineLookup(i, clip, start, Math.max(0L, Math.min(abs - start, span - 1)));
            }
            start += span;
        }
        return null;
    }

    /** The position in the source, from the In point: where a loop repeat really is in the media. */
    public long sourceOffsetMs() {
        return Math.max(0L, clip.mapToSourceMs(visualMs) - clip.getInPointMs());
    }
}
