package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes ACCUMULATED duplicate transcript versions from clips.
 *
 * <p>Historically every transcription run appended a new {@link NamedTranscript}
 * to the clip, so re-running the same model ("High accuracy" three times)
 * silently stacked near-identical multi-thousand-word transcripts in
 * project.json — and, because undo snapshots clone the whole project JSON,
 * the bloat multiplied through memory and save/load latency.</p>
 *
 * <p><b>The rule (conservative — when in doubt, KEEP):</b> a version is removed
 * only if ALL of the following hold:</p>
 * <ul>
 *   <li>it is NOT the clip's active (live) version — the one captions,
 *       word-scrub and the transcript panel render;</li>
 *   <li>it carries NO user edits (no struck word, no forced line break —
 *       the only user-mutable state a transcript has; word text/timings are
 *       immutable after recognition);</li>
 *   <li>another version of the SAME engine + model label is retained on the
 *       same clip (the active one, an edited one, or — if the whole group is
 *       un-edited and inactive — the newest run of that group, which is kept).</li>
 * </ul>
 *
 * <p>So user-edited versions are NEVER deleted, the live version is NEVER
 * touched (same object instance is preserved, not copied), and versions from
 * distinct models/labels are never collapsed into each other.</p>
 *
 * <p>Idempotent, memory-only: callers decide about file backups/rewrites.</p>
 */
public final class TranscriptDedup {

    private TranscriptDedup() {}

    /** True if the user has edited this transcript (struck words / line breaks). */
    public static boolean isEdited(@NonNull Transcript t) {
        for (TranscriptWord w : t.words) {
            if (w.struck || w.forceLineBreakAfter) return true;
        }
        return false;
    }

    /** Count how many versions {@link #dedupProject} would remove, without mutating. */
    public static int countRemovable(@NonNull FaditorProject project) {
        return run(project, false);
    }

    /** Remove duplicate versions across all clips. Returns number removed. */
    public static int dedupProject(@NonNull FaditorProject project) {
        return run(project, true);
    }

    /** Dedup a single video clip's versions. Returns number removed. */
    public static int dedupClip(@NonNull Clip clip, boolean mutate) {
        int newActive = process(clip.getTranscripts(), clip.getActiveTranscriptIndex(), mutate);
        if (mutate && newActive != Integer.MIN_VALUE) {
            int removed = pendingRemoved;
            clip.setActiveTranscriptIndex(newActive);
            return removed;
        }
        return pendingRemoved;
    }

    /** Dedup a single audio clip's versions. Returns number removed. */
    public static int dedupAudioClip(@NonNull AudioClip clip, boolean mutate) {
        int newActive = process(clip.getTranscripts(), clip.getActiveTranscriptIndex(), mutate);
        if (mutate && newActive != Integer.MIN_VALUE) {
            int removed = pendingRemoved;
            clip.setActiveTranscriptIndex(newActive);
            return removed;
        }
        return pendingRemoved;
    }

    private static int run(@NonNull FaditorProject project, boolean mutate) {
        Timeline tl = project.getTimeline();
        if (tl == null) return 0;
        int removed = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            removed += dedupClip(tl.getClip(i), mutate);
        }
        for (AudioClip ac : tl.getAudioClips()) {
            removed += dedupAudioClip(ac, mutate);
        }
        return removed;
    }

    // Number removed by the last process() call (single-threaded use only:
    // load path / UI thread — matches all existing ProjectStorage call patterns).
    private static int pendingRemoved;

    /**
     * Core pass over one clip's version list.
     *
     * @return the new active index to set (index of the SAME live object after
     *         compaction), or {@link Integer#MIN_VALUE} if nothing was removed.
     *         Side channel {@link #pendingRemoved} carries the removal count.
     */
    private static int process(@NonNull List<NamedTranscript> list, int activeIndex,
                               boolean mutate) {
        pendingRemoved = 0;
        if (list.size() < 2) return Integer.MIN_VALUE;
        @Nullable NamedTranscript active =
                (activeIndex >= 0 && activeIndex < list.size()) ? list.get(activeIndex) : null;

        // Group by engine + label ("whisper\nHigh accuracy" etc.).
        // IMPORTED versions are user data, not engine re-runs: every paste import shares
        // engine="import" + label="Imported" but carries DIFFERENT content the user chose to
        // keep (JoyRaptor's lyrics + verses + addresses were three distinct imports). The dedup
        // rule below assumed same-engine+label implies same content re-run — true for vosk/
        // whisper runs, false for imports — and it ran on EVERY project load, eating one
        // imported version per open (the 3→2→1 report). Imports are never grouped.
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            NamedTranscript nt = list.get(i);
            if ("import".equals(nt.engine)) continue;
            String key = nt.engine + "\n" + nt.label;
            List<Integer> g = groups.get(key);
            if (g == null) { g = new ArrayList<>(); groups.put(key, g); }
            g.add(i);
        }

        Set<Integer> toRemove = new HashSet<>();
        for (List<Integer> g : groups.values()) {
            if (g.size() < 2) continue; // no duplicates of this model run
            boolean hasRetained = false;
            List<Integer> removable = new ArrayList<>();
            for (int idx : g) {
                NamedTranscript nt = list.get(idx);
                if (nt == active || isEdited(nt.transcript)) {
                    hasRetained = true;      // live or user-edited: always kept
                } else {
                    removable.add(idx);
                }
            }
            if (!hasRetained && !removable.isEmpty()) {
                // Whole group is inactive & un-edited: keep the newest run.
                removable.remove(removable.size() - 1);
            }
            toRemove.addAll(removable);
        }
        if (toRemove.isEmpty()) return Integer.MIN_VALUE;

        pendingRemoved = toRemove.size();
        if (!mutate) return Integer.MIN_VALUE;

        List<NamedTranscript> kept = new ArrayList<>(list.size() - toRemove.size());
        for (int i = 0; i < list.size(); i++) {
            if (!toRemove.contains(i)) kept.add(list.get(i));
        }
        list.clear();
        list.addAll(kept);
        // Re-point the active index at the SAME live object (identity, not copy).
        return active == null ? -1 : list.indexOf(active);
    }
}
