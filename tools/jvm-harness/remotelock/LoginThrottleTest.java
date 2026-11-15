import com.fadcam.streaming.LoginThrottle;

/** Remote Studio lock (B3, 11-15): guessing the pairing code is slowed per address. */
public class LoginThrottleTest {
    static int fails = 0, passes = 0;
    static void check(boolean ok, String what) {
        if (ok) { passes++; } else { fails++; System.out.println("FAIL: " + what); }
    }
    public static void main(String[] a) {
        LoginThrottle t = new LoginThrottle();
        long now = 1_000_000L;
        for (int i = 0; i < 4; i++) t.failed("10.0.0.5", now);
        check(t.waitMs("10.0.0.5", now) == 0, "four misses: may still try");
        t.failed("10.0.0.5", now);
        check(t.waitMs("10.0.0.5", now) == 30_000L, "fifth miss: 30 s wait");
        check(t.waitMs("10.0.0.6", now) == 0, "another address is not held up");
        check(t.waitMs("10.0.0.5", now + 30_000L) == 0, "after the wait it may try");
        t.failed("10.0.0.5", now + 30_000L);
        check(t.waitMs("10.0.0.5", now + 30_000L) == 60_000L, "sixth miss: wait doubles");
        for (int i = 0; i < 20; i++) t.failed("10.0.0.5", now);
        check(t.waitMs("10.0.0.5", now) == 15 * 60_000L, "wait stops at 15 minutes");
        t.succeeded("10.0.0.5");
        check(t.waitMs("10.0.0.5", now) == 0, "the right code clears the address");
        t.failed(null, now);
        check(t.waitMs(null, now) == 0, "an unknown address is handled");
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " LoginThrottleTest " + passes + "/" + (passes + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
