import com.fadcam.ui.faditor.transcript.CaptionAnimator;
import com.fadcam.ui.faditor.transcript.CaptionPhrases;
import com.fadcam.ui.faditor.transcript.CaptionStyle;
import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

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
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionPhrases.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java \
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
        caretMapping();

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

        System.out.println("\n── zoneForSpan: a FRACTION of each line, and 0.5 is the model ──");
        ok("a fifth of the line", CaptionAnimator.zoneForSpan(0.2f, 1000) == 200);
        ok("half the line is full travel", CaptionAnimator.zoneForSpan(0.5f, 1000) == 500);
        ok("past half is capped at half, never overlapping",
                CaptionAnimator.zoneForSpan(0.9f, 1000) == 500);
        ok("zero stays zero (the off state survives)",
                CaptionAnimator.zoneForSpan(0f, 1000) == 0);
        ok("a negative fraction is the off state, not a reversed zone",
                CaptionAnimator.zoneForSpan(-0.3f, 1000) == 0);
        ok("NaN collapses to the off state rather than poisoning the span",
                CaptionAnimator.zoneForSpan(Float.NaN, 1000) == 0);
        ok("a zero-length span yields no zone", CaptionAnimator.zoneForSpan(0.5f, 0) == 0);
        // The property the cap exists to protect: capped in + capped out never exceed the span,
        // so unitProgress is never asked to resolve an overlap it has no good answer for.
        boolean neverOverlaps = true;
        for (long span = 1; span <= 400; span += 7) {
            long in = CaptionAnimator.zoneForSpan(5f, span);
            long out = CaptionAnimator.zoneForSpan(5f, span);
            if (in + out > span) neverOverlaps = false;
        }
        ok("capped zones never overlap, at any span", neverOverlaps);

        // ── The two properties the change from ms to a fraction was MADE for ──
        //
        // (1) THE AUTHORING PROPERTY. One value, set once in the style panel, must mean the same
        //     thing on every caption line of a thirty-minute video — that is the user's whole
        //     objection to dragging a caret per line. A duration cannot do this: 300ms is most of
        //     a short phrase and a flicker on a long one. A fraction is the same share of every
        //     line by definition, so pin exactly that, across a 150× range of line lengths.
        boolean sameShareEverywhere = true;
        for (long span = 200; span <= 30_000; span += 137) {
            long zone = CaptionAnimator.zoneForSpan(0.25f, span);
            // Rounding is the only permitted deviation: at most half a millisecond.
            if (Math.abs(zone - span * 0.25) > 0.5) sameShareEverywhere = false;
        }
        ok("one stored value is the SAME share of every line, from 0.2s to 30s",
                sameShareEverywhere);

        // (2) THE UNITS PROPERTY. The ms form had to be held in SOURCE ms by hand so a
        //     speed-adjusted clip would not animate over the wrong span — the exact mistake
        //     LEDGER §3g originally was. A fraction has no units, so it is correct in BOTH bases
        //     by construction: rebasing a span (here 2x speed, halving it) rescales the zone by
        //     exactly the same factor, leaving the proportion untouched. There is nothing left
        //     for a caller to remember, which is the point.
        boolean baseInvariant = true;
        for (long span = 100; span <= 12_000; span += 71) {
            long inSource = CaptionAnimator.zoneForSpan(0.3f, span);
            long inTimeline = CaptionAnimator.zoneForSpan(0.3f, span / 2);
            if (Math.abs(inSource / (double) span - inTimeline / (double) (span / 2)) > 1e-2) {
                baseInvariant = false;
            }
        }
        ok("the same value is correct in source AND timeline ms (no base to get wrong)",
                baseInvariant);

        System.out.println("\n── clampZonePct ──");
        eqF("the cap is exactly half a line", 0.5f, CaptionAnimator.MAX_ZONE_PCT);
        eqF("in range is untouched", 0.3f, CaptionAnimator.clampZonePct(0.3f));
        eqF("above the cap saturates", 0.5f, CaptionAnimator.clampZonePct(12f));
        eqF("below zero is the off state", 0f, CaptionAnimator.clampZonePct(-1f));
        eqF("NaN is the off state", 0f, CaptionAnimator.clampZonePct(Float.NaN));
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

    // ── The tape carets: what the user drags <-> what gets stored ───────────────────────────

    static Transcript t(long[]... spans) {
        Transcript t = new Transcript();
        for (long[] s : spans) t.words.add(new TranscriptWord("w", s[0], s[1]));
        return t;
    }

    /** Strike every word out — the "edited away, still on file" state. */
    static Transcript struck(Transcript t) {
        for (TranscriptWord w : t.words) w.struck = true;
        return t;
    }

    static void caretMapping() {
        System.out.println("\n── hasAnimatableSpan: is there anything here to time at all ──");
        // One phrase, 0..1000ms (words are within the 550ms gap rule and under the 6-word cap).
        CaptionPhrases one = CaptionPhrases.of(t(new long[]{0, 400}, new long[]{500, 1000}));
        ok("a phrase with a span can be animated", one.hasAnimatableSpan());

        // Two phrases split by a >550ms gap.
        CaptionPhrases two = CaptionPhrases.of(t(
                new long[]{0, 200},          // phrase 0: span 200
                new long[]{2000, 4000}));    // phrase 1: span 2000
        ok("several phrases likewise", two.hasAnimatableSpan());

        ok("nothing drawn -> no timing control is offered",
                !CaptionPhrases.of(new Transcript()).hasAnimatableSpan());
        ok("a null transcript does not throw",
                !CaptionPhrases.of(null).hasAnimatableSpan());
        // The case an earlier draft of this test got WRONG, so it is pinned from both sides.
        // spanMs floors a degenerate word (startMs == endMs, which some ASR backends emit) at 1ms
        // so it still DRAWS. But on a 1ms span the floor cap in zoneForSpan takes 1/2 = 0, so the
        // timing control would be offered and then do nothing at every setting. Offering a dead
        // control is worse than withholding it, so it is withheld — and 2ms, the shortest span
        // where full travel buys anything at all, is where it starts being offered.
        ok("a zero-length word draws, but nothing can be timed on 1ms, so no control",
                !CaptionPhrases.of(t(new long[]{700, 700})).hasAnimatableSpan());
        ok("2ms is the shortest span a zone can exist on, and it IS offered",
                CaptionPhrases.of(t(new long[]{700, 702})).hasAnimatableSpan());
        ok("a struck-out word draws nothing, so it earns no timing control",
                !CaptionPhrases.of(struck(t(new long[]{0, 400}))).hasAnimatableSpan());
        // The predicate is defined as "full travel buys a non-zero zone" rather than "the span is
        // non-zero", so it cannot drift away from the evaluator. Swept, not sampled.
        boolean gateAgreesWithEvaluator = true;
        for (long span = 1; span <= 300; span++) {
            boolean offered = CaptionPhrases.of(t(new long[]{0, span})).hasAnimatableSpan();
            boolean useful = CaptionAnimator.zoneForSpan(CaptionAnimator.MAX_ZONE_PCT, span) > 0;
            if (offered != useful) gateAgreesWithEvaluator = false;
        }
        ok("the control is offered on exactly the spans where it can do something",
                gateAgreesWithEvaluator);

        System.out.println("\n── travel fraction <-> stored fraction round-trips ──");
        // Same mapping for the text-box caret and the caption range control: both hand a 0..1
        // travel fraction to ONE conversion, so they cannot disagree about what "halfway" stores.
        eqF("resting at the end is the off state", 0f,
                CaptionAnimator.caretFractionForZone(0f));
        eqF("full travel is half a line", 1f,
                CaptionAnimator.caretFractionForZone(CaptionAnimator.MAX_ZONE_PCT));
        eqF("a stored value past the cap still pins the control at full travel", 1f,
                CaptionAnimator.caretFractionForZone(9f));
        eqF("halfway along the travel stores a quarter of a line", 0.25f,
                CaptionAnimator.zoneFromCaretFraction(0.5f));
        eqF("zero stored is zero back", 0f, CaptionAnimator.zoneFromCaretFraction(0f));
        eqF("full travel stores the cap", CaptionAnimator.MAX_ZONE_PCT,
                CaptionAnimator.zoneFromCaretFraction(1f));
        eqF("a fraction past 1 is clamped, not extrapolated", CaptionAnimator.MAX_ZONE_PCT,
                CaptionAnimator.zoneFromCaretFraction(5f));
        eqF("a negative fraction is clamped to the off state", 0f,
                CaptionAnimator.zoneFromCaretFraction(-3f));
        eqF("NaN travel is the off state", 0f, CaptionAnimator.zoneFromCaretFraction(Float.NaN));

        // The property that matters: a control dragged and then redrawn must land back where the
        // finger left it. A drift here would make a zone creep every time the view is rebuilt.
        boolean roundTrips = true;
        for (int i = 0; i <= 100; i++) {
            float f = i / 100f;
            float zone = CaptionAnimator.zoneFromCaretFraction(f);
            float back = CaptionAnimator.caretFractionForZone(zone);
            if (Math.abs(back - f) > 1e-3f) roundTrips = false;
        }
        ok("travel -> stored -> travel is stable across the whole range", roundTrips);

        // And the model's headline property, end to end: full travel on BOTH controls means every
        // phrase finishes arriving exactly as it starts leaving. Unlike the ms model, which could
        // only be checked on one clip's longest phrase, this now holds at EVERY line length —
        // which is precisely why the control can be set once for a whole video.
        float full = CaptionAnimator.zoneFromCaretFraction(1f);
        boolean meetsEverywhere = true;
        boolean neverExceeds = true;
        for (long span = 2; span <= 20_000; span += 39) {
            long sum = CaptionAnimator.zoneForSpan(full, span)
                    + CaptionAnimator.zoneForSpan(full, span);
            if (sum > span) neverExceeds = false;
            // Exact on an even span; one millisecond short on an odd one, because the floor cap
            // in zoneForSpan spends that millisecond on never overlapping rather than on meeting.
            if (sum != span - (span % 2)) meetsEverywhere = false;
        }
        ok("both controls at full travel: entrance ends where the exit begins, "
                + "at EVERY line length (to the odd millisecond)", meetsEverywhere);
        ok("and the two zones never exceed the line, at any length", neverExceeds);
    }
}
