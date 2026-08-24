import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.transcript.TranscriptParagraphs;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

/**
 * G16 / §8 — transcript paragraph grouping, off device.
 *
 * <p>Written because this shipped with ZERO coverage and sits under a gutter the user drags
 * paragraphs by. The grouping decides what a drag MOVES, so a wrong boundary does not look like
 * a formatting glitch — it silently reorders the wrong span of someone's recording.</p>
 *
 * <p>The invariant that matters more than any single boundary rule: the paragraphs must PARTITION
 * the words. Every word belongs to exactly one paragraph, the ranges tile the whole transcript
 * with no gap and no overlap, and {@code paragraphOf} agrees with the ranges. A gap means a word
 * that no drag can ever pick up; an overlap means one word moving with two paragraphs at once.</p>
 */
public class ParagraphTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    /** Words at a steady cadence: each 300ms long, `gapMs` apart. */
    static Transcript speech(int count, long gapMs) {
        Transcript t = new Transcript();
        long cursor = 0;
        for (int i = 0; i < count; i++) {
            t.words.add(new TranscriptWord("w" + i, cursor, cursor + 300));
            cursor += 300 + gapMs;
        }
        return t;
    }

    /** Widen the gap BEFORE word `idx` to `gapMs`, shifting everything after it. */
    static void widenGapBefore(Transcript t, int idx, long gapMs) {
        long prevEnd = t.words.get(idx - 1).endMs;
        long shift = (prevEnd + gapMs) - t.words.get(idx).startMs;
        for (int i = idx; i < t.words.size(); i++) {
            TranscriptWord w = t.words.get(i);
            t.words.set(i, new TranscriptWord(w.text, w.startMs + shift, w.endMs + shift));
        }
    }

    /** Every word in exactly one paragraph; ranges tile [0, n-1] contiguously. */
    static boolean partitions(TranscriptParagraphs p, int n) {
        if (n == 0) return p.paragraphCount() == 0;
        int expectNext = 0;
        for (int i = 0; i < p.paragraphCount(); i++) {
            int[] r = p.paragraphs.get(i);
            if (r[0] != expectNext) return false;
            if (r[1] < r[0]) return false;
            for (int w = r[0]; w <= r[1]; w++) {
                if (p.paragraphOf(w) != i) return false;
            }
            expectNext = r[1] + 1;
        }
        return expectNext == n;
    }

    public static void main(String[] args) {
        final long GAP = TranscriptParagraphs.GAP_BREAK_MS;
        final int MINW = TranscriptParagraphs.MIN_WORDS;
        System.out.println("      GAP_BREAK_MS=" + GAP + "  MIN_WORDS=" + MINW);

        // ── 1. Degenerate inputs ────────────────────────────────────────────────────
        TranscriptParagraphs empty = TranscriptParagraphs.of(new Transcript());
        check(empty.paragraphCount() == 0, "an empty transcript has no paragraphs");
        check(empty.paragraphOf(0) == -1, "paragraphOf out of range returns -1, not a crash");
        check(TranscriptParagraphs.of(null).paragraphCount() == 0, "a null transcript is safe");

        TranscriptParagraphs one = TranscriptParagraphs.of(speech(1, 100));
        check(one.paragraphCount() == 1, "a single word is one paragraph");
        check(partitions(one, 1), "and it partitions");

        // ── 2. Continuous speech never breaks ──────────────────────────────────────
        Transcript flow = speech(60, 120);
        TranscriptParagraphs pf = TranscriptParagraphs.of(flow);
        check(pf.paragraphCount() == 1, "steady speech is ONE paragraph (" + pf.paragraphCount() + ")");
        check(partitions(pf, 60), "steady speech partitions");

        // ── 3. A long pause AFTER enough words breaks ──────────────────────────────
        Transcript br = speech(60, 120);
        widenGapBefore(br, 30, GAP + 500);
        TranscriptParagraphs pb = TranscriptParagraphs.of(br);
        System.out.println("      long pause at word 30 -> " + pb.paragraphCount() + " paragraphs");
        check(pb.paragraphCount() == 2, "a long pause splits the transcript in two");
        check(pb.paragraphOf(29) == 0 && pb.paragraphOf(30) == 1,
                "the split lands exactly at the pause");
        check(partitions(br.words.size() == 60 ? pb : pb, 60), "a split transcript still partitions");

        // ── 4. THE RULE THAT SURPRISES: a long pause TOO EARLY must not break ──────
        // Both conditions must hold. A pause after only a few words is someone hesitating,
        // not starting a new thought, and breaking there litters the gutter with stubs.
        Transcript early = speech(60, 120);
        widenGapBefore(early, 3, GAP + 5000);
        TranscriptParagraphs pe = TranscriptParagraphs.of(early);
        check(pe.paragraphCount() == 1,
                "a huge pause after only 3 words does NOT break (" + pe.paragraphCount() + ")");
        check(partitions(pe, 60), "and it still partitions");

        // ── 5. A forced break wins regardless of length ────────────────────────────
        Transcript forced = speech(60, 120);
        forced.words.get(2).forceLineBreakAfter = true;
        TranscriptParagraphs pfo = TranscriptParagraphs.of(forced);
        check(pfo.paragraphCount() == 2, "a forced break splits even after 3 words");
        check(pfo.paragraphOf(2) == 0 && pfo.paragraphOf(3) == 1,
                "the forced break lands after the marked word");
        check(partitions(pfo, 60), "a forced split partitions");

        // ── 6. Overlapping words — a real recogniser artefact ──────────────────────
        // Whisper emits overlapping spans on fast speech. A negative gap must clamp, never
        // read as an enormous positive one through a sign error.
        Transcript ov = speech(60, 120);
        TranscriptWord w = ov.words.get(10);
        ov.words.set(10, new TranscriptWord(w.text, w.startMs, w.endMs + 5000)); // swallows peers
        TranscriptParagraphs po = TranscriptParagraphs.of(ov);
        check(po.paragraphCount() >= 1, "overlapping words do not crash");
        check(partitions(po, 60), "overlapping words still partition");

        // ── 7. Boundary arithmetic — the off-by-one that decides a drag ────────────
        // gap must be STRICTLY greater than GAP_BREAK_MS.
        Transcript exact = speech(60, 120);
        widenGapBefore(exact, 30, GAP);
        check(TranscriptParagraphs.of(exact).paragraphCount() == 1,
                "a gap of exactly GAP_BREAK_MS does NOT break (strictly greater)");
        Transcript over = speech(60, 120);
        widenGapBefore(over, 30, GAP + 1);
        check(TranscriptParagraphs.of(over).paragraphCount() == 2,
                "one ms more than GAP_BREAK_MS DOES break");

        // curLen must be >= MIN_WORDS, so the break at exactly MIN_WORDS is allowed.
        Transcript atMin = speech(60, 120);
        widenGapBefore(atMin, MINW, GAP + 500);
        check(TranscriptParagraphs.of(atMin).paragraphCount() == 2,
                "a pause after exactly MIN_WORDS words DOES break");
        Transcript belowMin = speech(60, 120);
        widenGapBefore(belowMin, MINW - 1, GAP + 500);
        check(TranscriptParagraphs.of(belowMin).paragraphCount() == 1,
                "a pause one word short of MIN_WORDS does NOT break");

        // ── 8. Many breaks still partition ─────────────────────────────────────────
        Transcript many = speech(200, 120);
        for (int i = 1; i <= 4; i++) widenGapBefore(many, i * 40, GAP + 800);
        TranscriptParagraphs pm = TranscriptParagraphs.of(many);
        System.out.println("      four pauses in 200 words -> " + pm.paragraphCount());
        check(pm.paragraphCount() == 5, "four qualifying pauses give five paragraphs");
        check(partitions(pm, 200), "a heavily split transcript still partitions");

        // ── NEGATIVE CONTROLS ──────────────────────────────────────────────────────
        // The partition checker must be capable of failing, or every "partitions" above is
        // decoration. Assert it rejects a deliberately wrong shape.
        TranscriptParagraphs good = TranscriptParagraphs.of(speech(10, 120));
        check(!partitions(good, 11), "NEGCTRL: the partition checker REJECTS a wrong word count");
        check(!partitions(good, 9), "NEGCTRL: and rejects a short one");
        check(GAP > 0 && MINW > 1, "NEGCTRL: the thresholds are real, not zero");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
