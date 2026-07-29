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

        // 7. FEATHER (soft edges) — the v1 slider's model half.
        CompositingSpec f = new CompositingSpec();
        check(f.maskFeather == 0f, "feather defaults to 0 — every shipped project is hard-edged");
        check(!f.hasFeather(), "no masks, no feather work to do");
        f.maskFeather = 0.4f;
        check(!f.hasFeather(), "feather with NO shapes is inert — it must not open a layer");
        f.masks.add(new CompositingSpec.MaskShape());
        check(f.hasFeather(), "feather + a shape is the one case that pays for a layer");
        CompositingSpec fr = CompositingSpec.fromJson(f.toJson());
        check(Math.abs(fr.maskFeather - 0.4f) < 1e-6, "feather round-trips");
        check(Math.abs(f.copy().maskFeather - 0.4f) < 1e-6, "feather survives copy()");

        // A feather with no shapes must not make an empty-looking spec non-empty on disk:
        // it writes inside the masks block, so there is nothing to write.
        CompositingSpec lonely = new CompositingSpec();
        lonely.maskFeather = 1f;
        check(lonely.isEmpty() && lonely.toJson().size() == 0,
                "feather alone serializes to nothing");

        // Hard edge omits the key entirely (same JSON-diff hygiene as corner/rot/sub).
        CompositingSpec hard = new CompositingSpec();
        hard.masks.add(new CompositingSpec.MaskShape());
        check(!hard.toJson().has("feather"), "feather=0 omits the key");

        check(Math.abs(CompositingSpec.fromJson(JsonParser.parseString(
                "{\"masks\":[{}],\"feather\":7}").getAsJsonObject()).maskFeather - 1f) < 1e-6,
                "an out-of-range feather clamps to 1 rather than blurring the whole frame");

        // 8. featherRadiusPx — the units authority. Preview draws into a content rect and
        //    export into a full frame, so the SAME slider has to mean the same softness in
        //    both. That only holds if the radius is derived from the frame, which is what
        //    these pin. (The class of bug: zones in source vs timeline ms, twice.)
        check(CompositingSpec.featherRadiusPx(0f, 1920, 1080) == 0f,
                "feather 0 → radius 0 (the hard-edge fast path stays reachable)");
        check(CompositingSpec.featherRadiusPx(-1f, 1920, 1080) == 0f, "negative feather → 0");
        check(CompositingSpec.featherRadiusPx(0.5f, 0, 1080) == 0f, "a degenerate frame → 0");
        check(CompositingSpec.featherRadiusPx(Float.NaN, 1920, 1080) == 0f, "NaN feather → 0");
        float full = CompositingSpec.featherRadiusPx(1f, 1920, 1080);
        check(Math.abs(full - 1080 * CompositingSpec.MAX_FEATHER_FRACTION) < 1e-3,
                "full feather is MAX_FEATHER_FRACTION of the SHORTER side");
        check(Math.abs(CompositingSpec.featherRadiusPx(1f, 1080, 1920) - full) < 1e-3,
                "portrait and landscape of the same frame agree — the short side, not width");
        check(Math.abs(CompositingSpec.featherRadiusPx(2f, 1920, 1080) - full) < 1e-3,
                "an out-of-range feather clamps at the radius too, not just on read");
        // The property that makes preview and export agree: radius scales with the surface,
        // so a half-size preview gets a half-size blur of the same authored value.
        check(Math.abs(CompositingSpec.featherRadiusPx(0.5f, 960, 540) * 2f
                        - CompositingSpec.featherRadiusPx(0.5f, 1920, 1080)) < 1e-3,
                "half-size surface, half-size radius — one slider, one look in both renderers");
        check(CompositingSpec.featherRadiusPx(0.25f, 1920, 1080)
                        < CompositingSpec.featherRadiusPx(0.75f, 1920, 1080),
                "the slider is monotonic");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
