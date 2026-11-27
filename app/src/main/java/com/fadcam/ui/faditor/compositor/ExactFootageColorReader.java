package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Exact-only color donor decoding. The caller owns the current GL context. */
final class ExactFootageColorReader {
    private static final long DEQUEUE_US = 10_000;
    private static final long LATCH_MS = 250;
    private static final long DECODE_NS = TimeUnit.SECONDS.toNanos(2);

    interface Staging {
        void stage(long latchedPtsUs, int oesTexture, float[] transform) throws Exception;
    }

    private ExactFootageColorReader() {}

    static void read(Context context, Uri uri, int width, int height, long previousUs, long nextUs,
                     BooleanSupplier cancelled, Staging staging) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat format = selectVideo(extractor);
            if (format.getInteger(MediaFormat.KEY_WIDTH) != width
                    || format.getInteger(MediaFormat.KEY_HEIGHT) != height
                    || (format.containsKey(MediaFormat.KEY_ROTATION)
                    && Math.floorMod(format.getInteger(MediaFormat.KEY_ROTATION), 360) != 0)) {
                throw new IOException("Donor geometry differs from the preview decoder");
            }
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)
                    && format.getInteger(MediaFormat.KEY_FRAME_RATE) >= 90_000) {
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            }
            extractor.seekTo(previousUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (extractor.getSampleTime() < 0 || extractor.getSampleTime() > previousUs) {
                throw new IOException("No donor sync sample");
            }
            if (cancelled.getAsBoolean()) return;
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            try {
                int target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                GLES20.glBindTexture(target, texture[0]);
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                SurfaceTexture input = new SurfaceTexture(texture[0]);
                try {
                    Surface surface = new Surface(input);
                    try {
                        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                        try {
                            decoder.configure(format, surface, null, 0);
                            decoder.start();
                            pump(extractor, decoder, input, texture[0], previousUs, nextUs, cancelled, staging);
                        } finally {
                            decoder.release();
                        }
                    } finally {
                        surface.release();
                    }
                } finally {
                    input.release();
                }
            } finally {
                GLES20.glDeleteTextures(1, texture, 0);
            }
        } finally {
            extractor.release();
        }
    }

    private static MediaFormat selectVideo(MediaExtractor extractor) throws IOException {
        MediaFormat video = null;
        for (int track = 0; track < extractor.getTrackCount(); track++) {
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                if (video != null) throw new IOException("Could not identify the preview video track");
                extractor.selectTrack(track);
                video = format;
            }
        }
        if (video == null) throw new IOException("No video track for color donors");
        return video;
    }

    private static void pump(MediaExtractor extractor, MediaCodec decoder, SurfaceTexture texture, int oes,
                             long previousUs, long nextUs, BooleanSupplier cancelled, Staging staging)
            throws Exception {
        MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
        Handler callbacks = new Handler(Looper.getMainLooper());
        boolean inputEnded = false;
        long targetPtsUs = previousUs;
        long started = System.nanoTime();
        while (!cancelled.getAsBoolean() && System.nanoTime() - started < DECODE_NS) {
            if (!inputEnded) {
                int input = decoder.dequeueInputBuffer(DEQUEUE_US);
                if (input >= 0) {
                    var bytes = decoder.getInputBuffer(input);
                    int size = bytes == null ? -1 : extractor.readSampleData(bytes, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEnded = true;
                    } else {
                        decoder.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int index = decoder.dequeueOutputBuffer(output, DEQUEUE_US);
            if (index < 0) continue;
            boolean frame = output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
            if (frame && output.presentationTimeUs == targetPtsUs) {
                long latchedPtsUs = latch(decoder, index, targetPtsUs, texture, callbacks);
                if (cancelled.getAsBoolean()) return;
                float[] transform = new float[16];
                texture.getTransformMatrix(transform);
                staging.stage(latchedPtsUs, oes, transform);
                if (targetPtsUs == nextUs || nextUs == Long.MIN_VALUE) return;
                targetPtsUs = nextUs;
            } else {
                decoder.releaseOutputBuffer(index, false);
            }
            if ((output.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
        if (!cancelled.getAsBoolean()) throw new IOException("Exact color donor unavailable");
    }

    /** The render timestamp carries this decoder output's PTS through the texture queue. */
    private static long latch(MediaCodec decoder, int index, long ptsUs, SurfaceTexture texture,
                              Handler handler) throws IOException, InterruptedException {
        var ready = new CountDownLatch(1);
        texture.setOnFrameAvailableListener(ignored -> ready.countDown(), handler);
        try {
            long timestampNs = Math.multiplyExact(ptsUs, 1000L);
            decoder.releaseOutputBuffer(index, timestampNs);
            if (!ready.await(LATCH_MS, TimeUnit.MILLISECONDS)) throw new IOException("Donor texture timed out");
            texture.updateTexImage();
            if (texture.getTimestamp() != timestampNs) throw new IOException("Wrong donor texture timestamp");
            return texture.getTimestamp() / 1000L;
        } finally {
            texture.setOnFrameAvailableListener(null);
        }
    }
}
