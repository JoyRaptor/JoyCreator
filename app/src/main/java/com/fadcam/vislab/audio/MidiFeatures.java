package com.fadcam.vislab.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * What a MIDI file says the music is doing at one moment, written over the {@link Features} the
 * ear (FeatureExtractor) guessed from the sound. Where the ear has to work out that a kick
 * happened, a snare, the beat, the bar, here it is simply read: a kick is a General MIDI drum
 * note 35 / 36 on channel 10 and it lands at its exact time, how hard it was hit sets how strong
 * it is, and the beat and bar come from the file's tempo map and time signatures.
 *
 * <p>Everything is a plain function of the time in the song (looping every {@code period}
 * seconds), so scrubbing back or looping never leaves a stale value. Used from one thread
 * (the render thread); not thread-safe.</p>
 *
 * <ul>
 *   <li><b>Replaced with the exact value:</b> kick, snare, hat, punch, shocks, beat, beatPhase,
 *       barPhase, bpm. Same shapes the ear gives: a hit is its strength on the frame it lands and
 *       fades after (kick 0.16 s, snare 0.13 s, hat 0.08 s), beat is 1 on the beat and fades 0.12 s.</li>
 *   <li><b>Blended:</b> bass, low, mid, high, air. Each note sounding adds its loudness to the band
 *       its pitch is in (the ear's own bands: below 150 Hz, 150-400, 400-2000, 2000-6000, above),
 *       weighted by how hard it was hit, and a harder note is brighter: it also lights the band
 *       above. Drums light the bands they live in (kick: bass; snare: mid and high; hats and
 *       cymbals: air and high; toms: bass and low). The blend keeps the analysed value for
 *       {@code 1 - exactShare}, so the picture still has the sound's own texture.</li>
 *   <li><b>Left to the ear:</b> level, voice, bright, energy, bassAcc, levelAcc, the spectrum and wave.</li>
 * </ul>
 */
final class MidiFeatures {

    private static final int KICK = 0, SNARE = 1, HAT = 2, TOM_LOW = 3, TOM_HIGH = 4;
    private static final double KICK_TAU = 0.16, SNARE_TAU = 0.13, HAT_TAU = 0.08, TOM_TAU = 0.2;
    private static final double BEAT_TAU = 0.12;
    /** A note rings at full, settles to this while held, and fades after the key is let go. */
    private static final double SUSTAIN = 0.55, SETTLE_TAU = 0.5, RELEASE_TAU = 0.12, ATTACK = 0.006;
    private static final double RELEASE_WINDOW = 0.8;     // after this, a released note is silence
    private static final double BRIGHTER = 0.4;           // how much of a hard note spills into the band above
    private static final double[] EDGES_HZ = {150, 400, 2000, 6000};
    private static final int[] BAND_OF_NOTE = new int[128];

    static {
        for (int n = 0; n < 128; n++) {
            double hz = 440.0 * Math.pow(2, (n - 69) / 12.0);
            int b = 0;
            while (b < EDGES_HZ.length && hz >= EDGES_HZ[b]) b++;
            BAND_OF_NOTE[n] = b;
        }
    }

    /** The hits of one drum class, in time order, each with a strength 0..1. */
    private static final class Hits {
        final double[] t;
        final float[] v;

        Hits(List<MidiFile.Note> list) {
            t = new double[list.size()];
            v = new float[t.length];
            for (int i = 0; i < t.length; i++) {
                t[i] = list.get(i).start;
                // a ghost note is felt, an accent is full: 0.25 .. 1
                v[i] = (float) (0.25 + 0.75 * list.get(i).velocity / 127.0);
            }
        }

        /** Index of the last hit at or before {@code time}, or -1. */
        int atOrBefore(double time) {
            int lo = 0, hi = t.length - 1, r = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (t[mid] <= time) {
                    r = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return r;
        }
    }

    private final MidiFile midi;
    private final double period;
    private final double exactShare;
    private final Hits[] drums = new Hits[5];
    private final MidiFile.Note[] tones;
    private final List<MidiFile.Note> active = new ArrayList<>();
    private int cursor;
    private double lastT = Double.NaN;
    private final double[] sum = new double[5];

    /**
     * @param period     the loop length in seconds (what the player repeats every)
     * @param exactShare how much of bass..air comes from the notes (0..1); 1 when there is no
     *                   sound to blend with
     */
    MidiFeatures(MidiFile midi, double period, double exactShare) {
        this.midi = midi;
        this.period = Math.max(period, 0.05);
        this.exactShare = Math.max(0, Math.min(1, exactShare));
        List<List<MidiFile.Note>> byClass = new ArrayList<>();
        for (int i = 0; i < drums.length; i++) byClass.add(new ArrayList<>());
        List<MidiFile.Note> pitched = new ArrayList<>();
        for (MidiFile.Note n : midi.notes()) {
            if (n.start >= this.period) continue;
            if (!n.isDrum()) {
                pitched.add(n);
                continue;
            }
            int c = drumClass(n.note);
            if (c >= 0) byClass.get(c).add(n);
        }
        for (int i = 0; i < drums.length; i++) drums[i] = new Hits(byClass.get(i));
        tones = pitched.toArray(new MidiFile.Note[0]);
    }

    /**
     * General MIDI drum map. Beyond the kick (35, 36), snare (38, 40) and hats (42, 44, 46) the
     * brief names: side stick and clap count as snare (they fill its place), cymbals as hats (the
     * ear hears them in the same top band), toms feed the bass and low bands only.
     */
    static int drumClass(int note) {
        switch (note) {
            case 35: case 36:
                return KICK;
            case 37: case 38: case 39: case 40:
                return SNARE;
            case 42: case 44: case 46: case 49: case 51: case 52: case 53: case 55: case 57: case 59:
                return HAT;
            case 41: case 43:
                return TOM_LOW;
            case 45: case 47: case 48: case 50:
                return TOM_HIGH;
            default:
                return -1;
        }
    }

    /**
     * Overwrite {@code f} with what the file says at song time {@code t} (seconds into the loop).
     * Call once per frame with increasing {@code t}; {@code dt} is the frame length, used only to
     * decide which hits count as landing on this frame.
     */
    void apply(Features f, double t, double dt) {
        // Hits that land on this frame: after the last frame's time, up to this one. A loop wrap
        // looks at the end of the last pass and the start of this one; the very first frame
        // looks one frame back (and includes a hit at exactly 0).
        double punch;
        if (Double.isNaN(lastT)) {
            punch = landed(drums[KICK], t - dt <= 0 ? -1e-9 : t - dt, t);
        } else if (t >= lastT) {
            punch = landed(drums[KICK], lastT, t);
        } else {
            punch = Math.max(landed(drums[KICK], lastT, period), landed(drums[KICK], -1e-9, t));
        }
        boolean reset = Double.isNaN(lastT) || t < lastT - 1e-9;
        lastT = t;

        double kick = ring(drums[KICK], t, KICK_TAU);
        double snare = ring(drums[SNARE], t, SNARE_TAU);
        double hat = ring(drums[HAT], t, HAT_TAU);
        f.kick = (float) kick;
        f.snare = (float) snare;
        f.hat = (float) hat;
        f.punch = (float) punch;
        shocks(f, t);

        // Beat and bar from the tempo map. The small push keeps a beat that lands exactly on t
        // from reading as the end of the one before.
        double q = midi.quartersAt(t);
        double beat = Math.floor(q + 1e-9);
        double phase = Math.max(0, Math.min(0.999999, q - beat));
        f.beatPhase = (float) phase;
        f.beat = (float) Math.exp(-Math.max(0, t - midi.secondsAtQuarters(beat)) / BEAT_TAU);
        MidiFile.Meter m = midi.meterAtQuarters(q);
        double bars = (q - m.quarters) / m.barQuarters();
        f.barPhase = (float) Math.max(0, Math.min(0.999999, bars - Math.floor(bars + 1e-9)));
        f.bpm = (float) midi.bpmAt(t);

        // Bands: notes sounding, drums ringing.
        java.util.Arrays.fill(sum, 0);
        notes(t, reset);
        addDrum(drums[KICK], t, KICK_TAU, 0, 1.0, 1, 0.25);
        addDrum(drums[SNARE], t, SNARE_TAU, 2, 0.8, 3, 0.6);
        addDrum(drums[HAT], t, HAT_TAU, 4, 0.9, 3, 0.4);
        addDrum(drums[TOM_LOW], t, TOM_TAU, 0, 0.8, 1, 0.3);
        addDrum(drums[TOM_HIGH], t, TOM_TAU, 1, 0.8, 2, 0.3);
        f.bass = mix(f.bass, band(0));
        f.low = mix(f.low, band(1));
        f.mid = mix(f.mid, band(2));
        f.high = mix(f.high, band(3));
        f.air = mix(f.air, band(4));
    }

    private float mix(float analysed, double exact) {
        return (float) (analysed + (exact - analysed) * exactShare);
    }

    /** Loudness of a band, 0..1: it saturates softly, so a chord is louder than a note but never clips. */
    private double band(int i) {
        return 1 - Math.exp(-1.6 * sum[i]);
    }

    private void addDrum(Hits h, double t, double tau, int band, double w, int spill, double ws) {
        double e = ring(h, t, tau);
        if (e <= 0) return;
        sum[band] += e * w;
        sum[spill] += e * ws;
    }

    /** Strength of the strongest hit still ringing at {@code t}; the end of the previous pass counts after a wrap. */
    private double ring(Hits h, double t, double tau) {
        int n = h.t.length;
        if (n == 0) return 0;
        double best = 0;
        int idx = h.atOrBefore(t);
        boolean wrapped = false;
        for (int k = 0; k < 8; k++) {
            if (idx < 0) {
                if (wrapped) break;
                wrapped = true;
                idx = n - 1;
            }
            double age = t - h.t[idx] + (wrapped ? period : 0);
            if (age > 8 * tau) break;
            best = Math.max(best, h.v[idx] * Math.exp(-age / tau));
            idx--;
        }
        return best;
    }

    /** Strongest kick with {@code lo < time <= hi}, else 0. */
    private static double landed(Hits h, double lo, double hi) {
        double best = 0;
        for (int i = h.atOrBefore(hi); i >= 0 && h.t[i] > lo; i--) best = Math.max(best, h.v[i]);
        return best;
    }

    /** Seconds since each of the last 8 kicks, newest first (the last pass's kicks follow a wrap). */
    private void shocks(Features f, double t) {
        Hits h = drums[KICK];
        int n = h.t.length, count = 0;
        if (n > 0) {
            int idx = h.atOrBefore(t);
            boolean wrapped = false;
            while (count < f.shocks.length) {
                if (idx < 0) {
                    if (wrapped) break;
                    wrapped = true;
                    idx = n - 1;
                }
                f.shocks[count++] = (float) (t - h.t[idx] + (wrapped ? period : 0));
                idx--;
            }
        }
        f.shockCount = count;
    }

    /** Add every note sounding at {@code t} to the band sums. */
    private void notes(double t, boolean reset) {
        if (reset) {
            cursor = 0;
            active.clear();
        }
        while (cursor < tones.length && tones[cursor].start <= t) active.add(tones[cursor++]);
        for (int i = active.size() - 1; i >= 0; i--) {
            MidiFile.Note n = active.get(i);
            if (t > n.end + RELEASE_WINDOW) {
                active.set(i, active.get(active.size() - 1));   // gone for good: swap it out
                active.remove(active.size() - 1);
                continue;
            }
            double vel = n.velocity / 127.0;
            double e = envelope(t, n.start, n.end) * vel;
            int b = BAND_OF_NOTE[n.note & 127];
            sum[b] += e;
            if (b < 4) sum[b + 1] += e * vel * BRIGHTER;
        }
    }

    /** 0..1: quick attack, settles while held, fades after the key goes up. */
    static double envelope(double t, double start, double end) {
        double held = Math.min(t, end) - start;
        double level = SUSTAIN + (1 - SUSTAIN) * Math.exp(-held / SETTLE_TAU);
        double attack = Math.min(1, held / ATTACK + 1e-3);
        if (t <= end) return level * attack;
        return level * Math.min(1, (end - start) / ATTACK + 1e-3) * Math.exp(-(t - end) / RELEASE_TAU);
    }
}
