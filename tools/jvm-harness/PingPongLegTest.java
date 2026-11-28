import com.fadcam.ui.faditor.model.PingPongLeg;

/**
 * STU-F7 (11-27, Note 9): a ping-pong clip's export repeated FORWARD after its own pass (frame 580 = frame 40). The leg
 * next to the clip's own pass must play backward, then the legs alternate, and each leg starts where the one before it
 * ended: after the clip, backward from its end; before the clip, ending at its start.
 */
public class PingPongLegTest {
    static int failed = 0, ran = 0;
    static void check(boolean ok, String what) {
        ran++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        long in = 0, out = 18_000;          // an 18 s clip
        long rev = out - in;                 // the reversed copy's length
        // After the clip: 22 s of extension = one whole leg (18 s) and 4 s.
        PingPongLeg a0 = PingPongLeg.of(false, 2, 0, in, out, 18_000, true);
        PingPongLeg a1 = PingPongLeg.of(false, 2, 1, in, out, 4_000, true);
        check(a0.reverse, "the leg right after the clip plays backward");
        check(a0.startMs == 0 && a0.endMs == rev, "a whole backward leg is the whole reversed copy (end of clip back to its start)");
        check(!a1.reverse, "the next leg plays forward again");
        check(a1.startMs == in && a1.endMs == in + 4_000, "a forward leg starts where the backward one ended (the clip's start)");
        // A short extension: only part of a backward leg, which must start at the clip's END (where its pass ended).
        PingPongLeg p = PingPongLeg.of(false, 1, 0, in, out, 4_000, true);
        check(p.reverse && p.startMs == 0 && p.endMs == 4_000, "a partial backward leg after the clip starts at its end (reversed copy from 0)");
        // Before the clip: the leg next to it plays backward and ENDS at the clip's start.
        PingPongLeg b1 = PingPongLeg.of(true, 2, 1, in, out, 4_000, true);
        PingPongLeg b0 = PingPongLeg.of(true, 2, 0, in, out, 18_000, true);
        check(b1.reverse && b1.endMs == rev && b1.startMs == rev - 4_000, "the leg right before the clip plays backward into its start");
        check(!b0.reverse && b0.endMs == out, "the leg before that plays forward, ending at the clip's end");
        // Without a reversed copy (too long to bake, or not ping-pong): every leg replays forward from the start.
        PingPongLeg f = PingPongLeg.of(false, 2, 0, in, out, 18_000, false);
        check(!f.reverse && f.startMs == in && f.endMs == out, "no reversed copy: a forward replay");
        System.out.println("PingPongLeg: " + (ran - failed) + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
