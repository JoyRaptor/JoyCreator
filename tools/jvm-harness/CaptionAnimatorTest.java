import com.fadcam.ui.faditor.transcript.CaptionAnimator;
import com.fadcam.ui.faditor.transcript.CaptionStyle;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity.BLOCK;
import static com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity.LETTER;
import static com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity.SENTENCE;
import static com.fadcam.ui.faditor.transcript.CaptionAnimator.Granularity.WORD;

/**
 * Pins SPEC_TEXT_ANIMATION's arithmetic off-device, and — the reason this file exists — pins the
 * LEDGER §3g unification as a PROPERTY rather than as a one-off frame comparison.
 *
 * <p>The §3g defect was that preview and export computed the same caption animation from two
 * different clocks: a wall-clock {@code ValueAnimator} on one side, media time on the other. A
 * frame-diff can only ever show that ONE sampled frame agrees. What actually makes them agree
 * everywhere is that the animation is a pure function of ELAPSED MEDIA TIME — {@code (mediaMs -
 * unitStartMs)} — and nothing else. {@link #clockInvariant} asserts exactly that, so a future
 * edit that reintroduces a wall-clock or absolute-time term fails here instead of silently in
 * somebody's exported video weeks later.</p>
 *
 * <p>{@code CaptionStyle} is a harness stub (see {@code stubs-caption/}); {@link #stubGuard}
 * parses the real source so the stub cannot drift away from it unnoticed.</p>
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out-caption \
 *     tools/jvm-harness/stubs/androidx/annotation/*.java \
 *     tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java \
 *     tools/jvm-harness/CaptionAnimatorTest.java
 *   java -cp tools/jvm-harness/out-caption CaptionAnimatorTest
 */
public class CaptionAnimatorTest {

    static int pass = 0, fail = 0;
    static final float EPS = 1e-4f;

    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("PASS  " + what); }
        else { fail++; System.out.println("FAIL  " + what); }
    }

    static void eqF(String what, float want, float got) {
        if (Math.abs(want - got) <= EPS) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else { fail++; System.out.println("FAIL  " + what + "\n      want=" + want + " got=" + got); }
    }

    static void eqS(String what, String want, String got) {
        if (want.equals(got)) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else { fail++; System.out.println("FAIL  " + what + "\n      want=" + want + " got=" + got); }
    }

    static CaptionStyle style(CaptionStyle.Anim a) {
        return new CaptionStyle("t", a);
    }

    public static void main(String[] args) {
        stubGuard();
        clockInvariant();
        handlesAreTheTiming();
        presetsRestAtIdentity();
        presetShapes();
        unitSplitting();
        phraseUnits();

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ── The stub cannot drift from the real class ────────────────────────────────────────────

    static void stubGuard() {
        System.out.println("\n── stub vs real CaptionStyle ──");
        String src;
        try {
            src = new String(Files.readAllBytes(Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionStyle.java")));
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL  could not read the real CaptionStyle.java: " + e
                    + "\n      (run this from the repo root)");
            return;
        }
        // The real enum body, e.g. "public enum Anim { ... }" spanning lines with javadoc.
        Matcher m = Pattern.compile("enum\\s+Anim\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(src);
        if (!m.find()) {
            fail++;
            System.out.println("FAIL  no 'enum Anim' found in the real CaptionStyle.java");
            return;
        }
        String body = m.group(1)
                .replaceAll("/\\*.*?\\*/", " ")   // strip javadoc/block comments
                .replaceAll("//[^\\n]*", " ");    // strip line comments
        List<String> real = new ArrayList<>();
        for (String tok : body.split("[,\\s]+")) {
            if (tok.matches("[A-Z][A-Z0-9_]*")) real.add(tok);
        }
        List<String> stub = new ArrayList<>();
        for (CaptionStyle.Anim a : CaptionStyle.Anim.values()) stub.add(a.name());
        eqS("stub Anim constants match the real ones", real.toString(), stub.toString());
    }

    // ── §3g: the property that makes preview and export agree ───────────────────────────────

    static void clockInvariant() {
        System.out.println("\n── §3g clock invariant: animation depends ONLY on elapsed media time ──");
        for (CaptionStyle.Anim a : CaptionStyle.Anim.values()) {
            CaptionStyle s = style(a);
            // The same word, 300ms in, at three wildly different absolute positions in the file.
            // A wall-clock or absolute-time term anywhere would break these apart.
            float atZero    = CaptionAnimator.emphasis(s, 150L, 0L);
            float atMinute  = CaptionAnimator.emphasis(s, 60_150L, 60_000L);
            float atHour    = CaptionAnimator.emphasis(s, 3_600_150L, 3_600_000L);
            eqF(a + ": emphasis is translation-invariant (0s vs 60s)", atZero, atMinute);
            eqF(a + ": emphasis is translation-invariant (0s vs 1h)", atZero, atHour);

            // And the transform derived from it carries no extra time term of its own.
            CaptionAnimator.Transform t1 = CaptionAnimator.transformAt(s, 150L, 0L, 100f);
            CaptionAnimator.Transform t2 = CaptionAnimator.transformAt(s, 60_150L, 60_000L, 100f);
            ok(a + ": transformAt matches across the same offset",
                    Math.abs(t1.scaleX - t2.scaleX) <= EPS
                            && Math.abs(t1.scaleY - t2.scaleY) <= EPS
                            && Math.abs(t1.dy - t2.dy) <= EPS
                            && Math.abs(t1.alpha - t2.alpha) <= EPS);
        }
        // Before the entrance and long after it, the value is pinned — no drift, no retrigger.
        CaptionStyle pop = style(CaptionStyle.Anim.POP);
        eqF("emphasis clamps below the start", CaptionAnimator.ease(CaptionStyle.Anim.POP, 0f),
                CaptionAnimator.emphasis(pop, -5_000L, 0L));
        eqF("emphasis clamps after the end", 1f, CaptionAnimator.emphasis(pop, 999_999L, 0L));
    }

    // ── The user's timing model: the handles ARE the timing ─────────────────────────────────

    static void handlesAreTheTiming() {
        System.out.println("\n── unitProgress: handles at the ends is the natural 'off' ──");
        // "If the carets are at the end, then there will be no animation."
        for (long t = 0; t <= 1000; t += 125) {
            eqF("no zones @" + t + "ms -> fully present",
                    1f, CaptionAnimator.unitProgress(t, 0, 1000, 0, 0, 0, 1));
        }

        System.out.println("\n── unitProgress: entrance ──");
        // One unit, 400ms entrance on a 1000ms item.
        eqF("in: start of item", 0f, CaptionAnimator.unitProgress(0, 0, 1000, 400, 0, 0, 1));
        eqF("in: halfway", 0.5f, CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 0, 1));
        eqF("in: past the zone is fully present",
                1f, CaptionAnimator.unitProgress(400, 0, 1000, 400, 0, 0, 1));
        eqF("in: mid-item is fully present",
                1f, CaptionAnimator.unitProgress(700, 0, 1000, 400, 0, 0, 1));

        System.out.println("\n── unitProgress: exit ──");
        eqF("out: before the zone is fully present",
                1f, CaptionAnimator.unitProgress(700, 0, 1000, 0, 300, 0, 1));
        eqF("out: end of item is fully gone",
                0f, CaptionAnimator.unitProgress(1000, 0, 1000, 0, 300, 0, 1));
        eqF("out: halfway through the exit",
                0.5f, CaptionAnimator.unitProgress(850, 0, 1000, 0, 300, 0, 1));

        System.out.println("\n── unitProgress: units are STAGGERED, and the last one lands on the zone end ──");
        float u0 = CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 0, 3);
        float u1 = CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 1, 3);
        float u2 = CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 2, 3);
        ok("earlier units lead later ones (a sweep, not lockstep)", u0 > u1 && u1 > u2);
        eqF("last unit is exactly arrived at the end of the entrance zone",
                1f, CaptionAnimator.unitProgress(400, 0, 1000, 400, 0, 2, 3));
        ok("first unit arrived strictly before the zone ended",
                CaptionAnimator.unitProgress(267, 0, 1000, 400, 0, 0, 3) >= 1f - EPS);

        System.out.println("\n── unitProgress: guards ──");
        eqF("before the item starts", 0f,
                CaptionAnimator.unitProgress(-500, 0, 1000, 400, 0, 0, 1));
        eqF("after the item ends", 0f,
                CaptionAnimator.unitProgress(9999, 0, 1000, 0, 300, 0, 1));
        eqF("zero-length item does not divide by zero",
                1f, CaptionAnimator.unitProgress(0, 500, 500, 0, 0, 0, 1));
        eqF("unitIndex out of range is clamped, not thrown",
                CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 2, 3),
                CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 99, 3));
        eqF("unitCount 0 is treated as 1",
                CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 0, 1),
                CaptionAnimator.unitProgress(200, 0, 1000, 400, 0, 0, 0));

        // "brought all the way both into the centre ... everything animates in, and then as soon
        // as it's in, it starts animating out." Zones meeting exactly at the midpoint.
        System.out.println("\n── unitProgress: handles meeting at the centre ──");
        eqF("centre: peaks at the midpoint",
                1f, CaptionAnimator.unitProgress(500, 0, 1000, 500, 500, 0, 1));
        eqF("centre: quarter in", 0.5f,
                CaptionAnimator.unitProgress(250, 0, 1000, 500, 500, 0, 1));
        eqF("centre: three-quarters in", 0.5f,
                CaptionAnimator.unitProgress(750, 0, 1000, 500, 500, 0, 1));
        // If the handles are dragged PAST each other the zones overlap. The entrance wins, which
        // is a decision, not an accident — pinned here so the UI knows it must clamp the handles
        // rather than rely on the evaluator to invent a blend.
        ok("overlapping zones: the entrance wins",
                CaptionAnimator.unitProgress(650, 0, 1000, 700, 700, 0, 1)
                        == CaptionAnimator.unitProgress(650, 0, 1000, 700, 0, 0, 1));
    }

    // ── Presets ─────────────────────────────────────────────────────────────────────────────

    static void presetsRestAtIdentity() {
        System.out.println("\n── every preset resolves to IDENTITY at rest ──");
        // This is the "compose, don't replace" constraint made testable: a preset that is not
        // identity at progress=1 permanently offsets the text and silently fights the user's own
        // keyframed scale/opacity for the whole middle of the clip.
        for (CaptionAnimator.Preset p : CaptionAnimator.Preset.values()) {
            CaptionAnimator.Transform t = CaptionAnimator.presetTransform(p, 1f, 100f);
            ok(p + " is identity at progress=1",
                    Math.abs(t.scaleX - 1f) <= EPS && Math.abs(t.scaleY - 1f) <= EPS
                            && Math.abs(t.dx) <= EPS && Math.abs(t.dy) <= EPS
                            && Math.abs(t.alpha - 1f) <= EPS && Math.abs(t.blurPx) <= EPS);
        }

        System.out.println("\n── unimplemented presets return identity, never an approximation ──");
        for (CaptionAnimator.Preset p : CaptionAnimator.Preset.values()) {
            if (p.implemented) continue;
            CaptionAnimator.Transform t = CaptionAnimator.presetTransform(p, 0f, 100f);
            ok(p + " (unimplemented) is identity even at progress=0",
                    Math.abs(t.scaleX - 1f) <= EPS && Math.abs(t.alpha - 1f) <= EPS);
            ok(p + " states why it cannot ship", !CaptionAnimator.unsupportedReason(p).isEmpty());
        }
        ok("NONE is implemented (it is the off state, not a gap)",
                CaptionAnimator.Preset.NONE.implemented);
        eqS("an implemented preset has no unsupported reason",
                "", CaptionAnimator.unsupportedReason(CaptionAnimator.Preset.FADE));
    }

    static void presetShapes() {
        System.out.println("\n── preset shapes at progress=0 ──");
        CaptionAnimator.Transform tw = CaptionAnimator.presetTransform(
                CaptionAnimator.Preset.TYPEWRITER, 0f, 100f);
        eqF("TYPEWRITER is absent before its slot", 0f, tw.alpha);
        // A typewriter that ramps is a fade — the step is the whole point.
        eqF("TYPEWRITER is fully present the instant it starts", 1f,
                CaptionAnimator.presetTransform(CaptionAnimator.Preset.TYPEWRITER, 0.01f, 100f).alpha);

        eqF("FADE starts transparent", 0f,
                CaptionAnimator.presetTransform(CaptionAnimator.Preset.FADE, 0f, 100f).alpha);
        ok("FADE ramps rather than steps",
                CaptionAnimator.presetTransform(CaptionAnimator.Preset.FADE, 0.5f, 100f).alpha > 0.5f);

        CaptionAnimator.Transform rise = CaptionAnimator.presetTransform(
                CaptionAnimator.Preset.RISE, 0f, 100f);
        ok("RISE starts BELOW the baseline (positive dy) so it rises into place", rise.dy > 0f);

        CaptionAnimator.Transform ghost = CaptionAnimator.presetTransform(
                CaptionAnimator.Preset.GHOST, 0f, 100f);
        ok("GHOST starts offset, oversized, blurred and transparent",
                ghost.dx > 0f && ghost.scaleX > 1f && ghost.blurPx > 0f && ghost.alpha < EPS);

        CaptionAnimator.Transform beam = CaptionAnimator.presetTransform(
                CaptionAnimator.Preset.BEAM, 0f, 100f);
        // The reporter's words: "letters start 200% tall / 5% wide".
        eqF("BEAM starts 5% wide", 0.05f, beam.scaleX);
        eqF("BEAM starts 200% tall", 2.0f, beam.scaleY);
        ok("BEAM needs a non-uniform scale (this is why Transform has two axes)",
                Math.abs(beam.scaleX - beam.scaleY) > 0.5f);

        System.out.println("\n── presetTransform clamps out-of-range progress ──");
        for (CaptionAnimator.Preset p : CaptionAnimator.Preset.values()) {
            CaptionAnimator.Transform lo = CaptionAnimator.presetTransform(p, -3f, 100f);
            CaptionAnimator.Transform z = CaptionAnimator.presetTransform(p, 0f, 100f);
            CaptionAnimator.Transform hi = CaptionAnimator.presetTransform(p, 7f, 100f);
            ok(p + " clamps below 0", Math.abs(lo.alpha - z.alpha) <= EPS);
            ok(p + " clamps above 1", Math.abs(hi.alpha - 1f) <= EPS);
        }
    }

    // ── Granularity ─────────────────────────────────────────────────────────────────────────

    static String render(String text, int[][] units) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < units.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(text, units[i][0], units[i][1]);
        }
        return sb.append(']').toString();
    }

    static void phraseUnits() {
        System.out.println("\n── unitCount / unitIndexOf over a phrase's visible words ──");
        List<String> w = java.util.Arrays.asList("Hi", "there.", "How", "are", "you?");

        ok("BLOCK: one unit", CaptionAnimator.unitCount(w, BLOCK) == 1);
        ok("WORD: one unit per word", CaptionAnimator.unitCount(w, WORD) == w.size());
        ok("SENTENCE: two sentences here", CaptionAnimator.unitCount(w, SENTENCE) == 2);
        int letters = 0;
        for (String s : w) letters += s.length();
        ok("LETTER: one unit per character", CaptionAnimator.unitCount(w, LETTER) == letters);

        ok("BLOCK: every word maps to unit 0",
                CaptionAnimator.unitIndexOf(w, BLOCK, 0, 0) == 0
                        && CaptionAnimator.unitIndexOf(w, BLOCK, 4, 0) == 0);
        ok("WORD: word 3 is unit 3", CaptionAnimator.unitIndexOf(w, WORD, 3, 0) == 3);
        ok("SENTENCE: 'there.' still belongs to sentence 0 (the terminator ENDS it)",
                CaptionAnimator.unitIndexOf(w, SENTENCE, 1, 0) == 0);
        ok("SENTENCE: 'How' starts sentence 1",
                CaptionAnimator.unitIndexOf(w, SENTENCE, 2, 0) == 1);
        ok("LETTER: first char of word 1 follows the 2 chars of word 0",
                CaptionAnimator.unitIndexOf(w, LETTER, 1, 0) == 2);
        ok("LETTER: units are strictly increasing across the phrase",
                CaptionAnimator.unitIndexOf(w, LETTER, 1, 1)
                        > CaptionAnimator.unitIndexOf(w, LETTER, 1, 0));
        ok("LETTER: the last unit is in range",
                CaptionAnimator.unitIndexOf(w, LETTER, 4, 3) < CaptionAnimator.unitCount(w, LETTER));

        System.out.println("\n── unitIndexOf guards ──");
        ok("word index past the end is clamped",
                CaptionAnimator.unitIndexOf(w, WORD, 99, 0) == w.size() - 1);
        ok("negative word index is clamped", CaptionAnimator.unitIndexOf(w, WORD, -5, 0) == 0);
        ok("empty phrase does not throw",
                CaptionAnimator.unitCount(new ArrayList<String>(), WORD) == 1
                        && CaptionAnimator.unitIndexOf(new ArrayList<String>(), WORD, 0, 0) == 0);
        ok("a terminator wrapped in a quote still ends the sentence",
                CaptionAnimator.unitCount(java.util.Arrays.asList("He", "left.\"", "Then"),
                        SENTENCE) == 2);
        ok("a decimal point mid-word does NOT end a sentence",
                CaptionAnimator.unitCount(java.util.Arrays.asList("costs", "3.50", "today"),
                        SENTENCE) == 1);

        System.out.println("\n── zoneForSpan: the centre is a saturation point, not an overlap ──");
        ok("a zone shorter than half the span is untouched",
                CaptionAnimator.zoneForSpan(200, 1000) == 200);
        ok("a zone at exactly half saturates", CaptionAnimator.zoneForSpan(500, 1000) == 500);
        ok("a zone past half is capped at half, never overlapping",
                CaptionAnimator.zoneForSpan(9999, 1000) == 500);
        ok("zero stays zero (the off state survives)", CaptionAnimator.zoneForSpan(0, 1000) == 0);
        ok("a zero-length span yields no zone", CaptionAnimator.zoneForSpan(500, 0) == 0);
        // The property the cap exists to protect: capped in + capped out never exceed the span,
        // so unitProgress is never asked to resolve an overlap it has no good answer for.
        boolean neverOverlaps = true;
        for (long span = 1; span <= 400; span += 7) {
            long in = CaptionAnimator.zoneForSpan(100_000, span);
            long out = CaptionAnimator.zoneForSpan(100_000, span);
            if (in + out > span) neverOverlaps = false;
        }
        ok("capped zones never overlap, at any span", neverOverlaps);
    }

    static void unitSplitting() {
        System.out.println("\n── splitUnits ──");
        String s = "Hi there. How are you?! Fine";

        eqS("BLOCK is one unit", "[" + s + "]", render(s, CaptionAnimator.splitUnits(s, BLOCK)));
        eqS("WORD splits on whitespace", "[Hi|there.|How|are|you?!|Fine]",
                render(s, CaptionAnimator.splitUnits(s, WORD)));
        eqS("SENTENCE absorbs a run of terminators ('?!' is one sentence)",
                "[Hi there.|How are you?!|Fine]",
                render(s, CaptionAnimator.splitUnits(s, SENTENCE)));

        int[][] letters = CaptionAnimator.splitUnits(s, LETTER);
        ok("LETTER excludes whitespace (it belongs to no unit)",
                letters.length == s.replaceAll("\\s", "").length());
        ok("LETTER units are all single characters",
                letters.length > 0 && letters[0][1] - letters[0][0] == 1);

        eqS("WORD: leading/trailing whitespace produces no empty units", "[a|b]",
                render("  a   b  ", CaptionAnimator.splitUnits("  a   b  ", WORD)));
        eqS("SENTENCE: a newline terminates a sentence", "[one\n|two]",
                render("one\ntwo", CaptionAnimator.splitUnits("one\ntwo", SENTENCE)));

        for (CaptionAnimator.Granularity g : CaptionAnimator.Granularity.values()) {
            ok(g + ": empty text yields no units",
                    CaptionAnimator.splitUnits("", g).length == 0);
            ok(g + ": whitespace-only text yields no non-empty units",
                    render("   ", CaptionAnimator.splitUnits("   ", g)).replaceAll("[\\[\\]| ]", "")
                            .isEmpty());
        }
    }
}
