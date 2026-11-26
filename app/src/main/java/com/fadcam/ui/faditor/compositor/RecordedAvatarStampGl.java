package com.fadcam.ui.faditor.compositor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.avatar.AvatarItemPuppet;
import com.fadcam.ui.faditor.compositor.lane.ExactLaneExposure;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionShutter;

/** Renders exact recorded avatar taps into one isolated, premultiplied sRGB texture. */
public final class RecordedAvatarStampGl {
    private static final String SAMPLE = """
            #version 100
            precision mediump float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uAvatar;
            uniform float uOpacity;
            void main() {
              vec2 bitmapUv = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
              gl_FragColor = texture2D(uAvatar, bitmapUv) * uOpacity;
            }
            """;
    private static final int MAX_RASTER_EDGE = 512;
    private static final String COMPOSITE = """
            #version 100
            precision mediump float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uBase;
            uniform sampler2D uStamp;
            void main() {
              vec4 base = texture2D(uBase, vTexSamplingCoord);
              vec4 stamp = texture2D(uStamp, vTexSamplingCoord);
              gl_FragColor = vec4(base.rgb * (1.0 - stamp.a) + stamp.rgb, base.a);
            }
            """;

    @FunctionalInterface
    public interface Opacity {
        float sample(long editorUs);
    }

    private final ExactLaneExposure exposure = new ExactLaneExposure(ExactLaneExposure.Encoding.SRGB);
    private final int[] priorFramebuffer = new int[1];
    private final int[] priorViewport = new int[4];
    private final float[] priorClearColor = new float[4];
    private final float[] transform = GlUtil.create4x4IdentityMatrix();
    private final float[] opacity = new float[1];
    private final RectF bounds = new RectF();
    private GlProgram copy;
    private GlProgram composite;
    private Bitmap bitmap;
    private Canvas canvas;
    private int texture;

    /**
     * The puppet must be owned by the same render thread as this instance. The returned texture
     * is borrowed until the next draw or release. Zero declines to the ordinary sharp path.
     */
    public int render(int width, int height, MotionShutter.Schedule schedule, long itemStartUs,
                      AvatarItemPuppet.Exposure avatar, ItemMotionSampler placement, Opacity alpha)
            throws GlUtil.GlException, VideoFrameProcessingException {
        if (schedule == null || avatar == null
                || !MeshExposureGlPolicy.admits(width, height, schedule.timesUs(), schedule.weights())) return 0;

        boolean depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, priorFramebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, priorViewport, 0);
        GLES20.glGetFloatv(GLES20.GL_COLOR_CLEAR_VALUE, priorClearColor, 0);
        try {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            prepareRaster(Math.min(MAX_RASTER_EDGE, Math.max(width, height)));
            exposure.configure(width, height);
            return exposure.drawToTexture(schedule, (target, editorUs) -> {
                var pose = placement.at(editorUs);
                float weight = alpha.sample(editorUs);
                if (!Float.isFinite(weight) || pose == null
                        || !pose.writeClipTransform(transform, width, height)) {
                    throw new VideoFrameProcessingException("Invalid recorded avatar tap placement");
                }
                GlUtil.focusFramebufferUsingCurrentContext(target, width, height);
                GLES20.glDisable(GLES20.GL_BLEND);
                GLES20.glClearColor(0f, 0f, 0f, 0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                if (weight <= 0f) return;
                bitmap.eraseColor(0);
                avatar.draw(canvas, bounds, editorUs - itemStartUs, 1f);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
                copy.use();
                copy.setSamplerTexIdUniform("uAvatar", texture, 0);
                copy.setFloatsUniform("uTransformationMatrix", transform);
                opacity[0] = Math.min(1f, weight);
                copy.setFloatsUniform("uOpacity", opacity);
                copy.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            });
        } finally {
            restore(GLES20.GL_DEPTH_TEST, depth);
            restore(GLES20.GL_CULL_FACE, cull);
            restore(GLES20.GL_SCISSOR_TEST, scissor);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, priorFramebuffer[0]);
            GLES20.glViewport(priorViewport[0], priorViewport[1], priorViewport[2], priorViewport[3]);
            GLES20.glClearColor(priorClearColor[0], priorClearColor[1], priorClearColor[2], priorClearColor[3]);
        }
    }

    private void prepareRaster(int edge) throws GlUtil.GlException {
        if (copy == null) {
            copy = new GlProgram(RigidTextureShutterPass.VERTEX_SHADER, SAMPLE);
            copy.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            copy.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        }
        if (bitmap != null && bitmap.getWidth() == edge && texture != 0) return;
        releaseRaster();
        bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        bounds.set(0, 0, edge, edge);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        texture = GlUtil.createTexture(edge, edge, false);
    }

    public void composite(int targetFramebuffer, int baseTexture, int stampTexture, int width, int height)
            throws GlUtil.GlException {
        if (composite == null) {
            composite = new GlProgram(RigidTextureShutterPass.VERTEX_SHADER, COMPOSITE);
            composite.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            var identity = GlUtil.create4x4IdentityMatrix();
            composite.setFloatsUniform("uTransformationMatrix", identity);
            composite.setFloatsUniform("uTexTransformationMatrix", identity);
        }
        boolean blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        boolean depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        boolean cull = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GlUtil.focusFramebufferUsingCurrentContext(targetFramebuffer, width, height);
            composite.use();
            composite.setSamplerTexIdUniform("uBase", baseTexture, 0);
            composite.setSamplerTexIdUniform("uStamp", stampTexture, 1);
            composite.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GlUtil.checkGlError();
        } finally {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            restore(GLES20.GL_BLEND, blend);
            restore(GLES20.GL_SCISSOR_TEST, scissor);
            restore(GLES20.GL_DEPTH_TEST, depth);
            restore(GLES20.GL_CULL_FACE, cull);
        }
    }

    private static void restore(int state, boolean enabled) {
        if (enabled) GLES20.glEnable(state);
        else GLES20.glDisable(state);
    }

    private void releaseRaster() {
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
        canvas = null;
        if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        texture = 0;
    }

    public void release() throws GlUtil.GlException {
        releaseRaster();
        try {
            exposure.release();
        } finally {
            GlProgram oldCopy = copy;
            GlProgram oldComposite = composite;
            copy = null;
            composite = null;
            try {
                if (oldCopy != null) oldCopy.delete();
            } finally {
                if (oldComposite != null) oldComposite.delete();
            }
        }
    }
}
