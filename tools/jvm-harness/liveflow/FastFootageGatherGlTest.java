package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;

import com.fadcam.ui.faditor.motion.MotionShutter;

import java.util.Arrays;

/** Checks the draw contract and the actual uniforms delivered from the editor clock. */
public final class FastFootageGatherGlTest {
    public static void main(String[] args) throws Exception {
        FastFootageGatherGl gather = new FastFootageGatherGl(new AssetManager());
        var geometry = new LiveFlowGl.SourceGeometry("clip", 1920, 1080, 90, 0.25f, 0, 0.75f, 1);
        var field = new GpuPyramidFlow.FieldTexture(71, 240, 135, 72);
        var estimate = new LiveFlowGl.Estimate(field, 34_000, 1_000, geometry);
        var frame = new FastFootagePreviewOwner.Frame(estimate, 2);
        var closed = new MotionShutter.Schedule(new long[]{100}, new double[]{1});
        check(!gather.draw(0, -1, 0, 0, null, 100, null), "no estimate keeps the staged frame");
        check(!gather.draw(0, -1, 0, 0, frame, 100, closed), "a centre-only shutter is sharp");
        check(GlProgram.programs == 0 && GLES20.draws == 0, "sharp cases make no GL calls");

        var opened = new MotionShutter.Schedule(
                new long[]{0, 8_250, 16_500, 24_750, 33_000}, new double[]{1, 1, 2, 2, 2});
        int originalFbo = GLES20.framebuffer;
        int[] originalViewport = GLES20.viewport.clone();
        int originalUnit = GLES20.activeTexture;
        check(gather.draw(13, 50, 1280, 720, frame, 16_500, opened), "exposure draws");
        check(GLES20.draws == 1 && GlProgram.programs == 1, "one draw and one shader");
        check(GlProgram.samplers.get("uSource") == 13 && GlProgram.samplers.get("uFlow") == 71
                        && GlProgram.samplers.get("uGuide") == 72,
                "the staged frame, packed field and luma guide occupy separate inputs");
        expect("uRegion", 0.25f, 0, 0.5f, 1);
        expect("uSourcePerFlowPixel", 8, 8);
        expect("uOffsetsA", 1, 0.5f, 0, -0.5f);
        expect("uOffsetsB", -1, 0, 0, 0);
        expect("uWeightsA", 0.125f, 0.125f, 0.25f, 0.25f);
        expect("uWeightsB", 0.25f, 0, 0, 0);
        expect("uTravelScale", 2);
        check(GLES20.framebuffer == originalFbo && Arrays.equals(GLES20.viewport, originalViewport)
                        && GLES20.activeTexture == originalUnit && GLES20.dither,
                "the draw restores the caller's GL state");

        var shortExposure = new MotionShutter.Schedule(new long[]{0, 16_500}, new double[]{1, 1});
        GLES20.dither = false;
        check(gather.draw(13, 50, 1280, 720, frame, 16_500, shortExposure), "reuse the shader");
        check(GlProgram.programs == 1 && GLES20.draws == 2 && !GLES20.dither, "reuse GL state");
        expect("uWeightsB", 0, 0, 0, 0);
        gather.release();
        gather.release();
        check(GlProgram.deletions == 1, "release the shader once");
        System.out.println("FastFootageGatherGl: PASS");
    }

    private static void expect(String name, float... expected) {
        float[] actual = GlProgram.uniforms.get(name);
        check(actual.length == expected.length, name + " length");
        for (int i = 0; i < actual.length; i++) {
            check(Math.abs(actual[i] - expected[i]) < 1e-6f, name + " at " + i);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
