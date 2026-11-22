package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.motion.RigidShutterPlan;

/** Resolves several placements of the same isolated studio frame in one draw. */
final class RigidSpineShutterPass {
    static final int SAMPLE_COUNT = 6;

    private final GlProgram program;
    private final float[] frame = new float[2];
    private final float[] bounds = new float[4];
    private final float[][] rows = new float[SAMPLE_COUNT * 2][4];

    RigidSpineShutterPass(String vertexShader) throws GlUtil.GlException {
        program = new GlProgram(vertexShader, fragmentShader());
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
        float[] identity = GlUtil.create4x4IdentityMatrix();
        program.setFloatsUniform("uTransformationMatrix", identity);
        program.setFloatsUniform("uTexTransformationMatrix", identity);
    }

    void draw(int textureId, int width, int height, RigidShutterPlan plan) throws GlUtil.GlException {
        frame[0] = width;
        frame[1] = height;
        var footprint = plan.bounds();
        bounds[0] = footprint.left();
        bounds[1] = footprint.top();
        bounds[2] = footprint.right();
        bounds[3] = footprint.bottom();
        program.use();
        program.setSamplerTexIdUniform("uTexSampler", textureId, 0);
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

    private static String fragmentShader() {
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
                uniform vec2 uFrame;
                uniform vec4 uBounds;
                """);
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            source.append("uniform vec4 uTapX").append(i).append(", uTapY").append(i).append(";\n");
        }
        source.append("""
                vec4 samplePose(vec3 pixel, vec4 rowX, vec4 rowY) {
                  if (rowX.w == 0.0) return vec4(0.0);
                  vec2 uv = vec2(dot(rowX.xyz, pixel), dot(rowY.xyz, pixel));
                  if (any(lessThanEqual(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0))))
                    return vec4(0.0);
                  vec2 feather = max(vec2(length(rowX.xy), length(rowY.xy)), vec2(1e-5));
                  vec2 coverage = smoothstep(vec2(0.0), feather, min(uv, 1.0 - uv));
                  // Canvas is top-left, while Media3's input texture starts at the bottom left.
                  return texture2D(uTexSampler, vec2(uv.x, 1.0 - uv.y))
                         * (coverage.x * coverage.y * rowX.w);
                }
                void main() {
                  vec2 p = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uFrame;
                  if (any(lessThan(p, uBounds.xy)) || any(greaterThanEqual(p, uBounds.zw))) {
                    gl_FragColor = vec4(0.0);
                    return;
                  }
                  vec3 pixel = vec3(p, 1.0);
                  vec4 color = vec4(0.0);
                """);
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            source.append("  color += samplePose(pixel, uTapX").append(i)
                    .append(", uTapY").append(i).append(");\n");
        }
        return source.append("  gl_FragColor = color;\n}\n").toString();
    }
}
