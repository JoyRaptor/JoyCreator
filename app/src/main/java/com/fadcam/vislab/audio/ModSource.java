package com.fadcam.vislab.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A tracker module (.mod) as a sound source (roadmap R13): the demoscene beats the owner likes.
 * {@link ModPlayer} plays it through an AudioTrack and into the {@link PcmRing}, so the ear hears
 * it like any song; on top of that its notes, judged into kicks, snares and hats by their sound
 * and written as MIDI ({@link ModMidi}), give the same exact hits a MIDI file gets
 * ({@link MidiFeatures}), read at the frame the speaker is playing. Loops like the demo song.
 */
public final class ModSource implements SoundSource {

    private static final String TAG = "VisMod";
    private static final int RATE = 44100;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int BLOCK = 1024;
    /** How much of bass..air comes from the notes (the rest is the module's own sound, analysed). */
    private static final double EXACT_SHARE = 0.6;

    private final PcmRing ring;
    private final Uri uri;
    private volatile boolean running, paused;
    private Thread thread;
    private volatile AudioTrack track;
    private volatile long ringBase;
    private volatile long loopFrames;
    @Nullable private volatile MidiFeatures exact;
    /** At least one sample was judged a kick; else the ear's kicks are kept (see refine). */
    private volatile boolean hasKick;
    private ModPlayer player;

    public ModSource(@NonNull PcmRing ring, @NonNull Uri uri) {
        this.ring = ring;
        this.uri = uri;
    }

    /** True when the file behind {@code uri} is a module (by its channel tag, not its name). */
    public static boolean isMod(@NonNull Context c, @NonNull Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[1084];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            return ModFile.looksLikeMod(head, n);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean start(@NonNull Context context) {
        if (running) return true;
        ModFile mod;
        try {
            mod = ModFile.parse(read(context.getApplicationContext(), uri));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "module unreadable", e);
            return false;
        }
        ModPlayer.Pass pass = ModPlayer.dryRun(mod);
        if (pass.events.isEmpty() || pass.seconds <= 0) return false;   // nothing to play
        int[] judged = ModMidi.judge(mod, pass.events);
        hasKick = ModMidi.hasKick(judged);
        try {
            exact = new MidiFeatures(MidiFile.parse(ModMidi.toMidi(pass.events, pass.seconds,
                    ModMidi.secondsPerBeat(pass.speed, pass.tempo), judged)), pass.seconds, EXACT_SHARE);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "no exact hits for this module; the ear alone hears it", e);
            exact = null;
        }
        AudioTrack t = openTrack();
        if (t == null) return false;
        loopFrames = Math.round(pass.seconds * RATE);
        player = new ModPlayer(mod, RATE);
        // A loud module would clip: measure its first 20 s and trim to just under full scale.
        player.setLevel(ModPlayer.loudness(mod, Math.min(20, pass.seconds)));
        ring.setFormat(RATE, false);
        ringBase = ring.framesWritten();
        track = t;
        running = true;
        thread = new Thread(this::loop, "vislab-mod");
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
                // released under us: the loop is ending anyway
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
    public void refine(@NonNull Features f, long playheadFrame, double dt) {
        MidiFeatures x = exact;
        long loop = loopFrames;
        long rel = playheadFrame - ringBase;
        if (x == null || loop <= 0 || rel < 0) return;
        if (hasKick) {
            x.apply(f, (rel % loop) / (double) RATE, dt);
            return;
        }
        // No sample judged a kick (a chip beat whose kick is a pitched wave): the exact kicks
        // would all be zero, so the kicks the ear heard are kept; snares, hats and beat are exact.
        float kick = f.kick, punch = f.punch;
        int shockCount = f.shockCount;
        float[] shocks = f.shocks.clone();
        x.apply(f, (rel % loop) / (double) RATE, dt);
        f.kick = kick;
        f.punch = punch;
        f.shockCount = shockCount;
        System.arraycopy(shocks, 0, f.shocks, 0, shocks.length);
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
        short[] pcm = new short[BLOCK];
        try {
            while (running) {
                player.render(pcm, 0, BLOCK);
                writeOut(pcm, BLOCK);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "module stopped", e);
        } finally {
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

    @Nullable
    private AudioTrack openTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, RATE / 8 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (!paused) t.play();
            return t;
        } catch (RuntimeException e) {
            Log.w(TAG, "no audio track", e);
            return null;
        }
    }

    private void writeOut(short[] pcm, int frames) {
        int done = 0;
        while (running && done < frames) {
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
            int w = t.write(pcm, done, frames - done, AudioTrack.WRITE_BLOCKING);
            if (w <= 0) return;
            done += w;
        }
        // The ring after the track took the samples, so ring frame and track frame stay in step.
        if (done == frames) ring.write16(pcm, 0, frames, 1);
    }

    private static byte[] read(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open");
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("file too big for a module");
            }
            return out.toByteArray();
        }
    }
}
