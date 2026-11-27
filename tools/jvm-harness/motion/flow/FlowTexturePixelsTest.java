package com.fadcam.ui.faditor.motion.flow;

import com.fadcam.ui.faditor.motion.VelocityPack;

/** STU-7 C6: CPU image coordinates become the exact packed GL flow-texture convention. */
public final class FlowTexturePixelsTest {
    private int assertions;

    public static void main(String[] args) {
        FlowTexturePixelsTest test = new FlowTexturePixelsTest();
        byte[] pixels = FlowTexturePixels.rgba(test.asymmetricField());
        boolean noFlip = args.length > 0 && args[0].equals("--no-row-flip");
        boolean noInvert = args.length > 0 && args[0].equals("--no-v-invert");
        test.checkBottomRow(pixels, noFlip, noInvert);
        test.checkTopRow(pixels);
        test.checkConfidenceSafety();
        test.checkColdWarmRounding(args.length > 0 && args[0].equals("--round-after-flip"));
        System.out.println("FlowTexturePixelsTest: " + test.assertions + " assertions");
    }

    private FlowField asymmetricField() {
        // Top row, then bottom row. Both axes and confidence vary so no orientation bug can hide.
        return new FlowField(2, 2,
                new float[]{1.25f, 2.0f, -3.5f, 7.0f},
                new float[]{2.5f, -1.0f, -4.75f, 3.0f},
                new float[]{0.25f, 0.5f, 0.75f, 1.0f}, 100, 200);
    }

    private void checkBottomRow(byte[] pixels, boolean noFlip, boolean noInvert) {
        int first = packed(pixels, 0);
        double expectedX = noFlip ? 1.25 : -3.5;
        double expectedY = noFlip ? -2.5 : noInvert ? -4.75 : 4.75;
        check(VelocityPack.xPixels(first) == expectedX,
                "OpenGL row zero comes from the image bottom");
        check(VelocityPack.yPixels(first) == expectedY,
                "downward image motion becomes upward OpenGL motion");
        check(VelocityPack.order(first) == 191, "confidence is rounded into alpha");
    }

    private void checkTopRow(byte[] pixels) {
        int upperLeft = packed(pixels, 2);
        check(VelocityPack.xPixels(upperLeft) == 1.25, "image top becomes OpenGL top");
        check(VelocityPack.yPixels(upperLeft) == -2.5, "top-row V is inverted once");
        check(VelocityPack.order(upperLeft) == 64, "top-row confidence stays with its vector");
        check(pixels.length == 16, "texture is tightly packed RGBA8");
    }

    private void checkConfidenceSafety() {
        FlowField unsafe = new FlowField(3, 1, new float[3], new float[3],
                new float[]{-2f, Float.NaN, 2f}, 0, 1);
        byte[] pixels = FlowTexturePixels.rgba(unsafe);
        check(VelocityPack.order(packed(pixels, 0)) == 0, "negative confidence clamps to zero");
        check(VelocityPack.order(packed(pixels, 1)) == 0, "non-finite confidence rejects the sample");
        check(VelocityPack.order(packed(pixels, 2)) == 255, "high confidence clamps to one");
    }

    private void checkColdWarmRounding(boolean roundAfterFlip) {
        var cold = new FlowField(1, 1, new float[]{0}, new float[]{1f / 32f}, null, 1, 2);
        var warm = new FlowField(1, 1, new float[]{0}, new float[]{1f / 16f}, null, 1, 2);
        int coldPacked = roundAfterFlip ? VelocityPack.pack(0, -cold.v(0, 0), 255)
                : packed(FlowTexturePixels.rgba(cold), 0);
        check(coldPacked == packed(FlowTexturePixels.rgba(warm), 0),
                "cold and warm textures round half steps in source coordinates");
    }

    private static int packed(byte[] pixels, int pixel) {
        int o = pixel * 4;
        return (pixels[o] & 255) << 24 | (pixels[o + 1] & 255) << 16
                | (pixels[o + 2] & 255) << 8 | (pixels[o + 3] & 255);
    }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
