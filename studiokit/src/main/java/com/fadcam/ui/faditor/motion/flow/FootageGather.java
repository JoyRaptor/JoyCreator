package com.fadcam.ui.faditor.motion.flow;

/**
 * CPU reference for Fast footage reconstruction from the current decoded frame.
 *
 * <p>Flow must originate at that frame's source PTS. The already-fenced shutter schedule
 * supplies editor-clock taps; the signed speed maps their offsets into the decoded pair.
 * Coarse flow uses the estimator's luma from the same current frame for edge-aware upsampling.
 * At each tap, incompatible or untrusted donors give their weight back to the centre pixel.
 *
 * <p>Input and output are straight-alpha SDR sRGB ARGB. Filtering and exposure accumulation
 * operate on linear-light premultiplied colour and coverage. This is a one-frame gather,
 * so it cannot reconstruct newly revealed surfaces or replace the High neighbouring-frame warp.
 */
public final class FootageGather {
    private static final double GUIDE_RADIUS = 32.0 / 255.0;
    private static final double MIN_CONFIDENCE = 0.25;
    private static final double FULL_CONFIDENCE = 0.75;
    // The shared UI/spec threshold: sub-half-pixel motion must remain byte-exact.
    private static final double MIN_TRAVEL_PX = 0.5;
    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < LINEAR.length; i++) {
            double srgb = i / 255.0;
            LINEAR[i] = srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }
    }

    private FootageGather() {}

    /**
     * Reconstructs an exposure without modifying the input. Pass the editor times and weights
     * from MotionShutter.Schedule. Returns the same array when the result is byte-identical,
     * including missing flow, a freeze or a closed shutter.
     */
    public static int[] reconstruct(int[] sourceArgb, int width, int height, FlowField flow,
                                    LumaImage flowSourceLuma, long centerProjectUs,
                                    long[] times, double[] weights, double sourceSpeed) {
        if (width <= 0 || height <= 0 || (long) width * height != sourceArgb.length
                || !Double.isFinite(sourceSpeed)) {
            throw new IllegalArgumentException("invalid source frame or speed");
        }
        if (flow == null || sourceSpeed == 0) return sourceArgb;
        if (times.length != weights.length || times.length == 0) {
            throw new IllegalArgumentException("invalid shutter schedule");
        }
        double[] offsets = new double[times.length];
        double scalePerUs = flow.displacementScale(1, sourceSpeed);
        double maxAbsoluteOffset = 0, firstOffset = Double.POSITIVE_INFINITY;
        double lastOffset = Double.NEGATIVE_INFINITY, totalWeight = 0;
        for (int tap = 0; tap < times.length; tap++) {
            double weight = weights[tap];
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("invalid shutter weight");
            }
            totalWeight += weight;
            offsets[tap] = ((double) times[tap] - centerProjectUs) * scalePerUs;
            if (weight > 0) {
                maxAbsoluteOffset = Math.max(maxAbsoluteOffset, Math.abs(offsets[tap]));
                firstOffset = Math.min(firstOffset, offsets[tap]);
                lastOffset = Math.max(lastOffset, offsets[tap]);
            }
        }
        double travelScale = Math.max(maxAbsoluteOffset, lastOffset - firstOffset);
        if (!Double.isFinite(totalWeight) || totalWeight <= 0 || !Double.isFinite(travelScale)) {
            throw new IllegalArgumentException("invalid shutter exposure");
        }
        if (travelScale == 0) return sourceArgb;

        FlowField field = fullResolution(sourceArgb, width, height, flow, flowSourceLuma);
        ColorSum sum = new ColorSum();
        int[] result = sourceArgb;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = field.u(x, y), v = field.v(x, y);
                double confidence = trust(field.confidence(x, y));
                double speedSquared = u * u + v * v;
                if (confidence == 0 || !Double.isFinite(speedSquared)
                        || travelScale * Math.sqrt(speedSquared) < MIN_TRAVEL_PX) continue;
                int index = y * width + x;
                int center = sourceArgb[index];
                sum.clear();
                double centerWeight = totalWeight;
                for (int tap = 0; tap < times.length; tap++) {
                    if (weights[tap] == 0 || offsets[tap] == 0) continue;
                    double sx = x - u * offsets[tap], sy = y - v * offsets[tap];
                    if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue;
                    centerWeight -= gather(sum, sourceArgb, field, sx, sy, u, v, center,
                            weights[tap] * confidence);
                }
                if (centerWeight == totalWeight) continue;
                sum.add(center, Math.max(0, centerWeight));
                int pixel = sum.encode(totalWeight, center);
                if (pixel != center) {
                    if (result == sourceArgb) result = sourceArgb.clone();
                    result[index] = pixel;
                }
            }
        }
        return result;
    }

    private static FlowField fullResolution(int[] argb, int width, int height, FlowField field,
                                            LumaImage guide) {
        if (field.width == width && field.height == height) return field;
        if (guide == null || guide.width != field.width || guide.height != field.height) {
            throw new IllegalArgumentException("coarse flow requires its current-frame luma");
        }
        LumaImage source = LumaImage.fromArgb(width, height, argb);
        double resizeX = (double) width / field.width, resizeY = (double) height / field.height;
        float[] u = new float[argb.length], v = new float[argb.length];
        float[] confidence = new float[argb.length];
        for (int y = 0; y < height; y++) {
            double fy = (y + 0.5) / resizeY - 0.5;
            int iy = (int) Math.floor(fy);
            for (int x = 0; x < width; x++) {
                double fx = (x + 0.5) / resizeX - 0.5;
                int ix = (int) Math.floor(fx);
                float luma = source.at(x, y);
                double spatialSupport = 0, trustedSupport = 0, sumU = 0, sumV = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int cy = iy + dy;
                    double spatialY = 1 - Math.abs(cy - fy);
                    for (int dx = 0; dx < 2; dx++) {
                        int cx = ix + dx;
                        double edge = Math.max(0, 1 - Math.abs(guide.at(cx, cy) - luma) / GUIDE_RADIUS);
                        double spatialWeight = spatialY * (1 - Math.abs(cx - fx)) * edge * edge;
                        double coarseConfidence = Math.max(0,
                                Math.min(1, field.confidence(cx, cy)));
                        double weight = spatialWeight * coarseConfidence;
                        double vx = field.u(cx, cy), vy = field.v(cx, cy);
                        if (!(spatialWeight > 0) || !Double.isFinite(vx)
                                || !Double.isFinite(vy)) continue;
                        spatialSupport += spatialWeight;
                        if (!(weight > 0)) continue;
                        sumU += vx * weight;
                        sumV += vy * weight;
                        trustedSupport += weight;
                    }
                }
                if (trustedSupport > 0) {
                    int i = y * width + x;
                    u[i] = (float) (resizeX * sumU / trustedSupport);
                    v[i] = (float) (resizeY * sumV / trustedSupport);
                    // Edge rejection chooses a compatible surface; it is not evidence that the
                    // selected surface is less trustworthy. Only estimator confidence is.
                    confidence[i] = (float) Math.min(1, trustedSupport / spatialSupport);
                }
            }
        }
        return new FlowField(width, height, u, v, confidence, field.fromPtsUs, field.toPtsUs);
    }

    /** Returns the weight moved off the centre into compatible bilinear donors. */
    private static double gather(ColorSum sum, int[] argb, FlowField field, double sx, double sy,
                                 double u, double v, int center, double tapWeight) {
        int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy);
        double accepted = 0;
        // Absolute tolerance handles quantization; the relative term allows slight velocity gradients.
        double toleranceSquared = 0.75 * 0.75 + (u * u + v * v) / 16.0;
        for (int dy = 0; dy < 2; dy++) {
            int y = iy + dy;
            if (y >= field.height) continue;
            double spatialY = 1 - Math.abs(y - sy);
            for (int dx = 0; dx < 2; dx++) {
                int x = ix + dx;
                if (x >= field.width) continue;
                int color = argb[y * field.width + x];
                if (color == center) continue;
                double du = field.u(x, y) - u, dv = field.v(x, y) - v;
                if (!(du * du + dv * dv <= toleranceSquared)) continue;
                double weight = tapWeight * spatialY * (1 - Math.abs(x - sx))
                        * trust(field.confidence(x, y));
                if (weight <= 0) continue;
                sum.add(color, weight);
                accepted += weight;
            }
        }
        return accepted;
    }

    private static double trust(float confidence) {
        if (!Float.isFinite(confidence)) return 0;
        return Math.max(0, Math.min(1, (confidence - MIN_CONFIDENCE)
                / (FULL_CONFIDENCE - MIN_CONFIDENCE)));
    }

    private static final class ColorSum {
        private double red, green, blue, alpha;

        void clear() { red = green = blue = alpha = 0; }

        void add(int argb, double weight) {
            double coverage = weight * ((argb >>> 24) / 255.0);
            alpha += coverage;
            red += coverage * LINEAR[(argb >>> 16) & 255];
            green += coverage * LINEAR[(argb >>> 8) & 255];
            blue += coverage * LINEAR[argb & 255];
        }

        int encode(double totalWeight, int center) {
            int a = quantize(alpha / totalWeight);
            if (a == 0) return center >>> 24 == 0 ? center : 0;
            return a << 24 | srgb(red / alpha) << 16 | srgb(green / alpha) << 8
                    | srgb(blue / alpha);
        }

        private static int srgb(double linear) {
            return quantize(linear <= 0.0031308 ? 12.92 * linear
                    : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055);
        }

        private static int quantize(double normalized) {
            return (int) Math.round(255 * Math.max(0, Math.min(1, normalized)));
        }
    }
}
