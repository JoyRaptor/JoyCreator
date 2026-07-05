import com.fadcam.ui.faditor.sprite.SpriteGridDetector;

public class DetectorTest {
    static int fails = 0;
    static void check(boolean c, String n) { System.out.println((c?"PASS  ":"FAIL  ")+n); if(!c) fails++; }

    // Build stats for a synthetic grid: margin, then cells of cellSize separated by gap.
    static int[] stats(int total, int lineLen, int margin, int cells, int cellSize, int gap) {
        int[] s = new int[total];
        java.util.Arrays.fill(s, lineLen);            // everything empty (gutter)
        int x = margin;
        for (int c = 0; c < cells; c++) {
            for (int i = 0; i < cellSize && x + i < total; i++) s[x + i] = 0; // content
            x += cellSize + gap;
        }
        return s;
    }

    public static void main(String[] a) {
        // 4x3 grid, margins 10/8, cell 50x40, spacing 6/4; image 10+4*50+3*6+10=238 wide, 8+3*40+2*4+8=144 tall
        int[] col = stats(238, 144, 10, 4, 50, 6);
        int[] row = stats(144, 238, 8, 3, 40, 4);
        SpriteGridDetector.Result r = SpriteGridDetector.fromStats(col, row, 238, 144);
        check(r != null && r.cols == 4 && r.rows == 3, "4x3 grid detected");
        check(r != null && r.marginX == 10 && r.marginY == 8, "margins 10/8");
        check(r != null && r.spacingX == 6 && r.spacingY == 4, "spacing 6/4");

        // 1xN horizontal strip (single row) still detects
        int[] col2 = stats(316, 60, 4, 6, 48, 4);
        int[] row2 = stats(60, 316, 4, 1, 52, 0);
        SpriteGridDetector.Result r2 = SpriteGridDetector.fromStats(col2, row2, 316, 60);
        check(r2 != null && r2.cols == 6 && r2.rows == 1, "1x6 strip detected");

        // Solid image (no gutters at all) -> single content run each way -> null
        int[] col3 = stats(100, 100, 0, 1, 100, 0);
        int[] row3 = stats(100, 100, 0, 1, 100, 0);
        check(SpriteGridDetector.fromStats(col3, row3, 100, 100) == null, "solid image -> null");

        // Wildly non-uniform runs -> null (not a grid)
        int[] col4 = new int[100];
        java.util.Arrays.fill(col4, 100);
        for (int i = 5; i < 15; i++) col4[i] = 0;   // 10 wide
        for (int i = 40; i < 90; i++) col4[i] = 0;  // 50 wide
        check(SpriteGridDetector.fromStats(col4, row3, 100, 100) == null, "non-uniform -> null");

        System.out.println(fails == 0 ? "ALL GREEN" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
