import cc.joycreator.avatar.file.CharacterFile;
import cc.joycreator.avatar.file.Stack;
import cc.joycreator.avatar.file.Stances;
import cc.joycreator.avatar.solve.CharacterSolver;
import cc.joycreator.avatar.solve.Item;
import cc.joycreator.avatar.solve.Pics;
import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.solve.SolveOut;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** In front or behind: the stack read from the real draw list, and what a step writes, checked by drawing again. */
public class StackTest {
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

    static List<String> order(JSONObject doc, Pics pics) throws Exception {
        CharacterSolver sv = new CharacterSolver(doc, pics);
        Signals S = new Signals();
        CharacterSolver.Opt o = new CharacterSolver.Opt();
        SolveOut out = sv.eval(S, o);
        List<String> ids = new ArrayList<>();
        for (Item it : out.items) if (!it.ghost) ids.add(it.id);
        return ids;
    }

    static String name(Stack.Read r, int i) {
        return r.runs.get(i).unit.optString("role") + ":" + r.runs.get(i).unit.optString("name");
    }

    public static void main(String[] a) throws Exception {
        JSONObject doc = load();
        Pics pics = new WalkerTest.PngPics(doc);
        List<String> ids = order(doc, pics);
        ok(ids.size() > 5, "the draw list has the parts (" + ids.size() + ")");
        // a part the room can stack: the head's whole
        JSONObject head = null, body = null;
        JSONObject root = doc.getJSONObject("root");
        Stack.Read any = Stack.read(doc, ids, root.getJSONArray("kids").getJSONObject(0).optString("id"));
        for (Stack.Run r : any.runs) {
            if ("head".equals(r.unit.optString("role")) || "face".equals(r.unit.optString("role"))) head = r.unit;
            if ("torso".equals(r.unit.optString("role")) || "body".equals(r.unit.optString("role"))) body = r.unit;
        }
        ok(head != null && body != null, "the head and the body are both parts of the stack");
        Stack.Read r0 = Stack.read(doc, ids, head.optString("id"));
        ok(r0.whole, "the head is a whole part");
        int hi = r0.mine(head), bi = r0.mine(body);
        ok(hi >= 0 && bi >= 0 && r0.runs.size() >= 3, "the runs are listed back to front (" + r0.runs.size() + " runs)");
        System.out.println("  stack: " + names(r0));

        // put the head behind the body, in the standing character, and draw again
        JSONObject d1 = load();
        JSONObject head1 = find(d1, head.optString("id")), body1 = find(d1, body.optString("id"));
        List<String> before = order(d1, new WalkerTest.PngPics(d1));
        Stack.past(d1, null, head1, body1, true);
        ok(head1.has("zo") && "under".equals(head1.getJSONObject("zo").optString("how")), "a step behind the body writes the head's own word (zo)");
        List<String> after = order(d1, new WalkerTest.PngPics(d1));
        Stack.Read rb = Stack.read(d1, before, head.optString("id")), ra = Stack.read(d1, after, head.optString("id"));
        ok(ra.mine(head1) < ra.mine(body1), "drawn again, the head is now behind the body (was " + rb.mine(head1) + " vs " + rb.mine(body1) + ")");
        ok(!before.equals(after), "the picture's draw order really changed");

        // the same step, in a stance, writes only the stance's word and leaves standing alone
        JSONObject d2 = load();
        JSONObject pose = Stances.find(d2, d2.getJSONArray("poses").getJSONObject(0).getString("id"));
        JSONObject head2 = find(d2, head.optString("id")), body2 = find(d2, body.optString("id"));
        Stack.past(d2, pose.getString("id"), head2, body2, true);
        ok(!head2.has("zo"), "in a stance the part's own word is left alone");
        ok("under".equals(Stack.getZ(d2, pose.getString("id"), head2).optString("how")), "the stance holds the word");
        Stack.setZ(d2, pose.getString("id"), head2, null);
        ok(Stack.getZ(d2, pose.getString("id"), head2) == null, "As when standing forgets it");

        // the newest word wins: body told to be behind the head while the head is behind the body
        JSONObject d3 = load();
        JSONObject head3 = find(d3, head.optString("id")), body3 = find(d3, body.optString("id"));
        Stack.past(d3, null, head3, body3, true);
        Stack.past(d3, null, body3, head3, true);
        ok(!head3.has("zo"), "the older word lets go when the newer one says the opposite");
        ok(body3.has("zo"), "the newer word stands");

        // steps along the row
        JSONObject d4 = load();
        JSONObject head4 = find(d4, head.optString("id"));
        List<String> o4 = order(d4, new WalkerTest.PngPics(d4));
        int at = Stack.read(d4, o4, head.optString("id")).mine(head4);
        boolean moved = Stack.step(d4, null, o4, head4, -1);
        ok(at == 0 ? !moved : moved, "Back one steps back unless it is already at the back");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }

    static String names(Stack.Read r) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < r.runs.size(); i++) b.append(i == 0 ? "" : " < ").append(name(r, i));
        return b.toString();
    }

    static JSONObject find(JSONObject doc, String id) throws Exception {
        List<JSONObject> st = new ArrayList<>();
        st.add(doc.getJSONObject("root"));
        while (!st.isEmpty()) {
            JSONObject n = st.remove(st.size() - 1);
            if (id.equals(n.optString("id"))) return n;
            org.json.JSONArray k = n.optJSONArray("kids");
            for (int i = 0; k != null && i < k.length(); i++) st.add(k.getJSONObject(i));
        }
        return null;
    }
}
