package com.fadcam.ui.faditor.motion.flow;

import java.nio.ByteBuffer;

/** Converts a cropped flexible-YUV Y plane directly into the flow grid without RGB round trips. */
public final class YPlaneLuma {
    private YPlaneLuma() {}

    public static LumaImage boxDownsample(ByteBuffer plane, int planeOrigin,
                                           int rowStride, int pixelStride,
                                           int sourceWidth, int sourceHeight,
                                           int outputWidth, int outputHeight) {
        if (plane == null || planeOrigin < 0 || rowStride <= 0 || pixelStride <= 0
                || sourceWidth <= 0 || sourceHeight <= 0
                || outputWidth <= 0 || outputHeight <= 0
                || outputWidth > sourceWidth || outputHeight > sourceHeight) {
            throw new IllegalArgumentException("invalid Y-plane luma geometry");
        }
        long last = (long) planeOrigin + (long) (sourceHeight - 1) * rowStride
                + (long) (sourceWidth - 1) * pixelStride;
        if (last >= plane.limit()) throw new IllegalArgumentException("Y plane is shorter than its geometry");

        float[] out = new float[outputWidth * outputHeight];
        for (int oy = 0; oy < outputHeight; oy++) {
            int top = oy * sourceHeight / outputHeight;
            int bottom = Math.max(top + 1, (oy + 1) * sourceHeight / outputHeight);
            for (int ox = 0; ox < outputWidth; ox++) {
                int left = ox * sourceWidth / outputWidth;
                int right = Math.max(left + 1, (ox + 1) * sourceWidth / outputWidth);
                long total = 0;
                int samples = 0;
                for (int y = top; y < bottom; y++) {
                    int row = planeOrigin + y * rowStride;
                    for (int x = left; x < right; x++) {
                        total += plane.get(row + x * pixelStride) & 0xff;
                        samples++;
                    }
                }
                out[oy * outputWidth + ox] = total / (255f * samples);
            }
        }
        return new LumaImage(outputWidth, outputHeight, out);
    }
}
