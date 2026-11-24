package android.opengl;

public final class GLES20 {
    public static final int GL_FRAMEBUFFER = 0x8D40;
    public static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    public static final int GL_VIEWPORT = 0x0BA2;
    public static final int GL_ACTIVE_TEXTURE = 0x84E0;
    public static final int GL_DITHER = 0x0BD0;
    public static final int GL_TEXTURE0 = 0x84C0;
    public static final int GL_TRIANGLE_STRIP = 0x0005;

    public static int framebuffer = 41;
    public static final int[] viewport = {7, 8, 901, 503};
    public static int activeTexture = GL_TEXTURE0 + 5;
    public static boolean dither = true;
    public static int draws;
    public static int deletedFramebuffers;
    public static int deletedTextures;

    private GLES20() {}

    public static void glGetIntegerv(int name, int[] value, int offset) {
        if (name == GL_FRAMEBUFFER_BINDING) value[offset] = framebuffer;
        else if (name == GL_ACTIVE_TEXTURE) value[offset] = activeTexture;
        else if (name == GL_VIEWPORT) System.arraycopy(viewport, 0, value, offset, 4);
    }

    public static boolean glIsEnabled(int capability) { return capability == GL_DITHER && dither; }
    public static void glDisable(int capability) { if (capability == GL_DITHER) dither = false; }
    public static void glEnable(int capability) { if (capability == GL_DITHER) dither = true; }
    public static void glActiveTexture(int texture) { activeTexture = texture; }
    public static void glBindFramebuffer(int target, int name) { framebuffer = name; }
    public static void glViewport(int x, int y, int width, int height) {
        viewport[0] = x; viewport[1] = y; viewport[2] = width; viewport[3] = height;
    }
    public static void glDrawArrays(int mode, int first, int count) { draws++; }
    public static void glDeleteFramebuffers(int count, int[] names, int offset) {
        deletedFramebuffers += count;
    }
    public static void glDeleteTextures(int count, int[] names, int offset) {
        deletedTextures += count;
    }
}
