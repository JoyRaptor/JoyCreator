import com.fadcam.ui.faditor.audio.Ducker;
import com.fadcam.ui.faditor.model.VolumeKeyframe;

import java.util.List;

/**
 * C5.E — sidechain ducking, off device.
 *
 * <p>The three ways a ducker is wrong in practice, each measured here rather than listened for:
 * it ducks when nobody is speaking, it fails to duck when somebody is, or it pumps — bringing
 * the music back up in the gap between two sentences. The last one is the reason the bridge
 * exists and is the check most worth keeping.</p>
 *
 * <p>Sparseness is also a correctness property, not a nicety. The whole premise of C5.E is that
 * the result is an ORDINARY editable curve; a keyframe every analysis frame would satisfy every
 * gain assertion below and still be useless to a human, so the count is asserted too.</p>
 */
public class DuckerTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int FPS = 100;              // 10ms frames

    /** Envelope of `len` frames, loud inside each [from,to) frame pair. */
    static int[] speech(int len, int[]... spans) {
        int[] e = new int[len];
        for (int i = 0; i < len; i++) e[i] = 2;   // quiet room tone, not digital silence
        for (int[] s : spans) {
            for (int i = s[0]; i < Math.min(len, s[1]); i++) e[i] = 100;
        }
        return e;
    }

    /** Gain at a clip-local time, reading the keyframe list the way the model does. */
    static float gainAt(List<VolumeKeyframe> kfs, long ms) {
        if (kfs.isEmpty()) return 1f;
        if (ms <= kfs.get(0).timeMs) return kfs.get(0).volume;
        for (int i = 1; i < kfs.size(); i++) {
            VolumeKeyframe a = kfs.get(i - 1), b = kfs.get(i);
            if (ms <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) return b.volume;
                float t = (ms - a.timeMs) / (float) span;
                return a.volume + t * (b.volume - a.volume);
            }
        }
        return kfs.get(kfs.size() - 1).volume;
    }

    public static void main(String[] args) {
        Ducker.Params p = new Ducker.Params();

        // ── 1. One sentence: 1000ms..2000ms of the key track ──────────────────────────
        // Target starts at 0 and runs 6s, so timeline ms == clip-local ms here.
        int[] one = speech(600, new int[]{100, 200});
        List<VolumeKeyframe> kfs = Ducker.compute(one, FPS, 0, 0, 6000, p);
        System.out.println("      keyframes: " + kfs.size());
        for (VolumeKeyframe kf : kfs) {
            System.out.println("        " + kf.timeMs + "ms -> " + kf.volume);
        }
        check(!kfs.isEmpty(), "a sentence produces a duck at all");
        check(gainAt(kfs, 500) > 0.95f, "music is at full level well BEFORE the voice");
        check(gainAt(kfs, 1500) < 0.30f, "music is ducked WHILE the voice is talking");
        check(gainAt(kfs, 4000) > 0.95f, "music has recovered long after the voice stops");
        check(kfs.size() <= 4, "one sentence costs at most 4 keyframes (editable, got "
                + kfs.size() + ")");

        // ── 2. PUMPING — two sentences with a short gap must stay down THROUGH it ──────
        // This is the check that separates a usable ducker from an irritating one.
        int[] two = speech(600, new int[]{100, 180}, new int[]{210, 300});
        List<VolumeKeyframe> k2 = Ducker.compute(two, FPS, 0, 0, 6000, p);
        float inGap = gainAt(k2, 1950);      // 1800ms..2100ms is the gap between sentences
        System.out.println("      gain in the 300ms gap: " + inGap);
        check(inGap < 0.30f, "music does NOT surge back up in a short gap (no pumping)");
        check(k2.size() <= 4, "two bridged sentences still cost at most 4 keyframes (got "
                + k2.size() + ")");

        // ── 3. A genuinely long gap SHOULD recover ────────────────────────────────────
        // Otherwise check 2 could be satisfied by a ducker that simply never comes back up.
        int[] far = speech(600, new int[]{50, 100}, new int[]{400, 450});
        List<VolumeKeyframe> k3 = Ducker.compute(far, FPS, 0, 0, 6000, p);
        check(gainAt(k3, 2500) > 0.95f, "music DOES recover across a long gap (bridge is not "
                + "just 'never come back')");
        check(k3.size() > 4, "two separate regions produce two dips (got " + k3.size() + ")");

        // ── 4. Timeline offsets: the target does not start at zero ────────────────────
        // Key speaks at timeline 3000..4000; target clip starts at timeline 2000.
        int[] off = speech(600, new int[]{300, 400});
        List<VolumeKeyframe> k4 = Ducker.compute(off, FPS, 0, 2000, 6000, p);
        check(gainAt(k4, 1500) < 0.30f, "offset target ducks at the right LOCAL time "
                + "(timeline 3500 = local 1500)");
        check(gainAt(k4, 200) > 0.95f, "offset target is at full level at its own start");

        // ── 5. Voice already talking when the clip starts ─────────────────────────────
        // The dip begins before the clip exists; the clip must begin ALREADY ducked rather
        // than starting loud underneath a voice mid-sentence.
        int[] early = speech(600, new int[]{0, 200});
        List<VolumeKeyframe> k5 = Ducker.compute(early, FPS, 0, 500, 6000, p);
        check(gainAt(k5, 0) < 0.60f, "a clip starting mid-sentence starts already ducked");

        // ── NEGATIVE CONTROLS ─────────────────────────────────────────────────────────
        // Every check above passes trivially if the ducker just always ducks, or always does
        // nothing. These fix that.
        int[] silent = speech(600);                       // room tone only, no speech
        check(Ducker.compute(silent, FPS, 0, 0, 6000, p).isEmpty(),
                "NEGCTRL: a silent key track produces NO keyframes (does not duck at random)");

        Ducker.Params none = new Ducker.Params();
        none.duckMultiplier = 1.0f;                       // "duck by nothing"
        check(Ducker.compute(one, FPS, 0, 0, 6000, none).isEmpty(),
                "NEGCTRL: duckMultiplier 1.0 leaves the envelope untouched, not flat 1.0s");

        Ducker.Params deaf = new Ducker.Params();
        deaf.thresholdFraction = 1.5f;                    // above any possible peak
        check(Ducker.compute(one, FPS, 0, 0, 6000, deaf).isEmpty(),
                "NEGCTRL: a threshold above the peak ducks nothing (threshold is real)");

        Ducker.Params deep = new Ducker.Params();
        deep.duckMultiplier = 0.05f;
        float shallow = gainAt(Ducker.compute(one, FPS, 0, 0, 6000, p), 1500);
        float deeper = gainAt(Ducker.compute(one, FPS, 0, 0, 6000, deep), 1500);
        check(deeper < shallow, "NEGCTRL: a deeper duckMultiplier really ducks deeper ("
                + shallow + " -> " + deeper + ")");

        // ── Degenerate inputs must not throw ──────────────────────────────────────────
        check(Ducker.compute(new int[0], FPS, 0, 0, 6000, p).isEmpty(), "empty envelope is safe");
        check(Ducker.compute(one, 0, 0, 0, 6000, p).isEmpty(), "zero frame-rate is safe");
        check(Ducker.compute(one, FPS, 0, 0, 0, p).isEmpty(), "zero-duration target is safe");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
