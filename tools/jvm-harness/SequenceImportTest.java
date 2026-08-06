import com.fadcam.ui.faditor.sprite.DurationParser;
import com.fadcam.ui.faditor.sprite.SequenceDetector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM harness for SPEC_IMAGE_SEQUENCE §3 — run detection (§3a) and duration parsing (§3c).
 *
 * <p>Both are pure, and both are places where being wrong is quiet: a detector that mis-orders
 * {@code frame_9} and {@code frame_10} produces an animation that plays in almost the right
 * order, and a parser that silently reads "1/6 min" as invalid just makes a field look broken.
 * Every accepted spelling in the spec is pinned here rather than left to be discovered.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-sequence.sh}</p>
 */
public class SequenceImportTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // §3a detection
        detectsASimpleRun();
        sortsNumericallyNotLexically();
        requiresTheSameStem();
        requiresTheSameExtension();
        singleImageIsNotASequence();
        unnumberedFileIsNotASequence();
        pickedFileIsAlwaysIncluded();
        capsWhatItOffersAndSaysSo();
        toleratesMixedZeroPadding();
        imageNameFilter();
        describeReadsLikeTheSpec();

        // §3c duration parsing
        parsesSeconds();
        parsesMinutesAndFractions();
        parsesClockTimes();
        parsesFrames();
        parsesCompound();
        parsesMilliseconds();
        rejectsJunk();
        formatsBack();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static List<String> names(String... n) { return new ArrayList<>(Arrays.asList(n)); }

    // ── §3a detection ───────────────────────────────────────────────────────

    static void detectsASimpleRun() {
        SequenceDetector.Candidate c = SequenceDetector.detect("shot_001.png",
                names("shot_001.png", "shot_002.png", "shot_003.png"));
        check("detects a run", c != null && c.count() == 3);
        check("keeps order", c != null && c.names.get(0).equals("shot_001.png")
                && c.names.get(2).equals("shot_003.png"));
        check("reports the stem", c != null && c.stem.equals("shot_"));
        check("reports the extension", c != null && c.extension.equals(".png"));
    }

    static void sortsNumericallyNotLexically() {
        SequenceDetector.Candidate c = SequenceDetector.detect("f9.png",
                names("f9.png", "f10.png", "f11.png", "f2.png"));
        check("9 sorts before 10 (numeric, not lexical)",
                c != null && c.names.equals(Arrays.asList("f2.png", "f9.png", "f10.png", "f11.png")));
    }

    static void requiresTheSameStem() {
        SequenceDetector.Candidate c = SequenceDetector.detect("shot_001.png",
                names("shot_001.png", "shot_002.png", "other_003.png"));
        check("a different stem is a different run", c != null && c.count() == 2);
    }

    static void requiresTheSameExtension() {
        SequenceDetector.Candidate c = SequenceDetector.detect("shot_001.png",
                names("shot_001.png", "shot_002.png", "shot_003.txt"));
        check("a per-frame sidecar does not join the run", c != null && c.count() == 2);
    }

    static void singleImageIsNotASequence() {
        check("one numbered image alone is not a run",
                SequenceDetector.detect("shot_001.png", names("shot_001.png")) == null);
    }

    static void unnumberedFileIsNotASequence() {
        check("a file with no trailing number cannot start a run",
                SequenceDetector.detect("photo.png", names("photo.png", "photo2.png")) == null);
    }

    static void pickedFileIsAlwaysIncluded() {
        // The listing may not contain the pick (a provider that returns a partial page).
        SequenceDetector.Candidate c = SequenceDetector.detect("shot_005.png",
                names("shot_001.png", "shot_002.png"));
        check("the picked file is always in the result",
                c != null && c.names.contains("shot_005.png") && c.count() == 3);
    }

    static void capsWhatItOffersAndSaysSo() {
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= SequenceDetector.MAX_OFFERED + 50; i++) {
            many.add(String.format("img_%04d.png", i));
        }
        SequenceDetector.Candidate c = SequenceDetector.detect("img_0001.png", many);
        check("offers at most MAX_OFFERED",
                c != null && c.count() == SequenceDetector.MAX_OFFERED);
        check("says it truncated", c != null && c.truncated);
        check("still reports the true total",
                c != null && c.totalFound == SequenceDetector.MAX_OFFERED + 50);
        check("truncated wording mentions both numbers",
                c != null && c.describe().contains("650") && c.describe().contains("600"));
    }

    static void toleratesMixedZeroPadding() {
        SequenceDetector.Candidate c = SequenceDetector.detect("f1.png",
                names("f1.png", "f01.png", "f2.png"));
        check("differently padded spellings still order deterministically",
                c != null && c.count() == 3 && c.names.get(2).equals("f2.png"));
    }

    static void imageNameFilter() {
        check("png is an image", SequenceDetector.isImageName("a.PNG"));
        check("webp is an image", SequenceDetector.isImageName("a.webp"));
        check("txt is not", !SequenceDetector.isImageName("a.txt"));
        check("null is not", !SequenceDetector.isImageName(null));
    }

    static void describeReadsLikeTheSpec() {
        SequenceDetector.Candidate c = SequenceDetector.detect("shot_001.png",
                names("shot_001.png", "shot_002.png", "shot_003.png"));
        check("describe matches the spec's wording: " + (c == null ? "null" : c.describe()),
                c != null && c.describe().equals("We found 3 images in this sequence"));
    }

    // ── §3c duration parsing ────────────────────────────────────────────────

    static void parsesSeconds() {
        check("10.5s", DurationParser.parseMs("10.5s", 24f) == 10500);
        check("10.5 sec", DurationParser.parseMs("10.5 sec", 24f) == 10500);
        check("10.5 seconds", DurationParser.parseMs("10.5 seconds", 24f) == 10500);
        check("bare number means seconds", DurationParser.parseMs("10", 24f) == 10000);
        check("comma decimal", DurationParser.parseMs("10,5s", 24f) == 10500);
        check("whitespace is fine", DurationParser.parseMs("  3s  ", 24f) == 3000);
    }

    static void parsesMinutesAndFractions() {
        check("2min", DurationParser.parseMs("2min", 24f) == 120000);
        check("1/6 min (the spec's own example)",
                DurationParser.parseMs("1/6 min", 24f) == 10000);
        check("0.5m", DurationParser.parseMs("0.5m", 24f) == 30000);
        check("1h", DurationParser.parseMs("1h", 24f) == 3600000);
    }

    static void parsesClockTimes() {
        check("00:00:10.5", DurationParser.parseMs("00:00:10.5", 24f) == 10500);
        check("1:30", DurationParser.parseMs("1:30", 24f) == 90000);
        check("1:02:03", DurationParser.parseMs("1:02:03", 24f) == 3723000);
    }

    static void parsesFrames() {
        check("90f at 24fps", DurationParser.parseMs("90f", 24f) == 3750);
        check("90 frames at 24fps", DurationParser.parseMs("90 frames", 24f) == 3750);
        check("12f at 12fps is one second", DurationParser.parseMs("12f", 12f) == 1000);
        check("frames need an fps", DurationParser.parseMs("90f", 0f) == DurationParser.INVALID);
    }

    static void parsesCompound() {
        check("2m30s", DurationParser.parseMs("2m30s", 24f) == 150000);
        check("1h2m3s", DurationParser.parseMs("1h2m3s", 24f) == 3723000);
        check("2m 30s with a space", DurationParser.parseMs("2m 30s", 24f) == 150000);
    }

    static void parsesMilliseconds() {
        check("250ms", DurationParser.parseMs("250ms", 24f) == 250);
        // The load-bearing ordering check: "ms" must not be eaten by the "m" (minutes) suffix.
        check("ms is not minutes", DurationParser.parseMs("250ms", 24f) != 250 * 60000);
    }

    static void rejectsJunk() {
        check("empty", DurationParser.parseMs("", 24f) == DurationParser.INVALID);
        check("null", DurationParser.parseMs(null, 24f) == DurationParser.INVALID);
        check("words", DurationParser.parseMs("soon", 24f) == DurationParser.INVALID);
        check("divide by zero", DurationParser.parseMs("1/0 min", 24f) == DurationParser.INVALID);
        check("unknown unit", DurationParser.parseMs("5 furlongs", 24f) == DurationParser.INVALID);
    }

    static void formatsBack() {
        check("short format", DurationParser.formatMs(10500).equals("10.50s"));
        check("minutes format", DurationParser.formatMs(90000).equals("1:30.00"));
        check("hours format", DurationParser.formatMs(3723000).equals("1:02:03.00"));
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
