import com.fadcam.ui.faditor.export.PipAudioLanes;

/**
 * STU-F6 on the Note 9 (11-28): two PiPs that both start at 0 s exported one after the other (B 0-8 s, then A 8-16 s)
 * instead of together, and the sound ran 16 s against a 6 s video. PiPs that overlap must be laid on separate lanes
 * (mixed, as the sound lanes are), and no PiP sound may run past the end of the video.
 */
public class PipAudioLanesTest {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String name) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    public static void main(String[] args) {
        int[] two = PipAudioLanes.assign(new long[]{0, 0}, new long[]{8_000, 8_000});
        check(two[0] != two[1], "two PiPs that both start at 0 s go on different lanes, so they play together");
        int[] three = PipAudioLanes.assign(new long[]{0, 2_000, 9_000}, new long[]{8_000, 6_000, 12_000});
        check(three[0] != three[1], "two that overlap are apart");
        check(three[2] == three[0] || three[2] == three[1], "a third after both reuses a lane (no lane per PiP for nothing)");
        int[] apart = PipAudioLanes.assign(new long[]{0, 8_000}, new long[]{8_000, 16_000});
        check(apart[0] == apart[1], "one after the other share a lane, as before");
        check(PipAudioLanes.fits(0, 8_000, 6_000) == 6_000, "a PiP sound longer than the 6 s video is cut at 6 s");
        check(PipAudioLanes.fits(7_000, 2_000, 6_000) == 0, "a piece starting after the video's end is dropped");
        check(PipAudioLanes.fits(1_000, 2_000, 6_000) == 2_000, "a piece inside the video is whole");
        System.out.println("PipAudioLanes: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
