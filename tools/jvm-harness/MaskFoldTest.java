import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskFold;

/**
 * JVM harness for MaskFold — the boolean-op decision behind multi-shape masks, and the
 * feather-cache signature.
 *
 * <p><b>THE GATE is the whole point of this file.</b> MaskFold claims that a stack with no
 * INTERSECT shape provably takes the shipped two-bucket path, so no project that exists today
 * can change behaviour. That is a falsifiable claim about every add/subtract stack, not a
 * comment, so it is tested directly: {@code sequential} must be false for every arrangement of
 * adds and subtracts, and true the instant one intersect appears.</p>
 *
 * <p>The second group covers <b>the seed</b>. In the ordered fold, shape 0 folds into an EMPTY
 * accumulator, so a leading INTERSECT would erase the entire mask and a leading SUBTRACT would
 * do nothing — MaskFold forces shape 0 to UNION. In the two-bucket path it must NOT, because
 * there a leading subtract genuinely means "put me in the sub bucket". Two opposite rules that
 * look like one, which is exactly the kind of thing that rots silently.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-mask.sh}</p>
 */
public class MaskFoldTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        emptyAndNull();
        theGateHoldsForEveryAddSubtractStack();
        oneIntersectFlipsTheGate();
        bucketsAreLabelledButUnordered();
        orderedFoldSeedsWithUnion();
        signatureSeparatesModeAndSlot();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** A spec whose shapes carry the given modes, in order. */
    static CompositingSpec spec(int... modes) {
        CompositingSpec s = new CompositingSpec();
        for (int mode : modes) {
            CompositingSpec.MaskShape m = s.addShape();
            m.mode = mode;
        }
        return s;
    }

    static final int ADD = CompositingSpec.MODE_ADD;
    static final int SUB = CompositingSpec.MODE_SUBTRACT;
    static final int INT = CompositingSpec.MODE_INTERSECT;

    // ── The gate ────────────────────────────────────────────────────────────

    static void emptyAndNull() {
        check("a null spec folds to nothing", MaskFold.foldOps(null).ops.length == 0);
        check("a null spec is never sequential", !MaskFold.foldOps(null).sequential);
        check("an empty mask list folds to nothing", MaskFold.foldOps(spec()).ops.length == 0);
    }

    static void theGateHoldsForEveryAddSubtractStack() {
        // Exhaustive over every add/subtract arrangement up to 3 shapes: 2 + 4 + 8 = 14 stacks.
        // "No existing project changes path" is a claim about ALL of them, so spot-checking one
        // would not settle it.
        int stacks = 0;
        boolean allTwoBucket = true;
        for (int n = 1; n <= 3; n++) {
            for (int bits = 0; bits < (1 << n); bits++) {
                int[] modes = new int[n];
                for (int i = 0; i < n; i++) modes[i] = ((bits >> i) & 1) == 1 ? SUB : ADD;
                if (MaskFold.foldOps(spec(modes)).sequential) allTwoBucket = false;
                stacks++;
            }
        }
        check("all " + stacks + " add/subtract stacks stay on the shipped two-bucket path",
                allTwoBucket && stacks == 14);
    }

    static void oneIntersectFlipsTheGate() {
        check("an intersect anywhere turns the ordered fold on",
                MaskFold.foldOps(spec(ADD, INT)).sequential);
        check("...including when it is the only shape",
                MaskFold.foldOps(spec(INT)).sequential);
        check("...and when it is buried under later adds",
                MaskFold.foldOps(spec(ADD, INT, ADD, SUB)).sequential);
    }

    // ── Ops ─────────────────────────────────────────────────────────────────

    static void bucketsAreLabelledButUnordered() {
        MaskFold.Fold f = MaskFold.foldOps(spec(SUB, ADD, SUB));
        check("a leading SUBTRACT keeps its bucket in the two-bucket path",
                f.ops[0] == MaskFold.OP_DIFFERENCE);
        check("the adds are unioned", f.ops[1] == MaskFold.OP_UNION);
        check("the trailing subtract is differenced", f.ops[2] == MaskFold.OP_DIFFERENCE);
        check("one op is emitted per shape", f.ops.length == 3);
    }

    static void orderedFoldSeedsWithUnion() {
        // A leading INTERSECT against an empty accumulator would erase everything.
        MaskFold.Fold lead = MaskFold.foldOps(spec(INT, ADD));
        check("a leading INTERSECT is seeded as UNION, so it cannot erase the stack",
                lead.ops[0] == MaskFold.OP_UNION);
        check("the shape after it keeps its own op", lead.ops[1] == MaskFold.OP_UNION);

        // A leading SUBTRACT against an empty accumulator would be a silent no-op.
        MaskFold.Fold sub = MaskFold.foldOps(spec(SUB, INT));
        check("a leading SUBTRACT is seeded as UNION once the fold is ordered",
                sub.ops[0] == MaskFold.OP_UNION);
        check("the intersect that follows still intersects",
                sub.ops[1] == MaskFold.OP_INTERSECT);

        // The seed applies ONLY to the ordered fold — the previous test pins the other half.
        check("...but the same leading SUBTRACT keeps its bucket when nothing intersects",
                MaskFold.foldOps(spec(SUB, ADD)).ops[0] == MaskFold.OP_DIFFERENCE);
    }

    // ── Signature ───────────────────────────────────────────────────────────

    static void signatureSeparatesModeAndSlot() {
        // Same geometry, different mode: the erase bitmap differs, so the cache key must too.
        check("mode is part of the feather-cache key",
                !MaskFold.signature(spec(ADD)).equals(MaskFold.signature(spec(INT))));

        // Same geometry AND mode, different slot. The geometry was resolved from that slot's
        // keyframe tracks, so two shapes with equal numbers in different slots are not
        // interchangeable and must not share a cached bitmap.
        CompositingSpec a = spec(ADD, ADD);
        CompositingSpec b = spec(ADD, ADD);
        b.masks.get(1).slot = 7;
        check("slot is part of the feather-cache key",
                !MaskFold.signature(a).equals(MaskFold.signature(b)));

        check("an unchanged spec signs identically twice",
                MaskFold.signature(a).equals(MaskFold.signature(spec(ADD, ADD))));

        CompositingSpec moved = spec(ADD);
        moved.masks.get(0).cx = 0.9f;
        check("moving a shape changes the key",
                !MaskFold.signature(moved).equals(MaskFold.signature(spec(ADD))));

        CompositingSpec feathered = spec(ADD);
        feathered.maskFeather = 0.4f;
        check("feather changes the key",
                !MaskFold.signature(feathered).equals(MaskFold.signature(spec(ADD))));
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
