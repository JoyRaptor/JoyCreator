package com.fadcam.ui.faditor.effects;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * The shader-only half of the clip colour grade on EXPORT — highlights, shadows, fade, vignette
 * and grain, the things no {@code RgbMatrix} upstream can express.
 *
 * <p><b>This is a {@link GlEffect}, and the program it makes is built LAZILY.</b> That split is
 * not tidiness. Effects are assembled by {@code ExportManager.assembleClipVideoEffects} on the
 * MAIN thread, long before media3 has created a GL context; the frame processor then calls
 * {@link #toGlShaderProgram} on ITS OWN GL thread. This class used to compile its
 * {@code GlProgram} in its constructor, which therefore ran with no current context —
 * {@code glCreateShader} returns 0, the compile fails with an EMPTY info log, and the whole
 * export aborts. <b>Every project using any of these five parameters failed to export</b>, and
 * the empty log plus the VERTEX source in the message pointed away from the real cause. Confirmed
 * against the shipped build on the sandbox Note 9 before this was changed, so the attribution is
 * measured rather than assumed.</p>
 *
 * <p>The grade itself lives in {@link ColorGradeGlSource}, shared with the live GL preview so the
 * editor and the export cannot compute a different picture.</p>
 */
public class ColorGradeShaderProgram implements GlEffect {

    @NonNull private final EffectStack params;

    /**
     * @param context unused now that nothing is compiled here — kept because
     *                {@code EffectStack.toEffects} passes one to every effect it builds, and
     *                dropping it from only this signature would be a trap for the next edit.
     */
    public ColorGradeShaderProgram(@NonNull android.content.Context context,
                                   @NonNull EffectStack params) {
        this.params = new EffectStack(params);
    }

    @NonNull
    @Override
    public GlShaderProgram toGlShaderProgram(@NonNull android.content.Context context, boolean hdr)
            throws VideoFrameProcessingException {
        if (hdr) {
            throw new VideoFrameProcessingException("HDR color grading is not supported");
        }
        // ON THE GL THREAD — the first moment a program can legally be compiled.
        return new Program(params);
    }

    /** The actual pass. Constructed only from {@link #toGlShaderProgram}. */
    private static final class Program extends BaseGlShaderProgram {

        private static final String VERTEX_SHADER =
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

        @NonNull private final EffectStack params;
        @NonNull private final GlProgram glProgram;
        private int width = 1;
        private int height = 1;

        Program(@NonNull EffectStack params) throws VideoFrameProcessingException {
            super(false, 2);
            this.params = params;
            try {
                this.glProgram = new GlProgram(VERTEX_SHADER, fragmentShader());
                this.glProgram.setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), 4);
                float[] identity = GlUtil.create4x4IdentityMatrix();
                this.glProgram.setFloatsUniform("uTransformationMatrix", identity);
                this.glProgram.setFloatsUniform("uTexTransformationMatrix", identity);
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @NonNull
        @Override
        public Size configure(int inputWidth, int inputHeight) {
            width = Math.max(1, inputWidth);
            height = Math.max(1, inputHeight);
            return new Size(width, height);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            try {
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                // uExposure / uTemperature / uTint are GONE, not merely unset — see the shader's
                // own note. media3's Brightness and RgbAdjustment sit EARLIER in the same chain
                // and already applied them, so setting them here applied each one twice.
                glProgram.setFloatUniform("uHighlights", params.getHighlights());
                glProgram.setFloatUniform("uShadows", params.getShadows());
                glProgram.setFloatUniform("uFade", params.getFade());
                glProgram.setFloatUniform("uVignette", params.getVignette());
                glProgram.setFloatUniform("uGrain", params.getGrain());
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
                glProgram.delete();
                super.release();
            } catch (Exception e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @NonNull
        private static String fragmentShader() {
            return "#version 100\n"
                    + "precision mediump float;\n"
                    + "uniform sampler2D uTexSampler;\n"
                    // NO uExposure / uTemperature / uTint. This program is the LAST link in a
                    // chain whose earlier links (media3 Brightness, then RgbAdjustment) have
                    // already applied exposure and temperature/tint — see EffectStack.toEffects.
                    // Declaring them here and adding them again was a double-apply with DIFFERENT
                    // maths each time (additive here, multiplicative there), and it fired on
                    // every project where any of highlights / shadows / fade / vignette / grain
                    // was non-zero.
                    + "uniform float uHighlights;\n"
                    + "uniform float uShadows;\n"
                    + "uniform float uFade;\n"
                    + "uniform float uVignette;\n"
                    + "uniform float uGrain;\n"
                    + "varying vec2 vTexSamplingCoord;\n"
                    // The grade itself lives in ColorGradeGlSource, because the LIVE GL PREVIEW
                    // runs the identical function on the phone. Only the sampling coordinate
                    // differs between the two — hence the uv parameter.
                    + ColorGradeGlSource.GRADE_FN
                    + "void main() {\n"
                    + "  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);\n"
                    + "  c.rgb = fadColorGrade(c.rgb, vTexSamplingCoord, uHighlights, uShadows,\n"
                    + "                        uFade, uVignette, uGrain);\n"
                    + "  gl_FragColor = c;\n"
                    + "}\n";
        }
    }
}
