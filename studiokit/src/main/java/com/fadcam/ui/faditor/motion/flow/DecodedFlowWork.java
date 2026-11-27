package com.fadcam.ui.faditor.motion.flow;

import java.util.EnumSet;

/** One lazy decoded PTS pair for {@link FlowAnalyzer}; queued work retains no frame pixels. */
public final class DecodedFlowWork implements FlowAnalyzer.Work {
    public interface Decoder {
        LumaImage decode(long ptsUs, int width, int height) throws Exception;
    }

    public interface Estimator {
        FlowField estimate(LumaImage from, LumaImage to, long fromPtsUs, long toPtsUs);
    }

    private final FlowCache.Key key;
    private final Decoder decoder;
    private final Estimator estimator;
    private final FlowConfidence.Params confidence;

    public DecodedFlowWork(FlowCache.Key key, Decoder decoder) {
        this(key, decoder, new PyramidFlowEstimator()::estimate, FlowConfidence.Params.DEFAULT);
    }

    public DecodedFlowWork(FlowCache.Key key, Decoder decoder, Estimator estimator,
                           FlowConfidence.Params confidence) {
        if (key == null || decoder == null || estimator == null || confidence == null) {
            throw new IllegalArgumentException("missing decoded flow dependency");
        }
        this.key = key;
        this.decoder = decoder;
        this.estimator = estimator;
        this.confidence = confidence;
    }

    @Override public FlowCache.Key key() { return key; }

    @Override public FlowCache.Pair analyze(FlowAnalyzer.Cancellation cancellation) throws Exception {
        if (cancellation.isCancelled()) return null;
        if (FlowPairAssessment.longGap(key)) return zeroPair(FlowCache.Flag.LONG_GAP);
        LumaImage from = decoder.decode(key.fromPtsUs, key.width, key.height);
        if (cancellation.isCancelled()) return null;
        checkShape(from);
        LumaImage to = decoder.decode(key.toPtsUs, key.width, key.height);
        if (cancellation.isCancelled()) return null;
        checkShape(to);
        if (FlowPairAssessment.identical(from, to)) return zeroPair(FlowCache.Flag.DUPLICATE);
        FlowField forward = estimator.estimate(from, to, key.fromPtsUs, key.toPtsUs);
        if (cancellation.isCancelled()) return null;
        FlowField backward = estimator.estimate(to, from, key.toPtsUs, key.fromPtsUs);
        if (cancellation.isCancelled()) return null;
        FlowField trustedForward = FlowConfidence.attach(forward, backward, from, to, confidence);
        if (cancellation.isCancelled()) return null;
        FlowField trustedBackward = FlowConfidence.attach(backward, forward, to, from, confidence);
        if (cancellation.isCancelled()) return null;
        var assessment = FlowPairAssessment.assess(from, to, trustedForward, trustedBackward, confidence);
        if (cancellation.isCancelled()) return null;
        if (assessment.contains(FlowCache.Flag.SCENE_CUT)) return zeroPair(FlowCache.Flag.SCENE_CUT);
        return new FlowCache.Pair(trustedForward, trustedBackward, assessment);
    }

    private FlowCache.Pair zeroPair(FlowCache.Flag flag) {
        FlowField forward = FlowField.zero(key.width, key.height, key.fromPtsUs, key.toPtsUs);
        FlowField backward = FlowField.zero(key.width, key.height, key.toPtsUs, key.fromPtsUs);
        if (flag != FlowCache.Flag.DUPLICATE) {
            float[] untrusted = new float[key.width * key.height];
            forward = forward.withConfidence(untrusted);
            backward = backward.withConfidence(untrusted);
        }
        return new FlowCache.Pair(forward, backward, EnumSet.of(flag));
    }

    private void checkShape(LumaImage image) {
        if (image == null || image.width != key.width || image.height != key.height) {
            throw new IllegalArgumentException("decoder did not return the requested analysis size");
        }
    }
}
