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

import java.util.Map;
import java.util.TreeMap;

/**
 * The VisLab values on a Studio visualizer (VISLAB_SPEC section 7, S4), each ready to keyframe
 * (owner 10-18: Studio is to replace After Effects):
 * <ul>
 *   <li>which look shows from when: held keys, the look changes AT a key with no blend (owner
 *       10-22). A key's value is a look id, or {@link #CLASSIC} for the classic style;</li>
 *   <li>Reactivity (0..2, every music link of the look scaled; 1 = as the look is set);</li>
 *   <li>Fade (0..1, the picture's opacity, times the item's own fades).</li>
 * </ul>
 * Kept apart from the item's pose keys, so keying a VisLab value never arms the pose. Key times
 * are the item's local ms, like its pose keys.
 */
public final class VisLabKeys {

    public static final String REACT = "vis_react";
    public static final String FADE = "vis_fade";
    /** A look key's value meaning "the classic style here". */
    public static final String CLASSIC = "";

    @NonNull private final KeyframeSet keys = new KeyframeSet();
    @NonNull private final TreeMap<Long, String> looks = new TreeMap<>();
    private float react = 1f, fade = 1f;

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

    public float reactAt(long localMs) {
        return keys.valueAt(REACT, localMs, react);
    }

    public float fadeAt(long localMs) {
        return keys.valueAt(FADE, localMs, fade);
    }

    public float getReact() { return react; }
    public void setReact(float r) { react = Math.max(0f, Math.min(2f, r)); }
    public float getFade() { return fade; }
    public void setFade(float f) { fade = Math.max(0f, Math.min(1f, f)); }

    /** Reactivity and Fade keys (tracks {@link #REACT}, {@link #FADE}). */
    @NonNull
    public KeyframeSet keys() {
        return keys;
    }

    public boolean isDefault() {
        return looks.isEmpty() && keys.isEmpty() && react == 1f && fade == 1f;
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
        react = o.react;
        fade = o.fade;
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

    /** {"react":1.2,"fade":0.8,"looks":{"0":"halo-drop","4000":""},"keys":{prop:[{t,v,e}]}}, defaults left out. */
    @NonNull
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        if (react != 1f) j.addProperty("react", react);
        if (fade != 1f) j.addProperty("fade", fade);
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
            if (j.has("react")) setReact(j.get("react").getAsFloat());
            if (j.has("fade")) setFade(j.get("fade").getAsFloat());
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
