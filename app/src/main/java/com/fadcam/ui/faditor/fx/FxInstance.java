package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE card in an {@link FxStack} — an effect chosen from the {@link FxRegistry}, the values its
 * parameters currently hold, and the fold controls every card carries.
 *
 * <p><b>Self-serializing</b>, the {@code CompositingSpec} idiom rather than the serializer-side
 * one: the class that knows what a field means is the class that writes it.</p>
 *
 * <p><b>The slot is the identity.</b> Cards are reordered by dragging, and keyframe tracks are
 * named {@code "fx" + slot + "." + param} — so a slot that renumbered on a reorder would hand
 * card 3's animation to card 2. Exactly the rule {@code CompositingSpec.MaskShape.slot} follows,
 * and for exactly the same reason (spec risk R11). {@link FxStack#add} assigns them and never
 * reuses one.</p>
 *
 * <p>Android-free: gson and annotations only, so the JVM harness can reach it.</p>
 */
public final class FxInstance {

    /** Registry id of the effect this card runs. */
    @NonNull public final String effectId;

    /** STABLE identity — never reused, never renumbered on reorder or delete. */
    public final int slot;

    /**
     * Authored parameter values, by {@link FxParam#name}. Absent means "the descriptor's
     * default", so a registry that gains a parameter does not have to migrate every project
     * that predates it — the new slider simply starts where the author said it should.
     */
    @NonNull private final Map<String, float[]> values = new LinkedHashMap<>();

    /** Bypass. A disabled card contributes NO pass, so bypassing is also the cheap way out. */
    public boolean enabled = true;

    /** How much of this card's result is folded over what was there. 0..1. */
    public float opacity = 1f;

    /**
     * Fold mode, in the SAME wire values {@code Clip.getOverlayBlendMode()} uses — so the FX
     * stack and a PiP's blend mode cannot come to mean different things by the same name.
     */
    @NonNull public String blendMode = com.fadcam.ui.faditor.model.BlendModes.NORMAL;

    /** UI state, persisted so a stack reopens looking how it was left. */
    public boolean collapsed = false;

    public FxInstance(@NonNull String effectId, int slot) {
        this.effectId = effectId;
        this.slot = slot;
    }

    /** The definition this card runs, or null if the registry no longer has it. */
    @Nullable
    public FxEffectDef def() { return FxRegistry.get(effectId); }

    // ── Values ──────────────────────────────────────────────────────────────

    /**
     * This card's value for {@code p}, clamped to the descriptor's rails, falling back to the
     * default when unset. Never returns the stored array — a caller that mutated it would edit
     * the project without going through {@link #set}.
     */
    @NonNull
    public float[] get(@NonNull FxParam p) {
        float[] v = values.get(p.name);
        return v == null ? p.defaultValue() : p.clamp(v);
    }

    /** Convenience for the scalar kinds. */
    public float getScalar(@NonNull FxParam p) { return get(p)[0]; }

    public void set(@NonNull FxParam p, @NonNull float[] v) {
        values.put(p.name, p.clamp(v));
    }

    public void set(@NonNull FxParam p, float v) {
        values.put(p.name, p.clamp(new float[]{v}));
    }

    /** True when this card has stored anything for {@code name} (i.e. is not on the default). */
    public boolean isSet(@NonNull String name) { return values.containsKey(name); }

    /** Forget every authored value, returning the card to the registry defaults. */
    public void reset() { values.clear(); }

    // ── Keyframe track names ────────────────────────────────────────────────

    /**
     * The track animating {@code p} on this card: {@code "fx" + slot + "." + name}, with a
     * component suffix only when the parameter needs one.
     *
     * <p>The FX panel builds its rows with this same string, so {@code KeyframeDiamondControl}
     * needs no translation layer between what the UI shows and what the stack stores.</p>
     */
    @NonNull
    public String track(@NonNull FxParam p, int component) {
        return "fx" + slot + "." + p.name + p.componentSuffix(component);
    }

    /** @see #track(FxParam, int) */
    @NonNull
    public String track(@NonNull FxParam p) { return track(p, 0); }

    // ── Serialization ───────────────────────────────────────────────────────

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", effectId);
        o.addProperty("slot", slot);
        // Every flag is omitted at its default, so a plain untouched card is four keys. The
        // additive-schema rule the rest of the model follows.
        if (!enabled) o.addProperty("off", true);
        if (opacity != 1f) o.addProperty("opacity", opacity);
        if (!com.fadcam.ui.faditor.model.BlendModes.NORMAL.equals(blendMode)) {
            o.addProperty("blend", blendMode);
        }
        if (collapsed) o.addProperty("collapsed", true);
        if (!values.isEmpty()) {
            JsonObject v = new JsonObject();
            for (Map.Entry<String, float[]> e : values.entrySet()) {
                float[] a = e.getValue();
                if (a.length == 1) {
                    v.addProperty(e.getKey(), a[0]);
                } else {
                    JsonArray arr = new JsonArray();
                    for (float f : a) arr.add(f);
                    v.add(e.getKey(), arr);
                }
            }
            o.add("v", v);
        }
        return o;
    }

    /**
     * @return null when the object names an effect this build does not have. Dropping the card
     *         is the right degradation: rendering an unknown effect is impossible, and keeping
     *         a placeholder that silently does nothing is the invisible-state trap.
     */
    @Nullable
    public static FxInstance fromJson(@Nullable JsonObject o) {
        if (o == null || !o.has("id")) return null;
        String id = o.get("id").getAsString();
        FxEffectDef def = FxRegistry.get(id);
        if (def == null) return null;
        FxInstance fx = new FxInstance(id, o.has("slot") ? o.get("slot").getAsInt() : 0);
        fx.enabled = !(o.has("off") && o.get("off").getAsBoolean());
        if (o.has("opacity")) fx.opacity = Math.max(0f, Math.min(1f, o.get("opacity").getAsFloat()));
        if (o.has("blend")) fx.blendMode = o.get("blend").getAsString();
        fx.collapsed = o.has("collapsed") && o.get("collapsed").getAsBoolean();
        if (o.has("v") && o.get("v").isJsonObject()) {
            JsonObject v = o.getAsJsonObject("v");
            for (FxParam p : def.params) {
                if (!v.has(p.name)) continue;
                try {
                    if (v.get(p.name).isJsonArray()) {
                        JsonArray arr = v.getAsJsonArray(p.name);
                        float[] a = new float[arr.size()];
                        for (int i = 0; i < a.length; i++) a[i] = arr.get(i).getAsFloat();
                        fx.set(p, a);
                    } else {
                        fx.set(p, v.get(p.name).getAsFloat());
                    }
                } catch (RuntimeException ignored) {
                    // Hand-edited or AI-authored JSON degrades to the default rather than
                    // taking the project load down — the tolerance rule this model follows.
                }
            }
            // §3.11 migration: gradient_map used to be two colours (lowColor/highColor). A
            // project written before the ramp landed still has those and no ramp — load them as
            // a 2-stop ramp rather than dropping the user's grading to the default.
            if ("gradient_map".equals(id) && v.has("lowColor") && v.has("highColor")
                    && !v.has("ramp")) {
                FxParam ramp = def.param("ramp");
                if (ramp != null) {
                    try {
                        float[] lo = toRgb(v.getAsJsonArray("lowColor"));
                        float[] hi = toRgb(v.getAsJsonArray("highColor"));
                        GradientRamp r = new GradientRamp();
                        r.colorStops.clear();
                        r.opacityStops.clear();
                        r.addColorStop(0f, packRgb(lo));
                        r.addColorStop(1f, packRgb(hi));
                        r.addOpacityStop(0f, 1f);
                        r.addOpacityStop(1f, 1f);
                        fx.set(ramp, r.toFloatArray());
                    } catch (RuntimeException ignored) { }
                }
            }
            // W6 migration: the Curve shape used to be ONE quadratic control point — a vec2 param
            // named "curve" — before the editable bezier path replaced it. Without this, a project
            // saved with a bent curve loads with the param unrecognised and silently straightens:
            // no crash, no message, just the user's shape gone.
            //
            // A quadratic IS a cubic, exactly: for (p0, p1, p2) the equal cubic has controls
            // c1 = p0 + 2/3(p1-p0) and c2 = p2 + 2/3(p1-p2). The path model carries ONE handle per
            // anchor where the outgoing control is a+h and the incoming is a-h, so h_start =
            // 2/3(p1-start) and h_end = 2/3(end-p1). No intermediate vertex is needed and the
            // curve is reproduced rather than approximated.
            //
            // EXACT only for the default centre (0.5, 0.5) and angle 0, which is where the old
            // shape sat unless the user moved it: the old curve was evaluated in centre-relative,
            // rotated, ASPECT-corrected space, and the frame's aspect is not knowable at load
            // time. A moved centre or a non-zero angle therefore lands the same bend on the
            // default gradient line. Recovering the bend approximately beats dropping it.
            if ("gradient_fill".equals(id) && v.has("curve") && !v.has("path")) {
                FxParam path = def.param("path");
                if (path != null) {
                    try {
                        JsonArray cp = v.getAsJsonArray("curve");
                        float px = cp.get(0).getAsFloat();
                        float py = cp.get(1).getAsFloat();
                        GradientCurve gc = GradientCurve.defaultCurve();
                        gc.start.hx = (2f / 3f) * (px - gc.start.x);
                        gc.start.hy = (2f / 3f) * (py - gc.start.y);
                        gc.end.hx = (2f / 3f) * (gc.end.x - px);
                        gc.end.hy = (2f / 3f) * (gc.end.y - py);
                        fx.set(path, gc.toFloatArray());
                    } catch (RuntimeException ignored) { }
                }
            }
        }
        return fx;
    }

    /** Read a JsonArray as a float[] (missing/odd entries → 0). */
    private static float[] toRgb(@NonNull JsonArray a) {
        float[] out = new float[3];
        for (int i = 0; i < Math.min(3, a.size()); i++) {
            try { out[i] = a.get(i).getAsFloat(); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /** Pack three 0..1 floats to 0xRRGGBB (alpha forced opaque). */
    private static int packRgb(@NonNull float[] rgb) {
        int r = Math.max(0, Math.min(255, Math.round(rgb[0] * 255f)));
        int g = Math.max(0, Math.min(255, Math.round(rgb[1] * 255f)));
        int b = Math.max(0, Math.min(255, Math.round(rgb[2] * 255f)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @NonNull
    public FxInstance copy() {
        FxInstance c = new FxInstance(effectId, slot);
        for (Map.Entry<String, float[]> e : values.entrySet()) {
            c.values.put(e.getKey(), e.getValue().clone());
        }
        c.enabled = enabled;
        c.opacity = opacity;
        c.blendMode = blendMode;
        c.collapsed = collapsed;
        return c;
    }

    @NonNull
    @Override
    public String toString() { return "fx" + slot + ":" + effectId; }
}
