package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a {@link ModFile} into 16-bit mono at any rate, the way a ProTracker does: a row lasts
 * {@code speed} ticks, a tick {@code 2.5 / tempo} seconds; each channel plays its sample at the
 * Amiga clock divided by its period. The effects beat modules lean on are here: arpeggio (0),
 * slides (1, 2), tone portamento (3, 5), vibrato (4, 6), sample offset (9), volume slide (A),
 * position jump (B), set volume (C), pattern break (D), speed / tempo (F), and E1 / E2 fine
 * slides, E9 retrigger, EA / EB fine volume, EC note cut, ED note delay.
 *
 * <p>The song repeats: at its end, or when a jump goes back to a row already played, it starts
 * again from the top with the default speed and tempo, so every pass is identical. {@link #dryRun}
 * plays one pass silently and returns every note it struck with its time, which is what turns a
 * module into exact kicks and snares ({@link ModMidi}).</p>
 *
 * <p>Pure Java (no Android), so the JVM harness can play a module and check it.</p>
 */
public final class ModPlayer {

    /** Amiga PAL clock / 2: a period of 428 (C-2) plays a sample at 8287 Hz. */
    static final double CLOCK = 3546894.6;

    /** A struck note: when, which channel and sample, its period and volume (0..64). */
    public static final class Event {
        public final double time;
        public final int channel, sample, period, volume;

        Event(double time, int channel, int sample, int period, int volume) {
            this.time = time;
            this.channel = channel;
            this.sample = sample;
            this.period = period;
            this.volume = volume;
        }
    }

    private static final class Chan {
        @Nullable ModFile.Sample s;
        int sampleNo, vol, finetune;
        double pos, step;
        boolean on;
        int period, basePeriod, target, portaSpeed, vibPos, vibSpeed, vibDepth;
        int fx, param;
        int delayPeriod = -1, delayTick;
    }

    private final ModFile mod;
    private final int rate;
    private final Chan[] ch;
    private int speed, tempo, tick, row, pos;
    private int breakRow, jumpPos;
    private double tickLeft;
    private long frames;
    private boolean[] seen;
    private boolean looped;
    @Nullable private List<Event> events;

    public ModPlayer(@NonNull ModFile mod, int rate) {
        this.mod = mod;
        this.rate = rate;
        ch = new Chan[mod.channels];
        for (int i = 0; i < ch.length; i++) ch[i] = new Chan();
        restart();
    }

    private void restart() {
        speed = 6;
        tempo = 125;
        tick = 0;
        row = 0;
        pos = 0;
        breakRow = -1;
        jumpPos = -1;
        seen = new boolean[128 * ModFile.ROWS];
    }

    private float level = 1f;

    /** Scale the output (1 = as mixed). */
    public void setLevel(float l) {
        level = Math.max(0.05f, Math.min(1f, l));
    }

    /**
     * The level that keeps this module's loudest moment just under full scale: its first
     * {@code seconds} (or one pass) played silently at a low rate, the peak measured.
     */
    public static float loudness(@NonNull ModFile mod, double seconds) {
        ModPlayer p = new ModPlayer(mod, 11025);
        // Measured at a quarter of the level, so a loud peak is read, not clipped to full scale.
        p.setLevel(0.25f);
        short[] buf = new short[4096];
        int peak = 1;
        for (long done = 0; done < seconds * 11025; done += buf.length) {
            p.render(buf, 0, buf.length);
            for (short s : buf) peak = Math.max(peak, Math.abs(s));
        }
        float full = peak * 4f;
        return full > 29000f ? 29000f / full : 1f;
    }

    /** Seconds played since the start (all passes). */
    public double seconds() {
        return frames / (double) rate;
    }

    /** One silent pass: every note struck, how long it lasts, and the opening speed / tempo. */
    public static final class Pass {
        @NonNull public final List<Event> events;
        public final double seconds;
        public final int speed, tempo;

        Pass(@NonNull List<Event> events, double seconds, int speed, int tempo) {
            this.events = events;
            this.seconds = seconds;
            this.speed = speed;
            this.tempo = tempo;
        }
    }

    /**
     * Play one pass without sound, so a module is known in full before it is heard. Capped at
     * 15 minutes. The speed and tempo are those after the first row (where modules set them).
     */
    @NonNull
    public static Pass dryRun(@NonNull ModFile mod) {
        ModPlayer p = new ModPlayer(mod, 1000);
        p.events = new ArrayList<>();
        double cap = 15 * 60;
        int speed = -1, tempo = -1;
        while (!p.looped && p.seconds() < cap) {
            p.nextTick();
            if (speed < 0) {
                speed = p.speed;
                tempo = p.tempo;
            }
            long n = (long) Math.ceil(p.tickLeft);
            p.frames += n;
            p.tickLeft -= n;
        }
        // The pass ends where the next one begins: the restart happened at the last tick.
        double seconds = p.loopedAt > 0 ? p.loopedAt : p.seconds();
        return new Pass(p.events, seconds, Math.max(1, speed), Math.max(32, tempo));
    }

    private double loopedAt;

    /** Mix {@code n} frames into {@code out} from {@code off}. */
    public void render(@NonNull short[] out, int off, int n) {
        int done = 0;
        while (done < n) {
            if (tickLeft <= 0) nextTick();
            int run = (int) Math.min(n - done, Math.max(1, Math.ceil(tickLeft)));
            mix(out, off + done, run);
            done += run;
            tickLeft -= run;
            frames += run;
        }
    }

    // ------------------------------------------------------------------ sequencing

    private void nextTick() {
        if (tick == 0) startRow();
        else everyTick();
        tickLeft += rate * 2.5 / tempo;
        if (++tick >= speed) {
            tick = 0;
            advance();
        }
    }

    private void advance() {
        if (jumpPos >= 0 || breakRow >= 0) {
            pos = jumpPos >= 0 ? jumpPos : pos + 1;
            row = breakRow >= 0 ? Math.min(ModFile.ROWS - 1, breakRow) : 0;
            jumpPos = -1;
            breakRow = -1;
        } else if (++row >= ModFile.ROWS) {
            row = 0;
            pos++;
        }
        if (pos >= mod.songLength || !mod.hasPattern(mod.order[pos])
                || seen[pos * ModFile.ROWS + row]) {
            // The end, or a jump back to a row already played: start again from the top, the
            // same way every time, so every pass is the same length.
            looped = true;
            if (loopedAt == 0) loopedAt = seconds() + tickLeftSeconds();
            restart();
        }
    }

    private double tickLeftSeconds() {
        return Math.max(0, tickLeft) / rate;
    }

    private void startRow() {
        seen[pos * ModFile.ROWS + row] = true;
        int pat = mod.order[pos];
        for (int c = 0; c < ch.length; c++) {
            Chan k = ch[c];
            int sNo = mod.sampleAt(pat, row, c);
            int period = mod.periodAt(pat, row, c);
            int fx = mod.effectAt(pat, row, c), prm = mod.paramAt(pat, row, c);
            if (sNo > 0 && sNo <= 31) {
                k.sampleNo = sNo;
                k.s = mod.samples[sNo - 1];
                k.vol = k.s.volume;
                k.finetune = k.s.finetune;
            }
            k.fx = fx;
            k.param = prm;
            k.delayPeriod = -1;
            boolean delay = fx == 0xE && (prm >> 4) == 0xD && (prm & 15) > 0;
            // Effects that act on the first tick, before the note sounds (so a C on the same row
            // as a note is that note's volume).
            switch (fx) {
                case 0x3: case 0x5:
                    if (fx == 0x3 && prm > 0) k.portaSpeed = prm;
                    break;
                case 0x4:
                    if ((prm >> 4) > 0) k.vibSpeed = prm >> 4;
                    if ((prm & 15) > 0) k.vibDepth = prm & 15;
                    break;
                case 0xB: jumpPos = prm; break;
                case 0xC: k.vol = Math.min(64, prm); break;
                case 0xD: breakRow = (prm >> 4) * 10 + (prm & 15); break;
                case 0xF:
                    if (prm > 0 && prm < 32) speed = prm;
                    else if (prm >= 32) tempo = prm;
                    break;
                case 0xE: fine(k, prm); break;
                default: break;
            }
            if (period > 0) {
                int p = tuned(period, k.finetune);
                if (fx == 0x3 || fx == 0x5) k.target = p;
                else if (delay) {
                    k.delayPeriod = p;
                    k.delayTick = prm & 15;
                } else {
                    strike(k, c, p);
                    if (fx == 0x9 && k.s != null) k.pos = Math.min(prm * 256, k.s.data.length);
                }
            }
        }
    }

    private void fine(@NonNull Chan k, int prm) {
        int v = prm & 15;
        switch (prm >> 4) {
            case 0x1: k.period = Math.max(113, k.period - v); k.basePeriod = k.period; break;
            case 0x2: k.period = Math.min(856 * 2, k.period + v); k.basePeriod = k.period; break;
            case 0xA: k.vol = Math.min(64, k.vol + v); break;
            case 0xB: k.vol = Math.max(0, k.vol - v); break;
            default: break;
        }
    }

    private void everyTick() {
        for (int c = 0; c < ch.length; c++) {
            Chan k = ch[c];
            int prm = k.param;
            switch (k.fx) {
                case 0x0:
                    if (prm != 0 && k.basePeriod > 0) {
                        int semis = tick % 3 == 1 ? prm >> 4 : tick % 3 == 2 ? prm & 15 : 0;
                        k.period = (int) Math.round(k.basePeriod * Math.pow(2, -semis / 12.0));
                    }
                    break;
                case 0x1: k.period = Math.max(113, k.period - prm); k.basePeriod = k.period; break;
                case 0x2: k.period = Math.min(856 * 2, k.period + prm); k.basePeriod = k.period; break;
                case 0x3: porta(k); break;
                case 0x4: vibrato(k); break;
                case 0x5: porta(k); slide(k, prm); break;
                case 0x6: vibrato(k); slide(k, prm); break;
                case 0xA: slide(k, prm); break;
                case 0xE: {
                    int v = prm & 15;
                    switch (prm >> 4) {
                        case 0x9: if (v > 0 && tick % v == 0) strike(k, c, k.basePeriod); break;
                        case 0xC: if (tick == v) k.vol = 0; break;
                        case 0xD: if (tick == k.delayTick && k.delayPeriod > 0) strike(k, c, k.delayPeriod); break;
                        default: break;
                    }
                    break;
                }
                default: break;
            }
        }
    }

    private static void slide(@NonNull Chan k, int prm) {
        int up = prm >> 4, down = prm & 15;
        k.vol = up > 0 ? Math.min(64, k.vol + up) : Math.max(0, k.vol - down);
    }

    private static void porta(@NonNull Chan k) {
        if (k.target <= 0 || k.portaSpeed <= 0) return;
        if (k.period < k.target) k.period = Math.min(k.target, k.period + k.portaSpeed);
        else if (k.period > k.target) k.period = Math.max(k.target, k.period - k.portaSpeed);
        k.basePeriod = k.period;
    }

    private static void vibrato(@NonNull Chan k) {
        double d = Math.sin(k.vibPos * 2 * Math.PI / 64) * k.vibDepth * 2;
        k.period = Math.max(113, (int) Math.round(k.basePeriod + d));
        k.vibPos = (k.vibPos + k.vibSpeed) & 63;
    }

    private void strike(@NonNull Chan k, int channel, int period) {
        if (period <= 0) return;
        k.period = period;
        k.basePeriod = period;
        k.pos = 0;
        k.vibPos = 0;
        k.on = k.s != null && k.s.data.length > 0;
        if (k.on && events != null) events.add(new Event(seconds(), channel, k.sampleNo, period, k.vol));
    }

    /** Finetune in eighths of a semitone, as a period. */
    private static int tuned(int period, int finetune) {
        return finetune == 0 ? period : (int) Math.round(period * Math.pow(2, -finetune / 96.0));
    }

    // ------------------------------------------------------------------ sound

    private void mix(@NonNull short[] out, int off, int n) {
        // One channel at full volume is +-128 x 64. Four at once rarely peak together, so a
        // little over a channel's worth of headroom each keeps a busy beat loud; {@link #level}
        // then trims a module measured as louder than that ({@link #loudness}).
        float gain = 32767f / 128f / 64f * (1.2f / Math.max(2, ch.length / 2f)) * level;
        for (int c = 0; c < ch.length; c++) {
            Chan k = ch[c];
            k.step = k.period > 0 ? CLOCK / k.period / rate : 0;
        }
        for (int i = 0; i < n; i++) {
            float sum = 0;
            for (Chan k : ch) {
                if (!k.on || k.s == null || k.vol == 0) continue;
                byte[] d = k.s.data;
                int p = (int) k.pos;
                if (p >= d.length) {
                    k.on = false;
                    continue;
                }
                float a = d[p], b = p + 1 < d.length ? d[p + 1] : a;
                float frac = (float) (k.pos - p);
                sum += (a + (b - a) * frac) * k.vol;
                k.pos += k.step;
                int end = k.s.loops() ? k.s.loopStart + k.s.loopLength : d.length;
                if (k.pos >= end) {
                    if (k.s.loops()) k.pos -= k.s.loopLength;
                    else k.on = false;
                }
            }
            int v = Math.round(sum * gain);
            out[off + i] = (short) Math.max(-32768, Math.min(32767, v));
        }
    }
}
