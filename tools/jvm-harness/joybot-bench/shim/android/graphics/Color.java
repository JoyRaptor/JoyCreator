package android.graphics;

/** Runtime shim of Color's static helpers (the tools never use a Color instance). */
public class Color {
    public static final int BLACK = 0xFF000000, DKGRAY = 0xFF444444, GRAY = 0xFF888888, LTGRAY = 0xFFCCCCCC,
            WHITE = 0xFFFFFFFF, RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, YELLOW = 0xFFFFFF00,
            CYAN = 0xFF00FFFF, MAGENTA = 0xFFFF00FF, TRANSPARENT = 0;

    public static int alpha(int c) { return c >>> 24; }
    public static int red(int c) { return (c >> 16) & 0xFF; }
    public static int green(int c) { return (c >> 8) & 0xFF; }
    public static int blue(int c) { return c & 0xFF; }
    public static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }
    public static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }
    public static int rgb(float r, float g, float b) { return rgb((int) (r * 255 + .5f), (int) (g * 255 + .5f), (int) (b * 255 + .5f)); }

    public static int argb(float a, float r, float g, float b) {
        return argb((int) (a * 255 + .5f), (int) (r * 255 + .5f), (int) (g * 255 + .5f), (int) (b * 255 + .5f));
    }

    public static int parseColor(String s) {
        if (s.charAt(0) == '#') {
            long color = Long.parseLong(s.substring(1), 16);
            if (s.length() == 7) color |= 0x00000000ff000000L;
            else if (s.length() != 9) throw new IllegalArgumentException("Unknown color");
            return (int) color;
        }
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "black": return BLACK;
            case "darkgray": case "darkgrey": return DKGRAY;
            case "gray": case "grey": return GRAY;
            case "lightgray": case "lightgrey": return LTGRAY;
            case "white": return WHITE;
            case "red": return RED;
            case "green": case "lime": return GREEN;
            case "blue": return BLUE;
            case "yellow": return YELLOW;
            case "cyan": case "aqua": return CYAN;
            case "magenta": case "fuchsia": return MAGENTA;
            case "maroon": return 0xFF800000;
            case "navy": return 0xFF000080;
            case "olive": return 0xFF808000;
            case "purple": return 0xFF800080;
            case "silver": return 0xFFC0C0C0;
            case "teal": return 0xFF008080;
            default: throw new IllegalArgumentException("Unknown color");
        }
    }

    public static void RGBToHSV(int r, int g, int b, float[] hsv) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == rf) h = 60 * (((gf - bf) / d) % 6);
        else if (max == gf) h = 60 * (((bf - rf) / d) + 2);
        else h = 60 * (((rf - gf) / d) + 4);
        if (h < 0) h += 360;
        hsv[0] = h;
        hsv[1] = max == 0 ? 0 : d / max;
        hsv[2] = max;
    }

    public static void colorToHSV(int color, float[] hsv) { RGBToHSV(red(color), green(color), blue(color), hsv); }

    public static int HSVToColor(float[] hsv) { return HSVToColor(0xFF, hsv); }

    public static int HSVToColor(int alpha, float[] hsv) {
        float h = ((hsv[0] % 360) + 360) % 360, s = hsv[1], v = hsv[2];
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return argb(alpha, Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    public static float luminance(int c) {
        double r = lin(red(c) / 255.0), g = lin(green(c) / 255.0), b = lin(blue(c) / 255.0);
        return (float) (0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    private static double lin(double v) { return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
}
