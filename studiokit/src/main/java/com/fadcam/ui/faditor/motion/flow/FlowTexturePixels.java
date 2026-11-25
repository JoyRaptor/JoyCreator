package com.fadcam.ui.faditor.motion.flow;

import com.fadcam.ui.faditor.motion.VelocityPack;

/**
 * Packs an analysed, top-down CPU flow field for an OpenGL RGBA8 texture upload.
 *
 * <p>{@link FlowField} follows image coordinates: row zero is the top and positive {@code v}
 * points down. The staged Studio textures consumed by the footage gather shaders follow OpenGL
 * coordinates: the first uploaded row is the bottom and positive Y points up. This bridge flips
 * the rows and negates V exactly once. X remains unchanged. Alpha carries confidence, rounded to
 * an unsigned byte, so the gather shader can return rejected samples to the sharp centre frame.
 */
public final class FlowTexturePixels {
    private FlowTexturePixels() {}

    /** Returns tightly packed RGBA bytes ready for {@code glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE)}. */
    public static byte[] rgba(FlowField field) {
        if (field == null) throw new IllegalArgumentException("missing flow field");
        byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(field.width, field.height), 4)];
        for (int glY = 0; glY < field.height; glY++) {
            int imageY = field.height - 1 - glY;
            for (int x = 0; x < field.width; x++) {
                int packed = VelocityPack.pack(field.u(x, imageY), -field.v(x, imageY),
                        confidenceByte(field.confidence(x, imageY)));
                int offset = (glY * field.width + x) * 4;
                pixels[offset] = (byte) (packed >>> 24);
                pixels[offset + 1] = (byte) (packed >>> 16);
                pixels[offset + 2] = (byte) (packed >>> 8);
                pixels[offset + 3] = (byte) packed;
            }
        }
        return pixels;
    }

    private static int confidenceByte(float confidence) {
        if (!Float.isFinite(confidence)) return 0;
        return Math.round(Math.max(0f, Math.min(1f, confidence)) * 255f);
    }
}
