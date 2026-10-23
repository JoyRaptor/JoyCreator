package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VisLab registry ({@code assets/vislab/types.json}): every primitive, every knob, the
 * music signals a knob can react to, and the scene-level Echo and Finish passes.
 *
 * <p>Read tolerantly. A knob or type with a broken entry is skipped, never fatal, so a hand
 * edit or a pack that adds a type cannot take the whole lab down.</p>
 */
public final class VisTypes {

    /** A music signal a knob can follow ("bass", "kick"...), with the name people see. */
    public static final class Signal {
        @NonNull public final String id;
        @NonNull public final String label;

        Signal(@NonNull String id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** A family heading in the add-layer picker. */
    public static final class Family {
        @NonNull public final String id;
        @NonNull public final String label;

        Family(@NonNull String id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }
    }

    @NonNull public final Map<String, VisType> types;
    @NonNull public final List<Signal> signals;
    @NonNull public final List<Family> families;
    @NonNull public final VisType echo;
    @NonNull public final VisType post;
    /** The controls every layer has (opacity, reactivity, softness, place), read by the host. */
    @NonNull public final VisType layer;

    private VisTypes(@NonNull Map<String, VisType> types, @NonNull List<Signal> signals,
                     @NonNull List<Family> families, @NonNull VisType echo, @NonNull VisType post,
                     @NonNull VisType layer) {
        this.layer = layer;
        this.types = Collections.unmodifiableMap(types);
        this.signals = Collections.unmodifiableList(signals);
        this.families = Collections.unmodifiableList(families);
        this.echo = echo;
        this.post = post;
    }

    @Nullable
    public VisType type(@Nullable String id) {
        return id == null ? null : types.get(id);
    }

    @NonNull
    public static VisTypes parse(@NonNull String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, VisType> types = new LinkedHashMap<>();
        JsonObject t = obj(root, "types");
        if (t != null) {
            for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                VisType vt = readType(e.getKey(), e.getValue().getAsJsonObject());
                if (vt != null) types.put(vt.id, vt);
            }
        }
        List<Signal> signals = new ArrayList<>();
        JsonArray sa = arr(root, "signals");
        if (sa != null) {
            for (JsonElement e : sa) {
                if (!e.isJsonObject()) continue;
                String id = str(e.getAsJsonObject(), "id", null);
                if (id != null) signals.add(new Signal(id, str(e.getAsJsonObject(), "label", id)));
            }
        }
        List<Family> families = new ArrayList<>();
        JsonArray fa = arr(root, "families");
        if (fa != null) {
            for (JsonElement e : fa) {
                if (!e.isJsonObject()) continue;
                String id = str(e.getAsJsonObject(), "id", null);
                if (id != null) families.add(new Family(id, str(e.getAsJsonObject(), "label", id)));
            }
        }
        JsonObject eo = obj(root, "echo");
        JsonObject po = obj(root, "post");
        VisType echo = readType("echo", eo != null ? eo : new JsonObject());
        VisType post = readType("post", po != null ? po : new JsonObject());
        if (echo == null || post == null) throw new IllegalArgumentException("types.json lost echo/post");
        JsonObject lo = obj(root, "layer");
        VisType layer = readType("layer", lo != null ? lo : JsonParser.parseString(
                "{\"params\":[{\"id\":\"opacity\",\"label\":\"Opacity\",\"min\":0,\"max\":1,\"def\":1}]}").getAsJsonObject());
        if (layer == null) throw new IllegalArgumentException("types.json lost layer");
        return new VisTypes(types, signals, families, echo, post, layer);
    }

    @Nullable
    private static VisType readType(@NonNull String id, @NonNull JsonObject o) {
        try {
            List<VisKnob> knobs = new ArrayList<>();
            JsonArray pa = arr(o, "params");
            if (pa != null) {
                for (JsonElement e : pa) {
                    if (!e.isJsonObject()) continue;
                    VisKnob k = readKnob(e.getAsJsonObject());
                    if (k != null) knobs.add(k);
                }
            }
            List<Object> inst = new ArrayList<>();
            JsonArray ia = arr(o, "instances");
            if (ia != null) {
                for (JsonElement e : ia) {
                    if (!e.isJsonPrimitive()) continue;
                    if (e.getAsJsonPrimitive().isNumber()) inst.add(e.getAsInt());
                    else inst.add(e.getAsString());
                }
            }
            return new VisType(id, str(o, "label", id), str(o, "family", ""), str(o, "about", ""),
                    str(o, "draw", VisType.DRAW_FULLSCREEN),
                    str(o, "frag", null), str(o, "vert", null), str(o, "sim", null),
                    str(o, "step", null), str(o, "depositVert", null), str(o, "depositFrag", null),
                    str(o, "diffuse", null),
                    (int) num(o, "verts", 6), inst, num(o, "res", 1f), knobs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static VisKnob readKnob(@NonNull JsonObject o) {
        String id = str(o, "id", null);
        if (id == null) return null;
        String[] options = null;
        JsonArray ea = arr(o, "enum");
        if (ea != null) {
            options = new String[ea.size()];
            for (int i = 0; i < ea.size(); i++) options[i] = ea.get(i).getAsString();
        }
        boolean toggle = o.has("toggle") && o.get("toggle").getAsBoolean();
        float def = num(o, "def", 0f);
        float min = num(o, "min", options != null || toggle ? 0f : def);
        float max = num(o, "max", options != null ? options.length - 1 : (toggle ? 1f : def));
        String showKnob = null;
        int[] showValues = null;
        if (o.has("showFor") && o.get("showFor").isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> e : o.getAsJsonObject("showFor").entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                JsonArray va = e.getValue().getAsJsonArray();
                showKnob = e.getKey();
                showValues = new int[va.size()];
                for (int i = 0; i < va.size(); i++) showValues[i] = va.get(i).getAsInt();
                break;
            }
        }
        VisKnob k = new VisKnob(id, str(o, "label", id), VisKnob.parseSlot(str(o, "slot", null)),
                min, max, def, num(o, "step", 0f), o.has("log") && o.get("log").getAsBoolean(),
                toggle, options, str(o, "group", null), showKnob, showValues);
        k.rate = o.has("rate") && o.get("rate").isJsonPrimitive() && o.get("rate").getAsBoolean();
        k.music = o.has("music") && o.get("music").isJsonPrimitive() && o.get("music").getAsBoolean();
        return k;
    }

    @Nullable
    private static JsonObject obj(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonArray arr(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(@NonNull JsonObject o, @NonNull String k, @Nullable String def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        return e.getAsString();
    }

    private static float num(@NonNull JsonObject o, @NonNull String k, float def) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        return e.getAsFloat();
    }
}
