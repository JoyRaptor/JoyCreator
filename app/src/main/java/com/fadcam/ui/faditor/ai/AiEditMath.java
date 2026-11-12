package com.fadcam.ui.faditor.ai;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Small pure helpers for the AI tools, kept apart from {@code AIToolExecutor} so the JVM harness
 * can pin them (review of 2026-11-12, B1 and B3).
 */
public final class AiEditMath {
    private AiEditMath() { }

    /**
     * The clip ids after moving the one at {@code fromIndex} so it ENDS UP at {@code toIndex}
     * (0-based, a final position: "end" is {@code size - 1}). B1: the caller used to subtract one
     * from {@code toIndex} after removing the clip, which already accounts for the removal, so
     * every forward move landed one place short ("to the end" put it second to last).
     */
    @NonNull
    public static List<String> moveId(@NonNull List<String> ids, int fromIndex, int toIndex) {
        List<String> out = new ArrayList<>(ids);
        if (fromIndex < 0 || fromIndex >= out.size()) return out;
        String id = out.remove(fromIndex);
        int to = Math.max(0, Math.min(toIndex, out.size()));
        out.add(to, id);
        return out;
    }

    /**
     * A time on a clip's SOURCE clock (transcript words, in-point, out-point) as editor timeline
     * time from the start of the project (B3). {@code clipStartMs} is where the clip begins on the
     * timeline; the clip shows its source from {@code inPointMs} onward at {@code speed}.
     */
    public static long sourceToTimelineMs(long clipStartMs, long inPointMs, float speed,
                                          long sourceMs) {
        float s = speed > 0f ? speed : 1f;
        return clipStartMs + Math.round((sourceMs - inPointMs) / s);
    }
}
