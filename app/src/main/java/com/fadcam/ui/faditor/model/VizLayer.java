package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One layer in the Joy Viz Engine's modular visualizer stack (SPEC_VIZ_ENGINE §2/§4). A
 * visualizer instance renders an ORDERED stack of these through the four-stage pipeline
 * (AudioMapper → GeometryMapper → Emitter → PaintStage). A legacy single-shape
 * {@link WaveformStyle} (with {@code layers == null}) is auto-wrapped by the renderer into a
 * transient stack of one, so pre-existing styles keep rendering pixel-identical.
 *
 * <p>Pure model: gson-free (self-serializing like {@code CompositingSpec}); tolerant read with
 * clamped/omit-default JSON so a hand-edited or AI-authored design degrades rather than crashes.
 * No per-frame state — the renderer stays a pure function of (style, energies, t, w, h).</p>
 */
public class VizLayer {

    // ── Emitter (shape module) ids ───────────────────────────────────────────
    /** Rounded-cap bars — the legacy default shape. */
    public static final String EMITTER_BARS = "bars";
    /** Poly-line stroke through the band tips. */
    public static final String EMITTER_LINE = "line";
    /** Filled area under the line. */
    public static final String EMITTER_FILLED = "filled";
    /** Circle per band, radius ∝ energy (P1's proof the pipeline generalizes). */
    public static final String EMITTER_DOTS = "dots";
    // Reserved for LATER phases (kept in the known-set so their JSON round-trips, but NOT drawn
    // yet by WaveformStyleRenderer — see spec §5 P2/P3).
    public static final String EMITTER_SQUARES = "squares";
    public static final String EMITTER_PEAKS = "peaks";
    public static final String EMITTER_RING = "ring";
    public static final String EMITTER_PARTICLES = "particles";

    /** Blend modes (PaintStage). */
    public static final String BLEND_NORMAL = "normal";
    public static final String BLEND_ADD = "add"; // neon additive stacking

    /** Multi-stop gradient axes (P4, spec §2 PaintStage). */
    /** Vertical, along amplitude — the legacy 2-stop direction (top→bottom over the strip height). */
    public static final String GRAD_AXIS_AMPLITUDE = "amplitude";
    /** Along the band strip (left→right), or — in radial mode — around the ring via a SweepGradient. */
    public static final String GRAD_AXIS_BAND = "band";

    /**
     * One colour stop in a multi-stop gradient (P4). {@code pos} is the fractional position 0..1
     * along the {@link #gradientAxis}; {@code color} is a hex string. Self-serializing like the
     * enclosing layer: tolerant read (a malformed stop is dropped by {@link VizLayer#fromJson}).
     */
    public static class GradStop {
        public float pos;
        @NonNull public String color;

        public GradStop(float pos, @NonNull String color) {
            this.pos = pos;
            this.color = color;
        }

        @NonNull
        JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("pos", pos);
            j.addProperty("color", color);
            return j;
        }

        /** Parse one stop; returns {@code null} when the object is malformed (skip-and-continue). */
        @Nullable
        static GradStop fromJson(@Nullable JsonElement e) {
            if (e == null || !e.isJsonObject()) return null;
            JsonObject j = e.getAsJsonObject();
            try {
                if (!j.has("pos") || !j.has("color") || j.get("color").isJsonNull()) return null;
                float p = j.get("pos").getAsFloat();
                if (Float.isNaN(p)) return null;
                String c = j.get("color").getAsString();
                if (c == null || c.isEmpty()) return null;
                return new GradStop(Math.max(0f, Math.min(1f, p)), c);
            } catch (RuntimeException ex) {
                return null;
            }
        }

        @NonNull
        GradStop copy() {
            return new GradStop(pos, color);
        }
    }

    /** Which shape module draws this layer. */
    public String emitter = EMITTER_BARS;

    // ── Geometry params ──────────────────────────────────────────────────────
    public float barWidthDp = 3f;
    public float barGapDp = 1f;
    public float cornerRadiusDp = 2f;

    // ── Paint params ─────────────────────────────────────────────────────────
    /** Primary colour (hex, e.g. "#00E676"). */
    public String color = "#00E676";
    /** Optional vertical gradient endpoints; fall back to {@link #color} when null. */
    @Nullable public String gradientStart;
    @Nullable public String gradientEnd;
    /**
     * Optional multi-stop gradient (P4, spec §2). {@code null} = fall back to the 2-stop
     * {@link #gradientStart}/{@link #gradientEnd} (or the solid {@link #color}). When present it
     * wins and must carry ≥2 valid stops (enforced on read). Direction is {@link #gradientAxis}.
     */
    @Nullable public List<GradStop> gradientStops;
    /** {@link #GRAD_AXIS_AMPLITUDE} (default, vertical — legacy direction) or {@link #GRAD_AXIS_BAND}. */
    public String gradientAxis = GRAD_AXIS_AMPLITUDE;
    /** Optional glow; ignored when {@link #glowRadiusDp} <= 0 or {@link #glowColor} is null. */
    @Nullable public String glowColor;
    public float glowRadiusDp = 0f;

    /**
     * Optional drop shadow (PaintStage). {@code null} shadowColor = off. Rendered by the renderer as
     * a blurred FIRST pass (translated by dx/dy), NOT via {@code Paint.setShadowLayer} which is
     * unreliable on a hardware canvas for arbitrary shapes.
     */
    @Nullable public String shadowColor;
    public float shadowDx = 0f;
    public float shadowDy = 2f;
    public float shadowRadiusDp = 4f;

    /** Layer opacity 0..1 (1 = fully opaque, matches legacy). */
    public float opacity = 1f;
    /** {@link #BLEND_NORMAL} or {@link #BLEND_ADD}. */
    public String blend = BLEND_NORMAL;
    /**
     * Edge softness 0..1 (P3). 0 = hard edge (legacy, no mask filter); >0 blurs the layer's solid
     * pass with a NORMAL {@code BlurMaskFilter} — the spec's "soft edged things".
     */
    public float softness = 0f;
    /**
     * Motion-trail echo passes 0..6 (P3). Each trail k redraws the emitter with the band energies
     * sampled at t − k·Δ and decaying alpha — stateless per spec §3, so scrub/export agree.
     */
    public int trailCount = 0;

    // ── Particle params (P3, EMITTER_PARTICLES only) ─────────────────────────
    /** Particles per band 1..8 (total per layer is budget-clamped by the renderer). */
    public int particleCount = 4;
    /** Travel-speed multiplier 0.1..4 (1 = default drift rate). */
    public float particleSpeed = 1f;

    // ── GeometryMapper params ────────────────────────────────────────────────
    /** Fraction 0..1 of the strip/arc actually used (1 = full, matches legacy). */
    public float spread = 1f;
    /** Phase / rotation offset in degrees (0 = none). */
    public float phaseDeg = 0f;
    /** Mirror the band placement order in mapper space. */
    public boolean mirror = false;
    /** Extra visual gain applied to the shared band energies for THIS layer (1 = identity). */
    public float gain = 1f;

    // ── AudioMapper response (P4, Muviz "Audio Response Rate", stateless) ─────
    /**
     * Attack (slow rise) smoothing in ms, 0..1000. 0 = off = legacy identical. When &gt;0 the layer's
     * energies are a trailing moving average of taps at t − k·STEP, delaying the rise. Stateless —
     * computed from the shared {@code TapSampler}, so preview/scrub/export agree (spec §2/§3).
     */
    public float attackMs = 0f;
    /**
     * Release (slow decay) smoothing in ms, 0..1000. 0 = off = legacy identical. When &gt;0 the layer's
     * energies are a peak-hold-with-envelope over taps at t − k·STEP (same pattern as computePeaks),
     * so a transient lingers and decays. Stateless (spec §2/§3).
     */
    public float releaseMs = 0f;

    public VizLayer() {}

    /** True when {@code id} is a shape the engine knows about (drawn now or reserved for a later
     *  phase). Unknown strings are dropped by the IO layer so a typo never crashes a load. */
    public static boolean isKnownEmitter(@Nullable String id) {
        return EMITTER_BARS.equals(id) || EMITTER_LINE.equals(id) || EMITTER_FILLED.equals(id)
                || EMITTER_DOTS.equals(id) || EMITTER_SQUARES.equals(id) || EMITTER_PEAKS.equals(id)
                || EMITTER_RING.equals(id) || EMITTER_PARTICLES.equals(id);
    }

    @NonNull
    public VizLayer copy() {
        VizLayer l = new VizLayer();
        l.emitter = emitter;
        l.barWidthDp = barWidthDp;
        l.barGapDp = barGapDp;
        l.cornerRadiusDp = cornerRadiusDp;
        l.color = color;
        l.gradientStart = gradientStart;
        l.gradientEnd = gradientEnd;
        if (gradientStops != null) {
            l.gradientStops = new ArrayList<>(gradientStops.size());
            for (GradStop s : gradientStops) l.gradientStops.add(s.copy());
        }
        l.gradientAxis = gradientAxis;
        l.glowColor = glowColor;
        l.glowRadiusDp = glowRadiusDp;
        l.shadowColor = shadowColor;
        l.shadowDx = shadowDx;
        l.shadowDy = shadowDy;
        l.shadowRadiusDp = shadowRadiusDp;
        l.opacity = opacity;
        l.blend = blend;
        l.softness = softness;
        l.trailCount = trailCount;
        l.particleCount = particleCount;
        l.particleSpeed = particleSpeed;
        l.spread = spread;
        l.phaseDeg = phaseDeg;
        l.mirror = mirror;
        l.gain = gain;
        l.attackMs = attackMs;
        l.releaseMs = releaseMs;
        return l;
    }

    // ── JSON (self-serializing, tolerant read, omit-defaults write) ───────────

    @NonNull
    public JsonObject toJson() {
        JsonObject j = new JsonObject();
        j.addProperty("emitter", emitter); // identity — always written
        if (barWidthDp != 3f) j.addProperty("barWidthDp", barWidthDp);
        if (barGapDp != 1f) j.addProperty("barGapDp", barGapDp);
        if (cornerRadiusDp != 2f) j.addProperty("cornerRadiusDp", cornerRadiusDp);
        if (color != null && !"#00E676".equals(color)) j.addProperty("color", color);
        if (gradientStart != null) j.addProperty("gradientStart", gradientStart);
        if (gradientEnd != null) j.addProperty("gradientEnd", gradientEnd);
        if (gradientStops != null && gradientStops.size() >= 2) {
            JsonArray stops = new JsonArray();
            for (GradStop s : gradientStops) stops.add(s.toJson());
            j.add("gradientStops", stops);
        }
        if (gradientAxis != null && !GRAD_AXIS_AMPLITUDE.equals(gradientAxis)) {
            j.addProperty("gradientAxis", gradientAxis);
        }
        if (glowColor != null) j.addProperty("glowColor", glowColor);
        if (glowRadiusDp != 0f) j.addProperty("glowRadiusDp", glowRadiusDp);
        if (shadowColor != null) j.addProperty("shadowColor", shadowColor);
        if (shadowDx != 0f) j.addProperty("shadowDx", shadowDx);
        if (shadowDy != 2f) j.addProperty("shadowDy", shadowDy);
        if (shadowRadiusDp != 4f) j.addProperty("shadowRadiusDp", shadowRadiusDp);
        if (opacity != 1f) j.addProperty("opacity", opacity);
        if (blend != null && !BLEND_NORMAL.equals(blend)) j.addProperty("blend", blend);
        if (softness != 0f) j.addProperty("softness", softness);
        if (trailCount != 0) j.addProperty("trailCount", trailCount);
        if (particleCount != 4) j.addProperty("particleCount", particleCount);
        if (particleSpeed != 1f) j.addProperty("particleSpeed", particleSpeed);
        if (spread != 1f) j.addProperty("spread", spread);
        if (phaseDeg != 0f) j.addProperty("phaseDeg", phaseDeg);
        if (mirror) j.addProperty("mirror", true);
        if (gain != 1f) j.addProperty("gain", gain);
        if (attackMs != 0f) j.addProperty("attackMs", attackMs);
        if (releaseMs != 0f) j.addProperty("releaseMs", releaseMs);
        return j;
    }

    @NonNull
    public static VizLayer fromJson(@Nullable JsonObject j) {
        VizLayer l = new VizLayer();
        if (j == null) return l;
        try {
            l.emitter = optString(j, "emitter", EMITTER_BARS);
            l.barWidthDp = optFloat(j, "barWidthDp", 3f);
            l.barGapDp = optFloat(j, "barGapDp", 1f);
            l.cornerRadiusDp = optFloat(j, "cornerRadiusDp", 2f);
            l.color = optString(j, "color", "#00E676");
            l.gradientStart = j.has("gradientStart") ? j.get("gradientStart").getAsString() : null;
            l.gradientEnd = j.has("gradientEnd") ? j.get("gradientEnd").getAsString() : null;
            l.gradientStops = readStops(j);
            l.gradientAxis = optString(j, "gradientAxis", GRAD_AXIS_AMPLITUDE);
            l.glowColor = j.has("glowColor") ? j.get("glowColor").getAsString() : null;
            l.glowRadiusDp = optFloat(j, "glowRadiusDp", 0f);
            l.shadowColor = j.has("shadowColor") && !j.get("shadowColor").isJsonNull()
                    ? j.get("shadowColor").getAsString() : null;
            l.shadowDx = optFloat(j, "shadowDx", 0f);
            l.shadowDy = optFloat(j, "shadowDy", 2f);
            l.shadowRadiusDp = optFloat(j, "shadowRadiusDp", 4f);
            l.opacity = clamp(optFloat(j, "opacity", 1f), 0f, 1f);
            l.blend = optString(j, "blend", BLEND_NORMAL);
            l.softness = clamp(optFloat(j, "softness", 0f), 0f, 1f);
            l.trailCount = (int) clamp(optFloat(j, "trailCount", 0f), 0f, 6f);
            l.particleCount = (int) clamp(optFloat(j, "particleCount", 4f), 1f, 8f);
            l.particleSpeed = clamp(optFloat(j, "particleSpeed", 1f), 0.1f, 4f);
            l.spread = clamp(optFloat(j, "spread", 1f), 0f, 1f);
            l.phaseDeg = optFloat(j, "phaseDeg", 0f);
            l.mirror = j.has("mirror") && j.get("mirror").getAsBoolean();
            l.gain = optFloat(j, "gain", 1f);
            l.attackMs = clamp(optFloat(j, "attackMs", 0f), 0f, 1000f);
            l.releaseMs = clamp(optFloat(j, "releaseMs", 0f), 0f, 1000f);
        } catch (RuntimeException e) {
            // Malformed layer degrades to whatever parsed before the fault.
        }
        return l;
    }

    /**
     * Tolerant multi-stop read (P4): drop malformed stops, sort by {@code pos}, and require ≥2 valid
     * stops — otherwise return {@code null} so the layer falls back to the 2-stop/solid path. Never
     * throws (a hand-edited or AI-authored gradient degrades rather than crashing the load).
     */
    @Nullable
    private static List<GradStop> readStops(@NonNull JsonObject j) {
        if (!j.has("gradientStops") || !j.get("gradientStops").isJsonArray()) return null;
        List<GradStop> stops = new ArrayList<>();
        for (JsonElement e : j.getAsJsonArray("gradientStops")) {
            GradStop s = GradStop.fromJson(e);
            if (s != null) stops.add(s);
        }
        if (stops.size() < 2) return null;
        Collections.sort(stops, (a, b) -> Float.compare(a.pos, b.pos));
        return stops;
    }

    private static float optFloat(@NonNull JsonObject j, @NonNull String k, float def) {
        try {
            return j.has(k) ? j.get(k).getAsFloat() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    @NonNull
    private static String optString(@NonNull JsonObject j, @NonNull String k, @NonNull String def) {
        try {
            return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
