package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Backward flow between the last two ungraded, decoded frames on a single current GLES context.
 *
 * <p>The caller matches a latched texture to its decoder PTS and supplies a new epoch after a
 * seek, cut, or output reroute. The input is borrowed only for the capture draw. GLES commands
 * on this context order that draw before subsequent writes to the caller's staging texture.
 * The ring owns two one-eighth-resolution snapshots, with a third pooled slot for the incoming
 * frame while both retained frames are still live.
 *
 * <p>As with {@link GpuPyramidFlow}, the caller establishes fullscreen pass state (blend,
 * scissor, depth, stencil, and cull off, full color mask, no array buffer) and binds its own
 * program, texture bindings and vertex attributes for the next pass. This owner preserves the
 * caller's framebuffer, viewport, dithering and active texture unit. All methods belong on the
 * same GL thread. Release before destroying the context, then construct a new owner.
 */
public final class LiveFlowGl {
    private static final int SCALE = 8;
    private static final int SNAPSHOT_COUNT = 3;
    private static final int MIN_GRID_SIDE = PyramidFlowEstimator.Settings.DEFAULT.patch();
    private static final String COPY_VERTEX = """
            attribute vec2 aPosition;
            varying highp vec2 vUv;

            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
            """;
    private static final String COPY_FRAGMENT = """
            precision highp float;
            uniform sampler2D uSource;
            uniform vec4 uRegion;
            uniform vec2 uTapOffset;
            varying highp vec2 vUv;

            void main() {
                vec2 p = uRegion.xy + vUv * uRegion.zw;
                vec2 d = uTapOffset;
                gl_FragColor = 0.25 * (texture2D(uSource, p + vec2(-d.x, -d.y))
                        + texture2D(uSource, p + vec2(d.x, -d.y))
                        + texture2D(uSource, p + vec2(-d.x, d.y))
                        + texture2D(uSource, p + vec2(d.x, d.y)));
            }
            """;

    /** Visible upright source size and its normalized, bottom-up rectangle in the input texture. */
    public record SourceGeometry(String sourceKey, int visibleWidth, int visibleHeight,
                                 int rotationDegrees, float left, float bottom, float right, float top) {
        public SourceGeometry {
            if (sourceKey == null || sourceKey.isEmpty() || visibleWidth <= 0 || visibleHeight <= 0
                    || (rotationDegrees != 0 && rotationDegrees != 90
                    && rotationDegrees != 180 && rotationDegrees != 270)
                    || !(left >= 0f && left < right && right <= 1f
                    && bottom >= 0f && bottom < top && top <= 1f)) {
                throw new IllegalArgumentException("Invalid live flow source geometry");
            }
        }
    }

    /** Borrowed field, in analysis-grid pixels from the current source image to its previous one. */
    public record Estimate(GpuPyramidFlow.FieldTexture field, long currentPtsUs, long previousPtsUs,
                           SourceGeometry source) {}

    private final AssetManager assets;
    private final ArrayDeque<Snapshot> available = new ArrayDeque<>(SNAPSHOT_COUNT);
    private final SourceFrameRing<Snapshot> frames;
    private final int[] savedFramebuffer = new int[1];
    private final int[] savedViewport = new int[4];
    private final int[] savedActiveTexture = new int[1];
    private final float[] region = new float[4];
    private final float[] tapOffset = new float[2];
    private boolean savedDither;
    private GlProgram copyProgram;
    private GpuPyramidFlow estimator;
    private int gridWidth;
    private int gridHeight;
    private SourceGeometry source;
    private long epoch;
    private Estimate latest;
    private boolean released;

    public LiveFlowGl(AssetManager assets, long maxGapUs) {
        if (assets == null || maxGapUs <= 0) {
            throw new IllegalArgumentException("Live flow requires assets and a positive gap limit");
        }
        this.assets = assets;
        frames = new SourceFrameRing<>(2, maxGapUs, available::addLast);
    }

    /** Prewarm for a known source size before playback. Smaller grids will remain sharp. */
    public void prepare(SourceGeometry geometry) throws IOException, GlUtil.GlException {
        requireOpen();
        int width = geometry.visibleWidth() / SCALE;
        int height = geometry.visibleHeight() / SCALE;
        if (Math.min(width, height) < MIN_GRID_SIDE) return;
        saveState();
        try {
            ensureWorkspace(width, height);
        } finally {
            restoreState();
        }
    }

    /**
     * Capture one matched decoder frame and estimate current-to-previous flow. A null result
     * means draw sharp, including the first frame, unsupported geometry, a long PTS gap, and
     * decreasing PTS (reverse decode is outside this forward-preview owner). A repeated PTS
     * reuses the last estimate without copying or rerunning flow. The estimate remains valid
     * until an advancing capture, reset, size change, or release.
     */
    public Estimate observe(int sourceTexture, long sourcePtsUs, long continuityEpoch,
                            SourceGeometry geometry) throws IOException, GlUtil.GlException {
        requireOpen();
        if (sourceTexture <= 0) throw new IllegalArgumentException("Missing live source texture");
        int width = geometry.visibleWidth() / SCALE;
        int height = geometry.visibleHeight() / SCALE;
        if (Math.min(width, height) < MIN_GRID_SIDE) {
            reset();
            return null;
        }
        if (continuityEpoch == epoch && geometry.equals(source)) {
            var newest = frames.newest();
            if (newest != null && newest.ptsUs == sourcePtsUs) return latest;
        }

        saveState();
        try {
            ensureWorkspace(width, height);
            if (continuityEpoch != epoch || !geometry.equals(source)) {
                reset();
                epoch = continuityEpoch;
                source = geometry;
                region[0] = geometry.left();
                region[1] = geometry.bottom();
                region[2] = geometry.right() - geometry.left();
                region[3] = geometry.top() - geometry.bottom();
                tapOffset[0] = region[2] / (4f * gridWidth);
                tapOffset[1] = region[3] / (4f * gridHeight);
            }
            Snapshot current = available.removeFirst();
            try {
                capture(sourceTexture, current);
            } catch (RuntimeException | GlUtil.GlException failure) {
                available.addLast(current);
                throw failure;
            }
            frames.push(new SourceFrameRing.Frame<>(sourcePtsUs, geometry.sourceKey(),
                    width, height, geometry.rotationDegrees(), current));
            var previous = frames.previous(sourcePtsUs);
            latest = previous == null ? null
                    : new Estimate(estimator.estimate(current.texture, previous.handle.texture),
                            sourcePtsUs, previous.ptsUs, geometry);
            return latest;
        } catch (IOException | RuntimeException | GlUtil.GlException failure) {
            reset();
            throw failure;
        } finally {
            restoreState();
        }
    }

    /** Discard frame history on a discontinuity or when decoder metadata is unavailable. */
    public void reset() {
        frames.invalidate();
        latest = null;
        source = null;
    }

    /** Release all textures and programs. Safe to call twice on the owning GL thread. */
    public void release() throws GlUtil.GlException {
        if (released) return;
        released = true;
        GlUtil.GlException failure = null;
        try {
            releaseWorkspace();
        } catch (GlUtil.GlException error) {
            failure = error;
        }
        GlProgram previousCopy = copyProgram;
        copyProgram = null;
        if (previousCopy != null) {
            try {
                previousCopy.delete();
            } catch (GlUtil.GlException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("Live flow has been released");
    }

    private void ensureWorkspace(int width, int height) throws IOException, GlUtil.GlException {
        if (estimator != null && width == gridWidth && height == gridHeight) return;
        releaseWorkspace();
        try {
            if (copyProgram == null) {
                GlProgram candidate = new GlProgram(COPY_VERTEX, COPY_FRAGMENT);
                try {
                    candidate.setBufferAttribute("aPosition",
                            new float[]{-1, -1, 1, -1, -1, 1, 1, 1}, 2);
                    copyProgram = candidate;
                } catch (RuntimeException failure) {
                    try {
                        candidate.delete();
                    } catch (GlUtil.GlException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
            estimator = new GpuPyramidFlow(assets, width, height, false);
            for (int i = 0; i < SNAPSHOT_COUNT; i++) available.addLast(Snapshot.create(width, height));
            gridWidth = width;
            gridHeight = height;
        } catch (IOException | RuntimeException | GlUtil.GlException failure) {
            try {
                releaseWorkspace();
            } catch (GlUtil.GlException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void releaseWorkspace() throws GlUtil.GlException {
        reset();
        while (!available.isEmpty()) {
            available.removeFirst().delete();
        }
        GpuPyramidFlow previous = estimator;
        estimator = null;
        gridWidth = 0;
        gridHeight = 0;
        if (previous != null) {
            previous.release();
        }
    }

    private void capture(int sourceTexture, Snapshot target) throws GlUtil.GlException {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer);
        GLES20.glViewport(0, 0, gridWidth, gridHeight);
        copyProgram.use();
        copyProgram.setSamplerTexIdUniform("uSource", sourceTexture, 0);
        copyProgram.setFloatsUniform("uRegion", region);
        copyProgram.setFloatsUniform("uTapOffset", tapOffset);
        copyProgram.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GlUtil.checkGlError();
    }

    private void saveState() {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, savedActiveTexture, 0);
        savedDither = GLES20.glIsEnabled(GLES20.GL_DITHER);
        GLES20.glDisable(GLES20.GL_DITHER);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private void restoreState() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0]);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GLES20.glActiveTexture(savedActiveTexture[0]);
        if (savedDither) GLES20.glEnable(GLES20.GL_DITHER);
    }

    private static final class Snapshot {
        private int texture;
        private int framebuffer;

        static Snapshot create(int width, int height) throws GlUtil.GlException {
            Snapshot target = new Snapshot();
            try {
                target.texture = GlUtil.createTexture(width, height, false);
                target.framebuffer = GlUtil.createFboForTexture(target.texture);
                GlUtil.checkGlError();
                return target;
            } catch (RuntimeException | GlUtil.GlException failure) {
                target.delete();
                throw failure;
            }
        }

        void delete() {
            if (framebuffer != 0) {
                GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
                framebuffer = 0;
            }
            if (texture != 0) {
                GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                texture = 0;
            }
        }
    }
}
