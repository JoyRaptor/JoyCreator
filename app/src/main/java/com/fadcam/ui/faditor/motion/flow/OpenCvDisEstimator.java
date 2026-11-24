package com.fadcam.ui.faditor.motion.flow;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.video.DISOpticalFlow;

/**
 * OpenCV DIS candidate for the C6 estimator bake-off.
 *
 * <p>This class is deliberately not wired into playback. It implements the same source-space
 * contract as {@link DecodedFlowWork.Estimator}, reuses the input buffers, and lets the caller
 * time conversion and DIS together with any competing GPU readback. The winner is selected only
 * after both Note 9 variants run the high-speed reference corpus.
 */
public final class OpenCvDisEstimator implements DecodedFlowWork.Estimator, AutoCloseable {
    public enum Preset {
        ULTRA_FAST(DISOpticalFlow.PRESET_ULTRAFAST),
        FAST(DISOpticalFlow.PRESET_FAST),
        MEDIUM(DISOpticalFlow.PRESET_MEDIUM);

        final int openCvValue;
        Preset(int openCvValue) { this.openCvValue = openCvValue; }
    }

    private final DISOpticalFlow dis;
    private final Mat from;
    private final Mat to;
    private final Mat flow;
    private byte[] lumaBytes = new byte[0];
    private float[] packedFlow = new float[0];
    private int width;
    private int height;
    private boolean closed;

    public OpenCvDisEstimator(Preset preset) {
        if (preset == null) throw new IllegalArgumentException("Missing DIS preset");
        if (!OpenCVLoader.initDebug()) {
            throw new IllegalStateException("OpenCV native runtime not available");
        }
        dis = DISOpticalFlow.create(preset.openCvValue);
        from = new Mat();
        to = new Mat();
        flow = new Mat();
    }

    @Override
    public synchronized FlowField estimate(LumaImage first, LumaImage second,
                                           long fromPtsUs, long toPtsUs) {
        if (closed) throw new IllegalStateException("DIS estimator has been closed");
        if (first == null || second == null || first.width != second.width
                || first.height != second.height || fromPtsUs == toPtsUs) {
            throw new IllegalArgumentException("Invalid DIS flow pair");
        }
        ensureWorkspace(first.width, first.height);
        putLuma(first, from);
        putLuma(second, to);
        // DIS treats a populated output Mat as an initial estimate. Each pair is independent.
        flow.release();
        dis.calc(from, to, flow);
        if (flow.rows() != height || flow.cols() != width || flow.type() != CvType.CV_32FC2
                || flow.get(0, 0, packedFlow) != (long) packedFlow.length * Float.BYTES) {
            throw new IllegalStateException("DIS returned the wrong flow shape");
        }
        float[] u = new float[width * height];
        float[] v = new float[u.length];
        for (int i = 0; i < u.length; i++) {
            u[i] = packedFlow[2 * i];
            v[i] = packedFlow[2 * i + 1];
            if (!Float.isFinite(u[i]) || !Float.isFinite(v[i])) {
                throw new IllegalStateException("DIS returned non-finite flow");
            }
        }
        return new FlowField(width, height, u, v, null, fromPtsUs, toPtsUs);
    }

    private void ensureWorkspace(int requestedWidth, int requestedHeight) {
        if (requestedWidth == width && requestedHeight == height) return;
        from.create(requestedHeight, requestedWidth, CvType.CV_8UC1);
        to.create(requestedHeight, requestedWidth, CvType.CV_8UC1);
        lumaBytes = new byte[requestedWidth * requestedHeight];
        packedFlow = new float[2 * lumaBytes.length];
        width = requestedWidth;
        height = requestedHeight;
    }

    private void putLuma(LumaImage image, Mat target) {
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++, i++) {
                float value = image.at(x, y);
                if (!Float.isFinite(value)) value = 0;
                int channel = Math.round(255f * Math.max(0f, Math.min(1f, value)));
                lumaBytes[i] = (byte) channel;
            }
        }
        if (target.put(0, 0, lumaBytes) != lumaBytes.length) {
            throw new IllegalStateException("Could not upload luma to OpenCV");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        from.release();
        to.release();
        flow.release();
        dis.collectGarbage();
        lumaBytes = new byte[0];
        packedFlow = new float[0];
        width = height = 0;
    }
}
