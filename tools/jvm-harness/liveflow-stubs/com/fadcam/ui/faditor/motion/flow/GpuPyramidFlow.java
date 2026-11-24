package com.fadcam.ui.faditor.motion.flow;

import android.content.res.AssetManager;
import androidx.media3.common.util.GlUtil;
import java.io.IOException;

public final class GpuPyramidFlow {
    public record FieldTexture(int textureId, int width, int height) {}

    static int constructions;
    static int estimates;
    static int releases;
    static int lastFrom;
    static int lastTo;
    private final FieldTexture field;

    public GpuPyramidFlow(AssetManager assets, int width, int height, boolean reverse)
            throws IOException, GlUtil.GlException {
        constructions++;
        field = new FieldTexture(900 + constructions, width, height);
    }

    public FieldTexture estimate(int from, int to) throws GlUtil.GlException {
        estimates++;
        lastFrom = from;
        lastTo = to;
        return field;
    }

    public void release() throws GlUtil.GlException { releases++; }
}
