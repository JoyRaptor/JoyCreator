package cc.joycreator.textlab;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Neon tubes from PATHS (the tube font). The twin of {@code tools/textlab-web/tl-tubes.js}: a tube is
 * a centre line, baked into a piece whose distance is from that line (so a tube is an exact round
 * rod) and whose second number is 1 + how far along the tube a point is.
 */
final class TlTubes {

    static final double TUBE_R = 0.03;   // em: a baked tube's radius before the look thickens or thins it
    private static final int PX = 144;   // pixels per em in a tube's piece

    private TlTubes() {}

    /** Round every corner of a centre line with a bend of the given radius (a torch bends, never folds). */
    @NonNull
    private static List<double[]> bendCorners(@NonNull List<double[]> pts, double radius) {
        List<double[]> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            double[] a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
            double la = Math.hypot(a[0] - b[0], a[1] - b[1]), lc = Math.hypot(c[0] - b[0], c[1] - b[1]);
            double t = Math.min(radius, Math.min(la / 2, lc / 2));
            double[] p1 = {b[0] + ((a[0] - b[0]) / la) * t, b[1] + ((a[1] - b[1]) / la) * t};
            double[] p2 = {b[0] + ((c[0] - b[0]) / lc) * t, b[1] + ((c[1] - b[1]) / lc) * t};
            for (int k = 0; k <= 6; k++) {
                double u = k / 6.0, v = 1 - u;
                out.add(new double[]{v * v * p1[0] + 2 * v * u * b[0] + u * u * p2[0],
                        v * v * p1[1] + 2 * v * u * b[1] + u * u * p2[1]});
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    /** One tube (flat x,y list in the font's cap units) into a piece. {@code scale} = em per cap unit. */
    @NonNull
    static TlText.Piece bake(@NonNull TlGl glw, @NonNull JSONArray flat, double scale, double bend) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i + 1 < flat.length(); i += 2) pts.add(new double[]{flat.optDouble(i) * scale, flat.optDouble(i + 1) * scale});
        pts = bendCorners(pts, bend * scale);
        double[] len = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            len[i] = len[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        double total = len[len.length - 1];
        double pad = TlGlyphs.SPREAD + TUBE_R + 2.0 / PX;
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (double[] p : pts) {
            x0 = Math.min(x0, p[0]);
            y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0]);
            y1 = Math.max(y1, p[1]);
        }
        x0 -= pad;
        y0 -= pad;
        x1 += pad;
        y1 += pad;
        int w = (int) Math.ceil((x1 - x0) * PX), h = (int) Math.ceil((y1 - y0) * PX);
        float[] data = new float[w * h * 2], dOnly = new float[w * h];
        int n = pts.size();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double px = x0 + (i + 0.5) / PX, py = y0 + (j + 0.5) / PX;
                double best = 1e9, at = 0;
                for (int s = 1; s < n; s++) {
                    double ax = pts.get(s - 1)[0], ay = pts.get(s - 1)[1];
                    double bx = pts.get(s)[0] - ax, by = pts.get(s)[1] - ay;
                    double ll = bx * bx + by * by;
                    double u = ll > 0 ? ((px - ax) * bx + (py - ay) * by) / ll : 0;
                    u = u < 0 ? 0 : u > 1 ? 1 : u;
                    double dx = px - ax - bx * u, dy = py - ay - by * u, dd = dx * dx + dy * dy;
                    if (dd < best) {
                        best = dd;
                        at = len[s - 1] + (len[s] - len[s - 1]) * u;
                    }
                }
                float d = (float) Math.max(TUBE_R - Math.sqrt(best), -TlGlyphs.SPREAD);
                int k = j * w + i;
                data[k * 2] = d;
                data[k * 2 + 1] = (float) (1 + (total > 0 ? at / total : 0));
                dOnly[k] = d;
            }
        }
        TlText.Piece p = new TlText.Piece();
        p.tex = glw.pairTexture(w, h, data);
        p.paint = true;
        p.len = total;
        p.cellW = (double) w / PX;
        p.cellH = (double) h / PX;
        p.cellX = x0;
        p.cellY = y0;
        p.points = pointsOfTube(dOnly, w, h);
        return p;
    }

    @NonNull
    private static TlText.Points pointsOfTube(@NonNull float[] d, int w, int h) {
        TlText.Doubles edge = new TlText.Doubles(), fill = new TlText.Doubles();
        double half = 0.6 / PX;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double v = d[y * w + x];
                if (Math.abs(v) < half) {
                    double gx = d[y * w + x + 1] - d[y * w + x - 1], gy = d[(y + 1) * w + x] - d[(y - 1) * w + x];
                    double l = Math.hypot(gx, gy);
                    if (l == 0) l = 1;
                    edge.add((x + 0.5) / PX, (y + 0.5) / PX, -gx / l, -gy / l);
                } else if (v > 0.008 && ((x * 7 + y * 13) % 5) == 0) {
                    fill.add((x + 0.5) / PX, (y + 0.5) / PX, 0, 1);
                }
            }
        }
        double[] e = edge.toArray();
        return new TlText.Points(e, fill.toArray(), java.util.Arrays.copyOf(e, Math.min(64, e.length)));
    }

    /** Lay a line of tube letters out. The same shape as {@link TlGlyphs#layout}. */
    @NonNull
    static TlText.Layout layout(@NonNull TlGl glw, @NonNull JSONObject font, @NonNull Map<String, TlText.Piece> cache,
                                @NonNull String text, @NonNull TlText.Font spec, @NonNull double[] margin) {
        double cap = font.optDouble("cap");
        double gap = (Double.isNaN(spec.tracking) ? font.optDouble("gap") : spec.tracking) * cap;
        String[] lines = text.toUpperCase(java.util.Locale.ROOT).split("\n", -1);
        double pitch = cap + (Double.isNaN(spec.leading) ? 0.5 : spec.leading);
        JSONObject glyphs = font.optJSONObject("glyphs");
        double bend = font.optDouble("bend");
        List<List<Object[]>> rows = new ArrayList<>();
        double[] widths = new double[lines.length];
        double boxW = 0.5;
        int count = 0;
        for (int li = 0; li < lines.length; li++) {
            double x = 0;
            List<Object[]> items = new ArrayList<>();
            for (String ch : TlGlyphs.chars(lines[li])) {
                JSONObject g = glyphs != null ? glyphs.optJSONObject(ch) : null;
                if (g == null) {
                    x += cap * 0.4;
                    continue;
                }
                items.add(new Object[]{ch, g, x});
                x += g.optDouble("w") * cap + gap;
            }
            widths[li] = Math.max(x - gap, 0);
            boxW = Math.max(boxW, widths[li]);
            rows.add(items);
            count += items.size();
        }
        double boxH = cap + (lines.length - 1) * pitch;
        double ml = margin[0], mr = margin[2], mb = margin[3], mt = margin[1];
        TlText.Layout lay = new TlText.Layout();
        int index = 0;
        for (int li = 0; li < rows.size(); li++) {
            double baseline = mb + (lines.length - 1 - li) * pitch, left = ml + (boxW - widths[li]) / 2;
            for (Object[] it : rows.get(li)) {
                String ch = (String) it[0];
                JSONObject g = (JSONObject) it[1];
                double ix = (Double) it[2];
                int first = lay.letters.size();
                JSONArray tubes = g.optJSONArray("t");
                for (int order = 0; tubes != null && order < tubes.length(); order++) {
                    String key = ch + ":" + order;
                    TlText.Piece piece = cache.get(key);
                    if (piece == null) {
                        piece = bake(glw, tubes.optJSONArray(order), cap, bend);
                        cache.put(key, piece);
                    }
                    TlText.Letter l = new TlText.Letter();
                    l.ch = ch;
                    l.piece = piece;
                    l.baseline = baseline;
                    l.cap = cap;
                    l.order = order;
                    l.index = index;
                    l.count = count;
                    l.x = left + ix + piece.cellX;
                    l.y = baseline + piece.cellY;
                    l.pivotX = left + ix + (g.optDouble("w") * cap) / 2;
                    l.pivotY = baseline + cap / 2;
                    lay.letters.add(l);
                }
                // the pen writes the letter's tubes one after another: each tube's share of the whole letter
                double whole = 0;
                for (int i = first; i < lay.letters.size(); i++) whole += lay.letters.get(i).piece.len;
                if (whole == 0) whole = 1;
                double done = 0;
                for (int i = first; i < lay.letters.size(); i++) {
                    TlText.Letter l = lay.letters.get(i);
                    l.arc = new double[]{done / whole, l.piece.len / whole};
                    done += l.piece.len;
                }
                index++;
            }
        }
        lay.fieldW = boxW + ml + mr;
        lay.fieldH = boxH + mt + mb;
        lay.cap = cap;
        lay.tubes = true;
        return lay;
    }
}
