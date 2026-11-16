package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A picture's shape as people say it (BOT-7): "3:1", "3 by 1", "3x1", "3.1" (how a voice keyboard
 * writes "three to one"; the owner's own request, 11-15), "1920x640", "square", "portrait",
 * "widescreen", "phone". Pure Java (IdeaEngineTest).
 */
public final class Shape {
    /** The ratio, reduced (16:9, 3:1). */
    public final int w, h;

    private Shape(int w, int h) {
        int g = gcd(w, h);
        this.w = w / g;
        this.h = h / g;
    }

    @NonNull
    public static Shape of(int w, int h) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("shape " + w + ":" + h);
        return new Shape(w, h);
    }

    /** "16:9". */
    @NonNull
    public String ratio() {
        return w + ":" + h;
    }

    /** Wider than tall, taller than wide, or square, in words. */
    @NonNull
    public String words() {
        if (w == h) return "square";
        return ratio() + (w > h ? " (wide)" : " (tall)");
    }

    /**
     * Studio's canvas preset: its own name for the shapes it lists ("16_9"), otherwise a custom
     * size with a 1920-pixel long edge ("custom_1920_640"), even numbers as the encoder needs.
     */
    @NonNull
    public String studioCanvas() {
        String own = w + "_" + h;
        switch (own) {
            case "16_9": case "9_16": case "1_1": case "4_3": case "3_4": case "4_5": case "21_9":
                return own;
            default:
                int[] px = pixels(1920);
                return "custom_" + px[0] + "_" + px[1];
        }
    }

    /** Pixel size with the given long edge, even numbers. */
    @NonNull
    public int[] pixels(int longEdge) {
        int pw, ph;
        if (w >= h) {
            pw = longEdge;
            ph = Math.round((float) longEdge * h / w);
        } else {
            ph = longEdge;
            pw = Math.round((float) longEdge * w / h);
        }
        return new int[]{Math.max(2, (pw / 2) * 2), Math.max(2, (ph / 2) * 2)};
    }

    private static final Pattern PAIR = Pattern.compile(
            "(\\d{1,5}(?:\\.\\d+)?)\\s*(?::|x|×|by|to|/|\\.)\\s*(\\d{1,5}(?:\\.\\d+)?)");

    /** The shape in {@code text}, or null when it names none. */
    @Nullable
    public static Shape parse(@Nullable String text) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.US).replace(',', '.').trim();
        Matcher m = PAIR.matcher(t);
        while (m.find()) {
            String a = m.group(1), b = m.group(2);
            // "3.1" is "3:1" (a voice keyboard's "three to one"), but "2.35:1" is a decimal ratio:
            // the pattern takes "2.35" whole, so a lone dot only ever sits between two numbers.
            try {
                double x = Double.parseDouble(a), y = Double.parseDouble(b);
                if (x <= 0 || y <= 0) continue;
                Shape s = fromDoubles(x, y);
                if (s != null && s.sane()) return s;
            } catch (NumberFormatException ignored) { }
        }
        if (t.matches(".*\\bsquare\\b.*")) return of(1, 1);
        if (t.matches(".*\\b(widescreen|wide screen|youtube|landscape|tv)\\b.*")) return of(16, 9);
        if (t.matches(".*\\b(cinema|cinematic|ultrawide|ultra wide)\\b.*")) return of(21, 9);
        if (t.matches(".*\\b(portrait|vertical|phone|story|stories|tiktok|reel|reels|shorts|tall)\\b.*")) return of(9, 16);
        if (t.matches(".*\\b(banner|panorama|panoramic)\\b.*")) return of(3, 1);
        return null;
    }

    @Nullable
    private static Shape fromDoubles(double x, double y) {
        // 2.35:1 → 47:20; keep to two decimals.
        int sx = (int) Math.round(x * 100), sy = (int) Math.round(y * 100);
        if (sx <= 0 || sy <= 0) return null;
        return of(sx, sy);
    }

    /** Ratios no one means (1000:1) are not shapes; sizes like 1920x640 are fine. */
    private boolean sane() {
        double r = (double) w / h;
        return r >= 0.1 && r <= 10;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return Math.max(1, a);
    }

    @Override public boolean equals(Object o) {
        return o instanceof Shape && ((Shape) o).w == w && ((Shape) o).h == h;
    }

    @Override public int hashCode() { return w * 31 + h; }

    @Override public String toString() { return ratio(); }
}
