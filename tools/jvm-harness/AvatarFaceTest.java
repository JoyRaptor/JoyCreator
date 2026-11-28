import cc.joycreator.avatar.perform.Inputs;
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

    public static void main(String[] a) throws Exception {
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

        // ── in the room's own hands (Inputs, with the character's life on: idle, blinks by themselves, eyes lead) ──
        long now = 0;
        Inputs in = new Inputs();
        in.setSettings(new org.json.JSONObject());
        in.head(0.3, 0);
        in.releaseHead();
        for (int i = 0; i < 60; i++) in.frame(dt, now += 33);
        in.face(true);
        double lo = 9, hi = -9, mouthTalk;
        int blinks = 0;
        Map<String, Object> f = null;
        for (int i = 0; i < 300; i++) {                        // ten seconds looking straight at the phone, eyes open
            in.faceSaw(sig("turnX", 0.1, "turnY", 0, "roll", 0, "mouth", 0, "blinkL", 0, "blinkR", 0, "smileL", 0.6, "smileR", 0.6));
            f = in.frame(dt, now += 33);
            double x = ((Number) f.get("turnX")).doubleValue();
            if (i > 30) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
            if (((Number) f.get("blinkL")).doubleValue() > 0.5) blinks++;
        }
        check(Math.abs(lo - 0.3) < 0.005 && Math.abs(hi - 0.3) < 0.005,
                "in the room: the face takes the head from where the pad left it, and the idle sway rests while it drives (" + r(lo) + " to " + r(hi) + ")");
        check(blinks == 0, "the face's own eyes blink for him: no blinks by themselves while yours are open (" + blinks + ")");
        check(in.owners(now).containsAll(java.util.Arrays.asList("head", "mouth", "blink", "facepins")) && in.channels().contains("face.smile")
                && Math.abs(((Number) f.get("face.smile")).doubleValue() - 0.6) < 0.01, "while it drives, the head, mouth, blinks and the face's lanes are its own, and the lanes are in a take's list");
        for (int i = 0; i < 60; i++) {
            in.faceSaw(sig("turnX", 0.6, "turnY", 0, "roll", 0, "mouth", 0, "blinkL", 1, "blinkR", 0));
            f = in.frame(dt, now += 33);
        }
        check(Math.abs(((Number) f.get("turnX")).doubleValue() - 0.8) < 0.01 && ((Number) f.get("blinkL")).doubleValue() == 1 && ((Number) f.get("blinkR")).doubleValue() == 0,
                "turn your head and his turns as far, from the pad's place; a wink is a wink (" + r(((Number) f.get("turnX")).doubleValue()) + ")");
        in.faceDrive().setMode(FaceDrive.Mode.TALK);           // the chip's second tap: only while Hold to talk is held
        for (int i = 0; i < 60; i++) {
            in.faceSaw(sig("turnX", 0.6, "turnY", 0, "roll", 0, "mouth", 0.2));
            f = in.frame(dt, now += 33);
        }
        boolean quiet = !in.owners(now).contains("head") && Math.abs(((Number) f.get("turnX")).doubleValue() - 0.3) < 0.06;
        in.talk(true);
        double mx = 0;
        for (int i = 0; i < 60; i++) {
            in.faceSaw(sig("turnX", 0.6, "turnY", 0, "roll", 0, "mouth", 0.2));
            f = in.frame(dt, now += 33);
            if (i > 20) mx = Math.max(mx, ((Number) f.get("mouth")).doubleValue());
        }
        mouthTalk = mx;
        check(quiet && in.owners(now).contains("head") && Math.abs(mouthTalk - 0.2) < 0.02,
                "while talking only: the head goes back to the pad until you talk; talking, the mouth is yours, not a babble (" + r(mouthTalk) + ")");
        in.talk(false);
        in.face(false);
        for (int i = 0; i < 60; i++) f = in.frame(dt, now += 33);
        check(!in.owners(now).contains("head") && Math.abs(((Number) f.get("turnX")).doubleValue() - 0.3) < 0.06 && Math.abs(((Number) f.get("face.smile")).doubleValue()) < 0.01,
                "Face off: the head goes back to the pad's place and the face's lanes ease to rest");

        // ── the face's lanes reach the solver (a face pin's Shapes follow pt.<i>; Links and Shapes follow face.<name>) ──
        Inputs pin = new Inputs();
        pin.face(true);
        Map<String, Object> pf = null;
        for (int i = 0; i < 30; i++) {
            pin.faceSaw(sig("turnX", 0, "pt.61.x", 0.2, "pt.61.y", -0.1, "smileL", 0.5, "smileR", 0.5));
            pf = pin.frame(dt, now += 33);
        }
        cc.joycreator.avatar.solve.Signals ps = cc.joycreator.avatar.ui.SignalMap.toSignals(pf, 0, 0);
        Map<String, Object> mm = new HashMap<>();
        mm.put("mk.3.y", -0.25);
        mm.put("mp.smile", 0.7);
        mm.put("shrugL", 0.4);
        mm.put("shrugging", 0.9);
        cc.joycreator.avatar.solve.Signals ms = cc.joycreator.avatar.ui.SignalMap.toSignals(mm, 0, 0);
        check(ps.num("pt.61.x") != null && Math.abs(ps.num("pt.61.x") - 0.2) < 0.01 && ps.num("face.smile") != null && Math.abs(ps.num("face.smile") - 0.5) < 0.01
                && ms.num("mk.3.y") != null && ms.num("mp.smile") != null && ms.num("shrugL") != null && ms.num("shrugging") == null,
                "the face's lanes reach the solver: face pins (pt.*), expressions (face.*), the mask (mk.*, mp.*) and the shoulders");

        // ── the Puff button (the lead, 11-27: the tracker never sees a cheek puff): held, the cheek puff a part follows rises,
        //    no camera needed; its lane has an owner of its own, so a pass with it held leaves the face's other lanes alone ──
        Inputs pb = new Inputs();
        pb.puff(true);
        Map<String, Object> pq = null;
        for (int i = 0; i < 10; i++) pq = pb.frame(dt, now += 33);
        double held = ((Number) pq.get("face.cheekPuff")).doubleValue();
        boolean ownPuff = pb.owners(now).contains("puff") && !pb.owners(now).contains("facepins");
        pb.puff(false);
        for (int i = 0; i < 30; i++) pq = pb.frame(dt, now += 33);
        check(held > 0.9 && ownPuff && ((Number) pq.get("face.cheekPuff")).doubleValue() < 0.02,
                "the Puff button: held, the cheeks puff (no camera needed) and the lane is the puff's own; let go, they settle (" + r(held) + ")");
        Inputs rp = new Inputs();
        rp.record();
        for (int i = 0; i < 30; i++) rp.frame(dt, now += 33);                   // a first pass: everything is written
        rp.recorder.stop();
        rp.record();
        rp.puff(true);
        for (int i = 0; i < 20; i++) rp.frame(dt, now += 33);
        rp.puff(false);
        java.util.Set<String> wrote = rp.recorder.wroteOwners();
        rp.recorder.stop();
        check("puff".equals(cc.joycreator.avatar.perform.TakeRecorder.ownerOf("face.cheekPuff")) && "facepins".equals(cc.joycreator.avatar.perform.TakeRecorder.ownerOf("face.smile"))
                        && "puff".equals(FaceDrive.ownerOf("face.cheekPuff")) && wrote.contains("puff") && !wrote.contains("facepins"),
                "a pass with Puff held records the puff and nothing else of the face (" + wrote + ")");

        // ── Check directions (the pilot's five moves): on a camera that hands the picture over mirrored, the moves come in
        //    reversed; once checked, his right turn turns the head the same way as on a normal phone ──
        double plain = dirsRun(false, false), fixedUp = dirsRun(true, true), unfixed = dirsRun(true, false);
        check(Math.signum(plain) > 0 && Math.signum(unfixed) < 0 && Math.signum(fixedUp) == Math.signum(plain),
                "Check directions: on a mirrored camera his right turn ran the wrong way (" + r(unfixed) + "); after the five moves it runs as on a normal phone (" + r(fixedUp) + " vs " + r(plain) + ")");
        check(dirsAnswered == 4, "a move not made within eight seconds keeps its old direction, the rest are set (" + dirsAnswered + " of 5)");

        // ── the face mask's drive (FACE-2, the pilot's al-mask.js drive and al-mask-poses.js weights) ──
        org.json.JSONObject mset = new org.json.JSONObject("{\"type\":\"set\",\"marks\":{\"\":{\"pts\":[{\"n\":1,\"face\":[17]},{\"n\":2,\"face\":[61]},{\"n\":3,\"face\":[291]}],\"at\":{}}},"
                + "\"face\":{\"poses\":[{\"id\":\"open\",\"face\":{\"1\":[0,0.2]}},{\"id\":\"wide\",\"face\":{\"2\":[-0.1,0],\"3\":[0.1,0]}}]}}");
        double u = 0.75;                                                                // (mask units per point unit with no neutral points)
        Map<String, Double> mpin = new HashMap<>(), mo = new HashMap<>();
        mpin.put("pt.17.x", 0.05 / u); mpin.put("pt.17.y", 0.12 / u); mpin.put("pt.61.x", -0.03 / u); mpin.put("pt.61.y", 0.0); mpin.put("pt.291.x", 0.03 / u); mpin.put("pt.291.y", 0.0);
        cc.joycreator.avatar.face.MaskDrive.drive(mset, mpin, false, null, mo);
        check(Math.abs(mo.get("mp.open") - 0.6) < 0.01 && Math.abs(mo.get("mp.wide") - 0.3) < 0.01 && Math.abs(mo.get("mk.1.x") - 0.05) < 1e-6 && Math.abs(mo.get("mk.1.y")) < 0.01,
                "the mask: how much of each pose your face holds (0.6 open, 0.3 wide), and what they do not explain follows directly (" + r(mo.get("mp.open")) + ", " + r(mo.get("mp.wide")) + ")");
        mpin.put("pt.17.y", -0.12 / u);
        cc.joycreator.avatar.face.MaskDrive.drive(mset, mpin, false, null, mo);
        double neg = mo.get("mp.open"), negRest = mo.get("mk.1.y");
        mpin.put("pt.17.y", 0.6 / u);
        cc.joycreator.avatar.face.MaskDrive.drive(mset, mpin, false, null, mo);
        check(neg == 0 && Math.abs(negRest + 0.12) < 1e-6 && Math.abs(mo.get("mp.open") - 1.5) < 1e-9 && Math.abs(mo.get("mk.1.y") - 0.3) < 1e-6,
                "a pose is never held backwards (0) nor past 1.5; what is left over follows directly");
        org.json.JSONObject noPoses = new org.json.JSONObject(mset.toString());
        noPoses.getJSONObject("face").remove("poses");
        Map<String, Double> m0 = new HashMap<>(), m1 = new HashMap<>();
        cc.joycreator.avatar.face.MaskDrive.drive(noPoses, mpin, false, null, m0);
        cc.joycreator.avatar.face.MaskDrive.drive(noPoses, mpin, true, null, m1);
        check(Math.abs(m0.get("mk.2.x") + 0.03) < 1e-6 && Math.abs(m1.get("mk.2.x") - 0.03) < 1e-6,
                "Mirror on: the mask's right side follows your left (" + r(m0.get("mk.2.x")) + " -> " + r(m1.get("mk.2.x")) + ")");
        int[] wch = cc.joycreator.avatar.face.MaskDrive.watch(mset);
        check(wch.length == 3 && java.util.Arrays.stream(wch).boxed().collect(java.util.stream.Collectors.toSet()).containsAll(java.util.Arrays.asList(17, 61, 291)),
                "the camera watches the mask's face points, both sides of each");

        // ── face pins (the model half of the pilot's al-facepins.js): bind, strength, the All pins dial, unbind, one piece ──
        org.json.JSONObject fch = new org.json.JSONObject("{\"root\":{\"id\":\"r\",\"type\":\"group\",\"kids\":[{\"id\":\"hd\",\"type\":\"set\",\"role\":\"head\",\"pick\":{\"by\":\"turn\"},\"kids\":["
                + "{\"id\":\"front\",\"type\":\"group\",\"at\":[0,0],\"kids\":[{\"id\":\"cheek\",\"type\":\"art\"}]}]}]}}");
        org.json.JSONObject frontN = cc.joycreator.avatar.face.FacePins.node(fch.getJSONObject("root"), "front"), cheekN = cc.joycreator.avatar.face.FacePins.node(fch.getJSONObject("root"), "cheek");
        org.json.JSONObject sheet = cc.joycreator.avatar.face.FacePins.sheetOf(fch, cheekN);
        cc.joycreator.avatar.face.FacePins.sheetMesh(sheet);
        sheet.getJSONObject("mesh").getJSONArray("pins").put(new org.json.JSONObject().put("id", "p1").put("x", 100).put("y", 120));
        String fid = cc.joycreator.avatar.face.FacePins.bind(fch, sheet, "p1", 61, true, 60);
        org.json.JSONArray fsh = sheet.getJSONArray("shapes");
        check(sheet == frontN && sheet.getJSONObject("mesh").optBoolean("last") && fsh.length() == 2 && "pt.61.x".equals(fsh.getJSONObject(0).getString("sig")) && "pt.61.y".equals(fsh.getJSONObject(1).getString("sig"))
                        && fsh.getJSONObject(0).getJSONObject("d").getJSONArray("p1").getDouble(0) == 60 && fsh.getJSONObject(1).getJSONObject("d").getJSONArray("p1").getDouble(1) == 60
                        && "your right mouth corner".equals(cc.joycreator.avatar.face.FacePins.nameOf(61)) && fsh.getJSONObject(0).getString("name").contains("your right mouth corner")
                        && java.util.Arrays.equals(cc.joycreator.avatar.face.FacePins.points(fch), new int[]{61}),
                "a face pin on a slice goes on the head's whole drawing (one piece, bent last), two Shapes follow the point, named in his words");
        cc.joycreator.avatar.face.FacePins.setGain(fch, fch.getJSONObject("root"), 2);
        double afterDial = fsh.getJSONObject(0).getDouble("amt");
        cc.joycreator.avatar.face.FacePins.strength(fch, fid, 0.5);
        check(Math.abs(afterDial - 2.0) < 1e-9 && Math.abs(fsh.getJSONObject(0).getDouble("amt") - 1.0) < 1e-9 && Math.abs(cc.joycreator.avatar.face.FacePins.gain(fch) - 2) < 1e-9,
                "the All pins dial scales every pin (1 x 2), and a pin's strength is times the dial (0.5 x 2)");
        cc.joycreator.avatar.face.FacePins.unbind(fch, fid);
        check(!fch.has("facePins") && sheet.getJSONArray("shapes").length() == 0 && sheet.getJSONObject("mesh").getJSONArray("pins").length() == 0,
                "taking a face pin off takes its Shapes, and the pin made for it");
        fch.put("settings", new org.json.JSONObject().put("faceSheet", false));
        check(cc.joycreator.avatar.face.FacePins.sheetOf(fch, cheekN) == cheekN, "one piece off: the slice itself");

        // ── shoulder shrugs (SHRUG-1, the pilot's al-pose.js): the rig on Topps, the glide, the lanes in a take ──
        org.json.JSONObject tp = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("tools/avatarlab/characters/topps.character.json")), java.nio.charset.StandardCharsets.UTF_8));
        for (String role : new String[]{"armL", "armR", "head"}) {                    // (as the pilot's test: any old shrug Links off first)
            org.json.JSONObject n = cc.joycreator.avatar.face.Shrugs.firstRole(tp.getJSONObject("root"), role);
            org.json.JSONArray l = n == null ? null : n.optJSONArray("links"), keep = new org.json.JSONArray();
            for (int i = 0; l != null && i < l.length(); i++) if (!l.getJSONObject(i).has("shrug")) keep.put(l.get(i));
            if (n != null) n.put("links", keep);
        }
        tp.remove("noShrug");
        boolean rigged = cc.joycreator.avatar.face.Shrugs.rig(tp, nd -> null);
        check(rigged && cc.joycreator.avatar.face.Shrugs.amount(tp, true) == 32.5 && cc.joycreator.avatar.face.Shrugs.amount(tp, false) == 16
                        && shrugAmt(tp, "armR") == -24.5 && !cc.joycreator.avatar.face.Shrugs.rig(tp, nd -> null),
                "Topps's shrug, as the pilot rigs it: the left arm lifts 32.5, the right 24.5, the head sinks 16; once");
        cc.joycreator.avatar.face.Shrugs.setOn(tp, false, nd -> null);
        check(tp.optBoolean("noShrug") && !cc.joycreator.avatar.face.Shrugs.has(tp) && !cc.joycreator.avatar.face.Shrugs.rig(tp, nd -> null),
                "no shrugs: the Links come off, and he is not set up again");
        check(!cc.joycreator.avatar.face.Shrugs.canRig(tp) && cc.joycreator.avatar.face.Shrugs.rigs(tp) && !cc.joycreator.avatar.face.Shrugs.drives(tp),
                "said no shrugs: nothing a shrug moves (no Shoulders tick box), the parts still there to turn it on again");
        cc.joycreator.avatar.face.Shrugs.setOn(tp, true, nd -> null);
        check(cc.joycreator.avatar.face.Shrugs.has(tp) && cc.joycreator.avatar.face.Shrugs.drives(tp) && cc.joycreator.avatar.face.Shrugs.amount(tp, true) == 32.5,
                "shrugs on again: set up as before");
        org.json.JSONObject bare = new org.json.JSONObject().put("root", new org.json.JSONObject().put("kids", new org.json.JSONArray()
                .put(new org.json.JSONObject().put("id", "t").put("links", new org.json.JSONArray().put(new org.json.JSONObject().put("sig", "shrugR").put("prop", "rot").put("amt", 9))))));
        check(!cc.joycreator.avatar.face.Shrugs.rigs(bare) && cc.joycreator.avatar.face.Shrugs.drives(bare),
                "no arms or head, but a part follows a shrug: the Shoulders tick box is not a dead control");

        // the camera's side (ShoulderFeed: the pilot's reading and feed): body readings ten a second, the screen thirty
        com.fadcam.ui.faditor.avatar.face.ShoulderFeed sf = new com.fadcam.ui.faditor.avatar.face.ShoulderFeed();
        double[] clock = {0};
        Map<String, Float> fs = feed(sf, 0, 0, 1.0, false, clock, null);           // a still second: the rest
        double[] trail = new double[1];
        fs = feed(sf, 0.05f, 0.05f, 0.05, false, clock, null);                     // a half shrug starts: the first frames
        double firstFrames = fs.get("shrug");
        fs = feed(sf, 0.05f, 0.05f, 1.0, false, clock, trail);
        double half = fs.get("shrug");
        check(half > 0.3 && half < 0.8 && firstFrames < half * 0.6,
                "a half shrug glides in, no step (" + r(firstFrames) + " after a moment, " + r(half) + " settled)");
        check(trail[0] <= half + 1e-3, "...and never overshoots where it settles (" + r(trail[0]) + ")");
        fs = feed(sf, 0.1f, 0.1f, 0.8, false, clock, null);
        check(fs.get("shrugL") > 0.9 && fs.get("shrugR") > 0.9 && fs.get("body") > 0.9, "both shoulders up: a full shrug (" + r(fs.get("shrug")) + ")");
        feed(sf, 0, 0, 1.0, false, clock, null);
        fs = feed(sf, 0.1f, 0, 1.0, false, clock, null);                            // the person's right shoulder only
        check(fs.get("shrugL") > 0.8 && fs.get("shrugR") < 0.1,
                "Mirror on (a reflection): your right shoulder lifts the character's left (" + r(fs.get("shrugL")) + ", " + r(fs.get("shrugR")) + ")");
        fs = feed(sf, 0.1f, 0, 1.0, true, clock, null);
        check(fs.get("shrugR") > 0.8 && fs.get("shrugL") < 0.1,
                "Mirror off (someone facing you): your right shoulder lifts its right (" + r(fs.get("shrugL")) + ", " + r(fs.get("shrugR")) + ")");
        Map<String, Float> off1 = sf.sample(1 / 30.0, false);
        Map<String, Float> off = off1;
        for (int i = 0; i < 45; i++) off = sf.sample(1 / 30.0, false);
        check(off1.get("shrugR") > 0.5 && off.get("shrugR") < 0.02 && off.get("body") == 0,
                "Shoulders off (or the camera gone): the shrug eases away, not frozen and not snapped (" + r(off1.get("shrugR")) + " then " + r(off.get("shrugR")) + ")");
        for (int i = 0; i < 30; i++) { clock[0] += 0.1; sf.reading(null, 0.75f, clock[0], false, 1f); }
        check(!sf.seen() && sf.unseenSeconds() > 2.5, "three seconds out of the picture: the room can say so (" + r(sf.unseenSeconds()) + " s)");
        sf.reading(shoulderBody(0, 0), 0.75f, clock[0] += 0.1, false, 1f);
        check(sf.seen() && sf.unseenSeconds() == 0, "back in the picture: seen again");
        feed(sf, 0.1f, 0.1f, 1.0, false, clock, null);
        sf.requestNeutral();
        fs = feed(sf, 0.1f, 0.1f, 1.5, false, clock, null);
        check(fs.get("shrug") < 0.05, "Re-centre: the shoulders as they are now become their rest (" + r(fs.get("shrug")) + ")");
        feed(sf, 0, 0, 1.0, false, clock, null);
        sf.requestNeutral();
        feed(sf, 0, 0, 1.0, false, clock, null);
        Map<String, Float> lessSens = feed(sf, 0.05f, 0.05f, 1.0, false, clock, null, 0.5f);
        Map<String, Float> moreSens = feed(sf, 0.05f, 0.05f, 1.0, false, clock, null, 2f);
        check(moreSens.get("shrug") > lessSens.get("shrug") + 0.3,
                "Shoulder sensitivity: higher, the same shrug lifts further (" + r(lessSens.get("shrug")) + " at 0.5x, " + r(moreSens.get("shrug")) + " at 2x)");

        // the app's notice for Google's models names them by their hashes (Apache 2.0 asks for the licence beside them)
        String notice = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/src/main/assets/licenses/mediapipe-models.txt")), java.nio.charset.StandardCharsets.UTF_8);
        boolean named = notice.contains("Apache License") && notice.contains("Version 2.0, January 2004");
        for (String m : new String[]{"face_landmarker.task", "pose_landmarker_lite.task"}) {
            byte[] dig = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/src/main/assets/mediapipe/" + m)));
            StringBuilder hex = new StringBuilder();
            for (byte x : dig) hex.append(String.format("%02x", x));
            named &= notice.contains(m) && notice.contains(hex.toString());
        }
        check(named, "the MediaPipe notice in the app carries the licence and both models' true hashes (a swapped model must update it)");

        // Motion's Try: a shrug to see without the camera, while the finger is on it
        Inputs ti = new Inputs();
        long tnow = 0;
        ti.tryShrug(0.7);
        Map<String, Object> tf = null;
        for (int i = 0; i < 5; i++) tf = ti.frame(1 / 30.0, tnow += 33);
        check(tf.get("shrugL") instanceof Number && ((Number) tf.get("shrugL")).doubleValue() == 0.7 && ((Number) tf.get("shrug")).doubleValue() == 0.7,
                "Try: the shrug lanes at the slider's value, no camera");
        ti.tryShrug(0);
        tf = ti.frame(1 / 30.0, tnow += 33);
        check(tf.get("shrug") == null || ((Number) tf.get("shrug")).doubleValue() == 0, "let go of Try: it rests");
        FaceDrive sd = new FaceDrive();
        sd.setMode(FaceDrive.Mode.ALWAYS);
        FaceDrive.Out so = sd.apply(sig("turnX", 0, "shrugL", 0.6, "shrugR", 0.1, "shrug", 0.35, "body", 1), false, false, false, 0, 0, 0, 0.033);
        check(so.shoulders && Math.abs(so.lanes.get("shrugL") - 0.6) < 1e-6 && Math.abs(so.lanes.get("shrug") - 0.35) < 1e-6
                        && "shoulders".equals(cc.joycreator.avatar.perform.TakeRecorder.ownerOf("shrugL")),
                "the shrugs ride the lanes (the shoulders' in a take) while your shoulders are seen");

        // X6 audit P3: a take is saved as JSON, which cannot hold a value that is not a number; one in a lane made every save
        // fail. Whatever the camera hands in (a face size of 0 divides to infinity), the face's lanes and head are numbers.
        FaceDrive bad = new FaceDrive();
        bad.setMode(FaceDrive.Mode.ALWAYS);
        Map<String, Float> odd = sig("turnX", 0, "pt.61.x", 0.2, "pt.61.y", 0.1, "smileL", 0.5, "shrugL", 0.4, "body", 1);
        FaceDrive.Out bo = null;
        for (int i = 0; i < 10; i++) bo = bad.apply(odd, false, false, false, 0, 0, 0, 0.033);
        odd.put("pt.61.x", Float.POSITIVE_INFINITY);
        odd.put("pt.61.y", Float.NaN);
        odd.put("smileL", Float.POSITIVE_INFINITY);
        odd.put("cheekPuff", Float.NEGATIVE_INFINITY);
        odd.put("shrugL", Float.NaN);
        odd.put("shrugR", Float.POSITIVE_INFINITY);
        odd.put("turnX", Float.NaN);
        odd.put("lookX", Float.POSITIVE_INFINITY);
        boolean finite = true;
        StringBuilder which = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            bo = bad.apply(odd, false, false, false, 0, 0, 0, 0.033);
            for (Map.Entry<String, Double> e : bo.lanes.entrySet()) if (!Double.isFinite(e.getValue())) { finite = false; which.append(' ').append(e.getKey()); }
            for (double v : new double[]{bo.turnX, bo.turnY, bo.roll, bo.lookX, bo.lookY, bo.mouth, bo.browL, bo.browR, bo.blinkL, bo.blinkR})
                if (!Double.isFinite(v)) { finite = false; which.append(" head/eyes/mouth"); }
        }
        check(finite, "a camera value that is not a number (NaN, infinity) never reaches a lane, the head, the eyes or the mouth:" + (finite ? " all numbers" : which.toString()));

        // X6 audit P10: the mask's lanes are the face's in a take: a second pass with the face on records the live mask, it does
        // not play the first pass's over it
        cc.joycreator.avatar.perform.TakeRecorder mr = new cc.joycreator.avatar.perform.TakeRecorder();
        java.util.List<String> mnames = java.util.Arrays.asList("mk.3.y", "mp.1");
        java.util.Set<String> faceOwns = new java.util.HashSet<>(java.util.Arrays.asList("facepins"));
        Map<String, Object> mlive = new HashMap<>();
        mlive.put("mk.3.y", 0.5);
        mlive.put("mp.1", 0.8);
        mr.record();
        for (int i = 0; i < 30; i++) mr.step(1 / 30.0, mlive, mnames, faceOwns);
        mr.stop();
        mlive.put("mk.3.y", 0.1);
        mlive.put("mp.1", 0.2);
        mr.scrubTo(0);
        mr.record();
        Map<String, Object> mplayed = null;
        for (int i = 0; i < 20; i++) mplayed = mr.step(1 / 30.0, mlive, mnames, faceOwns);
        mr.stop();
        Object mk = mplayed.get("mk.3.y"), mp = mplayed.get("mp.1");
        check("facepins".equals(cc.joycreator.avatar.perform.TakeRecorder.ownerOf("mk.3.y")) && "facepins".equals(cc.joycreator.avatar.perform.TakeRecorder.ownerOf("mp.1"))
                        && mk == null && mp == null,
                "a second pass with the face on: the mask's lanes are the face's own, the first pass's mask is not played over the live one (played "
                        + mk + ", " + mp + ")");

        // ...and one body reading that is not a number does not stop the shoulders for good (the smoothing would keep it)
        com.fadcam.ui.faditor.avatar.face.ShoulderFeed nf = new com.fadcam.ui.faditor.avatar.face.ShoulderFeed();
        double[] nclock = {0};
        feed(nf, 0, 0, 1.0, false, nclock, null);
        float[] broken = shoulderBody(0.1f, 0.1f);
        broken[12 * 3 + 1] = Float.NaN;
        nf.reading(broken, 0.75f, nclock[0] += 0.1, false, 1f);
        nf.reading(shoulderBody(0.1f, 0.1f), Float.NaN, nclock[0] += 0.1, false, 1f);
        Map<String, Float> after = feed(nf, 0.1f, 0.1f, 1.0, false, nclock, null);
        check(after != null && Float.isFinite(after.get("shrug")) && after.get("shrug") > 0.9,
                "a body reading that is not a number is no body: the next shrug still shows (" + (after == null ? "none" : r(after.get("shrug"))) + ")");

        System.out.println(fails == 0 ? "PASSED" : "FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    /** A lane's value; a missing lane is NaN, so every comparison with it fails. */
    /**
     * A body as the tracker gives it (33 points: x, y, visibility, 0..1 of a 4:3 picture), the person facing an unmirrored
     * front camera: their right shoulder on the picture's left. {@code liftR}, {@code liftL}: the person's right and left
     * shoulder raised by this much of the picture's height.
     */
    static float[] shoulderBody(float liftR, float liftL) {
        float[] p = new float[com.fadcam.ui.faditor.avatar.face.ShoulderStage.COUNT * 3];
        for (int i = 0; i < com.fadcam.ui.faditor.avatar.face.ShoulderStage.COUNT; i++) { p[i * 3] = 0.5f; p[i * 3 + 1] = 0.5f; p[i * 3 + 2] = 0.9f; }
        p[7 * 3] = 0.55f; p[7 * 3 + 1] = 0.30f;                      // the person's left ear (the picture's right)
        p[8 * 3] = 0.45f; p[8 * 3 + 1] = 0.30f;                      // the right ear
        p[11 * 3] = 0.72f; p[11 * 3 + 1] = 0.62f - liftL;            // the left shoulder
        p[12 * 3] = 0.28f; p[12 * 3 + 1] = 0.62f - liftR;            // the right shoulder
        return p;
    }

    /** {@code secs} of body readings (ten a second) and screen frames (thirty) into {@code sf}; the last frame's shrugs. {@code peak}: the highest shrug shown. */
    static Map<String, Float> feed(com.fadcam.ui.faditor.avatar.face.ShoulderFeed sf, float liftR, float liftL, double secs, boolean mirror, double[] clock, double[] peak) {
        return feed(sf, liftR, liftL, secs, mirror, clock, peak, 1f);
    }

    static Map<String, Float> feed(com.fadcam.ui.faditor.avatar.face.ShoulderFeed sf, float liftR, float liftL, double secs, boolean mirror, double[] clock, double[] peak, float sens) {
        Map<String, Float> last = null;
        int frames = (int) Math.round(secs * 30);
        for (int i = 0; i < frames; i++) {
            clock[0] += 1 / 30.0;
            if (i % 3 == 0) sf.reading(shoulderBody(liftR, liftL), 0.75f, clock[0], mirror, sens);
            last = sf.sample(1 / 30.0, true);
            if (peak != null && last != null) peak[0] = Math.max(peak[0], last.get("shrug"));
        }
        return last;
    }

    static double shrugAmt(org.json.JSONObject ch, String role) {
        org.json.JSONObject n = cc.joycreator.avatar.face.Shrugs.firstRole(ch.optJSONObject("root"), role);
        org.json.JSONArray l = n == null ? null : n.optJSONArray("links");
        for (int i = 0; l != null && i < l.length(); i++) if (l.optJSONObject(i).has("shrug")) return l.optJSONObject(i).optDouble("amt");
        return Double.NaN;
    }

    static int dirsAnswered;

    /** A face stage on a camera that may hand the picture over mirrored; optionally the five moves first (the fourth, lean,
     *  never made); then a right turn: the head's turnX. */
    static double dirsRun(boolean mirroredCamera, boolean check) {
        FaceStage st = new FaceStage();
        float m = mirroredCamera ? -1f : 1f;
        double t = 0;
        for (int i = 0; i < 20; i++, t += 1 / 15.0) st.process(raw(0, 0), t);          // the neutral
        if (check) {
            cc.joycreator.avatar.face.DirectionPrompts dp = new cc.joycreator.avatar.face.DirectionPrompts(new cc.joycreator.avatar.face.DirectionPrompts.Face() {
                public void check(String what) { st.startDirectionCheck(what); }
                public FaceCalibration.DirectionCheck last() { return st.lastDirectionCheck(); }
            });
            long now = 0;
            dp.start(now);
            for (int i = 0; i < 15 * 60 && dp.running(); i++, t += 1 / 15.0, now += 66_666_667L) {
                Map<String, Float> f = raw(0, 0);
                String what = cc.joycreator.avatar.face.DirectionPrompts.STEPS[dp.prompt()];
                if (what.equals(FaceCalibration.HEAD_YAW)) f.put(FaceCalibration.HEAD_YAW, 3f + 20f * m);        // turning to his right
                if (what.equals(FaceCalibration.HEAD_PITCH)) f.put(FaceCalibration.HEAD_PITCH, 8f + 20f);       // looking down
                if (what.equals(FaceCalibration.HEAD_ROLL)) f.put(FaceCalibration.HEAD_ROLL, 20f * m);          // tilting to his right
                if (what.equals("eye")) { f.put(mirroredCamera ? "eyeBlinkRight" : "eyeBlinkLeft", 0.9f); }   // his right eye shut
                st.process(f, t);
                dp.step(now);                                                              // (lean: not made, times out)
            }
            dirsAnswered = dp.answered();
        }
        Map<String, Float> out = null;
        Map<String, Float> turn = raw(0, 0);
        turn.put(FaceCalibration.HEAD_YAW, 3f + 25f * m);                                  // he turns to his right
        for (int i = 0; i < 20; i++, t += 1 / 15.0) out = st.process(turn, t);
        return out.get("turnX");
    }

    static double gl(Map<String, Double> m, String k) {
        Double v = m == null ? null : m.get(k);
        return v == null ? Double.NaN : v;
    }

    static String r(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
