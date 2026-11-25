package androidx.media3.common.util;

public final class GlUtil {
    public static final class GlException extends Exception {
        public GlException(String message) { super(message); }
    }

    private static int nextName = 100;

    private GlUtil() {}
    public static int createTexture(int width, int height, boolean highPrecision) { return nextName++; }
    public static int createFboForTexture(int texture) { return nextName++; }
    public static void checkGlError() throws GlException {}
}
