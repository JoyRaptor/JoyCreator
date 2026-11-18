package com.fadcam.ui.faditor.ai.actions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * do_trick (BOT-7, JoybotActions): the model's way to Joybot's tricks. It names a trick, plays nothing itself, and its
 * list is the director's own. Run by run-actions.sh, in the actions package so it can reach the family.
 */
public final class JoybotActionsTest {
    private static int pass, fail;

    private static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what); }
        else { fail++; System.out.println("FAIL  " + what + "  [" + detail + "]"); }
    }

    public static void main(String[] argv) throws Exception {
        ActionRegistry<AppCtx> reg = new ActionRegistry<>();
        JoybotActions.register(reg);
        ActionSpec spec = reg.spec("do_trick");
        check("do_trick is declared", spec != null, "");
        check("do_trick only reads (no Undo row, no snapshot)", spec != null && spec.effect == ActionSpec.Effect.READ, "");
        String schema = spec == null ? "" : spec.schemaJson();
        check("schema lists every trick", schema.contains("\"enum\":[\"sleep\",\"hop\",\"backflip\",\"beg\",\"roll-over\",\"shake\"]"), schema);

        AppCtx ctx = new AppCtx(new android.content.Context(), null);
        Map<String, Object> args = new HashMap<>();
        args.put("name", "backflip");
        ActionResult r = reg.run(ctx, "do_trick", args);
        check("do_trick names the trick for the chat", "backflip".equals(ctx.trick), String.valueOf(ctx.trick));
        check("do_trick changes nothing", !r.changed, r.toToolText());
        check("do_trick doesn't open a room", ctx.route == null, String.valueOf(ctx.route));

        AppCtx bad = new AppCtx(new android.content.Context(), null);
        args.put("name", "moonwalk");
        boolean threw = false;
        try { reg.run(bad, "do_trick", args); } catch (ActionError e) { threw = true; }
        check("a trick he doesn't know is refused", threw && bad.trick == null, String.valueOf(bad.trick));

        // The list must be JoybotDirector.TRICKS, which is an Android class: read it from its source.
        String src = new String(Files.readAllBytes(Paths.get("app/src/main/java/com/fadcam/ui/lobby/JoybotDirector.java")),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("String\\[\\] TRICKS = \\{([^}]*)\\}").matcher(src);
        String director = m.find() ? m.group(1).replace("\"", "").replaceAll("\\s", "") : "(not found)";
        check("the tricks are the director's own", director.equals(String.join(",", JoybotActions.TRICKS)), director);

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
