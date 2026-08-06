package com.fadcam.ui.faditor.keyframe;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * THE wire format for a {@link KeyframeSet}: <code>{ property: [ {t, v, e}, … ] }</code>.
 *
 * <p><b>Why it is a class and not four copies.</b> This shape was written out by hand in
 * {@code ProjectStorage} for a clip's {@code overlayTransform}, and mask keyframes needed the
 * same thing on a model class that serializes itself. A second hand-rolled copy of a wire format
 * is how two writers end up disagreeing about a field name months later, with the symptom being
 * "my keyframes vanished when I reopened the project" — so both go through here.</p>
 *
 * <p>Byte-compatible with what {@code ProjectStorage} already wrote: same keys, same order, same
 * omit-empty-track rule. Existing projects round-trip unchanged, which is checkable by md5 on a
 * real project file rather than by reading.</p>
 *
 * <p>Reads are tolerant — a malformed key is skipped rather than taking the project down, on the
 * same reasoning as {@code CompositingSpec.fromJson}.</p>
 */
public final class KeyframeCodec {

    private KeyframeCodec() {}

    /** {@code null} when the set is null or holds no keys — callers omit the field entirely. */
    @Nullable
    public static JsonObject toJson(@Nullable KeyframeSet set) {
        if (set == null || set.isEmpty()) return null;
        JsonObject tracksJson = new JsonObject();
        for (KeyframeTrack tr : set.tracks()) {
            if (tr.isEmpty()) continue;
            JsonArray kfArr = new JsonArray();
            for (Keyframe k : tr.keyframes) {
                JsonObject kj = new JsonObject();
                kj.addProperty("t", k.timeMs);
                kj.addProperty("v", k.value);
                kj.addProperty("e", k.easing.name());
                kfArr.add(kj);
            }
            tracksJson.add(tr.property, kfArr);
        }
        return tracksJson;
    }

    /** {@code null} when nothing usable parsed, so a caller can leave its field null. */
    @Nullable
    public static KeyframeSet fromJson(@Nullable JsonObject tracksJson) {
        if (tracksJson == null) return null;
        KeyframeSet ks = new KeyframeSet();
        for (Map.Entry<String, JsonElement> e : tracksJson.entrySet()) {
            try {
                KeyframeTrack tr = ks.getOrCreate(e.getKey());
                JsonArray kfArr = e.getValue().getAsJsonArray();
                for (int k = 0; k < kfArr.size(); k++) {
                    JsonObject kj = kfArr.get(k).getAsJsonObject();
                    tr.put(kj.get("t").getAsLong(), kj.get("v").getAsFloat(),
                            Easing.fromName(kj.get("e").getAsString()));
                }
            } catch (RuntimeException ex) {
                // One bad track does not cost the others.
            }
        }
        return ks.isEmpty() ? null : ks;
    }

    /** Convenience for a caller that must not hand back null. */
    @NonNull
    public static KeyframeSet fromJsonOrEmpty(@Nullable JsonObject tracksJson) {
        KeyframeSet ks = fromJson(tracksJson);
        return ks == null ? new KeyframeSet() : ks;
    }
}
