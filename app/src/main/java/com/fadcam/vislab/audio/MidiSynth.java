package com.fadcam.vislab.audio;

import java.util.Arrays;

/**
 * A tiny built-in synth that turns a {@link MidiFile} into sound: sine, saw and plucked voices for
 * the notes (the kind picked from the General MIDI instrument the note was played on) and a
 * simple drum kit made of noise and sweeping sines for channel 10. It will not win prizes; it
 * exists so a MIDI file can be HEARD through the same path as any song (the sound goes to the
 * speaker and the ring, the ear analyses it) and so every note starts on its exact sample.
 *
 * <p>Plays the piece in a loop: {@link #render} always produces the next stretch of sound, and when
 * it reaches {@code loopFrames} the notes still held are let go and the file starts again from its
 * first event. Mono, deterministic (the noise is a fixed sequence), pure Java: the same code is
 * run on the desk by {@code tools/jvm-harness/MidiTest}.</p>
 */
final class MidiSynth {

    private static final int MAX_VOICES = 40;
    private static final int SINE = 0, SAW = 1, PLUCK = 2;
    private static final int KICK = 10, SNARE = 11, HAT = 12, TOM = 13, CYMBAL = 14, BLIP = 15;
    private static final float MASTER = 0.85f;

    // per tone kind: attack (s), settle time constant (s), sustain level, release time constant (s), gain
    private static final float[] ATK = {0.015f, 0.008f, 0.003f};
    private static final float[] SETTLE = {0.6f, 0.3f, 0.45f};
    private static final float[] SUS = {0.85f, 0.6f, 0f};
    private static final float[] REL = {0.12f, 0.1f, 0.12f};
    private static final float[] GAIN = {0.9f, 0.5f, 0.8f};

    private static final float[] SIN = new float[4097];

    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(2 * Math.PI * i / 4096);
    }

    private static final class Voice {
        boolean live, held;
        int kind, noteIdx, age, limit;
        float phase, inc, env, gain, lp, a1, aux, aux2, envK, auxK, f0;
    }

    private final int rate;
    private final long loopFrames;
    private final MidiFile.Note[] notes;
    private final long[] keys;           // frame << 24 | on << 23 | note index, in playing order
    private final Voice[] voices = new Voice[MAX_VOICES];
    private float[] acc = new float[0];
    private long pos;
    private int next;
    private int rng = 0x2545F491;

    /**
     * @param loopFrames how long one pass is; notes that start at or after it are never heard
     */
    MidiSynth(MidiFile midi, int sampleRate, long loopFrames) {
        rate = sampleRate;
        this.loopFrames = Math.max(1, loopFrames);
        notes = midi.notes().toArray(new MidiFile.Note[0]);
        long[] k = new long[notes.length * 2];
        int n = 0;
        for (int i = 0; i < notes.length; i++) {
            long on = Math.round(notes[i].start * rate);
            if (on >= this.loopFrames) continue;
            k[n++] = (on << 24) | (1L << 23) | i;
            if (!notes[i].isDrum()) {                          // a drum plays itself out
                // At least one frame after the start: an off sorts before an on at the same frame,
                // so a zero-length note must not let go of itself before it began.
                long off = Math.max(Math.round(notes[i].end * rate), on + 1);
                if (off < this.loopFrames) k[n++] = (off << 24) | i;
            }
        }
        keys = Arrays.copyOf(k, n);
        Arrays.sort(keys);
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
    }

    /** Frames from the start of the pass to the next frame {@link #render} will produce. */
    long position() {
        return pos;
    }

    /** Next {@code frames} samples into {@code out[offset..]} (16-bit mono). */
    void render(short[] out, int offset, int frames) {
        if (acc.length < frames) acc = new float[frames];
        int done = 0;
        while (done < frames) {
            if (pos >= loopFrames) wrap();
            while (next < keys.length && (keys[next] >>> 24) <= pos) fire(keys[next++]);
            long until = next < keys.length ? Math.min(keys[next] >>> 24, loopFrames) : loopFrames;
            int run = (int) Math.min(frames - done, until - pos);
            Arrays.fill(acc, 0, run, 0f);
            for (Voice v : voices) if (v.live) voice(v, run);
            for (int i = 0; i < run; i++) {
                out[offset + done + i] = (short) Math.round(Math.tanh(acc[i] * MASTER) * 32000.0);
            }
            pos += run;
            done += run;
        }
    }

    private void wrap() {
        pos = 0;
        next = 0;
        for (Voice v : voices) v.held = false;     // let go of what was still held; it fades out
    }

    private void fire(long key) {
        int idx = (int) (key & 0x7FFFFF);
        if ((key & (1L << 23)) == 0) {
            for (Voice v : voices) if (v.live && v.held && v.noteIdx == idx) v.held = false;
            return;
        }
        MidiFile.Note n = notes[idx];
        Voice v = free();
        v.live = true;
        v.held = true;
        v.noteIdx = idx;
        v.age = 0;
        v.phase = 0;
        v.lp = 0;
        v.aux = 0;
        v.aux2 = 1;
        double vel = n.velocity / 127.0;
        if (n.isDrum()) drum(v, n.note, vel);
        else tone(v, n, vel);
    }

    /** A free voice, or the quietest one (a note being let go first). */
    private Voice free() {
        Voice quiet = voices[0];
        for (Voice v : voices) {
            if (!v.live) return v;
            if (v.env * (v.held ? 2f : 1f) < quiet.env * (quiet.held ? 2f : 1f)) quiet = v;
        }
        return quiet;
    }

    // ------------------------------------------------------------------ starting voices

    private void tone(Voice v, MidiFile.Note n, double vel) {
        int k = kindOf(n.program);
        double hz = 440.0 * Math.pow(2, (Math.min(n.note, 120) - 69) / 12.0);
        v.kind = k;
        v.inc = (float) (hz / rate);
        v.gain = (float) (0.2 * Math.pow(vel, 1.3) * GAIN[k]);
        v.env = 0;
        v.limit = 0;
        v.a1 = (float) (1 - Math.exp(-2 * Math.PI * Math.min(hz * 5 + 500 * vel, 9000) / rate));
    }

    /** Bright for basses, brass and synths; soft for strings, organs and pipes; plucked for the rest. */
    static int kindOf(int program) {
        if (program >= 16 && program < 24) return SINE;                  // organs
        if (program >= 32 && program < 40) return SAW;                   // basses
        if (program >= 40 && program < 56) return SINE;                  // strings, choir
        if (program >= 56 && program < 64) return SAW;                   // brass
        if (program >= 64 && program < 80) return SINE;                  // reeds, pipes
        if (program >= 80 && program < 104) return SAW;                  // synth leads, pads, effects
        if (program >= 120) return SAW;
        return PLUCK;                                                    // pianos, mallets, guitars, the rest
    }

    private void drum(Voice v, int note, double vel) {
        v.gain = (float) Math.pow(vel, 1.2);
        v.env = 1;
        v.limit = rate * 5 / 2;
        v.held = false;      // drums do not wait for a note-off
        switch (note) {
            case 35: case 36:
                v.kind = KICK;
                v.aux = 1;
                v.auxK = decay(0.03);
                v.envK = decay(0.16);
                break;
            case 37: case 38: case 39: case 40:
                v.kind = SNARE;
                v.envK = decay(note == 39 ? 0.07 : 0.11);
                v.aux = note == 39 ? 0f : 1f;                  // the tone under the noise (a clap has none)
                v.auxK = decay(0.05);
                v.gain *= 0.7f;
                break;
            case 42: case 44: case 46:
                v.kind = HAT;
                v.envK = decay(note == 46 ? 0.25 : note == 44 ? 0.04 : 0.03);
                v.gain *= 0.35f;
                v.aux2 = note == 46 ? 2f : 1f;                 // 2 marks an open hat
                if (note != 46) chokeOpenHats();               // a closed or pedal hat shuts an open one
                break;
            case 41: case 43: case 45: case 47: case 48: case 50:
                v.kind = TOM;
                v.f0 = (float) (100 * Math.pow(2, (note - 45) / 12.0));
                v.aux = 1;
                v.auxK = decay(0.04);
                v.envK = decay(0.22);
                v.gain *= 0.9f;
                break;
            case 49: case 51: case 52: case 53: case 55: case 57: case 59:
                v.kind = CYMBAL;
                v.envK = decay(note == 51 || note == 53 || note == 59 ? 0.45 : 0.9);
                v.gain *= 0.25f;
                break;
            default:
                v.kind = BLIP;                                   // cowbell, claves, shakers: a short pitched tick
                v.f0 = 700 + (note % 12) * 60;
                v.envK = decay(0.08);
                v.gain *= 0.4f;
                break;
        }
    }

    private void chokeOpenHats() {
        for (Voice o : voices) if (o.live && o.kind == HAT && o.aux2 == 2f) o.env *= 0.15f;
    }

    /** Per-sample multiplier for an exponential fade with the given time constant (seconds). */
    private float decay(double tau) {
        return (float) Math.exp(-1.0 / (tau * rate));
    }

    // ------------------------------------------------------------------ voices

    private void voice(Voice v, int n) {
        switch (v.kind) {
            case SINE: case SAW: case PLUCK:
                playTone(v, n);
                return;
            case KICK:
                playKick(v, n);
                break;
            case SNARE:
                playSnare(v, n);
                break;
            case HAT: case CYMBAL:
                playNoise(v, n);
                break;
            case TOM:
                playTom(v, n);
                break;
            default:
                playBlip(v, n);
                break;
        }
        if (v.env < 0.002f || v.age > v.limit) v.live = false;
    }

    private void playTone(Voice v, int n) {
        final int k = v.kind;
        final float atk = ATK[k] * rate, sus = SUS[k], settle = decay(SETTLE[k]), rel = decay(REL[k]);
        float env = v.env, phase = v.phase, lp = v.lp;
        int age = v.age;
        for (int i = 0; i < n; i++) {
            if (!v.held) env *= rel;
            else if (age < atk) env = (age + 1) / atk;
            else env = sus + (env - sus) * settle;
            age++;
            phase += v.inc;
            if (phase >= 1f) phase -= 1f;
            float s;
            if (k == SINE) {
                s = sin(phase);
            } else if (k == SAW) {
                lp += v.a1 * ((2f * phase - 1f) - lp);
                s = lp;
            } else {
                s = 0.6f * (sin(phase) + 0.45f * sin(phase * 2f) + 0.2f * sin(phase * 3f));
            }
            acc[i] += s * env * v.gain;
        }
        v.env = env;
        v.phase = phase;
        v.lp = lp;
        v.age = age;
        if (!v.held && env < 0.0008f) v.live = false;
    }

    private void playKick(Voice v, int n) {
        float env = v.env, sweep = v.aux, phase = v.phase;
        int age = v.age;
        for (int i = 0; i < n; i++) {
            phase += (46f + 130f * sweep) / rate;
            if (phase >= 1f) phase -= 1f;
            float s = sin(phase) * env;
            if (age < 48) s += noise() * (1f - age / 48f) * 0.35f;     // the beater's click
            acc[i] += s * v.gain;
            env *= v.envK;
            sweep *= v.auxK;
            age++;
        }
        v.env = env;
        v.aux = sweep;
        v.phase = phase;
        v.age = age;
    }

    private void playSnare(Voice v, int n) {
        float env = v.env, tone = v.aux, lp = v.lp, phase = v.phase;
        int age = v.age;
        for (int i = 0; i < n; i++) {
            float nz = noise();
            float hp = nz - lp;
            lp = nz;
            phase += 190f / rate;
            if (phase >= 1f) phase -= 1f;
            acc[i] += (hp * 0.5f * env + sin(phase) * 0.45f * tone * env) * v.gain;
            env *= v.envK;
            tone *= v.auxK;
            age++;
        }
        v.env = env;
        v.aux = tone;
        v.lp = lp;
        v.phase = phase;
        v.age = age;
    }

    /** Hi-hats and cymbals: differenced noise (only the top of the spectrum) fading out. */
    private void playNoise(Voice v, int n) {
        float env = v.env, lp = v.lp;
        for (int i = 0; i < n; i++) {
            float nz = noise();
            acc[i] += (nz - lp) * 0.5f * env * v.gain;
            lp = nz;
            env *= v.envK;
        }
        v.env = env;
        v.lp = lp;
        v.age += n;
    }

    private void playTom(Voice v, int n) {
        float env = v.env, sweep = v.aux, phase = v.phase;
        for (int i = 0; i < n; i++) {
            phase += v.f0 * (1f + 0.6f * sweep) / rate;
            if (phase >= 1f) phase -= 1f;
            acc[i] += sin(phase) * env * v.gain;
            env *= v.envK;
            sweep *= v.auxK;
        }
        v.env = env;
        v.aux = sweep;
        v.phase = phase;
        v.age += n;
    }

    private void playBlip(Voice v, int n) {
        float env = v.env, phase = v.phase;
        for (int i = 0; i < n; i++) {
            phase += v.f0 / rate;
            if (phase >= 1f) phase -= 1f;
            acc[i] += sin(phase) * env * v.gain;
            env *= v.envK;
        }
        v.env = env;
        v.phase = phase;
        v.age += n;
    }

    // ------------------------------------------------------------------ bits

    /** Sine of a phase in turns (any non-negative number), from a table with linear interpolation. */
    private static float sin(float turns) {
        float x = (turns - (float) Math.floor(turns)) * 4096f;
        int i = (int) x;
        float fr = x - i;
        return SIN[i] + (SIN[i + 1] - SIN[i]) * fr;
    }

    /** White noise in -1..1 (xorshift: the same every run). */
    private float noise() {
        int x = rng;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rng = x;
        return (x >> 8) / 8388608f;
    }
}
