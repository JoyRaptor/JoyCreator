import com.fadcam.ui.faditor.avatar.AvatarParamTrack;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM harness for AvatarParamTrack (bake-to-keyframes foundation — pure
 * Java + Gson). Run like the other harnesses: compile against the app's
 * built classes + gson + annotation-jvm (see Opencode-work.md TASK 2 recipe).
 */
public class ParamTrackTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        emptyTrackIsNeutral();
        appendAndExactLookup();
        monotonicGuardNudgesForward();
        midpointLerps();
        oneSidedKeyHolds();
        clampsOutsideRange();
        jsonRoundTrips();
        tolerantFromJson();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static void emptyTrackIsNeutral() {
        AvatarParamTrack t = new AvatarParamTrack();
        check("empty: isEmpty", t.isEmpty());
        check("empty: duration 0", t.durationMs() == 0);
        check("empty: sampleAt returns empty map", t.sampleAt(500).isEmpty());
    }

    static void appendAndExactLookup() {
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(0, map("yaw", 0.2f));
        t.add(100, map("yaw", 0.8f));
        check("exact: size", t.size() == 2);
        check("exact: duration", t.durationMs() == 100);
        check("exact: at t=0", near(t.sampleAt(0).get("yaw"), 0.2f));
        check("exact: at t=100", near(t.sampleAt(100).get("yaw"), 0.8f));
    }

    static void monotonicGuardNudgesForward() {
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(50, map("yaw", 0.1f));
        t.add(50, map("yaw", 0.2f)); // equal stamp → nudged to 51
        t.add(10, map("yaw", 0.3f)); // rewound stamp → nudged to 52
        check("monotonic: size 3", t.size() == 3);
        check("monotonic: duration 52", t.durationMs() == 52);
        check("monotonic: last wins at end", near(t.sampleAt(999).get("yaw"), 0.3f));
    }

    static void midpointLerps() {
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(0, map("yaw", -1f, "pitch", 0f));
        t.add(200, map("yaw", 1f, "pitch", 0.5f));
        Map<String, Float> mid = t.sampleAt(100);
        check("lerp: yaw midpoint", near(mid.get("yaw"), 0f));
        check("lerp: pitch midpoint", near(mid.get("pitch"), 0.25f));
        Map<String, Float> q = t.sampleAt(50);
        check("lerp: yaw quarter", near(q.get("yaw"), -0.5f));
    }

    static void oneSidedKeyHolds() {
        // Tracking loss dropped jawOpen from the second sample: it must HOLD,
        // never fade toward an invented zero.
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(0, map("yaw", 0f, "jawOpen", 0.6f));
        t.add(100, map("yaw", 1f));
        Map<String, Float> mid = t.sampleAt(50);
        check("hold: jawOpen kept", near(mid.get("jawOpen"), 0.6f));
        check("hold: yaw still lerps", near(mid.get("yaw"), 0.5f));
        // And the mirror case: key only on the RIGHT side appears held too.
        AvatarParamTrack u = new AvatarParamTrack();
        u.add(0, map("yaw", 0f));
        u.add(100, map("yaw", 1f, "blinkL", 0.9f));
        check("hold: right-side key present", near(u.sampleAt(50).get("blinkL"), 0.9f));
    }

    static void clampsOutsideRange() {
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(100, map("yaw", 0.4f));
        t.add(200, map("yaw", 0.9f));
        check("clamp: before start", near(t.sampleAt(-50).get("yaw"), 0.4f));
        check("clamp: after end", near(t.sampleAt(5000).get("yaw"), 0.9f));
    }

    static void jsonRoundTrips() {
        AvatarParamTrack t = new AvatarParamTrack();
        t.add(0, map("yaw", 0.25f, "pitch", -0.5f));
        t.add(33, map("yaw", 0.5f));
        t.add(66, map("yaw", 0.75f, "blinkR", 1f));
        AvatarParamTrack r = AvatarParamTrack.fromJson(t.toJson());
        check("json: size", r.size() == 3);
        check("json: duration", r.durationMs() == 66);
        check("json: values", near(r.sampleAt(0).get("pitch"), -0.5f)
                && near(r.sampleAt(66).get("blinkR"), 1f));
        check("json: lerp equal", near(r.sampleAt(16).get("yaw"), t.sampleAt(16).get("yaw")));
    }

    static void tolerantFromJson() {
        check("tolerant: null → empty", AvatarParamTrack.fromJson(null).isEmpty());
        com.google.gson.JsonObject junk = new com.google.gson.JsonObject();
        junk.addProperty("schemaVersion", 99);
        check("tolerant: no samples → empty", AvatarParamTrack.fromJson(junk).isEmpty());
        // One bad entry among good ones is skipped, not fatal.
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        com.google.gson.JsonObject good = new com.google.gson.JsonObject();
        good.addProperty("t", 10);
        com.google.gson.JsonObject p = new com.google.gson.JsonObject();
        p.addProperty("yaw", 0.5f);
        good.add("p", p);
        arr.add(good);
        com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
        bad.addProperty("t", "not-a-number");
        bad.add("p", new com.google.gson.JsonObject());
        arr.add(bad);
        arr.add(new com.google.gson.JsonPrimitive(42)); // not even an object
        o.add("samples", arr);
        AvatarParamTrack r = AvatarParamTrack.fromJson(o);
        check("tolerant: good sample survives junk", r.size() == 1
                && near(r.sampleAt(10).get("yaw"), 0.5f));
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    static Map<String, Float> map(Object... kv) {
        Map<String, Float> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Float) kv[i + 1]);
        return m;
    }

    static boolean near(Float a, float b) {
        return a != null && Math.abs(a - b) < 1e-4f;
    }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }
}
