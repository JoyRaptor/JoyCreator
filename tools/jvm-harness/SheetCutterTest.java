import cc.joycreator.avatar.intake.SheetCutter;

import java.util.List;

/** The sheet cutter on a made-up sheet: parts found, background only where it touches the edge, grid cells, extraction. */
public class SheetCutterTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static void rect(int[] px, int W, int x0, int y0, int w, int h, int c) {
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) px[y * W + x] = c;
    }

    public static void main(String[] a) throws Exception {
        int W = 400, H = 300, WHITE = 0xFFFFFFFF, RED = 0xFFCC2222, BLUE = 0xFF2244CC;
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, WHITE);
        rect(px, W, 40, 40, 100, 80, RED);
        rect(px, W, 70, 60, 12, 12, WHITE);               // the white of an eye inside the red part
        rect(px, W, 220, 150, 60, 110, BLUE);
        rect(px, W, 300, 20, 3, 3, RED);                  // a speck too small to be a part
        int GREEN = 0xFF22AA22, MAG = 0xFFCC22CC;
        rect(px, W, 300, 100, 60, 10, GREEN);
        rect(px, W, 300, 100, 10, 60, GREEN);
        rect(px, W, 325, 125, 15, 15, MAG);               // a part sitting in the crook of the L: its box is inside the L's box
        SheetCutter.Sheet s = SheetCutter.read(px, W, H);
        ok(s.found.size() == 4, "four parts found, the speck left out (" + s.found.size() + ")");
        ok((px[0] >>> 24) == 0, "the plain background touching the edge went see-through");
        ok((px[(65) * W + 75] >>> 24) == 255, "the white inside a part stayed");
        SheetCutter.Comp red = SheetCutter.hit(s.found, 90 / (double) s.f, 80 / (double) s.f);
        SheetCutter.Comp blue = SheetCutter.hit(s.found, 250 / (double) s.f, 200 / (double) s.f);
        ok(red != null && blue != null && red != blue, "a tap finds the drawing under it");
        ok(SheetCutter.hit(s.found, 5, 5) == null, "a tap on nothing finds nothing");
        SheetCutter.Piece pr = SheetCutter.extract(s, red), pb = SheetCutter.extract(s, blue);
        ok(pr != null && Math.abs(pr.w - 100) <= 2 && Math.abs(pr.h - 80) <= 2, "the red part is cut to its own size (" + pr.w + "x" + pr.h + ")");
        ok(pb != null && pb.w == 60 && pb.h == 110, "the blue part too (" + pb.w + "x" + pb.h + ")");
        boolean clean = true;
        for (int v : pr.px) if ((v >>> 24) > 0 && v == BLUE) clean = false;
        ok(clean, "nothing of the other part comes with it");
        ok((pr.px[(25) * pr.w + 32] >>> 24) == 255, "the eye's white is in the cut part");
        SheetCutter.Piece ell = SheetCutter.extract(s, SheetCutter.hit(s.found, 330 / (double) s.f, 104 / (double) s.f));
        boolean noMag = ell != null;
        for (int v : ell.px) if (v == MAG) noMag = false;
        ok(noMag && ell.w == 60 && ell.h == 60, "a part inside another's box does not come along with it (" + ell.w + "x" + ell.h + ")");
        // a see-through sheet keeps its alpha and is not touched
        int[] t = new int[W * H];
        rect(t, W, 10, 10, 50, 50, BLUE);
        SheetCutter.Sheet st = SheetCutter.read(t, W, H);
        ok(st.found.size() == 1 && SheetCutter.extract(st, st.found.get(0)).w == 50, "a sheet that is already see-through is cut as it is");
        // an even grid
        List<SheetCutter.Comp> g = SheetCutter.cells(s, 4, 3);
        ok(g.size() == 12 && g.get(5).cell[0] == 100 && g.get(5).cell[1] == 100 && g.get(5).cell[2] == 100 && g.get(5).cell[3] == 100, "a 4 by 3 grid has twelve cells in reading order");
        SheetCutter.Piece cellPiece = SheetCutter.extract(s, g.get(6));          // the cell holding part of the blue part? (cell 6 = column 2, row 1: x 200-300, y 100-200)
        ok(cellPiece != null && cellPiece.w == 60 && cellPiece.h == 50, "a cell is cut to the paint inside it (" + (cellPiece == null ? "-" : cellPiece.w + "x" + cellPiece.h) + ")");
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
