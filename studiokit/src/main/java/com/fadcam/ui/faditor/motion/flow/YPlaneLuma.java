package com.fadcam.ui.faditor.motion.flow;

import java.nio.ByteBuffer;

/** Converts a cropped flexible-YUV Y plane directly into the flow grid without RGB round trips. */
public final class YPlaneLuma {
    private YPlaneLuma() {}

    public static LumaImage boxDownsample(ByteBuffer plane, int planeOrigin,
                                           int rowStride, int pixelStride,
                                           int sourceWidth, int sourceHeight,
                                           int outputWidth, int outputHeight) {
        return boxDownsample(plane, planeOrigin, rowStride, pixelStride,
                sourceWidth, sourceHeight, outputWidth, outputHeight, 0);
    }

    /** Output dimensions are in upright coordinates. Rotation is clockwise from the coded crop. */
    public static LumaImage boxDownsample(ByteBuffer plane, int planeOrigin,
                                           int rowStride, int pixelStride,
                                           int sourceWidth, int sourceHeight,
                                           int outputWidth, int outputHeight, int rotationDegrees) {
        int rotation = Math.floorMod(rotationDegrees, 360);
        boolean swapsAxes = rotation == 90 || rotation == 270;
        int uprightWidth = swapsAxes ? sourceHeight : sourceWidth;
        int uprightHeight = swapsAxes ? sourceWidth : sourceHeight;
        if (plane == null || planeOrigin < 0 || rowStride <= 0 || pixelStride <= 0
                || sourceWidth <= 0 || sourceHeight <= 0
                || outputWidth <= 0 || outputHeight <= 0
                || rotation % 90 != 0 || outputWidth > uprightWidth || outputHeight > uprightHeight) {
            throw new IllegalArgumentException("invalid Y-plane luma geometry");
        }
        long last = (long) planeOrigin + (long) (sourceHeight - 1) * rowStride
                + (long) (sourceWidth - 1) * pixelStride;
        if (last >= plane.limit()) throw new IllegalArgumentException("Y plane is shorter than its geometry");

        float[] out = new float[outputWidth * outputHeight];
        for (int oy = 0; oy < outputHeight; oy++) {
            int uprightTop = oy * uprightHeight / outputHeight;
            int uprightBottom = (oy + 1) * uprightHeight / outputHeight;
            for (int ox = 0; ox < outputWidth; ox++) {
                int uprightLeft = ox * uprightWidth / outputWidth;
                int uprightRight = (ox + 1) * uprightWidth / outputWidth;
                int left, right, top, bottom;
                switch (rotation) {
                    case 90:
                        left = uprightTop;
                        right = uprightBottom;
                        top = sourceHeight - uprightRight;
                        bottom = sourceHeight - uprightLeft;
                        break;
                    case 180:
                        left = sourceWidth - uprightRight;
                        right = sourceWidth - uprightLeft;
                        top = sourceHeight - uprightBottom;
                        bottom = sourceHeight - uprightTop;
                        break;
                    case 270:
                        left = sourceWidth - uprightBottom;
                        right = sourceWidth - uprightTop;
                        top = uprightLeft;
                        bottom = uprightRight;
                        break;
                    default:
                        left = uprightLeft;
                        right = uprightRight;
                        top = uprightTop;
                        bottom = uprightBottom;
                        break;
                }
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
