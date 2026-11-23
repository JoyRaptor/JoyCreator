package com.fadcam.ui.faditor.motion;

/** Signed 12-bit X/Y at 1/16 pixel, plus an unsigned 8-bit order in RGBA byte order. */
public final class VelocityPack {
    private static final int MASK = 0xfff;
    private static final int MIN = -2048;
    private static final int MAX = 2047;
    public static final double MIN_PIXELS = MIN / 16.0;
    public static final double MAX_PIXELS = MAX / 16.0;

    private VelocityPack() {}

    /** High byte is R; low byte is A (order). Interpolate decoded velocities, not packed bytes. */
    public static int pack(double xPixels, double yPixels, int order) {
        int x = quantize(xPixels) & MASK;
        int y = quantize(yPixels) & MASK;
        return (x << 20) | (y << 8) | Math.max(0, Math.min(255, order));
    }

    public static double xPixels(int rgba) { return signed(rgba >>> 20) / 16.0; }

    public static double yPixels(int rgba) { return signed((rgba >>> 8) & MASK) / 16.0; }

    public static int order(int rgba) { return rgba & 0xff; }

    private static int signed(int value) { return (value ^ 0x800) - 0x800; }

    private static int quantize(double pixels) {
        return (int) Math.round(Math.max(MIN, Math.min(MAX, pixels * 16.0)));
    }
}
