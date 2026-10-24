package com.fadcam.vislab.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One VisLab primitive (a layer type such as Spectrum, Particles, Nebula), or the scene-level
 * Echo and Finish passes, as declared in {@code types.json}.
 *
 * <p>{@link #draw} names how the host runs it:
 * <ul>
 *   <li>{@code fullscreen}: one triangle over the target, the fragment shader does everything</li>
 *   <li>{@code instanced}: {@link #verts} vertices per instance, instance count from {@link #instances}</li>
 *   <li>{@code particles}, {@code slime}, {@code rd}: living layers with their own state</li>
 *   <li>{@code image}: fullscreen with a picture bound as {@code uImage}</li>
 *   <li>{@code sprite}: fullscreen with a sprite sheet bound as {@code uSheet} and this frame's
 *       place in it as {@code uCell}</li>
 * </ul></p>
 */
public final class VisType {

    public static final String DRAW_FULLSCREEN = "fullscreen";
    public static final String DRAW_INSTANCED = "instanced";
    public static final String DRAW_PARTICLES = "particles";
    public static final String DRAW_SLIME = "slime";
    public static final String DRAW_RD = "rd";
    public static final String DRAW_IMAGE = "image";
    /** A sprite sheet playing frame by frame (see vislab/gl/SpriteLayers). */
    public static final String DRAW_SPRITE = "sprite";
    /** Timed words drawn from a glyph atlas (see vislab/words/WordFeed). */
    public static final String DRAW_WORDS = "words";

    @NonNull public final String id;
    @NonNull public final String label;
    @NonNull public final String family;
    @NonNull public final String about;
    @NonNull public final String draw;
    @Nullable public final String frag;
    @Nullable public final String vert;
    @Nullable public final String sim;
    @Nullable public final String step;
    @Nullable public final String depositVert;
    @Nullable public final String depositFrag;
    @Nullable public final String diffuse;
    public final int verts;
    /** Product terms: a String names a knob whose value is used, an Integer is a constant. */
    @NonNull public final List<Object> instances;
    /** Default render scale (0.5 = half resolution). */
    public final float res;
    @NonNull public final List<VisKnob> knobs;
    private final Map<String, Integer> index = new HashMap<>();

    public VisType(@NonNull String id, @NonNull String label, @NonNull String family,
                   @NonNull String about, @NonNull String draw,
                   @Nullable String frag, @Nullable String vert, @Nullable String sim,
                   @Nullable String step, @Nullable String depositVert,
                   @Nullable String depositFrag, @Nullable String diffuse,
                   int verts, @NonNull List<Object> instances, float res,
                   @NonNull List<VisKnob> knobs) {
        this.id = id;
        this.label = label;
        this.family = family;
        this.about = about;
        this.draw = draw;
        this.frag = frag;
        this.vert = vert;
        this.sim = sim;
        this.step = step;
        this.depositVert = depositVert;
        this.depositFrag = depositFrag;
        this.diffuse = diffuse;
        this.verts = verts;
        this.instances = Collections.unmodifiableList(instances);
        this.res = res;
        this.knobs = Collections.unmodifiableList(knobs);
        for (int i = 0; i < knobs.size(); i++) index.put(knobs.get(i).id, i);
    }

    /** Position of a knob in {@link #knobs}, or -1. */
    public int indexOf(@NonNull String knobId) {
        Integer i = index.get(knobId);
        return i == null ? -1 : i;
    }

    @Nullable
    public VisKnob knob(@NonNull String knobId) {
        int i = indexOf(knobId);
        return i < 0 ? null : knobs.get(i);
    }

    /** Every shader file this type needs, for preloading and for pack validation. */
    @NonNull
    public String[] shaderFiles() {
        String[] all = {frag, vert, sim, step, depositVert, depositFrag, diffuse};
        int n = 0;
        for (String s : all) if (s != null) n++;
        String[] out = new String[n];
        int k = 0;
        for (String s : all) if (s != null) out[k++] = s;
        return out;
    }
}
