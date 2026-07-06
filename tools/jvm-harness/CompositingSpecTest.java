import com.fadcam.ui.faditor.model.CompositingSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** §C compositing family: JSON round-trip, tolerant reads, omit-empty contract. */
public class CompositingSpecTest {
    static int fails = 0;
    static void check(boolean c, String n) { System.out.println((c?"PASS  ":"FAIL  ")+n); if(!c) fails++; }

    public static void main(String[] a) {
        // 1. Empty spec: isEmpty, and an item carrying it serializes nothing.
        CompositingSpec empty = new CompositingSpec();
        check(empty.isEmpty(), "fresh spec is empty (serializer omits it)");

        // 2. Full round-trip: masks + key + matte survive byte-exactly.
        CompositingSpec s = new CompositingSpec();
        CompositingSpec.MaskShape m1 = new CompositingSpec.MaskShape();
        m1.cx = 0.25f; m1.cy = 0.30f; m1.w = 0.20f; m1.h = 0.15f;
        m1.corner = 0.5f; m1.rotationDeg = 12f;
        CompositingSpec.MaskShape m2 = new CompositingSpec.MaskShape();
        m2.cx = 0.28f; m2.cy = 0.33f; m2.w = 0.05f; m2.h = 0.05f; m2.subtract = true;
        s.masks.add(m1);
        s.masks.add(m2);
        s.keyEnabled = true;
        s.keyColor = 0x11AA33;
        s.keyTolerance = 0.22f;
        s.keyFuzziness = 0.08f;
        s.keyOffset = -0.1f;
        s.mattePeerId = "clip-xyz";
        CompositingSpec r = CompositingSpec.fromJson(s.toJson());
        check(r.masks.size() == 2, "both mask shapes round-trip");
        check(Math.abs(r.masks.get(0).cx - 0.25f) < 1e-6
                && Math.abs(r.masks.get(0).corner - 0.5f) < 1e-6
                && Math.abs(r.masks.get(0).rotationDeg - 12f) < 1e-6
                && !r.masks.get(0).subtract, "additive shape fields exact");
        check(r.masks.get(1).subtract, "subtract flag survives");
        check(r.keyEnabled && r.keyColor == 0x11AA33
                && Math.abs(r.keyTolerance - 0.22f) < 1e-6
                && Math.abs(r.keyFuzziness - 0.08f) < 1e-6
                && Math.abs(r.keyOffset + 0.1f) < 1e-6, "chroma key round-trips");
        check("clip-xyz".equals(r.mattePeerId), "matte peer id round-trips");

        // 3. Copy is deep: mutating the copy leaves the original alone.
        CompositingSpec c = s.copy();
        c.masks.get(0).cx = 0.9f;
        c.keyColor = 0xFFFFFF;
        check(Math.abs(s.masks.get(0).cx - 0.25f) < 1e-6 && s.keyColor == 0x11AA33,
                "copy() is deep (undo/duplicate safety)");

        // 4. Tolerant read: garbage values clamp, missing fields default,
        //    a malformed section degrades instead of throwing.
        JsonObject bad = JsonParser.parseString(
                "{\"masks\":[{\"cx\":9.5,\"w\":-3,\"corner\":\"x\"}],"
                + "\"chromaKey\":{\"color\":\"not-a-color\",\"tolerance\":42},"
                + "\"matte\":{}}").getAsJsonObject();
        CompositingSpec t = CompositingSpec.fromJson(bad);
        check(t.masks.size() == 1
                && t.masks.get(0).cx <= 1f && t.masks.get(0).w > 0f
                && t.masks.get(0).corner == 0f, "garbage mask values clamp to sane");
        check(t.keyEnabled && t.keyColor == 0x00FF00 && t.keyTolerance <= 1f,
                "unparseable key color falls back to green, tolerance clamps");
        check(t.mattePeerId == null, "matte without peerId reads as no matte");

        // 5. Omit-defaults writing: a sharp, unrotated, additive shape writes
        //    only cx/cy/w/h (JSON diff hygiene for the AI contract).
        CompositingSpec lean = new CompositingSpec();
        lean.masks.add(new CompositingSpec.MaskShape());
        JsonObject lj = lean.toJson().getAsJsonArray("masks").get(0).getAsJsonObject();
        check(!lj.has("corner") && !lj.has("rot") && !lj.has("sub"),
                "default shape omits corner/rot/sub keys");

        // 6. null JSON → empty spec (absent field on old projects).
        check(CompositingSpec.fromJson(null).isEmpty(), "null json → empty spec");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
