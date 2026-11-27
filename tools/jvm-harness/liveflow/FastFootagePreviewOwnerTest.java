package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

public final class FastFootagePreviewOwnerTest {
    private static final LiveFlowGl.SourceGeometry GEOMETRY =
            new LiveFlowGl.SourceGeometry("clip-a", 1920, 1080, 0, 0f, 0f, 1f, 1f);
    private static int checks;

    public static void main(String[] args) throws Exception {
        hiddenAndDisabledPathsDoNotOpenGl();
        enabledPathPublishesOnlyContinuousFlow();
        verifiedCutFlushesOldFrames();
        System.out.println("FastFootagePreviewOwnerTest: " + checks + " checks passed");
    }

    private static void verifiedCutFlushesOldFrames() throws Exception {
        var owner = new FastFootagePreviewOwner(new AssetManager(), 50_000);
        check(owner.observeVerifiedPair(1, 11, 40_000, 7, GEOMETRY, Long.MIN_VALUE) == null,
                "first indexed frame starts sharp");
        check(owner.observeVerifiedPair(1, 12, 80_000, 7, GEOMETRY, 40_000) != null,
                "verified neighbour gets flow");
        check(owner.observeVerifiedPair(1, 13, 120_000, 7, GEOMETRY, Long.MIN_VALUE) == null,
                "cut invalidates the ring at the same decoder epoch");
        check(owner.observeVerifiedPair(1, 13, 120_000, 7, GEOMETRY, Long.MIN_VALUE) == null,
                "redrawing the cut cannot recover an old estimate");
        var after = owner.observeVerifiedPair(1, 14, 160_000, 7, GEOMETRY, 120_000);
        check(after != null && after.estimate().previousPtsUs() == 120_000,
                "the next frame only borrows inside the new shot");
        check(owner.observeVerifiedPair(1, 15, 208_000, 7, GEOMETRY, 200_000) == null,
                "dropped indexed neighbour cannot use a different staged timestamp");
        owner.release();
    }

    private static void hiddenAndDisabledPathsDoNotOpenGl() throws Exception {
        int constructions = GpuPyramidFlow.constructions;
        int draws = GLES20.draws;
        FastFootagePreviewOwner owner = new FastFootagePreviewOwner(new AssetManager(), 50_000);
        check(owner.observe(true, true, true, true, 1.0, 0, 0, 0, null) == null,
                "hidden launch feature is sharp");
        check(owner.observe(false, false, true, true, 1.0, 0, 0, 0, null) == null,
                "project master off is sharp");
        check(owner.observe(false, true, false, true, 1.0, 0, 0, 0, null) == null,
                "clip tick off is sharp");
        check(owner.observe(false, true, true, false, 1.0, 0, 0, 0, null) == null,
                "still source is sharp");
        check(owner.observe(false, true, true, true, 0.0, 0, 0, 0, null) == null,
                "freeze is sharp");
        check(GpuPyramidFlow.constructions == constructions && GLES20.draws == draws,
                "closed gates make no GL call");
        owner.release();
        owner.release();
    }

    private static void enabledPathPublishesOnlyContinuousFlow() throws Exception {
        FastFootagePreviewOwner owner = new FastFootagePreviewOwner(new AssetManager(), 50_000);
        check(owner.observe(false, true, true, true, 2.0, 11, 1_000, 7, GEOMETRY) == null,
                "first enabled frame is sharp");
        FastFootagePreviewOwner.Frame frame =
                owner.observe(false, true, true, true, 2.0, 12, 34_000, 7, GEOMETRY);
        check(frame != null, "continuous neighbour produces flow");
        check(frame.estimate().currentPtsUs() == 34_000
                        && frame.estimate().previousPtsUs() == 1_000,
                "real decoder PTS survive the seam");
        check(Math.abs(frame.scaleForOffset(16_500) + 1.0) < 1e-9,
                "source speed and pair duration give one signed scale");
        check(Math.abs(frame.scaleForOffset(-16_500) - 1.0) < 1e-9,
                "a prior shutter tap follows the backward flow");
        check(frame.scaleForOffset(0) == 0.0, "centre tap stays at the current frame");
        check(owner.observe(false, true, true, true, -2.0, 12, 34_000, 7, GEOMETRY)
                        .scaleForOffset(16_500) == 1.0,
                "negative project-to-source speed inverts the tap displacement");

        check(owner.observe(true, true, true, true, 2.0, 0, 0, 0, null) == null,
                "closing the launch gate clears the run without GL");
        check(owner.observe(false, true, true, true, 2.0, 13, 67_000, 7, GEOMETRY) == null,
                "reopening starts sharp instead of borrowing hidden history");
        check(owner.observe(false, true, true, true, 2.0, 14, 100_000, 7, GEOMETRY) != null,
                "a new visible run resumes on its second frame");
        int draws = GLES20.draws;
        check(owner.observe(false, true, true, true, 2.0, 15, 110_000, 7, null) == null,
                "unavailable decoder geometry stays sharp");
        check(GLES20.draws == draws, "unavailable decoder geometry makes no capture draw");
        check(owner.observe(false, true, true, true, 2.0, 16, 120_000, 7, GEOMETRY) == null,
                "the next known frame cannot borrow a neighbour across unavailable geometry");
        check(owner.observe(false, true, true, true, 2.0, 0, 126_000, 7, GEOMETRY) == null,
                "missing staging texture stays sharp");
        check(owner.observe(false, true, true, true, 2.0, 17, 132_000, 7, GEOMETRY) == null,
                "the next staged frame cannot borrow across a missing texture");
        owner.reset();
        check(owner.observe(false, true, true, true, 2.0, 15, 133_000, 8, GEOMETRY) == null,
                "explicit discontinuity starts sharp");
        owner.release();
        expectThrows(IllegalStateException.class,
                () -> owner.observe(false, true, true, true, 1.0, 16, 166_000, 8, GEOMETRY));
    }

    private static void expectThrows(Class<? extends Throwable> type, Throwing action) {
        try {
            action.run();
            throw new AssertionError("expected " + type.getSimpleName());
        } catch (Throwable actual) {
            if (!type.isInstance(actual)) throw new AssertionError("wrong exception", actual);
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private interface Throwing { void run() throws Exception; }
}
