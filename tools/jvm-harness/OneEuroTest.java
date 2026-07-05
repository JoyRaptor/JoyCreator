import com.fadcam.ui.faditor.avatar.OneEuroFilter;
import com.fadcam.ui.faditor.avatar.DriverParamSmoother;
import java.util.HashMap;
import java.util.Map;

public class OneEuroTest {
    static int fails = 0;
    static void check(boolean c, String n) { System.out.println((c?"PASS  ":"FAIL  ")+n); if(!c) fails++; }

    public static void main(String[] a) {
        // 1. Jitter suppression: slow sine + deterministic pseudo-noise at 60Hz.
        OneEuroFilter f = new OneEuroFilter();
        long seed = 12345;
        double rawVar = 0, smoothVar = 0;
        double prevRaw = 0, prevSmooth = 0;
        for (int i = 0; i < 600; i++) {
            double t = i / 60.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double noise = ((seed >> 33) / (double) (1L << 30) - 1.0) * 0.05; // +/-0.05
            double truth = 0.5 * Math.sin(2 * Math.PI * 0.2 * t);             // slow 0.2Hz
            float raw = (float) (truth + noise);
            float smooth = f.filter(raw, t);
            if (i > 60) { // skip settle
                rawVar += Math.pow(raw - prevRaw, 2);
                smoothVar += Math.pow(smooth - prevSmooth, 2);
            }
            prevRaw = raw; prevSmooth = smooth;
        }
        check(smoothVar < rawVar * 0.15, "jitter energy cut >85% on slow+noisy signal (raw="
                + String.format("%.4f", rawVar) + " smooth=" + String.format("%.4f", smoothVar) + ")");

        // 2. Fast step tracks quickly (low lag at speed).
        OneEuroFilter f2 = new OneEuroFilter(1.0f, 0.3f, 1.0f);
        for (int i = 0; i < 60; i++) f2.filter(0f, i / 60.0);
        float v = 0;
        int framesTo90 = -1;
        for (int i = 0; i < 60; i++) {
            v = f2.filter(1f, 1.0 + i / 60.0);
            if (framesTo90 < 0 && v >= 0.9f) framesTo90 = i + 1;
        }
        check(framesTo90 > 0 && framesTo90 <= 20, "step reaches 90% within 20 frames (got " + framesTo90 + ")");

        // 3. Determinism: identical sequences -> identical outputs.
        OneEuroFilter d1 = new OneEuroFilter(), d2 = new OneEuroFilter();
        boolean same = true;
        for (int i = 0; i < 100; i++) {
            float x = (float) Math.sin(i * 0.37) * (i % 7);
            if (d1.filter(x, i / 30.0) != d2.filter(x, i / 30.0)) { same = false; break; }
        }
        check(same, "deterministic replay (bake doctrine)");

        // 4. Smoother bank: NaN dropped, params independent, reset forgets.
        DriverParamSmoother bank = new DriverParamSmoother();
        Map<String, Float> in = new HashMap<>();
        in.put("yaw", 0.5f);
        in.put("pitch", Float.NaN);
        Map<String, Float> out = bank.smooth(in, 0.0);
        check(out.containsKey("yaw") && !out.containsKey("pitch"), "NaN sample dropped, yaw kept");
        in.put("pitch", -0.25f);
        out = bank.smooth(in, 1 / 60.0);
        check(Math.abs(out.get("pitch") + 0.25f) < 1e-6, "first pitch sample passes through unfiltered");
        bank.reset();
        out = bank.smooth(java.util.Collections.singletonMap("yaw", 0.9f), 2.0);
        check(Math.abs(out.get("yaw") - 0.9f) < 1e-6, "reset() snaps to new truth (no glide)");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
