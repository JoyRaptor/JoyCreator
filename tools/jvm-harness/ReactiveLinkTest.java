import com.fadcam.ui.faditor.audio.AudioReactiveLinker;
import com.fadcam.ui.faditor.model.VolumeKeyframe;

import java.util.List;

/**
 * D8 — audio-reactive property links, off device.
 *
 * <p>The three ways this is wrong in practice: it misses the beat (peaks flattened by
 * over-smoothing or by simplification), it never rests (noise drives the property forever), or
 * it floods the timeline with keyframes nobody can edit. Each is measured here, and each has a
 * control proving the check is not vacuous.</p>
 */
public class ReactiveLinkTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static final int FPS = 100;                  // 10ms frames

    /** Band envelope: quiet floor with a decaying hit at each given frame. */
    static int[] beats(int len, int floor, int amp, int... at) {
        int[] e = new int[len];
        for (int i = 0; i < len; i++) e[i] = floor;
        for (int f : at) {
            for (int k = 0; k < 25 && f + k < len; k++) {
                int v = (int) (amp * Math.exp(-k / 6.0));
                if (v > e[f + k]) e[f + k] = v;
            }
        }
        return e;
    }

    static float valueAt(List<VolumeKeyframe> kfs, long ms) {
        if (kfs.isEmpty()) return Float.NaN;
        if (ms <= kfs.get(0).timeMs) return kfs.get(0).volume;
        for (int i = 1; i < kfs.size(); i++) {
            VolumeKeyframe a = kfs.get(i - 1), b = kfs.get(i);
            if (ms <= b.timeMs) {
                long span = b.timeMs - a.timeMs;
                if (span <= 0) return b.volume;
                return a.volume + (ms - a.timeMs) / (float) span * (b.volume - a.volume);
            }
        }
        return kfs.get(kfs.size() - 1).volume;
    }

    static float maxOf(List<VolumeKeyframe> kfs) {
        float m = -Float.MAX_VALUE;
        for (VolumeKeyframe k : kfs) m = Math.max(m, k.volume);
        return m;
    }

    static float minOf(List<VolumeKeyframe> kfs) {
        float m = Float.MAX_VALUE;
        for (VolumeKeyframe k : kfs) m = Math.min(m, k.volume);
        return m;
    }

    public static void main(String[] args) {
        AudioReactiveLinker.Params p = new AudioReactiveLinker.Params();

        // ── 1. Four beats, one second apart ──────────────────────────────────────────
        int[] env = beats(600, 3, 200, 100, 200, 300, 400);
        List<VolumeKeyframe> k = AudioReactiveLinker.link(env, FPS, 0, 0, 6000, p);
        System.out.println("      keyframes: " + k.size()
                + "  range " + minOf(k) + ".." + maxOf(k));
        check(!k.isEmpty(), "a beat track drives the property at all");

        // Peaks must land ON the hits and the property must REST between them. Both halves
        // matter: a curve pinned high scores well on one and fails the other.
        float atBeat = valueAt(k, 1020);
        float between = valueAt(k, 1700);
        System.out.println("      at beat " + atBeat + "   between " + between);
        check(atBeat > 1.30f, "property rises on the beat (" + atBeat + " > 1.30)");
        check(between < 1.10f, "property RESTS between beats (" + between + " < 1.10)");
        check(maxOf(k) <= p.outMax + 1e-3f && minOf(k) >= p.outMin - 1e-3f,
                "output stays inside [outMin, outMax]");

        // ── 2. SPARSITY — the property this feature lives or dies by ─────────────────
        // 600 frames of input. A keyframe per frame is a correct answer and an unusable one.
        System.out.println("      600 input frames -> " + k.size() + " keyframes");
        check(k.size() < 60, "output is sparse enough to hand-edit (" + k.size() + " < 60)");
        check(k.size() >= 8, "but not so sparse the beats are lost (" + k.size() + " >= 8)");

        // ── 3. Simplification must be REAL, not a fixed decimation ───────────────────
        AudioReactiveLinker.Params coarse = new AudioReactiveLinker.Params();
        coarse.simplifyTolerance = 0.40f;
        AudioReactiveLinker.Params fine = new AudioReactiveLinker.Params();
        fine.simplifyTolerance = 0.0005f;
        int nCoarse = AudioReactiveLinker.link(env, FPS, 0, 0, 6000, coarse).size();
        int nFine = AudioReactiveLinker.link(env, FPS, 0, 0, 6000, fine).size();
        System.out.println("      coarse " + nCoarse + "  fine " + nFine);
        check(nCoarse < nFine, "a coarser tolerance really yields fewer keyframes");
        check(nFine > k.size(), "a finer tolerance really yields more");

        // A tall, narrow spike is exactly what simplification tends to eat. It must survive.
        int[] spike = beats(600, 3, 200, 300);
        List<VolumeKeyframe> ks = AudioReactiveLinker.link(spike, FPS, 0, 0, 6000, p);
        check(maxOf(ks) > 1.30f, "a lone transient SURVIVES simplification (" + maxOf(ks) + ")");

        // ── 4. Timeline offsets ─────────────────────────────────────────────────────
        // Band starts at timeline 2000; target starts at 1000, so a beat at band-local 1000ms
        // is timeline 3000 and target-local 2000.
        List<VolumeKeyframe> k4 =
                AudioReactiveLinker.link(beats(600, 3, 200, 100), FPS, 2000, 1000, 6000, p);
        check(valueAt(k4, 2000) > 1.30f, "offsets map the hit to the right target-local time");
        check(valueAt(k4, 200) < 1.10f, "and the property is at rest before it");

        // ── NEGATIVE CONTROLS ───────────────────────────────────────────────────────
        // Silence must not drive anything. Without this, every check above passes on a
        // generator that simply always moves the property.
        check(AudioReactiveLinker.link(new int[600], FPS, 0, 0, 6000, p).isEmpty(),
                "NEGCTRL: a digitally silent band produces NO keyframes");

        // Constant room tone is the case that broke the ducker: measured against the PEAK
        // alone, a flat signal reads as permanently loud.
        int[] flat = new int[600];
        java.util.Arrays.fill(flat, 40);
        List<VolumeKeyframe> kf = AudioReactiveLinker.link(flat, FPS, 0, 0, 6000, p);
        System.out.println("      constant tone -> " + kf.size() + " keyframes, max " + maxOf(kf));
        check(kf.size() <= 3, "NEGCTRL: constant tone yields a FLAT curve, not a busy one ("
                + kf.size() + ")");

        AudioReactiveLinker.Params noop = new AudioReactiveLinker.Params();
        noop.outMax = noop.outMin;
        check(AudioReactiveLinker.link(env, FPS, 0, 0, 6000, noop).isEmpty(),
                "NEGCTRL: a zero-width output range leaves the property untouched");

        // A floor above the peak must silence the effect, or the floor is decorative.
        AudioReactiveLinker.Params deaf = new AudioReactiveLinker.Params();
        deaf.noiseFloorFraction = 1.5f;
        List<VolumeKeyframe> kd = AudioReactiveLinker.link(env, FPS, 0, 0, 6000, deaf);
        check(kd.isEmpty() || maxOf(kd) < 1.05f,
                "NEGCTRL: a noise floor above the peak stops the effect");

        // ── Degenerate inputs must not throw ────────────────────────────────────────
        check(AudioReactiveLinker.link(new int[0], FPS, 0, 0, 6000, p).isEmpty(), "empty is safe");
        check(AudioReactiveLinker.link(env, 0, 0, 0, 6000, p).isEmpty(), "zero fps is safe");
        check(AudioReactiveLinker.link(env, FPS, 0, 0, 0, p).isEmpty(), "zero duration is safe");
        check(AudioReactiveLinker.link(env, FPS, 0, 900_000, 6000, p).isEmpty(),
                "a target entirely outside the band's span is safe");

        // A long track must not blow the stack — the recursive form of RDP dies on exactly
        // the input this feature exists for.
        int[] longEnv = new int[100 * 60 * 5];      // five minutes at 100 fps
        for (int i = 0; i < longEnv.length; i++) longEnv[i] = (i % 97) * 2;
        List<VolumeKeyframe> kl =
                AudioReactiveLinker.link(longEnv, FPS, 0, 0, 5 * 60_000L, p);
        check(!kl.isEmpty(), "a five-minute track survives simplification (" + kl.size() + " kfs)");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
