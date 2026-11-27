import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.studio.Acting;
import cc.joycreator.avatar.studio.TakeClock;
import cc.joycreator.avatar.studio.VoiceMouth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Avatar Studio inside Video Studio (slice 2 and 2b): the take on Studio's clock, a split's right half, the voice to a
 * mouth (the pilot's listen), and acting from a video (only the ticked things write; a lost face holds; one source per
 * thing followed; the plan round-trips).
 */
public class AvatarStudioTest {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL " + what); }
    }

    static Take take(int n) {
        Take t = new Take();
        t.n = n;
        List<Object> tx = new ArrayList<>();
        for (int i = 0; i < n; i++) tx.add(i / 10.0);
        t.ch.put("turnX", tx);
        t.ch.put("vis", new ArrayList<>(Arrays.asList("Neutral", "Aa", "Ee")));   // shorter: holds "Ee"
        List<Object> hl = new ArrayList<>(); hl.add(0.5); hl.add(null); hl.add(0.7);
        t.ch.put("hLx", hl);
        return t;
    }

    static void clock() {
        Take t = take(90);
        check(TakeClock.lengthMs(t) == 3000, "90 frames at 30 are " + TakeClock.lengthMs(t) + " ms");
        check(TakeClock.frameAt(t, 0) == 0 && TakeClock.frameAt(t, 33) == 0 && TakeClock.frameAt(t, 34) == 1, "frame of 33/34 ms");
        check(TakeClock.frameAt(t, 1000) == 30, "frame at one second is " + TakeClock.frameAt(t, 1000));
        check(TakeClock.frameAt(t, -500) == 0 && TakeClock.frameAt(t, 99999) == 89, "before / after the take clamp");
        Map<String, Object> s = TakeClock.sample(t, 1000);
        check(Math.abs((Double) s.get("turnX") - 3.0) < 1e-9, "turnX at one second " + s.get("turnX"));
        check("Ee".equals(s.get("vis")), "a short lane holds its last value: " + s.get("vis"));
        check(!TakeClock.sample(t, 34).containsKey("hLx"), "a null (not driven) sample is left out");
        check(TakeClock.lengthMs(new Take()) == 33, "an empty take still lasts a frame");

        Take r = TakeClock.sliceFrom(t, 1000);
        check(r.n == 60, "right half has " + r.n + " frames");
        boolean same = true;
        for (long ms = 0; ms < 2000; ms += 17) {
            Object a = TakeClock.sample(t, ms + 1000).get("turnX"), b = TakeClock.sample(r, ms).get("turnX");
            if (!a.equals(b)) same = false;
        }
        check(same, "the right half does not carry on where the item was");
        check("Ee".equals(TakeClock.sample(r, 0).get("vis")), "a held lane keeps holding after the cut");
        check(t.n == 90, "slicing changed the original");
    }

    static VoiceMouth.Samples tone(double hz, double amp, int rate, double seconds) {
        int n = (int) (rate * seconds);
        return new VoiceMouth.Samples() {
            public int count() { return n; }
            public float at(int i) { return (float) (amp * Math.sin(2 * Math.PI * hz * i / rate)); }
        };
    }

    static VoiceMouth.Samples noise(double amp, int rate, double seconds) {
        int n = (int) (rate * seconds);
        java.util.Random r = new java.util.Random(3);
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = (float) (amp * (r.nextDouble() * 2 - 1));
        // keep the hiss high: difference twice (a crude high-pass)
        for (int k = 0; k < 2; k++) for (int i = n - 1; i > 0; i--) d[i] = (d[i] - d[i - 1]) / 2f;
        return new VoiceMouth.Samples() {
            public int count() { return n; }
            public float at(int i) { return d[i]; }
        };
    }

    static String last(VoiceMouth.Samples s, int rate) {
        Object[] f = VoiceMouth.frames(s, rate, 0, 30, 20);
        return ((String[]) f[1])[19];
    }

    static void voice() {
        int rate = 22050;
        check(new VoiceMouth(rate).windowSize() == 512, "window at 22 050 is " + new VoiceMouth(rate).windowSize());
        check(new VoiceMouth(48000).windowSize() == 1024, "window at 48 000 is not the pilot's 1024");
        Object[] quiet = VoiceMouth.frames(tone(300, 0.0, rate, 1), rate, 0, 30, 20);
        check(((float[]) quiet[0])[10] == 0f && "Neutral".equals(((String[]) quiet[1])[10]), "silence opens the mouth");
        Object[] loud = VoiceMouth.frames(tone(300, 0.5, rate, 1), rate, 0, 30, 20);
        float o = ((float[]) loud[0])[15];
        check(o > 0.9f, "a loud voice opens the mouth only " + o);
        Object[] soft = VoiceMouth.frames(tone(300, 0.04, rate, 1), rate, 0, 30, 20);
        float so = ((float[]) soft[0])[15];
        check(so > 0.05f && so < o, "a soft voice opens it " + so + " (loud " + o + ")");
        float tenth = ((float[]) VoiceMouth.frames(tone(330, 0.1, rate, 1), rate, 0, 30, 20)[0])[15];
        double want = Math.pow((0.1 / Math.sqrt(2) - 0.012) / 0.13, 0.65);
        check(Math.abs(tenth - want) < 0.01, "a tenth-scale voice opens " + tenth + ", the pilot " + want);
        // the pilot's bands: 200-520 Hz is "low" (a round mouth), 520-1250 the middle (an open one)
        String low = last(tone(330, 0.5, rate, 1), rate);
        check("Oh".equals(low), "a 330 Hz hum makes '" + low + "', the pilot makes 'Oh'");
        String mid = last(tone(800, 0.5, rate, 1), rate);
        check("Aa".equals(mid), "an 800 Hz tone makes '" + mid + "', the pilot makes 'Aa'");
        String lowSoft = last(tone(330, 0.03, rate, 1), rate);
        check("W-Oo".equals(lowSoft), "a soft 330 Hz hum makes '" + lowSoft + "', the pilot makes 'W-Oo'");
        String hiss = last(noise(0.9, rate, 1), rate);
        check("S".equals(hiss), "a hiss makes '" + hiss + "', the pilot makes 'S'");
    }

    static Map<String, Float> face(float turn, float mouth, float vis, float blink) {
        Map<String, Float> m = new HashMap<>();
        m.put("turnX", turn); m.put("turnY", 0.1f); m.put("roll", -0.2f);
        m.put("mouth", mouth); m.put("vis", vis); m.put("blinkL", blink); m.put("blinkR", blink);
        m.put("lookX", 0f); m.put("lookY", 0f); m.put("browL", 0.3f); m.put("browR", 0.3f); m.put("leanX", 0.4f);
        return m;
    }

    static final String[] SHAPES = {"Neutral", "Aa", "Ee", "Oh"};

    static void acting() {
        Take base = take(10);
        List<Map<String, Float>> frames = new ArrayList<>();
        for (int i = 0; i < 20; i++) frames.add(i == 5 || i == 6 ? null : face(i / 20f, 0.5f, i % 4, 0f));
        Take t = Acting.write(base, 20, frames, EnumSet.of(Acting.Listen.LIPS), SHAPES);
        check(t.n == 20, "the take grows to the item: " + t.n);
        check(t.ch.get("turnX").equals(base.ch.get("turnX")), "head not ticked, but turnX changed");
        check(t.ch.get("hLx").equals(base.ch.get("hLx")), "a hand lane changed");
        check("Ee".equals(t.ch.get("vis").get(2)) && "Oh".equals(t.ch.get("vis").get(3)), "vis is not the shape's name");
        check(t.ch.get("vis").get(5).equals(t.ch.get("vis").get(4)), "a lost face does not hold the last mouth");
        check(base.ch.get("vis").size() == 3, "write changed the take it was given");

        Take h = Acting.write(base, 20, frames, EnumSet.of(Acting.Listen.HEAD, Acting.Listen.LEANING), SHAPES);
        check(Math.abs((Double) h.ch.get("turnX").get(7) - 7 / 20.0) < 1e-6, "head: turnX not written");
        check(Math.abs((Double) h.ch.get("sway").get(0) - 0.4) < 1e-6, "leaning is not the body's sway");
        check(h.ch.get("vis").equals(base.ch.get("vis")), "lips not ticked, but vis changed");
        check(!h.ch.containsKey("blinkL"), "eyes not ticked, but blinks written");

        // no face at all before frame 3: the take keeps what it had there
        List<Map<String, Float>> late = new ArrayList<>();
        for (int i = 0; i < 10; i++) late.add(i < 3 ? null : face(0.9f, 0f, 0, 1f));
        Take k = Acting.write(base, 10, late, EnumSet.of(Acting.Listen.HEAD), SHAPES);
        check(k.ch.get("turnX").get(1).equals(base.ch.get("turnX").get(1)), "before the first face the take was overwritten");

        Take lips = Acting.writeLips(base, 12, new float[]{0f, 0.5f}, new String[]{"Neutral", "Aa"});
        check(lips.ch.get("mouth").size() == 12 && "Aa".equals(lips.ch.get("vis").get(1)), "lips from a sound");
        check(lips.ch.get("turnX").equals(base.ch.get("turnX")), "lips from a sound changed the head");
        Take late2 = Acting.writeLips(base, 12, 2, new float[]{0.9f}, new String[]{"Oh"});
        check("Aa".equals(late2.ch.get("vis").get(1)) && "Oh".equals(late2.ch.get("vis").get(2)), "lips from frame 2 on: " + late2.ch.get("vis"));

        Acting.Plan p = new Acting.Plan();
        p.tick("videoA", Acting.Listen.HEAD, true);
        p.tick("videoA", Acting.Listen.LIPS, true);
        p.tick("videoB", Acting.Listen.HEAD, true);
        check(!p.source("videoA").listen.contains(Acting.Listen.HEAD) && p.source("videoB").listen.contains(Acting.Listen.HEAD),
                "the head is followed from two videos at once");
        p.source("videoB").nudgeMs = -250;
        p.keepAsDriver = true;
        p.source("videoA").readAt = Acting.fingerprint(1000, 0, 5000, 1f, 0);
        Acting.Plan q = Acting.Plan.fromJson(p.toJson());
        check(q.keepAsDriver && q.source("videoB").nudgeMs == -250 && q.source("videoA").listen.equals(EnumSet.of(Acting.Listen.LIPS))
                && q.source("videoA").readAt.equals(p.source("videoA").readAt), "the plan does not round-trip");
        p.tick("videoA", Acting.Listen.LIPS, false);
        p.tidy();
        check(p.source("videoA") == null && p.sources.size() == 1, "a video ticked for nothing stays in the plan");
        check(!Acting.fingerprint(1000, 0, 5000, 1f, 0).equals(Acting.fingerprint(1200, 0, 5000, 1f, 0)), "moving the video is not seen");
        check(!Acting.fingerprint(1000, 0, 5000, 1f, 0).equals(Acting.fingerprint(1000, 0, 4000, 1f, 0)), "trimming the video is not seen");
        check(!Acting.fingerprint(1000, 0, 5000, 1f, 0).equals(Acting.fingerprint(1000, 0, 5000, 1f, 100)), "a nudge is not seen");
    }

    static void resample() {
        double[] times = {0, 0.1, 0.2, 0.3};
        List<Map<String, Float>> sm = new ArrayList<>();
        sm.add(face(0f, 0f, 1, 0f)); sm.add(face(1f, 0f, 2, 0f)); sm.add(null); sm.add(face(0.5f, 0f, 3, 0f));
        List<Map<String, Float>> fr = Acting.resample(times, sm, 30, 12);
        check(fr.size() == 12, "resampled frames " + fr.size());
        check(Math.abs(fr.get(1).get("turnX") - 1 / 3f) < 1e-4, "frame 1 (1/30 s) turnX " + fr.get(1).get("turnX"));
        check(fr.get(1).get("vis") == 1f && fr.get(2).get("vis") == 1f, "the mouth shape was blended");
        check(fr.get(3).get("turnX") == 1f, "frame 3 is not the 0.1 s sample");
        check(fr.get(4).get("turnX") == 1f, "into a lost face the last face is not held");
        check(fr.get(6) == null, "a frame at a lost face is not lost");
        check(fr.get(11).get("turnX") == 0.5f, "after the last sample it does not hold");
        check(Acting.resample(new double[]{0.2}, java.util.Collections.singletonList(face(1f, 0f, 0, 0f)), 30, 10).get(0) == null,
                "a frame before the first sample has a face");
    }

    public static void main(String[] a) {
        resample();
        clock();
        voice();
        acting();
        System.out.println((fail == 0 ? "PASSED " : "FAILED ") + pass + " checks, " + fail + " failures");
        System.exit(fail == 0 ? 0 : 1);
    }
}
