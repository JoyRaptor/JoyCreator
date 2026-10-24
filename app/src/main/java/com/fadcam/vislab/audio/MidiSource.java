package com.fadcam.vislab.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.words.WordFeed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A MIDI file (.mid, .midi, .kar) as a sound source. It is played by the app's own tiny synth
 * ({@link MidiSynth}) through an AudioTrack, and the same samples go into the {@link PcmRing}, so
 * the ear (LiveAnalyzer) hears it like any song and the spectrum, wave and level are real. On top
 * of that, {@link #refine} overwrites what the ear had to guess (kick, snare, hat, beat, bar,
 * tempo, and the band loudness it blends) with what the file says, read at the exact frame the
 * speaker is playing: every kick lands on the beat, a velocity-30 ghost note is weaker than a
 * velocity-120 accent.
 *
 * <p>Why the app's own synth and not Android's: the built-in MIDI player (MediaPlayer) sends the sound
 * straight to the speaker, so there are no samples to analyse and no playhead to read events
 * against, and tapping it needs the microphone permission. The app's synth gives both for nothing,
 * on every phone, and every note starts on its exact sample.</p>
 *
 * <p>Loops: a MIDI file is short, so it repeats from its first event when it ends, like the demo
 * song does. Lyrics (.kar) come out as {@link #words()} for a Words layer, timed to
 * {@link #songSeconds()}.</p>
 */
public final class MidiSource implements SoundSource {

    private static final String TAG = "VisMidi";
    private static final int RATE = 44100;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int BLOCK = 1024;
    /** How much of bass..air comes from the notes (the rest is the synth's own sound, analysed). */
    private static final double EXACT_SHARE = 0.7;

    private final PcmRing ring;
    @Nullable private final Uri uri;
    @Nullable private MidiFile midi;
    private volatile boolean running, paused;
    private Thread thread;
    private volatile AudioTrack track;
    private volatile long ringBase;
    private volatile long loopFrames;
    private volatile MidiFeatures exact;
    private MidiSynth synth;
    private Context appContext;

    /** A MIDI file the person picked. */
    public MidiSource(@NonNull PcmRing ring, @NonNull Uri uri) {
        this.ring = ring;
        this.uri = uri;
    }

    /** A MIDI file already read. */
    public MidiSource(@NonNull PcmRing ring, @NonNull MidiFile midi) {
        this.ring = ring;
        this.uri = null;
        this.midi = midi;
    }

    /** True when the file behind {@code uri} starts like a MIDI file (by its bytes, not its name). */
    public static boolean isMidi(@NonNull Context c, @NonNull Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[12];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            return MidiFile.looksLikeMidi(head, n);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ what the file holds

    /** The lyric words with their times (empty when the file has none). Valid after a successful {@link #start}. */
    @NonNull
    public List<WordFeed.Word> words() {
        List<WordFeed.Word> out = new ArrayList<>();
        MidiFile m = midi;
        if (m != null) for (MidiFile.Word w : m.words()) out.add(new WordFeed.Word(w.text, w.start, w.end));
        return out;
    }

    /** Seconds in one pass of the loop (the period for the words). */
    public double loopSeconds() {
        return loopFrames / (double) RATE;
    }

    /** Seconds the music has played since it started, not wrapped at the loop: NaN when not playing. */
    public double songSeconds() {
        long head = playheadFrame();
        return head < 0 ? Double.NaN : (head - ringBase) / (double) RATE;
    }

    // ------------------------------------------------------------------ SoundSource

    @Override
    public boolean start(@NonNull Context context) {
        if (running) return true;
        appContext = context.getApplicationContext();
        try {
            if (midi == null) midi = MidiFile.parse(read(appContext, uri));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "midi unreadable", e);
            return false;
        }
        MidiFile m = midi;
        if (m.notes().isEmpty()) return false;               // nothing to play
        long frames = Math.max(RATE / 2, Math.round(m.lengthSec() * RATE));
        AudioTrack t = openTrack();
        if (t == null) return false;
        loopFrames = frames;
        synth = new MidiSynth(m, RATE, frames);
        exact = new MidiFeatures(m, frames / (double) RATE, EXACT_SHARE);
        ring.setFormat(RATE, false);
        ringBase = ring.framesWritten();
        track = t;
        running = true;
        thread = new Thread(this::loop, "vislab-midi");
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
        x.apply(f, (rel % loop) / (double) RATE, dt);
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

    // ------------------------------------------------------------------ playing

    private void loop() {
        short[] pcm = new short[BLOCK];
        try {
            while (running) {
                synth.render(pcm, 0, BLOCK);
                writeOut(pcm, BLOCK);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "midi stopped", e);
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
        // The ring after the track took the samples: ring frame and track frame stay in step
        // (both count from ringBase), as SongPlayer does it.
        if (done == frames) ring.write16(pcm, 0, frames, 1);
    }

    private static byte[] read(Context c, @Nullable Uri uri) throws IOException {
        if (uri == null) throw new IOException("no file");
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open");
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("file too big for a MIDI file");
            }
            return out.toByteArray();
        }
    }
}
