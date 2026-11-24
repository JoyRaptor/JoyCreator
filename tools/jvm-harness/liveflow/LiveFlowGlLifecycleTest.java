package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import java.util.Arrays;

public final class LiveFlowGlLifecycleTest {
    private static final LiveFlowGl.SourceGeometry A = geometry("a", 1920, 1080, 0);
    private static final LiveFlowGl.SourceGeometry B = geometry("b", 1920, 1080, 0);

    public static void main(String[] args) throws Exception {
        rejectsInvalidGeometry();
        ownsFramesAndHonoursContinuity();
        System.out.println("LiveFlowGl lifecycle: PASS");
    }

    private static void rejectsInvalidGeometry() {
        expectThrows(IllegalArgumentException.class,
                () -> geometry("a", 1920, 1080, 45));
        expectThrows(IllegalArgumentException.class,
                () -> new LiveFlowGl.SourceGeometry("a", 1920, 1080, 0,
                        Float.NaN, 0f, 1f, 1f));
    }

    private static void ownsFramesAndHonoursContinuity() throws Exception {
        LiveFlowGl flow = new LiveFlowGl(new AssetManager(), 50_000);
        int savedFramebuffer = GLES20.framebuffer;
        int[] savedViewport = GLES20.viewport.clone();
        int savedActive = GLES20.activeTexture;

        flow.prepare(A);
        assertState(savedFramebuffer, savedViewport, savedActive, true);
        check(GpuPyramidFlow.constructions == 1, "prepare must warm one workspace");
        check(flow.observe(11, 1_000, 1, A) == null, "first frame must be sharp");
        assertState(savedFramebuffer, savedViewport, savedActive, true);

        LiveFlowGl.Estimate second = flow.observe(12, 34_000, 1, A);
        check(second != null && second.currentPtsUs() == 34_000 && second.previousPtsUs() == 1_000,
                "second frame must pair backward");
        check(GpuPyramidFlow.lastFrom != 12 && GpuPyramidFlow.lastTo != 11,
                "estimator must receive owned snapshots, not borrowed inputs");
        check(GpuPyramidFlow.lastFrom != GpuPyramidFlow.lastTo,
                "current and previous snapshots must differ");

        int draws = GLES20.draws;
        int estimates = GpuPyramidFlow.estimates;
        check(flow.observe(99, 34_000, 1, A) == second, "paused redraw must reuse estimate");
        check(GLES20.draws == draws && GpuPyramidFlow.estimates == estimates,
                "paused redraw must not copy or estimate again");

        GLES20.dither = false;
        check(flow.observe(13, 67_000, 1, A) != null, "third frame must recycle safely");
        assertState(savedFramebuffer, savedViewport, savedActive, false);
        GLES20.dither = true;
        check(flow.observe(14, 40_000, 1, A) == null, "decreasing PTS must flush and draw sharp");
        check(flow.observe(15, 73_000, 2, A) == null, "new epoch must draw sharp");
        check(flow.observe(16, 106_000, 2, B) == null, "new source identity must draw sharp");

        int beforeSmall = GLES20.draws;
        LiveFlowGl.SourceGeometry tiny = geometry("tiny", 40, 40, 0);
        check(flow.observe(17, 139_000, 2, tiny) == null, "unsupported grid must draw sharp");
        check(GLES20.draws == beforeSmall, "unsupported grid must not capture");

        flow.release();
        flow.release();
        check(GpuPyramidFlow.releases == GpuPyramidFlow.constructions,
                "every estimator must release exactly once");
        check(GLES20.deletedTextures == 3 && GLES20.deletedFramebuffers == 3,
                "all three owned snapshots must release exactly once");
        expectThrows(IllegalStateException.class, () -> flow.observe(18, 172_000, 2, A));
        assertState(savedFramebuffer, savedViewport, savedActive, true);
    }

    private static LiveFlowGl.SourceGeometry geometry(String key, int width, int height, int rotation) {
        return new LiveFlowGl.SourceGeometry(key, width, height, rotation, 0f, 0f, 1f, 1f);
    }

    private static void assertState(int framebuffer, int[] viewport, int activeTexture,
                                    boolean dither) {
        check(GLES20.framebuffer == framebuffer, "framebuffer must be restored");
        check(Arrays.equals(GLES20.viewport, viewport), "viewport must be restored");
        check(GLES20.activeTexture == activeTexture, "active texture must be restored");
        check(GLES20.dither == dither, "dither must be restored");
    }

    private static void expectThrows(Class<? extends Throwable> type, Throwing action) {
        try {
            action.run();
            throw new AssertionError("expected " + type.getSimpleName());
        } catch (Throwable actual) {
            if (!type.isInstance(actual)) throw new AssertionError("wrong exception", actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Throwing { void run() throws Exception; }
}
