package com.fadcam.vislab.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Plays a song (a file the person picked, or the demo song in the APK) and feeds the same
 * samples to a {@link PcmRing}. The ring runs ahead of the speaker by the audio buffer, so the
 * picture reads the ring at {@link #playheadFrame()}, the frame the speaker is playing now:
 * kicks land on screen when they land in the ear.
 */
public final class SongPlayer implements SoundSource {

    private static final String TAG = "VisSong";

    /** Points a MediaExtractor at the song. */
    public interface Opener {
        void open(@NonNull Context c, @NonNull MediaExtractor ex) throws IOException;
    }

    private final PcmRing ring;
    private final Opener opener;
    private volatile boolean running, paused;
    private volatile boolean looping = true;
    private Thread thread;
    private volatile AudioTrack track;
    private volatile long ringBase;
    private volatile long durationUs;
    private Context appContext;

    public SongPlayer(@NonNull PcmRing ring, @NonNull Opener opener) {
        this.ring = ring;
        this.opener = opener;
    }

    @NonNull
    public static SongPlayer ofUri(@NonNull PcmRing ring, @NonNull Uri uri) {
        return new SongPlayer(ring, (c, ex) -> ex.setDataSource(c, uri, null));
    }

    /** An uncompressed-in-APK asset (.m4a/.ogg/.mp3 are stored uncompressed by default). */
    @NonNull
    public static SongPlayer ofAsset(@NonNull PcmRing ring, @NonNull String assetPath) {
        return new SongPlayer(ring, (c, ex) -> {
            try (AssetFileDescriptor fd = c.getAssets().openFd(assetPath)) {
                ex.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
        });
    }

    public void setLooping(boolean loop) {
        looping = loop;
    }

    public float durationSec() {
        return durationUs / 1e6f;
    }

    @Override
    public boolean start(@NonNull Context context) {
        if (running) return true;
        appContext = context.getApplicationContext();
        running = true;
        thread = new Thread(this::loop, "vislab-song");
        thread.start();
        return true;
    }

    @Override
    public void setPaused(boolean p) {
        paused = p;
        AudioTrack t = track;
        if (t != null) {
            try {
                if (p) t.pause();
                else t.play();
            } catch (IllegalStateException ignored) {
                // track released under us: the loop is ending anyway
            }
        }
        synchronized (this) {
            notifyAll();
        }
    }

    @Override
    public long playheadFrame() {
        AudioTrack t = track;
        if (t == null) return -1;
        try {
            return ringBase + (t.getPlaybackHeadPosition() & 0xFFFFFFFFL);
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    @Override
    public void stop() {
        running = false;
        synchronized (this) {
            notifyAll();
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (IllegalStateException ignored) {
                // fine
            }
        }
        Thread th = thread;
        thread = null;
        if (th != null) {
            try {
                th.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            opener.open(appContext, ex);
            int ti = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ti = i;
                    fmt = f;
                    break;
                }
            }
            if (ti < 0) throw new IOException("no audio track");
            ex.selectTrack(ti);
            durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            openTrack(rate, channels);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pcm = new short[0];
            boolean inputDone = false;
            while (running) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(5000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 5000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int r2 = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int c2 = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (r2 != rate || c2 != channels) {
                        rate = r2;
                        channels = c2;
                        openTrack(rate, channels);
                    }
                } else if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int n = sb.remaining();
                        if (pcm.length < n) pcm = new short[n];
                        sb.get(pcm, 0, n);
                        writeOut(pcm, n, channels);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!looping) break;
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        inputDone = false;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "song stopped", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // already stopped
                }
                codec.release();
            }
            ex.release();
            AudioTrack t = track;
            track = null;
            if (t != null) {
                try {
                    t.stop();
                } catch (IllegalStateException ignored) {
                    // fine
                }
                t.release();
            }
            running = false;
        }
    }

    private void openTrack(int rate, int channels) {
        AudioTrack old = track;
        if (old != null) {
            old.release();
        }
        int mask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, rate / 8 * 2 * Math.min(2, channels)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        ring.setFormat(rate, channels >= 2);
        ringBase = ring.framesWritten();
        track = t;
        if (!paused) t.play();
    }

    private void writeOut(short[] pcm, int samples, int channels) {
        int frames = samples / Math.max(1, channels);
        int done = 0;
        while (running && done < samples) {
            synchronized (this) {
                while (paused && running) {
                    try {
                        wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            AudioTrack t = track;
            if (t == null) return;
            int w = t.write(pcm, done, samples - done, AudioTrack.WRITE_BLOCKING);
            if (w <= 0) return;
            done += w;
        }
        // Write to the ring AFTER the track accepted the samples, so ring frame index and
        // track frame index stay in lock step (both count from ringBase).
        ring.write16(pcm, 0, frames, Math.min(2, channels));
    }
}
