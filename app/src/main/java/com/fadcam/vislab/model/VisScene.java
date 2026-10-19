package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One VisLab look ({@code *.vis.json}): palette, background, an ordered stack of layers, an
 * optional Echo and the Finish. Kept as the JSON tree itself, so a look written by a person, a
 * pack or Joybot round-trips byte-for-byte through the app even when it carries fields this
 * version does not know. Editors change the tree and call {@link #touch()}; the renderer
 * recompiles when {@link #edits()} moves.
 *
 * <p>Format {@code joy.vis/1}. See {@code docs/vislab-format.md}.</p>
 */
public final class VisScene {

    public static final String FORMAT = "joy.vis/1";
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @NonNull private final JsonObject root;
    private int edits;

    public VisScene(@NonNull JsonObject root) {
        this.root = root;
        if (!root.has("layers") || !root.get("layers").isJsonArray()) root.add("layers", new JsonArray());
    }

    @NonNull
    public static VisScene parse(@NonNull String json) {
        return new VisScene(JsonParser.parseString(json).getAsJsonObject());
    }

    @NonNull
    public JsonObject json() {
        return root;
    }

    @NonNull
    public VisScene copy() {
        return new VisScene(root.deepCopy());
    }

    @NonNull
    public String toJson() {
        return PRETTY.toJson(root);
    }

    /** Call after changing the tree: the renderer picks the change up on its next frame. */
    public void touch() {
        edits++;
    }

    public int edits() {
        return edits;
    }

    @NonNull
    public String id() {
        return str("id", "untitled");
    }

    @NonNull
    public String name() {
        return str("name", id());
    }

    @NonNull
    public String family() {
        return str("family", "");
    }

    @NonNull
    public String description() {
        return str("description", "");
    }

    /** Five palette stops (hex), or null when the look has none. */
    @Nullable
    public String[] palette() {
        return stops(root.get("palette"));
    }

    /** Background hex, or null for see-through (sits over video or the screen). */
    @Nullable
    public String background() {
        JsonElement e = root.get("background");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    @NonNull
    public JsonArray layers() {
        return root.getAsJsonArray("layers");
    }

    /** The Echo block when it is switched on, else null. */
    @Nullable
    public JsonObject echoOn() {
        JsonElement e = root.get("echo");
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement on = o.get("on");
        if (on != null && on.isJsonPrimitive() && !on.getAsBoolean()) return null;
        return o;
    }

    /** The knob values of the Finish pass (may be null: all defaults). */
    @Nullable
    public JsonObject postKnobs() {
        return knobsOf(root.get("post"));
    }

    @Nullable
    static JsonObject knobsOf(@Nullable JsonElement block) {
        if (block == null || !block.isJsonObject()) return null;
        JsonElement p = block.getAsJsonObject().get("p");
        return p != null && p.isJsonObject() ? p.getAsJsonObject() : null;
    }

    @Nullable
    public static String[] stops(@Nullable JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() == 0) return null;
        JsonArray a = e.getAsJsonArray();
        String[] out = new String[5];
        for (int i = 0; i < 5; i++) {
            JsonElement s = a.get(Math.min(i, a.size() - 1));
            out[i] = s.isJsonPrimitive() ? s.getAsString() : "#000000";
        }
        return out;
    }

    @NonNull
    private String str(@NonNull String k, @NonNull String def) {
        JsonElement e = root.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    // ---- colour ----

    /** sRGB hex ("#RRGGBB" or "#RGB") to linear light, as the shaders want it. */
    @NonNull
    public static float[] hexToLinear(@Nullable String hex, @NonNull float[] out, int offset) {
        String h = hex == null ? "000000" : hex.trim().replace("#", "");
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        int n = 0;
        try {
            n = Integer.parseInt(h.length() >= 6 ? h.substring(0, 6) : h, 16);
        } catch (NumberFormatException ignored) {
            // A bad colour reads as black rather than failing the look.
        }
        out[offset] = srgbToLinear(((n >> 16) & 255) / 255f);
        out[offset + 1] = srgbToLinear(((n >> 8) & 255) / 255f);
        out[offset + 2] = srgbToLinear((n & 255) / 255f);
        return out;
    }

    public static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Five stops packed for {@code uPal[5]} (15 floats, linear). */
    @NonNull
    public static float[] paletteLinear(@Nullable String[] stops) {
        float[] out = new float[15];
        String[] s = stops != null ? stops : new String[]{"#000000", "#333333", "#777777", "#bbbbbb", "#ffffff"};
        for (int i = 0; i < 5; i++) hexToLinear(s[Math.min(i, s.length - 1)], out, i * 3);
        return out;
    }
}
