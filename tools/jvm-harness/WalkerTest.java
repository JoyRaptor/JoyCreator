import cc.joycreator.avatar.file.CharacterFile;
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
 * Walking, against the pilot: the same drags on Topps (walk across the stage; jump and fall; push down onto all fours),
 * frame by frame, the walker's output within a hundredth of a pixel of the pilot's. The traces are in tasks/avatar/golden/
 * (made by running the pilot's own walker and solver, 11-27). Run: bash tools/jvm-harness/run-avatar-walk.sh
 */
public class WalkerTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
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

    /** The room's hands, as the pilot's inp: a flip, buttons and a stance level on a spring. */
    static final class Hands implements Walker.Body {
        boolean flip, heldHead;
        final Map<String, Boolean> trig = new HashMap<>();
        double[] lv = new double[2];
        String poseId, trigId;
        double time = 0.6;

        public boolean flip() { return flip; }
        public void setFlip(boolean f) { flip = f; }
        public void reverseTurn() { }
        public boolean heldHead() { return heldHead; }
        public double[] placed(String held) { return null; }
        public double stanceLevel(String id) { return id.equals(poseId) ? lv[0] : 0; }
        public boolean trigOn(String id) { Boolean b = trig.get(id); return b != null && b; }
        public void setTrig(String id, boolean on) { trig.put(id, on); }

        /** The pilot's spring (al-inputs.js easeLevels), once a frame. */
        void ease(double dt) {
            double tgt = trigOn(trigId) ? 1 : 0, x = lv[0], v = lv[1], om = 5.4 / Math.max(0.08, time), z = 1, h = dt / 3;
            for (int i = 0; i < 3; i++) {
                v += (om * om * (tgt - x) - 2 * z * om * v) * h;
                x += v * h;
            }
            if (Math.abs(tgt - x) < 0.0006 && Math.abs(v) < 0.004) {
                x = tgt;
                v = 0;
            }
            lv[0] = x;
            lv[1] = v;
        }
    }

    static double worst;

    static boolean near(double a, double b, double tol) {
        double d = Math.abs(a - b);
        if (d > worst) worst = d;
        return d <= tol;
    }

    static boolean handNear(JSONArray exp, Signals.Hand got, double tol) throws Exception {
        if (exp == null || exp.isNull(0)) return got == null;
        if (got == null) return false;
        return near(exp.getDouble(0), got.x, tol) && near(exp.getDouble(1), got.y, tol) && (exp.getInt(2) == 1) == got.w;
    }

    static JSONObject read(String p) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8));
    }

    /** Runs the walker through a trace's own drags and compares every frame. Returns the number of frames that disagree. */
    static int run(JSONObject doc, JSONObject trace, Pics pics, String kind) throws Exception {
        JSONObject set = doc.getJSONObject("settings");
        set.put("idle", false);
        set.put("blinkAuto", false);
        set.put("breathe", false);
        Hands hands = new Hands();
        JSONObject fours = null;
        for (int i = 0; i < doc.getJSONArray("poses").length(); i++) {
            JSONObject p = doc.getJSONArray("poses").getJSONObject(i);
            if (p.getString("id").equals(set.getJSONObject("walk").getString("fours"))) fours = p;
        }
        hands.poseId = fours.getString("id");
        hands.trigId = fours.getString("trig");
        hands.time = fours.optDouble("time", 0.6);
        Walker w = new Walker(doc, hands);
        CharacterSolver sv = new CharacterSolver(doc, pics);
        w.reset();
        Map<String, double[]> anchor = new HashMap<>(), tips = new HashMap<>();
        JSONArray frames = trace.getJSONArray("frames");
        double dt = trace.getDouble("dt");
        int bad = 0;
        for (int i = 0; i < frames.length(); i++) {
            if (kind.equals("walk")) {
                if (i == 0) w.grab(0, 0);
                if (i == 100) w.release();
                if (w.held) w.drag(i < 100 ? Math.min(420, i * 9) : 420, 0);
            } else {
                double gy = i < 30 ? -i * 9 : i < 60 ? -270 : i < 150 ? 0 : 170;
                if (i == 0) w.grab(0, 0);
                if (i == 60) w.release();
                if (i == 150) w.grab(0, 0);
                if (i == 230) w.release();
                if (w.held) w.drag(0, gy);
            }
            Signals S = new Signals();
            S.flip = hands.flip;
            w.step(dt, S, anchor, tips);                     // the walker reads last frame's stance level...
            hands.ease(dt);                                  // ...then the levels ease (the end of the pilot's input step)
            S.pose.put(hands.poseId, hands.lv[0]);
            S.flip = hands.flip;
            CharacterSolver.Opt opt = new CharacterSolver.Opt();
            opt.live = true;
            opt.dt = dt;
            sv.eval(S, opt);
            anchor = new HashMap<>(S.anchor);
            tips = new HashMap<>(S.tips);
            JSONObject e = frames.getJSONObject(i);
            boolean good = near(e.getDouble("rx"), S.rootX, 0.01) && near(e.getDouble("ry"), S.rootY, 0.01) && near(e.getDouble("rs"), S.rootScale, 0.001)
                    && handNear(e.optJSONArray("FL"), S.hand.get("FL"), 0.01) && handNear(e.optJSONArray("FR"), S.hand.get("FR"), 0.01)
                    && handNear(e.optJSONArray("L"), S.hand.get("L"), 0.01) && handNear(e.optJSONArray("R"), S.hand.get("R"), 0.01)
                    && near(e.getDouble("fl"), S.foot.get("FL"), 0.001) && near(e.getDouble("fr"), S.foot.get("FR"), 0.001)
                    && near(e.getDouble("walk"), S.walk, 0.001) && near(e.getDouble("sway"), S.sway, 0.001) && near(e.getDouble("bounce"), S.bounce, 0.001);
            if (!good) {
                bad++;
                if (bad <= 2) System.out.println("  parts: root " + near(e.getDouble("rx"), S.rootX, 0.01) + near(e.getDouble("ry"), S.rootY, 0.01) + " FL " + handNear(e.optJSONArray("FL"), S.hand.get("FL"), 0.01) + " FR " + handNear(e.optJSONArray("FR"), S.hand.get("FR"), 0.01)
                        + " L " + handNear(e.optJSONArray("L"), S.hand.get("L"), 0.01) + " R " + handNear(e.optJSONArray("R"), S.hand.get("R"), 0.01) + " feet " + near(e.getDouble("fl"), S.foot.get("FL"), 0.001) + near(e.getDouble("fr"), S.foot.get("FR"), 0.001)
                        + " walk " + near(e.getDouble("walk"), S.walk, 0.001) + " sway " + near(e.getDouble("sway"), S.sway, 0.001) + " bounce " + near(e.getDouble("bounce"), S.bounce, 0.001) + " pilot L " + e.opt("L") + " mine " + (S.hand.get("L") == null ? "-" : S.hand.get("L").x + "," + S.hand.get("L").y));
                if (bad <= 3)
                    System.out.println("  frame " + i + " differs: root " + S.rootX + "," + S.rootY + " pilot " + e.getDouble("rx") + "," + e.getDouble("ry") + "; FL "
                            + (S.hand.get("FL") == null ? "-" : S.hand.get("FL").x + "," + S.hand.get("FL").y) + " pilot " + e.opt("FL"));
            }
        }
        return bad;
    }

    public static void main(String[] a) throws Exception {
        JSONObject doc = CharacterFile.upgrade(read("app/src/main/assets/avatar/topps.character.json")).doc;
        Pics pics = new PngPics(doc);
        JSONObject t1 = read("tasks/avatar/golden/topps-walk.json");
        int bad1 = run(CharacterFile.upgrade(read("app/src/main/assets/avatar/topps.character.json")).doc, t1, pics, "walk");
        ok(bad1 == 0, "walking across the stage: every one of " + t1.getJSONArray("frames").length() + " frames agrees with the pilot (" + bad1 + " differ, worst " + worst + ")");
        worst = 0;
        JSONObject t2 = read("tasks/avatar/golden/topps-walk-jump-fours.json");
        int bad2 = run(CharacterFile.upgrade(read("app/src/main/assets/avatar/topps.character.json")).doc, t2, pics, "jump");
        ok(bad2 == 0, "jumping, falling and going onto all fours: every one of " + t2.getJSONArray("frames").length() + " frames agrees (" + bad2 + " differ, worst " + worst + ")");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
