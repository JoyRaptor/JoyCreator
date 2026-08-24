import com.fadcam.ui.faditor.audio.Ducker;
import com.fadcam.ui.faditor.model.VolumeKeyframe;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

import java.util.Arrays;

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

        // ── D11: TRANSCRIPT-KEYED DUCKING ────────────────────────────────────────────
        // Word timings know a voice from a door slam; amplitude does not. Same curve shape,
        // different signal, so the shared region->keyframe stage is exercised both ways.
        System.out.println("      -- D11 transcript-keyed --");
        TranscriptWord w1 = new TranscriptWord("hello", 1000, 1400);
        TranscriptWord w2 = new TranscriptWord("there", 1500, 2000);
        List<VolumeKeyframe> t1 = Ducker.computeFromWords(
                Arrays.asList(w1, w2), 0, 0, 0, 6000, p);
        System.out.println("      keyframes: " + t1.size());
        check(!t1.isEmpty(), "D11: words produce a duck");
        check(gainAt(t1, 500) > 0.95f, "D11: full level before the first word");
        check(gainAt(t1, 1200) < 0.30f, "D11: ducked on the first word");
        check(gainAt(t1, 1450) < 0.30f, "D11: stays down in the 100ms between words");
        check(gainAt(t1, 4000) > 0.95f, "D11: recovered well after the last word");
        check(t1.size() <= 4, "D11: two adjacent words cost at most 4 keyframes (got "
                + t1.size() + ")");

        // Source-to-timeline mapping: a trimmed key clip placed later on the timeline.
        // Word at source 1000ms, clip trimmed from 500ms and placed at timeline 2000ms,
        // so it is heard at timeline 2500ms. Target starts at 2000ms -> local 500ms.
        List<VolumeKeyframe> t2 = Ducker.computeFromWords(
                Arrays.asList(new TranscriptWord("word", 1000, 1600)), 2000, 500, 2000, 6000, p);
        check(gainAt(t2, 700) < 0.30f, "D11: trim in-point and clip offset both applied");
        check(gainAt(t2, 100) > 0.95f, "D11: full level before that word arrives");

        // ── D11 NEGATIVE CONTROLS ────────────────────────────────────────────────────
        // A struck word is deleted from the render: it is never heard, so nothing may duck
        // under it. Ducking under words the viewer cannot hear is the precise failure this
        // signal is meant to be immune to.
        TranscriptWord struck = new TranscriptWord("deleted", 1000, 2000);
        struck.struck = true;
        check(Ducker.computeFromWords(Arrays.asList(struck), 0, 0, 0, 6000, p).isEmpty(),
                "NEGCTRL D11: a struck (deleted) word ducks NOTHING");

        // ...but an identical UNstruck word must duck, or the check above passes on a build
        // that simply ignores every word.
        TranscriptWord kept = new TranscriptWord("kept", 1000, 2000);
        check(!Ducker.computeFromWords(Arrays.asList(kept), 0, 0, 0, 6000, p).isEmpty(),
                "NEGCTRL D11: the same word UNstruck does duck (strike check is not vacuous)");

        check(Ducker.computeFromWords(
                        java.util.Collections.<TranscriptWord>emptyList(), 0, 0, 0, 6000, p)
                        .isEmpty(),
                "NEGCTRL D11: no transcript means no keyframes, not a guess");

        // Zero-length words (a recogniser artefact) must not become zero-length regions.
        check(Ducker.computeFromWords(
                        Arrays.asList(new TranscriptWord("x", 1000, 1000)), 0, 0, 0, 6000, p)
                        .isEmpty(),
                "NEGCTRL D11: a zero-length word is discarded, not ducked under");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
