import cc.joycreator.avatar.file.FormatCheck;
import cc.joycreator.avatar.intake.PsdDoor;
import cc.joycreator.avatar.intake.PsdReader;
import cc.joycreator.avatar.intake.PsdUpdate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * "Update the art": the repainted file replaces pictures by name, every pin and setting stays, new layers land in Extras,
 * a bigger file of the same shape comes in as finer art. Run: bash tools/jvm-harness/run-avatar-psd.sh
 */
public class PsdUpdateTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    /** A PNG that is only its header: enough for the sizes the update reads. */
    static String fakePng(int w, int h, int tag) {
        ByteBuffer b = ByteBuffer.allocate(33 + 1);
        b.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}).putInt(13).put(new byte[]{'I', 'H', 'D', 'R'}).putInt(w).putInt(h)
                .put(new byte[]{8, 6, 0, 0, 0}).putInt(0).put((byte) tag);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(b.array());
    }

    static PsdDoor.PngEncoder enc(int tag) {
        return (w, h, rgba) -> fakePng(w, h, tag);
    }

    static JSONObject find(JSONObject n, String name) throws Exception {
        if (name.equals(n.optString("name"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject f = find(k.getJSONObject(i), name);
            if (f != null) return f;
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        // the character, as first imported: Head (Nose 3x2, Eye 2x2), a Hat on its own
        List<PsdReaderTest.Lay> v1 = new ArrayList<>();
        v1.add(PsdReaderTest.layer("Hat", 0, 0, 2, 2, 1, 1, 1, 255, 0));
        v1.add(PsdReaderTest.marker("</Layer group>", 3));
        v1.add(PsdReaderTest.layer("Eye", 2, 2, 2, 2, 2, 2, 2, 255, 0));
        v1.add(PsdReaderTest.layer("Nose", 0, 0, 3, 2, 3, 3, 3, 255, 0));
        v1.add(PsdReaderTest.marker("Head", 1));
        JSONObject doc = PsdDoor.build(PsdReader.parse(PsdReaderTest.psd(v1)), "Test", enc(1), null);
        JSONObject nose = find(doc.getJSONObject("root"), "Nose");
        nose.put("pivot", new JSONObject().put("x", 5).put("y", 6));                      // a pin the person placed
        final String oldNoseId = nose.getString("img");
        String oldNosePic = doc.getJSONObject("pictures").getString(oldNoseId);

        // the repainted file: Nose repainted (same size), Eye drawn twice as big, Hat gone, a new Tail
        List<PsdReaderTest.Lay> v2 = new ArrayList<>();
        v2.add(PsdReaderTest.layer("Tail", 5, 5, 2, 2, 9, 9, 9, 255, 0));
        v2.add(PsdReaderTest.marker("</Layer group>", 3));
        v2.add(PsdReaderTest.layer("Eye", 2, 2, 4, 4, 8, 8, 8, 255, 0));
        v2.add(PsdReaderTest.layer("Nose", 0, 0, 3, 2, 200, 0, 0, 255, 0));
        v2.add(PsdReaderTest.marker("Head", 1));
        PsdUpdate.Result r = PsdUpdate.apply(doc, PsdReader.parse(PsdReaderTest.psd(v2)), enc(2));

        ok(r.replaced == 1 && r.finer == 1 && r.added == 1 && r.left == 1, "one repainted, one finer, one new, one not in the new file (" + r.replaced + "/" + r.finer + "/" + r.added + "/" + r.left + ")");
        String newNosePic = doc.getJSONObject("pictures").getString(nose.getString("img"));
        ok(!newNosePic.equals(oldNosePic), "the nose's picture is the repainted one");
        ok(doc.getJSONObject("pictures").has(oldNoseId) && doc.getJSONObject("pictures").getString(oldNoseId).equals(oldNosePic), "the old picture stays in the file, so Undo can bring it back");
        ok(nose.getJSONObject("pivot").getInt("x") == 5 && nose.getJSONObject("pivot").getInt("y") == 6, "and the pin the person placed is still there");
        JSONObject eye = find(doc.getJSONObject("root"), "Eye");
        int[] shown = {doc.getJSONObject("fine").getJSONArray(eye.getString("img")).getInt(0), doc.getJSONObject("fine").getJSONArray(eye.getString("img")).getInt(1)};
        int[] real = PsdUpdate.size(doc.getJSONObject("pictures").getString(eye.getString("img")));
        ok(shown[0] == 2 && shown[1] == 2 && real[0] == 4 && real[1] == 4, "a bigger eye of the same shape is finer art: still 2 x 2 on the character, 4 x 4 pixels");
        ok(eye.getInt("x") == 2 && eye.getInt("y") == 2, "and it keeps its place");
        JSONObject tail = find(doc.getJSONObject("root"), "Tail");
        JSONObject extras = find(doc.getJSONObject("root"), "Extras");
        ok(tail != null && extras != null && extras.getJSONArray("kids").length() == 1, "a new layer lands in Extras");
        ok(find(doc.getJSONObject("root"), "Hat") != null, "a drawing that is not in the new file stays");
        List<FormatCheck.Problem> bad = FormatCheck.check(doc);
        ok(bad.isEmpty(), "the updated character follows the rules: " + bad);
        ok(PsdUpdate.size("data:image/png;base64,AAAA") == null && PsdUpdate.size("nothing") == null, "a picture that is not a PNG has no size");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
