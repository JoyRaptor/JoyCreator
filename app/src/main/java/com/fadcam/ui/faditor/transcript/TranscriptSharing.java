package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses per-clip transcript FORKS onto one shared instance per transcript id.
 *
 * <p>A transcript belongs to the SOURCE — each clip is a window over it (see
 * {@code tasks/PLAN_transcript_windowing.md}). The code has never quite worked that way:
 * a clip holds its own {@link NamedTranscript} objects, and copying a clip deep-copied
 * them, so every split forked the text. An edit made from one clip was invisible from its
 * own sibling.</p>
 *
 * <p><b>Two different fork shapes exist in real projects</b>, and they need opposite
 * treatment. Measured on the reporter's device:</p>
 * <ul>
 *   <li><b>Legacy partitions.</b> Splitting used to BAKE the transcript — the words on the
 *       far side of the cut were deleted. One project's four forks held 1291/524/272/141
 *       words whose start times exactly matched each clip's own trim, with no overlap.
 *       Here the forks must be UNIONed: that reconstructs 2228 words of a transcript that
 *       looks truncated from every individual clip.</li>
 *   <li><b>Modern full copies.</b> Splitting no longer bakes, so each fork spans the whole
 *       source and they differ only where the user edited one — a word re-timed in the
 *       scrub drawer, a junk word deleted. Unioning THESE would duplicate the edited word
 *       (the same token at two different start times). Here one copy must simply win.</li>
 * </ul>
 *
 * <p><b>The rule that handles both</b>: the fork with the most words is canonical; a word
 * from another fork is adopted only when its start time falls OUTSIDE the span the
 * canonical already covers. Disjoint partitions therefore union completely, while full
 * copies adopt nothing and the most complete one wins untouched. Note that word start
 * times are NOT unique within a transcript (21 and 38 same-start pairs were found inside
 * single forks in the wild), so they cannot be used as a merge key — only as a coverage
 * span, which is what this does.</p>
 */
public final class TranscriptSharing {

    private TranscriptSharing() { }

    /** Outcome of a sharing pass, for logging and for deciding whether to persist. */
    public static final class Result {
        /** Fork instances that were replaced by a shared canonical one. */
        public int collapsed;
        /** Words recovered from legacy partitions that no single clip could see. */
        public int recovered;
        /** Distinct transcript ids after sharing. */
        public int shared;

        public boolean changedAnything() { return collapsed > 0 || recovered > 0; }

        @NonNull
        @Override
        public String toString() {
            return "collapsed=" + collapsed + " recovered=" + recovered + " shared=" + shared;
        }
    }

    /**
     * Share every clip's transcripts onto one instance per id, merging legacy partitions.
     * Mutates {@code project} in place; safe to run repeatedly (a second pass finds
     * nothing to do).
     */
    @NonNull
    public static Result shareProject(@NonNull FaditorProject project) {
        Result r = new Result();
        if (project.getTimeline() == null) return r;
        List<Clip> clips = project.getTimeline().getClips();
        if (clips == null || clips.isEmpty()) return r;

        // 1. Gather every fork of every id, in clip order.
        Map<String, List<NamedTranscript>> byId = new LinkedHashMap<>();
        for (Clip c : clips) {
            if (c == null) continue;
            for (NamedTranscript nt : c.getTranscripts()) {
                if (nt == null) continue;
                List<NamedTranscript> forks = byId.get(nt.id);
                if (forks == null) { forks = new ArrayList<>(); byId.put(nt.id, forks); }
                forks.add(nt);
            }
        }

        // 2. Pick a canonical per id and absorb only out-of-span words from the others.
        Map<String, NamedTranscript> canonicalById = new LinkedHashMap<>();
        for (Map.Entry<String, List<NamedTranscript>> e : byId.entrySet()) {
            List<NamedTranscript> forks = e.getValue();
            NamedTranscript canonical = forks.get(0);
            for (NamedTranscript f : forks) {
                if (f.transcript.words.size() > canonical.transcript.words.size()) canonical = f;
            }
            canonicalById.put(e.getKey(), canonical);
            if (forks.size() < 2) continue;

            List<TranscriptWord> canonWords = canonical.transcript.words;
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
            for (TranscriptWord w : canonWords) {
                if (w.startMs < lo) lo = w.startMs;
                if (w.startMs > hi) hi = w.startMs;
            }
            int recoveredHere = 0;
            for (NamedTranscript f : forks) {
                if (f == canonical) continue;
                for (TranscriptWord w : f.transcript.words) {
                    if (w.startMs < lo || w.startMs > hi) {
                        canonWords.add(w);
                        recoveredHere++;
                    }
                }
            }
            if (recoveredHere > 0) {
                // Stable sort: equal start times keep their relative order, which matters
                // because same-start words genuinely occur in this data.
                Collections.sort(canonWords, (a, b) -> Long.compare(a.startMs, b.startMs));
                r.recovered += recoveredHere;
            }
        }

        // 3. Re-point every clip at the canonical instance.
        for (Clip c : clips) {
            if (c == null) continue;
            List<NamedTranscript> list = c.getTranscripts();
            for (int i = 0; i < list.size(); i++) {
                NamedTranscript nt = list.get(i);
                if (nt == null) continue;
                NamedTranscript canonical = canonicalById.get(nt.id);
                if (canonical != null && canonical != nt) {
                    list.set(i, canonical);
                    r.collapsed++;
                }
            }
        }
        r.shared = canonicalById.size();
        return r;
    }

    /** Fork instances that {@link #shareProject} would collapse — cheap pre-check. */
    public static int countForks(@NonNull FaditorProject project) {
        if (project.getTimeline() == null) return 0;
        List<Clip> clips = project.getTimeline().getClips();
        if (clips == null) return 0;
        Map<String, Integer> seen = new LinkedHashMap<>();
        int extra = 0;
        for (Clip c : clips) {
            if (c == null) continue;
            for (NamedTranscript nt : c.getTranscripts()) {
                if (nt == null) continue;
                Integer n = seen.get(nt.id);
                if (n == null) seen.put(nt.id, 1); else { seen.put(nt.id, n + 1); extra++; }
            }
        }
        return extra;
    }
}
