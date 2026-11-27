package com.fadcam.ui.faditor.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** An object's editable audio recipe. Each repeated effect owns separate parameters. */
public final class AudioFxRack {
    public final List<Effect> effects = new ArrayList<>();
    public String routing = "unchanged";
    public float gainDb;
    public float pan;
    public boolean bypassed;
    public boolean studioVoice;

    public static final class Param {
        public final String key, unit;
        public final float min, max, initial;
        private Param(String key, String unit, float min, float max, float initial) {
            this.key = key; this.unit = unit; this.min = min; this.max = max; this.initial = initial;
        }
    }

    private static final Param[] EQ = {
            new Param("frequency", "Hz", 30, 16000, 1500),
            new Param("gain", "dB", -18, 18, 0),
            new Param("q", "Q", .2f, 10, 1)};
    private static final Param[] COMPRESSOR = {
            new Param("threshold", "dB", -60, 0, -18),
            new Param("ratio", ":1", 1, 20, 3),
            new Param("attack", "ms", .1f, 200, 20),
            new Param("release", "ms", 10, 1500, 200),
            new Param("makeup", "dB", 0, 20, 0)};
    private static final Param[] GATE = {
            new Param("threshold", "dB", -70, -5, -45),
            new Param("ratio", ":1", 1, 20, 3),
            new Param("attack", "ms", .1f, 100, 8),
            new Param("release", "ms", 10, 1500, 150)};
    private static final Param[] LIMITER = {
            new Param("ceiling", "dB", -18, 0, -1),
            new Param("release", "ms", 10, 1000, 80)};
    private static final Param[] DEESSER = {
            new Param("intensity", "%", 0, 100, 30),
            new Param("reduction", "%", 0, 100, 50),
            new Param("frequency", "%", 0, 100, 50)};
    private static final Param[] DEHUM = {
            new Param("frequency", "Hz", 40, 70, 60),
            new Param("q", "Q", 5, 60, 30),
            new Param("depth", "dB", 0, 35, 18)};
    private static final Param[] NONE = {};

    public static Param[] params(String type) {
        switch (type) {
            case "eq": return EQ;
            case "compressor": return COMPRESSOR;
            case "gate": return GATE;
            case "limiter": return LIMITER;
            case "deesser": return DEESSER;
            case "dehum": return DEHUM;
            default: return NONE;
        }
    }

    public static final class Effect {
        public final String type;
        public boolean enabled = true;
        private final float[] values;

        public Effect(String type) {
            this.type = type;
            Param[] controls = params(type);
            if (controls.length == 0) throw new IllegalArgumentException("Unknown effect " + type);
            values = new float[controls.length];
            for (int i = 0; i < controls.length; i++) values[i] = controls[i].initial;
        }
        private int index(String key) {
            Param[] controls = params(type);
            for (int i = 0; i < controls.length; i++) if (key.equals(controls[i].key)) return i;
            throw new IllegalArgumentException("Unknown " + type + " parameter " + key);
        }
        public float get(String key) { return values[index(key)]; }
        public void set(String key, float value) {
            int i = index(key);
            Param spec = params(type)[i];
            values[i] = clamp(value, spec.min, spec.max, spec.initial);
        }
    }

    public boolean isIdentity() {
        if (!"unchanged".equals(routing) || gainDb != 0 || pan != 0 || studioVoice) return false;
        if (!bypassed) for (Effect effect : effects) if (effect.enabled) return false;
        return true;
    }

    public boolean hasSettings() {
        return !effects.isEmpty() || bypassed || !isIdentity();
    }

    public AudioFxRack copy() {
        AudioFxRack result = new AudioFxRack();
        result.routing = routing; result.gainDb = gainDb; result.pan = pan;
        result.bypassed = bypassed; result.studioVoice = studioVoice;
        for (Effect old : effects) {
            Effect item = new Effect(old.type);
            item.enabled = old.enabled;
            System.arraycopy(old.values, 0, item.values, 0, item.values.length);
            result.effects.add(item);
        }
        return result;
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("routing", routing);
        out.addProperty("gainDb", gainDb);
        out.addProperty("pan", pan);
        out.addProperty("bypassed", bypassed);
        out.addProperty("studioVoice", studioVoice);
        JsonArray items = new JsonArray();
        for (Effect e : effects) {
            JsonObject item = new JsonObject();
            item.addProperty("type", e.type);
            item.addProperty("enabled", e.enabled);
            for (Param spec : params(e.type)) item.addProperty(spec.key, e.get(spec.key));
            items.add(item);
        }
        out.add("effects", items);
        return out;
    }

    public static AudioFxRack fromJson(JsonObject json) {
        AudioFxRack out = new AudioFxRack();
        if (json == null) return out;
        JsonElement route = json.get("routing");
        if (route != null && route.isJsonPrimitive()) {
            String value = route.getAsString();
            if ("swap".equals(value) || "left".equals(value) || "right".equals(value)) out.routing = value;
        }
        out.gainDb = clamp(number(json, "gainDb", 0), -60, 24, 0);
        out.pan = clamp(number(json, "pan", 0), -1, 1, 0);
        out.bypassed = flag(json, "bypassed", false);
        out.studioVoice = flag(json, "studioVoice", false);
        JsonElement list = json.get("effects");
        if (list == null || !list.isJsonArray()) return out;
        for (JsonElement element : list.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonElement kind = item.get("type");
            if (kind == null || !kind.isJsonPrimitive() || params(kind.getAsString()).length == 0) continue;
            Effect effect = new Effect(kind.getAsString());
            effect.enabled = flag(item, "enabled", true);
            for (Param spec : params(effect.type)) effect.set(spec.key, number(item, spec.key, spec.initial));
            out.effects.add(effect);
        }
        return out;
    }

    private static float number(JsonObject o, String k, float fallback) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        return e.getAsFloat();
    }

    private static boolean flag(JsonObject o, String k, boolean fallback) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    private static float clamp(float value, float min, float max, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }
}
