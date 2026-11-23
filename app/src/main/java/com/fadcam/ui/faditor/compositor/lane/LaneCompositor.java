package com.fadcam.ui.faditor.compositor.lane;

import android.opengl.GLES20;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import com.fadcam.ui.faditor.compositor.RigidTextureShutterPass;

/** Composes a bottom-to-top scope. Each pass receives the pixels of every earlier entry. */
public final class LaneCompositor {
    @FunctionalInterface
    public interface DrawPass {
        void draw(int index, int prefixTexture, long presentationTimeUs)
                throws VideoFrameProcessingException;
    }

    private static final String COPY = """
            #version 100
            precision mediump float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTexSamplingCoord); }
            """;

    private final int[] textures = new int[2];
    private final int[] framebuffers = new int[2];
    private GlProgram copy;
    private int width;
    private int height;

    public void configure(int width, int height) throws GlUtil.GlException {
        if (width == this.width && height == this.height) return;
        releaseTargets();
        this.width = width;
        this.height = height;
    }

    /** The last pass writes directly into the caller's target. The input and target must differ. */
    public void drawScopeAt(int inputTexture, int outputFramebuffer, int passCount,
                            long presentationTimeUs, DrawPass pass)
            throws VideoFrameProcessingException, GlUtil.GlException {
        try {
            if (passCount == 0) {
                drawCopy(inputTexture, outputFramebuffer);
                return;
            }
            int prefix = inputTexture;
            for (int i = 0; i < passCount; i++) {
                boolean last = i == passCount - 1;
                int slot = i % 2;
                if (!last) ensureTarget(slot);
                GlUtil.focusFramebufferUsingCurrentContext(
                        last ? outputFramebuffer : framebuffers[slot], width, height);
                pass.draw(i, prefix, presentationTimeUs);
                if (!last) prefix = textures[slot];
            }
        } finally {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer);
            GLES20.glViewport(0, 0, width, height);
        }
    }

    public void drawCopy(int inputTexture, int outputFramebuffer) throws GlUtil.GlException {
        GlUtil.focusFramebufferUsingCurrentContext(outputFramebuffer, width, height);
        copy(inputTexture);
    }

    private void ensureTarget(int slot) throws GlUtil.GlException {
        if (framebuffers[slot] != 0) return;
        if (textures[slot] == 0) textures[slot] = GlUtil.createTexture(width, height, false);
        framebuffers[slot] = GlUtil.createFboForTexture(textures[slot]);
    }

    private void copy(int from) throws GlUtil.GlException {
        if (copy == null) {
            copy = new GlProgram(RigidTextureShutterPass.VERTEX_SHADER, COPY);
            copy.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            float[] identity = GlUtil.create4x4IdentityMatrix();
            copy.setFloatsUniform("uTransformationMatrix", identity);
            copy.setFloatsUniform("uTexTransformationMatrix", identity);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        copy.use();
        copy.setSamplerTexIdUniform("uTexture", from, 0);
        copy.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    private void releaseTargets() throws GlUtil.GlException {
        GLES20.glDeleteFramebuffers(framebuffers.length, framebuffers, 0);
        GLES20.glDeleteTextures(textures.length, textures, 0);
        java.util.Arrays.fill(framebuffers, 0);
        java.util.Arrays.fill(textures, 0);
        GlUtil.checkGlError();
    }

    public void release() throws GlUtil.GlException {
        try {
            releaseTargets();
        } finally {
            if (copy != null) {
                copy.delete();
                copy = null;
            }
        }
    }
}
