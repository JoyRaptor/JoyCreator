import cc.joycreator.avatar.solve.Signals;
import cc.joycreator.avatar.ui.SignalMap;

import java.util.HashMap;
import java.util.Map;

/** A take from the pilot stores the mouth shape as a number, the phone as a name: both must read. */
public class SignalMapTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] a) {
        Map<String, Object> m = new HashMap<>();
        m.put("vis", "Oh");
        ok("Oh".equals(SignalMap.toSignals(m, 0, 0).viseme), "a shape by name reads as itself");
        m.put("vis", 3.0);
        ok("Oh".equals(SignalMap.toSignals(m, 0, 0).viseme), "the pilot's shape number 3 is Oh");
        m.put("vis", 99.0);
        ok("Neutral".equals(SignalMap.toSignals(m, 0, 0).viseme), "a number out of range is neutral");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
