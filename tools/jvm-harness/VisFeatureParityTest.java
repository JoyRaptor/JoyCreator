import com.fadcam.vislab.audio.FeatureExtractor;
import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.audio.SpectrumAnalyzer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The phone must hear music exactly the way the browser pilot does, or a look tuned in the
 * look book moves differently in the app. Feeds the same synthetic 124 bpm beat through the
 * Java SpectrumAnalyzer + FeatureExtractor and compares with the numbers the pilot's own
 * JavaScript produced (fixtures/vislab_features.json, from vislab_parity.mjs).
 */
public class VisFeatureParityTest {

    static final int SR = 48000, N = 2048, HOP = SR / 60;

    static float[] signal(int total) {
        float[] out = new float[total];
        long seed = 12345;
        double beat = SR * 60.0 / 124;
        for (int i = 0; i < total; i++) {
            seed = (seed * 1103515245L + 12345L) & 0xFFFFFFFFL;
            double noise = ((seed >>> 8) / 16777216.0) * 2 - 1;
            double tb = i % beat;
            double kt = tb / SR;
            double kick = tb < SR * 0.35 ? Math.sin(2 * Math.PI * (45 + 115 * Math.exp(-kt * 30)) * kt) * Math.exp(-kt * 9) : 0;
            double half = (i + beat / 2) % beat;
            double hat = half < SR * 0.04 ? noise * 0.25 * Math.exp(-(half / SR) * 90) : 0;
            double pad = 0.12 * Math.sin(2 * Math.PI * 220 * i / SR) + 0.08 * Math.sin(2 * Math.PI * 330 * i / SR + 1.3);
            out[i] = (float) (kick * 0.8 + hat + pad * (i > SR * 4 ? 1 : 0.3));
        }
        return out;
    }

    static int fails = 0;

    static void near(String what, int frame, double want, double got, double tol) {
        if (Math.abs(want - got) > tol) {
            fails++;
            if (fails < 25) System.out.printf("  FRAME %d %s: pilot %.6f  java %.6f%n", frame, what, want, got);
        }
    }

    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("tools/jvm-harness/fixtures/vislab_features.json")), StandardCharsets.UTF_8);
        JsonObject ref = JsonParser.parseString(json).getAsJsonObject();
        int frames = ref.get("frames").getAsInt();
        float[] sig = signal(N + frames * HOP);
        SpectrumAnalyzer sa = new SpectrumAnalyzer(N);
        FeatureExtractor fx = new FeatureExtractor(SR, N);
        float[] block = new float[N];
        JsonArray samples = ref.getAsJsonArray("samples");
        int si = 0;
        int kicks = 0;
        for (int f = 0; f < frames; f++) {
            int end = N + f * HOP;
            System.arraycopy(sig, end - N, block, 0, N);
            Features fr = fx.update(sa.analyze(block), block, null, 1.0 / 60);
            if (fr.punch > 0) kicks++;
            if (si < samples.size() && samples.get(si).getAsJsonObject().get("f").getAsInt() == f) {
                JsonObject s = samples.get(si++).getAsJsonObject();
                double tol = 2e-3;
                near("bass", f, d(s, "bass"), fr.bass, tol);
                near("mid", f, d(s, "mid"), fr.mid, tol);
                near("high", f, d(s, "high"), fr.high, tol);
                near("level", f, d(s, "level"), fr.level, tol);
                near("voice", f, d(s, "voice"), fr.voice, tol);
                near("bright", f, d(s, "bright"), fr.bright, tol);
                near("kick", f, d(s, "kick"), fr.kick, tol);
                near("hat", f, d(s, "hat"), fr.hat, tol);
                near("beat", f, d(s, "beat"), fr.beat, tol);
                near("beatPhase", f, d(s, "beatPhase"), fr.beatPhase, tol);
                near("punch", f, d(s, "punch"), fr.punch, tol);
                near("bpm", f, d(s, "bpm"), fr.bpm, 0.05);
                near("spec40", f, d(s, "spec40"), fr.spec[40], tol);
                near("spec90", f, d(s, "spec90"), fr.spec[90], tol);
                near("raw20", f, d(s, "raw20"), fr.raw[20], tol);
                near("wave100", f, d(s, "wave100"), fr.waveL[100], tol);
            }
        }
        int wantKicks = ref.get("kicks").getAsInt();
        if (kicks != wantKicks) {
            fails++;
            System.out.println("  kicks: pilot " + wantKicks + "  java " + kicks);
        }
        System.out.println("VisFeatureParityTest: " + samples.size() + " sampled frames, " + kicks + " kicks, "
                + (fails == 0 ? "PASS" : fails + " mismatches FAIL"));
        if (fails > 0) System.exit(1);
    }

    static double d(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }
}
