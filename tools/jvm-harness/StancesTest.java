import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.FormatCheck;
import cc.joycreator.avatar.file.Stances;
import cc.joycreator.avatar.intake.PsdUpdate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Stances: add, rename, copy, remove; all fours worked out from the art, checked against the stance the pilot worked out
 * for Topps (hips, how far it tips, how far the hips come down, where the torso goes). Run: bash tools/jvm-harness/run-avatar-stances.sh
 */
public class StancesTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static JSONObject byId(JSONObject n, String id) {
        if (id.equals(n.optString("id"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject f = byId(k.optJSONObject(i), id);
            if (f != null) return f;
        }
        return null;
    }

    /** The union of every drawing under a part, at its own pixel size. */
    static void grow(JSONObject n, JSONObject pics, double[] b) {
        if ("art".equals(n.optString("type"))) {
            int[] sz = PsdUpdate.size(pics.optString(n.optString("img"), ""));
            if (sz == null) return;
            double x = n.optDouble("x"), y = n.optDouble("y");
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.max(b[2], x + sz[0]);
            b[3] = Math.max(b[3], y + sz[1]);
        }
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) grow(k.optJSONObject(i), pics, b);
    }

    public static void main(String[] a) throws Exception {
        JSONObject topps = new JSONObject(new String(Files.readAllBytes(new File("app/src/main/assets/avatar/topps.character.json").toPath()), StandardCharsets.UTF_8));
        final JSONObject doc = CharacterFile.upgrade(topps).doc;
        final JSONObject pics = doc.getJSONObject("pictures");
        Stances.Bounds bounds = n -> {
            double[] b = {1e9, 1e9, -1e9, -1e9};
            grow(n, pics, b);
            return b[0] > b[2] ? null : b;
        };

        // the stance the pilot worked out, kept to compare
        JSONObject pilot = Stances.fours(doc);
        ok(pilot != null && pilot.has("fours"), "Topps comes with the all-fours stance the pilot worked out");
        double pAngle = pilot.getJSONObject("fours").getDouble("angle"), pDrop = pilot.getJSONObject("fours").optDouble("drop", 0);
        JSONObject pHip = pilot.getJSONObject("fours").getJSONObject("hip");
        JSONObject pTorso = null;
        String torsoId = null;
        for (java.util.Iterator<String> it = pilot.getJSONObject("d").keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject e = pilot.getJSONObject("d").getJSONObject(k);
            if (e.has("orbit") && Math.abs(e.optDouble("autoRot") - pAngle) < 1e-6) {
                torsoId = k;
                pTorso = e;
            }
        }
        ok(Stances.canFours(doc, bounds), "Topps can go onto all fours");

        // worked out again from the art, at the pilot's own angle, over a copy
        JSONObject mine = CharacterFile.upgrade(topps).doc;
        JSONObject made = Stances.makeFours(mine, null, n -> {
            double[] b = {1e9, 1e9, -1e9, -1e9};
            grow(n, mine.optJSONObject("pictures"), b);
            return b[0] > b[2] ? null : b;
        });
        ok(made != null, "all fours is worked out");
        JSONObject f = made.getJSONObject("fours");
        ok(Math.abs(f.getDouble("angle") - pAngle) <= 1, "it tips as far as the pilot's (" + f.getDouble("angle") + " against " + pAngle + ")");
        ok(Math.abs(f.getDouble("drop") - pDrop) <= 2, "the hips come down as far (" + f.getDouble("drop") + " against " + pDrop + ")");
        ok(Math.abs(f.getJSONObject("hip").getDouble("x") - pHip.getDouble("x")) < 1 && Math.abs(f.getJSONObject("hip").getDouble("y") - pHip.getDouble("y")) < 1, "round the same hips");
        JSONObject myTorso = made.getJSONObject("d").getJSONObject(torsoId);
        ok(Math.abs(myTorso.getDouble("rot") - pTorso.getDouble("rot")) < 1.5 && myTorso.getJSONObject("orbit").getDouble("x") == pTorso.getJSONObject("orbit").getDouble("x"), "the torso turns by the same and about the same point");
        ok(FormatCheck.check(mine).isEmpty(), "the character still follows the rules: " + FormatCheck.check(mine));
        ok(made.getJSONObject("d").length() >= 4, "the legs, tail, head and arms are placed for the stance too (" + made.getJSONObject("d").length() + " parts)");

        // where the arms are slid to: the numbers the pilot's own makeFours gives for Topps today (run in the pilot, 11-27)
        JSONObject dd = made.getJSONObject("d");
        ok(Math.abs(dd.getJSONObject("n3fn72").getDouble("autoX") - -48.49504400162726) < 1e-6 && Math.abs(dd.getJSONObject("n3fn72").getDouble("autoY") - -45.888197931529746) < 1e-6, "the left arm is slid to exactly where the pilot slides it");
        ok(Math.abs(dd.getJSONObject("n3oyl7").getDouble("autoX") - 68.60131052936487) < 1e-6 && Math.abs(dd.getJSONObject("n3oyl7").getDouble("autoY") - -82.78104835023154) < 1e-6, "and the right arm too");

        // a hand move survives working it out again
        String armId = null;
        for (java.util.Iterator<String> it = made.getJSONObject("d").keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals(torsoId) && made.getJSONObject("d").getJSONObject(k).optBoolean("auto")) armId = k;
        }
        JSONObject e = made.getJSONObject("d").getJSONObject(armId);
        double auto = e.optDouble("autoX");
        e.put("x", auto + 12);
        ok(Stances.handMoves(made) == 1, "a part moved by hand is counted");
        Stances.makeFours(mine, f.getDouble("angle"), n -> {
            double[] b = {1e9, 1e9, -1e9, -1e9};
            grow(n, mine.optJSONObject("pictures"), b);
            return b[0] > b[2] ? null : b;
        });
        ok(Math.abs(mine.getJSONArray("poses").length() - doc.getJSONArray("poses").length()) == 0, "working it out again updates the stance, it does not add another");
        JSONObject again = Stances.fours(mine).getJSONObject("d").getJSONObject(armId);
        ok(Math.abs(again.optDouble("x") - again.optDouble("autoX") - 12) < 0.2, "and the move placed by hand is still there");
        Stances.clearHand(Stances.fours(mine));
        ok(Stances.handMoves(Stances.fours(mine)) == 0, "Work it out again forgets every hand move");

        // ordinary stances
        JSONObject sit = Stances.add(mine, "Sitting");
        ok(sit.has("trig") && FormatCheck.check(mine).isEmpty(), "a new stance has its own button and the file is fine");
        Stances.entry(sit, torsoId).put("y", 30);
        JSONObject cp = Stances.copy(mine, sit);
        ok(cp.getJSONObject("d").getJSONObject(torsoId).getDouble("y") == 30 && !cp.getString("trig").equals(sit.getString("trig")), "a copy carries the moves and has its own button");
        Stances.rename(mine, sit, "Crouch");
        boolean named = false;
        for (int i = 0; i < mine.getJSONArray("triggers").length(); i++) named |= "Crouch".equals(mine.getJSONArray("triggers").getJSONObject(i).optString("name"));
        ok(named && "Crouch".equals(sit.getString("name")), "renaming a stance renames its button");
        int before = mine.getJSONArray("poses").length();
        Stances.remove(mine, cp);
        ok(mine.getJSONArray("poses").length() == before - 1 && FormatCheck.check(mine).isEmpty(), "removing a stance takes its button too and the file is fine");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
