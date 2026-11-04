package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How much of Joybot's request a model can take, and what to leave out when it cannot.
 * Pure Java (tested in PromptBudgetTest, no Android, no JSON).
 *
 * <p>Measured 10-28: a request is about 19,000 characters, most of it tool definitions. A small free
 * model with a short context window cannot read all of that and still answer. So a model whose window
 * is known to be small gets a {@link Budget}: fewer tool definitions, shorter descriptions, a shorter
 * project summary. A model with a big window, or one whose window is unknown, gets {@link
 * Budget#UNLIMITED} and every function here hands back exactly what it was given: the request is the
 * same as before this class existed.</p>
 *
 * <p>Trimming rules, all deterministic (same input, same output):</p>
 * <ol>
 *   <li>A tool in a family the request asked for (or loaded with more_tools) is <b>protected</b>: it is
 *       never dropped. Only the older core tools (reading, looking, planning, rooms) can go.</li>
 *   <li>Candidates are ranked by how many of the request's words their name or description share.
 *       The least relevant go first; among equals the later one in the list goes first.</li>
 *   <li>Order of savings: shorten the others' descriptions, then drop the others, and only if it is
 *       still too big shorten the protected ones' descriptions. The result says whether it fits.</li>
 *   <li>What was dropped is named in one line ({@link #droppedNote}) that tells the model they still
 *       work when called and what to say when one is refused. The model never meets a silent gap.</li>
 * </ol>
 */
public final class PromptBudget {
    private PromptBudget() { }

    /** A window this big or bigger takes the whole request unchanged. */
    public static final int BIG_CONTEXT_TOKENS = 64_000;
    /** When a free model's window is not known (the free router picks any of them), assume this. */
    public static final int FREE_GUESS_TOKENS = 32_000;
    /** Characters per token used for sizing: low on purpose (JSON and symbols cost more tokens). */
    public static final int CHARS_PER_TOKEN = 3;
    /** The smallest room ever given to the tool definitions, so a tiny window still gets a few tools. */
    public static final int MIN_TOOL_CHARS = 3_000;
    /** An unprotected tool's description is cut to this when space is short. */
    public static final int SHORT_DESCRIPTION = 120;
    /** A protected tool's description is cut to this only as the last resort. */
    public static final int LAST_RESORT_DESCRIPTION = 300;
    /** The most dropped names listed in the note. */
    public static final int NOTE_NAMES_CAP = 600;

    /** What a model can take: characters for tool definitions and for the project summary. */
    public static final class Budget {
        public final boolean unlimited;
        public final int toolChars;
        public final int contextChars;

        Budget(boolean unlimited, int toolChars, int contextChars) {
            this.unlimited = unlimited;
            this.toolChars = toolChars;
            this.contextChars = contextChars;
        }

        @Override public String toString() {
            return unlimited ? "unlimited" : "tools " + toolChars + ", context " + contextChars;
        }
    }

    public static final Budget UNLIMITED = new Budget(true, Integer.MAX_VALUE, Integer.MAX_VALUE);

    /** A budget of exact sizes (tests, and any caller that knows better than the window arithmetic). */
    @NonNull
    public static Budget ofChars(int toolChars, int contextChars) {
        return new Budget(false, toolChars, contextChars);
    }

    /**
     * The budget for a model.
     *
     * @param modelId       the model's id (only used to recognise free models with an unknown window)
     * @param contextTokens its context window in tokens, or 0 when unknown
     * @param replyTokens   what is kept free for the reply
     */
    @NonNull
    public static Budget forModel(@Nullable String modelId, int contextTokens, int replyTokens) {
        int ctx = contextTokens;
        if (ctx <= 0) {
            boolean free = modelId != null && (modelId.endsWith(":free") || modelId.equals("openrouter/free"));
            if (!free) return UNLIMITED;   // unknown and not known to be small: behave as before
            ctx = FREE_GUESS_TOKENS;
        }
        if (ctx >= BIG_CONTEXT_TOKENS) return UNLIMITED;
        long usable = Math.max(2_000L, (long) ctx - Math.max(0, replyTokens)) * CHARS_PER_TOKEN;
        // Of the room that is left: tools 40%, project summary 15%, the rest for the manual, history, words.
        int tools = (int) Math.max(MIN_TOOL_CHARS, usable * 40 / 100);
        int context = (int) Math.max(600, usable * 15 / 100);
        return new Budget(false, tools, context);
    }

    // ----------------------------------------------------------------------------- tools

    /** One tool definition as the caller will send it. */
    public static final class Candidate {
        @NonNull public final String name;
        @NonNull public final String description;
        /** Size of the whole definition as sent (JSON included). */
        public final int chars;
        /** True when the router selected this tool's family for this request: never dropped. */
        public final boolean protectedTool;

        public Candidate(@NonNull String name, @NonNull String description, int chars, boolean protectedTool) {
            this.name = name;
            this.description = description;
            this.chars = chars;
            this.protectedTool = protectedTool;
        }
    }

    public enum Fate { FULL, SHORT, DROP }

    /** What to do with each candidate (same order as the input), and how it came out. */
    public static final class Plan {
        @NonNull public final List<Fate> fates;
        /** Estimated size of what will be sent (tools only, plus the fixed part given). */
        public final int chars;
        /** False only when even the last resort could not make it fit (all protected, too big). */
        public final boolean fits;

        Plan(@NonNull List<Fate> fates, int chars, boolean fits) {
            this.fates = fates;
            this.chars = chars;
            this.fits = fits;
        }

        /** Names of the dropped tools, in input order. */
        @NonNull
        public List<String> dropped(@NonNull List<Candidate> input) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < input.size(); i++) if (fates.get(i) == Fate.DROP) out.add(input.get(i).name);
            return out;
        }
    }

    /**
     * Decides which tools go, which go with a short description, and which are dropped.
     *
     * @param fixedChars size of what is always sent besides these (the more_tools definition)
     */
    @NonNull
    public static Plan plan(@NonNull List<Candidate> tools, @NonNull String request, @NonNull Budget budget,
                            int fixedChars) {
        int n = tools.size();
        List<Fate> fates = new ArrayList<>(Collections.nCopies(n, Fate.FULL));
        int total = fixedChars;
        for (Candidate c : tools) total += c.chars;
        if (budget.unlimited || total <= budget.toolChars) return new Plan(fates, total, true);

        Set<String> words = words(request);
        final int[] score = new int[n];
        for (int i = 0; i < n; i++) score[i] = relevance(tools.get(i), words);

        // Least relevant first; among equals the later in the list first.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!tools.get(i).protectedTool) order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                if (score[a] != score[b]) return Integer.compare(score[a], score[b]);
                return Integer.compare(b, a);
            }
        });

        // 1. Shorten the others' descriptions.
        for (int i : order) {
            if (total <= budget.toolChars) break;
            int saved = saving(tools.get(i), SHORT_DESCRIPTION);
            if (saved > 0) { fates.set(i, Fate.SHORT); total -= saved; }
        }
        // 2. Drop the others.
        for (int i : order) {
            if (total <= budget.toolChars) break;
            total -= size(tools.get(i), fates.get(i));
            fates.set(i, Fate.DROP);
        }
        // 3. Last resort: shorten the protected ones, later ones first.
        for (int i = n - 1; i >= 0 && total > budget.toolChars; i--) {
            Candidate c = tools.get(i);
            if (!c.protectedTool) continue;
            int saved = saving(c, LAST_RESORT_DESCRIPTION);
            if (saved > 0) { fates.set(i, Fate.SHORT); total -= saved; }
        }
        return new Plan(fates, total, total <= budget.toolChars);
    }

    /** The description a SHORT tool is sent with (the protected last resort uses its own, longer cap). */
    @NonNull
    public static String shorten(@NonNull String description, boolean protectedTool) {
        return cut(description, protectedTool ? LAST_RESORT_DESCRIPTION : SHORT_DESCRIPTION);
    }

    private static int size(@NonNull Candidate c, @NonNull Fate fate) {
        if (fate == Fate.SHORT) return c.chars - saving(c, c.protectedTool ? LAST_RESORT_DESCRIPTION : SHORT_DESCRIPTION);
        return c.chars;
    }

    private static int saving(@NonNull Candidate c, int cap) {
        return Math.max(0, c.description.length() - cut(c.description, cap).length());
    }

    /** First sentence or line if it fits, else cut at a word with an ellipsis. Never longer than {@code cap}. */
    @NonNull
    static String cut(@NonNull String d, int cap) {
        String t = d.trim();
        if (t.length() <= cap) return t;
        int nl = t.indexOf('\n');
        int dot = t.indexOf(". ");
        int end = Integer.MAX_VALUE;
        if (nl > 0) end = Math.min(end, nl);
        if (dot > 0) end = Math.min(end, dot + 1);
        if (end <= cap) return t.substring(0, end).trim();
        int stop = cap - 1;
        int space = t.lastIndexOf(' ', stop);
        if (space > cap / 2) stop = space;
        return t.substring(0, stop).trim() + "…";
    }

    @NonNull
    private static Set<String> words(@NonNull String request) {
        Set<String> out = new HashSet<>();
        for (String w : request.toLowerCase(Locale.US).split("[^a-z0-9]+")) if (w.length() >= 3) out.add(w);
        return out;
    }

    private static int relevance(@NonNull Candidate c, @NonNull Set<String> words) {
        int s = 0;
        Set<String> nameParts = new HashSet<>();
        for (String p : c.name.toLowerCase(Locale.US).split("[^a-z0-9]+")) if (!p.isEmpty()) nameParts.add(p);
        Set<String> descParts = new HashSet<>();
        for (String p : c.description.toLowerCase(Locale.US).split("[^a-z0-9]+")) if (!p.isEmpty()) descParts.add(p);
        for (String w : words) {
            if (nameParts.contains(w)) s += 3;
            else if (descParts.contains(w)) s += 1;
        }
        return s;
    }

    /**
     * One line for the model about the tools left out of its list. Empty when none were. They still
     * run when called by name; if one is refused as unknown, the model should say so plainly.
     */
    @NonNull
    public static String droppedNote(@NonNull List<String> dropped) {
        if (dropped.isEmpty()) return "";
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (String d : dropped) {
            if (names.length() + d.length() + 2 > NOTE_NAMES_CAP) break;
            if (shown++ > 0) names.append(", ");
            names.append(d);
        }
        int more = dropped.size() - shown;
        return " To save space these tools are not described: " + names + (more > 0 ? " and " + more + " more" : "")
                + ". They still work if you call them by name. If a call is answered with an unknown-tool"
                + " error, do not guess: tell the person you cannot do that here.";
    }

    // ----------------------------------------------------------------------------- project summary

    /**
     * The project summary cut to {@code max} characters. Kept always: the header, the project line,
     * playhead, selection, the closing summary note. Then the clip and audio lines. Dropped first:
     * everything else (transcripts, layers, sheets), from the end; then clip lines from the end.
     * One marker says how many lines were left out and how to read them.
     */
    @NonNull
    public static String trimContext(@NonNull String context, int max) {
        if (max == Integer.MAX_VALUE || context.length() <= max) return context;
        String[] lines = context.split("\n", -1);
        int n = lines.length;
        int[] prio = new int[n];
        for (int i = 0; i < n; i++) prio[i] = priority(lines[i]);
        boolean[] gone = new boolean[n];
        int total = context.length();
        final String marker = "(%d more lines left out to save space: list_items, get_item and find_words read them)";
        int reserve = marker.length() + 2;
        int removed = 0;
        for (int level = 2; level >= 1 && total > max - reserve; level--) {
            for (int i = n - 1; i >= 0 && total > max - reserve; i--) {
                if (gone[i] || prio[i] != level) continue;
                gone[i] = true;
                total -= lines[i].length() + 1;
                removed++;
            }
        }
        if (removed == 0) return context;
        StringBuilder sb = new StringBuilder();
        boolean said = false;
        for (int i = 0; i < n; i++) {
            if (gone[i]) {
                if (!said) { sb.append(String.format(Locale.US, marker, removed)).append('\n'); said = true; }
                continue;
            }
            sb.append(lines[i]);
            if (i < n - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** 0 = always kept, 1 = clip and audio lines, 2 = everything else. */
    private static int priority(@NonNull String line) {
        if (line.isEmpty() || line.startsWith("CURRENT PROJECT") || line.startsWith("Joybot was opened")
                || line.startsWith("Project \"") || line.startsWith("Playhead") || line.startsWith("SELECTED")
                || line.startsWith("No project") || line.startsWith("The project could not")
                || line.startsWith("VisLab is showing") || line.startsWith("(This is a summary")
                || line.startsWith("(Could not read")) return 0;
        if (line.startsWith("Clip ") || line.startsWith("AudioClip ")) return 1;
        return 2;
    }
}
