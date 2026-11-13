package cc.joycreator.textlab;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A look as an MP4 (TextLab's quick export): H.264, drawn straight into the encoder's own surface on the
 * export's GL thread, so no frame is ever copied through the CPU. Silent; the person adds sound in Studio.
 */
final class LookVideo {

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    /** EGL_RECORDABLE_ANDROID: a config whose window surfaces an encoder can take. */
    static final int EGL_RECORDABLE_ANDROID = 0x3142;

    /** Draws frame {@code i} into the bound framebuffer 0, {@code w} x {@code h}. */
    interface Frames {
        void draw(int i, int w, int h);
    }

    /** True when the stop was asked for. */
    interface Stop {
        boolean now();
    }

    interface Progress {
        void at(float done);
    }

    private LookVideo() {}

    /** Why this phone's encoder will not take this size, or null when it will. */
    @Nullable
    static String refuses(int w, int h, int fps) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(MIME)) continue;
                    MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType(MIME).getVideoCapabilities();
                    if (v != null && v.areSizeAndRateSupported(w, h, fps)) return null;
                }
            }
        } catch (RuntimeException e) {
            return null;   // cannot tell: try it, and say so if it fails
        }
        return "This phone cannot make a video " + w + " × " + h + ". Try a smaller size.";
    }

    /**
     * Encode {@code count} frames at {@code fps} into {@code out}. The GL thread's context ({@code ctx} on
     * {@code display}, made with {@code cfg}) is made current on the encoder's surface for the frames and on
     * {@code idle} afterwards.
     */
    static void encode(@NonNull EGLDisplay display, @NonNull EGLConfig cfg, @NonNull EGLContext ctx,
                       @NonNull EGLSurface idle, int w, int h, int fps, int count, @NonNull File out,
                       @NonNull Frames frames, @NonNull Stop stop, @NonNull Progress progress) throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // generous for moving fire and grain: about 7.5 Mbit/s at 1080p, 30 at 4K
        f.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(60_000_000L, (long) w * h * fps / 8));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaMuxer mux = null;
        Surface input = null;
        EGLSurface win = EGL14.EGL_NO_SURFACE;
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start();
            win = EGL14.eglCreateWindowSurface(display, cfg, input, new int[]{EGL14.EGL_NONE}, 0);
            if (win == EGL14.EGL_NO_SURFACE) throw new IOException("the encoder's surface could not be drawn on");
            if (!EGL14.eglMakeCurrent(display, win, win, ctx)) throw new IOException("eglMakeCurrent on the encoder");
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Drain d = new Drain(codec, mux);
            for (int i = 0; i < count; i++) {
                if (stop.now()) throw new LookExport.Stopped();
                frames.draw(i, w, h);
                EGLExt.eglPresentationTimeANDROID(display, win, i * 1_000_000_000L / fps);
                EGL14.eglSwapBuffers(display, win);
                d.drain(false);
                progress.at((i + 1f) / count);
            }
            codec.signalEndOfInputStream();
            d.drain(true);
        } finally {
            EGL14.eglMakeCurrent(display, idle, idle, ctx);
            if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, win);
            try { codec.stop(); } catch (RuntimeException ignored) { }
            codec.release();
            if (input != null) input.release();
            if (mux != null) {
                try { mux.release(); } catch (RuntimeException ignored) { }   // never started: nothing was written
            }
        }
    }

    /** The encoder's output into the muxer. */
    private static final class Drain {
        final MediaCodec codec;
        final MediaMuxer mux;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;

        Drain(MediaCodec codec, MediaMuxer mux) {
            this.codec = codec;
            this.mux = mux;
        }

        void drain(boolean toEnd) throws IOException {
            long waitedUs = 0;
            while (true) {
                int i = codec.dequeueOutputBuffer(info, 10_000);
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!toEnd) return;
                    waitedUs += 10_000;
                    if (waitedUs > 5_000_000) throw new IOException("the encoder did not finish");
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat());
                    mux.start();
                } else if (i >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(i);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (b != null && info.size > 0 && !config && track >= 0) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        mux.writeSampleData(track, b, info);
                    }
                    codec.releaseOutputBuffer(i, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        mux.stop();
                        return;
                    }
                }
            }
        }
    }

    /** Copy the renderer's finished picture (pw x ph) onto the bound surface (w x h), stretched to fill it. */
    static void blit(int fromFbo, int pw, int ph, int w, int h) {
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fromFbo);
        GLES30.glBlitFramebuffer(0, 0, pw, ph, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }
}
