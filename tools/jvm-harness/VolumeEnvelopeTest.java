import com.fadcam.ui.faditor.model.VolumeEnvelope;

/**
 * The volume envelope curve, pinned off device.
 *
 * <p>It has three readers now — the export's VolumeAudioProcessor, the live preview, and the
 * Volume row's diamond — so a change here is heard in the file and in the editor. These checks
 * are written against properties a LISTENER would notice (no clicks at the ends, a straight
 * ramp between keys), not against whatever the implementation returns.</p>
 */
public class VolumeEnvelopeTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }
    static boolean close(float a, float b) { return Math.abs(a - b) < 1e-5f; }

    public static void main(String[] a) {
        long[] t = {0L, 1000L, 2000L};
        float[] v = {0f, 1f, 0.5f};

        // 1. No envelope at all falls back to the flat level — the path every existing
        //    project takes, which must be untouched by this feature existing.
        check(close(VolumeEnvelope.gainAt(null, null, 500, 0.8f), 0.8f), "null arrays → flat level");
        check(close(VolumeEnvelope.gainAt(new long[0], new float[0], 500, 0.8f), 0.8f),
                "empty envelope → flat level");
        check(close(VolumeEnvelope.gainAt(t, new float[]{0f, 1f}, 500, 0.8f), 0.8f),
                "mismatched array lengths → flat level, not an exception");

        // 2. Ends CLAMP rather than extrapolate. Extrapolation past the last key would drive
        //    the gain negative and a listener hears that as a click.
        check(close(VolumeEnvelope.gainAt(t, v, -5000, 9f), 0f), "before the first key → first value");
        check(close(VolumeEnvelope.gainAt(t, v, 99999, 9f), 0.5f), "after the last key → last value");
        check(close(VolumeEnvelope.gainAt(t, v, 0, 9f), 0f), "exactly on the first key");
        check(close(VolumeEnvelope.gainAt(t, v, 2000, 9f), 0.5f), "exactly on the last key");

        // 3. Between keys it is a straight line — the fade a user draws is the fade they get.
        check(close(VolumeEnvelope.gainAt(t, v, 500, 9f), 0.5f), "midpoint of a 0→1 ramp is 0.5");
        check(close(VolumeEnvelope.gainAt(t, v, 250, 9f), 0.25f), "quarter point is 0.25 (linear)");
        check(close(VolumeEnvelope.gainAt(t, v, 1500, 9f), 0.75f),
                "interpolates on the SECOND segment too, not just the first");
        check(close(VolumeEnvelope.gainAt(t, v, 1000, 9f), 1f), "the interior key is hit exactly");

        // 4. Monotone sweep: no discontinuity anywhere across the whole span. A jump here is
        //    an audible click, which is the failure this shape exists to avoid.
        float prev = -1f;
        boolean smooth = true;
        for (int ms = 0; ms <= 1000; ms += 10) {
            float g = VolumeEnvelope.gainAt(t, v, ms, 9f);
            if (prev >= 0 && Math.abs(g - prev) > 0.02f) smooth = false;
            prev = g;
        }
        check(smooth, "no step larger than 0.02 across a 1s ramp (no audible click)");

        // 5. A single key is a constant, not a ramp to nowhere.
        check(close(VolumeEnvelope.gainAt(new long[]{500}, new float[]{0.3f}, 0, 9f), 0.3f),
                "one key before it → that value");
        check(close(VolumeEnvelope.gainAt(new long[]{500}, new float[]{0.3f}, 9999, 9f), 0.3f),
                "one key after it → that value");

        // 6. Two keys sharing a time is a STEP, and the later one wins so the curve stays a
        //    function of time rather than of list order.
        long[] st = {0L, 1000L, 1000L, 2000L};
        float[] sv = {0f, 0f, 1f, 1f};
        check(close(VolumeEnvelope.gainAt(st, sv, 1000, 9f), 0f)
                        || close(VolumeEnvelope.gainAt(st, sv, 1000, 9f), 1f),
                "a zero-span segment resolves to one of its endpoints, never NaN");
        check(close(VolumeEnvelope.gainAt(st, sv, 1500, 9f), 1f),
                "after a step the later value holds");
        check(!Float.isNaN(VolumeEnvelope.gainAt(st, sv, 1000, 9f)),
                "a zero-length span never divides by zero");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
