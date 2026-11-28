import com.fadcam.ui.faditor.compositor.GaplessEligibility;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * STU-F7 (Note 9, 11-28): a project of ONE clip, ping-ponged and extended to 22 s, previewed forward 0-17.9 s and then
 * forward again, never backward. The true reverse leg is the gapless engine's; it required two or more clips (its first
 * job, 07-02, was crossing cuts), so a lone looped clip always fell back to the legacy forward replay.
 */
public class GaplessEligibilityTest {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String name) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    public static void main(String[] args) {
        GaplessEligibility.ReversedReady baked = c -> true;
        GaplessEligibility.ReversedReady notYet = c -> false;

        Timeline one = new Timeline();
        Clip pong = clip(18_000);
        pong.setLoopMode(Clip.LOOP_MODE_PING_PONG);
        pong.setLoopAfterMs(4_000);
        one.addClip(pong);
        check(GaplessEligibility.eligible(one, baked), "one ping-pong clip with its reverse baked plays on the engine (backward leg)");
        check(!GaplessEligibility.eligible(one, notYet), "not yet baked: the forward fallback, until the bake lands");

        Timeline loop = new Timeline();
        Clip normal = clip(10_000);
        normal.setLoopMode(Clip.LOOP_MODE_NORMAL);
        normal.setLoopAfterMs(5_000);
        loop.addClip(normal);
        check(GaplessEligibility.eligible(loop, baked), "one looped clip plays its repeats on the engine too");

        Timeline plain = new Timeline();
        plain.addClip(clip(10_000));
        check(!GaplessEligibility.eligible(plain, baked), "one plain clip keeps today's path (nothing to gain)");

        Timeline two = new Timeline();
        two.addClip(clip(5_000));
        two.addClip(clip(5_000));
        check(GaplessEligibility.eligible(two, baked), "two plain clips: as before");

        try {
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/compositor/MasterPlaybackEngine.java")), "UTF-8");
            check(src.contains("GaplessEligibility.eligible("), "the engine decides by this rule");
            String act = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java")), "UTF-8");
            int ext = act.indexOf("private void extendLoop(long deltaMs)");
            int next = act.indexOf("// ── Move drawer", ext);
            check(ext > 0 && next > ext && act.substring(ext, next).contains("refreshTotalTimeDisplay()"),
                    "extending a loop updates the total time (it read 00:18 beside a 22 s clip)");
        } catch (java.io.IOException e) { check(false, "read sources: " + e); }

        System.out.println("GaplessEligibility: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
