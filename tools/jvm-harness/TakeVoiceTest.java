import cc.joycreator.avatar.perform.TakeVoice;
import cc.joycreator.avatar.perform.Wav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The voice kept with a take: a pass with the voice on replaces the voice for its stretch and only that stretch, with
 * 6 ms blended at each end; the voice is exactly as long as the take; a WAV of any rate reads back.
 * Run: bash tools/jvm-harness/run-avatar-voice.sh
 */
public class TakeVoiceTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static float[] constant(int n, float v) {
        float[] d = new float[n];
        Arrays.fill(d, v);
        return d;
    }

    public static void main(String[] a) {
        int R = TakeVoice.RATE, F = TakeVoice.FADE;
        ok(F == 144, "6 ms at 24 000 a second is 144 samples");

        // the WAV
        float[] sine = new float[480];
        for (int i = 0; i < sine.length; i++) sine[i] = (float) Math.sin(i * 0.1) * 0.8f;
        byte[] bytes = Wav.encode(sine, 44100);
        ok(bytes.length == 44 + 960 && bytes[0] == 'R' && bytes[8] == 'W', "a WAV is a 44-byte header then 16-bit samples");
        Wav.Pcm back = Wav.decode(bytes);
        boolean same = back != null && back.rate == 44100 && back.d.length == sine.length;
        for (int i = 0; same && i < sine.length; i++) same &= Math.abs(back.d[i] - sine[i]) < 1.0 / 16384;
        ok(same, "a WAV reads back at its own rate, sample for sample (to 16 bits)");
        ok(Wav.decode("not a wav".getBytes()) == null, "something that is not a WAV reads as nothing");
        // a chunk that claims almost 4 GB must be refused at once, not loop for ever
        {
            byte[] evil = new byte[64];
            System.arraycopy("RIFF".getBytes(), 0, evil, 0, 4);
            System.arraycopy("WAVE".getBytes(), 0, evil, 8, 4);
            System.arraycopy("junk".getBytes(), 0, evil, 12, 4);
            evil[16] = (byte) 0xF7;
            evil[17] = evil[18] = evil[19] = (byte) 0xFF;
            final Wav.Pcm[] got = {new Wav.Pcm(1, new float[1])};
            Thread th = new Thread(() -> got[0] = Wav.decode(evil));
            th.setDaemon(true);
            th.start();
            try {
                th.join(3000);
            } catch (InterruptedException e) {
                // the test is over either way
            }
            ok(!th.isAlive() && got[0] == null, "a WAV chunk of 0xFFFFFFF7 bytes is refused, not looped on");
        }

        // a take of 2 s that already has a voice (constant 0.5); a pass laid 0.2 over 0.5 s to 1.0 s
        Wav.Pcm old = new Wav.Pcm(R, constant(2 * R, 0.5f));
        Wav.Pcm heard = new Wav.Pcm(R, constant(2 * R, 0.2f));
        List<TakeVoice.Stretch> one = Collections.singletonList(new TakeVoice.Stretch(0.5, 1.0, 0.5));
        Wav.Pcm out = TakeVoice.compose(2.0, old, one, heard);
        ok(out != null && out.d.length == 2 * R && out.rate == R, "the voice is exactly as long as the take");
        ok(Math.abs(out.d[R / 4] - 0.5f) < 1e-6 && Math.abs(out.d[(int) (1.5 * R)] - 0.5f) < 1e-6, "outside the stretch the old voice is untouched");
        ok(Math.abs(out.d[(int) (0.75 * R)] - 0.2f) < 1e-6, "inside the stretch the new voice replaces it");
        int p0 = R / 2;
        ok(Math.abs(out.d[p0] - (0.5f * (1 - 1.0 / F) + 0.2f * (1.0 / F))) < 1e-5, "the first sample of the stretch is almost all old: no click");
        ok(out.d[p0 + F / 2] < 0.5f && out.d[p0 + F / 2] > 0.2f, "half way through the blend it is between the two");
        ok(Math.abs(out.d[p0 + F] - 0.2f) < 1e-6, "after 6 ms it is all new");
        int p1 = R;
        ok(Math.abs(out.d[p1 - 1] - (0.5f * (1 - 1.0 / F) + 0.2f * (1.0 / F))) < 1e-5 && Math.abs(out.d[p1 - F] - 0.2f) < 1e-6, "the end of the stretch blends back the same way");

        // a pass without the voice leaves the voice alone (no stretches: the same samples, the take's length)
        Wav.Pcm kept = TakeVoice.compose(2.0, old, Collections.<TakeVoice.Stretch>emptyList(), null);
        ok(kept != null && Arrays.equals(kept.d, old.d), "a pass without the voice leaves the voice as it was");
        ok(TakeVoice.compose(2.0, null, Collections.<TakeVoice.Stretch>emptyList(), null) == null, "no voice and nothing heard: no voice at all");

        // a take that grew gets silence after the old voice; a take that shrank is cut
        Wav.Pcm grown = TakeVoice.compose(3.0, old, Collections.<TakeVoice.Stretch>emptyList(), null);
        ok(grown.d.length == 3 * R && grown.d[2 * R - 1] == 0.5f && grown.d[2 * R] == 0f, "a longer take keeps the old voice then silence");

        // a fresh take (no old voice): silence outside the stretch
        Wav.Pcm fresh = TakeVoice.compose(1.0, null, Collections.singletonList(new TakeVoice.Stretch(0.25, 0.5, 0.25)), heard);
        ok(fresh.d[0] == 0f && Math.abs(fresh.d[(int) (0.4 * R)] - 0.2f) < 1e-6, "a fresh take is silent outside what was heard");

        // a recording made faster than 24 000 a second is brought down, smoothed
        Wav.Pcm fast = new Wav.Pcm(48000, constant(48000, 0.3f));
        Wav.Pcm down = TakeVoice.compose(1.0, null, Collections.singletonList(new TakeVoice.Stretch(0.0, 1.0, 0.0)), fast);
        ok(Math.abs(down.d[R / 2] - 0.3f) < 1e-6, "a 48 000 a second recording comes down to 24 000 at the same level");

        // the sound's own time: heard from t0 onwards, not from zero
        float[] ramp = new float[2 * R];
        for (int i = 0; i < ramp.length; i++) ramp[i] = i / (float) ramp.length;
        Wav.Pcm rampPcm = new Wav.Pcm(R, ramp);
        Wav.Pcm shifted = TakeVoice.compose(2.0, null, Collections.singletonList(new TakeVoice.Stretch(0.5, 1.0, 1.0)), rampPcm);
        ok(Math.abs(shifted.d[(int) (0.75 * R)] - ramp[(int) (1.25 * R)]) < 1e-3, "a stretch plays the sound from its own time (t0), at the take's place");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
