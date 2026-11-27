package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;

import com.fadcam.ui.faditor.motion.MotionShutter;

import java.util.Arrays;

/** Checks High's decoded-PTS mapping, pair selection and GL ownership contract. */
public final class HighFootageGatherGlTest {
    public static void main(String[] args) throws Exception {
        HighFootageGatherGl gather = new HighFootageGatherGl(new AssetManager());
        var before = new HighFootageGatherGl.Pair(31, 32, 67_000, 100_000);
        var after = new HighFootageGatherGl.Pair(41, 42, 100_000, 133_000);
        var frame = new HighFootageGatherGl.Frame(11, 12, 13, before, after, 2.0,
                0.25f, 0f, 0.75f, 1f);
        var centre = new MotionShutter.Schedule(new long[]{1_000_000}, new double[]{1});
        check(!gather.draw(frame, -1, 0, 0, 0, 0, 1_000_000, centre), "centre stays sharp");
        check(GLES20.draws == 0 && GlProgram.programs == 0, "sharp path makes no GL call");

        var opened = new MotionShutter.Schedule(
                new long[]{983_500, 991_750, 1_000_000, 1_008_250, 1_016_500},
                new double[]{1, 1, 2, 1, 1});
        int oldFbo = GLES20.framebuffer, oldUnit = GLES20.activeTexture;
        int[] oldViewport = GLES20.viewport.clone();
        check(gather.draw(frame, 50, 1280, 720, 320, 180, 1_000_000, opened), "High exposure draws");
        check(GLES20.draws == 1 && GlProgram.programs == 1, "one draw and one small shader");
        check(GlProgram.samplers.get("uPrevious") == 11 && GlProgram.samplers.get("uCurrent") == 12
                && GlProgram.samplers.get("uNext") == 13, "three decoded donors are distinct");
        check(GlProgram.samplers.get("uBeforeForward") == 31
                && GlProgram.samplers.get("uBeforeBackward") == 32
                && GlProgram.samplers.get("uAfterForward") == 41
                && GlProgram.samplers.get("uAfterBackward") == 42, "both bidirectional pairs are bound");
        for (int field : new int[]{31, 32, 41, 42}) {
            check(GLES20.minFilters.get(field) == GLES20.GL_NEAREST
                            && GLES20.magFilters.get(field) == GLES20.GL_NEAREST,
                    "packed flow keeps nearest filtering after Media3 sampler binding");
        }
        for (int color : new int[]{11, 12, 13}) {
            check(GLES20.minFilters.get(color) == GLES20.GL_LINEAR
                            && GLES20.magFilters.get(color) == GLES20.GL_LINEAR,
                    "color donors keep linear filtering");
        }
        expect("uRegion", .25f, 0f, .5f, 1f);
        expect("uFlowSize", 320f, 180f);
        expect("uSourcePerFlowPixel", 2f, 4f);
        expect("uFractionsA", -1f, -.5f, 0f, 1.5f);
        expect("uFractionsB", 2f, 0f, 0f, 0f);
        expect("uWeightsA", 1f/6f, 1f/6f, 0f, 1f/6f);
        expect("uWeightsB", 1f/6f, 0f, 0f, 0f);
        expect("uExtents", 1f, 1f);
        check(GLES20.framebuffer == oldFbo && GLES20.activeTexture == oldUnit
                        && Arrays.equals(GLES20.viewport, oldViewport) && GLES20.dither,
                "caller GL state is restored");

        var reverse = new HighFootageGatherGl.Frame(11, 12, 13, before, after, -2.0,
                0f, 0f, 1f, 1f);
        check(gather.draw(reverse, 50, 1280, 720, 320, 180, 1_000_000, opened), "reverse remaps pair sides");
        expect("uFractionsA", 2f, 1.5f, 0f, -.5f);
        expect("uFractionsB", -1f, 0f, 0f, 0f);

        var beforeOnly = new HighFootageGatherGl.Frame(11, 12, 0, before, null, 2.0,
                0f, 0f, 1f, 1f);
        var futureOnly = new MotionShutter.Schedule(new long[]{1_016_500}, new double[]{1});
        check(!gather.draw(beforeOnly, -1, 0, 0, 0, 0, 1_000_000, futureOnly),
                "missing neighbour returns its weight to the sharp centre");

        gather.release(); gather.release();
        check(GlProgram.deletions == 1, "shader releases once");
        System.out.println("HighFootageGatherGl: PASS");
    }

    private static void expect(String name, float... expected) {
        float[] actual = GlProgram.uniforms.get(name);
        check(actual != null && actual.length == expected.length, name + " length");
        for (int i = 0; i < expected.length; i++) {
            check(Math.abs(actual[i] - expected[i]) < 1e-5f, name + " at " + i
                    + " expected " + expected[i] + " got " + actual[i]);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
