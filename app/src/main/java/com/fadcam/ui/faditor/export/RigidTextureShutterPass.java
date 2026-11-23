package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionBreaks;
import com.fadcam.ui.faditor.motion.MotionShutter;
import com.fadcam.ui.faditor.motion.RigidShutterPlan;

/** Resolves several placements of the same isolated studio frame in one draw. */
final class RigidTextureShutterPass {
    private static final int SAMPLE_COUNT = 6;

    static final String VERTEX_SHADER =
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

    static RigidShutterPlan plan(MotionShutter.Controls controls, ItemMotionSampler sampler,
                                 long timeUs, long periodUs, MotionBreaks breaks, int width, int height) {
        return plan(controls, sampler, timeUs, periodUs, breaks, MotionBreaks.none(), width, height);
    }

    static RigidShutterPlan plan(MotionShutter.Controls controls, ItemMotionSampler sampler,
                                 long timeUs, long periodUs, MotionBreaks breaks,
                                 MotionBreaks sourceBreaks, int width, int height) {
        if (periodUs <= 0 || controls.mixPercent() == 0 || controls.angle() == 0) return null;
        var schedule = controls.schedule(timeUs, periodUs, SAMPLE_COUNT, breaks, sourceBreaks);
        return RigidShutterPlan.prepare(sampler, timeUs, schedule, width, height, 1.0);
    }

    enum Source { SPINE, PICTURE, PREMULTIPLIED_OVERLAY, PIP }

    private final GlProgram program;
    private final boolean overlay;
    private final boolean pip;
    private final float[] frame;
    private final float[] bounds = new float[4];
    private final float[][] rows = new float[SAMPLE_COUNT * 2][4];

    RigidTextureShutterPass(Source source) throws GlUtil.GlException {
        pip = source == Source.PIP;
        overlay = !pip && source != Source.SPINE;
        frame = new float[overlay || pip ? 3 : 2];
        program = new GlProgram(VERTEX_SHADER, fragmentShader(source));
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
        float[] identity = GlUtil.create4x4IdentityMatrix();
        program.setFloatsUniform("uTransformationMatrix", identity);
        program.setFloatsUniform("uTexTransformationMatrix", identity);
    }

    void draw(int textureId, int width, int height, RigidShutterPlan plan,
              int baseTexture, float opacity) throws GlUtil.GlException {
        frame[0] = width;
        frame[1] = height;
        var footprint = plan.bounds();
        bounds[0] = footprint.left();
        bounds[1] = footprint.top();
        bounds[2] = footprint.right();
        bounds[3] = footprint.bottom();
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

    void release() throws GlUtil.GlException {
        program.delete();
    }

    private static String fragmentShader(Source kind) {
        boolean pip = kind == Source.PIP;
        boolean overlay = !pip && kind != Source.SPINE;
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
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            source.append("uniform vec4 uTapX").append(i).append(", uTapY").append(i).append(";\n");
        }
        if (overlay) {
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
        if (overlay) {
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
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            source.append("  color += samplePose(pixel, uTapX").append(i)
                    .append(", uTapY").append(i).append(");\n");
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
}
