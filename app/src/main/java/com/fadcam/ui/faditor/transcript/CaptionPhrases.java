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

    /**
     * SLIDE mode (SPEC_20260831_CAPTION_SLIDES): true when built with {@link #ofSlides} —
     * one TIMING ENTRY is one phrase, for its full span. For imports where a whole paragraph
     * shares one timestamp (a scripture shown while the song references it), this is the
     * difference between one readable wrapped slide and a 6-paragraph ticker.
     */
    public final boolean slideMode;

    private final Transcript transcript;

    private CaptionPhrases(Transcript t, @NonNull int[] wordPhrase, @NonNull List<int[]> phrases) {
        this(t, wordPhrase, phrases, false);
    }

    private CaptionPhrases(Transcript t, @NonNull int[] wordPhrase, @NonNull List<int[]> phrases,
                           boolean slideMode) {
        this.transcript = t;
        this.wordPhrase = wordPhrase;
        this.phrases = phrases;
        this.slideMode = slideMode;
    }

    /**
     * Group a transcript at the caption's own word limit.
     *
     * <p>The limit used to be the private constant {@link #MAX_WORDS}, which is a fine DEFAULT
     * for speech and a poor ceiling for anything deliberate: a scripture or a paragraph cannot
     * be said in six words, and chopping it into six-word pieces is not a caption, it is a
     * ticker. The value now travels with the style, so it is saved, exported and shared with the
     * look it belongs to.</p>
     */
    @NonNull
    public static CaptionPhrases of(Transcript t, int maxWords) {
        return build(t, Math.max(1, maxWords));
    }

    /** Group a transcript. An empty or null transcript yields no phrases rather than throwing. */
    @NonNull
    public static CaptionPhrases of(Transcript t) {
        return build(t, MAX_WORDS);
    }

    /**
     * SLIDE grouping: every timing entry becomes its own phrase spanning the entry's whole
     * time. Pair with {@link #layoutWords}, which splits an entry holding a whole paragraph
     * into per-word layout units so the slide wraps inside the box.
     */
    @NonNull
    public static CaptionPhrases ofSlides(Transcript t) {
        List<int[]> out = new ArrayList<>();
        if (t == null || t.words.isEmpty()) {
            return new CaptionPhrases(t, new int[0], out, true);
        }
        int n = t.words.size();
        int[] wp = new int[n];
        for (int i = 0; i < n; i++) {
            wp[i] = i;
            out.add(new int[]{i, i});
        }
        return new CaptionPhrases(t, wp, out, true);
    }

    /**
     * The LAYOUT words of a phrase — the strings the renderers measure, wrap and draw.
     *
     * <p>Normal mode: each entry is already a word, so this is the entry texts unchanged.
     * Slide mode: an entry may hold a WHOLE PARAGRAPH (transcript imports store one entry
     * per timestamped line), and a paragraph must wrap by word inside the box, so its text
     * is split on whitespace here — at layout time only; the transcript itself is untouched.
     * Renderers calling this for both modes get wrapping for free without knowing the mode.</p>
     */
    @NonNull
    public List<String> layoutWords(int phraseIdx) {
        List<String> out = new ArrayList<>();
        List<Integer> vis = visibleWords(phraseIdx);
        if (transcript == null) return out;
        for (int wi : vis) {
            String text = transcript.words.get(wi).text;
            if (slideMode) {
                for (String piece : text.trim().split("\\s+")) {
                    if (!piece.isEmpty()) out.add(piece);
                }
                if (text.trim().isEmpty()) out.add(text);
            } else {
                out.add(text);
            }
        }
        return out;
    }

    @NonNull
    private static CaptionPhrases build(Transcript t, int maxWords) {
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
                brk = gap > GAP_BREAK_MS || (i - start) >= maxWords
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
     * True when at least one phrase is long enough that the timing control can actually change
     * something on it. False means there is nothing to time, so the control should not be offered
     * at all.
     *
     * <p><b>The test is "does full travel buy a non-zero zone", not "is the span non-zero".</b>
     * Those differ, and the difference is reachable: {@link #spanMs} floors every phrase at 1ms so
     * a degenerate word still DRAWS (several ASR backends emit {@code startMs == endMs}), and on a
     * 1ms span {@link CaptionAnimator#zoneForSpan} returns 0 at every setting, because the floor
     * cap that stops two zones overlapping takes {@code 1/2 = 0}. A "non-zero span" test therefore
     * offers a control that provably cannot do anything. Asking the evaluator itself is both
     * correct and drift-proof: if the cap ever changes, this moves with it.</p>
     *
     * <p>This replaces {@code maxUsefulZoneMs()}, which returned half the longest phrase in source
     * ms. That number existed for exactly one reason: to scale a tape caret's travel, because the
     * zones used to be absolute durations stored on the CLIP but spent against each PHRASE, and
     * phrases are short while clips are long — a caret mapped linearly onto a 60s tape put its
     * whole useful range in the first ~3% of its travel and felt broken for the other 94%.</p>
     *
     * <p>Zones are now a FRACTION of each line, so full travel is 0.5 at every phrase length and
     * there is no scale factor left to compute. What survived was a {@code > 0} existence check
     * wearing the name of a measurement, which is how a helper starts lying about what it is for.
     * Hence a predicate.</p>
     */
    public boolean hasAnimatableSpan() {
        for (int i = 0; i < phrases.size(); i++) {
            long[] s = spanMs(i);
            if (s == null) continue;
            if (CaptionAnimator.zoneForSpan(CaptionAnimator.MAX_ZONE_PCT, s[1] - s[0]) > 0) {
                return true;
            }
        }
        return false;
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
