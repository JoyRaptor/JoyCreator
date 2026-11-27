import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.perform.Stick;
import cc.joycreator.avatar.perform.Walker;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The joystick: steering, the speed curve, gestures and what each act does. Run: bash tools/jvm-harness/run-avatar-stick.sh */
public class StickTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static final class Queue implements Stick.Later {
        final List<Runnable> run = new ArrayList<>();
        final List<Long> ms = new ArrayList<>();

        public void after(long t, Runnable r) {
            ms.add(t);
            run.add(r);
        }

        public void cancel(Object tag) {
            run.clear();
            ms.clear();
        }

        void flush() {
            List<Runnable> l = new ArrayList<>(run);
            run.clear();
            ms.clear();
            for (Runnable r : l) r.run();
        }
    }

    static JSONObject topps() throws Exception {
        String s = new String(Files.readAllBytes(new File("app/src/main/assets/avatar/topps.character.json").toPath()), StandardCharsets.UTF_8);
        return CharacterFile.upgrade(new JSONObject(s)).doc;
    }

    static final class Rig {
        JSONObject doc;
        Inputs in = new Inputs();
        Walker w;
        Queue q = new Queue();
        Stick st;

        Rig() throws Exception {
            doc = topps();
            in.setSettings(doc.getJSONObject("settings"));
            in.setStances(doc);
            w = new Walker(doc, in);
            w.reset();
            st = new Stick(doc, in, w, q);
            st.setBodySize(400, 600);
        }

        void push(double x, double y, int frames) {
            st.knob(x, y);
            for (int i = 0; i < frames; i++) {
                st.step(1 / 60.0);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        // a tap, a flick, a hold
        Stick.Touch t = new Stick(topps(), new Inputs(), null, new Queue()).begin(0, 0, 0);
        t.move(0.05, 0.02);
        ok("tap".equals(Stick.gestureOf(t, 150)), "a quick touch that barely moves is a tap");
        ok(Stick.gestureOf(t, 600) == null, "the same touch held is the joystick, not a tap");
        t.move(0.1, -0.7);
        ok("up".equals(Stick.gestureOf(t, 300)), "a quick flick up is Swipe up");
        t.move(-0.8, -0.1);
        ok("left".equals(Stick.gestureOf(t, 300)), "a quick flick left is Swipe left");
        ok(Stick.gestureOf(t, 500) == null, "a slow drag is a steer");

        // steering: right walks right, further out is faster, letting go stops
        Rig r = new Rig();
        r.st.press();
        r.push(1, 0, 20);
        double full = r.w.tx;
        ok(full > 100, "the knob full right walks the body right (" + full + ")");
        double at = r.w.tx;
        r.st.release();
        for (int i = 0; i < 30; i++) r.st.step(1 / 60.0);
        ok(r.w.tx == at, "letting go leaves the body where it is");
        Rig half = new Rig();
        half.st.press();
        half.push(0.5, 0, 20);
        ok(half.w.tx < full * 0.45 && half.w.tx > 0, "half out is gentler than half the speed (" + half.w.tx + " vs " + full + ")");
        Rig far = new Rig();
        far.st.press();
        far.push(1, 0, 600);
        ok(far.w.tx <= 400 * 0.7 + 1e-6, "it stops at the edge of its room (" + far.w.tx + ")");

        // the acts
        Rig g = new Rig();
        java.util.Map<String, String> m = g.st.map();
        ok("jump".equals(m.get("up")) && "dash-l".equals(m.get("left")) && "dash-r".equals(m.get("right")), "a walker starts with jump and the two dashes");
        ok("trig:t19tc0".equals(m.get("tap")), "Tap starts as the character's first stance button (" + m.get("tap") + ")");
        g.doc.getJSONObject("settings").put("stick", new JSONObject().put("up", "trig:nope").put("down", "flip"));
        m = g.st.map();
        ok("jump".equals(m.get("up")), "an act the character cannot do falls back to the default, never a dead one");
        ok("flip".equals(m.get("down")), "a chosen act is kept");
        Stick.setSlot(g.doc, "down", "blink");
        ok("blink".equals(g.st.map().get("down")), "setSlot fills the slot");

        double x0 = g.w.tx;
        g.st.run("dash-r");
        ok(Math.abs(g.w.tx - x0 - 240) < 1e-9, "Dash right goes 0.6 body widths (" + (g.w.tx - x0) + ")");
        g.st.run("dash-l");
        g.st.run("dash-l");
        ok(g.w.tx >= -280 - 1e-9, "a dash stays in its room");
        boolean f0 = g.in.flip();
        g.st.run("flip");
        ok(g.in.flip() != f0, "Turn round turns it round");
        boolean on0 = g.in.isOn("t19tc0");
        g.st.run("trig:t19tc0");
        ok(g.in.isOn("t19tc0") != on0, "a stance button is the button");
        g.st.run("jump");
        ok(g.w.held, "Jump takes the body up first...");
        g.q.flush();
        ok(!g.w.held, "...and lets go so it falls by itself");
        ok(g.st.run("none") == null, "Nothing does nothing");

        // gestures through a touch
        Rig h = new Rig();
        Stick.Touch tt = h.st.begin(0, 0, 0);
        h.st.press();
        h.st.knob(0.15, 0);
        for (int i = 0; i < 5; i++) h.st.step(1 / 60.0);
        double nudged = h.w.tx;
        tt.move(0.05, 0);
        ok(nudged != 0, "the pad nudged the body a little");
        h.st.lift(tt, 120);
        ok(h.w.tx == 0, "a tap takes back the little the body was nudged");
        h.q.flush();
        ok(h.q.run.isEmpty(), "(queue empty)");
        // Tap waits for a second tap only when Two taps has an act
        boolean fl = h.in.flip();
        h.st.gesture("tap");
        ok(h.q.run.size() == 1 && h.q.ms.get(0) == Stick.DOUBLE_MS, "a first tap waits " + Stick.DOUBLE_MS + " ms for a second");
        h.st.gesture("tap");
        ok(h.in.flip() != fl, "two taps run the Two taps act (Turn round)");
        ok(h.q.run.isEmpty(), "the waiting single tap was cancelled");

        // the pad in perspective: up walks away, down comes closer, inside the walk's own limits; flicks are untouched
        Rig dz = new Rig();
        ok(dz.st.canDepth() && !dz.st.depthOn(), "a walker can be put in perspective; it starts off");
        Stick.setDepth(dz.doc, true);
        ok(dz.st.depthOn(), "the tick box turns it on");
        cc.joycreator.avatar.perform.DepthWalk.prep(dz.w, true, dz.doc, new double[]{0, 0, 300, 600});
        ok(dz.w.z != null, "depth is being used, so the walker has its depth model");
        dz.st.press();
        dz.push(0, -1, 40);
        ok(dz.w.tz != null && dz.w.tz < -20, "knob up walks it away from you (" + dz.w.tz + ")");
        dz.push(0, -1, 600);
        ok(dz.w.tz >= dz.w.z.lim[0] - 1e-9, "and stops at how far away it may go (" + dz.w.tz + " vs " + dz.w.z.lim[0] + ")");
        dz.push(0, 1, 800);
        ok(dz.w.tz <= dz.w.z.lim[1] + 1e-9 && dz.w.tz > 0, "knob down brings it toward you, no further than it may come (" + dz.w.tz + ")");
        ok(dz.w.ty == 0, "and up and down no longer pull the body up toward a jump");
        Rig off = new Rig();
        cc.joycreator.avatar.perform.DepthWalk.prep(off.w, true, off.doc, new double[]{0, 0, 300, 600});
        off.st.press();
        off.push(0, -1, 40);
        ok(off.w.tz == null, "with the tick box off the pad does not walk in depth");
        Stick.setAngle(dz.doc, 200);
        ok(dz.st.angle() == 75, "the camera angle stops at how low it may go (" + dz.st.angle() + ")");
        Stick.setAngle(dz.doc, 30);
        ok(dz.st.angle() == 30 && dz.doc.getJSONObject("settings").getInt("floorAngle") == 30, "the camera angle is the character's own");

        // a character that does not walk leans
        JSONObject plain = topps();
        plain.getJSONObject("settings").put("walk", new JSONObject().put("on", false));
        plain.getJSONObject("settings").remove("hover");
        Inputs pin = new Inputs();
        pin.setSettings(plain.getJSONObject("settings"));
        Walker pw = new Walker(plain, pin);
        Stick ps = new Stick(plain, pin, pw, new Queue());
        ok(!pw.travels(), "(the stand-in has no walk)");
        ps.press();
        ps.knob(1, 0);
        ps.step(1 / 60.0);
        ok(!"jump".equals(ps.map().get("up")), "a character that does not walk is not offered Jump");
        ps.release();

        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
