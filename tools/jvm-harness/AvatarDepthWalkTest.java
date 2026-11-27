import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.perform.DepthWalk;
import cc.joycreator.avatar.perform.Walker;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Pics;
import cc.joycreator.avatar.solve.Signals;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Walking toward the camera and away, against the pilot (al-depth-walk.js and the walker's depth part of al-walk.js): Topps
 * asked to the near limit, then the far limit, then Home, frame by frame, the phone's {@code Walker} + {@code DepthWalk} within a
 * hundredth of a pixel of the pilot's own walker and solver. The trace is tasks/avatar/golden/topps-walk-depth.json (made by
 * make-topps-walk-depth.js in the pilot, 11-27). Plus the depth model's own rules. Run: bash tools/jvm-harness/run-avatar-depth.sh
 */
public class AvatarDepthWalkTest {
    static int pass, fail;
    static double worst;

    static void ok(boolean c, String what) {
        System.out.println((c ? "PASS  " : "FAIL  ") + what);
        if (c) pass++;
        else fail++;
    }

    static final class PngPics implements Pics {
        final Map<String, int[]> size = new HashMap<>();
        final Map<String, byte[]> alpha = new HashMap<>();

        PngPics(JSONObject ch) throws Exception {
            JSONObject pics = ch.getJSONObject("pictures");
            for (java.util.Iterator<String> it = pics.keys(); it.hasNext(); ) {
                String k = it.next(), u = pics.getString(k);
                BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(u.substring(u.indexOf(',') + 1))));
                if (im == null) continue;
                int w = im.getWidth(), h = im.getHeight();
                byte[] al = new byte[w * h];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) al[y * w + x] = (byte) ((im.getRGB(x, y) >>> 24) & 0xff);
                size.put(k, new int[]{w, h});
                alpha.put(k, al);
            }
        }

        public boolean has(String img) { return size.containsKey(img); }
        public int w(String img) { return size.get(img)[0]; }
        public int h(String img) { return size.get(img)[1]; }
        public int ver(String img) { return 1; }
        public byte[] alpha(String img) { return alpha.get(img); }
    }

    /** The room's hands, quiet: nothing held, no stance moving. */
    static final class Hands implements Walker.Body {
        boolean flip;
        public boolean flip() { return flip; }
        public void setFlip(boolean f) { flip = f; }
        public void reverseTurn() { }
        public boolean heldHead() { return false; }
        public double[] placed(String held) { return null; }
        public double stanceLevel(String id) { return 0; }
        public boolean trigOn(String id) { return false; }
        public void setTrig(String id, boolean on) { }
    }

    static boolean near(double a, double b, double tol) {
        double d = Math.abs(a - b);
        if (d > worst) worst = d;
        return d <= tol;
    }

    static boolean handNear(JSONArray exp, Signals.Hand got, double tol) throws Exception {
        if (exp == null) return got == null;
        if (got == null) return false;
        return near(exp.getDouble(0), got.x, tol) && near(exp.getDouble(1), got.y, tol);
    }

    static JSONObject findRole(JSONObject n, String role) throws Exception {
        if (role.equals(n.optString("role"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) { JSONObject r = findRole(k.getJSONObject(i), role); if (r != null) return r; }
        return null;
    }

    static JSONObject read(String p) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8));
    }

    public static void main(String[] a) throws Exception {
        JSONObject doc = CharacterFile.upgrade(read("app/src/main/assets/avatar/topps.character.json")).doc;
        Pics pics = new PngPics(doc);
        JSONObject set = doc.getJSONObject("settings");
        set.put("idle", false);
        set.put("blinkAuto", false);
        set.put("breathe", false);
        JSONObject trace = read("tasks/avatar/golden/topps-walk-depth.json");
        JSONArray frames = trace.getJSONArray("frames");
        double dt = trace.getDouble("dt");
        Hands hands = new Hands();
        Walker w = new Walker(doc, hands);
        CharacterSolver sv = new CharacterSolver(doc, pics);
        double[] rb = sv.boundsOf(doc.getJSONObject("root").getString("id"));
        w.reset();
        Map<String, double[]> anchor = new HashMap<>(), tips = new HashMap<>();
        int bad = 0, gone = -1;
        double big = 0, small = 9;
        boolean want = true, modelOk = true;
        for (int i = 0; i < frames.length(); i++) {
            if (i >= 10 && i < 80) w.tz = 1e6;
            else if (i >= 80 && i < 150) w.tz = -1e6;
            if (i == 150) {
                want = false;
                w.home();
            }
            DepthWalk.prep(w, want, doc, rb);                // (the pilot's dw.prep runs at the start of its input step)
            Signals S = new Signals();
            S.flip = hands.flip;
            w.step(dt, S, anchor, tips);
            CharacterSolver.Opt opt = new CharacterSolver.Opt();
            opt.live = true;
            opt.dt = dt;
            sv.eval(S, opt);
            anchor = new HashMap<>(S.anchor);
            tips = new HashMap<>(S.tips);
            JSONObject e = frames.getJSONObject(i);
            JSONObject ez = e.optJSONObject("z");
            if ((ez == null) != (w.z == null)) modelOk = false;
            else if (ez != null && (!near(ez.getJSONArray("lim").getDouble(0), w.z.lim[0], 0.01) || !near(ez.getJSONArray("lim").getDouble(1), w.z.lim[1], 0.01)
                    || (ez.isNull("front") != (w.z.front == null)) || (w.z.front != null && !near(ez.getDouble("front"), w.z.front, 1e-9)))) modelOk = false;
            if (gone < 0 && i > 150 && w.z == null) gone = i;
            big = Math.max(big, S.rootScale);
            small = Math.min(small, S.rootScale);
            boolean good = near(e.getDouble("rx"), S.rootX, 0.01) && near(e.getDouble("ry"), S.rootY, 0.01) && near(e.getDouble("rs"), S.rootScale, 0.001)
                    && handNear(e.optJSONArray("FL"), S.hand.get("FL"), 0.01) && handNear(e.optJSONArray("FR"), S.hand.get("FR"), 0.01)
                    && near(e.getDouble("fl"), S.foot.get("FL"), 0.001) && near(e.getDouble("fr"), S.foot.get("FR"), 0.001)
                    && near(e.getDouble("walk"), S.walk, 0.001) && near(e.getDouble("sway"), S.sway, 0.001) && near(e.getDouble("bounce"), S.bounce, 0.001)
                    && near(e.getDouble("base"), w.baseY, 0.01) && near(e.getDouble("turnX"), S.turnX, 0.001);
            if (!good) {
                bad++;
                if (bad <= 2) System.out.println("  frame " + i + ": pilot rs " + e.getDouble("rs") + " ry " + e.getDouble("ry") + " base " + e.getDouble("base") + " FL " + e.opt("FL")
                        + " | phone rs " + S.rootScale + " ry " + S.rootY + " base " + w.baseY + " FL " + (S.hand.get("FL") == null ? "-" : S.hand.get("FL").x + "," + S.hand.get("FL").y));
            }
        }
        ok(bad == 0, "walking toward the camera, away, and Home: every one of " + frames.length() + " frames agrees with the pilot's walker (" + bad + " differ, worst " + worst + ")");
        ok(modelOk, "the depth model is made while depth is in use and let go once it is home, its limits and front drawing as the pilot's");
        ok(big > 1.4 && small < 0.7 && gone > 150, "it grows coming close and shrinks going away (" + big + ", " + small + "), and the stage is plain again once home (frame " + gone + ")");

        // coming toward the camera it turns to its front drawing (here moved off the middle of the turn pad, to see it)
        JSONObject off = new JSONObject(doc.toString());
        JSONObject head = findRole(off.getJSONObject("root"), "head");
        JSONArray hk = head.getJSONArray("kids");
        for (int i = 0; i < hk.length(); i++) { JSONArray at = hk.getJSONObject(i).optJSONArray("at"); if (at != null && Math.abs(at.getDouble(0)) < 0.01 && Math.abs(at.getDouble(1)) < 0.01) at.put(0, 0.2); }
        Walker wf = new Walker(off, hands);
        CharacterSolver sf = new CharacterSolver(off, pics);
        wf.reset();
        Map<String, double[]> an = new HashMap<>(), tp = new HashMap<>();
        double turned = 0;
        for (int i = 0; i < 60; i++) {
            wf.tz = 1e6;
            DepthWalk.prep(wf, true, off, rb);
            Signals S = new Signals();
            wf.step(dt, S, an, tp);
            CharacterSolver.Opt opt = new CharacterSolver.Opt();
            opt.live = true;
            opt.dt = dt;
            sf.eval(S, opt);
            an = new HashMap<>(S.anchor);
            tp = new HashMap<>(S.tips);
            turned = Math.max(turned, S.turnX);
        }
        ok(wf.z != null && wf.z.front != null && Math.abs(wf.z.front - 0.2) < 1e-9 && turned > 0.1, "coming toward the camera it turns to its front drawing (" + turned + ")");

        // the depth model's own rules
        ok(Math.abs(DepthWalk.k(45) - 1) < 1e-9 && Math.abs(DepthWalk.k(0)) < 1e-9 && DepthWalk.k(75) > 1.3, "the camera's angle: 45 degrees is the start, overhead no change of size, lower more");
        JSONObject flat = new JSONObject(doc.toString());
        flat.getJSONObject("settings").put("floorAngle", 0);
        DepthWalk over = DepthWalk.plain(flat, rb);
        ok(Math.abs(over.s(150) - 1) < 1e-9, "seen from overhead it does not change size");
        Walker still = new Walker(doc, hands);
        still.reset();
        DepthWalk.prep(still, false, doc, rb);
        ok(still.z == null, "with depth not in use, the plain stage is exactly as before (no depth model)");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
