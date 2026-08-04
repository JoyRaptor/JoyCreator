package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;

/**
 * M-EXPORT-2 — the export compositor for EVERY PiP clip (one instance per clip,
 * NORMAL included since the z-unification fix): inserted into the master item's
 * effect list in track z-order (bottom→top), BEFORE the text/caption
 * {@code OverlayEffect}, so ALL PiPs share one z authority and keep the preview
 * rule (video < PiPs-in-z-order < sprites/text/captions). NORMAL renders as
 * plain SRC_OVER (shader mode 0); MULTIPLY/SCREEN/OVERLAY/ADD blend against the
 * ACCUMULATED frame — the capability a {@code BitmapOverlay} cannot express and
 * the reason PiPs left {@code CompositeExportOverlay} (drawing NORMAL there and
 * blends here z-inverted any project that interleaved them).
 *
 * <p>Follows {@code GlTransitionExportEffect}/{@code GlTransitionShaderProgram}
 * exactly: a {@link BaseGlShaderProgram} whose fragment shader samples the frame
 * plus a {@link PipFrameOverlay} texture (the PiP frame already positioned on the
 * proven Canvas path, so the shader blends with plain full-frame UVs — the export
 * transition wrapper samples its bitmap overlay unflipped, device-proven).
 * Overlay pixels arrive PREMULTIPLIED (GLUtils upload); the shader unpremultiplies
 * before applying the blend equation, then mixes by alpha (which already carries
 * the clip's keyframed opacity via the Canvas paint).</p>
 *
 * <p>COMPOSITING FAMILY (FEEDBACK_20260702 §C) — all three stages modulate the
 * src alpha BEFORE the blend mix, in fixed order:</p>
 * <ol>
 *   <li>MASKS run on the Canvas side ({@code PipFrameOverlay} clips through
 *       {@code MaskPathBuilder}) — the shader never sees masked pixels.</li>
 *   <li>CHROMA KEY: straight-RGB distance to the key color →
 *       {@code smoothstep(tolerance, tolerance+fuzziness)} soft matte, plus a
 *       post-key alpha offset (choke/spread).</li>
 *   <li>TRACK MATTE: a SECOND {@link PipFrameOverlay} renders the matte peer
 *       clip positioned by ITS OWN transform; the shader multiplies src alpha
 *       by matte LUMA × matte alpha. Gated per-frame on the CPU
 *       ({@code PipFrameOverlay#activeAt}) so a recipient renders UNMATTED
 *       outside the matte's time window (B3's overlap rule) — the shader
 *       cannot tell an inactive matte from a black one.</li>
 * </ol>
 *
 * <p>Preview shows NORMAL/unkeyed/unmatted compositing until a preview
 * approximation lands — documented per Part 10 probe #4: export is ground
 * truth. (Preview MASKS do land with this slice via the preview clip path.)</p>
 */
public final class BlendModeGlEffect implements GlEffect {

    /** Wire values match {@code Clip#getOverlayBlendMode()} strings. */
    static int modeCode(@NonNull String blendMode) {
        switch (blendMode) {
            case "MULTIPLY": return 1;
            case "SCREEN":   return 2;
            case "OVERLAY":  return 3;
            case "ADD":      return 4;
            default:          return 0; // NORMAL = plain SRC_OVER (mode 0 in the shader)
        }
    }

    private final Context context;
    private final Clip clip;
    private final long editorTimeOffsetMs;
    @Nullable private final Clip matteClip;

    // NOTE: the old 2-arg constructor was DELETED rather than kept for convenience. It
    // hard-coded editorTimeOffsetMs = 0, so any future caller would have silently reintroduced
    // the §2d drift with no compile error and no test failure — a trap left lying in the road.
    // Callers must state the offset.

    /** @param matteClip the resolved matte peer (from {@code compositing.mattePeerId}),
     *                   or null. Resolution + peer-hiding is ExportManager's job. */
    public BlendModeGlEffect(@NonNull Context context, @NonNull Clip clip,
                             @Nullable Clip matteClip, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.clip = clip;
        this.matteClip = matteClip;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @NonNull
    @Override
    public BaseGlShaderProgram toGlShaderProgram(@NonNull Context ignored, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) {
            throw new VideoFrameProcessingException("HDR blend modes are not supported");
        }
        return new Program(context, clip, matteClip, editorTimeOffsetMs);
    }

    private static final class Program extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
                "#version 100\n"
                + "attribute vec4 aFramePosition;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aFramePosition;\n"
                + "  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n"
                + "}\n";

        private static final String FRAGMENT_SHADER =
                "#version 100\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexSamplingCoord;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "uniform sampler2D uMatteTexSampler0;\n"
                + "uniform float uBlendMode;\n" // float: ES2 int uniforms are patchy on old drivers
                + "uniform vec3 uKeyColor;\n"
                // x=enabled(0/1), y=tolerance, z=fuzziness, w=offset
                + "uniform vec4 uKeyParams;\n"
                + "uniform float uMatteOn;\n"
                + "vec3 blendPix(vec3 b, vec3 s) {\n"
                + "  if (uBlendMode < 0.5) return s;\n" // NORMAL: mix-by-alpha below = SRC_OVER
                + "  if (uBlendMode < 1.5) return b * s;\n"
                + "  if (uBlendMode < 2.5) return 1.0 - (1.0 - b) * (1.0 - s);\n"
                + "  if (uBlendMode < 3.5) {\n"
                + "    vec3 lo = 2.0 * b * s;\n"
                + "    vec3 hi = 1.0 - 2.0 * (1.0 - b) * (1.0 - s);\n"
                + "    return vec3(b.r < 0.5 ? lo.r : hi.r,\n"
                + "                b.g < 0.5 ? lo.g : hi.g,\n"
                + "                b.b < 0.5 ? lo.b : hi.b);\n"
                + "  }\n"
                + "  return min(b + s, vec3(1.0));\n"
                + "}\n"
                + "void main() {\n"
                + "  vec4 base = texture2D(uVideoTexSampler0, vTexSamplingCoord);\n"
                // Bitmap textures upload Y-DOWN (row 0 = top) while the frame
                // UVs are Y-UP — sample the overlay V-flipped or every PiP
                // composites upside-down/mirrored-in-place. CONFIRMED on
                // device: pre-fix, a PiP authored at y=.378 exported at
                // y=.622 (=1-.378) while the preview showed .378 — the
                // z-unification A/B luma proof was blind to it (both sides
                // flipped identically). Flipping V here un-flips position,
                // rotation and masks as one finished image.
                + "  vec2 ovc = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);\n"
                + "  vec4 src = texture2D(uOverlayTexSampler0, ovc);\n"
                + "  vec3 sc = src.rgb / max(src.a, 0.001);\n" // unpremultiply
                + "  float a = src.a;\n"
                + "  if (uKeyParams.x > 0.5) {\n"
                // Chroma key on the STRAIGHT color: distance → soft matte.
                + "    float d = distance(sc, uKeyColor);\n"
                + "    float keep = smoothstep(uKeyParams.y,\n"
                + "                            uKeyParams.y + uKeyParams.z + 0.0001, d);\n"
                + "    a *= clamp(keep + uKeyParams.w, 0.0, 1.0);\n"
                + "  }\n"
                + "  if (uMatteOn > 0.5) {\n"
                // Luma matte: the matte frame is premultiplied; luma of its
                // straight color × its alpha (empty regions matte to 0).
                + "    vec4 mt = texture2D(uMatteTexSampler0, ovc);\n"
                + "    vec3 mc = mt.rgb / max(mt.a, 0.001);\n"
                + "    a *= dot(mc, vec3(0.299, 0.587, 0.114)) * mt.a;\n"
                + "  }\n"
                + "  vec3 outc = mix(base.rgb, clamp(blendPix(base.rgb, sc), 0.0, 1.0), a);\n"
                + "  gl_FragColor = vec4(outc, base.a);\n"
                + "}\n";

        private final GlProgram glProgram;
        private final PipFrameOverlay overlay;
        @Nullable private final PipFrameOverlay matteOverlay;
        private final float mode;
        private final float[] keyColor = new float[3];
        private final float[] keyParams = new float[4];

        Program(@NonNull Context context, @NonNull Clip clip, @Nullable Clip matteClip,
                long editorTimeOffsetMs)
                throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1);
            this.overlay = new PipFrameOverlay(context, clip, editorTimeOffsetMs);
            this.matteOverlay = matteClip != null
                    ? new PipFrameOverlay(context, matteClip, editorTimeOffsetMs) : null;
            this.mode = modeCode(clip.getOverlayBlendMode());
            CompositingSpec spec = clip.getCompositing();
            boolean keyOn = spec != null && spec.keyEnabled;
            keyParams[0] = keyOn ? 1f : 0f;
            if (keyOn) {
                keyColor[0] = ((spec.keyColor >> 16) & 0xFF) / 255f;
                keyColor[1] = ((spec.keyColor >> 8) & 0xFF) / 255f;
                keyColor[2] = (spec.keyColor & 0xFF) / 255f;
                keyParams[1] = spec.keyTolerance;
                keyParams[2] = spec.keyFuzziness;
                keyParams[3] = spec.keyOffset;
            }
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
                this.glProgram.setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), 4);
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @NonNull
        @Override
        public Size configure(int inputWidth, int inputHeight) {
            Size size = new Size(Math.max(1, inputWidth), Math.max(1, inputHeight));
            overlay.configure(size);
            if (matteOverlay != null) matteOverlay.configure(size);
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uVideoTexSampler0", inputTexId, 0);
                glProgram.setSamplerTexIdUniform("uOverlayTexSampler0",
                        overlay.getTextureId(presentationTimeUs), 1);
                boolean matteOn = matteOverlay != null
                        && matteOverlay.activeAt(presentationTimeUs);
                // The matte texture unit must always be bound on ES2 — reuse the
                // overlay texture as a harmless placeholder while inactive.
                glProgram.setSamplerTexIdUniform("uMatteTexSampler0",
                        matteOn ? matteOverlay.getTextureId(presentationTimeUs)
                                : overlay.getTextureId(presentationTimeUs), 2);
                glProgram.setFloatUniform("uBlendMode", mode);
                glProgram.setFloatsUniform("uKeyColor", keyColor);
                glProgram.setFloatsUniform("uKeyParams", keyParams);
                glProgram.setFloatUniform("uMatteOn", matteOn ? 1f : 0f);
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                overlay.release();
                if (matteOverlay != null) matteOverlay.release();
                glProgram.delete();
                super.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
