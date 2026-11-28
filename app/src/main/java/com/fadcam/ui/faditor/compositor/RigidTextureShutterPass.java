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
    /** Smooth programs by tap count, built on first use. */
    private final java.util.Map<Integer, GlProgram> trailPrograms = new java.util.HashMap<>();
    private final float[][] trailRows = new float[RigidShutterPlan.MAX_TAPS * 2][4];
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
        program = new GlProgram(VERTEX_SHADER, fragmentShader(source, 0));
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

    /** A smooth plan: its tap-count program, built once on first use, then plain taps. */
    private void drawTrail(int textureId, RigidShutterPlan plan, int baseTexture, float opacity)
            throws GlUtil.GlException {
        var trail = plan.trail();
        int n = trail.program();
        GlProgram p = trailPrograms.get(n);
        if (p == null) {
            p = new GlProgram(VERTEX_SHADER, fragmentShader(source, n));
            p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            float[] identity = GlUtil.create4x4IdentityMatrix();
            p.setFloatsUniform("uTransformationMatrix", identity);
            p.setFloatsUniform("uTexTransformationMatrix", identity);
            trailPrograms.put(n, p);
        }
        p.use();
        p.setSamplerTexIdUniform("uTexSampler", textureId, 0);
        if (overlay || pip) frame[2] = opacity;
        if (overlay) p.setSamplerTexIdUniform("uBackdrop", baseTexture, 1);
        p.setFloatsUniform("uFrame", frame);
        p.setFloatsUniform("uBounds", bounds);
        var taps = plan.taps();
        boolean[] smooth = trail.smooth();
        for (int i = 0; i < n; i++) {
            float[] x = trailRows[2 * i];
            float[] y = trailRows[2 * i + 1];
            if (i < taps.size()) {
                rowsOf(taps.get(i), x, y);
                y[3] = smooth[i] ? 1f : 0f;   // soften toward the next tap
            } else {
                x[0] = x[1] = x[2] = x[3] = 0f;
                y[0] = y[1] = y[2] = y[3] = 0f;
            }
            p.setFloatsUniform("uTapX" + i, x);
            p.setFloatsUniform("uTapY" + i, y);
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

    /** {@code taps} 0 = the camera shutter program; else the smooth program with that many taps. */
    static String fragmentShader(Source kind, int taps) {
        String shader = fragmentShaderText(kind, taps);
        return taps == 0 ? shader : withSoftSampler(shader);
    }

    /**
     * The trail program's helpers, built from the camera program's own {@code samplePose} text
     * (which stays exactly as it was):
     * <ul>
     *   <li>{@code sampleUvSoft(uv, f, w)}: samplePose at a given UV, its edges softened by
     *   {@code f} (how far this pixel's UV moves between two smear steps), so overlapping steps
     *   add up evenly whatever the direction of motion;</li>
     *   <li>{@code uvAt(rx, ry, z)}: a canvas point to the picture's UV through one tap's rows.</li>
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
                vec2 uvAt(vec4 rx, vec4 ry, vec2 z) {
                  return vec2(dot(rx.xyz, vec3(z, 1.0)), dot(ry.xyz, vec3(z, 1.0)));
                }
                """;
        return shader.substring(0, end) + soft + arc + shader.substring(end);
    }

    private static String fragmentShaderText(Source kind, int taps) {
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
        int rowCount = taps == 0 ? SAMPLE_COUNT : taps;
        for (int i = 0; i < rowCount; i++) {
            source.append("uniform vec4 uTapX").append(i).append(", uTapY").append(i).append(";\n");
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
        if (taps == 0) {
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                source.append("  color += samplePose(pixel, uTapX").append(i)
                        .append(", uTapY").append(i).append(");\n");
            }
        } else {
            appendTaps(source, taps);
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
     * STU-7 §K.2 smooth accumulation: plain taps, each a real pose with its weight in rowX.w.
     * A tap marked smooth (rowY.w) softens its edges by how far this pixel's UV moves to the
     * next tap, so neighbouring smear taps add up to an even streak; copies and the sharp pose
     * stay crisp. No per-pixel maths beyond two dot products a tap.
     */
    private static void appendTaps(StringBuilder source, int taps) {
        source.append("  vec2 cur = uvAt(uTapX0, uTapY0, pixel.xy);\n  vec2 nxt;\n  vec2 d;\n");
        for (int i = 0; i + 1 < taps; i++) {
            source.append("  nxt = uvAt(uTapX").append(i + 1).append(", uTapY").append(i + 1)
                    .append(", pixel.xy);\n");
            source.append("  d = uTapY").append(i).append(".w > 0.5 ? min(abs(nxt - cur), vec2(0.35)) : vec2(0.0);\n");
            source.append("  color += sampleUvSoft(cur, d, uTapX").append(i).append(".w);\n");
            source.append("  cur = nxt;\n");
        }
        source.append("  color += sampleUvSoft(cur, vec2(0.0), uTapX").append(taps - 1).append(".w);\n");
        // A stroke's overlapping taps can sum past full coverage: keep it a coverage.
        source.append("  if (color.a > 1.0) color /= color.a;\n");
    }
}
