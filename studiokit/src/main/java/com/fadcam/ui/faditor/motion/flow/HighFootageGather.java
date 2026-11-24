package com.fadcam.ui.faditor.motion.flow;

/**
 * CPU reference for High footage exposure using decoded frames on both sides of the centre.
 *
 * <p>Each shutter tap is mapped from the project clock into real source PTS. A tap before the
 * centre is reconstructed from the previous/current bidirectional pair; a tap after the centre
 * uses the current/next pair. Both decoded donors are warped toward the tap. Forward/backward
 * consistency and per-pixel confidence reject disoccluded or mismatched donors, whose weight is
 * returned to the sharp centre frame. Colour and coverage are accumulated together in linear
 * premultiplied light.
 *
 * <p>This deterministic oracle accepts read-only, straight-alpha SDR sRGB ARGB frames. A missing
 * neighbour, a tap outside the two supplied pairs, a long-gap pair, a freeze, or less than half
 * a pixel of travel remains exactly sharp. The caller supplies pairs and shutter taps from within
 * one unbroken source segment and uses the rate for that segment.
 */
public final class HighFootageGather {
    private static final FlowConfidence.Params CONSISTENCY = FlowConfidence.Params.DEFAULT;

    private HighFootageGather() {}

    /** One decoded upright source frame. Coarse luma must match the originating flow grid. */
    public record SourceFrame(int[] argb, int width, int height, long ptsUs, LumaImage coarseLuma) {
        public SourceFrame {
            if (argb == null || width <= 0 || height <= 0
                    || (long) width * height != argb.length) {
                throw new IllegalArgumentException("invalid decoded source frame");
            }
        }
    }

    /**
     * Reconstructs one centred exposure. The returned reference is {@code current.argb()} when
     * every output pixel is byte-identical.
     */
    public static int[] reconstruct(SourceFrame previous, SourceFrame current, SourceFrame next,
                                    FlowCache.Pair previousToCurrent,
                                    FlowCache.Pair currentToNext,
                                    long centerProjectUs, long[] projectTimesUs,
                                    double[] weights, double sourceSpeed) {
        if (current == null || !Double.isFinite(sourceSpeed)) {
            throw new IllegalArgumentException("missing centre frame or invalid speed");
        }
        double totalWeight = validateSchedule(projectTimesUs, weights);
        if (sourceSpeed == 0) return current.argb();

        PairWindow before = window(previous, current, previousToCurrent, current.ptsUs());
        PairWindow after = window(current, next, currentToNext, current.ptsUs());
        if (before == null && after == null) return current.argb();

        double[] fractions = new double[projectTimesUs.length];
        PairWindow[] pairs = new PairWindow[projectTimesUs.length];
        double beforeExtent = 0;
        double afterExtent = 0;
        for (int i = 0; i < fractions.length; i++) {
            if (weights[i] == 0) continue;
            double offset = ((double) projectTimesUs[i] - centerProjectUs) * sourceSpeed;
            if (offset < 0 && before != null && before.contains(offset)) {
                fractions[i] = before.fraction(offset);
                pairs[i] = before;
                beforeExtent = Math.max(beforeExtent, 1 - fractions[i]);
            } else if (offset > 0 && after != null && after.contains(offset)) {
                fractions[i] = after.fraction(offset);
                pairs[i] = after;
                afterExtent = Math.max(afterExtent, fractions[i]);
            }
        }
        if (beforeExtent == 0 && afterExtent == 0) return current.argb();

        int width = current.width(), height = current.height();
        int[] result = current.argb();
        FootageGather.ColorSum exposure = new FootageGather.ColorSum();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!moves(x, y, before, beforeExtent, after, afterExtent)) continue;
                int index = y * width + x;
                int centre = current.argb()[index];
                exposure.clear();
                double centreWeight = totalWeight;
                for (int i = 0; i < pairs.length; i++) {
                    if (pairs[i] == null) continue;
                    centreWeight -= pairs[i].addTap(x, y, fractions[i], weights[i], exposure);
                }
                if (centreWeight == totalWeight) continue;
                exposure.add(centre, Math.max(0, centreWeight));
                int pixel = exposure.encode(totalWeight, centre);
                if (pixel != centre) {
                    if (result == current.argb()) result = current.argb().clone();
                    result[index] = pixel;
                }
            }
        }
        return result;
    }

    private static double validateSchedule(long[] times, double[] weights) {
        if (times == null || weights == null || times.length == 0 || times.length != weights.length) {
            throw new IllegalArgumentException("invalid shutter schedule");
        }
        double total = 0;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("invalid shutter weight");
            }
            total += weight;
        }
        if (!Double.isFinite(total) || total <= 0) {
            throw new IllegalArgumentException("invalid total shutter weight");
        }
        return total;
    }

    private static PairWindow window(SourceFrame from, SourceFrame to, FlowCache.Pair pair,
                                     long centerPts) {
        if (from == null || to == null || pair == null || pair.has(FlowCache.Flag.LONG_GAP)
                || pair.has(FlowCache.Flag.DUPLICATE)) {
            return null;
        }
        if (from.width() != to.width() || from.height() != to.height()
                || from.ptsUs() >= to.ptsUs()
                || pair.forward.fromPtsUs != from.ptsUs()
                || pair.forward.toPtsUs != to.ptsUs()) {
            throw new IllegalArgumentException("decoded frames do not match flow pair");
        }
        FlowField forward = FootageGather.fullResolution(from.argb(), from.width(), from.height(),
                pair.forward, from.coarseLuma());
        FlowField backward = FootageGather.fullResolution(to.argb(), to.width(), to.height(),
                pair.backward, to.coarseLuma());
        return new PairWindow(from, to, forward, backward,
                (double) from.ptsUs() - centerPts, (double) to.ptsUs() - centerPts);
    }

    private static boolean moves(int x, int y, PairWindow before, double beforeExtent,
                                 PairWindow after, double afterExtent) {
        double bx = beforeExtent == 0 ? 0 : beforeExtent * before.backward.u(x, y);
        double by = beforeExtent == 0 ? 0 : beforeExtent * before.backward.v(x, y);
        double ax = afterExtent == 0 ? 0 : afterExtent * after.forward.u(x, y);
        double ay = afterExtent == 0 ? 0 : afterExtent * after.forward.v(x, y);
        if (!Double.isFinite(bx) || !Double.isFinite(by)) bx = by = 0;
        if (!Double.isFinite(ax) || !Double.isFinite(ay)) ax = ay = 0;
        double minimum = FootageGather.MIN_TRAVEL_PX;
        return Math.hypot(bx, by) >= minimum || Math.hypot(ax, ay) >= minimum
                || Math.hypot(ax - bx, ay - by) >= minimum;
    }

    private static boolean consistent(FlowField field, FlowField inverse, double x, double y) {
        double u = field.sampleU(x, y), v = field.sampleV(x, y);
        if (!Double.isFinite(u) || !Double.isFinite(v)) return false;
        double tx = x + u, ty = y + v;
        if (tx < 0 || ty < 0 || tx > field.width - 1 || ty > field.height - 1) return false;
        double bu = inverse.sampleU(tx, ty), bv = inverse.sampleV(tx, ty);
        if (!Double.isFinite(bu) || !Double.isFinite(bv)) return false;
        double du = u + bu, dv = v + bv;
        double limit = CONSISTENCY.consistencyRel() * (u * u + v * v + bu * bu + bv * bv)
                + CONSISTENCY.consistencyAbsPx2();
        return du * du + dv * dv <= limit;
    }

    private static final class PairWindow {
        final SourceFrame from;
        final SourceFrame to;
        final FlowField forward;
        final FlowField backward;
        final double fromOffset;
        final double toOffset;

        PairWindow(SourceFrame from, SourceFrame to, FlowField forward, FlowField backward,
                   double fromOffset, double toOffset) {
            this.from = from;
            this.to = to;
            this.forward = forward;
            this.backward = backward;
            this.fromOffset = fromOffset;
            this.toOffset = toOffset;
        }

        boolean contains(double offset) { return offset >= fromOffset && offset <= toOffset; }

        double fraction(double offset) { return (offset - fromOffset) / (toOffset - fromOffset); }

        double addTap(int x, int y, double alpha, double tapWeight, FootageGather.ColorSum out) {
            return addWarpedDonor(from, forward, backward, x, y, alpha, (1 - alpha) * tapWeight, out)
                    + addWarpedDonor(to, backward, forward, x, y, 1 - alpha, alpha * tapWeight, out);
        }

        private static double addWarpedDonor(SourceFrame donor, FlowField field, FlowField inverse,
                                             int x, int y, double fraction, double weight,
                                             FootageGather.ColorSum out) {
            if (weight <= 0) return 0;
            double sx = x, sy = y;
            if (fraction != 0) {
                // Invert p = q + fraction * flow(q), sampling both components at the same q.
                for (int i = 0; i < 3; i++) {
                    double u = field.sampleU(sx, sy), v = field.sampleV(sx, sy);
                    if (!Double.isFinite(u) || !Double.isFinite(v)) return 0;
                    sx = x - fraction * u;
                    sy = y - fraction * v;
                }
                double errorX = sx + fraction * field.sampleU(sx, sy) - x;
                double errorY = sy + fraction * field.sampleV(sx, sy) - y;
                if (!(Math.hypot(errorX, errorY) <= FootageGather.MIN_TRAVEL_PX)) return 0;
            }
            if (sx < 0 || sy < 0 || sx > donor.width() - 1 || sy > donor.height() - 1
                    || !consistent(field, inverse, sx, sy)) return 0;
            return addSample(donor, field, inverse, sx, sy, weight, out);
        }

        private static double addSample(SourceFrame donor, FlowField field, FlowField inverse,
                                        double x, double y, double weight, FootageGather.ColorSum out) {
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
            double u = field.sampleU(x, y), v = field.sampleV(x, y);
            // Match the Fast oracle's surface compatibility, but test each warped donor texel.
            double toleranceSquared = 0.75 * 0.75 + (u * u + v * v) / 16.0;
            double accepted = 0;
            for (int dy = 0; dy < 2; dy++) {
                int sy = y0 + dy;
                if (sy >= donor.height()) continue;
                double wy = 1 - Math.abs(sy - y);
                for (int dx = 0; dx < 2; dx++) {
                    int sx = x0 + dx;
                    if (sx >= donor.width()) continue;
                    double contribution = weight * wy * (1 - Math.abs(sx - x))
                            * FootageGather.trust(field.confidence(sx, sy));
                    if (contribution <= 0) continue;
                    double du = field.u(sx, sy) - u, dv = field.v(sx, sy) - v;
                    if (!(du * du + dv * dv <= toleranceSquared)
                            || !consistent(field, inverse, sx, sy)) continue;
                    out.add(donor.argb()[sy * donor.width() + sx], contribution);
                    accepted += contribution;
                }
            }
            return accepted;
        }
    }
}
