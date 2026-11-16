package com.fadcam.vislab.audio;

/**
 * Codex audit 11-15, LAB-07: decoded 5.1 / 7.1 audio must reach the stereo speaker and the
 * picture's analysis as stereo, one stereo frame per decoded frame (the old code treated six
 * channels as three stereo frames: wrong speed, wrong sides).
 *
 * <p>Run: {@code bash tools/jvm-harness/run-downmix.sh}</p>
 */
public class StereoDownmixTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        frameCountIsKept();
        sidesStaySides();
        centreGoesToBoth();
        lfeIsLeftOut();
        fullScaleNeverClips();
        stereoAndMonoUntouched();
        inPlaceMatchesSeparate();
        System.out.println("StereoDownmixTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    /** n frames of a 6-channel signal with only channel {@code ch} set to {@code v}. */
    static short[] only(int channels, int frames, int ch, short v) {
        short[] s = new short[channels * frames];
        for (int f = 0; f < frames; f++) s[f * channels + ch] = v;
        return s;
    }

    static void frameCountIsKept() {
        for (int ch = 3; ch <= 8; ch++) {
            short[] in = new short[ch * 100];
            check(ch + " channels: 100 frames in, 100 stereo frames out",
                    StereoDownmix.fold(in, in.length, ch, new short[200]) == 100);
        }
    }

    static void sidesStaySides() {
        short[] out = new short[20];
        StereoDownmix.fold(only(6, 10, 0, (short) 10000), 60, 6, out);   // front left
        check("5.1 front left is heard on the left only", out[0] > 0 && out[1] == 0);
        StereoDownmix.fold(only(6, 10, 5, (short) 10000), 60, 6, out);   // back right
        check("5.1 back right is heard on the right only", out[0] == 0 && out[1] > 0);
        StereoDownmix.fold(only(8, 10, 6, (short) 10000), 80, 8, out);   // side left
        check("7.1 side left is heard on the left only", out[0] > 0 && out[1] == 0);
    }

    static void centreGoesToBoth() {
        short[] out = new short[20];
        StereoDownmix.fold(only(6, 10, 2, (short) 10000), 60, 6, out);
        check("5.1 centre (dialogue) is heard on both sides equally", out[0] > 0 && out[0] == out[1]);
    }

    static void lfeIsLeftOut() {
        short[] out = new short[20];
        StereoDownmix.fold(only(6, 10, 3, (short) 10000), 60, 6, out);
        check("5.1 low-frequency channel is left out", out[0] == 0 && out[1] == 0);
    }

    static void fullScaleNeverClips() {
        for (int ch = 3; ch <= 8; ch++) {
            short[] in = new short[ch * 4];
            java.util.Arrays.fill(in, Short.MAX_VALUE);
            short[] out = new short[8];
            StereoDownmix.fold(in, in.length, ch, out);
            check(ch + " channels at full scale: at most full scale, and still loud",
                    out[0] <= Short.MAX_VALUE && out[0] > 30000 && out[1] > 30000);
        }
    }

    static void stereoAndMonoUntouched() {
        short[] in = {1, 2, 3, 4};
        short[] out = new short[4];
        check("stereo passes through", StereoDownmix.fold(in, 4, 2, out) == 2
                && java.util.Arrays.equals(in, out));
        check("mono passes through", StereoDownmix.fold(in, 4, 1, out) == 4);
    }

    static void inPlaceMatchesSeparate() {
        short[] a = new short[6 * 50];
        for (int i = 0; i < a.length; i++) a[i] = (short) ((i * 7919) % 20000 - 10000);
        short[] b = a.clone();
        short[] sep = new short[100];
        StereoDownmix.fold(a, a.length, 6, sep);
        StereoDownmix.fold(b, b.length, 6, b);
        boolean same = true;
        for (int i = 0; i < 100; i++) if (sep[i] != b[i]) same = false;
        check("folding in place gives the same as into a new buffer", same);
    }
}
