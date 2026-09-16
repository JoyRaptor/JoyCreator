import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;

/**
 * The three ranged time edits a recorded bar needs: slide, stretch, retime.
 *
 * <p>SPEC_20260915_PUPPET_UI §01 promised all three as gestures on the tape and §7 recorded them
 * as deferred, because {@code MeshPoseTrack} offered {@code shiftAll} and nothing ranged. These
 * are the arithmetic underneath them, and the reason they are tested here rather than on a phone
 * is that the interesting half is the CLAMPING — what happens when a performance is dragged into
 * the key next to it — and that is precisely the case a human dragging a bar will hit by accident
 * and never think to check on purpose.
 *
 * <p>The invariant every test below exists to defend: <b>a time edit never destroys a key.</b>
 */
public final class PuppetRetimeTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        slideMovesOnlyTheRange();
        slideStopsAtTheNeighbour();
        slideRefusesWhenThereIsNoRoom();
        slideNeverLosesAKey();
        stretchGrowsAboutTheAnchor();
        stretchStopsAtTheNeighbour();
        squeezeHasAFloor();
        stretchNeedsAnInside();
        retimeClampsBetweenNeighbours();
        retimeKeepsTheOrder();
        everythingKeepsTheCount();

        System.out.println();
        System.out.println(failures == 0
                ? "PuppetRetimeTest: " + checks + " checks OK"
                : "PuppetRetimeTest: " + failures + " FAILURES of " + checks);
        if (failures != 0) System.exit(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** A track with one key per given time, each carrying its own time as its value. */
    private static MeshPoseTrack trackAt(long... times) {
        MeshPoseTrack t = new MeshPoseTrack(2);
        for (long ms : times) {
            t.put(ms, new float[]{ms / 1000f, -ms / 1000f}, null);
        }
        return t;
    }

    private static long[] times(MeshPoseTrack t) { return t.times(); }

    private static String show(long[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
        return sb.append(']').toString();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("  FAIL  " + what); }
    }

    private static void eq(String what, long got, long want) {
        check(what + " (got " + got + ", want " + want + ")", got == want);
    }

    private static void sameTimes(String what, MeshPoseTrack t, long... want) {
        long[] got = times(t);
        boolean ok = got.length == want.length;
        for (int i = 0; ok && i < got.length; i++) ok = got[i] == want[i];
        check(what + " (got " + show(got) + ", want " + show(want) + ")", ok);
    }

    // ── slide ────────────────────────────────────────────────────────────

    private static void slideMovesOnlyTheRange() {
        MeshPoseTrack t = trackAt(0, 1000, 1100, 1200, 5000);
        long moved = t.shiftRange(1000, 1200, 500);
        eq("a clear slide applies in full", moved, 500);
        sameTimes("only the keys inside the range moved", t, 0, 1500, 1600, 1700, 5000);
    }

    private static void slideStopsAtTheNeighbour() {
        // The block ends at 1200 and the next key outside it is at 1300: exactly 99ms of room,
        // because the stop leaves one millisecond of daylight rather than landing on top.
        MeshPoseTrack t = trackAt(0, 1000, 1100, 1200, 1300);
        long moved = t.shiftRange(1000, 1200, 9999);
        eq("a slide clamps at its neighbour", moved, 99);
        sameTimes("and nothing was destroyed doing it", t, 0, 1099, 1199, 1299, 1300);
    }

    private static void slideRefusesWhenThereIsNoRoom() {
        MeshPoseTrack t = trackAt(1000, 1100, 1200, 1201);
        long moved = t.shiftRange(1000, 1200, 400);
        eq("no room means no movement at all", moved, 0);
        sameTimes("and the track is untouched", t, 1000, 1100, 1200, 1201);
        // The important half: a clamp must never REVERSE the drag. Dragging right into a wall
        // has to do nothing, not jump left.
        check("a refused slide never inverts", times(t)[0] == 1000);
    }

    private static void slideNeverLosesAKey() {
        MeshPoseTrack t = trackAt(0, 100, 200, 300, 400, 500);
        int before = t.size();
        t.shiftRange(100, 300, -9999);
        eq("a slide backwards into a wall keeps every key", t.size(), before);
        t.shiftRange(100, 300, 9999);
        eq("and so does one forwards", t.size(), before);
    }

    // ── stretch ──────────────────────────────────────────────────────────

    private static void stretchGrowsAboutTheAnchor() {
        MeshPoseTrack t = trackAt(1000, 1500, 2000);
        float f = t.scaleRange(1000, 2000, 1000, 2f);
        check("a clear stretch applies in full (got " + f + ")", Math.abs(f - 2f) < 1e-3f);
        sameTimes("the anchor end did not move; the far end doubled away from it",
                t, 1000, 2000, 3000);
    }

    private static void stretchStopsAtTheNeighbour() {
        MeshPoseTrack t = trackAt(1000, 1500, 2000, 2500);
        float f = t.scaleRange(1000, 2000, 1000, 9f);
        check("a stretch clamps rather than running through the key after it",
                f > 1f && f <= 1.5f);
        long[] got = times(t);
        check("the stretched end stopped short of 2500 (got " + show(got) + ")",
                got[2] < 2500);
        eq("and the key it stopped against never moved", got[3], 2500);
    }

    private static void squeezeHasAFloor() {
        MeshPoseTrack t = trackAt(0, 500, 1000);
        t.scaleRange(0, 1000, 0, 0.0001f);
        long[] got = times(t);
        check("a squeeze cannot go below the floor (got " + show(got) + ")",
                got[2] - got[0] >= MeshPoseTrack.MIN_SPAN_MS - 1);
        eq("and it still has all three keys", t.size(), 3);
        check("with all three on distinct instants",
                got[0] < got[1] && got[1] < got[2]);
    }

    private static void stretchNeedsAnInside() {
        // Two keys are a start and an end with no middle. Scaling them is a slide of one key,
        // which is the retime gesture, not this one.
        MeshPoseTrack t = trackAt(1000, 2000);
        float f = t.scaleRange(1000, 2000, 1000, 2f);
        check("two keys are not a performance to stretch (got " + f + ")",
                Math.abs(f - 1f) < 1e-4f);
        sameTimes("so nothing moved", t, 1000, 2000);
    }

    // ── retime ───────────────────────────────────────────────────────────

    private static void retimeClampsBetweenNeighbours() {
        MeshPoseTrack t = trackAt(0, 500, 1000);
        eq("a key retimes freely inside its gap", t.moveKey(500, 800), 800);
        eq("and stops one short of the key after it", t.moveKey(800, 5000), 999);
        eq("and one after the key before it", t.moveKey(999, -5000), 1);
        eq("never losing one on the way", t.size(), 3);
    }

    private static void retimeKeepsTheOrder() {
        MeshPoseTrack t = trackAt(0, 500, 1000, 1500);
        t.moveKey(500, 1400);
        long[] got = times(t);
        boolean ascending = true;
        for (int i = 1; i < got.length; i++) ascending &= got[i] > got[i - 1];
        check("times stay strictly ascending after a retime " + show(got), ascending);
    }

    private static void retimeOfAMissingKeyDoesNothing() {
        MeshPoseTrack t = trackAt(0, 500);
        eq("retiming a key that is not there is a no-op", t.moveKey(123, 400), 123);
    }

    // ── the invariant, stated once more as a sweep ───────────────────────

    private static void everythingKeepsTheCount() {
        retimeOfAMissingKeyDoesNothing();
        long[] seed = {0, 120, 240, 360, 480, 2000, 4000};
        for (int i = 0; i < 40; i++) {
            MeshPoseTrack t = trackAt(seed);
            int n = t.size();
            t.shiftRange(120, 480, (i - 20) * 137L);
            t.scaleRange(120, 480, 120, 0.1f + i * 0.17f);
            t.moveKey(t.times()[2], t.times()[2] + (i - 20) * 53L);
            if (t.size() != n) {
                check("sweep " + i + " kept every key", false);
                return;
            }
            long[] got = t.times();
            for (int k = 1; k < got.length; k++) {
                if (got[k] <= got[k - 1]) {
                    check("sweep " + i + " kept times ascending " + show(got), false);
                    return;
                }
            }
        }
        check("forty mixed slide/stretch/retime rounds kept every key, in order", true);
    }
}
