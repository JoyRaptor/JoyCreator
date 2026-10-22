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
    /** Speeds with a music link: distance travelled so far, and the clock when last added to. */
    private final double[] dist;
    private final double[] distAt;
    private final boolean[] distOn;
    /**
     * Studio's look-wide key knobs ({@link #host}): x Brightness, + Colour shift, x every speed,
     * x size. Untouched at 1 / 0 / 1 / 1 (the room never sets them).
     */
    private float hostBright = 1f, hostHue = 0f, hostRate = 1f, hostSize = 1f;
    /** Once a host speed was set, speeds keep the travelled path, so going back to 1 never jumps. */
    private boolean rateHosted;
    /** 0 none, 1 Brightness, 2 Colour shift, 3 size: which host value moves knob i. */
    private final byte[] role;

    private KnobSet(@NonNull List<VisKnob> knobs) {
        this.knobs = knobs;
        int n = knobs.size();
        role = new byte[n];
        for (int i = 0; i < n; i++) {
            String id = knobs.get(i).id;
            role[i] = (byte) ("brightness".equals(id) ? 1 : "palShift".equals(id) ? 2 : "size".equals(id) ? 3 : 0);
        }
        base = new float[n];
        modSig = new int[n][];
        modAmt = new float[n][];
        values = new float[n];
        dist = new double[n];
        distAt = new double[n];
        distOn = new boolean[n];
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

    /**
     * Studio's look-wide key knobs for this set, applied from the next {@link #eval}: every
     * Brightness x {@code bright}, every Colour shift + {@code hue} (the palette wraps), every
     * speed x {@code rate} (smoothly: on the travelled path), every size x {@code size}.
     */
    public void host(float bright, float hue, float rate, float size) {
        hostBright = bright;
        hostHue = hue;
        hostRate = rate;
        hostSize = size;
        if (rate != 1f) rateHosted = true;
    }

    /** Evaluate every knob for this frame. {@code signals} null = no music (bases only). */
    public void eval(@Nullable Signals signals) {
        eval(signals, 1f);
    }

    /** As {@link #eval(Signals)}, every music link scaled by {@code react} (a layer's Reactivity). */
    public void eval(@Nullable Signals signals, float react) {
        for (int i = 0; i < base.length; i++) {
            VisKnob k = knobs.get(i);
            float v = base[i];
            int[] ms = modSig[i];
            if (ms != null && signals != null) {
                float range = k.range();
                float[] amt = modAmt[i];
                for (int j = 0; j < ms.length; j++) v += signals.v[ms[j]] * amt[j] * range * react;
                v = Math.max(k.min - range, Math.min(k.max + range, v));
            }
            switch (role[i]) {
                case 1: v *= hostBright; break;
                case 2: v += hostHue; break;
                case 3: v *= hostSize; break;
                default: break;
            }
            if (k.rate) v *= hostRate;
            values[i] = v;
            if (k.slot >= 0) {
                boolean travels = k.rate && signals != null && (ms != null || rateHosted);
                params[k.slot] = travels ? travelled(i, v, signals.time) : v;
            }
        }
    }

    /**
     * A speed with a music link: the shader works out TIME x speed, which jumps by the whole
     * elapsed time whenever the speed changes (the owner's Spiral Banner spun whole turns on each
     * kick). Keep the distance travelled instead and hand the shader distance / TIME, so its
     * TIME x speed comes out as that distance: the beat speeds it up, smoothly.
     */
    private float travelled(int i, float v, double t) {
        double dt = t - distAt[i];
        if (!distOn[i] || dt < 0) {
            dist[i] = base[i] * t;   // (re)start as if it had always run at its own speed
            distOn[i] = true;
        } else {
            dist[i] += v * dt;
        }
        distAt[i] = t;
        return t > 1e-3 ? (float) (dist[i] / t) : v;
    }

    /** After an edit recompiles the look: speeds carry on from where they were, no jump. */
    public void carryFrom(@Nullable KnobSet prev) {
        if (prev == null) return;
        for (int i = 0; i < knobs.size(); i++) {
            if (!knobs.get(i).rate) continue;
            String id = knobs.get(i).id;
            for (int j = 0; j < prev.knobs.size(); j++) {
                if (!prev.knobs.get(j).id.equals(id) || !prev.distOn[j]) continue;
                dist[i] = prev.dist[j];
                distAt[i] = prev.distAt[j];
                distOn[i] = true;
                break;
            }
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
