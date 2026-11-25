package com.fadcam.ui.faditor.motion.flow;

import java.util.EnumSet;

/** Conservative, advisory scene hints. Duplicate pixels and long gaps are safe zero-motion cases. */
final class FlowPairAssessment {
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
        int count = 0, valid = 0, fast = 0;
        double residual = 0, residualSquared = 0, directionX = 0, directionY = 0;
        for (int y = 0; y < from.height; y += stride) {
            for (int x = 0; x < from.width; x += stride) {
                count++;
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
