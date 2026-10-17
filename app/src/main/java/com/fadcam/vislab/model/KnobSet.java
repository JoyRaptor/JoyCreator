package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.audio.Signals;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * The knobs of one layer (or of Echo / Finish), compiled from the look's JSON once and then
 * evaluated every frame without allocating.
 *
 * <p>A knob's JSON value is either a number, or {@code {"v": base, "mod": {"kick": 0.6}}}:
 * base plus, for every signal, signal x amount x the knob's range. With music on it may
 * overshoot by one range either side (that is part of a punchy look), never further. Missing
 * knobs take the registry default. Same rule as the pilot's {@code evalKnob}.</p>
 */
public final class KnobSet {

    @NonNull public final List<VisKnob> knobs;
    private final float[] base;
    private final int[][] modSig;
    private final float[][] modAmt;
    /** Evaluated this frame, one per knob (host knobs too). */
    public final float[] values;
    /** Evaluated this frame, packed for the shader's {@code uP[12]}. */
    public final float[] params = new float[48];

    private KnobSet(@NonNull List<VisKnob> knobs) {
        this.knobs = knobs;
        int n = knobs.size();
        base = new float[n];
        modSig = new int[n][];
        modAmt = new float[n][];
        values = new float[n];
    }

    /** Compile {@code raw} (may be null: every knob at its default). */
    @NonNull
    public static KnobSet compile(@NonNull List<VisKnob> knobs, @Nullable JsonObject raw) {
        KnobSet s = new KnobSet(knobs);
        for (int i = 0; i < knobs.size(); i++) {
            VisKnob k = knobs.get(i);
            JsonElement e = raw != null ? raw.get(k.id) : null;
            s.base[i] = k.def;
            if (e == null || e.isJsonNull()) continue;
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                s.base[i] = e.getAsFloat();
            } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
                s.base[i] = e.getAsBoolean() ? 1f : 0f;
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                JsonElement v = o.get("v");
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) s.base[i] = v.getAsFloat();
                JsonElement m = o.get("mod");
                if (m != null && m.isJsonObject()) {
                    int count = 0;
                    for (Map.Entry<String, JsonElement> me : m.getAsJsonObject().entrySet()) {
                        if (Signals.indexOf(me.getKey()) >= 0 && me.getValue().isJsonPrimitive()) count++;
                    }
                    if (count > 0) {
                        s.modSig[i] = new int[count];
                        s.modAmt[i] = new float[count];
                        int j = 0;
                        for (Map.Entry<String, JsonElement> me : m.getAsJsonObject().entrySet()) {
                            int si = Signals.indexOf(me.getKey());
                            if (si < 0 || !me.getValue().isJsonPrimitive()) continue;
                            s.modSig[i][j] = si;
                            s.modAmt[i][j] = me.getValue().getAsFloat();
                            j++;
                        }
                    }
                }
            }
        }
        return s;
    }

    /** Evaluate every knob for this frame. {@code signals} null = no music (bases only). */
    public void eval(@Nullable Signals signals) {
        for (int i = 0; i < base.length; i++) {
            VisKnob k = knobs.get(i);
            float v = base[i];
            int[] ms = modSig[i];
            if (ms != null && signals != null) {
                float range = k.range();
                float[] amt = modAmt[i];
                for (int j = 0; j < ms.length; j++) v += signals.v[ms[j]] * amt[j] * range;
                v = Math.max(k.min - range, Math.min(k.max + range, v));
            }
            values[i] = v;
            if (k.slot >= 0) params[k.slot] = v;
        }
    }

    /** This frame's value of knob {@code id}, or {@code fallback} when the type has no such knob. */
    public float value(@NonNull VisType type, @NonNull String id, float fallback) {
        int i = type.indexOf(id);
        return i < 0 ? fallback : values[i];
    }

    /** Static base value (no music), for editors. */
    public float base(int i) {
        return base[i];
    }

    public boolean hasMods(int i) {
        return modSig[i] != null;
    }
}
