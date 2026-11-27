import com.fadcam.ui.faditor.motion.MotionShutter.Controls;
import com.fadcam.ui.faditor.motion.MotionShutter.Timing;
import com.fadcam.ui.faditor.motion.ShutterKeys;

/** STU-7 §K: keyframes on the Motion Blur card's Shutter and Mix. Run by run-motioncard.sh. */
public final class ShutterKeysTest {

    private static int checks;

    public static void main(String[] args) {
        Controls base = Controls.NATURAL;            // 180 centred, mix 100
        eq(base, ShutterKeys.EMPTY.at(5000, base), "no keys leaves the fixed setting");

        Controls crisp = new Controls(90, Timing.CENTERED, 0, 100);
        Controls heavy = new Controls(270, Timing.TRAILING, 0, 100);
        ShutterKeys k = ShutterKeys.EMPTY.withShutterKey(1000, crisp).withShutterKey(3000, heavy);
        eq(crisp, k.at(0, base), "before the first key holds the first");
        eq(crisp, k.at(1000, base), "on a key is that key");
        eq(heavy, k.at(3000, base), "on the last key it is named again (behind)");
        eq(heavy, k.at(9000, base), "after the last key holds the last");
        Controls mid = k.at(2000, base);
        eq(180, mid.angle(), "halfway eases to halfway angle");
        eq(Timing.CUSTOM, mid.timing(), "between differently placed keys the opening is custom");
        near(-157.5, mid.phase(), 0.5, "opening halfway between -45 and -270");
        check(k.at(1500, base).angle() < 130, "ease-in: a quarter of the way is under a quarter of the change");
        eq(100, mid.mixPercent(), "mix is not keyed, so it stays the fixed value");

        ShutterKeys m = ShutterKeys.EMPTY.withMixKey(0, new Controls(180, Timing.CENTERED, 0, 0))
                .withMixKey(1000, new Controls(180, Timing.CENTERED, 0, 100));
        eq(50, m.at(500, base).mixPercent(), "mix eases on its own track");
        eq(base.angle(), m.at(500, base).angle(), "and leaves the shutter alone");

        ShutterKeys replaced = k.withShutterKey(1000, heavy);
        eq(2, replaced.shutterCount(), "keying an existing time replaces, not adds");
        eq(heavy, replaced.at(1000, base), "with the new value");
        eq(1, k.withoutShutterKey(1000).shutterCount(), "remove takes one key away");
        eq(k, k.withoutShutterKey(1234), "removing where there is none changes nothing");
        check(k.hasShutterKeyAt(3000) && !k.hasShutterKeyAt(3001), "key lookup is exact");

        eq(1000L, ShutterKeys.previous(k.shutterTimes(), 3000), "previous key");
        eq(3000L, ShutterKeys.next(k.shutterTimes(), 1000), "next key");
        eq(-1L, ShutterKeys.next(k.shutterTimes(), 3000), "no next after the last");

        ShutterKeys moved = k.shifted(500);
        eq(crisp, moved.at(1500, base), "keys follow the object when it moves");

        ShutterKeys back = ShutterKeys.of(new long[]{3000, 1000},
                new double[]{270, 90}, new double[]{-270, -45}, new long[0], new double[0]);
        eq(k, back, "rebuilt from saved arrays in any order equals the original");
        System.out.println("ShutterKeysTest: " + checks + " checks passed");
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!want.equals(got)) throw new AssertionError(what + ": expected " + want + " got " + got);
    }

    private static void near(double want, double got, double tol, String what) {
        checks++;
        if (Math.abs(want - got) > tol) throw new AssertionError(what + ": expected " + want + " got " + got);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
