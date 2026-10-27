package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.audio.Signals;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link VisScene} made ready to draw: layer types resolved, knobs compiled, palettes
 * linearised. Rebuilt whenever the look is edited ({@link VisScene#edits()} moves); nothing
 * here allocates per frame.
 */
public final class CompiledScene {


    /** One drawable layer. {@link #source} is the layer's JSON (identity for per-layer state). */
    public static final class Layer {
        @NonNull public final JsonObject source;
        @NonNull public final VisType type;
        @NonNull public final KnobSet knobs;
        /** The controls every layer has (types.json "layer"): opacity, reactivity, softness, place. */
        @NonNull public final KnobSet common;
        private final int iOpacity, iReact, iSoft, iX, iY, iSize, iTurn, iSpin;
        /** Place Spin (turns per second, a speed): turns travelled so far, and when last added to. */
        private double spun = Double.NaN, spunAt;
        /** 0 = paint (hides what is behind), 1 = light (adds). */
        public final float add;
        /** Draw into the echo trails (true) or crisp above them. */
        public final boolean underEcho;
        public final float res;
        public final float seed;
        /** Own palette (15 linear floats) or null to use the scene's. */
        @Nullable public final float[] palette;
        /** Picture reference for image layers ("asset:...", a file path, or null for the default logo). */
        @Nullable public final String image;

        Layer(@NonNull JsonObject source, @NonNull VisType type, @NonNull VisType layerType, int index) {
            this.source = source;
            this.type = type;
            knobs = KnobSet.compile(type.knobs, VisScene.knobsOf(source));
            // Layer-wide controls sit on the layer itself ("opacity": 0.8, "turn": {"v": 0, "mod": ...}).
            JsonObject lc = new JsonObject();
            for (VisKnob k : layerType.knobs) if (source.has(k.id)) lc.add(k.id, source.get(k.id));
            common = KnobSet.compile(layerType.knobs, lc);
            iOpacity = layerType.indexOf("opacity");
            iReact = layerType.indexOf("react");
            iSoft = layerType.indexOf("soft");
            iX = layerType.indexOf("x");
            iY = layerType.indexOf("y");
            iSize = layerType.indexOf("size");
            iTurn = layerType.indexOf("turn");
            iSpin = layerType.indexOf("spin");
            add = num(source, "add", 1f);
            JsonElement e = source.get("echo");
            underEcho = !(e != null && e.isJsonPrimitive() && !e.getAsBoolean());
            res = num(source, "res", type.res);
            seed = num(source, "seed", (float) Math.floor(index * 7.31 + 1));
            String[] stops = VisScene.stops(source.get("palette"));
            palette = stops != null ? VisScene.paletteLinear(stops) : null;
            JsonElement im = source.get("image");
            image = im != null && im.isJsonPrimitive() ? im.getAsString() : null;
        }

        public void eval(@Nullable Signals s) {
            common.eval(s);
            knobs.eval(s, react());
            // Spin keeps the turns travelled, so a music link or a new speed speeds it up or slows
            // it down smoothly, never jumping the angle (as the other speeds do, KnobSet.travelled).
            if (iSpin >= 0 && s != null) {
                double t = s.time;
                float sp = common.values[iSpin];
                if (Double.isNaN(spun) || t < spunAt) spun = sp * t;   // as if it had always turned so
                else spun += sp * (t - spunAt);
                spunAt = t;
            }
        }

        private float v(int i, float fallback) {
            return i < 0 ? fallback : common.values[i];
        }

        /**
         * A host that moves this layer on top of its own Place (Studio: a proxy it follows, R8).
         * Look units / a size ratio / degrees, added to the look's own values and never saved in
         * the look. (0, 0, 1, 0) = untouched.
         */
        private float followX, followY, followSize = 1f, followTurn;

        public void follow(float dx, float dy, float sizeMul, float turnDeg) {
            followX = dx;
            followY = dy;
            followSize = sizeMul > 0.001f ? sizeMul : 0.001f;
            followTurn = turnDeg;
        }

        public float opacity() { return v(iOpacity, 1f); }

        /** Scales every music link of this layer: 0 calm, 1 as set, 2 wild. */
        public float react() { return Math.max(0f, v(iReact, 1f)); }

        public float soft() { return Math.max(0f, Math.min(1f, v(iSoft, 0f))); }

        public float x() { return v(iX, 0f) + followX; }

        public float y() { return v(iY, 0f) + followY; }

        public float size() { return Math.max(0.05f, v(iSize, 1f) * followSize); }

        /** Degrees. */
        public float turn() {
            float spin = iSpin >= 0 && !Double.isNaN(spun) ? (float) ((spun % 1.0) * 360.0) : 0f;
            return v(iTurn, 0f) + spin + followTurn;
        }
    }

    @NonNull public final VisScene scene;
    public final int edits;
    @NonNull public final List<Layer> layers;
    @Nullable public final KnobSet echo;
    @NonNull public final KnobSet post;
    @NonNull public final VisTypes types;
    @NonNull public final float[] palette;
    /** Linear rgb, or null for see-through. */
    @Nullable public final float[] background;

    /** After an edit: every speed carries on from the look as it was (see KnobSet#carryFrom). */
    public void carryFrom(@Nullable CompiledScene prev) {
        if (prev == null) return;
        for (Layer L : layers) {
            for (Layer P : prev.layers) {
                if (P.source != L.source) continue;
                L.knobs.carryFrom(P.knobs);
                L.spun = P.spun;   // Spin carries on from where it was
                L.spunAt = P.spunAt;
                break;
            }
        }
        if (echo != null) echo.carryFrom(prev.echo);
        post.carryFrom(prev.post);
    }

    public CompiledScene(@NonNull VisScene scene, @NonNull VisTypes types, @Nullable String[] brandPalette) {
        this.scene = scene;
        this.types = types;
        edits = scene.edits();
        List<Layer> out = new ArrayList<>();
        JsonArray arr = scene.layers();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonObject()) continue;
            JsonObject lo = e.getAsJsonObject();
            JsonElement on = lo.get("on");
            if (on != null && on.isJsonPrimitive() && !on.getAsBoolean()) continue;
            JsonElement t = lo.get("type");
            VisType type = types.type(t != null && t.isJsonPrimitive() ? t.getAsString() : null);
            if (type == null) continue;   // unknown type (newer pack): skipped, not fatal
            out.add(new Layer(lo, type, types.layer, i));
        }
        layers = Collections.unmodifiableList(out);
        JsonObject eo = scene.echoOn();
        echo = eo != null ? KnobSet.compile(types.echo.knobs, VisScene.knobsOf(eo)) : null;
        post = KnobSet.compile(types.post.knobs, scene.postKnobs());
        palette = VisScene.paletteLinear(brandPalette != null ? brandPalette : scene.palette());
        String bg = scene.background();
        background = bg != null ? VisScene.hexToLinear(bg, new float[3], 0) : null;
    }

    private static float num(@NonNull JsonObject o, @NonNull String k, float def) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return def;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean() ? 1f : 0f;
        return e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : def;
    }
}
