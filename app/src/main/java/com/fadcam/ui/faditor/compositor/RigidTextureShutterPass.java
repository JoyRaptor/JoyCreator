package com.fadcam.ui.faditor.compositor;

import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

/** Resolves several placements of the same isolated studio frame in one draw. */
public final class RigidTextureShutterPass {
    private static final int SAMPLE_COUNT = 6;

    /** A camera tap gap past this many canvas pixels would show as separate copies. */
    static final double CAMERA_STEP_PX = 3.0;

    public static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec4 aFramePosition;\n"
            + "uniform mat4 uTransformationMatrix;\n"
            + "uniform mat4 uTexTransformationMatrix;\n"
            + "varying vec2 vTexSamplingCoord;\n"
            + "void main() {\n"
            + "  gl_Position = uTransformationMatrix * aFramePosition;\n"
            + "  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,\n"
            + "                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;\n"
            + "}\n";

    public static RigidShutterPlan plan(MotionShutter.Controls controls, ItemMotionSampler sampler,
                                 long timeUs, long periodUs, MotionBreaks breaks, int width, int height) {
        return plan(controls, sampler, timeUs, periodUs, breaks, MotionBreaks.none(), width, height);
    }

    public static RigidShutterPlan plan(MotionShutter.Controls controls, ItemMotionSampler sampler,
                                 long timeUs, long periodUs, MotionBreaks breaks,
                                 MotionBreaks sourceBreaks, int width, int height) {
        if (periodUs <= 0 || controls.mixPercent() == 0 || controls.angle() == 0) return null;
        // STU-7 §K.2: a long trail, Fade or Copies is drawn from real earlier poses by the trail
        // program. Source changes inside the window (a sprite's drawing swap) do not cut a trail:
        // it carries the current drawing along the path. Cuts, step keys and loops still do.
        if (controls.isStylised()) {
            return RigidShutterPlan.prepareTrail(sampler, timeUs, controls, periodUs, breaks,
                    width, height);
        }
        var schedule = controls.schedule(timeUs, periodUs, SAMPLE_COUNT, breaks, sourceBreaks);
        // A fast move inside one shutter would show the six taps as copies: draw the same
        // exposure with the smooth trail program (steps along the true path) instead.
        if (RigidShutterPlan.tapsWouldStep(sampler, schedule, CAMERA_STEP_PX)) {
            RigidShutterPlan smooth = RigidShutterPlan.prepareTrail(sampler, timeUs, controls,
                    periodUs, breaks, sourceBreaks, width, height);
            if (smooth != null) return smooth;
        }
        return RigidShutterPlan.prepare(sampler, timeUs, schedule, width, height, 1.0);
    }

    public record Exposure(RigidShutterPlan plan, float opacity) {}

    public enum Source { SPINE, PICTURE, PREMULTIPLIED_OVERLAY, PIP, VIDEO_OVERLAY }

    private final GlProgram program;
    private final Source source;
    private final java.util.EnumMap<MotionShutter.Look, GlProgram> trailPrograms =
            new java.util.EnumMap<>(MotionShutter.Look.class);
    private final float[][] trailRows = new float[RigidShutterPlan.MAX_TRAIL_POSES * 2][4];
    private final float[] sharpX = new float[4];
    private final float[] sharpY = new float[4];
    private final float[] scale = new float[3];
    private final float[] segment = new float[4];
    private final boolean overlay;
    private final boolean pip;
    private final float[] frame;
    private final float[] bounds = new float[4];
    private final float[][] rows = new float[SAMPLE_COUNT * 2][4];

    public RigidTextureShutterPass(Source source) throws GlUtil.GlException {
        this.source = source;
        pip = source == Source.PIP;
        overlay = !pip && source != Source.SPINE;
        frame = new float[overlay || pip ? 3 : 2];
        program = new GlProgram(VERTEX_SHADER, fragmentShader(source, null));
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
        float[] identity = GlUtil.create4x4IdentityMatrix();
        program.setFloatsUniform("uTransformationMatrix", identity);
        program.setFloatsUniform("uTexTransformationMatrix", identity);
    }

    public void draw(int textureId, int width, int height, RigidShutterPlan plan,
              int baseTexture, float opacity) throws GlUtil.GlException {
        frame[0] = width;
        frame[1] = height;
        var footprint = plan.bounds();
        bounds[0] = footprint.left();
        bounds[1] = footprint.top();
        bounds[2] = footprint.right();
        bounds[3] = footprint.bottom();
        if (plan.trail() != null) {
            drawTrail(textureId, plan, baseTexture, opacity);
            return;
        }
        program.use();
        program.setSamplerTexIdUniform("uTexSampler", textureId, 0);
        if (overlay || pip) frame[2] = opacity;
        if (overlay) {
            program.setSamplerTexIdUniform("uBackdrop", baseTexture, 1);
        }
        program.setFloatsUniform("uFrame", frame);
        program.setFloatsUniform("uBounds", bounds);
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float[] x = rows[2 * i];
            float[] y = rows[2 * i + 1];
            if (i < plan.taps().size()) {
                var tap = plan.taps().get(i);
                var inverse = tap.canvasToUv();
                x[0] = (float) inverse.xx();
                x[1] = (float) inverse.xy();
                x[2] = (float) inverse.tx();
                x[3] = (float) tap.weight();
                y[0] = (float) inverse.yx();
                y[1] = (float) inverse.yy();
                y[2] = (float) inverse.ty();
            } else {
                x[3] = 0f;
            }
            program.setFloatsUniform("uTapX" + i, x);
            program.setFloatsUniform("uTapY" + i, y);
        }
        program.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    /** The trail program for {@code plan}: built once per look, on first use. */
    private void drawTrail(int textureId, RigidShutterPlan plan, int baseTexture, float opacity)
            throws GlUtil.GlException {
        var trail = plan.trail();
        GlProgram p = trailPrograms.get(trail.look());
        if (p == null) {
            p = new GlProgram(VERTEX_SHADER, fragmentShader(source, trail.look()));
            p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            float[] identity = GlUtil.create4x4IdentityMatrix();
            p.setFloatsUniform("uTransformationMatrix", identity);
            p.setFloatsUniform("uTexTransformationMatrix", identity);
            trailPrograms.put(trail.look(), p);
        }
        p.use();
        p.setSamplerTexIdUniform("uTexSampler", textureId, 0);
        if (overlay || pip) frame[2] = opacity;
        if (overlay) p.setSamplerTexIdUniform("uBackdrop", baseTexture, 1);
        p.setFloatsUniform("uFrame", frame);
        p.setFloatsUniform("uBounds", bounds);
        var taps = plan.taps();
        for (int i = 0; i < RigidShutterPlan.MAX_TRAIL_POSES; i++) {
            float[] x = trailRows[2 * i];
            float[] y = trailRows[2 * i + 1];
            if (i < taps.size()) {
                rowsOf(taps.get(i), x, y);
                y[3] = 1f;   // a live pose: a smear may run from here to the next
            } else {
                x[3] = 0f;
                y[3] = 0f;
            }
            p.setFloatsUniform("uTapX" + i, x);
            p.setFloatsUniform("uTapY" + i, y);
        }
        rowsOf(trail.sharpTap(), sharpX, sharpY);
        p.setFloatsUniform("uSharpX", sharpX);
        p.setFloatsUniform("uSharpY", sharpY);
        scale[0] = (float) trail.smearScale();
        scale[1] = (float) trail.copyScale();
        scale[2] = (float) trail.sharp();
        p.setFloatsUniform("uScale", scale);
        // Copies never smears, so its program has no segments (the compiler drops unused
        // uniforms, and setting one that is not there throws).
        if (trail.look() != MotionShutter.Look.COPIES) {
            var segments = trail.segments();
            for (int i = 0; i + 1 < RigidShutterPlan.MAX_TRAIL_POSES; i++) {
                if (i < segments.size()) {
                    var g = segments.get(i);
                    segment[0] = (float) g.x();
                    segment[1] = (float) g.y();
                    segment[2] = (float) g.logScale();
                    segment[3] = (float) g.turn();
                } else {
                    segment[0] = segment[1] = segment[2] = segment[3] = 0f;
                }
                p.setFloatsUniform("uSeg" + i, segment);
            }
        }
        p.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    private static void rowsOf(RigidShutterPlan.Tap tap, float[] x, float[] y) {
        var inverse = tap.canvasToUv();
        x[0] = (float) inverse.xx();
        x[1] = (float) inverse.xy();
        x[2] = (float) inverse.tx();
        x[3] = (float) tap.weight();
        y[0] = (float) inverse.yx();
        y[1] = (float) inverse.yy();
        y[2] = (float) inverse.ty();
    }

    public void release() throws GlUtil.GlException {
        program.delete();
        for (GlProgram p : trailPrograms.values()) p.delete();
        trailPrograms.clear();
    }

    /** {@code trail} null = the camera shutter program; else the trail program for that look. */
    static String fragmentShader(Source kind, MotionShutter.Look trail) {
        String shader = fragmentShaderText(kind, trail);
        return trail == null ? shader : withSoftSampler(shader);
    }

    /**
     * The trail program's helpers, built from the camera program's own {@code samplePose} text
     * (which stays exactly as it was):
     * <ul>
     *   <li>{@code sampleUvSoft(uv, f, w)}: samplePose at a given UV, its edges softened by
     *   {@code f} (how far this pixel's UV moves between two smear steps), so overlapping steps
     *   add up evenly whatever the direction of motion;</li>
     *   <li>{@code arcBack(z, seg, t)}: canvas point z taken back by fraction t of a segment, a
     *   turn and zoom about its fixed point (a slide when seg.zw is zero), so the steps between
     *   two key poses trace the true arc (see RigidShutterPlan.Segment).</li>
     * </ul>
     */
    static String withSoftSampler(String shader) {
        int start = shader.indexOf("vec4 samplePose(vec3 pixel, vec4 rowX, vec4 rowY) {");
        int end = shader.indexOf("void main() {");
        if (start < 0 || end < start) throw new IllegalStateException("samplePose not found");
        String soft = shader.substring(start, end)
                .replace("vec4 samplePose(vec3 pixel, vec4 rowX, vec4 rowY) {",
                        "vec4 sampleUvSoft(vec2 uv, vec2 f, float w) {")
                .replace("  if (rowX.w == 0.0) return vec4(0.0);\n"
                                + "  vec2 uv = vec2(dot(rowX.xyz, pixel), dot(rowY.xyz, pixel));\n",
                        "  if (w == 0.0) return vec4(0.0);\n")
                .replace("  if (any(lessThanEqual(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0))))\n"
                                + "    return vec4(0.0);\n",
                        "  vec2 soft = clamp((min(uv, 1.0 - uv) + 0.5 * f)"
                                + " / max(f, vec2(0.0001)), 0.0, 1.0);\n"
                                + "  if (soft.x * soft.y <= 0.0) return vec4(0.0);\n"
                                + "  uv = clamp(uv, vec2(0.0), vec2(1.0));\n")
                .replace("vec2 feather = max(vec2(length(rowX.xy), length(rowY.xy)), vec2(1e-5));",
                        "vec2 feather = max(f, vec2(1e-5));")
                .replace("* rowX.w;", "* w * soft.x * soft.y;");
        if (soft.contains("rowX") || soft.contains("rowY") || !soft.contains("vec2 soft =")) {
            throw new IllegalStateException("soft sampler did not build");
        }
        String arc = """
                vec2 arcBack(vec2 z, vec4 seg, float t) {
                  if (seg.z == 0.0 && seg.w == 0.0) return z - t * seg.xy;
                  float k = exp(-t * seg.z);
                  float a = -t * seg.w;
                  float c = cos(a);
                  float s = sin(a);
                  vec2 d = z - seg.xy;
                  return seg.xy + k * vec2(c * d.x - s * d.y, s * d.x + c * d.y);
                }
                vec2 uvAt(vec4 rx, vec4 ry, vec2 z) {
                  return vec2(dot(rx.xyz, vec3(z, 1.0)), dot(ry.xyz, vec3(z, 1.0)));
                }
                """;
        return shader.substring(0, end) + soft + arc + shader.substring(end);
    }

    private static String fragmentShaderText(Source kind, MotionShutter.Look trail) {
        boolean pip = kind == Source.PIP;
        boolean overlay = !pip && kind != Source.SPINE;
        boolean bitmap = kind == Source.PICTURE || kind == Source.PREMULTIPLIED_OVERLAY;
        // Statically named rows stay below the GLES 2 minimum fragment-uniform limit.
        StringBuilder source = new StringBuilder("""
                #version 100
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                precision highp float;
                #else
                precision mediump float;
                #endif
                varying vec2 vTexSamplingCoord;
                uniform sampler2D uTexSampler;
                uniform vec4 uBounds;
                """);
        source.append(overlay ? "uniform vec3 uFrame;\nuniform sampler2D uBackdrop;\n"
                : pip ? "uniform vec3 uFrame;\n" : "uniform vec2 uFrame;\n");
        int rowCount = trail == null ? SAMPLE_COUNT : RigidShutterPlan.MAX_TRAIL_POSES;
        for (int i = 0; i < rowCount; i++) {
            source.append("uniform vec4 uTapX").append(i).append(", uTapY").append(i).append(";\n");
        }
        if (trail != null) {
            source.append("uniform vec4 uSharpX, uSharpY;\nuniform vec3 uScale;\n");
            for (int i = 0; i + 1 < RigidShutterPlan.MAX_TRAIL_POSES; i++) {
                source.append("uniform vec4 uSeg").append(i).append(";\n");
            }
        }
        if (bitmap) {
            source.append("""
                    vec3 toLinear(vec3 rgb) {
                      return mix(rgb / 12.92, pow((rgb + 0.055) / 1.055, vec3(2.4)),
                                 step(vec3(0.04045), rgb));
                    }
                    vec3 toSdr(vec3 rgb) {
                      return mix(rgb * 12.92, 1.055 * pow(rgb, vec3(1.0 / 2.4)) - 0.055,
                                 step(vec3(0.0031308), rgb));
                    }
                    """);
        } else {
            source.append("""
                    // Default SDR transfer at Media3's effect boundary (SMPTE 170M).
                    vec3 toLinear(vec3 rgb) {
                      vec3 power = pow((rgb + 0.099) / 1.099, vec3(1.0 / 0.45));
                      return mix(rgb / 4.5, power, step(vec3(0.081), rgb));
                    }
                    vec3 toSdr(vec3 rgb) {
                      vec3 power = 1.099 * pow(rgb, vec3(0.45)) - 0.099;
                      return mix(4.5 * rgb, power, step(vec3(0.018), rgb));
                    }
                    """);
        }
        source.append("""
                vec4 samplePose(vec3 pixel, vec4 rowX, vec4 rowY) {
                  if (rowX.w == 0.0) return vec4(0.0);
                  vec2 uv = vec2(dot(rowX.xyz, pixel), dot(rowY.xyz, pixel));
                  if (any(lessThanEqual(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0))))
                    return vec4(0.0);
                """);
        if (bitmap) {
            source.append("""
                    // Match the existing normal blend's RGB and alpha for uploaded bitmaps.
                    vec4 sampleColor = texture2D(uTexSampler, uv);
                    float alpha = sampleColor.a * rowX.w;
                    """);
            source.append(kind == Source.PICTURE
                    ? "  vec3 rgb = sampleColor.rgb;\n"
                    : "  vec3 rgb = clamp(sampleColor.rgb / max(sampleColor.a, 0.001), 0.0, 1.0);\n");
            source.append("  return vec4(toLinear(rgb) * alpha, alpha);\n");
        } else {
            source.append("""
                    vec2 feather = max(vec2(length(rowX.xy), length(rowY.xy)), vec2(1e-5));
                    vec2 coverage = smoothstep(vec2(0.0), feather, min(uv, 1.0 - uv));
                    vec4 sampleColor = texture2D(uTexSampler, vec2(uv.x, 1.0 - uv.y));
                    if (sampleColor.a <= 0.0) return vec4(0.0);
                    vec3 straight = clamp(sampleColor.rgb / sampleColor.a, 0.0, 1.0);
                    float alpha = sampleColor.a * coverage.x * coverage.y * rowX.w;
                    return vec4(toLinear(straight) * alpha, alpha);
                    """);
        }
        source.append("""
                }
                void main() {
                """);
        // The existing PiP compositor expects the same row order as a Canvas upload.
        source.append(pip ? "  vec2 p = vTexSamplingCoord * uFrame.xy;\n"
                : "  vec2 p = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uFrame.xy;\n");
        source.append(overlay
                ? "  vec4 base = texture2D(uBackdrop, vTexSamplingCoord);\n"
                : "  vec4 base = vec4(0.0);\n");
        source.append("""
                  if (any(lessThan(p, uBounds.xy)) || any(greaterThanEqual(p, uBounds.zw))) {
                    gl_FragColor = base;
                    return;
                  }
                  vec3 pixel = vec3(p, 1.0);
                  vec4 color = vec4(0.0);
                """);
        if (trail == null) {
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                source.append("  color += samplePose(pixel, uTapX").append(i)
                        .append(", uTapY").append(i).append(");\n");
            }
        } else {
            appendTrail(source, trail);
        }
        source.append("""
                  if (color.a <= 0.0) {
                    gl_FragColor = base;
                    return;
                  }
                  vec3 rgb = toSdr(clamp(color.rgb / color.a, 0.0, 1.0));
                """);
        source.append(overlay
                ? "  gl_FragColor = vec4(mix(base.rgb, rgb, color.a * uFrame.z), base.a);\n"
                : "  gl_FragColor = vec4(rgb * color.a, color.a);\n");
        if (pip) source.append("  gl_FragColor *= uFrame.z;\n");
        return source.append("}\n").toString();
    }

    /**
     * STU-7 §K.2 trail accumulation: Smear walks {@link RigidShutterPlan#TRAIL_SUBSTEPS} steps
     * between each pair of neighbouring key poses (rows carry Fade weights, rowY.w marks a live
     * pose); Copies draws the poses alone; Both does half of each. uScale = smear, copy, sharp.
     */
    private static void appendTrail(StringBuilder source, MotionShutter.Look look) {
        int n = RigidShutterPlan.MAX_TRAIL_POSES;
        boolean smear = look != MotionShutter.Look.COPIES;
        boolean copies = look != MotionShutter.Look.SMEAR;
        if (smear) {
            // Each live segment: steps along the arc from pose i toward pose i + 1. A step's
            // softening is how far this pixel's UV moves to the next step, so neighbours blend.
            source.append("  vec2 u; vec2 nu; vec2 d; float ww; vec2 endU = vec2(0.0); vec2 endF = vec2(0.0); float endW = 0.0;\n");
            int steps = RigidShutterPlan.TRAIL_SUBSTEPS;
            for (int i = 0; i + 1 < n; i++) {
                String rx = "uTapX" + i, ry = "uTapY" + i, seg = "uSeg" + i;
                source.append("  if (uTapY").append(i + 1).append(".w > 0.0) {\n");
                source.append("    u = uvAt(").append(rx).append(", ").append(ry).append(", pixel.xy);\n");
                for (int j = 0; j < steps; j++) {
                    String tNext = Float.toString((float) (j + 1) / steps);
                    String tHere = Float.toString((float) j / steps);
                    source.append("    nu = uvAt(").append(rx).append(", ").append(ry)
                            .append(", arcBack(pixel.xy, ").append(seg).append(", ").append(tNext).append("));\n");
                    // two taps a step (at the step and halfway to the next), each softened by
                    // its half step, capped so a huge step cannot smear past the thing's edge
                    source.append("    d = min(abs(nu - u), vec2(0.35)) * 0.5;\n");
                    source.append("    ww = mix(").append(rx).append(".w, uTapX").append(i + 1)
                            .append(".w, ").append(tHere).append(");\n");
                    source.append("    color += uScale.x * 0.5 * (sampleUvSoft(u, d, ww)"
                            + " + sampleUvSoft(0.5 * (u + nu), d, ww));\n");
                    if (j + 1 < steps) source.append("    u = nu;\n");
                }
                source.append("    endU = nu; endF = d; endW = uTapX").append(i + 1).append(".w;\n");
                source.append("  }\n");
            }
            // The newest live pose closes the smear.
            source.append("  if (endW > 0.0) color += uScale.x * sampleUvSoft(endU, endF, endW);\n");
        }
        if (copies) {
            for (int i = 0; i < n; i++) {
                source.append("  if (uTapY").append(i).append(".w > 0.0) color += uScale.y * samplePose(pixel, uTapX")
                        .append(i).append(", uTapY").append(i).append(");\n");
            }
        }
        source.append("  if (uScale.z > 0.0) color += uScale.z * samplePose(pixel, uSharpX, uSharpY);\n");
        // A stroke's overlapping samples can sum past full coverage: keep it a coverage.
        source.append("  if (color.a > 1.0) color /= color.a;\n");
    }
}
