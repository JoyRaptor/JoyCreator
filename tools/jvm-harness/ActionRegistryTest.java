import com.fadcam.ui.faditor.ai.actions.ActionError;
import com.fadcam.ui.faditor.ai.actions.ActionRegistry;
import com.fadcam.ui.faditor.ai.actions.ActionResult;
import com.fadcam.ui.faditor.ai.actions.ActionSpec;
import com.fadcam.ui.faditor.ai.actions.Args;
import com.fadcam.ui.faditor.ai.joybot.ToolDocs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one list of actions (gap F2): spec schema, argument checks, Args getters, registry
 * dispatch, the docs text and the result text. Pure Java, off device.
 *
 * Run: bash tools/jvm-harness/run-actions.sh
 */
public class ActionRegistryTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what); }
        else { fail++; System.out.println("FAIL  " + what + "   " + detail); }
    }

    static void eq(String what, Object want, Object got) {
        check(what, String.valueOf(want).equals(String.valueOf(got)), "want=" + want + " got=" + got);
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String joinAll(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) sb.append(sb.length() > 0 ? " | " : "").append(s);
        return sb.toString();
    }

    static boolean balanced(String s) {
        int curly = 0, square = 0;
        for (char ch : s.toCharArray()) {
            if (ch == '{') curly++;
            else if (ch == '}') curly--;
            else if (ch == '[') square++;
            else if (ch == ']') square--;
            if (curly < 0 || square < 0) return false;
        }
        return curly == 0 && square == 0;
    }

    /** A spec with every param kind the schema has to express. */
    static ActionSpec styleText() {
        return ActionSpec.of("style_text", "Style text")
                .help("Changes how a text layer looks.")
                .wing("STUDIO", "text")
                .id("text", "text", true, "which text layer")
                .choice("font", false, "typeface", Arrays.asList("serif", "mono"))
                .number("size", false, "size", 0.0, 10.0)
                .color("color", false, "colour")
                .flag("bold", false, "bold or not")
                .build();
    }

    /** A number with no limits, so a large value is legal. */
    static ActionSpec speedUp() {
        return ActionSpec.of("speed_up", "Speed up")
                .help("Plays a clip faster.")
                .number("speed", false, "how fast", null, null)
                .build();
    }

    static ActionSpec repeat() {
        return ActionSpec.of("repeat_clip", "Repeat clip")
                .help("Repeats a clip.")
                .param("count", ActionSpec.Type.INT, false, "how many times")
                .build();
    }

    public static void main(String[] argv) {
        ActionSpec style = styleText();

        // ── ActionSpec: names and schema ─────────────────────────────────────────
        boolean threw = false;
        try { ActionSpec.of("Bad-Name", "x"); } catch (IllegalArgumentException e) { threw = true; }
        check("ActionSpec.of rejects tool name 'Bad-Name'", threw, "no IllegalArgumentException");

        String schema = style.schemaJson();
        check("schema: required is [\"text\"]", schema.contains("\"required\":[\"text\"]"), schema);
        check("schema: choice enum is [\"serif\",\"mono\"]", schema.contains("\"enum\":[\"serif\",\"mono\"]"), schema);
        check("schema: number has \"minimum\":0", schema.contains("\"minimum\":0"), schema);
        check("schema: number has \"maximum\":10", schema.contains("\"maximum\":10"), schema);
        check("schema: additionalProperties false", schema.contains("\"additionalProperties\":false"), schema);
        check("schema: braces and brackets balanced", balanced(schema), schema);
        check("schema: openArgs() gives additionalProperties true",
                ActionSpec.of("free_form", "Free form").text("text", true, "words").openArgs().build()
                        .schemaJson().contains("\"additionalProperties\":true"), "");

        // ── ActionSpec.check: what the model sent ────────────────────────────────
        List<String> p = style.check(args());
        check("check: missing required mentions the param name",
                p.size() == 1 && p.get(0).startsWith("text is required"), joinAll(p));

        check("check: enum SERIF accepted (case-insensitive)",
                style.check(args("text", "Hi", "font", "SERIF")).isEmpty(), joinAll(style.check(args("text", "Hi", "font", "SERIF"))));

        p = style.check(args("text", "Hi", "font", "comic"));
        check("check: bad enum lists the allowed values",
                p.size() == 1 && p.get(0).contains("serif") && p.get(0).contains("mono"), joinAll(p));

        check("check: colour #FFD700 accepted", style.check(args("text", "Hi", "color", "#FFD700")).isEmpty(),
                joinAll(style.check(args("text", "Hi", "color", "#FFD700"))));
        check("check: colour #FFD70080 accepted", style.check(args("text", "Hi", "color", "#FFD70080")).isEmpty(),
                joinAll(style.check(args("text", "Hi", "color", "#FFD70080"))));
        p = style.check(args("text", "Hi", "color", "gold"));
        check("check: colour 'gold' is an error", p.size() == 1 && p.get(0).contains("colour"), joinAll(p));

        check("check: number sent as string \"2000\" accepted",
                speedUp().check(args("speed", "2000")).isEmpty(), joinAll(speedUp().check(args("speed", "2000"))));

        p = style.check(args("text", "Hi", "size", 11));
        check("check: number above max says \"at most\"",
                p.size() == 1 && p.get(0).contains("at most"), joinAll(p));

        p = repeat().check(args("count", 1.5));
        check("check: INT 1.5 says \"whole number\"", p.size() == 1 && p.get(0).contains("whole number"), joinAll(p));

        p = style.check(args("text", "Hi", "nope", 1));
        check("check: unknown argument says \"unknown argument\"",
                p.size() == 1 && p.get(0).contains("unknown argument"), joinAll(p));

        check("check: openArgs spec allows unknown arguments",
                ActionSpec.of("free_form", "Free form").text("text", true, "words").openArgs().build()
                        .check(args("text", "a", "anything", 1)).isEmpty(), "");

        // ── Args getters ─────────────────────────────────────────────────────────
        Integer c1 = new Args(args("c", "#FF0000")).color("c");
        check("Args.color #FF0000 == 0xFFFF0000", c1 != null && c1 == 0xFFFF0000, "got " + c1);
        Integer c2 = new Args(args("c", "#80FF0000")).color("c");
        check("Args.color #80FF0000 == 0x80FF0000", c2 != null && c2 == 0x80FF0000, "got " + c2);
        Double n1 = new Args(args("n", "2.5")).num("n");
        check("Args.num \"2.5\" == 2.5", n1 != null && n1 == 2.5, "got " + n1);
        Long w1 = new Args(args("n", 2.4)).whole("n");
        Long w2 = new Args(args("n", 2.6)).whole("n");
        check("Args.whole rounds 2.4 down and 2.6 up", w1 != null && w1 == 2L && w2 != null && w2 == 3L,
                "got " + w1 + ", " + w2);
        Boolean b1 = new Args(args("b", "true")).bool("b");
        check("Args.bool \"true\" == true", b1 != null && b1, "got " + b1);
        List<String> l1 = new Args(args("x", "clip-1")).list("x");
        check("Args.list of a single string is a one-item list", l1.size() == 1 && "clip-1".equals(l1.get(0)),
                joinAll(l1));
        check("Args.has false for empty string", !new Args(args("e", "")).has("e"), "");
        check("Args.has false for missing key", !new Args(args()).has("missing"), "");
        eq("Args.choice returns the spec's spelling", "mono",
                new Args(args("f", "MONO")).choice("f", Arrays.asList("serif", "mono")));

        // ── ActionRegistry ───────────────────────────────────────────────────────
        ActionRegistry<Object> reg = new ActionRegistry<>();
        final List<String> seen = new ArrayList<>();
        ActionRegistry.Handler<Object> styler = (ctx, a) -> {
            seen.add(a.str("text"));
            seen.add(a.choice("font", Arrays.asList("serif", "mono")));
            return ActionResult.changed("styled " + a.str("text"), Arrays.asList("t1"));
        };
        reg.add(style, styler).add(speedUp(), (ctx, a) -> ActionResult.unchanged("fast"));

        threw = false;
        try { reg.add(style, styler); } catch (IllegalStateException e) { threw = true; }
        check("registry: adding the same tool twice throws IllegalStateException", threw, "");

        threw = false;
        try { reg.run("ctx", "no_such_tool", args()); }
        catch (ActionError e) { threw = e.getMessage() != null && e.getMessage().contains("no_such_tool"); }
        catch (Exception e) { threw = false; }
        check("registry: run() of an unknown tool gives ActionError", threw, "");

        threw = false;
        String badMsg = "";
        try { reg.run("ctx", "style_text", args("font", "comic")); }
        catch (ActionError e) { badMsg = e.getMessage(); threw = true; }
        catch (Exception e) { threw = false; }
        check("registry: bad args give one ActionError listing every problem joined by \"; \"",
                threw && badMsg.contains("text is required") && badMsg.contains("must be one of")
                        && badMsg.contains("; "), badMsg);

        try {
            ActionResult r = reg.run("ctx", "style_text", args("text", "Hi", "font", "SERIF"));
            check("registry: good args reach the handler and its result comes back",
                    r.changed && "styled Hi".equals(r.summary) && r.touchedIds.size() == 1,
                    "summary=" + r.summary);
            check("registry: handler received Args (text and normalised font)",
                    seen.size() == 2 && "Hi".equals(seen.get(0)) && "serif".equals(seen.get(1)),
                    joinAll(seen));
        } catch (ActionError e) {
            check("registry: good args reach the handler and its result comes back", false, e.getMessage());
            check("registry: handler received Args (text and normalised font)", false, "");
        }

        List<String> nj = reg.nativeToolsJson();
        check("nativeToolsJson: two entries", nj.size() == 2, "size=" + nj.size());
        check("nativeToolsJson: entry starts with {\"type\":\"function\"",
                nj.get(0).startsWith("{\"type\":\"function\""), nj.get(0));
        check("nativeToolsJson: entry names the tool",
                nj.get(0).contains("\"name\":\"style_text\""), nj.get(0));

        String docs = reg.docsText(100);
        check("docsText(100) numbers the first entry \"100. \"", docs.contains("100. "), docs);
        check("docsText(100) numbers the second entry \"101. \"", docs.contains("101. "), docs);

        List<ToolDocs.Doc> parsed = ToolDocs.parse(docs);
        List<String> names = new ArrayList<>();
        for (ToolDocs.Doc d : parsed) names.add(d.name);
        eq("ToolDocs.parse(docsText) returns the tool names in order", "[style_text, speed_up]", names);

        // ── descriptions ─────────────────────────────────────────────────────────
        String destructive = ActionSpec.of("remove_all", "Remove all").help("Removes every clip.")
                .effect(ActionSpec.Effect.DESTRUCTIVE).build().description();
        check("description: DESTRUCTIVE says \"propose it first\"", destructive.contains("propose it first"), destructive);
        String paid = ActionSpec.of("buy_music", "Buy music").help("Buys a track.")
                .effect(ActionSpec.Effect.PAID).build().description();
        check("description: PAID says \"price\"", paid.contains("price"), paid);

        // ── ActionResult text ────────────────────────────────────────────────────
        eq("toToolText with no detail is the summary alone", "Done.",
                ActionResult.changed("Done.", Arrays.asList("a")).toToolText());
        eq("toToolText with detail is summary, newline, detail", "Done.\nMore.",
                ActionResult.changed("Done.", Arrays.asList("a")).withDetail("More.").toToolText());

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
