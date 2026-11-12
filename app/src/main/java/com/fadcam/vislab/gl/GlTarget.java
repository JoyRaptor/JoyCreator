package com.fadcam.vislab.gl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

/**
 * A framebuffer with one or more colour textures (several = MRT, for the particle state).
 * Created and freed on the GL thread that owns the context.
 */
public final class GlTarget {

    /** Texture format: internal format and the pixel type used to allocate it. */
    public static final class Format {
        final int internal;
        final int type;
        final String name;

        Format(int internal, int type, String name) {
            this.internal = internal;
            this.type = type;
            this.name = name;
        }
    }

    public static final Format RGBA8 = new Format(GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE, "RGBA8");
    public static final Format RGBA16F = new Format(GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT, "RGBA16F");
    public static final Format RGBA32F = new Format(GLES30.GL_RGBA32F, GLES30.GL_FLOAT, "RGBA32F");

    public final int fb;
    public final int[] texs;
    public final int w, h;

    private GlTarget(int fb, int[] texs, int w, int h) {
        this.fb = fb;
        this.texs = texs;
        this.w = w;
        this.h = h;
    }

    public int tex() {
        return texs[0];
    }

    @NonNull
    public static GlTarget create(int w, int h, @NonNull Format fmt, int attachments, boolean linear) {
        int[] ids = new int[1];
        GLES30.glGenFramebuffers(1, ids, 0);
        int fb = ids[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb);
        int[] texs = new int[attachments];
        GLES30.glGenTextures(attachments, texs, 0);
        int filter = linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST;
        int[] bufs = new int[attachments];
        for (int i = 0; i < attachments; i++) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texs[i]);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, fmt.internal, w, h, 0, GLES30.GL_RGBA, fmt.type, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i,
                    GLES30.GL_TEXTURE_2D, texs[i], 0);
            bufs[i] = GLES30.GL_COLOR_ATTACHMENT0 + i;
        }
        if (attachments > 1) GLES30.glDrawBuffers(attachments, bufs, 0);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, new int[]{fb}, 0);
            GLES30.glDeleteTextures(attachments, texs, 0);
            throw new IllegalStateException("framebuffer incomplete 0x" + Integer.toHexString(st)
                    + " " + w + "x" + h + " " + fmt.name);
        }
        return new GlTarget(fb, texs, w, h);
    }

    public void free() {
        GLES30.glDeleteFramebuffers(1, new int[]{fb}, 0);
        GLES30.glDeleteTextures(texs.length, texs, 0);
    }

    public static void free(GlTarget t) {
        if (t != null) t.free();
    }
}
