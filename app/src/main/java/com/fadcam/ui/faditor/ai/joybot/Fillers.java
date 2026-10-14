package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which transcript words are hesitation sounds, and whether a span is already cut.
 *
 * <p>One definition shared by what Joybot COUNTS ("still has 12 filler words") and what the cut
 * tool CUTS, so the number in his sentence is exactly the number of cuts the button makes.</p>
 *
 * <p>Only pure hesitation sounds. "Like", "so" and "you know" are fillers sometimes and meaning
 * the rest of the time; cutting them automatically would cut real words.</p>
 */
public final class Fillers {

    private Fillers() {}

    private static final Set<String> SOUNDS = new HashSet<>(Arrays.asList(
            "um", "umm", "ummm", "uh", "uhh", "uhhh", "uhm", "er", "erm", "ah", "ahh",
            "hmm", "hm", "hmmm", "mm", "mmm"));

    /** True for "um", "Uh," "hmm…" and the like. */
    public static boolean isFiller(@NonNull String word) {
        String w = word.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
        return !w.isEmpty() && SOUNDS.contains(w);
    }

    /** True when [startMs, endMs] lies wholly inside one of the removed spans. */
    public static boolean isCut(long startMs, long endMs, @NonNull List<long[]> removed) {
        for (long[] r : removed) {
            if (r != null && r.length >= 2 && startMs >= r[0] && endMs <= r[1]) return true;
        }
        return false;
    }
}
