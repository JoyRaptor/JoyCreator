import com.fadcam.ui.faditor.model.SolidColour;

/**
 * The Solid colour clip (Avatar build, Studio reuse item 6): the still is shaped like the frame (so the colour fills it
 * edge to edge), opaque, one file per colour and size, and its name gives the colour back.
 */
public class SolidColourTest {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL " + what); }
    }

    static boolean shape(float aspect) {
        int[] wh = SolidColour.size(aspect);
        float got = wh[0] / (float) wh[1];
        // within one pixel of the frame's shape on the short side
        return Math.abs(got - aspect) <= aspect / Math.min(wh[0], wh[1]) + 1e-6
                && Math.max(wh[0], wh[1]) == SolidColour.LONG_SIDE;
    }

    public static void main(String[] a) {
        for (float asp : new float[]{16f / 9f, 9f / 16f, 1f, 4f / 3f, 4f / 5f, 21f / 9f})
            check(shape(asp), "the still is not shaped like a " + asp + " frame: " + java.util.Arrays.toString(SolidColour.size(asp)));
        int[] wide = SolidColour.size(16f / 9f);
        check(wide[0] == 192 && wide[1] == 108, "16:9 still is " + wide[0] + "x" + wide[1]);
        int[] tall = SolidColour.size(9f / 16f);
        check(tall[0] == 108 && tall[1] == 192, "9:16 still is " + tall[0] + "x" + tall[1]);
        int[] unk = SolidColour.size(0f);
        check(unk[0] == 192 && unk[1] == 108, "an unknown frame does not get 16:9");
        check(SolidColour.size(Float.NaN)[1] == 108, "NaN frame shape");

        check(SolidColour.opaque(0x0000B140) == SolidColour.KEY_GREEN, "a see-through colour is not made opaque");
        check(SolidColour.hex(SolidColour.KEY_BLUE).equals("#0047BB"), "hex " + SolidColour.hex(SolidColour.KEY_BLUE));
        check(!SolidColour.fileName(SolidColour.KEY_GREEN, 192, 108).equals(SolidColour.fileName(SolidColour.KEY_BLUE, 192, 108)),
                "two colours share a file");
        check(!SolidColour.fileName(SolidColour.KEY_GREEN, 192, 108).equals(SolidColour.fileName(SolidColour.KEY_GREEN, 108, 192)),
                "two frame shapes share a file");
        check(SolidColour.fileName(0x8000B140, 192, 108).equals(SolidColour.fileName(SolidColour.KEY_GREEN, 192, 108)),
                "alpha changes the file of an opaque colour");

        for (int c : new int[]{SolidColour.KEY_GREEN, SolidColour.KEY_BLUE, 0xFF000000, 0xFFFFFFFF, 0xFF123456}) {
            Integer back = SolidColour.colourOf(SolidColour.displayName(c));
            check(back != null && back == c, "the name does not give the colour back: " + SolidColour.displayName(c));
        }
        check(SolidColour.colourOf("Blank") == null, "the Black clip reads as a solid colour");
        check(SolidColour.colourOf("Solid colour #12") == null, "a damaged name reads as a colour");
        check(SolidColour.colourOf(null) == null, "no name");

        System.out.println((fail == 0 ? "PASSED " : "FAILED ") + pass + " checks, " + fail + " failures");
        System.exit(fail == 0 ? 0 : 1);
    }
}
