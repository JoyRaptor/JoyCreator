package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * STU-6 step 3: TextLab looks that work over VIDEO as Studio effects: Burn Away, Stained Glass,
 * LED Board, Flag Wave and Steamed Glass. Same conventions as {@link FxTextLabDefs} and
 * {@link FxVisLabDefs} (uv y UP, distances against the frame height, {@code FX_TIME} timeline
 * seconds, sin-free hashes, constant loop bounds). Not portable yet, because they grow from a
 * letter's shape: fur, drips, rivets, bevels; "drips off a mask edge" is a later job.
 *
 * <p>Burn Away only changes colour and alpha so it is POINTWISE and fuses; the other four read
 * the picture at other points (each pane or bulb takes the colour at its centre, the flag and the
 * steam read displaced or blurred points), so they are SAMPLER. The panes' / bulbs' centre is
 * read once per pixel, one {@code FX_SAMPLE}.</p>
 *
 * <p>Ids are wire values: add, never rename. Android-free.</p>
 */
final class FxTextLabDefs2 {

    private FxTextLabDefs2() {}

    /**
     * A ragged line sweeping across and eating the picture: nothing behind it, a hair of live
     * ember on it, char just ahead of it. Progress 0 to 1 is keyable (one key at each end);
     * Direction is the way it travels; Raggedness how torn the front is.
     */
    private static final String BODY_BURN =
            "vec2 q = (uv - vec2(0.5)) * vec2(FX_ASPECT, 1.0);\n"
            + "float a = radians(FX_P(direction));\n"
            + "vec2 dir = vec2(cos(a), sin(a));\n"
            + "float ext = 0.5 * (abs(dir.x) * FX_ASPECT + abs(dir.y)) + 0.1;\n"
            + "float u = dot(q, dir) / ext;\n"
            + FxVisLabDefs.vnoise("bn", "q.x * 6.0 + FX_P(seed) * 5.0", "q.y * 6.0")
            + FxVisLabDefs.vnoise("bm", "q.x * 17.0", "q.y * 17.0 + 3.0")
            + "float rag = (bn * 0.7 + bm * 0.3 - 0.5) * FX_P(raggedness);\n"
            + "float front = mix(-1.25, 1.25, FX_P(progress));\n"
            + "float d = front - u + rag;\n"        // > 0 means already burnt
            + "float gone = smoothstep(0.0, 0.015, d);\n"
            + "float ahead = max(-d, 0.0);\n"
            + "float charW = max(FX_P(charWidth), 0.01);\n"
            + "float charAmt = (1.0 - gone) * (1.0 - smoothstep(0.0, charW, ahead));\n"
            + "float ember = (1.0 - gone) * exp(-ahead / max(FX_P(glow) * 0.05, 0.002));\n"
            + "vec3 rgb = mix(src.rgb, src.rgb * 0.12, charAmt * 0.9);\n"
            + "rgb += FX_P(emberColor) * ember * 1.6;\n"
            + "return vec4(clamp(rgb, 0.0, 1.0), src.a * (1.0 - gone));\n";

    /**
     * Panes of glass: each takes the picture's colour at its own centre, lit a little from within,
     * with a dark lead line between panes.
     */
    private static final String BODY_STAINED =
            "vec2 pos = vec2(uv.x * FX_ASPECT, uv.y);\n"
            + FxTextLabDefs.voronoi("v", "pos.x * FX_P(panes)", "pos.y * FX_P(panes)",
                    "FX_P(randomness)", "FX_P(seed) * 3.0")
            + "vec2 cuv = vec2(v_c.x / (FX_P(panes) * FX_ASPECT), v_c.y / FX_P(panes));\n"
            + "vec3 glass = FX_SAMPLE(cuv).rgb;\n"
            + "glass *= 0.78 + 0.45 * (1.0 - clamp(v_d1 * 1.2, 0.0, 1.0)) * FX_P(light);\n"
            + "float lead = 1.0 - smoothstep(0.0, max(FX_P(leadWidth), 0.005), v_d2 - v_d1);\n"
            + "vec3 o = mix(glass, FX_P(leadColor), lead);\n"
            + "return vec4(clamp(o, 0.0, 1.0), src.a);\n";

    /**
     * A board of lamps: each bulb shows the picture's colour at its own centre, in a dark socket,
     * with a soft glow around it. Round or square bulbs.
     */
    private static final String BODY_LED =
            "vec2 g = vec2(uv.x * FX_ASPECT, uv.y) * FX_P(bulbs);\n"
            + "vec2 cell = floor(g);\n"
            + "vec2 f = fract(g) - vec2(0.5);\n"
            + "vec2 cuv = vec2((cell.x + 0.5) / (FX_P(bulbs) * FX_ASPECT),"
            + " (cell.y + 0.5) / FX_P(bulbs));\n"
            + "vec3 c = FX_SAMPLE(cuv).rgb;\n"
            + "float d = FX_P(shape) < 0.5 ? length(f) : max(abs(f.x), abs(f.y));\n"
            + "float r = FX_P(bulbSize) * 0.5;\n"
            + "float bulb = 1.0 - smoothstep(r - 0.04, r + 0.04, d);\n"
            + "float halo = exp(-max(d - r, 0.0) * 9.0) * FX_P(glow);\n"
            + "vec3 o = FX_P(socket) + c * (bulb * FX_P(brightness) + halo * 0.5);\n"
            + "return vec4(clamp(o, 0.0, 1.0), src.a);\n";

    /**
     * A flag in wind: the picture ripples up and down, pinned along its left edge and freer toward
     * the right, with the folds lit from their slope. Speed is waves a second; the phase is wrapped
     * with fract before sin so long timelines stay exact.
     */
    private static final String BODY_FLAG =
            "float ph = fract(FX_TIME * FX_P(speed)) * 6.28318530718;\n"
            + "float x = uv.x * FX_P(waves) * 6.28318530718;\n"
            + "float free = mix(1.0, uv.x, FX_P(pinned));\n"
            + "float w = sin(x - ph) + 0.4 * sin(x * 2.3 - ph * 1.7 + uv.y * 3.0);\n"
            + "float slope = cos(x - ph) + 0.4 * 2.3 * cos(x * 2.3 - ph * 1.7 + uv.y * 3.0);\n"
            + "vec2 q = vec2(uv.x, uv.y + w * FX_P(amount) * 0.04 * free);\n"
            + "vec3 c = FX_SAMPLE(clamp(q, 0.0, 1.0)).rgb;\n"
            + "c *= 1.0 + slope * FX_P(shading) * 0.12 * free;\n"
            + "return vec4(clamp(c, 0.0, 1.0), src.a);\n";

    /**
     * Breath on a window: the world behind goes soft and pale, tiny drops sit on it as little clear
     * windows, and the whole thing thins with Fog. Where it is wiped clear comes from the card's
     * own masks (draw a shape, invert it).
     */
    private static final String BODY_STEAM =
            "vec2 st = FX_STEP * (4.0 + FX_P(softness) * 40.0);\n"
            + FxVisLabDefs.hash12("sj", "uv.x / FX_STEP.x", "uv.y / FX_STEP.y")
            + "float rot = sj * 6.28318530718;\n"
            + "vec3 acc = vec3(0.0);\n"
            + "for (int i = 0; i < 12; i++) {\n"
            + "  float fi = float(i) + 0.5;\n"
            + "  float rr = sqrt(fi / 12.0);\n"
            + "  float a = fi * 2.39996323 + rot;\n"
            + "  acc += FX_SAMPLE(uv + vec2(cos(a), sin(a)) * rr * st).rgb;\n"
            + "}\n"
            + "vec3 soft = acc / 12.0;\n"
            + "vec3 fogged = mix(soft, FX_P(tint), 0.35 * FX_P(fog));\n"
            + "vec2 g = vec2(uv.x * FX_ASPECT, uv.y) * FX_P(dropsAcross);\n"
            + "vec2 cell = floor(g);\n"
            + FxVisLabDefs.hash12("dh", "cell.x + FX_P(seed)", "cell.y")
            + FxVisLabDefs.hash12("dr", "cell.y * 1.7 + 4.0", "cell.x + 9.0")
            + "vec2 f = fract(g) - vec2(0.5);\n"
            + "float has = step(1.0 - FX_P(drops), dh);\n"
            + "float rad = 0.12 + 0.2 * dr;\n"
            + "float inside = has * (1.0 - smoothstep(rad - 0.03, rad + 0.03, length(f)));\n"
            + "vec3 clear = FX_SAMPLE(uv - f * 0.03).rgb;\n"      // a drop is a tiny lens
            + "vec3 o = mix(mix(src.rgb, fogged, FX_P(fog)), clear, inside);\n"
            + "return vec4(clamp(o, 0.0, 1.0), src.a);\n";

    /** Appends the five: Burn Away to Color, the rest to Distort. */
    static void addTo(@NonNull List<FxEffectDef> defs) {
        defs.add(new FxEffectDef("burn_away", "Burn Away",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.8f,
                BODY_BURN,
                FxParam.flt("progress", "Progress", 0f, 1f, 0.5f),
                FxParam.flt("direction", "Direction", -180f, 180f, 90f),
                FxParam.flt("raggedness", "Raggedness", 0f, 1.5f, 0.5f),
                FxParam.flt("charWidth", "Char width", 0.01f, 0.5f, 0.12f),
                FxParam.flt("glow", "Ember glow", 0.1f, 3f, 1f),
                FxParam.color("emberColor", "Ember colour", 0xFF6A1A),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f)));

        defs.add(new FxEffectDef("stained_glass", "Stained Glass",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 1.6f,
                BODY_STAINED,
                FxParam.flt("panes", "Panes across", 2f, 60f, 12f),
                FxParam.flt("randomness", "Randomness", 0f, 1f, 1f),
                FxParam.flt("leadWidth", "Lead width", 0.005f, 0.3f, 0.05f),
                FxParam.flt("light", "Light through", 0f, 1f, 0.6f),
                FxParam.color("leadColor", "Lead colour", 0x0C0C10),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f)));

        defs.add(new FxEffectDef("led_board", "LED Board",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 0.8f,
                BODY_LED,
                FxParam.flt("bulbs", "Bulbs across height", 6f, 160f, 40f),
                FxParam.enumOf("shape", "Bulb", 0, "Round", "Square"),
                FxParam.flt("bulbSize", "Bulb size", 0.2f, 1f, 0.7f),
                FxParam.flt("glow", "Glow", 0f, 1f, 0.5f),
                FxParam.flt("brightness", "Brightness", 0f, 2f, 1.1f),
                FxParam.color("socket", "Socket", 0x050507)));

        defs.add(new FxEffectDef("flag_wave", "Flag Wave",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 0.6f,
                BODY_FLAG,
                FxParam.flt("amount", "Wave height", 0f, 2f, 1f),
                FxParam.flt("waves", "Waves across", 0.5f, 6f, 1.6f),
                FxParam.flt("speed", "Speed", 0f, 3f, 0.5f),
                FxParam.flt("pinned", "Pinned on left", 0f, 1f, 1f),
                FxParam.flt("shading", "Fold shading", 0f, 2f, 1f)));

        defs.add(new FxEffectDef("steamed_glass", "Steamed Glass",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 1.4f,
                BODY_STEAM,
                FxParam.flt("fog", "Fog", 0f, 1f, 0.8f),
                FxParam.flt("softness", "Softness", 0f, 1f, 0.4f),
                FxParam.flt("drops", "Drops", 0f, 1f, 0.15f),
                FxParam.flt("dropsAcross", "Drop grid", 6f, 80f, 24f),
                FxParam.color("tint", "Tint", 0xDDE6EE),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f)));
    }
}
