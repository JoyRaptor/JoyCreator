package cc.joycreator.textlab;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Letters into distance pictures, and where each letter sits. The twin of
 * {@code tools/textlab-web/tl-glyphs.js}, sum for sum: the glyph is drawn at {@link #SS} times the
 * piece's size with Android's Canvas (the browser's canvas is the same Skia), coverage becomes an
 * exact distance by the same two-pass transform, and the result is averaged down, rows bottom to top.
 */
final class TlGlyphs {

    static final int GLYPH_EM = 208;     // pixels per em in a piece
    static final double SPREAD = 0.25;   // em: the field stores no distance beyond this
    static final int SS = 2;             // drawn this many times larger, then averaged down
    private static final double INF = 1e20;

    private final TlGl glw;
    private final TlFonts fonts;
    private final Map<String, TlText.Piece> cache = new HashMap<>();
    private final Map<String, TlText.Piece> tubeCache = new HashMap<>();
    @Nullable JSONObject tubefont;

    TlGlyphs(@NonNull TlGl glw, @NonNull TlFonts fonts) {
        this.glw = glw;
        this.fonts = fonts;
    }

    // ---- the distance transform -------------------------------------------------------------

    /** Felzenszwalb and Huttenlocher's exact distance transform, one row or column at a time. */
    private static void edt1d(double[] grid, int offset, int stride, int length, double[] f, int[] v, double[] z) {
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        f[0] = grid[offset];
        for (int q = 1, k = 0; q < length; q++) {
            f[q] = grid[offset + q * stride];
            double q2 = (double) q * q;
            double s;
            do {
                int r = v[k];
                s = (f[q] - f[r] + q2 - (double) r * r) / (q - r) / 2;
            } while (s <= z[k] && --k > -1);
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        for (int q = 0, k = 0; q < length; q++) {
            while (z[k + 1] < q) k++;
            int r = v[k];
            double qr = q - r;
            grid[offset + q * stride] = f[r] + qr * qr;
        }
    }

    private static void edt(double[] grid, int w, int h) {
        int n = Math.max(w, h);
        double[] f = new double[n];
        int[] v = new int[n];
        double[] z = new double[n + 1];
        for (int x = 0; x < w; x++) edt1d(grid, x, w, h, f, v, z);
        for (int y = 0; y < h; y++) edt1d(grid, y * w, 1, w, f, v, z);
    }

    /** A distance picture: em, positive inside, rows bottom to top. */
    static final class Pic {
        final float[] d;
        final int w, h;

        Pic(float[] d, int w, int h) {
            this.d = d;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * Coverage (0..255, rows top to bottom, w x h both even) to a distance picture at half that size.
     * Edge pixels use their coverage as a sub-pixel distance, which keeps a bevel from shimmering.
     */
    @NonNull
    static Pic distanceFromCoverage(@NonNull int[] alpha, int w, int h) {
        double[] outer = new double[w * h], inner = new double[w * h];
        for (int i = 0; i < w * h; i++) {
            double a = alpha[i] / 255.0;
            if (a <= 0) {
                outer[i] = INF;
                inner[i] = 0;
            } else if (a >= 1) {
                outer[i] = 0;
                inner[i] = INF;
            } else {
                double d = 0.5 - a;
                outer[i] = d > 0 ? d * d : 0;
                inner[i] = d < 0 ? d * d : 0;
            }
        }
        edt(outer, w, h);
        edt(inner, w, h);
        int ow = w / SS, oh = h / SS;
        float[] out = new float[ow * oh];
        double toEm = 1.0 / (SS * GLYPH_EM);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                double s = 0;
                for (int j = 0; j < SS; j++) {
                    for (int i = 0; i < SS; i++) {
                        int k = (y * SS + j) * w + x * SS + i;
                        s += Math.sqrt(inner[k]) - Math.sqrt(outer[k]);
                    }
                }
                out[(oh - 1 - y) * ow + x] = (float) Math.max(s / (SS * SS) * toEm, -SPREAD);
            }
        }
        return new Pic(out, ow, oh);
    }

    // ---- points for the emitter ---------------------------------------------------------------

    private static double[] dirAt(float[] d, int w, int x, int y) {
        double gx = d[y * w + x + 1] - d[y * w + x - 1], gy = d[(y + 1) * w + x] - d[(y - 1) * w + x];
        double l = Math.hypot(gx, gy);
        if (l == 0) l = 1;
        return new double[]{gx / l, gy / l};
    }

    /** Outline (with the outward direction), inside, and CORNERS (where a made object catches a glint). */
    @NonNull
    static TlText.Points pointsOf(@NonNull Pic pic) {
        float[] d = pic.d;
        int w = pic.w, h = pic.h, R = 3;
        double half = 0.6 / GLYPH_EM;
        TlText.Doubles edge = new TlText.Doubles(), fill = new TlText.Doubles();
        List<double[]> cand = new ArrayList<>();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double v = d[y * w + x];
                if (Math.abs(v) < half) {
                    double gx = d[y * w + x + 1] - d[y * w + x - 1], gy = d[(y + 1) * w + x] - d[(y - 1) * w + x];
                    double l = Math.hypot(gx, gy);
                    if (l == 0) l = 1;
                    edge.add((x + 0.5) / GLYPH_EM, (y + 0.5) / GLYPH_EM, -gx / l, -gy / l);
                    if (x > R && y > R && x < w - R - 1 && y < h - R - 1) {
                        double div = (dirAt(d, w, x + R, y)[0] - dirAt(d, w, x - R, y)[0]
                                + dirAt(d, w, x, y + R)[1] - dirAt(d, w, x, y - R)[1]) / (2 * R);
                        if (div < -0.07) cand.add(new double[]{-div, (x + 0.5) / GLYPH_EM, (y + 0.5) / GLYPH_EM, -gx / l, -gy / l});
                    }
                } else if (v > 0.012 && ((x * 7 + y * 13) % 5) == 0) {
                    fill.add((x + 0.5) / GLYPH_EM, (y + 0.5) / GLYPH_EM, 0, 1);
                }
            }
        }
        cand.sort((a, b) -> Double.compare(b[0], a[0]));
        TlText.Doubles corner = new TlText.Doubles();
        for (double[] c : cand) {
            boolean near = false;
            for (int i = 0; i < corner.n && !near; i += 4) near = Math.hypot(corner.a[i] - c[1], corner.a[i + 1] - c[2]) < 0.07;
            if (!near) corner.add(c[1], c[2], c[3], c[4]);
            if (corner.n >= 4 * 16) break;
        }
        return new TlText.Points(edge.toArray(), fill.toArray(), corner.toArray());
    }

    /**
     * Where each loop of a letter's outline gets its BREAK (a neon outline is never an endless ring).
     * Returns pairs: distance, and the distance to the nearest break (em).
     */
    @NonNull
    static float[] withBreaks(@NonNull Pic pic) {
        float[] d = pic.d;
        int w = pic.w, h = pic.h;
        double half = 0.75 / GLYPH_EM;
        boolean[] seen = new boolean[w * h];
        List<double[]> breaks = new ArrayList<>();
        int[] stack = new int[w * h];
        int[] run = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || Math.abs(d[start]) >= half) continue;
            int sp = 0, rn = 0;
            stack[sp++] = start;
            seen[start] = true;
            while (sp > 0) {
                int k = stack[--sp];
                run[rn++] = k;
                int x = k % w, y = k / w;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        int xx = x + i, yy = y + j, kk = yy * w + xx;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h || seen[kk] || Math.abs(d[kk]) >= half) continue;
                        seen[kk] = true;
                        stack[sp++] = kk;
                    }
                }
            }
            if (rn < 12) continue;
            int lowY = Integer.MAX_VALUE, leftX = Integer.MAX_VALUE;
            double sx = 0;
            int n = 0;
            for (int q = 0; q < rn; q++) lowY = Math.min(lowY, run[q] / w);
            for (int q = 0; q < rn; q++) if (run[q] / w <= lowY + 1) leftX = Math.min(leftX, run[q] % w);
            for (int q = 0; q < rn; q++) {
                int k = run[q];
                if (k / w <= lowY + 1 && k % w < leftX + 0.2 * GLYPH_EM) {
                    sx += k % w;
                    n++;
                }
            }
            breaks.add(new double[]{(sx / n + 0.5) / GLYPH_EM, (lowY + 0.5) / GLYPH_EM});
        }
        float[] out = new float[w * h * 2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double px = (x + 0.5) / GLYPH_EM, py = (y + 0.5) / GLYPH_EM;
                double best = 9;
                for (double[] b : breaks) best = Math.min(best, Math.hypot(px - b[0], py - b[1]));
                out[(y * w + x) * 2] = d[y * w + x];
                out[(y * w + x) * 2 + 1] = (float) best;
            }
        }
        return out;
    }

    // ---- measuring and drawing a letter -----------------------------------------------------

    /** The look book's TextMetrics, the parts it reads. */
    static final class Metrics {
        double width, ascent, descent, left, right;
    }

    @NonNull
    private Paint paintFor(@NonNull TlText.Font font) {
        TlFonts.Face f = fonts.face(font.family);
        // a face with one weight is used at that weight (never a faked bold); only a face with widths is asked for one
        return fonts.paint(f, font.weightOr700(), font.widthOr100(), GLYPH_EM * SS);
    }

    private final Map<String, Paint> paints = new HashMap<>();

    @NonNull
    private Paint paint(@NonNull TlText.Font font) {
        String k = font.key();
        Paint p = paints.get(k);
        if (p == null) {
            p = paintFor(font);
            paints.put(k, p);
        }
        return p;
    }

    private final Map<String, Paint> bigPaints = new HashMap<>();

    @NonNull
    private Paint big(@NonNull TlText.Font font) {
        String k = font.key();
        Paint p = bigPaints.get(k);
        if (p == null) {
            p = new Paint(paint(font));
            p.setTextSize(GLYPH_EM * SS * 64f);
            bigPaints.put(k, p);
        }
        return p;
    }

    @NonNull
    Metrics measure(@NonNull TlText.Font font, @NonNull String s) {
        Paint p = paint(font);
        Metrics m = new Metrics();
        // Android rounds an advance to whole pixels; the look book keeps fractions. Measured 64 times
        // larger and scaled back, the rounding is gone to within 1/128 px.
        m.width = big(font).measureText(s) / 64.0;
        if (!s.isEmpty()) {
            Path path = new Path();
            p.getTextPath(s, 0, s.length(), 0, 0, path);
            RectF b = new RectF();
            path.computeBounds(b, true);
            if (!b.isEmpty()) {
                // The browser's ink box (TextMetrics.actualBoundingBox*) is the glyph's box at Skia's
                // canonical path size, 64 px, rounded OUT to whole pixels, then scaled up: every number it
                // gives at 416 px is a multiple of 6.5. The look book cuts each letter's cell from that box,
                // so the phone must cut the same box, or every distance picture is a pixel or two off.
                // (a hair of slack so a flat baseline at exactly 0 is not pushed out by float noise)
                double k = 64.0 / (GLYPH_EM * SS), e = 1e-3;
                m.ascent = -Math.floor(b.top * k + e) / k;
                m.descent = Math.ceil(b.bottom * k - e) / k;
                m.left = -Math.floor(b.left * k + e) / k;
                m.right = Math.ceil(b.right * k - e) / k;
            }
        }
        return m;
    }

    /** One letter as a piece (cached). Its cell is the letter's ink plus the spread all round. */
    @Nullable
    TlText.Piece piece(@NonNull String ch, @NonNull TlText.Font font) {
        String key = ch + "|" + font.key();
        if (cache.containsKey(key)) return cache.get(key);
        int px = GLYPH_EM * SS;
        double slant = font.slant;
        Metrics m = measure(font, ch);
        int pad = (int) Math.ceil(SPREAD * px) + 2;
        int asc = (int) Math.ceil(m.ascent) + 1, desc = (int) Math.ceil(m.descent) + 1;
        int left = (int) Math.ceil(m.left + slant * desc) + 1, right = (int) Math.ceil(m.right + slant * asc) + 1;
        if (m.right + m.left <= 0) {
            cache.put(key, null);   // a space
            return null;
        }
        int w = left + right + 2 * pad, h = asc + desc + 2 * pad;
        w += w % SS;
        h += h % SS;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas cv = new Canvas(bmp);
        int ox = pad + left, by = pad + asc;
        Matrix mx = new Matrix();
        mx.setValues(new float[]{1, (float) -slant, (float) (slant * by), 0, 1, 0, 0, 0, 1});
        cv.setMatrix(mx);
        // The TRUE outline. Chrome on Android snaps a big letter's outline to the 64 px grid it builds it at
        // (a T's top at 286.0 px where the font says 285.8), which no Android call reproduces; drawing it as a
        // path instead of text was tried and gives the same pixels. Effect: faint speckle in fine bevel detail
        // on straight horizontal edges (dark-steel mean 0.62 of 255), nothing else.
        cv.drawText(ch, ox, by, paint(font));
        ByteBuffer buf = ByteBuffer.allocate(bmp.getRowBytes() * h);
        bmp.copyPixelsToBuffer(buf);
        int stride = bmp.getRowBytes();
        byte[] raw = buf.array();
        int[] alpha = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) alpha[y * w + x] = raw[y * stride + x] & 255;
        bmp.recycle();
        Pic pic = distanceFromCoverage(alpha, w, h);
        TlText.Piece p = new TlText.Piece();
        p.tex = glw.pairTexture(pic.w, pic.h, withBreaks(pic));
        p.cellW = (double) pic.w / GLYPH_EM;
        p.cellH = (double) pic.h / GLYPH_EM;
        p.originX = (double) ox / px;
        p.originY = (double) (h - by) / px;
        p.points = pointsOf(pic);
        cache.put(key, p);
        return p;
    }

    /** Code points of a string, each as its own string (the look book's {@code Array.from}). */
    @NonNull
    static List<String> chars(@NonNull String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            out.add(s.substring(i, i + n));
            i += n;
        }
        return out;
    }

    /** Where every letter sits, em, in a field with room round the words. margin = left, top, right, bottom. */
    @NonNull
    TlText.Layout layout(@NonNull String text, @NonNull TlText.Font font, @NonNull double[] margin) {
        if ("tube".equals(font.family) && tubefont != null) {
            return TlTubes.layout(glw, tubefont, tubeCache, text, font, margin);
        }
        int px = GLYPH_EM * SS;
        String[] lines = (font.caps ? text.toUpperCase(java.util.Locale.ROOT) : text).split("\n", -1);
        double cap = measure(font, "H").ascent / px;
        double pitch = cap + (Double.isNaN(font.leading) ? 0.42 : font.leading);
        double track = Double.isNaN(font.tracking) ? 0 : font.tracking;
        List<List<String>> rowChars = new ArrayList<>();
        List<double[]> rowXs = new ArrayList<>();
        double[] widths = new double[lines.length];
        double boxW = 0.5;
        for (int li = 0; li < lines.length; li++) {
            List<String> cs = chars(lines[li]);
            double[] xs = new double[cs.size()];
            StringBuilder acc = new StringBuilder();
            for (int i = 0; i < cs.size(); i++) {
                xs[i] = measure(font, acc.toString()).width / px + track * i;
                acc.append(cs.get(i));
            }
            widths[li] = measure(font, acc.toString()).width / px + track * Math.max(cs.size() - 1, 0);
            boxW = Math.max(boxW, widths[li]);
            rowChars.add(cs);
            rowXs.add(xs);
        }
        double boxH = cap + (lines.length - 1) * pitch;
        double ml = margin[0], mt = margin[1], mr = margin[2], mb = margin[3];
        TlText.Layout lay = new TlText.Layout();
        lay.fieldW = boxW + ml + mr;
        lay.fieldH = boxH + mt + mb;
        lay.cap = cap;
        for (int li = 0; li < lines.length; li++) {
            double baseline = mb + (lines.length - 1 - li) * pitch;
            double x0 = ml + (boxW - widths[li]) / 2;
            List<String> cs = rowChars.get(li);
            for (int i = 0; i < cs.size(); i++) {
                TlText.Piece piece = piece(cs.get(i), font);
                if (piece == null) continue;
                double penX = x0 + rowXs.get(li)[i];
                TlText.Letter l = new TlText.Letter();
                l.ch = cs.get(i);
                l.piece = piece;
                l.baseline = baseline;
                l.cap = cap;
                l.x = penX - piece.originX;
                l.y = baseline - piece.originY;
                l.pivotX = penX + (piece.cellW - 2 * piece.originX) / 2;
                l.pivotY = baseline + cap / 2;
                lay.letters.add(l);
            }
        }
        for (int i = 0; i < lay.letters.size(); i++) {
            lay.letters.get(i).index = i;
            lay.letters.get(i).count = lay.letters.size();
        }
        return lay;
    }

    /**
     * The emitter's point list for a laid-out line of letters: 64 x 192 points. Rows 0..63 lie on the
     * outline, 64..127 inside, 128..191 on corners. Each point: x, y (field em), outward x, y.
     */
    @NonNull
    static float[] emitterPoints(@NonNull List<TlText.Letter> letters) {
        int W = 64, H = 192;
        float[] out = new float[W * H * 4];
        int[] seed = {0x9e3779b9};
        for (int which = 0; which < 3; which++) {
            int row0 = which * 64;
            int[] sizes = new int[letters.size()];
            int total = 0;
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = letters.get(i).piece.points.of(which).length / 4;
                total += sizes[i];
            }
            for (int i = 0; i < W * 64; i++) {
                int o = (row0 * W + i) * 4;
                if (total == 0) {
                    out[o] = -99;
                    out[o + 1] = -99;
                    out[o + 3] = 1;
                    continue;
                }
                double pick = rnd(seed) * total;
                int li = 0;
                while (li < sizes.length - 1 && pick >= sizes[li]) {
                    pick -= sizes[li];
                    li++;
                }
                TlText.Letter l = letters.get(li);
                double[] pts = l.piece.points.of(which);
                int k = Math.min((int) Math.floor(pick), sizes[li] - 1) * 4;
                out[o] = (float) (l.x + pts[k]);
                out[o + 1] = (float) (l.y + pts[k + 1]);
                out[o + 2] = (float) pts[k + 2];
                out[o + 3] = (float) pts[k + 3];
            }
        }
        return out;
    }

    /** mulberry32, exactly as the look book rolls it. */
    private static double rnd(int[] seed) {
        seed[0] = seed[0] + 0x6d2b79f5;
        int s = seed[0];
        int t = (s ^ (s >>> 15)) * (1 | s);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
    }

    void release() {
        for (TlText.Piece p : cache.values()) if (p != null) TlGl.deleteTexture(p.tex);
        for (TlText.Piece p : tubeCache.values()) if (p != null) TlGl.deleteTexture(p.tex);
        cache.clear();
        tubeCache.clear();
    }
}
