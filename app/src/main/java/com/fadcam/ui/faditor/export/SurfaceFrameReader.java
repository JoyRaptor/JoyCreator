package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.nio.ByteBuffer;

/**
 * Surface counterpart to {@link SequentialFrameReader}: one forward MediaCodec
 * decode per source, but the decoder renders into a {@link SurfaceTexture}'s
 * OES texture so pixels never round-trip through the CPU.
 *
 * <p>Why a second reader: {@link SequentialFrameReader} configures the codec in
 * ByteBuffer mode (COLOR_FormatYUV420Flexible + getOutputImage) and walks YUV to
 * ARGB on the CPU ({@code convertMs} ~40% of export time, SEQ_FRAMES). This
 * reader configures the same codec with a {@link Surface} and exposes the OES
 * texture directly. The fallback is the existing reader — a slow export beats a
 * broken one.
 *
 * <p>Traps handled, in the order they matter:
 * <ul>
 *   <li>6.1 r_frame_rate 90000/1 — the format's declared frame rate is the MP4
 *       timescale leaking into the field. Configuring a decoder for 90000 fps
 *       is refused. Fixed by clamping the declared rate before configure.</li>
 *   <li>6.2 fMP4 does not seek — sniff for moof before seeking, same as
 *       SequentialFrameReader.isFragmented.</li>
 *   <li>6.4 v-flip — OES textures are bottom-row-first; the transform matrix
 *       carries the flip and is applied once in the staging blit, not per effect.</li>
 * </ul>
 */
final class SurfaceFrameReader {

    private static final String TAG = "SurfaceFrameReader";
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final int MAX_STALLED_DRAINS = 2000;
    private static final int MAX_REWINDS = 8;
    private static final long SEEK_WORTH_IT_US = 1_500_000L;

    private final Context context;
    private final Uri uri;

    @Nullable private MediaExtractor extractor;
    @Nullable private MediaCodec codec;
    @Nullable private Surface surface;
    @Nullable private SurfaceTexture surfaceTexture;
    private int oesTexId = 0;
    private final float[] texMatrix = new float[16];
    private boolean texMatrixValid = false;

    private boolean started = false;
    private boolean inputDone = false;
    private boolean streamEnded = false;
    private boolean degraded = false;
    private int rotationDeg = 0;

    private long currentPtsUs = Long.MIN_VALUE;
    private long lastRequestUs = Long.MIN_VALUE;

    private int decodedFrames = 0;
    private int rewinds = 0;
    private long pumpNanos = 0L;

    @Nullable private Boolean fragmented;

    private int sourceW = 0, sourceH = 0, displayW = 0, displayH = 0;
    private boolean triedInitialSeek = false;

    SurfaceFrameReader(@NonNull Context context, @NonNull Uri uri) {
        this.context = context.getApplicationContext();
        this.uri = uri;
    }

    boolean isDegraded() { return degraded; }

    int getOesTextureId() { return oesTexId; }

    void getTransformMatrix(@NonNull float[] out) {
        if (texMatrixValid) System.arraycopy(texMatrix, 0, out, 0, 16);
        else {
            // identity
            for (int i = 0; i < 16; i++) out[i] = (i % 5 == 0) ? 1f : 0f;
        }
    }

    /**
     * Ensure the texture is current for {@code sourceUs}. Returns true if a
     * frame at or after sourceUs is now bound to the OES texture.
     * Must be called on the GL thread with EGL current (Transformer effect thread).
     */
    boolean updateTo(long sourceUs) {
        if (degraded) return false;
        try {
            if (lastRequestUs != Long.MIN_VALUE && sourceUs < lastRequestUs) {
                if (++rewinds > MAX_REWINDS) {
                    FLog.w(TAG, "Too many rewinds for " + uri.getLastPathSegment() + "; degrading");
                    degraded = true;
                    releaseDecoder();
                    return false;
                }
                releaseDecoder();
            }
            lastRequestUs = sourceUs;
            if (!started && !openOnGlThread()) { degraded = true; return false; }
            if (streamEnded) {
                // No more frames will arrive; last frame remains current.
                return currentPtsUs != Long.MIN_VALUE;
            }
            return decodeForwardTo(sourceUs);
        } catch (Exception e) {
            FLog.w(TAG, "Surface read failed for " + uri + "; degrading", e);
            degraded = true;
            releaseDecoder();
            return false;
        }
    }

    private boolean openOnGlThread() {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { track = i; break; }
            }
            if (track < 0) {
                FLog.w(TAG, "No video track in " + uri);
                try { ex.release(); } catch (Exception ignored) {}
                return false;
            }
            ex.selectTrack(track);
            MediaFormat format = ex.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) { try { ex.release(); } catch (Exception ignored) {} return false; }
            rotationDeg = format.containsKey(MediaFormat.KEY_ROTATION) ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            sourceW = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
            sourceH = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            boolean quarterTurn = ((rotationDeg % 360) + 360) % 360 % 180 == 90;
            displayW = quarterTurn ? sourceH : sourceW;
            displayH = quarterTurn ? sourceW : sourceH;

            // 6.1 fix r_frame_rate 90000
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int rate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (rate >= 90000 || rate <= 0) {
                    // Strip the lie; decoder will be told 30. Preview decodes these files on hardware, so 30 is safe.
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                    FLog.d(TAG, "Clamped declared frame rate " + rate + " -> 30 for " + uri.getLastPathSegment());
                }
            }
            if (format.containsKey("capture-rate")) {
                try {
                    int cr = format.getInteger("capture-rate");
                    if (cr >= 90000) format.setInteger("capture-rate", 30);
                } catch (Exception ignored) {}
            }

            // OES texture for the decoder
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            oesTexId = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            if (oesTexId == 0) { try { ex.release(); } catch (Exception ignored) {} return false; }

            SurfaceTexture st = new SurfaceTexture(oesTexId);
            surfaceTexture = st;
            Surface s = new Surface(st);
            surface = s;

            // Prefer hardware decoder: same preference as ExportManager.hardwareFirstAssetLoaderFactory,
            // but for manual MediaCodec we pick explicitly.
            MediaCodec c = null;
            try {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                MediaCodecInfo[] infos = list.getCodecInfos();
                String hwName = null;
                for (MediaCodecInfo info : infos) {
                    if (!info.isEncoder()) {
                        boolean hw = info.isHardwareAccelerated();
                        if (hw) {
                            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) { hwName = info.getName(); break; }
                            if (hwName != null) break;
                        }
                    }
                }
                if (hwName != null) {
                    try { c = MediaCodec.createByCodecName(hwName); } catch (Exception e) { FLog.w(TAG, "HW codec " + hwName + " failed, trying default", e); }
                }
                if (c == null) c = MediaCodec.createDecoderByType(mime);
            } catch (Exception e) {
                c = MediaCodec.createDecoderByType(mime);
            }
            c.configure(format, surface, null, 0);
            c.start();
            extractor = ex;
            codec = c;
            started = true;
            inputDone = false;
            streamEnded = false;
            // identity until first frame
            for (int i = 0; i < 16; i++) texMatrix[i] = (i % 5 == 0) ? 1f : 0f;
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "Could not open " + uri + " for surface reading", e);
            try { ex.release(); } catch (Exception ignored) {}
            if (oesTexId != 0) {
                int[] tex = new int[]{oesTexId};
                try { GLES20.glDeleteTextures(1, tex, 0); } catch (Exception ignored) {}
                oesTexId = 0;
            }
            if (surface != null) { try { surface.release(); } catch (Exception ignored) {} surface = null; }
            if (surfaceTexture != null) { try { surfaceTexture.release(); } catch (Exception ignored) {} surfaceTexture = null; }
            return false;
        }
    }

    private boolean decodeForwardTo(long targetUs) {
        MediaCodec c = codec;
        MediaExtractor ex = extractor;
        SurfaceTexture st = surfaceTexture;
        if (c == null || ex == null || st == null) return false;

        if (!triedInitialSeek) {
            triedInitialSeek = true;
            if (targetUs > SEEK_WORTH_IT_US && !isFragmented()) {
                try {
                    ex.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    long landed = ex.getSampleTime();
                    if (landed < 0 || landed > targetUs) {
                        FLog.d(TAG, "Initial seek landed at " + landed + " for target " + targetUs + "; decoding from start");
                        releaseDecoder();
                        triedInitialSeek = true;
                        if (!openOnGlThread()) return false;
                        c = codec; ex = extractor; st = surfaceTexture;
                        if (c == null || ex == null || st == null) return false;
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Initial seek failed; decoding from start", e);
                }
            }
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int stalled = 0;
        final long pumpStart = System.nanoTime();
        while (true) {
            if (!inputDone) {
                int inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer ib = c.getInputBuffer(inIdx);
                    int size = (ib == null) ? -1 : ex.readSampleData(ib, 0);
                    if (size < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            if (outIdx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean isFrame = info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                if (isFrame) decodedFrames++;
                if (isFrame && info.presentationTimeUs >= targetUs) {
                    c.releaseOutputBuffer(outIdx, true);
                    // SurfaceTexture now has the frame; make it current
                    try {
                        st.updateTexImage();
                        st.getTransformMatrix(texMatrix);
                        texMatrixValid = true;
                    } catch (Exception e) {
                        FLog.w(TAG, "updateTexImage failed", e);
                        return false;
                    }
                    pumpNanos += System.nanoTime() - pumpStart;
                    currentPtsUs = info.presentationTimeUs;
                    return true;
                } else {
                    // Not yet the target — render to surface but don't expose? For surface,
                    // every render advances the texture, but we only want target. However we
                    // must still render to advance; the non-target frames will be overwritten.
                    // So we still render and update, but keep pumping until target.
                    if (isFrame) {
                        c.releaseOutputBuffer(outIdx, true);
                        try {
                            st.updateTexImage();
                            st.getTransformMatrix(texMatrix);
                            texMatrixValid = true;
                            currentPtsUs = info.presentationTimeUs;
                        } catch (Exception ignored) {}
                    } else {
                        c.releaseOutputBuffer(outIdx, false);
                    }
                }
                stalled = 0;
                if (eos) {
                    pumpNanos += System.nanoTime() - pumpStart;
                    streamEnded = true;
                    return currentPtsUs != Long.MIN_VALUE;
                }
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone && ++stalled > MAX_STALLED_DRAINS) {
                    FLog.w(TAG, "Decoder stalled after EOS for " + uri.getLastPathSegment());
                    pumpNanos += System.nanoTime() - pumpStart;
                    streamEnded = true;
                    return currentPtsUs != Long.MIN_VALUE;
                }
            }
        }
    }

    private boolean isFragmented() {
        if (fragmented != null) return fragmented;
        boolean result = true;
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] buf = new byte[65536];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) break;
                    n += r;
                }
                result = false;
                for (int i = 0; i + 4 <= n; i++) {
                    if (buf[i]=='m' && buf[i+1]=='o' && buf[i+2]=='o' && buf[i+3]=='f') { result = true; break; }
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not sniff container for " + uri + "; assuming fragmented", e);
        }
        fragmented = result;
        return result;
    }

    private void releaseDecoder() {
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) {}
            extractor = null;
        }
        if (surface != null) { try { surface.release(); } catch (Exception ignored) {} surface = null; }
        if (surfaceTexture != null) { try { surfaceTexture.release(); } catch (Exception ignored) {} surfaceTexture = null; }
        if (oesTexId != 0) {
            int[] tex = new int[]{oesTexId};
            try { GLES20.glDeleteTextures(1, tex, 0); } catch (Exception ignored) {}
            oesTexId = 0;
        }
        texMatrixValid = false;
        currentPtsUs = Long.MIN_VALUE;
        triedInitialSeek = false;
        sourceW = sourceH = displayW = displayH = 0;
        started = false;
        inputDone = false;
        streamEnded = false;
    }

    void release() {
        if (decodedFrames > 0) {
            FLog.i(TAG, "GL_FRAMES " + uri.getLastPathSegment()
                    + " decoded=" + decodedFrames
                    + " rewinds=" + rewinds
                    + " pumpMs=" + (pumpNanos / 1_000_000L)
                    + (degraded ? " DEGRADED" : ""));
        }
        releaseDecoder();
    }
}
