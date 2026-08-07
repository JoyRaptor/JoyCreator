import com.fadcam.ui.faditor.model.CompositingSpec;

/**
 * JVM harness for CompositingSpec's multi-shape model — serialization, slot stability, and the
 * schema-v13 predicate.
 *
 * <p><b>The load-bearing test is byte-identity.</b> The whole multi-shape design rests on one
 * promise: a project that only ever used add/subtract shapes serializes to exactly the bytes it
 * did before modes and slots existed, so it keeps its old schema stamp and older builds keep
 * opening it losslessly. That promise is what lets the v13 stamp be conditional. If it breaks,
 * every existing masked project silently becomes unopenable by an older build — so it is
 * checked as a STRING, not as a field-by-field comparison, because key order is part of the
 * claim.</p>
 *
 * <p><b>The second is slot stability.</b> Keyframe tracks are named off the slot, so a slot that
 * renumbers on delete hands shape 3's animation to shape 2 — spec risk R11. The test deletes
 * from the middle and proves the survivor keeps its original number.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-mask.sh}</p>
 */
public class CompositingSpecTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        addSubtractOnlyIsByteIdentical();
        intersectWritesTheModeKey();
        slotsNeverRenumberOnDelete();
        roundTripPreservesModeAndSlot();
        schema13FiresOnBothTriggersAndOnlyThem();
        copyFromMutatesInPlace();
        presetsAreAStartingShapeNotAReset();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    static CompositingSpec.MaskShape add(CompositingSpec s, float cx, int mode) {
        CompositingSpec.MaskShape m = s.addShape();
        m.cx = cx; m.cy = 0.5f; m.w = 0.3f; m.h = 0.5f; m.corner = 1f;
        m.mode = mode;
        return m;
    }

    // ── Serialization ───────────────────────────────────────────────────────

    static void addSubtractOnlyIsByteIdentical() {
        CompositingSpec s = new CompositingSpec();
        add(s, 0.5f, CompositingSpec.MODE_ADD);
        add(s, 0.2f, CompositingSpec.MODE_SUBTRACT);
        String json = s.toJson().toString();

        check("an ADD shape writes no 'mode' and no 'slot'",
                !json.contains("\"mode\"") && !json.contains("\"slot\""));
        check("a SUBTRACT shape still writes the legacy \"sub\" key", json.contains("\"sub\":true"));
        check("the shape keys are exactly the pre-change set, in the pre-change order",
                json.contains("\"cx\":0.5,\"cy\":0.5,\"w\":0.3,\"h\":0.5,\"corner\":1.0"));
        check("and this spec does NOT ask for v13", !s.needsSchema13());
    }

    static void intersectWritesTheModeKey() {
        CompositingSpec s = new CompositingSpec();
        add(s, 0.5f, CompositingSpec.MODE_ADD);
        add(s, 0.7f, CompositingSpec.MODE_INTERSECT);
        String json = s.toJson().toString();
        check("an INTERSECT shape writes \"mode\":2", json.contains("\"mode\":2"));
        check("...and only that one shape does", count(json, "\"mode\"") == 1);
        check("an intersect spec asks for v13", s.needsSchema13());
    }

    // ── Slots ───────────────────────────────────────────────────────────────

    static void slotsNeverRenumberOnDelete() {
        CompositingSpec s = new CompositingSpec();
        add(s, 0.1f, CompositingSpec.MODE_ADD);
        add(s, 0.2f, CompositingSpec.MODE_ADD);
        add(s, 0.3f, CompositingSpec.MODE_ADD);
        check("three shapes get slots 0,1,2",
                s.masks.get(0).slot == 0 && s.masks.get(1).slot == 1 && s.masks.get(2).slot == 2);

        s.removeShape(1);
        check("deleting the middle shape leaves two", s.masks.size() == 2);
        check("the SURVIVOR keeps slot 2 — it does not slide down to 1",
                s.masks.get(1).slot == 2);
        check("...which is exactly when an explicit \"slot\" must be persisted",
                s.hasExplicitSlots() && s.toJson().toString().contains("\"slot\":2"));

        CompositingSpec.MaskShape next = s.addShape();
        check("a NEW shape never reuses the freed slot 1", next.slot != 1);
        check("...it takes the next unused number", next.slot == 3);
    }

    static void roundTripPreservesModeAndSlot() {
        CompositingSpec s = new CompositingSpec();
        add(s, 0.1f, CompositingSpec.MODE_ADD);
        add(s, 0.2f, CompositingSpec.MODE_INTERSECT);
        add(s, 0.3f, CompositingSpec.MODE_SUBTRACT);
        s.removeShape(0);                       // force a slot divergence too
        s.maskFeather = 0.25f;
        s.invertMasks = true;

        CompositingSpec r = CompositingSpec.fromJson(s.toJson());
        check("round-trip keeps the shape count", r.masks.size() == s.masks.size());
        boolean modes = true, slots = true;
        for (int i = 0; i < s.masks.size(); i++) {
            if (r.masks.get(i).mode != s.masks.get(i).mode) modes = false;
            if (r.masks.get(i).slot != s.masks.get(i).slot) slots = false;
        }
        check("round-trip keeps every mode", modes);
        check("round-trip keeps every slot — the keyframe tracks depend on it", slots);
        check("round-trip keeps feather and invert",
                r.maskFeather == 0.25f && r.invertMasks);
        check("a second trip is stable", r.toJson().toString().equals(s.toJson().toString()));
    }

    // ── The stamp predicate ─────────────────────────────────────────────────

    static void schema13FiresOnBothTriggersAndOnlyThem() {
        CompositingSpec plain = new CompositingSpec();
        add(plain, 0.5f, CompositingSpec.MODE_ADD);
        add(plain, 0.2f, CompositingSpec.MODE_SUBTRACT);
        check("add/subtract only → no v13", !plain.needsSchema13());

        CompositingSpec inter = new CompositingSpec();
        add(inter, 0.5f, CompositingSpec.MODE_INTERSECT);
        check("trigger 1: intersect → v13", inter.needsSchema13());

        CompositingSpec diverged = new CompositingSpec();
        add(diverged, 0.1f, CompositingSpec.MODE_ADD);
        add(diverged, 0.2f, CompositingSpec.MODE_ADD);
        diverged.removeShape(0);
        check("trigger 2: a diverged slot → v13, with no intersect anywhere",
                diverged.needsSchema13() && !diverged.usesIntersect());

        check("an empty spec never asks for v13", !new CompositingSpec().needsSchema13());
    }

    // ── Undo plumbing ───────────────────────────────────────────────────────

    static void copyFromMutatesInPlace() {
        CompositingSpec live = new CompositingSpec();
        add(live, 0.1f, CompositingSpec.MODE_ADD);
        CompositingSpec snapshot = new CompositingSpec();
        add(snapshot, 0.9f, CompositingSpec.MODE_SUBTRACT);
        add(snapshot, 0.8f, CompositingSpec.MODE_ADD);

        java.util.List<CompositingSpec.MaskShape> identity = live.masks;
        live.copyFrom(snapshot);
        check("copyFrom keeps the SAME list object — Clip holds a final reference to it",
                live.masks == identity);
        check("copyFrom takes the other spec's shapes", live.masks.size() == 2);
        check("copyFrom takes their modes",
                live.masks.get(0).mode == CompositingSpec.MODE_SUBTRACT);

        snapshot.masks.get(0).cx = 0.05f;
        check("and it copied rather than aliased — editing the source cannot reach back",
                live.masks.get(0).cx != 0.05f);
    }

    static void presetsAreAStartingShapeNotAReset() {
        CompositingSpec s = new CompositingSpec();
        CompositingSpec.MaskShape m = add(s, 0.77f, CompositingSpec.MODE_INTERSECT);
        m.rotationDeg = 30f;
        int slot = m.slot;

        CompositingSpec.applyPreset(m, CompositingSpec.PRESET_CIRCLE);
        check("a circle preset equalises w and h", m.w == m.h);
        check("...and fully rounds the corner", m.corner == 1f);
        check("a preset leaves the CENTRE alone", m.cx == 0.77f);
        check("a preset leaves ROTATION alone", m.rotationDeg == 30f);
        check("a preset leaves MODE alone", m.isIntersect());
        check("a preset leaves the SLOT alone", m.slot == slot);

        CompositingSpec.applyPreset(m, CompositingSpec.PRESET_RECT);
        check("a rect preset squares the corner off", m.corner == 0f);
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
