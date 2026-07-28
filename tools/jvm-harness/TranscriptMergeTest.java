import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.transcript.TranscriptCoverage;
import com.fadcam.ui.faditor.transcript.TranscriptMerge;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

import java.util.List;

/**
 * Proves the source-scoped transcript core: coverage tracking and range merging.
 *
 * <p>This is the operation that replaces "every clip owns a private copy", so it is the one
 * that can silently destroy the user's transcript edits. The rules under test:
 * a re-run replaces ONLY its own span; edit state (struck / forced line break) outside that
 * span survives untouched; edit state inside it is knowingly lost and countable in advance;
 * coverage answers "what still needs running" without re-running silence.</p>
 *
 * <p>Every check is paired with a positive control, so a merge that quietly dropped everything,
 * or a coverage model that claimed to cover the whole timeline, would fail rather than pass.</p>
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out7 \
 *     tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
 *     tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptCoverage.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptMerge.java \
 *     tools/jvm-harness/TranscriptMergeTest.java
 *   java -cp tools/jvm-harness/out7 TranscriptMergeTest
 */
public class TranscriptMergeTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what + (detail.isEmpty() ? "" : " -> " + detail)); }
        else { fail++; System.out.println("FAIL  " + what + "   " + detail); }
    }

    static void eq(String what, Object want, Object got) {
        check(what, String.valueOf(want).equals(String.valueOf(got)), "want=" + want + " got=" + got);
    }

    static Transcript tr(Object... spec) {   // text, startMs, text, startMs, ...
        Transcript t = new Transcript();
        for (int i = 0; i < spec.length; i += 2) {
            long s = ((Number) spec[i + 1]).longValue();
            t.words.add(new TranscriptWord((String) spec[i], s, s + 300));
        }
        return t;
    }

    static String rangesOf(List<long[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (long[] r : rs) { if (sb.length() > 0) sb.append(','); sb.append(r[0]).append("..").append(r[1]); }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    public static void main(String[] args) {

        // ── 1. COVERAGE: what still needs transcribing ────────────────────────────────────
        {
            TranscriptCoverage c = new TranscriptCoverage();
            eq("1a nothing covered -> whole span outstanding", "0..46000", rangesOf(c.gaps(0, 46000)));
            c.add(8000, 28000);                       // the user transcribed one long clip
            eq("1b after one clip, two gaps remain", "0..8000,28000..46000", rangesOf(c.gaps(0, 46000)));
            eq("1c coveredMs", 20000L, c.coveredMs());
            check("1d covers() true inside", c.covers(9000, 27000), "");
            check("1e control: covers() FALSE when it straddles the edge",
                    !c.covers(27000, 29000), "");

            // Abutting ranges must coalesce — a clip boundary is not a hole to re-transcribe.
            c.add(28000, 34000);
            eq("1f abutting ranges coalesce", "[8000..34000]", c.toString());
            eq("1g gaps after coalesce", "0..8000,34000..46000", rangesOf(c.gaps(0, 46000)));

            // Overlapping re-run does not fragment coverage.
            c.add(30000, 40000);
            eq("1h overlapping add stays one range", "[8000..40000]", c.toString());

            c.add(0, 8000);
            c.add(40000, 46000);
            eq("1i fully covered -> nothing outstanding", "(none)", rangesOf(c.gaps(0, 46000)));
            check("1j control: a coverage that is NOT complete still reports a gap",
                    !new TranscriptCoverage().gaps(0, 46000).isEmpty(), "");
        }

        // ── 2. MERGE: a re-run replaces only its own span ─────────────────────────────────
        {
            // Source transcript assembled from two earlier clip runs.
            Transcript existing = tr("alpha", 1000, "bravo", 2000, "charlie", 9000, "delta", 10000);
            // Clip covering 9000..11000 is re-run with better timings and a corrected word.
            Transcript rerun = tr("charlie", 9100, "DELTA", 10200);

            Transcript merged = TranscriptMerge.mergeRange(existing, rerun, 9000, 11000);
            eq("2a words outside the span are untouched",
                    "[alpha, bravo, charlie, DELTA]", TranscriptMerge.texts(merged).toString());
            eq("2b the re-run's timings won inside the span", 9100L, merged.words.get(2).startMs);
            eq("2c words before the span kept their original timings", 1000L, merged.words.get(0).startMs);
            eq("2d no duplication", 4, merged.words.size());
            // Positive control: the merge really did change something.
            eq("2e control: the OLD transcript still has the old word", "delta",
                    existing.words.get(3).text);
        }

        // ── 3. EDIT STATE: the thing a careless merge would destroy ───────────────────────
        {
            Transcript existing = tr("keep", 1000, "struck", 2000, "alsokeep", 3000,
                                     "inRange", 9000);
            existing.words.get(1).struck = true;                 // user deleted this word
            existing.words.get(2).forceLineBreakAfter = true;    // user forced a line break
            existing.words.get(3).struck = true;                 // inside the re-run span

            eq("3a edits inside the re-run span are countable BEFORE merging",
                    1, TranscriptMerge.countEditsInRange(existing, 9000, 11000));
            eq("3b control: counting a span with no edits returns 0",
                    0, TranscriptMerge.countEditsInRange(existing, 20000, 30000));

            Transcript merged = TranscriptMerge.mergeRange(existing, tr("fresh", 9000), 9000, 11000);
            check("3c a struck word OUTSIDE the span survives", merged.words.get(1).struck,
                    "word=" + merged.words.get(1).text);
            check("3d a forced line break OUTSIDE the span survives",
                    merged.words.get(2).forceLineBreakAfter, "");
            check("3e control: the replacement word is NOT struck (state did not leak in)",
                    !merged.words.get(3).struck, "word=" + merged.words.get(3).text);
            eq("3f the in-span word really was replaced", "fresh", merged.words.get(3).text);
        }

        // ── 4. DISJOINT CLIP RUNS COMPOSE INTO ONE TRANSCRIPT ────────────────────────────
        // The real scenario: one source, several clips, transcribed at different times.
        {
            Transcript src = new Transcript();
            TranscriptCoverage cov = new TranscriptCoverage();

            src = TranscriptMerge.mergeRange(src, tr("one", 1000, "two", 2000), 0, 5000);
            cov.add(0, 5000);
            src = TranscriptMerge.mergeRange(src, tr("nine", 9000, "ten", 10000), 9000, 12000);
            cov.add(9000, 12000);

            eq("4a two disjoint runs compose in time order",
                    "[one, two, nine, ten]", TranscriptMerge.texts(src).toString());
            eq("4b coverage records both, gap between them preserved",
                    "5000..9000", rangesOf(cov.gaps(0, 12000)));

            // Filling the gap completes it without disturbing either neighbour.
            src = TranscriptMerge.mergeRange(src, tr("six", 6000), 5000, 9000);
            cov.add(5000, 9000);
            eq("4c filling the gap inserts in the right place",
                    "[one, two, six, nine, ten]", TranscriptMerge.texts(src).toString());
            eq("4d coverage is now continuous", "(none)", rangesOf(cov.gaps(0, 12000)));
            eq("4e control: coverage of a WIDER span still reports the uncovered tail",
                    "12000..20000", rangesOf(cov.gaps(0, 20000)));
        }

        // ── 5. WINDOWING: a clip is a view, not an owner ─────────────────────────────────
        {
            Transcript src = tr("a", 1000, "b", 5000, "c", 9000, "d", 13000);
            eq("5a clip window returns only its own words",
                    "[b, c]", TranscriptMerge.texts(TranscriptMerge.window(src, 5000, 13000)).toString());
            eq("5b a different clip over the same source sees different words",
                    "[a]", TranscriptMerge.texts(TranscriptMerge.window(src, 0, 5000)).toString());
            eq("5c control: the source itself is unchanged by windowing", 4, src.words.size());
            // Windowed copies must not alias — editing a window must not mutate the source.
            Transcript w = TranscriptMerge.window(src, 5000, 13000);
            w.words.get(0).struck = true;
            check("5d a window is a COPY (striking it does not touch the source)",
                    !src.words.get(1).struck, "");
        }

        // ── 6. BOUNDARY: engine output straying outside the requested span ───────────────
        {
            Transcript existing = tr("before", 4000, "after", 12000);
            // The engine emitted a word at 3500 and one at 12500, outside 5000..12000.
            Transcript sloppy = tr("stray-early", 3500, "good", 6000, "stray-late", 12500);
            Transcript merged = TranscriptMerge.mergeRange(existing, sloppy, 5000, 12000);
            eq("6a out-of-span engine words are dropped, neighbours preserved",
                    "[before, good, after]", TranscriptMerge.texts(merged).toString());
            check("6b control: the in-span word WAS taken",
                    TranscriptMerge.texts(merged).contains("good"), "");
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
