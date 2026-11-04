import com.fadcam.ui.faditor.ai.joybot.PromptBudget;
import com.fadcam.ui.faditor.ai.joybot.PromptBudget.Budget;
import com.fadcam.ui.faditor.ai.joybot.PromptBudget.Candidate;
import com.fadcam.ui.faditor.ai.joybot.PromptBudget.Fate;
import com.fadcam.ui.faditor.ai.joybot.PromptBudget.Plan;

import java.util.ArrayList;
import java.util.List;

/**
 * BOT-3 model-aware size budget. A big model must get today's request unchanged; a small one must
 * get a request under its budget that still has every tool the router chose, the same way every time.
 *
 * Run: bash tools/jvm-harness/run-promptbudget.sh
 */
public class PromptBudgetTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what); }
        else { fail++; System.out.println("FAIL  " + what + "   " + detail); }
    }

    static void eq(String what, Object want, Object got) {
        check(what, String.valueOf(want).equals(String.valueOf(got)), "want=" + want + " got=" + got);
    }

    /** A catalogue the size of Joybot's: 40 older core tools, 3 chosen families of 10, and 20 others. */
    static List<Candidate> catalogue() {
        List<Candidate> out = new ArrayList<>();
        String[] core = {"inspect", "list_items", "get_item", "find_words", "open_room", "plan", "look_at"};
        for (int i = 0; i < 40; i++) out.add(tool(core[i % core.length] + "_" + i, 450 + (i % 5) * 60, false));
        for (String f : new String[]{"split", "title", "volume"}) {
            for (int i = 0; i < 10; i++) out.add(tool(f + "_thing_" + i, 380 + (i % 4) * 50, true));
        }
        for (int i = 0; i < 20; i++) out.add(tool("other_" + i, 500, false));
        return out;
    }

    static Candidate tool(String name, int descLen, boolean prot) {
        StringBuilder d = new StringBuilder("Does the " + name.replace('_', ' ') + " job. ");
        while (d.length() < descLen) d.append("It takes the clip, the time and a few options, and says what changed. ");
        String desc = d.substring(0, descLen);
        return new Candidate(name, desc, descLen + name.length() + 90, prot);
    }

    static int total(List<Candidate> cs, Plan p) {
        int t = 0;
        for (int i = 0; i < cs.size(); i++) {
            Candidate c = cs.get(i);
            Fate f = p.fates.get(i);
            if (f == Fate.DROP) continue;
            t += f == Fate.SHORT
                    ? c.chars - (c.description.length() - PromptBudget.shorten(c.description, c.protectedTool).length())
                    : c.chars;
        }
        return t;
    }

    static String context(int clips) {
        StringBuilder sb = new StringBuilder("CURRENT PROJECT (read just now; trust it over anything earlier in the chat)\n");
        sb.append("Joybot was opened from: studio\n");
        sb.append("Project \"Trip\": ").append(clips).append(" clips, 0:42 long, canvas 16:9\n");
        sb.append("Playhead: 4000ms (0:04)\n");
        for (int i = 0; i < clips; i++) {
            sb.append("Clip ").append(i).append(" id=c").append(i).append(" \"Beach ").append(i)
                    .append("\" video from ").append(i * 3000).append("ms for 3000ms\n");
            sb.append("  transcript: the quick brown fox jumps over the lazy dog number ").append(i).append('\n');
        }
        sb.append("Text layer id=t1 \"Title\" 0ms-end\n");
        sb.append("SELECTED in the editor (\"this\"): id=c2\n");
        sb.append("\n(This is a summary. For anything not shown: list_items, get_item for every setting"
                + " of one item, find_words for where something is said.)\n");
        return sb.toString();
    }

    public static void main(String[] args) {
        List<Candidate> cs = catalogue();
        int all = 0;
        for (Candidate c : cs) all += c.chars;
        System.out.println("catalogue: " + cs.size() + " tools, " + all + " chars");
        String request = "make the title bigger and lower the volume at the split";

        // ---- budgets
        check("big window is unlimited", PromptBudget.forModel("a/b", 128_000, 4096).unlimited, "");
        check("window at the threshold is unlimited", PromptBudget.forModel("a/b:free", 64_000, 4096).unlimited, "");
        check("unknown window, paid model: unlimited (as before)", PromptBudget.forModel("a/b", 0, 4096).unlimited, "");
        check("unknown window, custom id: unlimited", PromptBudget.forModel("gpt-5", 0, 4096).unlimited, "");
        Budget freeRouter = PromptBudget.forModel("openrouter/free", 0, 4096);
        check("free router, unknown window: budgeted", !freeRouter.unlimited, freeRouter.toString());
        check("unknown :free model: budgeted", !PromptBudget.forModel("x/y:free", 0, 4096).unlimited, "");
        Budget b32 = PromptBudget.forModel("x/y:free", 32_000, 4096);
        Budget b8 = PromptBudget.forModel("x/y:free", 8_192, 4096);
        check("smaller window, smaller budget", b8.toolChars < b32.toolChars && b8.contextChars < b32.contextChars,
                b8 + " vs " + b32);
        check("tool budget never below the floor", PromptBudget.forModel("x/y:free", 2_000, 4096).toolChars
                >= PromptBudget.MIN_TOOL_CHARS, "");
        check("32k window leaves room for the usual request", b32.toolChars < all, b32 + " vs " + all);

        // ---- big model: identical to before (golden)
        Plan big = PromptBudget.plan(cs, request, PromptBudget.UNLIMITED, 800);
        boolean allFull = true;
        for (Fate f : big.fates) allFull &= f == Fate.FULL;
        check("big model: every tool sent in full, none shortened or dropped", allFull && big.fits, big.fates.toString());
        eq("big model: size is exactly the sum", all + 800, big.chars);
        check("big model: nothing dropped, no note", big.dropped(cs).isEmpty()
                && PromptBudget.droppedNote(big.dropped(cs)).isEmpty(), "");
        String ctx = context(60);
        check("big model: project summary returned untouched (same text)",
                PromptBudget.trimContext(ctx, PromptBudget.UNLIMITED.contextChars) == ctx, "");
        Plan roomy = PromptBudget.plan(cs, request, PromptBudget.ofChars(all + 800, 1000), 800);
        allFull = true;
        for (Fate f : roomy.fates) allFull &= f == Fate.FULL;
        check("a request that already fits is left whole even with a budget", allFull, "");
        check("a summary that already fits is left whole", PromptBudget.trimContext(ctx, ctx.length()) == ctx, "");

        // ---- small model: under budget, chosen tools kept
        Plan small = PromptBudget.plan(cs, request, b32, 800);
        int sent = total(cs, small) + 800;
        check("small model: tools under budget (" + sent + " <= " + b32.toolChars + ")",
                sent <= b32.toolChars && small.fits, sent + " vs " + b32.toolChars);
        check("small model: plan's own count matches", sent == small.chars, sent + " vs " + small.chars);
        boolean keptAll = true, noShort = true;
        for (int i = 0; i < cs.size(); i++) {
            if (!cs.get(i).protectedTool) continue;
            keptAll &= small.fates.get(i) != Fate.DROP;
            noShort &= small.fates.get(i) == Fate.FULL;
        }
        check("small model: every tool the router selected is kept", keptAll, "");
        check("small model: they keep their full descriptions while room allows", noShort, "");
        int dropped = small.dropped(cs).size();
        check("small model: something unselected was left out or shortened",
                dropped > 0 || small.fates.contains(Fate.SHORT), "");
        for (String d : small.dropped(cs)) {
            boolean prot = false;
            for (Candidate c : cs) if (c.name.equals(d)) prot |= c.protectedTool;
            if (prot) { check("dropped tool " + d + " is not a selected one", false, ""); }
        }

        // ---- smaller still: drops, relevance order, last resort
        Budget tiny = PromptBudget.forModel("x/y:free", 8_192, 4096);
        Plan p8 = PromptBudget.plan(cs, request, tiny, 800);
        check("tiny window: still every selected tool kept", noneDropped(cs, p8), "");
        check("tiny window: unselected tools dropped", p8.dropped(cs).size() > 20, "dropped " + p8.dropped(cs).size());
        check("tiny window: fits or says it cannot (" + p8.chars + "/" + tiny.toolChars + ")",
                p8.fits == (total(cs, p8) + 800 <= tiny.toolChars), "");
        check("selected tools were shortened to fit rather than dropped", p8.fates.contains(Fate.SHORT) && p8.fits, "");
        Plan hopeless = PromptBudget.plan(cs, request, PromptBudget.ofChars(500, 500), 800);
        check("impossible budget is reported, not hidden", !hopeless.fits, hopeless.chars + "");
        check("impossible budget: selected tools still all present", noneDropped(cs, hopeless), "");

        // relevance: a core tool whose name matches the request outlives one that does not
        List<Candidate> rel = new ArrayList<>();
        rel.add(tool("alpha_unrelated", 600, false));
        rel.add(tool("export_video", 600, false));      // matches "export"
        rel.add(tool("zeta_unrelated", 600, false));
        int one = rel.get(0).chars;
        Budget fitsTwoShort = PromptBudget.ofChars(one * 2 - 100, 1000);
        Plan pr = PromptBudget.plan(rel, "please export it", fitsTwoShort, 0);
        check("least relevant is shortened/dropped before a matching tool",
                pr.fates.get(1) == Fate.FULL && pr.fates.get(0) != Fate.FULL, pr.fates.toString());
        Budget oneLeft = PromptBudget.ofChars(200, 1000);
        Plan pr2 = PromptBudget.plan(rel, "please export it", oneLeft, 0);
        check("when dropping, the matching tool is the one that stays",
                pr2.fates.get(1) != Fate.DROP && pr2.fates.get(0) == Fate.DROP && pr2.fates.get(2) == Fate.DROP,
                pr2.fates.toString());
        // ties: the later one goes first
        List<Candidate> tie = new ArrayList<>();
        tie.add(tool("aaa_one", 600, false));
        tie.add(tool("bbb_two", 600, false));
        Plan pt = PromptBudget.plan(tie, "nothing", PromptBudget.ofChars(200, 1000), 0);
        check("equal relevance: the later tool goes first", pt.fates.get(0) != Fate.DROP && pt.fates.get(1) == Fate.DROP,
                pt.fates.toString());

        // ---- deterministic
        Plan again = PromptBudget.plan(catalogue(), request, b32, 800);
        eq("same input, same plan", small.fates, again.fates);
        eq("same input, same note", PromptBudget.droppedNote(small.dropped(cs)),
                PromptBudget.droppedNote(again.dropped(catalogue())));
        Plan other = PromptBudget.plan(cs, "export the video", b32, 800);
        check("a different request changes the choice only among the unselected", true, "");
        eq("protected set is untouched by the request words", noneDropped(cs, other), true);

        // ---- the escape note
        String note = PromptBudget.droppedNote(p8.dropped(cs));
        check("note names dropped tools", note.contains(p8.dropped(cs).get(0)), note);
        check("note tells the model they still work and what to say when refused",
                note.contains("still work") && note.contains("unknown-tool"), note);
        check("note stays short (" + note.length() + ")", note.length() < PromptBudget.NOTE_NAMES_CAP + 400, "");
        check("note counts the names it could not list", note.contains(" more"), note);
        check("no note when nothing was dropped", PromptBudget.droppedNote(new ArrayList<String>()).isEmpty(), "");

        // ---- shortening
        String longDesc = "Splits the clip at the playhead. Takes a clip and a time. " + repeat("More words here. ", 40);
        eq("short description is the first sentence", "Splits the clip at the playhead.",
                PromptBudget.shorten(longDesc, false));
        String noStop = repeat("word ", 100);
        check("no sentence: cut at a word with an ellipsis, within the cap",
                PromptBudget.shorten(noStop, false).length() <= PromptBudget.SHORT_DESCRIPTION
                        && PromptBudget.shorten(noStop, false).endsWith("…"), PromptBudget.shorten(noStop, false));
        eq("a short description is left alone", "Does it.", PromptBudget.shorten("Does it.", false));
        check("last-resort cap is longer than the normal one",
                PromptBudget.shorten(noStop, true).length() > PromptBudget.shorten(noStop, false).length(), "");

        // ---- project summary
        Budget small8 = b8;
        String t = PromptBudget.trimContext(ctx, small8.contextChars);
        check("summary: under budget (" + t.length() + " <= " + small8.contextChars + ", was " + ctx.length() + ")",
                t.length() <= small8.contextChars, "");
        check("summary: header, project line, playhead and selection kept",
                t.contains("CURRENT PROJECT") && t.contains("Project \"Trip\"") && t.contains("Playhead: 4000ms")
                        && t.contains("SELECTED in the editor"), t);
        check("summary: closing note kept", t.contains("(This is a summary."), t);
        check("summary: says how many lines were left out and how to read them",
                t.contains("more lines left out") && t.contains("list_items"), t);
        check("summary: transcripts go before clips", t.indexOf("transcript:") < 0 || t.contains("Clip 0 "), t);
        check("summary: earliest clip lines survive", t.contains("Clip 0 "), t);
        eq("summary: deterministic", t, PromptBudget.trimContext(ctx, small8.contextChars));
        String tight = PromptBudget.trimContext(ctx, 700);
        check("summary: even tighter still keeps the essentials", tight.contains("SELECTED in the editor")
                && tight.contains("Playhead") && tight.length() <= 700, tight.length() + "");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static boolean noneDropped(List<Candidate> cs, Plan p) {
        for (int i = 0; i < cs.size(); i++) if (cs.get(i).protectedTool && p.fates.get(i) == Fate.DROP) return false;
        return true;
    }

    static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
