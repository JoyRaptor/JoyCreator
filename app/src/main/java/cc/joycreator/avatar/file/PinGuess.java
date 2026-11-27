package cc.joycreator.avatar.file;

import cc.joycreator.avatar.solve.Pics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pins guessed from the pixels (the pilot's {@code al-autorig.js}: guessFan, guessMouth, guessSwing, guessChain), so nobody
 * places three pins on sixteen arm drawings by hand. Every guess is ordinary data afterwards: visible, draggable, one tap
 * to redo ({@code guessed: true} marks a limb as a guess).
 *
 * <p>The trick for a fan of limb drawings: drawings of one arm at many angles all overlap at the SHOULDER, so the shoulder
 * is simply where most of them are opaque at once. The hand is the far end.</p>
 */
public final class PinGuess {
    private PinGuess() {}

    public static final Pattern BENDY = Pattern.compile("hair|tuft|feather|braid|ponytail|pigtail|bangs?\\b|fringe|antenna|ribbon|scarf|cape|whisker|tassel|plume|mane|tail", Pattern.CASE_INSENSITIVE);
    public static final Pattern STIFF = Pattern.compile("chain|\\blinks?\\b|earring|pendant|charm|medal|locket|\\bbell\\b|\\bkeys?\\b|dangl", Pattern.CASE_INSENSITIVE);

    // ── pixels ────────────────────────────────────────────────────────────────────────────────────

    private interface Opaque {
        void at(double x, double y);
    }

    private static void eachOpaque(JSONObject n, Pics pics, int step, Opaque fn) {
        String img = n.optString("img", "");
        if (img.isEmpty() || !pics.has(img)) return;
        byte[] a = pics.alpha(img);
        int w = pics.w(img), h = pics.h(img);
        double nx = n.optDouble("x", 0), ny = n.optDouble("y", 0);
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) if ((a[y * w + x] & 255) > 60) fn.at(nx + x, ny + y);
    }

    private static double hyp(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    /** [x0, y0, x1, y1, w, h, cx, cy] of one drawing, or null. */
    private static double[] boxOf(JSONObject n, Pics pics) {
        String img = n.optString("img", "");
        if (img.isEmpty() || !pics.has(img)) return null;
        double x = n.optDouble("x", 0), y = n.optDouble("y", 0);
        int w = pics.w(img), h = pics.h(img);
        return new double[]{x, y, x + w, y + h, w, h, x + w / 2.0, y + h / 2.0};
    }

    private static double[] ringCentre(JSONObject n, Pics pics, double[] c, double r0, double r1) {
        final double[] acc = {0, 0, 0};
        eachOpaque(n, pics, 1, (x, y) -> {
            double d = hyp(x - c[0], y - c[1]);
            if (d >= r0 && d <= r1) {
                acc[0] += x;
                acc[1] += y;
                acc[2]++;
            }
        });
        return acc[2] >= 4 ? new double[]{acc[0] / acc[2], acc[1] / acc[2]} : null;
    }

    private static double[] farthest(JSONObject n, Pics pics, double[] c) {
        final double[] best = {0, 0, -1};
        eachOpaque(n, pics, 1, (x, y) -> {
            double d = hyp(x - c[0], y - c[1]);
            if (d > best[2]) {
                best[0] = x;
                best[1] = y;
                best[2] = d;
            }
        });
        return best[2] < 0 ? null : best;      // x, y, distance
    }

    /** The two ends of a long shape (principal axis). */
    private static double[][] ends(JSONObject n, Pics pics) {
        final List<double[]> pts = new ArrayList<>();
        final double[] sum = {0, 0};
        eachOpaque(n, pics, 2, (x, y) -> {
            sum[0] += x;
            sum[1] += y;
            pts.add(new double[]{x, y});
        });
        if (pts.size() < 3) return null;
        double cx = sum[0] / pts.size(), cy = sum[1] / pts.size(), xx = 0, xy = 0, yy = 0;
        for (double[] p : pts) {
            double dx = p[0] - cx, dy = p[1] - cy;
            xx += dx * dx;
            xy += dx * dy;
            yy += dy * dy;
        }
        double ang = 0.5 * Math.atan2(2 * xy, xx - yy), ux = Math.cos(ang), uy = Math.sin(ang), lo = 1e9, hi = -1e9;
        for (double[] p : pts) {
            double t = (p[0] - cx) * ux + (p[1] - cy) * uy;
            if (t < lo) lo = t;
            if (t > hi) hi = t;
        }
        return new double[][]{{cx + ux * lo * 0.92, cy + uy * lo * 0.92}, {cx + ux * hi * 0.92, cy + uy * hi * 0.92}};
    }

    /** A limb's two ends, plain end first: a hand, paw or foot is the bigger blob; a shoulder is just the cut end. */
    private static double[][] plainEndFirst(JSONObject n, Pics pics) {
        double[][] e = ends(n, pics);
        if (e == null) return null;
        final double r = hyp(e[0][0] - e[1][0], e[0][1] - e[1][1]) * 0.22;
        final int[] c = {0, 0};
        final double[][] ee = e;
        eachOpaque(n, pics, 2, (x, y) -> {
            if (hyp(x - ee[0][0], y - ee[0][1]) < r) c[0]++;
            if (hyp(x - ee[1][0], y - ee[1][1]) < r) c[1]++;
        });
        return c[0] <= c[1] ? new double[][]{e[0], e[1]} : new double[][]{e[1], e[0]};
    }

    private static JSONObject pt(double x, double y) throws JSONException {
        return new JSONObject().put("x", x).put("y", y);
    }

    // ── a fan of limb drawings ────────────────────────────────────────────────────────────────────

    /**
     * Pins for a fan of limb drawings (an arm's or a leg's). {@code near} (a point such as the torso's middle) says which
     * end is the shoulder when the drawings do not overlap enough to tell. {@code onlyNew}: only drawings with no pins yet.
     */
    public static void guessFan(JSONObject set, Pics pics, double[] near, boolean onlyNew) throws JSONException {
        List<JSONObject> kids = new ArrayList<>();
        JSONArray ks = set.optJSONArray("kids");
        for (int i = 0; ks != null && i < ks.length(); i++) {
            JSONObject k = ks.optJSONObject(i);
            if (k != null && "art".equals(k.optString("type")) && pics.has(k.optString("img", ""))) kids.add(k);
        }
        if (kids.isEmpty()) return;
        double[] b = null;
        for (JSONObject k : kids) {
            double[] q = boxOf(k, pics);
            b = b == null ? q : new double[]{Math.min(b[0], q[0]), Math.min(b[1], q[1]), Math.max(b[2], q[2]), Math.max(b[3], q[3])};
        }
        final int bw = (int) Math.ceil(b[2] - b[0]), bh = (int) Math.ceil(b[3] - b[1]);
        final double bx0 = b[0], by0 = b[1];
        final int[] cover = new int[bw * bh];
        final int[] mx = {0};
        for (JSONObject k : kids) eachOpaque(k, pics, 1, (x, y) -> {
            int i = ((int) (y - by0)) * bw + (int) (x - bx0);
            if (i >= 0 && i < cover.length && ++cover[i] > mx[0]) mx[0] = cover[i];
        });
        double[] shoulder = null;
        if (kids.size() >= 3 && mx[0] >= 3) {
            int thr = Math.max(2, (int) Math.ceil(mx[0] * 0.72));
            double sx = 0, sy = 0;
            int c = 0;
            for (int i = 0; i < cover.length; i++) if (cover[i] >= thr) {
                sx += i % bw;
                sy += i / bw;
                c++;
            }
            if (c > 0) shoulder = new double[]{bx0 + sx / c, by0 + sy / c};
        }
        List<JSONObject> todo = new ArrayList<>(), have = new ArrayList<>();
        for (JSONObject k : kids) {
            if (onlyNew && k.optJSONObject("limb") != null) have.add(k);
            else todo.add(k);
        }
        if (!have.isEmpty()) {
            double hx = 0, hy = 0;
            for (JSONObject k : have) {
                hx += pt(k.getJSONObject("limb"), "a", 0);
                hy += pt(k.getJSONObject("limb"), "a", 1);
            }
            shoulder = new double[]{hx / have.size(), hy / have.size()};     // new drawings share the shoulder already set
        }
        List<Double> lens = new ArrayList<>();
        for (JSONObject k : todo) {
            double[] a = shoulder;
            if (a == null) {
                if (near != null) {
                    double[][] e = ends(k, pics);
                    if (e == null) continue;
                    a = hyp(e[0][0] - near[0], e[0][1] - near[1]) <= hyp(e[1][0] - near[0], e[1][1] - near[1]) ? e[0] : e[1];
                } else {
                    double[][] e = plainEndFirst(k, pics);
                    if (e == null) continue;
                    a = e[0];
                }
            }
            double[] f = farthest(k, pics, a);
            if (f == null) continue;
            double len = f[2];
            double[] wrist = ringCentre(k, pics, a, len * 0.74, len * 0.92);
            if (wrist == null) wrist = new double[]{a[0] + (f[0] - a[0]) * 0.85, a[1] + (f[1] - a[1]) * 0.85};
            double wl = hyp(wrist[0] - a[0], wrist[1] - a[1]);
            double[] elbow = ringCentre(k, pics, a, wl * 0.44, wl * 0.56);
            if (elbow == null) elbow = new double[]{(a[0] + wrist[0]) / 2, (a[1] + wrist[1]) / 2};
            JSONObject keep = k.optJSONObject("limb");
            JSONObject nl = new JSONObject();
            if (keep != null) for (String q : Js.keys(keep)) nl.put(q, keep.get(q));               // softness, stretch and the flip are kept
            nl.put("a", pt(a[0], a[1])).put("b", pt(elbow[0], elbow[1])).put("c", pt(wrist[0], wrist[1])).put("guessed", true);
            k.put("limb", nl);
            lens.add(wl);
        }
        // a drawing far shorter than its sisters points at the camera: keep its elbow simple
        List<Double> sorted = new ArrayList<>(lens);
        java.util.Collections.sort(sorted);
        double med = sorted.isEmpty() ? 0 : sorted.get(sorted.size() >> 1);
        for (JSONObject k : todo) {
            JSONObject l = k.optJSONObject("limb");
            if (l == null) continue;
            double wl = hyp(pt(l, "c", 0) - pt(l, "a", 0), pt(l, "c", 1) - pt(l, "a", 1));
            if (wl < med * 0.5) l.put("b", pt((pt(l, "a", 0) + pt(l, "c", 0)) / 2, (pt(l, "a", 1) + pt(l, "c", 1)) / 2));
        }
        JSONObject rest = null;
        for (JSONObject k : kids) {
            JSONObject l = k.optJSONObject("limb");
            if (l != null && (rest == null || pt(l, "c", 1) > pt(rest.getJSONObject("limb"), "c", 1))) rest = k;
        }
        JSONObject pick = set.optJSONObject("pick");
        if (pick == null) {
            pick = new JSONObject();
            set.put("pick", pick);
        }
        if (rest != null) {
            boolean missing = true;
            String cur = pick.optString("rest", "");
            for (JSONObject k : kids) if (k.optString("id").equals(cur)) missing = false;
            if (cur.isEmpty() || missing) pick.put("rest", rest.getString("id"));
        }
    }

    private static double pt(JSONObject l, String which, int axis) {
        JSONObject p = l.optJSONObject(which);
        return p == null ? 0 : p.optDouble(axis == 0 ? "x" : "y", 0);
    }

    // ── a mouth ───────────────────────────────────────────────────────────────────────────────────

    /** A mouth drawing that opens and shuts: a wide one squashes; a small one swings on a hinge at the corner away from the nose. */
    public static void guessMouth(JSONObject art, Pics pics, double[] noseBox, double[] faceBox) throws JSONException {
        double[] b = boxOf(art, pics);
        if (b == null) return;
        art.remove("hinge");
        art.remove("squash");
        if (b[4] / b[5] >= 1.5) {
            art.put("squash", new JSONObject().put("rest", "open").put("min", 0.14));
            art.put("pivot", pt(b[6], b[1] + b[5] * 0.15));
            return;
        }
        double towards = noseBox != null ? (noseBox[0] + noseBox[2]) / 2 : faceBox != null ? (faceBox[0] + faceBox[2]) / 2 - (b[6] - (faceBox[0] + faceBox[2]) / 2) : b[0];
        boolean hingeRight = towards < b[6];                       // the lips point at the nose; the hinge is at the other corner
        art.put("pivot", pt(hingeRight ? b[0] + b[4] * 0.86 : b[0] + b[4] * 0.14, b[1] + b[5] * 0.3));
        art.put("hinge", new JSONObject().put("rest", "open").put("angle", hingeRight ? 30 : -30));
    }

    // ── swinging things ───────────────────────────────────────────────────────────────────────────

    /** Root and tip for something that swings: bendy things grow from the end nearest the body; stiff things hang from their top. */
    public static void guessSwing(JSONObject art, Pics pics, double[] near, String kind) throws JSONException {
        double[] b = boxOf(art, pics);
        if (b == null) return;
        JSONObject keep = art.optJSONObject("swing");
        if (keep == null) keep = new JSONObject();
        String k = kind != null ? kind : keep.optString("kind", "bendy");
        double[][] e = ends(art, pics);
        if (e == null || (b[4] / b[5] > 0.7 && b[4] / b[5] < 1.4 && k.equals("stiff")))
            e = new double[][]{{b[6], b[1] + b[5] * 0.08}, {b[6], b[3] - b[5] * 0.08}};
        double[] rootP, tip;
        if (k.equals("stiff") && near == null) rootP = e[0][1] <= e[1][1] ? e[0] : e[1];
        else {
            double d0 = near != null ? hyp(e[0][0] - near[0], e[0][1] - near[1]) : e[0][1], d1 = near != null ? hyp(e[1][0] - near[0], e[1][1] - near[1]) : e[1][1];
            rootP = d0 <= d1 ? e[0] : e[1];
        }
        tip = rootP == e[0] ? e[1] : e[0];
        boolean st = k.equals("stiff");
        art.put("pivot", pt(rootP[0], rootP[1]));
        JSONObject sw = new JSONObject().put("kind", k).put("tip", pt(tip[0], tip[1])).put("segs", keep.has("segs") ? keep.getInt("segs") : 4)
                .put("stiff", keep.has("stiff") ? keep.getDouble("stiff") : (st ? 0.25 : 0.35)).put("damp", keep.has("damp") ? keep.getDouble("damp") : (st ? 0.2 : 0.3))
                .put("follow", keep.has("follow") ? keep.getDouble("follow") : 1).put("hang", keep.has("hang") ? keep.getBoolean("hang") : st)
                .put("limit", keep.has("limit") ? keep.getDouble("limit") : 80).put("wind", keep.has("wind") ? keep.getDouble("wind") : (st ? 0.4 : 1))
                .put("flutter", keep.optDouble("flutter", 0)).put("wag", keep.has("wag") ? keep.getDouble("wag") : (st ? 0 : 0.04));
        if (keep.has("on")) sw.put("on", keep.get("on"));
        art.put("swing", sw);
    }

    /** Stiff pieces that hang one from the next: sorted top to bottom, each hangs from the one above. */
    public static void guessChain(List<JSONObject> arts, Pics pics, String anchorId) throws JSONException {
        List<JSONObject> sorted = new ArrayList<>(arts);
        sorted.sort((p, q) -> Double.compare(boxOf(p, pics)[1], boxOf(q, pics)[1]));
        for (int i = 0; i < sorted.size(); i++) {
            JSONObject a = sorted.get(i);
            guessSwing(a, pics, null, "stiff");
            if (i > 0) a.put("attach", sorted.get(i - 1).getString("id"));
            else if (anchorId != null) a.put("attach", anchorId);
        }
    }

    /** True when a layer's name says it swings: "bendy", "stiff", or null. */
    public static String swingKindOf(String name) {
        if (STIFF.matcher(name).find()) return "stiff";
        if (BENDY.matcher(name).find()) return "bendy";
        return null;
    }
}
