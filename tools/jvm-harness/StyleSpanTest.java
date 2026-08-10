import com.fadcam.ui.faditor.model.StyleSpan;
import com.fadcam.ui.faditor.model.TextStyleResolver;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.fadcam.ui.faditor.model.TextStyleResolver.Prop.BOLD;
import static com.fadcam.ui.faditor.model.TextStyleResolver.Prop.FILL_COLOR;
import static com.fadcam.ui.faditor.model.TextStyleResolver.Prop.FONT_FAMILY;
import static com.fadcam.ui.faditor.model.TextStyleResolver.Prop.ITALIC;
import static com.fadcam.ui.faditor.model.TextStyleResolver.Prop.TEXT_CASE;

/**
 * Pins W5-2's rich-text span rules (§3.8) off-device: resolution (last-span-wins per property,
 * base fallback), the mixed-state queries the drawer displays, the range writes ("one choice
 * overriding"), text-edit realignment, case application (length-preserving), and sparse JSON.
 *
 * <p>{@code TextStyleResolver} and {@code StyleSpan} are pure Java by design — no android.graphics
 * — so this harness compiles the REAL model classes (not stubs) and every sentence below is about
 * the shipped code.</p>
 *
 * Run:
 *   bash tools/jvm-harness/run-textstyle.sh
 */
public class StyleSpanTest {

    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static TextStyleResolver.Base base() {
        TextStyleResolver.Base b = new TextStyleResolver.Base();
        b.fontFamily = "popular";
        b.textCase = TextStyleResolver.CASE_NONE;
        b.fillColor = 0xFFFFFFFF;
        return b;
    }

    static String dump(List<TextStyleResolver.Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (TextStyleResolver.Run r : runs) {
            sb.append("[").append(r.start).append(",").append(r.end)
              .append(" ").append(r.fontFamily).append(" ").append(r.bold ? "B" : "-")
              .append(r.italic ? "I" : "-").append(r.underline ? "U" : "-")
              .append(" c").append(r.textCase).append(" f").append(r.fill)
              .append(" st").append(r.stroke).append(" gl").append(r.glow)
              .append(" sh").append(r.shadow).append(" bg").append(r.background)
              .append("] ");
        }
        return sb.toString();
    }

    public static void main(String[] a) {
        // ── Resolution ────────────────────────────────────────────────────────────────────────
        TextStyleResolver.Base base = base();
        List<StyleSpan> spans = new ArrayList<>();

        check(TextStyleResolver.at(base, spans, BOLD, 3, 10) == Boolean.FALSE,
                "no spans: BOLD at char 3 = base (false)");
        check(TextStyleResolver.at(base, spans, FILL_COLOR, 0, 10) == null
                        || ((Integer) TextStyleResolver.at(base, spans, FILL_COLOR, 0, 10)).intValue() == 0xFFFFFFFF,
                "no spans: fill at char 0 = base white");

        // Overlapping spans: last wins per property.
        StyleSpan s1 = new StyleSpan(0, 6);
        s1.bold = true;
        s1.textCase = TextStyleResolver.CASE_ALL_CAPS;
        s1.fillColor = 0xFF0000FF;

        StyleSpan s2 = new StyleSpan(2, 8);
        s2.bold = false;            // kills bold 2..8
        s2.textCase = TextStyleResolver.CASE_NONE; // kills caps 2..8

        spans.add(s1);
        spans.add(s2);

        List<TextStyleResolver.Run> runs = TextStyleResolver.resolve(base, spans, 10);
        String d = dump(runs);
        check(runs.get(0).start == 0 && runs.get(0).end == 2 && runs.get(0).bold
                        && TextStyleResolver.CASE_ALL_CAPS.equals(runs.get(0).textCase)
                        && runs.get(0).fill == 0xFF0000FF,
                "chars 0-2: span1 only — bold+caps+blue: " + d);
        check(runs.get(1).start == 2 && runs.get(1).end == 6 && !runs.get(1).bold
                        && TextStyleResolver.CASE_NONE.equals(runs.get(1).textCase)
                        && runs.get(1).fill == 0xFF0000FF,
                "chars 2-6: span2 wins bold/case, span1 keeps fill (per-property): " + d);
        check(runs.get(2).start == 6 && runs.get(2).end == 10 && !runs.get(2).bold
                        && TextStyleResolver.CASE_NONE.equals(runs.get(2).textCase)
                        && runs.get(2).fill == 0xFFFFFFFF,
                "chars 6-10: span2's own off-state then base coalesce — white, not bold: " + d);
        check(runs.size() == 3, "exactly 3 runs (adjacent identical intervals coalesce): " + d);

        // ── Mixed-state queries ────────────────────────────────────────────────────────────────
        check(TextStyleResolver.overRange(base, spans, BOLD, 0, 4, 10) == null,
                "overRange [0,4) BOLD = mixed (true then false)");
        check(TextStyleResolver.overRange(base, spans, BOLD, 0, 2, 10) == Boolean.TRUE,
                "overRange [0,2) BOLD = uniform true");
        check(TextStyleResolver.overRange(base, spans, BOLD, 8, 10, 10) == Boolean.FALSE,
                "overRange [8,10) BOLD = uniform false (base)");
        check(TextStyleResolver.overRange(base, spans, FILL_COLOR, 0, 8, 10) == null,
                "overRange [0,8) FILL = mixed (blue till 6, then base white)");
        check(TextStyleResolver.overRange(base, spans, FILL_COLOR, 0, 3, 10) == null
                        ? false : ((Integer) TextStyleResolver.overRange(base, spans, FILL_COLOR, 0, 3, 10)).intValue() == 0xFF0000FF,
                "overRange [0,3) FILL = uniform blue");
        Set<Object> fams = TextStyleResolver.valuesOverRange(base, spans, FONT_FAMILY, 0, 10, 10);
        check(fams.size() == 1 && fams.contains("popular"),
                "valuesOverRange FONT_FAMILY over whole text = {popular} only");
        Set<Object> cases = TextStyleResolver.valuesOverRange(base, spans, TEXT_CASE, 0, 4, 10);
        check(cases.size() == 2 && cases.contains(TextStyleResolver.CASE_ALL_CAPS)
                        && cases.contains(TextStyleResolver.CASE_NONE),
                "valuesOverRange TEXT_CASE over mixed [0,4) = {ALL_CAPS, NONE}");
        check(TextStyleResolver.valuesOverRange(base, spans, BOLD, 10, 12, 10).isEmpty(),
                "valuesOverRange out of bounds = empty set");

        // ── Range writes: one choice overriding ────────────────────────────────────────────────
        List<StyleSpan> w = new ArrayList<>();
        StyleSpan a1 = new StyleSpan(0, 10);
        a1.fillColor = 0xFFFF0000;
        w.add(a1);
        TextStyleResolver.apply(w, BOLD, true, 2, 8);
        check(w.size() == 2 && w.get(1).start == 2 && w.get(1).end == 8
                        && w.get(1).bold == true && w.get(1).fillColor == null,
                "apply bold on [2,8) appends a bold-only span; original span untouched");

        // Same-range second write merges into the existing span, no growth.
        TextStyleResolver.apply(w, BOLD, false, 2, 8);
        check(w.size() == 2 && w.get(1).bold == false, "apply again on same range mutates in place");

        // Overwriting a single-property span prunes the dead one.
        TextStyleResolver.apply(w, BOLD, true, 1, 9);
        check(w.size() == 2 && w.get(1).start == 1 && w.get(1).end == 9,
                "apply on wider range prunes the now-dead bold span (list stays 2)");

        // A span carrying a second property must survive a wider apply of one of them.
        StyleSpan keeper = new StyleSpan(3, 5);
        keeper.bold = true;
        keeper.italic = true;
        w.add(keeper);
        TextStyleResolver.apply(w, BOLD, false, 0, 10);
        check(w.size() == 2, "apply BOLD over everything: bold-only span pruned, bold+italic "
                + "survives, whole-range span reused (size 2)");
        check(TextStyleResolver.at(base, w, ITALIC, 4, 10) == Boolean.TRUE,
                "pruned-or-not, italic override still resolves at char 4");
        check(TextStyleResolver.at(base, w, BOLD, 4, 10) == Boolean.FALSE,
                "the whole-range bold=false set is LAST and wins at char 4");
        check(TextStyleResolver.at(base, w, FILL_COLOR, 7, 10) == null
                        ? false : ((Integer) TextStyleResolver.at(base, w, FILL_COLOR, 7, 10)).intValue() == 0xFFFF0000,
                "the reused whole-range span still carries its fill at char 7");
        w.clear();

        // ── clearRange splits remnants, keeps outside styles ───────────────────────────────────
        StyleSpan c1 = new StyleSpan(0, 10);
        c1.bold = true;
        c1.fillColor = 0xFF00FF00;
        StyleSpan c2 = new StyleSpan(4, 6);
        c2.italic = true;
        w.add(c1);
        w.add(c2);
        TextStyleResolver.clearRange(w, 2, 8);
        check(w.size() == 2, "clearRange [2,8) splits the crossing span in two; the span fully "
                + "inside the range disappears (size 2)");
        check(w.get(0).start == 0 && w.get(0).end == 2 && w.get(0).bold
                        && ((Integer) w.get(0).fillColor).intValue() == 0xFF00FF00,
                "left remnant keeps fill+bold on [0,2)");
        check(w.get(1).start == 8 && w.get(1).end == 10 && w.get(1).bold
                        && ((Integer) w.get(1).fillColor).intValue() == 0xFF00FF00
                        && w.get(1).italic == null && w.get(1).italic != Boolean.TRUE,
                "right remnant keeps bold+fill on [8,10), no italic leak from the cleared zone");
        check(TextStyleResolver.at(base, w, ITALIC, 4, 10) == Boolean.FALSE,
                "italic inside the cleared range falls back to base");
        check(TextStyleResolver.at(base, w, FILL_COLOR, 3, 10) == null
                        ? false : ((Integer) TextStyleResolver.at(base, w, FILL_COLOR, 3, 10)).intValue() == 0xFFFFFFFF,
                "fill inside the cleared range falls back to base");
        w.clear();

        // ── adjustForEdit ──────────────────────────────────────────────────────────────────────
        StyleSpan e1 = new StyleSpan(2, 6);
        e1.bold = true;
        List<StyleSpan> e = new ArrayList<>(Arrays.asList(e1));
        TextStyleResolver.adjustForEdit(e, 4, 0, 2);            // insert 2 chars at 4
        check(e.size() == 1 && e.get(0).start == 2 && e.get(0).end == 8,
                "insert INSIDE a span extends it: [2,6) +2 at 4 → [2,8)");
        TextStyleResolver.adjustForEdit(e, 1, 0, 1);            // insert 1 char at 1
        check(e.size() == 1 && e.get(0).start == 3 && e.get(0).end == 9,
                "insert BEFORE a span shifts it (exclusive start edge): [2,8) +1 at 1 → [3,9)");
        TextStyleResolver.adjustForEdit(e, 2, 0, 1);            // insert 1 char at its start
        check(e.size() == 1 && e.get(0).start == 4 && e.get(0).end == 10,
                "insert exactly AT a span's start does not join it: [3,9) +1 at 2 → [4,10)");
        TextStyleResolver.adjustForEdit(e, 3, 2, 0);            // delete 2 chars at 3
        check(e.get(0).start == 3 && e.get(0).end == 8,
                "delete INSIDE a span shrinks it: [4,10) −2 at 3 → [3,8)");
        TextStyleResolver.adjustForEdit(e, 0, 2, 3);            // replace [0,2) with 3 chars
        check(e.get(0).start == 4 && e.get(0).end == 9,
                "replace before the span shifts it: +1 net → [4,9)");
        TextStyleResolver.adjustForEdit(e, 0, 9, 0);            // delete a range covering it all
        check(e.isEmpty(), "delete removing the span entirely drops it");
        e.add(new StyleSpan(3, 5));                              // [3,5) fully inside a replaced zone
        TextStyleResolver.adjustForEdit(e, 3, 5, 1);             // replace [3,8) with 1 char
        check(e.size() == 1 && e.get(0).start == 3 && e.get(0).end == 4,
                "replacement text inherits the deleted span's range: [3,5) → [3,4)");
        e.clear();

        // ── normalize ──────────────────────────────────────────────────────────────────────────
        StyleSpan n1 = new StyleSpan(0, 5);
        n1.bold = true;
        StyleSpan n2 = new StyleSpan(-1, 99);                    // out of range
        n2.italic = true;
        StyleSpan n3 = new StyleSpan();                          // noop
        List<StyleSpan> n = new ArrayList<>(Arrays.asList(n1, n2, n3));
        TextStyleResolver.normalize(n, 10);
        check(n.size() == 2 && n.get(1).start == 0 && n.get(1).end == 10,
                "normalize clamps out-of-range spans and drops noops");
        n.clear();

        // ── applyCaseToRange ──────────────────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        TextStyleResolver.applyCaseToRange(sb, "hello world", 0, 11,
                TextStyleResolver.CASE_ALL_CAPS, true);
        check("HELLO WORLD".equals(sb.toString()), "ALL_CAPS per char, length preserved: 'HELLO WORLD'");
        sb.setLength(0);
        TextStyleResolver.applyCaseToRange(sb, "mix it", 2, 5,
                TextStyleResolver.CASE_CAPITALIZE_FIRST, false);
        check("x I".equals(sb.toString()), "CAPITALIZE_FIRST across a run boundary: 'x I'");
        sb.setLength(0);
        TextStyleResolver.applyCaseToRange(sb, "mix it", 2, 5,
                TextStyleResolver.CASE_CAPITALIZE_FIRST, true);
        check("X I".equals(sb.toString()), "CAPITALIZE_FIRST with run at word start: 'X I'");
        sb.setLength(0);
        TextStyleResolver.applyCaseToRange(sb, "ß", 0, 1, TextStyleResolver.CASE_ALL_CAPS, true);
        check("ß".equals(sb.toString()),
                "ALL_CAPS keeps length for ß (no locale expansion — span offsets stay valid)");
        sb.setLength(0);
        TextStyleResolver.applyCaseToRange(sb, "abc", 0, 3, TextStyleResolver.CASE_NONE, true);
        check("abc".equals(sb.toString()), "CASE_NONE is identity");

        // ── sparse JSON round-trip ─────────────────────────────────────────────────────────────
        StyleSpan j1 = new StyleSpan(0, 5);
        j1.bold = true;
        j1.fillColor = 0xFF123456;
        StyleSpan j2 = new StyleSpan(5, 10);
        j2.fontFamily = "serif";
        j2.textCase = TextStyleResolver.CASE_ALL_CAPS;
        j2.underline = false;
        JsonObject o1 = new JsonObject();
        StyleSpan.toJson(j1, o1);
        JsonObject o2 = new JsonObject();
        StyleSpan.toJson(j2, o2);
        check(o1.has("s") && o1.has("e") && o1.has("b") && o1.has("tc")
                        && !o1.has("f") && !o1.has("i") && !o1.has("u") && !o1.has("c")
                        && !o1.has("oc") && !o1.has("gc") && !o1.has("hc") && !o1.has("bg"),
                "JSON writes only the set overrides (sparse)");
        StyleSpan r1 = StyleSpan.fromJson(o1);
        StyleSpan r2 = StyleSpan.fromJson(o2);
        check(j1.equals(r1) && j2.equals(r2), "JSON round-trips spans exactly");
        check(j1.hashCode() == r1.hashCode(), "hashCode survives the round-trip");
        JsonObject malformed = new JsonObject();
        StyleSpan bad = StyleSpan.fromJson(malformed);
        check(bad.start == 0 && bad.end == 0 && bad.isNoop(), "malformed JSON reads as empty span");

        // spansEqual — the drawer's undo gate
        List<StyleSpan> x1 = new ArrayList<>();
        x1.add(j1);
        List<StyleSpan> x2 = new ArrayList<>();
        x2.add(r1);
        check(TextStyleResolver.spansEqual(x1, x2), "spansEqual across a JSON round-trip");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}