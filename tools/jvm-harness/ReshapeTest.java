import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.Reshape;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Pics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Reshape with pins, against the pilot: the warp of a spot (warpAt) and dropping a pin on a part that is already reshaped, with
 * numbers taken by running the pilot's own AL.Solver.warpAt and AL.fit.dropPin on Topps's face (11-27).
 */
public class ReshapeTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static JSONObject load() throws Exception {
        return CharacterFile.upgrade(new JSONObject(new String(Files.readAllBytes(new File("app/src/main/assets/avatar/topps.character.json").toPath()), StandardCharsets.UTF_8))).doc;
    }

    static JSONObject find(JSONObject doc, String id) throws Exception {
        List<JSONObject> st = new ArrayList<>();
        st.add(doc.getJSONObject("root"));
        while (!st.isEmpty()) {
            JSONObject n = st.remove(st.size() - 1);
            if (id.equals(n.optString("id"))) return n;
            JSONArray k = n.optJSONArray("kids");
            for (int i = 0; k != null && i < k.length(); i++) st.add(k.getJSONObject(i));
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        JSONObject doc = load();
        CharacterSolver sv = new CharacterSolver(doc, new WalkerTest.PngPics(doc));
        String id = "n49ojb";
        Map<String, Double> w = new HashMap<>();
        w.put("s8ejk", 1.0);
        double[][] pts = {{190.6, 174.6}, {224.2, 216.3}, {213, 146.8}};
        double[][] pilot = {{190.60194396972656, 174.86264038085938}, {224.1951446533203, 217.63748168945312}, {212.94381713867188, 147.06948852539062}};
        for (int i = 0; i < 3; i++) {
            double[] g = sv.warpAt(id, pts[i][0], pts[i][1], w);
            ok(Math.hypot(g[0] - pilot[i][0], g[1] - pilot[i][1]) < 0.02, "warpAt of point " + i + " matches the pilot (" + g[0] + "," + g[1] + " vs " + pilot[i][0] + "," + pilot[i][1] + ")");
            double[] z = sv.warpAt(id, pts[i][0], pts[i][1], new HashMap<>());
            ok(z[0] == pts[i][0] && z[1] == pts[i][1], "with no shape worn nothing moves");
        }

        // a pin dropped on the reshaped face, standing: the pilot's numbers
        JSONObject n = find(doc, id);
        Reshape.on(n, null);
        ok(Reshape.fixShape(n, null) != null && "always".equals(Reshape.fixShape(n, null).optString("sig")), "reshaping standing makes an always-on shape");
        Reshape.Warp warp = (nid, x, y, W) -> sv.warpAt(nid, x, y, W);
        JSONObject q = Reshape.dropPin(n, null, 190.6, 174.6, warp);
        ok(q.optDouble("x") == 191 && q.optDouble("y") == 175, "the pin lands where the pilot's does (" + q.optDouble("x") + "," + q.optDouble("y") + ")");
        JSONArray rd = n.getJSONArray("shapes").getJSONObject(1).getJSONObject("d").optJSONArray(q.getString("id"));
        ok(rd != null && rd.optDouble(0) == 0 && Math.abs(rd.optDouble(1) - 0.3) < 1e-9, "the mouth shape carries the new pin as the pilot's does (" + rd + ")");
        JSONObject q2 = Reshape.dropPin(n, null, 224.2, 216.3, warp);
        JSONArray rd2 = n.getJSONArray("shapes").getJSONObject(1).getJSONObject("d").optJSONArray(q2.getString("id"));
        ok(q2.optDouble("x") == 224 && q2.optDouble("y") == 216 && rd2 != null && Math.abs(rd2.optDouble(1) - 1.3) < 1e-9, "the second pin too (" + q2.optDouble("x") + "," + q2.optDouble("y") + " " + rd2 + ")");

        // the part did not move when the pins landed, in the reshape or in the mouth's shape
        CharacterSolver sv2 = new CharacterSolver(doc, new WalkerTest.PngPics(doc));
        Map<String, Double> both = new HashMap<>();
        both.put("s8ejk", 1.0);
        both.put("fix", 1.0);
        for (int i = 0; i < 3; i++) {
            double[] g = sv2.warpAt(id, pts[i][0], pts[i][1], both), p0 = pilot[i];
            ok(Math.hypot(g[0] - p0[0], g[1] - p0[1]) < 0.6, "after the drops the mouth shape still moves point " + i + " as before (" + g[0] + "," + g[1] + ")");
            double[] z = sv2.warpAt(id, pts[i][0], pts[i][1], Reshape.weights(n, null));
            ok(Math.hypot(z[0] - pts[i][0], z[1] - pts[i][1]) < 0.6, "standing, point " + i + " has not moved (" + z[0] + "," + z[1] + ")");
        }

        // dragging a pin writes this shape's offset; the pin is drawn there; start again puts it back
        Reshape.dragTo(n, null, q.getString("id"), 191, 175 + 12);
        double[] shown = Reshape.shownAt(n, q, null);
        ok(Math.abs(shown[0] - 191) < 0.11 && Math.abs(shown[1] - 187) < 0.11, "a dragged pin is shown where it was dragged (" + shown[0] + "," + shown[1] + ")");
        CharacterSolver sv3 = new CharacterSolver(doc, new WalkerTest.PngPics(doc));
        double[] pulled = sv3.warpAt(id, 191, 175, Reshape.weights(n, null));
        ok(pulled[1] > 175 + 6, "pulling the pin pulls the part with it (" + pulled[1] + ")");
        Reshape.startAgain(n, null);
        CharacterSolver sv4 = new CharacterSolver(doc, new WalkerTest.PngPics(doc));
        double[] back = sv4.warpAt(id, 191, 175, Reshape.weights(n, null));
        ok(Math.abs(back[1] - 175) < 0.6, "Start again puts it back (" + back[1] + ")");

        // the last pin dropped in this pose goes; a stance's pins and the standing ones are kept apart
        JSONObject pose = doc.getJSONArray("poses").getJSONObject(0);
        Reshape.on(n, pose);
        ok(Reshape.fixShape(n, pose.getString("id")) != null && Reshape.fixShape(n, pose.getString("id")).optString("sig").startsWith("trig:"), "a stance gets its own shape, on its button");
        ok(Reshape.ownPins(n, pose.getString("id")).isEmpty() && Reshape.ownPins(n, null).size() >= 2, "the standing pins are not the stance's own");
        ok(Reshape.removePin(n, pose.getString("id")) == null, "a stance cannot remove a standing pin");
        JSONObject r = Reshape.removePin(n, null);
        ok(r != null && r.getString("id").equals(q2.getString("id")), "the last standing pin is the one taken out");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
