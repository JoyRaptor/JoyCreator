package cc.joycreator.avatar.intake;

import java.util.ArrayList;
import java.util.List;

/**
 * The "sheet of parts" door (the pilot's cutter): one picture with the parts laid out. If the sheet has a plain background it is
 * taken away, but only where it touches the edge (the white of an eye or a highlight inside a part stays); each separate drawing
 * is then one part, found with a little reach so a loose strand stays with its drawing; or the sheet is an even grid and every cell
 * is one drawing. Pure pixels (ARGB ints), so it is checked off the phone.
 */
public final class SheetCutter {
    /** One piece on the sheet, in the working (reduced) resolution: its box and how many working pixels it holds. */
    public static final class Comp {
        public final int id;
        public int x0, y0, x1, y1, n;
        /** For a grid cell: the cell in sheet pixels (x, y, w, h); null for a found drawing. */
        public int[] cell;

        Comp(int id, int x0, int y0, int x1, int y1) {
            this.id = id;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    /** What a sheet is made of once read. */
    public static final class Sheet {
        public final int w, h, f;                 // sheet size; working scale (one working pixel is f sheet pixels)
        public final int[] px;                    // the sheet, background taken away
        public final int lw, lh;
        public final int[] lab;                   // working pixels: which piece (0 = none)
        public final List<Comp> found = new ArrayList<>();

        Sheet(int w, int h, int f, int[] px) {
            this.w = w;
            this.h = h;
            this.f = f;
            this.px = px;
            lw = (w + f - 1) / f;
            lh = (h + f - 1) / f;
            lab = new int[lw * lh];
        }
    }

    /** One drawing cut out: its pixels (ARGB) and size, trimmed to its paint. */
    public static final class Piece {
        public final int[] px;
        public final int w, h;
        /** Where its top left sat on the sheet (so frames of a loop can be kept in register). */
        public final int sx, sy;

        Piece(int[] px, int w, int h, int sx, int sy) {
            this.px = px;
            this.w = w;
            this.h = h;
            this.sx = sx;
            this.sy = sy;
        }
    }

    private SheetCutter() {
    }

    private static int alpha(int c) {
        return c >>> 24;
    }

    /** Read a sheet ({@code argb} is changed: a plain background touching the edge goes see-through). */
    public static Sheet read(int[] argb, int W, int H) {
        boolean any = false;
        for (int i = 0; i < argb.length; i += 97) if (alpha(argb[i]) < 250) {
            any = true;
            break;
        }
        if (!any) takeAwayBackground(argb, W, H);
        int f = Math.max(1, (int) Math.ceil(Math.max(W, H) / 560.0));
        Sheet s = new Sheet(W, H, f, argb);
        boolean[] mask = new boolean[s.lw * s.lh];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (alpha(argb[y * W + x]) > 40) mask[(y / f) * s.lw + (x / f)] = true;
        int[] stack = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || s.lab[i] != 0) continue;
            int cid = s.found.size() + 1, sp = 0;
            Comp c = new Comp(cid, s.lw, s.lh, 0, 0);
            stack[sp++] = i;
            s.lab[i] = cid;
            while (sp > 0) {
                int p = stack[--sp], px = p % s.lw, py = p / s.lw;
                c.n++;
                if (px < c.x0) c.x0 = px;
                if (px > c.x1) c.x1 = px;
                if (py < c.y0) c.y0 = py;
                if (py > c.y1) c.y1 = py;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        int qx = px + dx, qy = py + dy;
                        if (qx < 0 || qy < 0 || qx >= s.lw || qy >= s.lh) continue;
                        int q = qy * s.lw + qx;
                        if (mask[q] && s.lab[q] == 0) {
                            s.lab[q] = cid;
                            stack[sp++] = q;
                        }
                    }
            }
            s.found.add(c);
        }
        List<Comp> keep = new ArrayList<>();
        for (Comp c : s.found) if (c.n >= 14) keep.add(c);
        s.found.clear();
        s.found.addAll(keep);
        return s;
    }

    /** The corner's colour goes see-through, but only where it is joined to the edge. */
    private static void takeAwayBackground(int[] d, int W, int H) {
        int c0 = d[0], r0 = (c0 >> 16) & 255, g0 = (c0 >> 8) & 255, b0 = c0 & 255;
        boolean[] seen = new boolean[W * H];
        int[] todo = new int[W * H];
        int sp = 0;
        for (int e = 0; e < W; e++) {
            sp = seed(d, seen, todo, sp, e, r0, g0, b0);
            sp = seed(d, seen, todo, sp, (H - 1) * W + e, r0, g0, b0);
        }
        for (int e = 0; e < H; e++) {
            sp = seed(d, seen, todo, sp, e * W, r0, g0, b0);
            sp = seed(d, seen, todo, sp, e * W + W - 1, r0, g0, b0);
        }
        while (sp > 0) {
            int q = todo[--sp], qx = q % W;
            d[q] &= 0x00FFFFFF;
            if (qx > 0) sp = seed(d, seen, todo, sp, q - 1, r0, g0, b0);
            if (qx < W - 1) sp = seed(d, seen, todo, sp, q + 1, r0, g0, b0);
            if (q >= W) sp = seed(d, seen, todo, sp, q - W, r0, g0, b0);
            if (q < W * (H - 1)) sp = seed(d, seen, todo, sp, q + W, r0, g0, b0);
        }
    }

    private static int seed(int[] d, boolean[] seen, int[] todo, int sp, int q, int r0, int g0, int b0) {
        if (seen[q]) return sp;
        int c = d[q];
        if (Math.abs(((c >> 16) & 255) - r0) + Math.abs(((c >> 8) & 255) - g0) + Math.abs((c & 255) - b0) < 46) {
            seen[q] = true;
            todo[sp++] = q;
        }
        return sp;
    }

    /** An even grid: every cell is one drawing (a mouth chart, a row of frames). */
    public static List<Comp> cells(Sheet s, int across, int down) {
        List<Comp> out = new ArrayList<>();
        double cw = (double) s.w / across, ch = (double) s.h / down;
        for (int r = 0; r < down; r++)
            for (int q = 0; q < across; q++) {
                Comp c = new Comp(-1 - out.size(), (int) (q * cw / s.f), (int) (r * ch / s.f), (int) ((q + 1) * cw / s.f) - 1, (int) ((r + 1) * ch / s.f) - 1);
                c.n = 1_000_000_000;
                c.cell = new int[]{(int) Math.round(q * cw), (int) Math.round(r * ch), (int) Math.round(cw), (int) Math.round(ch)};
                out.add(c);
            }
        return out;
    }

    /** The drawing under a tap (working pixels): the smallest piece whose box holds it, or null. */
    public static Comp hit(List<Comp> comps, double px, double py) {
        Comp best = null;
        for (Comp c : comps) if (px >= c.x0 - 1 && px <= c.x1 + 1 && py >= c.y0 - 1 && py <= c.y1 + 1 && (best == null || c.n < best.n)) best = c;
        return best;
    }

    /** Cut one piece out of the sheet: only what belongs to it (a found drawing) or the whole cell, trimmed to its paint. Null if empty. */
    public static Piece extract(Sheet s, Comp c) {
        int x0, y0, w, h;
        if (c.cell != null) {
            x0 = c.cell[0];
            y0 = c.cell[1];
            w = Math.min(c.cell[2], s.w - x0);
            h = Math.min(c.cell[3], s.h - y0);
        } else {
            x0 = c.x0 * s.f;
            y0 = c.y0 * s.f;
            w = Math.min(s.w - x0, (c.x1 - c.x0 + 1) * s.f);
            h = Math.min(s.h - y0, (c.y1 - c.y0 + 1) * s.f);
        }
        if (w <= 0 || h <= 0) return null;
        int[] t = new int[w * h];
        int tx0 = w, ty0 = h, tx1 = -1, ty1 = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int v = s.px[(y0 + y) * s.w + x0 + x];
                if (c.cell == null && s.lab[((y0 + y) / s.f) * s.lw + ((x0 + x) / s.f)] != c.id) v &= 0x00FFFFFF;
                t[y * w + x] = v;
                if (alpha(v) > 10) {
                    if (x < tx0) tx0 = x;
                    if (x > tx1) tx1 = x;
                    if (y < ty0) ty0 = y;
                    if (y > ty1) ty1 = y;
                }
            }
        if (tx1 < 0) return null;
        int tw = tx1 - tx0 + 1, th = ty1 - ty0 + 1;
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) System.arraycopy(t, (ty0 + y) * w + tx0, out, y * tw, tw);
        return new Piece(out, tw, th, x0 + tx0, y0 + ty0);
    }

    /** Frames of a loop: every frame in the SAME box, the one that holds all of them, so they stay in register when they play. */
    public static List<Piece> align(List<Piece> in) {
        if (in.isEmpty()) return in;
        int ux0 = Integer.MAX_VALUE, uy0 = Integer.MAX_VALUE, ux1 = -1, uy1 = -1;
        for (Piece p : in) {
            ux0 = Math.min(ux0, p.sx);
            uy0 = Math.min(uy0, p.sy);
            ux1 = Math.max(ux1, p.sx + p.w);
            uy1 = Math.max(uy1, p.sy + p.h);
        }
        int w = ux1 - ux0, h = uy1 - uy0;
        List<Piece> out = new ArrayList<>();
        for (Piece p : in) {
            int[] px = new int[w * h];
            for (int y = 0; y < p.h; y++) System.arraycopy(p.px, y * p.w, px, (p.sy - uy0 + y) * w + (p.sx - ux0), p.w);
            out.add(new Piece(px, w, h, ux0, uy0));
        }
        return out;
    }
}
