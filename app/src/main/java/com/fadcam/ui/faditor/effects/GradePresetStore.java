package com.fadcam.ui.faditor.effects;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Persists user-saved colour/grade presets in a dedicated SharedPreferences file.
 * Each preset stores the 10 grade floats + LUT id/intensity as a JSON object.
 * <p>
 * Touch ONLY this file and FilterBottomSheet — no project schema changes.
 */
public final class GradePresetStore {

    private static final String PREFS_NAME = "faditor_grade_presets";
    private static final String KEY_PRESETS = "presets";

    private GradePresetStore() {}

    /** Returns the sorted list of saved preset names (empty if none). */
    @NonNull
    public static List<String> listNames(@NonNull Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject root = loadRoot(ctx);
            Iterator<String> it = root.keys();
            while (it.hasNext()) out.add(it.next());
            java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        } catch (JSONException ignored) {}
        return out;
    }

    /**
     * Save the current grade values of {@code stack} under {@code name}.
     * Overwrites any existing preset with the same name.
     */
    public static void save(@NonNull Context ctx, @NonNull String name, @NonNull EffectStack stack) {
        try {
            JSONObject root = loadRoot(ctx);
            root.put(name, serialize(stack));
            storeRoot(ctx, root);
        } catch (JSONException ignored) {}
    }

    /**
     * Load a saved preset by {@code name} and apply its grade values onto {@code stack}.
     *
     * @return {@code true} if the preset existed and was applied.
     */
    public static boolean load(@NonNull Context ctx, @NonNull String name, @NonNull EffectStack stack) {
        try {
            JSONObject root = loadRoot(ctx);
            JSONObject preset = root.optJSONObject(name);
            if (preset == null) return false;
            apply(preset, stack);
            return true;
        } catch (JSONException ignored) {
            return false;
        }
    }

    /** Delete a preset by name. Returns {@code true} if it existed. */
    public static boolean delete(@NonNull Context ctx, @NonNull String name) {
        try {
            JSONObject root = loadRoot(ctx);
            if (!root.has(name)) return false;
            root.remove(name);
            storeRoot(ctx, root);
            return true;
        } catch (JSONException ignored) {
            return false;
        }
    }

    // ── internal ──────────────────────────────────────────────────

    @NonNull
    private static JSONObject serialize(@NonNull EffectStack s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("exposure",    s.getExposure());
        o.put("contrast",    s.getContrast());
        o.put("saturation",  s.getSaturation());
        o.put("temperature", s.getTemperature());
        o.put("tint",        s.getTint());
        o.put("highlights",  s.getHighlights());
        o.put("shadows",     s.getShadows());
        o.put("fade",        s.getFade());
        o.put("vignette",    s.getVignette());
        o.put("grain",       s.getGrain());
        o.put("lutEnabled",  s.isLutEnabled());
        o.put("lutId",       s.getLutId() != null ? s.getLutId() : JSONObject.NULL);
        o.put("lutIntensity", s.getLutIntensity());
        return o;
    }

    private static void apply(@NonNull JSONObject o, @NonNull EffectStack s) {
        s.setExposure((float) o.optDouble("exposure", 0));
        s.setContrast((float) o.optDouble("contrast", 0));
        s.setSaturation((float) o.optDouble("saturation", 1));
        s.setTemperature((float) o.optDouble("temperature", 0));
        s.setTint((float) o.optDouble("tint", 0));
        s.setHighlights((float) o.optDouble("highlights", 0));
        s.setShadows((float) o.optDouble("shadows", 0));
        s.setFade((float) o.optDouble("fade", 0));
        s.setVignette((float) o.optDouble("vignette", 0));
        s.setGrain((float) o.optDouble("grain", 0));
        if (o.has("lutEnabled")) s.setLutEnabled(o.optBoolean("lutEnabled", false));
        if (o.has("lutId")) {
            Object lid = o.opt("lutId");
            s.setLutId(JSONObject.NULL.equals(lid) ? null : lid.toString());
        }
        if (o.has("lutIntensity")) s.setLutIntensity((float) o.optDouble("lutIntensity", 1));
    }

    @NonNull
    private static JSONObject loadRoot(@NonNull Context ctx) throws JSONException {
        String raw = prefs(ctx).getString(KEY_PRESETS, null);
        if (raw != null) return new JSONObject(raw);
        return new JSONObject();
    }

    private static void storeRoot(@NonNull Context ctx, @NonNull JSONObject root) {
        prefs(ctx).edit().putString(KEY_PRESETS, root.toString()).apply();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
