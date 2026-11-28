import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.perform.TakeRecorder;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recording over a take, punch-in and punch-out (a port of the take half of al-inputs.js): an owner is written only
 * while something live drives it, then handed back to the old take over a quarter of a second; everything else is the
 * same afterwards, sample for sample; one "take back" undoes the whole pass.
 * Run: bash tools/jvm-harness/run-avatar-take.sh
 */
public class TakeRecorderTest {
    static int pass, fail;
    static final double DT = 0.0334;   // a hair over a frame, so floor(clock x 30) steps cleanly (as a real frame time does)

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static final List<String> NAMES = Arrays.asList("turnX", "roll", "mouth", "vis", "hLx", "flip", "g:t1", "p:pin1:x");

    static Map<String, Object> live(double turn, double hand, String viseme) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnX", turn);
        m.put("roll", 0.0);
        m.put("mouth", 0.5);
        m.put("vis", viseme);
        m.put("hLx", hand);
        m.put("flip", 0.0);
        m.put("g:t1", 0.0);
        m.put("p:pin1:x", 7.0);
        return m;
    }

    static double lane(Take t, String name, int i) {
        List<Object> l = t.ch.get(name);
        return ((Number) l.get(Math.min(i, l.size() - 1))).doubleValue();
    }

    static Set<String> owners(String... o) {
        return new HashSet<>(Arrays.asList(o));
    }

    public static void main(String[] a) throws Exception {
        // who owns what
        ok("head".equals(TakeRecorder.ownerOf("turnY")) && "head".equals(TakeRecorder.ownerOf("dx:head")), "turnY and dx: belong to the head");
        ok("p:pin1".equals(TakeRecorder.ownerOf("p:pin1:x")) && "g:t1".equals(TakeRecorder.ownerOf("g:t1")), "a pin and a button own themselves");
        ok("facepins".equals(TakeRecorder.ownerOf("face.smile")) && "facepins".equals(TakeRecorder.ownerOf("pt.l1")), "face pins belong together");
        ok("mouth".equals(TakeRecorder.ownerOf("vis")) && "footL".equals(TakeRecorder.ownerOf("fLr")) && "body".equals(TakeRecorder.ownerOf("flip")), "mouth, feet and body signals are grouped");

        // pass 1: a fresh take records everything, whoever is driving
        TakeRecorder r = new TakeRecorder();
        r.record();
        for (int f = 0; f < 30; f++) r.step(DT, live(f / 100.0, 1.0, "Aa"), NAMES, owners());
        r.stop();
        Take first = r.take().copy();
        ok(first.n == 30, "a 30-frame pass makes a take of 30 frames (" + first.n + ")");
        ok(first.ch.size() == NAMES.size(), "a fresh take records every signal");
        ok(Math.abs(lane(first, "turnX", 10) - 0.10) < 1e-9 && "Aa".equals(first.ch.get("vis").get(3)), "numbers and mouth shapes are recorded as they were");

        // pass 2: only the head is driven for 15 frames; everything else must stay as it was
        r.record();
        List<Map<String, Object>> played = new ArrayList<>();
        for (int f = 0; f < 30; f++) {
            Set<String> own = f < 15 ? owners("head") : owners();
            played.add(r.step(DT, live(5.0, 99.0, "Zed"), NAMES, own));
        }
        r.stop();
        Take t = r.take();
        ok(t.n == 30, "a second pass does not change the take's length");
        boolean headWritten = true;
        for (int f = 0; f < 15; f++) headWritten &= lane(t, "turnX", f) == 5.0;
        ok(headWritten, "while the head is driven its lane is replaced");
        boolean untouched = true;
        for (String n : Arrays.asList("hLx", "mouth", "vis", "flip", "g:t1", "p:pin1:x")) {
            for (int f = 0; f < 30; f++) untouched &= t.ch.get(n).get(f).equals(first.ch.get(n).get(f));
        }
        ok(untouched, "every lane nobody drove is the same afterwards, sample for sample");
        double had16 = first.ch.get("turnX").get(16) instanceof Number ? lane(first, "turnX", 16) : 0;
        double expect16 = had16 + (5.0 - had16) * ((FADE() - DT) / FADE());
        ok(Math.abs(lane(t, "turnX", 16) - expect16) < 1e-9, "after the finger lets go the old take is handed back over a quarter of a second (frame 16)");
        ok(Math.abs(lane(t, "turnX", 25) - lane(first, "turnX", 25)) < 1e-9, "a quarter of a second later the old take is all there is");
        ok(played.get(3).containsKey("mouth") && !played.get(3).containsKey("turnX"), "while recording, the take plays back what nobody is driving, not what is");
        ok(played.get(15).containsKey("turnX"), "while an owner is being handed back, its blended value is applied to the character");

        // take the whole pass back
        ok(r.undoPass() && r.take().sameAs(first), "take back the last pass: the take is exactly as it was before");
        ok(!r.undoPass(), "there is nothing more to take back after that");

        // pass 3: the mouth shape is a step, never blended while handed back
        r.record();
        for (int f = 0; f < 12; f++) {
            Map<String, Object> lv = live(0.0, 1.0, "Zed");
            lv.put("flip", 1.0);
            r.step(DT, lv, NAMES, f < 5 ? owners("mouth", "body") : owners());
        }
        r.stop();
        ok(lane(r.take(), "flip", 4) == 1.0 && lane(r.take(), "flip", 7) == 0.0, "a step (flip) handed back is the old value whole, never a blend of 0 and 1");
        ok("Zed".equals(r.take().ch.get("vis").get(4)), "an owned mouth shape is written");
        ok("Aa".equals(r.take().ch.get("vis").get(7)), "a mouth shape handed back is the old one, whole, never a blend");

        // a lane shorter than the take holds its last value; a scrub shows a frame without playing
        Take shortLane = new Take();
        shortLane.n = 10;
        shortLane.ch.put("turnX", new ArrayList<>(Arrays.asList((Object) 0.1, 0.2, 0.3)));
        TakeRecorder p = new TakeRecorder();
        p.setTake(shortLane);
        p.scrubTo(7 * DT);
        Map<String, Object> shown = p.step(0, live(9, 9, "x"), Arrays.asList("turnX"), owners());
        ok(Math.abs(((Number) shown.get("turnX")).doubleValue() - 0.3) < 1e-9, "a lane shorter than the take holds its last value");
        ok(Math.abs(p.clock() - 7 * DT) < 1e-9, "a scrub does not move the clock");
        p.play();
        double c0 = p.clock();
        p.step(DT, live(9, 9, "x"), Arrays.asList("turnX"), owners());
        ok(p.clock() > c0, "playing moves the clock");

        // the file form
        try {
            Take back = Take.fromJson(first.toJson());
            ok(back.sameAs(first), "a take survives being written and read as text");
        } catch (Exception e) {
            ok(false, "a take survives being written and read as text: " + e);
        }
        // a pass must not erase the voice kept with a take
        {
            Take vt = new Take();
            vt.ch.put("turnX", new ArrayList<Object>(Arrays.<Object>asList(0.5, 0.6)));
            JSONObject before = new JSONObject().put("fps", 30).put("n", 1).put("voice", new JSONObject().put("name", "hello.wav"));
            JSONObject after = vt.toJson(before);
            ok(after.has("voice") && "hello.wav".equals(after.getJSONObject("voice").optString("name")), "a pass keeps the take's voice");
            ok(!vt.toJson().has("voice"), "(the plain take has none)");
        }

        // X6 M1: the longest take counts from the pass's start, not from the take's start: Studio starts a pass at the
        // playhead's place in the take, which can be past 2:00, and the pass must not end on its first frame
        TakeRecorder late = new TakeRecorder();
        Take long0 = new Take();
        long0.n = 30 * 200;
        late.setTake(long0);
        late.record();
        late.scrubTo(150);
        for (int f = 0; f < 30; f++) late.step(DT, live(f / 100.0, 1.0, "Aa"), NAMES, owners("head"));
        ok(late.recording(), "a pass begun 150 s into the take is still recording a second later");
        for (int f = 0; f < (int) ((TakeRecorder.MAX_SECONDS + 1) / DT); f++) late.step(DT, live(0, 1.0, "Aa"), NAMES, owners("head"));
        ok(!late.recording(), "a pass still ends by itself after the longest take");

        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }

    static double FADE() {
        return TakeRecorder.FADE;
    }
}
