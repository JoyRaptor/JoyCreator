package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A Photoshop-style gradient ramp: a capped list of colour stops, a capped list of opacity stops
 * (with a bias diamond between each adjacent pair), and the render-time mirror/flip/solid-bands
 * flags — JoyRaptor's "THIS IS TO BE THE STANDARD GRADIENT EDITOR APP-WIDE" (2026-08-08).
 *
 * <p><b>Two colours are always present.</b> A gradient with fewer than two colour stops is not a
 * gradient, so the constructor and every removal path enforce the floor rather than letting the
 * editor produce a degenerate ramp that renders as a flat fill with no way back.</p>
 *
 * <p><b>Capped, like {@code MaskSdf}.</b> The packed form ({@link #toFloatArray()}) feeds FIXED
 * SIZE GLSL uniforms — see {@link FxCompiler}'s gradient uniforms — so a stop count has to have a
 * ceiling. {@link #CAP} matches the reasoning on {@code MaskSdf#MAX_SHAPES}: generous for hand
 * authoring, small enough that a per-card uniform budget stays trivial. A stop added past the cap
 * is simply not added — {@link #addColorStop} and {@link #addOpacityStop} return {@code false}.</p>
 *
 * <p><b>The bias lives on the LEFT stop of a pair</b> ({@link OpacityStop#biasToNext}), as a
 * RATIO within the segment rather than an absolute position. That is what makes it "keep its
 * ratio as you move stops around it" for free: the diamond's on-screen position is always
 * recomputed as {@code lerp(leftPos, rightPos, biasToNext)}, so moving either endpoint moves the
 * diamond with it without this class doing anything.</p>
 *
 * <p><b>Two serializations, deliberately.</b> {@link #toJson()}/{@link #fromJson} is the rich,
 * human-legible form — the one a future caller elsewhere in the app would want. {@link
 * #toFloatArray()}/{@link #fromFloatArray} is the flat form {@link FxParam.Kind#GRADIENT} stores
 * a card's value as, matching every other {@code FxInstance} value being a {@code float[]}. They
 * are independent: a param's stored array round-trips through the flat form only.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
public final class GradientRamp {

    private GradientRamp(boolean seed) {
        if (seed) {
            colorStops.add(new ColorStop(0f, 0xFF000000));
            colorStops.add(new ColorStop(1f, 0xFFFFFFFF));
            opacityStops.add(new OpacityStop(0f, 1f));
            opacityStops.add(new OpacityStop(1f, 1f));
        }
    }

    public GradientRamp() { this(true); }

    /** The most stops either track carries. Beyond this, an add is refused, not silently dropped
     *  later — see {@link #addColorStop}. */
    public static final int CAP = 8;

    /** Floats in {@link #toFloatArray()}: 3 flags + CAP*4 colour + CAP*3 opacity. */
    public static final int PACKED_LENGTH = 3 + CAP * 4 + CAP * 3;

    /** One colour stop. Alpha bits of {@link #color} are ignored — alpha lives on the opacity
     *  track, never on a colour swatch, the same rule {@link FxParam#color} states. */
    public static final class ColorStop {
        public float pos;
        public int color;

        public ColorStop(float pos, int color) { this.pos = pos; this.color = color; }

        @NonNull
        public ColorStop copy() { return new ColorStop(pos, color); }
    }

    /** One opacity stop, plus the bias of the diamond between IT and the next stop. */
    public static final class OpacityStop {
        public float pos;
        public float alpha;
        /** 0..1, default 0.5 (centred). Meaningless on the last stop — there is no "next". */
        public float biasToNext = 0.5f;

        public OpacityStop(float pos, float alpha) { this.pos = pos; this.alpha = alpha; }

        @NonNull
        public OpacityStop copy() {
            OpacityStop o = new OpacityStop(pos, alpha);
            o.biasToNext = biasToNext;
            return o;
        }
    }

    @NonNull public final List<ColorStop> colorStops = new ArrayList<>();
    @NonNull public final List<OpacityStop> opacityStops = new ArrayList<>();
    public boolean mirror;
    public boolean flip;
    public boolean solidBands;

    // ── Mutation (always keeps both lists sorted by position) ─────────────────────────────────

    /** @return false when the track is already at {@link #CAP} — the caller should say so. */
    public boolean addColorStop(float pos, int color) {
        if (colorStops.size() >= CAP) return false;
        colorStops.add(new ColorStop(clamp01(pos), color));
        sortColorStops();
        return true;
    }

    /** Never drops below two stops — a one-stop gradient is not a gradient. */
    public boolean removeColorStop(int index) {
        if (index < 0 || index >= colorStops.size() || colorStops.size() <= 2) return false;
        colorStops.remove(index);
        return true;
    }

    public void moveColorStop(int index, float newPos) {
        if (index < 0 || index >= colorStops.size()) return;
        colorStops.get(index).pos = clamp01(newPos);
        sortColorStops();
    }

    public boolean addOpacityStop(float pos, float alpha) {
        if (opacityStops.size() >= CAP) return false;
        opacityStops.add(new OpacityStop(clamp01(pos), clamp01(alpha)));
        sortOpacityStops();
        return true;
    }

    public boolean removeOpacityStop(int index) {
        if (index < 0 || index >= opacityStops.size() || opacityStops.size() <= 2) return false;
        opacityStops.remove(index);
        return true;
    }

    public void moveOpacityStop(int index, float newPos) {
        if (index < 0 || index >= opacityStops.size()) return;
        opacityStops.get(index).pos = clamp01(newPos);
        sortOpacityStops();
    }

    private void sortColorStops() {
        Collections.sort(colorStops, Comparator.comparingDouble(s -> s.pos));
    }

    private void sortOpacityStops() {
        Collections.sort(opacityStops, Comparator.comparingDouble(s -> s.pos));
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }

    // ── CPU evaluation ──────────────────────────────────────────────────────────────────────
    //
    // Mirrors FxCompiler's fxGradColor/fxGradAlpha exactly — same segment math, same banding,
    // same bias remap — so the editor's own ramp-bar preview is WYSIWYG against the shader
    // rather than an approximation of it. See the class note on FxCompiler's gradient section.

    /** Colour at {@code t} in 0..1, packed 0xRRGGBB. */
    public int sampleColor(float t) {
        t = clamp01(t);
        int n = colorStops.size();
        if (n == 0) return 0xFF000000;
        if (t <= colorStops.get(0).pos) return colorStops.get(0).color;
        for (int i = 0; i < n - 1; i++) {
            ColorStop a = colorStops.get(i), b = colorStops.get(i + 1);
            if (t <= b.pos) {
                float f = segFraction(a.pos, b.pos, t, 0.5f);
                return lerpColor(a.color, b.color, f);
            }
        }
        return colorStops.get(n - 1).color;
    }

    /** Alpha at {@code t} in 0..1, biased per {@link OpacityStop#biasToNext}. */
    public float sampleAlpha(float t) {
        t = clamp01(t);
        int n = opacityStops.size();
        if (n == 0) return 1f;
        if (t <= opacityStops.get(0).pos) return opacityStops.get(0).alpha;
        for (int i = 0; i < n - 1; i++) {
            OpacityStop a = opacityStops.get(i), b = opacityStops.get(i + 1);
            if (t <= b.pos) {
                float f = segFraction(a.pos, b.pos, t, a.biasToNext);
                return a.alpha + (b.alpha - a.alpha) * f;
            }
        }
        return opacityStops.get(n - 1).alpha;
    }

    private float segFraction(float posA, float posB, float t, float bias) {
        float span = Math.max(posB - posA, 0.0001f);
        float f = clamp01((t - posA) / span);
        if (solidBands) return f < 0.5f ? 0f : 1f;
        float m = Math.max(0.02f, Math.min(0.98f, bias));
        return f <= m ? 0.5f * f / m : 1f - 0.5f * (1f - f) / (1f - m);
    }

    private static int lerpColor(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * f);
        int g = Math.round(ag + (bg - ag) * f);
        int bl = Math.round(ab + (bb - ab) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ── Packed float form — what an FxInstance actually stores ────────────────────────────────

    /**
     * Layout: {@code [mirror, flip, solidBands,
     * c0.pos,c0.r,c0.g,c0.b, ... c7,
     * o0.pos,o0.alpha,o0.biasToNext, ... o7]}.
     *
     * <p>Unused slots past the real stop count are padded with a SENTINEL position {@code > 1.0}
     * carrying the last real stop's colour/alpha — the same "overflow silently drops" contract
     * {@code MaskSdf#packShapes} follows. The shader never needs a count uniform because of it:
     * {@code t} is always clamped to 0..1, so a sentinel segment can never be selected, and
     * because it repeats the last real value, even a boundary rounding error picks a colour that
     * is already correct. {@link #fromFloatArray} reverses this by dropping any stop whose
     * unpacked position is {@code > 1.0} — the sentinel and the "real" range are disjoint by
     * construction, so no count needs to travel with the array at all.</p>
     */
    @NonNull
    public float[] toFloatArray() {
        float[] out = new float[PACKED_LENGTH];
        out[0] = mirror ? 1f : 0f;
        out[1] = flip ? 1f : 0f;
        out[2] = solidBands ? 1f : 0f;

        int nc = Math.min(colorStops.size(), CAP);
        int lastColor = nc > 0 ? colorStops.get(nc - 1).color : 0xFF000000;
        int cBase = 3;
        for (int i = 0; i < CAP; i++) {
            int o = cBase + i * 4;
            if (i < nc) {
                ColorStop c = colorStops.get(i);
                out[o] = clamp01(c.pos);
                out[o + 1] = ((c.color >> 16) & 0xFF) / 255f;
                out[o + 2] = ((c.color >> 8) & 0xFF) / 255f;
                out[o + 3] = (c.color & 0xFF) / 255f;
            } else {
                out[o] = 1.01f + 0.01f * (i - nc);   // sentinel: always > 1.0, ascending
                out[o + 1] = ((lastColor >> 16) & 0xFF) / 255f;
                out[o + 2] = ((lastColor >> 8) & 0xFF) / 255f;
                out[o + 3] = (lastColor & 0xFF) / 255f;
            }
        }

        int no = Math.min(opacityStops.size(), CAP);
        float lastAlpha = no > 0 ? opacityStops.get(no - 1).alpha : 1f;
        int oBase = cBase + CAP * 4;
        for (int i = 0; i < CAP; i++) {
            int o = oBase + i * 3;
            if (i < no) {
                OpacityStop s = opacityStops.get(i);
                out[o] = clamp01(s.pos);
                out[o + 1] = clamp01(s.alpha);
                out[o + 2] = clamp01(s.biasToNext);
            } else {
                out[o] = 1.01f + 0.01f * (i - no);
                out[o + 1] = lastAlpha;
                out[o + 2] = 0.5f;
            }
        }
        return out;
    }

    /** Reconstruct from {@link #toFloatArray()}'s layout. Tolerant of a short/garbled array —
     *  falls back to the default ramp rather than throwing, matching {@code FxInstance}'s own
     *  hand-edited-JSON tolerance. */
    @NonNull
    public static GradientRamp fromFloatArray(@Nullable float[] a) {
        GradientRamp r = new GradientRamp(false);
        if (a == null || a.length < PACKED_LENGTH) return defaultRamp();
        r.mirror = a[0] >= 0.5f;
        r.flip = a[1] >= 0.5f;
        r.solidBands = a[2] >= 0.5f;

        int cBase = 3;
        for (int i = 0; i < CAP; i++) {
            int o = cBase + i * 4;
            float pos = a[o];
            if (pos > 1.0f) continue;   // sentinel — not a real stop
            int rr = Math.round(clamp01(a[o + 1]) * 255f);
            int gg = Math.round(clamp01(a[o + 2]) * 255f);
            int bb = Math.round(clamp01(a[o + 3]) * 255f);
            r.colorStops.add(new ColorStop(pos, 0xFF000000 | (rr << 16) | (gg << 8) | bb));
        }
        if (r.colorStops.size() < 2) { r.colorStops.clear();
            r.colorStops.add(new ColorStop(0f, 0xFF000000));
            r.colorStops.add(new ColorStop(1f, 0xFFFFFFFF));
        }
        r.sortColorStops();

        int oBase = cBase + CAP * 4;
        for (int i = 0; i < CAP; i++) {
            int o = oBase + i * 3;
            float pos = a[o];
            if (pos > 1.0f) continue;
            OpacityStop s = new OpacityStop(pos, clamp01(a[o + 1]));
            s.biasToNext = clamp01(a[o + 2]);
            r.opacityStops.add(s);
        }
        if (r.opacityStops.size() < 2) { r.opacityStops.clear();
            r.opacityStops.add(new OpacityStop(0f, 1f));
            r.opacityStops.add(new OpacityStop(1f, 1f));
        }
        r.sortOpacityStops();
        return r;
    }

    @NonNull
    public static GradientRamp defaultRamp() { return new GradientRamp(); }

    // ── copy/undo ───────────────────────────────────────────────────────────────────────────

    @NonNull
    public GradientRamp copy() {
        GradientRamp c = new GradientRamp(false);
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(@NonNull GradientRamp other) {
        colorStops.clear();
        for (ColorStop s : other.colorStops) colorStops.add(s.copy());
        opacityStops.clear();
        for (OpacityStop s : other.opacityStops) opacityStops.add(s.copy());
        mirror = other.mirror;
        flip = other.flip;
        solidBands = other.solidBands;
    }

    // ── Rich JSON — the reusable, human-legible form ───────────────────────────────────────────

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray cs = new JsonArray();
        for (ColorStop s : colorStops) {
            JsonObject j = new JsonObject();
            j.addProperty("pos", s.pos);
            j.addProperty("color", s.color);
            cs.add(j);
        }
        o.add("colors", cs);
        JsonArray os = new JsonArray();
        for (OpacityStop s : opacityStops) {
            JsonObject j = new JsonObject();
            j.addProperty("pos", s.pos);
            j.addProperty("alpha", s.alpha);
            if (s.biasToNext != 0.5f) j.addProperty("bias", s.biasToNext);
            os.add(j);
        }
        o.add("opacity", os);
        if (mirror) o.addProperty("mirror", true);
        if (flip) o.addProperty("flip", true);
        if (solidBands) o.addProperty("solid", true);
        return o;
    }

    @NonNull
    public static GradientRamp fromJson(@Nullable JsonObject o) {
        GradientRamp r = new GradientRamp(false);
        if (o == null) return defaultRamp();
        try {
            if (o.has("colors") && o.get("colors").isJsonArray()) {
                for (com.google.gson.JsonElement e : o.getAsJsonArray("colors")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject j = e.getAsJsonObject();
                    r.colorStops.add(new ColorStop(
                            j.has("pos") ? j.get("pos").getAsFloat() : 0f,
                            j.has("color") ? j.get("color").getAsInt() : 0xFF000000));
                }
            }
            if (o.has("opacity") && o.get("opacity").isJsonArray()) {
                for (com.google.gson.JsonElement e : o.getAsJsonArray("opacity")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject j = e.getAsJsonObject();
                    OpacityStop s = new OpacityStop(
                            j.has("pos") ? j.get("pos").getAsFloat() : 0f,
                            j.has("alpha") ? j.get("alpha").getAsFloat() : 1f);
                    if (j.has("bias")) s.biasToNext = j.get("bias").getAsFloat();
                    r.opacityStops.add(s);
                }
            }
            r.mirror = o.has("mirror") && o.get("mirror").getAsBoolean();
            r.flip = o.has("flip") && o.get("flip").getAsBoolean();
            r.solidBands = o.has("solid") && o.get("solid").getAsBoolean();
        } catch (RuntimeException ignored) {
            return defaultRamp();
        }
        if (r.colorStops.size() < 2) return defaultRamp();
        if (r.opacityStops.size() < 2) {
            r.opacityStops.clear();
            r.opacityStops.add(new OpacityStop(0f, 1f));
            r.opacityStops.add(new OpacityStop(1f, 1f));
        }
        r.sortColorStops();
        r.sortOpacityStops();
        return r;
    }
}
