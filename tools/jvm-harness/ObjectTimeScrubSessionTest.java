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
        long dur = 100, start = 0, breakthrough = 100, snap = 0;
        boolean push = true;
        List<Span> origin = new ArrayList<>(), above = null;
        // recording
        String lastCommit = "none";
        int commits = 0;
        String lastPreview = "none";

        public long durationMs() { return dur; }
        public long startMs() { return start; }
        public List<Span> originLaneSpans() { return origin; }
        public List<Span> aboveLaneSpans() { return above; }
        public boolean pushThrough() { return push; }
        public long breakthroughMs() { return breakthrough; }
        public long snapStepMs() { return snap; }
        public void onPreview(long s, Lane l, boolean changed) { lastPreview = l + "@" + s; }
        public void onCommit(long s, Lane l) { commits++; lastCommit = l + "@" + s; }
    }

    static List<Span> spans(Span... s) { return new ArrayList<>(Arrays.asList(s)); }

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

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail > 0 ? 1 : 0);
    }
}
