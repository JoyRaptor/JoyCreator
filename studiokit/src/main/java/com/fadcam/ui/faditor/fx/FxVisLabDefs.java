package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * VisLab's FINISH pass ({@code assets/vislab/shaders/composite.frag}) as Studio effects, so the
 * same looks work on any clip's adjustment layer, with keyframable knobs (roadmap R4).
 *
 * <p><b>What was ported, and what was not.</b> Glitch Tear, VHS, Scanlines, CRT Bend,
 * Kaleidoscope, Mirror, Zoom Blur, Shake, Wet Floor and a radial Lens Color Split. Studio already
 * has Pixelate, Posterize, Invert, Film Look (vignette and grain) and Gradient Map, so those are
 * not repeated; Flash is Brightness / Exposure's {@code lift}. Lens Color Split IS new because it
 * is not RGB Shift: VisLab pulls the channels apart along the line from the centre, so the
 * split grows toward the edges like a real lens, where RGB Shift slides them by one fixed
 * offset.</p>
 *
 * <p><b>The music link is deliberately left out.</b> VisLab scales a few of these by the snare
 * and the kick; Studio effects cannot hear the soundtrack yet. Each port is the VisLab look with
 * the music term at zero, and the knobs it gains instead (density, speed) are what the owner can
 * key by hand.</p>
 *
 * <p><b>Coordinates.</b> A body sees {@code uv} in 0..1 with y UP (v = 0 is the bottom of the
 * picture, the same convention {@code MaskSdf} flips out of). VisLab works in "scene units",
 * where the short side runs -1..+1. These bodies use the same numbers with the HEIGHT running
 * -1..+1 ({@code (uv * 2 - 1) * vec2(FX_ASPECT, 1)}), which is what the rest of the effect
 * library quotes against, and which is identical to VisLab on a landscape frame. A shift
 * measured in scene units is turned back into uv by dividing x by {@code 2 * FX_ASPECT}.</p>
 *
 * <p><b>Time.</b> Every animated body reads {@code FX_TIME}, i.e. {@code uTime}, which both the
 * adjustment-layer export and the live preview set to the TIMELINE time in seconds. So the same
 * timeline time gives the same picture in the editor and in the file. The noise used for jitter
 * is a sin-free hash (VisLab's own {@code hash12}) rather than the registry's {@code fxNoise}:
 * a time argument grows without bound, and {@code sin} of a large number is the one place a
 * mobile GPU stops being trustworthy.</p>
 *
 * <p><b>Capability choices.</b> Glitch Tear, Kaleidoscope, Mirror and Shake only change where
 * the picture is read, so they are {@code UV_REMAP} and cost no pass of their own. Mirror and
 * Kaleidoscope fold the coordinate back inside the frame (a triangle wave) rather than letting
 * it clamp, so no edge ever smears. Zoom Blur, Lens Color Split, VHS, CRT Bend and Wet Floor
 * read several neighbouring points, so they are {@code SAMPLER}. Scanlines reads only the colour
 * and its own position, so it is {@code POINTWISE} and fuses.</p>
 *
 * <p>Ids are wire values — see {@link FxRegistry}. Add, never rename.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
final class FxVisLabDefs {

    private FxVisLabDefs() {}

    // ── Hash and noise, as GLSL snippets ────────────────────────────────────────────────────
    //
    // A body is a statement block and cannot declare a function, so the shared pieces are built
    // as text. Each helper declares `float <v>` plus temporaries prefixed with <v>_, so two calls
    // in one body never collide. Both are VisLab's hashes (common.glsl), which never call sin.

    /** {@code float v = hash11(a)}. Keep {@code a} below ~1e5 or the fraction runs out of bits. */
    private static String hash11(String v, String a) {
        return "float " + v + " = fract((" + a + ") * 0.1031);\n"
                + v + " *= " + v + " + 33.33;\n"
                + v + " *= " + v + " + " + v + ";\n"
                + v + " = fract(" + v + ");\n";
    }

    /** {@code float v = hash12(vec2(a, b))}. Each axis is folded to a fraction BEFORE they mix. */
    private static String hash12(String v, String a, String b) {
        return "vec3 " + v + "_q = fract(vec3(" + a + ", " + b + ", " + a + ") * 0.1031);\n"
                + v + "_q += dot(" + v + "_q, " + v + "_q.yzx + 33.33);\n"
                + "float " + v + " = fract((" + v + "_q.x + " + v + "_q.y) * " + v + "_q.z);\n";
    }

    /**
     * {@code float v = valueNoise(vec2(x, y))}, 0..1, smooth. The four corner hashes are
     * {@link #hash12} run across the lanes of one {@code vec4}, so it is one block of
     * arithmetic, not four.
     */
    private static String vnoise(String v, String x, String y) {
        return "vec2 " + v + "_p = vec2(" + x + ", " + y + ");\n"
                + "vec2 " + v + "_i = floor(" + v + "_p);\n"
                + "vec2 " + v + "_f = fract(" + v + "_p);\n"
                + v + "_f = " + v + "_f * " + v + "_f * (3.0 - 2.0 * " + v + "_f);\n"
                + "vec4 " + v + "_x = fract(vec4(" + v + "_i.x, " + v + "_i.x + 1.0, "
                + v + "_i.x, " + v + "_i.x + 1.0) * 0.1031);\n"
                + "vec4 " + v + "_y = fract(vec4(" + v + "_i.y, " + v + "_i.y, "
                + v + "_i.y + 1.0, " + v + "_i.y + 1.0) * 0.1031);\n"
                + "vec4 " + v + "_d = " + v + "_x * (" + v + "_y + 33.33) + "
                + v + "_y * (" + v + "_x + 33.33) + " + v + "_x * (" + v + "_x + 33.33);\n"
                + "vec4 " + v + "_k = fract((" + v + "_x + " + v + "_y + " + v + "_d + "
                + v + "_d) * (" + v + "_x + " + v + "_d));\n"
                + "float " + v + " = mix(mix(" + v + "_k.x, " + v + "_k.y, " + v + "_f.x), "
                + "mix(" + v + "_k.z, " + v + "_k.w, " + v + "_f.x), " + v + "_f.y);\n";
    }

    // ── Bodies ──────────────────────────────────────────────────────────────────────────────

    /**
     * Rows of the picture tear sideways in bursts (composite.frag's "Glitch"). Four clocks, as
     * in VisLab: the row grid slides 9 times a second, which rows are torn re-rolls 17 times a
     * second, and how far each slides re-rolls 31 times a second. VisLab's burst chance rises
     * with the snare; here it is the Density knob.
     *
     * <p>A torn row WRAPS around the frame edge ({@code fract}) rather than clamping, which is
     * what a real tear does and leaves no smeared edge column.</p>
     */
    private static final String BODY_GLITCH_TEAR =
            "float t = FX_TIME * FX_P(speed);\n"
            + hash11("gb", "floor(t * 9.0)")
            + "float row = floor(uv.y * FX_P(rows) + gb * 7.0);\n"
            + hash12("gh", "row", "floor(t * 17.0)")
            + "float burst = step(1.0 - FX_P(density), gh);\n"
            + hash12("gs", "row * 3.1", "floor(t * 31.0)")
            + "float shift = burst * (gs - 0.5) * 0.35 * FX_P(strength);\n"
            + "vec2 q = uv;\n"
            + "q.x = fract(q.x + shift / (2.0 * FX_ASPECT));\n"
            + "return q;\n";

    /**
     * VisLab's VHS, term for term: a per-line wobble and a slow drift, red and blue smeared
     * either side of green, blocky tape noise, and a dark dropout line that crawls up the
     * frame. {@code band} is composite.frag's tracking-band mask, kept exactly as written
     * (the wobble runs everywhere except a thin crawling seam).
     */
    private static final String BODY_VHS =
            "float a = FX_P(amount);\n"
            + "float t = FX_TIME;\n"
            + "float py = uv.y * 2.0 - 1.0;\n"
            + "float band = smoothstep(0.0, 0.02, abs(fract(py * 0.35 - t * 0.17) - 0.5) - 0.47);\n"
            + vnoise("jn", "py * 40.0", "t * 30.0")
            + vnoise("dn", "py * 3.0", "t * 2.0")
            + "float dx = (1.0 - band) * 0.03 * a * (jn * 2.0 - 1.0)"
            + " + 0.004 * a * (dn * 2.0 - 1.0);\n"
            // scene-unit x shifts to uv
            + "float bx = 0.5 / FX_ASPECT;\n"
            + "vec2 q = vec2(uv.x + dx * bx, uv.y);\n"
            + "vec4 c = FX_SAMPLE(q);\n"
            + "c.r = mix(c.r, FX_SAMPLE(q + vec2(0.012 * a * bx, 0.0)).r, 0.7);\n"
            + "c.b = mix(c.b, FX_SAMPLE(q - vec2(0.008 * a * bx, 0.0)).b, 0.7);\n"
            + "vec2 cell = floor((uv * 2.0 - 1.0) * vec2(FX_ASPECT, 1.0) * vec2(160.0, 90.0))"
            + " + vec2(t);\n"
            + hash12("nz", "cell.x", "cell.y")
            + "c.rgb += a * 0.08 * (nz - 0.5);\n"
            + "c.rgb *= 1.0 - a * 0.15 * step(0.985, fract(py * 1.5 - t * 0.6));\n"
            + "return vec4(clamp(c.rgb, 0.0, 1.0), c.a);\n";

    /**
     * Dark horizontal lines. VisLab keys them off the target pixel row, four pixels a period,
     * which is a different picture at every export size; here the period is a count per frame
     * HEIGHT (270 is four pixels at 1080 tall, the old look). Reads only its own position, so
     * it is a POINTWISE card, fuses with grading, and also works on a single object.
     */
    private static final String BODY_SCANLINES =
            "float line = 0.5 + 0.5 * sin(uv.y * FX_P(lines) * 6.28318530718);\n"
            + "return vec4(src.rgb * (1.0 - FX_P(strength) * 0.45 * line), src.a);\n";

    /**
     * The picture bulges like a curved tube. Same arithmetic as VisLab's Bend (CRT) — the
     * reading point is pushed outward by a quarter of the squared distance from the centre —
     * so the picture shrinks toward the middle and the corners go dark. "Zoom in to fill"
     * rescales it so the middle of each edge just meets the frame, leaving only the rounded
     * corners black.
     *
     * <p>The black is a mask over where the reading point has left the frame, soft across about
     * a pixel and a half and never touching a picture that is not bent (amount 0 is exact).</p>
     */
    private static final String BODY_CRT_BEND =
            "vec2 d = (uv - vec2(0.5)) * 2.0;\n"
            + "float k = FX_P(amount) * 0.25;\n"
            + "float fit = FX_P(fill) > 0.5 ? 1.0 / (1.0 + k) : 1.0;\n"
            + "vec2 q = vec2(0.5) + 0.5 * d * (1.0 + k * dot(d, d)) * fit;\n"
            + "vec2 e = min(q, vec2(1.0) - q);\n"
            + "float edge = min(e.x / FX_STEP.x, e.y / FX_STEP.y);\n"
            + "float m = smoothstep(0.0, 1.5, edge + 1.0);\n"
            + "vec4 c = FX_SAMPLE(q);\n"
            + "return vec4(c.rgb * m, c.a);\n";

    /**
     * Fold the picture into N wedges around the centre, each a mirror of the last. VisLab's
     * Kaleidoscope: take the angle, wrap it into one wedge, reflect it about the wedge's middle,
     * and read the picture there. Turn rotates where the wedge seams fall. Reading points that
     * land outside the frame are reflected back in (triangle wave), not clamped.
     */
    private static final String BODY_KALEIDOSCOPE =
            "float n = floor(max(FX_P(segments), 2.0));\n"
            + "vec2 asp = vec2(FX_ASPECT, 1.0);\n"
            + "vec2 c = FX_P(center);\n"
            + "vec2 p = (uv - c) * 2.0 * asp;\n"
            + "float seg = 6.28318530718 / n;\n"
            // The nudge keeps atan off its undefined (0, 0) at the exact centre pixel.
            + "float ang = atan(p.y, p.x + 0.000001) + radians(FX_P(turn));\n"
            + "float r = length(p);\n"
            + "ang = mod(ang, seg);\n"
            + "ang = abs(ang - seg * 0.5);\n"
            + "vec2 f = c + r * vec2(cos(ang), sin(ang)) / (2.0 * asp);\n"
            + "return 1.0 - abs(1.0 - mod(f, 2.0));\n";

    /**
     * One half of the picture reflected over the other. Mode 0 mirrors left-right (the right
     * half is copied over the left), 1 top-bottom (the top is copied down), 2 both. "Other side"
     * picks which half is the original. The centre can be moved, and a reading point pushed
     * outside the frame by that is reflected back in.
     */
    private static final String BODY_MIRROR =
            "float m = FX_P(mode);\n"
            + "float sgn = FX_P(flip) > 0.5 ? -1.0 : 1.0;\n"
            + "vec2 c = FX_P(center);\n"
            + "vec2 d = uv - c;\n"
            + "if (m < 0.5 || m > 1.5) d.x = sgn * abs(d.x);\n"
            + "if (m > 0.5) d.y = sgn * abs(d.y);\n"
            + "return 1.0 - abs(1.0 - mod(c + d, 2.0));\n";

    /**
     * Streaks the picture toward (or away from) a point: the average of twelve copies, each
     * scaled a little more about the centre. Same geometry as VisLab's Zoom blur (up to 12%
     * at amount 2), at twelve taps instead of eight so the largest setting does not band.
     */
    private static final String BODY_ZOOM_BLUR =
            "vec2 c = FX_P(center);\n"
            + "float k = FX_P(amount) * 0.06;\n"
            + "vec4 acc = vec4(0.0);\n"
            + "for (int i = 0; i < 12; i++) {\n"
            + "  float s = 1.0 - k * float(i) / 11.0;\n"
            + "  acc += FX_SAMPLE(c + (uv - c) * s);\n"
            + "}\n"
            + "return acc / 12.0;\n";

    /**
     * A camera shake: the whole picture jitters by smooth random steps, 23 a second at speed 1
     * (VisLab's rate), up to 2% of the frame height at strength 1. "Zoom in to hide edges" scales
     * the picture just enough that the largest jitter never shows the frame edge; with it off the
     * edge pixels stretch. At strength 0 the zoom is 1, so keying strength from 0 starts clean.
     */
    private static final String BODY_SHAKE =
            "float st = FX_TIME * FX_P(speed) * 23.0;\n"
            + vnoise("nx", "st", "1.3")
            + vnoise("ny", "4.7", "st")
            + "vec2 lim = FX_P(amount) * 0.02 * vec2(1.0 / FX_ASPECT, 1.0);\n"
            + "float s = FX_P(fill) > 0.5 ? 1.0 - 2.0 * max(lim.x, lim.y) : 1.0;\n"
            + "return vec2(0.5) + (uv - vec2(0.5)) * s + (vec2(nx, ny) * 2.0 - 1.0) * lim;\n";

    /**
     * Red and blue pulled apart along the line from the centre, so the fringe grows toward the
     * edges like a cheap lens (VisLab's Colour split). Not RGB Shift, which slides the channels
     * by one fixed offset in one direction.
     */
    private static final String BODY_LENS_SPLIT =
            "vec2 c = FX_P(center);\n"
            + "vec2 dir = (uv - c) * FX_P(amount) * 0.06;\n"
            + "vec4 g = FX_SAMPLE(uv);\n"
            + "float r = FX_SAMPLE(uv + dir).r;\n"
            + "float b = FX_SAMPLE(uv - dir).b;\n"
            + "return vec4(r, g.g, b, g.a);\n";

    /**
     * A wet floor: below the Floor line the picture is shown upside down, rippled, softened and
     * fading to dark with depth (composite.frag's reflection, minus its bloom lean — Studio has
     * no bloom buffer, so a short vertical smear that widens with depth stands in for the soft
     * water). Above the line nothing changes. {@code depth} is in scene units (the frame is 2
     * tall), so the ripple frequencies and the fade rate are VisLab's numbers; the Fade knob
     * scales VisLab's 2.2. Reflection is how bright the mirror image is at the line; the card's
     * own Opacity row is the overall mix.
     */
    private static final String BODY_WET_FLOOR =
            "float yh = FX_P(horizon);\n"
            + "float dv = yh - uv.y;\n"
            + "if (dv <= 0.0) return src;\n"
            + "float depth = dv * 2.0;\n"
            + "float px = (uv.x * 2.0 - 1.0) * FX_ASPECT;\n"
            + "float t = FX_TIME;\n"
            + vnoise("wx", "px * 3.0", "depth * 40.0 - t * 2.0")
            + vnoise("wy", "depth * 25.0 + t", "px * 2.0")
            + "float rip = FX_P(ripple);\n"
            + "float rx = rip * 0.02 * (wx * 2.0 - 1.0) * (0.3 + depth);\n"
            + "float ry = rip * 0.03 * (wy * 2.0 - 1.0);\n"
            + "vec2 m = vec2(uv.x + rx * 0.5 / FX_ASPECT, yh + dv + ry * 0.5);\n"
            + "float soft = FX_P(softness) * (0.006 + 0.05 * dv);\n"
            + "vec4 acc = vec4(0.0);\n"
            + "for (int i = 0; i < 5; i++) {\n"
            + "  float o = (float(i) - 2.0) * soft;\n"
            + "  acc += FX_SAMPLE(m + vec2(0.35 * o / FX_ASPECT, o));\n"
            + "}\n"
            + "float refl = FX_P(strength) * exp(-depth * FX_P(fade) * 2.2);\n"
            // Two pixels of blend at the line, so the join is not a stair-step.
            + "float seam = clamp(dv / (2.0 * FX_STEP.y), 0.0, 1.0);\n"
            + "return vec4(mix(src.rgb, acc.rgb * 0.2 * refl, seam), src.a);\n";

    // ── The catalog entries ─────────────────────────────────────────────────────────────────

    /** Appends the ten VisLab finish effects, in picker order within each family. */
    static void addTo(@NonNull List<FxEffectDef> defs) {
        // ── Color ──
        defs.add(new FxEffectDef("lens_split", "Lens Color Split",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.SAMPLER, 1, 1.2f,
                BODY_LENS_SPLIT,
                FxParam.flt("amount", "Amount", 0f, 2f, 0.3f),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        defs.add(new FxEffectDef("scanlines", "Scanlines",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.3f,
                BODY_SCANLINES,
                FxParam.flt("strength", "Strength", 0f, 2f, 1f),
                // A period count per frame height. 270 = four pixels at 1080 tall, VisLab's.
                FxParam.flt("lines", "Lines", 30f, 540f, 270f)));

        // ── Blur ──
        defs.add(new FxEffectDef("zoom_blur", "Zoom Blur",
                FxEffectDef.Family.BLUR, FxEffectDef.Capability.SAMPLER, 1, 1.6f,
                BODY_ZOOM_BLUR,
                FxParam.flt("amount", "Amount", 0f, 2f, 0.8f),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        // ── Distort ──
        defs.add(new FxEffectDef("glitch_tear", "Glitch Tear",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.2f,
                BODY_GLITCH_TEAR,
                FxParam.flt("strength", "Strength", 0f, 2f, 0.6f),
                FxParam.flt("density", "Density", 0f, 1f, 0.25f),
                // A count, so the timeline never keys it: a keyed row count would re-cut the
                // bands on every frame between two keys.
                FxParam.fltStatic("rows", "Tear rows", 4f, 120f, 36f),
                FxParam.flt("speed", "Speed", 0.1f, 4f, 1f)));

        defs.add(new FxEffectDef("vhs", "VHS",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 1.8f,
                BODY_VHS,
                FxParam.flt("amount", "Amount", 0f, 1f, 0.7f)));

        defs.add(new FxEffectDef("crt_bend", "CRT Bend",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 1.1f,
                BODY_CRT_BEND,
                FxParam.flt("amount", "Bend", 0f, 1f, 0.5f),
                FxParam.bool("fill", "Zoom in to fill", false)));

        defs.add(new FxEffectDef("kaleidoscope", "Kaleidoscope",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.3f,
                BODY_KALEIDOSCOPE,
                FxParam.flt("segments", "Segments", 2f, 16f, 6f),
                FxParam.flt("turn", "Turn", -180f, 180f, 0f),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        defs.add(new FxEffectDef("mirror", "Mirror",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.1f,
                BODY_MIRROR,
                FxParam.enumOf("mode", "Mirror", 0, "Left-right", "Top-bottom", "Both"),
                FxParam.bool("flip", "Other side", false),
                FxParam.point("center", "Center", 0.5f, 0.5f)));

        defs.add(new FxEffectDef("shake", "Shake",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0.2f,
                BODY_SHAKE,
                FxParam.flt("amount", "Strength", 0f, 2f, 1f),
                FxParam.flt("speed", "Speed", 0.1f, 4f, 1f),
                FxParam.bool("fill", "Zoom in to hide edges", true)));

        defs.add(new FxEffectDef("wet_floor", "Wet Floor",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.SAMPLER, 1, 1.8f,
                BODY_WET_FLOOR,
                // Up from the BOTTOM of the frame (v = 0), so 0.28 is VisLab's default -0.45.
                FxParam.flt("horizon", "Floor line", 0.05f, 0.95f, 0.28f),
                FxParam.flt("strength", "Reflection", 0f, 1f, 0.8f),
                FxParam.flt("ripple", "Ripple", 0f, 2f, 0.6f),
                FxParam.flt("softness", "Softness", 0f, 1f, 0.5f),
                // 1.0 is VisLab's fade rate (the body multiplies it by 2.2).
                FxParam.flt("fade", "Fade with depth", 0f, 3f, 1f)));
    }
}
