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

    private static final List<VisKnob> OPACITY = Collections.singletonList(
            new VisKnob("opacity", "Opacity", -1, 0f, 1f, 1f, 0f, false, false, null, null));

    /** One drawable layer. {@link #source} is the layer's JSON (identity for per-layer state). */
    public static final class Layer {
        @NonNull public final JsonObject source;
        @NonNull public final VisType type;
        @NonNull public final KnobSet knobs;
        @NonNull public final KnobSet opacity;
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

        Layer(@NonNull JsonObject source, @NonNull VisType type, int index) {
            this.source = source;
            this.type = type;
            knobs = KnobSet.compile(type.knobs, VisScene.knobsOf(source));
            JsonObject op = new JsonObject();
            if (source.has("opacity")) op.add("opacity", source.get("opacity"));
            opacity = KnobSet.compile(OPACITY, op);
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
            knobs.eval(s);
            opacity.eval(s);
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
            out.add(new Layer(lo, type, i));
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
