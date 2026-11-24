import com.fadcam.ui.faditor.effects.LutMath;

/** PREVIEW-1: the CPU grade used for grade swatches. Run: bash tools/jvm-harness/run-lutmath.sh */
public class LutMathTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("FAIL  " + name); }
    }

    static int[] identity(int n) {
        int[] lut = new int[n * n * n];
        int i = 0;
        for (int r = 0; r < n; r++) for (int g = 0; g < n; g++) for (int b = 0; b < n; b++)
            lut[i++] = 0xFF000000 | ((r * 255 / (n - 1)) << 16) | ((g * 255 / (n - 1)) << 8) | (b * 255 / (n - 1));
        return lut;
    }

    static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tol && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tol
                && Math.abs((a & 255) - (b & 255)) <= tol;
    }

    public static void main(String[] a) {
        int[] src = {0xFF000000, 0xFFFFFFFF, 0xFF336699, 0xFFFF8000, 0x80123456, 0xFF808080};
        int[] id = LutMath.apply(identity(17), 17, src);
        boolean same = true;
        for (int i = 0; i < src.length; i++) same &= near(id[i], src[i], 1) && (id[i] >>> 24) == (src[i] >>> 24);
        check("identity grade leaves every colour (and alpha) as it was", same);

        // a grade that swaps red and blue
        int n = 9;
        int[] swap = new int[n * n * n];
        int i = 0;
        for (int r = 0; r < n; r++) for (int g = 0; g < n; g++) for (int b = 0; b < n; b++)
            swap[i++] = 0xFF000000 | ((b * 255 / (n - 1)) << 16) | ((g * 255 / (n - 1)) << 8) | (r * 255 / (n - 1));
        int[] out = LutMath.apply(swap, n, new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF336699});
        check("red becomes blue", near(out[0], 0xFF0000FF, 1));
        check("green stays green", near(out[1], 0xFF00FF00, 1));
        check("blue becomes red", near(out[2], 0xFFFF0000, 1));
        check("a mixed colour swaps its ends", near(out[3], 0xFF996633, 2));

        // an invert grade is a real change, and between two lattice points it blends
        int[] inv = new int[n * n * n];
        i = 0;
        for (int r = 0; r < n; r++) for (int g = 0; g < n; g++) for (int b = 0; b < n; b++)
            inv[i++] = 0xFF000000 | ((255 - r * 255 / (n - 1)) << 16) | ((255 - g * 255 / (n - 1)) << 8) | (255 - b * 255 / (n - 1));
        int[] o2 = LutMath.apply(inv, n, new int[]{0xFF000000, 0xFF404040});
        check("invert: black becomes white", near(o2[0], 0xFFFFFFFF, 1));
        check("invert blends between lattice points", near(o2[1], 0xFFBFBFBF, 2));

        // a LUT of the wrong length is refused by returning the picture untouched
        int[] same2 = LutMath.apply(new int[10], 3, src);
        check("wrong-sized grade returns the picture as it was", same2 == src);
        System.out.println("LutMathTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
