package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * The rest of VisLab's look as Studio effects (roadmap R4, second half): Glow (VisLab's bloom),
 * Flash, Vignette, Ripple, Swirl and Warp. With {@link FxVisLabDefs} this is every finish and
 * every warp a VisLab look has, keyframable on any clip or adjustment layer.
 *
 * <p>Same conventions as {@link FxVisLabDefs}: {@code uv} 0..1 with y UP, distances measured
 * against the frame HEIGHT ({@code vec2(FX_ASPECT, 1.0)} turns uv steps into equal picture
 * distances), {@code FX_TIME} is timeline time so preview = export, and nothing calls
 * {@code sin} on a number that grows with time: a phase is wrapped with {@code fract} first.</p>
 *
 * <p><b>Glow is one pass.</b> VisLab blurs its bright parts down a mip chain; a Studio card
 * cannot reach the picture again after a two-pass blur, so Glow reads a soft disc of 24 points
 * (a sunflower spiral, turned a little per pixel so it never shows as dots), keeps only what is
 * brighter than the threshold, and screens it over the picture.</p>
 *
 * <p>Ids are wire values — see {@link FxRegistry}. Add, never rename. Android-free.</p>
 */
final class FxVisLabDefs2 {

    private FxVisLabDefs2() {}

    /**
     * A reading point pushed outside the frame by a warp is folded back in (a triangle wave),
     * the way Mirror and Kaleidoscope do, so no edge column ever smears.
     */
    private static final String FOLD = "return 1.0 - abs(1.0 - mod(q, 2.0));\n";

    /**
     * Bright parts bleed soft light. Radius is in pixels at 1080 tall (FX_STEP), so the glow is
     * the same size at any export resolution. The soft knee (threshold to threshold + 0.15)
     * keeps the edge of what glows from showing as a hard outline.
     */
    private static final String BODY_GLOW =
            "float th = FX_P(threshold);\n"
            + "vec2 st = FX_STEP * FX_P(radius);\n"
            + FxVisLabDefs.hash12("jr", "uv.x / FX_STEP.x", "uv.y / FX_STEP.y")
            + "float rot = jr * 6.28318530718;\n"
            + "vec3 acc = vec3(0.0);\n"
            + "float wsum = 0.0;\n"
            + "for (int i = 0; i < 24; i++) {\n"
            + "  float fi = float(i) + 0.5;\n"
            + "  float rr = sqrt(fi / 24.0);\n"
            + "  float a = fi * 2.39996323 + rot;\n"
            + "  vec3 c = FX_SAMPLE(uv + vec2(cos(a), sin(a)) * rr * st).rgb;\n"
            + "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
            + "  float w = exp(-2.5 * rr * rr);\n"
            + "  acc += c * smoothstep(th, th + 0.15, l) * w;\n"
            + "  wsum += w;\n"
            + "}\n"
            + "vec3 g = clamp(acc / wsum * FX_P(amount), 0.0, 1.0);\n"
            + "return vec4(src.rgb + g - src.rgb * g, src.a);\n";

    /**
     * The picture flashes to a colour. Rate 0 is a steady wash the owner keys by hand (a single
     * flash is two keys on Amount); a rate above 0 strobes that many times a second, each flash
     * jumping in and fading out by Decay.
     */
    private static final String BODY_FLASH =
            "float ph = FX_P(rate) > 0.0 ? fract(FX_TIME * FX_P(rate)) : 0.0;\n"
            + "float k = clamp(FX_P(amount) * exp(-ph * FX_P(decay) * 8.0), 0.0, 1.0);\n"
            + "return vec4(mix(src.rgb, FX_P(color), k), src.a);\n";

    /**
     * The edges fall away to a colour (black by default). Roundness 1 is a circle; 0 follows the
     * frame's own shape. Size is where the falloff starts, from the centre (0) to the corners (1).
     */
    private static final String BODY_VIGNETTE =
            "vec2 d = (uv - vec2(0.5)) * 2.0;\n"
            + "float rnd = FX_P(roundness);\n"
            + "d.x *= mix(1.0, FX_ASPECT, rnd);\n"
            + "float rmax = mix(1.41421356, length(vec2(FX_ASPECT, 1.0)), rnd);\n"
            + "float r = length(d) / rmax;\n"
            + "float e = smoothstep(FX_P(size), FX_P(size) + max(FX_P(softness), 0.01), r);\n"
            + "return vec4(mix(src.rgb, FX_P(color), e * FX_P(amount)), src.a);\n";

    /**
     * Rings travel out from a centre like a drop in water. Rings is how many fit in the frame
     * height; Speed is rings a second (negative runs them inward); Fade weakens them away from
     * the centre.
     */
    private static final String BODY_RIPPLE =
            "vec2 asp = vec2(FX_ASPECT, 1.0);\n"
            + "vec2 c = FX_P(center);\n"
            + "vec2 d = (uv - c) * asp;\n"
            + "float r = length(d);\n"
            + "float ph = fract(r * FX_P(rings) - FX_TIME * FX_P(speed)) * 6.28318530718;\n"
            + "float w = sin(ph) * FX_P(amount) * 0.015 * exp(-r * FX_P(fade) * 2.0);\n"
            + "vec2 q = c + (d + d / max(r, 0.0001) * w) / asp;\n"
            + FOLD;

    /**
     * A twist around a centre, strongest in the middle and fading to nothing at Radius (a share
     * of the frame height). Angle is how far the middle turns, in degrees; key it to spin.
     */
    private static final String BODY_SWIRL =
            "vec2 asp = vec2(FX_ASPECT, 1.0);\n"
            + "vec2 c = FX_P(center);\n"
            + "vec2 d = (uv - c) * asp;\n"
            + "float t = clamp(1.0 - length(d) / max(FX_P(radius), 0.01), 0.0, 1.0);\n"
            + "float a = radians(FX_P(angle)) * t * t;\n"
            + "float ca = cos(a);\n"
            + "float sa = sin(a);\n"
            + "vec2 q = c + vec2(d.x * ca - d.y * sa, d.x * sa + d.y * ca) / asp;\n"
            + FOLD;

    /**
     * The picture flows like heat haze or water (VisLab echo's warp, on the picture itself):
     * two smooth noise fields push the reading point. Scale is how big the swirls are; Speed
     * how fast they drift.
     */
    private static final String BODY_WARP =
            "vec2 p = (uv * 2.0 - 1.0) * vec2(FX_ASPECT, 1.0) * FX_P(scale);\n"
            + "float t = FX_TIME * FX_P(speed);\n"
            + FxVisLabDefs.vnoise("wx", "p.x + t * 0.7", "p.y")
            + FxVisLabDefs.vnoise("wy", "p.x - 3.1", "p.y + t * 0.6")
            + "vec2 q = uv + (vec2(wx, wy) * 2.0 - 1.0) * FX_P(amount) * 0.05"
            + " * vec2(1.0 / FX_ASPECT, 1.0);\n"
            + FOLD;

    /**
     * VisLab's echo on a video: each frame keeps a fading copy of the last one, zoomed, turned and
     * colour-drifted, so moving things leave trails (FEEDBACK: the renderer keeps that copy). Echo
     * crossfades with it (ghosting), Light trails keeps whichever is brighter, Glow trails screens
     * it on. Every rate is per second and Trail length is "kept per 1/30 s", raised to the real
     * frame time, so a 30 fps export and a 60 fps preview build the same trails (echo.frag's rule).
     */
    private static final String BODY_ECHO_TRAILS =
            "float dt = FX_DT;\n"
            + "vec2 asp = vec2(FX_ASPECT, 1.0);\n"
            + "vec2 c = FX_P(center);\n"
            + "vec2 d = (uv - c) * asp;\n"
            + "float z = exp(FX_P(zoom) * dt);\n"
            + "float a = FX_P(turn) * 6.28318530718 * dt;\n"
            + "float ca = cos(a);\n"
            + "float sa = sin(a);\n"
            + "d = vec2(d.x * ca + d.y * sa, d.y * ca - d.x * sa) / z;\n"
            + "vec4 tr = FX_PREV(c + d / asp);\n"
            // Colour drift: turn the trail's hue (YIQ), a little each frame.
            + "float ha = FX_P(hue) * 6.28318530718 * dt;\n"
            + "float hc = cos(ha);\n"
            + "float hs = sin(ha);\n"
            + "float yy = dot(tr.rgb, vec3(0.299, 0.587, 0.114));\n"
            + "float ii = dot(tr.rgb, vec3(0.596, -0.274, -0.322));\n"
            + "float qq = dot(tr.rgb, vec3(0.211, -0.523, 0.312));\n"
            + "float i2 = ii * hc - qq * hs;\n"
            + "float q2 = ii * hs + qq * hc;\n"
            + "tr.rgb = max(vec3(yy + 0.956 * i2 + 0.621 * q2, yy - 0.272 * i2 - 0.647 * q2,"
            + " yy - 1.106 * i2 + 1.703 * q2), 0.0);\n"
            + "float keep = pow(clamp(FX_P(amount), 0.0, 0.99), dt * 30.0);\n"
            + "vec4 cur = FX_SAMPLE(uv);\n"
            + "float m = FX_P(mode);\n"
            + "vec3 o;\n"
            + "if (m < 0.5) o = mix(cur.rgb, tr.rgb, keep);\n"
            + "else if (m < 1.5) o = max(cur.rgb, tr.rgb * keep);\n"
            + "else { vec3 t = tr.rgb * keep; o = cur.rgb + t - cur.rgb * t; }\n"
            + "return vec4(o, cur.a);\n";

    /** Appends the seven, in picker order within each family. */
    static void addTo(@NonNull List<FxEffectDef> defs) {
        // ── Color ──
        defs.add(new FxEffectDef("glow", "Glow",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.SAMPLER, 1, 2.2f,
                BODY_GLOW,
                FxParam.flt("amount", "Amount", 0f, 3f, 1f),
                FxParam.flt("threshold", "Threshold", 0f, 1f, 0.6f),
                FxParam.flt("radius", "Radius", 1f, 60f, 18f)));

        defs.add(new FxEffectDef("flash", "Flash",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.1f,
                BODY_FLASH,
                FxParam.flt("amount", "Amount", 0f, 1f, 0.6f),
                FxParam.color("color", "Color", 0xFFFFFF),
                // Flashes a second; 0 = no strobe, key Amount by hand instead.
                FxParam.flt("rate", "Strobe rate", 0f, 8f, 0f),
                FxParam.flt("decay", "Decay", 0.1f, 3f, 1f)));

        defs.add(new FxEffectDef("vignette", "Vignette",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.1f,
                BODY_VIGNETTE,
                FxParam.flt("amount", "Amount", 0f, 1f, 0.6f),
                FxParam.flt("size", "Size", 0f, 1f, 0.45f),
                FxParam.flt("softness", "Softness", 0.01f, 1f, 0.5f),
                FxParam.flt("roundness", "Roundness", 0f, 1f, 1f),
                FxParam.color("color", "Color", 0x000000)));

        // ── Distort ──
        defs.add(new FxEffectDef("ripple", "Ripple",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.2f,
                BODY_RIPPLE,
                FxParam.flt("amount", "Strength", 0f, 2f, 0.6f),
                FxParam.flt("rings", "Rings", 1f, 40f, 10f),
                FxParam.flt("speed", "Speed", -4f, 4f, 1f),
                FxParam.flt("fade", "Fade", 0f, 5f, 1f),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        defs.add(new FxEffectDef("swirl", "Swirl",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.2f,
                BODY_SWIRL,
                FxParam.flt("angle", "Angle", -720f, 720f, 180f),
                FxParam.flt("radius", "Radius", 0.05f, 2f, 0.6f),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        defs.add(new FxEffectDef("echo_trails", "Echo Trails",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.FEEDBACK, 1, 1.4f,
                BODY_ECHO_TRAILS,
                FxParam.enumOf("mode", "Trails", 1, "Echo", "Light trails", "Glow trails"),
                // Kept per 1/30 s: 0.75 leaves about half a second of trail.
                FxParam.flt("amount", "Trail length", 0f, 0.98f, 0.75f),
                FxParam.flt("zoom", "Zoom", -2f, 2f, 0.3f),
                FxParam.flt("turn", "Turn", -1f, 1f, 0f),
                FxParam.flt("hue", "Colour drift", -1f, 1f, 0f),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        defs.add(new FxEffectDef("warp", "Warp",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.4f,
                BODY_WARP,
                FxParam.flt("amount", "Strength", 0f, 2f, 0.5f),
                FxParam.flt("scale", "Scale", 0.5f, 12f, 3f),
                FxParam.flt("speed", "Speed", 0f, 3f, 0.5f)));
    }
}
