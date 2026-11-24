package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * STU-6 step 2: the pattern makers TextLab's looks are built from, as Studio GENERATE effects
 * (Tiles, Cells, Halftone, Brushed Metal, Chipped Wear, Caustics, Glitter), so textures can be
 * built the way the owner did in 3ds Max: patterns plus displacement plus masks.
 *
 * <p>They are TextLab's own ideas (the plate courses of the rust look, the 3x3 Voronoi of the
 * ice / stained-glass looks, the dots / stripes / checks of the halftone, the groove-and-streak
 * lighting of brushed metal, the worn-through coat, the refraction caustics, the sand glitter),
 * re-expressed in the Studio conventions of {@link FxVisLabDefs}: {@code uv} 0..1 with y UP,
 * distances against the frame HEIGHT, {@code FX_TIME} = timeline seconds (so preview equals
 * export), sin-free hashes for anything fed by time, helpers built as text snippets because a
 * body is a statement block and cannot declare a function, constant loop bounds.</p>
 *
 * <p>TextLab reads a noise texture and the phone's tilt; Studio has neither, so noise is the
 * sin-free value noise and the brushed-metal light streak is a keyable position slider.</p>
 *
 * <p>Every card is a GENERATOR: it fuses and costs no pass of its own, and mixes over whatever
 * the pass has built so far by its Amount, like {@code noise}. Ids are wire values: add, never
 * rename. Android-free.</p>
 */
final class FxTextLabDefs {

    private FxTextLabDefs() {}

    /**
     * {@code vec2 v_p / float v_d1, v_d2, v_id}: nearest and second-nearest feature distance and a
     * 0..1 id of the nearest cell, from a 3x3 search around {@code (px, py)} (cell units).
     * {@code rnd} (0..1) is how far a cell's point strays from its centre; {@code seed} a float.
     */
    private static String voronoi(String v, String px, String py, String rnd, String seed) {
        return "vec2 " + v + "_p = vec2(" + px + ", " + py + ");\n"
                + "vec2 " + v + "_i = floor(" + v + "_p);\n"
                + "vec2 " + v + "_f = fract(" + v + "_p);\n"
                + "float " + v + "_d1 = 8.0;\n"
                + "float " + v + "_d2 = 8.0;\n"
                + "float " + v + "_id = 0.0;\n"
                + "for (int vj = -1; vj <= 1; vj++) {\n"
                + "  for (int vi = -1; vi <= 1; vi++) {\n"
                + "    vec2 vg = vec2(float(vi), float(vj));\n"
                + "    vec2 vc = " + v + "_i + vg;\n"
                + FxVisLabDefs.hash12(v + "h1", "vc.x + " + seed, "vc.y")
                + FxVisLabDefs.hash12(v + "h2", "vc.y + " + seed + " * 1.7", "vc.x + 17.0")
                + "    vec2 vo = mix(vec2(0.5), vec2(" + v + "h1, " + v + "h2), " + rnd + ");\n"
                + "    float vd = length(vg + vo - " + v + "_f);\n"
                + "    if (vd < " + v + "_d1) {\n"
                + "      " + v + "_d2 = " + v + "_d1;\n"
                + "      " + v + "_d1 = vd;\n"
                + "      " + v + "_id = " + v + "h1;\n"
                + "    } else if (vd < " + v + "_d2) {\n"
                + "      " + v + "_d2 = vd;\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    /** Frame position in aspect-true units, origin bottom left, 1.0 = frame height. */
    private static final String POS = "vec2 pos = vec2(uv.x * FX_ASPECT, uv.y);\n";

    // ── Tiles ───────────────────────────────────────────────────────────────

    /**
     * Plates laid in courses (rust look's plates). Tiles across is per frame width; Shape is a
     * tile's width over its height (2 = bricks, 1 = squares); every course slides by Row offset;
     * Gap is the seam width in tile units, Softness how blurred its edge is. Each tile gets its
     * own brightness by Variation.
     */
    private static final String BODY_TILES =
            "vec2 t = vec2(uv.x * FX_P(across),"
            + " uv.y * FX_P(across) * FX_P(shape) / FX_ASPECT);\n"
            + "t.x += floor(t.y) * FX_P(offset);\n"
            + "vec2 cell = floor(t);\n"
            + "vec2 f = fract(t);\n"
            + "float edge = min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y));\n"
            + "float body = smoothstep(FX_P(gap), FX_P(gap) + max(FX_P(softness), 0.002), edge);\n"
            + FxVisLabDefs.hash12("th", "cell.x + FX_P(seed)", "cell.y")
            + "vec3 tileCol = FX_P(tile) * (1.0 - FX_P(variation) * th);\n"
            + "float m = FX_P(mode);\n"
            + "vec3 o = m < 0.5 ? mix(FX_P(seam), tileCol, body)\n"
            + "       : (m < 1.5 ? vec3(1.0 - body) : vec3(th));\n"
            + "return vec4(mix(src.rgb, o, FX_P(amount)), src.a);\n";

    // ── Cells ───────────────────────────────────────────────────────────────

    /**
     * Voronoi cells (ice fissures, stained-glass panes, ember cracks). Edges draws the cracks
     * between cells, Filled gives each cell its own grey, Soft is the distance to the nearest
     * point (blobs).
     */
    private static final String BODY_CELLS =
            POS
            + voronoi("v", "pos.x * FX_P(scale)", "pos.y * FX_P(scale)",
                    "FX_P(randomness)", "FX_P(seed) * 3.0")
            + "float m = FX_P(mode);\n"
            + "float k = m < 0.5 ? 1.0 - smoothstep(0.0, max(FX_P(width), 0.002), v_d2 - v_d1)\n"
            + "        : (m < 1.5 ? v_id : clamp(v_d1 * 1.2, 0.0, 1.0));\n"
            + "return vec4(mix(src.rgb, mix(FX_P(back), FX_P(ink), k), FX_P(amount)), src.a);\n";

    // ── Halftone / Stripes / Checks ─────────────────────────────────────────

    /**
     * TextLab's three patterns. Dots, Stripes and Checks run at an Angle with Cells across the
     * frame height. With "Size from picture" on, dots and stripes swell where the picture
     * underneath is dark, a true halftone of it; off, Size alone sets them.
     */
    private static final String BODY_HALFTONE =
            "vec2 q = (uv - vec2(0.5)) * vec2(FX_ASPECT, 1.0);\n"
            + "float a = radians(FX_P(angle));\n"
            + "vec2 r = vec2(q.x * cos(a) + q.y * sin(a), q.y * cos(a) - q.x * sin(a));\n"
            + "r *= FX_P(cells);\n"
            + "float lum = dot(src.rgb, vec3(0.299, 0.587, 0.114));\n"
            + "float sz = FX_P(fromPicture) > 0.5 ? clamp(1.0 - lum, 0.0, 1.0) * FX_P(size) * 2.0"
            + " : FX_P(size);\n"
            + "float pat = FX_P(kind);\n"
            + "vec2 f = fract(r) - vec2(0.5);\n"
            + "float k;\n"
            + "if (pat < 0.5) {\n"
            + "  float rad = sqrt(clamp(sz, 0.0, 1.0)) * 0.7;\n"
            + "  k = 1.0 - smoothstep(rad - 0.04, rad + 0.04, length(f));\n"
            + "} else if (pat < 1.5) {\n"
            + "  k = 1.0 - smoothstep(sz * 0.5 - 0.04, sz * 0.5 + 0.04, abs(f.y));\n"
            + "} else {\n"
            + "  k = mod(floor(r.x) + floor(r.y), 2.0);\n"
            + "}\n"
            + "return vec4(mix(src.rgb, mix(FX_P(paper), FX_P(ink), k), FX_P(amount)), src.a);\n";

    // ── Brushed metal ───────────────────────────────────────────────────────

    /**
     * Fine grooves along the brush direction and a soft light streak ACROSS them. Streak position
     * replaces the phone tilt TextLab reads, so it is keyable: sweep it to move the glint.
     */
    private static final String BODY_BRUSHED =
            "vec2 q = (uv - vec2(0.5)) * vec2(FX_ASPECT, 1.0);\n"
            + "float a = radians(FX_P(direction));\n"
            + "vec2 along = vec2(cos(a), sin(a));\n"
            + "vec2 across = vec2(-along.y, along.x);\n"
            + "float u = dot(q, along);\n"
            + "float w = dot(q, across);\n"
            + FxVisLabDefs.vnoise("g1", "w * FX_P(fineness)", "u * 1.5")
            + FxVisLabDefs.vnoise("g2", "w * FX_P(fineness) * 2.7 + 5.0", "u * 3.0")
            + "float groove = mix(g1, g2, 0.4);\n"
            + "float s = (u - (FX_P(streakAt) - 0.5) * FX_ASPECT) / max(FX_P(streakWidth), 0.02);\n"
            + "float streak = exp(-s * s);\n"
            + "vec3 col = FX_P(metal) * (0.55 + 0.45 * groove);\n"
            + "col += vec3(1.0) * streak * FX_P(shine) * (0.4 + 0.6 * groove);\n"
            + "return vec4(mix(src.rgb, clamp(col, 0.0, 1.0), FX_P(amount)), src.a);\n";

    // ── Chipped wear ────────────────────────────────────────────────────────

    /**
     * A coat worn through (the rust look's chipped paint): fractal noise plus a cell offset
     * decides where it has chipped, with a lifted lip at each edge. Mask output is white where
     * the coat is gone, for wearing through another layer; Layered shows coat, under-coat and
     * bare metal themselves.
     */
    private static final String BODY_CHIPPED =
            POS
            + FxVisLabDefs.vnoise("n1", "pos.x * FX_P(chipSize) + FX_P(seed) * 7.0",
                    "pos.y * FX_P(chipSize)")
            + FxVisLabDefs.vnoise("n2", "pos.x * FX_P(chipSize) * 2.1 + 3.0",
                    "pos.y * FX_P(chipSize) * 2.1")
            + voronoi("v", "pos.x * FX_P(chipSize) * 0.7", "pos.y * FX_P(chipSize) * 0.7",
                    "1.0", "FX_P(seed)")
            + "float field = n1 * 0.55 + n2 * 0.25 + (v_id - 0.5) * 0.45 + 0.2;\n"
            + "float thr = 1.0 - FX_P(worn);\n"
            + "float gone = smoothstep(thr, thr + 0.02, field);\n"
            + "float lip = (1.0 - smoothstep(0.0, 0.05, abs(field - thr))) * FX_P(lift);\n"
            + "float under = smoothstep(thr + 0.10, thr + 0.12, field);\n"
            + "vec3 layered = mix(FX_P(coat), mix(FX_P(underCoat), FX_P(bare), under), gone);\n"
            + "layered += vec3(lip * 0.35);\n"
            + "vec3 o = FX_P(mode) < 0.5 ? vec3(gone) : layered;\n"
            + "return vec4(mix(src.rgb, clamp(o, 0.0, 1.0), FX_P(amount)), src.a);\n";

    // ── Caustics ────────────────────────────────────────────────────────────

    /**
     * Bright moving net of light, as on a pool floor (the refraction look): two drifting Voronoi
     * layers, bright where a cell's nearest and second-nearest points are close. Added to the
     * picture in Colour by Amount.
     */
    private static final String BODY_CAUSTICS =
            POS
            + "float t = FX_TIME * FX_P(speed);\n"
            + "vec2 p1 = pos * FX_P(scale) + vec2(t * 0.35, t * 0.22);\n"
            + "vec2 p2 = pos * FX_P(scale) * 1.4 - vec2(t * 0.27, -t * 0.31) + 9.0;\n"
            + voronoi("a", "p1.x", "p1.y", "1.0", "0.0")
            + voronoi("b", "p2.x", "p2.y", "1.0", "4.0")
            + "float ca = pow(clamp(1.0 - (a_d2 - a_d1) * 1.6, 0.0, 1.0), FX_P(sharpness));\n"
            + "float cb = pow(clamp(1.0 - (b_d2 - b_d1) * 1.6, 0.0, 1.0), FX_P(sharpness));\n"
            + "float c = clamp(ca + cb * 0.6, 0.0, 1.0);\n"
            + "return vec4(clamp(src.rgb + FX_P(color) * c * FX_P(amount), 0.0, 1.0), src.a);\n";

    // ── Glitter ─────────────────────────────────────────────────────────────

    /**
     * Sparks that flash on their own clocks (the sand look's glitter): a few cells of a fine grid
     * hold a spark, each flashes with its own phase. Density is the share of cells that hold one.
     * The phase is wrapped with fract before sin, so long timelines stay exact.
     */
    private static final String BODY_GLITTER =
            POS
            + "vec2 g = pos * FX_P(size);\n"
            + "vec2 cell = floor(g);\n"
            + FxVisLabDefs.hash12("gh", "cell.x + FX_P(seed)", "cell.y")
            + FxVisLabDefs.hash12("gp", "cell.y * 1.3 + 11.0", "cell.x + 5.0")
            + "float has = step(1.0 - FX_P(density), gh);\n"
            + "float ph = fract(FX_TIME * FX_P(flashSpeed) + gp) * 6.28318530718;\n"
            + "float flash = pow(max(sin(ph), 0.0), 6.0);\n"
            + "float d = length(fract(g) - vec2(0.5));\n"
            + "float core = (1.0 - smoothstep(0.0, 0.35, d)) * flash * has;\n"
            + "return vec4(clamp(src.rgb + FX_P(color) * core * FX_P(amount), 0.0, 1.0), src.a);\n";

    /** Appends the seven to the GENERATE family. */
    static void addTo(@NonNull List<FxEffectDef> defs) {
        defs.add(new FxEffectDef("tiles", "Tiles",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 0.4f,
                BODY_TILES,
                FxParam.enumOf("mode", "Show", 0, "Tiles", "Gaps only", "Tile id"),
                FxParam.flt("across", "Tiles across", 1f, 60f, 8f),
                FxParam.flt("shape", "Shape (wide / tall)", 0.25f, 4f, 2f),
                FxParam.flt("offset", "Row offset", 0f, 1f, 0.5f),
                FxParam.flt("gap", "Gap width", 0f, 0.4f, 0.06f),
                FxParam.flt("softness", "Gap softness", 0.002f, 0.3f, 0.02f),
                FxParam.flt("variation", "Tile variation", 0f, 1f, 0.35f),
                FxParam.color("tile", "Tile colour", 0xB8B8C0),
                FxParam.color("seam", "Gap colour", 0x101014),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("cells", "Cells",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 1.2f,
                BODY_CELLS,
                FxParam.enumOf("mode", "Show", 0, "Edges (cracks)", "Filled cells", "Soft blobs"),
                FxParam.flt("scale", "Cells across", 1f, 80f, 10f),
                FxParam.flt("randomness", "Randomness", 0f, 1f, 1f),
                FxParam.flt("width", "Edge width", 0.005f, 0.5f, 0.06f),
                FxParam.color("ink", "Colour", 0xFFFFFF),
                FxParam.color("back", "Background", 0x000000),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("halftone", "Halftone / Stripes / Checks",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 0.5f,
                BODY_HALFTONE,
                FxParam.enumOf("kind", "Pattern", 0, "Dots", "Stripes", "Checks"),
                FxParam.flt("cells", "Cells across", 2f, 120f, 30f),
                FxParam.flt("angle", "Angle", -90f, 90f, 45f),
                FxParam.flt("size", "Size", 0f, 1f, 0.5f),
                FxParam.bool("fromPicture", "Size from picture", true),
                FxParam.color("ink", "Ink", 0x000000),
                FxParam.color("paper", "Paper", 0xFFFFFF),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("brushed_metal", "Brushed Metal",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 0.8f,
                BODY_BRUSHED,
                FxParam.flt("direction", "Grooves direction", -90f, 90f, 0f),
                FxParam.flt("fineness", "Fineness", 20f, 400f, 160f),
                FxParam.flt("streakAt", "Streak position", 0f, 1f, 0.5f),
                FxParam.flt("streakWidth", "Streak width", 0.02f, 1f, 0.25f),
                FxParam.flt("shine", "Shine", 0f, 1f, 0.6f),
                FxParam.color("metal", "Metal", 0xA9A9B3),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("chipped_wear", "Chipped Wear",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 1.4f,
                BODY_CHIPPED,
                FxParam.enumOf("mode", "Output", 0, "Mask (worn = white)", "Layered colours"),
                FxParam.flt("worn", "How worn", 0f, 1f, 0.45f),
                FxParam.flt("chipSize", "Chip size", 2f, 60f, 10f),
                FxParam.flt("lift", "Edge lift", 0f, 1f, 0.5f),
                FxParam.color("coat", "Coat", 0x2F6FB3),
                FxParam.color("underCoat", "Under-coat", 0x8A8F96),
                FxParam.color("bare", "Bare", 0x7A3B1E),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("caustics", "Caustics",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 2.0f,
                BODY_CAUSTICS,
                FxParam.flt("scale", "Scale", 1f, 30f, 6f),
                FxParam.flt("speed", "Speed", 0f, 3f, 0.6f),
                FxParam.flt("sharpness", "Sharpness", 1f, 8f, 4f),
                FxParam.color("color", "Colour", 0xBFF4FF),
                FxParam.flt("amount", "Amount", 0f, 1f, 0.7f)));

        defs.add(new FxEffectDef("glitter", "Glitter",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 0.6f,
                BODY_GLITTER,
                FxParam.flt("density", "Density", 0f, 1f, 0.12f),
                FxParam.flt("size", "Size (cells across)", 10f, 200f, 60f),
                FxParam.flt("flashSpeed", "Flash speed", 0f, 8f, 2f),
                FxParam.color("color", "Colour", 0xFFF1C2),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));
    }
}
