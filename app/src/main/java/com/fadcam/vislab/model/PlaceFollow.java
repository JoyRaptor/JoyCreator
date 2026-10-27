package com.fadcam.vislab.model;

import androidx.annotation.NonNull;

/**
 * Studio's frame fractions -> a look layer's Place units (R8, "proxies drive looks").
 *
 * <p>A look is drawn in scene units where 1 is half of the frame's SHORTER side (the shaders'
 * {@code pxScene = 2 / min(w, h)}), x to the right and y UP. Studio poses are fractions of the
 * frame, x of the width and y of the height, y DOWN. So a move of {@code dxFrac} across a
 * landscape frame is {@code 2 * aspect * dxFrac} scene units, the same move up a portrait frame
 * is {@code 2 / aspect * dyFrac}, and the sign of y flips.</p>
 */
public final class PlaceFollow {
    private PlaceFollow() {}

    /**
     * @param dxFrac move across, as a fraction of the frame width (right = positive)
     * @param dyFrac move down, as a fraction of the frame height (down = positive)
     * @param aspect frame width / height
     * @param out    [0] = Place "Move across", [1] = Place "Move up", in look units
     */
    public static void toLook(float dxFrac, float dyFrac, float aspect, @NonNull float[] out) {
        float w = aspect > 0.01f ? aspect : 1f, h = 1f;   // the frame, measured in heights
        float half = Math.min(w, h) / 2f;                  // one scene unit, in the same measure
        out[0] = dxFrac * w / half;
        out[1] = -dyFrac * h / half;
    }
}
