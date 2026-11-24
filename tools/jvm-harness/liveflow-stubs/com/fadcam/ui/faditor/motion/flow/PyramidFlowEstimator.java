package com.fadcam.ui.faditor.motion.flow;

public final class PyramidFlowEstimator {
    public record Settings(int patch) {
        public static final Settings DEFAULT = new Settings(8);
    }

    private PyramidFlowEstimator() {}
}
