import com.fadcam.ui.faditor.avatar.FabrikSolver;
import com.fadcam.ui.faditor.avatar.LifeSignals;
import com.fadcam.ui.faditor.avatar.AudioLevelViseme;
import java.util.Map;

public class LifeMathTest {
    static int fails = 0;
    static void check(boolean c, String n) { System.out.println((c?"PASS  ":"FAIL  ")+n); if(!c) fails++; }
    static double d(float[] j, int a, int b) { return Math.hypot(j[b*2]-j[a*2], j[b*2+1]-j[a*2+1]); }

    public static void main(String[] a) {
        // FABRIK: 2-bone arm (shoulder 0,0 - elbow 1,0 - wrist 2,0), reach (1.2, 0.9)
        float[] arm = {0,0, 1,0, 2,0};
        boolean reached = FabrikSolver.solve(arm, 1.2f, 0.9f);
        check(reached, "2-bone target reachable");
        check(Math.abs(arm[0]) < 1e-6 && Math.abs(arm[1]) < 1e-6, "base stays pinned");
        check(Math.abs(d(arm,0,1) - 1.0) < 1e-3 && Math.abs(d(arm,1,2) - 1.0) < 1e-3, "bone lengths rigid");
        check(Math.hypot(arm[4]-1.2, arm[5]-0.9) < 1e-3, "effector on target");

        // Unreachable: full extension toward target, correct total length
        float[] arm2 = {0,0, 1,0, 2,0};
        boolean r2 = FabrikSolver.solve(arm2, 5f, 0f);
        check(!r2 && Math.abs(arm2[4] - 2.0) < 1e-3 && Math.abs(arm2[5]) < 1e-3, "unreachable = straight full extension");

        // 3-bone chain converges too
        float[] tail = {0,0, 0.5f,0, 1.0f,0, 1.5f,0};
        FabrikSolver.solve(tail, 0.4f, 0.8f);
        check(Math.hypot(tail[6]-0.4, tail[7]-0.8) < 5e-3, "3-bone effector converges");

        // Determinism
        float[] x1 = {0,0, 1,0, 2,0}, x2 = {0,0, 1,0, 2,0};
        FabrikSolver.solve(x1, 1.1f, 0.4f); FabrikSolver.solve(x2, 1.1f, 0.4f);
        check(java.util.Arrays.equals(x1, x2), "FABRIK deterministic");

        // LifeSignals: silent input -> gate engages after 2s, weight ramps to 1
        LifeSignals life = new LifeSignals(42);
        Map<String,Float> out = null;
        for (int i = 0; i <= 60*5; i++) out = life.update(i/60.0, -60f);
        check(life.getWeight() > 0.99, "idle gate + ramp reaches full weight after 5s silence");
        // Breathing oscillates
        float breathA = out.get(LifeSignals.PARAM_BREATH);
        for (int i = 0; i < 60; i++) out = life.update(5.0 + i/60.0, -60f);
        float breathB = out.get(LifeSignals.PARAM_BREATH);
        check(Math.abs(breathA - breathB) > 0.05, "breathing oscillates");
        // Speech kills life quickly
        for (int i = 0; i < 60; i++) out = life.update(6.0 + i/60.0, -20f);
        check(life.getWeight() < 0.01, "speaking fades life out within 1s");
        // Blink fired at least once during 20 idle seconds, and asymmetric lag exists
        LifeSignals l2 = new LifeSignals(7);
        boolean blinked = false, asym = false;
        for (int i = 0; i <= 60*20; i++) {
            Map<String,Float> o = l2.update(i/60.0, -60f);
            float bl = o.get(LifeSignals.PARAM_BLINK_L), br = o.get(LifeSignals.PARAM_BLINK_R);
            if (bl > 0.5f || br > 0.5f) blinked = true;
            if (Math.abs(bl - br) > 0.2f) asym = true;
        }
        check(blinked, "blinks fire during idle");
        check(asym, "blink is asymmetric (one eye lags)");
        // Determinism: same seed same sequence
        LifeSignals d1 = new LifeSignals(99), d2 = new LifeSignals(99);
        boolean same = true;
        for (int i = 0; i < 600; i++) {
            if (!d1.update(i/60.0, -60f).equals(d2.update(i/60.0, -60f))) { same = false; break; }
        }
        check(same, "LifeSignals deterministic per seed (bake doctrine)");

        // AudioLevelViseme: silence closed, loud opens fast, closes slower
        AudioLevelViseme v = new AudioLevelViseme();
        v.update(0, -60f);
        check(v.current() < 0.01f, "silence = closed");
        int framesToOpen = 0;
        for (int i = 1; i <= 30; i++) { v.update(i/60.0, -12f); if (v.current() < 0.9f) framesToOpen = i; }
        check(v.current() > 0.9f && framesToOpen <= 12, "loud opens jaw fast (<=12 frames to 90%, got " + framesToOpen + ")");
        int framesToClose = 0;
        for (int i = 1; i <= 60; i++) { v.update(0.5 + i/60.0, -60f); if (v.current() > 0.1f) framesToClose = i; }
        check(framesToClose > framesToOpen, "release slower than attack (close=" + framesToClose + ")");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
