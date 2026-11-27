import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.perform.Take;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The hands-on half of performing: the head pad, Hold to talk, moods, blinks, buttons and the Breeze become one record
 * of signals per frame; a pass records only what is driven. Run: bash tools/jvm-harness/run-avatar-take.sh
 */
public class InputsTest {
    static int pass, fail;
    static final double DT = 0.0334;
    static long now;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static Map<String, Object> tick(Inputs in) {
        now += 33;
        return in.frame(DT, now);
    }

    static double lane(Take t, String name, int i) {
        List<Object> l = t.ch.get(name);
        return ((Number) l.get(Math.min(i, l.size() - 1))).doubleValue();
    }

    public static void main(String[] a) {
        Inputs in = new Inputs();
        in.setButtons(Arrays.asList("t1", "t2"));

        // live signals
        in.head(0.5, -0.25);
        Map<String, Object> f = tick(in);
        ok(((Number) f.get("turnX")).doubleValue() == 0.5 && ((Number) f.get("turnY")).doubleValue() == -0.25, "the head pad turns the head");
        in.head(9, -9);
        f = tick(in);
        ok(((Number) f.get("turnX")).doubleValue() == 1.0 && ((Number) f.get("turnY")).doubleValue() == -1.0, "the head pad stops at the edge");
        in.releaseHead();
        in.letGoOfEverything();
        f = tick(in);
        ok(((Number) f.get("turnX")).doubleValue() == 0.0, "let go of everything puts the head in the middle");

        in.mood("Angry", now);
        f = tick(in);
        ok(((Number) f.get("lidTilt")).doubleValue() == 1.0 && ((Number) f.get("browL")).doubleValue() == -0.9, "a mood sets the lids and brows");
        ok("Angry".equals(in.moodName()) && in.owners(now).contains("mood"), "a mood is yours for a moment after you touch it");
        in.blinkNow(now);
        f = tick(in);
        ok(((Number) f.get("blinkL")).doubleValue() == 1.0, "a blink closes the eyes");
        for (int i = 0; i < 12; i++) f = tick(in);
        ok(((Number) f.get("blinkL")).doubleValue() == 0.0 && !in.owners(now).contains("blink"), "and opens them again");

        in.setButton("t1", true);
        f = tick(in);
        ok(((Number) f.get("g:t1")).doubleValue() == 1.0 && ((Number) f.get("g:t2")).doubleValue() == 0.0, "a button switches on and off");

        in.talk(true);
        boolean opened = false;
        for (int i = 0; i < 20; i++) {
            f = tick(in);
            opened |= ((Number) f.get("mouth")).doubleValue() > 0.2;
        }
        ok(opened && in.owners(now).contains("mouth"), "Hold to talk moves the mouth");
        in.talk(false);
        f = tick(in);
        ok(((Number) f.get("mouth")).doubleValue() == 0.0 && "Neutral".equals(f.get("vis")), "letting go shuts it");

        // the microphone: the mouth follows the voice, and is the mic's while it is on
        Inputs m = new Inputs();
        m.mic(true);
        m.micHeard(0.6, "Oh");
        Map<String, Object> mf = m.frame(DT, now += 33);
        ok(((Number) mf.get("mouth")).doubleValue() == 0.6 && "Oh".equals(mf.get("vis")) && m.owners(now).contains("mouth"), "the microphone drives the mouth");
        m.mic(false);
        mf = m.frame(DT, now += 33);
        ok(((Number) mf.get("mouth")).doubleValue() == 0.0 && !m.owners(now).contains("mouth"), "and lets go when it is switched off");

        // the face camera: head, mouth, brows and blinks are the face's while it is on
        Inputs fc = new Inputs();
        fc.face(true);
        Map<String, Float> seen = new java.util.HashMap<>();
        seen.put("turnX", 0.4f);
        seen.put("mouth", 0.7f);
        seen.put("vis", 1f);
        seen.put("blinkL", 1f);
        fc.faceSaw(seen);
        Map<String, Object> ff = fc.frame(DT, now += 33);
        ok(((Number) ff.get("turnX")).doubleValue() > 0.39 && ((Number) ff.get("mouth")).doubleValue() > 0.69 && "Aa".equals(ff.get("vis")) && ((Number) ff.get("blinkL")).doubleValue() == 1.0, "the face drives head, mouth and blinks");
        ok(fc.owners(now).containsAll(Arrays.asList("head", "mouth", "mood", "blink")), "and owns them while it is on");
        fc.face(false);
        ff = fc.frame(DT, now += 33);
        ok(((Number) ff.get("turnX")).doubleValue() == 0.0 && !fc.owners(now).contains("head"), "switching it off lets go");

        // a pass: the first records everything; the second, talking only, leaves the head's lane alone
        Inputs p = new Inputs();
        p.setButtons(Arrays.asList("t1"));
        now = 0;
        p.record();
        for (int i = 0; i < 30; i++) {
            p.head(i / 30.0, 0);
            tick(p);
        }
        p.recorder.stop();
        Take first = p.recorder.take().copy();
        ok(first.n == 30 && first.ch.containsKey("g:t1") && first.ch.containsKey("turnX"), "a first pass keeps every lane, buttons included");
        p.releaseHead();
        p.letGoOfEverything();
        p.record();
        p.talk(true);
        for (int i = 0; i < 30; i++) tick(p);
        p.talk(false);
        p.recorder.stop();
        Take second = p.recorder.take();
        boolean headSame = true;
        for (int i = 0; i < 30; i++) headSame &= lane(second, "turnX", i) == lane(first, "turnX", i);
        ok(headSame, "a second pass of talking only leaves the head's lane as it was");
        boolean mouthWritten = false;
        for (int i = 0; i < 30; i++) mouthWritten |= lane(second, "mouth", i) > 0.2;
        ok(mouthWritten, "and writes the mouth");
        ok(p.recorder.undoPass() && p.recorder.take().sameAs(first), "take back the last pass restores the take");

        // a button pressed during a pass is yours to the end of it
        Inputs q = new Inputs();
        q.setButtons(Arrays.asList("t1"));
        now = 0;
        q.record();
        for (int i = 0; i < 10; i++) tick(q);
        q.setButton("t1", true);
        for (int i = 0; i < 10; i++) tick(q);
        q.recorder.stop();
        ok(lane(q.recorder.take(), "g:t1", 0) == 0.0 && lane(q.recorder.take(), "g:t1", 15) == 1.0, "a button press is recorded on its own lane");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
