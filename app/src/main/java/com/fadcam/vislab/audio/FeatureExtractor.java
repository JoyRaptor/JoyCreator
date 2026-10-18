package com.fadcam.vislab.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The musical "ear" every VisLab effect reacts to. A line-for-line port of the web pilot's
 * {@code FeatureExtractor} (tools/vislab-web/vislab-audio.js): same bins, same time constants,
 * same thresholds, so a look tuned in the browser moves the same way on the phone. Keep the
 * two in step; {@code tools/jvm-harness/VisFeatureParityTest} compares them on one signal.
 *
 * <p>Feed it about 60 times a second: a dB spectrum shaped like WebAudio's
 * {@code getFloatFrequencyData} ({@link SpectrumAnalyzer} makes one from PCM), the matching
 * left/right samples and the seconds since the last call. Studio's offline analysis calls it
 * at exactly 60 Hz so preview, scrub and export all hear the same thing.</p>
 */
public final class FeatureExtractor {

    private static final int NB = Features.NB, NW = Features.NW, NH = Features.NH;
    private static final double F_LO = 30, F_HI = 16000;
    private static final double[][] BANDS = {{30, 150}, {150, 400}, {400, 2000}, {2000, 6000}, {6000, 16000}};

    private final float[] a = new float[NB], b = new float[NB], tilt = new float[NB];
    private final int[][] bandK = new int[5][2];
    private final float[] db = new float[NB];
    private final float[] prevRaw = new float[NB];
    private final float[] env = new float[5], avg = new float[5], mx = new float[5];
    private final float[] bands = new float[5];
    private final float[] onsetEnv = new float[360];
    private final float[] score = new float[60];
    private final OnsetDetector kick, snare, hat;
    private final int voice0, voice1;

    private double agc = -40, levelAgc = -30, waveGain = 4;
    private double level, voice, bright = 0.4, energy;
    private double beat, phase, period = 0.5;
    private long beatCount;
    private int onsetIdx;
    private double onsetClock, tempoClock, histClock;
    private double bassAcc, levelAcc;
    private final float[] shocks = new float[8];
    private int shockCount;

    private final Features out = new Features();

    public FeatureExtractor(int sampleRate, int fftSize) {
        double hz = (double) sampleRate / fftSize;
        for (int k = 0; k < NB; k++) {
            double e0 = F_LO * Math.pow(F_HI / F_LO, (double) k / NB);
            double e1 = F_LO * Math.pow(F_HI / F_LO, (double) (k + 1) / NB);
            a[k] = (float) (e0 / hz);
            b[k] = (float) (e1 / hz);
            double fc = (float) Math.sqrt(e0 * e1);
            tilt[k] = (float) (3.0 * (Math.log(fc / 1000) / Math.log(2)));
        }
        for (int i = 0; i < 5; i++) {
            int k0 = binOf(BANDS[i][0]);
            bandK[i][0] = k0;
            bandK[i][1] = Math.max(k0 + 1, binOf(BANDS[i][1]));
        }
        java.util.Arrays.fill(avg, 0.2f);
        java.util.Arrays.fill(mx, 0.3f);
        kick = new OnsetDetector(binOf(30), binOf(150), 0.03, 0.12, 0.16);
        snare = new OnsetDetector(binOf(1800), binOf(6500), 0.025, 0.1, 0.13);
        hat = new OnsetDetector(binOf(7000), NB, 0.02, 0.06, 0.08);
        voice0 = binOf(150);
        voice1 = binOf(3500);
    }

    /** Spectrum bin (0..128) for a frequency in Hz. */
    public static int binOf(double f) {
        double x = Math.log(f / F_LO) / Math.log(F_HI / F_LO) * NB;
        return (int) clamp(Math.round(x), 0, NB);
    }

    /**
     * One frame of analysis.
     * @param dbSpec  dB magnitudes, fftSize/2 bins (WebAudio convention, -inf allowed)
     * @param timeL   left (or mono) samples, at least 2048
     * @param timeR   right samples, or null for mono (the scope then uses a delayed copy)
     * @param dtSec   seconds since the previous call
     * @return the frame, owned by this extractor and overwritten next call
     */
    @NonNull
    public Features update(@NonNull float[] dbSpec, @NonNull float[] timeL, @Nullable float[] timeR, double dtSec) {
        double dt = clamp(dtSec, 1.0 / 240, 0.25);
        int nbins = dbSpec.length;
        Features f = out;
        // 1. log-spaced bins, power-averaged, with a pink tilt so the highs are not always dead.
        double frameMax = -160;
        for (int k = 0; k < NB; k++) {
            double ak = a[k], bk = b[k];
            double p;
            if (bk - ak < 1) {
                double x = clamp((ak + bk) * 0.5, 0, nbins - 1.001);
                int i = (int) Math.floor(x);
                double fr = x - i;
                double p0 = Math.pow(10, Math.max(dbSpec[i], -160) / 10.0);
                double p1 = Math.pow(10, Math.max(dbSpec[i + 1], -160) / 10.0);
                p = p0 + (p1 - p0) * fr;
            } else {
                double s = 0;
                int c = 0;
                int i1 = (int) Math.ceil(bk);
                for (int i = (int) Math.floor(ak); i < i1 && i < nbins; i++) {
                    s += Math.pow(10, Math.max(dbSpec[i], -160) / 10.0);
                    c++;
                }
                p = c > 0 ? s / c : 0;
            }
            double d = 10 * Math.log10(p + 1e-16) + tilt[k];
            db[k] = (float) d;
            if (d > frameMax) frameMax = d;
        }
        // 2. automatic gain: follow the loudest bin fast up, slowly down. Silence stays dark.
        if (frameMax > agc) agc += (frameMax - agc) * (1 - Math.exp(-dt / 0.05));
        else agc -= 4 * dt;
        agc = Math.max(agc, -72);
        double range = 50, floor = agc - range;
        double gate = clamp((frameMax + 85) / 20, 0, 1);
        System.arraycopy(f.raw, 0, prevRaw, 0, NB);
        for (int k = 0; k < NB; k++) {
            double v = clamp((db[k] - floor) / range, 0, 1);
            v = Math.pow(v, 1.6) * gate;
            f.raw[k] = (float) v;
            f.spec[k] = (float) ease(f.spec[k], v, dt, 0.02, 0.16);
            f.peak[k] = (float) Math.max(f.spec[k], f.peak[k] - 0.6 * dt);
        }
        // 3. bands, each normalised to its own recent range so it always uses 0..1.
        for (int i = 0; i < 5; i++) {
            int k0 = bandK[i][0], k1 = bandK[i][1];
            double s = 0;
            for (int k = k0; k < k1; k++) s += f.raw[k];
            s /= Math.max(1, k1 - k0);
            env[i] = (float) ease(env[i], s, dt, 0.015, i == 0 ? 0.22 : 0.15);
            avg[i] = (float) ease(avg[i], env[i], dt, 3, 3);
            mx[i] = (float) Math.max(env[i], mx[i] - 0.15 * dt);
            double lo = avg[i] * 0.6;
            double norm = clamp((env[i] - lo) / Math.max(mx[i] - lo, 0.08), 0, 1.2);
            double quiet = clamp((env[i] - 0.02) / 0.1, 0, 1);
            bands[i] = (float) ((env[i] * 0.35 + norm * 0.65) * quiet);
        }
        // 4. loudness from the waveform itself.
        double sum = 0, pk = 0;
        int nt = timeL.length;
        for (int i = 0; i < nt; i++) {
            double s = timeL[i];
            sum += s * s;
            double ab = Math.abs(s);
            if (ab > pk) pk = ab;
        }
        double rmsDb = 10 * Math.log10(sum / nt + 1e-12);
        if (rmsDb > levelAgc) levelAgc += (rmsDb - levelAgc) * (1 - Math.exp(-dt / 0.1));
        else levelAgc -= 3 * dt;
        levelAgc = Math.max(levelAgc, -60);
        double lv = clamp((rmsDb - (levelAgc - 30)) / 30, 0, 1) * gate;
        level = ease(level, lv * lv, dt, 0.02, 0.25);
        energy = ease(energy, level, dt, 6, 6);
        // 5. voice band and brightness.
        double vs = 0, cw = 0, cs = 0;
        int vc = 0;
        for (int k = 0; k < NB; k++) {
            double r = f.raw[k];
            if (k >= voice0 && k < voice1) { vs += r; vc++; }
            cw += r * r * k;
            cs += r * r;
        }
        voice = ease(voice, vc > 0 ? clamp(vs / vc * 1.6, 0, 1) : 0, dt, 0.03, 0.2);
        bright = ease(bright, cs > 1e-6 ? cw / cs / NB : bright, dt, 0.2, 0.2);
        // 6. hits.
        double kf = kick.update(f.raw, prevRaw, dt);
        snare.update(f.raw, prevRaw, dt);
        hat.update(f.raw, prevRaw, dt);
        if (kf > 0) {
            System.arraycopy(shocks, 0, shocks, 1, shocks.length - 1);
            shocks[0] = 0;
            shockCount = Math.min(shockCount + 1, shocks.length);
        }
        for (int i = 0; i < shockCount; i++) shocks[i] += (float) dt;
        // 7. tempo: autocorrelate the kick-flux envelope, then a phase-locked loop on the kicks.
        onsetClock += dt;
        while (onsetClock >= 1.0 / 60) {
            onsetClock -= 1.0 / 60;
            onsetEnv[onsetIdx] = (float) kick.flux;
            onsetIdx = (onsetIdx + 1) % onsetEnv.length;
        }
        tempoClock += dt;
        if (tempoClock > 0.5) {
            tempoClock = 0;
            tempo();
        }
        phase += dt / period;
        if (kf > 0) {
            double err = phase - Math.round(phase);
            phase -= err * 0.35;
        }
        beat *= Math.exp(-dt / 0.12);
        if (phase >= 1) {
            phase -= Math.floor(phase);
            beat = 1;
            beatCount++;
        }
        if (phase < 0) phase += 1;
        // 8. travel: always creeping forward, faster with the music.
        bassAcc += (0.15 + bands[0]) * dt;
        levelAcc += (0.15 + level) * dt;
        // 9. scope: trigger on a rising zero crossing so the wave stands still, auto-levelled.
        scope(timeL, timeR, pk, dt, gate);
        // 10. two seconds of spectrum history, a row every 1/30 s.
        histClock += dt;
        if (histClock >= 1.0 / 30) {
            histClock -= 1.0 / 30;
            System.arraycopy(f.hist, 0, f.hist, NB, NB * (NH - 1));
            System.arraycopy(f.spec, 0, f.hist, 0, NB);
        }
        f.bass = bands[0]; f.low = bands[1]; f.mid = bands[2]; f.high = bands[3]; f.air = bands[4];
        f.level = (float) level; f.voice = (float) voice; f.bright = (float) bright;
        f.kick = (float) kick.value; f.snare = (float) snare.value; f.hat = (float) hat.value;
        f.beat = (float) beat;
        f.beatPhase = (float) phase;
        f.barPhase = (float) (((beatCount % 4) + phase) / 4);
        f.bassAcc = (float) bassAcc; f.levelAcc = (float) levelAcc; f.energy = (float) energy;
        f.punch = (float) kf;
        f.bpm = (float) (60 / period);
        System.arraycopy(shocks, 0, f.shocks, 0, shocks.length);
        f.shockCount = shockCount;
        return f;
    }

    private void tempo() {
        float[] o = onsetEnv;
        int n = o.length;
        double r0 = 0;
        for (int i = 0; i < n; i++) r0 += (double) o[i] * o[i];
        if (r0 < 1e-6) return;
        java.util.Arrays.fill(score, 0f);
        double best = -1;
        int bestL = 30;
        for (int L = 20; L <= 51; L++) {
            double r = 0;
            for (int i = 0; i < n; i++) r += (double) o[i] * o[(i - L + n) % n];
            double bpm = 3600.0 / L;
            double z = (Math.log(bpm / 120) / Math.log(2)) / 0.8;
            double prior = Math.exp(-0.5 * z * z);
            score[L] = (float) (r * prior);
            if (score[L] > best) {
                best = score[L];
                bestL = L;
            }
        }
        double y0 = score[bestL - 1], y1 = score[bestL], y2 = score[bestL + 1];
        double den = y0 - 2 * y1 + y2;
        double off = Math.abs(den) > 1e-9 ? clamp(0.5 * (y0 - y2) / den, -0.5, 0.5) : 0;
        if (best / r0 > 0.08) {
            double p = (bestL + off) / 60;
            period = Math.abs(p - period) / period < 0.08 ? period * 0.7 + p * 0.3 : p;
        }
    }

    private void scope(float[] L, @Nullable float[] R, double pk, double dt, double gate) {
        int n = L.length;
        int span = Math.min(1024, n - 1);
        int start = 0;
        for (int i = 1; i < n - span; i++) {
            if (L[i - 1] < 0 && L[i] >= 0) { start = i; break; }
        }
        double target = clamp(0.8 / Math.max(pk, 0.01), 0.5, 40);
        waveGain = ease(waveGain, target, dt, 0.3, 1.0);
        double g = waveGain * gate;
        double step = (double) span / NW;
        for (int i = 0; i < NW; i++) {
            int j = start + (int) Math.floor(i * step);
            out.waveL[i] = (float) clamp(L[j] * g, -1.2, 1.2);
            double rv = R != null ? R[j] : L[Math.max(0, j - 23)];
            out.waveR[i] = (float) clamp(rv * g, -1.2, 1.2);
        }
    }

    private static double ease(double cur, double target, double dt, double up, double down) {
        double tau = target > cur ? up : down;
        return cur + (target - cur) * (1 - Math.exp(-dt / tau));
    }

    static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }
}
