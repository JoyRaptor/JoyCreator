package com.fadcam.ui.faditor.motion.flow;

import android.opengl.GLES20;

import java.util.EnumSet;

/** Checks exact-pair texture reuse, fallback and context ownership. */
public final class FlowPairTexturesGlTest {
    public static void main(String[] args) throws Exception {
        int oldUnit = GLES20.activeTexture, oldBinding = GLES20.boundTexture;
        FlowCache.Pair first = pair(100, 200, 2f, -3f);
        var owner = new FlowPairTexturesGl();
        HighFootageGatherGl.Pair handle = owner.set(first);
        check(handle.fromPtsUs() == 100 && handle.toPtsUs() == 200,
                "handle keeps exact decoded timestamps");
        check(GLES20.generatedTextures == 2 && GLES20.textureUploads == 2,
                "one bidirectional pair owns two uploads");
        check(GLES20.activeTexture == oldUnit && GLES20.boundTexture == oldBinding,
                "upload restores caller texture state");

        check(owner.set(first) == handle && GLES20.textureUploads == 2,
                "same immutable resident pair does not upload again");
        HighFootageGatherGl.Pair replacement = owner.set(pair(200, 300, -4f, 1f));
        check(replacement.forwardTexture() == handle.forwardTexture()
                        && replacement.backwardTexture() == handle.backwardTexture()
                        && GLES20.textureUploads == 4,
                "new exact pair reuses owned texture names");

        FlowCache.Pair gap = new FlowCache.Pair(field(300, 400, 0, 0), field(400, 300, 0, 0),
                EnumSet.of(FlowCache.Flag.LONG_GAP));
        check(owner.set(gap) == null && GLES20.deletedTextures == 2,
                "unusable pair clears stale textures and returns sharp fallback");
        owner.release(); owner.release();
        check(GLES20.deletedTextures == 2, "release after clear is idempotent");

        var lost = new FlowPairTexturesGl();
        lost.set(first);
        int deletes = GLES20.deletedTextures;
        lost.abandon();
        check(GLES20.deletedTextures == deletes, "lost context forgets names without GL deletion");
        expectClosed(lost);
        System.out.println("FlowPairTexturesGl: PASS");
    }

    private static FlowCache.Pair pair(long from, long to, float u, float v) {
        return new FlowCache.Pair(field(from, to, u, v), field(to, from, -u, -v));
    }

    private static FlowField field(long from, long to, float u, float v) {
        return new FlowField(2, 2, new float[]{u, u, u, u}, new float[]{v, v, v, v},
                new float[]{1, .75f, .5f, .25f}, from, to);
    }

    private static void expectClosed(FlowPairTexturesGl owner) {
        try {
            owner.set(pair(0, 1, 0, 0));
            throw new AssertionError("abandoned owner accepted a new pair");
        } catch (IllegalStateException expected) {
            // expected
        } catch (Exception wrong) {
            throw new AssertionError("wrong closed-owner failure", wrong);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
