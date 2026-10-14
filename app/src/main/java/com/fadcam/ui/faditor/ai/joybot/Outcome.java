package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "What changed", in plain words, measured by comparing the project before and after.
 *
 * <p>Tool results are written for the model ("Cut 12 filler words from clip 4f1c…"). Joybot's own
 * result card instead compares two {@link ProjectFacts} snapshots, so what he reports is what
 * actually happened to the project — if a tool claimed success and changed nothing, he says
 * nothing changed.</p>
 */
public final class Outcome {

    private Outcome() {}

    /** The sentences, or null when nothing measurable changed. */
    @Nullable
    public static String describe(@NonNull ProjectFacts before, @NonNull ProjectFacts after) {
        List<String> out = new ArrayList<>();

        Map<String, ProjectFacts.ClipFacts> was = new HashMap<>();
        for (ProjectFacts.ClipFacts c : before.spine) was.put(c.id, c);
        for (ProjectFacts.ClipFacts c : before.audio) was.put(c.id, c);

        int newlyTranscribed = 0, newWords = 0, captionsOn = 0, fillersCut = 0;
        String oneName = null;
        List<ProjectFacts.ClipFacts> now = new ArrayList<>(after.spine);
        now.addAll(after.audio);
        for (ProjectFacts.ClipFacts c : now) {
            ProjectFacts.ClipFacts b = was.get(c.id);
            if (b == null) continue;
            if (c.hasTranscript && !b.hasTranscript) {
                newlyTranscribed++;
                newWords += c.words + c.fillersLeft;
                oneName = c.name;
            }
            if (c.captionsOn && !b.captionsOn) captionsOn++;
            if (b.hasTranscript && c.hasTranscript && b.fillersLeft > c.fillersLeft) {
                fillersCut += b.fillersLeft - c.fillersLeft;
            }
        }
        if (newlyTranscribed == 1) {
            out.add("Transcribed " + IdeaEngine.q(oneName) + ": " + count(newWords, "word") + ".");
        } else if (newlyTranscribed > 1) {
            out.add("Transcribed " + newlyTranscribed + " clips: " + count(newWords, "word") + ".");
        }
        if (captionsOn == 1) out.add("Captions are on.");
        else if (captionsOn > 1) out.add("Captions are on for " + captionsOn + " clips.");
        if (fillersCut > 0) out.add("Cut " + count(fillersCut, "filler word") + ".");

        int transitions = after.transitions - before.transitions;
        if (transitions > 0) out.add("Added " + count(transitions, "transition") + ".");
        int viz = after.visualizers - before.visualizers;
        if (viz == 1) out.add("Added a visualizer.");
        else if (viz > 1) out.add("Added " + viz + " visualizers.");
        int layers = (after.textOverlays + after.overlayClips) - (before.textOverlays + before.overlayClips);
        if (layers > 0) out.add("Added " + count(layers, "layer") + ".");
        int slides = slideCount(after) - slideCount(before);
        int clips = after.spine.size() - before.spine.size() - Math.max(0, slides);
        if (slides > 0) out.add("Added " + (slides == 1 ? "a slide" : slides + " slides") + ".");
        if (clips > 0) out.add("Added " + count(clips, "clip") + ".");
        if (after.titleAtStart && !before.titleAtStart) out.add("Your video now opens on a title.");

        long shorter = before.totalMs - after.totalMs;
        if (shorter >= 500) {
            out.add("The video is " + IdeaEngine.seconds(shorter) + " shorter ("
                    + clock(before.totalMs) + " → " + clock(after.totalMs) + ").");
        } else if (-shorter >= 500) {
            out.add("The video is " + IdeaEngine.seconds(-shorter) + " longer ("
                    + clock(before.totalMs) + " → " + clock(after.totalMs) + ").");
        }
        return out.isEmpty() ? null : String.join(" ", out);
    }

    private static int slideCount(@NonNull ProjectFacts f) {
        int n = 0;
        for (ProjectFacts.ClipFacts c : f.spine) if (c.slide) n++;
        return n;
    }

    /** "1 word", "312 words". */
    @NonNull
    static String count(int n, @NonNull String noun) {
        return String.format(Locale.US, "%,d", n) + " " + noun + (n == 1 ? "" : "s");
    }

    /** "3:07", or "1:02:09". */
    @NonNull
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
