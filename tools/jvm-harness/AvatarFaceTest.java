import cc.joycreator.avatar.face.FaceDrive;
import cc.joycreator.avatar.face.FaceWalk;
import com.fadcam.ui.faditor.avatar.face.FaceCalibration;
import com.fadcam.ui.faditor.avatar.face.FaceSignals;
import com.fadcam.ui.faditor.avatar.face.FaceStage;

import java.util.HashMap;
import java.util.Map;

/**
 * The phone room's face: {@code cc.joycreator.avatar.face.FaceDrive} (the Face chip's modes, the head through the head pad,
 * the expression, the face's lanes) and {@code FaceWalk} (walk with your face), against the web pilot's behaviour
 * ({@code al-inputs.js}, {@code al-facepins.js}, {@code al-face-walk.js}). The head checks run the phone's own face engine
 * ({@code FaceStage}) on a scripted head turned 30 degrees and nodded 20, end to end. Pure Java.
 * Run: bash tools/jvm-harness/run-avatar-face.sh   (--proof: plant each fault, expect a FAIL for every one)
 */
public class AvatarFaceTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    /** A resting face (as FaceEngineTest's): the phone held low, the head 3 degrees round, 8 down. */
    static Map<String, Float> raw(float yaw, float pitch) {
        Map<String, Float> m = new HashMap<>();
        m.put("eyeBlinkLeft", 0.08f); m.put("eyeBlinkRight", 0.08f);
        m.put("browDownLeft", 0.25f); m.put("browDownRight", 0.25f);
        m.put(FaceCalibration.HEAD_YAW, 3f + yaw); m.put(FaceCalibration.HEAD_PITCH, 8f + pitch); m.put(FaceCalibration.HEAD_ROLL, 0f);
        m.put(FaceCalibration.FACE_X, 0.5f); m.put(FaceCalibration.FACE_Y, 0.45f); m.put(FaceCalibration.FACE_SIZE, 0.3f);
        return m;
    }

    static Map<String, Float> sig(Object... kv) {
        Map<String, Float> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
        return m;
    }

    /** A walker that only goes left and right (the phone's today), or in depth too. */
    static final class Legs implements FaceWalk.Legs {
        double tx, base;
        Double tz;
        boolean held, deep;

        public boolean travels() { return true; }
        public boolean held() { return held; }
        public double tx() { return tx; }
        public void setTx(double v) { tx = v; }
        public boolean depth() { return deep; }
        public double[] depthLimits() { return new double[]{-40, 90}; }
        public Double tz() { return tz; }
        public void setTz(double v) { tz = v; }
        public double base() { return base; }
    }

    public static void main(String[] a) {
        // ── the Face chip ──
        FaceDrive d = new FaceDrive();
        String seq = d.mode() + ">" + d.next() + ">" + d.next() + ">" + d.next();
        check(seq.equals("OFF>ALWAYS>TALK>OFF"), "the Face chip: the first tap drives always, the second only while talking, the third turns it off (" + seq + ")");

        // ── the head, end to end through the phone's face engine: turned 30 degrees, nodded 20 ──
        FaceStage st = new FaceStage();
        double t = 0;
        Map<String, Float> out = null;
        for (int i = 0; i < 20; i++, t += 1 / 15.0) out = st.process(raw(0, 0), t);          // the neutral is taken
        FaceDrive h = new FaceDrive();
        h.setMode(FaceDrive.Mode.ALWAYS);
        FaceDrive.Out o0 = h.apply(out, false, false, false, 0, 0, 0, 1 / 15.0);
        double[][] poses = {{30, 0}, {-30, 0}, {0, 20}, {0, -20}};
        double[][] got = new double[4][];
        for (int p = 0; p < 4; p++) {
            for (int i = 0; i < 20; i++, t += 1 / 15.0) out = st.process(raw((float) poses[p][0], (float) poses[p][1]), t);
            FaceDrive.Out o = h.apply(out, false, false, false, 0, 0, 0, 1 / 15.0);
            got[p] = new double[]{o.turnX, o.turnY};
        }
        check(o0.drives && o0.head && Math.abs(o0.turnX) < 0.05 && Math.abs(o0.turnY) < 0.05, "looking straight at the phone, the head stays where the pad was");
        check(Math.abs(got[0][0]) >= 0.6 && Math.abs(got[1][0]) >= 0.6 && Math.signum(got[0][0]) == -Math.signum(got[1][0]),
                "turning your head 30 degrees either way turns his head toward its ends, both ways (" + r(got[0][0]) + ", " + r(got[1][0]) + ")");
        check(Math.abs(got[2][1]) >= 0.6 && Math.abs(got[3][1]) >= 0.6 && Math.signum(got[2][1]) == -Math.signum(got[3][1]),
                "nodding 20 degrees down or up nods his head toward its ends (" + r(got[2][1]) + ", " + r(got[3][1]) + ")");

        // ── from where the pad was: no jump in ──
        FaceDrive z = new FaceDrive();
        z.setMode(FaceDrive.Mode.ALWAYS);
        FaceDrive.Out z0 = z.apply(sig("turnX", 0.2, "turnY", -0.1, "roll", 0), false, false, false, 0.3, 0.1, 0, 0.033);
        FaceDrive.Out z1 = z.apply(sig("turnX", 0.7, "turnY", 0.2, "roll", 0), false, false, false, 0.3, 0.1, 0, 0.033);
        check(Math.abs(z0.turnX - 0.3) < 1e-6 && Math.abs(z0.turnY - 0.1) < 1e-6 && Math.abs(z1.turnX - 0.8) < 1e-6 && Math.abs(z1.turnY - 0.4) < 1e-6,   // (signals are floats)
                "the face takes the head from where the pad was and moves it by how far your head moved (no jump in)");
        z.recentre();
        FaceDrive.Out z2 = z.apply(sig("turnX", 0.7, "turnY", 0.2, "roll", 0), false, false, false, -0.2, 0, 0, 0.033);
        check(Math.abs(z2.turnX + 0.2) < 1e-6, "Re-centre: the head moves from where the pad is now");

        // ── while talking only ──
        FaceDrive tk = new FaceDrive();
        tk.setMode(FaceDrive.Mode.TALK);
        FaceDrive.Out q0 = tk.apply(sig("turnX", 0.5, "mouth", 0.6), false, false, false, 0, 0, 0, 0.033);
        FaceDrive.Out q1 = tk.apply(sig("turnX", 0.5, "mouth", 0.6), true, false, false, 0, 0, 0, 0.033);
        check(!q0.drives && q1.drives && q1.head && Math.abs(q1.mouth - 0.6) < 1e-6, "while talking only: the face drives while Hold to talk is held, not before");

        // ── the hand on the pad, a mood button ──
        FaceDrive hh = new FaceDrive();
        hh.setMode(FaceDrive.Mode.ALWAYS);
        FaceDrive.Out h1 = hh.apply(sig("turnX", 0.5, "mouth", 0.4), false, true, false, 0, 0, 0, 0.033);
        check(h1.drives && !h1.head && h1.expression, "a finger on the head pad wins over the face's turn (the face still talks)");
        FaceDrive.Out h2 = hh.apply(sig("turnX", 0.5, "mouth", 0.4), false, false, true, 0, 0, 0, 0.033);
        FaceDrive.Out h3 = hh.apply(sig("turnX", 0.5, "mouth", 0.4), true, false, true, 0, 0, 0, 0.033);
        check(!h2.expression && h3.expression, "a mood button just touched keeps the expression, unless you are talking");

        // ── the expression ──
        FaceDrive ex = new FaceDrive();
        ex.setMode(FaceDrive.Mode.ALWAYS);
        String v1 = ex.apply(sig("turnX", 0, "mouth", 0.5, "smileL", 0.7, "smileR", 0.6), false, false, false, 0, 0, 0, 0.033).vis;
        String v2 = ex.apply(sig("turnX", 0, "mouth", 0.5, "smileL", 0.1, "smileR", 0.2), false, false, false, 0, 0, 0, 0.033).vis;
        String v3 = ex.apply(sig("turnX", 0, "mouth", 0.05), false, false, false, 0, 0, 0, 0.033).vis;
        check(v1.equals("Ee") && v2.equals("Aa") && v3.equals("Neutral"), "the mouth shape: open in a smile Ee, open Aa, shut Neutral (" + v1 + v2 + v3 + ")");
        FaceDrive.Out e1 = ex.apply(sig("turnX", 0, "browL", 0.8, "browR", -0.2, "blinkL", 1, "blinkR", 0, "lookX", 0.4, "lidUp", 0.3), false, false, false, 0, 0, 0, 0.033);
        check(Math.abs(e1.browL - 0.8) < 1e-6 && Math.abs(e1.browR + 0.2) < 1e-6 && e1.blinkL == 1 && e1.blinkR == 0 && Math.abs(e1.lookX - 0.4) < 1e-6 && Math.abs(e1.lidUp - 0.3) < 1e-6,
                "one brow, one eye (a wink), the gaze and the lids come from the face as they are");

        // ── the face's lanes ──
        FaceDrive ln = new FaceDrive();
        ln.setMode(FaceDrive.Mode.ALWAYS);
        Map<String, Double> l1 = null;
        for (int i = 0; i < 30; i++) l1 = ln.apply(sig("turnX", 0, "pt.61.x", 0.2, "pt.61.y", -0.1, "cheekPuff", 0.7, "smileL", 0.6, "smileR", 0.4), false, false, false, 0, 0, 0, 0.033).lanes;
        check(Math.abs(gl(l1, "pt.61.x") - 0.2) < 0.01 && Math.abs(gl(l1, "face.cheekPuff") - 0.7) < 0.01 && Math.abs(gl(l1, "face.smile") - 0.5) < 0.01,
                "the face's points (pt.<i>) and expressions (face.<name>) become lanes, eased");
        Map<String, Double> l2 = null;
        ln.setMode(FaceDrive.Mode.OFF);
        for (int i = 0; i < 30; i++) l2 = ln.apply(null, false, false, false, 0, 0, 0, 0.033).lanes;
        check(Math.abs(gl(l2, "pt.61.x")) < 0.01 && Math.abs(gl(l2, "face.cheekPuff")) < 0.01, "with the face off, its lanes ease back to rest");
        check("facepins".equals(FaceDrive.ownerOf("pt.61.x")) && "facepins".equals(FaceDrive.ownerOf("face.smile")) && "facepins".equals(FaceDrive.ownerOf("mk.3.y"))
                && "facepins".equals(FaceDrive.ownerOf("mp.smile")) && "shoulders".equals(FaceDrive.ownerOf("shrugL")) && FaceDrive.ownerOf("turnX") == null,
                "a take knows the face's lanes are the face pins' (and shrugs the shoulders')");

        // ── walk with your face ──
        double H = 400, LIM = 300, dt = 1 / 30.0;
        FaceWalk w = new FaceWalk();
        w.setLeftRight(true);
        Legs L = new Legs();
        for (int i = 0; i < 30; i++) w.step(dt, sig("leanX", 0.1, "face", 1), L, H, LIM, false);
        double still = L.tx;
        for (int i = 0; i < 30; i++) w.step(dt, sig("leanX", 0.7, "face", 1), L, H, LIM, false);
        double right = L.tx;
        for (int i = 0; i < 60; i++) w.step(dt, sig("leanX", -0.7, "face", 1), L, H, LIM, false);
        check(Math.abs(still) < 1e-9 && right > 20 && L.tx < right - 20, "your face to one side walks him that way; a little drift does not (" + r(still) + ", " + r(right) + ", " + r(L.tx) + ")");
        Legs T = new Legs();
        FaceWalk wt = new FaceWalk();
        wt.setLeftRight(true);
        for (int i = 0; i < 15; i++) wt.step(dt, sig("leanX", 0.7, "roll", 0.6, "face", 1), T, H, LIM, false);
        double tilted = T.tx;                                  // (the tilt eases in over a few frames, as the pilot's)
        for (int i = 0; i < 60; i++) wt.step(dt, sig("leanX", 0.7, "roll", 0.6, "face", 1), T, H, LIM, false);
        check(Math.abs(T.tx - tilted) < 1e-9 && tilted < 8, "a head tilted toward a shoulder does not walk (a tilt is not a lean) (" + r(tilted) + ", " + r(T.tx) + ")");
        Legs B = new Legs();
        FaceWalk wb = new FaceWalk();
        wb.setLeftRight(true);
        for (int i = 0; i < 600; i++) wb.step(dt, sig("leanX", 1, "face", 1), B, H, LIM, false);
        check(B.tx <= LIM + 1e-9 && B.tx > LIM - 1, "it walks no further than the edge (" + r(B.tx) + ")");
        Legs O = new Legs();
        FaceWalk wo = new FaceWalk();
        wo.setLeftRight(true);
        for (int i = 0; i < 10; i++) wo.step(dt, sig("leanX", 0.7, "face", 1), O, H, LIM, false);
        boolean ownWhile = wo.owns();
        for (int i = 0; i < 6; i++) wo.step(dt, sig("leanX", 0, "face", 1), O, H, LIM, false);
        boolean ownJust = wo.owns();
        for (int i = 0; i < 12; i++) wo.step(dt, sig("leanX", 0, "face", 1), O, H, LIM, false);
        boolean ownLater = wo.owns();
        for (int i = 0; i < 10; i++) wo.step(dt, sig("leanX", 0.7, "face", 1), O, H, LIM, false);
        wo.step(dt, sig("leanX", 0.7, "face", 1), O, H, LIM, true);
        check(ownWhile && ownJust && !ownLater && !wo.owns(), "the body is the face's while it walks (a take records it), handed back once it stops, and at once when a take plays");
        Legs F = new Legs();
        F.held = true;
        FaceWalk wf = new FaceWalk();
        wf.setLeftRight(true);
        for (int i = 0; i < 30; i++) wf.step(dt, sig("leanX", 0.8, "face", 1), F, H, LIM, false);
        check(Math.abs(F.tx) < 1e-9, "a finger on the body wins over the face");
        Legs N = new Legs();
        N.deep = true;
        FaceWalk wn = new FaceWalk();
        wn.setLeftRight(false);
        wn.setCloser(true);
        for (int i = 0; i < 60; i++) wn.step(dt, sig("near", 0.6, "face", 1), N, H, LIM, false);
        double near = N.tz == null ? 0 : N.tz;
        for (int i = 0; i < 60; i++) wn.step(dt, sig("near", -0.6, "face", 1), N, H, LIM, false);
        double far = N.tz == null ? 0 : N.tz;
        wn.setCloser(false);
        wn.setUpClose(true);
        wn.step(dt, null, N, H, LIM, false);
        double up = N.tz;
        wn.setUpClose(false);
        N.base = 0;
        wn.step(dt, null, N, H, LIM, false);
        check(near > 30 && far < -10 && Math.abs(up - 90) < 1e-9 && Math.abs(N.tz) < 1e-9,
                "with a walker that walks in depth: come closer and he walks toward you, sit back and he steps away; Up close with no face; untick and he walks back (" + r(near) + ", " + r(far) + ")");
        Legs P = new Legs();
        FaceWalk wp = new FaceWalk();
        wp.setCloser(true);
        for (int i = 0; i < 30; i++) wp.step(dt, sig("near", 0.8, "face", 1), P, H, LIM, false);
        check(P.tz == null, "on a walker that does not walk in depth yet, Closer and further asks nothing of it");

        System.out.println(fails == 0 ? "PASSED" : "FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    /** A lane's value; a missing lane is NaN, so every comparison with it fails. */
    static double gl(Map<String, Double> m, String k) {
        Double v = m == null ? null : m.get(k);
        return v == null ? Double.NaN : v;
    }

    static String r(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
