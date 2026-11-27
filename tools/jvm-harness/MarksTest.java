import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.FormatCheck;
import cc.joycreator.avatar.file.Marks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Editing a head's landmarks (the owner's model): add, move, here / behind / not here, remove with renumbering, "they
 * meet", copy an angle. After every edit the file still follows the format's rules.
 * Run: bash tools/jvm-harness/run-avatar-marks.sh
 */
public class MarksTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static Map<String, double[]> at(Object... kv) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (double[]) kv[i + 1]);
        return m;
    }

    static boolean rules(JSONObject doc) {
        return FormatCheck.check(doc).isEmpty();
    }

    public static void main(String[] a) throws Exception {
        JSONObject doc = new JSONObject(new String(Files.readAllBytes(new File("tasks/avatar/format-tests/v2-crest-hidden.character.json").toPath()), StandardCharsets.UTF_8));
        JSONObject crest = doc.getJSONObject("root").getJSONArray("kids").getJSONObject(1);   // the set with marks
        JSONObject mk = Marks.piece(crest, "", false);
        int n0 = mk.getJSONArray("pts").length();
        ok(Marks.has(crest) && n0 > 0, "the test head has landmarks");
        JSONArray angles = crest.getJSONArray("kids");
        String A = angles.getJSONObject(0).getString("id"), B = angles.getJSONObject(1).getString("id");

        String third = angles.getJSONObject(2).getString("id");
        Marks.copyRows(crest, A, third);   // a third drawing with a live row, so "not here elsewhere" has somewhere to be seen
        // add: placed in the two drawings, "not here" in the rest, numbered on
        int i = Marks.add(mk, at(A, new double[]{10, 20}, B, new double[]{12, 22}));
        ok(i == n0 && mk.getJSONArray("pts").length() == n0 + 1 && mk.getJSONArray("pts").getJSONObject(i).getInt("n") == n0 + 1, "a new landmark is numbered on");
        ok(Marks.stateOf(mk.getJSONObject("at").getJSONArray(A).getJSONArray(i)).equals("here"), "it is here in the drawing it was placed in");
        boolean offElsewhere = true;
        for (String id : new java.util.ArrayList<>(java.util.Arrays.asList(java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(mk.getJSONObject("at").keys(), 0), false).toArray(String[]::new))))
            if (!id.equals(A) && !id.equals(B)) offElsewhere &= Marks.off(mk.getJSONObject("at").getJSONArray(id).getJSONArray(i));
        ok(offElsewhere && mk.getJSONObject("at").has(third), "and \"not here\" in every other drawing");
        ok(rules(doc), "the file still follows the rules after adding a landmark");

        // move, states
        Marks.move(mk, A, i, 33, 44);
        ok(mk.getJSONObject("at").getJSONArray(A).getJSONArray(i).getDouble(0) == 33, "a landmark moves in one drawing only");
        ok(mk.getJSONObject("at").getJSONArray(B).getJSONArray(i).getDouble(0) == 12, "and stays where it was in the other");
        Marks.setState(mk, A, i, "behind");
        ok(Marks.stateOf(mk.getJSONObject("at").getJSONArray(A).getJSONArray(i)).equals("behind") && mk.getJSONObject("at").getJSONArray(A).getJSONArray(i).getDouble(0) == 33, "behind keeps the place");
        Marks.move(mk, A, i, 35, 45);
        ok(Marks.stateOf(mk.getJSONObject("at").getJSONArray(A).getJSONArray(i)).equals("behind"), "moving a behind point leaves it behind");
        Marks.setState(mk, A, i, "here");
        ok(mk.getJSONObject("at").getJSONArray(A).getJSONArray(i).length() == 2, "here is a plain place");
        ok(rules(doc), "the file follows the rules after moves and states");

        // they meet
        Marks.setMeet(mk, A, B, 0.3);
        ok(Math.abs(Marks.meet(mk, A, B) - 0.3) < 1e-9 && Math.abs(Marks.meet(mk, B, A) - 0.7) < 1e-9, "they meet reads both ways round");
        Marks.setMeet(mk, A, B, 0.5);
        ok(!mk.has("meets") || mk.getJSONArray("meets").length() == 0 || Marks.meet(mk, A, B) == 0.5, "setting it back to half way drops it");
        ok(rules(doc), "the file follows the rules after changing where they meet");

        // copy an angle
        String C = angles.getJSONObject(2).getString("id");
        Marks.copyRows(crest, A, C);
        ok(mk.getJSONObject("at").getJSONArray(C).length() == mk.getJSONArray("pts").length(), "a copied drawing has every landmark");
        ok(mk.getJSONObject("at").getJSONArray(C).getJSONArray(i).getDouble(0) == mk.getJSONObject("at").getJSONArray(A).getJSONArray(i).getDouble(0), "exactly where its source has it");

        // remove: renumbered; the last one takes the piece away
        Marks.remove(crest, "", 0);
        JSONArray pts = mk.getJSONArray("pts");
        boolean numbered = true;
        for (int k = 0; k < pts.length(); k++) numbered &= pts.getJSONObject(k).getInt("n") == k + 1;
        ok(pts.length() == n0 && numbered, "removing a landmark numbers the rest again");
        boolean rowsShort = true;
        JSONObject atObj = mk.getJSONObject("at");
        for (java.util.Iterator<String> it = atObj.keys(); it.hasNext(); ) rowsShort &= atObj.getJSONArray(it.next()).length() == pts.length();
        ok(rowsShort, "and takes it out of every drawing");
        ok(rules(doc), "the file follows the rules after removing one");
        while (Marks.piece(crest, "", false) != null) Marks.remove(crest, "", 0);
        ok(!crest.has("marks") || !crest.getJSONObject("marks").has(""), "removing the last landmark takes the piece away");

        // a new piece on a head with none
        JSONObject fresh = new JSONObject().put("kids", new JSONArray());
        JSONObject fm = Marks.piece(fresh, "frill", true);
        ok(fm != null && fresh.getJSONObject("marks").has("frill"), "a piece is made when asked for");
        ok(Marks.piece(new JSONObject(), "x", false) == null, "and not when it is not");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
