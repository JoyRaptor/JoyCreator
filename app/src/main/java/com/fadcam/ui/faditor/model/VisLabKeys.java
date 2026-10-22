package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The VisLab values on a Studio visualizer (VISLAB_SPEC section 7, S4), each ready to keyframe
 * (owner 10-18: Studio is to replace After Effects):
 * <ul>
 *   <li>which look shows from when: held keys, the look changes AT a key with no blend (owner
 *       10-22). A key's value is a look id, or {@link #CLASSIC} for the classic style;</li>
 *   <li>React (0..2: how strongly the look hears the music; 1 = as it is);</li>
 *   <li>Fade (0..1, the picture's opacity, times the item's own fades);</li>
 *   <li>the look's own key knobs, look-wide: Bright (x every layer's Brightness), Colour (+ every
 *       layer's Colour shift, wrapping), Size (x every layer's size in its own space) and Speed
 *       (x every running motion, smoothly).</li>
 * </ul>
 * Kept apart from the item's pose keys, so keying a VisLab value never arms the pose. Key times
 * are the item's local ms, like its pose keys. A value at its default is not stored.
 */
public final class VisLabKeys {

    public static final String REACT = "vis_react";
    public static final String FADE = "vis_fade";
    public static final String BRIGHT = "vis_bright";
    public static final String HUE = "vis_hue";
    public static final String SIZE = "vis_size";
    public static final String SPEED = "vis_speed";
    /** Every keyframable value, in drawer order. */
    public static final String[] VALUES = {REACT, FADE, BRIGHT, HUE, SIZE, SPEED};
    /** A look key's value meaning "the classic style here". */
    public static final String CLASSIC = "";

    @NonNull private final KeyframeSet keys = new KeyframeSet();
    @NonNull private final TreeMap<Long, String> looks = new TreeMap<>();
    /** Static values that differ from their default (no key on that value). */
    @NonNull private final Map<String, Float> statics = new HashMap<>();

    /** The value of {@code key} when nothing changes it: 0 for Colour, 1 for the rest. */
    public static float defaultOf(@NonNull String key) {
        return HUE.equals(key) ? 0f : 1f;
    }

    public static float minOf(@NonNull String key) {
        return SIZE.equals(key) ? 0.25f : 0f;
    }

    public static float maxOf(@NonNull String key) {
        switch (key) {
            case FADE:
            case HUE:
                return 1f;
            case SIZE:
                return 2.5f;
            case SPEED:
                return 3f;
            default:
                return 2f;   // React, Bright
        }
    }

    /** The look at item-local {@code localMs}: the last key at or before it, else {@code base}. */
    @Nullable
    public String lookAt(@Nullable String base, long localMs) {
        Map.Entry<Long, String> k = looks.floorEntry(localMs);
        if (k == null) return base;
        return CLASSIC.equals(k.getValue()) ? null : k.getValue();
    }

    @NonNull
    public TreeMap<Long, String> lookKeys() {
        return looks;
    }

    public boolean hasLookKeys() {
        return !looks.isEmpty();
    }

    /** Every VisLab look this item shows at some moment: {@code base} (when set) and each key's. */
    @NonNull
    public java.util.Set<String> lookIds(@Nullable String base) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (base != null) ids.add(base);
        for (String v : looks.values()) if (!CLASSIC.equals(v)) ids.add(v);
        return ids;
    }

    /** True when any look key names a VisLab look (not the classic style). */
    public boolean anyLookKeyIsVisLab() {
        for (String v : looks.values()) if (!CLASSIC.equals(v)) return true;
        return false;
    }

    /** {@code key}'s value at item-local {@code localMs} (its keys, else its static value). */
    public float at(@NonNull String key, long localMs) {
        return keys.valueAt(key, localMs, get(key));
    }

    public float reactAt(long localMs) {
        return at(REACT, localMs);
    }

    public float fadeAt(long localMs) {
        return at(FADE, localMs);
    }

    /** {@code key}'s static value (used where it has no keys). */
    public float get(@NonNull String key) {
        Float v = statics.get(key);
        return v != null ? v : defaultOf(key);
    }

    /** Set {@code key}'s static value, kept in range; its default is not stored. */
    public void set(@NonNull String key, float v) {
        float c = Math.max(minOf(key), Math.min(maxOf(key), v));
        if (c == defaultOf(key)) statics.remove(key);
        else statics.put(key, c);
    }

    /** Keys of every value (tracks named as in {@link #VALUES}). */
    @NonNull
    public KeyframeSet keys() {
        return keys;
    }

    public boolean isDefault() {
        return looks.isEmpty() && keys.isEmpty() && statics.isEmpty();
    }

    @NonNull
    public VisLabKeys copy() {
        VisLabKeys c = new VisLabKeys();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(@NonNull VisLabKeys o) {
        keys.copyFrom(o.keys);
        looks.clear();
        looks.putAll(o.looks);
        statics.clear();
        statics.putAll(o.statics);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VisLabKeys)) return false;
        return toJson().equals(((VisLabKeys) other).toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }

    /** The JSON name of a value: "react", "fade", "bright", "hue", "size", "speed". */
    @NonNull
    private static String jsonName(@NonNull String key) {
        return key.substring("vis_".length());
    }

    /**
     * {"react":1.2,"bright":0.8,"looks":{"0":"halo-drop","4000":""},"keys":{prop:[{t,v,e}]}};
     * defaults left out.
     */
    @NonNull
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        for (String key : VALUES) {
            Float v = statics.get(key);
            if (v != null) j.addProperty(jsonName(key), v);
        }
        if (!looks.isEmpty()) {
            JsonObject l = new JsonObject();
            for (Map.Entry<Long, String> e : looks.entrySet()) l.addProperty(String.valueOf(e.getKey()), e.getValue());
            j.add("looks", l);
        }
        if (!keys.isEmpty()) {
            JsonObject k = new JsonObject();
            for (KeyframeTrack tr : keys.tracks()) {
                if (tr.isEmpty()) continue;
                JsonArray arr = new JsonArray();
                for (Keyframe f : tr.keyframes) {
                    JsonObject kj = new JsonObject();
                    kj.addProperty("t", f.timeMs);
                    kj.addProperty("v", f.value);
                    kj.addProperty("e", f.easing.name());
                    arr.add(kj);
                }
                k.add(tr.property, arr);
            }
            j.add("keys", k);
        }
        return j;
    }

    /** Tolerant read of {@link #toJson}: anything unreadable is left at its default. */
    public void readJson(@NonNull JsonObject j) {
        try {
            for (String key : VALUES) {
                String n = jsonName(key);
                if (j.has(n) && j.get(n).isJsonPrimitive()) set(key, j.get(n).getAsFloat());
            }
            if (j.has("looks") && j.get("looks").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("looks").entrySet()) {
                    try {
                        looks.put(Long.parseLong(e.getKey()), e.getValue().getAsString());
                    } catch (RuntimeException ignored) {
                        // one bad key does not lose the others
                    }
                }
            }
            if (j.has("keys") && j.get("keys").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("keys").entrySet()) {
                    if (!e.getValue().isJsonArray()) continue;
                    KeyframeTrack tr = keys.getOrCreate(e.getKey());
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        JsonObject kj = el.getAsJsonObject();
                        tr.put(kj.get("t").getAsLong(), kj.get("v").getAsFloat(),
                                Easing.fromName(kj.get("e").getAsString()));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // a damaged block keeps what was read so far
        }
    }
}
