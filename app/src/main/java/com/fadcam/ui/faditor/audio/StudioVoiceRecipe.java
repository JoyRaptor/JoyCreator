package com.fadcam.ui.faditor.audio;

import java.util.Arrays;
import java.util.Locale;

/** Signal decisions and sample-clock math shared by the Android render and host regression tests. */
final class StudioVoiceRecipe {
    enum Strength { ORIGINAL, GENTLE, STRONG }

    // 100 ms RMS windows, 8 kHz signed little-endian mono.
    static Strength assess(byte[] pcm, int channels) {
        int frameBytes = 1600 * channels;
        int frames = pcm.length / frameBytes;
        if (frames < 3) return Strength.GENTLE;
        double[] levels = new double[frames];
        double[] differences = new double[frames];
        for (int i = 0; i < frames; i++) {
            double energy = 0, deltaEnergy = 0;
            for (int j = i * frameBytes; j < (i + 1) * frameBytes; j += 2) {
                int value = (pcm[j] & 255) | (pcm[j + 1] << 8);
                energy += (double) value * value;
                int previous = j - 2 * channels;
                if (previous >= i * frameBytes) {
                    int last = (pcm[previous] & 255) | (pcm[previous + 1] << 8);
                    double delta = value - last;
                    deltaEnergy += delta * delta;
                }
            }
            differences[i] = deltaEnergy / Math.max(1, energy);
            levels[i] = 10 * Math.log10(Math.max(1e-12, energy / (800.0 * channels * 32768 * 32768)));
        }
        double[] sorted = levels.clone();
        Strength initial = assessLevels(sorted);
        if (initial != Strength.STRONG) return initial;
        // On a bass-dominated noise floor, aggressive speech suppression also strips
        // low voice harmonics. Estimate spectral slope only in lower-energy frames.
        int used = 0;
        for (int i = 0; i < frames; i++) {
            if (levels[i] >= sorted[frames / 10] && levels[i] <= sorted[frames / 3])
                differences[used++] = differences[i];
        }
        Arrays.sort(differences, 0, used);
        return used > 0 && differences[used / 2] < 0.05 ? Strength.GENTLE : Strength.STRONG;
    }

    static Strength assessLevels(double[] levels) {
        int frames = levels.length;
        Arrays.sort(levels);
        double quiet = levels[frames / 5], strong = levels[frames * 9 / 10];
        double low = levels[frames / 20];
        if (levels[frames - 1] <= -80 || (quiet < -62 && strong - quiet > 28)
                || (low < -70 && quiet < -45 && strong - quiet > 20)) return Strength.ORIGINAL;
        // A widely fluctuating floor often includes transient sounds and soft consonants.
        if (quiet - levels[frames / 20] > 7) return Strength.GENTLE;
        if (quiet > -48 && strong - quiet >= 12) return Strength.STRONG;
        return Strength.GENTLE;
    }

    static boolean surround(String layout) {
        return "5.1".equals(layout) || "5.1(side)".equals(layout);
    }

    static String graph(String layout, int rate, Strength strength, String modelPath,
                        long startMs, long endMs, long prerollMs) {
        long duration = endMs - startMs;
        // FFmpeg's arnndn introduces 480 samples and afftdn 1200 at 48 kHz.
        // Pad BEFORE filtering, then discard both the delay and any real preroll.
        long trim = 1680 + prerollMs * 48;
        boolean strong = strength == Strength.STRONG;
        String process = "aresample=48000,apad=pad_len=2400,arnndn=m=" + modelPath
                + (strong ? ":mix=1,afftdn=nr=5:nf=-40" : ":mix=0.75,afftdn=nr=3:nf=-45")
                + ",atrim=start_sample=" + trim + ":end_sample=" + (trim + duration * 48)
                + ",asetpts=PTS-STARTPTS";
        String input = "[0:a:0]atrim=duration=" + seconds(duration + prerollMs) + ",asetpts=PTS-STARTPTS";
        String signal;
        if (surround(layout)) {
            String backs = layout.equals("5.1") ? "0.BL-BL|0.BR-BR" : "0.SL-SL|0.SR-SR";
            signal = input + ",asplit=2[bed][dialogue];[dialogue]pan=mono|c0=FC," + process
                    + ",aresample=" + rate + "[voice];[bed]atrim=start=" + seconds(prerollMs)
                    + ",asetpts=PTS-STARTPTS[rest];[rest][voice]join=inputs=2:channel_layout=" + layout
                    + ":map=0.FL-FL|0.FR-FR|1.0-FC|0.LFE-LFE|" + backs;
        } else {
            signal = input + "," + process;
        }
        if (startMs == 0) return signal + "[out]";
        // A finite silence source uses bounded memory even on a trim hours into a recording.
        // adelay would allocate a delay line proportional to the in-point.
        return signal + "[span];anullsrc=r=" + rate + ":cl=" + layout + ",atrim=duration="
                + seconds(startMs) + "[prefix];[prefix][span]concat=n=2:v=0:a=1[out]";
    }

    static String seconds(long ms) { return String.format(Locale.US, "%.3f", ms / 1000.0); }
    private StudioVoiceRecipe() {}
}
