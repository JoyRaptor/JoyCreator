import com.fadcam.ui.faditor.move.ObjectTimeMover;
import com.fadcam.ui.faditor.move.ObjectTimeMover.Span;
import com.fadcam.ui.faditor.move.ObjectTimeMover.Lane;
import com.fadcam.ui.faditor.move.ObjectTimeMover.Result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Proves ObjectTimeMover's collision + push-through relayering rules
 * (tasks/SPEC_OBJECT_TIME_SCRUBBER.md §2), each with a POSITIVE CONTROL so a
 * "no difference" result can't pass as a blind instrument.
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out4 \
 *     app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeMover.java \
 *     tools/jvm-harness/ObjectTimeMoverTest.java
 *   java -cp tools/jvm-harness/out4 ObjectTimeMoverTest
 */
public class ObjectTimeMoverTest {

    static int pass = 0, fail = 0;

    static void check(String what, long wantStart, Lane wantLane, Result got) {
        boolean ok = got.startMs == wantStart && got.lane == wantLane;
        if (ok) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else {
            fail++;
            System.out.println("FAIL  " + what + "\n      want=" + wantLane + "@" + wantStart
                    + " got=" + got);
        }
    }

    static List<Span> spans(Span... s) { return new ArrayList<>(Arrays.asList(s)); }
    static final List<Span> NONE = new ArrayList<>();

    public static void main(String[] args) {
        final long DUR = 100;

        // ── 1. Free origin: a plain move with nothing in the way stays home. ──
        check("free move -> origin@desired",
                250, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 250, false, 150, NONE, null));

        // ── 2. Lock RIGHT (push-through OFF): ram an obstacle, stop flush left of it. ──
        // object [0,100) sliding right toward [250,350); obstacle [200,300).
        check("lock right -> flush at 100",
                100, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 250, false, 150, spans(new Span(200, 300)), null));
        // POSITIVE CONTROL: identical call minus the obstacle must reach the desired 250.
        check("control: no obstacle -> reaches 250 (proves the lock did something)",
                250, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 250, false, 150, NONE, null));

        // ── 3. Lock LEFT (push-through OFF): slide left, stop flush right of the obstacle. ──
        // object [400,500) sliding left toward [120,220); obstacle [100,200).
        check("lock left -> flush at 200",
                200, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 400, 120, false, 150, spans(new Span(100, 200)), null));
        check("control: no obstacle left -> reaches 120",
                120, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 400, 120, false, 150, NONE, null));

        // ── 4. Breakthrough threshold. Obstacle [200,300); object [0,100) locks at 100. ──
        // Overshoot = |desired-100|, and it stays bounded because once the object clears the
        // obstacle (desired>=300 -> [300,400)) origin is free again — so the threshold must be
        // reachable WITHIN the ram (desired in (100,300)). desired 250 -> overshoot 150.
        List<Span> obR = spans(new Span(200, 300));
        check("push-through ON, overshoot<breakthrough -> still locked",
                100, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 250, true, 200, obR, null));
        // POSITIVE CONTROL: same push (overshoot 150) with a smaller threshold (100) -> breaks
        // through (NEW, no lane above). Only the threshold changed -> proves it gates the relayer.
        check("control: overshoot>=breakthrough -> breaks through (NEW, no lane above)",
                250, Lane.NEW,
                ObjectTimeMover.resolve(DUR, 0, 250, true, 100, obR, null));

        // ── 5. Breakthrough with a lane ABOVE that is OPEN across the span -> ABOVE. ──
        List<Span> aboveOpen = spans(new Span(0, 50));           // clear of [250,350)
        check("breakthrough + above open -> ABOVE",
                250, Lane.ABOVE,
                ObjectTimeMover.resolve(DUR, 0, 250, true, 100, obR, aboveOpen));
        // POSITIVE CONTROL: the SAME breakthrough with the lane above BLOCKED -> NEW (no chain).
        List<Span> aboveBlocked = spans(new Span(240, 260));     // overlaps [250,350)
        check("control: breakthrough + above blocked -> NEW (never climbs a 2nd lane)",
                250, Lane.NEW,
                ObjectTimeMover.resolve(DUR, 0, 250, true, 100, obR, aboveBlocked));

        // ── 6. No lane above at all (topmost) -> NEW on breakthrough. ──
        check("breakthrough + no lane above -> NEW",
                250, Lane.NEW,
                ObjectTimeMover.resolve(DUR, 0, 250, true, 100, obR, null));

        // ── 7. Return-to-origin: desired well PAST the obstacle, origin free there -> ORIGIN. ──
        // Even with push-through ON, if the desired span clears the origin obstacle it comes home.
        check("past the obstacle -> returns to ORIGIN",
                350, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 350, true, 100, obR, aboveBlocked));

        // ── 8. Toggle OFF never relayers, even ramming a WIDE obstacle with a big push. ──
        // Wide obstacle [200,10000): object [0,100) locks flush at 100; desired 5000 stays
        // overlapping (so it can't just pass) -> overshoot 4900.
        List<Span> wide = spans(new Span(200, 10000));
        check("toggle OFF, big overshoot on a wide obstacle -> still locked ORIGIN",
                100, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 5000, false, 100, wide, aboveOpen));
        // POSITIVE CONTROL: toggle ON, same push -> relayers (proves the toggle gates it).
        check("control: toggle ON, same push -> relayers (ABOVE)",
                5000, Lane.ABOVE,
                ObjectTimeMover.resolve(DUR, 0, 5000, true, 100, wide, aboveOpen));

        // ── 9. Half-open flush is not an overlap: object may sit exactly edge-to-edge. ──
        // desired 100 puts object [100,200); obstacle [200,300) -> touching, not overlapping.
        check("edge-to-edge is free (half-open) -> ORIGIN@100",
                100, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 0, 100, false, 150, obR, null));

        // ── 10. Desired clamped to >= 0. ──
        check("negative desired clamped to 0",
                0, Lane.ORIGIN,
                ObjectTimeMover.resolve(DUR, 50, -500, false, 150, NONE, null));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail > 0 ? 1 : 0);
    }
}
