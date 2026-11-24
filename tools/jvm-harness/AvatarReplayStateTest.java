import com.fadcam.ui.faditor.avatar.AvatarReplayState;

public final class AvatarReplayStateTest {
    public static void main(String[] args) {
        boolean shareState = args.length == 1 && "--share-state".equals(args[0]);
        boolean millisecondClock = args.length == 1 && "--millisecond-clock".equals(args[0]);
        AvatarReplayState sharp = new AvatarReplayState();
        sharp.observe("hand", 0, true, 0);
        require(sharp.fade("hand") == null, "first pose does not fade a missing cell");
        sharp.observe("hand", 1, true, 10_000);
        for (int ageMs = 0; ageMs < 130; ageMs++) {
            float legacy = 1f - ageMs / 130f;
            require(sharp.fade("hand").alphaAt(10_000 + 1000L * ageMs) == legacy,
                    "ordinary replay fade matches the millisecond clock");
        }
        sharp.observe("mouth", 3, false, 20_000);
        sharp.observe("mouth", 4, false, 30_000);
        require(sharp.fade("mouth") == null, "hard visemes stay sharp");

        AvatarReplayState exposure = shareState ? sharp : sharp.copy();
        require(exposure.fade("hand").oldCell() == 0, "fork preserves an in-flight fade");
        long tap = millisecondClock ? 40_000 : 40_750;
        exposure.observe("hand", 2, true, tap);
        require(exposure.fade("hand").startUs() == 40_750, "shutter preserves sub-millisecond swap time");
        require(exposure.fade("hand").oldCell() == 1, "fork starts from the checkpoint cell");
        require(exposure.fade("hand").alphaAt(40_749) == 0f, "a future fade has no weight");
        require(exposure.fade("hand").alphaAt(105_750) == 0.5f, "halfway weight");
        require(exposure.fade("hand").alphaAt(170_750) == 0f, "expiry boundary");

        // A positive phase can sample past the next output centre. The sharp replay still
        // advances from its original checkpoint when that centre is drawn.
        require(sharp.fade("hand").startUs() == 10_000, "future tap leaves sharp fade unchanged");
        sharp.observe("hand", 3, true, 33_333);
        require(sharp.fade("hand").oldCell() == 1, "future tap leaves sharp previous cell unchanged");
        sharp.clear();
        require(exposure.fade("hand").oldCell() == 1, "sharp rewind leaves the fork untouched");
        System.out.println("AvatarReplayStateTest PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
