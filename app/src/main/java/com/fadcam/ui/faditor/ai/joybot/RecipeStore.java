package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileJoyFs;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Joybot's recipes on disk (gap F7): one small JSON file per recipe under {@code Joybot/recipes/}
 * in the Joy Creator folder, so the person can open, copy or edit them by hand. When that folder
 * can't be reached, recipes go to the app's own {@code joybot/recipes/} folder instead, and a log
 * line says so.
 *
 * <p>Blocking: call from a worker thread only.</p>
 */
public final class RecipeStore {

    private RecipeStore() {}

    private static final String TAG = "RecipeStore";
    /** Relative to the Joy Creator folder. */
    static final String DIR = "Joybot/recipes";
    /** Relative to the app's files folder, when the Joy Creator folder is unreachable. */
    static final String FALLBACK_DIR = "joybot/recipes";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A recipe as it sits on disk: its file name (what Delete needs) and its contents. */
    public static final class Saved {
        @NonNull public final String file;
        @NonNull public final Recipe recipe;

        Saved(@NonNull String file, @NonNull Recipe recipe) {
            this.file = file;
            this.recipe = recipe;
        }
    }

    /** Where recipes are kept right now. {@code folder} is null in the fallback. */
    private static final class Home {
        final JoyFs fs;
        @Nullable final JoyFolder folder;
        final String dir;

        Home(@NonNull JoyFs fs, @Nullable JoyFolder folder, @NonNull String dir) {
            this.fs = fs;
            this.folder = folder;
            this.dir = dir;
        }
    }

    @NonNull
    private static Home home(@NonNull Context c) throws IOException {
        JoyFolder jf = JoyFolderHome.open(c);   // null when no folder is chosen or it can't be reached
        if (jf != null) return new Home(jf.fs, jf, DIR);
        FLog.w(TAG, "Joy Creator folder unreachable; keeping recipes in the app's own folder");
        return new Home(new FileJoyFs(c.getFilesDir()), null, FALLBACK_DIR);
    }

    /** Save a recipe under a free file name: its slug, then "-2", "-3"… Returns that file name. */
    @WorkerThread
    @NonNull
    public static synchronized String save(@NonNull Context c, @NonNull Recipe recipe) throws IOException {
        Home h = home(c);
        h.fs.mkdirs(h.dir);
        Set<String> taken = new HashSet<>();
        for (JoyFs.Entry e : h.fs.list(h.dir)) taken.add(e.name.toLowerCase(Locale.ROOT));
        String base = Recipe.slug(recipe.name);
        String file = base + ".json";
        for (int n = 2; taken.contains(file); n++) file = base + "-" + n + ".json";
        byte[] data = toJson(recipe).getBytes(StandardCharsets.UTF_8);
        String rel = h.dir + "/" + file;
        if (h.folder != null) {
            h.folder.writeAtomic(rel, data);
        } else {
            try (OutputStream out = h.fs.create(rel)) {
                out.write(data);
            }
        }
        return file;
    }

    /**
     * Every recipe that reads cleanly, by name. A file that doesn't parse (or is missing a field) is
     * skipped with a log line, never an error. Empty when there are none or nothing can be read.
     */
    @WorkerThread
    @NonNull
    public static synchronized List<Saved> list(@NonNull Context c) {
        List<Saved> out = new ArrayList<>();
        try {
            Home h = home(c);
            for (JoyFs.Entry e : h.fs.list(h.dir)) {
                if (e.dir || !e.name.endsWith(".json")) continue;
                try {
                    String json = new String(readAll(h.fs, h.dir + "/" + e.name), StandardCharsets.UTF_8);
                    out.add(new Saved(e.name, parse(json)));
                } catch (Exception ex) {
                    FLog.w(TAG, "Skipped " + e.name + ": " + ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException ex) {
            FLog.w(TAG, "Couldn't list recipes", ex);
        }
        Collections.sort(out, (a, b) -> a.recipe.name.compareToIgnoreCase(b.recipe.name));
        return out;
    }

    /**
     * Delete a recipe. In the Joy Creator folder it goes to Trash (restorable there); in the fallback
     * folder the file is removed.
     */
    @WorkerThread
    public static synchronized boolean delete(@NonNull Context c, @NonNull String file) throws IOException {
        Home h = home(c);
        String rel = h.dir + "/" + file;
        if (h.folder != null) {
            h.folder.trash(rel, System.currentTimeMillis());
            return true;
        }
        return h.fs.delete(rel);
    }

    // ── JSON ─────────────────────────────────────────────────────────────

    /** The file's text. Pretty-printed with two-space indents, so it reads well by hand. */
    @NonNull
    static String toJson(@NonNull Recipe r) {
        JsonObject o = new JsonObject();
        o.addProperty("name", r.name);
        o.addProperty("request", r.request);
        JsonArray steps = new JsonArray();
        for (Idea.Step s : r.steps) {
            JsonObject so = new JsonObject();
            so.addProperty("tool", s.tool);
            so.add("args", GSON.toJsonTree(s.args));
            steps.add(so);
        }
        o.add("steps", steps);
        return GSON.toJson(o) + "\n";
    }

    /** The recipe in this text. Throws (with the reason) when a required field is missing. */
    @NonNull
    static Recipe parse(@NonNull String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String name = requireString(o, "name");
        String request = requireString(o, "request");
        JsonElement stepsEl = o.get("steps");
        if (stepsEl == null || !stepsEl.isJsonArray()) throw new IllegalArgumentException("no \"steps\" list");
        List<Idea.Step> steps = new ArrayList<>();
        for (JsonElement el : stepsEl.getAsJsonArray()) {
            if (!el.isJsonObject()) throw new IllegalArgumentException("a step is not an object");
            JsonObject so = el.getAsJsonObject();
            String tool = requireString(so, "tool");
            JsonElement args = so.get("args");
            if (args == null || !args.isJsonObject()) throw new IllegalArgumentException("a step has no \"args\" object");
            steps.add(new Idea.Step(tool, toMap(args.getAsJsonObject())));
        }
        if (steps.isEmpty()) throw new IllegalArgumentException("no steps");
        return new Recipe(name, request, steps);
    }

    @NonNull
    private static String requireString(@NonNull JsonObject o, @NonNull String key) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("no \"" + key + "\" text");
        }
        return el.getAsString();
    }

    @NonNull
    private static Map<String, Object> toMap(@NonNull JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), toValue(e.getValue()));
        return m;
    }

    @Nullable
    private static Object toValue(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) return toMap(el.getAsJsonObject());
        if (el.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : el.getAsJsonArray()) l.add(toValue(x));
            return l;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            String s = p.getAsString();
            // Whole numbers stay whole ("fps": 12, not 12.0).
            if (s.matches("-?\\d{1,18}")) return Long.parseLong(s);
            return p.getAsDouble();
        }
        return p.getAsString();
    }

    @NonNull
    private static byte[] readAll(@NonNull JoyFs fs, @NonNull String rel) throws IOException {
        try (InputStream in = fs.open(rel)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
