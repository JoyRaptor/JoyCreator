import com.fadcam.ui.faditor.move.ObjectTimeMover.Lane;
import com.fadcam.ui.faditor.move.ObjectTimeMover.Span;
import com.fadcam.ui.faditor.move.ObjectTimeScrubSession;
import com.fadcam.ui.faditor.move.ObjectTimeScrubSession.Host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Proves ObjectTimeScrubSession's tick/commit bookkeeping (SPEC_OBJECT_TIME_SCRUBBER §2/§3)
 * with a fake host, each assertion paired with a positive control.
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out4 \
 *     app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeMover.java \
 *     app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeScrubSession.java \
 *     tools/jvm-harness/ObjectTimeScrubSessionTest.java
 *   java -cp tools/jvm-harness/out4 ObjectTimeScrubSessionTest
 */
public class ObjectTimeScrubSessionTest {

    static int pass = 0, fail = 0;

    static void eq(String what, String want, String got) {
        if (want.equals(got)) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else { fail++; System.out.println("FAIL  " + what + "\n      want=" + want + " got=" + got); }
    }

    static final class FakeHost implements Host {
        long dur = 100, start = 0, breakthrough = 100, snap = 0, maxStart = Long.MAX_VALUE;
        boolean push = true;
        List<Span> origin = new ArrayList<>(), above = null;
        // recording
        String lastCommit = "none";
        int commits = 0;
        String lastPreview = "none";
        List<String> previews = new ArrayList<>();

        public long durationMs() { return dur; }
        public long startMs() { return start; }
        public List<Span> originLaneSpans() { return origin; }
        public List<Span> aboveLaneSpans() { return above; }
        public boolean pushThrough() { return push; }
        public long breakthroughMs() { return breakthrough; }
        public long snapStepMs() { return snap; }
        public long maxStartMs() { return maxStart; }
        public void onPreview(long s, Lane l, boolean changed) { lastPreview = l + "@" + s; previews.add(lastPreview); }
        public void onCommit(long s, Lane l) { commits++; lastCommit = l + "@" + s; }
    }

    static List<Span> spans(Span... s) { return new ArrayList<>(Arrays.asList(s)); }

    /** True when [start, start+dur) overlaps none of the host's origin-lane neighbours. */
    static boolean freeOn(FakeHost h, long start) {
        for (Span o : h.origin) if (start < o.endMs && o.startMs < start + h.dur) return false;
        return true;
    }

    /** The first preview position past the last neighbour (where the shuttle came to rest). */
    static String firstStopPast(FakeHost h) {
        long lastEnd = 0;
        for (Span o : h.origin) lastEnd = Math.max(lastEnd, o.endMs);
        for (String p : h.previews) {
            long at = Long.parseLong(p.substring(p.indexOf('@') + 1));
            if (at >= lastEnd) return p;
        }
        return "never";
    }

    public static void main(String[] args) {
        // ── 1. Plain move, nothing in the way. ──
        {
            FakeHost h = new FakeHost();
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(500); s.tick(200); s.end();
            eq("free move commits ORIGIN@700", "ORIGIN@700", h.lastCommit);
        }

        // ── 2. Lock (push OFF) vs breakthrough (push ON) — same ticks, one flag differs. ──
        // obstacle [200,300), dur 100, above open [0,50), breakthrough 100.
        {
            FakeHost h = new FakeHost();
            h.origin = spans(new Span(200, 300));
            h.above = spans(new Span(0, 50));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(150); s.tick(100); s.end();   // desired 150 then 250
            eq("push OFF: locks flush, commits ORIGIN@100", "ORIGIN@100", h.lastCommit);
        }
        {
            FakeHost h = new FakeHost();
            h.origin = spans(new Span(200, 300));
            h.above = spans(new Span(0, 50));
            h.push = true;                                   // POSITIVE CONTROL: only the flag flips
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(150); s.tick(100); s.end();    // desired 150 -> 250 (mid-obstacle)
            eq("push ON: breaks through, commits ABOVE@250", "ABOVE@250", h.lastCommit);
        }

        // ── 3. Return-to-origin: keep scrubbing PAST the obstacle -> lands back on ORIGIN. ──
        {
            FakeHost h = new FakeHost();
            h.origin = spans(new Span(200, 300));
            h.above = spans(new Span(0, 50));
            h.push = true;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(150); s.tick(100); s.tick(100); s.end(); // desired 150->250->350
            eq("push ON past the obstacle -> ORIGIN@350", "ORIGIN@350", h.lastCommit);
        }

        // ── 4. Scrub back after relayering: cross the obstacle both ways, end past it. ──
        {
            FakeHost h = new FakeHost();
            h.origin = spans(new Span(200, 300));
            h.above = spans(new Span(0, 50));
            h.push = true;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            // right into the obstacle (ABOVE), back left to origin, then right past it.
            s.begin();
            s.tick(250);   // desired 250 -> ABOVE
            s.tick(-250);  // desired 0 -> origin free -> ORIGIN@0
            s.tick(360);   // desired 360 -> past obstacle -> ORIGIN@360
            s.end();
            eq("scrub both ways ends ORIGIN@360 (anchor stayed valid)", "ORIGIN@360", h.lastCommit);
        }

        // ── 5. No-op: begin then end with no movement -> NO commit. ──
        {
            FakeHost h = new FakeHost();
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.end();
            eq("no movement -> no commit", "0 commits", h.commits + " commits");
        }
        // POSITIVE CONTROL: a real move DOES commit.
        {
            FakeHost h = new FakeHost();
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(120); s.end();
            eq("control: a move commits once", "1 commits", h.commits + " commits");
        }

        // ── 6. Increment snap. ──
        {
            FakeHost h = new FakeHost();
            h.snap = 100;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(437); s.end();       // desired 437 snaps to 400
            eq("snap 100: 437 -> 400", "ORIGIN@400", h.lastCommit);
        }
        // POSITIVE CONTROL: no snap keeps the raw value.
        {
            FakeHost h = new FakeHost();
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(437); s.end();
            eq("control: no snap -> 437", "ORIGIN@437", h.lastCommit);
        }

        // ── 7. Jump-to-time obeys the same collision behaviour. ──
        {
            FakeHost h = new FakeHost();
            h.origin = spans(new Span(200, 300));
            h.above = null;                        // no lane above
            h.push = true;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(250); s.end();     // lands in the obstacle -> NEW lane
            eq("jump into obstacle (no above) -> NEW@250", "NEW@250", h.lastCommit);
        }

        // ── 8. Anti-overshoot: a lock-only clear STEPS flush-past the obstacle, not to far desired. ──
        {
            FakeHost h = new FakeHost();
            h.origin = spans(new Span(200, 300));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin();
            s.tick(150);   // desired 150 -> locks flush at 100
            s.tick(60);    // desired 210 -> still locked at 100
            s.tick(500);   // desired 710 -> would JUMP free; instead steps to flush-past 300
            s.end();
            eq("push OFF clear steps to flush-past (300), not 710", "ORIGIN@300", h.lastCommit);
        }
        // POSITIVE CONTROL: no obstacle -> the same big push reaches the desired (step-past inert).
        {
            FakeHost h = new FakeHost();
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(150); s.tick(60); s.tick(500); s.end();  // desired 710
            eq("control: no obstacle -> reaches 710", "ORIGIN@710", h.lastCommit);
        }

        // ── 9. R2-TIME-01 (Codex audit round 2): the lock-only step-past must land in a gap the
        //       whole object fits in, not flush against the first obstacle when the next one is
        //       closer than the object is long. Codex's counterexample: dur 1000 at 0, neighbours
        //       [2000,3000) and [3500,5000), jump to 6000. Before the fix: ORIGIN@3000, which
        //       overlaps the second neighbour by 500 ms.
        {
            FakeHost h = new FakeHost();
            h.dur = 1000;
            h.origin = spans(new Span(2000, 3000), new Span(3500, 5000));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(6000); s.end();
            eq("R2-TIME-01: steps past both into the first gap that fits (5000)", "ORIGIN@5000", h.lastCommit);
            eq("R2-TIME-01: committed span overlaps nothing", "free", freeOn(h, s.currentStart()) ? "free" : "overlap");
        }
        // Touching neighbours count as one wall.
        {
            FakeHost h = new FakeHost();
            h.dur = 1000;
            h.origin = spans(new Span(2000, 3000), new Span(3000, 4000));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(6000); s.end();
            eq("R2-TIME-01: touching neighbours -> past both (4000)", "ORIGIN@4000", h.lastCommit);
        }
        // POSITIVE CONTROL: a gap exactly as long as the object still takes it (stops after one).
        {
            FakeHost h = new FakeHost();
            h.dur = 1000;
            h.origin = spans(new Span(2000, 3000), new Span(4000, 5000));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(6000); s.end();
            eq("control: exact-fit gap is used (3000)", "ORIGIN@3000", h.lastCommit);
        }
        // Several tight gaps, crossed by shuttle ticks instead of a jump; every preview stays legal.
        {
            FakeHost h = new FakeHost();
            h.dur = 1000;
            h.origin = spans(new Span(1500, 2000), new Span(2600, 3000), new Span(3400, 4000));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin();
            boolean allFree = true;
            for (int i = 0; i < 40; i++) { s.tick(250); allFree &= freeOn(h, s.currentStart()); }
            s.end();
            eq("R2-TIME-01: shuttle ticks never preview an overlap", "true", String.valueOf(allFree));
            eq("R2-TIME-01: shuttle steps over the tight cluster to 4000", "ORIGIN@4000", firstStopPast(h));
        }
        // Backward: there is no step-past going left (by design); the resolved start is still legal.
        {
            FakeHost h = new FakeHost();
            h.dur = 1000; h.start = 6000;
            h.origin = spans(new Span(2000, 3000), new Span(3500, 5000));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(3200); s.end();          // desired overlaps both -> locks at 5000
            eq("backward into a cluster locks flush on the near side (5000)", "ORIGIN@5000", h.lastCommit);
        }

        // ── 10. R2-TIME-02: snapping must not round the start past the host's maximum start. ──
        {
            FakeHost h = new FakeHost();
            h.snap = 100; h.maxStart = 950;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(950); s.end();           // before the fix: round(9.5)*100 = 1000
            eq("R2-TIME-02: snap at a non-aligned end cap stays at the cap (950)", "ORIGIN@950", h.lastCommit);
        }
        {
            FakeHost h = new FakeHost();
            h.snap = 100; h.maxStart = 950;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.tick(5000); s.end();            // shuttled far past the cap
            eq("R2-TIME-02: shuttle past the cap stops at the cap (950)", "ORIGIN@950", h.lastCommit);
        }
        // POSITIVE CONTROLS: a half-step tie inside the cap still rounds up; zero stays zero.
        {
            FakeHost h = new FakeHost();
            h.snap = 100; h.maxStart = 950; h.start = 400;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(850); s.end();
            eq("control: half-step tie under the cap rounds to 900", "ORIGIN@900", h.lastCommit);
        }
        {
            FakeHost h = new FakeHost();
            h.snap = 100; h.maxStart = 950; h.start = 400;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(-300); s.end();
            eq("control: start clamps to 0", "ORIGIN@0", h.lastCommit);
        }
        // A neighbour right at the end cap: the object locks flush against it, never past the cap.
        {
            FakeHost h = new FakeHost();
            h.snap = 100; h.maxStart = 950; h.dur = 100;
            h.origin = spans(new Span(900, 1050));
            h.push = false;
            ObjectTimeScrubSession s = new ObjectTimeScrubSession(h);
            s.begin(); s.jumpTo(950); s.end();
            eq("R2-TIME-02: neighbour at the cap -> locks flush at 800", "ORIGIN@800", h.lastCommit);
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail > 0 ? 1 : 0);
    }
}
