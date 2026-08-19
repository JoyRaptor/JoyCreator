package android.graphics;

/**
 * Inert stub — only the constants TextOverlayItem reads at field-initialisation time.
 * Deliberately minimal: the harness tests time arithmetic, not colour, and a stub that
 * grew methods would start implying coverage it does not have.
 */
public class Color {
    public static final int TRANSPARENT = 0x00000000;
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    public static int alpha(int c) { return (c >>> 24) & 0xFF; }
    public static int red(int c) { return (c >> 16) & 0xFF; }
    public static int green(int c) { return (c >> 8) & 0xFF; }
    public static int blue(int c) { return c & 0xFF; }
}
