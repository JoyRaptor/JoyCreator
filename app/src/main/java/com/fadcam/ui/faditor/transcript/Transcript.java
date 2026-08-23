package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of {@link TranscriptWord}s for one source, plus helpers to
 * turn the un-struck words into the "keep ranges" the editor uses to cut the
 * timeline (the same mechanism as auto-cut-silence).
 *
 * <p>Common filler words are recognised so "Clean fillers" can strike them
 * in one tap.</p>
 */
public class Transcript {

    @NonNull
    public final List<TranscriptWord> words = new ArrayList<>();

    /** D4 — chapter titles: paragraph index → title. A named paragraph IS a chapter. */
    @NonNull
    public final java.util.Map<Integer,String> chapterTitles = new java.util.HashMap<>();

    /** D5 — speaker label per paragraph: paragraph index → speaker name. */
    @NonNull
    public final java.util.Map<Integer,String> paragraphSpeakers = new java.util.HashMap<>();

    private static final java.util.Set<String> FILLERS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "um", "uh", "umm", "uhh", "erm", "er", "ah", "hmm",
                    "like", "mmm", "uhm"));

    public boolean isEmpty() {
        return words.isEmpty();
    }

    /** Deep copy, preserving each word's edit state. */
    @NonNull
    public Transcript copy() {
        Transcript t = new Transcript();
        for (TranscriptWord w : words) {
            t.words.add(new TranscriptWord(w.text, w.startMs, w.endMs,
                    w.struck, w.forceLineBreakAfter));
        }
        t.chapterTitles.putAll(this.chapterTitles);
        t.paragraphSpeakers.putAll(this.paragraphSpeakers);
        return t;
    }

    // ── D4 chapters ──────────────────────────────────────────────────────

    @Nullable
    public String getChapterTitle(int paraIdx) { return chapterTitles.get(paraIdx); }

    public void setChapterTitle(int paraIdx, @Nullable String title) {
        if (title == null || title.trim().isEmpty()) chapterTitles.remove(paraIdx);
        else chapterTitles.put(paraIdx, title.trim());
    }

    public boolean hasChapters() { return !chapterTitles.isEmpty(); }

    /**
     * Export named paragraphs as YouTube chapter text: {@code 0:00 Title} per line,
     * first must be 0:00. A named paragraph is a chapter.
     * Time is startMs of the paragraph's first word, formatted m:ss or h:mm:ss.
     */
    @NonNull
    public static String toYoutubeChapters(@NonNull Transcript t, @NonNull TranscriptParagraphs pg) {
        if (t.words.isEmpty() || pg.paragraphCount() == 0) return "";
        java.util.List<Integer> named = new java.util.ArrayList<>();
        for (int i = 0; i < pg.paragraphCount(); i++) {
            String title = t.chapterTitles.get(i);
            if (title != null && !title.trim().isEmpty()) named.add(i);
        }
        if (named.isEmpty()) return "";
        // Sort by start time (paragraph order already sorted, but keep)
        named.sort((a,b) -> {
            int sa = pg.paragraphs.get(a)[0], sb = pg.paragraphs.get(b)[0];
            long ta = sa >=0 && sa < t.words.size() ? t.words.get(sa).startMs : 0;
            long tb = sb >=0 && sb < t.words.size() ? t.words.get(sb).startMs : 0;
            return Long.compare(ta, tb);
        });
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < named.size(); idx++) {
            int p = named.get(idx);
            int[] range = pg.paragraphs.get(p);
            long startMs = range[0] >=0 && range[0] < t.words.size() ? t.words.get(range[0]).startMs : 0;
            if (idx == 0) startMs = 0; // first must be 0:00 per YouTube spec
            sb.append(formatYoutubeTime(startMs)).append(' ').append(t.chapterTitles.get(p).trim());
            if (idx + 1 < named.size()) sb.append('\n');
        }
        return sb.toString();
    }

    private static String formatYoutubeTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    // ── D5 speaker helpers ───────────────────────────────────────────────

    @Nullable
    public String getParagraphSpeaker(int paraIdx) { return paragraphSpeakers.get(paraIdx); }

    public void setParagraphSpeaker(int paraIdx, @Nullable String speaker) {
        if (speaker == null || speaker.trim().isEmpty()) paragraphSpeakers.remove(paraIdx);
        else paragraphSpeakers.put(paraIdx, speaker.trim());
    }

    /**
     * A windowed VIEW of this transcript: a new {@link Transcript} holding the SAME
     * {@link TranscriptWord} references whose span falls within {@code [inMs, outMs]} (strict
     * containment, matching the timeline's word filter). Because the word objects are shared, strike
     * edits made through the view propagate to the source words. Used so a clip displays/edits only
     * the words inside its trim while the full source transcript stays intact (lengthening a trim
     * reveals more words). See {@code tasks/PLAN_transcript_windowing.md}.
     */
    @NonNull
    public Transcript windowed(long inMs, long outMs) {
        Transcript t = new Transcript();
        for (TranscriptWord w : words) {
            if (w.startMs >= inMs && w.endMs <= outMs) t.words.add(w);
        }
        return t;
    }

    /** Word at index, or null if out of range. */
    @Nullable
    public TranscriptWord get(int index) {
        return index >= 0 && index < words.size() ? words.get(index) : null;
    }

    /** Strike or restore a single word. */
    public void setStruck(int index, boolean struck) {
        TranscriptWord w = get(index);
        if (w != null) w.struck = struck;
    }

    /** Force (or remove) a caption line break after a single word. */
    public void setForceLineBreakAfter(int index, boolean force) {
        TranscriptWord w = get(index);
        if (w != null) w.forceLineBreakAfter = force;
    }

    /** Strike/restore an inclusive range of words. */
    public void setStruckRange(int from, int to, boolean struck) {
        int a = Math.max(0, Math.min(from, to));
        int b = Math.min(words.size() - 1, Math.max(from, to));
        for (int i = a; i <= b; i++) {
            words.get(i).struck = struck;
        }
    }

    /** Auto-strike common filler words and immediate single-word repeats. */
    public int strikeFillers() {
        int count = 0;
        String prevKept = null;
        for (TranscriptWord w : words) {
            String norm = w.text.toLowerCase().replaceAll("[^a-z]", "");
            boolean filler = FILLERS.contains(norm);
            boolean repeat = prevKept != null && prevKept.equals(norm) && norm.length() > 1;
            if (filler || repeat) {
                if (!w.struck) count++;
                w.struck = true;
            } else if (!w.struck) {
                prevKept = norm;
            }
        }
        return count;
    }

    /**
     * Build the keep-ranges (source ms) covering only the un-struck words.
     * Adjacent kept words are merged into a single range; a small bridge is
     * allowed so we don't shred continuous speech at every word gap.
     *
     * @param bridgeMs gaps up to this between kept words stay in one range
     * @return ordered list of [startMs,endMs] to keep
     */
    @NonNull
    public List<long[]> buildKeepRanges(long bridgeMs) {
        List<long[]> ranges = new ArrayList<>();
        long curStart = -1, curEnd = -1;
        for (TranscriptWord w : words) {
            if (w.struck) continue;
            if (curStart < 0) {
                curStart = w.startMs;
                curEnd = w.endMs;
            } else if (w.startMs - curEnd <= bridgeMs) {
                curEnd = Math.max(curEnd, w.endMs);
            } else {
                ranges.add(new long[]{curStart, curEnd});
                curStart = w.startMs;
                curEnd = w.endMs;
            }
        }
        if (curStart >= 0) {
            ranges.add(new long[]{curStart, curEnd});
        }
        return ranges;
    }

    /** True if any word is struck (i.e. the transcript implies a cut). */
    public boolean hasStrikes() {
        for (TranscriptWord w : words) {
            if (w.struck) return true;
        }
        return false;
    }

    /** Index of the word spanning the given source time, or -1. */
    public int indexAtTime(long sourceMs) {
        for (int i = 0; i < words.size(); i++) {
            TranscriptWord w = words.get(i);
            if (sourceMs >= w.startMs && sourceMs <= w.endMs) return i;
        }
        return -1;
    }

    /**
     * Index of the most recent word that has started by {@code sourceMs}. Used
     * for the playback highlight so a word stays lit through the gap after it
     * until the next word begins (no flicker between words).
     */
    public int indexAtOrBeforeTime(long sourceMs) {
        // Return the highest-indexed word whose start is at/before sourceMs.
        // Do NOT break early: Whisper word timestamps are sometimes slightly out
        // of order (overlapping/non-monotonic starts), and an early break would
        // freeze the active word at the first out-of-order entry — making captions
        // stop advancing mid-transcript.
        int result = -1;
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).startMs <= sourceMs) result = i;
        }
        return result;
    }
}
