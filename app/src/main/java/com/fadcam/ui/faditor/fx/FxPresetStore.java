package com.fadcam.ui.faditor.fx;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * User-saved FX STACKS, in their own SharedPreferences file
 * (SPEC_ADJUSTMENT_LAYERS_FX M6).
 *
 * <p>Copies {@code GradePresetStore} deliberately, down to the shape of the file: a dedicated
 * prefs name holding one JSON blob keyed by preset name. The important property is what it is
 * NOT — presets live <b>outside the project schema</b>, so saving one costs no schema bump, no
 * migration and no stamp, and a preset can never make a project unopenable by an older build.
 * A "psychedelic bubbles" look is a tool the user carries between projects, not part of any one
 * of them.</p>
 *
 * <p>Touch ONLY this file and {@code FxPanel} — the same containment note
 * {@code GradePresetStore} carries.</p>
 */
public final class FxPresetStore {

    private static final String PREFS_NAME = "faditor_fx_presets";
    private static final String KEY_PRESETS = "presets";

    private FxPresetStore() {}

    /** Saved preset names, case-insensitively sorted. Empty when there are none. */
    @NonNull
    public static List<String> listNames(@NonNull Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject root = loadRoot(ctx);
            Iterator<String> it = root.keys();
            while (it.hasNext()) out.add(it.next());
            java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        } catch (JSONException ignored) {
            // A corrupt blob costs the LIST, never the editor. Same tolerance as the grade store.
        }
        return out;
    }

    /**
     * Save {@code stack} under {@code name}, overwriting any preset of that name.
     *
     * <p>Stores {@link FxStack#toJson()} verbatim — the same bytes the project file would hold —
     * so a preset and a saved stack cannot drift into two formats, and loading one is exactly
     * the parse the project loader already trusts.</p>
     */
    public static void save(@NonNull Context ctx, @NonNull String name, @NonNull FxStack stack) {
        if (name.trim().isEmpty()) return;
        try {
            JSONObject root = loadRoot(ctx);
            root.put(name.trim(), new JSONObject(stack.toJson().toString()));
            prefs(ctx).edit().putString(KEY_PRESETS, root.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /**
     * Load {@code name} INTO {@code target}, replacing its contents.
     *
     * <p>Mutates in place rather than returning a new stack, because the layer holds a reference
     * to its own — the same reason {@code CompositingSpec.copyFrom} exists.</p>
     *
     * @return false when no such preset exists, so the caller can say so rather than silently
     *         clearing the stack the user was working on.
     */
    public static boolean load(@NonNull Context ctx, @NonNull String name,
                               @NonNull FxStack target) {
        try {
            JSONObject root = loadRoot(ctx);
            if (!root.has(name)) return false;
            JSONObject obj = root.getJSONObject(name);
            FxStack loaded = FxStack.fromJson(
                    com.google.gson.JsonParser.parseString(obj.toString()).getAsJsonObject());
            target.copyFrom(loaded);
            return true;
        } catch (JSONException | RuntimeException e) {
            return false;
        }
    }

    /** @return false when there was nothing of that name to delete. */
    public static boolean delete(@NonNull Context ctx, @NonNull String name) {
        try {
            JSONObject root = loadRoot(ctx);
            if (!root.has(name)) return false;
            root.remove(name);
            prefs(ctx).edit().putString(KEY_PRESETS, root.toString()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    @NonNull
    private static JSONObject loadRoot(@NonNull Context ctx) throws JSONException {
        String raw = prefs(ctx).getString(KEY_PRESETS, "{}");
        return new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
