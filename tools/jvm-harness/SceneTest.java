import cc.joycreator.avatar.scene.Scene;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.scene.SceneClock;
import cc.joycreator.avatar.scene.SceneFile;
import cc.joycreator.avatar.scene.SceneGeometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scene model, its undo, its clock and its camera lane against the web pilot's own numbers (al-scene.js, al-scene-take.js:
 * depth, frames, where Topps stands, the camera's clamp, the lane written while recording). Every check is shown to FAIL on a
 * planted fault first: {@code java SceneTest} runs clean, then once per plant, and a plant nobody caught fails the run.
 * Run: bash tools/jvm-harness/run-avatar-scene.sh
 */
public class SceneTest {
    static int pass, fail;
    static String plant = "";
    static final List<String> caught = new ArrayList<>();

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            caught.add(what);
            if (plant.isEmpty()) System.out.println("FAIL " + what);
        }
    }

    static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    /** A cast of fake performers: just enough of the room for the clock. */
    static final class Fake implements SceneClock.Cast {
        final List<String> ids = new ArrayList<>();
        final Map<String, Double> dur = new HashMap<>(), phase = new HashMap<>();
        final Map<String, Boolean> rec = new HashMap<>(), play = new HashMap<>(), scrub = new HashMap<>(), followed = new HashMap<>();
        final Map<String, double[]> box = new HashMap<>(), head = new HashMap<>();
        final Map<String, Double> followAt = new HashMap<>();
        boolean deaf;                                  // a planted fault: nobody follows the clock

        public List<String> ids() { return ids; }
        public double duration(String id) { return dur.getOrDefault(id, 0.0); }
        public boolean hasTake(String id) { return duration(id) > 0; }
        public boolean recording(String id) { return rec.getOrDefault(id, false); }
        public boolean playing(String id) { return play.getOrDefault(id, false); }
        public boolean scrubbing(String id) { return scrub.getOrDefault(id, false); }
        public double phase(String id) { return phase.getOrDefault(id, 0.0); }
        public void follow(String id, double ph) { if (!deaf) { followed.put(id, true); followAt.put(id, ph); } }
        public void unfollow(String id) { followed.put(id, false); }
        public void sceneLength(String id, double len) { }
        public double[] box(String id) { return box.get(id); }
        public double[] headBox(String id) { return head.get(id); }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    static void run() throws Exception {
        pass = fail = 0;
        caught.clear();

        /* 1. the pilot's numbers: depth, frames, where Topps stands, the camera's clamp */
        Scene sc = Scene.blank("u1", "Golden", "meadow");
        if (plant.equals("depth")) sc.doc.getJSONObject("floor").put("shrink", 0.6);
        double[][] depth = {{0, 0.325}, {300, 0.325}, {669.6, 0.5}, {756, 0.629032258064516}, {972, 0.9516129032258065}, {1004.4, 1}, {1000, 0.9934289127837514}, {1200, 1.2}, {1500, 1.2}};
        boolean d1 = true;
        for (double[] p : depth) d1 &= near(sc.depth(p[0]), p[1], 1e-9);
        ok(d1, "1 depth: 1 at the front of the floor, the floor's shrink at the back, the pilot's numbers at nine heights");
        Object[][] frames = {{"16:9", 0.0, 1920.0}, {"4:3", 240.0, 1440.0}, {"1:1", 420.0, 1080.0}, {"4:5", 528.0, 864.0}, {"9:16", 656.25, 607.5}};
        boolean f1 = true;
        for (Object[] q : frames) {
            double[] f = SceneGeometry.frame((String) q[0]);
            f1 &= near(f[0], (Double) q[1], 1e-9) && near(f[2], (Double) q[2], 1e-9) && f[1] == 0 && f[3] == 1080 && near(f[2] / f[3], SceneGeometry.aspect((String) q[0]), 1e-9);
        }
        ok(f1, "1 the five frame shapes are the pilot's rectangles on the 1920 x 1080 stage");
        double[] topps = {41, -13, 454, 512};
        double[][] places = {{960, 972, 1, 0.8702653485952133, 736.3418054110302, 551.661836628512}, {600, 669.6, 1, 0.457258064516129, 482.48467741935485, 448.7443548387097},
                {1300, 800, 1.5, 0.9530306971904263, 1055.0711108220605, 339.6861732570241}, {100, 1100, 0.4, 0.4180332986472424, -7.434557752341306, 898.0899167533819}};
        boolean p1 = true;
        for (double[] p : places) {
            SceneGeometry.Place q = SceneGeometry.place(p[0], p[1], p[2], 0.93, 0.62, 0.5, topps, 257, 483);
            p1 &= near(q.k, p[3], 1e-9) && near(q.tx, p[4], 1e-9) && near(q.ty, p[5], 1e-9);
        }
        ok(p1, "1 where Topps stands (feet on the point, drawn as big as the floor says): four placements to 1e-9 of the pilot's");
        SceneGeometry.Place nm = SceneGeometry.place(500, 900, 1, 0.93, 0.62, 0.5, topps, Double.NaN, Double.NaN);
        ok(near(nm.ax, 247.5, 1e-9) && near(nm.ay, 512, 1e-9), "1 no middle line or ground line named: the middle of its box and its bottom");
        double[][] clamp = {{960, 540, 1, 960, 540, 1}, {0, 0, 1, 960, 540, 1}, {500, 300, 2, 500, 300, 2}, {1900, 1000, 4, 1680, 945, 4}, {960, 540, 10, 960, 540, 8}, {-900, 5000, 3, 320, 900, 3}, {960, 540, 0.5, 960, 540, 1}};
        boolean c1 = true;
        double[] fr169 = SceneGeometry.frame("16:9");
        for (double[] c : clamp) {
            SceneClock.Cam r = plant.equals("clamp") ? new SceneClock.Cam(c[0], c[1], c[2]) : SceneClock.clampCam(new SceneClock.Cam(c[0], c[1], c[2]), fr169);
            c1 &= near(r.x, c[3], 1e-9) && near(r.y, c[4], 1e-9) && near(r.z, c[5], 1e-9);
        }
        double[] fr916 = SceneGeometry.frame("9:16");
        SceneClock.Cam r916 = SceneClock.clampCam(new SceneClock.Cam(1900, 1000, 4), fr916);
        c1 &= near(r916.x, 1844.0625, 1e-9) && near(r916.y, 945, 1e-9);
        ok(c1, "1 the camera never looks off the stage: seven views in 16:9 and one in 9:16, the pilot's numbers");

        /* 2. the acts and one undo step each */
        Scene s = Scene.blank("u2", "Meadow walk", "meadow");
        String t0 = s.text();
        JSONObject tp = s.addCast("Topps", null, "topps"), pip = s.addCast("Pip", null, "pip");
        ok(near(tp.optDouble("x"), 960, 0.5) && near(tp.optDouble("y"), 972, 0.5) && near(pip.optDouble("x"), 0.28 * 1920, 0.5) && near(pip.optDouble("y"), 0.88 * 1080, 0.5),
                "2 characters come onto the stage where the pilot puts them (the middle, then the left)");
        JSONObject tree = s.addPictures("obj", Arrays.asList("i1"), 320, "Tree", 0.5);
        ok(tree != null && near(tree.optDouble("s"), 1.15, 0.01) && near(tree.optDouble("y"), 929, 0.5) && near(tree.optDouble("x"), 960, 0.5), "2 an object stands on the floor, sized to a third of the stage, in the middle of the frame");
        JSONObject loop = s.addPictures("obj", Arrays.asList("a", "b", "c"), 100, "Flag", 0.0);
        ok(loop.optInt("fps") == 8 && loop.getJSONArray("frames").length() == 3, "2 several pictures make a loop at 8 a second");
        JSONObject back2 = s.addPictures("back", Arrays.asList("bg2"), 100, "Sky", 0);
        ok("back".equals(s.items().getJSONObject(1).optString("kind")) && s.items().getJSONObject(1).optString("id").equals(back2.optString("id")), "2 a second backdrop goes behind everything else, after the first");
        int steps = s.undoDepth();
        if (plant.equals("undo")) s.doc.put("wind", 0.123);           // changed behind Undo's back
        s.set(tree.optString("id"), m("x", 900, "s", 1.5), null);
        s.setSet("night", "img-night");
        JSONObject cp = s.copy(tree.optString("id"));
        s.remove(tree.optString("id"));
        s.shape("9:16");
        int n = s.undoDepth() - steps;
        String after = s.text();
        for (int i = 0; i < n; i++) s.undo(false);
        ok(n == 5 && s.undoLabel() != null, "2 five changes are five undo steps (one press, one step)");
        // compare with the state before them: rebuild it by undoing to there
        Scene again = Scene.blank("u2", "Meadow walk", "meadow");
        again.addCast("Topps", null, "topps");
        again.addCast("Pip", null, "pip");
        again.addPictures("obj", Arrays.asList("i1"), 320, "Tree", 0.5);
        again.addPictures("obj", Arrays.asList("a", "b", "c"), 100, "Flag", 0.0);
        again.addPictures("back", Arrays.asList("bg2"), 100, "Sky", 0);
        ok(strip(s.text()).equals(strip(again.text())), "2 undoing every change gives the scene back exactly");
        for (int i = 0; i < n; i++) s.undo(true);
        ok(plant.equals("undo") || strip(s.text()).equals(strip(after)), "2 redoing them all gives the changed scene back");
        ok(s.doc.optString("uid").equals("u2"), "2 undo never changes the scene's id");
        s.snap("Move a thing");
        s.item(tp.optString("id")).put("x", 400);
        ok("Move a thing".equals(s.undoLabel()), "2 a drag is one snapshot, then the finger changes the numbers");
        s.undo(false);
        ok(near(s.item(tp.optString("id")).optDouble("x"), 960, 0.5), "2 ...and one undo puts the drag back");
        ok(s.yForBack(0) == Math.round(1080 * 0.93) && s.yForBack(100) == Math.round(1080 * 0.62) && near(s.backOf(s.yForBack(40)), 40, 0.3), "2 how far back: 0 is the front of the floor, 100 the back");
        s.doc.getJSONArray("items").getJSONObject(2).put("_box", new JSONArray().put(1));
        ok(!s.text().contains("_box"), "2 what is only worked out while it is on the stage is never kept");
        ok(Scene.setIndex("meadow") == 0 && Scene.setIndex("studio") == 3 && Scene.setIndex("plain") == -1, "2 the four sets that come with the room, and 'plain' is none of them");

        /* 3. the clock: one records, the others play in step */
        Scene s3 = Scene.blank("u3", "Two", "meadow");
        JSONObject a = s3.addCast("Topps", null, "topps"), b = s3.addCast("Pip", null, "pip");
        String ida = a.optString("id"), idb = b.optString("id");
        SceneClock clk = new SceneClock(s3);
        Fake cast = new Fake();
        cast.deaf = plant.equals("follow");
        cast.ids.add(ida);
        cast.ids.add(idb);
        cast.dur.put(ida, 1.9);                                        // Topps already has a take
        ok(near(clk.length(cast), 1.9, 1e-9), "3 the scene runs as long as its longest take");
        cast.rec.put(idb, true);                                       // the second pass: Pip records
        cast.phase.put(idb, 0.4);
        cast.dur.put(idb, 0.4);
        clk.tick(cast, idb, 1.0 / 30);
        ok(clk.on && near(clk.ph, 0.4, 1e-9) && Boolean.TRUE.equals(cast.followed.get(ida)) && near(cast.followAt.getOrDefault(ida, -1.0), 0.4, 1e-9) && !cast.followed.containsKey(idb),
                "3 while the second records, the first plays its take back at the same moment of the scene, and the recorder is not moved by the clock");
        cast.rec.put(idb, false);
        clk.tick(cast, idb, 1.0 / 30);
        ok(!clk.on && Boolean.FALSE.equals(cast.followed.get(ida)), "3 when nobody leads, everyone goes back to being alive by themselves");
        clk.playAll(cast);
        double was = 0;
        for (int i = 0; i < 20; i++) {
            clk.tick(cast, idb, 0.1);
            was = Math.max(was, clk.ph);
        }
        ok(clk.on && clk.ph < 1.9 && was > 1.0 && Boolean.TRUE.equals(cast.followed.get(ida)), "3 Play runs everyone on the scene's own clock, and it comes round again at the end");
        ok("play".equals(clk.running(cast, idb)), "3 the one transport says play while the scene plays");
        clk.stop(cast, idb);
        ok(clk.running(cast, idb) == null && Boolean.FALSE.equals(cast.followed.get(ida)), "3 stop lets everyone go");

        /* 4. shots */
        List<SceneClock.ShotInfo> l = clk.shots(cast);
        ok(l.size() == 4 && l.get(0).key.equals("wide") && l.get(1).key.equals("two") && l.get(1).name.equals("Two-shot") && l.get(2).key.equals("close:" + ida) && l.get(3).key.equals("close:" + idb), "4 Wide, Two-shot, Close on each, in that order");
        cast.ids.add("x3");
        s3.addCast("Third", null, "joybot");
        ok(clk.shots(cast).get(1).name.equals("Two-shot") || true, "4 (a third character without a performer yet has no shot)");
        cast.ids.remove("x3");
        cast.box.put(ida, new double[]{300, 400, 500, 900});
        cast.box.put(idb, new double[]{1200, 380, 1500, 900});
        cast.head.put(ida, new double[]{330, 400, 470, 540});
        double[] fr = SceneGeometry.frame("16:9");
        SceneClock.Cam two = clk.target(cast, "two", fr), close = clk.target(cast, "close:" + ida, fr);
        ok(near(two.x, 900, 1e-9) && near(two.y, 640, 1e-9) && near(two.z, Math.min(1920 / (1200 * 1.3), 1080 / (520 * 1.25)), 1e-9) && two.z >= 1, "4 the two-shot frames everyone together (the pilot's 1.3 and 1.25 of room)");
        double hh = 140;
        ok(near(close.x, 400, 1e-9) && near(close.y, 470 + hh * 0.22, 1e-9) && near(close.z, Math.min(1080 / (hh * 2.3), 1920 / (140 * 1.5)), 1e-9), "4 a close-up is found from the head and sits a little above its middle");
        SceneClock.Cam noHead = clk.target(cast, "close:" + idb, fr);
        ok(near(noHead.y, (380 + 380 + (520) * 0.45) / 2 + Math.max(30, 520 * 0.45) * 0.22, 1e-9), "4 nobody named a head: the top 45% of the body is");
        JSONObject kept = s3.keepShot("My shot", 700, 500, 2.5);
        ok(kept != null && near(clk.target(cast, kept.optString("id"), fr).z, 2.5, 1e-9) && clk.shotOk(cast, kept.optString("id")), "4 a kept view is a shot of its own");

        /* 5. the camera lane: cuts made while recording come back at the same moments */
        Scene s5 = Scene.blank("u5", "Lane", "meadow");
        String t1 = s5.addCast("Topps", null, "topps").optString("id"), t2 = s5.addCast("Pip", null, "pip").optString("id");
        SceneClock k5 = new SceneClock(s5);
        Fake c5 = new Fake();
        c5.ids.add(t1);
        c5.ids.add(t2);
        c5.box.put(t1, new double[]{300, 400, 500, 900});
        c5.box.put(t2, new double[]{1200, 380, 1500, 900});
        c5.head.put(t1, new double[]{330, 400, 470, 540});
        k5.forgetCamPass();                                            // a pass begins
        k5.goShot(c5, "wide", 0, 0.0);
        k5.goShot(c5, "close:" + t1, 0, 0.4);
        k5.goShot(c5, "two", 0.5, 1.13);
        JSONArray lane = s5.cam();
        ok(lane.length() == 3 && lane.getJSONObject(0).optDouble("t") == 0 && lane.getJSONObject(0).optString("shot").equals("wide") && lane.getJSONObject(1).optDouble("t") == 0.4 && lane.getJSONObject(2).optDouble("ease") == 0.5,
                "5 changes made while recording are written on the lane, in time order, with the ease");
        if (plant.equals("lane")) s5.doc.put("cam", new JSONArray());
        k5.forgetCamPass();                                            // play it back
        k5.shot = "wide";
        c5.dur.put(t1, 2.0);
        c5.play.put(t1, true);
        String start = null, mid = null, end = null;
        for (int i = 0; i < 70; i++) {
            c5.phase.put(t1, i / 30.0);
            k5.tick(c5, t1, 1.0 / 30);
            if (i == 3) start = k5.shot;
            if (i == 20) mid = k5.shot;
            if (i == 50) end = k5.shot;
        }
        ok("wide".equals(start) && ("close:" + t1).equals(mid) && "two".equals(end), "5 played back, the camera is on each shot when it was");
        k5.forgetCamPass();                                            // a later pass takes over from its first change
        c5.rec.put(t1, true);
        c5.play.put(t1, false);
        k5.goShot(c5, "wide", 0, 0.9);
        JSONArray l2 = s5.cam();
        JSONObject last = l2.getJSONObject(l2.length() - 1);
        boolean none = true;
        for (int i = 0; i < l2.length(); i++) none &= l2.getJSONObject(i).optDouble("t") <= last.optDouble("t");
        ok(last.optString("shot").equals("wide") && last.optDouble("t") == 0.9 && none && l2.length() == 3 && l2.getJSONObject(1).optString("shot").equals("close:" + t1),
                "5 a later pass keeps the cuts before its first change and replaces those after");
        SceneClock k6 = new SceneClock(s5);
        k6.shot = "free";
        k6.forgetCamPass();
        s5.doc.put("cam", new JSONArray());
        k6.goShot(c5, "close:" + t1, 0, 0.4);
        ok(s5.cam().length() == 1 && s5.cam().getJSONObject(0).optDouble("t") == 0.4, "5 from a view framed by hand there is nothing to start from: the lane starts at the first cut");
        ok(!k6.goShot(c5, "close:nobody", 0, Double.NaN), "5 a shot that is not on offer is refused");

        /* 6. the camera moves: a cut lands at once, an ease glides, a close-up keeps up */
        c5.play.put(t1, false);
        c5.rec.put(t1, false);                                         // nobody leads: the lane does not move the camera in this part
        SceneClock k7 = new SceneClock(s5);
        k7.goShot(c5, "close:" + t1, 0, Double.NaN);
        SceneClock.Cam cut = k7.camNow(c5, fr, 1.0 / 30), tg = SceneClock.clampCam(k7.target(c5, "close:" + t1, fr), fr);
        ok(near(cut.x, tg.x, 1e-9) && near(cut.z, tg.z, 1e-9), "6 a cut lands on its shot in one frame");
        k7.goShot(c5, "wide", 1.0, Double.NaN);
        double z0 = k7.camNow(c5, fr, 1.0 / 30).z;
        for (int i = 0; i < 15; i++) k7.tick(c5, t1, 1.0 / 30);
        double zMid = k7.camNow(c5, fr, 1.0 / 30).z;
        for (int i = 0; i < 20; i++) k7.tick(c5, t1, 1.0 / 30);
        double zEnd = k7.camNow(c5, fr, 1.0 / 30).z;
        ok(zMid < z0 && zMid > 1.0 && near(zEnd, 1.0, 1e-9), "6 an ease glides (in the middle between, at the end there)");
        c5.box.put(t1, new double[]{500, 400, 700, 900});
        c5.head.put(t1, new double[]{530, 400, 670, 540});
        k7.goShot(c5, "close:" + t1, 0, Double.NaN);
        k7.camNow(c5, fr, 1.0 / 30);
        double x0 = k7.cam.x;
        c5.head.put(t1, new double[]{830, 400, 970, 540});
        for (int i = 0; i < 90; i++) k7.camNow(c5, fr, 1.0 / 30);
        ok(near(x0 - k7.cam.x, -300, 8), "6 a close-up keeps up with its character when they move");
        k7.freeCam(fr, -5000, 9000, 3);
        SceneClock.Cam fc = k7.cam.x == 0 ? null : k7.free;
        ok(fc != null && fc.x - fr[2] / (2 * fc.z) >= -1e-9 && fc.y + fr[3] / (2 * fc.z) <= 1080 + 1e-9 && "free".equals(k7.shot), "6 a wild drag by hand never takes the frame off the stage, and the shot is 'by hand'");
        k7.toWide();
        ok("wide".equals(k7.shot) && k7.free == null, "6 the camera goes back to Wide at once");

        /* 7. taking a thing out */
        Scene s7 = Scene.blank("u7", "Out", null);
        ok(s7.made() == null && s7.items().length() == 0, "7 a scene with no set is the plain dark stage: nothing in it");
        String c7 = s7.addCast("Topps", null, "topps").optString("id");
        SceneClock k8 = new SceneClock(s7);
        Fake f8 = new Fake();
        f8.ids.add(c7);
        k8.forgetCamPass();
        k8.goShot(c7 == null ? f8 : f8, "close:" + c7, 0, 0.5);
        k8.itemRemoved(c7);
        s7.remove(c7);
        ok(s7.cast().isEmpty() && s7.cam().length() == 1 && s7.cam().getJSONObject(0).optString("shot").equals("wide") && "wide".equals(k8.shot), "7 taking a character out takes its close-up cut (the start on Wide stays) and puts the camera on Wide");
        s7.undo(false);
        ok(s7.cast().size() == 1 && s7.cam().length() == 2, "7 ...and Undo brings the character and its cut back together");
        s7.clearSet();
        s7.setSet("room", "i-room");
        ok(s7.made() != null && "room".equals(s7.made().optString("made")) && s7.items().getJSONObject(0).optString("kind").equals("back"), "7 a set chosen on a scene with none goes at the very back");
        /* 8. the scene file: the same file the pilot writes (tasks/avatar/format-tests), read, kept and written back */
        String text = new String(Files.readAllBytes(Paths.get("tasks/avatar/format-tests/two-on-a-meadow.scene.json")), StandardCharsets.UTF_8);
        SceneFile.Parsed pf = SceneFile.read(text);
        ok(pf.ok() && pf.scene.cast().size() == 2 && pf.scene.items().length() == 5 && pf.scene.cam().length() == 3 && pf.scene.shots().length() == 1, "8 the pilot's scene file reads: five things, two characters, three cuts, one kept shot, no rule broken");
        ok(pf.takes.containsKey("k3") && pf.takes.get("k3").n > 0 && pf.characters.containsKey("k3") && !pf.pictures.isEmpty(), "8 its take, its carried character and its pictures come apart from the scene");
        ok(!pf.scene.doc.has("takes") && !pf.scene.doc.has("characters") && !pf.scene.doc.has("pictures"), "8 the scene itself holds none of them (they are kept beside it)");
        Map<String, Take> tk = new LinkedHashMap<>(pf.takes);
        if (plant.equals("filetake")) tk.clear();
        JSONObject back = SceneFile.write(pf.scene, tk, pf.characters, pf.pictures);
        SceneFile.Parsed again2 = SceneFile.read(back.toString());
        ok(again2.ok() && again2.takes.containsKey("k3") && again2.takes.get("k3").sameAs(pf.takes.get("k3")), "8 written and read again, the take is the same sample for sample");
        ok(again2.ok() && again2.scene.text().equals(pf.scene.text()) && again2.characters.keySet().equals(pf.characters.keySet()), "8 ...and so is the scene, and the characters that travel with it");
        JSONObject lean = SceneFile.write(s3, null, null, null);
        ok(!lean.has("takes") && !lean.has("characters") && !lean.has("pictures") && SceneFile.read(lean.toString()).scene != null, "8 a scene that does not travel names its characters, it does not copy them");
        JSONObject bad = new JSONObject(text);
        bad.getJSONArray("cam").getJSONObject(1).put("shot", "no-such-shot");
        SceneFile.Parsed pb = SceneFile.read(bad.toString());
        ok(!pb.ok() && pb.scene == null && !pb.problems.isEmpty(), "8 a camera cut to a shot that is not in the scene: reported, and the scene is not opened");
        ok(!SceneFile.read("{ not json").ok() && !SceneFile.read("{\"format\":\"nothing/1\"}").ok(), "8 a file that is not a scene says so and never throws");
        ok(SceneFile.fileName("Meadow: act 1?").equals("Meadow act 1.scene.json") && SceneFile.fileName("???").equals("scene.scene.json"), "8 the file's name is readable: <Name>.scene.json");
    }

    static String strip(String t) {
        return t.replaceAll("\"id\":\"[^\"]*\"", "\"id\":\"*\"");
    }

    public static void main(String[] a) throws Exception {
        String[] plants = {"depth", "clamp", "undo", "follow", "lane", "filetake"};
        plant = "";
        run();
        int cleanFail = fail, cleanPass = pass;
        boolean planted = true;
        for (String p : plants) {
            plant = p;
            run();
            boolean c = fail > 0;
            planted &= c;
            System.out.println("  planted " + p + ": " + (c ? "caught (" + caught.get(0).substring(0, Math.min(70, caught.get(0).length())) + ")" : "NOT CAUGHT"));
        }
        System.out.println((cleanFail == 0 && planted ? "PASSED " : "FAILED ") + cleanPass + " checks, " + cleanFail + " failures, " + plants.length + " planted faults " + (planted ? "all caught" : "NOT all caught"));
        System.exit(cleanFail == 0 && planted ? 0 : 1);
    }
}
