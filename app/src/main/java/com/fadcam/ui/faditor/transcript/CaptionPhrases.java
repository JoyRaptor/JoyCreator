package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * How a transcript is grouped into the short phrases a caption shows at once, and where each
 * phrase sits in source time.
 *
 * <h3>Why this is its own class</h3>
 * The grouping rule — break on a gap over 550ms, every ~6 words, or a forced line break — was
 * implemented IDENTICALLY and INDEPENDENTLY in {@code CaptionOverlayView} and
 * {@code CaptionExportRenderer}, the two renderers whose disagreement was LEDGER §3g. The copies
 * happened to match, which is exactly what the §3g easing copies did right up until they didn't.
 *
 * <p>It stopped being a tolerable duplication when text animation started deriving TIMING from
 * the phrase: the phrase span is the animating object's tape, so preview and export disagreeing
 * about where a phrase begins would put the two animations on different clocks all over again —
 * the same defect, one level up. One grouping, called by both.</p>
 *
 * <p>Pure model code (no {@code android.*}), so the JVM harness can exercise it.</p>
 */
public final class CaptionPhrases {

    /** A gap longer than this starts a new phrase. */
    private static final long GAP_BREAK_MS = 550L;
    /** Phrases are capped at this many words so they stay readable on screen. */
    private static final int MAX_WORDS = 6;

    /** For each word index, the index of the phrase it belongs to. */
    @NonNull public final int[] wordPhrase;
    /** Each phrase as {@code {firstWordIdx, lastWordIdxInclusive}}. */
    @NonNull public final List<int[]> phrases;

    private final Transcript transcript;

    private CaptionPhrases(Transcript t, @NonNull int[] wordPhrase, @NonNull List<int[]> phrases) {
        this.transcript = t;
        this.wordPhrase = wordPhrase;
        this.phrases = phrases;
    }

    /** Group a transcript. An empty or null transcript yields no phrases rather than throwing. */
    @NonNull
    public static CaptionPhrases of(Transcript t) {
        List<int[]> out = new ArrayList<>();
        if (t == null || t.words.isEmpty()) {
            return new CaptionPhrases(t, new int[0], out);
        }
        int n = t.words.size();
        int[] wp = new int[n];
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean brk = i == n;
            if (!brk) {
                long gap = t.words.get(i).startMs - t.words.get(i - 1).endMs;
                brk = gap > GAP_BREAK_MS || (i - start) >= MAX_WORDS
                        || t.words.get(i - 1).forceLineBreakAfter;
            }
            if (brk) {
                int phraseIdx = out.size();
                out.add(new int[]{start, i - 1});
                for (int j = start; j < i; j++) wp[j] = phraseIdx;
                start = i;
            }
        }
        return new CaptionPhrases(t, wp, out);
    }

    /** The phrase containing {@code wordIdx}, or -1 if there is none. */
    public int phraseOf(int wordIdx) {
        if (wordIdx < 0 || wordIdx >= wordPhrase.length) return -1;
        return wordPhrase[wordIdx];
    }

    /**
     * The word indices of a phrase that are actually DRAWN — struck (removed) words are not,
     * so they must not occupy an animation slot either or the sweep would stall on a gap the
     * viewer cannot see.
     */
    @NonNull
    public List<Integer> visibleWords(int phraseIdx) {
        List<Integer> out = new ArrayList<>();
        if (transcript == null || phraseIdx < 0 || phraseIdx >= phrases.size()) return out;
        int[] p = phrases.get(phraseIdx);
        for (int i = p[0]; i <= p[1] && i < transcript.words.size(); i++) {
            if (!transcript.words.get(i).struck) out.add(i);
        }
        return out;
    }

    /**
     * The largest in/out zone that still CHANGES anything, in source ms — half the longest
     * phrase, because {@link CaptionAnimator#zoneForSpan} caps every phrase's zone at half its
     * own span.
     *
     * <h3>Why the tape handles need this</h3>
     * The zones are stored as absolute durations on the CLIP but spent against each PHRASE, and
     * phrases are short (six words, capped) while clips are long. So a handle that mapped its
     * travel linearly onto the clip's tape would put its entire useful range in the first few
     * percent: on a 60s clip every position from ~3% to the centre stores a zone that saturates
     * every phrase, and the handle would feel broken for 94% of its travel.
     *
     * <p>Mapping full inward travel to THIS value instead keeps both endpoints of the user's
     * stated model exactly true — handle at the end is zero and therefore off; handle at the
     * centre makes every phrase finish arriving exactly as it starts leaving — and makes every
     * position in between distinct. The travel is compressed relative to the tape; the meaning
     * is not.</p>
     *
     * @return 0 when nothing is drawn, in which case there is no animation to time and the
     *         handles should not be offered at all
     */
    public long maxUsefulZoneMs() {
        long longest = 0L;
        for (int i = 0; i < phrases.size(); i++) {
            long[] s = spanMs(i);
            if (s != null) longest = Math.max(longest, s[1] - s[0]);
        }
        return longest / 2;
    }

    /**
     * The phrase's span in SOURCE ms — from the first visible word's start to the last visible
     * word's end. This is the animating object's tape: the in/out zones are measured against it,
     * so a phrase of continuous speech animates in and out on its own, rather than only the
     * first phrase of a clip animating while the rest merely appear.
     *
     * @return {@code {startMs, endMs}}, or null when the phrase draws nothing
     */
    public long[] spanMs(int phraseIdx) {
        List<Integer> vis = visibleWords(phraseIdx);
        if (vis.isEmpty()) return null;
        long s = transcript.words.get(vis.get(0)).startMs;
        long e = transcript.words.get(vis.get(vis.size() - 1)).endMs;
        return new long[]{s, Math.max(s + 1L, e)};
    }
}
