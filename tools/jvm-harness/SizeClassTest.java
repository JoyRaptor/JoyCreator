import com.fadcam.ui.size.SizeClass;

/**
 * TAB-1 window size classes and the two sums every wide-window layout uses.
 *
 * <p>The owner's rules under test: the phone layout stays exactly as drawn (a phone upright gets
 * no extra padding and the same two Library cards), and a wide window uses its room for MORE
 * content of the phone's size, never bigger controls. Sizes are the real devices: the Note 9
 * (about 412 x 780 dp upright), the test tablet (1480 x 924 dp sideways, 924 x 1480 upright).
 *
 * <p>Run: {@code bash tools/jvm-harness/run-sizeclass.sh}
 */
public class SizeClassTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // Classes: by window, not device.
        eq("Note 9 upright is compact", SizeClass.Width.COMPACT, SizeClass.width(548));
        eq("small phone sideways is medium wide", SizeClass.Width.MEDIUM, SizeClass.width(780));
        eq("Note 9 sideways is expanded wide", SizeClass.Width.EXPANDED, SizeClass.width(1127));
        eq("phone sideways is short", SizeClass.Height.COMPACT, SizeClass.height(390));
        eq("tablet sideways is expanded", SizeClass.Width.EXPANDED, SizeClass.width(1480));
        eq("tablet sideways is medium tall", SizeClass.Height.MEDIUM, SizeClass.height(880));
        eq("tablet upright is medium wide", SizeClass.Width.EXPANDED, SizeClass.width(924));
        eq("tablet upright is tall", SizeClass.Height.EXPANDED, SizeClass.height(1440));
        eq("half a tablet in split-screen is medium, like a phone sideways",
                SizeClass.Width.MEDIUM, SizeClass.width(736));
        eq("third of a tablet is compact, like a phone", SizeClass.Width.COMPACT, SizeClass.width(490));
        eq("599 is still compact", SizeClass.Width.COMPACT, SizeClass.width(599));
        eq("600 is medium", SizeClass.Width.MEDIUM, SizeClass.width(600));
        eq("840 is expanded", SizeClass.Width.EXPANDED, SizeClass.width(840));

        // Library cards: two on any phone upright, more on wide windows, never under 168dp.
        eq("Note 9 Library grid stays two (found on the phone: it showed three)", 2, SizeClass.columns(532, 168, 2));
        eq("any phone upright stays two, even 599 dp", 2, SizeClass.columns(599, 168, 2));
        eq("Note 9 list rows stay one", 1, SizeClass.columns(532, 360, 1));
        eq("small 360dp phone stays two", 2, SizeClass.columns(344, 168, 2));
        eq("tablet sideways gets eight cards", 8, SizeClass.columns(1464, 168, 2));
        eq("tablet upright gets five cards", 5, SizeClass.columns(908, 168, 2));
        eq("list rows: one on a phone", 1, SizeClass.columns(395, 360, 1));
        eq("list rows: four on a tablet sideways", 4, SizeClass.columns(1464, 360, 1));
        eq("bad cell size never divides by zero", 2, SizeClass.columns(500, 0, 2));
        truth("cards on the tablet are no bigger than on the Note 9",
                1464 / SizeClass.columns(1464, 168, 2) <= 532 / 2);

        // Reading column: zero on a phone, centred to 640 on a tablet, base padding kept.
        eq("phone upright: no extra padding", 0, SizeClass.sideInset(1080, 0, 0, 1280));
        eq("exactly fits: no extra padding", 0, SizeClass.sideInset(1280, 0, 0, 1280));
        eq("tablet sideways (2960px, 640dp at 2x): centred", 840, SizeClass.sideInset(2960, 0, 0, 1280));
        eq("base padding counted once", 816, SizeClass.sideInset(2960, 24, 24, 1280));
        int w = 2960, base = 24, max = 1280, add = SizeClass.sideInset(w, base, base, max);
        eq("content width is exactly the column", max, w - 2 * (base + add));

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void eq(String what, Object want, Object got) {
        if (want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL " + what + ": want " + want + ", got " + got);
    }

    private static void truth(String what, boolean ok) { eq(what, true, ok); }
}
