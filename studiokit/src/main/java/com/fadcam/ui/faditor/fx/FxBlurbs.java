package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * One plain sentence per effect, for the effect picker's hover labels (standing rule: every
 * button gets one). The picker shows only names like "Warp" or "Duotone"; this says what each
 * does, in the owner's words rather than a compositor's.
 *
 * <p>Keyed by effect id. FxCompilerTest checks every registry effect has one, so a new effect
 * cannot ship without it. Android-free.</p>
 */
public final class FxBlurbs {

    private FxBlurbs() {}

    private static final Map<String, String> BY_ID = new HashMap<>();

    static {
        // Blur
        put("gaussian_blur", "Softens the whole picture evenly");
        put("directional_blur", "Smears the picture in one direction, like fast motion");
        put("pixelate", "Turns the picture into big square blocks");
        put("zoom_blur", "Streaks the picture toward a point, like zooming in fast");
        // Color
        put("brightness", "Makes the picture brighter or darker; Lift raises the shadows");
        put("color_grade", "Exposure, contrast, saturation, warmth and tint in one card");
        put("film", "A film look: soft highlights, lifted shadows, vignette and grain");
        put("invert", "Flips every colour to its opposite, like a negative");
        put("levels", "Moves the middle tones lighter or darker");
        put("gradient_map", "Recolours the picture through a gradient, dark to light");
        put("threshold", "Turns the picture into pure black and white");
        put("posterize", "Cuts the colours down to a few flat bands");
        put("duotone", "Two colours only: one for the shadows, one for the light");
        put("rgb_shift", "Slides red, green and blue apart by a fixed amount");
        put("lens_split", "Colour fringes that grow toward the edges, like a cheap lens");
        put("scanlines", "Dark lines across the picture, like an old screen");
        put("glow", "Bright parts bleed soft light");
        put("flash", "Flashes the picture to a colour; can strobe on its own");
        put("vignette", "Darkens, or colours, the edges of the picture");
        // Generate
        put("solid_color", "Lays a flat colour over the picture");
        put("gradient_fill", "Lays a colour gradient over the picture");
        put("noise", "Adds moving noise or grain");
        // Distort
        put("offset", "Slides the picture sideways or up and down, wrapping round");
        put("glitch_tear", "Rows of the picture tear sideways in bursts");
        put("vhs", "A worn videotape: wobble, colour smear and tape noise");
        put("crt_bend", "Bulges the picture like an old curved TV");
        put("kaleidoscope", "Folds the picture into mirrored wedges around a centre");
        put("mirror", "Reflects one half of the picture onto the other");
        put("shake", "Camera shake: the picture jitters");
        put("wet_floor", "A wet-floor reflection under a line you choose");
        put("ripple", "Water ripples spreading out from a point");
        put("swirl", "Twists the picture around a point");
        put("warp", "Makes the picture flow like heat haze or water");
    }

    private static void put(@NonNull String id, @NonNull String blurb) {
        BY_ID.put(id, blurb);
    }

    /** The sentence for {@code def}, or its name when it has none yet. */
    @NonNull
    public static String of(@NonNull FxEffectDef def) {
        String b = BY_ID.get(def.id);
        return b != null ? b : def.displayName;
    }

    /** True when {@code id} has its own sentence (the test's check). */
    public static boolean has(@NonNull String id) {
        return BY_ID.containsKey(id);
    }
}
