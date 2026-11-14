package com.fadcam.ui.faditor.ai.joybot;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recipes on the bench's Android shim (BOT-A33): the "offer for new recordings" switch is saved in
 * the recipe's own readable file, an older file without it still reads (as off), a rewrite keeps the
 * file, and the trigger offers each recording once. Run:
 *   bash tools/jvm-harness/run-joybot-bench.sh --recipes
 */
public final class RecipeE2E {
    static int pass, fail;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + what); }
        else { fail++; System.out.println("FAIL " + what + "   " + detail); }
    }

    static void wipe(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) wipe(x);
        f.delete();
    }

    public static void main(String[] a) throws Exception {
        File dir = new File("tools/jvm-harness/out-joybot-runs/recipes");
        wipe(dir);
        android.content.Context ctx = new android.content.Context(new File(dir, "files"),
                new File(System.getProperty("bench.assets")));

        Map<String, Object> args = new HashMap<>();
        args.put("all", true);
        List<Idea.Step> steps = new ArrayList<>(Arrays.asList(new Idea.Step("cut_all_fillers", args)));
        Recipe plain = new Recipe("Clean my talk", "cut the ums", steps);
        String file = RecipeStore.save(ctx, plain);
        List<RecipeStore.Saved> list = RecipeStore.list(ctx);
        check("saved and listed", list.size() == 1 && list.get(0).file.equals(file), String.valueOf(list.size()));
        check("a new recipe is not offered for recordings", !list.get(0).recipe.forNewRecordings, "");
        check("its file doesn't mention the switch when off", !RecipeStore.toJson(plain).contains("offerForNewRecordings"), "");

        RecipeStore.rewrite(ctx, file, plain.withNewRecordings(true));
        list = RecipeStore.list(ctx);
        check("the switch is saved in the same file", list.size() == 1 && list.get(0).file.equals(file)
                && list.get(0).recipe.forNewRecordings, list.isEmpty() ? "" : list.get(0).file);
        check("steps survive the rewrite", list.get(0).recipe.steps.size() == 1
                && "cut_all_fillers".equals(list.get(0).recipe.steps.get(0).tool), "");
        check("the file says it in words a person can read",
                RecipeStore.toJson(list.get(0).recipe).contains("\"offerForNewRecordings\": true"), RecipeStore.toJson(list.get(0).recipe));

        String old = "{\"name\":\"Old\",\"request\":\"captions\",\"steps\":[{\"tool\":\"toggle_captions\",\"args\":{\"on\":true}}]}";
        check("an older file without the switch reads as off", !RecipeStore.parse(old).forNewRecordings, "");

        // The trigger: newest recording first, each recipe and recording asked once.
        List<String> flagged = Arrays.asList(file);
        List<String> recs = Arrays.asList("content://rec/1", "content://rec/2");
        Set<String> answered = new HashSet<>();
        RecipeTrigger.Offer o = RecipeTrigger.next(flagged, recs, answered, "p1");
        check("the newest recording is offered first", o != null && o.recordingUri.equals("content://rec/2"), o == null ? "" : o.recordingUri);
        answered.add(RecipeTrigger.key(o.recipeFile, "p1", o.recordingUri));
        o = RecipeTrigger.next(flagged, recs, answered, "p1");
        check("after an answer, the next recording", o != null && o.recordingUri.equals("content://rec/1"), "");
        answered.add(RecipeTrigger.key(o.recipeFile, "p1", o.recordingUri));
        check("all answered: nothing more to offer", RecipeTrigger.next(flagged, recs, answered, "p1") == null, "");
        check("another project asks afresh", RecipeTrigger.next(flagged, recs, answered, "p2") != null, "");
        check("no recordings, no offer", RecipeTrigger.next(flagged, new ArrayList<String>(), new HashSet<String>(), "p1") == null, "");
        check("no recipe switched on, no offer", RecipeTrigger.next(new ArrayList<String>(), recs, new HashSet<String>(), "p1") == null, "");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
