import com.fadcam.ui.faditor.avatar.AudioLevelViseme;
import com.fadcam.ui.faditor.avatar.FabrikSolver;
import com.fadcam.ui.faditor.avatar.SyntheticTrackingSource;
import com.fadcam.ui.faditor.avatar.TrackingDriverBus;
import com.fadcam.ui.faditor.avatar.TrackingFrame;
import com.fadcam.ui.faditor.avatar.TrackingParamPipeline;
import com.fadcam.ui.faditor.avatar.TrackingSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A2 tracking core: pipeline order/determinism, bus mount safety, FABRIK re-aim. */
public class TrackingCoreTest {
    static int fails = 0;
    static void check(boolean c, String n) { System.out.println((c?"PASS  ":"FAIL  ")+n); if(!c) fails++; }

    static TrackingFrame frame(double t, float yaw, float audioDb) {
        Map<String, Float> p = new HashMap<>();
        p.put("yaw", yaw);
        return new TrackingFrame(t, p, audioDb);
    }

    public static void main(String[] a) throws Exception {
        // ── 1. Pipeline smooths jittery input (One-Euro is in the path) ──
        TrackingParamPipeline pl = new TrackingParamPipeline(7);
        long seed = 424242;
        double rawVar = 0, outVar = 0; float prevRaw = 0, prevOut = 0;
        for (int i = 0; i < 300; i++) {
            double t = i / 30.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float noise = (float) ((seed >> 33) / (double) (1L << 30) - 1.0) * 0.06f;
            float raw = (float) (0.7 * Math.sin(2 * Math.PI * 0.15 * t)) + noise;
            float out = pl.process(frame(t, raw, Float.NaN)).get("yaw");
            if (i > 30) {
                rawVar += Math.pow(raw - prevRaw, 2);
                outVar += Math.pow(out - prevOut, 2);
            }
            prevRaw = raw; prevOut = out;
        }
        check(outVar < rawVar * 0.2, "pipeline cuts jitter energy >80% (raw="
                + String.format("%.4f", rawVar) + " out=" + String.format("%.4f", outVar) + ")");

        // ── 2. Deterministic replay: same seed + frames → identical params ──
        TrackingParamPipeline d1 = new TrackingParamPipeline(99), d2 = new TrackingParamPipeline(99);
        boolean same = true;
        for (int i = 0; i < 200 && same; i++) {
            double t = i / 30.0;
            float yaw = (float) Math.sin(i * 0.21) * 0.8f;
            float db = i % 60 < 30 ? -20f : -55f;
            same = d1.process(frame(t, yaw, db)).equals(d2.process(frame(t, yaw, db)));
        }
        check(same, "deterministic replay incl. life package (bake doctrine)");

        // ── 3. jawOpen precedence: tracker beats amplitude ──
        TrackingParamPipeline p3 = new TrackingParamPipeline(1);
        Map<String, Float> in = new HashMap<>();
        in.put(AudioLevelViseme.PARAM_JAW_OPEN, 0.9f);
        Map<String, Float> out3 = p3.process(new TrackingFrame(0.0, in, -12f));
        check(Math.abs(out3.get(AudioLevelViseme.PARAM_JAW_OPEN) - 0.9f) < 1e-6,
                "tracker jawOpen wins over loud audio (first sample passes through)");

        // ── 4. jawOpen amplitude fallback + no-mic behavior ──
        TrackingParamPipeline p4 = new TrackingParamPipeline(1);
        float jaw = 0;
        for (int i = 0; i < 30; i++) {
            Map<String, Float> o = p4.process(frame(i / 30.0, 0f, -12f)); // loud
            jaw = o.get(AudioLevelViseme.PARAM_JAW_OPEN);
        }
        check(jaw > 0.8f, "loud audio opens the jaw via amplitude tier (got " + jaw + ")");
        TrackingParamPipeline p4b = new TrackingParamPipeline(1);
        Map<String, Float> o4b = p4b.process(frame(0.0, 0f, Float.NaN));
        check(!o4b.containsKey(AudioLevelViseme.PARAM_JAW_OPEN),
                "no mic + no tracker jaw → no jawOpen param (rig keeps neutral)");

        // ── 5. Life gate: silence engages idle life, speech kills it ──
        TrackingParamPipeline p5 = new TrackingParamPipeline(3);
        for (int i = 0; i <= 120; i++) p5.process(frame(i / 30.0, 0f, Float.NaN)); // 4s silent
        check(p5.lifeWeight() > 0.95, "4s of silence → life weight ~1 (got "
                + String.format("%.2f", p5.lifeWeight()) + ")");
        boolean lifeParams = p5.process(frame(4.05, 0f, Float.NaN)).containsKey("life_breath");
        check(lifeParams, "life_* params present in the merged output");
        for (int i = 0; i < 40; i++) p5.process(frame(4.1 + i / 30.0, 0f, -10f)); // speech
        check(p5.lifeWeight() < 0.05, "speech → life ramps out (got "
                + String.format("%.2f", p5.lifeWeight()) + ")");

        // ── 6. Bus: stale source can never republish after stop/swap ──
        class FakeSource implements TrackingSource {
            FrameListener captured;
            @Override public void start(FrameListener l) { captured = l; }
            @Override public void stop() {}
            void emit(double t, float yaw) { captured.onFrame(frame(t, yaw, Float.NaN)); }
        }
        TrackingDriverBus bus = new TrackingDriverBus();
        FakeSource sA = new FakeSource(), sB = new FakeSource();
        bus.start(sA, 5);
        sA.emit(0.0, 0.5f);
        check(bus.latest() != null && Math.abs(bus.latest().get("yaw") - 0.5f) < 1e-6,
                "bus publishes mounted source frames");
        bus.stop();
        sA.emit(0.1, -0.9f);
        check(bus.latest() == null, "post-stop straggler frame dropped");
        bus.start(sB, 5);
        sB.emit(0.0, 0.25f);
        sA.emit(0.2, -0.9f); // zombie thread from the OLD source
        check(Math.abs(bus.latest().get("yaw") - 0.25f) < 1e-6,
                "swapped-out source cannot clobber the new mount");
        bus.stop();

        // ── 7. Pin-target extraction ──
        Map<String, Float> pt = new HashMap<>();
        pt.put(TrackingFrame.pinTargetX("arm"), 0.7f);
        pt.put(TrackingFrame.pinTargetY("arm"), 0.4f);
        pt.put(TrackingFrame.pinTargetX("tail"), 0.2f); // half pair — must drop
        pt.put("yaw", 0.1f);
        Map<String, float[]> targets = TrackingDriverBus.extractPinTargets(pt);
        check(targets.size() == 1 && targets.containsKey("arm")
                        && Math.abs(targets.get("arm")[0] - 0.7f) < 1e-6
                        && Math.abs(targets.get("arm")[1] - 0.4f) < 1e-6,
                "pinTarget pairs extracted, half-pairs dropped, drivers ignored");

        // ── 8. FABRIK re-aim (the hookup's math contract) ──
        float[] chain = {0, 0, 0, 100, 0, 200}; // straight 2-bone arm, 100+100
        boolean reached = FabrikSolver.solve(chain, 120, 80);
        double err = Math.hypot(chain[4] - 120, chain[5] - 80);
        double l0 = Math.hypot(chain[2] - chain[0], chain[3] - chain[1]);
        double l1 = Math.hypot(chain[4] - chain[2], chain[5] - chain[3]);
        check(reached && err < 1.0, "reachable target hit (err=" + String.format("%.3f", err) + ")");
        check(Math.abs(l0 - 100) < 0.5 && Math.abs(l1 - 100) < 0.5,
                "segment lengths stay rigid (" + String.format("%.1f/%.1f", l0, l1) + ")");
        check(chain[0] == 0 && chain[1] == 0, "base pin stays fixed");
        float[] chain2 = {0, 0, 0, 100, 0, 200};
        FabrikSolver.solve(chain2, 500, 0);
        check(Math.abs(chain2[4] - 200) < 0.5 && Math.abs(chain2[5]) < 0.5,
                "unreachable target → chain extends straight toward it");

        // ── 9. SyntheticTrackingSource: deterministic frames across runs ──
        List<Map<String, Float>> run1 = collect(4), run2 = collect(4);
        check(run1.equals(run2), "synthetic source frames identical across runs");
        check(run1.get(0).containsKey(TrackingFrame.pinTargetX("arm")),
                "synthetic source emits pin targets for requested parts");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }

    static List<Map<String, Float>> collect(int n) throws InterruptedException {
        SyntheticTrackingSource src = new SyntheticTrackingSource(java.util.Arrays.asList("arm"));
        List<Map<String, Float>> frames = java.util.Collections.synchronizedList(new ArrayList<>());
        final Object done = new Object();
        src.start(f -> {
            if (frames.size() < n) {
                frames.add(new HashMap<>(f.params));
                if (frames.size() == n) { synchronized (done) { done.notifyAll(); } }
            }
        });
        synchronized (done) {
            long deadline = System.currentTimeMillis() + 3000;
            while (frames.size() < n && System.currentTimeMillis() < deadline) done.wait(100);
        }
        src.stop();
        return new ArrayList<>(frames.subList(0, Math.min(n, frames.size())));
    }
}
