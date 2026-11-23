package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fixed-size GLES2 flow workspace owned by one GL context. Input textures remain caller-owned. */
public final class GpuPyramidFlow {
    private static final String ASSETS = "studio/motion/flow/";
    private static final PyramidFlowEstimator.Settings SETTINGS = PyramidFlowEstimator.Settings.DEFAULT;
    private static final float[] NO_COARSE_LEVEL = {0f, 0f};

    /** RGB stores signed flow from the first texture to the second. Alpha stores confidence. */
    public record FieldTexture(int textureId, int width, int height) {}

    private final List<GlProgram> programs = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private final List<Level> levels = new ArrayList<>();
    private final int[] savedFramebuffer = new int[1];
    private final int[] savedViewport = new int[4];
    private final GlProgram luma;
    private final GlProgram half;
    private final GlProgram patch;
    private final GlProgram densify;
    private final GlProgram confidence;
    private final Target backward;
    private final Target output;
    private final FieldTexture result;
    private boolean released;

    /**
     * Compile shaders and reserve targets on the GL thread before playback. Dimensions describe the
     * reduced analysis grid. The caller chooses them and recreates the workspace if they change.
     */
    public GpuPyramidFlow(AssetManager assets, int width, int height, boolean checkReverse)
            throws IOException, GlUtil.GlException {
        if (Math.min(width, height) < SETTINGS.patch()) {
            throw new IllegalArgumentException("Flow grid is smaller than one patch");
        }
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, savedFramebuffer, 0);
        if (Math.max(width, height) > savedFramebuffer[0]) {
            throw new IllegalArgumentException("Flow grid exceeds the texture size limit");
        }

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0);
        try {
            String vertex = read(assets, "fullscreen.vert");
            String velocity = read(assets, "velocity.glsl");
            luma = program(vertex, read(assets, "luma.frag"));
            half = program(vertex, read(assets, "half-luma.frag"));
            patch = program(vertex, read(assets, "patch-search.frag") + "\n" + velocity);
            densify = program(vertex, read(assets, "densify.frag") + "\n" + velocity);
            String confidenceSource = read(assets, "confidence.frag");
            if (checkReverse) {
                confidenceSource = confidenceSource.replace("#version 100\n",
                        "#version 100\n#define FLOW_HAS_REVERSE_FIELD\n");
            }
            confidence = program(vertex, confidenceSource + "\n" + velocity);

            int w = width;
            int h = height;
            do {
                levels.add(new Level(target(w, h, true), target(w, h, true),
                        target(patchCount(w), patchCount(h), false), target(w, h, false)));
                w /= 2;
                h /= 2;
            } while (levels.size() < SETTINGS.maxLevels() && Math.min(w, h) >= SETTINGS.minLevelSize());
            backward = checkReverse ? target(width, height, false) : null;
            output = target(width, height, false);
            result = new FieldTexture(output.texture, width, height);
        } catch (IOException | GlUtil.GlException | RuntimeException problem) {
            try {
                release();
            } catch (GlUtil.GlException cleanup) {
                problem.addSuppressed(cleanup);
            }
            throw problem;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0]);
        }
    }

    /**
     * Analyze two upright, SDR-coded GL_TEXTURE_2D inputs from one continuous source. The returned
     * field is valid until the next estimate or release. The caller sets up a fullscreen pass with
     * blend, scissor, depth, stencil and culling off, the RGBA write mask on, and no array buffer
     * bound. Framebuffer, viewport and dithering are restored. Programs, texture units and vertex
     * attributes are scratch state; the caller binds its own before drawing again.
     */
    public FieldTexture estimate(int fromTexture, int toTexture) throws GlUtil.GlException {
        if (released) throw new IllegalStateException("Flow workspace has been released");
        if (fromTexture <= 0 || toTexture <= 0) throw new IllegalArgumentException("Missing source texture");

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFramebuffer, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);
        boolean dither = GLES20.glIsEnabled(GLES20.GL_DITHER);
        GLES20.glDisable(GLES20.GL_DITHER);
        try {
            Level first = levels.get(0);
            drawLuma(fromTexture, first.from);
            drawLuma(toTexture, first.to);
            for (int i = 1; i < levels.size(); i++) {
                Level previous = levels.get(i - 1);
                Level level = levels.get(i);
                drawHalf(previous.from, level.from);
                drawHalf(previous.to, level.to);
            }
            estimateDirection(false);
            if (backward != null) estimateDirection(true);

            confidence.setSamplerTexIdUniform("uFrom", first.from.texture, 0);
            confidence.setSamplerTexIdUniform("uTo", first.to.texture, 1);
            confidence.setSamplerTexIdUniform("uForward", first.dense.texture, 2);
            if (backward != null) confidence.setSamplerTexIdUniform("uBackward", backward.texture, 3);
            confidence.setFloatsUniform("uLevelSize", output.size);
            draw(confidence, output);
            GlUtil.checkGlError();
            return result;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFramebuffer[0]);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            if (dither) GLES20.glEnable(GLES20.GL_DITHER);
        }
    }

    private void estimateDirection(boolean reverse) throws GlUtil.GlException {
        Target coarse = null;
        for (int i = levels.size() - 1; i >= 0; i--) {
            Level level = levels.get(i);
            Target from = reverse ? level.to : level.from;
            Target to = reverse ? level.from : level.to;

            patch.setSamplerTexIdUniform("uFrom", from.texture, 0);
            patch.setSamplerTexIdUniform("uTo", to.texture, 1);
            patch.setSamplerTexIdUniform("uCoarseFlow", coarse == null ? from.texture : coarse.texture, 2);
            patch.setFloatsUniform("uLevelSize", from.size);
            patch.setFloatsUniform("uCoarseSize", coarse == null ? NO_COARSE_LEVEL : coarse.size);
            draw(patch, level.patches);

            Target dense = reverse && i == 0 ? backward : level.dense;
            densify.setSamplerTexIdUniform("uFrom", from.texture, 0);
            densify.setSamplerTexIdUniform("uTo", to.texture, 1);
            densify.setSamplerTexIdUniform("uPatchFlow", level.patches.texture, 2);
            densify.setFloatsUniform("uLevelSize", from.size);
            densify.setFloatsUniform("uPatchGridSize", level.patches.size);
            draw(densify, dense);
            coarse = dense;
        }
    }

    private void drawLuma(int source, Target target) throws GlUtil.GlException {
        luma.setSamplerTexIdUniform("uSource", source, 0);
        draw(luma, target);
    }

    private void drawHalf(Target source, Target target) throws GlUtil.GlException {
        half.setSamplerTexIdUniform("uSource", source.texture, 0);
        half.setFloatsUniform("uSourceTexel", source.texel);
        draw(half, target);
    }

    private static void draw(GlProgram program, Target target) throws GlUtil.GlException {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer);
        GLES20.glViewport(0, 0, target.width, target.height);
        program.use();
        program.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Release on the context that created this workspace. */
    public void release() throws GlUtil.GlException {
        if (released) return;
        released = true;
        for (Target target : targets) target.delete();
        targets.clear();
        GlUtil.GlException failure = null;
        for (GlProgram program : programs) {
            try {
                program.delete();
            } catch (GlUtil.GlException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        programs.clear();
        if (failure != null) throw failure;
    }

    private GlProgram program(String vertex, String fragment) throws GlUtil.GlException {
        GlProgram program = new GlProgram(vertex, fragment);
        programs.add(program);
        program.setBufferAttribute("aPosition", new float[]{-1, -1, 1, -1, -1, 1, 1, 1}, 2);
        return program;
    }

    private Target target(int width, int height, boolean linear) throws GlUtil.GlException {
        Target target = new Target(width, height);
        targets.add(target);
        int[] name = new int[1];
        GLES20.glGenTextures(1, name, 0);
        target.texture = name[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.texture);
        int filter = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        name[0] = 0;
        GLES20.glGenFramebuffers(1, name, 0);
        target.framebuffer = name[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, target.texture, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete flow framebuffer " + width + "x" + height);
        }
        GlUtil.checkGlError();
        return target;
    }

    private static int patchCount(int size) {
        return 1 + (size - SETTINGS.patch() + SETTINGS.stride() - 1) / SETTINGS.stride();
    }

    private static String read(AssetManager assets, String name) throws IOException {
        try (InputStream stream = assets.open(ASSETS + name)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private record Level(Target from, Target to, Target patches, Target dense) {}

    private static final class Target {
        final int width;
        final int height;
        final float[] size;
        final float[] texel;
        int texture;
        int framebuffer;

        Target(int width, int height) {
            this.width = width;
            this.height = height;
            size = new float[]{width, height};
            texel = new float[]{1f / width, 1f / height};
        }

        void delete() {
            GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        }
    }
}
