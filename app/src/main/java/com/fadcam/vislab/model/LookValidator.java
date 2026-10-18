package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.audio.Signals;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Makes a look safe to show, whoever wrote it (a person, a pack, Joybot). Everything it can
 * fix it fixes and says so in plain words: unknown layer types and knobs are dropped, numbers
 * are pulled into range, choices are rounded to a real option, music links to signals that do
 * not exist are removed, bad colours are reported. It never throws.
 */
public final class LookValidator {

    private static final Pattern HEX = Pattern.compile("#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    private static final float MAX_MOD = 2f;

    private LookValidator() {}

    /** Fix {@code look} in place; returns what was changed, one sentence each. */
    @NonNull
    public static List<String> fix(@NonNull JsonObject look, @NonNull VisTypes types) {
        List<String> w = new ArrayList<>();
        if (!look.has("format")) look.addProperty("format", VisScene.FORMAT);
        JsonElement pal = look.get("palette");
        if (pal != null && !pal.isJsonNull()) {
            if (!pal.isJsonArray() || pal.getAsJsonArray().size() == 0) {
                look.remove("palette");
                w.add("The palette was not a list of colours, so the look keeps its default colours.");
            } else {
                JsonArray a = pal.getAsJsonArray();
                for (int i = 0; i < a.size(); i++) {
                    JsonElement c = a.get(i);
                    if (!c.isJsonPrimitive() || !HEX.matcher(c.getAsString().trim()).matches()) {
                        a.set(i, new com.google.gson.JsonPrimitive("#808080"));
                        w.add("Palette colour " + (i + 1) + " was not a colour; it is grey now.");
                    }
                }
            }
        }
        JsonElement bg = look.get("background");
        if (bg != null && !bg.isJsonNull() && (!bg.isJsonPrimitive() || !HEX.matcher(bg.getAsString().trim()).matches())) {
            look.add("background", JsonNull.INSTANCE);
            w.add("The background was not a colour, so the look is see-through.");
        }
        JsonElement le = look.get("layers");
        if (le == null || !le.isJsonArray()) {
            look.add("layers", new JsonArray());
            w.add("The look had no layer list; it starts empty.");
        } else {
            JsonArray layers = le.getAsJsonArray();
            for (int i = layers.size() - 1; i >= 0; i--) {
                JsonElement e = layers.get(i);
                if (!e.isJsonObject()) {
                    layers.remove(i);
                    w.add("Layer " + (i + 1) + " was not a layer and was removed.");
                    continue;
                }
                JsonObject L = e.getAsJsonObject();
                JsonElement te = L.get("type");
                VisType t = types.type(te != null && te.isJsonPrimitive() ? te.getAsString() : null);
                if (t == null) {
                    layers.remove(i);
                    w.add("There is no layer type called \"" + (te == null ? "?" : te.getAsString()) + "\"; that layer was removed.");
                    continue;
                }
                String who = layerName(L, t);
                clampTop(L, "opacity", 0f, 1f, who, w);
                clampTop(L, "add", 0f, 1f, who, w);
                clampTop(L, "res", 0.25f, 1f, who, w);
                knobs(L, t, who, w);
            }
        }
        block(look, "echo", types.echo, "Echo", w);
        block(look, "post", types.post, "Finish", w);
        return w;
    }

    private static void block(@NonNull JsonObject look, @NonNull String key, @NonNull VisType t,
                              @NonNull String who, @NonNull List<String> w) {
        JsonElement b = look.get(key);
        if (b == null || b.isJsonNull()) return;
        if (!b.isJsonObject()) {
            look.remove(key);
            w.add(who + " settings were not readable and were reset.");
            return;
        }
        knobs(b.getAsJsonObject(), t, who, w);
    }

    private static void knobs(@NonNull JsonObject holder, @NonNull VisType t, @NonNull String who, @NonNull List<String> w) {
        JsonElement pe = holder.get("p");
        if (pe == null || pe.isJsonNull()) return;
        if (!pe.isJsonObject()) {
            holder.add("p", new JsonObject());
            w.add(who + ": its settings were not readable and were reset.");
            return;
        }
        JsonObject p = pe.getAsJsonObject();
        if ("words".equals(t.id)) wordsScene(p, who, w);
        Iterator<Map.Entry<String, JsonElement>> it = p.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonElement> en = it.next();
            VisKnob k = t.knob(en.getKey());
            if (k == null) {
                it.remove();
                w.add(who + " has no setting called \"" + en.getKey() + "\"; it was ignored.");
                continue;
            }
            JsonElement v = en.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                float f = v.getAsFloat();
                float g = clampKnob(k, f);
                if (g != f) {
                    en.setValue(new com.google.gson.JsonPrimitive(g));
                    w.add(who + ": " + k.label + " " + fmt(f) + " is out of range; set to " + fmt(g) + ".");
                }
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                en.setValue(new com.google.gson.JsonPrimitive(v.getAsBoolean() ? 1 : 0));
            } else if (v.isJsonObject()) {
                JsonObject o = v.getAsJsonObject();
                JsonElement base = o.get("v");
                if (base == null || !base.isJsonPrimitive() || !base.getAsJsonPrimitive().isNumber()) {
                    o.addProperty("v", k.def);
                } else {
                    float f = base.getAsFloat(), g = clampKnob(k, f);
                    if (g != f) {
                        o.addProperty("v", g);
                        w.add(who + ": " + k.label + " " + fmt(f) + " is out of range; set to " + fmt(g) + ".");
                    }
                }
                JsonElement me = o.get("mod");
                if (me != null && me.isJsonObject()) {
                    Iterator<Map.Entry<String, JsonElement>> mi = me.getAsJsonObject().entrySet().iterator();
                    while (mi.hasNext()) {
                        Map.Entry<String, JsonElement> m = mi.next();
                        if (Signals.indexOf(m.getKey()) < 0 || !m.getValue().isJsonPrimitive()
                                || !m.getValue().getAsJsonPrimitive().isNumber()) {
                            mi.remove();
                            w.add(who + ": " + k.label + " cannot follow \"" + m.getKey() + "\"; that link was removed.");
                            continue;
                        }
                        float a = m.getValue().getAsFloat();
                        if (Math.abs(a) > MAX_MOD) {
                            m.setValue(new com.google.gson.JsonPrimitive(Math.signum(a) * MAX_MOD));
                            w.add(who + ": " + k.label + " followed " + m.getKey() + " too strongly; capped.");
                        }
                    }
                } else if (me != null) {
                    o.remove("mod");
                }
            } else {
                it.remove();
                w.add(who + ": " + k.label + " was not a number; it is back to its default.");
            }
        }
    }

    /**
     * Words layers saved before 10-18 picked one "mode" (a whole scene). Words is now three parts
     * that mix freely: a path, a behaviour and letters. Turn the old scene into those parts so
     * the look stays as it was. Rows: mode -> path, behaviour, letters (-1 = keep).
     */
    private static final int[][] WORDS_SCENES = {
            {5, 0, -1},   // star flight
            {1, 0, -1},   // spiral banner
            {3, 0, -1},   // tunnel ticker
            {7, 3, -1},   // smoke
            {6, 1, -1},   // puck
            {2, 0, -1},   // ring
            {0, 0, -1},   // weaving ribbon
            {8, 3, -1},   // cigar smoke
            {4, 2, -1},   // neon street
            {7, 5, 5},    // constellations
            {7, 4, 4},    // fireworks
    };

    private static void wordsScene(@NonNull JsonObject p, @NonNull String who, @NonNull List<String> w) {
        JsonElement m = p.get("mode");
        if (m == null || p.has("path")) return;
        p.remove("mode");
        if (!m.isJsonPrimitive() || !m.getAsJsonPrimitive().isNumber()) return;
        int mode = Math.max(0, Math.min(WORDS_SCENES.length - 1, Math.round(m.getAsFloat())));
        int[] row = WORDS_SCENES[mode];
        p.addProperty("path", row[0]);
        p.addProperty("behaviour", row[1]);
        if (row[2] >= 0) p.addProperty("letters", row[2]);
        if (mode == 10 && p.has("size") && p.get("size").isJsonPrimitive()) {
            p.addProperty("size", p.get("size").getAsFloat() * 1.3f);   // the old fireworks drew 1.3x
        }
        w.add(who + ": its words scene is now a path, a behaviour and letters (same look).");
    }

    private static float clampKnob(@NonNull VisKnob k, float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) return k.def;
        float g = Math.max(k.min, Math.min(k.max, f));
        if (k.options != null || k.toggle || k.step >= 1f) g = Math.round(g);
        return g;
    }

    private static void clampTop(@NonNull JsonObject L, @NonNull String key, float lo, float hi,
                                 @NonNull String who, @NonNull List<String> w) {
        JsonElement e = L.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return;
        float f = e.getAsFloat(), g = Math.max(lo, Math.min(hi, Float.isNaN(f) ? hi : f));
        if (g != f) {
            L.addProperty(key, g);
            w.add(who + ": " + key + " " + fmt(f) + " is out of range; set to " + fmt(g) + ".");
        }
    }

    @NonNull
    private static String layerName(@NonNull JsonObject L, @NonNull VisType t) {
        JsonElement n = L.get("name");
        return n != null && n.isJsonPrimitive() ? n.getAsString() : t.label;
    }

    @NonNull
    private static String fmt(float f) {
        return f == Math.round(f) ? String.valueOf(Math.round(f)) : String.valueOf(Math.round(f * 1000f) / 1000f);
    }

    /** True for "#RGB" / "#RRGGBB" (with or without the #). */
    public static boolean isColour(@Nullable String s) {
        return s != null && HEX.matcher(s.trim()).matches();
    }
}
