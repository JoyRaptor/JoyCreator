package com.fadcam.ui.faditor.motion.flow;

import java.util.EnumSet;

/** Detects decoded shot boundaries and records other scene hints for the flow pair. */
final class FlowPairAssessment {
    private static final double CUT_LUMA_JUMP = 0.16;
    private static final double CUT_MAX_TRUSTED_FRACTION = 0.15;
    private static final double TRUST_FLOOR = 0.25;
    private static final double EXPOSURE_NOISE_VARIANCE = 0.05 * 0.05;
    private static final double FLASH_MIN_DIRECTIONAL_CHANGE = 0.90;
    private static final double FLASH_MIN_LUMA_CORRELATION = 0.80;

    private FlowPairAssessment() {}

    static boolean longGap(FlowCache.Key key) {
        long gap = Math.max(key.fromPtsUs, key.toPtsUs) - Math.min(key.fromPtsUs, key.toPtsUs);
        return gap < 0 || gap > key.maxGapUs;
    }

    static boolean identical(LumaImage a, LumaImage b) {
        for (int y = 0; y < a.height; y++) {
            for (int x = 0; x < a.width; x++) {
                if (a.at(x, y) != b.at(x, y)) return false;
            }
        }
        return true;
    }

    static EnumSet<FlowCache.Flag> assess(LumaImage from, LumaImage to, FlowField forward,
                                         FlowField backward, FlowConfidence.Params confidence) {
        EnumSet<FlowCache.Flag> flags = EnumSet.noneOf(FlowCache.Flag.class);
        int stride = Math.max(1, Math.min(from.width, from.height) / 48);
        double whipDistance = Math.hypot(from.width, from.height) * 0.08;
        int count = 0, valid = 0, fast = 0, trusted = 0;
        double residual = 0, residualSquared = 0, directionX = 0, directionY = 0;
        double jump = 0, signedJump = 0, jumpSquared = 0, lumaSum = 0, lumaSquared = 0;
        double nextLumaSquared = 0, lumaProduct = 0;
        for (int y = 0; y < from.height; y += stride) {
            for (int x = 0; x < from.width; x += stride) {
                count++;
                double luma = from.at(x, y);
                double nextLuma = to.at(x, y);
                double difference = nextLuma - luma;
                jump += Math.abs(difference);
                signedJump += difference;
                jumpSquared += difference * difference;
                lumaSum += luma;
                lumaSquared += luma * luma;
                nextLumaSquared += nextLuma * nextLuma;
                lumaProduct += luma * nextLuma;
                if (forward.confidence(x, y) >= TRUST_FLOOR) trusted++;
                if (backward.confidence(x, y) >= TRUST_FLOOR) trusted++;
                if (FlowConfidence.occluded(forward, backward, x, y, confidence)) continue;
                valid++;
                double u = forward.u(x, y), v = forward.v(x, y);
                double delta = to.sample(x + u, y + v) - from.at(x, y);
                residual += delta;
                residualSquared += delta * delta;
                double distance = Math.hypot(u, v);
                if (distance >= whipDistance && forward.confidence(x, y) >= 0.3) {
                    fast++;
                    directionX += u / distance;
                    directionY += v / distance;
                }
            }
        }
        double meanShift = signedJump / count;
        double shiftVariance = jumpSquared / count - meanShift * meanShift;
        double meanLuma = lumaSum / count;
        double lumaVariance = lumaSquared / count - meanLuma * meanLuma;
        double nextMean = meanLuma + meanShift;
        double nextVariance = nextLumaSquared / count - nextMean * nextMean;
        double covariance = lumaProduct / count - meanLuma * nextMean;
        // A flash can change exposure nonuniformly as highlights clip. Require preserved
        // texture and a consistent brightness direction before exempting a low-trust pair.
        boolean keepsTexture = lumaVariance >= EXPOSURE_NOISE_VARIANCE
                && nextVariance >= EXPOSURE_NOISE_VARIANCE
                && covariance >= FLASH_MIN_LUMA_CORRELATION * Math.sqrt(lumaVariance * nextVariance);
        boolean exposureChange = Math.abs(meanShift) >= 0.10
                && Math.abs(signedJump) >= FLASH_MIN_DIRECTIONAL_CHANGE * jump
                && (shiftVariance <= EXPOSURE_NOISE_VARIANCE && lumaVariance >= EXPOSURE_NOISE_VARIANCE
                    || keepsTexture);
        if (exposureChange) {
            flags.add(FlowCache.Flag.FLASH);
        } else if (jump >= count * CUT_LUMA_JUMP
                && trusted <= 2 * count * CUT_MAX_TRUSTED_FRACTION) {
            flags.add(FlowCache.Flag.SCENE_CUT);
        }
        if (count - valid >= count * 0.1) flags.add(FlowCache.Flag.OCCLUSION);
        if (valid >= count * 0.5) {
            double mean = residual / valid;
            double variance = residualSquared / valid - mean * mean;
            if (Math.abs(mean) >= 0.10 && variance <= 0.04 * 0.04) {
                flags.add(FlowCache.Flag.FLASH);
            }
        }
        if (fast >= count * 0.5 && Math.hypot(directionX, directionY) >= fast * 0.85) {
            flags.add(FlowCache.Flag.POSSIBLE_WHIP_PAN);
        }
        return flags;
    }
}
