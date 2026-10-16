package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A recipe (gap F7): something Joybot did once, kept so it can be done again in one tap — "every
 * new recording: transcribe, cut the ums, captions on". Two ways to replay, chosen honestly:
 * <ul>
 *   <li><b>portable</b> — every step names nothing of THIS project (no clip ids, no layer words),
 *   so the steps run as they are, through the same door as an idea button: offline, free, one
 *   undo step.</li>
 *   <li>otherwise the person's original words go to the model again, on the new project, as an
 *   approved plan — because "the clip called Take 2" means nothing in another project.</li>
 * </ul>
 * Pure Java (tested off device). Saved as a small JSON file the person can read and edit.
 */
public final class Recipe {

    @NonNull public final String name;
    /** What the person asked, in their words. */
    @NonNull public final String request;
    @NonNull public final List<Idea.Step> steps;

    public Recipe(@NonNull String name, @NonNull String request, @NonNull List<Idea.Step> steps) {
        this.name = name.trim().isEmpty() ? "My recipe" : name.trim();
        this.request = request;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Runs as recorded, with no model, on any project. */
    public boolean portable() {
        return portable(steps);
    }

    private static final Pattern UUIDISH = Pattern.compile(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*");

    /**
     * True when no step points at something of the project it was recorded in: an argument named
     * like an id or a target ("clipId", "text", "layer", "sheetId"…) holding anything but "all", or
     * any value that looks like an id. Unknown is treated as not portable — replaying it through
     * the model is slower but can't hit the wrong thing.
     */
    public static boolean portable(@NonNull List<Idea.Step> steps) {
        if (steps.isEmpty()) return false;
        for (Idea.Step s : steps) {
            for (Map.Entry<String, Object> e : s.args.entrySet()) {
                if (!portableValue(e.getKey(), e.getValue())) return false;
            }
        }
        return true;
    }

    private static boolean portableValue(@NonNull String key, Object v) {
        if (v instanceof List) {
            for (Object o : (List<?>) v) if (!portableValue(key, o)) return false;
            return true;
        }
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!portableValue(String.valueOf(e.getKey()), e.getValue())) return false;
            }
            return true;
        }
        String s = v == null ? "" : String.valueOf(v);
        if (UUIDISH.matcher(s).matches()) return false;
        String k = key.toLowerCase(Locale.ROOT);
        boolean target = k.endsWith("id") || k.endsWith("ids") || k.equals("text") || k.equals("layer")
                || k.equals("clip") || k.equals("slide") || k.equals("target") || k.equals("item")
                || k.equals("file") || k.equals("sheet") || k.equals("music") || k.equals("voice");
        return !target || "all".equalsIgnoreCase(s.trim());
    }

    /** "3 steps, works offline" / "uses the AI (it names things in the project it was made in)". */
    @NonNull
    public String describe() {
        int n = steps.size();
        String count = n == 1 ? "1 step" : n + " steps";
        return portable() ? count + ", works offline" : count + ", uses the AI (it names things in the project it was made in)";
    }

    /** A file name from the recipe's name: letters, digits, dashes. */
    @NonNull
    public static String slug(@NonNull String name) {
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        return s.isEmpty() ? "recipe" : s;
    }
}
