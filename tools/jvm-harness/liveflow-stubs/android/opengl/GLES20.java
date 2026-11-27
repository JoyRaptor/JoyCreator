package android.opengl;

import java.nio.Buffer;
import java.util.HashMap;
import java.util.Map;

public final class GLES20 {
    public static final int GL_FRAMEBUFFER = 0x8D40;
    public static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    public static final int GL_VIEWPORT = 0x0BA2;
    public static final int GL_ACTIVE_TEXTURE = 0x84E0;
    public static final int GL_DITHER = 0x0BD0;
    public static final int GL_TEXTURE0 = 0x84C0;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_TEXTURE_BINDING_2D = 0x8069;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_TRIANGLE_STRIP = 0x0005;

    public static int framebuffer = 41;
    public static final int[] viewport = {7, 8, 901, 503};
    public static int activeTexture = GL_TEXTURE0 + 5;
    public static int boundTexture = 73;
    public static boolean dither = true;
    public static int draws;
    public static int deletedFramebuffers;
    public static int deletedTextures;
    public static int generatedTextures;
    public static int textureUploads;
    private static final Map<Integer, Integer> boundTextures = new HashMap<>();
    public static final Map<Integer, Integer> minFilters = new HashMap<>();
    public static final Map<Integer, Integer> magFilters = new HashMap<>();

    private GLES20() {}

    public static void glGetIntegerv(int name, int[] value, int offset) {
        if (name == GL_FRAMEBUFFER_BINDING) value[offset] = framebuffer;
        else if (name == GL_ACTIVE_TEXTURE) value[offset] = activeTexture;
        else if (name == GL_TEXTURE_BINDING_2D) value[offset] = boundTexture;
        else if (name == GL_VIEWPORT) System.arraycopy(viewport, 0, value, offset, 4);
    }

    public static boolean glIsEnabled(int capability) { return capability == GL_DITHER && dither; }
    public static void glDisable(int capability) { if (capability == GL_DITHER) dither = false; }
    public static void glEnable(int capability) { if (capability == GL_DITHER) dither = true; }
    public static void glActiveTexture(int texture) { activeTexture = texture; }
    public static void glBindTexture(int target, int texture) {
        boundTexture = texture;
        boundTextures.put(activeTexture, texture);
    }
    public static void glGenTextures(int count, int[] names, int offset) {
        for (int i = 0; i < count; i++) names[offset + i] = 100 + generatedTextures++;
    }
    public static void glTexParameteri(int target, int name, int value) {
        int texture = boundTextures.getOrDefault(activeTexture, boundTexture);
        if (name == GL_TEXTURE_MIN_FILTER) minFilters.put(texture, value);
        if (name == GL_TEXTURE_MAG_FILTER) magFilters.put(texture, value);
    }
    public static void glTexImage2D(int target, int level, int internal, int width, int height,
                                    int border, int format, int type, Buffer pixels) {
        if (boundTexture <= 0 || width <= 0 || height <= 0 || pixels == null) {
            throw new IllegalArgumentException("invalid fake texture upload");
        }
        textureUploads++;
    }
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
